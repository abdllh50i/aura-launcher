package com.abdllh.aura.nav.places

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import android.util.Log
import com.abdllh.aura.R
import java.io.File
import java.io.FileInputStream

/** Why places.db can or cannot be used. */
enum class PlacesState { UNKNOWN, READY, MISSING, TRUNCATED, NOT_PLACES, WRONG_NORM, WRONG_SCHEMA, BROKEN }

/** [why]: for the log and a debug line, with the numbers. [message]: for the driver. */
class PlacesStatus(val state: PlacesState, val why: String) {
    val usable: Boolean get() = state == PlacesState.READY

    /** A string resource, or 0 when there is nothing to tell. */
    val message: Int
        get() = when (state) {
            PlacesState.UNKNOWN, PlacesState.READY -> 0
            PlacesState.MISSING -> R.string.places_missing
            PlacesState.WRONG_NORM, PlacesState.WRONG_SCHEMA -> R.string.places_other_version
            PlacesState.TRUNCATED, PlacesState.NOT_PLACES, PlacesState.BROKEN -> R.string.places_damaged
        }
}

/** The file's first 100 bytes against its length, before SQLite sees it. No Android in here (src/test). */
internal object PlacesFile {
    const val HEADER = 100
    private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, at: Int) = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()

    /** Null when nothing is wrong. A pack that was cut short is told here, not by the first search that happens to reach the missing pages. */
    fun problem(head: ByteArray, got: Int, length: Long): PlacesStatus? {
        if (got < HEADER) return PlacesStatus(PlacesState.TRUNCATED, "only $length bytes")
        for (i in MAGIC.indices) if (head[i] != MAGIC[i]) return PlacesStatus(PlacesState.NOT_PLACES, "not an SQLite file")
        val raw = u16(head, 16)
        val pageSize = if (raw == 1) 65536L else raw.toLong()
        // a file in WAL mode cannot be read without a -shm beside it, which a read-only pack must not need
        if (head[18].toInt() != 1 || head[19].toInt() != 1) return PlacesStatus(PlacesState.NOT_PLACES, "journal format ${head[18]}/${head[19]}, not a rollback journal")
        val pages = u32(head, 28)
        if (pages > 0 && pageSize * pages > length) return PlacesStatus(PlacesState.TRUNCATED, "$length bytes of ${pageSize * pages}")
        return null
    }
}

/**
 * places.db, one read-only file (SEARCH_SPEC.md section 7): FTS4 for search, and SQLite 3.22's the firmware's. Only the engine's thread ([PlaceSearch]) calls this,
 * and everything it holds goes with [close].
 *
 * Never the framework's default error handler: on any report of corruption that one deletes the file, and the pack manager decides what happens to a pack, not
 * a search that read a bad page. No helper, no WAL: nothing here writes, and a read-only connection sets none of it.
 */
internal class PlacesDb private constructor(private val db: SQLiteDatabase, val syn: Syn, val cats: Map<Int, PlaceCat>, val catKeys: Map<String, PlaceCat>) {

    /** Set by the handler: the file reported corruption. The engine closes it, and its status says so until the file changes. */
    @Volatile var corrupt = false

    fun close() {
        try { db.close() } catch (t: Throwable) { Log.w(TAG, "close: $t") }
    }

    /** Rows of table p for [COLUMNS]-shaped SQL. [pool]: a row read before in this search comes back as the same object, without reading its strings again. */
    fun rows(sql: String, args: Array<String>?, cs: CancellationSignal?, pool: HashMap<Int, Row>? = null): ArrayList<Row> {
        val out = ArrayList<Row>()
        db.rawQuery(sql, args, cs).use { c ->
            while (c.moveToNext()) {
                val id = c.getInt(0)
                var r = pool?.get(id)
                if (r == null) {
                    r = Row(id, c.getInt(1), c.getString(2) ?: "", if (c.isNull(3)) null else c.getString(3), c.getInt(4), c.getInt(5), c.getInt(6),
                        if (c.isNull(7)) 0 else c.getInt(7), c.getInt(8), c.getInt(9))
                    pool?.put(id, r)
                }
                out.add(r)
            }
        }
        return out
    }

    fun oneInt(sql: String, cs: CancellationSignal?): Int? = db.rawQuery(sql, null, cs).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }

    /** Rows of table area by id, in pieces (SQLite's limit on terms is no concern at 400). */
    fun areas(ids: Collection<Int>, cs: CancellationSignal?): HashMap<Int, Area> {
        val out = HashMap<Int, Area>()
        if (ids.isEmpty()) return out
        val list = ids.toList()
        var i = 0
        while (i < list.size) {
            val part = list.subList(i, minOf(i + 400, list.size))
            db.rawQuery("SELECT id,ar,en,city_ar,city_en FROM area WHERE id IN (${part.joinToString(",")})", null, cs).use { c ->
                while (c.moveToNext()) out[c.getInt(0)] = Area(c.getInt(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "")
            }
            i += 400
        }
        return out
    }

    fun area(id: Int, cs: CancellationSignal?): Area? = areas(listOf(id), cs)[id]

    /** Table spot in a band of latitude, by latitude. */
    fun spots(south: Int, north: Int, cs: CancellationSignal?): ArrayList<SpotRow> {
        val out = ArrayList<SpotRow>()
        db.rawQuery("SELECT kind,lat,lon,dir,kmh,src,j FROM spot WHERE lat BETWEEN $south AND $north ORDER BY lat", null, cs).use { c ->
            while (c.moveToNext()) {
                out.add(SpotRow(c.getInt(0), c.getInt(1), c.getInt(2), if (c.isNull(3)) null else c.getInt(3), if (c.isNull(4)) null else c.getInt(4), c.getInt(5), if (c.isNull(6)) null else c.getInt(6)))
            }
        }
        return out
    }

    /** Table gate in a band of latitude, by latitude. */
    fun gates(south: Int, north: Int, cs: CancellationSignal?): ArrayList<GateRow> {
        val out = ArrayList<GateRow>()
        db.rawQuery("SELECT lat,lon,sub,road FROM gate WHERE lat BETWEEN $south AND $north ORDER BY lat", null, cs).use { c ->
            while (c.moveToNext()) out.add(GateRow(c.getInt(0), c.getInt(1), c.getInt(2), if (c.isNull(3)) 8 else c.getInt(3)))
        }
        return out
    }

    /** The zones whose box meets a box. */
    fun zones(south: Double, north: Double, west: Double, east: Double, cs: CancellationSignal?): ArrayList<ZoneRow> =
        zoneRows("SELECT id,kind,name,en,s,n,w,e,ring FROM zone WHERE s<=$north AND n>=$south AND w<=$east AND e>=$west", cs)

    fun zonesOfKind(kind: Int, cs: CancellationSignal?): ArrayList<ZoneRow> = zoneRows("SELECT id,kind,name,en,s,n,w,e,ring FROM zone WHERE kind=$kind ORDER BY id", cs)

    private fun zoneRows(sql: String, cs: CancellationSignal?): ArrayList<ZoneRow> {
        val out = ArrayList<ZoneRow>()
        db.rawQuery(sql, null, cs).use { c ->
            while (c.moveToNext()) {
                out.add(ZoneRow(c.getInt(0), c.getInt(1), c.getString(2), c.getString(3), c.getDouble(4), c.getDouble(5), c.getDouble(6), c.getDouble(7), c.getString(8) ?: ""))
            }
        }
        return out
    }

    companion object {
        private const val TAG = "AuraPlaces"
        /** What this code was written for. */
        const val SCHEMA = 2
        /** 128 pages of 4 KB. The file cache holds the rest (SEARCH_SPEC.md: the time goes into reading rows, and they are read once a search). */
        private const val CACHE_PAGES = 128
        /** Its SQL carries the car's position, so hardly one is seen twice: each kept statement would only be memory. */
        private const val STATEMENTS = 4
        const val COLUMNS = "p.id,p.kind,p.name,p.alt,p.cat,p.lat,p.lon,p.area,p.rank,p.src"

        /** Opens and checks: a [PlacesDb], or null and why not. Never throws. */
        fun open(file: File): Pair<PlacesDb?, PlacesStatus> {
            val length = file.length()
            if (!file.isFile || length == 0L) return null to PlacesStatus(PlacesState.MISSING, "no file at ${file.path}")
            var db: SQLiteDatabase? = null
            try {
                val head = ByteArray(PlacesFile.HEADER)
                val got = FileInputStream(file).use { it.read(head) }
                PlacesFile.problem(head, got, length)?.let { return null to it }
                var holder: PlacesDb? = null
                db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                    DatabaseErrorHandler { Log.e(TAG, "corruption reported in ${file.name}"); holder?.corrupt = true })
                db.setMaxSqlCacheSize(STATEMENTS)
                db.rawQuery("PRAGMA cache_size=$CACHE_PAGES", null).use { it.moveToFirst() }
                val meta = HashMap<String, String>()
                try {
                    db.rawQuery("SELECT k,v FROM meta WHERE k IN ('norm_version','schema')", null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) }
                } catch (t: SQLiteException) {
                    db.close()
                    return null to PlacesStatus(PlacesState.NOT_PLACES, "no table meta: $t")
                }
                val norm = meta["norm_version"]
                if (norm?.trim()?.toIntOrNull() != Norm.VERSION) {
                    db.close()
                    return null to PlacesStatus(PlacesState.WRONG_NORM, "places.db has norm version $norm, this app knows ${Norm.VERSION}")
                }
                val schema = meta["schema"]
                if (schema?.trim()?.toIntOrNull() != SCHEMA) {
                    db.close()
                    return null to PlacesStatus(PlacesState.WRONG_SCHEMA, "places.db has schema $schema, this app knows $SCHEMA")
                }
                val cats = HashMap<Int, PlaceCat>()
                val keys = HashMap<String, PlaceCat>()
                db.rawQuery("SELECT id,key,ar,en,icon FROM cat", null).use { c ->
                    while (c.moveToNext()) {
                        val x = PlaceCat(c.getInt(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getInt(4))
                        cats[x.id] = x
                        keys[x.key] = x
                    }
                }
                val syn = Syn(keys["airport"]?.id ?: -1)
                db.rawQuery("SELECT w,cats,role,canon FROM syn ORDER BY w", null).use { c ->
                    while (c.moveToNext()) syn.add(c.getString(0), c.getString(1), c.getInt(2), c.getString(3))
                }
                val places = PlacesDb(db, syn, cats, keys)
                holder = places
                return places to PlacesStatus(PlacesState.READY, "norm ${Norm.VERSION}, schema $SCHEMA")
            } catch (t: Throwable) {
                try { db?.close() } catch (_: Throwable) { }
                Log.w(TAG, "open: $t")
                return null to PlacesStatus(PlacesState.BROKEN, "$t")
            }
        }
    }
}
