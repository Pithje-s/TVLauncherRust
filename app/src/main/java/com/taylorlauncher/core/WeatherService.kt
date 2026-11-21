package com.taylorlauncher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class WeatherInfo(
    val temperature: Double,
    val condition: String,
    val isDay: Boolean
)

object WeatherService {

    const val OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast"

    private val okHttpClient = HttpClient.client
    private val geocodeCache = ConcurrentHashMap<String, Pair<Double, Double>>()

    suspend fun geocodeCity(city: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        geocodeCache[city]?.let { return@withContext it }
        try {
            val url = "https://geocoding-api.open-meteo.com/v1/search" +
                "?name=${URLEncoder.encode(city, "UTF-8")}&count=1&format=json"
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val results = JSONObject(body).optJSONArray("results") ?: return@withContext null
                if (results.length() == 0) return@withContext null
                val first = results.getJSONObject(0)
                val lat = first.optDouble("latitude", Double.NaN)
                val lon = first.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@withContext null
                val coords = lat to lon
                geocodeCache[city] = coords
                coords
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun fetchWeather(latitude: Double, longitude: Double): WeatherInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "${OPEN_METEO_URL}?latitude=$latitude&longitude=$longitude&current=temperature_2m,weather_code,is_day&timezone=auto"
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val current = json.optJSONObject("current") ?: return@withContext null
                val temperature = current.optDouble("temperature_2m", Double.NaN)
                val code = current.optInt("weather_code", -1)
                val isDay = current.optInt("is_day", 1) == 1
                if (temperature.isNaN()) return@withContext null
                return@withContext WeatherInfo(temperature, weatherCodeToCondition(code), isDay)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun weatherCodeToCondition(code: Int): String = when (code) {
        0, 1 -> "clear"
        2 -> "partly"
        3 -> "cloudy"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "rain"
        71, 73, 75, 77, 85, 86 -> "snow"
        95, 96, 99 -> "thunderstorm"
        else -> "unknown"
    }
}
