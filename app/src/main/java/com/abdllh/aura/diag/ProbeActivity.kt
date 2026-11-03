package com.abdllh.aura.diag

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.nav.offline.TileServer
import com.abdllh.aura.system.Animations
import com.abdllh.aura.system.LocalAdb
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.Dp
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The tests that load GL or native code, in a process of their own (":diag", where AuraApp does nothing): if the
 * driver or the map engine dies, this process goes and Home does not notice. Runs what files/diag/plan.json lists, each
 * test with a marker file on the disk while it runs, each result a file of its own; [ScreenCheck] in the main process
 * watches from outside and starts this again for the rest if it dies. Started once per group of tests, so the memory
 * read after a map test is that test's alone.
 *
 * The tests are plain sequential code on a thread of their own, reaching the main thread through [ui]. The window shows
 * one line (changed only between tests: nothing here should add frames to what is measured) and a Cancel.
 */
class ProbeActivity : Activity() {
    private class Stopped(val why: String) : Exception(why)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var strip: View
    private lateinit var line: AText
    private lateinit var log: FrameLog
    private var rig: MapRig? = null
    /** Why the tests must end now (the owner cancelled, something came over the screen); null while they may go on. */
    @Volatile private var stop: String? = null
    private var budgetMs = 16.67

    override fun attachBaseContext(base: Context) {
        // (AuraApp returned at once in this process: only what these screens use is brought up, here)
        val app = base.applicationContext ?: base
        Dp.init(app)
        Prefs.init(app)
        Fonts.init(app)
        if (BuildConfig.DEBUG) {
            // emulator tests: the root shell through scripts/fake_adbd.py, as in AuraApp
            SystemProps.get("debug.aura.adb").split(":").takeIf { it.size == 2 }?.let { (h, p) ->
                p.toIntOrNull()?.let { LocalAdb.host = h; LocalAdb.port = it }
            }
        }
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.refreshLocale(this)
        Theme.refresh()
        Animations.ensureAnimators() // (the firmware's animator scale is 0: the motion tests would end before they start)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        immersive()
        root = FrameLayout(this)
        line = label(Type.FOOTNOTE, Palette.text)
        val cancel = ABtn(this).apply {
            kind = ABtn.Kind.TONAL
            setText(R.string.diag_cancel)
            setOnClickListener { cancelNow() }
        }
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(Space.L.dp, Space.XS.dp, Space.XS.dp, Space.XS.dp)
            background = Shapes.rect(Palette.withAlpha(Palette.bg, 0.9f), Radius.XL)
            addView(line, lp(0, WRAP, 1f))
            addView(cancel, lp(WRAP, WRAP).apply { marginStart = Space.M.dp })
        }
        root.addView(strip, flp(500.dp, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(Space.L.dp, 0, 0, Space.L.dp) })
        setContentView(root)
        budgetMs = 1000.0 / (windowManager.defaultDisplay.refreshRate.takeIf { it > 1f } ?: 60f)
        log = FrameLog(budgetMs)
        val frames = HandlerThread("aura-diag-frames").apply { start() }
        window.addOnFrameMetricsAvailableListener(log, Handler(frames.looper))
        // (once the window is there: the first test may want its size)
        root.post { Thread({ tests() }, "aura-diag-tests").apply { isDaemon = true; start() } }
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    private fun cancelNow() {
        if (stop != null) return
        try { DiagFiles.cancel(this).createNewFile() } catch (t: Throwable) { Log.w(TAG, "cancel: $t") }
        stop = "cancel"
        finish()
        overridePendingTransition(0, 0)
    }

    @Deprecated("the same as the button")
    override fun onBackPressed() = cancelNow()

    override fun onStop() {
        // something came over the screen (the reversing camera, another app, the Home key): the numbers from now on
        // would be for a screen nobody draws
        if (!isFinishing && stop == null) stop = "left"
        super.onStop()
    }

    override fun onDestroy() {
        if (stop == null && !allDone) stop = "left"
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------ the tests in turn
    @Volatile private var allDone = false

    private fun tests() {
        val c = applicationContext
        val done = JSONObject()
        try {
            val plan = DiagFiles.read(DiagFiles.plan(c)) ?: throw IllegalStateException("no plan")
            val names = plan.optJSONArray("tests") ?: JSONArray()
            for (i in 0 until names.length()) {
                val name = names.getString(i)
                if (stop != null) break
                val t = Checks.of(name) ?: continue
                val leftSec = (i until names.length()).sumOf { Checks.of(names.getString(it))?.seconds ?: 0 } + plan.optInt("afterSec")
                val words = getString(R.string.diag_progress, getString(t.label), Checks.time(leftSec))
                ui { line.text = words }
                // on the disk before it starts: a crash leaves no chance to write anything
                Marker.set(c, name, t.budgetMs)
                debugTrouble(name)
                val r = try {
                    run(name).apply { if (!has("status")) put("status", "ok") }
                } catch (s: Stopped) {
                    JSONObject().put("status", "interrupted").put("why", s.why)
                } catch (e: Throwable) {
                    Log.w(TAG, "$name: $e")
                    JSONObject().put("status", "failed").put("error", e.toString())
                }
                DiagFiles.write(DiagFiles.result(c, name), r.put("test", name).put("uptimeMs", SystemClock.elapsedRealtime()))
                Marker.clear(c)
            }
            stop?.let { done.put("interrupted", it) }
        } catch (t: Throwable) {
            Log.w(TAG, "tests: $t")
            done.put("error", t.toString())
            Marker.clear(c)
        }
        allDone = true
        try { DiagFiles.write(DiagFiles.planDone(c), done) } catch (t: Throwable) { Log.w(TAG, "done: $t") }
        main.post {
            if (!isFinishing) { finish(); overridePendingTransition(0, 0) }
            // (nothing of this process is wanted any more; ScreenCheck ends it too, should this not come)
            main.postDelayed({ Process.killProcess(Process.myPid()) }, 500L)
        }
    }

    /** Debug builds, for proving the crash and hang handling on the emulator: setprop debug.aura.diagtrouble <test>[:hang]. */
    private fun debugTrouble(name: String) {
        if (!BuildConfig.DEBUG) return
        val p = SystemProps.get("debug.aura.diagtrouble").split(":")
        if (p.firstOrNull() != name) return
        if (p.getOrNull(1) == "hang") { Thread.sleep(3_600_000L); return }
        Process.sendSignal(Process.myPid(), 11) // SIGSEGV: as a driver would die, with no Java left to catch it
        Thread.sleep(5000L)
    }

    private fun run(name: String): JSONObject = when (name) {
        "gl.es2" -> GlInfo.info(2)
        "gl.es3" -> GlInfo.info(3)
        "gl.formats" -> GlInfo.formats()
        "gl.fill" -> fill()
        "map.surface" -> mapTest(texture = false, keep = true)
        "map.texture" -> mapTest(texture = true, keep = false)
        else -> motion(name)
    }

    // ------------------------------------------------------------------------------------------ helpers of the test thread
    private fun checkStop() { stop?.let { throw Stopped(it) } }

    private fun pause(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (true) {
            checkStop()
            val left = until - SystemClock.elapsedRealtime()
            if (left <= 0) return
            Thread.sleep(left.coerceAtMost(50L))
        }
    }

    /** True once [what] holds, false when [ms] went by first. */
    private fun waitFor(ms: Long, what: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < until) {
            checkStop()
            if (what()) return true
            Thread.sleep(50L)
        }
        return what()
    }

    /** [f] on the main thread, and its answer. A main thread that does not answer fails the test; it does not hold it. */
    private fun <T> ui(timeoutMs: Long = 15_000L, f: () -> T): T {
        val latch = CountDownLatch(1)
        val r = java.util.concurrent.atomic.AtomicReference<Result<T>>()
        main.post {
            r.set(try { Result.success(f()) } catch (t: Throwable) { Result.failure(t) })
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IllegalStateException("the main thread did not answer in $timeoutMs ms")
        return r.get().getOrThrow()
    }

    // ------------------------------------------------------------------------------------------ C: fill rate
    private fun fill(): JSONObject {
        val finished = CountDownLatch(1)
        var out: JSONObject? = null
        val view = ui {
            FillView(this, windowManager.defaultDisplay.refreshRate) { out = it; finished.countDown() }.also { root.addView(it, 0, flp(MATCH, MATCH)) }
        }
        try {
            while (!finished.await(100, TimeUnit.MILLISECONDS)) checkStop()
        } finally {
            try { ui { root.removeView(view) } } catch (_: Throwable) { }
        }
        return out ?: JSONObject().put("error", "no answer")
    }

    // ------------------------------------------------------------------------------------------ E: the map
    private fun bringUpMap(texture: Boolean, o: JSONObject): MapRig {
        // the main process serves the tiles (127.0.0.1): offline ones when it has them, else from the internet
        val local = TileServer.attach()
        val offline = Prefs.raw.getString("offlineMapState", "none") == "ready"
        o.put("tiles", if (!local) "online, directly" else if (offline) "offline map, online for the rest" else "online, through the unit's tile server")
        val t0 = SystemClock.elapsedRealtime()
        val r = ui(30_000L) { MapRig(this, root, texture).also { it.start(); rig = it } }
        o.put("buildMs", SystemClock.elapsedRealtime() - t0)
        if (!waitFor(25_000L) { r.styleAt != 0L }) throw IllegalStateException("the map's style did not come in 25 s")
        o.put("styleMs", r.styleAt - t0)
        pause(1500L) // (the camera starts where the car was left, and flies to the road)
        ui { r.frames.watchFully() }
        // the road's tiles; the numbers are of a map that has them, or say that it had none
        val got = waitFor(12_000L) { r.frames.fullyAt != 0L }
        o.put("tilesCame", got)
        if (got) o.put("tilesMs", r.frames.fullyAt - r.cameraAt)
        return r
    }

    private fun measure(r: MapRig, ms: Long): JSONObject {
        val o = JSONObject()
        val cpu = CpuSnap.take()
        val t0 = SystemClock.elapsedRealtime()
        log.begin()
        r.frames.begin()
        pause(ms)
        val seconds = (SystemClock.elapsedRealtime() - t0) / 1000.0
        o.put("map", r.frames.end(seconds))
        o.put("hwui", log.end())
        cpu.since(o)
        return o
    }

    private fun mapTest(texture: Boolean, keep: Boolean): JSONObject {
        val o = JSONObject().put("mode", if (texture) "texture" else "surface")
        val r = bringUpMap(texture, o)
        o.put("width", root.width).put("height", root.height).put("dark", Palette.dark)
        // what a trip asks for (it falls to 30 by itself when it cannot keep up: see the seconds), then the home map's 30
        ui { r.map.setFps(60) }
        o.put("cap60", measure(r, 12_000L))
        // (between the two, so a dump that stalls the compositor is in neither; the car goes on driving)
        if (!texture) o.put("surfaceFlinger", surfaceFlinger())
        ui { r.map.setFps(30) }
        pause(600L)
        o.put("cap30", measure(r, 5_000L))
        o.put("features", ui { r.drawnFeatures() })
        val mem = processMemory()
        mem.put("dumpsys", Num.cut(Sh.text("dumpsys meminfo ${Process.myPid()}", 8000), 9000))
        mem.put("mali", maliMemory())
        o.put("mem", mem)
        if (!keep) { ui { r.stop(); rig = null } }
        return o
    }

    // ------------------------------------------------------------------------------------------ F: motion over the map
    private fun motion(name: String): JSONObject {
        val o = JSONObject()
        // (started again after a crash in an earlier test of the group: its own map, then the same)
        if (rig == null) { bringUpMap(texture = false, o = JSONObject()); pause(1000L) }
        val v = ui { motionView(name).also { root.addView(it, root.indexOfChild(strip)) } }
        pause(300L)
        val cpu = CpuSnap.take()
        log.begin()
        val until = SystemClock.elapsedRealtime() + MOTION_MS
        ui { play(name, v, until, 0) }
        pause(MOTION_MS)
        o.put("hwui", log.end())
        cpu.since(o)
        ui { v.animate().cancel(); root.removeView(v) }
        o.put("budgetMs", Num.of(budgetMs, 2))
        return o
    }

    /** What moves in test [name], added above the map, invisible until it plays. */
    private fun motionView(name: String): View = when (name) {
        "ui.crossfade" -> object : View(this) {
            override fun hasOverlappingRendering() = false // (one flat fill: no layer asked for, none needed)
        }.apply {
            setBackgroundColor(Palette.bg)
            alpha = 0f
            layoutParams = flp(MATCH, MATCH)
        }
        "ui.bigtext" -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Space.XL.dp, Space.L.dp, Space.XL.dp, Space.L.dp)
            background = Shapes.rect(Palette.withAlpha(Palette.bg, 0.86f), Radius.XL)
            addView(AText(this@ProbeActivity).apply {
                textSize = Type.DISPLAY_L.sp
                setTextColor(Palette.text)
                gravity = Gravity.CENTER
                Fonts.arabic()?.let { typeface = it }
                text = BIG_TEXT[0]
            })
            alpha = 0f
            layoutParams = flp(620.dp, WRAP, Gravity.CENTER)
        }
        else -> panel(name)
    }

    /** The 440 x 600 panel of the motion tests: a title and rows, as a card of 2.0 would have, on the test's background. */
    private fun panel(name: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(Space.XL.dp, Space.XL.dp, Space.XL.dp, Space.XL.dp)
        background = when (name) {
            "ui.glass9" -> getDrawable(R.drawable.diag_glass)
            "ui.fill" -> Shapes.rect(Palette.withAlpha(Palette.bg, 0.72f), Radius.XL, Palette.stroke)
            else -> Shapes.rect(Palette.card, Radius.XL)
        }
        addView(label(Type.TITLE, Palette.text).apply { setText(R.string.diag_panel_title) })
        for (i in 0 until 6) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(iconView(R.drawable.ic_nav, 26, Palette.text2), lp(26.dp, 26.dp))
            row.addView(label(Type.BODY, Palette.text).apply {
                setText(if (i % 3 == 0) R.string.diag_panel_line1 else if (i % 3 == 1) R.string.diag_panel_line2 else R.string.diag_panel_line3)
            }, lp(0, WRAP, 1f).apply { marginStart = Space.L.dp })
            addView(row, lp(MATCH, WRAP).apply { topMargin = Space.L.dp })
        }
        alpha = 0f
        layoutParams = flp(440.dp, 600.dp.coerceAtMost(root.height), Gravity.TOP or Gravity.START)
    }

    /** In and out again until [until]: the app's own durations and curves (ui/Motion.kt). */
    private fun play(name: String, v: View, until: Long, round: Int) {
        if (SystemClock.elapsedRealtime() >= until || stop != null) { v.alpha = 0f; return }
        val slide = name != "ui.crossfade" && name != "ui.bigtext"
        val layer = name != "ui.slide_plain" && name != "ui.crossfade"
        val away = if (slide) 32.dp.toFloat() else 0f
        if (name == "ui.bigtext") {
            // a new sentence each round: letters the glyph atlas may not have yet, as a real screen brings
            ((v as LinearLayout).getChildAt(0) as AText).text = BIG_TEXT[round % BIG_TEXT.size]
        }
        v.translationX = away
        v.alpha = 0f
        val inn = v.animate().alpha(1f).translationX(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.EMPHASIZED)
        if (layer) inn.withLayer()
        inn.withEndAction {
            v.postDelayed({
                val out = v.animate().alpha(0f).translationX(away).setDuration(Motion.SHORT).setInterpolator(Motion.EXIT)
                if (layer) out.withLayer()
                out.withEndAction { v.postDelayed({ play(name, v, until, round + 1) }, 80L) }.start()
            }, 120L)
        }.start()
    }

    private companion object {
        const val TAG = "AuraDiag"
        const val MOTION_MS = 3000L
        val BIG_TEXT = arrayOf("عمري يسمعك", "الدمام ١٨ كم", "لفّ يمين بعد قليل", "الخبر الكورنيش", "طريق الملك فهد", "خلصت الأغنية")
    }
}
