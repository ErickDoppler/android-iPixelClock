package com.example.ipixelclock.data

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Current conditions from Open-Meteo.
 *
 * Adapted from MatrixClockApp. Open-Meteo needs no API key and no account,
 * which keeps the app free of credentials — it and the city lookup are the only
 * things here that touch the network, and only when the readouts ask.
 *
 * Extended over the original with pressure and humidity, because the telemetry
 * page wants them and most phones have no barometer to ask instead.
 */
object WeatherService {

    private const val TAG = "WeatherService"
    private const val TIMEOUT_MS = 8000

    data class Conditions(
        val temperatureC: Double,
        val apparentC: Double?,
        val humidityPercent: Int?,
        val pressureHpa: Double?,
        val windKph: Double?,
        /** WMO interpretation code, for picking the icon. */
        val code: Int,
        val description: String,
        val fetchedAtMs: Long = System.currentTimeMillis()
    )

    /** Blocking. Must not run on the main thread. Null when unreachable. */
    fun fetch(latitude: Double, longitude: Double): Conditions? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
            "weather_code,wind_speed_10m,surface_pressure"

        return try {
            val body = get(url) ?: return null
            val current = JSONObject(body).getJSONObject("current")
            val code = current.optInt("weather_code", -1)
            Conditions(
                temperatureC = current.getDouble("temperature_2m"),
                apparentC = current.optDouble("apparent_temperature").takeIf { !it.isNaN() },
                humidityPercent = current.optInt("relative_humidity_2m", -1).takeIf { it >= 0 },
                pressureHpa = current.optDouble("surface_pressure").takeIf { !it.isNaN() },
                windKph = current.optDouble("wind_speed_10m").takeIf { !it.isNaN() },
                code = code,
                description = describe(code)
            )
        } catch (e: Exception) {
            Log.w(TAG, "weather lookup failed", e)
            null
        }
    }

    /** A named place, for the manual city picker. */
    data class Place(val name: String, val country: String, val lat: Double, val lon: Double)

    /**
     * City search, proxied through the app so the browser never talks to a
     * third party and no user location leaves the device except the query.
     */
    fun geocode(query: String, limit: Int = 8): List<Place> {
        if (query.isBlank()) return emptyList()
        val q = try {
            URLEncoder.encode(query.trim(), "UTF-8")
        } catch (_: Exception) {
            return emptyList()
        }
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=$limit"
        return try {
            val body = get(url) ?: return emptyList()
            val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val out = ArrayList<Place>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Place(
                        name = o.optString("name"),
                        country = o.optString("country_code", o.optString("country", "")),
                        lat = o.getDouble("latitude"),
                        lon = o.getDouble("longitude")
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "geocode failed", e)
            emptyList()
        }
    }

    private fun get(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                Log.w(TAG, "HTTP ${conn.responseCode} for $url")
                null
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "request failed", e)
        null
    }

    /**
     * WMO codes collapsed to the handful of icons a 16-row panel can actually
     * distinguish. Drawing a difference between "light rain" and "rain" at this
     * size would be a lie.
     */
    fun icon(code: Int): Icon = when (code) {
        0, 1 -> Icon.CLEAR
        2 -> Icon.PARTLY
        3 -> Icon.CLOUD
        45, 48 -> Icon.FOG
        in 51..57 -> Icon.DRIZZLE
        in 61..67, in 80..82 -> Icon.RAIN
        in 71..77, 85, 86 -> Icon.SNOW
        95, 96, 99 -> Icon.STORM
        else -> Icon.UNKNOWN
    }

    enum class Icon { CLEAR, PARTLY, CLOUD, FOG, DRIZZLE, RAIN, SNOW, STORM, UNKNOWN }

    /** WMO interpretation codes as short phrases. */
    private fun describe(code: Int): String = when (code) {
        0 -> "clear sky"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
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
        82 -> "violent rain showers"
        85, 86 -> "snow showers"
        95 -> "a thunderstorm"
        96, 99 -> "a thunderstorm with hail"
        else -> "unknown conditions"
    }
}
