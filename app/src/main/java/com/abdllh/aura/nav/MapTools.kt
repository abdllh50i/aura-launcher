package com.abdllh.aura.nav

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.glyph
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.pressScale
import kotlin.math.abs

/**
 * What belongs to the map itself over [MapsPanel], at x 384 beside the column in both languages (SPEC.md sections 1, 5): the compass at y 138, the zoom stack at y 330, locate at y 450, and what
 * the camera does after a touch. Browse only ever calls [forSearch], [recenter], [hideRecenter].
 */
internal class MapTools(panel: MapsPanel) : MapsPart(panel) {
    /** The whole layer's own, which the old speed cluster of the column still lies in, until the column is rebuilt. */
    lateinit var controls: FrameLayout
        private set
    /** The tools, faded together: their alpha goes straight into what they draw, no layer. */
    private lateinit var group: FrameLayout
    private lateinit var zoom: SurfaceCard
    private lateinit var locate: IconButton
    private lateinit var compass: SurfaceCard
    private lateinit var compassGlyph: ImageView

    private var bearingWatched = false
    private var compassAt = Float.NaN
    private var searchOpen = false

    override fun build() {
        compassAt = Float.NaN
        controls = FrameLayout(this)
        panel.toolsLayer.addView(controls, MATCH, MATCH)
        group = object : FrameLayout(this) {
            override fun hasOverlappingRendering() = false
        }
        panel.toolsLayer.addView(group, MATCH, MATCH)

        compass = pillCard()
        compassGlyph = ImageView(this).apply { setImageResource(R.drawable.ic_compass); scaleType = ImageView.ScaleType.FIT_CENTER }.glyph { Palette.icon }
        compass.addView(compassGlyph, at(13, 13, GLYPH, GLYPH))
        compass.contentDescription = getString(R.string.maps_north)
        compass.pressScale(PressGesture.CONTROL)
        compass.setOnClickListener { if (NavSession.state == NavSession.State.NAVIGATING) recenter() else map.northUp() }
        compass.visibility = View.GONE
        group.addView(compass, Surface.PILL.at(TOOL_X, COMPASS_Y, BTN, BTN))

        // + and − as one stack with a hairline, one surface
        zoom = pillCard()
        zoom.addView(IconButton(this, R.drawable.ic_plus, ZOOM_GLYPH, BTN, BTN, press = PressGesture.CONTROL).apply {
            contentDescription = getString(R.string.maps_zoom_in)
            setOnClickListener { map.zoomBy(1.0) }
        }, at(0, 0, BTN, BTN))
        zoom.addView(View(this).look { setBackgroundColor(Palette.line) }, at(12, BTN, BTN - 24, 1))
        zoom.addView(IconButton(this, R.drawable.ic_minus, ZOOM_GLYPH, BTN, BTN, press = PressGesture.CONTROL).apply {
            contentDescription = getString(R.string.maps_zoom_out)
            setOnClickListener { map.zoomBy(-1.0) }
        }, at(0, BTN, BTN, BTN))
        group.addView(zoom, Surface.PILL.at(TOOL_X, ZOOM_Y, BTN, 2 * BTN))

        locate = IconButton(this, R.drawable.ic_locate, GLYPH, BTN, BTN, IconButton.Kind.PILL, PressGesture.CONTROL).apply {
            contentDescription = getString(R.string.maps_my_location)
            setOnClickListener { recenter() }
        }
        group.addView(locate, Surface.PILL.at(TOOL_X, LOCATE_Y, BTN, BTN))
    }

    private fun pillCard() = object : SurfaceCard(this@MapTools, Surface.PILL) {
        override fun hasOverlappingRendering() = false
    }

    override fun sync() = syncTools()

    override fun tripChanged(on: Boolean) {
        if (on) return
        root.removeCallbacks(autoRecenter) // the trip has just ended (Done, End, or by itself after arriving): back to browsing
        syncTools()
    }

    override fun listen(on: Boolean) {
        if (!on) root.removeCallbacks(autoRecenter)
    }

    override fun onFace(face: MapsPanel.Face) = syncTools()

    /** The panel as it was built, at rest. */
    fun fresh() {
        searchOpen = false
        group.animate().cancel()
        group.alpha = 1f
        group.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE
        syncTools()
    }

    /** A search or a place card is up, or not: the tools go while it is (`search`, `place-routes`), 180 `exit`, and come back in 140 `move`. */
    fun forSearch(open: Boolean) {
        if (open == searchOpen) return
        searchOpen = open
        // (the old speed cluster goes with them: it would lie over the card)
        controls.visibility = if (open) View.INVISIBLE else View.VISIBLE
        if (!open) {
            group.visibility = View.VISIBLE
            group.anim(Tokens2.MS_mapToolsIn, Motion.MOVE).alpha(1f).start()
        } else if (group.visibility == View.VISIBLE) {
            group.anim(Tokens2.MS_mapToolsOut, Motion.EXIT).alpha(0f).withEndAction { if (searchOpen) group.visibility = View.INVISIBLE }.start()
        }
    }

    // ------------------------------------------------------------------------------------------ camera
    /** The driver moved the map: a way back, and while driving the map goes back to the car by itself after a while. */
    fun onUserMoved() {
        syncTools()
        if (NavSession.state == NavSession.State.NAVIGATING) {
            root.removeCallbacks(autoRecenter)
            root.postDelayed(autoRecenter, AUTO_RECENTER_MS)
        }
    }

    /** A brush of the screen while driving must not leave the car off the screen until the pill is found. */
    private val autoRecenter = Runnable {
        if (NavSession.state == NavSession.State.NAVIGATING && map.camera != AuraMap.Camera.NAVIGATE) recenter()
    }

    fun recenter() {
        root.removeCallbacks(autoRecenter)
        map.setCamera(if (NavSession.state == NavSession.State.NAVIGATING) AuraMap.Camera.NAVIGATE else AuraMap.Camera.FOLLOW)
        syncTools()
    }

    /** The way back has nothing to go back to any more: a trip has just taken the camera. */
    fun hideRecenter() = syncTools()

    /** The trip being driven, whole, for a while: a look at it lasts longer than a brush of the screen. */
    fun overviewOfTrip() {
        NavSession.route?.let { r ->
            panel.overview(r.line.points)
            syncTools()
            root.removeCallbacks(autoRecenter)
            root.postDelayed(autoRecenter, AUTO_RECENTER_OVERVIEW_MS)
        }
    }

    /** End pressed: the way back has nothing to go back to. */
    fun tripEnded() {
        root.removeCallbacks(autoRecenter)
        syncTools()
    }

    /**
     * On browse always; on a trip only while the camera is the driver's. Locate says the camera is free in `accentInk`, for as long as it is (section 5). No tools in `drive-free`:
     * the column is there.
     */
    private fun syncTools() {
        val nav = NavSession.state == NavSession.State.NAVIGATING
        val free = map.camera == AuraMap.Camera.FREE || map.camera == AuraMap.Camera.OVERVIEW
        val show = panel.face != MapsPanel.Face.DRIVE_FREE && (!nav || free)
        val v = if (show) View.VISIBLE else View.INVISIBLE
        if (zoom.visibility != v) { zoom.visibility = v; locate.visibility = v }
        locate.setGlyphToken(if (free) ({ Palette.accentInk }) else ({ Palette.icon }))
    }

    /** The compass shows where north is on the turned map (a transform, set only when it moved a degree or more), and only when it is turned. */
    fun watchBearing() {
        val m = map.map?.map ?: return
        turnCompass(m.cameraPosition.bearing.toFloat())
        if (bearingWatched) return
        bearingWatched = true
        m.addOnCameraMoveListener { turnCompass(m.cameraPosition.bearing.toFloat()) }
        m.addOnCameraIdleListener { turnCompass(m.cameraPosition.bearing.toFloat()) }
    }

    private fun turnCompass(bearing: Float) {
        if (!panel.shown) return // (the map goes on moving under the home screen: a change to a hidden view would still ask for a frame)
        if (!compassAt.isNaN() && abs(Geo.angleDiff(compassAt.toDouble(), bearing.toDouble())) < 1) return
        compassAt = bearing
        compassGlyph.rotation = -bearing
        val turned = abs(Geo.angleDiff(0.0, bearing.toDouble())) >= 1 && NavSession.state != NavSession.State.NAVIGATING
        val v = if (turned) View.VISIBLE else View.GONE
        if (compass.visibility != v) compass.visibility = v
    }

    private companion object {
        /** After the driver moved the map while driving, it goes back to the car by itself after this long, */
        private const val AUTO_RECENTER_MS = 12_000L
        /** and after a look at the whole route, this long. */
        private const val AUTO_RECENTER_OVERVIEW_MS = 20_000L
        const val TOOL_X = 384
        const val COMPASS_Y = 138
        const val ZOOM_Y = 330
        const val LOCATE_Y = 450
        const val BTN = 56
        const val GLYPH = 30
        const val ZOOM_GLYPH = 28
    }
}
