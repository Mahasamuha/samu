package com.mahasamuha.assistant.weather

import java.time.LocalDate
import java.time.LocalDateTime

enum class Units(val temperature: String, val speed: String) {
    IMPERIAL("°F", "mph"),
    METRIC("°C", "km/h"),
}

/** Provider-neutral forecast. Times are local to the forecast location; [now] is its current local time. */
data class Forecast(
    val units: Units,
    val now: LocalDateTime,
    val current: Current,
    val hours: List<Hour>,
    val days: List<Day>,
) {
    data class Current(val temperature: Double, val feelsLike: Double, val condition: String, val windSpeed: Double, val humidity: Int?)

    data class Hour(val time: LocalDateTime, val temperature: Double, val condition: String, val precipitationChance: Int?)

    data class Day(val date: LocalDate, val high: Double, val low: Double, val condition: String, val precipitationChance: Int?)
}


/**
 * Where [WeatherTool] gets forecasts; [OpenMeteoWeather.forecast] is the real one. Returns current
 * conditions, and hourly and daily forecasts for `days` days starting today.
 */
typealias ForecastFetch = suspend (latitude: Double, longitude: Double, units: Units, days: Int) -> Forecast
