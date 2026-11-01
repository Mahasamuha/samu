package com.mahasamuha.assistant.weather

import com.mahasamuha.assistant.voice.ApproximateLocation
import com.mahasamuha.assistant.voice.LocationSource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class WeatherToolTest {
    private val savedLocale = Locale.getDefault()

    @Before
    fun usEnglish() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    private val today = LocalDate.now()

    /** A forecast for [days] days from today, 10:00 now, with a reading for every hour of every day. */
    private class FakeForecast(
        private val today: LocalDate,
    ) {
        var requestedDays = 0
        var requestedUnits: Units? = null
        var requestedAt: Pair<Double, Double>? = null

        suspend fun forecast(
            latitude: Double,
            longitude: Double,
            units: Units,
            days: Int,
        ): Forecast {
            requestedDays = days
            requestedUnits = units
            requestedAt = latitude to longitude
            // Like the real service, the forecast stops at its furthest day.
            val count = minOf(days, OpenMeteoWeather.MAX_DAYS)
            val now = today.atTime(10, 0)
            val hours =
                (0 until count * 24)
                    .map { now.toLocalDate().atStartOfDay().plusHours(it.toLong()) }
                    .filter { it.isAfter(now) }
                    .map { Forecast.Hour(it, 50.0 + it.hour, "clear", if (it.hour == 12) 40 else null) }
            return Forecast(
                units = units,
                now = now,
                current = Forecast.Current(57.4, 54.6, "partly cloudy", 7.5, 55),
                hours = hours,
                days = (0 until count).map { Forecast.Day(today.plusDays(it.toLong()), 60.0 + it, 44.0 + it, "overcast", 20) },
            )
        }
    }

    private class FakeLocations(
        private val here: ApproximateLocation? = ApproximateLocation("Springfield", "Oregon", "US", 44.0, -123.0),
        private val named: Map<String, ApproximateLocation> = emptyMap(),
    ) : LocationSource {
        override suspend fun current() = here

        override suspend fun find(name: String) = named[name]
    }

    private val forecast = FakeForecast(today)

    private fun run(
        arguments: String = "{}",
        locations: LocationSource = FakeLocations(),
    ): JSONObject = JSONObject(runBlocking { WeatherTool(forecast::forecast, locations).run(JSONObject(arguments)) })

    @Test
    fun noDayGivesCurrentConditionsTodayAndTheRestOfTheNight() {
        val result = run()
        assertEquals("Springfield, Oregon", result.getString("place"))
        val now = result.getJSONObject("now")
        assertEquals("57°F", now.getString("temperature"))
        assertEquals("55°F", now.getString("feels_like"))
        assertEquals("8 mph", now.getString("wind"))
        assertEquals("55%", now.getString("humidity"))
        assertEquals("60°F", result.getJSONObject("today").getString("high"))
        assertEquals("20%", result.getJSONObject("today").getString("precipitation_chance"))
        assertFalse(result.has("day"))
    }

    @Test
    fun theRestOfTodayRunsUntilSixTomorrowEveryThirdHour() {
        val hours = run().getJSONArray("hours")
        val times = (0 until hours.length()).map { hours.getJSONObject(it).getString("time") }
        val dayName = today.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.US)
        val tomorrowName = today.plusDays(1).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.US)
        assertEquals(
            listOf(
                "$dayName 12 PM",
                "$dayName 3 PM",
                "$dayName 6 PM",
                "$dayName 9 PM",
                "$tomorrowName 12 AM",
                "$tomorrowName 3 AM",
                "$tomorrowName 6 AM",
            ),
            times,
        )
    }

    @Test
    fun hoursCarryPrecipitationChanceOnlyWhenKnown() {
        val hours = run().getJSONArray("hours")
        assertEquals("40%", hours.getJSONObject(0).getString("precipitation_chance"))
        assertFalse(hours.getJSONObject(1).has("precipitation_chance"))
    }

    @Test
    fun aSingleDayGivesItsSummaryAndHours() {
        val day = today.plusDays(2)
        val result = run("""{"day":"$day"}""")
        assertEquals("62°F", result.getJSONObject("day").getString("high"))
        val hours = result.getJSONArray("hours")
        assertEquals(8, hours.length())
        assertEquals("12 AM", hours.getJSONObject(0).getString("time"))
        assertFalse(result.has("now"))
    }

    @Test
    fun aRangeGivesOneSummaryPerDayIncludingBothEnds() {
        val result = run("""{"day":"${today.plusDays(1)}","end_day":"${today.plusDays(3)}"}""")
        assertEquals(3, result.getJSONArray("days").length())
        assertFalse(result.has("hours"))
    }

    @Test
    fun aRangeThatEndsWhereItStartsIsASingleDay() {
        val day = today.plusDays(1)
        val result = run("""{"day":"$day","end_day":"$day"}""")
        assertTrue(result.has("day"))
        assertTrue(result.has("hours"))
    }

    @Test
    fun fetchesOnlyAsFarAsTheRequestReaches() {
        run()
        assertEquals(3, forecast.requestedDays)
        run("""{"day":"${today.plusDays(5)}"}""")
        assertEquals(7, forecast.requestedDays)
        run("""{"day":"${today.plusDays(1)}","end_day":"${today.plusDays(4)}"}""")
        assertEquals(6, forecast.requestedDays)
    }

    @Test
    fun usesImperialUnitsInTheUsAndMetricElsewhere() {
        run()
        assertEquals(Units.IMPERIAL, forecast.requestedUnits)
        Locale.setDefault(Locale.GERMANY)
        val result = run()
        assertEquals(Units.METRIC, forecast.requestedUnits)
        assertEquals("57°C", result.getJSONObject("now").getString("temperature"))
        assertEquals("8 km/h", result.getJSONObject("now").getString("wind"))
    }

    @Test
    fun aNamedPlaceIsLookedUpAndNamedInTheResult() {
        val paris = ApproximateLocation("Paris", "Ile-de-France", "FR", 48.9, 2.4)
        val result = run("""{"place":" Paris "}""", FakeLocations(named = mapOf("Paris" to paris)))
        assertEquals("Paris, Ile-de-France", result.getString("place"))
        assertEquals(48.9 to 2.4, forecast.requestedAt)
    }

    @Test
    fun aPlaceWithoutANameUsesWhatTheUserTyped() {
        val unnamed = ApproximateLocation(null, null, null, 10.0, 20.0)
        assertEquals("Atlantis", run("""{"place":"Atlantis"}""", FakeLocations(named = mapOf("Atlantis" to unnamed))).getString("place"))
    }

    @Test
    fun anUnknownPlaceIsReportedToTheModel() {
        val e = assertThrows(IllegalArgumentException::class.java) { run("""{"place":"Nowhere"}""") }
        assertEquals("Couldn't find a place called Nowhere", e.message)
    }

    @Test
    fun unavailableLocationAsksTheModelToAskForACity() {
        val e = assertThrows(IllegalStateException::class.java) { run(locations = FakeLocations(here = null)) }
        assertTrue(e.message!!.contains("ask which city"))
    }

    @Test
    fun rejectsPastDatesAndBadDates() {
        val past = assertThrows(IllegalArgumentException::class.java) { run("""{"day":"${today.minusDays(1)}"}""") }
        assertTrue(past.message!!.startsWith("Only forecasts are available"))
        val bad = assertThrows(IllegalArgumentException::class.java) { run("""{"day":"next friday"}""") }
        assertEquals("day must be a date like 2026-10-24", bad.message)
        val badEnd = assertThrows(IllegalArgumentException::class.java) { run("""{"day":"${today.plusDays(1)}","end_day":"soon"}""") }
        assertTrue(badEnd.message!!.startsWith("end_day must be a date"))
    }

    @Test
    fun rejectsABackwardsRangeAndAnEndWithoutAStart() {
        assertThrows(IllegalArgumentException::class.java) { run("""{"day":"${today.plusDays(3)}","end_day":"${today.plusDays(1)}"}""") }
        val e = assertThrows(IllegalArgumentException::class.java) { run("""{"end_day":"${today.plusDays(1)}"}""") }
        assertEquals("end_day needs day as the start of the range", e.message)
    }

    @Test
    fun rejectsDatesBeyondTheForecast() {
        val e = assertThrows(IllegalArgumentException::class.java) { run("""{"day":"${today.plusDays(30)}"}""") }
        assertTrue(e.message!!.startsWith("The forecast only reaches"))
    }

    @Test
    fun theToolIsReadOnlyOpenWorldAndLetsTheServerReplaceIt() {
        val tool = WeatherTool(forecast::forecast, FakeLocations())
        assertTrue(tool.definition.annotations.readOnlyHint)
        assertTrue(tool.definition.annotations.openWorldHint)
        assertTrue(tool.allowRemote)
        assertEquals("get_weather", tool.definition.name)
    }
}
