package com.abdllh.aura.diag

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.ETC1
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * What the GPU itself says, asked through EGL with a throwaway context: the firmware declares ES 2.0
 * (ro.opengles.version), which says what it was built to promise, not what the driver can do. Runs in :diag, on the
 * caller's thread, one context at a time: a driver that dies on an ES 3 context takes only that test with it.
 */
internal object GlInfo {
    private const val EGL_OPENGL_ES3_BIT = 0x40

    private class Ctx(val dpy: EGLDisplay, val surface: EGLSurface, val context: EGLContext) {
        fun close() {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surface)
            EGL14.eglDestroyContext(dpy, context)
            EGL14.eglReleaseThread()
        }
    }

    /** A current context of client [version] on a small pbuffer, or null with the reason in [why]. */
    private fun open(version: Int, why: JSONObject): Ctx? {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        if (dpy == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(dpy, v, 0, v, 1)) { why.put("why", "eglInitialize"); return null }
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        fun choose(bit: Int) = EGL14.eglChooseConfig(dpy, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, bit,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0
        if (!choose(if (version >= 3) EGL_OPENGL_ES3_BIT else EGL14.EGL_OPENGL_ES2_BIT)) {
            // no config admits to ES 3; a driver may still make the context on an ES 2 one, and that is what is asked
            if (version >= 3) why.put("es3Config", false)
            if (version < 3 || !choose(EGL14.EGL_OPENGL_ES2_BIT)) {
                why.put("why", "no EGL config for ES $version").put("eglError", EGL14.eglGetError())
                return null
            }
        }
        val cfg = configs[0]
        val context = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, version, EGL14.EGL_NONE), 0)
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            why.put("why", "eglCreateContext").put("eglError", EGL14.eglGetError())
            return null
        }
        val surface = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            why.put("why", "eglCreatePbufferSurface").put("eglError", EGL14.eglGetError())
            EGL14.eglDestroyContext(dpy, context)
            return null
        }
        if (!EGL14.eglMakeCurrent(dpy, surface, surface, context)) {
            why.put("why", "eglMakeCurrent").put("eglError", EGL14.eglGetError())
            EGL14.eglDestroySurface(dpy, surface)
            EGL14.eglDestroyContext(dpy, context)
            return null
        }
        return Ctx(dpy, surface, context)
    }

    private fun limit(name: Int, n: Int = 1): Any {
        val v = IntArray(n.coerceAtLeast(2))
        GLES20.glGetIntegerv(name, v, 0)
        return if (n == 1) v[0] else JSONArray().apply { for (i in 0 until n) put(v[i]) }
    }

    /** One context of client [version]: names, extensions, limits. "ok" false (and "why"): it could not be made. */
    fun info(version: Int): JSONObject {
        val o = JSONObject().put("asked", version)
        val c = open(version, o)
        if (c == null) return o.put("ok", false)
        try {
            o.put("ok", true)
            o.put("vendor", GLES20.glGetString(GLES20.GL_VENDOR))
            o.put("renderer", GLES20.glGetString(GLES20.GL_RENDERER))
            o.put("version", GLES20.glGetString(GLES20.GL_VERSION))
            o.put("glsl", GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION))
            o.put("eglVendor", EGL14.eglQueryString(c.dpy, EGL14.EGL_VENDOR))
            o.put("eglVersion", EGL14.eglQueryString(c.dpy, EGL14.EGL_VERSION))
            val glx = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
            val eglx = EGL14.eglQueryString(c.dpy, EGL14.EGL_EXTENSIONS).orEmpty()
            o.put("extensions", glx)
            o.put("eglExtensions", eglx)
            val gl = glx.split(' ').toSet()
            val egl = eglx.split(' ').toSet()
            val formats = run {
                val n = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_NUM_COMPRESSED_TEXTURE_FORMATS, n, 0)
                val list = IntArray(n[0].coerceIn(0, 256).coerceAtLeast(1))
                GLES20.glGetIntegerv(GLES20.GL_COMPRESSED_TEXTURE_FORMATS, list, 0)
                list.take(n[0].coerceIn(0, 256))
            }
            o.put("compressedFormats", JSONArray().apply { for (f in formats) put("0x" + Integer.toHexString(f)) })
            // the questions the plans for 2.0 hang on
            o.put("has", JSONObject()
                .put("astcLdr", "GL_KHR_texture_compression_astc_ldr" in gl)
                .put("astcHdr", "GL_KHR_texture_compression_astc_hdr" in gl)
                .put("etc1", "GL_OES_compressed_ETC1_RGB8_texture" in gl)
                .put("etc2", 0x9278 in formats || "GL_ARB_ES3_compatibility" in gl)
                .put("eglPartialUpdate", "EGL_KHR_partial_update" in egl)
                .put("eglBufferAge", "EGL_EXT_buffer_age" in egl)
                .put("eglSwapDamage", "EGL_KHR_swap_buffers_with_damage" in egl || "EGL_EXT_swap_buffers_with_damage" in egl)
                .put("discardFramebuffer", "GL_EXT_discard_framebuffer" in gl)
                .put("msaaToTexture", "GL_EXT_multisampled_render_to_texture" in gl)
                .put("framebufferFetch", "GL_EXT_shader_framebuffer_fetch" in gl || "GL_ARM_shader_framebuffer_fetch" in gl)
                .put("vertexArrays", "GL_OES_vertex_array_object" in gl)
                .put("npot", "GL_OES_texture_npot" in gl)
                .put("derivatives", "GL_OES_standard_derivatives" in gl))
            o.put("limits", JSONObject()
                .put("textureSize", limit(GLES20.GL_MAX_TEXTURE_SIZE))
                .put("cubeSize", limit(GLES20.GL_MAX_CUBE_MAP_TEXTURE_SIZE))
                .put("renderbufferSize", limit(GLES20.GL_MAX_RENDERBUFFER_SIZE))
                .put("viewport", limit(GLES20.GL_MAX_VIEWPORT_DIMS, 2))
                .put("textureUnits", limit(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS))
                .put("vertexTextureUnits", limit(GLES20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS))
                .put("combinedTextureUnits", limit(GLES20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS))
                .put("vertexAttribs", limit(GLES20.GL_MAX_VERTEX_ATTRIBS))
                .put("vertexUniforms", limit(GLES20.GL_MAX_VERTEX_UNIFORM_VECTORS))
                .put("fragmentUniforms", limit(GLES20.GL_MAX_FRAGMENT_UNIFORM_VECTORS))
                .put("varyings", limit(GLES20.GL_MAX_VARYING_VECTORS))
                .put("lineWidths", limit(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, 2)))
            val range = IntArray(2)
            val precision = IntArray(1)
            GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, precision, 0)
            o.put("fragmentHighp", precision[0] > 0)
            if (version >= 3) {
                o.put("major", limit(GLES30.GL_MAJOR_VERSION))
                o.put("minor", limit(GLES30.GL_MINOR_VERSION))
                o.put("limits3", JSONObject()
                    .put("samples", limit(GLES30.GL_MAX_SAMPLES))
                    .put("texture3d", limit(GLES30.GL_MAX_3D_TEXTURE_SIZE))
                    .put("arrayLayers", limit(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS))
                    .put("drawBuffers", limit(GLES30.GL_MAX_DRAW_BUFFERS))
                    .put("uniformBuffers", limit(GLES30.GL_MAX_UNIFORM_BUFFER_BINDINGS)))
                // a context is one thing, a real ES 3 shader another
                val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
                GLES20.glShaderSource(vs, "#version 300 es\nin vec2 p; void main() { gl_Position = vec4(p, 0.0, 1.0); }")
                GLES20.glCompileShader(vs)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(vs, GLES20.GL_COMPILE_STATUS, ok, 0)
                o.put("shader300", ok[0] != 0)
                GLES20.glDeleteShader(vs)
            }
        } finally {
            c.close()
        }
        return o
    }

    private class Fmt(val name: String, val gl: Int, val size: Int, val block: ByteArray)

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /**
     * Whether a format is real, not only named: one block of a known colour (red) uploaded, drawn and read back. The
     * driver naming ASTC or ETC2 was all anyone knew until now.
     */
    fun formats(): JSONObject {
        val o = JSONObject()
        val c = open(3, o) ?: open(2, o) ?: return o.put("ok", false)
        try {
            o.put("ok", true)
            o.remove("why"); o.remove("eglError")
            val prog = program(
                "attribute vec2 p; varying vec2 v; void main() { v = p * 0.5 + 0.5; gl_Position = vec4(p, 0.0, 1.0); }",
                "precision mediump float; varying vec2 v; uniform sampler2D t; void main() { gl_FragColor = texture2D(t, v); }")
            GLES20.glUseProgram(prog)
            val quad = quad()
            val at = GLES20.glGetAttribLocation(prog, "p")
            GLES20.glEnableVertexAttribArray(at)
            GLES20.glVertexAttribPointer(at, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glViewport(0, 0, 16, 16)
            // red in every format: ETC1's individual mode with R1 = R2 = 15 and the smallest modifier; an EAC alpha of
            // 255; ASTC's void-extent block, one constant colour for the whole block
            val etc = bytes(0xFF, 0x00, 0x00, 0x00, 0, 0, 0, 0)
            val eac = bytes(0xFF, 0x00, 0, 0, 0, 0, 0, 0) + etc
            val astc = bytes(0xFC, 0xFD, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF)
            val list = listOf(
                Fmt("etc1", ETC1.ETC1_RGB8_OES, 4, etc),
                Fmt("etc2Rgb", 0x9274, 4, etc),
                Fmt("etc2Rgba", 0x9278, 4, eac),
                Fmt("astc4x4", 0x93B0, 4, astc),
                Fmt("astc6x6", 0x93B4, 6, astc),
                Fmt("astc8x8", 0x93B7, 8, astc))
            val ids = IntArray(1)
            for (f in list) {
                val r = JSONObject()
                while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* (what came before) */ }
                GLES20.glGenTextures(1, ids, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                val buf = ByteBuffer.allocateDirect(f.block.size).order(ByteOrder.nativeOrder()).put(f.block)
                buf.position(0)
                GLES20.glCompressedTexImage2D(GLES20.GL_TEXTURE_2D, 0, f.gl, f.size, f.size, 0, f.block.size, buf)
                val err = GLES20.glGetError()
                r.put("uploadError", err)
                if (err == GLES20.GL_NO_ERROR) {
                    GLES20.glClearColor(0f, 0f, 1f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
                    GLES20.glReadPixels(8, 8, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
                    val rgb = IntArray(4) { px.get(it).toInt() and 0xFF }
                    r.put("pixel", JSONArray().apply { for (x in rgb) put(x) })
                    r.put("right", rgb[0] > 200 && rgb[1] < 40 && rgb[2] < 40)
                } else r.put("right", false)
                GLES20.glDeleteTextures(1, ids, 0)
                o.put(f.name, r)
            }
            o.put("quadBytes", quad.capacity()) // (GL keeps only the pointer: the buffer must live until here)
        } finally {
            c.close()
        }
        return o
    }

    fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    /** The whole viewport as a triangle strip. */
    fun quad(): ByteBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).apply {
        for (v in floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)) putFloat(v)
        position(0)
    }
}

/**
 * How many pixels the GPU fills, on the screen and through the window above it: N textured quads the size of the
 * surface, with blending off and on, about two seconds each, each frame ended by glFinish. The time around the drawing
 * is the GPU's own and is what "Mpix" comes from; the frames per second also show what the display's pace allows
 * ("swap interval 0" asked for, not always given). With blending off a tile GPU may never shade the hidden layers
 * (Mali's forward pixel kill): the difference between the two columns is itself a fact to know. Then the same picture
 * in ETC1, the format the car is kept in, against RGBA8.
 */
@SuppressLint("ViewConstructor")
internal class FillView(ctx: Context, private val refreshRate: Float, private val done: (JSONObject) -> Unit) : GLSurfaceView(ctx), GLSurfaceView.Renderer {
    private class Mode(val name: String, val layers: Int, val blend: Boolean, val etc1: Boolean)

    private val modes = listOf(
        Mode("opaque x1", 1, false, false), Mode("opaque x2", 2, false, false), Mode("opaque x4", 4, false, false), Mode("opaque x8", 8, false, false),
        Mode("blend x1", 1, true, false), Mode("blend x2", 2, true, false), Mode("blend x4", 4, true, false), Mode("blend x8", 8, true, false),
        Mode("etc1 opaque x1", 1, false, true), Mode("etc1 blend x2", 2, true, true))

    private var w = 0
    private var h = 0
    private val quad = GlInfo.quad() // (GL keeps only the pointer: the buffer must live as long as the drawing)
    private var rgba = 0
    private var etc1 = 0
    private var uAlpha = 0
    private var index = 0
    private var modeStart = 0L
    private var first = 0L
    private var last = 0L
    private var n = 0
    private val gpu = DoubleArray(4096)
    private val out = JSONObject()
    private val rows = JSONArray()
    private var over = false

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser { egl, dpy ->
            val attrs = intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, 4 /* ES 2 */, EGL10.EGL_NONE)
            val c = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!egl.eglChooseConfig(dpy, attrs, c, 1, count) || count[0] == 0) throw IllegalArgumentException("no EGL config")
            c[0]!!
        }
        holder.setFormat(PixelFormat.RGBA_8888)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private fun fail(t: Throwable) {
        if (over) return
        over = true
        renderMode = RENDERMODE_WHEN_DIRTY
        out.put("error", t.toString())
        out.put("modes", rows)
        post { done(out) }
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        try {
            out.put("swapInterval0", EGL14.eglSwapInterval(EGL14.eglGetCurrentDisplay(), 0))
            val prog = GlInfo.program(
                "attribute vec2 p; varying vec2 v; void main() { v = p * 0.5 + 0.5; gl_Position = vec4(p, 0.0, 1.0); }",
                "precision mediump float; varying vec2 v; uniform sampler2D t; uniform float a; void main() { gl_FragColor = vec4(texture2D(t, v).rgb, a); }")
            GLES20.glUseProgram(prog)
            val at = GLES20.glGetAttribLocation(prog, "p")
            GLES20.glEnableVertexAttribArray(at)
            GLES20.glVertexAttribPointer(at, 2, GLES20.GL_FLOAT, false, 0, quad)
            uAlpha = GLES20.glGetUniformLocation(prog, "a")
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            // (the window keeps its alpha: only the colour blends)
            GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ZERO, GLES20.GL_ONE)
            makeTextures()
        } catch (t: Throwable) {
            fail(t)
        }
    }

    /** One 512 x 512 picture with no flat areas, in both formats. Encoding it is also a measure of this CPU. */
    private fun makeTextures() {
        val size = 512
        val rgb = ByteBuffer.allocateDirect(size * size * 3).order(ByteOrder.nativeOrder())
        val full = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        var seed = 0x2545F491
        for (y in 0 until size) for (x in 0 until size) {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            val noise = seed and 0x1F
            val r = (x * 255 / size + noise).coerceAtMost(255)
            val g = (y * 255 / size + noise).coerceAtMost(255)
            val b = (128 + noise * 2).coerceAtMost(255)
            rgb.put(r.toByte()); rgb.put(g.toByte()); rgb.put(b.toByte())
            full.put(r.toByte()); full.put(g.toByte()); full.put(b.toByte()); full.put(255.toByte())
        }
        rgb.position(0)
        full.position(0)
        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        rgba = ids[0]
        etc1 = ids[1]
        fun params() {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rgba)
        params()
        var t0 = SystemClock.elapsedRealtime()
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, full)
        GLES20.glFinish()
        out.put("rgba8UploadMs", SystemClock.elapsedRealtime() - t0)
        val coded = ByteBuffer.allocateDirect(ETC1.getEncodedDataSize(size, size)).order(ByteOrder.nativeOrder())
        t0 = SystemClock.elapsedRealtime()
        ETC1.encodeImage(rgb, size, size, 3, size * 3, coded)
        out.put("etc1EncodeMs", SystemClock.elapsedRealtime() - t0) // (one thread of plain native code, 512 x 512)
        coded.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, etc1)
        params()
        t0 = SystemClock.elapsedRealtime()
        GLES20.glCompressedTexImage2D(GLES20.GL_TEXTURE_2D, 0, ETC1.ETC1_RGB8_OES, size, size, 0, coded.capacity(), coded)
        GLES20.glFinish()
        out.put("etc1UploadMs", SystemClock.elapsedRealtime() - t0)
        out.put("textureSize", size)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        w = width
        h = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(unused: GL10?) {
        if (over) return
        try {
            val m = modes[index]
            val t0 = System.nanoTime()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (m.etc1) etc1 else rgba)
            if (m.blend) GLES20.glEnable(GLES20.GL_BLEND) else GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUniform1f(uAlpha, if (m.blend) 0.5f else 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            for (i in 0 until m.layers) GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glFinish()
            val t1 = System.nanoTime()
            if (modeStart == 0L) modeStart = t0
            val age = (t1 - modeStart) / 1_000_000L
            if (age >= WARM_MS) {
                if (first == 0L) first = t1
                last = t1
                if (n < gpu.size) gpu[n++] = (t1 - t0) / 1e6
            }
            if (age >= WARM_MS + WINDOW_MS) nextMode(m)
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun nextMode(m: Mode) {
        val r = JSONObject().put("name", m.name).put("layers", m.layers).put("blend", m.blend).put("etc1", m.etc1)
        val glError = GLES20.glGetError()
        if (glError != GLES20.GL_NO_ERROR) r.put("glError", glError)
        val times = gpu.copyOf(n)
        val mean = if (n > 0) times.average() else Double.NaN
        val seconds = (last - first) / 1e9
        val fps = if (n > 1 && seconds > 0) (n - 1) / seconds else Double.NaN
        val pixels = w.toDouble() * h * m.layers
        r.put("frames", n)
        r.put("fps", Num.of(fps))
        r.put("gpuMs", Num.of(mean, 2))
        Num.spread(r, times)
        // (Mpixels a second: the GPU's own time, and what reached the screen)
        r.put("mpix", Num.of(if (mean > 0) pixels / mean / 1000.0 else Double.NaN))
        r.put("mpixShown", Num.of(fps * pixels / 1e6))
        rows.put(r)
        index++
        modeStart = 0L
        first = 0L
        last = 0L
        n = 0
        if (index >= modes.size) {
            over = true
            renderMode = RENDERMODE_WHEN_DIRTY
            out.put("width", w).put("height", h).put("refreshRate", Num.of(refreshRate.toDouble())).put("modes", rows)
            post { done(out) }
        }
    }

    private companion object {
        const val WARM_MS = 300L
        const val WINDOW_MS = 2000L
    }
}
