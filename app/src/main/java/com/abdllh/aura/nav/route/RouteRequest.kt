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
 * reading the answer ([StepTune]), not part of the question.
 */
open class RouteRequest(
    val from: LatLon,
    val to: LatLon,
    val heading: Double? = null,
    val via: List<Via> = emptyList(),
    val avoid: List<List<LatLon>> = emptyList(),
    val startRoad: RoadClasses? = null,
    val alternates: Int = 2,
    val arabic: Boolean = true
) {
    class Via(val pos: LatLon, val stop: Boolean = false)

    /**
     * The service-road switch, search_filter on the start: exclude_locations does not do it (tests/route_probes.py: 40 sites
     * between the two roads, the side road won 12 of them that way; with this filter 38, the main road 39). 91 of 1,138 frontage
     * pairs have two roads of one class, which no filter can tell apart.
     */
    class RoadClasses private constructor(val min: String?, val max: String?) {
        companion object {
            private val ORDER = listOf("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "service_other")

            /** The car is on the main road, of class [main]. */
            fun onMain(main: String) = RoadClasses(main, null)

            /** The car is on the side road beside a main road of class [main]: [side] when its class is known, else one class below the main road's. */
            fun onSide(main: String, side: String? = null) = RoadClasses(null, side ?: ORDER.getOrElse(ORDER.indexOf(main) + 1) { ORDER.last() })
        }
    }

    open fun json(): String = buildString {
        append("{\"locations\":[")
        val start = StringBuilder("\"type\":\"break\"")
        if (heading != null) start.append(",\"heading\":").append(heading.toInt().coerceIn(0, 359)).append(",\"heading_tolerance\":45")
        startRoad?.let { f ->
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
}
