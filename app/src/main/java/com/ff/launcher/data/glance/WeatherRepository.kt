package com.ff.launcher.data.glance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import com.ff.launcher.util.Logger
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private val Context.weatherDataStore: DataStore<Preferences> by preferencesDataStore(name = "weather_cache")

data class WeatherData(
    val tempC: Float = 28f,
    val tempF: Float = (28f * 9f / 5f) + 32f,
    val conditionCode: Int = 0,
    val conditionText: String = "Clear",
    val highC: Float = 31f,
    val lowC: Float = 25f,
    val locationName: String = "Local Weather",
    val lastUpdatedMs: Long = 0L,
    val isLoading: Boolean = false,
) {
    fun formatTemp(useFahrenheit: Boolean): String {
        val t = if (useFahrenheit) tempF.roundToInt() else tempC.roundToInt()
        val unit = if (useFahrenheit) "°F" else "°C"
        return "$t$unit"
    }

    fun conditionEmoji(): String = when (conditionCode) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 61, 63, 65, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "❄️"
        95, 96, 99 -> "⛈️"
        else -> "⛅"
    }
}

/**
 * Manages weather fetching, caching, and parsing via Open-Meteo API.
 */
class WeatherRepository(private val context: Context) {

    companion object {
        private const val TAG = "WeatherRepository"
        private const val CACHE_EXPIRATION_MS = 60 * 60 * 1000L // 1 hour

        private val KEY_TEMP_C = floatPreferencesKey("weather_temp_c")
        private val KEY_CONDITION_CODE = intPreferencesKey("weather_condition_code")
        private val KEY_CONDITION_TEXT = stringPreferencesKey("weather_condition_text")
        private val KEY_HIGH_C = floatPreferencesKey("weather_high_c")
        private val KEY_LOW_C = floatPreferencesKey("weather_low_c")
        private val KEY_LOCATION_NAME = stringPreferencesKey("weather_location_name")
        private val KEY_LAST_UPDATED = longPreferencesKey("weather_last_updated")

        fun getWeatherDescription(code: Int): String = when (code) {
            0 -> "Clear sky"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            61, 63, 65 -> "Rain"
            71, 73, 75 -> "Snow"
            80, 81, 82 -> "Rain showers"
            95, 96, 99 -> "Thunderstorm"
            else -> "Cloudy"
        }
    }

    fun hasLocationPermission(): Boolean {
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return hasCoarse || hasFine
    }

    val cachedWeather: Flow<WeatherData> = context.weatherDataStore.data.map { prefs ->
        val tempC = prefs[KEY_TEMP_C] ?: 28f
        val code = prefs[KEY_CONDITION_CODE] ?: 0
        WeatherData(
            tempC = tempC,
            tempF = (tempC * 9f / 5f) + 32f,
            conditionCode = code,
            conditionText = prefs[KEY_CONDITION_TEXT] ?: getWeatherDescription(code),
            highC = prefs[KEY_HIGH_C] ?: (tempC + 3f),
            lowC = prefs[KEY_LOW_C] ?: (tempC - 3f),
            locationName = prefs[KEY_LOCATION_NAME] ?: "Local Weather",
            lastUpdatedMs = prefs[KEY_LAST_UPDATED] ?: 0L,
            isLoading = false,
        )
    }

    suspend fun getOrFetchWeather(forceRefresh: Boolean = false): WeatherData = withContext(Dispatchers.IO) {
        val current = cachedWeather.first()
        val now = System.currentTimeMillis()
        val isOldFallback = current.locationName == "Los Angeles" || current.locationName == "San Francisco"
        if (!forceRefresh && !isOldFallback && (now - current.lastUpdatedMs) < CACHE_EXPIRATION_MS && current.lastUpdatedMs > 0L) {
            return@withContext current
        }

        val location = resolveLocation()
        val (lat, lon) = if (location != null) {
            Logger.i(TAG) { "Using device location: lat=${location.latitude}, lon=${location.longitude}" }
            location.latitude to location.longitude
        } else {
            val fallback = getFallbackCoordinates()
            Logger.i(TAG) { "Device location unavailable, using timezone fallback: lat=${fallback.first}, lon=${fallback.second}" }
            fallback
        }

        val fetched = fetchFromOpenMeteo(lat, lon, location != null)
        if (fetched != null) {
            saveToCache(fetched)
            return@withContext fetched
        }
        current
    }

    @SuppressLint("MissingPermission")
    private suspend fun resolveLocation(): Location? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) return@withContext null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@withContext null

        // 1. Check last known location (instant)
        val lastKnown = runCatching {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }.getOrNull()

        // If fresh within 30 minutes, use it
        if (lastKnown != null && (System.currentTimeMillis() - lastKnown.time) < 30 * 60 * 1000L) {
            return@withContext lastKnown
        }

        // 2. On Android 11+, request a fresh current location fix if last known is missing/stale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val fresh = runCatching {
                suspendCancellableCoroutine<Location?> { cont ->
                    val cancelSignal = CancellationSignal()
                    cont.invokeOnCancellation { cancelSignal.cancel() }
                    val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        LocationManager.NETWORK_PROVIDER
                    } else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        LocationManager.GPS_PROVIDER
                    } else {
                        LocationManager.PASSIVE_PROVIDER
                    }
                    try {
                        lm.getCurrentLocation(
                            provider,
                            cancelSignal,
                            ContextCompat.getMainExecutor(context),
                        ) { loc ->
                            if (cont.isActive) cont.resume(loc)
                        }
                    } catch (e: Exception) {
                        Logger.w(TAG, "getCurrentLocation failed: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }.getOrNull()

            if (fresh != null) return@withContext fresh
        }

        return@withContext lastKnown
    }

    /**
     * Resolves fallback coordinates using device's default timezone instead of hardcoded US cities.
     */
    private fun getFallbackCoordinates(): Pair<Double, Double> {
        val tzId = TimeZone.getDefault().id
        Logger.d(TAG) { "getFallbackCoordinates: resolving for tzId=$tzId" }
        return when {
            tzId.startsWith("Asia/Singapore") || tzId == "Singapore" -> 1.3521 to 103.8198
            tzId.startsWith("Asia/Kuala_Lumpur") -> 3.1390 to 101.6869
            tzId.startsWith("Asia/Hong_Kong") -> 22.3193 to 114.1694
            tzId.startsWith("Asia/Taipei") -> 25.0330 to 121.5654
            tzId.startsWith("Asia/Tokyo") -> 35.6762 to 139.6503
            tzId.startsWith("Asia/Seoul") -> 37.5665 to 126.9780
            tzId.startsWith("Asia/Shanghai") || tzId.startsWith("Asia/Chongqing") || tzId.startsWith("Asia/Beijing") -> 31.2304 to 121.4737
            tzId.startsWith("Asia/Bangkok") -> 13.7563 to 100.5018
            tzId.startsWith("Asia/Jakarta") -> -6.2088 to 106.8456
            tzId.startsWith("Asia/Manila") -> 14.5995 to 120.9842
            tzId.startsWith("Asia/Kolkata") || tzId.startsWith("Asia/Calcutta") -> 28.6139 to 77.2090
            tzId.startsWith("Asia/Dubai") -> 25.2048 to 55.2708
            tzId.startsWith("Australia/Sydney") -> -33.8688 to 151.2093
            tzId.startsWith("Australia/Melbourne") -> -37.8136 to 144.9631
            tzId.startsWith("Australia/Perth") -> -31.9505 to 115.8605
            tzId.startsWith("Europe/London") -> 51.5074 to -0.1278
            tzId.startsWith("Europe/Paris") -> 48.8566 to 2.3522
            tzId.startsWith("Europe/Berlin") -> 52.5200 to 13.4050
            tzId.startsWith("America/New_York") -> 40.7128 to -74.0060
            tzId.startsWith("America/Chicago") -> 41.8781 to -87.6298
            tzId.startsWith("America/Denver") -> 39.7392 to -104.9903
            tzId.startsWith("America/Los_Angeles") -> 34.0522 to -118.2437
            tzId.startsWith("America/Toronto") -> 43.6532 to -79.3832
            tzId.startsWith("America/Vancouver") -> 49.2827 to -123.1207
            else -> {
                val offsetHours = TimeZone.getDefault().rawOffset / (1000 * 60 * 60)
                when (offsetHours) {
                    8 -> 1.3521 to 103.8198 // Default UTC+8 -> Singapore
                    9 -> 35.6762 to 139.6503 // UTC+9 -> Tokyo
                    7 -> 13.7563 to 100.5018 // UTC+7 -> Bangkok
                    0 -> 51.5074 to -0.1278  // UTC+0 -> London
                    1 -> 48.8566 to 2.3522   // UTC+1 -> Paris
                    -5 -> 40.7128 to -74.0060 // UTC-5 -> New York
                    -8 -> 34.0522 to -118.2437 // UTC-8 -> Los Angeles
                    else -> 1.3521 to 103.8198 // Safe default: Singapore
                }
            }
        }
    }

    private suspend fun fetchFromOpenMeteo(
        lat: Double,
        lon: Double,
        isActualLocation: Boolean,
    ): WeatherData? = withContext(Dispatchers.IO) {
        val urlString = "https://api.open-meteo.com/v1/forecast?" +
            "latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=auto"

        val conn = runCatching {
            (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
        }.getOrNull() ?: return@withContext null

        try {
            if (conn.responseCode != 200) {
                Logger.w(TAG, "Open-Meteo returned HTTP ${conn.responseCode}")
                return@withContext null
            }

            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }

            val root = JSONObject(jsonText)
            val currentObj = root.getJSONObject("current")
            val tempC = currentObj.getDouble("temperature_2m").toFloat()
            val code = currentObj.getInt("weather_code")

            val dailyObj = root.optJSONObject("daily")
            val maxArray = dailyObj?.optJSONArray("temperature_2m_max")
            val minArray = dailyObj?.optJSONArray("temperature_2m_min")
            val highC = maxArray?.optDouble(0, (tempC + 3).toDouble())?.toFloat() ?: (tempC + 3f)
            val lowC = minArray?.optDouble(0, (tempC - 3).toDouble())?.toFloat() ?: (tempC - 3f)

            val tz = root.optString("timezone", "").substringAfterLast("/").replace("_", " ")

            // Determine friendly location name:
            var locName = if (tz.isNotBlank()) tz else "Local Weather"
            if (isActualLocation) {
                try {
                    if (Geocoder.isPresent()) {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        val locality = withTimeoutOrNull(3_000L) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                suspendCancellableCoroutine { continuation ->
                                    geocoder.getFromLocation(lat, lon, 1) { addresses ->
                                        val address = addresses.firstOrNull()
                                        val loc = address?.locality ?: address?.subAdminArea ?: address?.adminArea
                                        if (continuation.isActive) continuation.resume(loc)
                                    }
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                val addresses = geocoder.getFromLocation(lat, lon, 1)
                                val address = addresses?.firstOrNull()
                                address?.locality ?: address?.subAdminArea ?: address?.adminArea
                            }
                        }
                        if (!locality.isNullOrBlank()) {
                            locName = locality
                        }
                    }
                } catch (e: Exception) {
                    Logger.d(TAG) { "Geocoder lookup skipped: ${e.message}" }
                }
            }

            WeatherData(
                tempC = tempC,
                tempF = (tempC * 9f / 5f) + 32f,
                conditionCode = code,
                conditionText = getWeatherDescription(code),
                highC = highC,
                lowC = lowC,
                locationName = locName,
                lastUpdatedMs = System.currentTimeMillis(),
                isLoading = false,
            )
        } catch (t: Throwable) {
            Logger.w(TAG, "Weather fetch error", t)
            null
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun saveToCache(data: WeatherData) {
        context.weatherDataStore.edit { prefs ->
            prefs[KEY_TEMP_C] = data.tempC
            prefs[KEY_CONDITION_CODE] = data.conditionCode
            prefs[KEY_CONDITION_TEXT] = data.conditionText
            prefs[KEY_HIGH_C] = data.highC
            prefs[KEY_LOW_C] = data.lowC
            prefs[KEY_LOCATION_NAME] = data.locationName
            prefs[KEY_LAST_UPDATED] = data.lastUpdatedMs
        }
    }
}
