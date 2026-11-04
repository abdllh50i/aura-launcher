package com.abdllh.aura.nav.pack

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.io.FileInputStream

/**
 * The last look before a switch: the hashes say the bytes are the ones built on the PC, this says this unit can open them. Read only: nothing here writes beside
 * the files (none is in WAL mode, make_pack.py refuses that).
 */
internal object PackVerify {
    /** What is wrong, or null. */
    fun problem(dir: File, m: PackManifest): String? {
        tar(File(dir, PackPaths.ROUTE))?.let { return it }
        // (a file whose hash is right and SQLite cannot open is a wrong pack, not a storage error; each try would end the same)
        try {
            places(File(dir, PackPaths.PLACES), m)?.let { return it }
            tiles(File(dir, PackPaths.BASE))?.let { return it }
            tiles(File(dir, PackPaths.POIS))?.let { return it }
            glyphs(File(dir, PackPaths.GLYPHS), m)?.let { return it }
        } catch (t: SQLiteException) {
            return "cannot be opened: $t"
        }
        return null
    }

    /** Android's default handler deletes a database it finds corrupt. Here it stays: whoever looks at it can see why, and a cancel takes it away. */
    private val KEEP = DatabaseErrorHandler { d -> android.util.Log.w("AuraPack", "corrupt: ${d.path}") }

    /** Valhalla's scan-free start needs index.bin as the first entry (tools/mapbuild/tar_start_check.sh). */
    internal fun tar(f: File): String? {
        val h = ByteArray(512)
        val n = FileInputStream(f).use { it.read(h) }
        if (n < 512) return "${f.name}: too short"
        val name = String(h, 0, 100, Charsets.ISO_8859_1).substringBefore('\u0000').removePrefix("./")
        if (name != "index.bin") return "${f.name} starts with $name, not index.bin"
        if (!String(h, 257, 5, Charsets.ISO_8859_1).startsWith("ustar")) return "${f.name}: not a tar"
        return null
    }

    private inline fun <T> db(f: File, block: (SQLiteDatabase) -> T): T {
        val d = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS, KEEP)
        try { return block(d) } finally { d.close() }
    }

    private fun one(d: SQLiteDatabase, sql: String): String? =
        d.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun places(f: File, m: PackManifest): String? {
        db(f) { d ->
            val q = one(d, "PRAGMA quick_check")
            if (q != "ok") return "${f.name}: quick_check says $q"
            val schema = one(d, "SELECT v FROM meta WHERE k='schema'")
            val norm = one(d, "SELECT v FROM meta WHERE k='norm_version'")
            if (schema != m.placesSchema.toString() || norm != m.normVersion.toString()) return "${f.name}: schema $schema, norm $norm; manifest ${m.placesSchema}, ${m.normVersion}"
            if (one(d, "SELECT count(*) FROM (SELECT 1 FROM p LIMIT 1)") != "1") return "${f.name}: no places"
        }
        return null
    }

    private fun tiles(f: File): String? {
        db(f) { d ->
            if (one(d, "SELECT value FROM metadata WHERE name='format'") != "pbf") return "${f.name}: format is not pbf"
            if (one(d, "SELECT count(*) FROM (SELECT tile_data FROM tiles LIMIT 1)") != "1") return "${f.name}: no tiles"
        }
        return null
    }

    private fun glyphs(f: File, m: PackManifest): String? {
        db(f) { d ->
            for (stack in m.fonts) for (range in listOf("0-255", "1536-1791", "65024-65279")) { // Latin, Arabic, the joined forms (make_pack.py)
                val has = d.rawQuery("SELECT 1 FROM glyphs WHERE name=?", arrayOf("$stack/$range")).use { it.moveToFirst() }
                if (!has) return "${f.name}: no $stack/$range"
            }
            if (one(d, "SELECT count(*) FROM (SELECT 1 FROM glyphs LIMIT 1)") != "1") return "${f.name}: empty"
        }
        return null
    }
}
