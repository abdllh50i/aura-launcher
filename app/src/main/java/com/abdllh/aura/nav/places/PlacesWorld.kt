package com.abdllh.aura.nav.places

import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.guide.GateRow as GuideGate
import com.abdllh.aura.nav.guide.GeoBox
import com.abdllh.aura.nav.guide.PoiRow
import com.abdllh.aura.nav.guide.RouteWorld
import com.abdllh.aura.nav.guide.SpotRow as GuideSpot
import com.abdllh.aura.nav.guide.ZoneRow as GuideZone

/**
 * What guidance asks of the place data ([RouteWorld]) over places.db: rows in the small boxes along a route, each row once. On the engine's thread ([PlaceSearch.guidance]). A failure in
 * here is what [com.abdllh.aura.nav.guide.Guidance.build] catches: guidance goes on, only plainer. The owner's live additions (MapExtras) are not in the file as they are now: its rows of src=2 are the build's copy,
 * and they stay on top where RoadWatch asks them.
 */
internal class PlacesWorld(private val db: PlacesDb) : RouteWorld {
    private fun Double.e6() = Math.round(this * 1e6).toInt()

    override fun spots(boxes: List<GeoBox>): List<GuideSpot> {
        val seen = HashSet<Long>()
        val out = ArrayList<GuideSpot>()
        for (b in boxes) for (r in db.spotsIn(b.south.e6(), b.north.e6(), b.west.e6(), b.east.e6(), null)) {
            if (seen.add(key(r.lat, r.lon, r.kind))) out.add(GuideSpot(r.kind, LatLon(r.lat / 1e6, r.lon / 1e6), r.dir, r.kmh ?: 0, r.src == 2, r.j ?: 0))
        }
        return out
    }

    override fun gates(boxes: List<GeoBox>): List<GuideGate> {
        val seen = HashSet<Long>()
        val out = ArrayList<GuideGate>()
        for (b in boxes) for (r in db.gatesIn(b.south.e6(), b.north.e6(), b.west.e6(), b.east.e6(), null)) {
            if (seen.add(key(r.lat, r.lon, r.sub))) out.add(GuideGate(LatLon(r.lat / 1e6, r.lon / 1e6), r.sub, r.road))
        }
        return out
    }

    override fun pois(boxes: List<GeoBox>, cats: Set<String>, minRank: Int): List<PoiRow> {
        val ids = cats.mapNotNull { db.catKeys[it]?.id }
        if (ids.isEmpty()) return emptyList()
        val keys = ids.joinToString(",")
        val seen = HashSet<Int>()
        val out = ArrayList<PoiRow>()
        for (b in boxes) {
            val sql = "SELECT ${PlacesDb.COLUMNS} FROM p WHERE p.lat BETWEEN ${b.south.e6()} AND ${b.north.e6()} AND p.lon BETWEEN ${b.west.e6()} AND ${b.east.e6()} AND p.kind=$KIND_POI AND p.cat IN ($keys) AND p.rank>=$minRank"
            for (r in db.rows(sql, null, null)) {
                if (seen.add(r.id)) out.add(PoiRow(r.id.toLong(), r.name, r.alt, db.cats[r.cat]?.key.orEmpty(), LatLon(r.lat / 1e6, r.lon / 1e6), r.rank, r.src and SRC_COARSE != 0))
            }
        }
        return out
    }

    override fun zones(boxes: List<GeoBox>): List<GuideZone> {
        if (boxes.isEmpty()) return emptyList()
        val rows = db.zones(boxes.minOf { it.south }, boxes.maxOf { it.north }, boxes.minOf { it.west }, boxes.maxOf { it.east }, null)
        val out = ArrayList<GuideZone>()
        for (z in rows) {
            val box = GeoBox(z.s, z.w, z.n, z.e)
            if (boxes.none { it.touches(box) }) continue
            val flat = RoadAhead.parseRing(z.ring) ?: continue
            out.add(GuideZone(z.id, z.kind, z.name, z.en, box, List(flat.size / 2) { LatLon(flat[it * 2 + 1], flat[it * 2]) }))
        }
        return out
    }

    /** One SQL for a name in a place of about 15 km: a name is asked for each step that has one. */
    private val names = HashMap<String, String?>()

    override fun arabicRoadName(latin: String, near: LatLon): String? {
        if (latin.isBlank()) return null
        return names.getOrPut("$latin ${(near.lat * 10).toInt()} ${(near.lon * 10).toInt()}") {
            val d = REACH_KM / 110.54
            val dl = REACH_KM / (111.32 * kotlin.math.cos(Math.toRadians(near.lat)).coerceAtLeast(0.01))
            db.roadsByAlt(latin, (near.lat - d).e6(), (near.lat + d).e6(), (near.lon - dl).e6(), (near.lon + dl).e6(), null)
                .filter { Norm.hasArabic(it.first) }
                .map { it.first to Score.kmBetween(near.lat, near.lon, it.second / 1e6, it.third / 1e6) }
                .filter { it.second <= REACH_KM }
                .minByOrNull { it.second }?.first
        }
    }

    private fun key(lat: Int, lon: Int, kind: Int) = (lat.toLong() shl 32) xor (lon.toLong() shl 3) xor kind.toLong()

    private companion object {
        const val REACH_KM = 15.0
    }
}
