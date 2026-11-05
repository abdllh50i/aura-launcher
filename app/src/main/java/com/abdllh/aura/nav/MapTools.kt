package com.abdllh.aura.nav

import android.location.LocationManager
import android.os.SystemClock
import android.text.Layout
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import com.abdllh.aura.nav.drive.line
import com.abdllh.aura.nav.drive.moveMs
import com.abdllh.aura.nav.drive.showCard
import com.abdllh.aura.nav.drive.up
import com.abdllh.aura.system.DriveState
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.look
import kotlin.math.abs

/**
 * What belongs to the map itself over [MapsPanel] (SPEC.md sections 1 and 6.2, simple/CHANGES.md sections 4 and 5): on a trip at x 392 in both languages, beside the column and clear of the route; with no trip zoom and locate at
 * x 16, the driver's edge, because the column is empty there. Compass, camera here, overview, zoom, locate, and the attribution, and what the camera does after a touch. Trip or browse, the tools come with a touch on the map and go
 * 5 s after the last one, unless the camera is free: then locate is `accentInk` and stays. Browse has the compass whenever the map is turned. While moving a free camera comes back by itself 10 s after the last touch, a whole route
 * 20 s, and at once under 300 m to a turn; parked it stays until locate is pressed. `drive-free` has none of them. The guidance mute of the mockups is not here: no spoken navigation in 2.0.
 */
internal class MapTools(panel: MapsPanel) : MapsPart(panel) {
    private lateinit var compass: SurfaceCard
    private lateinit var compassGlyph: ImageView
    private lateinit var camHere: IconButton
    private lateinit var overview: IconButton
    private lateinit var zoom: SurfaceCard
    private lateinit var locate: IconButton
    private lateinit var attribution: AText
    private lateinit var markNote: SurfaceCard
    private lateinit var markText: AText
    private var built = false

    private var bearingWatched = false
    private var compassAt = Float.NaN
    private var turned = false
    /** The tools are up from a touch on the map, until [hideTools]. */
    private var touched = false
    /** The keyboard is up: nothing here is needed while typing. */
    private var searching = false
    private var lastMarked: MapExtras.Spot? = null
    private var listening = false

    private fun pill(icon: Int, desc: Int, token: () -> Int = { Palette.icon }, onClick: () -> Unit) =
        IconButton(this, icon, GLYPH, BTN, BTN, IconButton.Kind.PILL, PressGesture.CONTROL, token = token).apply {
            contentDescription = getString(desc)
            setOnClickListener { onClick() }
        }

    override fun build() {
        compassAt = Float.NaN
        val layer = panel.toolsLayer
        attribution = line(Type.attribution, { Palette.ink3 }, moving = false).apply {
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_LTR
            text = MapStyle.attribution(CarLocation.lastKnown())
        }
        layer.addView(attribution, Type.attribution.box(508, 490, 500))
        // (the credit follows what is drawn: the pack inside the Kingdom, OpenFreeMap's tiles outside it)
        map.onCameraIdle = { at -> MapStyle.attribution(at).let { if (attribution.text != it) attribution.text = it } }

        compass = SurfaceCard(this, Surface.PILL).apply {
            isClickable = true
            contentDescription = getString(R.string.maps_north)
            setOnClickListener { map.northUp() }
        }
        compassGlyph = iconView(R.drawable.ic_compass, GLYPH) { Palette.icon }
        compass.addView(compassGlyph, at((BTN - GLYPH) / 2, (BTN - GLYPH) / 2, GLYPH, GLYPH))
        layer.addView(compass, Surface.PILL.at(X_BROWSE, 256, BTN, BTN))

        camHere = pill(R.drawable.ic_cam_add, R.string.mark_camera, { Palette.red }) { mark() }
        layer.addView(camHere, Surface.PILL.at(X, 188, BTN, BTN))
        overview = pill(R.drawable.ic_overview, R.string.maps_overview) { overviewOfTrip() }
        layer.addView(overview, Surface.PILL.at(X, 256, BTN, BTN))

        zoom = SurfaceCard(this, Surface.PILL)
        zoom.addView(IconButton(this, R.drawable.ic_plus, GLYPH, BTN, BTN, press = PressGesture.CONTROL).apply {
            contentDescription = getString(R.string.maps_zoom_in)
            setOnClickListener { map.zoomBy(1.0) }
        }, at(0, 0, BTN, BTN))
        zoom.addView(View(this).look { setBackgroundColor(Palette.line) }, at(12, BTN, BTN - 24, 1))
        zoom.addView(IconButton(this, R.drawable.ic_minus, GLYPH, BTN, BTN, press = PressGesture.CONTROL).apply {
            contentDescription = getString(R.string.maps_zoom_out)
            setOnClickListener { map.zoomBy(-1.0) }
        }, at(0, BTN, BTN, BTN))
        layer.addView(zoom, Surface.PILL.at(X, 324, BTN, 2 * BTN))

        locate = pill(R.drawable.ic_locate, R.string.maps_my_location) { recenter() }
        layer.addView(locate, Surface.PILL.at(X, 448, BTN, BTN))

        // "a camera here": what happened, beside the button, and a tap on it takes it back
        markNote = SurfaceCard(this, Surface.PILL).apply {
            isClickable = true
            setOnClickListener { unmark() }
            visibility = View.INVISIBLE
        }
        markText = line(Type.chip, { Palette.ink }, moving = false)
        markNote.addView(markText, Type.chip.box(16, 12, MARK_W - 32))
        layer.addView(markNote, Surface.PILL.at(X + BTN + 8, 188, MARK_W, BTN))

        for (v in arrayOf<View>(compass, camHere, overview, zoom, locate)) { v.alpha = 0f; v.visibility = View.INVISIBLE }
        built = true
        syncTools(false)
    }

    override fun sync() {
        syncTools(true)
        val p = NavSession.progress
        // a turn under 300 m: the camera comes back at once, and whatever it was showing goes (moving: parked at a light the look is his)
        if (NavSession.state == NavSession.State.NAVIGATING && p != null && p.toNext < TURN_NEAR_M && DriveState.moving &&
            (map.camera == AuraMap.Camera.FREE || map.camera == AuraMap.Camera.OVERVIEW)) recenter()
    }

    override fun tripChanged(on: Boolean) {
        root.removeCallbacks(autoRecenter)
        root.removeCallbacks(hideTools)
        touched = false
    }

    override fun onFace(face: MapsPanel.Face) {
        // `drive-free`: the driving camera, with no trip. Back on browse with the car moving it stays until he moves it or the 10 s bring the face back
        when (face) {
            MapsPanel.Face.DRIVE_FREE -> if (map.camera != AuraMap.Camera.NAVIGATE) map.setCamera(AuraMap.Camera.NAVIGATE)
            MapsPanel.Face.BROWSE -> if (DriveState.parked && map.camera == AuraMap.Camera.NAVIGATE) map.setCamera(AuraMap.Camera.FOLLOW)
            MapsPanel.Face.TRIP -> {}
        }
        syncTools(true)
    }

    override fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        if (on) {
            DriveState.addListener(onDrive)
            armReturn() // (back from a panel with the camera where he left it: it comes back by itself all the same)
        } else {
            DriveState.removeListener(onDrive)
            root.removeCallbacks(autoRecenter)
            root.removeCallbacks(hideTools)
            root.removeCallbacks(hideNote)
            touched = false
        }
    }

    private val onDrive: () -> Unit = { armReturn() }

    /** The panel as it was built, at rest. */
    fun fresh() {
        searching = false
        touched = false
        root.removeCallbacks(hideTools)
        syncTools(false)
    }

    /** The map's buttons go while the keyboard is up: nothing in them is needed while typing. */
    fun forSearch(open: Boolean) {
        searching = open
        syncTools(true)
    }

    // ------------------------------------------------------------------------------------------ which are up
    private val freeCamera: Boolean get() = map.camera == AuraMap.Camera.FREE
    private val trip: Boolean get() = NavSession.state != NavSession.State.IDLE

    private fun syncTools(animate: Boolean) {
        if (!built) return
        val face = panel.face
        val free = freeCamera
        val whole = map.camera == AuraMap.Camera.OVERVIEW
        val onTrip = trip && !searching
        val browsing = !trip && !searching && face == MapsPanel.Face.BROWSE
        val up = touched || free
        val all = onTrip && up
        // (`رجّعها` lies where they would)
        val browseTools = browsing && up && !panel.drive.undoUp
        placeZoomAndLocate()
        tool(compass, browsing && turned && !panel.drive.undoUp, animate)
        tool(camHere, all && !whole && CameraMark.enabled, animate)
        tool(overview, all && !whole && NavSession.state == NavSession.State.NAVIGATING, animate)
        tool(zoom, (all && !whole) || browseTools, animate)
        tool(locate, all || (onTrip && whole) || browseTools, animate)
        attribution.visibility = if (browsing) View.VISIBLE else View.INVISIBLE
        val want = if (free || whole) ACCENT else ICON
        if (want != locateTint) {
            locateTint = want
            locate.setGlyphToken(if (want == ACCENT) { { Palette.accentInk } } else { { Palette.icon } })
        }
    }

    private var locateTint = ICON

    /** Beside the column on a trip, at the driver's edge with none. A translation, and at once: they fade in the place they belong to. */
    private fun placeZoomAndLocate() {
        val dx = if (trip) 0f else (X_BROWSE - X).toFloat()
        if (zoom.translationX == dx) return
        for (v in arrayOf(zoom, locate)) {
            tool(v, false, false)
            v.translationX = dx
        }
    }

    /** Map tools in, out: alpha, 140 and 180 (section 9). */
    private fun tool(v: View, show: Boolean, animate: Boolean) {
        if (show == v.up) return
        if (!animate || !v.isAttachedToWindow || !root.isShown) {
            v.animate().cancel()
            v.setTag(R.id.aura_exiting, null)
            v.alpha = if (show) 1f else 0f
            v.visibility = if (show) View.VISIBLE else View.INVISIBLE
            return
        }
        if (show) {
            v.setTag(R.id.aura_exiting, null)
            v.visibility = View.VISIBLE
            v.anim(moveMs(Tokens2.MS_mapToolsIn), Motion.MOVE).alpha(1f).start()
        } else {
            v.setTag(R.id.aura_exiting, true)
            v.anim(moveMs(Tokens2.MS_mapToolsOut), Motion.EXIT).alpha(0f).withEndAction {
                v.setTag(R.id.aura_exiting, null)
                v.visibility = View.INVISIBLE
            }.start()
        }
    }

    // ------------------------------------------------------------------------------------------ touches
    /** A touch on the map that nothing over it took. */
    fun mapTouched() {
        // (a card lies over the map: this touch is to leave it, not to ask for tools)
        if (searching) return
        touched = true
        syncTools(true)
        keepUp()
    }

    /** Any touch on the panel: the tools stay while he is at them, and a camera he holds does not leave under his hand. */
    fun keepUp() {
        if (touched) {
            root.removeCallbacks(hideTools)
            root.postDelayed(hideTools, TOOLS_HIDE_MS)
        }
        armReturn()
    }

    private val hideTools = Runnable {
        touched = false
        syncTools(true)
    }

    /** The driver moved the map: locate says so, and while moving it comes back by itself. */
    fun onUserMoved() {
        syncTools(true)
        armReturn()
    }

    private fun armReturn() {
        root.removeCallbacks(autoRecenter)
        if (!trip || !DriveState.moving) return
        when (map.camera) {
            AuraMap.Camera.FREE -> root.postDelayed(autoRecenter, RETURN_FREE_MS)
            AuraMap.Camera.OVERVIEW -> root.postDelayed(autoRecenter, RETURN_OVERVIEW_MS)
            else -> {}
        }
    }

    /** A brush of the screen while driving must not leave the car off the screen until locate is found. */
    private val autoRecenter = Runnable {
        if (NavSession.state == NavSession.State.NAVIGATING && DriveState.moving && map.camera != AuraMap.Camera.NAVIGATE) recenter()
    }

    fun recenter() {
        root.removeCallbacks(autoRecenter)
        map.setCamera(if (NavSession.state == NavSession.State.NAVIGATING) AuraMap.Camera.NAVIGATE else AuraMap.Camera.FOLLOW)
        // (the tools stay as long as the touch that brought them says)
        if (touched) keepUp()
        syncTools(true)
    }

    /** The old name for what the place card calls when a trip starts: the tools of browse go with [syncTools]. */
    fun hideRecenter() {
        root.removeCallbacks(autoRecenter)
        syncTools(true)
    }

    /** The trip being driven, whole, north up, the column staying. A look lasts longer than a brush of the screen. */
    private fun overviewOfTrip() {
        val r = NavSession.route ?: return
        panel.overview(r.line.points)
        touched = false
        root.removeCallbacks(hideTools)
        syncTools(true)
        armReturn()
    }

    /** End pressed: the way back has nothing to go back to. */
    fun tripEnded() {
        root.removeCallbacks(autoRecenter)
        touched = false
        syncTools(true)
    }

    // ------------------------------------------------------------------------------------------ the compass
    /** The compass shows where north is on the turned map (a transform, set only when it moved a degree or more), and only then: section 5. */
    fun watchBearing() {
        val m = map.map?.map ?: return
        turnCompass(m.cameraPosition.bearing.toFloat())
        if (bearingWatched) return
        bearingWatched = true
        m.addOnCameraMoveListener { turnCompass(m.cameraPosition.bearing.toFloat()) }
        m.addOnCameraIdleListener { turnCompass(m.cameraPosition.bearing.toFloat()) }
    }

    private fun turnCompass(bearing: Float) {
        if (!panel.shown || !built) return // (the map goes on moving under the home screen: a change to a hidden view would still ask for a frame)
        if (!compassAt.isNaN() && abs(Geo.angleDiff(compassAt.toDouble(), bearing.toDouble())) < 1) return
        compassAt = bearing
        val t = abs(Geo.angleDiff(0.0, bearing.toDouble())) >= 1
        if (t != turned) {
            turned = t
            syncTools(true)
        }
        if (t) compassGlyph.rotation = -bearing
    }

    // ------------------------------------------------------------------------------------------ a camera here
    /**
     * Only a fix of now, from the GPS, precise: [CarLocation] keeps the last fix of the day before until the GPS has found the sky again, and a camera marked with it would be stored at the wrong
     * place and beeped there on every drive. The simulator's and the debug `mock_loc` fixes count as GPS ones. As [CameraMark] on the old screens.
     */
    private fun mark() {
        val car = CarLocation.last
        val fresh = car != null &&
            (car.provider == LocationManager.GPS_PROVIDER || car.provider == "sim" || car.provider == "mock") &&
            SystemClock.elapsedRealtimeNanos() - car.elapsedRealtimeNanos < MARK_FRESH_NS &&
            (!car.hasAccuracy() || car.accuracy <= MARK_ACCURACY_M)
        if (car == null || !fresh) { say(getString(R.string.mark_camera_no_gps), false); return }
        // its speed: the limit of the marked street the car is on, when there is one
        val (spot, added) = MapExtras.addMine(LatLon(car.latitude, car.longitude), RoadWatch.state.limit)
        if (!added) { say(getString(R.string.mark_camera_known), false); return }
        RoadWatch.quiet(spot)
        lastMarked = spot
        say(getString(R.string.mark_camera_saved), true)
    }

    private fun unmark() {
        val s = lastMarked ?: return
        lastMarked = null
        MapExtras.removeMine(s)
        say(getString(R.string.mark_camera_undone), false)
    }

    private fun say(s: String, canUndo: Boolean) {
        if (!canUndo) lastMarked = null
        markText.text = s
        markNote.showCard(true)
        root.removeCallbacks(hideNote)
        root.postDelayed(hideNote, if (canUndo) 5000L else 2500L)
    }

    private val hideNote = Runnable { markNote.showCard(false) }

    private companion object {
        const val X = 392
        const val X_BROWSE = 16
        const val BTN = 56
        const val GLYPH = 30
        const val ICON = 0
        const val ACCENT = 1
        const val MARK_W = 300
        const val TOOLS_HIDE_MS = 5000L
        const val RETURN_FREE_MS = Tokens2.MS_cameraBack
        const val RETURN_OVERVIEW_MS = 20_000L
        const val TURN_NEAR_M = 300.0
        const val MARK_FRESH_NS = 5_000_000_000L
        const val MARK_ACCURACY_M = 50f
    }
}
