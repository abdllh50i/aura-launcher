package com.abdllh.aura.debug

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Debug-only tool: renders the high-resolution car model (tools/car3d) into the turntable frames the app ships.
 *   adb push mesh.bin basecolor.jpg rm.png [params.txt] /sdcard/Android/data/com.abdllh.aura/files/bake/
 *   adb shell am start -n com.abdllh.aura/.debug.CarBakerActivity
 *   ... wait for bake/done.txt, then pull bake/out/
 * The work runs on the GL thread into off-screen framebuffers; the visible view is only a log.
 */
class CarBakerActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(getExternalFilesDir(null), "bake")
        File(dir, "done.txt").delete()
        File(dir, "error.txt").delete()
        val root = FrameLayout(this)
        status = TextView(this).apply { textSize = 18f; setPadding(40, 40, 40, 40) }
        root.addView(status)
        val gl = GLSurfaceView(this)
        gl.setEGLContextClientVersion(2)
        gl.setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        gl.preserveEGLContextOnPause = true
        gl.setRenderer(object : GLSurfaceView.Renderer {
            private var started = false
            override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {}
            override fun onSurfaceChanged(g: GL10?, w: Int, h: Int) {}
            override fun onDrawFrame(g: GL10?) {
                if (started) return
                started = true
                try {
                    CarBaker(dir) { msg -> log(msg) }.run()
                    File(dir, "done.txt").writeText("ok")
                    log("DONE")
                } catch (t: Throwable) {
                    val sw = StringWriter()
                    t.printStackTrace(PrintWriter(sw))
                    File(dir, "error.txt").writeText(sw.toString())
                    log("FAILED: $t")
                }
            }
        })
        gl.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        root.addView(gl, FrameLayout.LayoutParams(64, 64))
        setContentView(root)
    }

    private fun log(msg: String) {
        Log.i("CarBaker", msg)
        runOnUiThread { status.text = msg }
    }
}
