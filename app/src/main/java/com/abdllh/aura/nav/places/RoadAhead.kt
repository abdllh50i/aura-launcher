package com.abdllh.aura.nav.places

import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Polyline
import kotlin.math.abs
import kotlin.math.cos

/** Rows of tables spot, gate and zone (SEARCH_SPEC.md section 1). */
internal class SpotRow(@JvmField val kind: Int, @JvmField val lat: Int, @JvmField val lon: Int, @JvmField val dir: Int?, @JvmField val kmh: Int?, @JvmField val src: Int, @JvmField val j: Int?)
internal class GateRow(@JvmField val lat: Int, @JvmField val lon: Int, @JvmField val sub: Int, @JvmField val road: Int)
internal class ZoneRow(@JvmField val id: Int, @JvmField val kind: Int, @JvmField val name: String?, @JvmField val en: String?, @JvmField val s: Double, @JvmField val n: Double, @JvmField val w: Double, @JvmField val e: Double, @JvmField val ring: String)

/**
 * A camera, a junction with signals or a bump a route goes by. A signal here stands for its whole junction: "الإشارة الثانية" counts these, not the nodes of table spot.
 * [cameraKmh] is a camera's own limit; on a signal or a bump the file only knows the limit of the road it is on, [roadKmh], which is not the spot's own.
 */
class SpotHit internal constructor(
    val kind: Int, val lat: Double, val lon: Double,
    /** The heading of the traffic it concerns, null when unknown (a camera on a two-way road: either way). */
    val dir: Int?,
    val cameraKmh: Int?,
    val roadKmh: Int?,
    /** The owner's own additions, as they were when the file was built (his live file still goes on top: MapExtras). */
    val owners: Boolean,
    /** Signals: the junction's number, spot.j. */
    val junction: Int?,
    /** Metres along the line. */
    val along: Double,
    /** Metres off the line. */
    val offset: Double
) {
    companion object {
        const val CAMERA = 1
        const val SIGNAL = 2
        const val BUMP = 3
    }
}

class RouteSpots internal constructor(val hits: List<SpotHit>) {
    val cameras: List<SpotHit> get() = hits.filter { it.kind == SpotHit.CAMERA }
    val junctions: List<SpotHit> get() = hits.filter { it.kind == SpotHit.SIGNAL }
    val bumps: List<SpotHit> get() = hits.filter { it.kind == SpotHit.BUMP }

    /** The first of a kind from a distance along the line. */
    fun next(kind: Int, from: Double): SpotHit? = hits.firstOrNull { it.kind == kind && it.along >= from }

    /** How many junctions with signals between two distances along the line. Junctions, never signal nodes. */
    fun junctionsBetween(from: Double, to: Double): Int = hits.count { it.kind == SpotHit.SIGNAL && it.along > from && it.along <= to }

    /** The number of the junction with signals a turn is at, counted from [from]: 2 is "at the second signal". 0: the turn is at none. A junction is met a little before or after the step's own point. */
    fun signalNumberAt(from: Double, turnAlong: Double, slack: Double = 60.0): Int {
        var n = 0
        var at = 0
        for (h in hits) {
            if (h.kind != SpotHit.SIGNAL || h.along <= from) continue
            if (h.along > turnAlong + slack) break
            n++
            if (h.along >= turnAlong - slack) at = n
        }
        return at
    }
}

/** A node that can stop a car, on the route. sub: 1 gate 2 private or no entry 3 checkpoint 4 border post 5 toll booth 6 block or bollard. road: 0 motorway .. 8 service. */
class GateHit internal constructor(val lat: Double, val lon: Double, val sub: Int, val road: Int, val along: Double, val offset: Double) {
    /** "A route through sub 2 or 3 off the main roads may end at a guard: warn before it starts." */
    val warn: Boolean get() = RoadAhead.warnsGate(sub, road)
}

/** An area the route goes through. kind: 1 the Haram of Makkah 2 of Madinah 3 military 4 private. */
class ZoneHit internal constructor(
    val id: Int, val kind: Int, val name: String?, val en: String?,
    /** First metres along the line inside it, 0 for a route that starts inside; the last. */
    val enterAlong: Double,
    val lastAlong: Double,
    val insideMetres: Double,
    val startsInside: Boolean,
    val endsInside: Boolean,
    /** Came in from outside (a route that only drives out of it was already in). */
    val entered: Boolean,
    /** Metres inside on anything but a motorway or trunk, after coming in from outside; null when the road classes were not given. The spec's words, kept for whoever wants to judge by them. */
    val offMainMetres: Double?,
    /** Metres inside on a tertiary road or less, after coming in from outside; null when the road classes were not given. What [warn] goes by. */
    val minorMetres: Double?,
    /**
     * Haram: any route that comes in, not one only driving out or an errand inside. Military and private: a route that ends well inside, or goes on a tertiary road or less inside for [RoadAhead.OFF_MAIN_M] or more. Without classes only the first is known.
     * The spec says "leaves the motorway and trunk roads", and gives Jubail-Dammam as the public highway that must not warn: but that road is OSM's primary for 9 km in the
     * naval base's ring, and the rings are coarse (519 holds 1,289 POIs of Khobar and Dhahran). Secondary roads are public too: King Abdul Aziz street and Madeena Street in Jubail town are that, in the same ring.
     * Over the 53 kept test trips with road classes the spec's words warn 11 times, 1 of them a guarded place (gate-air); a line at secondary 7 times; this rule 2 times (gate-air, and ithra, which ends inside),
     * and a guard that OSM knows is told by table gate anyway. The one rule: [com.abdllh.aura.nav.guide.TripCheck] asks here too.
     */
    val warn: Boolean,
    /** Ends inside and not near the ring's edge ([RoadAhead.EDGE_M]). */
    val endsWell: Boolean
)

class RouteWarnings internal constructor(val gates: List<GateHit>, val zones: List<ZoneHit>) {
    val warnGates: List<GateHit> get() = gates.filter { it.warn }
    val warnZones: List<ZoneHit> get() = zones.filter { it.warn }
    val warn: Boolean get() = gates.any { it.warn } || zones.any { it.warn }
}

/** A zone's ring. [json] as stored, `[[lon,lat],...]`, ready for exclude_polygons. OSM's lines, not a legal text. */
class ZoneRing internal constructor(val id: Int, val kind: Int, val name: String?, val en: String?, val json: String, private val flat: DoubleArray) {
    val points: Int get() = flat.size / 2
    fun contains(lat: Double, lon: Double): Boolean = RoadAhead.inside(lat, lon, flat)
    val ring: List<LatLon> get() = List(points) { LatLon(flat[it * 2 + 1], flat[it * 2]) }
}

/** A place of a kind on the way. [along]: metres along the line. [offset]: metres off it. [onRight]: on the driver's right, where one gets to it without crossing the road. */
class RoutePlace internal constructor(val place: PlaceHit, val along: Double, val offset: Double, val onRight: Boolean) {
    internal class On(@JvmField val along: Double, @JvmField val offset: Double, @JvmField val onRight: Boolean)
    internal class Found(@JvmField val row: Row, @JvmField val on: On)
}

/**
 * What a route's line meets in the file: by plain geometry, no Android, so src/test can run it. Valhalla's line follows OSM's ways, so what stands on a way is on the line to within a few
 * metres; a camera stands beside its road (spot_check: up to 25 m, which is also how far its road is looked for at build).
 */
internal object RoadAhead {
    const val CAMERA_REACH_M = 30.0
    /** Signals and bumps measured under 5 m from their road; a carriageway beside it is 10 m and more away. */
    const val SIGNAL_REACH_M = 15.0
    const val BUMP_REACH_M = 10.0
    const val GATE_REACH_M = 15.0
    /** The same way as the traffic it concerns. */
    const val SAME_WAY_DEG = 60.0
    /** A signal's node counts for its junction unless it is for the traffic coming the other way: a node on the crossing road is the same junction. */
    const val AGAINST_DEG = 135.0
    /** Nodes of one junction (within 60 m of the next, so it can take a few) on one pass. */
    const val ONE_JUNCTION_M = 300.0
    /** Two spots of one kind so close along the road are one (Pairs within 15 m exist in the file, of cameras 9): one warning. */
    const val ONE_SPOT_M = 25.0
    /** Under this inside a zone on small roads is a slip road's end or a parking lane, not a route that left the big ones. */
    const val OFF_MAIN_M = 100.0
    /**
     * Inside and this near the ring's edge is not said to be inside, for a destination: the rings are simplified to about 24 points and grown outward, which leaves thin wedges over what lies beside (Jubail Center, a mall in
     * town, is 74 m inside the naval base's). What lies just inside a real fence has its gate on the road (table gate). Not for the Haram, whose ring is kept finer.
     */
    const val EDGE_M = 150.0
    /** From this class of `gate.road` on (tertiary) a stretch inside a military or private zone counts as leaving the roads the public drives through. */
    const val MINOR_CLASS = 4

    private const val LAT_M = 110540.0
    private const val LON_M = 111320.0
    private const val DEG = Math.PI / 180.0
    private const val WINDOW_SEGMENTS = 48
    private const val WINDOW_M = 3000.0

    fun warnsGate(sub: Int, road: Int) = (sub == 2 || sub == 3) && road >= 2

    /** Valhalla's road_class (trace_attributes) as the number of `gate.road`; the service kinds are 8. */
    fun classNumber(roadClass: String): Int = when (roadClass) {
        "motorway" -> 0
        "trunk" -> 1
        "primary" -> 2
        "secondary" -> 3
        "tertiary" -> 4
        "unclassified" -> 5
        "residential" -> 6
        "living_street" -> 7
        else -> 8
    }

    /** South and north of a line, a little wider, in 1e-6 degrees. */
    fun latBand(line: Polyline): IntArray? {
        if (line.points.size < 2) return null
        val b = box(line)
        return intArrayOf(((b[0] - 0.002) * 1e6).toInt(), ((b[1] + 0.002) * 1e6).toInt())
    }

    /** South, north, west, east of a line. */
    fun box(line: Polyline): DoubleArray {
        var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
        for (p in line.points) {
            if (p.lat < s) s = p.lat
            if (p.lat > n) n = p.lat
            if (p.lon < w) w = p.lon
            if (p.lon > e) e = p.lon
        }
        return doubleArrayOf(s, n, w, e)
    }

    /** One time the line goes by a point of a list: [index] into the list. */
    class Pass(@JvmField val index: Int, @JvmField val along: Double, @JvmField val offset: Double, @JvmField val bearing: Double)

    /**
     * Every time [line] goes by one of the points, in order along the line: [lats] sorted, in 1e-6 degrees, and [accept] for each stretch it comes near (its offset and bearing).
     * Walks the line in short windows and looks only at the points in each window's box, so a long route costs little more than a short one. Stretches of one pass
     * (no more than 150 m apart along the line) come back as one, the nearest.
     */
    fun passes(line: Polyline, lats: IntArray, lons: IntArray, reach: Double, accept: (index: Int, offset: Double, bearing: Double) -> Boolean): List<Pass> {
        val pts = line.points
        if (pts.size < 2 || lats.isEmpty()) return emptyList()
        val raw = HashMap<Int, ArrayList<Pass>>()
        var i = 0
        while (i < pts.size - 1) {
            var j = i
            var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
            while (j < pts.size - 1 && j - i < WINDOW_SEGMENTS && line.cumulative[j] - line.cumulative[i] < WINDOW_M) {
                for (p in arrayOf(pts[j], pts[j + 1])) {
                    if (p.lat < s) s = p.lat
                    if (p.lat > n) n = p.lat
                    if (p.lon < w) w = p.lon
                    if (p.lon > e) e = p.lon
                }
                j++
            }
            val padLa = reach / LAT_M
            val padLo = reach / (LON_M * cos(s * DEG))
            var k = lowerBound(lats, ((s - padLa) * 1e6).toInt())
            val top = ((n + padLa) * 1e6).toInt() + 1
            val west = ((w - padLo) * 1e6).toInt() - 1
            val east = ((e + padLo) * 1e6).toInt() + 1
            while (k < lats.size && lats[k] <= top) {
                if (lons[k] in west..east) {
                    val p = LatLon(lats[k] / 1e6, lons[k] / 1e6)
                    for (seg in i until j) {
                        val (t, d) = Geo.project(p, pts[seg], pts[seg + 1])
                        if (d > reach) continue
                        val bearing = Geo.bearing(pts[seg], pts[seg + 1])
                        if (accept(k, d, bearing)) {
                            val along = line.cumulative[seg] + (line.cumulative[seg + 1] - line.cumulative[seg]) * t
                            raw.getOrPut(k) { ArrayList(2) }.add(Pass(k, along, d, bearing))
                        }
                    }
                }
                k++
            }
            i = j
        }
        val out = ArrayList<Pass>()
        for (list in raw.values) {
            var best = list[0]
            var last = list[0].along
            for (x in 1 until list.size) {
                val m = list[x]
                if (m.along - last > 150.0) {
                    out.add(best)
                    best = m
                } else if (m.offset < best.offset) best = m
                last = m.along
            }
            out.add(best)
        }
        out.sortWith(compareBy({ it.along }, { it.index }))
        return out
    }

    private fun lowerBound(a: IntArray, v: Int): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Is this stretch going the spot's way? Null is either way. */
    private fun dirFits(s: SpotRow, bearing: Double): Boolean {
        val dir = s.dir ?: return true
        val off = abs(Geo.angleDiff(dir.toDouble(), bearing))
        return if (s.kind == SpotHit.SIGNAL) off <= AGAINST_DEG else off <= SAME_WAY_DEG
    }

    private fun reachOf(kind: Int) = when (kind) {
        SpotHit.CAMERA -> CAMERA_REACH_M
        SpotHit.SIGNAL -> SIGNAL_REACH_M
        else -> BUMP_REACH_M
    }

    /** [rows] by latitude. One hit per junction per pass, one per camera or bump. */
    fun spotsAlong(line: Polyline, rows: List<SpotRow>): RouteSpots {
        val lats = IntArray(rows.size) { rows[it].lat }
        val lons = IntArray(rows.size) { rows[it].lon }
        val passes = passes(line, lats, lons, CAMERA_REACH_M) { k, d, bearing -> d <= reachOf(rows[k].kind) && dirFits(rows[k], bearing) }
        val out = ArrayList<SpotHit>()
        val lastJunction = HashMap<Int, Int>() // j to its place in out
        var lastCamera = -1
        var lastBump = -1
        for (p in passes) {
            val r = rows[p.index]
            val camera = r.kind == SpotHit.CAMERA
            val merged = when {
                r.kind == SpotHit.SIGNAL && r.j != null -> lastJunction[r.j]?.takeIf { p.along - out[it].along <= ONE_JUNCTION_M }
                camera -> lastCamera.takeIf { it >= 0 && p.along - out[it].along <= ONE_SPOT_M }
                r.kind == SpotHit.BUMP -> lastBump.takeIf { it >= 0 && p.along - out[it].along <= ONE_SPOT_M }
                else -> null
            }
            if (merged != null) {
                // the first one met stands for them; what it does not know the other may
                val o = out[merged]
                if ((camera && o.cameraKmh == null && r.kmh != null) || (!camera && o.roadKmh == null && r.kmh != null)) {
                    out[merged] = SpotHit(o.kind, o.lat, o.lon, o.dir ?: r.dir, if (camera) r.kmh else null, if (camera) null else r.kmh, o.owners, o.junction, o.along, o.offset)
                }
                continue
            }
            out.add(SpotHit(r.kind, r.lat / 1e6, r.lon / 1e6, r.dir, if (camera) r.kmh else null, if (camera) null else r.kmh, r.src == 2, r.j, p.along, p.offset))
            when {
                r.kind == SpotHit.SIGNAL && r.j != null -> lastJunction[r.j] = out.size - 1
                camera -> lastCamera = out.size - 1
                r.kind == SpotHit.BUMP -> lastBump = out.size - 1
            }
        }
        return RouteSpots(out)
    }

    fun gatesAlong(line: Polyline, rows: List<GateRow>): List<GateHit> {
        val lats = IntArray(rows.size) { rows[it].lat }
        val lons = IntArray(rows.size) { rows[it].lon }
        return passes(line, lats, lons, GATE_REACH_M) { _, _, _ -> true }.map {
            val r = rows[it.index]
            GateHit(r.lat / 1e6, r.lon / 1e6, r.sub, r.road, it.along, it.offset)
        }
    }

    /** `[[lon,lat],...]` as lon, lat, lon, lat; null for anything else. A small scanner: a ring is read for every zone near a route, org.json is not for that (nor in src/test). */
    fun parseRing(text: String): DoubleArray? {
        val out = ArrayList<Double>(64)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '-' || c == '.' || c in '0'..'9') {
                var j = i + 1
                while (j < n && (text[j] == '.' || text[j] == '-' || text[j] == '+' || text[j] == 'e' || text[j] == 'E' || text[j] in '0'..'9')) j++
                out.add(text.substring(i, j).toDoubleOrNull() ?: return null)
                i = j
            } else i++
        }
        if (out.size < 6 || out.size % 2 != 0) return null
        return out.toDoubleArray()
    }

    /** Even-odd, with an open ring taken as closed. */
    fun inside(lat: Double, lon: Double, ring: DoubleArray): Boolean {
        var hit = false
        val n = ring.size / 2
        var a = n - 1
        for (b in 0 until n) {
            val x1 = ring[a * 2]; val y1 = ring[a * 2 + 1]
            val x2 = ring[b * 2]; val y2 = ring[b * 2 + 1]
            if ((y1 > lat) != (y2 > lat) && lon < (x2 - x1) * (lat - y1) / (y2 - y1) + x1) hit = !hit
            a = b
        }
        return hit
    }

    /** Where the stretch a to b crosses the ring, as fractions of it, in order. */
    private fun crossings(a: LatLon, b: LatLon, ring: DoubleArray): DoubleArray {
        var out = DoubleArray(4)
        var c = 0
        val dx = b.lon - a.lon
        val dy = b.lat - a.lat
        val n = ring.size / 2
        var p = n - 1
        for (q in 0 until n) {
            val x1 = ring[p * 2]; val y1 = ring[p * 2 + 1]
            val ex = ring[q * 2] - x1; val ey = ring[q * 2 + 1] - y1
            val d = dx * ey - dy * ex
            if (d != 0.0) {
                val t = ((x1 - a.lon) * ey - (y1 - a.lat) * ex) / d
                val u = ((x1 - a.lon) * dy - (y1 - a.lat) * dx) / d
                if (t > 0.0 && t < 1.0 && u >= 0.0 && u < 1.0) {
                    if (c == out.size) out = out.copyOf(c * 2)
                    out[c++] = t
                }
            }
            p = q
        }
        val r = out.copyOf(c)
        r.sort()
        return r
    }

    /** How the line goes through a zone, or null when it never does. */
    fun zoneAlong(z: ZoneRow, line: Polyline, roadClass: ((Int) -> Int)?): ZoneHit? {
        val ring = parseRing(z.ring) ?: return null
        return zoneAlong(z.id, z.kind, z.name, z.en, z.s, z.n, z.w, z.e, ring, line, roadClass)
    }

    /** The same from a ring as lon, lat, lon, lat. A stretch can cross a small zone with both its ends outside, so the crossings count, not only the points. */
    fun zoneAlong(id: Int, kind: Int, name: String?, en: String?, south: Double, north: Double, west: Double, east: Double, ring: DoubleArray, line: Polyline, roadClass: ((Int) -> Int)?): ZoneHit? {
        val pts = line.points
        if (pts.size < 2) return null
        val startsInside = inside(pts[0].lat, pts[0].lon, ring)
        var now = startsInside
        var entered = false
        var first = if (startsInside) 0.0 else Double.NaN
        var last = 0.0
        var metres = 0.0
        var off = 0.0
        var minor = 0.0
        var i = 0
        while (i < pts.size - 1) {
            // whole windows out of the zone's box go at once
            var j = i
            var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
            while (j < pts.size - 1 && j - i < WINDOW_SEGMENTS) {
                val a = pts[j]
                val b = pts[j + 1]
                if (a.lat < s) s = a.lat
                if (b.lat < s) s = b.lat
                if (a.lat > n) n = a.lat
                if (b.lat > n) n = b.lat
                if (a.lon < w) w = a.lon
                if (b.lon < w) w = b.lon
                if (a.lon > e) e = a.lon
                if (b.lon > e) e = b.lon
                j++
            }
            if (s > north || n < south || w > east || e < west) {
                now = false
                i = j
                continue
            }
            for (seg in i until j) {
                val a = pts[seg]
                val b = pts[seg + 1]
                val len = line.cumulative[seg + 1] - line.cumulative[seg]
                val cross = crossings(a, b, ring)
                val cl = roadClass?.invoke(seg) ?: -1
                var from = 0.0
                for (k in 0..cross.size) {
                    val t = if (k < cross.size) cross[k] else 1.0
                    if (now) {
                        val m = (t - from) * len
                        metres += m
                        if (entered && cl >= 2) off += m
                        if (entered && cl >= MINOR_CLASS) minor += m
                        last = line.cumulative[seg] + t * len
                    }
                    if (k < cross.size) {
                        now = !now
                        if (now) {
                            entered = true
                            if (first.isNaN()) first = line.cumulative[seg] + t * len
                        }
                    }
                    from = t
                }
                val really = inside(b.lat, b.lon, ring) // a line through a corner of the ring counts twice or not at all: the point itself decides
                if (really && !now) {
                    entered = true
                    if (first.isNaN()) first = line.cumulative[seg + 1]
                }
                now = really
            }
            i = j
        }
        if (first.isNaN()) return null
        val end = pts[pts.size - 1]
        val endsInside = inside(end.lat, end.lon, ring)
        val haram = kind == 1 || kind == 2
        val known = roadClass != null
        val endsWell = endsInside && (haram || farFromEdge(end, ring))
        val warn = entered && (haram || endsWell || (known && minor >= OFF_MAIN_M))
        return ZoneHit(id, kind, name, en, first, last, metres, startsInside, endsInside, entered, if (known) off else null, if (known) minor else null, warn, endsWell)
    }

    private fun farFromEdge(p: LatLon, ring: DoubleArray): Boolean {
        val n = ring.size / 2
        var a = n - 1
        for (b in 0 until n) {
            if (Geo.project(p, LatLon(ring[a * 2 + 1], ring[a * 2]), LatLon(ring[b * 2 + 1], ring[b * 2])).second < EDGE_M) return false
            a = b
        }
        return true
    }

    /** Walks a line from [fromAlong] in stretches of up to 5 km, with each one's box in 1e-6 degrees; [each] says whether to go on. */
    inline fun stretches(line: Polyline, fromAlong: Double, maxAhead: Double, pad: Double, each: (s: Int, n: Int, w: Int, e: Int, first: Int, last: Int) -> Boolean) {
        val pts = line.points
        if (pts.size < 2) return
        var i = 0
        while (i < pts.size - 2 && line.cumulative[i + 1] <= fromAlong) i++
        val end = fromAlong + maxAhead
        while (i < pts.size - 1 && line.cumulative[i] < end) {
            var j = i
            var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
            while (j < pts.size - 1 && (j == i || line.cumulative[j] - line.cumulative[i] < 5000.0)) {
                for (p in arrayOf(pts[j], pts[j + 1])) {
                    if (p.lat < s) s = p.lat
                    if (p.lat > n) n = p.lat
                    if (p.lon < w) w = p.lon
                    if (p.lon > e) e = p.lon
                }
                j++
            }
            val padLa = pad / 110540.0
            val padLo = pad / (111320.0 * cos(s * 0.017453292519943295))
            if (!each(((s - padLa) * 1e6).toInt(), ((n + padLa) * 1e6).toInt(), ((w - padLo) * 1e6).toInt(), ((e + padLo) * 1e6).toInt(), i, j)) return
            i = j
        }
    }

    /** The nearest place on segments [first] until [last] of a line, from [fromAlong] on and within [reach], or null. */
    fun nearestOn(line: Polyline, first: Int, last: Int, fromAlong: Double, lat: Double, lon: Double, reach: Double): RoutePlace.On? {
        val p = LatLon(lat, lon)
        var best: RoutePlace.On? = null
        for (seg in first until last) {
            val a = line.points[seg]
            val b = line.points[seg + 1]
            val (t, d) = Geo.project(p, a, b)
            if (d > reach || (best != null && d >= best.offset)) continue
            val along = line.cumulative[seg] + (line.cumulative[seg + 1] - line.cumulative[seg]) * t
            if (along < fromAlong) continue
            // x east, y north: the place on the right of the way a to b when the turn from a-b to a-p is clockwise
            val k = cos(a.lat * DEG)
            val cross = (b.lon - a.lon) * k * (lat - a.lat) - (b.lat - a.lat) * (lon - a.lon) * k
            best = RoutePlace.On(along, d, cross < 0)
        }
        return best
    }
}
