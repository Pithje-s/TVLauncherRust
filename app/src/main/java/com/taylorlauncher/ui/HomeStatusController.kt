package com.taylorlauncher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.taylorlauncher.R
import com.taylorlauncher.core.LauncherPrefs
import com.taylorlauncher.core.Tween
import com.taylorlauncher.core.WeatherInfo
import com.taylorlauncher.core.WeatherService
import com.taylorlauncher.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HomeStatusController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val prefs: LauncherPrefs
) {

    private var clockFormat: SimpleDateFormat? = null
    private var clockFormat24: Boolean? = null
    private var lastGreetingHour = -1

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTicker = object : Runnable {
        override fun run() {
            refresh()
            val seconds = Calendar.getInstance().get(Calendar.SECOND)
            clockHandler.postDelayed(this, (60 - seconds) * 1000L)
        }
    }

    fun start() {
        clockHandler.post(clockTicker)
    }

    fun stop() {
        clockHandler.removeCallbacks(clockTicker)
    }

    fun destroy() {
        clockHandler.removeCallbacksAndMessages(null)
    }

    fun refresh() {
        val use24 = prefs.use24Hour
        if (clockFormat == null || clockFormat24 != use24) {
            clockFormat = SimpleDateFormat(if (use24) "HH:mm" else "h:mm", Locale.getDefault())
            clockFormat24 = use24
        }
        binding.bigClock.text = clockFormat!!.format(Date())
        val dateText = buildCurrentDate()
        if (binding.date.text != dateText) {
            binding.date.text = dateText
        }
        val hour = Calendar.getInstance(TimeZone.getDefault()).get(Calendar.HOUR_OF_DAY)
        if (hour != lastGreetingHour) {
            lastGreetingHour = hour
            binding.greetings.text = getGreeting()
        }
    }

    fun loadWeather() {
        activity.lifecycleScope.launch {
            val coords = withContext(Dispatchers.IO) { readWeatherCoordinates() } ?: return@launch
            val weather = WeatherService.fetchWeather(coords.first, coords.second)
            withContext(Dispatchers.Main) { applyWeather(weather) }
        }
    }

    private fun getGreeting(): String {
        val hour = Calendar.getInstance(TimeZone.getDefault()).get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 5 -> activity.getString(R.string.greeting_night)
            hour < 12 -> activity.getString(R.string.greeting_morning)
            hour < 18 -> activity.getString(R.string.greeting_day)
            else -> activity.getString(R.string.greeting_evening)
        }
    }

    private suspend fun readWeatherCoordinates(): Pair<Double, Double>? {
        val locationManager = activity.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val location = if (hasLocationPermission) {
            locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } else {
            null
        }
        if (location != null) return location.latitude to location.longitude

        val city = TimeZone.getDefault().id
            .substringAfter('/')
            .replace('_', ' ')
            .trim()
        if (city.isBlank()) return null
        return WeatherService.geocodeCity(city)
    }

    private fun applyWeather(weather: WeatherInfo?) {
        if (weather == null) return
        val temp = "${weather.temperature.toInt()}°"
        binding.weatherCurrent.text = activity.getString(
            R.string.weather_now,
            weatherConditionText(weather.condition),
            temp
        )
        binding.weatherCurrent.visibility = View.VISIBLE
        binding.weatherCurrent.alpha = 0f
        Tween.alpha(binding.weatherCurrent, 1f, 300L)
    }

    private fun weatherConditionText(condition: String): String = activity.getString(when (condition) {
        "clear" -> R.string.weather_clear
        "partly" -> R.string.weather_partly
        "cloudy" -> R.string.weather_cloudy
        "fog" -> R.string.weather_fog
        "drizzle" -> R.string.weather_drizzle
        "rain" -> R.string.weather_rain
        "snow" -> R.string.weather_snow
        "thunderstorm" -> R.string.weather_thunderstorm
        else -> R.string.weather_unknown
    })

    private fun buildCurrentDate(): String =
        DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault()).format(Date())
}
