package com.example.ipixelclock.data

import kotlin.math.asin
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sunrise and sunset from latitude, longitude and date.
 *
 * This is the standard NOAA sunrise equation, computed locally — no network and no API key, so the
 * date announcement works with nothing but a last-known location.
 */
object SunTimes {

    /** Solar elevation at sunrise/sunset, accounting for refraction and the sun's disc. */
    private const val SUN_ALTITUDE_DEG = -0.833

    private const val EARTH_OBLIQUITY_DEG = 23.44
    private const val UNIX_EPOCH_JULIAN_DAY = 2440587.5
    private const val J2000 = 2451545.0
    private const val MS_PER_DAY = 86_400_000.0

    /** Sunrise and sunset as epoch millis, or null at a latitude with polar day or night. */
    data class Result(val sunriseMs: Long?, val sunsetMs: Long?)

    fun calculate(latitude: Double, longitude: Double, atMs: Long): Result {
        // Days since J2000, corrected for longitude so the "day" is the local solar one.
        val julianDay = atMs / MS_PER_DAY + UNIX_EPOCH_JULIAN_DAY
        val n = Math.round(julianDay - J2000 - 0.0009 + longitude / 360.0).toDouble()
        val meanSolarNoon = n + 0.0009 - longitude / 360.0

        val meanAnomaly = (357.5291 + 0.98560028 * meanSolarNoon).mod(360.0)
        val centre = 1.9148 * sinDeg(meanAnomaly) +
            0.0200 * sinDeg(2 * meanAnomaly) +
            0.0003 * sinDeg(3 * meanAnomaly)
        val eclipticLongitude = (meanAnomaly + centre + 180.0 + 102.9372).mod(360.0)

        val solarTransit = J2000 + meanSolarNoon +
            0.0053 * sinDeg(meanAnomaly) -
            0.0069 * sinDeg(2 * eclipticLongitude)

        val declination = asin(sinDeg(eclipticLongitude) * sinDeg(EARTH_OBLIQUITY_DEG))
        val hourAngleCos = (sinDeg(SUN_ALTITUDE_DEG) - sinDeg(latitude) * sin(declination)) /
            (cosDeg(latitude) * cos(declination))

        // |cos| > 1 means the sun never reaches the horizon: midnight sun or polar night.
        if (abs(hourAngleCos) > 1.0) return Result(null, null)

        val hourAngle = Math.toDegrees(acos(hourAngleCos))
        val sunset = solarTransit + hourAngle / 360.0
        val sunrise = solarTransit - hourAngle / 360.0

        return Result(julianToMillis(sunrise), julianToMillis(sunset))
    }

    private fun julianToMillis(julian: Double): Long =
        ((julian - UNIX_EPOCH_JULIAN_DAY) * MS_PER_DAY).toLong()

    private fun sinDeg(degrees: Double) = sin(Math.toRadians(degrees))
    private fun cosDeg(degrees: Double) = cos(Math.toRadians(degrees))
}
