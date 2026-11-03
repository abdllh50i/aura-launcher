package com.abdllh.aura.ui

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
import android.view.TextureView
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.util.Prefs
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * The turntable's frames for the GPU. Each frame is laid over the stage's background and its floor light
 * (pre-composed: no alpha is needed then) and compressed to ETC1, which every GLES 2 device decodes in hardware: one
 * file per background (no_backup/car-etc-<hash>.bin). 180 frames of 640x360 take ~21 MB, so all of them stay in the
 * GPU's memory and turning the car costs no copies and no uploads at all. Built once in the background (some seconds
 * per theme); the other theme's file follows, so the switch at dusk finds it ready.
 */
object CarEtc {
    private const val TAG = "AuraCar"
    private const val MAGIC = 0x31435445 // "ETC1"
    const val HEADER = 32
    private const val VERSION = 2 // 2: the floor light's radius as drawn on the canvas (0.34 of the frame)
    private const val KEY_HASH = "carEtcHash" // the hash of this APK's frames, as "<apkStamp>=<hash>"

    // the strength of the stage's floor light per theme (CarStage.floorColor)
    fun floorAlpha(dark: Boolean) = if (dark) 0.085f else 0.95f

    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private val waiting = HashMap<String, MutableList<(File?) -> Unit>>()
    private var builder: Thread? = null

    // The stage's background per theme (light, dark): the Palette.bg the stage asked with, so the frames always match
    // the screen they stand on (until 1.11 these were copies of Palette's, which a new background colour would have
    // left the car standing in a rectangle of the old one). Until a theme has been shown, its value as of 1.12 stands
    // in, only for building that theme's file in advance: a wrong guess just means it is built when it is shown.
    private val bgs = intArrayOf(0xFFECEEF1.toInt(), 0xFF0B0C0E.toInt())

    fun bg(dark: Boolean): Int = synchronized(lock) { bgs[if (dark) 1 else 0] }

    fun frameBytes(m: CarFrames.Meta) = ETC1.getEncodedDataSize(m.width, m.height)

    /**
     * The frame file for the theme [dark] over the background [bg], built first if needed (then the other theme's, at
     * leisure). [done] runs on [on] with the file, or null when it cannot be had.
     */
    fun ensure(ctx: Context, dark: Boolean, bg: Int, on: Handler, done: (File?) -> Unit) {
        val app = ctx.applicationContext
        val m = CarFrames.meta(app)
        if (m == null) { on.post { done(null) }; return }
        val cb: (File?) -> Unit = { f -> on.post { done(f) } }
        synchronized(lock) {
            bgs[if (dark) 1 else 0] = bg
            waiting.getOrPut(if (dark) "d" else "l") { ArrayList() }.add(cb)
            if (builder != null) return
            builder = Thread({ work(app, m, dark) }, "car-etc").apply { isDaemon = true; start() }
        }
    }

    private fun work(ctx: Context, m: CarFrames.Meta, firstDark: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var dark = firstDark
        var otherDone = false
        while (true) {
            val f = try { fileFor(ctx, m, dark) } catch (t: Throwable) { Log.w(TAG, "GPU frames: $t"); null }
            val ok = f != null && (valid(f, m) || build(ctx, m, dark, f))
            val cbs = synchronized(lock) { waiting.remove(if (dark) "d" else "l") }
            cbs?.forEach { cb -> cb(if (ok) f else null) }
            if (dark != firstDark) otherDone = true
            // whoever waits next; otherwise the other theme's file, once
            val next: Boolean? = synchronized(lock) {
                val k = waiting.keys.firstOrNull()
                when {
                    k != null -> k == "d"
                    !otherDone -> !firstDark
                    else -> { builder = null; null }
                }
            }
            if (next == null) return
            dark = next
            if (next != firstDark && !otherDone && synchronized(lock) { waiting.isEmpty() }) {
                Thread.sleep(3000) // the first theme is drawing by now: no hurry
                // somebody may have come for a theme meanwhile (a rebuilt home screen): that one first
                synchronized(lock) { waiting.keys.firstOrNull() }?.let { dark = it == "d" }
            }
        }
    }

    fun fileFor(ctx: Context, m: CarFrames.Meta, dark: Boolean): File =
        named(ctx, framesHash(ctx, m), dark, bg(dark))

    private fun named(ctx: Context, frames: Int, dark: Boolean, bg: Int): File {
        var h = frames
        h = 31 * h + bg
        h = 31 * h + floorAlpha(dark).toBits()
        h = 31 * h + VERSION
        return File(ctx.noBackupFilesDir, "car-etc-%08x.bin".format(java.util.Locale.ROOT, h))
    }

    // Which APK this is, without reading it: every install or update changes it.
    private fun apkStamp(ctx: Context, m: CarFrames.Meta): String {
        val apk = File(ctx.applicationInfo.sourceDir)
        return "${BuildConfig.VERSION_CODE}:${apk.length()}:${apk.lastModified()}:${m.count}"
    }

    private fun knownHash(ctx: Context, m: CarFrames.Meta): Int? {
        val kept = Prefs.raw.getString(KEY_HASH, "").orEmpty()
        val stamp = apkStamp(ctx, m)
        return if (kept.startsWith("$stamp=")) kept.substring(stamp.length + 1).toIntOrNull() else null
    }

    // The frames' part of the file's name. Read from the assets once per APK and kept: the 181 assets opened at every
    // start came before the GPU's first frame, which now is the car's first frame.
    private fun framesHash(ctx: Context, m: CarFrames.Meta): Int {
        knownHash(ctx, m)?.let { return it }
        var h = ctx.assets.open("car/car.json").use { it.readBytes() }.contentHashCode()
        for (i in 0 until m.count) h = 31 * h + ctx.assets.open(CarFrames.frameAsset(i)).use { it.available() }
        Prefs.raw.edit().putString(KEY_HASH, "${apkStamp(ctx, m)}=$h").apply()
        return h
    }

    /**
     * The frame file for this theme is there, as far as can be told without opening the assets (a few stats: main thread).
     * False also after an update or install, once: nothing has read this APK's frames yet, and the CPU path, which
     * needs no such knowledge, draws that one start.
     */
    fun readyNow(ctx: Context, dark: Boolean, bg: Int): Boolean {
        val m = CarFrames.meta(ctx) ?: return false
        return try {
            val h = knownHash(ctx, m) ?: return false
            named(ctx, h, dark, bg).length() == HEADER + frameBytes(m).toLong() * m.count
        } catch (t: Throwable) {
            Log.w(TAG, "readyNow: $t")
            false
        }
    }

    fun valid(f: File, m: CarFrames.Meta): Boolean {
        if (!f.isFile || f.length() != HEADER + frameBytes(m).toLong() * m.count) return false
        return try {
            RandomAccessFile(f, "r").use { r ->
                val h = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                r.channel.read(h, 0)
                h.flip()
                h.int == MAGIC && h.int == m.count && h.int == m.width && h.int == m.height
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** What lies under the car in a frame: the background [bg] and the floor light (RGB, one int per pixel). */
    fun under(m: CarFrames.Meta, bg: Int, dark: Boolean): IntArray {
        val br = (bg shr 16) and 0xFF; val bgG = (bg shr 8) and 0xFF; val bb = bg and 0xFF
        val fa = floorAlpha(dark)
        val w = m.width; val h = m.height
        val cx = w / 2f; val cy = h * CarStage.FLOOR_Y; val r = w * CarStage.FLOOR_R
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x + 0.5f - cx) / r
            val dy = (y + 0.5f - cy) / (r * CarStage.FLOOR_SQUASH)
            val d = sqrt(dx * dx + dy * dy)
            // the light's stops (CarStage): 1 at the centre, 0.45 at 45 %, 0 at the rim
            val k = when {
                d >= 1f -> 0f
                d <= 0.45f -> 1f - d / 0.45f * 0.55f
                else -> 0.45f * (1f - (d - 0.45f) / 0.55f)
            }
            val a = fa * k
            fun ch(c: Int) = (c * (1f - a) + 255f * a + 0.5f).toInt().coerceIn(0, 255)
            out[y * w + x] = (ch(br) shl 16) or (ch(bgG) shl 8) or ch(bb)
        }
        return out
    }

    /** One frame over [under] as RGB bytes (unpremultiplied ARGB in, straight alpha over the opaque layer below). */
    fun compose(px: IntArray, under: IntArray, out: ByteArray) {
        var o = 0
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            val u = under[i]
            if (a == 0) {
                out[o] = (u shr 16).toByte(); out[o + 1] = (u shr 8).toByte(); out[o + 2] = u.toByte()
            } else {
                val ia = 255 - a
                out[o] = ((((p shr 16) and 0xFF) * a + ((u shr 16) and 0xFF) * ia + 127) / 255).toByte()
                out[o + 1] = ((((p shr 8) and 0xFF) * a + ((u shr 8) and 0xFF) * ia + 127) / 255).toByte()
                out[o + 2] = (((p and 0xFF) * a + (u and 0xFF) * ia + 127) / 255).toByte()
            }
            o += 3
        }
    }

    /** Frame [i] decoded and composed over [under] (RGB bytes), for the full-quality resting view. */
    fun composed(ctx: Context, m: CarFrames.Meta, i: Int, under: IntArray): ByteArray? = try {
        val bytes = ctx.assets.open(CarFrames.frameAsset(i)).use { it.readBytes() }
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val px = IntArray(m.width * m.height)
        bmp.getPixels(px, 0, m.width, 0, 0, m.width, m.height)
        bmp.recycle()
        ByteArray(m.width * m.height * 3).also { compose(px, under, it) }
    } catch (t: Throwable) {
        Log.w(TAG, "rest frame $i: $t")
        null
    }

    private fun build(ctx: Context, m: CarFrames.Meta, dark: Boolean, f: File): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        val dir = f.parentFile ?: return false
        val size = HEADER + frameBytes(m).toLong() * m.count
        if (dir.usableSpace < size + 64L * 1024 * 1024) { Log.w(TAG, "no room for the GPU frames"); return false }
        val tmp = File(f.path + ".tmp")
        val bg = bg(dark)
        val under = under(m, bg, dark)
        return try {
            RandomAccessFile(tmp, "rw").use { out ->
                out.setLength(size)
                val ch = out.channel
                // two encoders (the unit has four cores), each with its own buffers
                val workers = (0 until 2).map { part ->
                    Thread({
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        val px = IntArray(m.width * m.height)
                        val rgb = ByteArray(m.width * m.height * 3)
                        val inBuf = ByteBuffer.allocateDirect(rgb.size).order(ByteOrder.nativeOrder())
                        val outBuf = ByteBuffer.allocateDirect(frameBytes(m)).order(ByteOrder.nativeOrder())
                        val o = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        var reuse: Bitmap? = null
                        var i = part
                        while (i < m.count) {
                            val bytes = ctx.assets.open(CarFrames.frameAsset(i)).use { it.readBytes() }
                            o.inBitmap = reuse
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: throw IllegalStateException("decode $i")
                            if (bmp.width != m.width || bmp.height != m.height) throw IllegalStateException("frame $i size")
                            reuse = bmp
                            bmp.getPixels(px, 0, m.width, 0, 0, m.width, m.height)
                            compose(px, under, rgb)
                            inBuf.clear(); inBuf.put(rgb); inBuf.flip()
                            outBuf.clear()
                            ETC1.encodeImage(inBuf, m.width, m.height, 3, m.width * 3, outBuf)
                            outBuf.position(0); outBuf.limit(frameBytes(m))
                            var at = HEADER + i.toLong() * frameBytes(m)
                            while (outBuf.hasRemaining()) at += ch.write(outBuf, at)
                            i += 2
                        }
                    }, "car-etc-$part")
                }
                var failure: Throwable? = null
                workers.forEach { it.setUncaughtExceptionHandler { _, e -> failure = e }; it.start() }
                workers.forEach { it.join() }
                failure?.let { throw it }
                val head = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
                head.putInt(MAGIC).putInt(m.count).putInt(m.width).putInt(m.height).putInt(frameBytes(m)).putInt(bg)
                head.rewind()
                ch.write(head, 0)
                ch.force(false)
            }
            if (!tmp.renameTo(f)) throw IllegalStateException("rename")
            // older sets (other frames, other versions): at most this one and the other theme's stay
            val keep = setOf(f.name, try { fileFor(ctx, m, !dark).name } catch (_: Throwable) { "" })
            dir.listFiles()?.filter { it.name.startsWith("car-etc-") && it.name !in keep }?.forEach { it.delete() }
            Log.i(TAG, "GPU frames (${if (dark) "dark" else "light"}) built in ${SystemClock.elapsedRealtime() - t0} ms")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "GPU frames: $t")
            tmp.delete()
            false
        }
    }
}

/**
 * Draws the turntable with OpenGL ES 2 from [CarEtc]'s frames, all of them resident in the GPU: per frame the GPU
 * blends two textures and fades the edges into the background, and the CPU does nothing but set a few numbers. The
 * resting view is swapped for an uncompressed copy of its frame (crisp; compression only shows while it turns).
 * The frames of the intro ([firstFrames]) go in first and the car is drawn from them; the others follow one by one
 * ([loadRest]), a frame not in yet being stood in for by the nearest that is.
 * [CarStage] owns the motion and calls [render] with the state to show; until [onReady] it draws the frames itself.
 */
class CarGlView(context: Context, private val dark: Boolean, private val bg: Int) : TextureView(context), TextureView.SurfaceTextureListener {
    class State {
        var a = 0; var b = 0; var t = 0f
        var scale = 1f; var tx = 0f; var ty = 0f
        var shown = 1f
        var rest = false; var restMix = 0f
        var edge = 0f; var edgeBottom = 0f
        fun copyFrom(o: State) {
            a = o.a; b = o.b; t = o.t; scale = o.scale; tx = o.tx; ty = o.ty; shown = o.shown
            rest = o.rest; restMix = o.restMix; edge = o.edge; edgeBottom = o.edgeBottom
        }
    }

    /** The GPU draws the car now (main thread): the stage stops drawing it. */
    var onReady: (() -> Unit)? = null
    /** The GPU stopped drawing it (its surface went away) (main thread): the stage draws it again until [onReady]. */
    var onLost: (() -> Unit)? = null
    /** The GPU path cannot be used at all (main thread): the stage draws the frames itself from now on. */
    var onFailed: (() -> Unit)? = null
    /** The uncompressed copy of frame [i] is in the GPU (main thread). */
    var onRestReady: ((Int) -> Unit)? = null

    /** Frames drawn per second while the car last turned (diagnostics). */
    @Volatile var fps = 0f; private set

    /** The frames to have in the GPU before anything is drawn (main thread, before the surface comes): the intro's. */
    @Volatile var firstFrames: IntArray? = null

    private val meta = CarFrames.meta(context)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var gl: Handler? = null

    private val pending = State()
    private var posted = false            // guarded by pending
    private var hasState = false          // guarded by pending: the stage has said what to show
    private val cur = State()             // GL thread
    @Volatile private var file: File? = null
    @Volatile private var surface: SurfaceTexture? = null
    @Volatile private var vw = 0
    @Volatile private var vh = 0
    @Volatile private var failed = false
    @Volatile private var restFrame = -1
    private var restWanted = -1           // main thread

    // GL thread state
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglCtx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglCfg: EGLConfig? = null
    private var offscreen: EGLSurface = EGL14.EGL_NO_SURFACE // what the context is current on until the window's surface comes
    private var noOffscreen = false       // that did not work here: everything waits for the window's surface
    private var loaded = false            // the first frames are in the GPU
    @Volatile private var showing = false // a frame has been put on screen (the stage was told)
    private var program = 0
    private var tex = IntArray(0)
    private var have = BooleanArray(0)    // which of the textures hold their frame
    private var restStarted = false       // the frames after the first ones are coming (or came)
    private var restFile: RandomAccessFile? = null
    private var restOrder = IntArray(0)
    private var restAt = 0
    private var frameBuf: ByteBuffer? = null
    private var firstInAt = 0L
    private var restTex = 0
    private var quad: FloatBuffer? = null
    private var quadW = -1                // the view size the quad, the viewport and u_view were set for
    private var quadH = -1
    // (the program's locations, looked up once: by name on every frame they were a dozen hash lookups)
    private var aPos = 0
    private var uView = 0
    private var uXf = 0
    private var uT = 0
    private var uShown = 0
    private var uEdge = 0
    private var lastSwap = 0L
    private var motionFrames = 0
    private var motionStart = 0L

    init {
        surfaceTextureListener = this
        isOpaque = false // nothing drawn yet: the stage's own drawing shows through
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detached = false
        if (meta == null || failed || thread != null) return
        val t = HandlerThread("car-gl", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        thread = t
        val h = Handler(t.looper)
        gl = h
        CarEtc.ensure(context, dark, bg, h) { f ->
            if (f == null) fail("no frame file") else { file = f; tryStart() }
        }
    }

    // TextureView tells about its surface going away (onSurfaceTextureDestroyed) after this: the GL thread is
    // ended there, once it has let go of the surface; here only when there never was one
    override fun onDetachedFromWindow() {
        detached = true
        if (surface == null) {
            gl?.post { release() } // (the frames may be in already, for a surface that never came)
            quitThread()
        }
        super.onDetachedFromWindow()
    }

    private var detached = false

    private fun quitThread() {
        val t = thread
        thread = null
        gl = null
        t?.quitSafely() // what is queued (the release) still runs
    }

    /** Shows [s] (main thread; also before the frames are in, so the first frame is already the right one). */
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

    /** Prepares the uncompressed copy of frame [i] for the resting view (main thread). */
    fun prepareRest(i: Int) {
        val h = gl ?: return
        val m = meta ?: return
        if (i == restFrame) { main.post { if (restFrame == i) onRestReady?.invoke(i) }; return } // still in the GPU
        if (i == restWanted) return
        restWanted = i
        val app = context.applicationContext
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            // (the layer under the car, 0.9 MB, is made each time: the car comes to rest seldom, kept it was there for good)
            val rgb = CarEtc.composed(app, m, i, CarEtc.under(m, bg, dark))
            if (rgb == null || !h.post { uploadRest(i, rgb) }) main.post { if (restWanted == i) restWanted = -1 } // may be tried again
        }, "car-rest").apply { isDaemon = true; start() }
    }

    /** The frames after the first ones may come now (any thread): the intro is over, or a finger wants them. */
    fun loadRest() {
        gl?.post { startRest() }
    }

    // ------------------------------------------------------------------------------------------ TextureView
    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = st; vw = w; vh = h
        gl?.post { tryStart() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        vw = w; vh = h
        gl?.post { drawNow() }
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        surface = null
        val h = gl ?: return true
        // the GL objects go first, then the surface (released by the GL thread, hence false); a new surface loads the
        // frames again. A view going away ends its GL thread after that.
        val posted = h.post { release(); try { st.release() } catch (_: Throwable) { } }
        if (detached) quitThread()
        return !posted
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ------------------------------------------------------------------------------------------ GL thread
    private fun fail(why: String) {
        Log.w("AuraCar", "GPU car off: $why")
        failed = true
        main.post { visibility = GONE; onFailed?.invoke() }
    }

    /**
     * The context, the program and the first frames come as soon as the frame file is known, current on a small
     * off-screen surface: the window's surface only comes with the screen's first draw, and with the frames in by the
     * time that frame shows, the car starts with the rest of the screen. A driver that refuses this goes the plain
     * way: all of it once the window's surface is there.
     */
    private fun tryStart() {
        val f = file ?: return
        val m = meta ?: return
        if (failed) return
        try {
            if (!loaded) {
                val st = surface
                if (st == null && noOffscreen) return
                val t0 = SystemClock.elapsedRealtime()
                if (BuildConfig.DEBUG) debugHooks()
                initEgl(st)
                initProgram(m)
                val t1 = SystemClock.elapsedRealtime()
                val set = firstFrames ?: intArrayOf(Math.floorMod(m.defaultFrame, m.count))
                loadFirst(f, m, set)
                loaded = true
                firstInAt = SystemClock.elapsedRealtime()
                Log.i("AuraCar", "GPU car: ${set.size} of ${m.count} frames in ${firstInAt - t0} ms " +
                    "(EGL and program ${t1 - t0}, ${if (st == null) "ahead of the window" else "on the window"})")
                CarStage.gpuFirstMs = firstInAt - t0
                gl?.postDelayed({ startRest() }, REST_AFTER_MS) // (the intro's end or a touch asks for them before that)
            }
            if (eglSurface == EGL14.EGL_NO_SURFACE) attachWindow()
            if (eglSurface != EGL14.EGL_NO_SURFACE) drawRunnable.run() // the state the stage asked for meanwhile
        } catch (t: Throwable) {
            release()
            if (noOffscreen) fail(t.toString())
            else {
                Log.i("AuraCar", "GPU car ahead of the window: $t")
                noOffscreen = true
                tryStart() // (or when the window's surface comes)
            }
        }
    }

    // emulator tests: a slow start of the GPU path, one that fails, one without the off-screen surface
    private fun debugHooks() {
        SystemProps.get("debug.aura.car.slow").toLongOrNull()?.let { Thread.sleep(it) }
        if (SystemProps.get("debug.aura.car.fail") == "1") throw IllegalStateException("debug.aura.car.fail")
        if (SystemProps.get("debug.aura.car.nooff") == "1" && !noOffscreen) throw IllegalStateException("debug.aura.car.nooff")
    }

    private fun initEgl(st: SurfaceTexture?) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw IllegalStateException("eglInitialize")
        val type = if (noOffscreen) EGL14.EGL_WINDOW_BIT else EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, type,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, n, 0) || n[0] < 1) throw IllegalStateException("eglChooseConfig")
        eglCfg = cfgs[0]
        eglCtx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (eglCtx == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext")
        if (st != null) { attachWindow(); return }
        offscreen = EGL14.eglCreatePbufferSurface(display, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (offscreen == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreatePbufferSurface")
        if (!EGL14.eglMakeCurrent(display, offscreen, offscreen, eglCtx)) throw IllegalStateException("eglMakeCurrent")
    }

    /** The context moves to the window's surface, as soon as there is one. */
    private fun attachWindow() {
        val st = surface ?: return
        eglSurface = EGL14.eglCreateWindowSurface(display, eglCfg, st, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface")
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglCtx)) throw IllegalStateException("eglMakeCurrent")
        if (offscreen != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, offscreen)
            offscreen = EGL14.EGL_NO_SURFACE
        }
    }

    /**
     * The one program, in use for the context's whole life, with what never changes in it set once: the samplers'
     * units, the frame size, the background and the feather (a new context comes through here again).
     */
    private fun initProgram(m: CarFrames.Meta) {
        val vs = shader(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("link: " + GLES20.glGetProgramInfoLog(program))
        fun u(n: String) = GLES20.glGetUniformLocation(program, n)
        aPos = GLES20.glGetAttribLocation(program, "a_pos")
        uView = u("u_view"); uXf = u("u_xf"); uT = u("u_t"); uShown = u("u_shown"); uEdge = u("u_edge")
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(u("u_a"), 0)
        GLES20.glUniform1i(u("u_b"), 1)
        GLES20.glUniform2f(u("u_frame"), m.width.toFloat(), m.height.toFloat())
        GLES20.glUniform3f(u("u_bg"), ((bg shr 16) and 0xFF) / 255f, ((bg shr 8) and 0xFF) / 255f, (bg and 0xFF) / 255f)
        GLES20.glUniform2f(u("u_feather"), 6f / m.width, 6f / m.height)
        quad = ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quadW = -1
        quadH = -1
    }

    /** The quad covering the view, the viewport and u_view, for the view's size (set again only when it changes). */
    private fun fitView(q: FloatBuffer) {
        quadW = vw
        quadH = vh
        q.clear()
        q.put(floatArrayOf(0f, 0f, vw.toFloat(), 0f, 0f, vh.toFloat(), vw.toFloat(), vh.toFloat()))
        q.flip()
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, q)
        GLES20.glViewport(0, 0, vw, vh)
        GLES20.glUniform2f(uView, vw.toFloat(), vh.toFloat())
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

    /** The frames the car is first drawn from; all or nothing: without them there is no GPU path. */
    private fun loadFirst(f: File, m: CarFrames.Meta, set: IntArray) {
        tex = IntArray(m.count)
        have = BooleanArray(m.count)
        GLES20.glGenTextures(m.count, tex, 0)
        val buf = ByteBuffer.allocateDirect(CarEtc.frameBytes(m)).order(ByteOrder.nativeOrder())
        frameBuf = buf
        val r = RandomAccessFile(f, "r")
        restFile = r
        for (i in set) loadFrame(r, m, i, buf)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) throw IllegalStateException("texture upload: 0x" + Integer.toHexString(err))
    }

    private fun loadFrame(r: RandomAccessFile, m: CarFrames.Meta, i: Int, buf: ByteBuffer) {
        val bytes = CarEtc.frameBytes(m)
        buf.clear()
        var at = CarEtc.HEADER + i.toLong() * bytes
        while (buf.hasRemaining()) { val n = r.channel.read(buf, at); if (n <= 0) throw IllegalStateException("short read"); at += n }
        buf.flip()
        bindParams(tex[i])
        GLES20.glCompressedTexImage2D(GLES20.GL_TEXTURE_2D, 0, ETC1.ETC1_RGB8_OES, m.width, m.height, 0, bytes, buf)
        have[i] = true
    }

    /** The frames not in the GPU yet, nearest to the resting view first: one per message, so a draw waits for one at most. */
    private fun startRest() {
        val m = meta ?: return
        if (restStarted || !loaded) return
        restStarted = true
        val home = cur.a
        restOrder = (0 until m.count).filter { !have[it] }.sortedBy { i -> Math.floorMod(i - home, m.count).let { minOf(it, m.count - it) } }.toIntArray()
        restAt = 0
        gl?.post(loadNext)
    }

    private val loadNext: Runnable = object : Runnable {
        override fun run() {
            val m = meta ?: return
            val r = restFile
            val buf = frameBuf
            if (!loaded || r == null || buf == null) return
            try {
                if (restAt < restOrder.size) {
                    loadFrame(r, m, restOrder[restAt++], buf)
                    val err = GLES20.glGetError()
                    if (err != GLES20.GL_NO_ERROR) throw IllegalStateException("texture upload: 0x" + Integer.toHexString(err))
                    gl?.post(this)
                    return
                }
                Log.i("AuraCar", "GPU car: all ${m.count} frames in ${SystemClock.elapsedRealtime() - firstInAt} ms after the first ones")
                CarStage.gpuAllMs = SystemClock.elapsedRealtime() - firstInAt
            } catch (t: Throwable) {
                // what is in stays, and stands in for what is not: a coarser turn, no worse
                Log.w("AuraCar", "GPU frames after the first ones: $t")
                CarStage.gpuAllMs = -1L
            }
            closeRest()
        }
    }

    private fun closeRest() {
        try { restFile?.close() } catch (_: Throwable) { }
        restFile = null
        frameBuf = null
    }

    /** The texture of frame [i], or of the nearest frame that is in. */
    private fun texOf(i: Int): Int {
        val n = tex.size
        val k = Math.floorMod(i, n)
        if (have[k]) return tex[k]
        startRest()
        for (d in 1..n / 2) {
            val a = Math.floorMod(k - d, n)
            if (have[a]) return tex[a]
            val b = Math.floorMod(k + d, n)
            if (have[b]) return tex[b]
        }
        return tex[k]
    }

    private fun bindParams(t: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun uploadRest(i: Int, rgb: ByteArray) {
        val m = meta ?: return
        if (!loaded) { main.post { if (restWanted == i) restWanted = -1 }; return }
        try {
            if (restTex == 0) { val t = IntArray(1); GLES20.glGenTextures(1, t, 0); restTex = t[0] }
            bindParams(restTex)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            val buf = ByteBuffer.allocateDirect(rgb.size).order(ByteOrder.nativeOrder()).put(rgb)
            buf.flip()
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, m.width, m.height, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, buf)
            restFrame = i
            main.post { if (restWanted == i) restWanted = -1; onRestReady?.invoke(i) }
        } catch (t: Throwable) {
            Log.w("AuraCar", "rest upload: $t")
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
        if (!loaded || eglSurface == EGL14.EGL_NO_SURFACE || vw <= 0 || vh <= 0) return
        val s = cur
        val n = tex.size
        val a = texOf(s.a)
        val useRest = s.rest && s.restMix > 0f && restFrame == Math.floorMod(s.a, n) && restTex != 0
        val b = if (useRest) restTex else texOf(s.b)
        val t = if (useRest) s.restMix else s.t
        try {
            val q = quad!!
            if (vw != quadW || vh != quadH) fitView(q)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, a)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, b)
            GLES20.glUniform4f(uXf, s.scale, s.tx, s.ty, 0f)
            GLES20.glUniform1f(uT, t)
            GLES20.glUniform1f(uShown, s.shown)
            GLES20.glUniform2f(uEdge, s.edge.coerceAtLeast(1f), s.edgeBottom.coerceAtLeast(1f))
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                // the surface or the context went away: the stage draws until a new start succeeds (its last frame
                // must not stay on top of that, frozen)
                Log.w("AuraCar", "swap failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
                release()
                main.post { visibility = INVISIBLE }
                gl?.postDelayed({ tryStart() }, 500)
                return
            }
            if (!showing) {
                // the first frame is up: from now on the GPU draws the car (the view is opaque from then on)
                showing = true
                main.post { if (!failed) { visibility = VISIBLE; isOpaque = true; onReady?.invoke() } }
            }
            // diagnostics: frames per second while turning (draws closer than 100 ms apart count as one motion)
            val now = SystemClock.elapsedRealtime()
            if (now - lastSwap < 100) {
                if (motionFrames == 0) motionStart = lastSwap
                motionFrames++
            } else if (motionFrames > 20) {
                fps = motionFrames * 1000f / (lastSwap - motionStart).coerceAtLeast(1)
                motionFrames = 0
            } else motionFrames = 0
            lastSwap = now
        } catch (t: Throwable) {
            release()
            fail("draw: $t")
        }
    }

    private fun release() {
        if (showing) main.post { isOpaque = false; onLost?.invoke() }
        showing = false
        loaded = false
        restStarted = false
        closeRest()
        if (display != EGL14.EGL_NO_DISPLAY) {
            try {
                if (eglCtx != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroyContext(display, eglCtx) // its textures and program go with it
                }
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (offscreen != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, offscreen)
            } catch (_: Throwable) { }
        }
        eglCtx = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        offscreen = EGL14.EGL_NO_SURFACE
        display = EGL14.EGL_NO_DISPLAY
        tex = IntArray(0)
        have = BooleanArray(0)
        restTex = 0
        restFrame = -1
        program = 0
    }

    /** Is the GPU drawing the car (any thread)? */
    val drawing: Boolean get() = showing && !failed

    companion object {
        /** Longest the frames after the first ones wait for the intro's end (or a touch) to ask for them. */
        private const val REST_AFTER_MS = 3000L

        private const val VERTEX = """
attribute vec2 a_pos;
uniform vec2 u_view;
uniform vec4 u_xf;
uniform vec2 u_frame;
varying vec2 v_uv;
varying vec2 v_px;
void main() {
    v_px = a_pos;
    v_uv = (a_pos - u_xf.yz) / u_xf.x / u_frame;
    gl_Position = vec4(a_pos.x / u_view.x * 2.0 - 1.0, 1.0 - a_pos.y / u_view.y * 2.0, 0.0, 1.0);
}"""

        // the frames already contain the background and the floor light: outside a frame, at the view's edges and
        // while the car fades in, the background shows; two frames (or a frame and its crisp copy) are blended
        private const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D u_a;
uniform sampler2D u_b;
uniform float u_t;
uniform float u_shown;
uniform vec3 u_bg;
uniform vec2 u_view;
uniform vec2 u_edge;
uniform vec2 u_feather;
varying vec2 v_uv;
varying vec2 v_px;
void main() {
    vec3 c = mix(texture2D(u_a, v_uv).rgb, texture2D(u_b, v_uv).rgb, u_t);
    // compression shifts the flat background a few levels: what is that close to it is the background itself
    vec3 d = abs(c - u_bg);
    c = mix(c, u_bg, 1.0 - smoothstep(2.0 / 255.0, 6.0 / 255.0, max(d.r, max(d.g, d.b))));
    vec2 f = clamp(v_uv / u_feather, 0.0, 1.0) * clamp((1.0 - v_uv) / u_feather, 0.0, 1.0);
    float k = f.x * f.y * u_shown;
    k *= clamp(v_px.x / u_edge.x, 0.0, 1.0) * clamp((u_view.x - v_px.x) / u_edge.x, 0.0, 1.0);
    k *= clamp((u_view.y - v_px.y) / u_edge.y, 0.0, 1.0);
    gl_FragColor = vec4(mix(u_bg, c, k), 1.0);
}"""
    }
}
