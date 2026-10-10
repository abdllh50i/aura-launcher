package com.abdllh.aura.nav.offline

import android.util.Log
import com.abdllh.aura.nav.ArabicQuery
import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Place
import com.abdllh.aura.util.TextMatch
import kotlin.math.floor

/**
 * Search without internet: the names the offline map's tiles carry (places, points of interest, airports, parks and
 * street names), indexed on the unit right after the download. Each entry keeps its names (local, Arabic, English),
 * a normalised key ([TextMatch.norm]) and a consonant skeleton ([TextMatch.skeleton]), so an Arabic query also finds
 * a place that only has a Latin name and the other way round, plus the nearest town for the result's second line.
 */
object PlaceIndex {
    private const val TAG = "AuraPlaces"
    private val LAYERS = setOf("poi", "place", "transportation_name", "aerodrome_label", "park")

    // kinds, in the order of their weight in the ranking
    private const val CITY = 0
    private const val VILLAGE = 1
    private const val QUARTER = 2
    private const val AIRPORT = 3
    private const val POI = 4
    private const val STREET = 5
    private const val PARK = 6

    private class Town(val name: String, val ar: String, val en: String, val lat: Double, val lon: Double, val big: Boolean)

    fun ready(st: TileStore): Boolean = try {
        st.db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='places'", null).use { it.moveToFirst() }
    } catch (_: Throwable) { false }

    /** Rebuilds the index from the stored tiles: towns from zoom 12 first, then everything at zoom 14 and 12. */
    fun build(st: TileStore, progress: (Int, Int) -> Unit, cancelled: () -> Boolean): Int {
        val db = st.db
        db.execSQL("DROP TABLE IF EXISTS places_new")
        // the index of an earlier build keeps this name after its table's rename
        db.execSQL("DROP INDEX IF EXISTS places_new_lat")
        db.execSQL("CREATE TABLE places_new (id INTEGER PRIMARY KEY, name TEXT, ar TEXT, en TEXT, kind INTEGER, cls TEXT, " +
            "lat REAL, lon REAL, area TEXT, area_ar TEXT, area_en TEXT, k TEXT, sk TEXT)")
        val total = (st.count(12) * 2 + st.count(14)).toInt()
        var done = 0

        // the towns, for "nearest town" (zoom 12 carries cities, towns and villages)
        val towns = ArrayList<Town>()
        val townSeen = HashSet<String>()
        st.forEachTile(12) { key, data ->
            done++
            if (done % 50 == 0) progress(done, total)
            for (layer in safeRead(data, setOf("place"))) for (f in layer.features) {
                val cls = f.tags["class"] as? String ?: continue
                if (cls != "city" && cls != "town" && cls != "village") continue
                val n = names(f.tags) ?: continue
                val (lat, lon) = where(key, layer, f)
                if (townSeen.add(TextMatch.norm(n.first) + "|" + cell(lat, lon, 0.05))) towns.add(Town(n.first, n.second, n.third, lat, lon, cls != "village"))
            }
        }
        val grid = HashMap<Long, MutableList<Town>>()
        for (t in towns) grid.getOrPut(cellKey(t.lat, t.lon)) { ArrayList() }.add(t)
        fun nearestTown(lat: Double, lon: Double): Town? {
            var best: Town? = null
            var bestD = 30_000.0
            val cx = floor(lon / 0.25).toLong(); val cy = floor(lat / 0.25).toLong()
            for (dx in -1L..1L) for (dy in -1L..1L) {
                for (t in grid[((cx + dx) shl 32) or ((cy + dy) and 0xFFFFFFFFL)] ?: continue) {
                    val d = Geo.distance(LatLon(lat, lon), LatLon(t.lat, t.lon)) * (if (t.big) 0.8 else 1.0) // a city's name reaches further
                    if (d < bestD) { bestD = d; best = t }
                }
            }
            return best
        }

        val seen = HashSet<String>()
        val st2 = db.compileStatement("INSERT INTO places_new (name, ar, en, kind, cls, lat, lon, area, area_ar, area_en, k, sk) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")
        var count = 0
        fun add(n: Triple<String, String, String>, kind: Int, cls: String, lat: Double, lon: Double) {
            val norm = TextMatch.norm(n.first)
            if (norm.isEmpty()) return
            val dedupe = when (kind) {
                STREET -> "s|$norm|" + cell(lat, lon, 0.01)
                CITY, VILLAGE, QUARTER -> "p|$norm|" + cell(lat, lon, 0.05)
                else -> "o|$norm|" + cell(lat, lon, 0.001)
            }
            if (!seen.add(dedupe)) return
            val all = listOf(n.first, n.second, n.third).filter { it.isNotBlank() }.distinct()
            val k = all.joinToString(" ", " ", " ") { TextMatch.norm(it) }
            val sk = all.joinToString(" ", " ", " ") { TextMatch.skeleton(TextMatch.norm(it)) }
            val town = if (kind == CITY) null else nearestTown(lat, lon)?.takeIf { TextMatch.norm(it.name) != norm }
            st2.bindString(1, n.first); st2.bindString(2, n.second); st2.bindString(3, n.third)
            st2.bindLong(4, kind.toLong()); st2.bindString(5, cls)
            st2.bindDouble(6, lat); st2.bindDouble(7, lon)
            st2.bindString(8, town?.name ?: ""); st2.bindString(9, town?.ar ?: ""); st2.bindString(10, town?.en ?: "")
            st2.bindString(11, k); st2.bindString(12, sk)
            st2.executeInsert()
            st2.clearBindings()
            count++
        }

        fun index(z: Int) {
            var inBatch = 0
            db.beginTransaction()
            try {
                st.forEachTile(z) { key, data ->
                    if (cancelled()) throw InterruptedException()
                    done++
                    if (done % 50 == 0) progress(done, total)
                    for (layer in safeRead(data, LAYERS)) for (f in layer.features) {
                        val n = names(f.tags) ?: continue
                        val cls = (f.tags["subclass"] as? String)?.takeIf { it.isNotBlank() } ?: (f.tags["class"] as? String) ?: ""
                        val kind = when (layer.name) {
                            "place" -> when (f.tags["class"]) {
                                "city", "town" -> CITY
                                "village", "suburb", "borough" -> VILLAGE
                                "quarter", "neighbourhood", "hamlet", "isolated_dwelling", "island" -> QUARTER
                                else -> null
                            }
                            "poi" -> POI
                            "transportation_name" -> if (f.tags["class"] in setOf("path", "track", "rail", "transit", "ferry")) null else STREET
                            "aerodrome_label" -> AIRPORT
                            "park" -> PARK
                            else -> null
                        } ?: continue
                        // zoom 12 only adds what zoom 14 may lack: places (the roads between towns are not searched)
                        if (z == 12 && kind != CITY && kind != VILLAGE && kind != AIRPORT) continue
                        val (lat, lon) = where(key, layer, f)
                        add(n, kind, cls, lat, lon)
                    }
                    if (++inBatch >= 50) {
                        db.setTransactionSuccessful(); db.endTransaction(); db.beginTransaction(); inBatch = 0
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        index(14)
        index(12)
        db.execSQL("CREATE INDEX places_new_lat ON places_new (lat)")
        db.beginTransaction()
        try {
            db.execSQL("DROP TABLE IF EXISTS places")
            db.execSQL("ALTER TABLE places_new RENAME TO places")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        progress(total, total)
        Log.i(TAG, "index: $count names, ${towns.size} towns")
        return count
    }

    private fun safeRead(data: ByteArray, layers: Set<String>): List<Mvt.Layer> = try { Mvt.read(data, layers) } catch (t: Throwable) { emptyList() }

    /** (local name, Arabic, English), or null without a name. */
    private fun names(tags: Map<String, Any>): Triple<String, String, String>? {
        val name = (tags["name"] as? String)?.trim().orEmpty()
        val ar = (tags["name:ar"] as? String)?.trim().orEmpty()
        val en = ((tags["name:en"] as? String) ?: (tags["name_en"] as? String) ?: (tags["name:latin"] as? String))?.trim().orEmpty()
        val main = name.ifBlank { ar.ifBlank { en } }
        if (main.isBlank()) return null
        return Triple(main, ar, en)
    }

    private fun where(key: Long, layer: Mvt.Layer, f: Mvt.Feature): Pair<Double, Double> {
        val z = TileMath.z(key)
        val x = TileMath.x(key) + f.px / layer.extent
        val y = TileMath.y(key) + f.py / layer.extent
        return TileMath.lat(y, z) to TileMath.lon(x, z)
    }

    private fun cell(lat: Double, lon: Double, size: Double) = "${floor(lat / size).toLong()}:${floor(lon / size).toLong()}"
    private fun cellKey(lat: Double, lon: Double): Long = (floor(lon / 0.25).toLong() shl 32) or (floor(lat / 0.25).toLong() and 0xFFFFFFFFL)

    // ------------------------------------------------------------------------------------------------------ search
    private class Row(val name: String, val ar: String, val en: String, val kind: Int, val cls: String,
                      val lat: Double, val lon: Double, val area: String, val areaAr: String, val areaEn: String)

    /** The best matches for [query] near [near] (ranked by how well the name answers, what it is, and distance). */
    fun search(st: TileStore, query: String, near: LatLon?, arabic: Boolean, limit: Int = 12): List<Place> {
        val q = TextMatch.norm(query)
        if (q.length < 2) return emptyList()
        val latin = ArabicQuery.latin(query)?.let { TextMatch.norm(it) }
        val skel = TextMatch.skeleton(q)
        val terms = listOfNotNull(q, latin).distinct()
        val where = StringBuilder()
        val args = ArrayList<String>()
        for (t in terms) { if (where.isNotEmpty()) where.append(" OR "); where.append("k LIKE ?"); args.add("%${t.replace("%", "")}%") }
        // the same consonants in the other script, from the start of a word (too loose for very short queries)
        if (skel.length >= 3) { where.append(" OR sk LIKE ?"); args.add("% ${skel}%") }
        val rows = ArrayList<Row>()
        // nearest first before the limit (common words match thousands of names; the far ones must be the ones cut)
        val order = near?.let {
            val k = kotlin.math.cos(Math.toRadians(it.lat)).let { c -> c * c }
            String.format(java.util.Locale.ROOT, " ORDER BY ((lat - %.6f) * (lat - %.6f) + (lon - %.6f) * (lon - %.6f) * %.6f)",
                it.lat, it.lat, it.lon, it.lon, k)
        } ?: ""
        try {
            st.db.rawQuery("SELECT name, ar, en, kind, cls, lat, lon, area, area_ar, area_en FROM places WHERE ($where)$order LIMIT 600", args.toTypedArray()).use { c ->
                while (c.moveToNext()) rows.add(Row(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4),
                    c.getDouble(5), c.getDouble(6), c.getString(7), c.getString(8), c.getString(9)))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "search: $t")
            return emptyList()
        }
        fun score(r: Row): Double {
            val text = listOf(r.name, r.ar, r.en).filter { it.isNotBlank() }.maxOf { n ->
                maxOf(TextMatch.score(query, n), latin?.let { TextMatch.score(it, n) } ?: 0.0)
            }
            val kind = when (r.kind) { CITY -> 0.25; AIRPORT -> 0.2; VILLAGE -> 0.15; POI -> 0.12; PARK -> 0.08; QUARTER -> 0.1; else -> 0.04 }
            val km = near?.let { Geo.distance(it, LatLon(r.lat, r.lon)) / 1000.0 } ?: 0.0
            return text * 2.0 + kind - (km / 150.0).coerceAtMost(0.6)
        }
        return rows.sortedByDescending { score(it) }.take(limit).map { r -> place(r, arabic) }
    }

    /** The named thing nearest to a dropped pin (within about 250 m), or null. */
    fun reverse(st: TileStore, pos: LatLon, arabic: Boolean): Place? {
        val d = 0.0025
        val rows = ArrayList<Row>()
        try {
            st.db.rawQuery("SELECT name, ar, en, kind, cls, lat, lon, area, area_ar, area_en FROM places WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                arrayOf((pos.lat - d).toString(), (pos.lat + d).toString(), (pos.lon - d).toString(), (pos.lon + d).toString())).use { c ->
                while (c.moveToNext()) rows.add(Row(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4),
                    c.getDouble(5), c.getDouble(6), c.getString(7), c.getString(8), c.getString(9)))
            }
        } catch (_: Throwable) {
            return null
        }
        val best = rows.minByOrNull { r ->
            Geo.distance(pos, LatLon(r.lat, r.lon)) + when (r.kind) { POI, AIRPORT, PARK -> 0.0; STREET -> 40.0; else -> 120.0 }
        } ?: return null
        if (Geo.distance(pos, LatLon(best.lat, best.lon)) > 300) return null
        return place(best, arabic).copy(pos = pos)
    }

    private fun place(r: Row, arabic: Boolean): Place {
        val name = if (arabic) r.ar.ifBlank { r.name } else r.en.ifBlank { r.name }
        val area = if (arabic) r.areaAr.ifBlank { r.area } else r.areaEn.ifBlank { r.area }
        val what = label(r.kind, r.cls, arabic)
        val detail = listOf(what, area).filter { it.isNotBlank() && it != name }.joinToString(if (arabic) "، " else ", ")
        return Place(name, detail, LatLon(r.lat, r.lon))
    }

    private fun label(kind: Int, cls: String, arabic: Boolean): String = when (kind) {
        CITY, VILLAGE -> ""
        QUARTER -> if (arabic) "حي" else "District"
        AIRPORT -> if (arabic) "مطار" else "Airport"
        STREET -> if (arabic) "طريق" else "Road"
        PARK -> if (arabic) "حديقة" else "Park"
        else -> POI_LABELS[cls]?.let { if (arabic) it.first else it.second } ?: ""
    }

    private val POI_LABELS = mapOf(
        "mall" to ("مول" to "Mall"), "department_store" to ("متجر" to "Store"), "supermarket" to ("سوبرماركت" to "Supermarket"),
        "hospital" to ("مستشفى" to "Hospital"), "clinic" to ("عيادة" to "Clinic"), "pharmacy" to ("صيدلية" to "Pharmacy"),
        "fuel" to ("محطة وقود" to "Fuel"), "university" to ("جامعة" to "University"), "college" to ("كلية" to "College"),
        "school" to ("مدرسة" to "School"), "place_of_worship" to ("مسجد" to "Mosque"), "mosque" to ("مسجد" to "Mosque"),
        "restaurant" to ("مطعم" to "Restaurant"), "fast_food" to ("مطعم" to "Restaurant"), "cafe" to ("مقهى" to "Café"),
        "bank" to ("بنك" to "Bank"), "atm" to ("صراف" to "ATM"), "hotel" to ("فندق" to "Hotel"),
        "police" to ("شرطة" to "Police"), "townhall" to ("بلدية" to "Municipality"), "stadium" to ("ملعب" to "Stadium"),
        "car_repair" to ("ورشة" to "Car repair"), "car_wash" to ("مغسلة" to "Car wash"), "bakery" to ("مخبز" to "Bakery")
    )
}
