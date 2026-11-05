package com.abdllh.aura.ui

import com.abdllh.aura.nav.CarLocation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Day or night under the "auto" theme, from the real sun: its height at the car's last fix, at this moment (NOAA's solar
 * position formulas, good to a small part of a degree: under a minute or two of time at these latitudes). Night is the sun
 * under the horizon as an almanac counts it, so day ends at Maghrib and begins at sunrise (SPEC.md section 8). The date-only
 * estimate this replaces was one for the whole Gulf: up to 33 minutes off in the Eastern Province.
 */
object Daylight {
    /** The sun's upper edge with refraction: sunset and sunrise, the moments prayer times use. */
    const val NIGHT_BELOW_DEG = -0.833

    /** Before the first fix this unit ever had: Dammam. */
    private const val HOME_LAT = 26.43
    private const val HOME_LON = 50.10

    /** Night now, where the car is (or last was). */
    fun isNightNow(): Boolean {
        val (lat, lon) = place()
        return isNight(lat, lon, System.currentTimeMillis())
    }

    /** Where the sun is asked about: the car's last fix, or Dammam before any. Latitude, longitude. */
    fun place(): Pair<Double, Double> {
        val at = CarLocation.lastKnown()
        // (some GPS chips report 0, 0 before they have a position)
        return if (at != null && (abs(at.lat) > 0.01 || abs(at.lon) > 0.01)) at.lat to at.lon else HOME_LAT to HOME_LON
    }

    fun isNight(lat: Double, lon: Double, utcMs: Long): Boolean = altitude(lat, lon, utcMs) < NIGHT_BELOW_DEG

    /** The sun's height over the horizon at a place and moment, degrees (below it: negative). No refraction. */
    fun altitude(lat: Double, lon: Double, utcMs: Long): Double {
        val t = (utcMs / 86_400_000.0 + 2_440_587.5 - 2_451_545.0) / 36_525.0 // Julian centuries since J2000
        val meanLong = rad(mod360(280.46646 + t * (36000.76983 + t * 0.0003032)))
        val anomaly = rad(357.52911 + t * (35999.05029 - 0.0001537 * t))
        val ecc = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val centre = sin(anomaly) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * anomaly) * (0.019993 - 0.000101 * t) + sin(3 * anomaly) * 0.000289
        val omega = rad(125.04 - 1934.136 * t)
        val longitude = rad(deg(meanLong) + centre - 0.00569 - 0.00478 * sin(omega))
        val obliquity = rad(23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0 + 0.00256 * cos(omega))
        val declination = asin(sin(obliquity) * sin(longitude))
        val y = tan(obliquity / 2).let { it * it }
        // the equation of time, minutes: how far the sun runs ahead of the clock's mean sun
        val eqTime = 4.0 * deg(y * sin(2 * meanLong) - 2 * ecc * sin(anomaly) + 4 * ecc * y * sin(anomaly) * cos(2 * meanLong) -
            0.5 * y * y * sin(4 * meanLong) - 1.25 * ecc * ecc * sin(2 * anomaly))
        val minutesUtc = (utcMs % 86_400_000L + 86_400_000L) % 86_400_000L / 60_000.0
        val hourAngle = rad(mod360((minutesUtc + eqTime + 4.0 * lon) / 4.0) - 180.0)
        val latRad = rad(lat)
        val cosZenith = sin(latRad) * sin(declination) + cos(latRad) * cos(declination) * cos(hourAngle)
        return 90.0 - deg(acos(cosZenith.coerceIn(-1.0, 1.0)))
    }

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI
    private fun mod360(d: Double) = d - 360.0 * floor(d / 360.0)
}
