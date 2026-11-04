package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Polyline

/** A made-up road going due east along the 27th parallel, a point every 100 m or so, and steps placed on it. */
internal object Kit {
    val line = Polyline((0..200).map { LatLon(27.0, 49.0 + it * 0.001) })

    /** A point [metres] along the road, [right] metres to its right (south, the road going east). */
    fun at(metres: Double, right: Double = 0.0): LatLon = Geo.offset(line.at(metres).first, 180.0, right)

    private fun nearestIndex(m: Double): Int {
        var best = 0
        for (i in line.cumulative.indices) if (kotlin.math.abs(line.cumulative[i] - m) < kotlin.math.abs(line.cumulative[best] - m)) best = i
        return best
    }

    fun crossing(metres: Double, classes: List<String> = emptyList()) =
        Crossing(intArrayOf(90, 180, 270), booleanArrayOf(true, true, false), 2, 0, line.points[nearestIndex(metres)], nearestIndex(metres), classes, emptyList(), false)

    /** A step at about [metres], on a point of the line. */
    fun step(metres: Double, type: String, modifier: String = "", name: String = "", ref: String = "", exit: Int = 0, destinations: String = "", exits: String = "", mode: String = "driving", rotary: String = "", duration: Double = 30.0, length: Double = 300.0, classes: List<String> = emptyList(), crossings: List<Crossing>? = null) =
        GuideStep(type, modifier, exit, name, ref, line.points[nearestIndex(metres)], length, duration, destinations, exits, rotary, mode, 90, 180, crossings ?: listOf(crossing(metres, classes)))

    /** A route of a depart at 0, [steps], and an arrive at the far end. */
    fun route(vararg steps: GuideStep): GuideRoute {
        val all = listOf(step(0.0, "depart")) + steps + step(line.length, "arrive")
        val r = GuideRoute(line, all, line.length, 600.0, setOf("SA"))
        GuideAnswer.prepare(r)
        return r
    }

    fun world(spots: List<SpotRow> = emptyList(), pois: List<PoiRow> = emptyList(), gates: List<GateRow> = emptyList(), zones: List<ZoneRow> = emptyList(), names: Map<String, String> = emptyMap()) =
        FakeWorld(spots, gates, pois, zones, names)

    fun signal(metres: Double, junction: Int, right: Double = 0.0, dir: Int? = 90) = SpotRow(SpotRow.SIGNAL, at(metres, right), dir, 0, false, junction)

    fun poi(metres: Double, right: Double, name: String, cat: String, rank: Int = 70, alt: String? = null, coarse: Boolean = false) =
        PoiRow(metres.toLong(), name, alt, cat, at(metres, right), rank, coarse)

    /** What the voice says, in Arabic. */
    fun said(g: Guidance, told: Int, along: Double, then: Boolean = false) = Speech.text(g.spoken(told, along, then), true)
}
