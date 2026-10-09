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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque

/**
 * The pre-rendered turntable of the user's car: assets/car/f_000.webp ... (one frame every [Meta.yawStep] degrees,
 * with alpha and a soft floor shadow) and car.json (frame size, default frame, per-frame bounding boxes).
 *
 * Frames are decoded off the main thread into a small LRU of bitmaps; the most recent request is decoded first
 * (the view asks for the frames around the current angle on every draw), stale requests far from the current
 * angle are skipped, and evicted bitmaps are reused as decode targets so spinning does not churn memory.
 * All public functions are main-thread only.
 */
object CarFrames {
    private const val TAG = "AuraCar"
    private const val CACHE_FRAMES = 26
    private const val SKIP_DISTANCE = 12

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
    }

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    @Volatile private var meta: Meta? = null
    private var metaTried = false
    private var raw: Array<ByteArray?> = emptyArray()
    private val queued = HashSet<Int>()
    private val queue = LinkedBlockingDeque<Int>()
    private val pool = ArrayList<Bitmap>()          // evicted bitmaps waiting to be decoded into (guarded by itself)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var focus = 0
    private var workersStarted = false

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
            meta?.let { raw = arrayOfNulls(it.count) }
        }
        return meta
    }

    /** Starts decoding the frames the home screen shows first: the intro spin, which ends on the default view. */
    fun warmUp(ctx: Context, rtl: Boolean) {
        val m = meta(ctx) ?: return
        val d = if (rtl) m.defaultFrameRtl else m.defaultFrame
        val dir = introDirection(rtl)
        focus = Math.floorMod(d - dir * INTRO_FRAMES / 2, m.count)
        // the queue is last-in-first-out, so the intro frames decode in playback order and the default one last
        for (k in 0..INTRO_FRAMES) request(Math.floorMod(d - dir * k, m.count))
    }

    /** Length of the intro spin in frames. */
    const val INTRO_FRAMES = 22

    /** The intro turns the car's face towards the screen centre: forwards in LTR, backwards (mirrored) in RTL. */
    fun introDirection(rtl: Boolean) = if (rtl) -1 else 1

    fun peek(i: Int): Bitmap? = cache.get(i)

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

    fun setFocus(i: Int) { focus = i }

    fun request(i: Int) {
        val m = meta ?: return
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
            val n = meta?.count ?: continue
            val dist = Math.floorMod(i - focus, n).let { minOf(it, n - it) }
            val bmp = if (dist > SKIP_DISTANCE) null else decode(i)
            main.post {
                queued.remove(i)
                if (bmp != null) {
                    cache.put(i, bmp)
                    for (l in listeners) l()
                }
            }
        }
    }

    private fun decode(i: Int): Bitmap? {
        val ctx = app ?: return null
        val bytes = raw[i] ?: try {
            ctx.assets.open("car/f_%03d.webp".format(java.util.Locale.ROOT, i)).use { it.readBytes() }.also { raw[i] = it }
        } catch (t: Throwable) {
            Log.w(TAG, "frame $i: $t")
            return null
        }
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
}
