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

    // the stage's background per theme (Palette) and the strength of its floor light (CarStage.floorColor)
    const val BG_DARK = 0xFF0B0C0E.toInt()
    const val BG_LIGHT = 0xFFECEEF1.toInt()
    fun floorAlpha(dark: Boolean) = if (dark) 0.085f else 0.95f

    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private val waiting = HashMap<String, MutableList<(File?) -> Unit>>()
    private var builder: Thread? = null

    fun frameBytes(m: CarFrames.Meta) = ETC1.getEncodedDataSize(m.width, m.height)

    /**
     * The frame file for the theme [dark], built first if needed (then the other theme's, at leisure). [done] runs
     * on the main thread with the file, or null when it cannot be had.
     */
    fun ensure(ctx: Context, dark: Boolean, done: (File?) -> Unit) {
        val app = ctx.applicationContext
        val m = CarFrames.meta(app)
        if (m == null) { main.post { done(null) }; return }
        synchronized(lock) {
            waiting.getOrPut(if (dark) "d" else "l") { ArrayList() }.add(done)
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
            cbs?.forEach { cb -> main.post { cb(if (ok) f else null) } }
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

    fun fileFor(ctx: Context, m: CarFrames.Meta, dark: Boolean): File {
        var h = ctx.assets.open("car/car.json").use { it.readBytes() }.contentHashCode()
        for (i in 0 until m.count) h = 31 * h + ctx.assets.open(CarFrames.frameAsset(i)).use { it.available() }
        h = 31 * h + (if (dark) BG_DARK else BG_LIGHT)
        h = 31 * h + floorAlpha(dark).toBits()
        h = 31 * h + VERSION
        return File(ctx.noBackupFilesDir, "car-etc-%08x.bin".format(java.util.Locale.ROOT, h))
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

    /** What lies under the car in a frame: the background and the floor light (RGB, one int per pixel). */
    fun under(m: CarFrames.Meta, dark: Boolean): IntArray {
        val bg = if (dark) BG_DARK else BG_LIGHT
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
        val under = under(m, dark)
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
                head.putInt(MAGIC).putInt(m.count).putInt(m.width).putInt(m.height).putInt(frameBytes(m)).putInt(if (dark) BG_DARK else BG_LIGHT)
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
 * [CarStage] owns the motion and calls [render] with the state to show; until [onReady] it draws the frames itself.
 */
class CarGlView(context: Context, private val dark: Boolean) : TextureView(context), TextureView.SurfaceTextureListener {
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
    private var loaded = false            // frames in the GPU
    @Volatile private var showing = false // a frame has been put on screen (the stage was told)
    private var program = 0
    private var tex = IntArray(0)
    private var restTex = 0
    private var quad: FloatBuffer? = null
    private val loc = HashMap<String, Int>()
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
        gl = Handler(t.looper)
        CarEtc.ensure(context, dark) { f ->
            if (f == null) fail("no frame file") else { file = f; gl?.post { tryStart() } }
        }
    }

    // TextureView tells about its surface going away (onSurfaceTextureDestroyed) after this: the GL thread is
    // ended there, once it has let go of the surface; here only when there never was one
    override fun onDetachedFromWindow() {
        detached = true
        if (surface == null) quitThread()
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
            val rgb = CarEtc.composed(app, m, i, underLayer(m))
            if (rgb == null || !h.post { uploadRest(i, rgb) }) main.post { if (restWanted == i) restWanted = -1 } // may be tried again
        }, "car-rest").apply { isDaemon = true; start() }
    }

    private var underCache: IntArray? = null
    @Synchronized private fun underLayer(m: CarFrames.Meta): IntArray = underCache ?: CarEtc.under(m, dark).also { underCache = it }

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

    private fun tryStart() {
        val st = surface ?: return
        val f = file ?: return
        val m = meta ?: return
        if (eglCtx != EGL14.EGL_NO_CONTEXT || failed) return
        try {
            val t0 = SystemClock.elapsedRealtime()
            initEgl(st)
            initProgram()
            loadFrames(f, m)
            loaded = true
            Log.i("AuraCar", "GPU car: ${m.count} frames in ${SystemClock.elapsedRealtime() - t0} ms")
            drawRunnable.run() // the state the stage asked for meanwhile
        } catch (t: Throwable) {
            release()
            fail(t.toString())
        }
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
        eglCtx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (eglCtx == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext")
        eglSurface = EGL14.eglCreateWindowSurface(display, cfgs[0], st, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface")
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglCtx)) throw IllegalStateException("eglMakeCurrent")
    }

    private fun initProgram() {
        val vs = shader(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("link: " + GLES20.glGetProgramInfoLog(program))
        for (n in listOf("a_pos")) loc[n] = GLES20.glGetAttribLocation(program, n)
        for (n in listOf("u_view", "u_xf", "u_frame", "u_a", "u_b", "u_t", "u_shown", "u_bg", "u_edge", "u_feather"))
            loc[n] = GLES20.glGetUniformLocation(program, n)
        quad = ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
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

    private fun loadFrames(f: File, m: CarFrames.Meta) {
        val bytes = CarEtc.frameBytes(m)
        tex = IntArray(m.count)
        GLES20.glGenTextures(m.count, tex, 0)
        val buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        RandomAccessFile(f, "r").use { r ->
            val ch = r.channel
            for (i in 0 until m.count) {
                buf.clear()
                var at = CarEtc.HEADER + i.toLong() * bytes
                while (buf.hasRemaining()) { val n = ch.read(buf, at); if (n <= 0) throw IllegalStateException("short read"); at += n }
                buf.flip()
                bindParams(tex[i])
                GLES20.glCompressedTexImage2D(GLES20.GL_TEXTURE_2D, 0, ETC1.ETC1_RGB8_OES, m.width, m.height, 0, bytes, buf)
            }
        }
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) throw IllegalStateException("texture upload: 0x" + Integer.toHexString(err))
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
        val m = meta ?: return
        if (!loaded || vw <= 0 || vh <= 0) return
        val s = cur
        val n = tex.size
        val a = tex[Math.floorMod(s.a, n)]
        val useRest = s.rest && s.restMix > 0f && restFrame == Math.floorMod(s.a, n) && restTex != 0
        val b = if (useRest) restTex else tex[Math.floorMod(s.b, n)]
        val t = if (useRest) s.restMix else s.t
        try {
            GLES20.glViewport(0, 0, vw, vh)
            GLES20.glUseProgram(program)
            val q = quad!!
            q.clear()
            q.put(floatArrayOf(0f, 0f, vw.toFloat(), 0f, 0f, vh.toFloat(), vw.toFloat(), vh.toFloat()))
            q.flip()
            val ap = loc["a_pos"]!!
            GLES20.glEnableVertexAttribArray(ap)
            GLES20.glVertexAttribPointer(ap, 2, GLES20.GL_FLOAT, false, 0, q)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, a)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, b)
            GLES20.glUniform1i(loc["u_a"]!!, 0)
            GLES20.glUniform1i(loc["u_b"]!!, 1)
            GLES20.glUniform2f(loc["u_view"]!!, vw.toFloat(), vh.toFloat())
            GLES20.glUniform4f(loc["u_xf"]!!, s.scale, s.tx, s.ty, 0f)
            GLES20.glUniform2f(loc["u_frame"]!!, m.width.toFloat(), m.height.toFloat())
            GLES20.glUniform1f(loc["u_t"]!!, t)
            GLES20.glUniform1f(loc["u_shown"]!!, s.shown)
            val bg = if (dark) CarEtc.BG_DARK else CarEtc.BG_LIGHT
            GLES20.glUniform3f(loc["u_bg"]!!, ((bg shr 16) and 0xFF) / 255f, ((bg shr 8) and 0xFF) / 255f, (bg and 0xFF) / 255f)
            GLES20.glUniform2f(loc["u_edge"]!!, s.edge.coerceAtLeast(1f), s.edgeBottom.coerceAtLeast(1f))
            GLES20.glUniform2f(loc["u_feather"]!!, 6f / m.width, 6f / m.height)
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
        if (display != EGL14.EGL_NO_DISPLAY) {
            try {
                if (eglCtx != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroyContext(display, eglCtx) // its textures and program go with it
                }
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            } catch (_: Throwable) { }
        }
        eglCtx = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        display = EGL14.EGL_NO_DISPLAY
        tex = IntArray(0)
        restTex = 0
        restFrame = -1
        program = 0
    }

    /** Is the GPU drawing the car (any thread)? */
    val drawing: Boolean get() = showing && !failed

    companion object {
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
