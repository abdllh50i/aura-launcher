package com.abdllh.aura.nav

import android.Manifest
import android.content.ContextWrapper
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.chrome.Chrome
import com.abdllh.aura.home.HomeActivity
import com.abdllh.aura.home.Panel
import com.abdllh.aura.home.Screens
import com.abdllh.aura.system.DriveState
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

/**
 * Aura's own maps: search a place, see the route, drive with turn-by-turn guidance, over the one map under the window. The map and what is on
 * it are [MapController]'s, and it goes on being there when this panel is not. Kept once built, and built ahead of time ([com.abdllh.aura.home.PanelStack.prepare]);
 * what belonged to a visit (a search, a place, a place being chosen) goes when it leaves the stack, as it went with the activity.
 *
 * This file is the panel and what its parts share; each part is a file and a layer of its own, so that they can be rebuilt at the same time (docs/DEV.md, "Maps' parts"):
 * [DriveColumn] the driver's cards, [BrowseCards] search, results, the place and the visit's logic, [MapTools] zoom, locate, compass and attribution.
 *  args: "search" = open the search; "go" = "home" | "work" to drive there at once; "simulate" (debug builds).
 */
class MapsPanel(shell: HomeActivity) : Panel(shell, Screens.MAPS) {
    internal val map: MapController get() = shell.map
    override val root: FrameLayout = object : FrameLayout(this) {
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            val down = e.actionMasked == MotionEvent.ACTION_DOWN
            if (down) touched()
            val used = super.dispatchTouchEvent(e)
            // (what nothing over the map took goes through to the map)
            if (down && !used) tools.mapTouched()
            return used
        }
    }
    override val keep: Boolean get() = true
    // (a theme change recolours what shows in place: section 8. Every part reads its tokens at [com.abdllh.aura.ui.Look.apply])
    override val recolours: Boolean get() = true
    // (the map's slot does not follow the keyboard, and the field is at the top, above it: this window never pans. See DEV.md, "One window")
    override val softInput: Int get() = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN

    /** Whole-screen layers, bottom to top, section 6.0's order: a part puts its views in its own, at the mockups' pixels. */
    internal val columnLayer = FrameLayout(this)
    internal val browseLayer = FrameLayout(this)
    internal val toolsLayer = FrameLayout(this)

    // The parts. (Tools first: the old speed cluster of the column lies in their group until the column is rebuilt)
    internal val tools = MapTools(this)
    internal val drive = DriveColumn(this)
    internal val browse = BrowseCards(this)
    private val parts = arrayOf(tools, drive, browse)

    /** Section 6.0's row for Maps, worked out here and nowhere else: `map-browse`, `drive-free`, or the trip's `drive-*`. The parts hear [MapsPart.onFace]. */
    internal enum class Face { BROWSE, DRIVE_FREE, TRIP }

    internal var face = Face.BROWSE
        private set
    private var lastTouch = 0L

    /** Old cards only: how wide. The camera's side is [SIDE]. */
    internal val cardW: Int get() = CARD_W.dp

    private var tripShown = false
    private var listening = false
    private var locationAsked = false
    /** It left the stack, and what belonged to that visit is still on the screen ([onOff]). */
    private var chromeDue = false

    // the look this screen was built with: a sunset under "auto", or a change in Settings, builds it again (until [recolours] is true)
    private var builtDark = true
    private var builtAccent = 0

    // ------------------------------------------------------------------------------------------ what the parts share
    /** A search or a place card lies over the column, or not: the column steps aside, and with the car moving the strip takes the speed (section 5). Browse says so. */
    internal fun cardOverColumn(on: Boolean) {
        drive.coveredByCard(on)
        Chrome.of(shell)?.speedCardHidden = on
        syncFace()
    }

    /** The whole of [points] in what the cards and the chrome leave of the map. */
    internal fun overview(points: List<LatLon>) = map.overview(points, OVERVIEW_LEFT, OVERVIEW_TOP, OVERVIEW_RIGHT, OVERVIEW_BOTTOM)

    /** The trip has just ended by End: with the car moving, `drive-free` now and not 10 s after that touch. */
    internal fun freeNow() {
        if (DriveState.moving) lastTouch = 0L
        syncFace()
    }

    internal fun toast(s: String, outlives: Boolean = false) = Snack.show(this, s, outlives = outlives)

    /** Read NavSession again, everywhere: it changed, or the panel is back. */
    internal fun syncNav() {
        val active = NavSession.state != NavSession.State.IDLE
        if (active != tripShown) {
            tripShown = active
            for (p in parts) p.tripChanged(active)
        }
        for (i in parts.indices.reversed()) parts[i].sync() // (browse, column, tools: a card comes off before what takes its place)
        syncFace()
    }

    // ---- Moving, no trip, no card, 10 s after the last touch: drive-free. A touch brings browse back for 10 s
    private fun touched() {
        lastTouch = SystemClock.uptimeMillis()
        if (listening) {
            syncFace()
            tools.keepUp()
        }
    }

    private val faceCheck = Runnable { syncFace() }

    private fun syncFace() {
        root.removeCallbacks(faceCheck)
        val idle = SystemClock.uptimeMillis() - lastTouch
        val f = when {
            NavSession.state != NavSession.State.IDLE -> Face.TRIP
            !DriveState.moving || browse.inUse -> Face.BROWSE
            idle >= FREE_AFTER_TOUCH_MS -> Face.DRIVE_FREE
            else -> {
                if (listening) root.postDelayed(faceCheck, FREE_AFTER_TOUCH_MS - idle)
                Face.BROWSE
            }
        }
        if (f == face) return
        face = f
        for (p in parts) p.onFace(f)
    }

    // ------------------------------------------------------------------------------------------ what comes in
    override fun onArgs(args: Bundle) {
        handle(args)
    }

    private fun handle(i: Bundle?) {
        if (BuildConfig.DEBUG && i?.containsKey("no3d") == true) { MapStyle.buildings3d = false; map.reloadStyle() }
        if (BuildConfig.DEBUG && i?.getBoolean("simulate", false) == true) browse.simulate = true
        if (BuildConfig.DEBUG) i?.getString("mock_loc")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.let { ll ->
            // emulator testing:  am start -n com.abdllh.aura/.nav.MapsActivity --es mock_loc "24.7136,46.6753" (forwarded: home/Entry.kt)
            if (ll.size >= 2) CarLocation.publish(Location("mock").apply {
                latitude = ll[0]; longitude = ll[1]; accuracy = 5f; time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (ll.size >= 3) bearing = ll[2].toFloat()
            })
        }
        browse.take(i)
    }

    override fun onPermission(code: Int, grants: IntArray) {
        if (CarLocation.hasPermission(this)) map.locationAllowed()
        else toast(getString(R.string.maps_location_needed))
    }

    // ------------------------------------------------------------------------------------------ lifecycle
    private val fixListener: (Location) -> Unit = { browse.onFix() }
    private val navListener: () -> Unit = { syncNav() }
    private val tripsListener: () -> Unit = { browse.syncAttempt() }
    private val driveListener: () -> Unit = { syncFace() }

    /** What the panel hears, on top or under a panel beside the column. */
    private fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        if (on) {
            lastTouch = SystemClock.uptimeMillis()
            map.onUserMoved = { tools.onUserMoved() }
            map.onLongPress = { p -> browse.dropPin(p) }
            // a shop, a mosque, a fuel station... tapped on the map: there, with the way to it
            map.onPoiTap = { name, kind, at -> browse.poiTapped(name, kind, at) }
            map.onReady = { syncNav(); CarLocation.last?.let { browse.onFix() }; tools.watchBearing() }
            CarLocation.addListener(fixListener)
            NavSession.addListener(navListener)
            Trips.addListener(tripsListener)
            DriveState.addListener(driveListener)
        } else {
            root.removeCallbacks(faceCheck)
            CarLocation.removeListener(fixListener)
            NavSession.removeListener(navListener)
            Trips.removeListener(tripsListener)
            DriveState.removeListener(driveListener)
            map.onUserMoved = null
            map.onLongPress = null
            map.onPoiTap = null
            map.onReady = null
        }
        for (p in parts) p.listen(on)
    }

    override fun onShow(args: Bundle?) {
        if (chromeDue) freshChrome()
        takeLook() // (a kept panel can be of another hour: before anything of this visit is on it)
        map.toMaps(SIDE.dp) // the car sits in the part of the map the panels leave free
        listen(true)
        tools.watchBearing()
        syncNav()
        browse.syncAttempt()
        if (!locationAsked && !CarLocation.hasPermission(this)) {
            locationAsked = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        handle(args)
    }

    override fun onHide() {
        listen(false)
        // (off the stack: gone from the map at once, as the home screen comes back over it)
        if (isOff) forgetVisit()
    }

    /** Under a panel beside the column: the trip's camera and the column go on, with nothing of a visit in it. */
    override fun onBeside(on: Boolean) {
        listen(on)
        if (on) {
            takeLook()
            map.toMaps(SIDE.dp)
            syncNav()
        }
    }

    /** Back or the Home key took it off the stack: what the visit chose. An activity forgot this at its destroy. */
    private fun forgetVisit() {
        browse.forgetVisit()
        tripShown = false
        chromeDue = true
    }

    override fun onOff() {
        forgetVisit()
        freshChrome()
    }

    /** The panel as it was built, at rest and not seen (the first show after this looks as if it had just been built). */
    private fun freshChrome() {
        chromeDue = false
        browse.fresh()
        tools.fresh()
    }

    override fun onDestroy() {
        root.removeCallbacks(faceCheck)
        browse.destroy()
        drive.destroy()
    }

    override fun onBack(): Boolean = browse.onBack()

    /**
     * Builds the panel again when its look is out of date: the map's style, the panels and the car are made in the
     * theme and accent of their time (a language is the whole window's: [HomeActivity.languageChanged]). Not while the driver
     * is choosing a place (the next minute's tick tries again), nor when the map says it is no time for it
     * ([MapController.lookMayChange]). A trip being driven goes on: [NavSession] is the app's, not this screen's.
     */
    override fun onLook() {
        if (shell.languageChanged()) return
        takeLook()
    }

    private fun takeLook() {
        if (recolours || (Theme.isDarkNow() == builtDark && Prefs.accent == builtAccent)) return
        if (browse.inUse || !map.lookMayChange) return
        rebuildLook()
    }

    /** The cards in the look of now, over the same map with the style of now. Nothing of the trip goes: it is all read again. */
    private fun rebuildLook() {
        Theme.refresh()
        builtDark = Palette.dark
        builtAccent = Prefs.accent
        shell.windowLook(navBar)
        map.lookChanged()
        columnLayer.removeAllViews()
        browseLayer.removeAllViews()
        toolsLayer.removeAllViews()
        tripShown = false
        for (p in parts) p.build()
        syncNav()
        browse.syncAttempt()
        tools.watchBearing()
    }

    // (last in the class: what is declared above is what this builds on)
    init {
        builtDark = Palette.dark
        builtAccent = Prefs.accent
        root.addView(columnLayer, MATCH, MATCH)
        root.addView(com.abdllh.aura.music.LyricChip(this), com.abdllh.aura.music.LyricChip.place())
        root.addView(browseLayer, MATCH, MATCH)
        root.addView(toolsLayer, MATCH, MATCH)
        for (p in parts) p.build()
    }

    internal companion object {
        /** The old cards' width. */
        const val CARD_W = 460
        /** What the camera leaves free at the left in both languages, for the column: map-*.json `camera`, so the car stands at x 701. */
        const val SIDE = 378
        /** What the chrome's strip and bottom row leave of the screen, from the top and from the bottom (SPEC.md section 1). */
        const val TOP_EDGE = 44
        const val BOTTOM_EDGE = 94
        /** Moving with no trip, this long after the last touch: `drive-free`. */
        const val FREE_AFTER_TOUCH_MS = 10_000L
        // map-*.json `camera.overview`, in px of the surface
        private const val OVERVIEW_LEFT = 410
        private const val OVERVIEW_TOP = 120
        private const val OVERVIEW_RIGHT = 44
        private const val OVERVIEW_BOTTOM = 110
    }
}

/**
 * A part of [MapsPanel]: a file and a layer of its own. A Context as the panel is, so label(), getString() and View(this) work on `this`. What the panel
 * tells every part is below; the rest is that part's own business, and the other parts call it by name.
 */
internal abstract class MapsPart(@JvmField val panel: MapsPanel) : ContextWrapper(panel) {
    protected val map: MapController get() = panel.map
    protected val root: FrameLayout get() = panel.root
    // (the window's own language: the process's default Locale can go back to the system's at a change of configuration, and a list then came out in English in an Arabic window)
    protected val arabic: Boolean get() = resources.configuration.locales[0].language == "ar"
    protected val rtl: Boolean get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    /** Its views, into its layer. Once; and again after the panel's rebuildLook emptied the layer, until the panel has `recolours` true. */
    abstract fun build()

    /** NavSession said something (every fix on a trip: only what changed may touch a view), or the panel is back. */
    open fun sync() {}

    /** A trip began or ended. Before the [sync] that comes with it. */
    open fun tripChanged(on: Boolean) {}

    /** The panel is on screen from now on, or not: what it hears of the outside world goes on and off here. */
    open fun listen(on: Boolean) {}

    /** Section 6.0's row changed ([MapsPanel.face]). */
    open fun onFace(face: MapsPanel.Face) {}

    // ---- the old look's, shared until both parts are rebuilt
    /**
     * The fill of the cards with text over the map (results, place, trip, arrival): solid, not the shared glass. At the
     * glass's 95 % the map's labels still showed through behind their text, clutter on a small screen in a moving car.
     */
    protected fun panelFill() = Shapes.rect(Palette.withAlpha(Palette.glass, 1f), Radius.XL, Palette.glassStroke)

    protected fun bottomCard() = flp(panel.cardW, WRAP, Gravity.BOTTOM or Gravity.START).apply {
        setMargins(Space.L.dp, 0, Space.L.dp, MapsPanel.BOTTOM_EDGE.dp)
        marginStart = Space.L.dp
    }

    protected fun roundIcon(icon: Int, size: Int, desc: Int, flipRtl: Boolean = false, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
        background = Shapes.ghostOval()
        isClickable = true
        contentDescription = getString(desc)
        pressScale(Motion.PRESS_ICON)
        setOnClickListener { onClick() }
        val side = (size * 0.45f).toInt().dp
        addView(ImageView(this@MapsPart).apply {
            setImageResource(icon)
            setColorFilter(Palette.text)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (flipRtl && rtl) scaleX = -1f
        }, flp(side, side, Gravity.CENTER))
    }
}

/** Visible and not on its way out. */
internal val View.isOn: Boolean get() = visibility == View.VISIBLE && getTag(R.id.aura_exiting) != true
