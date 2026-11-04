package com.abdllh.aura.nav.offline

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.system.SystemProps
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** The three files of the map pack the map itself reads; a file the unit does not hold is null. */
interface MapPackPaths {
    val base: File?
    val places: File?
    val glyphs: File?
}

/**
 * Until the pack's own PackPaths is merged: files/maps2/current/, which the pack manager switches when a new pack is whole.
 * Debug builds can read another folder of files/maps2 (setprop debug.aura.pack name): the emulator is shared, and another
 * agent's pack may be in current.
 */
class CurrentPackFolder(private val filesDir: File) : MapPackPaths {
    private fun file(name: String): File? {
        val folder = if (BuildConfig.DEBUG) SystemProps.get("debug.aura.pack").ifBlank { "current" } else "current"
        return File(File(File(filesDir, "maps2"), folder), name).takeIf { it.length() > 0L }
    }
    override val base get() = file("base.mbtiles")
    override val places get() = file("places.mbtiles")
    override val glyphs get() = file("glyphs.db")
}

/**
 * The map pack as the tile server reads it: read only, never written, and not [TileStore], which creates tables and turns on
 * WAL. An answer is the tile's bytes as stored (gzip, TileServer passes them on), [EMPTY] for a tile or a font range the pack
 * has no row for, or null when the file cannot be read: the caller then goes on as if there were no pack, so a damaged pack
 * costs the map its offline side, never a crash or a wait.
 *  - Opened on a tile thread at the first request, never on the main thread. The style only asks [present].
 *  - A failed open is tried again a minute later (the pack manager may be about to replace the file).
 *  - Android's default error handler deletes a database it finds corrupt. This file is a download of minutes: it stays, and
 *    the pack manager's check against the manifest decides about it.
 */
object PackTiles {
    private const val TAG = "AuraPack"
    private const val RETRY_MS = 60_000L
    private val KEEP_THE_FILE = DatabaseErrorHandler { db -> Log.w(TAG, "corrupt: ${db.path}") }
    val EMPTY = ByteArray(0)

    /** Where the pack is (set once, at the start: [OfflineMaps.init]). */
    @Volatile var paths: MapPackPaths? = null

    private class Reader(val pick: (MapPackPaths) -> File?) {
        @Volatile var db: SQLiteDatabase? = null
        @Volatile var path: String? = null
        @Volatile var failedAt = 0L

        fun open(): SQLiteDatabase? {
            db?.let { return it }
            synchronized(this) {
                db?.let { return it }
                if (failedAt != 0L && SystemClock.elapsedRealtime() - failedAt < RETRY_MS) return null
                val f = paths?.let(pick) ?: return null
                return try {
                    val t0 = SystemClock.elapsedRealtime()
                    SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS, KEEP_THE_FILE).also {
                        db = it
                        path = f.path
                        failedAt = 0L
                        Log.i(TAG, "opened ${f.name}, ${f.length() / 1_048_576} MB, ${SystemClock.elapsedRealtime() - t0} ms")
                    }
                } catch (t: Throwable) {
                    failedAt = SystemClock.elapsedRealtime()
                    Log.w(TAG, "${f.name}: $t")
                    null
                }
            }
        }

        fun failed(t: Throwable) {
            Log.w(TAG, "read: $t")
            close()
            failedAt = SystemClock.elapsedRealtime()
        }

        fun close() = synchronized(this) {
            try { db?.close() } catch (_: Throwable) { }
            db = null
            failedAt = 0L
        }
    }

    private val base = Reader { it.base }
    private val places = Reader { it.places }
    private val glyphs = Reader { it.glyphs }

    /** Whether the unit holds a pack the map should draw from: its files are there. Reads nothing, so the main thread may ask, and once it is open not even that (the tile server asks per tile). */
    val present: Boolean get() = base.db != null || try { paths?.base != null } catch (_: Throwable) { false }
    /** Whether the labels can be in the pack's fonts. */
    val hasFonts: Boolean get() = glyphs.db != null || try { paths?.glyphs != null } catch (_: Throwable) { false }
    val hasPlaces: Boolean get() = places.db != null || try { paths?.places != null } catch (_: Throwable) { false }

    /** The pack was switched or removed: the next request opens what is there now. */
    fun changed() {
        base.close(); places.close(); glyphs.close()
    }

    /** A tile of base.mbtiles (compact: tiles is a view). */
    fun base(z: Int, x: Int, y: Int): ByteArray? = tile(base, z, x, y)

    /** A tile of places.mbtiles. */
    fun places(z: Int, x: Int, y: Int): ByteArray? = tile(places, z, x, y)

    private fun tile(r: Reader, z: Int, x: Int, y: Int): ByteArray? {
        val db = r.open() ?: return null
        val t0 = SystemClock.elapsedRealtimeNanos()
        return try {
            // (MBTiles counts its rows from the south; a bound argument is text to SQLite, so the cast)
            db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=CAST(? AS INTEGER) AND tile_column=CAST(? AS INTEGER) AND tile_row=CAST(? AS INTEGER)",
                arrayOf(z.toString(), x.toString(), ((1 shl z) - 1 - y).toString())).use { c ->
                if (c.moveToFirst()) c.getBlob(0) else EMPTY
            }.also { note(SystemClock.elapsedRealtimeNanos() - t0) }
        } catch (t: Throwable) {
            r.failed(t)
            null
        }
    }

    /** A glyph range, "stack/first-last". */
    fun glyph(name: String): ByteArray? {
        val db = glyphs.open() ?: return null
        return try {
            db.rawQuery("SELECT data FROM glyphs WHERE name=?", arrayOf(name)).use { c -> if (c.moveToFirst()) c.getBlob(0) else EMPTY }
        } catch (t: Throwable) {
            glyphs.failed(t)
            null
        }
    }

    // ---- what the reads cost (Settings' diagnostics, the emulator's tests)
    private val reads = AtomicLong()
    private val readNanos = AtomicLong()
    private val worstNanos = AtomicLong()

    private fun note(ns: Long) {
        reads.incrementAndGet()
        readNanos.addAndGet(ns)
        while (true) {
            val w = worstNanos.get()
            if (ns <= w || worstNanos.compareAndSet(w, ns)) break
        }
    }

    /** Tiles read, their mean and their worst time in microseconds; the counts start again. */
    fun readStats(): String {
        val n = reads.getAndSet(0)
        val all = readNanos.getAndSet(0)
        val worst = worstNanos.getAndSet(0)
        return "$n tiles read, mean ${if (n > 0) all / n / 1000 else 0} us, worst ${worst / 1000} us"
    }
}
