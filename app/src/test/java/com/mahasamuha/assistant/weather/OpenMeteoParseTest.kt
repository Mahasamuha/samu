package com.mahasamuha.assistant.weather

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenMeteoParseTest {
    private fun response(
        currentExtra: String = ""","relative_humidity_2m":55""",
        hourlyTimes: String = """["2026-10-24T09:00","2026-10-24T10:00","2026-10-24T11:00"]""",
        hourlyProbability: String = "[10, null, 30]",
        weatherCodes: String = "[0, 61, 95]",
    ) = """
        {
          "current": {"time":"2026-10-24T10:00","temperature_2m":57.4,"apparent_temperature":54.6,"weather_code":2,"wind_speed_10m":7.5$currentExtra},
          "hourly": {"time":$hourlyTimes,"temperature_2m":[50.0,52.5,55.0],"precipitation_probability":$hourlyProbability,"weather_code":$weatherCodes},
          "daily": {"time":["2026-10-24","2026-10-25"],"temperature_2m_max":[60.1,58.0],"temperature_2m_min":[44.0,41.5],
                    "weather_code":[3,71],"precipitation_probability_max":[20,null]}
        }
    """.trimIndent()

    @Test
    fun shapesCurrentConditions() {
        val forecast = parseForecast(response(), Units.IMPERIAL)
        assertEquals(Units.IMPERIAL, forecast.units)
        assertEquals(LocalDateTime.parse("2026-10-24T10:00"), forecast.now)
        with(forecast.current) {
            assertEquals(57.4, temperature, 0.0)
            assertEquals(54.6, feelsLike, 0.0)
            assertEquals("partly cloudy", condition)
            assertEquals(7.5, windSpeed, 0.0)
            assertEquals(55, humidity)
        }
    }

    @Test
    fun aMissingHumidityIsNull() {
        assertNull(parseForecast(response(currentExtra = ""), Units.METRIC).current.humidity)
    }

    @Test
    fun hoursAtOrBeforeNowAreDropped() {
        val hours = parseForecast(response(), Units.IMPERIAL).hours
        assertEquals(listOf(LocalDateTime.parse("2026-10-24T11:00")), hours.map { it.time })
        assertEquals(55.0, hours.single().temperature, 0.0)
        assertEquals("thunderstorms", hours.single().condition)
        assertEquals(30, hours.single().precipitationChance)
    }

    @Test
    fun aNullPrecipitationChanceStaysNullRatherThanZero() {
        val hours = parseForecast(response(hourlyTimes = """["2026-10-24T11:00","2026-10-24T12:00","2026-10-24T13:00"]""", hourlyProbability = "[null, 0, 5]"), Units.IMPERIAL).hours
        assertNull(hours[0].precipitationChance)
        assertEquals(0, hours[1].precipitationChance)
    }

    @Test
    fun shapesDailyForecasts() {
        val days = parseForecast(response(), Units.IMPERIAL).days
        assertEquals(2, days.size)
        assertEquals(Forecast.Day(LocalDate.parse("2026-10-24"), 60.1, 44.0, "overcast", 20), days[0])
        assertEquals(Forecast.Day(LocalDate.parse("2026-10-25"), 58.0, 41.5, "light snow", null), days[1])
    }

    @Test
    fun weatherCodesMapToSpokenConditions() {
        val codes = listOf(0, 1, 2, 3, 45, 48, 51, 55, 56, 61, 63, 65, 66, 71, 75, 77, 80, 82, 85, 95, 96, 99, 1234)
        val expected = listOf(
            "clear", "mostly clear", "partly cloudy", "overcast", "fog", "fog", "drizzle", "drizzle", "freezing drizzle", "light rain", "rain",
            "heavy rain", "freezing rain", "light snow", "heavy snow", "snow grains", "rain showers", "heavy rain showers", "snow showers",
            "thunderstorms", "thunderstorms with hail", "thunderstorms with hail", "unknown",
        )
        // Minutes within one hour keep every row on one date, after "now".
        val minuteTimes = codes.indices.joinToString(",", "[", "]") { "\"2026-10-24T11:${it.toString().padStart(2, '0')}\"" }
        val json = """
            {"current":{"time":"2026-10-24T10:00","temperature_2m":1,"apparent_temperature":1,"weather_code":0,"wind_speed_10m":1},
             "hourly":{"time":$minuteTimes,"temperature_2m":${codes.map { 1.0 }},"precipitation_probability":${codes.map { 1 }},"weather_code":$codes},
             "daily":{"time":[],"temperature_2m_max":[],"temperature_2m_min":[],"weather_code":[],"precipitation_probability_max":[]}}
        """.trimIndent()
        assertEquals(expected, parseForecast(json, Units.METRIC).hours.map { it.condition })
    }

    @Test
    fun aMalformedResponseFails() {
        assertThrows(org.json.JSONException::class.java) { parseForecast("""{"current":{}}""", Units.METRIC) }
        assertThrows(org.json.JSONException::class.java) { parseForecast("<html>", Units.METRIC) }
    }
}
