package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.LatLon
import kotlin.math.abs

/** A camera, a signal or a bump on the route, in the route's direction: [along] metres from the start. [kmh]: a camera's limit, or the limit of the road a signal or bump is on, 0 when not known. */
class AlongSpot(val kind: Int, val along: Double, val pos: LatLon, val kmh: Int, val junction: Int, val owner: Boolean)

/**
 * Puts spots on the route. RoadWatch's straight line and 55 degree cone fires for a parallel road (`RoadWatch.kt:71-80`): here a
 * spot counts only near the line and going the route's way, and its distance is along the road. A `dir` is where the traffic goes (the
 * data's own fix: a camera's is its road's, when that road is one-way). No `dir` means either way, so it must be nearer: on
 * a divided road the other carriageway's camera has a `dir` and is left out, and a frontage road 20-40 m off stays out of a null one's reach.
 */
object AlongRoad {
    private const val REACH_M = 25.0
    private const val REACH_NO_DIR_M = 15.0
    private const val SAME_WAY_DEG = 50.0
    private const val NOT_AGAINST_DEG = 130.0

    internal fun match(c: Corridor, rows: List<SpotRow>): List<AlongSpot> {
        val out = ArrayList<AlongSpot>()
        for (s in rows) {
            for (h in c.hits(s.pos, if (s.dir != null) REACH_M else REACH_NO_DIR_M)) {
                // (a signal on the road across is the same junction's, and stops this road too; only the other carriageway's is not ours)
                val limit = if (s.kind == SpotRow.SIGNAL) NOT_AGAINST_DEG else SAME_WAY_DEG
                if (s.dir != null && abs(Geo.angleDiff(s.dir.toDouble(), h.bearing)) > limit) continue
                out.add(AlongSpot(s.kind, h.along, s.pos, s.kmh, s.junction, s.owner))
            }
        }
        out.sortBy { it.along }
        return out
    }
}

/** What to say and show for the next camera or bump. [metres]: along the road. [kmh]: the limit said with a camera, 0 when not known. */
class SpotAlert(val spot: AlongSpot, val metres: Double, val kmh: Int) {
    /** "ساهر بعد 500 متر، السرعة 80"; "مطب بعد 150 متر". */
    fun parts(): List<Part> {
        val out = ArrayList<Part>(6)
        out.add(Part.Clip(when (spot.kind) { SpotRow.CAMERA -> "camera"; SpotRow.SIGNAL -> "signal.ahead"; else -> "bump" }))
        if (metres >= 40) { out.add(Part.Clip("in")); out.add(Part.Clip(Words.distanceId(metres))) }
        if (spot.kind == SpotRow.CAMERA && kmh > 0) {
            out.add(Part.Clip("pause")); out.add(Part.Clip("limit")); out.add(Part.Clip("num.$kmh"))
        }
        return out
    }
}

/** Why a trip might not go as drawn. Said before it starts, not on the road. [along]: where, metres. [metres]: how much of it, when that says something, else 0. [name]: the zone's name, when it has one. */
class TripWarning(val kind: Kind, val along: Double, val metres: Double = 0.0, val name: String? = null) {
    enum class Kind(val word: String) {
        HARAM_END("warn.haram.end"), HARAM_ROUTE("warn.haram.route"), MILITARY_END("warn.military.end"), PRIVATE_END("warn.private.end"),
        MILITARY_ROAD("warn.military.road"), PRIVATE_ROAD("warn.private.road"), GUARD("warn.guard"), CAUSEWAY("warn.causeway"), BORDER("warn.border"),
        TOLL("warn.toll"), FERRY("warn.ferry"), TRACK_END("warn.track.end"), FAR_FROM_ROAD("warn.far")
    }

    fun parts(): List<Part> = listOf(Part.Clip(kind.word))
}

/**
 * The checks of a route before it starts, by SEARCH_SPEC section 1. No engine knows any of this: the route's own answer has no gate
 * flag and goes through guarded compounds as if open (gate-camp, gate-air).
 *
 * * A gate marked private or a checkpoint counts off the main roads only: on the motorway it is a checkpoint everyone drives through.
 * * Military and private zones: only when the route ends inside, or leaves the public roads ([PUBLIC_CLASSES]) inside. A public highway
 *   may cross such a polygon (Jubail-Dammam crosses the naval base's) and that stays silent. Without [RoadFacts] the classes are not
 *   known, so only an end inside can be told.
 * * The Haram: OSM's line and no legal text, grown outward; Hajj closures are not modelled. See [Haram].
 */
object TripCheck {
    /** Near the line: a gate is a node on the road. */
    private const val GATE_REACH_M = 15.0
    /** Still in the yard the car starts from, leaving: no warning about getting in. */
    private const val START_M = 150.0
    private const val SIDE_ROADS_MIN_M = 100.0
    private const val TRACK_MIN_M = 50.0
    private const val FAR_M = 300.0
    private const val SAMPLE_M = 100.0
    /**
     * The sea part of the King Fahd Causeway, by place: a road called "طريق جسر الملك فهد" runs on land for many kilometres and
     * crosses nothing (k6 is on it and never leaves Khobar).
     */
    private val CAUSEWAY_SEA = GeoBox(26.10, 50.22, 26.25, 50.50)
    /** The booths and the post come just before the sea: the causeway's own word covers them. */
    private const val CAUSEWAY_APPROACH_M = 2000.0
    /**
     * Roads a car may pass a zone on. SEARCH_SPEC says motorway and trunk; primary is added here, or the spec's own example would
     * warn: Jubail-Dammam runs 93 points of its line inside the naval base's ring on King Abdul Aziz street, primary, the main
     * street of Jubail town. A route into the air base (gate-air) comes in on tertiary and residential.
     */
    private val PUBLIC_CLASSES = setOf("motorway", "trunk", "primary")

    internal fun check(route: GuideRoute, c: Corridor, gates: List<GateRow>, zones: List<ZoneRow>, endSnap: Double): List<TripWarning> {
        val out = ArrayList<TripWarning>()
        // the causeway first: it says toll and passports, so its own booths and border post need no word of their own
        val onCauseway = route.line.points.indexOfFirst { CAUSEWAY_SEA.contains(it) }
        if (onCauseway >= 0) out.add(TripWarning(TripWarning.Kind.CAUSEWAY, route.line.cumulative[onCauseway]))
        val fromCauseway = if (onCauseway >= 0) route.line.cumulative[onCauseway] - CAUSEWAY_APPROACH_M else Double.MAX_VALUE
        fun once(k: TripWarning.Kind, along: Double) { if (out.none { it.kind == k }) out.add(TripWarning(k, along)) }
        // gates, in order along the route
        val onLine = gates.flatMap { g -> c.hits(g.pos, GATE_REACH_M).map { it.along to g } }.sortedBy { it.first }
        for ((along, g) in onLine) when {
            g.sub == GateRow.BORDER -> if (along < fromCauseway) once(TripWarning.Kind.BORDER, along)
            g.sub == GateRow.TOLL -> if (along < fromCauseway) once(TripWarning.Kind.TOLL, along)
            along < START_M -> Unit
            (g.sub == GateRow.PRIVATE || g.sub == GateRow.CHECKPOINT) && g.road > GateRow.MAIN_ROAD_MAX -> once(TripWarning.Kind.GUARD, along)
        }
        // tolls the answer knows of itself
        val line = route.line
        val tollAt = route.steps.flatMap { it.crossings }.firstOrNull { "toll" in it.classes || it.tollBooth }?.lineIndex
        if (tollAt != null && tollAt in line.cumulative.indices && line.cumulative[tollAt] < fromCauseway) once(TripWarning.Kind.TOLL, line.cumulative[tollAt])
        route.facts?.stretches?.firstOrNull { it.toll }?.let { if (it.from < fromCauseway) once(TripWarning.Kind.TOLL, it.from) }
        // zones
        val end = line.points.lastOrNull()
        val start = line.points.firstOrNull()
        if (end != null && start != null) for (z in zones) {
            if (z.isHaram) {
                if (z.contains(end)) out.add(TripWarning(TripWarning.Kind.HARAM_END, line.length, name = z.name))
                else entered(route, z)?.let { (at, inside) -> out.add(TripWarning(TripWarning.Kind.HARAM_ROUTE, at, inside, z.name)) }
                continue
            }
            val military = z.kind == ZoneRow.MILITARY
            if (z.holds(end)) {
                out.add(TripWarning(if (military) TripWarning.Kind.MILITARY_END else TripWarning.Kind.PRIVATE_END, line.length, name = z.name))
                continue
            }
            if (z.contains(start)) continue // (leaving it)
            sideRoadsInside(route, z)?.let { (at, inside) ->
                out.add(TripWarning(if (military) TripWarning.Kind.MILITARY_ROAD else TripWarning.Kind.PRIVATE_ROAD, at, inside, z.name))
            }
        }
        if (route.hasFerry) out.add(TripWarning(TripWarning.Kind.FERRY, route.steps.first { it.onFerry }.at))
        trackAtEnd(route)?.let { out.add(TripWarning(TripWarning.Kind.TRACK_END, line.length - it, it)) }
        if (endSnap > FAR_M) out.add(TripWarning(TripWarning.Kind.FAR_FROM_ROAD, line.length, endSnap))
        return out.sortedBy { it.kind.ordinal }
    }

    /** Where the route first enters [z], and how much of it runs inside; null when it never does. */
    private fun entered(route: GuideRoute, z: ZoneRow): Pair<Double, Double>? {
        var first = -1.0
        var inside = 0.0
        walk(route, z) { d, len ->
            if (first < 0) first = d
            inside += len
        }
        return if (first < 0) null else first to inside
    }

    /** The first place inside [z] on a road below [PUBLIC_CLASSES], and how much of the route is so; null when under [SIDE_ROADS_MIN_M]. */
    private fun sideRoadsInside(route: GuideRoute, z: ZoneRow): Pair<Double, Double>? {
        val facts = route.facts ?: return null
        var first = -1.0
        var inside = 0.0
        walk(route, z) { d, len ->
            val s = facts.at(d)
            if (s == null || s.roadClass !in PUBLIC_CLASSES) {
                if (first < 0) first = d
                inside += len
            }
        }
        return if (first < 0 || inside < SIDE_ROADS_MIN_M) null else first to inside
    }

    /** Calls [inside] (where, how long) for each piece of the line, at most [SAMPLE_M], that is inside [z]; a long trip's stretches far from the zone are not walked. */
    private inline fun walk(route: GuideRoute, z: ZoneRow, inside: (Double, Double) -> Unit) {
        val line = route.line
        val pts = line.points
        var i = 0
        while (i < pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val segBox = GeoBox(minOf(a.lat, b.lat), minOf(a.lon, b.lon), maxOf(a.lat, b.lat), maxOf(a.lon, b.lon))
            if (segBox.touches(z.box)) {
                var d = line.cumulative[i]
                val end = line.cumulative[i + 1]
                while (d < end) {
                    if (z.contains(line.at(d).first)) inside(d, minOf(SAMPLE_M, end - d))
                    d += SAMPLE_M
                }
            }
            i++
        }
    }

    /** Metres of dirt track the route ends on, null when under [TRACK_MIN_M]: "ترابي" only when OSM says so. */
    private fun trackAtEnd(route: GuideRoute): Double? {
        val facts = route.facts ?: return null
        var m = 0.0
        for (s in facts.stretches.asReversed()) {
            if (s.use != "track" && s.roadClass != "track") break
            m += s.to - s.from
        }
        return m.takeIf { it >= TRACK_MIN_M }
    }
}

/**
 * The rule of the Haram when the driver asks to go round it (`avoid_haram` in route-request.json: `exclude_polygons` with the zone's ring
 * of kind 1). A destination inside then answers NoRoute, and so does a start inside: so the zone he is going to or coming from is
 * not excluded, and he is told. Madinah's relation is only the mosque's 0.44 km2, so kind 2 is said, not excluded.
 */
object Haram {
    class Plan(val exclude: List<List<LatLon>>, val destinationInside: ZoneRow?, val startInside: ZoneRow?)

    /** [zones]: at least the Haram rows around the trip; [avoid]: his setting. */
    fun plan(start: LatLon?, destination: LatLon, avoid: Boolean, zones: List<ZoneRow>): Plan {
        val haram = zones.filter { it.isHaram }
        val destIn = haram.firstOrNull { it.contains(destination) }
        val startIn = start?.let { s -> haram.firstOrNull { it.contains(s) } }
        val ex = if (!avoid) emptyList() else haram.filter { it.kind == ZoneRow.HARAM_MAKKAH && it !== destIn && it !== startIn }.map { it.ring }
        return Plan(ex, destIn, startIn)
    }
}
