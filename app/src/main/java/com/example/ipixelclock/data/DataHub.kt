package com.example.ipixelclock.data

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import com.example.ipixelclock.settings.Settings
import kotlin.concurrent.thread

/**
 * Everything the info pages read: where we are, what the weather is, when the
 * sun rises, and whatever the phone's own sensors can add.
 *
 * Deliberately tolerant. A clock with no network, no location permission and no
 * barometer still shows the time and the date; each readout simply reports that
 * it has nothing, and the page rotation skips it. Nothing here ever blocks the
 * render thread — the network runs on its own thread and the results land in
 * volatile fields.
 */
class DataHub(private val context: Context) {

    // ------------------------------------------------------------- the values

    @Volatile
    var weather: WeatherService.Conditions? = null
        private set

    @Volatile
    var sunriseMs: Long? = null
        private set

    @Volatile
    var sunsetMs: Long? = null
        private set

    /** Barometric pressure in hPa from the phone's own sensor, if it has one. */
    @Volatile
    var sensorPressureHpa: Float? = null
        private set

    /** Relative humidity from the phone's own sensor, if it has one. */
    @Volatile
    var sensorHumidity: Float? = null
        private set

    /** Where the readouts are being computed for; NaN until known. */
    @Volatile
    var latitude: Double = Double.NaN
        private set

    @Volatile
    var longitude: Double = Double.NaN
        private set

    /**
     * Pressure and humidity for the telemetry page, preferring the phone's own
     * sensors and falling back to the forecast.
     *
     * The distinction matters: a barometer on the windowsill is measuring this
     * room, while Open-Meteo is reporting the region. Both are worth showing,
     * neither is worth pretending to be the other, so the page labels which.
     */
    val pressureHpa: Double? get() = sensorPressureHpa?.toDouble() ?: weather?.pressureHpa
    val humidityPercent: Int? get() = sensorHumidity?.toInt() ?: weather?.humidityPercent

    /** True when there is anything at all to put on the telemetry page. */
    val hasTelemetry: Boolean get() = pressureHpa != null || humidityPercent != null

    val hasLocation: Boolean get() = !latitude.isNaN() && !longitude.isNaN()

    // ------------------------------------------------------------- the sensors

    private val sensors by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_PRESSURE -> sensorPressureHpa = e.values[0]
                Sensor.TYPE_RELATIVE_HUMIDITY -> sensorHumidity = e.values[0]
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        val sm = sensors ?: return
        // SENSOR_DELAY_NORMAL is ~200 ms, which is far more often than a page
        // that changes once a minute needs; the readings are cheap and the
        // sensors are low power, so it is not worth a slower rate.
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "barometer present")
        }
        sm.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)?.let {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "humidity sensor present")
        }
    }

    fun stop() {
        try {
            sensors?.unregisterListener(sensorListener)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ the refresh

    private var lastWeatherMs = 0L
    private var lastSunDay = -1
    @Volatile
    private var fetching = false

    /**
     * Called from the render loop. Cheap unless something is actually due —
     * the weather every fifteen minutes, the sun times once a day.
     */
    fun tick(settings: Settings, nowMs: Long) {
        resolveLocation(settings)
        if (!hasLocation) return

        val day = (nowMs / 86_400_000L).toInt()
        if (day != lastSunDay) {
            lastSunDay = day
            val r = SunTimes.calculate(latitude, longitude, nowMs)
            sunriseMs = r.sunriseMs
            sunsetMs = r.sunsetMs
            Log.i(TAG, "sun times for $latitude,$longitude: ${r.sunriseMs} / ${r.sunsetMs}")
        }

        val wantsWeather = settings.showWeather
        if (wantsWeather && !fetching && nowMs - lastWeatherMs > WEATHER_INTERVAL_MS) {
            lastWeatherMs = nowMs
            fetching = true
            thread(isDaemon = true, name = "ipixel-weather") {
                try {
                    WeatherService.fetch(latitude, longitude)?.let { weather = it }
                } finally {
                    fetching = false
                }
            }
        }
    }

    /**
     * A typed-in city wins over the device's own fix. Somebody who has told the
     * clock where it is should not have that overridden by a stale GPS reading
     * from wherever the phone was last switched on.
     */
    private fun resolveLocation(settings: Settings) {
        if (!settings.cityLat.isNaN() && !settings.cityLon.isNaN()) {
            latitude = settings.cityLat
            longitude = settings.cityLon
            return
        }
        if (hasLocation) return
        lastKnownFix()?.let { (lat, lon) ->
            latitude = lat
            longitude = lon
            Log.i(TAG, "using the device's last known fix")
        }
    }

    /** The last known fix, if permission was granted. Never requests updates —
     *  a clock does not need to wake the GPS. */
    private fun lastKnownFix(): Pair<Double, Double>? {
        if (Build.VERSION.SDK_INT >= 23 &&
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            for (p in providers) {
                @Suppress("MissingPermission")
                val loc = try {
                    lm.getLastKnownLocation(p)
                } catch (_: Exception) {
                    null
                }
                if (loc != null) return loc.latitude to loc.longitude
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Forces a weather refresh, e.g. after the city changed. */
    fun invalidate() {
        lastWeatherMs = 0L
        lastSunDay = -1
        latitude = Double.NaN
        longitude = Double.NaN
    }

    companion object {
        private const val TAG = "DataHub"
        private const val WEATHER_INTERVAL_MS = 15 * 60 * 1000L
    }
}
