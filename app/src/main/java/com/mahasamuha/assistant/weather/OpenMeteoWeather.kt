package com.mahasamuha.assistant.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** Open-Meteo (open-meteo.com): free for non-commercial use, no API key. */
class OpenMeteoWeather {
    /** Current conditions, and hourly and daily forecasts for [days] days starting today (1–16). */
    suspend fun forecast(
        latitude: Double,
        longitude: Double,
        units: Units,
        days: Int,
    ): Forecast =
        withContext(Dispatchers.IO) {
            parseForecast(get(url(latitude, longitude, units, days.coerceIn(1, MAX_DAYS))), units)
        }

    private fun url(
        latitude: Double,
        longitude: Double,
        units: Units,
        days: Int,
    ): String {
        val unitParams =
            when (units) {
                Units.IMPERIAL -> "&temperature_unit=fahrenheit&wind_speed_unit=mph"
                Units.METRIC -> "&temperature_unit=celsius&wind_speed_unit=kmh"
            }
        return "https://api.open-meteo.com/v1/forecast?latitude=${"%.1f".format(
            Locale.US,
            latitude,
        )}&longitude=${"%.1f".format(Locale.US, longitude)}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&hourly=temperature_2m,precipitation_probability,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=$days$unitParams"
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("Forecast service returned $status")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** The furthest Open-Meteo forecasts, in days including today. */
        const val MAX_DAYS = 16
    }
}

/** Shapes an Open-Meteo forecast response into a [Forecast]; hours before the response's current time are dropped. */
internal fun parseForecast(
    body: String,
    units: Units,
): Forecast {
    val json = JSONObject(body)
    val currentJson = json.getJSONObject("current")
    val now = LocalDateTime.parse(currentJson.getString("time"))

    val hourly = json.getJSONObject("hourly")
    val hourTimes = hourly.getJSONArray("time")
    val hours =
        (0 until hourTimes.length())
            .map { i -> i to LocalDateTime.parse(hourTimes.getString(i)) }
            .filter { (_, time) -> time.isAfter(now) }
            .map { (i, time) ->
                Forecast.Hour(
                    time = time,
                    temperature = hourly.getJSONArray("temperature_2m").getDouble(i),
                    condition = condition(hourly.getJSONArray("weather_code").getInt(i)),
                    precipitationChance = hourly.getJSONArray("precipitation_probability").optIntOrNull(i),
                )
            }

    val daily = json.getJSONObject("daily")
    val dates = daily.getJSONArray("time")
    val days =
        (0 until dates.length()).map { i ->
            Forecast.Day(
                date = LocalDate.parse(dates.getString(i)),
                high = daily.getJSONArray("temperature_2m_max").getDouble(i),
                low = daily.getJSONArray("temperature_2m_min").getDouble(i),
                condition = condition(daily.getJSONArray("weather_code").getInt(i)),
                precipitationChance = daily.getJSONArray("precipitation_probability_max").optIntOrNull(i),
            )
        }

    return Forecast(
        units = units,
        now = now,
        current =
            Forecast.Current(
                temperature = currentJson.getDouble("temperature_2m"),
                feelsLike = currentJson.getDouble("apparent_temperature"),
                condition = condition(currentJson.getInt("weather_code")),
                windSpeed = currentJson.getDouble("wind_speed_10m"),
                humidity = currentJson.optInt("relative_humidity_2m", -1).takeIf { it >= 0 },
            ),
        hours = hours,
        days = days,
    )
}

private fun JSONArray.optIntOrNull(index: Int): Int? = if (isNull(index)) null else optInt(index)

/** WMO weather interpretation codes, as used by Open-Meteo. */
private fun condition(code: Int): String =
    when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80, 81 -> "rain showers"
        82 -> "heavy rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorms"
        96, 99 -> "thunderstorms with hail"
        else -> "unknown"
    }
