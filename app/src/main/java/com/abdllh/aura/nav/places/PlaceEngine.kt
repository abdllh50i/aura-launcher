package com.abdllh.aura.nav.places

import android.os.CancellationSignal
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Polyline
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.cos

/** A result of a search: one row of table p, with its names in both scripts when known and where it is. */
class PlaceHit internal constructor(
    val id: Int,
    /** `p.kind`: 1 region 2 city 3 town 4 village 5 district 6 POI 7 road. */
    val kind: Int,
    /** What is stored; [nameAr] and [nameEn] are the same two by script. */
    val name: String,
    val alt: String?,
    val cat: PlaceCat?,
    val lat: Double,
    val lon: Double,
    val rank: Int,
    /** From where it was asked; null without a position. */
    val km: Double?,
    val score: Double,
    /** The position is a geocoder's default: show "موقع تقريبي" ([com.abdllh.aura.R.string.places_approx]). */
    val approx: Boolean,
    private val area: Area?
) {
    private val nameIsAr = Norm.hasArabic(name)
    val nameAr: String? get() = if (nameIsAr) name else alt?.takeIf { it.isNotEmpty() && Norm.hasArabic(it) }
    val nameEn: String? get() = if (!nameIsAr && name.isNotEmpty()) name else alt?.takeIf { it.isNotEmpty() && !Norm.hasArabic(it) }
    val townAr: String? get() = area?.cityAr?.takeIf { it.isNotEmpty() }
    val townEn: String? get() = area?.cityEn?.takeIf { it.isNotEmpty() }
    val districtAr: String? get() = area?.ar?.takeIf { it.isNotEmpty() }
    val districtEn: String? get() = area?.en?.takeIf { it.isNotEmpty() }

    /** `name`, or `alt` if the UI's language differs from the script of `name` and there is an `alt`. */
    fun shown(arabic: Boolean): String = if (!alt.isNullOrEmpty() && arabic != nameIsAr) alt else name

    /** For POIs, roads and districts the category's name in the UI's language; then the district, then the town, each unless it repeats what is shown. */
    fun detail(arabic: Boolean): String {
        val main = shown(arabic)
        val parts = ArrayList<String>(3)
        if (cat != null && (kind == KIND_POI || kind == KIND_ROAD || kind == KIND_DISTRICT)) parts.add(if (arabic) cat.ar else cat.en)
        if (area != null) {
            val na = if (arabic) area.ar else area.en.ifEmpty { area.ar }
            val ci = if (arabic) area.cityAr else area.cityEn.ifEmpty { area.cityAr }
            if (na.isNotEmpty() && na != main && na !in parts) parts.add(na)
            if (ci.isNotEmpty() && ci != main && ci !in parts) parts.add(ci)
        }
        return parts.joinToString(if (arabic) "، " else ", ")
    }
}

/** What is at a point (dropped pin, "where am I"). Never made from a coarse row. */
class ReverseHit internal constructor(
    val what: What,
    val metres: Double,
    /** A POI, a road, a district: its row. */
    val id: Int?,
    val name: String?,
    val alt: String?,
    /** The area of the nearest row. */
    val areaId: Int?,
    val districtAr: String?,
    val districtEn: String?,
    val townAr: String?,
    val townEn: String?
) {
    enum class What { POI, ROAD, AREA, DISTRICT }

    /** The name to show: of the thing, or the district and the town. Empty when the file has none in either script. */
    fun shown(arabic: Boolean): String {
        if (what != What.AREA) {
            val n = name.orEmpty()
            return if (!alt.isNullOrEmpty() && arabic != Norm.hasArabic(n)) alt else n
        }
        val na = if (arabic) districtAr.orEmpty() else districtEn.orEmpty().ifEmpty { districtAr.orEmpty() }
        val ci = if (arabic) townAr.orEmpty() else townEn.orEmpty().ifEmpty { townAr.orEmpty() }
        return listOf(na, ci).filter { it.isNotEmpty() }.distinct().joinToString(if (arabic) "، " else ", ")
    }
}

/** Where a search's time goes, for the debug run. Stages by index: see [NAMES]. */
internal class SearchStats {
    val ns = LongArray(NAMES.size)
    private var last = 0L
    val matches = ArrayList<String>()
    var candidates = 0

    fun start() { last = System.nanoTime() }
    fun lap(stage: Int) {
        val n = System.nanoTime()
        ns[stage] += n - last
        last = n
    }

    companion object {
        val NAMES = arrayOf("plan", "first", "other", "place", "edit", "loose", "area", "score", "dedupe")
        const val PLAN = 0
        const val FIRST = 1
        const val OTHER = 2
        const val PLACE = 3
        const val EDIT = 4
        const val LOOSE = 5
        const val AREA = 6
        const val SCORE = 7
        const val DEDUPE = 8
    }
}

/**
 * Search and reverse over places.db: the stages of SEARCH_SPEC.md section 4 in search_ref.py's order, and what guidance asks of the file. Blocking; [cs] stops it at the next
 * read (the newer query, [PlaceSearch]). A coarse row is found by search and never by anything else here.
 */
internal class PlaceEngine(val db: PlacesDb) {

    /** The band of latitude of one search, found once, and its row pool: a row is read, and its name worked out, one time. */
    private inner class Reads(val near: LatLon?, val cs: CancellationSignal?) {
        val pool = HashMap<Int, Row>(512)
        private var band: IntArray? = null
        private var bandDone = false
        private val order: String

        init {
            order = if (near != null) {
                val la = (near.lat * 1e6).toInt()
                val lo = (near.lon * 1e6).toInt()
                val c = cos(near.lat * DEG)
                // Python's {k:.6f}: the exact value, rounded. String.format rounds the shortest decimal instead
                val k = BigDecimal(c * c).setScale(6, RoundingMode.HALF_EVEN).toPlainString()
                "ORDER BY (p.lat-$la)*(p.lat-$la)+(p.lon-$lo)*(p.lon-$lo)*$k LIMIT $NEAREST"
            } else ""
        }

        /** Ids run south to north, so a band of latitude is a range of ids, which FTS4 can skip to without reading the rows. */
        private fun band(): IntArray? {
            if (!bandDone) {
                bandDone = true
                val a = db.oneInt("SELECT id FROM p WHERE lat>=${((near!!.lat - BAND) * 1e6).toInt()} ORDER BY lat LIMIT 1", cs)
                val b = db.oneInt("SELECT id FROM p WHERE lat<=${((near.lat + BAND) * 1e6).toInt()} ORDER BY lat DESC LIMIT 1", cs)
                band = if (a != null && b != null) intArrayOf(a, b) else null
            }
            return band
        }

        /** Up to [NEAREST] nearest and [BEST] best-ranked rows answering a MATCH string. */
        fun candidates(match: String, wantBest: Boolean): List<Row> {
            val args = arrayOf(match)
            if (near == null) return db.rows("$BASE ORDER BY p.rank DESC, p.id LIMIT ${NEAREST + BEST}", args, cs, pool)
            var got: ArrayList<Row> = NOTHING
            val b = band()
            if (b != null) got = db.rows("$BASE AND f.docid BETWEEN ${b[0]} AND ${b[1]} $order", args, cs, pool)
            if (got.size < NEAREST) got = db.rows("$BASE $order", args, cs, pool)
            if (got.size == NEAREST && wantBest) got.addAll(db.rows("$BASE ORDER BY p.rank DESC, p.id LIMIT $BEST", args, cs, pool))
            return got
        }
    }

    /** The answer to a query, best first. [stats]: where the time went and each MATCH string run, as the reference's debug has them. */
    fun search(query: String, near: LatLon?, limit: Int = 12, cs: CancellationSignal? = null, stats: SearchStats? = null): List<PlaceHit> {
        val syn = db.syn
        stats?.start()
        // (a query is never this long, and a pasted page must not cost seconds of this thread: 500 characters took 1.4 s on the emulator)
        val plan = QueryPlan.plan(query.take(MAX_QUERY), syn)
        if (plan.required.isEmpty()) return emptyList()
        var letters = 0
        for (g in plan.required) for (w in g.words) letters += w.length
        if (letters < 2 && !(plan.roadHint && Norm.isDigits(plan.required[0].words[0]))) return emptyList() // one letter says nothing; "شارع 9" does
        val read = Reads(near, cs)
        val rows = LinkedHashMap<Int, Row>()
        fun take(got: List<Row>) { for (r in got) rows[r.id] = r }
        val loose = HashSet<Int>() // found by consonants alone
        fun takeLoose(got: List<Row>) { for (r in got) if (r.id !in rows) { loose.add(r.id); rows[r.id] = r } }
        stats?.lap(SearchStats.PLAN)

        val stages = QueryPlan.firstStages(plan)
        val best = QueryPlan.wantsBest(plan, syn)
        for (m in stages) {
            stats?.matches?.add(m)
            take(read.candidates(m, best))
        }
        val plain = stages[stages.size - 1]
        stats?.lap(SearchStats.FIRST)

        val plans = ArrayList<Plan>(2)
        plans.add(plan)
        for (other in QueryPlan.otherPlans(plan, syn)) {
            cs?.throwIfCanceled()
            var hit = false
            val want = QueryPlan.wantsBest(other, syn)
            for (m in QueryPlan.firstStages(other)) {
                stats?.matches?.add(m)
                val got = read.candidates(m, want)
                if (got.isNotEmpty()) hit = true
                if (other.skeletonFrom != 0) takeLoose(got)
                else {
                    take(got)
                    for (r in got) loose.remove(r.id)
                }
            }
            if (hit) plans.add(other)
        }
        stats?.lap(SearchStats.OTHER)

        val few = rows.size < 6
        // few, or none near the car: perhaps it is written another way. What a letter or two apart finds is a nearer branch of the same name more often than another word
        var nearby = 0
        if (near != null) for (r in rows.values) if (abs(r.lat / 1e6 - near.lat) < 0.1 && abs(r.lon / 1e6 - near.lon) < 0.1) nearby++
        val noneNear = near != null && nearby < 3
        QueryPlan.placeString(plan)?.let { mp ->
            stats?.matches?.add(mp)
            for (r in read.candidates(mp, false)) if (QueryPlan.placeOk(plan, r)) { rows[r.id] = r; loose.remove(r.id) }
        }
        stats?.lap(SearchStats.PLACE)
        if (few || noneNear) {
            val m3 = QueryPlan.ftsEdit(plan)
            if (m3.isNotEmpty() && m3 != plain) {
                stats?.matches?.add(m3)
                for (r in read.candidates(m3, false)) if (QueryPlan.editOk(plan, r)) { rows[r.id] = r; loose.remove(r.id) }
            }
        }
        stats?.lap(SearchStats.EDIT)
        if (few) {
            val m2 = QueryPlan.fts(plan, looseFrom = 3)
            if (m2 != plain) {
                stats?.matches?.add(m2)
                takeLoose(read.candidates(m2, false))
            }
        }
        if (rows.size < 3) { // two consonants say little: only when nothing else has been found
            val m2 = QueryPlan.fts(plan, looseFrom = 2)
            if (m2 != plain) {
                stats?.matches?.add(m2)
                takeLoose(read.candidates(m2, true))
            }
        }
        stats?.candidates = rows.size
        stats?.lap(SearchStats.LOOSE)
        cs?.throwIfCanceled()

        val ids = HashSet<Int>()
        for (r in rows.values) if (r.area != 0) ids.add(r.area)
        val areas = db.areas(ids, cs)
        stats?.lap(SearchStats.AREA)

        val facts = plans.map { PlanFacts(it, syn) }
        val scored = ArrayList<Scored>(rows.size)
        var n = 0
        for (r in rows.values) {
            if (++n and 63 == 0) cs?.throwIfCanceled()
            val km = near?.let { Score.kmBetween(it.lat, it.lon, r.lat / 1e6, r.lon / 1e6) }
            val a = if (r.area != 0) areas[r.area] else null
            var s = Double.NEGATIVE_INFINITY
            for (f in facts) {
                val x = Score.score(r, f, km, a, syn)
                if (x > s) s = x
            }
            val rounded = Score.pyRound(s, 3)
            // (nothing near a relevant answer: "nothing found" must still be possible)
            if (rounded < 0.0 || (r.id in loose && Score.farAndOnlyAlike(r, plans, km))) continue
            scored.add(Scored(r, rounded, km))
        }
        // ties by the lower id, after rounding (a comparison of the doubles themselves: -0.0 is 0.0)
        scored.sortWith(Comparator { x, y -> if (x.score != y.score) (if (x.score > y.score) -1 else 1) else x.row.id.compareTo(y.row.id) })
        stats?.lap(SearchStats.SCORE)

        // one place once. The reference goes down the whole list and cuts after; only the first [limit] that stay can matter
        val kept = ArrayList<Scored>(limit)
        for (x in scored) {
            if (kept.size >= limit) break
            var dup = false
            for (y in kept) if (Score.isSamePlace(x.row, y.row)) { dup = true; break }
            if (!dup) kept.add(x)
        }
        val out = kept.map { hit(it.row, if (it.row.area != 0) areas[it.row.area] else null, it.km?.let { km -> Score.pyRound(km, 2) }, it.score) }
        stats?.lap(SearchStats.DEDUPE)
        return out
    }

    private class Scored(@JvmField val row: Row, @JvmField val score: Double, @JvmField val km: Double?)

    private fun hit(r: Row, a: Area?, km: Double?, score: Double) =
        PlaceHit(r.id, r.kind, r.name, r.alt, db.cats[r.cat], r.lat / 1e6, r.lon / 1e6, r.rank, km, score, r.src and SRC_COARSE != 0, a)

    /** One row by its id, with its area. */
    fun row(id: Int, cs: CancellationSignal? = null): PlaceHit? {
        val r = db.rows("SELECT ${PlacesDb.COLUMNS} FROM p WHERE p.id=$id", null, cs).firstOrNull() ?: return null
        return hit(r, if (r.area != 0) db.areas(hashSetOf(r.area), cs)[r.area] else null, null, 0.0)
    }

    /**
     * What is at a point: the nearest POI within 60 m, else a named road within 600 m (its points are about 1 km apart), else the district and town of the nearest row
     * of any kind, else a district. A pile of rows on a geocoder's default point is not what is at that point.
     */
    fun reverse(lat: Double, lon: Double, cs: CancellationSignal? = null): ReverseHit? {
        val la = (lat * 1e6).toInt()
        val lo = (lon * 1e6).toInt()
        val d = 7000 // 0.007 degrees, about 780 m, is the widest ring this asks for
        val dl = (d / cos(lat * DEG)).toInt()
        val rows = db.rows("SELECT ${PlacesDb.COLUMNS} FROM p WHERE p.lat BETWEEN ${la - d} AND ${la + d} AND p.lon BETWEEN ${lo - dl} AND ${lo + dl} AND p.kind>=5", null, cs)
        val best = LinkedHashMap<Int, Pair<Double, Row>>() // nearest of each kind, in the order the kinds were met
        for (r in rows) {
            if (r.src and SRC_COARSE != 0) continue
            val m = Score.kmBetween(lat, lon, r.lat / 1e6, r.lon / 1e6) * 1000.0
            val old = best[r.kind]
            if (old == null || m < old.first) best[r.kind] = m to r
        }
        val poi = best[KIND_POI]
        if (poi != null && poi.first <= 60.0) return thing(ReverseHit.What.POI, poi)
        val road = best[KIND_ROAD]
        if (road != null && road.first <= 600.0) return thing(ReverseHit.What.ROAD, road)
        var nearest: Pair<Double, Row>? = null
        for (x in best.values) if (x.second.area != 0 && (nearest == null || x.first < nearest.first)) nearest = x
        if (nearest != null) {
            val a = db.area(nearest.second.area, cs)
            if (a != null) return ReverseHit(ReverseHit.What.AREA, Score.pyRound(nearest.first, 1), null, null, null, a.id, a.ar, a.en, a.cityAr, a.cityEn)
        }
        val dist = best[KIND_DISTRICT]
        if (dist != null) return thing(ReverseHit.What.DISTRICT, dist)
        return null
    }

    private fun thing(what: ReverseHit.What, x: Pair<Double, Row>) =
        ReverseHit(what, Score.pyRound(x.first, 1), x.second.id, x.second.name, x.second.alt, null, null, null, null, null)

    // ------------------------------------------------------------------------------------------- guidance and warnings

    /** The cameras, junctions with signals and bumps a route goes by, in order. */
    fun spotsAlong(line: Polyline, cs: CancellationSignal? = null): RouteSpots {
        val b = RoadAhead.latBand(line) ?: return RouteSpots(emptyList())
        return RoadAhead.spotsAlong(line, db.spots(b[0], b[1], cs))
    }

    /** What to tell before a route starts: gates and zones on it, by the rule of SEARCH_SPEC.md section 1 ([ZoneHit.warn] says where it differs). [roadClass]: the class of a segment of the line, numbered as `gate.road`. */
    fun warnings(line: Polyline, roadClass: ((Int) -> Int)?, cs: CancellationSignal? = null): RouteWarnings {
        val b = RoadAhead.latBand(line) ?: return RouteWarnings(emptyList(), emptyList())
        val gates = RoadAhead.gatesAlong(line, db.gates(b[0], b[1], cs))
        val box = RoadAhead.box(line)
        val zones = db.zones(box[0], box[1], box[2], box[3], cs).mapNotNull {
            cs?.throwIfCanceled()
            RoadAhead.zoneAlong(it, line, roadClass)
        }
        return RouteWarnings(gates, zones.sortedBy { it.enterAlong })
    }

    /** A zone's ring of the Haram for exclude_polygons (kind 1 Makkah, 2 Madinah). */
    fun haramRing(kind: Int, cs: CancellationSignal? = null): ZoneRing? {
        val z = db.zonesOfKind(kind, cs).firstOrNull() ?: return null
        val ring = RoadAhead.parseRing(z.ring) ?: return null
        return ZoneRing(z.id, z.kind, z.name, z.en, z.ring, ring)
    }

    /**
     * The nearest of a kind ahead on a route (key of table cat: "fuel", "mosque"), best first by the way along it: walks the line in stretches from [fromAlong], stops as soon as
     * [limit] are found or [maxAheadM] is behind it. Within [corridorM] of the line. Which side it is on is told, and only judged with [rightOnly]: on a divided road what is on the left may take a long way round.
     */
    fun nearestAhead(line: Polyline, fromAlong: Double, catKey: String, limit: Int = 3, rightOnly: Boolean = false, corridorM: Double = 200.0, maxAheadM: Double = 500_000.0, cs: CancellationSignal? = null): List<RoutePlace> {
        val cat = db.catKeys[catKey] ?: return emptyList()
        val found = LinkedHashMap<Int, RoutePlace.Found>()
        RoadAhead.stretches(line, fromAlong, maxAheadM, corridorM) { s, n, w, e, first, last ->
            cs?.throwIfCanceled()
            val rows = db.rows("SELECT ${PlacesDb.COLUMNS} FROM p WHERE p.lat BETWEEN $s AND $n AND p.lon BETWEEN $w AND $e AND p.cat=${cat.id} AND p.kind=$KIND_POI AND p.src&$SRC_COARSE=0", null, cs)
            for (r in rows) {
                if (r.id in found) continue
                val on = RoadAhead.nearestOn(line, first, last, fromAlong, r.lat / 1e6, r.lon / 1e6, corridorM) ?: continue
                if (!rightOnly || on.onRight) found[r.id] = RoutePlace.Found(r, on)
            }
            found.size < limit
        }
        val list = found.values.sortedBy { it.on.along }.take(limit)
        val areas = db.areas(list.mapNotNull { x -> x.row.area.takeIf { it != 0 } }.toSet(), cs)
        return list.map { RoutePlace(hit(it.row, areas[it.row.area], (it.on.along - fromAlong) / 1000.0, 0.0), it.on.along, it.on.offset, it.on.onRight) }
    }

    /** The nearest of a kind near a point ("parking"), nearest first, within [radiusM]. The box grows only until it holds enough. */
    fun nearestTo(lat: Double, lon: Double, catKey: String, limit: Int = 5, radiusM: Double = 3000.0, cs: CancellationSignal? = null): List<PlaceHit> {
        val cat = db.catKeys[catKey] ?: return emptyList()
        val la = (lat * 1e6).toInt()
        val lo = (lon * 1e6).toInt()
        val k = cos(lat * DEG)
        var r = minOf(500.0, radiusM)
        var picked: List<Pair<Double, Row>> = emptyList()
        while (true) {
            val d = (r / 110540.0 * 1e6).toInt()
            val dl = (r / (111320.0 * k) * 1e6).toInt()
            val rows = db.rows("SELECT ${PlacesDb.COLUMNS} FROM p WHERE p.lat BETWEEN ${la - d} AND ${la + d} AND p.lon BETWEEN ${lo - dl} AND ${lo + dl} AND p.cat=${cat.id} AND p.kind=$KIND_POI AND p.src&$SRC_COARSE=0", null, cs)
            picked = rows.map { Score.kmBetween(lat, lon, it.lat / 1e6, it.lon / 1e6) * 1000.0 to it }.filter { it.first <= r }.sortedWith(compareBy({ it.first }, { it.second.id }))
            if (picked.size >= limit || r >= radiusM) break
            r = minOf(r * 3.0, radiusM)
        }
        val list = picked.take(limit)
        val areas = db.areas(list.mapNotNull { x -> x.second.area.takeIf { it != 0 } }.toSet(), cs)
        return list.map { hit(it.second, areas[it.second.area], Score.pyRound(it.first / 1000.0, 2), 0.0) }
    }

    companion object {
        private const val DEG = Math.PI / 180.0
        private const val MAX_QUERY = 100
        private const val NEAREST = 150
        private const val BEST = 50
        private const val BAND = 1.0 // degrees of latitude either side of the car, about 110 km
        private const val BASE = "SELECT ${PlacesDb.COLUMNS} FROM f CROSS JOIN p ON p.id=f.docid WHERE f MATCH ?"
        private val NOTHING = ArrayList<Row>(0)
    }
}
