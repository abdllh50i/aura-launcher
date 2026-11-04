package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.Lane
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Polyline

/**
 * One junction the route passes, as the answer's `intersections` has it. [bearings] are all its arms, each pointing away
 * from it; [into] the arm the car comes by, -1 at the start; [out] the one it leaves by, -1 at the end; [entry]: a car
 * may go down that arm. [lineIndex]: where it is on the route's line.
 */
class Crossing(
    val bearings: IntArray,
    val entry: BooleanArray,
    val into: Int,
    val out: Int,
    val pos: LatLon,
    val lineIndex: Int,
    val classes: List<String>,
    val lanes: List<Lane>,
    val tollBooth: Boolean
)

/** One step of the answer, with what [com.abdllh.aura.nav.Step] does not keep: the raw lists of names, the mode, the crossings. */
class GuideStep(
    val type: String,
    val modifier: String,
    val exit: Int,
    val name: String,
    val ref: String,
    val pos: LatLon,
    val distance: Double,
    val duration: Double,
    val destinations: String,
    val exits: String,
    val rotaryName: String,
    val mode: String,
    val bearingBefore: Int,
    val bearingAfter: Int,
    val crossings: List<Crossing>
) {
    /** Where it is along the line, metres. */
    var at = 0.0
    /** A roundabout: metres to the exit taken; else 0. */
    var span = 0.0
    /** A roundabout: measured from the road in to the road out, degrees, + right; NaN when not. */
    var angle = Double.NaN
    /** Nothing for the driver: the street only changes its name, or the car leaves the roundabout it was told about. */
    var silent = false

    val entersRing get() = type == "roundabout" || type == "rotary" || type == "roundabout turn"
    val leavesRing get() = type.startsWith("exit ")
    val onFerry get() = mode == "ferry"
}

/** One route of an answer. [countries]: ISO codes of the countries it is in, from the answer's `admins`. */
class GuideRoute(val line: Polyline, val steps: List<GuideStep>, val distance: Double, val duration: Double, val countries: Set<String>) {
    /** `trace_attributes` over this very line, when the route side asked for it ([RoadFacts.parse]); limits and flyovers need it. */
    var facts: RoadFacts? = null
    val hasFerry get() = steps.any { it.onFerry }

    /** Where along the line the answer's own class changes, and whether it says motorway from there on. */
    private val motorwayMarks: Pair<DoubleArray, BooleanArray> by lazy {
        val at = ArrayList<Double>()
        val on = ArrayList<Boolean>()
        for (s in steps) for (c in s.crossings) {
            val here = line.cumulative.getOrNull(c.lineIndex) ?: continue
            val m = "motorway" in c.classes
            if (on.lastOrNull() != m) { at.add(here); on.add(m) }
        }
        at.toDoubleArray() to on.toBooleanArray()
    }

    /** The answer says motorway here (it has no other class of road: for trunk and below, [facts]). */
    fun motorwayAt(along: Double): Boolean {
        val (at, on) = motorwayMarks
        var lo = 0
        var hi = at.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (at[mid] <= along) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best >= 0 && on[best]
    }
}

class Waypoint(val pos: LatLon, val snapMetres: Double)

/**
 * A whole answer of the engine, `format: osrm`. Parsing here, not in [com.abdllh.aura.nav.Router]: it is pure, so what the
 * tests read is what runs, and it keeps only what guidance needs. An unknown shape gives null, never a crash: the caller
 * then has the old path (`Router.parse`, [com.abdllh.aura.nav.Instructions]).
 */
class GuideAnswer(val routes: List<GuideRoute>, val warnings: List<Int>, val waypoints: List<Waypoint>) {
    /**
     * Valhalla's 401, "Routing failed on first pass, retrying with relaxed restrictions": 2 of the 56 test trips, whose routes
     * agree with OSRM's where the two overlap. It means a second search, not a bad route: log it, say nothing.
     */
    val relaxed get() = RELAXED_WARNING in warnings

    /** How far the destination lies from the road the route ends on, metres. */
    val endSnapMetres get() = waypoints.lastOrNull()?.snapMetres ?: 0.0

    companion object {
        const val RELAXED_WARNING = 401

        /** Keys no guidance reads; a step's own `geometry` too (the route's whole line is there, one level up). */
        private val UNREAD = setOf("weight", "weight_name", "turn_weight", "turn_duration", "admin_index", "instruction", "driving_side", "via_waypoints", "summary", "voiceInstructions", "bannerInstructions")
        /** How deep a step's keys are: the answer's own 1, a route's 3, a leg's 5, a step's 7 ([MiniJson.parse] counts arrays too). */
        private const val STEP_DEPTH = 7

        fun parse(json: String): GuideAnswer? = try {
            of(MiniJson.parse(json) { k, depth -> k in UNREAD || (k == "geometry" && depth >= STEP_DEPTH) || (k == "duration" && depth > STEP_DEPTH) })
        } catch (_: Throwable) {
            null
        }

        internal fun of(root: Any?): GuideAnswer? {
            if (root.str("code") != "Ok") return null
            val routes = root.list("routes").mapNotNull { route(it) }
            if (routes.isEmpty()) return null
            return GuideAnswer(routes, root.list("warnings").mapNotNull { it.num("code")?.toInt() },
                root.list("waypoints").map { Waypoint(pos(it.list("location")) ?: LatLon(0.0, 0.0), it.num("distance") ?: 0.0) })
        }

        private fun pos(c: List<*>): LatLon? {
            val lon = c.getOrNull(0) as? Double ?: return null
            val lat = c.getOrNull(1) as? Double ?: return null
            return LatLon(lat, lon)
        }

        private fun route(r: Any?): GuideRoute? {
            // every point stays, also two on one spot: the crossings' indexes are into this very list
            val pts = r.obj("geometry").list("coordinates").mapNotNull { c -> (c as? List<*>)?.let { pos(it) } }
            val steps = ArrayList<GuideStep>()
            val countries = HashSet<String>()
            for (leg in r.list("legs")) {
                leg.list("admins").forEach { a -> a.str("iso_3166_1").takeIf { it.isNotEmpty() }?.let { countries.add(it) } }
                for (s in leg.list("steps")) steps.add(step(s))
            }
            if (steps.isEmpty()) return null
            val route = GuideRoute(Polyline(pts), steps, r.num("distance") ?: 0.0, r.num("duration") ?: 0.0, countries)
            prepare(route)
            return route
        }

        private fun step(s: Any?): GuideStep {
            val m = s.obj("maneuver")
            return GuideStep(m.str("type"), m.str("modifier"), m.num("exit")?.toInt() ?: 0, s.str("name"), s.str("ref"),
                pos(m.list("location")) ?: LatLon(0.0, 0.0), s.num("distance") ?: 0.0, s.num("duration") ?: 0.0,
                s.str("destinations"), s.str("exits"), s.str("rotary_name"), s.str("mode").ifEmpty { "driving" },
                m.num("bearing_before")?.toInt() ?: 0, m.num("bearing_after")?.toInt() ?: 0, s.list("intersections").map { crossing(it) })
        }

        private fun crossing(c: Any?): Crossing {
            val b = c.list("bearings")
            val e = c.list("entry")
            return Crossing(IntArray(b.size) { (b[it] as? Double)?.toInt() ?: 0 }, BooleanArray(e.size) { e[it] == true },
                c.num("in")?.toInt() ?: -1, c.num("out")?.toInt() ?: -1, pos(c.list("location")) ?: LatLon(0.0, 0.0),
                c.num("geometry_index")?.toInt() ?: -1, c.list("classes").mapNotNull { it as? String },
                c.list("lanes").map { l ->
                    Lane(l.list("indications").mapNotNull { it as? String }, l.bool("valid"), l.str("valid_indication"))
                }, c.obj("toll_collection") != null)
        }

        /** Places the steps on the line and says which are told, and how each roundabout is driven. */
        internal fun prepare(route: GuideRoute) {
            val line = route.line
            val steps = route.steps
            var run = 0.0
            var last = 0.0
            for (s in steps) {
                // (by the running distance when the index is missing or goes back, as a second leg's might)
                val index = s.crossings.firstOrNull()?.lineIndex ?: -1
                s.at = line.cumulative.getOrNull(index)?.takeIf { it >= last } ?: run
                last = s.at
                run = s.at + s.distance
            }
            for ((i, s) in steps.withIndex()) {
                val prev = steps.getOrNull(i - 1)
                val next = steps.getOrNull(i + 1)
                val straight = s.modifier.isEmpty() || s.modifier == "straight"
                val laneHint = lanesOf(s).isNotEmpty()
                val modeChange = prev != null && prev.mode != s.mode
                s.silent = !modeChange && ((s.type in QUIET_TYPES && straight && !laneHint) || (s.leavesRing && prev != null && prev.entersRing))
                if (!s.entersRing) continue
                val exitAt = if (next != null && next.leavesRing) next.at else s.at + s.distance
                s.span = (exitAt - s.at).coerceAtLeast(0.0)
                if (line.points.size >= 2) s.angle = turned(line, s.at, exitAt)
            }
        }

        /**
         * How far the car turns between the road in and the road out, on the line. The road's flare at a splitter island (up
         * to 35 degrees within the last metres) would count as turning: so from 60 to 15 m before, to 15 to 60 m after.
         */
        internal fun turned(line: Polyline, from: Double, to: Double): Double {
            val inBearing = Geo.bearing(line.at(from - 60).first, line.at(from - 15).first)
            val outBearing = Geo.bearing(line.at(to + 15).first, line.at(to + 60).first)
            return Geo.angleDiff(inBearing, outBearing)
        }

        /** The lanes to keep to at the step's manoeuvre, when the data has them and they matter: only then ([Crossing.lanes]). */
        fun lanesOf(s: GuideStep): List<Lane> {
            val l = s.crossings.firstOrNull()?.lanes.orEmpty()
            return if (l.size >= 2 && l.any { !it.valid } && l.any { it.valid }) l else emptyList()
        }

        private val QUIET_TYPES = setOf("new name", "continue", "notification", "use lane")
    }
}
