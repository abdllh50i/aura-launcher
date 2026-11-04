package com.abdllh.aura.nav.route

import com.abdllh.aura.nav.LatLon
import java.util.Locale

/**
 * What is asked of Valhalla: tools/mapbuild/route-request.json, the request tested on 56 trips (tests/route_trips.py). Its
 * reasons are there and in trips.py: top_speed 160 changes no driving speed (at 140 the search left highway 40 at 24
 * interchanges) and must never be used to tune time ([EtaScale] does that, on the answer).
 *
 * [heading]: the car's, when known, with the tolerance the tests used. The last location has search_cutoff, so a place no road
 * reaches answers "no road" instead of a route ending far from it (Jana Island: 22 km off, with code Ok). [avoid]: rings for
 * exclude_polygons, each at most 100 points (device-config.json), [Via.stop] a stop on the way where the route splits, otherwise
 * the route only passes there. [startRoad]: which road the car is on, when the driver said ([RoadClasses]). [arabic] is for
 * reading the answer ([StepTune]), not part of the question. [avoidHaram]: the owner's switch: [RouteChain] reads the ring from
 * places.db and puts it in [avoid].
 */
open class RouteRequest(
    val from: LatLon,
    val to: LatLon,
    val heading: Double? = null,
    val via: List<Via> = emptyList(),
    val avoid: List<List<LatLon>> = emptyList(),
    val startRoad: RoadClasses? = null,
    val alternates: Int = 2,
    val arabic: Boolean = true,
    val avoidHaram: Boolean = false
) {
    class Via(val pos: LatLon, val stop: Boolean = false)

    /** Without these it is another trip: no other router can do them, and a route that ignores them is no answer. [startRoad] only helps with the first metres. */
    val needsValhalla: Boolean get() = via.isNotEmpty() || avoid.isNotEmpty()

    /** What Valhalla would refuse or quietly ignore in this request; null when there is nothing. */
    fun problem(): String? {
        for (ring in avoid) {
            if (ring.size < 3) return "a ring of ${ring.size} points is no ring"
            if (ring.size > MAX_RING) return "a ring of ${ring.size} points, Valhalla takes $MAX_RING"
        }
        return null
    }

    /** The same request with these rings. */
    open fun with(rings: List<List<LatLon>>): RouteRequest = RouteRequest(from, to, heading, via, rings, startRoad, alternates, arabic, avoidHaram)

    /**
     * The service-road switch, search_filter on the start: exclude_locations does not do it (tests/route_probes.py: 40 sites
     * between the two roads, the side road won 12 of them that way; with this filter 38, the main road 39). 91 of 1,138 frontage
     * pairs have two roads of one class, which no filter can tell apart.
     */
    class RoadClasses private constructor(val min: String?, val max: String?) {
        companion object {
            private val ORDER = listOf("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "service_other")

            /** Valhalla's name of a class, from OSM's too (a word it does not know would make the filter do nothing, with no error); null when there is none. */
            private fun known(c: String?): String? {
                val k = c?.removeSuffix("_link") ?: return null
                return when {
                    k in ORDER -> k
                    k == "service" || k == "track" -> "service_other"
                    k == "living_street" -> "residential"
                    else -> null
                }
            }

            /** The car is on the main road, of class [main]. */
            fun onMain(main: String) = RoadClasses(known(main), null)

            /** The car is on the side road beside a main road of class [main]: [side] when its class is known, else one class below the main road's. */
            fun onSide(main: String, side: String? = null): RoadClasses {
                val own = known(side)
                if (own != null) return RoadClasses(null, own)
                val m = known(main) ?: return RoadClasses(null, null)
                return RoadClasses(null, ORDER.getOrElse(ORDER.indexOf(m) + 1) { ORDER.last() })
            }
        }
    }

    open fun json(): String = buildString {
        append("{\"locations\":[")
        val start = StringBuilder("\"type\":\"break\"")
        if (heading != null) start.append(",\"heading\":").append(heading.toInt().coerceIn(0, 359)).append(",\"heading_tolerance\":45")
        startRoad?.takeIf { it.min != null || it.max != null }?.let { f ->
            start.append(",\"search_filter\":{")
            start.append(listOfNotNull(f.min?.let { "\"min_road_class\":\"$it\"" }, f.max?.let { "\"max_road_class\":\"$it\"" }).joinToString(","))
            start.append('}')
        }
        location(from, start)
        for (v in via) {
            append(',')
            location(v.pos, if (v.stop) "\"type\":\"break\"" else "\"type\":\"through\"")
        }
        append(',')
        location(to, "\"type\":\"break\",\"search_cutoff\":1000")
        append("],\"costing\":\"auto\",\"costing_options\":{\"auto\":{\"top_speed\":160,\"use_distance\":0.5,\"use_tolls\":0.5,\"use_tracks\":0.0}},")
        append("\"format\":\"osrm\",\"shape_format\":\"geojson\",\"alternates\":").append(alternates)
        append(",\"units\":\"kilometers\",\"language\":\"en-US\",\"turn_lanes\":true")
        if (avoid.isNotEmpty()) {
            append(",\"exclude_polygons\":[")
            append(avoid.joinToString(",") { ring -> ring.joinToString(",", "[", "]") { p -> "[${num(p.lon)},${num(p.lat)}]" } })
            append(']')
        }
        append('}')
    }

    private fun StringBuilder.location(p: LatLon, rest: CharSequence) {
        append("{\"lat\":").append(num(p.lat)).append(",\"lon\":").append(num(p.lon)).append(',').append(rest).append('}')
    }

    private fun num(v: Double) = String.format(Locale.ROOT, "%.6f", v)

    companion object {
        /** What Valhalla's exclude_polygons takes (a ring of 501 points: "Exceeded maximum number of vertices"). */
        const val MAX_RING = 100
    }
}

/** Does a line go through a ring? Pure: what is checked of a router that knows nothing of rings. */
internal object RingCheck {
    fun crosses(line: List<LatLon>, ring: List<LatLon>): Boolean {
        if (line.size < 2 || ring.size < 3) return false
        var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
        for (p in ring) { if (p.lat < s) s = p.lat; if (p.lat > n) n = p.lat; if (p.lon < w) w = p.lon; if (p.lon > e) e = p.lon }
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            if (minOf(a.lat, b.lat) > n || maxOf(a.lat, b.lat) < s || minOf(a.lon, b.lon) > e || maxOf(a.lon, b.lon) < w) continue
            if (inside(a, ring) || inside(b, ring)) return true
            var j = ring.size - 1
            for (k in ring.indices) {
                if (meet(a, b, ring[j], ring[k])) return true
                j = k
            }
        }
        return false
    }

    fun inside(p: LatLon, ring: List<LatLon>): Boolean {
        var hit = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) && p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon) hit = !hit
            j = i
        }
        return hit
    }

    private fun meet(a: LatLon, b: LatLon, c: LatLon, d: LatLon): Boolean {
        val rx = b.lon - a.lon; val ry = b.lat - a.lat
        val sx = d.lon - c.lon; val sy = d.lat - c.lat
        val cross = rx * sy - ry * sx
        if (cross == 0.0) return false
        val t = ((c.lon - a.lon) * sy - (c.lat - a.lat) * sx) / cross
        val u = ((c.lon - a.lon) * ry - (c.lat - a.lat) * rx) / cross
        return t in 0.0..1.0 && u in 0.0..1.0
    }
}
