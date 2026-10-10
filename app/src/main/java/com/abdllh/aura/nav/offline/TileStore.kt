package com.abdllh.aura.nav.offline

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The offline map on disk (one SQLite file): vector tiles as the server sent them (gzip), the font glyphs the map
 * labels need, the place index for search, and a few facts about the download. WAL: the tile server reads while the
 * download writes.
 */
class TileStore(val file: File) {
    val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
    )

    init {
        db.execSQL("CREATE TABLE IF NOT EXISTS tiles (k INTEGER PRIMARY KEY, data BLOB NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS glyphs (name TEXT PRIMARY KEY, data BLOB NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)")
    }

    fun tile(key: Long): ByteArray? =
        db.rawQuery("SELECT data FROM tiles WHERE k = ?", arrayOf(key.toString())).use { if (it.moveToFirst()) it.getBlob(0) else null }

    /** The tiles already on disk (the download skips them). */
    fun keys(): HashSet<Long> {
        val out = HashSet<Long>()
        db.rawQuery("SELECT k FROM tiles", null).use { while (it.moveToNext()) out.add(it.getLong(0)) }
        return out
    }

    fun putTiles(batch: List<Pair<Long, ByteArray>>) {
        if (batch.isEmpty()) return
        db.beginTransaction()
        try {
            val st = db.compileStatement("INSERT OR REPLACE INTO tiles (k, data) VALUES (?, ?)")
            for ((k, data) in batch) {
                st.bindLong(1, k)
                st.bindBlob(2, data)
                st.executeInsert()
                st.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The tiles of one zoom level, in key order, a page at a time (the place index reads them all). */
    fun forEachTile(z: Int, each: (Long, ByteArray) -> Unit) {
        val lo = TileMath.key(z, 0, 0)
        val hi = TileMath.key(z + 1, 0, 0)
        var from = lo
        while (true) {
            var last = -1L
            db.rawQuery("SELECT k, data FROM tiles WHERE k >= ? AND k < ? ORDER BY k LIMIT 200", arrayOf(from.toString(), hi.toString())).use {
                while (it.moveToNext()) {
                    last = it.getLong(0)
                    each(last, it.getBlob(1))
                }
            }
            if (last < 0) return
            from = last + 1
        }
    }

    fun count(z: Int? = null): Long = if (z == null) DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM tiles", null)
        else DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM tiles WHERE k >= ? AND k < ?",
            arrayOf(TileMath.key(z, 0, 0).toString(), TileMath.key(z + 1, 0, 0).toString()))

    fun glyph(name: String): ByteArray? =
        db.rawQuery("SELECT data FROM glyphs WHERE name = ?", arrayOf(name)).use { if (it.moveToFirst()) it.getBlob(0) else null }

    fun putGlyph(name: String, data: ByteArray) {
        val st = db.compileStatement("INSERT OR REPLACE INTO glyphs (name, data) VALUES (?, ?)")
        st.bindString(1, name)
        st.bindBlob(2, data)
        st.executeInsert()
    }

    fun meta(k: String): String? =
        db.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMeta(k: String, v: String) {
        val st = db.compileStatement("INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)")
        st.bindString(1, k)
        st.bindString(2, v)
        st.executeInsert()
    }

    /** The file and its write-ahead log, in bytes. */
    fun bytes(): Long = file.length() + File(file.path + "-wal").length()

    fun close() = try { db.close() } catch (_: Throwable) { }
}
