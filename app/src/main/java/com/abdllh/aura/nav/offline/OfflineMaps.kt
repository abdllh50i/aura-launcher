package com.abdllh.aura.nav.offline

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.nav.Places
import com.abdllh.aura.util.Prefs
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The offline map of the Eastern Province (Settings › Navigation): downloaded once, then the map, the routes and the
 * search work without internet there. One download fetches BRouter's routing data (brouter.de), the font glyphs of the
 * map labels and every map tile of [Region] (OpenFreeMap), then builds the search index ([PlaceIndex]) from the tiles.
 * It can be paused, goes on where it stopped (also after a restart), and waits out internet drops.
 */
object OfflineMaps {
    private const val TAG = "AuraOffline"
    private const val KEY_STATE = "offlineMapState"     // none | downloading | ready
    private const val KEY_UPDATED = "offlineMapUpdated" // when it was completed (ms)
    private const val SEGMENTS_URL = "https://brouter.de/brouter/segments4/"
    private const val WORKERS = 6
    private val FONT_STACKS = listOf("Noto Sans Regular", "Noto Sans Bold")
    // Latin, Arabic (with its presentation forms), punctuation and digits: what the labels of this map use
    private val GLYPH_RANGES = listOf(0, 256, 512, 768, 1536, 1792, 8192, 8448, 64256, 64512, 64768, 65024, 65280)
        .map { "$it-${it + 255}" }

    enum class Phase { NONE, DOWNLOADING, INDEXING, READY, PAUSED, FAILED }

    class Status(val phase: Phase, val done: Long = 0, val total: Long = 0, val bytes: Long = 0, val error: String? = null,
                 val updatedAt: Long = 0, val sizeBytes: Long = 0) {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    @Volatile var status = Status(Phase.NONE); private set
    /** Called on the main thread when [status] changes (the settings card, while it shows). */
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    @Volatile private var store: TileStore? = null
    @Volatile private var running = false
    @Volatile private var stop = false
    private var lastNotify = 0L

    /** The application context once [init] ran (the offline router needs it), else null. */
    val context: Context? get() = app

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "offline").apply { mkdirs() }

    /** At start: the local tile server, the offline map if there is one, and a download to finish if one was going on. */
    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        TileServer.start()
        val db = File(dir(c), "map.db")
        if (db.exists()) {
            try {
                openStore(db)
            } catch (t: Throwable) {
                // a damaged file must not take the home screen down: set aside, downloaded again when asked
                Log.w(TAG, "offline map unreadable: $t")
                db.renameTo(File(db.path + ".bad"))
                Prefs.raw.edit().putString(KEY_STATE, "none").apply()
                return
            }
        }
        when (Prefs.raw.getString(KEY_STATE, "none")) {
            "ready" -> status = Status(Phase.READY, updatedAt = Prefs.raw.getLong(KEY_UPDATED, 0L), sizeBytes = size(c))
            "downloading" -> {
                status = Status(Phase.PAUSED, sizeBytes = size(c))
                main.postDelayed(autoResume, 30_000L) // where it stopped (the unit slept or restarted)
            }
        }
    }

    /** Going on with a download a restart interrupted (cancelled by any choice the user makes meanwhile). */
    private val autoResume = Runnable { download() }

    /** The offline map's store when search can use it. */
    fun searchStore(): TileStore? = store?.takeIf { status.phase == Phase.READY || PlaceIndex.ready(it) }

    val ready: Boolean get() = status.phase == Phase.READY

    fun download() {
        val c = app ?: return
        main.removeCallbacks(autoResume)
        if (running) return
        running = true
        stop = false
        Prefs.raw.edit().putString(KEY_STATE, "downloading").apply()
        set(Status(Phase.DOWNLOADING, sizeBytes = size(c)), force = true)
        Thread({ work(c) }, "aura-offline").start()
    }

    fun pause() {
        main.removeCallbacks(autoResume)
        stop = true
    }

    /** Everything removed (the download is stopped first). */
    fun delete(done: () -> Unit) {
        val c = app ?: return
        main.removeCallbacks(autoResume)
        stop = true
        Thread({
            while (running) SystemClock.sleep(100)
            TileServer.store = null
            store?.close()
            store = null
            dir(c).deleteRecursively()
            Prefs.raw.edit().putString(KEY_STATE, "none").remove(KEY_UPDATED).apply()
            set(Status(Phase.NONE), force = true)
            main.post(done)
        }, "aura-offline-delete").start()
    }

    private fun openStore(f: File): TileStore = TileStore(f).also { store = it; TileServer.store = it }

    // -------------------------------------------------------------------------------------------------------- work
    private fun work(c: Context) {
        try {
            val base = dir(c)
            if (base.usableSpace < 600L * 1024 * 1024) throw IOException("space")
            val st = store ?: openStore(File(base, "map.db"))
            // debug builds can take a small test area instead (setprop debug.aura.offline small)
            val small = com.abdllh.aura.BuildConfig.DEBUG && com.abdllh.aura.system.SystemProps.get("debug.aura.offline") == "small"
            val segments = if (small) Region.SEGMENTS.take(2) else Region.SEGMENTS
            val tiles = Region.tiles(small)
            val have = st.keys()
            val todo = tiles.filter { it !in have }
            // progress in one bar: routing data counts as about as many units as 1500 tiles, the road tiles (known
            // only once the zoom-12 tiles are in) are guessed until then
            val segUnits = 1500L
            val roadGuess = if (small) 300L else 4000L
            val total = AtomicLong(segUnits + tiles.size + GLYPH_RANGES.size * FONT_STACKS.size + roadGuess)
            val done = AtomicLong((tiles.size - todo.size).toLong())
            val bytes = AtomicLong(0)
            val failures = AtomicInteger(0) // in a row: the internet is down, the download waits ("waiting")
            fun progress(phase: Phase = Phase.DOWNLOADING) = set(Status(phase, done.get(), total.get(), bytes.get(),
                if (failures.get() >= WORKERS) "waiting" else null, sizeBytes = st.bytes()))

            // 1. routing data
            val segDir = File(base, "segments4").apply { mkdirs() }
            for (seg in segments) {
                check()
                val f = File(segDir, "$seg.rd5")
                if (!f.isFile) fetchFile(SEGMENTS_URL + "$seg.rd5", f) { n -> bytes.addAndGet(n); progress() }
                done.addAndGet(segUnits / segments.size)
                progress()
            }

            // 2. the font glyphs of the labels: all of them, or the download stops here (a missing range would keep
            //    every tile whose labels need it from being drawn offline)
            for (stack in FONT_STACKS) for (range in GLYPH_RANGES) {
                check()
                val name = "$stack/$range"
                if (st.glyph(name) == null) {
                    val b = retry(8) { get(TileServer.glyphUrl(stack, range)) } ?: throw IOException("internet")
                    st.putGlyph(name, b)
                    bytes.addAndGet(b.size.toLong())
                }
                done.incrementAndGet()
            }
            progress()

            // 3. the tiles, side by side; then the street-level tiles along the main roads between the towns. With
            //    the current tile address: a remembered one may name a weekly version that is gone (every tile "missing")
            val template = AtomicReference(retry(6) { TileServer.freshTemplate() } ?: throw IOException("internet"))
            val lastFresh = AtomicLong(SystemClock.elapsedRealtime())
            fun freshen() = synchronized(template) {
                if (SystemClock.elapsedRealtime() - lastFresh.get() > 60_000L) {
                    lastFresh.set(SystemClock.elapsedRealtime())
                    TileServer.freshTemplate()?.let { template.set(it) }
                }
            }
            val workerError = AtomicReference<Throwable?>(null)
            fun fetchTiles(list: List<Long>) {
                val next = AtomicInteger(0)
                val pool = Executors.newFixedThreadPool(WORKERS)
                val lock = Any()
                repeat(WORKERS) {
                    pool.execute {
                        val batch = ArrayList<Pair<Long, ByteArray>>()
                        fun flush() { if (batch.isNotEmpty()) { synchronized(lock) { st.putTiles(batch) }; batch.clear() } }
                        try {
                            while (!stop && workerError.get() == null) {
                                val i = next.getAndIncrement()
                                if (i >= list.size) break
                                val k = list[i]
                                var data: ByteArray? = null
                                var attempt = 0
                                var notFound = 0
                                while (data == null && !stop) {
                                    val url = template.get().replace("{z}", "${TileMath.z(k)}").replace("{x}", "${TileMath.x(k)}").replace("{y}", "${TileMath.y(k)}")
                                    try {
                                        data = getTile(url)
                                    } catch (e: NotFound) {
                                        // under a fresh address too: no tile there; else the address may be gone
                                        if (++notFound >= 2) data = ByteArray(0) else freshen()
                                    } catch (e: IOException) {
                                        // the internet is down (or the server busy): wait, and keep trying while not paused
                                        failures.incrementAndGet()
                                        attempt++
                                        progress()
                                        nap((2000L * attempt).coerceAtMost(30_000L))
                                    }
                                }
                                if (data == null) break
                                failures.set(0)
                                batch.add(k to data)
                                bytes.addAndGet(data.size.toLong())
                                done.incrementAndGet()
                                if (batch.size >= 25) flush()
                                progress()
                            }
                            flush()
                        } catch (t: Throwable) {
                            // anything else (a full disk, a database error): the whole download stops, reported below
                            workerError.compareAndSet(null, t)
                        }
                    }
                }
                pool.shutdown()
                while (!pool.awaitTermination(500, TimeUnit.MILLISECONDS)) progress()
                workerError.get()?.let { throw it }
                check()
            }
            fetchTiles(todo)
            val roads = Region.roadTiles(st, small)
            val have2 = st.keys()
            val roadTodo = roads.filter { it !in have2 }
            total.addAndGet(roads.size - roadGuess)
            done.addAndGet((roads.size - roadTodo.size).toLong())
            Log.i(TAG, "road tiles: ${roads.size} (${roadTodo.size} to fetch)")
            fetchTiles(roadTodo)

            // 4. the search index
            set(Status(Phase.INDEXING, 0, 1, bytes.get(), sizeBytes = st.bytes()), force = true)
            PlaceIndex.build(st, { d, t -> set(Status(Phase.INDEXING, d.toLong(), t.toLong(), bytes.get(), sizeBytes = st.bytes())) }, { stop })
            check()
            st.setMeta("completed", System.currentTimeMillis().toString())
            st.setMeta("tiles", template.get())
            val now = System.currentTimeMillis()
            Prefs.raw.edit().putString(KEY_STATE, "ready").putLong(KEY_UPDATED, now).apply()
            set(Status(Phase.READY, updatedAt = now, sizeBytes = size(c)), force = true)
            Log.i(TAG, "offline map ready: ${st.count()} tiles, ${size(c) / 1_048_576} MB")
        } catch (e: InterruptedException) {
            set(Status(Phase.PAUSED, status.done, status.total, status.bytes, sizeBytes = size(c)), force = true)
        } catch (t: Throwable) {
            Log.w(TAG, "offline map: $t")
            val why = when {
                stop -> null
                t.message == "space" || t is android.database.sqlite.SQLiteFullException || c.filesDir.usableSpace < 50L * 1024 * 1024 -> "space"
                else -> "internet"
            }
            set(Status(if (why == null) Phase.PAUSED else Phase.FAILED, status.done, status.total, status.bytes, why, sizeBytes = size(c)), force = true)
        } finally {
            running = false
        }
    }

    private fun check() { if (stop) throw InterruptedException() }

    /** A pause that ends early when the download is stopped. */
    private fun nap(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (!stop && SystemClock.elapsedRealtime() < until) SystemClock.sleep(250)
    }

    private fun <T> retry(times: Int = 4, block: () -> T?): T? {
        repeat(times) { i ->
            check()
            try { block()?.let { return it } } catch (_: IOException) { }
            nap(2000L * (i + 1))
        }
        return null
    }

    private class NotFound : IOException("404")

    /**
     * A tile as sent (gzip); empty for a tile with nothing in it (204). [NotFound] for a 404 (the address may be of a
     * planet version that is gone); IOException on a network failure.
     */
    private fun getTile(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", Places.USER_AGENT)
            c.setRequestProperty("Accept-Encoding", "gzip")
            return when (val code = c.responseCode) {
                200 -> c.inputStream.use { it.readBytes() }
                204 -> ByteArray(0)
                404 -> throw NotFound()
                else -> throw IOException("HTTP $code")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", Places.USER_AGENT)
            c.setRequestProperty("Accept-Encoding", "gzip")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * A large file, resumed where an earlier attempt stopped (.part), retried through internet drops. The resume is
     * tied to the file's version (If-Range with the validator kept in .etag): a file rebuilt meanwhile (brouter.de
     * renews them every week) comes whole instead of two versions spliced together.
     */
    private fun fetchFile(url: String, to: File, onBytes: (Long) -> Unit) {
        val part = File(to.path + ".part")
        val tag = File(to.path + ".etag")
        var attempt = 0
        while (true) {
            check()
            var c: HttpURLConnection? = null
            try {
                val have = if (part.isFile) part.length() else 0L
                val validator = if (tag.isFile) tag.readText() else ""
                c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", Places.USER_AGENT)
                if (have > 0 && validator.isNotBlank()) {
                    c.setRequestProperty("Range", "bytes=$have-")
                    c.setRequestProperty("If-Range", validator)
                }
                val code = c.responseCode
                if (code == 416) { part.delete(); tag.delete(); continue } // nothing left to send, or another file: anew
                if (code != 200 && code != 206) throw IOException("HTTP $code")
                if (code == 200) {
                    val v = c.getHeaderField("ETag")?.takeIf { !it.startsWith("W/") } ?: c.getHeaderField("Last-Modified")
                    if (v != null) tag.writeText(v) else tag.delete()
                }
                RandomAccessFile(part, "rw").use { raf ->
                    if (code == 206) raf.seek(have) else raf.setLength(0)
                    c.inputStream.use { inp ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            check()
                            val n = inp.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            onBytes(n.toLong())
                        }
                    }
                }
                if (!part.renameTo(to)) throw IOException("rename")
                tag.delete()
                return
            } catch (e: InterruptedException) {
                throw e
            } catch (t: Throwable) {
                attempt++
                Log.w(TAG, "$url: $t (attempt $attempt)")
                nap((3000L * attempt).coerceAtMost(30_000L))
            } finally {
                c?.disconnect()
            }
        }
    }

    private fun size(c: Context): Long = dir(c).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun set(s: Status, force: Boolean = false) {
        status = s
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 400) return
        lastNotify = now
        main.post { listener?.invoke() }
    }
}
