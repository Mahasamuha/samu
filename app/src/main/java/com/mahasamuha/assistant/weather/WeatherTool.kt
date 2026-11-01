package com.mahasamuha.assistant.weather

import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ReadOnlyTool
import com.mahasamuha.assistant.voice.LocationSource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * `get_weather`: live weather for the user's location or a named place, for the time the model asks
 * about: now (the rest of today and tonight), one date, or a range of dates. The model picks the dates,
 * so the result holds only what the answer needs. To change provider, replace this tool (e.g. with a
 * remote `get_weather`, plan §4b) rather than the forecast source.
 */
class WeatherTool(private val fetchForecast: ForecastFetch, private val locations: LocationSource) : ReadOnlyTool {

    // The server's Pirate Weather version replaces this one, which stays behind it as the fallback.
    override val allowRemote = true

    override val definition = ToolDefinition(
        name = "get_weather",
        description = "Live weather for the user's location or a named place. Omit day for current conditions and " +
            "the rest of today and tonight. Pass day for one date: its high, low, and hour-by-hour forecast. Pass day " +
            "and end_day for a range, such as a weekend: a summary per day. Forecasts reach " +
            "${OpenMeteoWeather.MAX_DAYS - 1} days ahead. Use this for every weather question instead of web search.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("place", JSONObject()
                    .put("type", "string")
                    .put("description", "A city or place name. Omit it for the user's current location."))
                .put("day", JSONObject()
                    .put("type", "string")
                    .put("format", "date")
                    .put("description", "The date asked about, as YYYY-MM-DD, worked out from the current date. " +
                        "Omit it for now, today, or tonight."))
                .put("end_day", JSONObject()
                    .put("type", "string")
                    .put("format", "date")
                    .put("description", "The last date of a range, as YYYY-MM-DD, with day as the first.")))
            .put("additionalProperties", false),
        annotations = ToolAnnotations(title = "Weather", readOnlyHint = true, openWorldHint = true),
    )

    override suspend fun run(arguments: JSONObject): String {
        val placeName = arguments.optString("place").trim()
        val place = if (placeName.isNotEmpty()) {
            locations.find(placeName) ?: throw IllegalArgumentException("Couldn't find a place called $placeName")
        } else {
            locations.current() ?: throw IllegalStateException("The user's location isn't available; ask which city")
        }
        val day = date(arguments, "day")
        val endDay = date(arguments, "end_day")?.takeIf { it != day }
        require(endDay == null || day != null) { "end_day needs day as the start of the range" }
        require(endDay == null || endDay.isAfter(day)) { "end_day must come after day" }

        // Fetch only as far as the request reaches; "now" needs tomorrow morning too, for tonight's hours. The
        // phone's date stands in for the place's here (one extra day covers time zones); checked below.
        val last = endDay ?: day ?: LocalDate.now().plusDays(1)
        val days = ChronoUnit.DAYS.between(LocalDate.now(), last).toInt() + 2
        val forecast = fetchForecast(place.latitude, place.longitude, unitsForLocale(), days)

        val today = forecast.now.toLocalDate()
        if (day != null) require(!day.isBefore(today)) { "Only forecasts are available, not past weather" }
        (endDay ?: day)?.let { requested ->
            val furthest = forecast.days.last().date
            require(!requested.isAfter(furthest)) { "The forecast only reaches ${dayLabel(furthest)}" }
        }

        val result = JSONObject().put("place", place.description ?: placeName)
        val format = Format(forecast.units)
        when {
            day == null -> {
                result.put("now", format.current(forecast.current))
                forecast.days.firstOrNull { it.date == today }?.let { result.put("today", format.day(it)) }
                // The rest of today and tonight, until 6 AM tomorrow.
                val until = today.plusDays(1).atTime(6, 0)
                result.put("hours", format.hours(forecast.hours.filter { !it.time.isAfter(until) }, withDay = true))
            }
            endDay == null -> {
                forecast.days.firstOrNull { it.date == day }?.let { result.put("day", format.day(it)) }
                result.put("hours", format.hours(forecast.hours.filter { it.time.toLocalDate() == day }, withDay = false))
            }
            else -> result.put("days", JSONArray().apply {
                forecast.days.filter { !it.date.isBefore(day) && !it.date.isAfter(endDay) }.forEach { put(format.day(it)) }
            })
        }
        return result.toString()
    }

    private fun date(arguments: JSONObject, key: String): LocalDate? {
        val value = arguments.optString(key).trim().takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("$key must be a date like 2026-10-24")
        }
    }

    /** Compact, already-rounded values with spoken-friendly times, so the model doesn't need to compute anything. */
    private class Format(private val units: Units) {
        private fun temp(value: Double) = "${value.roundToInt()}${units.temperature}"

        fun current(current: Forecast.Current): JSONObject = JSONObject()
            .put("temperature", temp(current.temperature))
            .put("feels_like", temp(current.feelsLike))
            .put("condition", current.condition)
            .put("wind", "${current.windSpeed.roundToInt()} ${units.speed}")
            .apply { current.humidity?.let { put("humidity", "$it%") } }

        fun day(day: Forecast.Day): JSONObject = JSONObject()
            .put("date", dayLabel(day.date))
            .put("high", temp(day.high))
            .put("low", temp(day.low))
            .put("condition", day.condition)
            .apply { day.precipitationChance?.let { put("precipitation_chance", "$it%") } }

        // Every third hour keeps the result short and still covers morning, afternoon, evening, and night.
        fun hours(hours: List<Forecast.Hour>, withDay: Boolean): JSONArray {
            val format = DateTimeFormatter.ofPattern(if (withDay) "EEEE h a" else "h a", Locale.US)
            return JSONArray().apply {
                hours.filter { it.time.hour % 3 == 0 }.forEach { hour ->
                    put(JSONObject()
                        .put("time", hour.time.format(format))
                        .put("temperature", temp(hour.temperature))
                        .put("condition", hour.condition)
                        .apply { hour.precipitationChance?.let { put("precipitation_chance", "$it%") } })
                }
            }
        }
    }

    private fun unitsForLocale(): Units =
        if (Locale.getDefault().country in setOf("US", "LR", "MM")) Units.IMPERIAL else Units.METRIC

    private companion object {
        fun dayLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US))
    }
}
