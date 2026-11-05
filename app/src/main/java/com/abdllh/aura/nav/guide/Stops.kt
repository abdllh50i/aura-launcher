package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Units
import com.abdllh.aura.system.PrayerTimes
import kotlin.math.abs

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
        return if (s != null) s.roadClass == "motorway" else route.motorwayAt(along)
    }

    companion object {
        const val REACH_MAIN_M = 300.0
        const val REACH_OTHER_M = 200.0
        /** Beside it now: too late to turn in. */
        const val PASSED_M = 150.0
        val CATS = setOf("fuel", "mosque")
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
