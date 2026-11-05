package com.abdllh.aura.nav.browse

import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Polyline
import com.abdllh.aura.nav.Route
import com.abdllh.aura.nav.Step
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Which routes of an answer the place's card offers (SPEC.md section 6.3), with no view and no Android: the best, and a second only when under 75 % of it is shared with the first.
 * Each is named by the longest road the other does not use. Pure: RouteChoicesTest.
 */
internal object RouteChoices {
    /** Over this much shared with the first it is the same way with a detour, and offers nothing. */
    const val MAX_SHARED = 0.75
    /** A sample along the second route every so many metres, */
    private const val STEP_M = 12.0
    /** and it runs on the first when it lies in a cell of this size next to one of the first's: the same road, and not the frontage road beside it. */
    private const val CELL_M = 14.0
    private const val M_PER_DEG = 111_320.0

    class Option(val route: Route, val road: String)

    /** What the card shows, the best first: one route, or two. */
    fun offer(routes: List<Route>): List<Option> {
        val first = routes.firstOrNull() ?: return emptyList()
        val second = routes.drop(1).firstOrNull { shared(first.line.points, it.line.points) < MAX_SHARED }
            ?: return listOf(Option(first, road(first, null)))
        return listOf(Option(first, road(first, second)), Option(second, road(second, first)))
    }

    /** How much of [alt] by length runs on [main], 0 to 1. */
    fun shared(main: List<LatLon>, alt: List<LatLon>): Double {
        if (main.size < 2 || alt.size < 2) return 0.0
        val lat0 = main[0].lat
        val perLon = M_PER_DEG * cos(Math.toRadians(lat0))
        fun cell(p: LatLon): Long {
            val x = floor(p.lon * perLon / CELL_M).toLong()
            val y = floor(p.lat * M_PER_DEG / CELL_M).toLong()
            return (x shl 32) xor (y and 0xFFFFFFFFL)
        }
        val cells = HashSet<Long>()
        along(main, perLon) { p -> cells.add(cell(p)) }
        var total = 0
        var on = 0
        along(alt, perLon) { p ->
            total++
            val x = floor(p.lon * perLon / CELL_M).toLong()
            val y = floor(p.lat * M_PER_DEG / CELL_M).toLong()
            var hit = false
            for (dx in -1L..1L) for (dy in -1L..1L) if (((x + dx) shl 32) xor ((y + dy) and 0xFFFFFFFFL) in cells) hit = true
            if (hit) on++
        }
        return if (total == 0) 0.0 else on.toDouble() / total
    }

    /** Every [STEP_M] along a line. */
    private inline fun along(pts: List<LatLon>, perLon: Double, each: (LatLon) -> Unit) {
        var left = 0.0
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val len = hypot((b.lat - a.lat) * M_PER_DEG, (b.lon - a.lon) * perLon)
            if (len <= 0.0) continue
            var at = left
            while (at < len) {
                val t = at / len
                each(LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
                at += STEP_M
            }
            left = at - len
        }
    }

    /** The longest road of [r] that [other] does not use; with no such road, its longest; "" when no step has a name. */
    fun road(r: Route, other: Route?): String = road(r.steps, other?.steps)

    fun road(steps: List<Step>, other: List<Step>?): String {
        val lengths = LinkedHashMap<String, Double>()
        for (s in steps) {
            val n = named(s)
            if (n.isNotEmpty()) lengths[n] = (lengths[n] ?: 0.0) + s.distance
        }
        val theirs = other?.mapTo(HashSet()) { named(it) } ?: emptySet<String>()
        return lengths.filterKeys { it !in theirs }.maxByOrNull { it.value }?.key
            ?: lengths.maxByOrNull { it.value }?.key.orEmpty()
    }

    private fun named(s: Step) = s.name.trim().ifEmpty { s.ref.trim() }
}
