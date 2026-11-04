package com.abdllh.aura.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DaylightTest {
    private val jubail = 27.0 to 49.66
    private val dammam = 26.43 to 50.10

    /** A moment in Saudi time (UTC+3), as UTC milliseconds. */
    private fun ksa(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("GMT+3")).run {
        clear()
        set(y, mo - 1, d, h, mi, 0)
        timeInMillis
    }

    /** The first minute of [from]..[to] where the sun has gone under [deg] (or, [rising], come over it), in minutes of the day. */
    private fun crossing(p: Pair<Double, Double>, from: Long, to: Long, deg: Double, rising: Boolean): Long {
        var t = from
        while (t < to) {
            val a = Daylight.altitude(p.first, p.second, t)
            if (if (rising) a >= deg else a < deg) return t
            t += 60_000L
        }
        throw AssertionError("no crossing")
    }

    private fun minutesInto(day: Long, t: Long) = (t - day) / 60_000L

    @Test fun sunsetAndSunriseMatchTheAlmanac() {
        // sunset is the sun's upper edge with refraction, -0.833 degrees. Reference: NOAA's other, shorter formula (by
        // fractional year), worked out apart from this code in Python: Jubail 3 Nov 05:52 and 16:57, Dammam 21 June 04:47
        // and 18:35
        val nov3 = ksa(2026, 11, 3, 0, 0)
        val set = minutesInto(nov3, crossing(jubail, ksa(2026, 11, 3, 15, 0), ksa(2026, 11, 3, 19, 0), -0.833, false))
        assertEquals(16 * 60 + 57.5, set.toDouble(), 2.0)
        val rise = minutesInto(nov3, crossing(jubail, ksa(2026, 11, 3, 4, 0), ksa(2026, 11, 3, 8, 0), -0.833, true))
        assertEquals(5 * 60 + 52.5, rise.toDouble(), 2.0)
        val jun21 = ksa(2026, 6, 21, 0, 0)
        val set2 = minutesInto(jun21, crossing(dammam, ksa(2026, 6, 21, 16, 0), ksa(2026, 6, 21, 21, 0), -0.833, false))
        assertEquals(18 * 60 + 35.0, set2.toDouble(), 2.0)
        val rise2 = minutesInto(jun21, crossing(dammam, ksa(2026, 6, 21, 3, 0), ksa(2026, 6, 21, 7, 0), -0.833, true))
        assertEquals(4 * 60 + 47.0, rise2.toDouble(), 2.0)
    }

    @Test fun nightStartsAtSunsetAndEndsAtSunrise() {
        // SPEC.md section 8: day ends at Maghrib and begins at sunrise. Jubail, 3 Nov: 05:52 and 16:57
        assertEquals(-0.833, Daylight.NIGHT_BELOW_DEG, 0.0)
        assertFalse(Daylight.isNight(jubail.first, jubail.second, ksa(2026, 11, 3, 16, 50)))
        assertTrue(Daylight.isNight(jubail.first, jubail.second, ksa(2026, 11, 3, 17, 5)))
        assertTrue(Daylight.isNight(jubail.first, jubail.second, ksa(2026, 11, 3, 5, 45)))
        assertFalse(Daylight.isNight(jubail.first, jubail.second, ksa(2026, 11, 3, 6, 0)))
    }

    @Test fun middayAndMidnightEveryMonth() {
        for (month in 1..12) {
            assertFalse("noon, month $month", Daylight.isNight(dammam.first, dammam.second, ksa(2026, month, 15, 12, 0)))
            assertTrue("midnight, month $month", Daylight.isNight(dammam.first, dammam.second, ksa(2026, month, 15, 0, 0)))
        }
    }

    @Test fun thePlaceCounts() {
        // the same moment: the sun has set further east first. 17:15 Saudi time on 3 Nov: Dammam (east) closer to dusk than
        // Tabuk (36.6 E), where it still stands over the horizon
        val at = ksa(2026, 11, 3, 17, 15)
        assertTrue(Daylight.altitude(dammam.first, dammam.second, at) < Daylight.altitude(28.4, 36.6, at) - 5.0)
    }

    @Test fun oneDayNightPerDayNoFlapping() {
        // walking one day minute by minute: exactly one change to night and one to day
        var changes = 0
        var was = Daylight.isNight(jubail.first, jubail.second, ksa(2026, 3, 20, 0, 0))
        var t = ksa(2026, 3, 20, 0, 0)
        val end = ksa(2026, 3, 21, 0, 0)
        while (t < end) {
            val now = Daylight.isNight(jubail.first, jubail.second, t)
            if (now != was) { changes++; was = now }
            t += 60_000L
        }
        assertEquals(2, changes)
    }
}
