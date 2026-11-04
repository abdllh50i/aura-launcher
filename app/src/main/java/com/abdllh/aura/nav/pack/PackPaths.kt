package com.abdllh.aura.nav.pack

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where the map's files are right now: ask here, every part, every time a file is opened. Null means no verified pack holds it (use what
 * there is without it: the old offline map, online tiles, BRouter). Never a half-written file: only [PackManager]'s last step changes the
 * answer, and it changes all five together. No Context kept and nothing but a small file read, so it works in the app's other processes
 * (`:route`) as well; there it notices a new pack by the pointer file changing, [addListener] is for this process only.
 *
 *   files/maps2/current         the id of the pack in use, one line, replaced by an atomic rename
 *   files/maps2/packs/<id>/     a verified pack: its pieces (read only), notices, manifest.json
 *   files/maps2/staging/<id>/   one on its way; nothing outside PackManager looks in here
 *
 * A file already open keeps working after a switch: the old pack stays until the new one has worked once ([noteWorked]), and Linux keeps a
 * deleted file for whoever still has it open.
 */
object PackPaths {
    const val BASE = "base.mbtiles"
    const val ROUTE = "route.tar"
    const val PLACES = "places.db"
    const val POIS = "places.mbtiles"
    const val GLYPHS = "glyphs.db"
    private const val TAG = "AuraPack"
    private const val POINTER = "current"
    private const val WORKED = "worked"

    fun routeTar(ctx: Context): File? = piece(ctx, ROUTE)
    fun placesDb(ctx: Context): File? = piece(ctx, PLACES)
    fun baseMbtiles(ctx: Context): File? = piece(ctx, BASE)
    fun placesMbtiles(ctx: Context): File? = piece(ctx, POIS)
    fun glyphsDb(ctx: Context): File? = piece(ctx, GLYPHS)

    /** The pack in use, or null. [manifest] has its date, its data's versions (normVersion, placesSchema, valhalla) and the attribution line. */
    class Current(val id: String, val dir: File, val manifest: PackManifest) {
        /** A notice or licence text that came with the pack (NOTICE-PACK.md, ...), or null. */
        fun notice(name: String): File? = manifest.notices.firstOrNull { it.url == name }?.let { File(dir, it.url) }?.takeIf { it.isFile }
    }

    fun current(ctx: Context): Current? {
        synchronized(this) {
            try {
                val pointer = pointer(ctx)
                val stamp = pointer.lastModified()
                if (stamp == 0L) { cached = null; cachedStamp = 0L; return null }
                if (stamp == cachedStamp) return cached
                cachedStamp = stamp
                cached = null
                val id = pointer.readText().trim()
                if (!PackRules.safeName(id)) return null
                val dir = File(packs(ctx), id)
                // (an app that a later update made too old for the pack: the pack is not used, the map falls back like with none)
                val m = PackManifest.parse(File(dir, "manifest.json").readText(), appCode(ctx))
                if (m.id != id) return null
                cached = Current(id, dir, m)
                return cached
            } catch (t: Throwable) {
                Log.w(TAG, "current: $t")
                return null
            }
        }
    }

    /** A file of the pack in use, if it is there and whole (its size; its hash was checked before the switch). */
    fun piece(ctx: Context, name: String): File? {
        val c = current(ctx) ?: return null
        val p = c.manifest.piece(name) ?: return null
        val f = File(c.dir, p.name)
        return if (f.isFile && f.length() == p.size) f else null
    }

    /**
     * The pack in use has just served a real tile, route or search. Say it once per start of a process that uses the pack: until then
     * files/offline/map.db and an older pack stay, whatever the space. Cheap and safe to call often.
     */
    fun noteWorked(ctx: Context) {
        val c = current(ctx) ?: return
        val mark = File(c.dir, WORKED)
        if (notedFor == c.id) return
        notedFor = c.id
        try {
            if (!mark.exists()) PackFiles.writeAtomic(mark, System.currentTimeMillis().toString())
        } catch (t: Throwable) {
            Log.w(TAG, "worked: $t")
        }
        changed()
    }

    fun hasWorked(ctx: Context): Boolean = current(ctx)?.let { File(it.dir, WORKED).isFile } ?: false

    /** Called on the main thread after the pack in use changed in this process. Whoever keeps a file open reopens it here. */
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var cached: Current? = null
    private var cachedStamp = 0L
    @Volatile private var notedFor: String? = null
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** The answers may differ now: forget what is held, tell who listens. Any thread. */
    internal fun changed() {
        synchronized(this) { cachedStamp = 0L; cached = null }
        main.post { for (l in listeners) try { l() } catch (t: Throwable) { Log.w(TAG, "listener: $t") } }
    }

    /** Debug builds can test beside another agent's files on a shared emulator (`setprop debug.aura.pack.root maps2-mine`). */
    private val rootName: String by lazy {
        if (com.abdllh.aura.BuildConfig.DEBUG) com.abdllh.aura.system.SystemProps.get("debug.aura.pack.root").takeIf { PackRules.safeName(it) } ?: "maps2" else "maps2"
    }

    internal fun root(ctx: Context): File = File(ctx.applicationContext.filesDir, rootName)
    internal fun pointer(ctx: Context): File = File(root(ctx), POINTER)
    internal fun packs(ctx: Context): File = File(root(ctx), "packs")
    internal fun staging(ctx: Context): File = File(root(ctx), "staging")
    internal fun trash(ctx: Context): File = File(root(ctx), "trash")

    internal fun appCode(ctx: Context): Long = com.abdllh.aura.BuildConfig.VERSION_CODE.toLong()
}
