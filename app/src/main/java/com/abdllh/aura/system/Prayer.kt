package com.abdllh.aura.system

import com.abdllh.aura.ui.Daylight
import java.time.Instant
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.tan

/**
 * The prayer times by the Umm al-Qura method, from the sun at a place and moment. The task's "existing Umm al-Qura code" is the
 * Hijri date in `Amri.kt` and has no times at all: this is new. Fajr at 18.5 degrees, Dhuhr at the noon, Asr at a shadow of its
 * object's length plus its noon shadow (Shafi'i, the Kingdom's), Maghrib at sunset (-0.833), Isha 90 minutes after, 120 in Ramadan.
 * [Daylight.altitude] is NOAA's sun. Checked against a separate computation only; compare with the official calendar and KACST's
 * app before relying on it to the minute.
 *
 * The same text as branch v2-guide's, in its nav/guide/Stops.kt; that file cannot come as it is (the rest of it is the guidance's: routes,
 * corridors, words). When the two meet, the object in Stops.kt goes and its two users import this one.
 */
object PrayerTimes {
    enum class Prayer(val word: String) { FAJR("prayer.fajr"), DHUHR("prayer.dhuhr"), ASR("prayer.asr"), MAGHRIB("prayer.maghrib"), ISHA("prayer.isha") }

    class Times(val fajr: Long, val dhuhr: Long, val asr: Long, val maghrib: Long, val isha: Long) {
        fun of(p: Prayer) = when (p) {
            Prayer.FAJR -> fajr
            Prayer.DHUHR -> dhuhr
            Prayer.ASR -> asr
            Prayer.MAGHRIB -> maghrib
            Prayer.ISHA -> isha
        }
    }

    private const val FAJR_DEG = -18.5
    private const val SUNSET_DEG = -0.833
    private const val ISHA_MIN = 90L
    private const val ISHA_RAMADAN_MIN = 120L
    private const val DAY_MS = 86_400_000L
    private const val MINUTE_MS = 60_000L
    /** The Hijri month of Ramadan. */
    private const val RAMADAN = 9

    /** The day's times at this place: the solar day that [utcMs] falls in. [ramadan]: Isha is later ([isRamadan]). */
    fun of(lat: Double, lon: Double, utcMs: Long, ramadan: Boolean): Times {
        val offset = (lon / 15.0 * 3_600_000.0).toLong()
        val dayStart = Math.floorDiv(utcMs + offset, DAY_MS) * DAY_MS - offset
        val noon = noonOf(lat, lon, dayStart + DAY_MS / 2)
        val noonAlt = Daylight.altitude(lat, lon, noon)
        // the sun's height when a stick's shadow is its own length plus its shadow at noon
        val asrAlt = Math.toDegrees(atan(1.0 / (1.0 + tan((90.0 - noonAlt) * PI / 180.0))))
        val maghrib = crossing(lat, lon, noon, noon + DAY_MS / 2, SUNSET_DEG, falling = true)
        val isha = maghrib + (if (ramadan) ISHA_RAMADAN_MIN else ISHA_MIN) * MINUTE_MS
        return Times(
            crossing(lat, lon, noon - DAY_MS / 2, noon, FAJR_DEG, falling = false),
            noon,
            crossing(lat, lon, noon, noon + DAY_MS / 2, asrAlt, falling = true),
            maghrib,
            isha
        )
    }

    /** The prayer next after [utcMs] and when it is, today's or tomorrow's. */
    fun next(lat: Double, lon: Double, utcMs: Long, ramadan: Boolean): Pair<Prayer, Long> {
        val t = of(lat, lon, utcMs, ramadan)
        for (p in Prayer.values()) if (t.of(p) > utcMs) return p to t.of(p)
        val tomorrow = of(lat, lon, utcMs + DAY_MS, ramadan)
        return Prayer.FAJR to tomorrow.fajr
    }

    /** By the Umm al-Qura calendar, which java.time's Hijri is (the same as `Amri.kt` says it). Not there or failing: not Ramadan. Isha off by 30 minutes is the worst that does. */
    fun isRamadan(utcMs: Long): Boolean = try {
        val d = HijrahDate.from(Instant.ofEpochMilli(utcMs).atOffset(java.time.ZoneOffset.ofHours(3)).toLocalDate())
        d.get(ChronoField.MONTH_OF_YEAR) == RAMADAN
    } catch (_: Throwable) {
        false
    }

    private fun noonOf(lat: Double, lon: Double, guess: Long): Long {
        var a = guess - 3_600_000L
        var b = guess + 3_600_000L
        while (b - a > 2_000L) {
            val m1 = a + (b - a) / 3
            val m2 = b - (b - a) / 3
            if (Daylight.altitude(lat, lon, m1) < Daylight.altitude(lat, lon, m2)) a = m1 else b = m2
        }
        return (a + b) / 2
    }

    private fun crossing(lat: Double, lon: Double, from: Long, to: Long, deg: Double, falling: Boolean): Long {
        var a = from
        var b = to
        while (b - a > 1_000L) {
            val m = (a + b) / 2
            val above = Daylight.altitude(lat, lon, m) > deg
            if (above == falling) a = m else b = m
        }
        return (a + b) / 2
    }
}

/**
 * What the glance and Home's prayer capsule show (SPEC.md section 5): the next prayer at the last fix, then Dammam ([Daylight.place]), and how
 * long until it. Asked once a minute; the day's times are kept until the day or the place changes, so a tick does no solar work.
 */
object Prayer {
    /** [minutes]: to the prayer, rounded up, so never 0 before it comes. */
    class Next(@JvmField val prayer: PrayerTimes.Prayer, @JvmField val atUtc: Long, @JvmField val minutes: Int)

    /** Moving further than this asks the sun again: about a minute of time. */
    private const val SAME_PLACE_DEG = 0.2

    private var today: PrayerTimes.Times? = null
    private var fajrAfter = 0L
    private var keptAt = 0L
    private var keptLat = 0.0
    private var keptLon = 0.0
    private var keptRamadan = false

    fun next(nowUtc: Long = System.currentTimeMillis()): Next {
        val (lat, lon) = Daylight.place()
        return of(lat, lon, nowUtc)
    }

    fun of(lat: Double, lon: Double, nowUtc: Long): Next {
        val ramadan = PrayerTimes.isRamadan(nowUtc)
        var t = today
        // (a clock set back counts too: what was kept is for a day that has not come)
        if (t == null || nowUtc >= fajrAfter || nowUtc < keptAt || ramadan != keptRamadan || Math.abs(lat - keptLat) > SAME_PLACE_DEG || Math.abs(lon - keptLon) > SAME_PLACE_DEG) {
            t = PrayerTimes.of(lat, lon, nowUtc, ramadan)
            today = t
            keptAt = nowUtc
            fajrAfter = PrayerTimes.of(lat, lon, nowUtc + 86_400_000L, ramadan).fajr
            keptLat = lat
            keptLon = lon
            keptRamadan = ramadan
        }
        for (p in PrayerTimes.Prayer.values()) if (t.of(p) > nowUtc) return Next(p, t.of(p), minutesTo(t.of(p), nowUtc))
        return Next(PrayerTimes.Prayer.FAJR, fajrAfter, minutesTo(fajrAfter, nowUtc))
    }

    private fun minutesTo(at: Long, now: Long) = ceil((at - now) / 60_000.0).toInt().coerceAtLeast(1)
}
