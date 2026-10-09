package com.abdllh.aura.nav

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 position. */
data class LatLon(val lat: Double, val lon: Double)

/** Spherical-earth helpers (good to a few metres over the distances navigation deals with). */
object Geo {
    private const val R = 6371008.8

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from a to b, degrees clockwise from north (0..360). */
    fun bearing(a: LatLon, b: LatLon): Double {
        val y = sin(rad(b.lon - a.lon)) * cos(rad(b.lat))
        val x = cos(rad(a.lat)) * sin(rad(b.lat)) - sin(rad(a.lat)) * cos(rad(b.lat)) * cos(rad(b.lon - a.lon))
        return (deg(atan2(y, x)) + 360.0) % 360.0
    }

    /** Point a fraction t of the way from a to b (linear; fine for short segments). */
    fun lerp(a: LatLon, b: LatLon, t: Double) = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** Shortest signed difference between two bearings, -180..180. */
    fun angleDiff(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return d
    }

    /**
     * Projects p onto segment a-b in a local flat frame.
     * Returns (fraction along the segment 0..1, distance from p to the segment in metres).
     */
    fun project(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val k = cos(rad(a.lat))
        val ax = 0.0
        val ay = 0.0
        val bx = rad(b.lon - a.lon) * k * R
        val by = rad(b.lat - a.lat) * R
        val px = rad(p.lon - a.lon) * k * R
        val py = rad(p.lat - a.lat) * R
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-9) 0.0 else ((px - ax) * dx + (py - ay) * dy) / len2
        val tc = t.coerceIn(0.0, 1.0)
        val qx = ax + dx * tc
        val qy = ay + dy * tc
        return tc to sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy))
    }
}

/** A polyline with cumulative distances, for "where along the route am I". */
class Polyline(val points: List<LatLon>) {
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.distance(points[i - 1], points[i])
    }
    val length: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative[cumulative.size - 1]

    /** Result of snapping a position to the line. */
    class Snap(val segment: Int, val along: Double, val offset: Double, val point: LatLon, val bearing: Double)

    /**
     * Snaps p to the line, searching segments [from - back, from + ahead] (cheap, and it cannot jump to a far-away
     * part of a route that passes near itself).
     */
    fun snap(p: LatLon, from: Int = 0, back: Int = 3, ahead: Int = 80): Snap? {
        if (points.size < 2) return null
        val lo = (from - back).coerceAtLeast(0)
        val hi = (from + ahead).coerceAtMost(points.size - 2)
        var best: Snap? = null
        for (i in lo..hi) {
            val a = points[i]
            val b = points[i + 1]
            val (t, d) = Geo.project(p, a, b)
            if (best == null || d < best.offset) {
                val seg = cumulative[i + 1] - cumulative[i]
                best = Snap(i, cumulative[i] + seg * t, d, Geo.lerp(a, b, t), Geo.bearing(a, b))
            }
        }
        return best
    }

    /** Position and direction at a distance along the line. */
    fun at(along: Double): Pair<LatLon, Double> {
        if (points.size < 2) return (points.firstOrNull() ?: LatLon(0.0, 0.0)) to 0.0
        val d = along.coerceIn(0.0, length)
        var i = cumulative.binarySearchFloor(d)
        if (i >= points.size - 1) i = points.size - 2
        val seg = cumulative[i + 1] - cumulative[i]
        val t = if (seg < 1e-6) 0.0 else (d - cumulative[i]) / seg
        return Geo.lerp(points[i], points[i + 1], t) to Geo.bearing(points[i], points[i + 1])
    }

    /** The part of the line from a distance onwards (for drawing the route still ahead). */
    fun tail(along: Double): List<LatLon> {
        if (points.size < 2) return points
        val d = along.coerceIn(0.0, length)
        val i = cumulative.binarySearchFloor(d).coerceAtMost(points.size - 2)
        val out = ArrayList<LatLon>(points.size - i + 1)
        out.add(at(d).first)
        for (k in i + 1 until points.size) out.add(points[k])
        return out
    }

    private fun DoubleArray.binarySearchFloor(v: Double): Int {
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (this[mid] <= v) lo = mid else hi = mid - 1
        }
        return lo
    }
}
