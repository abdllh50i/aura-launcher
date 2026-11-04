package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Units
import com.abdllh.aura.ui.Daylight
import java.time.Instant
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.abs
import kotlin.math.tan

/** A place to stop at ahead on the route. [along]: where the route passes it. [side]: +1 right, -1 left. */
class StopAhead(val poi: PoiRow, val along: Double, val side: Int)

/**
 * Stations and mosques along a route, for "أقرب محطة بعد 38 كم" and the prayer stop. A corridor query over `p`. On a motorway
 * the far side cannot be reached, so only the driver's own side counts; a station's slip road lets its point lie further off. On other
 * roads either side, and nearer (a U-turn gap is seldom far, on a trunk road too).
 */
internal class StopsAlong(route: GuideRoute, c: Corridor, rows: List<PoiRow>) {
    private val stops: Map<String, List<StopAhead>> = rows.asSequence().filter { !it.coarse }.flatMap { p ->
        c.hits(p.pos, REACH_MAIN_M).asSequence().mapNotNull { h ->
            when {
                roadIsDivided(route, h.along) -> if (h.side > 0) StopAhead(p, h.along, h.side) else null
                h.offset > REACH_OTHER_M -> null
                else -> StopAhead(p, h.along, h.side)
            }
        }
    }.groupBy { it.poi.cat }.mapValues { (_, v) -> v.sortedBy { it.along } }

    fun next(cat: String, along: Double): StopAhead? = stops[cat]?.firstOrNull { it.along > along + PASSED_M }

    fun all(cat: String): List<StopAhead> = stops[cat].orEmpty()

    /** A motorway, by the facts or the answer's own class: its other side cannot be reached. */
    private fun roadIsDivided(route: GuideRoute, along: Double): Boolean {
        val s = route.facts?.at(along)
        if (s != null) return s.roadClass == "motorway"
        val c = route.line.cumulative
        var last: Crossing? = null
        for (st in route.steps) {
            if (st.at > along) break
            for (x in st.crossings) {
                if (x.lineIndex !in c.indices || c[x.lineIndex] > along) break
                last = x
            }
        }
        return last?.classes?.contains("motorway") == true
    }

    companion object {
        const val REACH_MAIN_M = 300.0
        const val REACH_OTHER_M = 200.0
        /** Beside it now: too late to turn in. */
        const val PASSED_M = 150.0
        val CATS = setOf("fuel", "mosque")
    }
}

/**
 * The prayer times by the Umm al-Qura method, from the sun at a place and moment. The task's "existing Umm al-Qura code" is the
 * Hijri date in `Amri.kt` and has no times at all: this is new. Fajr at 18.5 degrees, Dhuhr at the noon, Asr at a shadow of its
 * object's length plus its noon shadow (Shafi'i, the Kingdom's), Maghrib at sunset (-0.833), Isha 90 minutes after, 120 in Ramadan.
 * [Daylight.altitude] is NOAA's sun. Checked against a separate computation only; compare with the official calendar and KACST's
 * app before relying on it to the minute.
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

/** The next prayer on the way. [beforeArrival]: it comes during the trip, so a stop means something. [mosque]: nearest on the route from where the car will be by then; null when the data has none. */
class PrayerStop(val prayer: PrayerTimes.Prayer, val atUtc: Long, val beforeArrival: Boolean, val mosque: StopAhead?, val metresToMosque: Double) {
    /** "الظهر بعد 40 دقيقة"; the banner's first half. */
    fun whenText(nowUtc: Long, arabic: Boolean): String =
        "${Words.text(prayer.word, arabic, false)} ${Words.text("in", arabic, false)} ${Units.duration((atUtc - nowUtc) / 1000.0, arabic)}"

    /** "أقرب مسجد على طريقك بعد 12 كم"; null with no mosque. */
    fun mosqueText(arabic: Boolean): String? = mosque?.let {
        "${Words.text("mosque.next", arabic, false)} ${Words.text("in", arabic, false)} ${Units.distance(metresToMosque, arabic)}"
    }
}

/** The arrival card: which side the destination is on, and where to leave the car. */
class ArrivalCard(val side: Side, val parking: PoiRow?, val parkingMetres: Double) {
    enum class Side(val word: String) { RIGHT("arrive.right"), LEFT("arrive.left"), AHEAD("arrive.ahead") }

    /** "أقرب مواقف 120 م". */
    fun parkingText(arabic: Boolean): String? = parking?.let {
        "${Words.text("parking.near", arabic, false)} ${Units.distance(parkingMetres.coerceAtLeast(10.0), arabic)}"
    }

    companion object {
        /** Off the road by less than this, there is no side to speak of. */
        private const val SIDE_MIN_M = 6.0
        private const val PARKING_M = 400.0

        internal fun of(route: GuideRoute, destination: LatLon?, parkingRows: List<PoiRow>): ArrivalCard {
            val arrive = route.steps.lastOrNull()
            val by = when {
                arrive == null -> null
                arrive.modifier.contains("right") -> Side.RIGHT
                arrive.modifier.contains("left") -> Side.LEFT
                else -> null
            }
            val side = by ?: destination?.let { sideOf(route, it) } ?: Side.AHEAD
            val end = route.line.points.lastOrNull() ?: destination
            var best: PoiRow? = null
            var bestD = PARKING_M
            if (end != null) for (p in parkingRows) {
                if (p.coarse) continue
                val d = Geo.distance(end, p.pos)
                if (d < bestD) { bestD = d; best = p }
            }
            return ArrivalCard(side, best, if (best != null) bestD else 0.0)
        }

        /** Valhalla's arrive step says it, on 48 of 66 routes; else from the line's last stretch. */
        private fun sideOf(route: GuideRoute, destination: LatLon): Side? {
            val pts = route.line.points
            if (pts.size < 2) return null
            val end = pts.last()
            if (Geo.distance(end, destination) < SIDE_MIN_M) return null
            val heading = route.line.at(route.line.length - 5).second
            val d = Geo.angleDiff(heading, Geo.bearing(end, destination))
            return if (abs(d) < 25 || abs(d) > 155) Side.AHEAD else if (d > 0) Side.RIGHT else Side.LEFT
        }
    }
}
