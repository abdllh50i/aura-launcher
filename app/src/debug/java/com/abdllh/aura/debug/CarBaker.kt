package com.abdllh.aura.debug

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.opengl.GLES20.*
import android.opengl.GLUtils
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import android.opengl.Matrix as Mx

/**
 * Off-line renderer for the car turntable (GLES 2.0, runs on the emulator's host GPU).
 *
 * Lighting: a procedural photo studio (overhead softbox, key light, side strips, rim) used both as the light source and for
 * reflections. Occlusion: the car is rendered as depth from [dirs] directions spread over the sphere; every pixel tests
 * itself against all of them, which gives soft shadows, ambient occlusion and the contact shadow on the floor.
 * Specular: GGX importance sampling of the studio plus a clear-coat lobe for the paint. Output: premultiplied RGBA PNGs
 * (car opaque, floor shadow as translucent black, background transparent), [ss] times the final size for anti-aliasing.
 */
class CarBaker(private val dir: File, private val log: (String) -> Unit) {
    private val params = HashMap<String, String>()
    private fun pf(k: String, d: Float) = params[k]?.toFloatOrNull() ?: d
    private fun pi(k: String, d: Int) = params[k]?.toIntOrNull() ?: d

    private var nVerts = 0
    private var nIdx = 0
    private val vbo = IntArray(4)
    private val bmin = FloatArray(3) { Float.MAX_VALUE }
    private val bmax = FloatArray(3) { -Float.MAX_VALUE }

    fun run() {
        File(dir, "params.txt").takeIf { it.exists() }?.readLines()?.forEach { line ->
            val kv = line.split("=", limit = 2)
            if (kv.size == 2) params[kv[0].trim()] = kv[1].trim()
        }
        val frames = pi("frames", 90)
        val outW = pi("width", 640)
        val outH = pi("height", 360)
        val ss = pi("ss", 4)
        val nDir = pi("dirs", 64)
        val only = params["only"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet()

        val ext = glGetString(GL_EXTENSIONS) ?: ""
        val maxTex = IntArray(1).also { glGetIntegerv(GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        val maxRb = IntArray(1).also { glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE, it, 0) }[0]
        val maxFu = IntArray(1).also { glGetIntegerv(GL_MAX_FRAGMENT_UNIFORM_VECTORS, it, 0) }[0]
        log("GL ${glGetString(GL_RENDERER)} maxTex=$maxTex maxRb=$maxRb fragUniforms=$maxFu uint=${"element_index_uint" in ext}")
        require("GL_OES_element_index_uint" in ext) { "32-bit indices are not supported" }

        loadMesh(File(dir, "mesh.bin"))
        val texBase = loadTexture(File(dir, "basecolor.jpg"))
        val texRM = loadTexture(File(dir, "rm.png"))

        // ---- occlusion: depth of the car seen from nDir directions, packed into one atlas
        val cx = (bmin[0] + bmax[0]) / 2
        val cy = (bmin[1] + bmax[1]) / 2
        val cz = (bmin[2] + bmax[2]) / 2
        val radius = 0.5f * sqrt(sq(bmax[0] - bmin[0]) + sq(bmax[1] - bmin[1]) + sq(bmax[2] - bmin[2])) * 1.02f
        val tiles = ceil(sqrt(nDir.toDouble())).toInt()
        var tileRes = pi("tile", 1024)
        while (tiles * tileRes > min(maxTex, maxRb)) tileRes /= 2
        val atlasSize = tiles * tileRes
        val dirs = FloatArray(nDir * 3)
        val dirVP = FloatArray(nDir * 16)
        val golden = PI * (3 - sqrt(5.0))
        for (k in 0 until nDir) {
            val y = 1 - 2 * (k + 0.5) / nDir
            val r = sqrt(1 - y * y)
            val ph = golden * k
            val dx = (cos(ph) * r).toFloat()
            val dy = y.toFloat()
            val dz = (sin(ph) * r).toFloat()
            dirs[k * 3] = dx; dirs[k * 3 + 1] = dy; dirs[k * 3 + 2] = dz
            val view = FloatArray(16)
            val up = if (abs(dy) > 0.98f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
            Mx.setLookAtM(view, 0, cx + dx * radius * 2, cy + dy * radius * 2, cz + dz * radius * 2, cx, cy, cz, up[0], up[1], up[2])
            val proj = FloatArray(16)
            Mx.orthoM(proj, 0, -radius, radius, -radius, radius, radius, radius * 3)
            Mx.multiplyMM(dirVP, k * 16, proj, 0, view, 0)
        }
        log("occlusion: $nDir directions, atlas ${atlasSize}x$atlasSize (tile $tileRes)")
        val atlas = bakeDepthAtlas(dirVP, nDir, tiles, tileRes, atlasSize)
        val floorExtent = radius * 1.4f
        val floorShadow = bakeFloorShadow(floorExtent)

        // ---- camera framing: the whole car (and some floor) must fit at every angle
        val w = outW * ss
        val h = outH * ss
        val fov = pf("fov", 24f)
        val elev = Math.toRadians(pf("elevation", 14f).toDouble())
        val targetY = (bmin[1] + (bmax[1] - bmin[1]) * pf("target", 0.42f))
        val margin = pf("margin", 0.86f)
        val proj = FloatArray(16)
        var dist = radius * 3
        repeat(8) {
            Mx.perspectiveM(proj, 0, fov, w.toFloat() / h, dist * 0.2f, dist * 4f)
            var worst = 0f
            for (a in 0 until 360 step 5) {
                val vp = mvp(proj, dist, elev, targetY, a.toFloat())
                for (corner in 0 until 8) {
                    val x = if (corner and 1 == 0) bmin[0] else bmax[0]
                    val y = if (corner and 2 == 0) bmin[1] else bmax[1]
                    val z = if (corner and 4 == 0) bmin[2] else bmax[2]
                    val c = FloatArray(4)
                    Mx.multiplyMV(c, 0, vp, 0, floatArrayOf(x, y, z, 1f), 0)
                    worst = max(worst, max(abs(c[0] / c[3]), abs(c[1] / c[3])))
                }
            }
            dist *= worst / margin
        }
        Mx.perspectiveM(proj, 0, fov, w.toFloat() / h, dist * 0.2f, dist * 4f)
        log("camera distance ${"%.1f".format(dist)}, ${w}x$h")

        // ---- programs
        val carProg = program(VS_CAR, fsCar(nDir, pi("spec", 48)))
        val floorProg = program(VS_FLOOR, FS_FLOOR)
        val xi = FloatArray(pi("spec", 48) * 2)
        for (i in 0 until xi.size / 2) {
            xi[i * 2] = (i + 0.5f) / (xi.size / 2)
            xi[i * 2 + 1] = radicalInverse(i)
        }

        // ---- target framebuffer
        val fb = IntArray(1); val rb = IntArray(2)
        glGenFramebuffers(1, fb, 0); glGenRenderbuffers(2, rb, 0)
        glBindRenderbuffer(GL_RENDERBUFFER, rb[0]); glRenderbufferStorage(GL_RENDERBUFFER, 0x8058 /* RGBA8_OES */, w, h)
        glBindRenderbuffer(GL_RENDERBUFFER, rb[1]); glRenderbufferStorage(GL_RENDERBUFFER, 0x81A6 /* DEPTH_COMPONENT24_OES */, w, h)
        glBindFramebuffer(GL_FRAMEBUFFER, fb[0])
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rb[0])
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rb[1])
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "target framebuffer incomplete" }

        val outDir = File(dir, "out").apply { mkdirs() }
        val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        val yaw0 = pf("yaw0", 0f)
        for (f in 0 until frames) {
            if (only != null && f !in only) continue
            val yaw = yaw0 + f * 360f / frames
            val vp = viewProj(proj, dist, elev, targetY)
            val model = FloatArray(16).also { Mx.setRotateM(it, 0, yaw, 0f, 1f, 0f) }
            val camPos = cameraPos(dist, elev, targetY)
            glBindFramebuffer(GL_FRAMEBUFFER, fb[0])
            glViewport(0, 0, w, h)
            glClearColor(0f, 0f, 0f, 0f)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            // floor shadow first (translucent black, premultiplied), then the opaque car
            glDisable(GL_DEPTH_TEST)
            glEnable(GL_BLEND)
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            drawFloor(floorProg, vp, model, floorShadow, floorExtent)
            glDisable(GL_BLEND)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_LEQUAL)
            drawCar(carProg, vp, model, camPos, texBase, texRM, atlas, dirs, dirVP, nDir, tiles, tileRes, xi)
            glFinish()
            pixels.position(0)
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
            val err = glGetError()
            check(err == GL_NO_ERROR) { "GL error $err on frame $f" }
            savePng(pixels, w, h, File(outDir, "frame_%03d.png".format(f)))
            log("frame ${f + 1}/$frames (yaw ${"%.1f".format(yaw)})")
        }
    }

    // ------------------------------------------------------------------------------------------- geometry
    private fun loadMesh(file: File) {
        val bytes = file.readBytes()
        val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(bytes).position(0)
        require(bytes[0] == 'A'.code.toByte() && bytes[1] == 'U'.code.toByte()) { "not a mesh.bin" }
        nVerts = bb.getInt(8)
        nIdx = bb.getInt(12)
        val posOff = 16
        val nrmOff = posOff + nVerts * 12
        val uvOff = nrmOff + nVerts * 12
        val idxOff = uvOff + nVerts * 8
        for (i in 0 until nVerts) for (a in 0 until 3) {
            val v = bb.getFloat(posOff + (i * 3 + a) * 4)
            bmin[a] = min(bmin[a], v); bmax[a] = max(bmax[a], v)
        }
        glGenBuffers(4, vbo, 0)
        upload(GL_ARRAY_BUFFER, vbo[0], bb, posOff, nVerts * 12)
        upload(GL_ARRAY_BUFFER, vbo[1], bb, nrmOff, nVerts * 12)
        upload(GL_ARRAY_BUFFER, vbo[2], bb, uvOff, nVerts * 8)
        upload(GL_ELEMENT_ARRAY_BUFFER, vbo[3], bb, idxOff, nIdx * 4)
        log("mesh: $nVerts vertices, ${nIdx / 3} triangles, bounds ${bmin.joinToString { "%.1f".format(it) }} .. ${bmax.joinToString { "%.1f".format(it) }}")
    }

    private fun upload(target: Int, id: Int, bb: ByteBuffer, off: Int, size: Int) {
        glBindBuffer(target, id)
        bb.position(off)
        glBufferData(target, size, bb, GL_STATIC_DRAW)
        glBindBuffer(target, 0)
    }

    private fun bindMesh(prog: Int, withAttribs: Boolean) {
        val aPos = glGetAttribLocation(prog, "aPos")
        glBindBuffer(GL_ARRAY_BUFFER, vbo[0]); glEnableVertexAttribArray(aPos); glVertexAttribPointer(aPos, 3, GL_FLOAT, false, 0, 0)
        if (withAttribs) {
            val aNrm = glGetAttribLocation(prog, "aNrm")
            glBindBuffer(GL_ARRAY_BUFFER, vbo[1]); glEnableVertexAttribArray(aNrm); glVertexAttribPointer(aNrm, 3, GL_FLOAT, false, 0, 0)
            val aUV = glGetAttribLocation(prog, "aUV")
            glBindBuffer(GL_ARRAY_BUFFER, vbo[2]); glEnableVertexAttribArray(aUV); glVertexAttribPointer(aUV, 2, GL_FLOAT, false, 0, 0)
        }
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, vbo[3])
    }

    private fun loadTexture(file: File): Int {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: error("cannot decode $file")
        val t = IntArray(1)
        glGenTextures(1, t, 0)
        glBindTexture(GL_TEXTURE_2D, t[0])
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        glGenerateMipmap(GL_TEXTURE_2D)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        log("texture ${file.name}: ${bmp.width}x${bmp.height}")
        bmp.recycle()
        return t[0]
    }

    // ------------------------------------------------------------------------------------------- occlusion atlas
    private fun bakeDepthAtlas(dirVP: FloatArray, n: Int, tiles: Int, tileRes: Int, size: Int): Int {
        val prog = program(VS_DEPTH, FS_DEPTH)
        val tex = IntArray(1); val fb = IntArray(1); val rb = IntArray(1)
        glGenTextures(1, tex, 0)
        glBindTexture(GL_TEXTURE_2D, tex[0])
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glGenFramebuffers(1, fb, 0); glGenRenderbuffers(1, rb, 0)
        glBindRenderbuffer(GL_RENDERBUFFER, rb[0]); glRenderbufferStorage(GL_RENDERBUFFER, 0x81A6, size, size)
        glBindFramebuffer(GL_FRAMEBUFFER, fb[0])
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[0], 0)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rb[0])
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "atlas framebuffer incomplete" }
        glUseProgram(prog)
        bindMesh(prog, false)
        val uVP = glGetUniformLocation(prog, "uVP")
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LESS)
        glDisable(GL_CULL_FACE)
        glEnable(GL_SCISSOR_TEST)
        for (k in 0 until n) {
            val tx = (k % tiles) * tileRes
            val ty = (k / tiles) * tileRes
            glViewport(tx, ty, tileRes, tileRes)
            glScissor(tx, ty, tileRes, tileRes)
            glClearColor(1f, 1f, 1f, 1f)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            glUniformMatrix4fv(uVP, 1, false, dirVP, k * 16)
            glDrawElements(GL_TRIANGLES, nIdx, GL_UNSIGNED_INT, 0)
        }
        glFinish()
        glDisable(GL_SCISSOR_TEST)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        log("occlusion atlas done")
        return tex[0]
    }

    /**
     * Floor shadow in car space: the car's silhouette seen from straight above, blurred wide (soft ambient shadow of the
     * body) plus the silhouette of its lowest parts blurred narrow (the dark contact under the tyres and bumpers).
     */
    private fun bakeFloorShadow(e: Float): Int {
        val size = 1024
        val prog = program(VS_TOP, FS_TOP)
        val tex = IntArray(1); val fb = IntArray(1)
        glGenTextures(1, tex, 0)
        glBindTexture(GL_TEXTURE_2D, tex[0])
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glGenFramebuffers(1, fb, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, fb[0])
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[0], 0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "floor framebuffer incomplete" }
        // car space -> clip: x -> x/e, z -> z/e (so texture coordinates are (x, z) / e * 0.5 + 0.5), depth unused
        val m = floatArrayOf(1f / e, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f / e, 0f, 0f, 0f, 0f, 0f, 1f)
        glUseProgram(prog)
        bindMesh(prog, false)
        glUniformMatrix4fv(glGetUniformLocation(prog, "uM"), 1, false, m, 0)
        val uMaxY = glGetUniformLocation(prog, "uMaxY")
        glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_CULL_FACE)
        glViewport(0, 0, size, size)
        val buf = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        fun cover(maxY: Float): FloatArray {
            glClearColor(0f, 0f, 0f, 0f)
            glClear(GL_COLOR_BUFFER_BIT)
            glUniform1f(uMaxY, maxY)
            glDrawElements(GL_TRIANGLES, nIdx, GL_UNSIGNED_INT, 0)
            glFinish()
            buf.position(0)
            glReadPixels(0, 0, size, size, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            return FloatArray(size * size) { (buf.get(it * 4).toInt() and 0xFF) / 255f }
        }
        val height = bmax[1] - bmin[1]
        val pxPerUnit = size / (2 * e)
        val full = blur(cover(Float.MAX_VALUE), size, (pf("shadowSoft", 0.085f) * (bmax[0] - bmin[0]) * pxPerUnit).toInt())
        val low = blur(cover(bmin[1] + height * pf("contactHeight", 0.12f)), size, (pf("shadowHard", 0.012f) * (bmax[0] - bmin[0]) * pxPerUnit).toInt())
        val a = pf("softWeight", 0.62f); val b = pf("hardWeight", 0.75f)
        val out = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until size * size) {
            val v = (a * full[i] + b * low[i]).coerceIn(0f, 1f)
            val by = (v * 255 + 0.5f).toInt().toByte()
            out.put(by); out.put(by); out.put(by); out.put(by)
        }
        out.position(0)
        glBindTexture(GL_TEXTURE_2D, tex[0])
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, out)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        log("floor shadow done")
        return tex[0]
    }

    /** Three box blurs ~ a Gaussian of the given radius (in pixels). */
    private fun blur(src: FloatArray, n: Int, r: Int): FloatArray {
        if (r < 1) return src
        var a = src
        var b = FloatArray(n * n)
        repeat(3) {
            for (y in 0 until n) {                       // horizontal
                var acc = 0f
                val row = y * n
                for (x in -r..r) acc += a[row + x.coerceIn(0, n - 1)]
                for (x in 0 until n) {
                    b[row + x] = acc / (2 * r + 1)
                    acc += a[row + (x + r + 1).coerceAtMost(n - 1)] - a[row + (x - r).coerceAtLeast(0)]
                }
            }
            for (x in 0 until n) {                       // vertical
                var acc = 0f
                for (y in -r..r) acc += b[y.coerceIn(0, n - 1) * n + x]
                for (y in 0 until n) {
                    a[y * n + x] = acc / (2 * r + 1)
                    acc += b[(y + r + 1).coerceAtMost(n - 1) * n + x] - b[(y - r).coerceAtLeast(0) * n + x]
                }
            }
        }
        return a
    }

    // ------------------------------------------------------------------------------------------- passes
    private fun drawCar(prog: Int, vp: FloatArray, model: FloatArray, cam: FloatArray, base: Int, rm: Int, atlas: Int,
                        dirs: FloatArray, dirVP: FloatArray, n: Int, tiles: Int, tileRes: Int, xi: FloatArray) {
        glUseProgram(prog)
        bindMesh(prog, true)
        setCommon(prog, vp, model, atlas, dirs, dirVP, n, tiles, tileRes)
        glUniform3f(glGetUniformLocation(prog, "uCam"), cam[0], cam[1], cam[2])
        glUniform2fv(glGetUniformLocation(prog, "uXi"), xi.size / 2, xi, 0)
        glUniform1f(glGetUniformLocation(prog, "uExposure"), pf("exposure", 1.0f))
        glUniform1f(glGetUniformLocation(prog, "uClearcoat"), pf("clearcoat", 1.0f))
        glUniform1f(glGetUniformLocation(prog, "uEnvScale"), pf("env", 1.0f))
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, base)
        glUniform1i(glGetUniformLocation(prog, "uBase"), 1)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, rm)
        glUniform1i(glGetUniformLocation(prog, "uRM"), 2)
        glDisable(GL_CULL_FACE)
        glDrawElements(GL_TRIANGLES, nIdx, GL_UNSIGNED_INT, 0)
    }

    private fun drawFloor(prog: Int, vp: FloatArray, model: FloatArray, shadowTex: Int, e: Float) {
        glUseProgram(prog)
        glUniformMatrix4fv(glGetUniformLocation(prog, "uVP"), 1, false, vp, 0)
        glUniformMatrix4fv(glGetUniformLocation(prog, "uModel"), 1, false, model, 0)
        glUniform1f(glGetUniformLocation(prog, "uStrength"), pf("shadow", 0.85f))
        glUniform1f(glGetUniformLocation(prog, "uExtent"), e)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, shadowTex)
        glUniform1i(glGetUniformLocation(prog, "uShadow"), 0)
        val y = bmin[1]
        val quad = floatArrayOf(-e, y, -e, e, y, -e, -e, y, e, e, y, e)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        buf.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        val aPos = glGetAttribLocation(prog, "aPos")
        glEnableVertexAttribArray(aPos)
        glVertexAttribPointer(aPos, 3, GL_FLOAT, false, 0, buf)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(aPos)
    }

    private fun setCommon(prog: Int, vp: FloatArray, model: FloatArray, atlas: Int, dirs: FloatArray, dirVP: FloatArray,
                          n: Int, tiles: Int, tileRes: Int) {
        glUniformMatrix4fv(glGetUniformLocation(prog, "uVP"), 1, false, vp, 0)
        glUniformMatrix4fv(glGetUniformLocation(prog, "uModel"), 1, false, model, 0)
        val rot = floatArrayOf(model[0], model[1], model[2], model[4], model[5], model[6], model[8], model[9], model[10])
        glUniformMatrix3fv(glGetUniformLocation(prog, "uRot"), 1, false, rot, 0)
        glUniform3fv(glGetUniformLocation(prog, "uDir"), n, dirs, 0)
        glUniformMatrix4fv(glGetUniformLocation(prog, "uDirVP"), n, false, dirVP, 0)
        glUniform1f(glGetUniformLocation(prog, "uTiles"), tiles.toFloat())
        glUniform1f(glGetUniformLocation(prog, "uTexel"), 1f / (tiles * tileRes))
        glUniform1f(glGetUniformLocation(prog, "uBias"), pf("bias", 0.004f))
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, atlas)
        glUniform1i(glGetUniformLocation(prog, "uAtlas"), 0)
    }

    // ------------------------------------------------------------------------------------------- helpers
    private fun cameraPos(dist: Float, elev: Double, targetY: Float) =
        floatArrayOf(0f, targetY + (dist * sin(elev)).toFloat(), (dist * cos(elev)).toFloat())

    /** Projection x view (the car's own rotation is applied separately through uModel). */
    private fun viewProj(proj: FloatArray, dist: Float, elev: Double, targetY: Float): FloatArray {
        val c = cameraPos(dist, elev, targetY)
        val view = FloatArray(16)
        Mx.setLookAtM(view, 0, c[0], c[1], c[2], 0f, targetY, 0f, 0f, 1f, 0f)
        val pv = FloatArray(16)
        Mx.multiplyMM(pv, 0, proj, 0, view, 0)
        return pv
    }

    /** Projection x view x model: used to check that the rotated car fits the frame. */
    private fun mvp(proj: FloatArray, dist: Float, elev: Double, targetY: Float, yaw: Float): FloatArray {
        val model = FloatArray(16).also { Mx.setRotateM(it, 0, yaw, 0f, 1f, 0f) }
        val out = FloatArray(16)
        Mx.multiplyMM(out, 0, viewProj(proj, dist, elev, targetY), 0, model, 0)
        return out
    }

    private fun savePng(px: ByteBuffer, w: Int, h: Int, out: File) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        px.position(0)
        bmp.copyPixelsFromBuffer(px)
        val flipped = Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { preScale(1f, -1f) }, false)
        FileOutputStream(out).use { flipped.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        flipped.recycle()
    }

    private fun radicalInverse(i: Int): Float {
        var b = i.toLong() and 0xFFFFFFFFL
        b = ((b shl 16) or (b ushr 16)) and 0xFFFFFFFFL
        b = (((b and 0x55555555L) shl 1) or ((b and 0xAAAAAAAAL) ushr 1)) and 0xFFFFFFFFL
        b = (((b and 0x33333333L) shl 2) or ((b and 0xCCCCCCCCL) ushr 2)) and 0xFFFFFFFFL
        b = (((b and 0x0F0F0F0FL) shl 4) or ((b and 0xF0F0F0F0L) ushr 4)) and 0xFFFFFFFFL
        b = (((b and 0x00FF00FFL) shl 8) or ((b and 0xFF00FF00L) ushr 8)) and 0xFFFFFFFFL
        return (b.toDouble() / 4294967296.0).toFloat()
    }

    private fun sq(v: Float) = v * v

    private fun program(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, src)
            glCompileShader(s)
            val ok = IntArray(1).also { glGetShaderiv(s, GL_COMPILE_STATUS, it, 0) }[0]
            check(ok != 0) { "shader compile failed: " + glGetShaderInfoLog(s) }
            return s
        }
        val p = glCreateProgram()
        glAttachShader(p, compile(GL_VERTEX_SHADER, vs))
        glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        val ok = IntArray(1).also { glGetProgramiv(p, GL_LINK_STATUS, it, 0) }[0]
        check(ok != 0) { "program link failed: " + glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        private const val VS_DEPTH = """
attribute vec3 aPos;
uniform mat4 uVP;
varying float vZ;
void main() { gl_Position = uVP * vec4(aPos, 1.0); vZ = gl_Position.z * 0.5 + 0.5; }
"""
        private const val FS_DEPTH = """
precision highp float;
varying float vZ;
void main() {
  vec4 e = fract(vZ * vec4(1.0, 255.0, 65025.0, 16581375.0));
  e -= e.yzww * vec4(1.0 / 255.0, 1.0 / 255.0, 1.0 / 255.0, 0.0);
  gl_FragColor = e;
}
"""
        private const val VS_CAR = """
attribute vec3 aPos; attribute vec3 aNrm; attribute vec2 aUV;
uniform mat4 uVP; uniform mat4 uModel; uniform mat3 uRot;
varying vec3 vW; varying vec3 vN; varying vec3 vLP; varying vec3 vLN; varying vec2 vUV;
void main() {
  vec4 w = uModel * vec4(aPos, 1.0);
  vW = w.xyz; vN = uRot * aNrm; vLP = aPos; vLN = aNrm; vUV = aUV;
  gl_Position = uVP * w;
}
"""
        private const val VS_FLOOR = """
attribute vec3 aPos;
uniform mat4 uVP; uniform mat4 uModel;
varying vec3 vLP;
void main() { vLP = aPos; gl_Position = uVP * (uModel * vec4(aPos, 1.0)); }
"""
        private const val FS_FLOOR = """
precision highp float;
varying vec3 vLP;
uniform sampler2D uShadow; uniform float uStrength; uniform float uExtent;
void main() {
  vec2 uv = vLP.xz / uExtent * 0.5 + 0.5;
  float s = texture2D(uShadow, uv).r * uStrength;
  float r = length(vLP.xz) / uExtent;
  s *= 1.0 - smoothstep(0.8, 1.0, r);
  gl_FragColor = vec4(0.0, 0.0, 0.0, s);
}
"""
        private const val VS_TOP = """
attribute vec3 aPos;
uniform mat4 uM;
varying float vY;
void main() { vY = aPos.y; gl_Position = uM * vec4(aPos, 1.0); }
"""
        private const val FS_TOP = """
precision highp float;
varying float vY;
uniform float uMaxY;
void main() { if (vY > uMaxY) discard; gl_FragColor = vec4(1.0); }
"""

        /** The studio: a dark room with an overhead softbox, a key light from the front left, two side strips and a rim. */
        private const val ENV = """
float softRect(vec2 p, vec2 lo, vec2 hi, float s) {
  vec2 a = smoothstep(lo - s, lo + s, p) * (1.0 - smoothstep(hi - s, hi + s, p));
  return a.x * a.y;
}
uniform float uEnvScale;
vec3 env(vec3 d) {
  float y = d.y;
  // dark studio: deep floor, walls slightly lighter towards the ceiling
  vec3 c = mix(vec3(0.006, 0.0065, 0.0075), vec3(0.05, 0.053, 0.06), smoothstep(-0.05, 0.85, y));
  c += vec3(0.018) * smoothstep(0.0, -0.12, y) * (1.0 - smoothstep(-0.12, -0.5, y));        // faint floor bounce
  // large overhead softbox (roof and bonnet highlights)
  if (y > 0.05) { vec2 p = d.xz / y; c += vec3(3.4, 3.35, 3.25) * softRect(p, vec2(-1.2, -0.5), vec2(1.2, 0.5), 0.22); }
  // key light, front left and high
  if (d.z > 0.05) { vec2 p = d.xy / d.z; c += vec3(4.2, 4.15, 4.05) * softRect(p, vec2(-1.7, 0.22), vec2(-0.4, 1.0), 0.22); }
  // long thin strip behind the camera, just above the horizon: the bright line along the doors
  if (d.z > 0.05) { vec2 p = d.xy / d.z; c += vec3(2.6, 2.6, 2.65) * softRect(p, vec2(-2.6, 0.07), vec2(2.6, 0.13), 0.025); }
  // side strips (flanks seen at an angle) and a rim behind the car
  if (abs(d.x) > 0.05) { vec2 p = vec2(d.z, d.y) / abs(d.x); c += vec3(1.5) * softRect(p, vec2(-1.8, 0.10), vec2(1.8, 0.24), 0.05); }
  if (d.z < -0.05) { vec2 p = d.xy / -d.z; c += vec3(2.8) * softRect(p, vec2(-1.8, 0.03), vec2(1.8, 0.20), 0.05); }
  return c * uEnvScale;
}
"""

        /** Visibility of a point towards occlusion direction k (2x2 PCF), inlined in loops (GLSL ES 1.0 indexing rules). */
        private const val VIS = """
float unpackDepth(vec4 e) { return dot(e, vec4(1.0, 1.0 / 255.0, 1.0 / 65025.0, 1.0 / 16581375.0)); }
"""

        private fun visLoop(n: Int, body: String) = """
  for (int k = 0; k < $n; k++) {
    vec3 d = uDir[k];
    vec4 c = uDirVP[k] * vec4(P, 1.0);
    vec2 uv = clamp(c.xy * 0.5 + 0.5, 0.001, 0.999);
    float z = c.z * 0.5 + 0.5 - uBias;
    float tx = mod(float(k), uTiles);
    float ty = floor(float(k) / uTiles);
    vec2 a = (vec2(tx, ty) + uv) / uTiles;
    float v = step(z, unpackDepth(texture2D(uAtlas, a + vec2(-0.5, -0.5) * uTexel)))
            + step(z, unpackDepth(texture2D(uAtlas, a + vec2(0.5, -0.5) * uTexel)))
            + step(z, unpackDepth(texture2D(uAtlas, a + vec2(-0.5, 0.5) * uTexel)))
            + step(z, unpackDepth(texture2D(uAtlas, a + vec2(0.5, 0.5) * uTexel)));
    v *= 0.25;
$body
  }
"""

        private fun fsCar(n: Int, ns: Int) = """
precision highp float;
varying vec3 vW; varying vec3 vN; varying vec3 vLP; varying vec3 vLN; varying vec2 vUV;
uniform sampler2D uAtlas; uniform sampler2D uBase; uniform sampler2D uRM;
uniform vec3 uDir[$n]; uniform mat4 uDirVP[$n]; uniform mat3 uRot;
uniform float uTiles; uniform float uTexel; uniform float uBias;
uniform vec3 uCam; uniform vec2 uXi[$ns]; uniform float uExposure; uniform float uClearcoat;
$ENV
$VIS
vec3 aces(vec3 x) { return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }
vec3 ggx(vec2 xi, vec3 N, float a) {
  float phi = 6.2831853 * xi.x;
  float ct = sqrt((1.0 - xi.y) / (1.0 + (a * a - 1.0) * xi.y));
  float st = sqrt(1.0 - ct * ct);
  vec3 up = abs(N.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
  vec3 T = normalize(cross(up, N)); vec3 B = cross(N, T);
  return normalize(T * (cos(phi) * st) + B * (sin(phi) * st) + N * ct);
}
void main() {
  vec3 N = normalize(vN);
  vec3 V = normalize(uCam - vW);
  if (dot(N, V) < 0.0) N = -N;                       // two-sided (thin parts, mirrored normals)
  vec3 LN = normalize(vLN);
  if (dot(uRot * LN, V) < 0.0) LN = -LN;
  vec3 base = pow(texture2D(uBase, vUV).rgb, vec3(2.2));
  vec2 rmv = texture2D(uRM, vUV).rg;
  float rough = clamp(rmv.r, 0.05, 1.0);
  float metal = rmv.g;
  float NoV = max(dot(N, V), 1e-3);
  vec3 F0 = mix(vec3(0.04), base, metal);
  vec3 R = reflect(-V, N);
  vec3 Rl = R * uRot;                                  // to car space (transpose of a rotation = inverse)
  vec3 P = vLP + LN * 0.6;                             // offset along the normal against self-shadowing acne
  vec3 diff = vec3(0.0); float wsum = 0.0; float sv = 0.0; float sw = 0.0;
  float lobe = mix(48.0, 3.0, rough);
${visLoop(n, """
    float cn = max(dot(LN, d), 0.0);
    diff += v * env(uRot * d) * cn; wsum += cn;
    float s = pow(max(dot(Rl, d), 0.0), lobe);
    sv += v * s; sw += s;
""")}
  diff /= max(wsum, 1e-4);
  float specOcc = sw > 1e-5 ? sv / sw : 1.0;
  float a = rough * rough;
  float k = a / 2.0;
  vec3 spec = vec3(0.0);
  for (int i = 0; i < $ns; i++) {
    vec3 H = ggx(uXi[i], N, a);
    vec3 L = 2.0 * dot(V, H) * H - V;
    float NoL = dot(N, L);
    if (NoL > 0.0) {
      float NoH = max(dot(N, H), 1e-4); float VoH = max(dot(V, H), 1e-4);
      float G = (NoL / (NoL * (1.0 - k) + k)) * (NoV / (NoV * (1.0 - k) + k));
      float Fc = pow(1.0 - VoH, 5.0);
      spec += env(L) * ((1.0 - Fc) * F0 + Fc) * (G * VoH / (NoH * NoV));
    }
  }
  spec /= float($ns);
  float paint = clamp(dot(base, vec3(0.3333)) * 1.6, 0.0, 1.0) * (1.0 - metal) * uClearcoat;
  float Fcc = 0.04 + 0.96 * pow(1.0 - NoV, 5.0);
  vec3 cc = env(R) * Fcc * paint;
  float kd = 1.0 - metal;
  vec3 col = base * kd * diff + (spec + cc) * specOcc;
  col = aces(col * uExposure);
  gl_FragColor = vec4(pow(col, vec3(1.0 / 2.2)), 1.0);
}
"""
    }
}
