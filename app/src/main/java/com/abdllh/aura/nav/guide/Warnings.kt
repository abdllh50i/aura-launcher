package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.places.RoadAhead

/** A camera, a signal or a bump on the route, in the route's direction: [along] metres from the start. [kmh]: a camera's limit, or the limit of the road a signal or bump is on, 0 when not known. */
class AlongSpot(val kind: Int, val along: Double, val pos: LatLon, val kmh: Int, val junction: Int, val owner: Boolean)

/**
 * Puts spots on the route, by the one rule of what a route goes by ([RoadAhead.spotsAlong]: the search part's, measured on the file's own distances). RoadWatch's straight line and 55 degree cone fires for
 * a parallel road (`RoadWatch.kt:71-80`): here a spot counts only near the line and going the route's way, and its distance is along the road. One hit for a junction with signals on one pass (`spot.j`), one for
 * rows of a camera or a bump that stand for one spot, with the limit of whichever knows it.
 */
object AlongRoad {
    internal fun match(c: Corridor, rows: List<SpotRow>): List<AlongSpot> {
        val spots = rows.map { com.abdllh.aura.nav.places.SpotRow(it.kind, Math.round(it.pos.lat * 1e6).toInt(), Math.round(it.pos.lon * 1e6).toInt(), it.dir, it.kmh.takeIf { k -> k > 0 }, if (it.owner) 2 else 1, it.junction.takeIf { j -> j != 0 }) }
            .sortedBy { it.lat }
        return RoadAhead.spotsAlong(c.line, spots).hits.map { AlongSpot(it.kind, it.along, LatLon(it.lat, it.lon), it.cameraKmh ?: it.roadKmh ?: 0, it.junction ?: 0, it.owners) }
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
 * * Military and private zones: only when the route ends inside, or goes on small roads inside ([RoadAhead.zoneAlong], one rule with the search part's). A public road
 *   may cross such a polygon (Jubail-Dammam crosses the naval base's, and so does the main street of Jubail town) and that stays silent. Without [RoadFacts] the classes are not
 *   known, so only an end inside can be told.
 * * The Haram: OSM's line and no legal text, grown outward; Hajj closures are not modelled. See [Haram].
 */
object TripCheck {
    /** Near the line: a gate is a node on the road. */
    private const val GATE_REACH_M = 15.0
    /** Still in the yard the car starts from, leaving: no warning about getting in. */
    private const val START_M = 150.0
    private const val TRACK_MIN_M = 50.0
    private const val FAR_M = 300.0
    /**
     * The sea part of the King Fahd Causeway, by place: a road called "طريق جسر الملك فهد" runs on land for many kilometres and
     * crosses nothing (k6 is on it and never leaves Khobar). From 50.25 east, where only the causeway is: the corniche at the south end of Khobar runs along 50.22, in sight of its start.
     */
    private val CAUSEWAY_SEA = GeoBox(26.10, 50.25, 26.25, 50.50)
    /** The booths and the post come just before the sea: the causeway's own word covers them. */
    private const val CAUSEWAY_APPROACH_M = 5000.0

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
        // zones: the one rule ([RoadAhead.zoneAlong])
        val classOf: ((Int) -> Int)? = route.facts?.let { f ->
            { seg: Int -> f.at(line.cumulative[seg])?.roadClass?.takeIf { it.isNotEmpty() }?.let { RoadAhead.classNumber(it) } ?: -1 }
        }
        for (z in zones) {
            val flat = DoubleArray(z.ring.size * 2)
            for ((i, p) in z.ring.withIndex()) { flat[i * 2] = p.lon; flat[i * 2 + 1] = p.lat }
            val hit = RoadAhead.zoneAlong(z.id, z.kind, z.name, z.en, z.box.south, z.box.north, z.box.west, z.box.east, flat, line, classOf) ?: continue
            if (!hit.warn) continue
            val military = z.kind == ZoneRow.MILITARY
            when {
                z.isHaram && hit.endsInside -> out.add(TripWarning(TripWarning.Kind.HARAM_END, line.length, name = z.name))
                z.isHaram -> out.add(TripWarning(TripWarning.Kind.HARAM_ROUTE, hit.enterAlong, hit.insideMetres, z.name))
                hit.endsWell -> out.add(TripWarning(if (military) TripWarning.Kind.MILITARY_END else TripWarning.Kind.PRIVATE_END, line.length, name = z.name))
                else -> out.add(TripWarning(if (military) TripWarning.Kind.MILITARY_ROAD else TripWarning.Kind.PRIVATE_ROAD, hit.enterAlong, hit.minorMetres ?: 0.0, z.name))
            }
        }
        if (route.hasFerry) out.add(TripWarning(TripWarning.Kind.FERRY, route.steps.first { it.onFerry }.at))
        trackAtEnd(route)?.let { out.add(TripWarning(TripWarning.Kind.TRACK_END, line.length - it, it)) }
        if (endSnap > FAR_M) out.add(TripWarning(TripWarning.Kind.FAR_FROM_ROAD, line.length, endSnap))
        return out.sortedBy { it.kind.ordinal }
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
