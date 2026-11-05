package com.abdllh.aura.nav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.home.HomeActivity
import com.abdllh.aura.home.Panel
import com.abdllh.aura.home.Screens
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Menu
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Theme
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
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp
import java.util.Locale
import kotlin.math.abs

/**
 * Aura's own maps: search a place, see the route, drive with turn-by-turn guidance. Glass panels, sized for a car screen, over the
 * one map under the window: this panel is those panels and what they do, the map and what is on it are [MapController]'s, and it
 * goes on being there when this panel is not. Every panel comes in and goes ([reveal]) instead of popping, and only
 * when its state changes: the panels are synced on every fix. Kept once built, and built ahead of time ([PanelStack.prepare]); what
 * belonged to a visit (a search, a place, a place being chosen) goes when it leaves the stack, as it went with the activity.
 *  args: "search" = open the search; "go" = "home" | "work" to drive there at once; "simulate" (debug builds).
 */
class MapsPanel(shell: HomeActivity) : Panel(shell, Screens.MAPS) {
    private val map: MapController get() = shell.map
    override val root = FrameLayout(this)
    override val keep: Boolean get() = true
    // (the map's slot does not follow the keyboard, and the field is at the top, above it: this window never pans. See DEV.md, "One window")
    override val softInput: Int get() = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
    /** The map's own controls at the end side, faded away together while the keyboard is up. */
    private lateinit var controls: FrameLayout
    private lateinit var searchCard: LinearLayout
    private lateinit var input: EditText
    private lateinit var clearBtn: View
    private lateinit var spinner: ProgressBar
    private lateinit var results: LinearLayout
    private lateinit var resultsCard: ScrollView
    private lateinit var placeCard: LinearLayout
    private lateinit var placeName: AText
    private lateinit var placeDetail: AText
    private lateinit var placeEta: AText
    private lateinit var placeRoute: AText
    private lateinit var goBtn: ABtn
    private lateinit var banner: GuidanceBanner
    private lateinit var tripBar: LinearLayout
    private lateinit var tripEta: AText
    private lateinit var tripLeft: AText
    private lateinit var voiceIcon: ImageView
    private lateinit var speedBubble: LinearLayout
    private lateinit var speedText: AText
    private lateinit var speedUnit: AText
    private lateinit var speedSign: RoadHud
    private lateinit var cameraMark: CameraMark
    private lateinit var recenterHolder: FrameLayout
    private lateinit var recenterBtn: LinearLayout
    private lateinit var recenterText: AText
    private lateinit var compassIcon: ImageView
    private lateinit var zoom: LinearLayout
    private lateinit var arrivedCard: FrameLayout
    private lateinit var arrivedCaption: AText
    private lateinit var arrivedName: AText
    private lateinit var arrivedBar: View

    private var selected: Place? = null
    private var preview: Route? = null
    private var routing = false
    private val arabic get() = Locale.getDefault().language == "ar"
    private val rtl get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    private var searchRunnable: Runnable? = null
    private var tripShown = false

    // the look this screen was built with: a sunset under "auto", or a change in Settings, builds it again
    private var builtDark = true
    private var builtAccent = 0

    override fun onArgs(args: Bundle) {
        handle(args)
    }

    private fun handle(i: Bundle?) {
        if (BuildConfig.DEBUG && i?.containsKey("no3d") == true) { MapStyle.buildings3d = false; map.reloadStyle() }
        if (BuildConfig.DEBUG && i?.getBoolean("simulate", false) == true) simulate = true
        if (BuildConfig.DEBUG) i?.getString("mock_loc")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.let { ll ->
            // emulator testing:  am start -n com.abdllh.aura/.nav.MapsActivity --es mock_loc "24.7136,46.6753" (forwarded: home/Entry.kt)
            if (ll.size >= 2) CarLocation.publish(Location("mock").apply {
                latitude = ll[0]; longitude = ll[1]; accuracy = 5f; time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (ll.size >= 3) bearing = ll[2].toFloat()
            })
        }
        when (val which = i?.getString("go")) {
            "home", "work" -> {
                val saved = Trips.saved(which)
                when {
                    saved != null -> driveTo(saved)
                    Trips.legacy(which).isNotBlank() -> { // an address typed in 1.0: find it once and keep the place
                        toast(getString(R.string.maps_searching))
                        Trips.findLegacy(which, arabic) { p ->
                            if (isGone || isOff) return@findLegacy
                            if (p == null) { toast(getString(R.string.maps_no_results)); openSearch(); return@findLegacy }
                            driveTo(p)
                        }
                    }
                    else -> openSearch()
                }
            }
        }
        // a place to show with its route and Go, the driver's to start: what the phone sent when no card can ask him,
        // or when no route was found for it ([com.abdllh.aura.link.PlaceInbox])
        if (i != null && i.containsKey(MapsActivity.EXTRA_LAT) && i.containsKey(MapsActivity.EXTRA_LON)) {
            val pos = LatLon(i.getDouble(MapsActivity.EXTRA_LAT, 0.0), i.getDouble(MapsActivity.EXTRA_LON, 0.0))
            notKept = if (i.getBoolean(MapsActivity.EXTRA_KEEP, true)) null else pos
            select(Place(i.getString(MapsActivity.EXTRA_NAME).orEmpty(), i.getString(MapsActivity.EXTRA_DETAIL).orEmpty(), pos))
        }
        if (i?.getBoolean("search", false) == true) openSearch()
        // a search asked for by name (the assistant's "where is the nearest petrol station", and the emulator's tests)
        i?.getString("q")?.takeIf { it.isNotBlank() }?.let { q -> openSearch(); input.setText(q); runSearch(q); hideKeyboard() }
        i?.getString("pick")?.let { which -> // Home / Work chosen on the map, then back to the home screen
            pickFor = which
            finishAfterPick = true
            showPickHint()
        }
    }

    private var simulate = false
    private var pickFor: String? = null
    private var finishAfterPick = false
    /** The place sent here that must not go to the recent places when Go starts it ([EXTRA_KEEP]). */
    private var notKept: LatLon? = null

    /** Choosing Home / Work: the search field says how, for as long as it takes. */
    private fun showPickHint() {
        val which = pickFor ?: return
        input.setHint(if (which == "work") R.string.maps_pick_work_short else R.string.maps_pick_home_short)
        toast(getString(if (which == "work") R.string.maps_pick_work else R.string.maps_pick_home))
    }

    /** Long press on the map: a pin there, named after what is at that spot, ready to drive to or keep as Home / Work. */
    private fun dropPin(p: LatLon) {
        if (NavSession.state != NavSession.State.IDLE) return
        select(Place(getString(R.string.maps_dropped_pin), "", p))
        Places.reverse(p, arabic) { found ->
            // (the same spot, not the same object: the name replaces the selection, and the route stays the pin's)
            if (isGone || selected?.pos != p || found == null) return@reverse
            val named = Place(found.name, found.detail, p)
            selected = named
            placeName.setIfChanged(named.name)
            placeDetail.setIfChanged(named.detail)
            placeDetail.visibility = if (named.detail.isBlank()) View.GONE else View.VISIBLE
            input.setText(named.name)
        }
    }

    /** Saved as Home / Work; when Maps was opened just to choose it, back to where the user came from. */
    private fun savePlace(which: String) {
        val p = selected ?: return
        if (which == "home") Places.home = p else Places.work = p
        toast(getString(R.string.maps_saved), outlives = finishAfterPick)
        pickFor = null
        input.setHint(R.string.home_where_to)
        if (finishAfterPick) { finishAfterPick = false; finish() }
    }

    override fun onPermission(code: Int, grants: IntArray) {
        if (CarLocation.hasPermission(this)) map.locationAllowed()
        else toast(getString(R.string.maps_location_needed))
    }

    // ------------------------------------------------------------------------------------------ lifecycle
    private var locationAsked = false
    /** It left the stack, and what belonged to that visit is still on the screen ([onOff]). */
    private var chromeDue = false

    override fun onShow(args: Bundle?) {
        if (chromeDue) freshChrome()
        takeLook() // (a kept panel can be of another hour: before anything of this visit is on it)
        map.toMaps(panelWidth() + 32.dp) // the car sits in the part of the map the panels leave free
        map.onUserMoved = { onUserMoved() }
        map.onLongPress = { p -> dropPin(p) }
        // a shop, a mosque, a fuel station... tapped on the map: there, with the way to it
        map.onPoiTap = { name, kind, at -> if (NavSession.state == NavSession.State.IDLE) select(Place(name, Poi.label(this, kind), at)) }
        map.onReady = { syncNav(); CarLocation.last?.let { onFix(it) }; watchBearing() }
        watchBearing()
        CarLocation.addListener(fixListener)
        NavSession.addListener(navListener)
        Trips.addListener(tripsListener)
        syncNav()
        syncAttempt()
        if (!locationAsked && !CarLocation.hasPermission(this)) {
            locationAsked = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        handle(args)
    }

    override fun onHide() {
        root.removeCallbacks(autoRecenter)
        root.removeCallbacks(arrivedTick)
        arrivalShown = 0L // (back on the screen, the arrival line starts stepping again from where the hold is)
        CarLocation.removeListener(fixListener)
        NavSession.removeListener(navListener)
        Trips.removeListener(tripsListener)
        map.onUserMoved = null
        map.onLongPress = null
        map.onPoiTap = null
        map.onReady = null
        // (off the stack: gone from the map at once, as the home screen comes back over it)
        if (isOff) forgetVisit()
    }

    /** Back or the Home key took it off the stack: what the visit chose. An activity forgot this at its destroy. */
    private fun forgetVisit() {
        searchRunnable?.let { root.removeCallbacks(it) }
        root.removeCallbacks(firstOverview)
        Places.cancelSearch()
        selected = null
        preview = null
        routing = false
        following = null
        pickFor = null
        finishAfterPick = false
        notKept = null
        simulate = false
        tripShown = false
        resetRouteRetry()
        map.setRoute(null)
        map.setDestination(null)
        chromeDue = true
    }

    override fun onOff() {
        forgetVisit()
        freshChrome()
    }

    /** The panel as it was built, at rest and not seen (the first show after this looks as if it had just been built). */
    private fun freshChrome() {
        chromeDue = false
        input.clearFocus()
        input.setHint(R.string.home_where_to)
        input.setText("")
        spinner.visibility = View.GONE
        results.alpha = 1f
        results.removeAllViews()
        resultsCard.reveal(false, -8f, animate = false)
        placeCard.reveal(false, 16f, animate = false)
        recenterBtn.reveal(false, 12f, animate = false)
        recenterHolder.translationY = 0f
        recenterLift = 0f
        syncClear(animate = false)
        controls.animate().cancel()
        controls.alpha = 1f
        controls.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        searchRunnable?.let { root.removeCallbacks(it) }
        root.removeCallbacks(firstOverview)
        Places.cancelSearch()
    }

    override fun onBack(): Boolean = when {
        resultsCard.isOn -> { closeSearch(); true }
        selected != null && NavSession.state == NavSession.State.IDLE -> { clearPlace(); true }
        else -> false
    }

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
        if (Theme.isDarkNow() == builtDark && Prefs.accent == builtAccent) return
        if (resultsCard.isOn || selected != null || pickFor != null || input.hasFocus() || routing || !map.lookMayChange) return
        rebuildLook()
    }

    /** The cards in the look of now, over the same map with the style of now. Nothing of the trip goes: it is all read again. */
    private fun rebuildLook() {
        Theme.refresh()
        builtDark = Palette.dark
        builtAccent = Prefs.accent
        shell.windowLook(navBar)
        map.lookChanged()
        root.removeAllViews()
        clusterNav = null
        tripShown = false
        overShown = false
        arrivalShown = 0L
        recenterLift = 0f
        compassAt = Float.NaN
        buildUi()
        syncNav()
        syncAttempt()
        watchBearing()
    }

    // ------------------------------------------------------------------------------------------ location / nav
    private val fixListener: (Location) -> Unit = { onFix(it) }

    private fun onFix(l: Location) {
        // the preview route once a fix is there; a failed one is retried with growing pauses, not on every fix
        // (not for a place whose trip is being started from elsewhere: [Trips] is finding its route)
        if (selected != null && preview == null && !routing && following == null && NavSession.state == NavSession.State.IDLE &&
            SystemClock.elapsedRealtime() >= routeRetryAt) requestRoute()
        // OSRM's route came after BRouter's was shown: the card and the line take it (nothing is being driven yet)
        preview?.better?.let { b ->
            preview = b
            showPreview(b)
            map.setRoute(b.line.points)
        }
    }

    private var routeFails = 0
    private var routeRetryAt = 0L

    private fun routeFailed() {
        routeFails++
        routeRetryAt = SystemClock.elapsedRealtime() + (5_000L shl (routeFails - 1).coerceAtMost(4)) // 5 s ... 80 s
        routeStatus(getString(R.string.maps_route_failed), failed = true)
    }

    private fun resetRouteRetry() {
        routeFails = 0
        routeRetryAt = 0L
    }

    private val navListener: () -> Unit = { syncNav() }
    private val tripsListener: () -> Unit = { syncAttempt() }

    /** The place of a trip being started from elsewhere (the phone, the voice), while this screen shows it. */
    private var following: Place? = null

    /**
     * A trip being started from elsewhere ([Trips]): while it has no route yet its place is on the card as if it were
     * chosen here, with what is going on; closing the card drops it. This screen's own [driveTo] shows itself.
     */
    private fun syncAttempt() {
        if (!::placeCard.isInitialized) return
        val a = Trips.attempt?.takeIf { it.source != Trips.SCREEN }
        if (a == null) {
            val p = following ?: return
            following = null
            routing = false
            when {
                NavSession.state != NavSession.State.IDLE -> afterStart()
                // (it did not start: a failure leaves the place here by its own way, a cancel takes it away)
                selected?.pos == p.pos -> clearPlace()
            }
            return
        }
        if (NavSession.state == NavSession.State.NAVIGATING) return // (a trip being replaced: the trip's panels stay)
        if (following?.pos != a.place.pos) {
            following = a.place
            routing = true
            selected = a.place
            preview = null
            showPlace(a.place)
        }
        routeStatus(getString(if (a.phase == Trips.Phase.WAITING_FIX) R.string.maps_waiting_gps else R.string.maps_routing))
    }

    /** Shows the navigation UI for NavSession's state. */
    private fun syncNav() {
        if (!::banner.isInitialized) return
        val st = NavSession.state
        val navigating = st == NavSession.State.NAVIGATING
        val active = st != NavSession.State.IDLE
        // (a trip started from elsewhere while this screen was away: nothing chosen earlier stays behind it)
        if (navigating && selected != null && !routing && following == null) { selected = null; preview = null; map.setRoute(null); map.setDestination(null) }
        searchCard.reveal(!active, -12f)
        // (a trip started from outside while the search was open: its keyboard-time fade must not hide the speed)
        if (active) { resultsCard.reveal(false, -8f); placeCard.reveal(false, 16f); showControls(true) }
        banner.reveal(navigating, -16f)
        tripBar.reveal(navigating, 16f, delay = 60)
        speedBubble.reveal(navigating, 0f, scaleFrom = 0.9f)
        arrivedCard.reveal(st == NavSession.State.ARRIVED, 16f, delay = 60)
        if (st == NavSession.State.ARRIVED) showArrival() else root.removeCallbacks(arrivedTick)
        placeEndCluster(navigating)
        syncZoom()
        val voice = NavSession.voiceOn && !NavSession.voiceMissing
        voiceIcon.setImageResource(if (voice) R.drawable.ic_volume else R.drawable.ic_volume_off)
        if (active && NavSession.voiceOn && NavSession.voiceMissing && !voiceHintShown) {
            voiceHintShown = true
            toast(getString(R.string.maps_voice_missing))
        }
        if (!active) {
            if (tripShown) { // the trip has just ended (Done, End, or by itself after arriving): back to browsing
                tripShown = false
                root.removeCallbacks(autoRecenter)
                if (selected == null) input.setText("")
                recenterBtn.reveal(false, 12f, scaleFrom = 0.92f)
            }
            return
        }
        if (!tripShown) { tripShown = true; banner.reset(); hideKeyboard() } // (once: it is a call to the keyboard's service)
        val p = NavSession.progress ?: return
        banner.update()
        // how long is left first (what the driver asks), then how far and when
        tripEta.setIfChanged(GuidanceBanner.styled(Units.duration(p.timeLeft, arabic), 0.5f, Palette.success))
        tripLeft.setIfChanged("${Units.distance(p.remaining, arabic)} · ${getString(R.string.maps_arrival)} ${Units.arrival(p.timeLeft, Prefs.clock24)}")
        speedText.setIfChanged("${p.speedKmh}")
        showOver(RoadWatch.state.over)
    }

    private var overShown = false

    /** Over the street's limit: the speed disc turns red and beats once, not only its digits. */
    private fun showOver(over: Boolean) {
        if (over == overShown) return
        overShown = over
        speedBubble.background = if (over) Shapes.oval(Palette.danger) else Shapes.oval(Palette.glass, Palette.glassStroke)
        val fg = if (over) Palette.onColor(Palette.danger) else Palette.text
        speedText.setTextColor(fg)
        speedUnit.setTextColor(if (over) Palette.withAlpha(fg, 0.8f) else Palette.text2)
        if (over) speedBubble.anim(Motion.XSHORT, Motion.STANDARD).scaleX(1.08f).scaleY(1.08f).withEndAction {
            speedBubble.anim(Motion.MEDIUM, Motion.EMPHASIZED).scaleX(1f).scaleY(1f).start()
        }.start()
    }

    private var arrivalShown = 0L

    /** The arrival card: where, and a line that runs out in the time left before the trip ends by itself. */
    private fun showArrival() {
        val at = NavSession.arrivedAt
        if (at == arrivalShown) return
        arrivalShown = at
        // (a pin whose address was not found has no name worth announcing: "arrived at Dropped pin")
        val name = NavSession.destination?.name?.takeIf { it.isNotBlank() && it != getString(R.string.maps_dropped_pin) }
        arrivedCaption.setText(if (name == null) R.string.maps_arrived else R.string.maps_arrived_at)
        arrivedName.text = name.orEmpty()
        arrivedName.visibility = if (name == null) View.GONE else View.VISIBLE
        root.removeCallbacks(arrivedTick)
        arrivedBar.animate().cancel()
        arrivedBar.scaleX = arrivedLeft()
        arrivedBar.post { arrivedBar.pivotX = if (rtl) arrivedBar.width.toFloat() else 0f } // (it runs out toward the start)
        root.postDelayed(arrivedTick, ARRIVED_STEP_MS)
    }

    /** The share of the arrival's hold still left. */
    private fun arrivedLeft(): Float =
        (NavSession.ARRIVED_HOLD_MS - (SystemClock.elapsedRealtime() - NavSession.arrivedAt)).coerceAtLeast(0L) / NavSession.ARRIVED_HOLD_MS.toFloat()

    /**
     * The arrival line moves on a step a second, each step a short glide: one animation over the whole hold drew a
     * frame at every vsync for half a minute, on top of the map, for a line moving a few pixels a second.
     */
    private val arrivedTick = object : Runnable {
        override fun run() {
            val left = arrivedLeft()
            arrivedBar.anim(Motion.SHORT, LinearInterpolator()).scaleX(left).start()
            if (left > 0f && NavSession.state == NavSession.State.ARRIVED) root.postDelayed(this, ARRIVED_STEP_MS)
        }
    }

    private var clusterNav: Boolean? = null

    /**
     * The camera button and the limit sign: while a route is driven the speed is at the screen's end edge and they
     * stand beside and above it; without one the camera button takes the speed's place. Moved by translation (the
     * layout is the driving one), so they glide instead of jumping by their margins.
     */
    private fun placeEndCluster(navigating: Boolean) {
        if (clusterNav == navigating) return
        val animate = clusterNav != null && root.isLaidOut
        clusterNav = navigating
        fun move(v: View, dx: Int, dy: Int) {
            val x = if (navigating) 0f else (if (rtl) -dx else dx).dp.toFloat()
            val y = if (navigating) 0f else dy.dp.toFloat()
            if (animate) v.anim(Motion.MEDIUM, Motion.STANDARD).translationX(x).translationY(y).start()
            else { v.translationX = x; v.translationY = y }
        }
        move(cameraMark, 106, 13)
        move(speedSign, 15, 20)
    }

    // ------------------------------------------------------------------------------------------ camera
    /** The driver moved the map: a way back, and while driving the map goes back to the car by itself after a while. */
    private fun onUserMoved() {
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

    private fun recenter() {
        root.removeCallbacks(autoRecenter)
        recenterBtn.reveal(false, 12f, scaleFrom = 0.92f)
        map.setCamera(if (NavSession.state == NavSession.State.NAVIGATING) AuraMap.Camera.NAVIGATE else AuraMap.Camera.FOLLOW)
        syncZoom()
    }

    private fun showRecenter() {
        recenterText.setIfChanged(getString(if (NavSession.state == NavSession.State.NAVIGATING) R.string.maps_recenter else R.string.maps_my_location))
        recenterBtn.reveal(true, 12f, scaleFrom = 0.92f)
    }

    /** + and − while the map is the driver's to move; while it follows the drive, pinching still zooms. */
    private fun syncZoom() {
        val nav = NavSession.state == NavSession.State.NAVIGATING
        zoom.reveal(!nav || map.camera == AuraMap.Camera.FREE || map.camera == AuraMap.Camera.OVERVIEW, 0f, scaleFrom = 0.9f)
    }

    private var bearingWatched = false
    private var compassAt = Float.NaN

    /** The compass shows where north is on the turned map (a transform, set only when it moved a degree or more). */
    private fun watchBearing() {
        val m = map.map?.map ?: return
        turnCompass(m.cameraPosition.bearing.toFloat())
        if (bearingWatched) return
        bearingWatched = true
        m.addOnCameraMoveListener { turnCompass(m.cameraPosition.bearing.toFloat()) }
        m.addOnCameraIdleListener { turnCompass(m.cameraPosition.bearing.toFloat()) }
    }

    private fun turnCompass(bearing: Float) {
        if (!shown) return // (the map goes on moving under the home screen: a change to a hidden view would still ask for a frame)
        if (!compassAt.isNaN() && abs(Geo.angleDiff(compassAt.toDouble(), bearing.toDouble())) < 1) return
        compassAt = bearing
        compassIcon.rotation = -bearing
        // north up already, browsing: the button has nothing to do, and says so by stepping back
        val north = abs(Geo.angleDiff(0.0, bearing.toDouble())) < 1 && NavSession.state != NavSession.State.NAVIGATING
        compassIcon.alpha = if (north) 0.55f else 1f
    }

    /** The recenter pill stands above whichever card is at the bottom (glides when that one comes or goes). */
    private var recenterLift = 0f

    private fun liftRecenter() {
        val card = listOf(tripBar, placeCard, arrivedCard).firstOrNull { it.isOn }
        val lift = if (card == null || card.height == 0) 0f else -(card.height + Space.M.dp).toFloat()
        if (lift == recenterLift) return
        recenterLift = lift
        if (recenterBtn.isOn) recenterHolder.anim(Motion.MEDIUM, Motion.STANDARD).translationY(lift).start()
        else recenterHolder.translationY = lift
    }

    // ------------------------------------------------------------------------------------------ actions
    private fun openSearch() {
        if (NavSession.state != NavSession.State.IDLE) return
        resultsCard.reveal(true, -8f)
        placeCard.reveal(false, 16f)
        showControls(false)
        input.requestFocus()
        input.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) }
        if (input.text.trim().length < 2) showSuggestions()
    }

    private fun closeSearch() {
        // a search still under way must not fill the list later (over Home / Work / recents)
        searchRunnable?.let { root.removeCallbacks(it) }
        Places.cancelSearch()
        searching(false)
        resultsCard.reveal(false, -8f)
        hideKeyboard()
        input.clearFocus()
        showControls(true)
        if (selected != null) placeCard.reveal(true, 16f, delay = 60)
    }

    /**
     * The map's buttons go while the keyboard is up: the screen shrinks to the strip above it, where they would
     * pile onto each other and onto the attribution; nothing in them is needed while typing.
     */
    private fun showControls(show: Boolean) {
        if (show) {
            if (controls.visibility == View.VISIBLE && controls.alpha == 1f) return
            controls.visibility = View.VISIBLE
            controls.fadeTo(1f, Motion.MEDIUM, Motion.EMPHASIZED)
        } else if (controls.visibility == View.VISIBLE) {
            controls.fadeTo(0f, Motion.XSHORT, Motion.EXIT) { if (controls.alpha == 0f) controls.visibility = View.INVISIBLE }
        }
    }

    private fun hideKeyboard() {
        try { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0) } catch (_: Throwable) { }
    }

    private fun select(p: Place) {
        selected = p
        preview = null
        showPlace(p)
        requestRoute()
    }

    /** [p] on the card and on the map, with no route yet. */
    private fun showPlace(p: Place) {
        resetRouteRetry()
        closeSearch()
        input.setText(p.name)
        placeName.setIfChanged(p.name)
        placeDetail.setIfChanged(p.detail)
        placeDetail.visibility = if (p.detail.isBlank()) View.GONE else View.VISIBLE
        routeStatus(getString(R.string.maps_routing))
        placeCard.reveal(true, 16f, delay = 60)
        map.setDestination(p.pos)
        map.setRoute(null)
        // The place at once; the place with the car once the route has had a moment to come (it brings its own
        // overview): two flights one after the other read as the map stuttering.
        root.removeCallbacks(firstOverview)
        if (CarLocation.last == null) map.overview(listOf(p.pos), panelWidth() + 40.dp, 90.dp, 150.dp, 60.dp)
        else root.postDelayed(firstOverview, 700)
    }

    private val firstOverview = Runnable {
        val p = selected ?: return@Runnable
        val car = CarLocation.last
        map.overview(listOfNotNull(p.pos, car?.let { LatLon(it.latitude, it.longitude) }), panelWidth() + 40.dp, 90.dp, 150.dp, 60.dp)
    }

    private fun requestRoute() {
        val p = selected ?: return
        val car = CarLocation.last
        if (car == null) { routeStatus(getString(R.string.maps_waiting_gps)); return }
        routing = true
        routeStatus(getString(R.string.maps_routing))
        Router.route(LatLon(car.latitude, car.longitude), p.pos, if (car.hasBearing() && car.speed > 2) car.bearing.toDouble() else null) { r, _ ->
            routing = false
            // (the same spot: a dropped pin is replaced by its named place while the route is on its way)
            if (isGone || selected?.pos != p.pos) return@route
            if (r == null) { routeFailed(); return@route }
            resetRouteRetry()
            preview = r
            root.removeCallbacks(firstOverview)
            showPreview(r)
            map.setRoute(r.line.points)
            // + the place itself: the route ends on the nearest road, the pin can stand well off it
            map.overview(r.line.points + p.pos, panelWidth() + 40.dp, 90.dp, 150.dp, 60.dp)
        }
    }

    /** The route's summary: the time it takes large (what is asked first), then how far and when it arrives. */
    private fun showPreview(r: Route) {
        placeEta.setIfChanged(GuidanceBanner.styled(Units.duration(r.duration, arabic), 0.6f, Palette.success))
        placeEta.visibility = View.VISIBLE
        placeRoute.setTextColor(Palette.text2)
        placeRoute.setIfChanged("  ·  ${Units.distance(r.distance, arabic)} · ${getString(R.string.maps_arrival)} ${Units.arrival(r.duration, Prefs.clock24)}")
        placeRoute.isClickable = false
        goBtn.fadeTo(1f)
    }

    /**
     * Instead of the summary: what is going on (finding the route, waiting for GPS), or that it failed, in the
     * warning colour and with a tap to try again. Go stays dimmed until there is a route.
     */
    private fun routeStatus(s: String, failed: Boolean = false) {
        placeEta.visibility = View.GONE
        placeRoute.setTextColor(if (failed) Palette.warn else Palette.text2)
        placeRoute.setIfChanged(if (failed) "$s\n${getString(R.string.maps_route_retry)}" else s)
        placeRoute.isClickable = failed
        goBtn.fadeTo(0.5f)
    }

    private fun clearPlace() {
        // (the card of a trip being started from elsewhere, closed: that trip is off, and its asker hears so)
        if (following != null) { following = null; routing = false; Trips.cancel("closed on the map") }
        selected = null
        preview = null
        resetRouteRetry()
        root.removeCallbacks(firstOverview)
        placeCard.reveal(false, 16f)
        input.setText("")
        map.setDestination(null)
        map.setRoute(null)
        recenter()
    }

    private fun go() {
        val p = selected ?: return
        val r = preview
        if (r == null) {
            toast(getString(if (CarLocation.last == null) R.string.maps_waiting_gps else R.string.maps_routing))
            return
        }
        Trips.start(this, p, r, remember = notKept != p.pos)
        afterStart()
    }

    /** A trip has started: from the card to the trip's panels. */
    private fun afterStart() {
        if (BuildConfig.DEBUG && simulate) NavSim.start(70.0)
        selected = null
        preview = null
        root.removeCallbacks(firstOverview)
        recenterBtn.reveal(false, 12f, scaleFrom = 0.92f)
        map.setCamera(AuraMap.Camera.NAVIGATE)
        syncNav()
    }

    /** Home/Work: route and start right away (like pressing the button in a car). */
    private fun driveTo(p: Place) {
        if (CarLocation.last == null) { select(p); return }
        selected = p
        preview = null // (another place's route must not stay behind this one's Go, should no route come)
        resetRouteRetry()
        placeName.setIfChanged(p.name)
        placeDetail.setIfChanged(p.detail)
        placeDetail.visibility = if (p.detail.isBlank()) View.GONE else View.VISIBLE
        routeStatus(getString(R.string.maps_routing))
        placeCard.reveal(true, 16f, delay = 60)
        routing = true
        // (Back or the Home key meanwhile: no navigation the driver can no longer see starting. Something opened over this panel is
        // another matter: he asked, it comes back to a trip, as it did with an activity under Music)
        val here = { !isGone && !isOff && selected?.pos == p.pos }
        Trips.goTo(this, p, source = Trips.SCREEN, wanted = here) { outcome, _ ->
            routing = false
            when (outcome) {
                Trips.Outcome.STARTED -> { resetRouteRetry(); afterStart() }
                Trips.Outcome.FAILED, Trips.Outcome.NO_FIX -> if (here()) routeFailed()
                Trips.Outcome.DROPPED -> {}
            }
        }
    }

    private fun endNav() {
        root.removeCallbacks(autoRecenter)
        NavSession.stop()
        map.setRoute(null)
        map.setDestination(null)
        map.setCamera(AuraMap.Camera.FOLLOW)
        recenterBtn.reveal(false, 12f, scaleFrom = 0.92f)
        syncNav()
    }

    private fun panelWidth() = 460.dp

    /** A message above the card at the bottom, if one is there (it would cover the card's buttons). */
    private fun toast(s: String, outlives: Boolean = false) {
        val card = if (::tripBar.isInitialized) listOf(tripBar, placeCard, arrivedCard).firstOrNull { it.isOn } else null
        // (a card revealed in the same pass, as the trip bar with the "no voice" hint, has no height yet: measured here)
        val h = when {
            card == null -> 0
            card.height > 0 -> card.height
            else -> {
                card.measure(View.MeasureSpec.makeMeasureSpec(panelWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
                card.measuredHeight
            }
        }
        val above = if (h == 0) Snack.ABOVE_DP else (h / resources.displayMetrics.density).toInt() + Space.GUTTER + Space.M
        Snack.show(this, s, aboveDp = above, outlives = outlives)
    }

    /** Visible and not on its way out. */
    private val View.isOn: Boolean get() = visibility == View.VISIBLE && getTag(R.id.aura_exiting) != true

    // ------------------------------------------------------------------------------------------ search
    private fun onQuery(q: String) {
        searchRunnable?.let { root.removeCallbacks(it) }
        if (q.trim().length < 2) { Places.cancelSearch(); searching(false); showSuggestions(); return }
        val r = Runnable { runSearch(q) }
        searchRunnable = r
        root.postDelayed(r, 350)
    }

    /**
     * Searching: the rows already there stay (dimmed) until the answer replaces them, and a spinner takes the clear
     * button's place. Emptying the list for a "searching" line made the card collapse and grow on every letter.
     */
    private fun searching(on: Boolean) {
        spinner.visibility = if (on) View.VISIBLE else View.GONE
        if (on) clearBtn.visibility = View.GONE else syncClear(animate = false)
        results.fadeTo(if (on) 0.55f else 1f)
    }

    private fun runSearch(q: String) {
        searching(true)
        // near the car, or near where the map is looked at when there is no fix yet (else no bias at all, and
        // "pharmacy" found Toronto first)
        val near = CarLocation.lastKnown() ?: map.target
        Places.search(q, near, arabic) { list ->
            if (!resultsCard.isOn) return@search
            searching(false)
            results.removeAllViews()
            when {
                list == null -> results.addView(note(getString(R.string.maps_offline)))
                list.isEmpty() -> results.addView(note(getString(R.string.maps_no_results)))
                else -> for (p in list) results.addView(row(R.drawable.ic_pin, p.name, p.detail, near?.let { Units.distance(Geo.distance(it, p.pos), arabic) }) { select(p) })
            }
        }
    }

    /** With an empty query: Home, Work and recent destinations. */
    private fun showSuggestions() {
        results.removeAllViews()
        val near = CarLocation.lastKnown()
        val home = Places.home
        val work = Places.work
        results.addView(row(R.drawable.ic_home, getString(R.string.home_home), home?.name ?: getString(R.string.maps_set_place_hint), null) {
            if (home != null) { closeSearch(); driveTo(home) } else { closeSearch(); pickFor = "home"; showPickHint() }
        })
        results.addView(row(R.drawable.ic_briefcase, getString(R.string.home_work), work?.name ?: getString(R.string.maps_set_place_hint), null) {
            if (work != null) { closeSearch(); driveTo(work) } else { closeSearch(); pickFor = "work"; showPickHint() }
        })
        val rec = Places.recents
        if (rec.isNotEmpty()) {
            results.addView(label(Type.CAPTION, Palette.text3).apply {
                setText(R.string.maps_recents)
                weight = Fonts.MEDIUM
                setPaddingRelative(Space.L.dp, Space.M.dp, Space.L.dp, Space.XS.dp)
            })
            for (p in rec) results.addView(row(R.drawable.ic_clock, p.name, p.detail, near?.let { Units.distance(Geo.distance(it, p.pos), arabic) }) { select(p) })
        }
    }

    /** The clear button: whenever the field has text (also a place's name put there), never on an empty one. */
    private fun syncClear(animate: Boolean = true) {
        if (spinner.visibility == View.VISIBLE) return
        val want = input.text.isNotEmpty()
        val shown = clearBtn.visibility == View.VISIBLE && clearBtn.getTag(R.id.aura_exiting) != true
        if (want == shown) return
        if (!animate || !clearBtn.isAttachedToWindow) { clearBtn.visibility = if (want) View.VISIBLE else View.GONE; clearBtn.alpha = 1f; clearBtn.scaleX = 1f; clearBtn.scaleY = 1f; return }
        clearBtn.reveal(want, 0f, scaleFrom = 0.8f)
    }

    // ------------------------------------------------------------------------------------------ UI
    private fun buildUi() {
        // ---- end side: the map's controls (one layer, faded away while typing)
        controls = FrameLayout(this)
        root.addView(controls, MATCH, MATCH)
        val attribution = label(Type.ATTRIBUTION_SP, Palette.text3, Fonts.REGULAR).apply {
            text = MapStyle.ATTRIBUTION
            setPadding(Space.S.dp, Space.XXS.dp, Space.S.dp, Space.XXS.dp)
            background = Shapes.rect(Palette.withAlpha(Palette.glass, 0.7f), Radius.XS)
        }
        controls.addView(attribution, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, Space.M.dp, Space.S.dp); marginEnd = Space.M.dp })

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
        controls.addView(btns, flp(WRAP, WRAP, Gravity.END or Gravity.TOP).apply { setMargins(0, Space.XL.dp, Space.L.dp, 0); marginEnd = Space.L.dp })

        speedBubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Shapes.oval(Palette.glass, Palette.glassStroke)
            lift(Elev.FLOAT, overMap = true)
            visibility = View.GONE
        }
        speedText = label(Type.DISPLAY_S, Palette.text, gravity = Gravity.CENTER)
        speedBubble.addView(speedText, lp(MATCH, WRAP))
        speedUnit = label(Type.CAPTION, Palette.text2, gravity = Gravity.CENTER).apply { setText(R.string.maps_kmh) }
        speedBubble.addView(speedUnit, lp(MATCH, WRAP))
        controls.addView(speedBubble, flp(92.dp, 92.dp, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 18.dp, 40.dp); marginEnd = 18.dp })
        // the owner's additions: the street's limit above the speed, and the camera or bump ahead at the top beside the
        // compass (beside the limit it covered the car while navigating)
        speedSign = RoadHud(this, parts = RoadHud.Parts.SIGN)
        controls.addView(speedSign, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 33.dp, 138.dp); marginEnd = 33.dp })
        controls.addView(RoadHud(this, parts = RoadHud.Parts.ALERT), flp(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, 31.dp, 94.dp, 0); marginEnd = 94.dp })
        // "a camera here": beside the speed, in reach of the driver's hand (Settings → Navigation hides it); laid out
        // where it stands while driving, moved into the speed's place by translation otherwise ([placeEndCluster])
        cameraMark = CameraMark(this)
        controls.addView(cameraMark, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 122.dp, 53.dp); marginEnd = 122.dp })

        // ---- start column: search / results
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Shapes.glass(Radius.PILL)
            lift(Elev.FLOAT, overMap = true)
        }
        searchCard.addView(roundIcon(R.drawable.ic_chevron_left, Touch.MIN, R.string.maps_close, flipRtl = true) { shell.panels.back() },
            lp(Touch.MIN.dp, Touch.MIN.dp).apply { marginStart = Space.S.dp })
        input = EditText(this).apply {
            setHint(R.string.home_where_to)
            setHintTextColor(Palette.text3)
            setTextColor(Palette.text)
            textSize = Type.LABEL.sp
            typeface = Fonts.get(Fonts.REGULAR)
            isSingleLine = true
            // (no spelling suggestions: the checker underlined place names in red, "Taslis Bukari" as a misspelling)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_FILTER
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = null
            setPadding(Space.XS.dp, 0, Space.XS.dp, 0)
            setOnFocusChangeListener { _, f -> if (f) openSearch() }
            setOnClickListener { openSearch() }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    syncClear()
                    if (hasFocus()) onQuery(s?.toString().orEmpty())
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, action, ev ->
                if (action == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    searchRunnable?.let { root.removeCallbacks(it) }
                    if (text.trim().length >= 2) runSearch(text.toString())
                    hideKeyboard() // the keyboard covers most of a car screen: show the results
                    true
                } else false
            }
        }
        searchCard.addView(input, lp(0, 64.dp, 1f))
        val end = FrameLayout(this)
        clearBtn = roundIcon(R.drawable.ic_close, Touch.MIN, R.string.maps_clear) { input.setText(""); openSearch() }.apply { visibility = View.GONE }
        end.addView(clearBtn, flp(Touch.MIN.dp, Touch.MIN.dp, Gravity.CENTER))
        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Palette.text2)
            visibility = View.GONE
        }
        end.addView(spinner, flp(22.dp, 22.dp, Gravity.CENTER))
        searchCard.addView(end, lp(Touch.MIN.dp, Touch.MIN.dp).apply { marginEnd = Space.S.dp })
        col.addView(searchCard, lp(panelWidth(), 68.dp))

        // (the rows' pressed tint sits inside the card's rounded corners: an inset, and the inner radius)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Space.S.dp, Space.S.dp, Space.S.dp, Space.S.dp) }
        resultsCard = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) {
                // The room under the search pill, from this measure pass (the screen's height read here is the one
                // before the keyboard came or went, which kept the card keyboard-sized after it went), cut at a
                // half row, so the cut reads as "more below".
                val room = (MeasureSpec.getSize(h).takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) - Space.L.dp
                val rows = ((room - Space.L.dp) / ROW_DP.dp.toFloat() - 0.5f).toInt().coerceAtLeast(1)
                val max = minOf(room, Space.L.dp + ((rows + 0.5f) * ROW_DP.dp).toInt()).coerceAtLeast(ROW_DP.dp)
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = panelFill()
            lift(Elev.FLOAT, overMap = true)
            visibility = View.GONE
            addView(results, MATCH, WRAP)
        }
        col.addView(resultsCard, lp(panelWidth(), WRAP).apply { topMargin = Space.M.dp })
        root.addView(col, flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(Space.L.dp, Space.L.dp, Space.L.dp, 0); marginStart = Space.L.dp })

        // ---- the guidance banner, in the search pill's place (both there while one goes and the other comes)
        banner = GuidanceBanner(this)
        root.addView(banner, flp(panelWidth(), WRAP, Gravity.TOP or Gravity.START).apply { setMargins(Space.L.dp, Space.L.dp, Space.L.dp, 0); marginStart = Space.L.dp })

        // ---- bottom start: place card / trip bar / arrived, one place
        placeCard = buildPlaceCard()
        root.addView(placeCard, bottomCard())
        tripBar = buildTripBar()
        root.addView(tripBar, bottomCard())
        arrivedCard = buildArrivedCard()
        root.addView(arrivedCard, bottomCard())

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
        root.addView(recenterHolder, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(Space.L.dp, 0, Space.L.dp, Space.L.dp); marginStart = Space.L.dp })
    }

    /**
     * The fill of the cards with text over the map (results, place, trip, arrival): solid, not the shared glass. At the
     * glass's 95 % the map's labels still showed through behind their text, clutter on a small screen in a moving car.
     */
    private fun panelFill() = Shapes.rect(Palette.withAlpha(Palette.glass, 1f), Radius.XL, Palette.glassStroke)

    private fun bottomCard() = flp(panelWidth(), WRAP, Gravity.BOTTOM or Gravity.START).apply {
        setMargins(Space.L.dp, 0, Space.L.dp, Space.L.dp); marginStart = Space.L.dp
    }

    private fun buildPlaceCard(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelFill()
            lift(Elev.FLOAT, overMap = true)
            visibility = View.GONE
            setPaddingRelative(Space.XL.dp, Space.L.dp, Space.M.dp, Space.L.dp)
            isClickable = true
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        placeName = label(Type.TITLE, Palette.text, lines = 2)
        // (an address is a list in the UI's language, its commas included: it reads in the UI's direction even when
        // its first part is a Latin street name, which would otherwise turn the whole line around)
        placeDetail = label(Type.FOOTNOTE, Palette.text2, lines = 2).apply { textDirection = View.TEXT_DIRECTION_LOCALE }
        texts.addView(placeName, lp(MATCH, WRAP).apply { topMargin = Space.XS.dp })
        texts.addView(placeDetail, lp(MATCH, WRAP).apply { topMargin = Space.XS.dp })
        head.addView(texts, lp(0, WRAP, 1f))
        head.addView(roundIcon(R.drawable.ic_close, Touch.MIN, R.string.maps_close) { clearPlace() }, lp(Touch.MIN.dp, Touch.MIN.dp).apply { marginStart = Space.S.dp })
        c.addView(head, lp(MATCH, WRAP))
        // the time it takes in large green figures, then how far and when (or what is going on, or a failure)
        val route = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        placeEta = label(Type.HEADLINE, Palette.success).apply { fontFeatureSettings = "tnum"; visibility = View.GONE }
        placeRoute = label(Type.FOOTNOTE, Palette.text2, lines = 2).apply {
            fontFeatureSettings = "tnum"
            setOnClickListener { resetRouteRetry(); requestRoute() }
            isClickable = false
        }
        route.addView(placeEta, lp(WRAP, WRAP))
        route.addView(placeRoute, lp(0, WRAP, 1f))
        c.addView(route, lp(MATCH, WRAP).apply { topMargin = Space.M.dp; marginEnd = Space.M.dp })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        goBtn = ABtn(this).apply {
            kind = ABtn.Kind.PRIMARY
            size = ABtn.Size.L
            setText(R.string.maps_go)
            alpha = 0.5f
            setOnClickListener { go() }
            if (BuildConfig.DEBUG) setOnLongClickListener { simulate = true; go(); true }
        }
        actions.addView(goBtn, lp(0, Touch.LARGE.dp, 1f))
        // Saving is one quiet choice beside Go, its two kinds in a menu: labelled "Home" next to Go it read as "drive
        // home" (and overwrote the saved Home instead), and two "Set as ..." buttons left Go barely wider than either.
        actions.addView(ABtn(this).apply {
            kind = ABtn.Kind.GHOST
            setText(R.string.btn_save)
            setPadding(Space.XL.dp, 0, Space.XL.dp, 0)
            setOnClickListener { v ->
                // (Home or Work being chosen, the field says which: Save keeps it as that at once)
                pickFor?.let { savePlace(it); return@setOnClickListener }
                Menu.show(v, listOf(
                    Menu.Item(R.drawable.ic_home, getString(R.string.maps_set_home)) { savePlace("home") },
                    Menu.Item(R.drawable.ic_briefcase, getString(R.string.maps_set_work)) { savePlace("work") }))
            }
        }, lp(WRAP, Touch.LARGE.dp).apply { marginStart = Space.S.dp })
        c.addView(actions, lp(MATCH, WRAP).apply { topMargin = Space.L.dp; marginEnd = Space.M.dp })
        return c
    }

    private fun buildTripBar(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panelFill()
            lift(Elev.FLOAT, overMap = true)
            visibility = View.GONE
            setPaddingRelative(Space.XL.dp, Space.M.dp, Space.M.dp, Space.M.dp)
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // (the time left in green, as on every navigation screen: all is well, this is how long; its unit small)
        tripEta = label(Type.DISPLAY_S, Palette.success).apply { weight = Fonts.MEDIUM }
        tripLeft = label(Type.FOOTNOTE, Palette.text2).apply { fontFeatureSettings = "tnum" }
        t.addView(tripEta, lp(MATCH, WRAP))
        t.addView(tripLeft, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
        c.addView(t, lp(0, WRAP, 1f))
        // the voice, here with the trip it belongs to (it was the fourth button of the map's column)
        voiceIcon = ImageView(this)
        c.addView(roundIcon(R.drawable.ic_volume, 60, R.string.maps_voice, img = voiceIcon) {
            if (NavSession.voiceMissing) { // no text-to-speech engine on the unit: offer Google's (it speaks Arabic)
                toast(getString(R.string.maps_voice_missing), outlives = true)
                openSpeechStore()
                return@roundIcon
            }
            NavSession.voiceOn = !NavSession.voiceOn
            voiceIcon.setImageResource(if (NavSession.voiceOn) R.drawable.ic_volume else R.drawable.ic_volume_off)
        }, lp(60.dp, 60.dp))
        c.addView(roundIcon(R.drawable.ic_layers, 60, R.string.maps_overview) {
            NavSession.route?.let { r ->
                map.overview(r.line.points, panelWidth() + 40.dp, 90.dp, 150.dp, 60.dp)
                showRecenter()
                syncZoom()
                // (a look at the whole route lasts longer than a brush of the screen)
                root.removeCallbacks(autoRecenter)
                root.postDelayed(autoRecenter, AUTO_RECENTER_OVERVIEW_MS)
            }
        }, lp(60.dp, 60.dp).apply { marginStart = Space.XS.dp })
        // a red word on a red tint, not a red block: stopping is there, but the time left is what draws the eye
        c.addView(ABtn(this).apply {
            kind = ABtn.Kind.DANGER
            size = ABtn.Size.L
            setPadding(Space.L.dp, 0, Space.L.dp, 0)
            setText(R.string.maps_end)
            setOnClickListener { endNav() }
        }, lp(WRAP, Touch.LARGE.dp).apply { marginStart = Space.S.dp })
        return c
    }

    private fun buildArrivedCard(): FrameLayout {
        val c = FrameLayout(this).apply {
            background = panelFill()
            roundedClip(Radius.XL)
            lift(Elev.FLOAT, overMap = true)
            visibility = View.GONE
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(Space.XL.dp, Space.L.dp, Space.M.dp, Space.L.dp)
        }
        row.addView(iconView(R.drawable.ic_check_circle, 34, Palette.success), lp(34.dp, 34.dp))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        arrivedCaption = label(Type.FOOTNOTE, Palette.text2)
        arrivedName = label(Type.TITLE, Palette.text)
        t.addView(arrivedCaption, lp(MATCH, WRAP))
        t.addView(arrivedName, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
        row.addView(t, lp(0, WRAP, 1f).apply { marginStart = Space.M.dp; marginEnd = Space.S.dp })
        row.addView(ABtn(this).apply {
            kind = ABtn.Kind.PRIMARY
            size = ABtn.Size.L
            setText(R.string.maps_done)
            setOnClickListener { endNav() }
        }, lp(WRAP, Touch.LARGE.dp))
        c.addView(row, flp(MATCH, WRAP))
        // the time before the trip ends by itself, running out along the bottom edge
        arrivedBar = View(this).apply { setBackgroundColor(Guide.color()) }
        c.addView(arrivedBar, flp(MATCH, 3.dp, Gravity.BOTTOM))
        return c
    }

    private fun row(icon: Int, title: String, detail: String, distance: String?, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ROW_DP.dp
            setPaddingRelative(Space.M.dp, Space.S.dp, Space.L.dp, Space.S.dp)
            background = Shapes.ghost(Radius.M)
            isClickable = true
            pressScale(Motion.PRESS_SURFACE)
            setOnClickListener { onClick() }
        }
        val badge = FrameLayout(this).apply { background = Shapes.oval(Palette.card2) }
        badge.addView(iconView(icon, 22, Palette.text2).apply { layoutParams = flp(22.dp, 22.dp, Gravity.CENTER) })
        r.addView(badge, lp(46.dp, 46.dp))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(Type.LABEL, Palette.text).apply { text = title }, lp(MATCH, WRAP))
        if (detail.isNotBlank()) t.addView(label(Type.FOOTNOTE, Palette.text2).apply { text = detail; textDirection = View.TEXT_DIRECTION_LOCALE }, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
        r.addView(t, lp(0, WRAP, 1f).apply { marginStart = Space.M.dp })
        if (distance != null) r.addView(label(Type.CAPTION, Palette.text3).apply { text = distance; fontFeatureSettings = "tnum" }, lp(WRAP, WRAP).apply { marginStart = Space.M.dp })
        return r
    }

    private fun note(s: String) = label(Type.BODY, Palette.text2).apply { text = s; setPaddingRelative(Space.L.dp, Space.L.dp, Space.L.dp, Space.L.dp) }

    private fun roundIcon(icon: Int, size: Int, desc: Int, flipRtl: Boolean = false, img: ImageView? = null, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
        background = Shapes.ghostOval()
        isClickable = true
        contentDescription = getString(desc)
        pressScale(Motion.PRESS_ICON)
        setOnClickListener { onClick() }
        val side = (size * 0.45f).toInt().dp
        addView((img ?: ImageView(this@MapsPanel)).apply {
            setImageResource(icon)
            setColorFilter(Palette.text)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (flipRtl && rtl) scaleX = -1f
        }, flp(side, side, Gravity.CENTER))
    }

    private var voiceHintShown = false

    /** Play Store page of Google's speech engine (voice guidance needs a TTS engine; the firmware ships none). */
    private fun openSpeechStore() {
        val pkg = "com.google.android.tts"
        for (uri in listOf("market://details?id=$pkg", "https://play.google.com/store/apps/details?id=$pkg")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {
            }
        }
    }

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

    // (last in the class: what is declared above is what this builds on)
    init {
        builtDark = Palette.dark
        builtAccent = Prefs.accent
        buildUi()
        // a new size of the screen (the keyboard came or went): the results measure their room again
        root.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot && resultsCard.isOn) resultsCard.post { resultsCard.requestLayout() } }
        root.viewTreeObserver.addOnGlobalLayoutListener { if (shown) liftRecenter() }
    }

    private companion object {
        /** After the driver moved the map while driving, it goes back to the car by itself after this long, */
        private const val AUTO_RECENTER_MS = 12_000L
        /** and after a look at the whole route, this long. */
        private const val AUTO_RECENTER_OVERVIEW_MS = 20_000L
        /** The arrival card's line moves on this often. */
        private const val ARRIVED_STEP_MS = 1000L
        /** The map's round buttons. */
        private const val MAP_BTN = 66
        /** A search result's height. */
        private const val ROW_DP = 74
    }
}
