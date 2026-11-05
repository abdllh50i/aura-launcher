package com.abdllh.aura.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.ViewTreeObserver
import android.view.Window
import com.abdllh.aura.chrome.Chrome
import com.abdllh.aura.home.HomeActivity
import com.abdllh.aura.home.Screens
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.ui.Asked
import java.util.Locale

/**
 * Debug builds: the chrome from outside (AuraChromeProbe in the log).
 *   am broadcast -n com.abdllh.aura/.debug.ChromeProbe --ez on true             the switch; off takes it away again
 *      --es show maps|music|phone|apps|settings|home      the steps of that panel coming to the top, with no panel
 *      --es hold home|maps --ef at 60                     a still picture, that many ms into the row's change
 *      --ef kmh 60                                        a fix each second at that speed, real path, until --ez nofix true
 *      --ez listen true|false --ef level .6               the voice's ring
 *      --ei vol 12 --ez mute false --ef bar .466          the mockups' numbers (bar -1: down)
 *      --es word "المغرب 4:56" --es left "بعد 38 د" --es icon sunset  the same
 *      --es time "4:18 م"
 *      --es order spec|nearest                            the row built anew in that order
 *      --ez status true                                   GPS, Wi-Fi and phone all on
 *      --es speed 112,120,over,"400 م"                    the strip's speed, in its place
 *      --ei rows 20 --ei gap 400                          Home's row to the dock and back, measured
 *      --ei plates 20 --ei gap 400                        the plate from cell to cell, measured
 *      --ei rest 30                                       frames the window drew in that many seconds with nothing touched
 *      --ei panels 10 --ei gap 700 --es to settings       real panels, there and back, measured
 *      --es fly 1                                         the Flyer once, from Home's cover place to Music's
 *      --ez night true                                    the Night Panel
 *      --es nightshow "600 م"                             the same with the mockup's numbers
 *      --ez state true                                    where it all is, in the log
 * Frame times from Window.addOnFrameMetricsAvailableListener. own: what the app does for a frame, ui (input, animation, layout, draw: the main thread) and rend (sync and commands: the render
 * thread, which also waits for the host's GL here); total also counts waiting and the swap, which on an emulator sharing a GPU say more about the host.
 */
class ChromeProbe : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val s = HomeActivity.current ?: return
        if (i.hasExtra("on")) Chrome.debugOn(s, i.getBooleanExtra("on", false))
        i.getStringExtra("order")?.let { Chrome.debugOrder(s, if (it == "spec") Asked.ORDER_SPEC else Asked.ORDER_NEAREST) }
        val ch = Chrome.of(s)
        if (i.hasExtra("status")) ch?.debugStatus = i.getBooleanExtra("status", false)
        i.getStringExtra("show")?.let { ch?.debugShow(it) }
        i.getStringExtra("cut")?.let { ch?.debugShow(it, false) }
        i.getStringExtra("speed")?.let { v ->
            val a = v.split(",")
            ch?.debugSpeed(a[0].toIntOrNull(), a[1].toInt(), a.getOrNull(2) == "over", a.getOrNull(3))
        }
        if (i.hasExtra("hold")) ch?.debugHold(i.getStringExtra("hold")!!, i.getFloatExtra("at", 0f))
        if (i.hasExtra("kmh")) Fake.go(i.getFloatExtra("kmh", 0f))
        if (i.getBooleanExtra("nofix", false)) Fake.stop()
        if (i.hasExtra("listen")) ch?.voice?.listening(i.getBooleanExtra("listen", false))
        if (i.hasExtra("level")) ch?.voice?.level(i.getFloatExtra("level", 0f))
        if (i.hasExtra("vol")) ch?.volume?.hold(i.getIntExtra("vol", 12), i.getBooleanExtra("mute", false), i.getFloatExtra("bar", -1f))
        i.getStringExtra("word")?.let { w ->
            val left = i.getStringExtra("left") ?: ""
            val icon = c.resources.getIdentifier("ic_" + (i.getStringExtra("icon") ?: "sunset"), "drawable", c.packageName)
            ch?.rowHome?.setPrayer(w, left, icon)
            ch?.rowDock?.glance?.setPrayer(w, icon, i.getBooleanExtra("quiet", false))
        }
        i.getStringExtra("time")?.let { ch?.strip?.setTime(it) }
        i.getStringExtra("song")?.let { t ->
            val art = try { c.assets.open("gallery/sea.jpg").use { android.graphics.BitmapFactory.decodeStream(it) } } catch (_: Throwable) { null }
            ch?.rowDock?.glance?.setSong(t, art, true, false)
            ch?.rowDock?.glance?.face(true, false)
        }
        if (i.getBooleanExtra("night", false)) s.root.night.show()
        i.getStringExtra("nightshow")?.let { s.root.night.debugHold("87", 120, it) }
        i.getStringExtra("fly")?.let {
            val b = c.assets.open("gallery/sea.jpg").use { st -> android.graphics.BitmapFactory.decodeStream(st) }
            val sq = android.graphics.Bitmap.createScaledBitmap(b, 204, 204, true)
            s.root.flyer.fly(sq, 278f, 338f, 84f, 16f, 92f, 48f, 204f, 20f, i.getIntExtra("ms", 320).toLong()) { Log.i(TAG, "flew") }
        }
        if (i.getBooleanExtra("state", false)) Log.i(TAG, "state: ${ch?.debugState()} volume=${com.abdllh.aura.system.CarAudio.volume()}")
        val gap = i.getIntExtra("gap", 400).toLong()
        if (i.hasExtra("rows") && ch != null) rows(s, ch, i.getIntExtra("rows", 10), gap)
        if (i.hasExtra("plates") && ch != null) plates(s, ch, i.getIntExtra("plates", 10), gap)
        if (i.hasExtra("panels")) panels(s, i.getIntExtra("panels", 5), gap, i.getStringExtra("to") ?: Screens.SETTINGS)
        if (i.hasExtra("rest")) rest(s, i.getIntExtra("rest", 30))
    }

    /** Fixes at one speed, once a second, through the real path: CarLocation, so DriveState, Gear, RoadWatch and the strip all hear them. */
    private object Fake {
        private val main = Handler(Looper.getMainLooper())
        private var kmh = -1f
        private val tick = object : Runnable {
            override fun run() {
                if (kmh < 0f) return
                val l = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = CarLocation.last?.latitude ?: 26.43
                    longitude = CarLocation.last?.longitude ?: 50.10
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    speed = kmh / 3.6f
                    bearing = 90f
                    accuracy = 5f
                }
                CarLocation.simulated = true
                CarLocation.publish(l)
                main.postDelayed(this, 1000L)
            }
        }

        fun go(v: Float) {
            val was = kmh >= 0f
            kmh = v
            if (!was) tick.run()
        }

        fun stop() {
            kmh = -1f
            main.removeCallbacks(tick)
        }
    }

    private fun rows(s: HomeActivity, ch: Chrome, rounds: Int, gap: Long) = run(s, "rows", rounds * 2, gap) { n ->
        ch.debugShow(if (n % 2 == 0) Screens.MAPS else Screens.HOME)
    }

    private fun plates(s: HomeActivity, ch: Chrome, rounds: Int, gap: Long) {
        ch.debugShow(Screens.MAPS, false)
        val walk = arrayOf(Screens.MUSIC, Screens.SETTINGS, Screens.MAPS, Screens.APPS, Screens.HOME, Screens.PHONE)
        var i = 0
        run(s, "plates", rounds, gap) {
            // (never Home itself here: that is the rows' change)
            var to = walk[i++ % walk.size]
            if (to == Screens.HOME) to = walk[i++ % walk.size]
            ch.debugShow(to)
        }
    }

    private fun panels(s: HomeActivity, rounds: Int, gap: Long, to: String) = run(s, "panels $to", rounds * 2, gap) { n ->
        s.panels.show(if (n % 2 == 0) to else Screens.HOME)
    }

    private fun run(s: HomeActivity, name: String, steps: Int, gap: Long, step: (Int) -> Unit) {
        val rec = Rec(name)
        rec.start(s.window)
        val h = Handler(Looper.getMainLooper())
        var n = 0
        val go = object : Runnable {
            override fun run() {
                if (n >= steps) {
                    h.postDelayed({ rec.stop(s.window) }, 500L)
                    return
                }
                step(n++)
                h.postDelayed(this, gap)
            }
        }
        go.run()
    }

    private fun rest(s: HomeActivity, seconds: Int) {
        val rec = Rec("rest ${seconds}s")
        rec.start(s.window)
        Handler(Looper.getMainLooper()).postDelayed({ rec.stop(s.window) }, seconds * 1000L)
    }

    private class Rec(private val name: String) : Window.OnFrameMetricsAvailableListener {
        private val own = ArrayList<Double>()
        private val ui = ArrayList<Double>()
        private val render = ArrayList<Double>()
        private val total = ArrayList<Double>()
        private val swap = ArrayList<Double>()
        private val thread = HandlerThread("chrome-frames").apply { start() }
        private var dropped = 0
        private var layouts = 0
        private val layoutSeen = ViewTreeObserver.OnGlobalLayoutListener { layouts++ }

        fun start(w: Window) {
            w.addOnFrameMetricsAvailableListener(this, Handler(thread.looper))
            w.decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutSeen)
            Log.i(TAG, "$name: started")
        }

        fun stop(w: Window) {
            w.removeOnFrameMetricsAvailableListener(this)
            w.decorView.viewTreeObserver.removeOnGlobalLayoutListener(layoutSeen)
            Handler(thread.looper).post {
                Log.i(TAG, "$name: frames ${own.size}, dropped reports $dropped, layout passes $layouts")
                Log.i(TAG, "$name: own   ${line(own)}")
                Log.i(TAG, "$name: ui    ${line(ui)}")
                Log.i(TAG, "$name: rend  ${line(render)}")
                Log.i(TAG, "$name: total ${line(total)}")
                Log.i(TAG, "$name: swap  ${line(swap)}")
                Log.i(TAG, "$name: done")
                thread.quitSafely()
            }
        }

        private fun line(v: List<Double>): String {
            if (v.isEmpty()) return "none"
            val s = v.sorted()
            fun p(q: Double) = s[((s.size - 1) * q).toInt()]
            return String.format(Locale.ROOT, "p50 %.2f p90 %.2f p95 %.2f p99 %.2f max %.2f, over 16 ms: %d", p(.5), p(.9), p(.95), p(.99), s.last(), s.count { it > 16.0 })
        }

        override fun onFrameMetricsAvailable(w: Window, m: FrameMetrics, drop: Int) {
            dropped += drop
            val u = m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + m.getMetric(FrameMetrics.ANIMATION_DURATION) + m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + m.getMetric(FrameMetrics.DRAW_DURATION)
            val r = m.getMetric(FrameMetrics.SYNC_DURATION) + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
            own.add((u + r) / 1e6)
            ui.add(u / 1e6)
            render.add(r / 1e6)
            total.add(m.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6)
            swap.add(m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION) / 1e6)
        }
    }

    private companion object {
        const val TAG = "AuraChromeProbe"
    }
}
