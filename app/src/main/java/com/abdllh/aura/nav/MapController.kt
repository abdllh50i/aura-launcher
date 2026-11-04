package com.abdllh.aura.nav

import android.content.ComponentCallbacks2
import android.graphics.Rect
import android.location.Location
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.abdllh.aura.home.HomeActivity
import com.abdllh.aura.ui.MapBackdropView
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.util.Prefs

/**
 * The one map of the one window (docs/DEV.md, "One engine"): owns the [AuraMap] in [com.abdllh.aura.home.MapHost], from the
 * window's start to its end, and is the only one that tells it anything. It alone listens to [NavSession] and [CarLocation]
 * for the map; a panel keeps its chrome (signs, cards, speed) and says what it wants of the map here, through calls of the
 * map's own names. Until 2.0 the home screen and Maps each did this with their own copy of the logic, and the copies had drifted.
 *
 * The map's states (home-shell.md 5.3): LIVE with the home screen or Maps showing it, FROZEN under an opaque panel, STOPPED with the
 * window, ABSENT when [MapGuard] refused it (no MapView at all, the home screen shows its picture; Maps builds one when asked, it is
 * the driver's own request). A panel never starts, stops, moves or hides it. Main thread.
 */
class MapController(private val shell: HomeActivity) {
    private enum class Front { NONE, HOME, MAPS }

    var map: AuraMap? = null
        private set
    /** What [MapGuard] said at this window's start; false: ABSENT unless Maps asks. */
    val allowed = MapGuard.homeMapAllowed(shell)

    private val host get() = shell.root.mapHost
    private var front = Front.NONE
    private var homeRoom: Rect? = null
    private var homeW = 0
    private var homeH = 0
    private var mapsSide = 0
    private var startedNow = false
    private var resumedNow = false
    private var destroyed = false
    /** Asked for while the window was stopped: built at its start (the mark of a start only a frame or a stop clears). */
    private var wanted = false
    private var wantedOnRequest = false
    private var drawnNow = false
    private var builtAt = 0L
    /** Whether a panel on top lets the map be seen where it may not have drawn: the home screen's card only once its hole is open. */
    private var homeOpen = false
    // the look of the style on the map
    private var styleDark = Palette.dark
    private var styleAccent = Prefs.accent

    /** What a panel shows on the map when no trip is on it: the place chosen, the route to it. */
    private var previewRoute: List<LatLon>? = null
    private var previewDest: LatLon? = null
    /** A trip is on the map. */
    private var tripShown = false
    /** The turn whose arrow is on the map (the route and the step's index). */
    private var turnShown: Pair<Route, Int>? = null

    private val fixListener: (Location) -> Unit = { onFix(it) }
    private val navListener: () -> Unit = { sync() }
    private val onCovered: (Boolean) -> Unit = { c ->
        map?.setFrozen(c)
        syncCover()
    }

    // ---- what a panel hears from the map, while it is on top (a panel sets them when it shows, and clears them when it hides)
    var onUserMoved: (() -> Unit)? = null
        set(v) { field = v; map?.onUserMoved = v }
    var onLongPress: ((LatLon) -> Unit)? = null
        set(v) { field = v; map?.onLongPress = v }
    var onPoiTap: ((name: String, kind: String, at: LatLon) -> Unit)? = null
        set(v) { field = v; map?.onPoiTap = v }
    /** Its style has come (also again after [lookChanged]). */
    var onReady: (() -> Unit)? = null
    /** It has drawn (the home screen's card opens then): at once when it has already. */
    private var onDrawn: (() -> Unit)? = null

    init {
        host.onCovered = onCovered
        if (!allowed) note = MapGuard.whyNot(shell)
    }

    // ------------------------------------------------------------------------------------------ the window's lifecycle
    fun started() {
        startedNow = true
        CarLocation.acquire(shell, HOLDER)
        CarLocation.addListener(fixListener)
        NavSession.addListener(navListener)
        val m = map
        if (m == null) {
            if (wanted) build()
            return
        }
        drawnNow = false
        m.onStart()
        syncCover()
        sync()
    }

    fun resumed() {
        resumedNow = true
        map?.onResume()
    }

    fun paused() {
        resumedNow = false
        map?.onPause()
    }

    fun stopped() {
        startedNow = false
        CarLocation.removeListener(fixListener)
        CarLocation.release(HOLDER)
        NavSession.removeListener(navListener)
        map?.onStop()
    }

    fun destroyed() {
        destroyed = true
        host.onCovered = null
        map?.onDestroy()
        map = null
    }

    fun trim(level: Int) {
        // the engine drops its tile caches
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) map?.onLowMemory()
    }

    /** Location has been allowed: the holder is there from the window's start, with nothing to hold until now. */
    fun locationAllowed() {
        if (startedNow) CarLocation.acquire(shell, HOLDER)
    }

    // ------------------------------------------------------------------------------------------ the engine
    /** The home screen's turn to want the map (it waits for the entrance): not if [MapGuard] refused it. */
    fun startForHome() = build(false)

    /** The driver asks for Maps: whatever [MapGuard] said (a crash then is a crash asked for, and the home screen still has no map). */
    fun startForMaps() = build(true)

    private fun build(onRequest: Boolean = wantedOnRequest) {
        if (map != null || destroyed || (!allowed && !onRequest)) return
        if (!startedNow) {
            wanted = true
            wantedOnRequest = wantedOnRequest || onRequest
            return
        }
        wanted = false
        val t0 = SystemClock.uptimeMillis()
        val m = try {
            AuraMap(shell, interactive = true, texture = false, guarded = true)
        } catch (t: Throwable) {
            // (no map, a land colour where it would be, and everything else as it was: a map must not cost the home screen)
            Log.w(TAG, "the map could not be built: $t")
            note = "could not be built: $t"
            syncCover()
            return
        }
        builtAt = SystemClock.uptimeMillis()
        Log.i(TAG, "built in ${builtAt - t0} ms, ${if (allowed) "for the home screen" else "asked for in Maps"}")
        map = m
        m.onUserMoved = onUserMoved
        m.onLongPress = onLongPress
        m.onPoiTap = onPoiTap
        m.setFrozen(host.covered)
        m.onReady = {
            sync()
            CarLocation.last?.let { onFix(it) }
            onReady?.invoke()
        }
        m.onDrawn = { drawn() }
        applyFront(m)
        // (under what covers it, under the panels: the slot is never empty again until the window ends)
        host.addView(m.view, 0)
        m.onCreate(null)
        m.onStart()
        if (resumedNow) m.onResume()
        syncCover()
    }

    private fun drawn() {
        if (!drawnNow && builtAt != 0L) {
            note = "drawn ${SystemClock.uptimeMillis() - builtAt} ms after it was built"
            Log.i(TAG, note)
            builtAt = 0L
        }
        drawnNow = true
        syncCover()
        onDrawn?.invoke()
    }

    /**
     * What covers the map from a start until it has drawn (home-shell.md 5.3): the land's colour, so a surface that is not there yet
     * never shows as black. Not where nothing shows it: under an opaque panel, or the home screen's wall with its card not open, where its
     * own picture is ([com.abdllh.aura.home.MapPanel]).
     */
    private fun syncCover() {
        val c = host.cover
        val seen = when (front) {
            Front.MAPS -> true
            Front.HOME -> homeOpen
            Front.NONE -> false
        } && !host.covered
        if (!drawnNow && seen) {
            c.animate().cancel()
            c.alpha = 1f
            c.visibility = View.VISIBLE
        } else if (c.visibility == View.VISIBLE) {
            if (drawnNow && seen) c.fadeTo(0f, Motion.SHORT) { if (drawnNow) c.visibility = View.GONE }
            else c.visibility = View.GONE
        }
    }

    /** [f] when the map has drawn, now if it has: once for each time it comes to draw again after a start or a new look. */
    fun whenDrawn(f: () -> Unit) {
        onDrawn = f
        if (drawnNow) f()
    }

    /** The home screen's card is a hole in its wall from now on, or not ([syncCover]). */
    fun homeCard(open: Boolean) {
        homeOpen = open
        syncCover()
    }

    /** The look may have changed (a panel took it in, when it was a good time for it): the same map, with the style of now. */
    fun lookChanged() {
        if (styleDark == Palette.dark && styleAccent == Prefs.accent) return
        reloadStyle()
    }

    /** The style again, whatever its look (a debug hook that changes how it is made). */
    fun reloadStyle() {
        styleDark = Palette.dark
        styleAccent = Prefs.accent
        host.cover.setBackgroundColor(Palette.mapLand)
        val m = map ?: return
        if (front == Front.HOME) m.pictureFile = MapBackdropView.pictureFile(shell)
        drawnNow = false
        m.reloadStyle()
        syncCover()
    }

    // ------------------------------------------------------------------------------------------ who has it on top
    /** The home screen is on top: no gestures, the car in its card, north up or on its trip. */
    fun toHome() {
        front = Front.HOME
        map?.let { applyFront(it) }
        syncCover()
    }

    /** Where the home screen's card is, as [AuraMap.setRoom] counts: it comes from a layout pass, also while another panel is on top. */
    fun setHomeRoom(card: Rect, w: Int, h: Int) {
        if (card == homeRoom && w == homeW && h == homeH) return
        homeRoom = Rect(card)
        homeW = w
        homeH = h
        if (front == Front.HOME) map?.let { applyFront(it) }
    }

    /** Maps is on top: gestures, and the car in what its panels leave free at the start side, [side] wide. */
    fun toMaps(side: Int) {
        front = Front.MAPS
        mapsSide = side
        val m = map
        if (m == null) startForMaps() else applyFront(m)
        syncCover()
    }

    private fun applyFront(m: AuraMap) {
        when (front) {
            Front.NONE -> return
            Front.HOME -> {
                m.setInteractive(false)
                m.zoomBack()
                m.pictureFile = MapBackdropView.pictureFile(shell)
                val c = homeRoom
                m.pictureArea = c
                if (c != null) m.setRoom(c.left, c.top, homeW - c.right, homeH - c.bottom, HOME_NAV_TOP)
            }
            Front.MAPS -> {
                m.setInteractive(true)
                m.pictureFile = null
                val rtl = shell.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
                m.setRoom(if (rtl) 0 else mapsSide, 0, if (rtl) mapsSide else 0, 0, MAPS_NAV_TOP)
            }
        }
        sync()
    }

    // ------------------------------------------------------------------------------------------ what is on it
    private fun onFix(l: Location) {
        if (NavSession.state != NavSession.State.NAVIGATING) { // (navigation moves the car itself, on the route)
            val speed = if (l.hasSpeed()) l.speed * 3.6 else 0.0
            map?.setCar(LatLon(l.latitude, l.longitude), if (l.hasBearing()) l.bearing.toDouble() else 0.0, speed)
        }
    }

    /**
     * The map as [NavSession] says. On Maps a trip stays on it through the arrival's card, and its camera may be the driver's (he
     * dragged it, or looks at the whole route: only [setCamera] takes that back); on the home screen, which has no such card or
     * finger, the trip is over at the arrival and the camera always follows.
     */
    private fun sync() {
        val m = map ?: return
        val st = NavSession.state
        val navigating = st == NavSession.State.NAVIGATING
        val onMaps = front == Front.MAPS
        m.setFps(if (navigating && onMaps) 60 else 30) // a turning, tilted map needs the frames; the home screen's card does not
        if (!navigating && turnShown != null) { turnShown = null; m.setTurn(null) }
        if (if (onMaps) st == NavSession.State.IDLE else !navigating) {
            if (tripShown) { // the trip has just ended (Done, End, or by itself after arriving): back to browsing
                tripShown = false
                m.setRoute(previewRoute)
                m.setDestination(previewDest)
                m.setCamera(AuraMap.Camera.FOLLOW)
            } else if (!onMaps && m.camera != AuraMap.Camera.FOLLOW) m.setCamera(AuraMap.Camera.FOLLOW)
            return
        }
        tripShown = true
        val r = NavSession.route ?: return
        val p = NavSession.progress
        m.setDestination(NavSession.destination?.pos)
        val c = m.camera
        if (c != AuraMap.Camera.NAVIGATE && (!onMaps || (c != AuraMap.Camera.FREE && c != AuraMap.Camera.OVERVIEW))) m.setCamera(AuraMap.Camera.NAVIGATE)
        // only the part still ahead shows, from under the arrow on: the map cuts the line at the car as it draws it
        m.setRouteAhead(r.line)
        if (p == null) return
        m.setCar(p.position, p.bearing, p.speedKmh.toDouble())
        val step = r.steps[p.next]
        // the turn ahead, as an arrow on the route itself (not the start or the arrival, nor a turn still far off);
        // through a roundabout it runs on to the exit taken, so its head shows the way out, not the ring
        val turn = if (step.type == "arrive" || step.type == "depart" || p.toNext > TURN_ARROW_FROM_M || NavSession.rerouting) null else r to p.next
        if (turn != turnShown) {
            turnShown = turn
            if (turn == null) m.setTurn(null)
            else m.setTurn(r.line, step.at, AuraMap.turnAfter(step))
        }
    }

    // ---- a panel's own: what it shows while no trip is on the map, and what the camera does
    /** A route shown whole (a preview); while a trip is on the map it waits for the trip's end. */
    fun setRoute(points: List<LatLon>?) {
        previewRoute = points
        if (!tripShown) map?.setRoute(points)
    }

    /** The place shown; while a trip is on the map it waits for the trip's end. */
    fun setDestination(p: LatLon?) {
        previewDest = p
        if (!tripShown) map?.setDestination(p)
    }

    fun setCamera(c: AuraMap.Camera) { map?.setCamera(c) }
    val camera: AuraMap.Camera get() = map?.camera ?: AuraMap.Camera.FOLLOW
    fun overview(points: List<LatLon>, padStart: Int, padTop: Int, padEnd: Int, padBottom: Int) { map?.overview(points, padStart, padTop, padEnd, padBottom) }
    fun zoomBy(d: Double) { map?.zoomBy(d) }
    fun northUp() { map?.northUp() }

    /** Where the map looks. */
    val target: LatLon? get() = map?.map?.cameraPosition?.target?.let { LatLon(it.latitude, it.longitude) }

    companion object {
        private const val TAG = "AuraMap"
        private const val HOLDER = "shell"
        /** The player covers the lower part of the home screen's card: the car stays in the middle of what is visible. */
        private const val HOME_NAV_TOP = 0.08
        private const val MAPS_NAV_TOP = 0.42
        /** The turn's arrow is on the map from this far before it (its size is the map's: [AuraMap.setTurn]). */
        private const val TURN_ARROW_FROM_M = 900.0

        /** What the map did at this window's start (for Settings → About): why it is not there, or when it drew. */
        @Volatile var note = ""
            private set
    }
}
