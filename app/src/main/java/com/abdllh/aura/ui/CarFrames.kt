package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import kotlin.math.roundToInt

/**
 * The pre-rendered turntable of the user's car: assets/car/f_000.webp ... (one frame every [Meta.yawStep] degrees,
 * with alpha and a soft floor shadow) and car.json (frame size, default frame, per-frame bounding boxes).
 *
 * The GPU draws the car ([CarGlView], from [CarEtc]'s compressed frames). What is here draws it on the CPU (the
 * stage's own canvas): while the GPU frames are being built at the first start, and for good if the GPU path fails.
 * Decoding a WebP frame takes tens of milliseconds on the unit's processor, too slow for a car that follows a finger:
 * frames are decoded off the main thread into a small LRU of bitmaps (the most recent request first, stale requests
 * far from the current angle skipped, evicted bitmaps reused). Only if the GPU path fails for good, all frames are
 * also decoded once into a file of raw pixels ([startRawFallback]; memory-mapped, a frame is then a ~1 ms copy).
 * All public functions are main-thread only.
 */
object CarFrames {
    private const val TAG = "AuraCar"
    private const val CACHE_FRAMES = 40
    private const val RAW_CACHE_FRAMES = 12
    private const val SKIP_DEG = 48f
    private const val INTRO_DEG = 88f
    private const val RAW_MAGIC = 0x41435246 // "ACRF"
    private const val RAW_HEADER = 32
    private const val QUIET_MS = 6000L       // the start-up rush

    class Meta(
        val count: Int,
        val width: Int,
        val height: Int,
        val yawStep: Float,
        val defaultFrame: Int,
        val boxes: Array<IntArray>
    ) {
        /** Box around the car in every frame (the turntable "fit" framing). */
        val union: IntArray = intArrayOf(
            boxes.minOf { it[0] }, boxes.minOf { it[1] }, boxes.maxOf { it[2] }, boxes.maxOf { it[3] }
        )

        /** Default frame for right-to-left layouts: the mirror image of [defaultFrame] (yaw' = 180 - yaw). */
        val defaultFrameRtl: Int get() = Math.floorMod(Math.round(180f / yawStep) - defaultFrame, count)

        /** An angle in degrees as a number of frames. */
        fun frames(deg: Float): Int = (deg / yawStep).roundToInt().coerceAtLeast(1)
    }

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    @Volatile private var meta: Meta? = null
    private var metaTried = false
    private var encoded: Array<ByteArray?> = emptyArray()
    private val queued = HashSet<Int>()
    private val queue = LinkedBlockingDeque<Int>()
    private val pool = ArrayList<Bitmap>()          // evicted bitmaps waiting to be decoded into (guarded by itself)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var focus = 0
    private var workersStarted = false
    @Volatile private var raw: MappedByteBuffer? = null
    private var rawStarted = false
    @Volatile private var introSet = IntArray(0)    // the frames of the intro spin, read in before the file is used

    private val cache = object : LruCache<Int, Bitmap>(CACHE_FRAMES) {
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && oldValue.isMutable) synchronized(pool) { if (pool.size < 4) pool.add(oldValue) }
        }
    }

    fun meta(ctx: Context): Meta? {
        if (!metaTried) {
            metaTried = true
            app = ctx.applicationContext
            meta = try {
                val j = JSONObject(ctx.assets.open("car/car.json").use { it.readBytes().toString(Charsets.UTF_8) })
                val b = j.getJSONArray("boxes")
                val boxes = Array(b.length()) { i -> b.getJSONArray(i).let { a -> IntArray(4) { k -> a.getInt(k) } } }
                Meta(j.getInt("frames"), j.getInt("width"), j.getInt("height"), j.getDouble("yawStep").toFloat(), j.getInt("default"), boxes)
                    .takeIf { it.count > 0 && boxes.size == it.count }
            } catch (t: Throwable) {
                Log.w(TAG, "no car frames: $t")
                null
            }
            meta?.let { encoded = arrayOfNulls(it.count) }
        }
        return meta
    }

    /** Starts decoding the frames the home screen shows first (the intro spin), for drawing them on the CPU. */
    fun warmUp(ctx: Context, rtl: Boolean) {
        val m = meta(ctx) ?: return
        val d = if (rtl) m.defaultFrameRtl else m.defaultFrame
        val dir = introDirection(rtl)
        val intro = introFrames()
        focus = Math.floorMod(d - dir * intro / 2, m.count)
        // the queue is last-in-first-out, so the intro frames decode in playback order and the default one last
        for (k in 0..intro) request(Math.floorMod(d - dir * k, m.count))
        introSet = IntArray(intro + 2) { k -> Math.floorMod(d - dir * k, m.count) }
    }

    /** The GPU path failed: frames come from a raw frame file from now on (built once, in the background). */
    fun startRawFallback() {
        if (rawStarted || meta == null) return
        rawStarted = true
        Thread({ prepareRaw() }, "car-raw").apply { isDaemon = true; start() }
    }

    /** The GPU draws the car: the raw frame file of the CPU path (~100 MB, from 1.5.1) is not needed. */
    fun dropRawFiles(ctx: Context) {
        if (rawStarted) return
        val c = ctx.applicationContext
        Thread({
            c.noBackupFilesDir.listFiles()?.filter { it.name.startsWith("car-") && (it.name.endsWith(".raw") || it.name.endsWith(".raw.tmp")) }
                ?.forEach { if (it.delete()) Log.i(TAG, "dropped ${it.name}") }
            c.cacheDir.listFiles()?.filter { it.name.startsWith("car-") }?.forEach { it.delete() }
        }, "car-drop").apply { isDaemon = true; start() }
    }

    fun frameAsset(i: Int) = "car/f_%03d.webp".format(java.util.Locale.ROOT, i)

    /** Length of the intro spin in frames (88 degrees). */
    fun introFrames(): Int = meta?.frames(INTRO_DEG) ?: 22

    /** The intro turns the car's face towards the screen centre: forwards in LTR, backwards (mirrored) in RTL. */
    fun introDirection(rtl: Boolean) = if (rtl) -1 else 1

    fun peek(i: Int): Bitmap? = cache.get(i)

    /**
     * Frame [i] for drawing now: the cached bitmap, else copied from the raw frame file (about a millisecond), else
     * null (the file is not there yet: the frame is requested and [nearest] stands in).
     */
    fun frameNow(i: Int): Bitmap? {
        cache.get(i)?.let { return it }
        val r = raw ?: return null
        val m = meta ?: return null
        if (i < 0 || i >= m.count) return null
        return try {
            val bmp = synchronized(pool) { if (pool.isEmpty()) null else pool.removeAt(pool.size - 1) }
                ?.takeIf { it.width == m.width && it.height == m.height }
                ?: Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
            val bytes = m.width * m.height * 4
            val b = r.duplicate()
            b.position(RAW_HEADER + i * bytes)
            b.limit(RAW_HEADER + (i + 1) * bytes)
            bmp.copyPixelsFromBuffer(b)
            cache.put(i, bmp)
            bmp
        } catch (t: Throwable) {
            Log.w(TAG, "raw frame $i: $t")
            null
        }
    }

    /** Nearest decoded frame to [i] within [radius] frames (used while the exact one is still decoding). */
    fun nearest(i: Int, radius: Int): Bitmap? {
        val n = meta?.count ?: return null
        cache.get(i)?.let { return it }
        for (d in 1..radius) {
            cache.get(Math.floorMod(i - d, n))?.let { return it }
            cache.get(Math.floorMod(i + d, n))?.let { return it }
        }
        return null
    }

    /** True once frames come from the raw file (no decoding any more). */
    val instant: Boolean get() = raw != null

    fun setFocus(i: Int) { focus = i }

    fun request(i: Int) {
        val m = meta ?: return
        if (raw != null) return // copied when drawn
        if (i < 0 || i >= m.count || cache.get(i) != null || !queued.add(i)) return
        startWorkers()
        queue.offerFirst(i)
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** Frees the cache except for [keep] (the home screen went to the background). */
    fun trim(keep: Collection<Int>) {
        // Hold the pool lock throughout (entryRemoved re-enters it): a worker must never take a bitmap that is
        // only passing through the pool while it is put back under its own key.
        synchronized(pool) {
            val kept = keep.mapNotNull { k -> cache.remove(k)?.let { k to it } } // remove() does not pool
            cache.evictAll()
            pool.clear()
            for ((k, b) in kept) cache.put(k, b)
        }
    }

    private fun startWorkers() {
        if (workersStarted) return
        workersStarted = true
        repeat(2) { n ->
            Thread({ work() }, "car-frames-$n").apply { isDaemon = true; start() }
        }
    }

    private fun work() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY + 2)
        while (true) {
            val i = try { queue.takeFirst() } catch (_: InterruptedException) { return }
            val m = meta ?: continue
            val n = m.count
            val dist = Math.floorMod(i - focus, n).let { minOf(it, n - it) }
            val bmp = if (raw != null || dist > m.frames(SKIP_DEG)) null else decode(i)
            main.post {
                queued.remove(i)
                if (bmp != null) {
                    cache.put(i, bmp)
                    for (l in listeners) l()
                }
            }
        }
    }

    private fun bytesOf(ctx: Context, i: Int): ByteArray? = encoded[i] ?: try {
        ctx.assets.open(frameAsset(i)).use { it.readBytes() }.also { if (raw == null) encoded[i] = it }
    } catch (t: Throwable) {
        Log.w(TAG, "frame $i: $t")
        null
    }

    private fun decode(i: Int): Bitmap? {
        val ctx = app ?: return null
        val bytes = bytesOf(ctx, i) ?: return null
        val reuse = synchronized(pool) { if (pool.isEmpty()) null else pool.removeAt(pool.size - 1) }
        val o = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inBitmap = reuse
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        } catch (_: IllegalArgumentException) {
            o.inBitmap = null // the recycled bitmap did not fit
            try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) } catch (_: Throwable) { null }
        } catch (t: Throwable) {
            Log.w(TAG, "decode $i: $t")
            null
        }
    }

    // ------------------------------------------------------------------------------------------ the raw frame file
    /**
     * Opens the raw frame file, building it first if this set of frames has none yet (background thread): a header
     * (magic, frames, width, height) and every frame's premultiplied ARGB pixels as Android keeps them in a bitmap.
     * Named after car.json and the frames' sizes, so new frames in an update build a new file (the old one is
     * deleted). It lives with the app's files, not in the cache: the system's cache cleaner would delete it while it
     * is mapped, which frees nothing until the process ends.
     * An existing file is used once the intro's frames are read in; building one, and reading in the rest, wait for
     * the start-up rush.
     */
    private fun prepareRaw() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val ctx = app ?: return
        val m = meta ?: return
        var tmp: File? = null
        try {
            // 1.5.1 test builds kept it in the cache
            ctx.cacheDir.listFiles()?.filter { it.name.startsWith("car-") }?.forEach { it.delete() }
            var hash = ctx.assets.open("car/car.json").use { it.readBytes() }.contentHashCode()
            for (i in 0 until m.count) hash = 31 * hash + ctx.assets.open(frameAsset(i)).use { it.available() }
            val dir = ctx.noBackupFilesDir
            val f = File(dir, "car-%08x.raw".format(java.util.Locale.ROOT, hash))
            val bytes = m.width * m.height * 4
            val size = RAW_HEADER + bytes.toLong() * m.count
            val build = !valid(f, m, size)
            if (build) {
                Thread.sleep(QUIET_MS)
                dir.listFiles()?.filter { it.name.startsWith("car-") && (it.name.endsWith(".raw") || it.name.endsWith(".raw.tmp")) }?.forEach { it.delete() }
                if (dir.usableSpace < size + 64L * 1024 * 1024) { Log.w(TAG, "no room for the raw frames"); return }
                tmp = File(f.path + ".tmp")
                val t0 = System.currentTimeMillis()
                RandomAccessFile(tmp, "rw").use { out ->
                    out.setLength(size)
                    val ch = out.channel
                    val buf = ByteBuffer.allocateDirect(bytes)
                    val o = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    var reuse: Bitmap? = null
                    for (i in 0 until m.count) {
                        val enc = bytesOf(ctx, i) ?: throw IllegalStateException("frame $i")
                        o.inBitmap = reuse
                        val bmp = BitmapFactory.decodeByteArray(enc, 0, enc.size, o) ?: throw IllegalStateException("decode $i")
                        if (bmp.width != m.width || bmp.height != m.height || bmp.rowBytes != m.width * 4) throw IllegalStateException("frame $i size")
                        reuse = bmp
                        buf.clear()
                        bmp.copyPixelsToBuffer(buf)
                        buf.flip()
                        var at = RAW_HEADER + i.toLong() * bytes
                        while (buf.hasRemaining()) at += ch.write(buf, at)
                    }
                    val head = ByteBuffer.allocate(RAW_HEADER).order(ByteOrder.LITTLE_ENDIAN)
                    head.putInt(RAW_MAGIC).putInt(m.count).putInt(m.width).putInt(m.height)
                    head.rewind()
                    ch.write(head, 0)
                    ch.force(false)
                }
                if (!tmp.renameTo(f)) throw IllegalStateException("rename")
                tmp = null
                Log.i(TAG, "raw frames built in ${System.currentTimeMillis() - t0} ms (${size / 1_048_576} MB)")
            }
            val mapped = RandomAccessFile(f, "r").use { r ->
                // Just written, the pages are in memory; after a start they are on the storage: read the intro's frames
                // in here, so the main thread never waits for the storage (decoded frames stand in meanwhile).
                if (!build) {
                    val t0 = System.currentTimeMillis()
                    val buf = ByteBuffer.allocateDirect(bytes)
                    for (i in introSet) {
                        buf.clear()
                        var at = RAW_HEADER + i.toLong() * bytes
                        while (buf.hasRemaining()) { val n = r.channel.read(buf, at); if (n <= 0) break; at += n }
                    }
                    Log.i(TAG, "intro frames read in ${System.currentTimeMillis() - t0} ms")
                }
                r.channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
            }
            main.post {
                raw = mapped
                encoded = arrayOfNulls(m.count) // the encoded frames are not needed any more
                cache.resize(RAW_CACHE_FRAMES)  // a frame is a copy away now: keep only the ones around the car's angle
                for (l in listeners) l()
            }
            // read it all in, at low priority: the first spin after a start must not wait for the storage either
            // (the pages are file cache: the system takes them back when it needs the memory)
            if (!build) Thread.sleep(QUIET_MS)
            mapped.load()
        } catch (t: Throwable) {
            Log.w(TAG, "raw frames: $t")
            tmp?.delete() // a half-built file (no room, a frame that did not decode...)
        }
    }

    private fun valid(f: File, m: Meta, size: Long): Boolean {
        if (!f.isFile || f.length() != size) return false
        return try {
            RandomAccessFile(f, "r").use { r ->
                val h = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                r.channel.read(h, 0)
                h.flip()
                h.int == RAW_MAGIC && h.int == m.count && h.int == m.width && h.int == m.height
            }
        } catch (_: Throwable) {
            false
        }
    }
}
