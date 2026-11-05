package com.abdllh.aura.nav

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lift
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.dp
import kotlin.math.abs

/**
 * What belongs to the map itself over [MapsPanel]: zoom, the compass, the way back to the car, the attribution, and what the camera does after a touch. Today's look, for
 * the driving column's builder to replace (the tools of `map-browse` and `drive-map-touched`, SPEC.md sections 1, 6.2, 6.3). Browse only ever calls [forSearch], [recenter], [hideRecenter].
 */
internal class MapTools(panel: MapsPanel) : MapsPart(panel) {
    /** The map's own controls at the end side, faded away together while the keyboard is up. */
    lateinit var controls: FrameLayout
        private set
    private lateinit var recenterHolder: FrameLayout
    private lateinit var recenterBtn: LinearLayout
    private lateinit var recenterText: AText
    private lateinit var compassIcon: ImageView
    private lateinit var zoom: LinearLayout

    private var bearingWatched = false
    private var compassAt = Float.NaN
    /** The recenter pill stands above whichever card is at the bottom (glides when that one comes or goes). */
    private var recenterLift = 0f

    init {
        root.viewTreeObserver.addOnGlobalLayoutListener { if (panel.shown) liftRecenter() }
    }

    override fun build() {
        recenterLift = 0f
        compassAt = Float.NaN
        // ---- end side: the map's controls (one layer, faded away while typing)
        controls = FrameLayout(this)
        panel.toolsLayer.addView(controls, MATCH, MATCH)
        val attribution = label(Type.ATTRIBUTION_SP, Palette.text3, Fonts.REGULAR).apply {
            text = MapStyle.attribution(CarLocation.lastKnown())
            setPadding(Space.S.dp, Space.XXS.dp, Space.S.dp, Space.XXS.dp)
            background = Shapes.rect(Palette.withAlpha(Palette.glass, 0.7f), Radius.XS)
        }
        controls.addView(attribution, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, Space.M.dp, MapsPanel.BOTTOM_EDGE.dp); marginEnd = Space.M.dp })
        // (the credit follows what is drawn: the pack inside the Kingdom, OpenFreeMap's tiles outside it)
        map.onCameraIdle = { at -> MapStyle.attribution(at).let { if (attribution.text != it) attribution.text = it } }

        // compass, and + / − as one capsule (six round buttons on one edge were a wall of controls)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        compassIcon = ImageView(this)
        btns.addView(mapButton(R.drawable.ic_compass, compassIcon, R.string.maps_north) {
            if (NavSession.state == NavSession.State.NAVIGATING) recenter() else map.northUp()
        })
        zoom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Shapes.pill(Palette.glass, Palette.glassStroke)
            lift(Elev.FLOAT, overMap = true)
        }
        zoom.addView(zoomHalf(R.drawable.ic_plus, R.string.maps_zoom_in) { map.zoomBy(1.0) }, lp(MAP_BTN.dp, 64.dp))
        zoom.addView(View(this).apply { setBackgroundColor(Palette.divider) }, lp(28.dp, 1.dp))
        zoom.addView(zoomHalf(R.drawable.ic_minus, R.string.maps_zoom_out) { map.zoomBy(-1.0) }, lp(MAP_BTN.dp, 64.dp))
        btns.addView(zoom, lp(MAP_BTN.dp, WRAP).apply { topMargin = Space.M.dp })
        controls.addView(btns, flp(WRAP, WRAP, Gravity.END or Gravity.TOP).apply { setMargins(0, MapsPanel.TOP_EDGE.dp + Space.S.dp, Space.L.dp, 0); marginEnd = Space.L.dp })

        // ---- the way back to the car: on the driver's side, above the card at the bottom
        recenterHolder = FrameLayout(this).apply { clipChildren = false }
        recenterBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(Space.L.dp, 0, Space.XL.dp, 0)
            background = Shapes.glassPressable(Radius.PILL)
            lift(Elev.FLOAT, overMap = true)
            isClickable = true
            pressScale(Motion.PRESS_CONTROL)
            setOnClickListener { recenter() }
            visibility = View.GONE
            addView(iconView(R.drawable.ic_nav, 22, Palette.accentFg), lp(22.dp, 22.dp))
        }
        recenterText = label(Type.BUTTON, Palette.text)
        recenterBtn.addView(recenterText, lp(WRAP, WRAP).apply { marginStart = Space.M.dp })
        recenterHolder.addView(recenterBtn, flp(WRAP, Touch.MIN.dp))
        panel.toolsLayer.addView(recenterHolder, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(Space.L.dp, 0, Space.L.dp, MapsPanel.BOTTOM_EDGE.dp); marginStart = Space.L.dp })
    }

    override fun sync() = syncZoom()

    override fun tripChanged(on: Boolean) {
        if (on) return
        root.removeCallbacks(autoRecenter) // the trip has just ended (Done, End, or by itself after arriving): back to browsing
        hideRecenter()
    }

    override fun listen(on: Boolean) {
        if (!on) root.removeCallbacks(autoRecenter)
    }

    /** The panel as it was built, at rest. */
    fun fresh() {
        recenterBtn.reveal(false, 12f, animate = false)
        recenterHolder.translationY = 0f
        recenterLift = 0f
        controls.animate().cancel()
        controls.alpha = 1f
        controls.visibility = View.VISIBLE
    }

    /**
     * The map's buttons go while the keyboard is up: the screen shrinks to the strip above it, where they would
     * pile onto each other and onto the attribution; nothing in them is needed while typing.
     */
    fun forSearch(open: Boolean) {
        if (!open) {
            if (controls.visibility == View.VISIBLE && controls.alpha == 1f) return
            controls.visibility = View.VISIBLE
            controls.fadeTo(1f, Motion.MEDIUM, Motion.EMPHASIZED)
        } else if (controls.visibility == View.VISIBLE) {
            controls.fadeTo(0f, Motion.XSHORT, Motion.EXIT) { if (controls.alpha == 0f) controls.visibility = View.INVISIBLE }
        }
    }

    // ------------------------------------------------------------------------------------------ camera
    /** The driver moved the map: a way back, and while driving the map goes back to the car by itself after a while. */
    fun onUserMoved() {
        showRecenter()
        syncZoom()
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
        hideRecenter()
        map.setCamera(if (NavSession.state == NavSession.State.NAVIGATING) AuraMap.Camera.NAVIGATE else AuraMap.Camera.FOLLOW)
        syncZoom()
    }

    fun hideRecenter() = recenterBtn.reveal(false, 12f, scaleFrom = 0.92f)

    private fun showRecenter() {
        recenterText.setIfChanged(getString(if (NavSession.state == NavSession.State.NAVIGATING) R.string.maps_recenter else R.string.maps_my_location))
        recenterBtn.reveal(true, 12f, scaleFrom = 0.92f)
    }

    /** The trip being driven, whole, for a while: a look at it lasts longer than a brush of the screen. */
    fun overviewOfTrip() {
        NavSession.route?.let { r ->
            panel.overview(r.line.points)
            showRecenter()
            syncZoom()
            root.removeCallbacks(autoRecenter)
            root.postDelayed(autoRecenter, AUTO_RECENTER_OVERVIEW_MS)
        }
    }

    /** End pressed: the way back has nothing to go back to. */
    fun tripEnded() {
        root.removeCallbacks(autoRecenter)
        hideRecenter()
    }

    /** + and − while the map is the driver's to move; while it follows the drive, pinching still zooms. */
    private fun syncZoom() {
        val nav = NavSession.state == NavSession.State.NAVIGATING
        zoom.reveal(!nav || map.camera == AuraMap.Camera.FREE || map.camera == AuraMap.Camera.OVERVIEW, 0f, scaleFrom = 0.9f)
    }

    /** The compass shows where north is on the turned map (a transform, set only when it moved a degree or more). */
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
        compassIcon.rotation = -bearing
        // north up already, browsing: the button has nothing to do, and says so by stepping back
        val north = abs(Geo.angleDiff(0.0, bearing.toDouble())) < 1 && NavSession.state != NavSession.State.NAVIGATING
        compassIcon.alpha = if (north) 0.55f else 1f
    }

    private fun liftRecenter() {
        val card = panel.bottomCard()
        val lift = if (card == null || card.height == 0) 0f else -(card.height + Space.M.dp).toFloat()
        if (lift == recenterLift) return
        recenterLift = lift
        if (recenterBtn.isOn) recenterHolder.anim(Motion.MEDIUM, Motion.STANDARD).translationY(lift).start()
        else recenterHolder.translationY = lift
    }

    // ------------------------------------------------------------------------------------------ old buttons
    private fun mapButton(icon: Int, img: ImageView, desc: Int, onClick: () -> Unit): View = FrameLayout(this).apply {
        background = Shapes.pressable(Shapes.oval(Palette.glass, Palette.glassStroke), Shapes.oval(Palette.mix(Palette.glass, Palette.text, 0.08f), Palette.glassStroke))
        lift(Elev.FLOAT, overMap = true)
        isClickable = true
        contentDescription = getString(desc)
        pressScale(Motion.PRESS_ICON)
        setOnClickListener { onClick() }
        img.setImageResource(icon)
        img.setColorFilter(Palette.text)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(img, flp(28.dp, 28.dp, Gravity.CENTER))
        layoutParams = lp(MAP_BTN.dp, MAP_BTN.dp)
    }

    /** One half of the zoom capsule: its own press, inside the capsule's surface. */
    private fun zoomHalf(icon: Int, desc: Int, onClick: () -> Unit): View = FrameLayout(this).apply {
        background = Shapes.ghost(Radius.PILL)
        isClickable = true
        contentDescription = getString(desc)
        pressScale(Motion.PRESS_ICON)
        setOnClickListener { onClick() }
        addView(iconView(icon, 26, Palette.text), flp(26.dp, 26.dp, Gravity.CENTER))
    }

    private companion object {
        /** After the driver moved the map while driving, it goes back to the car by itself after this long, */
        private const val AUTO_RECENTER_MS = 12_000L
        /** and after a look at the whole route, this long. */
        private const val AUTO_RECENTER_OVERVIEW_MS = 20_000L
        /** The map's round buttons. */
        private const val MAP_BTN = 66
    }
}
