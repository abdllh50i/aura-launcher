package com.abdllh.aura.diag

import android.app.Activity
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.nav.AuraMap
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Polyline
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp
import com.mapbox.mapboxsdk.maps.MapView

/**
 * The map as 2.0 will have it (understand/home-shell.md 5.2): full-bleed behind the window, two translucent panels and
 * their text standing still above it, and a car driving a stored road in Dammam at driving speed, heading-up and tilted.
 * It is the app's own [AuraMap] in its navigating camera, with its own style and tiles, fed as navigation feeds it (one
 * fix a second on the route, as [com.abdllh.aura.nav.NavSession] does); nothing here draws for itself, so what is
 * measured is what the car would show. [texture]: the same as a TextureView, at the same size.
 * Main thread, as the map is.
 */
internal class MapRig(private val a: Activity, private val root: FrameLayout, val texture: Boolean) {
    val map = AuraMap(a, interactive = false, texture = texture)
    val frames = MapFrames()
    private val main = Handler(Looper.getMainLooper())
    private val line = Polyline(ROUTE)
    private var along = 0.0
    private var ticks = 0
    private val panels = ArrayList<View>()
    private var running = false
    /** When the style came (elapsedRealtime; 0: not yet). */
    @Volatile var styleAt = 0L
        private set
    /** When the camera was first asked to be on the route. */
    @Volatile var cameraAt = 0L
        private set

    private val fix = object : Runnable {
        override fun run() {
            if (!running) return
            give()
            main.postDelayed(this, 1000L)
        }
    }

    /** One fix: where the car is on the road now, as navigation gives it. */
    private fun give() {
        along += KMH / 3.6
        if (along > line.length - 200) along = 0.0
        val (p, b) = line.at(along)
        map.setCar(p, b, KMH)
        // the next turn's arrow as navigation sets it, now and then (a line and its head, sent to the map)
        if (ticks++ % 8 == 0) map.setTurn(line, along + 260)
    }

    fun start() {
        map.onCreate(null)
        root.addView(map.view, 0, flp(MATCH, MATCH))
        mapView()?.addOnDidFinishRenderingFrameListener(frames)
        map.onReady = {
            styleAt = SystemClock.elapsedRealtime()
            val (p, b) = line.at(0.0)
            map.setRouteAhead(line)
            map.setDestination(line.at(line.length).first)
            map.setCar(p, b, KMH)
            map.setCamera(AuraMap.Camera.NAVIGATE)
            cameraAt = SystemClock.elapsedRealtime()
            if (!running) { running = true; main.postDelayed(fix, 1000L) }
        }
        map.onStart()
        map.onResume()
        addPanels()
    }

    fun stop() {
        running = false
        main.removeCallbacks(fix)
        for (p in panels) root.removeView(p)
        panels.clear()
        mapView()?.removeOnDidFinishRenderingFrameListener(frames)
        map.onPause()
        map.onStop()
        root.removeView(map.view)
        map.onDestroy()
    }

    private fun mapView(): MapView? = map.view.getChildAt(0) as? MapView

    /** How many things of the map are drawn around the car: 0 means that the numbers are for an empty land colour. */
    fun drawnFeatures(): Int {
        val m = map.map ?: return -1
        val cx = map.view.width / 2f
        val cy = map.view.height * 0.6f
        return try { m.queryRenderedFeatures(RectF(cx - 160, cy - 120, cx + 160, cy + 120)).size } catch (_: Throwable) { -1 }
    }

    private fun addPanels() {
        val fill = Palette.withAlpha(Palette.bg, 0.8f)
        val side = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(Space.XL.dp, Space.XL.dp, Space.XL.dp, Space.XL.dp)
            background = Shapes.rect(fill, Radius.XL, Palette.stroke)
            addView(a.label(Type.TITLE, Palette.text).apply { setText(R.string.diag_panel_title) })
            for (s in intArrayOf(R.string.diag_panel_line1, R.string.diag_panel_line2, R.string.diag_panel_line3)) {
                addView(a.label(Type.BODY, Palette.text2).apply { setText(s) }, lp(MATCH, WRAP).apply { topMargin = Space.M.dp })
            }
        }
        val pill = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Space.XL.dp, Space.M.dp, Space.XL.dp, Space.M.dp)
            background = Shapes.rect(fill, Radius.XL, Palette.stroke)
            addView(a.label(Type.DISPLAY_S, Palette.text, gravity = Gravity.CENTER).apply { text = "24:00" })
            addView(a.label(Type.CAPTION, Palette.text2, gravity = Gravity.CENTER).apply { setText(R.string.diag_panel_line1) })
        }
        // (above the map, under whatever the screen check itself shows)
        root.addView(side, 1, flp(320.dp, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp) })
        root.addView(pill, 2, flp(220.dp, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(Space.L.dp, Space.L.dp, Space.L.dp, Space.XXL.dp) })
        panels.add(side)
        panels.add(pill)
    }

    companion object {
        const val KMH = 60.0

        /**
         * A real road: the first 7 km from the middle of Dammam towards Khobar, as BRouter gave them from OpenStreetMap
         * (no one's own place, and a long way past what any one test drives). It is in the offline map's area.
         */
        private val ROUTE: List<LatLon> = listOf(
            26.420714 to 50.088791, 26.420893 to 50.089192, 26.422631 to 50.092644, 26.422835 to 50.093110, 26.423030 to 50.093492,
            26.423420 to 50.094269, 26.423718 to 50.094846, 26.423951 to 50.095309, 26.424444 to 50.096274, 26.424681 to 50.096750,
            26.425205 to 50.097783, 26.425255 to 50.097886, 26.425449 to 50.098207, 26.426088 to 50.099445, 26.426332 to 50.099957,
            26.426837 to 50.100989, 26.427415 to 50.102112, 26.428008 to 50.103291, 26.428254 to 50.103775, 26.428365 to 50.104027,
            26.428423 to 50.104190, 26.428463 to 50.104344, 26.428497 to 50.104526, 26.428518 to 50.104671, 26.428538 to 50.104968,
            26.428540 to 50.106819, 26.428536 to 50.107863, 26.428520 to 50.108045, 26.428497 to 50.108176, 26.428458 to 50.108334,
            26.428409 to 50.108479, 26.428342 to 50.108622, 26.427776 to 50.109734, 26.427177 to 50.110891, 26.425921 to 50.113347,
            26.425143 to 50.114889, 26.424919 to 50.115301, 26.423965 to 50.117174, 26.422972 to 50.118939, 26.422603 to 50.119476,
            26.422167 to 50.120065, 26.421924 to 50.120346, 26.421046 to 50.121328, 26.418111 to 50.124682, 26.417438 to 50.125402,
            26.415386 to 50.127743, 26.412780 to 50.130692, 26.410617 to 50.133118, 26.409467 to 50.134414, 26.407836 to 50.136231,
            26.403781 to 50.140830, 26.402713 to 50.142052, 26.400560 to 50.144488, 26.399681 to 50.145447
        ).map { LatLon(it.first, it.second) }
    }
}
