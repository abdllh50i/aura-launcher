package com.abdllh.aura.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.ETC1
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.TextureView
import android.view.Window
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.system.SystemProps
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * `assets/car/car-stage.json` (SPEC.md section 10): where each of the 180 frames stands. Screen px: its lower hull (4 points, x ascending), which the shader's floor, shadow and
 * mirror stand on, and the small dolly (zoom, dx, pivot) that keeps a broadside car inside the free area. Frame 108, the showroom angle, has zoom 1 and dx 0.
 */
class CarTable private constructor(j: JSONObject) {
    val width = j.getJSONArray("textureSize").getInt(0)
    val height = j.getJSONArray("textureSize").getInt(1)
    val box = j.getJSONArray("textureBox").let { a -> FloatArray(4) { a.getDouble(it).toFloat() } }
    val pivotY = j.getDouble("pivotY").toFloat()
    val defaultFrame = j.getInt("defaultFrame")
    val count: Int
    val frameBytes: Int get() = ETC1.getEncodedDataSize(width, height)
    val zoom: FloatArray
    val dx: FloatArray
    val pivotX: FloatArray
    val hullX: Array<FloatArray>
    val hullY: Array<FloatArray>
    /** Its two lowest points, lowest first: x, y, x, y. */
    val spot: Array<FloatArray>

    init {
        val f = j.getJSONArray("frames")
        count = f.length()
        zoom = FloatArray(count)
        dx = FloatArray(count)
        pivotX = FloatArray(count)
        hullX = Array(count) { FloatArray(4) }
        hullY = Array(count) { FloatArray(4) }
        spot = Array(count) { FloatArray(4) }
        for (i in 0 until count) {
            val o = f.getJSONObject(i)
            zoom[i] = o.getDouble("zoom").toFloat()
            dx[i] = o.getDouble("dx").toFloat()
            pivotX[i] = o.getDouble("pivotX").toFloat()
            val h = o.getJSONArray("hull")
            val pts = ArrayList<FloatArray>(4)
            for (k in 0 until 4) {
                val p = h.getJSONArray(k)
                val x = p.getDouble(0).toFloat()
                val y = p.getDouble(1).toFloat()
                hullX[i][k] = x
                hullY[i][k] = y
                pts.add(floatArrayOf(x, y))
            }
            val low = pts.sortedByDescending { it[1] }
            spot[i][0] = low[0][0]; spot[i][1] = low[0][1]; spot[i][2] = low[1][0]; spot[i][3] = low[1][1]
        }
    }

    companion object {
        private var one: CarTable? = null
        private var tried = false

        /** Null when the file is not there or cannot be read: no car, the wall stays whole. */
        @Synchronized
        fun of(ctx: Context): CarTable? {
            if (!tried) {
                tried = true
                one = try {
                    CarTable(JSONObject(ctx.assets.open("car/car-stage.json").use { it.readBytes().toString(Charsets.UTF_8) }))
                } catch (t: Throwable) {
                    Log.w("AuraCar", "no car table: $t")
                    null
                }
            }
            return one
        }
    }
}

/** The numbers of section 10's table that go to the shader. */
internal class CarLook(dark: Boolean) {
    val k: Int = if (dark) 0xFF101A2C.toInt() else 0xFFD6E6FA.toInt()
    val keyLo = 3f / 255f
    val keyHi = (if (dark) 9f else 12f) / 255f
    val keyC = if (dark) 0f else .094f
    val shadow: Int = if (dark) 0xFF020306.toInt() else 0xFF394353.toInt()
    val sharp = if (dark) .85f else .74f
    val wide = if (dark) .55f else .40f
    val r = if (dark) .30f else .28f
    val tau = if (dark) 34f else 38f
    val r0 = 2.2f
    val r1 = .17f
    val tint = if (dark) floatArrayOf(.88f, .91f, 1f) else floatArrayOf(.92f, .94f, .98f)
}

/**
 * The turntable's frames for the GPU: what ships is each frame's WebP, a 1 bit alpha marking the key K (assets/car/frames-<theme>). The first run only decodes, fills K where the alpha
 * is 0 and runs ETC1 on two threads, the current theme first, into no_backup/car-etc-<hash>.bin: 101,536 B a frame, 18.3 MB a theme, all of it resident in the GPU, so turning the car
 * costs no copies and no uploads. The other theme's file follows at leisure, so the change at dusk finds it ready.
 */
object CarEtc {
    private const val TAG = "AuraCar"
    private const val MAGIC = 0x32435445 // "ETC2": not the format, this file's second life (the key, no floor in the frames)
    const val HEADER = 32
    private const val VERSION = 3
    private const val THREADS = 2

    private val lock = Object()
    private val waiting = HashMap<Boolean, MutableList<(File?) -> Unit>>()
    private var builder: Thread? = null

    fun asset(dark: Boolean, i: Int) = "car/frames-${if (dark) "night" else "day"}/frame_%03d.webp".format(java.util.Locale.ROOT, i)

    /** The frame file for [dark], built first if needed, then the other theme's at leisure. [done] runs on [on]: the file, or null when it cannot be had. */
    fun ensure(ctx: Context, dark: Boolean, on: Handler, done: (File?) -> Unit) {
        val app = ctx.applicationContext
        val t = CarTable.of(app)
        if (t == null) { on.post { done(null) }; return }
        val cb: (File?) -> Unit = { f -> on.post { done(f) } }
        synchronized(lock) {
            waiting.getOrPut(dark) { ArrayList() }.add(cb)
            if (builder != null) return
            builder = Thread({ work(app, t, dark) }, "car-etc").apply { isDaemon = true; start() }
        }
    }

    private fun work(ctx: Context, t: CarTable, firstDark: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var dark = firstDark
        var otherDone = false
        while (true) {
            val f = try { fileFor(ctx, t, dark) } catch (e: Throwable) { Log.w(TAG, "frames: $e"); null }
            val ok = f != null && (valid(f, t) || build(ctx, t, dark, f))
            val cbs = synchronized(lock) { waiting.remove(dark) }
            cbs?.forEach { cb -> cb(if (ok) f else null) }
            if (dark != firstDark) otherDone = true
            // whoever waits next; otherwise the other theme's file, once
            val next: Boolean? = synchronized(lock) {
                val k = waiting.keys.firstOrNull()
                when {
                    k != null -> k
                    !otherDone -> !firstDark
                    else -> { builder = null; null }
                }
            }
            if (next == null) return
            dark = next
            if (next != firstDark && !otherDone && synchronized(lock) { waiting.isEmpty() }) {
                Thread.sleep(3000) // the first theme is drawing by now: no hurry
                synchronized(lock) { waiting.keys.firstOrNull() }?.let { dark = it }
            }
        }
    }

    // (the table and every frame's length: what changes when the bake changes. Off the main thread)
    private fun fileFor(ctx: Context, t: CarTable, dark: Boolean): File {
        var h = ctx.assets.open("car/car-stage.json").use { it.readBytes() }.contentHashCode()
        for (i in 0 until t.count) h = 31 * h + ctx.assets.open(asset(dark, i)).use { it.available() }
        h = 31 * h + CarLook(dark).k
        h = 31 * h + VERSION
        return File(ctx.noBackupFilesDir, "car-etc-%08x.bin".format(java.util.Locale.ROOT, h))
    }

    private fun valid(f: File, t: CarTable): Boolean {
        if (!f.isFile || f.length() != HEADER + t.frameBytes.toLong() * t.count) return false
        return try {
            RandomAccessFile(f, "r").use { r ->
                val h = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                r.channel.read(h, 0)
                h.flip()
                h.int == MAGIC && h.int == t.count && h.int == t.width && h.int == t.height
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** A frame's pixels as RGB888: K where its alpha is 0 (under the alpha the picture goes on as the floor, only so that the encoder saw no hard edge). */
    private fun keyed(px: IntArray, k: Int, out: ByteArray) {
        val kr = (k shr 16).toByte()
        val kg = (k shr 8).toByte()
        val kb = k.toByte()
        var o = 0
        for (p in px) {
            if (p ushr 24 < 128) {
                out[o] = kr; out[o + 1] = kg; out[o + 2] = kb
            } else {
                out[o] = (p shr 16).toByte(); out[o + 1] = (p shr 8).toByte(); out[o + 2] = p.toByte()
            }
            o += 3
        }
    }

    /** The crisp copy for a resting car: frame 108 from the retouched `rest-<theme>.webp`, another angle from its frame. RGB888, top row first. */
    fun restRgb(ctx: Context, t: CarTable, dark: Boolean, i: Int): ByteArray? = try {
        val name = if (i == t.defaultFrame) "car/rest-${if (dark) "night" else "day"}.webp" else asset(dark, i)
        val bmp = ctx.assets.open(name).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) }!!
        val px = IntArray(t.width * t.height)
        bmp.getPixels(px, 0, t.width, 0, 0, t.width, t.height)
        bmp.recycle()
        ByteArray(px.size * 3).also { keyed(px, CarLook(dark).k, it) }
    } catch (e: Throwable) {
        Log.w(TAG, "rest frame $i: $e")
        null
    }

    /** The wall under the GL rect, the same pixels the window shows. RGB888. */
    fun wallRgb(ctx: Context, dark: Boolean): Pair<Int, ByteArray>? = try {
        val bmp = ctx.assets.open("surfaces/wall-gl-${if (dark) "night" else "day"}.png").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }) }!!
        val w = bmp.width
        val px = IntArray(w * bmp.height)
        bmp.getPixels(px, 0, w, 0, 0, w, bmp.height)
        bmp.recycle()
        val out = ByteArray(px.size * 3)
        var o = 0
        for (p in px) { out[o++] = (p shr 16).toByte(); out[o++] = (p shr 8).toByte(); out[o++] = p.toByte() }
        w to out
    } catch (e: Throwable) {
        Log.w(TAG, "wall: $e")
        null
    }

    private fun build(ctx: Context, t: CarTable, dark: Boolean, f: File): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        val dir = f.parentFile ?: return false
        val size = HEADER + t.frameBytes.toLong() * t.count
        if (dir.usableSpace < size + 64L * 1024 * 1024) { Log.w(TAG, "no room for the frames"); return false }
        val tmp = File(f.path + ".tmp")
        val k = CarLook(dark).k
        return try {
            RandomAccessFile(tmp, "rw").use { out ->
                out.setLength(size)
                val ch = out.channel
                val workers = (0 until THREADS).map { part ->
                    Thread({
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        val px = IntArray(t.width * t.height)
                        val rgb = ByteArray(px.size * 3)
                        val inBuf = ByteBuffer.allocateDirect(rgb.size).order(ByteOrder.nativeOrder())
                        val outBuf = ByteBuffer.allocateDirect(t.frameBytes).order(ByteOrder.nativeOrder())
                        val o = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        var reuse: Bitmap? = null
                        var i = part
                        while (i < t.count) {
                            val bytes = ctx.assets.open(asset(dark, i)).use { it.readBytes() }
                            o.inBitmap = reuse
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: throw IllegalStateException("decode $i")
                            if (bmp.width != t.width || bmp.height != t.height) throw IllegalStateException("frame $i size")
                            reuse = bmp
                            bmp.getPixels(px, 0, t.width, 0, 0, t.width, t.height)
                            keyed(px, k, rgb)
                            inBuf.clear(); inBuf.put(rgb); inBuf.flip()
                            outBuf.clear()
                            ETC1.encodeImage(inBuf, t.width, t.height, 3, t.width * 3, outBuf)
                            outBuf.position(0); outBuf.limit(t.frameBytes)
                            var at = HEADER + i.toLong() * t.frameBytes
                            while (outBuf.hasRemaining()) at += ch.write(outBuf, at)
                            i += THREADS
                        }
                    }, "car-etc-$part")
                }
                var failure: Throwable? = null
                workers.forEach { it.setUncaughtExceptionHandler { _, e -> failure = e }; it.start() }
                workers.forEach { it.join() }
                failure?.let { throw it }
                val head = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
                head.putInt(MAGIC).putInt(t.count).putInt(t.width).putInt(t.height).putInt(t.frameBytes)
                head.rewind()
                ch.write(head, 0)
                ch.force(false)
            }
            if (!tmp.renameTo(f)) throw IllegalStateException("rename")
            // older sets go: at most this one and the other theme's stay
            val other = try { fileFor(ctx, t, !dark).name } catch (_: Throwable) { "" }
            dir.listFiles()?.filter { it.name.startsWith("car-etc-") && it.name != f.name && it.name != other }?.forEach { it.delete() }
            Log.i(TAG, "frames (${if (dark) "night" else "day"}) built in ${SystemClock.elapsedRealtime() - t0} ms")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "frames: $e")
            tmp.delete()
            false
        }
    }
}

/**
 * The car over the wall (SPEC.md section 10), GL ES 2 on its own thread: the rest frame and the wall's crop go in first, so it draws from its first frame, before any frame is baked; the
 * 180 ETC1 frames follow one per message. Floor light, shadow and reflection are the shader's, so the floor is right at every angle. No stage in the frames, no CPU path. [CarStage] owns the
 * motion and calls [render]. Its own frame time says which program: over 24 ms for 30 frames the second, compiled with CHEAP_MIRROR; well under again, the first.
 */
class CarGlView(context: Context, dark: Boolean) : TextureView(context), TextureView.SurfaceTextureListener {
    class State {
        @JvmField var a = 0
        @JvmField var b = 0
        @JvmField var t = 0f
        @JvmField var shown = 1f
        @JvmField var rest = false
        @JvmField var restMix = 0f
        fun copyFrom(o: State) {
            a = o.a; b = o.b; t = o.t; shown = o.shown; rest = o.rest; restMix = o.restMix
        }
    }

    /** The first frame is up (main thread): what stood in for the view can go. */
    var onReady: (() -> Unit)? = null
    /** The surface went away (main thread). */
    var onLost: (() -> Unit)? = null
    /** The GL path cannot be used at all (main thread). */
    var onFailed: (() -> Unit)? = null
    /** The crisp copy of frame [i] is in the GPU (main thread). */
    var onRestReady: ((Int) -> Unit)? = null
    /** All the frames are in the GPU (main thread): the car can turn. */
    var onFramesIn: (() -> Unit)? = null

    @Volatile var framesIn = false
        private set
    /** Frames drawn per second while the car last turned (diagnostics). */
    @Volatile var fps = 0f
        private set
    /** Under the frame time's pressure it draws the cheap mirror (diagnostics). */
    @Volatile var cheap = false
        private set

    private val table = CarTable.of(context)
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var gl: Handler? = null

    private val pending = State()
    private var posted = false            // guarded by pending
    private var hasState = false          // guarded by pending
    private val cur = State()             // GL thread
    @Volatile private var surface: SurfaceTexture? = null
    @Volatile private var vw = 0
    @Volatile private var vh = 0
    @Volatile private var failed = false
    @Volatile private var frameOver = false
    @Volatile private var isDark = dark
    @Volatile private var restFrame = -1
    private var restWanted = -1           // main thread
    private var detached = false

    // GL thread
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglCtx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var loaded = false
    @Volatile private var showing = false
    private var look = CarLook(dark)
    private var lookOfGl: Boolean? = null
    private var full: Prog? = null
    private var thin: Prog? = null
    private var tex = IntArray(0)
    private var have = BooleanArray(0)
    private var restTex = 0
    private var wallTex = 0
    private var quad: FloatBuffer? = null
    private var loadFile: RandomAccessFile? = null
    private var loadBuf: ByteBuffer? = null
    private var loadOrder = IntArray(0)
    private var loadAt = 0
    private var loadRun = 0
    private var lastSwap = 0L
    private var motionFrames = 0
    private var motionStart = 0L
    private var overCount = 0
    private var underCount = 0

    private class Prog(val id: Int) {
        fun u(n: String) = GLES20.glGetUniformLocation(id, n)
        val t = u("u_t")
        val shown = u("u_shown")
        val dolly = u("u_dolly")
        val hx = u("u_hx")
        val hy = u("u_hy")
        val spot = u("u_spot")
    }

    init {
        surfaceTextureListener = this
        isOpaque = false // (nothing drawn yet: what stands under it shows)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detached = false
        if (table == null || failed || thread != null) return
        val t = HandlerThread("car-gl", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        thread = t
        gl = Handler(t.looper)
    }

    // TextureView tells about its surface going away (onSurfaceTextureDestroyed) after this: the GL thread is ended there, once it has let go of the surface
    override fun onDetachedFromWindow() {
        detached = true
        if (surface == null) quitThread()
        super.onDetachedFromWindow()
    }

    private fun quitThread() {
        val t = thread
        thread = null
        gl = null
        t?.quitSafely() // (what is queued, the release, still runs)
    }

    /** Shows [s] (main thread; also before anything is in, so the first frame is already the right one). */
    fun render(s: State) {
        val h = gl ?: return
        synchronized(pending) {
            pending.copyFrom(s)
            hasState = true
            if (posted) return
            posted = true
        }
        h.post(drawRunnable)
    }

    /** Prepares the crisp copy of frame [i] for a car at rest (main thread). */
    fun prepareRest(i: Int) {
        val h = gl ?: return
        val t = table ?: return
        if (i == restFrame) { main.post { if (restFrame == i) onRestReady?.invoke(i) }; return } // (still in the GPU)
        if (i == restWanted) return
        restWanted = i
        val dark = isDark
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val rgb = CarEtc.restRgb(app, t, dark, i)
            if (rgb == null || !h.post { uploadRest(i, rgb, dark) }) main.post { if (restWanted == i) restWanted = -1 }
        }, "car-rest").apply { isDaemon = true; start() }
    }

    /** The other theme (main thread, in the swap's one frame): its wall, rest frame and numbers at once, its frames after them. */
    fun setDark(d: Boolean) {
        if (d == isDark) return
        isDark = d
        framesIn = false
        restWanted = -1
        gl?.post { applyLook() }
    }

    /** The picture of the view as it is now, for the theme change's ghost. Main thread. */
    fun picture(): Bitmap? = try { if (isAvailable && showing) bitmap else null } catch (_: Throwable) { null }

    /** Is the GL drawing the car (any thread)? */
    val drawing: Boolean get() = showing && !failed

    // ------------------------------------------------------------------------------------------ TextureView
    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = st; vw = w; vh = h
        FrameOver().start() // (this comes in the middle of drawing the window's frame)
    }

    /**
     * Lets the GL thread start once the window's frame being drawn is on the screen. Beside that frame, this thread's EGL set-up, program and uploads held the screen back: about 300 ms of 1200
     * on a loaded emulator. At the latest after [FRAME_WAIT_MS]: the car must not depend on the report coming.
     */
    private inner class FrameOver : Window.OnFrameMetricsAvailableListener, Runnable {
        private val win = if (BuildConfig.DEBUG && SystemProps.get("debug.aura.car.noreport") == "1") null else (context as? Activity)?.window
        private var done = false

        fun start() {
            frameOver = false
            main.postDelayed(this, FRAME_WAIT_MS)
            try { win?.addOnFrameMetricsAvailableListener(this, main) } catch (t: Throwable) { Log.w(TAG, "frame report: $t") }
        }

        override fun onFrameMetricsAvailable(w: Window, m: FrameMetrics, dropped: Int) {
            main.post(this) // (not from inside the window's own round of its listeners)
        }

        override fun run() {
            if (done) return
            done = true
            main.removeCallbacks(this)
            try { win?.removeOnFrameMetricsAvailableListener(this) } catch (_: Throwable) { }
            frameOver = true
            gl?.post { tryStart() }
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        vw = w; vh = h
        gl?.post { drawNow() }
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        surface = null
        val h = gl ?: return true
        // the GL objects go first, then the surface (released by the GL thread, hence false); a new surface loads it all again. A view going away ends its GL thread after that.
        val posted = h.post { release(); try { st.release() } catch (_: Throwable) { } }
        if (detached) quitThread()
        return !posted
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ------------------------------------------------------------------------------------------ GL thread
    private fun fail(why: String) {
        Log.w(TAG, "GL car off: $why")
        failed = true
        main.post { visibility = GONE; onFailed?.invoke() }
    }

    private fun tryStart() {
        val st = surface ?: return
        val t = table ?: return
        if (!frameOver || eglCtx != EGL14.EGL_NO_CONTEXT || failed) return
        try {
            val t0 = SystemClock.elapsedRealtime()
            if (BuildConfig.DEBUG) debugHooks()
            initEgl(st)
            full = program(false)
            quad = ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
                it.put(floatArrayOf(RECT_X, RECT_Y, RECT_X + RECT_W, RECT_Y, RECT_X, RECT_Y + RECT_H, RECT_X + RECT_W, RECT_Y + RECT_H))
                it.flip()
            }
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
            val n = IntArray(2)
            GLES20.glGenTextures(2, n, 0)
            restTex = n[0]
            wallTex = n[1]
            tex = IntArray(t.count)
            have = BooleanArray(t.count)
            GLES20.glGenTextures(t.count, tex, 0)
            loaded = true
            applyLook()
            Log.i(TAG, "GL car up in ${SystemClock.elapsedRealtime() - t0} ms")
            drawRunnable.run()
        } catch (e: Throwable) {
            release()
            fail(e.toString())
        }
    }

    // emulator tests: a slow start, one that fails
    private fun debugHooks() {
        SystemProps.get("debug.aura.car.slow").toLongOrNull()?.let { Thread.sleep(it) }
        if (SystemProps.get("debug.aura.car.fail") == "1") throw IllegalStateException("debug.aura.car.fail")
    }

    private fun initEgl(st: SurfaceTexture) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw IllegalStateException("eglInitialize")
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, n, 0) || n[0] < 1) throw IllegalStateException("eglChooseConfig")
        eglCtx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (eglCtx == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext")
        eglSurface = EGL14.eglCreateWindowSurface(display, cfgs[0], st, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface")
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglCtx)) throw IllegalStateException("eglMakeCurrent")
    }

    private fun program(cheapMirror: Boolean): Prog {
        val vs = shader(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, (if (cheapMirror) "#define CHEAP_MIRROR\n" else "") + FRAGMENT)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vs)
        GLES20.glAttachShader(id, fs)
        GLES20.glBindAttribLocation(id, 0, "a_pos")
        GLES20.glLinkProgram(id)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("link: " + GLES20.glGetProgramInfoLog(id))
        val p = Prog(id)
        GLES20.glUseProgram(id)
        GLES20.glUniform1i(p.u("u_a"), 0)
        GLES20.glUniform1i(p.u("u_b"), 1)
        GLES20.glUniform1i(p.u("u_m"), 2)
        GLES20.glUniform1i(p.u("u_wall"), 3)
        val t = table!!
        GLES20.glUniform4f(p.u("u_box"), t.box[0], t.box[1], t.box[2], t.box[3])
        GLES20.glUniform4f(p.u("u_rect"), RECT_X, RECT_Y, RECT_W, RECT_H)
        GLES20.glUniform4f(p.u("u_vr"), RECT_X, RECT_Y, RECT_W, RECT_H)
        statics(p)
        return p
    }

    /** What a theme says, in one program. */
    private fun statics(p: Prog) {
        val l = look
        GLES20.glUseProgram(p.id)
        fun rgb(n: String, c: Int) = GLES20.glUniform3f(p.u(n), ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
        rgb("u_k", l.k)
        rgb("u_shadow", l.shadow)
        GLES20.glUniform3f(p.u("u_tint"), l.tint[0], l.tint[1], l.tint[2])
        GLES20.glUniform2f(p.u("u_key"), l.keyLo, l.keyHi)
        GLES20.glUniform2f(p.u("u_sh"), l.sharp, l.wide)
        GLES20.glUniform1f(p.u("u_keyC"), l.keyC)
        GLES20.glUniform4f(p.u("u_refl"), l.r, l.tau, l.r0, l.r1)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun params(t: Int, filter: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun upload(texture: Int, filter: Int, w: Int, h: Int, rgb: ByteArray) {
        params(texture, filter)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        val buf = ByteBuffer.allocateDirect(rgb.size).order(ByteOrder.nativeOrder()).put(rgb)
        buf.flip()
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, w, h, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, buf)
    }

    /** The theme of now into the GPU: wall, rest frame, numbers; never both themes' frames in it (37 MB), so the old ones go and the new follow. */
    private fun applyLook() {
        val t = table ?: return
        if (!loaded) return
        val dark = isDark
        if (lookOfGl == dark) return
        lookOfGl = dark
        look = CarLook(dark)
        full?.let { statics(it) }
        thin?.let { statics(it) }
        CarEtc.wallRgb(app, dark)?.let { (w, rgb) -> upload(wallTex, GLES20.GL_NEAREST, w, rgb.size / 3 / w, rgb) }
        CarEtc.restRgb(app, t, dark, t.defaultFrame)?.let { rgb ->
            upload(restTex, GLES20.GL_LINEAR, t.width, t.height, rgb)
            restFrame = t.defaultFrame
            main.post { onRestReady?.invoke(t.defaultFrame) }
        }
        closeLoad()
        have.fill(false)
        framesIn = false
        // (the frames' textures keep their names: a new theme's upload replaces them, with no second set alive beside them beyond what is on its way)
        drawNow()
        val run = ++loadRun
        val h = gl ?: return
        CarEtc.ensure(app, dark, h) { f ->
            if (run == loadRun && f != null && loaded) startLoad(f, t, dark)
        }
    }

    /** The spin's frames first (78 to 108, for the entrance), then the nearest to where the car stands: one per message, so a draw waits for one at most. */
    private fun startLoad(f: File, t: CarTable, dark: Boolean) {
        if (dark != isDark) return
        try {
            closeLoad()
            loadFile = RandomAccessFile(f, "r")
            loadBuf = ByteBuffer.allocateDirect(t.frameBytes).order(ByteOrder.nativeOrder())
            val spin = (t.defaultFrame downTo t.defaultFrame - SPIN_FRAMES).map { Math.floorMod(it, t.count) }
            val rest = (0 until t.count).filter { it !in spin }.sortedBy { i -> Math.floorMod(i - t.defaultFrame, t.count).let { minOf(it, t.count - it) } }
            loadOrder = (spin + rest).toIntArray()
            loadAt = 0
            gl?.post(loadNext)
        } catch (e: Throwable) {
            Log.w(TAG, "frames: $e")
        }
    }

    private val loadNext: Runnable = object : Runnable {
        override fun run() {
            val t = table ?: return
            val r = loadFile ?: return
            val buf = loadBuf ?: return
            if (!loaded) return
            try {
                if (loadAt < loadOrder.size) {
                    val i = loadOrder[loadAt++]
                    buf.clear()
                    var at = CarEtc.HEADER + i.toLong() * t.frameBytes
                    while (buf.hasRemaining()) { val n = r.channel.read(buf, at); if (n <= 0) throw IllegalStateException("short read"); at += n }
                    buf.flip()
                    params(tex[i], GLES20.GL_LINEAR)
                    GLES20.glCompressedTexImage2D(GLES20.GL_TEXTURE_2D, 0, ETC1.ETC1_RGB8_OES, t.width, t.height, 0, t.frameBytes, buf)
                    have[i] = true
                    val err = GLES20.glGetError()
                    if (err != GLES20.GL_NO_ERROR) throw IllegalStateException("upload: 0x" + Integer.toHexString(err))
                    gl?.post(this)
                    return
                }
                closeLoad()
                framesIn = true
                Log.i(TAG, "GL car: all ${t.count} frames in")
                main.post { onFramesIn?.invoke() }
                if (thin == null) gl?.post { try { thin = program(true); full?.let { GLES20.glUseProgram(it.id) } } catch (e: Throwable) { Log.w(TAG, "cheap program: $e") } }
            } catch (e: Throwable) {
                // what is in stays, and stands in for what is not
                Log.w(TAG, "frames: $e")
                closeLoad()
            }
        }
    }

    private fun closeLoad() {
        try { loadFile?.close() } catch (_: Throwable) { }
        loadFile = null
        loadBuf = null
    }

    /** Whether the frames [i] needs are in: the entrance's spin asks. */
    fun hasFrames(from: Int, to: Int): Boolean {
        val h = have
        if (framesIn) return true
        if (h.isEmpty()) return false
        var i = from
        while (i <= to) { if (!h[Math.floorMod(i, h.size)]) return false; i++ }
        return true
    }

    /** The texture of frame [i], or of the nearest frame that is in, or the rest frame while none is. */
    private fun texOf(i: Int): Int {
        val n = tex.size
        val k = Math.floorMod(i, n)
        if (have[k]) return tex[k]
        for (d in 1..n / 2) {
            val a = Math.floorMod(k - d, n)
            if (have[a]) return tex[a]
            val b = Math.floorMod(k + d, n)
            if (have[b]) return tex[b]
        }
        return restTex
    }

    private fun uploadRest(i: Int, rgb: ByteArray, dark: Boolean) {
        val t = table ?: return
        if (!loaded || dark != isDark) { main.post { if (restWanted == i) restWanted = -1 }; return }
        try {
            upload(restTex, GLES20.GL_LINEAR, t.width, t.height, rgb)
            restFrame = i
            main.post { if (restWanted == i) restWanted = -1; onRestReady?.invoke(i) }
        } catch (e: Throwable) {
            Log.w(TAG, "rest upload: $e")
        }
    }

    private val drawRunnable = Runnable {
        val any = synchronized(pending) {
            cur.copyFrom(pending)
            posted = false
            hasState
        }
        if (any) drawNow()
    }

    private fun drawNow() {
        val table = table ?: return
        if (!loaded || eglSurface == EGL14.EGL_NO_SURFACE || vw <= 0 || vh <= 0) return
        val s = cur
        val n = tex.size
        val ai = Math.floorMod(s.a, n)
        val bi = Math.floorMod(s.b, n)
        val p = if (cheap && thin != null) thin!! else full ?: return
        val crisp = s.rest && restFrame == ai && s.restMix > 0f
        val texA = texOf(ai)
        val texB = if (crisp) restTex else texOf(bi)
        val mix = if (crisp) s.restMix else s.t
        val near = if (s.t < .5f) ai else bi
        val texM = if (crisp && s.restMix >= .5f) restTex else texOf(near)
        try {
            GLES20.glViewport(0, 0, vw, vh)
            GLES20.glUseProgram(p.id)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texM)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wallTex)
            GLES20.glUniform1f(p.t, mix)
            GLES20.glUniform1f(p.shown, s.shown)
            val t = s.t
            GLES20.glUniform4f(
                p.dolly,
                table.zoom[ai] + (table.zoom[bi] - table.zoom[ai]) * t,
                table.pivotX[ai] + (table.pivotX[bi] - table.pivotX[ai]) * t,
                table.pivotY,
                table.dx[ai] + (table.dx[bi] - table.dx[ai]) * t
            )
            val hx = table.hullX[near]
            val hy = table.hullY[near]
            val sp = table.spot[near]
            GLES20.glUniform4f(p.hx, hx[0], hx[1], hx[2], hx[3])
            GLES20.glUniform4f(p.hy, hy[0], hy[1], hy[2], hy[3])
            GLES20.glUniform4f(p.spot, sp[0], sp[1], sp[2], sp[3])
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                // the surface or the context went away: what stands under the view shows until a new start succeeds
                Log.w(TAG, "swap failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
                release()
                main.post { visibility = INVISIBLE }
                gl?.postDelayed({ tryStart() }, 500)
                return
            }
            if (!showing) {
                showing = true
                main.post { if (!failed) { visibility = VISIBLE; isOpaque = true; onReady?.invoke() } }
            }
            watchTime()
        } catch (e: Throwable) {
            release()
            fail("draw: $e")
        }
    }

    /** Draws closer than 100 ms apart are one motion: how fast, and whether the full mirror can be afforded. */
    private fun watchTime() {
        val now = SystemClock.uptimeMillis()
        val d = now - lastSwap
        if (d < 100) {
            if (motionFrames == 0) motionStart = lastSwap
            motionFrames++
            overCount = if (d > OVER_MS) overCount + 1 else (overCount - 1).coerceAtLeast(0)
            underCount = if (d < UNDER_MS) underCount + 1 else 0
            if (!cheap && overCount >= OVER_FRAMES && thin != null) {
                cheap = true
                overCount = 0
                underCount = 0
                Log.i(TAG, "the cheap mirror: over $OVER_MS ms a frame")
            } else if (cheap && underCount >= UNDER_FRAMES) {
                cheap = false
                overCount = 0
                underCount = 0
            }
        } else {
            if (motionFrames > 20) fps = motionFrames * 1000f / (lastSwap - motionStart).coerceAtLeast(1)
            motionFrames = 0
        }
        lastSwap = now
    }

    private fun release() {
        if (showing) main.post { isOpaque = false; onLost?.invoke() }
        showing = false
        loaded = false
        framesIn = false
        lookOfGl = null
        loadRun++
        closeLoad()
        if (display != EGL14.EGL_NO_DISPLAY) {
            try {
                if (eglCtx != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroyContext(display, eglCtx) // (its textures and programs go with it)
                }
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            } catch (_: Throwable) { }
        }
        eglCtx = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        display = EGL14.EGL_NO_DISPLAY
        tex = IntArray(0)
        have = BooleanArray(0)
        restTex = 0
        wallTex = 0
        restFrame = -1
        full = null
        thin = null
    }

    companion object {
        private const val TAG = "AuraCar"
        /** Longest the GL thread's start waits for the report that the window's frame is on the screen. */
        private const val FRAME_WAIT_MS = 1000L
        /** The view on screen (SPEC.md section 10). */
        const val RECT_X = 384f
        const val RECT_Y = 186f
        const val RECT_W = 640f
        const val RECT_H = 330f
        /** Frames from the showroom angle back to where the entrance's spin starts. */
        const val SPIN_FRAMES = 30
        private const val OVER_MS = 24L
        private const val OVER_FRAMES = 30
        private const val UNDER_MS = 18L
        private const val UNDER_FRAMES = 120

        private const val VERTEX = """
attribute vec2 a_pos;
uniform vec4 u_vr;
varying vec2 v_px;
void main() {
    v_px = a_pos;
    gl_Position = vec4((a_pos.x - u_vr.x) / u_vr.z * 2.0 - 1.0, 1.0 - (a_pos.y - u_vr.y) / u_vr.w * 2.0, 0.0, 1.0);
}"""

        // tools/car_stage.frag of the design, as proven by its shader_proof.mjs; with "#define CHEAP_MIRROR" in front, the second program
        private const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D u_a, u_b;
uniform sampler2D u_m;
uniform sampler2D u_wall;
uniform float u_t, u_shown;
uniform vec4 u_box;
uniform vec4 u_rect;
uniform vec4 u_dolly;
uniform vec4 u_hx, u_hy;
uniform vec4 u_spot;
uniform vec3 u_k, u_shadow, u_tint;
uniform vec2 u_key, u_sh;
uniform float u_keyC;
uniform vec4 u_refl;
varying vec2 v_px;

float maxabs(vec3 a) { a = abs(a); return max(a.r, max(a.g, a.b)); }
vec2 uvOf(vec2 p) { return (p - u_box.xy) / (u_box.zw - u_box.xy); }
float ground(float x) {
  if (x < u_hx.y) return mix(u_hy.x, u_hy.y, clamp((x - u_hx.x) / (u_hx.y - u_hx.x), 0., 1.));
  if (x < u_hx.z) return mix(u_hy.y, u_hy.z, (x - u_hx.y) / (u_hx.z - u_hx.y));
  return mix(u_hy.z, u_hy.w, clamp((x - u_hx.z) / (u_hx.w - u_hx.z), 0., 1.));
}
vec3 gmix(vec3 a, vec3 b, float s) { return sqrt(a * a * (1. - s) + b * b * s); }
float keyM(vec3 m) { return smoothstep(.00784, .0549, maxabs(m - u_k)); }

void main() {
  vec2 p = (v_px - vec2(u_dolly.y + u_dolly.w, u_dolly.z)) / u_dolly.x + u_dolly.yz;
  vec2 uv = uvOf(p);
  vec3 f = mix(texture2D(u_a, uv).rgb, texture2D(u_b, uv).rgb, u_t);
  float a = smoothstep(u_key.x, u_key.y, maxabs(f - u_k) - u_keyC * smoothstep(.04, .063, f.b - f.r));
  a *= step(u_box.x, p.x) * step(p.x, u_box.z) * step(u_box.y, p.y) * step(p.y, u_box.w);
  if (a * u_shown > .999) { gl_FragColor = vec4(f, 1.); return; }

  vec3 c = texture2D(u_wall, (v_px - u_rect.xy) / u_rect.zw).rgb;
  float gy = ground(p.x), d = p.y - gy;
  if (d > -58. && u_shown > .004) {
    float span = clamp((p.x - u_hx.x + 2.) / 24., 0., 1.) * clamp((u_hx.w - 8. - p.x) / 24., 0., 1.);
    float s = ((1. - smoothstep(-6., 12., d)) * u_sh.x + (1. - smoothstep(-22., 36., d)) * u_sh.y) * span * smoothstep(-58., -40., d);
    vec2 e = (p - u_spot.xy - vec2(0., 2.)) / vec2(42., 7.); s += exp(-.5 * dot(e, e)) * .85;
    e = (p - u_spot.zw - vec2(0., 2.)) / vec2(28., 5.); s += exp(-.5 * dot(e, e)) * .75;
    c = gmix(c, u_shadow, clamp(s, 0., 1.) * u_shown);
    if (d > 0.) {
      float fade = clamp((512. - v_px.y) / 26., 0., 1.); fade = fade * fade * (3. - 2. * fade);
      float F = u_refl.x * exp(-d / u_refl.y) * span * fade * u_shown;
      if (F > .004) {
        vec2 q = vec2(p.x, 2. * gy - p.y);
#ifdef CHEAP_MIRROR
        vec3 m1 = texture2D(u_m, uvOf(q)).rgb;
        float k1 = keyM(m1);
        if (k1 > .001) c = gmix(c, m1 * u_tint, F * k1);
#else
        float r = u_refl.z + u_refl.w * d;
        vec3 m1 = texture2D(u_m, uvOf(q + vec2(-.75, -.25) * r)).rgb, m2 = texture2D(u_m, uvOf(q + vec2(.25, -.75) * r)).rgb;
        vec3 m3 = texture2D(u_m, uvOf(q + vec2(.75, .25) * r)).rgb, m4 = texture2D(u_m, uvOf(q + vec2(-.25, .75) * r)).rgb;
        float k1 = keyM(m1), k2 = keyM(m2), k3 = keyM(m3), k4 = keyM(m4), ka = k1 + k2 + k3 + k4;
        if (ka > .001) c = gmix(c, (m1 * k1 + m2 * k2 + m3 * k3 + m4 * k4) / ka * u_tint, F * ka * .25);
#endif
      }
    }
  }
  gl_FragColor = vec4(mix(c, f, a * u_shown), 1.);
}"""
    }
}
