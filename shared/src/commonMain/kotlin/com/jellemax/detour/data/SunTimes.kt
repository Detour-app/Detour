package com.jellemax.detour.data

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Day/night from the sun's position, for the auto theme. */
object SunTimes {

    /** Sunrise/sunset elevation: solar disc radius + atmospheric refraction. */
    private const val HORIZON_DEG = -0.833

    fun isNight(lat: Double, lon: Double, timeMs: Long): Boolean =
        solarElevationDeg(lat, lon, timeMs) < HORIZON_DEG

    /** Clock-based guess for when no location is available. */
    fun isNightFallback(timeMs: Long): Boolean {
        val hour = Instant.fromEpochMilliseconds(timeMs)
            .toLocalDateTime(TimeZone.currentSystemDefault()).hour
        return hour < 7 || hour >= 21
    }

    /**
     * Solar elevation in degrees (NOAA low-precision formulas, good to ~0.5°
     * — plenty for a theme switch).
     */
    fun solarElevationDeg(lat: Double, lon: Double, timeMs: Long): Double {
        val n = timeMs / 86_400_000.0 + 2440587.5 - 2451545.0 // days since J2000
        val meanLon = (280.460 + 0.9856474 * n).mod(360.0)
        val meanAnom = toRadians((357.528 + 0.9856003 * n).mod(360.0))
        val eclLon = toRadians(
            meanLon + 1.915 * sin(meanAnom) + 0.020 * sin(2 * meanAnom))
        val obliquity = toRadians(23.439 - 0.0000004 * n)

        val ra = atan2(cos(obliquity) * sin(eclLon), cos(eclLon))
        val dec = asin(sin(obliquity) * sin(eclLon))

        val gmstHours = (18.697374558 + 24.06570982441908 * n).mod(24.0)
        val localSiderealRad = toRadians(gmstHours * 15.0 + lon)
        val hourAngle = localSiderealRad - ra

        val latRad = toRadians(lat)
        val elevation = asin(
            sin(latRad) * sin(dec) + cos(latRad) * cos(dec) * cos(hourAngle))
        return toDegrees(elevation)
    }
}
