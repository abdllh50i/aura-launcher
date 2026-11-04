package com.abdllh.aura.nav.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class PrayerTimesTest {
    private val ksa = ZoneOffset.ofHours(3)
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ksa).toInstant().toEpochMilli()
    private fun clock(t: Long): Int = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), ksa).let { it.hour * 60 + it.minute }

    /**
     * Fajr, Dhuhr, Asr, Maghrib, Isha in minutes of the day, worked out apart from this code by the formulas of praytimes.org in
     * Python (their own low-precision sun, not NOAA's) with Umm al-Qura's angles. Not the official calendar: that is not on this PC.
     */
    private val reference = listOf(
        Triple(27.0 to 49.66, ms(2026, 11, 4), listOf(272, 685, 874, 1016, 1106)),
        Triple(27.0 to 49.66, ms(2026, 6, 21), listOf(193, 703, 911, 1118, 1208)),
        Triple(27.0 to 49.66, ms(2026, 12, 21), listOf(301, 699, 873, 1013, 1103)),
        Triple(26.43 to 50.10, ms(2026, 11, 4), listOf(270, 683, 873, 1015, 1105)),
        Triple(26.43 to 50.10, ms(2027, 3, 20), listOf(265, 707, 914, 1071, 1161)),
        Triple(24.71 to 46.68, ms(2026, 6, 21), listOf(213, 715, 916, 1125, 1215)),
        Triple(24.71 to 46.68, ms(2026, 12, 21), listOf(309, 711, 890, 1029, 1119)),
        Triple(21.54 to 39.17, ms(2027, 3, 20), listOf(312, 751, 956, 1114, 1204))
    )

    @Test fun againstASeparateComputation() {
        for ((at, day, want) in reference) {
            val t = PrayerTimes.of(at.first, at.second, day, false)
            val got = PrayerTimes.Prayer.values().map { clock(t.of(it)) }
            for (i in want.indices) assertEquals("$at $i: $got against $want", want[i].toDouble(), got[i].toDouble(), 2.0)
        }
    }

    @Test fun ishaIsLaterInRamadan() {
        val plain = PrayerTimes.of(26.43, 50.10, ms(2026, 11, 4), false)
        val ramadan = PrayerTimes.of(26.43, 50.10, ms(2026, 11, 4), true)
        assertEquals(30 * 60_000L, ramadan.isha - plain.isha)
        assertEquals(plain.maghrib, ramadan.maghrib)
    }

    @Test fun ramadanByTheCalendar() {
        // Umm al-Qura: Ramadan 1447 runs from 18 February to 19 March 2026
        assertTrue(PrayerTimes.isRamadan(ms(2026, 3, 1)))
        assertFalse(PrayerTimes.isRamadan(ms(2026, 11, 4)))
        assertFalse(PrayerTimes.isRamadan(ms(2026, 1, 15)))
    }

    @Test fun theNextPrayerThroughADay() {
        val lat = 26.43
        val lon = 50.10
        fun next(h: Int, mi: Int) = PrayerTimes.next(lat, lon, ms(2026, 11, 4, h, mi), false).first
        assertEquals(PrayerTimes.Prayer.FAJR, next(3, 0))
        assertEquals(PrayerTimes.Prayer.DHUHR, next(5, 0))
        assertEquals(PrayerTimes.Prayer.ASR, next(12, 0))
        assertEquals(PrayerTimes.Prayer.MAGHRIB, next(15, 0))
        assertEquals(PrayerTimes.Prayer.ISHA, next(17, 30))
        // after Isha: tomorrow's Fajr
        val (p, at) = PrayerTimes.next(lat, lon, ms(2026, 11, 4, 22, 0), false)
        assertEquals(PrayerTimes.Prayer.FAJR, p)
        assertTrue(at > ms(2026, 11, 5, 3, 0) && at < ms(2026, 11, 5, 6, 0))
    }
}
