package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Polyline
import kotlin.math.abs
import kotlin.math.cos

/** A box of latitude and longitude, degrees. */
data class GeoBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(p: LatLon) = p.lat in south..north && p.lon in west..east
    fun touches(o: GeoBox) = south <= o.north && north >= o.south && west <= o.east && east >= o.west

    companion object {
        fun around(p: LatLon, metres: Double): GeoBox {
            val dLat = metres / 110_540.0
            val dLon = metres / (111_320.0 * cos(Math.toRadians(p.lat)).coerceAtLeast(0.01))
            return GeoBox(p.lat - dLat, p.lon - dLon, p.lat + dLat, p.lon + dLon)
        }
    }
}

/** A row of `spot` (SEARCH_SPEC section 1). [dir]: where the traffic it concerns is heading, null when not known. [junction]: `j`, signals only, else 0. */
class SpotRow(val kind: Int, val pos: LatLon, val dir: Int?, val kmh: Int, val owner: Boolean, val junction: Int) {
    companion object {
        const val CAMERA = 1
        const val SIGNAL = 2
        const val BUMP = 3
    }
}

/** A row of `p`, kind 6. [cat]: `cat.key`, which is stable. [coarse]: `src` bit 4, a geocoder's pile: never a landmark. */
class PoiRow(val id: Long, val name: String, val alt: String?, val cat: String, val pos: LatLon, val rank: Int, val coarse: Boolean)

/** A row of `gate`. [sub]: 1 gate, 2 private or no entry, 3 checkpoint, 4 border post, 5 toll booth, 6 block or bollard. [road]: 0 motorway .. 8 service. */
class GateRow(val pos: LatLon, val sub: Int, val road: Int) {
    companion object {
        const val PRIVATE = 2
        const val CHECKPOINT = 3
        const val BORDER = 4
        const val TOLL = 5
        const val BLOCK = 6
        /** The best road through it is below trunk. */
        const val MAIN_ROAD_MAX = 1
    }
}

/** A row of `zone`. [kind]: 1 the Haram of Makkah, 2 of Madinah, 3 military, 4 private. [ring]: its outline, OSM's lines and no legal text. */
class ZoneRow(val id: Int, val kind: Int, val name: String?, val en: String?, val box: GeoBox, val ring: List<LatLon>) {
    val isHaram get() = kind == HARAM_MAKKAH || kind == HARAM_MADINAH

    fun contains(p: LatLon): Boolean {
        if (!box.contains(p) || ring.size < 3) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) && p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Inside and not near its edge, for a destination: the rings are simplified to about 24 points and grown outward, which leaves
     * thin wedges over what lies beside. King Abdulaziz Naval Base's takes in Jubail Center, a mall in town, 74 m from the edge. What
     * lies just inside a real fence has its gate on the road ([GateRow]). Not for the Haram, whose ring is kept finer.
     */
    fun holds(p: LatLon): Boolean {
        if (!contains(p)) return false
        var j = ring.size - 1
        for (i in ring.indices) {
            if (Geo.project(p, ring[j], ring[i]).second < EDGE_M) return false
            j = i
        }
        return true
    }

    companion object {
        private const val EDGE_M = 150.0
        const val HARAM_MAKKAH = 1
        const val HARAM_MADINAH = 2
        const val MILITARY = 3
        const val PRIVATE = 4
    }
}

/**
 * What guidance asks of the place data: rows in small boxes along a route, the search part's to answer from `places.db`
 * (`spot` and `p` have an index on `lat`, so a box is a `lat BETWEEN` with a `lon` filter). Called off the main thread, when a
 * route is taken. A call that throws counts as no rows ([Guidance.build] catches it): guidance goes on, only plainer.
 */
interface RouteWorld {
    fun spots(boxes: List<GeoBox>): List<SpotRow>
    /** POIs of these `cat.key`s with `rank` at least [minRank], coarse ones too: the caller decides. */
    fun pois(boxes: List<GeoBox>, cats: Set<String>, minRank: Int): List<PoiRow>
    fun gates(boxes: List<GeoBox>): List<GateRow>
    fun zones(boxes: List<GeoBox>): List<ZoneRow>
    /** The Arabic name of the road the index knows by this Latin name near here (a `p` row of kind 7, `alt` = [latin]), nearest within 15 km; null when none. */
    fun arabicRoadName(latin: String, near: LatLon): String?
}

/** No pack yet, or no search: directions without landmarks, nothing wrong. */
object NoWorld : RouteWorld {
    override fun spots(boxes: List<GeoBox>) = emptyList<SpotRow>()
    override fun pois(boxes: List<GeoBox>, cats: Set<String>, minRank: Int) = emptyList<PoiRow>()
    override fun gates(boxes: List<GeoBox>) = emptyList<GateRow>()
    override fun zones(boxes: List<GeoBox>) = emptyList<ZoneRow>()
    override fun arabicRoadName(latin: String, near: LatLon): String? = null
}

/**
 * A route as stretches of about 2 km, each with its box: so the world is asked by small boxes (a long trip's one box would be
 * half the Kingdom), and a row is measured only against the stretches near it.
 */
internal class Corridor(val line: Polyline) {
    private class Chunk(val from: Int, val to: Int, val box: GeoBox)

    /** A row's place on the line. [side]: +1 to the right of the way the route goes, -1 to the left. */
    class Hit(val along: Double, val offset: Double, val bearing: Double, val side: Int)

    private val chunks: List<Chunk> = run {
        val out = ArrayList<Chunk>()
        val pts = line.points
        var i = 0
        while (i < pts.size - 1) {
            var j = i + 1
            while (j < pts.size - 1 && line.cumulative[j] - line.cumulative[i] < CHUNK_M) j++
            var s = 90.0
            var n = -90.0
            var w = 180.0
            var e = -180.0
            for (k in i..j) {
                val p = pts[k]
                if (p.lat < s) s = p.lat
                if (p.lat > n) n = p.lat
                if (p.lon < w) w = p.lon
                if (p.lon > e) e = p.lon
            }
            out.add(Chunk(i, j, GeoBox(s, w, n, e)))
            i = j
        }
        out
    }

    fun boxes(margin: Double): List<GeoBox> = chunks.map { grown(it.box, margin) }

    private fun grown(b: GeoBox, margin: Double): GeoBox {
        val dLat = margin / 110_540.0
        val dLon = margin / (111_320.0 * cos(Math.toRadians(b.south)).coerceAtLeast(0.01))
        return GeoBox(b.south - dLat, b.west - dLon, b.north + dLat, b.east + dLon)
    }

    /** Where the line passes within [reach] of [p]: one hit per passage, so a road driven out and back gives two. */
    fun hits(p: LatLon, reach: Double): List<Hit> {
        val out = ArrayList<Hit>(2)
        val dLat = reach / 110_540.0
        val dLon = reach / (111_320.0 * cos(Math.toRadians(p.lat)).coerceAtLeast(0.01))
        for (c in chunks) {
            val b0 = c.box
            if (p.lat < b0.south - dLat || p.lat > b0.north + dLat || p.lon < b0.west - dLon || p.lon > b0.east + dLon) continue
            var best: Hit? = null
            for (k in c.from until c.to) {
                val a = line.points[k]
                val b = line.points[k + 1]
                val (t, d) = Geo.project(p, a, b)
                if (d > reach || (best != null && d >= best.offset)) continue
                val cross = (b.lon - a.lon) * (p.lat - a.lat) - (b.lat - a.lat) * (p.lon - a.lon)
                best = Hit(line.cumulative[k] + (line.cumulative[k + 1] - line.cumulative[k]) * t, d, Geo.bearing(a, b), if (cross > 0) -1 else 1)
            }
            val h = best ?: continue
            val last = out.lastOrNull()
            if (last != null && abs(last.along - h.along) < SAME_PASSAGE_M) {
                if (h.offset < last.offset) out[out.size - 1] = h
            } else out.add(h)
        }
        return out
    }

    private companion object {
        const val CHUNK_M = 2000.0
        const val SAME_PASSAGE_M = 60.0
    }
}
