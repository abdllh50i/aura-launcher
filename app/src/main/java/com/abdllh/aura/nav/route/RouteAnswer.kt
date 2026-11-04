package com.abdllh.aura.nav.route

import android.content.Context
import com.abdllh.aura.R
import com.abdllh.aura.nav.Route

/**
 * What the routing chain answers: the routes, best first, the road they start with being what guidance drives ([route]); the
 * others are the alternates for a screen to offer. Or why not ([status]).
 */
internal class RouteAnswer(
    val status: Int,
    val message: String = "",
    /** Sorted by (scaled) time: Valhalla's first is not always the fastest (tests: 26.2 min first, an alternate of 24.9 behind it). */
    val routes: List<Route> = emptyList(),
    /** Valhalla's own time of each route, seconds, before [EtaScale]: to set against real drives. */
    val rawSeconds: DoubleArray = DoubleArray(0),
    /** How far from a road the destination lies, metres (Valhalla's waypoints); -1 when not known. */
    val endOffRoad: Double = -1.0,
    val stats: Stats = Stats()
) {
    /** Who answered: [VALHALLA], [BROUTER], [OSRM]. Only Valhalla honours via points, polygons and the road switch. */
    var engine = VALHALLA

    val route: Route? get() = routes.firstOrNull()

    /** For a screen: why there is no route, in the app's language. */
    fun userMessage(c: Context): String = c.getString(when (status) {
        NO_ROAD -> R.string.route_no_road
        NO_ROUTE -> R.string.route_no_route
        else -> R.string.route_failed
    })

    /** What the service measured. On the emulator and on the unit alike, read from the process; [pssKb] in debug builds only (a slow read). */
    class Stats(
        val routeMs: Long = 0,
        val parseMs: Long = 0,
        val answerBytes: Long = 0,
        val loadMs: Long = 0,
        val actorMs: Long = 0,
        val vmPeakKb: Long = 0,
        val vmHwmKb: Long = 0,
        val pssKb: Long = 0,
        val pid: Long = 0
    )

    companion object {
        const val OK = 0
        /** The destination (or the start) has no road near it: Valhalla's code 171. Believed: no other engine is asked for a place inside the Kingdom. */
        const val NO_ROAD = 1
        /** Roads at both ends, no way between them (or through what was to be avoided). */
        const val NO_ROUTE = 2
        /** The tar is missing, cut short or not what the code expects. */
        const val BAD_PACK = 3
        /** The engine said something else, or threw. */
        const val FAILED = 4
        /** Client side only: the process died while it worked. */
        const val DIED = 5
        const val TIMEOUT = 6
        /** A newer request took its place. */
        const val CANCELLED = 7
        /** [RouteGuard] does not let Valhalla run. */
        const val GUARDED = 8

        const val VALHALLA = "valhalla"
        const val BROUTER = "brouter"
        const val OSRM = "osrm"
    }
}
