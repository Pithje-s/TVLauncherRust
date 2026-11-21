package com.taylorlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherServiceTest {

    @Test
    fun mapsKnownWeatherCodes() {
        assertEquals("clear", WeatherService.weatherCodeToCondition(0))
        assertEquals("clear", WeatherService.weatherCodeToCondition(1))
        assertEquals("partly", WeatherService.weatherCodeToCondition(2))
        assertEquals("cloudy", WeatherService.weatherCodeToCondition(3))
        assertEquals("fog", WeatherService.weatherCodeToCondition(45))
        assertEquals("drizzle", WeatherService.weatherCodeToCondition(51))
        assertEquals("rain", WeatherService.weatherCodeToCondition(61))
        assertEquals("snow", WeatherService.weatherCodeToCondition(71))
        assertEquals("thunderstorm", WeatherService.weatherCodeToCondition(95))
    }

    @Test
    fun mapsUnknownWeatherCode() {
        assertEquals("unknown", WeatherService.weatherCodeToCondition(-1))
        assertEquals("unknown", WeatherService.weatherCodeToCondition(1234))
    }
}
