package com.abdllh.aura.nav

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.home.Screens
import com.abdllh.aura.nav.browse.BrowseGroup
import com.abdllh.aura.nav.browse.FromPhoneCard
import com.abdllh.aura.nav.browse.NoGpsCard
import com.abdllh.aura.nav.browse.PlaceCard
import com.abdllh.aura.nav.browse.ResultLine
import com.abdllh.aura.nav.browse.RouteChoices
import com.abdllh.aura.nav.browse.SearchPanel
import com.abdllh.aura.nav.guide.Speech
import com.abdllh.aura.nav.places.PlaceSearch
import com.abdllh.aura.nav.route.GuideLink
import com.abdllh.aura.nav.route.RouteAnswer
import com.abdllh.aura.nav.route.RouteChain
import com.abdllh.aura.nav.route.RouteRequest
import com.abdllh.aura.settings.SettingsActivity
import com.abdllh.aura.system.DriveState
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Menu
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.label
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.voice.Amri

/**
 * Maps with no trip (SPEC.md section 6.3, simple/CHANGES.md section 5): the one search pill, the search panel with his places, the place's card, the card of a place from the phone, the credit on the map; with the visit's
 * logic: what was chosen, its routes, Go, Home and Work, what the phone sent. The views are nav/browse's; this file is what they do. It reaches the rest only through [MapsPanel.cardOverColumn],
 * [MapsPanel.overview], [MapsPanel.syncNav] and panel.tools.
 */
internal class BrowseCards(panel: MapsPanel) : MapsPart(panel) {
    private lateinit var group: BrowseGroup
    private lateinit var search: SearchPanel
    private lateinit var placeCard: PlaceCard
    private lateinit var phoneCard: FromPhoneCard
    private lateinit var noGps: NoGpsCard
    private lateinit var credit: AText
    private val input get() = search.input

    private var selected: Place? = null
    /** The route that Go starts: the chosen one of [options]. */
    private var preview: Route? = null
    private var options: List<RouteChoices.Option> = emptyList()
    private var chosen = 0
    /** The answer said the trip starts or ends inside the Haram, though asked to go round it. */
    private var haramInside = false
    private var routing = false
    private var routeAsk = 0
    private var pickFor: String? = null
    private var finishAfterPick = false
    /** The place sent here that must not go to the recent places when Go starts it ([MapsActivity.EXTRA_KEEP]). */
    private var notKept: LatLon? = null
    /** Debug builds: the trip this starts drives itself. */
    var simulate = false
    /** The row a place tapped on the map just said it is ([AuraMap.onPlaceRow]). */
    private var tappedRow = 0L
    /** What the camera was when the search opened: leaving it, back to that (section 6.0's table). */
    private var cameraWas: com.mapbox.mapboxsdk.camera.CameraPosition? = null
    private var modeWas = AuraMap.Camera.FOLLOW
    private var searchFlew = false
    private var lastResults: List<Place> = emptyList()

    /** He is choosing a place or searching: no new look under his hands, no `drive-free`. */
    /** The field has the focus: the panel's soft input mode goes by this ([MapsPanel.softInput]). */
    val fieldFocused: Boolean get() = ::search.isInitialized && input.hasFocus()

    val inUse: Boolean get() = search.isOn || selected != null || pickFor != null || input.hasFocus() || routing

    /** The old cards' lift for the old locate button: none of these is a bottom card any more. */
    val bottomCard: View? get() = null

    /**
     * On the unit the window does not resize for the keyboard, only its visible frame shrinks (DEV.md, "Soft input"), and so that is what the list and the camera read: the list keeps above
     * the keyboard's top, and what is in it can be scrolled to. A layout pass tells; while the search is up only, because the frame is a call to the window manager.
     */
    private val keyboardWatch = android.view.ViewTreeObserver.OnGlobalLayoutListener { if (search.isOn) fitSearch() }
    private val frame = android.graphics.Rect()

    private fun fitSearch() {
        root.getWindowVisibleDisplayFrame(frame)
        search.setRoom(frame.bottom - SEARCH_LIST_TOP - SEARCH_LIST_FOOT)
    }

    private fun syncOver() {
        val cardOn = search.isOn || placeCard.isOn || phoneCard.isOn
        panel.cardOverColumn(cardOn)
        panel.tools.forSearch(cardOn)
        syncGroup()
    }

    /** The pill: with no trip and nothing over it, `drive-free` too (section 6.2). The credit here is search's; browse's is the map tools' (section 11: on both). */
    private fun syncGroup() {
        val trip = NavSession.state != NavSession.State.IDLE
        val cardOn = search.isOn || placeCard.isOn || phoneCard.isOn
        val moving = DriveState.moving
        group.setMoving(moving)
        group.show(!trip && !cardOn)
        credit.visibility = if (!trip && search.isOn) View.VISIBLE else View.INVISIBLE
        // (moving, a touch on the field is voice's and brings no keyboard: section 6.3)
        input.showSoftInputOnFocus = !moving
        syncGps()
    }

    // ---- no GPS for a while on browse: a card under the pill, and how old the last fix is (section 12). A gap of a fix or two is the strip's icon alone
    private var quietSince = 0L
    private val gpsCheck = Runnable { syncGps() }

    private fun syncGps() {
        root.removeCallbacks(gpsCheck)
        val now = SystemClock.elapsedRealtime()
        if (quietSince == 0L) quietSince = now
        val fix = CarLocation.last
        val age = if (fix != null) (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000L else now - quietSince
        val cardOn = search.isOn || placeCard.isOn || phoneCard.isOn
        val wanted = panel.shown && NavSession.state == NavSession.State.IDLE && panel.face == MapsPanel.Face.BROWSE && !cardOn
        val on = wanted && age >= NO_GPS_MS
        if (on) {
            noGps.sub(if (fix != null) getString(R.string.browse_last_fix, Units.duration(age / 1000.0, arabic)) else getString(R.string.browse_waiting_gps))
            noGps.showCard()
        } else noGps.hideCard()
        // (it says minutes; a fix clears it at once: [onFix])
        if (wanted) root.postDelayed(gpsCheck, if (on) GPS_LINE_MS else NO_GPS_MS - age)
    }

    override fun onFace(face: MapsPanel.Face) = syncGroup()

    // ------------------------------------------------------------------------------------------ what the panel asks
    /** What came with the panel's opening (its args). */
    fun take(i: Bundle?) {
        when (val which = i?.getString("go")) {
            "home", "work" -> {
                val saved = Trips.saved(which)
                when {
                    saved != null -> driveTo(saved)
                    Trips.legacy(which).isNotBlank() -> { // an address typed in 1.0: find it once and keep the place
                        panel.toast(getString(R.string.maps_searching))
                        Trips.findLegacy(which, arabic) { p ->
                            if (panel.isGone || panel.isOff) return@findLegacy
                            if (p == null) { panel.toast(getString(R.string.browse_nothing)); openSearch(); return@findLegacy }
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
        i?.getString("q")?.takeIf { it.isNotBlank() }?.let { q ->
            openSearch(keyboard = false)
            input.setText(q)
            input.setSelection(q.length)
            runSearch(q)
        }
        i?.getString("pick")?.let { which -> // Home / Work chosen on the map, then back to the home screen
            pickFor = which
            finishAfterPick = true
            showPickHint()
        }
    }

    fun onBack(): Boolean = when {
        search.isOn -> { closeSearch(); true }
        selected != null && NavSession.state == NavSession.State.IDLE -> { clearPlace(); true }
        else -> false
    }

    /** Back or the Home key took the panel off the stack: what the visit chose. */
    fun forgetVisit() {
        root.removeCallbacks(firstOverview)
        root.removeCallbacks(searchFit)
        root.removeCallbacks(countdown)
        root.removeCallbacks(gpsCheck)
        Places.cancelSearch()
        selected = null
        preview = null
        options = emptyList()
        routing = false
        following = null
        followingRoute = null
        pickFor = null
        finishAfterPick = false
        notKept = null
        simulate = false
        cameraWas = null
        lastResults = emptyList()
        tappedRow = 0L
        resetRouteRetry()
        map.setRoute(null)
        map.setRouteAlt(null)
        map.setDestination(null)
        map.setResults(emptyList())
    }

    /** As it was built, at rest and not seen. */
    fun fresh() {
        input.clearFocus()
        input.setText("")
        group.hint(getString(R.string.home_where_to))
        search.hideNow()
        placeCard.hideNow()
        phoneCard.hideNow()
        noGps.hideNow()
        syncOver()
    }

    fun destroy() {
        root.removeCallbacks(firstOverview)
        root.removeCallbacks(searchFit)
        root.removeCallbacks(countdown)
        root.removeCallbacks(gpsCheck)
        root.viewTreeObserver.removeOnGlobalLayoutListener(keyboardWatch)
        Places.cancelSearch()
    }

    override fun sync() {
        val st = NavSession.state
        val navigating = st == NavSession.State.NAVIGATING
        val active = st != NavSession.State.IDLE
        // (a trip started from elsewhere while this screen was away: nothing chosen earlier stays behind it)
        if (navigating && selected != null && !routing && following == null) { selected = null; preview = null; options = emptyList(); map.setRoute(null); map.setRouteAlt(null); map.setDestination(null) }
        // (a trip started from outside while the search was open: its keyboard-time fade must not hide the speed)
        if (active && (search.isOn || placeCard.isOn || phoneCard.isOn)) {
            search.hideCard()
            placeCard.hideCard()
            phoneCard.hideCard()
            map.setResults(emptyList())
            syncOver()
        } else syncGroup()
    }

    override fun tripChanged(on: Boolean) {
        if (on) hideKeyboard() // (once: it is a call to the keyboard's service)
        else if (selected == null) input.setText("")
    }

    override fun listen(on: Boolean) {
        if (on) {
            map.onPlaceRow = { id -> tappedRow = id }
            map.onResultTap = { i -> resultTapped(i) }
            map.onRouteAltTap = { if (options.size > 1) choose(1 - chosen) }
            map.onMapTap = { if (search.isOn) closeSearch() }
            GuideLink.landed = guideLanded
            DriveState.addListener(driveListener)
            root.viewTreeObserver.addOnGlobalLayoutListener(keyboardWatch)
            syncGroup()
        } else {
            map.onPlaceRow = null
            map.onResultTap = null
            map.onRouteAltTap = null
            map.onMapTap = null
            GuideLink.landed = null
            DriveState.removeListener(driveListener)
            root.viewTreeObserver.removeOnGlobalLayoutListener(keyboardWatch)
            root.removeCallbacks(countdown)
            root.removeCallbacks(gpsCheck)
        }
    }

    // (the car starting to move closes the keyboard: section 6.0; while moving the field opens voice)
    private val driveListener: () -> Unit = {
        if (DriveState.moving && input.hasFocus()) { hideKeyboard(); input.clearFocus() }
        syncGroup()
    }

    // ------------------------------------------------------------------------------------------ the map's own hooks
    /** Long press on the map: a pin there, named after what is at that spot, ready to drive to or keep as Home / Work. */
    fun dropPin(p: LatLon) {
        if (NavSession.state != NavSession.State.IDLE) return
        select(Place(getString(R.string.maps_dropped_pin), "", p))
        Places.reverse(p, arabic) { found ->
            // (the same spot, not the same object: the name replaces the selection, and the route stays the pin's)
            if (panel.isGone || selected?.pos != p || found == null) return@reverse
            val named = Place(found.name, found.detail, p)
            selected = named
            placeCard.setPlace(named.name, named.detail)
            map.setDestination(p, named.name)
            input.setText(named.name)
        }
    }

    fun poiTapped(name: String, kind: String, at: LatLon) {
        val row = tappedRow
        tappedRow = 0L
        if (search.isOn) { closeSearch(); return } // (a tap on the map leaves the search, whatever lies under it)
        if (NavSession.state != NavSession.State.IDLE) return
        select(Place(name, Poi.label(this, kind), at))
        // a place of the pack: its kind, district and town, from its own row
        if (row != 0L) PlaceSearch.row(row.toInt()) { h ->
            if (h == null || panel.isGone || selected?.pos != at) return@row
            val more = h.detail(arabic)
            if (more.isNotEmpty()) { selected = Place(name, more, at); placeCard.setPlace(name, more) }
        }
    }

    /** A search row's pin tapped: that row. */
    private fun resultTapped(i: Int) {
        lastResults.getOrNull(i)?.let { select(it) }
    }

    /** The preview route once a fix is there; a failed one is retried with growing pauses, not on every fix. */
    fun onFix() {
        if (noGps.visibility == View.VISIBLE) syncGps()
        // (not for a place whose trip is being started from elsewhere: [Trips] is finding its route)
        if (selected != null && preview == null && !routing && following == null && NavSession.state == NavSession.State.IDLE &&
            SystemClock.elapsedRealtime() >= routeRetryAt) requestRoute()
    }

    // ------------------------------------------------------------------------------------------ the place
    /** Choosing Home / Work: the search field says how, for as long as it takes. */
    private fun showPickHint() {
        val which = pickFor ?: return
        group.hint(getString(if (which == "work") R.string.maps_pick_work_short else R.string.maps_pick_home_short))
        panel.toast(getString(if (which == "work") R.string.browse_pick_work else R.string.browse_pick_home))
    }

    /** Saved as Home / Work; when Maps was opened just to choose it, back to where the user came from. */
    private fun savePlace(which: String) {
        val p = selected ?: return
        if (which == "home") Places.home = p else Places.work = p
        panel.toast(getString(R.string.maps_saved), outlives = finishAfterPick)
        pickFor = null
        group.hint(getString(R.string.home_where_to))
        placeCard.setSaved(true)
        if (finishAfterPick) { finishAfterPick = false; panel.finish() }
    }

    private var routeFails = 0
    private var routeRetryAt = 0L

    private fun routeFailed(message: String) {
        routeFails++
        routeRetryAt = SystemClock.elapsedRealtime() + (5_000L shl (routeFails - 1).coerceAtMost(4)) // 5 s ... 80 s
        placeCard.showNoRoute(message)
    }

    private fun resetRouteRetry() {
        routeFails = 0
        routeRetryAt = 0L
    }

    /** The place of a trip being started from elsewhere (the phone, the voice), while this screen shows it. */
    private var following: Place? = null
    /** That place's route on the map, shown once ([Trips.Phase.READY]). */
    private var followingRoute: Route? = null

    /**
     * A trip being started from elsewhere ([Trips]): while it has no route yet its place is on the card as if it were
     * chosen here, with what is going on; closing the card drops it. This screen's own [driveTo] shows itself. From the
     * phone it has its own card, which counts down once the route is there.
     */
    fun syncAttempt() {
        val a = Trips.attempt?.takeIf { it.source != Trips.SCREEN }
        if (a == null) {
            val p = following ?: return
            following = null
            followingRoute = null
            routing = false
            root.removeCallbacks(countdown)
            phoneCard.hideCard()
            when {
                NavSession.state != NavSession.State.IDLE -> afterStart()
                // (it did not start: a failure leaves the place here by its own way, a cancel takes it away)
                selected?.pos == p.pos -> clearPlace()
            }
            syncOver()
            return
        }
        if (NavSession.state == NavSession.State.NAVIGATING) return // (a trip being replaced: the trip's panels stay)
        val fromPhone = a.source == Trips.PHONE
        if (following?.pos != a.place.pos) {
            following = a.place
            followingRoute = null
            routing = true
            selected = a.place
            preview = null
            options = emptyList()
            showPlace(a.place, card = !fromPhone)
            if (fromPhone) {
                phoneCard.setPlace(a.place.name)
                phoneCard.showCard()
                syncOver()
            }
        }
        val status = getString(if (a.phase == Trips.Phase.WAITING_FIX) R.string.browse_waiting_gps else R.string.browse_computing)
        when {
            !fromPhone -> routeStatus(status)
            a.phase == Trips.Phase.READY -> a.route?.let { showFollowedRoute(a, it) }
            else -> phoneCard.waiting(status)
        }
    }

    /** A place from the phone has its route: on the map, and the count. */
    private fun showFollowedRoute(a: Trips.Attempt, r: Route) {
        if (followingRoute !== r) {
            followingRoute = r
            root.removeCallbacks(firstOverview)
            map.setRoute(r.line.points)
            map.setRouteAlt(null)
            panel.overview(r.line.points + a.place.pos)
        }
        tickCountdown()
    }

    /** Whole seconds, nothing on the fix: a change once a second, of one label. */
    private val countdown = Runnable { tickCountdown() }

    private fun tickCountdown() {
        root.removeCallbacks(countdown)
        val a = Trips.attempt ?: return
        val r = a.route ?: return
        val leftMs = (a.startsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val seconds = ((leftMs + 999) / 1000).toInt()
        phoneCard.ready(tripLine(r), startsIn(seconds))
        if (seconds > 0) root.postDelayed(countdown, (leftMs % 1000).let { if (it == 0L) 1000L else it })
    }

    private fun tripLine(r: Route) = "${Units.duration(r.duration, arabic)} · ${Units.distance(r.distance, arabic)}"

    /** "يبدأ بعد 5 ثوان", the number's own agreement. */
    private fun startsIn(s: Int): String = when {
        s <= 1 -> getString(R.string.browse_starts_in_1)
        s == 2 -> getString(R.string.browse_starts_in_2)
        else -> getString(R.string.browse_starts_in_n, s.toString()) // (Western digits, whatever the language: a %d would not be)
    }

    private fun pillTapped() {
        if (DriveState.moving) {
            // moving: no typing, the field is voice (section 6.3)
            if (Amri.hasMicPermission(this)) Amri.wake(this) else Screens.settings(this, SettingsActivity.PAGE_VOICE)
            return
        }
        openSearch()
    }

    private fun openSearch(keyboard: Boolean = true) {
        if (NavSession.state != NavSession.State.IDLE) return
        if (!search.isOn) {
            cameraWas = map.cameraNow()
            modeWas = map.camera
            searchFlew = false
        }
        fitSearch()
        credit.text = MapStyle.attribution(map.target ?: CarLocation.lastKnown())
        search.showCard()
        placeCard.hideCard()
        phoneCard.hideCard()
        if (input.text.trim().length < 2) showSuggestions()
        if (keyboard && !DriveState.moving) {
            input.requestFocus()
            input.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) }
        }
        syncOver()
    }

    private fun closeSearch(backToPlace: Boolean = true) {
        // a search still under way must not fill the list later (over Home / Work / recents)
        Places.cancelSearch()
        root.removeCallbacks(searchFit)
        search.hideCard()
        hideKeyboard()
        input.clearFocus()
        map.setResults(emptyList())
        lastResults = emptyList()
        if (selected != null) { if (backToPlace) placeCard.showCard() }
        else {
            input.setText("")
            // (back to the camera it had before: only if the search flew it)
            if (searchFlew) map.cameraBack(cameraWas, modeWas)
        }
        cameraWas = null
        syncOver()
    }

    private fun hideKeyboard() {
        try { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0) } catch (_: Throwable) { }
    }

    private fun select(p: Place) {
        // (this place is the driver's now: one that was being started from elsewhere is off, or has just failed and comes here with its retry)
        if (following != null) {
            following = null
            followingRoute = null
            routing = false
            root.removeCallbacks(countdown)
            phoneCard.hideCard()
            Trips.cancel("another place on the map")
        }
        selected = p
        preview = null
        options = emptyList()
        showPlace(p)
        requestRoute()
    }

    /** [p] on the card and on the map, with no route yet. */
    private fun showPlace(p: Place, card: Boolean = true) {
        resetRouteRetry()
        closeSearch(backToPlace = false)
        input.setText(p.name)
        placeCard.setPlace(p.name, p.detail)
        placeCard.setSaved(listOfNotNull(Places.home, Places.work).any { Geo.distance(it.pos, p.pos) < SAME_PLACE_M })
        routeStatus(getString(R.string.browse_computing))
        if (card) placeCard.showCard()
        syncOver()
        map.setDestination(p.pos, p.name)
        map.setRoute(null)
        map.setRouteAlt(null)
        // The place at once; the place with the car once the route has had a moment to come (it brings its own
        // overview): two flights one after the other read as the map stuttering.
        root.removeCallbacks(firstOverview)
        if (CarLocation.last == null) panel.overview(listOf(p.pos))
        else root.postDelayed(firstOverview, FIRST_OVERVIEW_MS)
    }

    private val firstOverview = Runnable {
        val p = selected ?: return@Runnable
        val car = CarLocation.last
        panel.overview(listOfNotNull(p.pos, car?.let { LatLon(it.latitude, it.longitude) }))
    }

    private fun requestRoute() {
        val p = selected ?: return
        val car = CarLocation.last
        if (car == null) { routeStatus(getString(R.string.browse_waiting_gps)); return }
        routing = true
        routeStatus(getString(R.string.browse_computing))
        val ask = ++routeAsk
        val asked = SystemClock.elapsedRealtime()
        RouteChain.ask(RouteRequest(LatLon(car.latitude, car.longitude), p.pos, if (car.hasBearing() && car.speed > 2) car.bearing.toDouble() else null,
            arabic = arabic, avoidHaram = RouteChain.avoidHaramOn)) { a ->
            if (ask != routeAsk) return@ask // (this screen has asked again: the newer one's answer is the one, and it is still routing)
            // (another asker took the engine: no answer yet, not a failure)
            if (a.status == RouteAnswer.CANCELLED) {
                Router.afterOthers {
                    if (ask == routeAsk) {
                        routing = false
                        if (!panel.isGone && selected?.pos == p.pos && preview == null && following == null && NavSession.state == NavSession.State.IDLE) requestRoute()
                    }
                }
                return@ask
            }
            com.abdllh.aura.diag.TripRecorder.route("route", SystemClock.elapsedRealtime() - asked, a.route != null) // (only while it records)
            routing = false
            // (the same spot: a dropped pin is replaced by its named place while the route is on its way)
            if (panel.isGone || selected?.pos != p.pos) return@ask
            val r = a.route
            if (r == null) { routeFailed(failureOf(a)); return@ask }
            resetRouteRetry()
            root.removeCallbacks(firstOverview)
            options = RouteChoices.offer(a.routes)
            chosen = 0
            preview = r
            haramInside = a.haramInside
            showRoutes()
            drawChoice()
            // + the place itself: the route ends on the nearest road, the pin can stand well off it
            panel.overview(options.flatMap { it.route.line.points } + p.pos)
        }
    }

    private fun failureOf(a: RouteAnswer): String =
        if (a.status == RouteAnswer.NO_ROUTE) getString(R.string.browse_no_route) else a.userMessage(this)

    /** The card as [options] say, with the chosen one's arrival and warning. */
    private fun showRoutes() {
        val bidi = android.text.BidiFormatter.getInstance(arabic)
        placeCard.showRoutes(options.map { o ->
            val far = Units.distance(o.route.distance, arabic)
            Units.duration(o.route.duration, arabic) to if (o.road.isEmpty()) far else "$far · ${bidi.unicodeWrap(o.road)}"
        }, chosen)
        syncChoiceLines()
    }

    private fun syncChoiceLines() {
        val r = preview ?: return
        placeCard.setArrival("${getString(R.string.browse_arrive)} ${Units.arrival(r.duration, Prefs.clock24, arabic)}")
        placeCard.warn(warningOf(r))
    }

    /** What a driver must hear before Go: why the trip might not go as drawn ([com.abdllh.aura.nav.guide.TripCheck]). */
    private fun warningOf(r: Route): String? {
        r.guide?.warnings?.firstOrNull()?.let { return Speech.text(it.parts(), arabic, said = false) }
        return if (haramInside) getString(R.string.browse_haram_inside) else null
    }

    /** A route's words have come, a moment after it: its warnings. */
    private val guideLanded: (Route) -> Unit = { r -> if (r === preview) placeCard.warn(warningOf(r)) }

    /** He chose the other route, on the card or on the map. The camera stays: it has both in view. */
    private fun choose(i: Int) {
        if (i !in options.indices || i == chosen) return
        chosen = i
        preview = options[i].route
        placeCard.select(i)
        syncChoiceLines()
        drawChoice()
    }

    private fun drawChoice() {
        val r = preview ?: return
        map.setRoute(r.line.points)
        map.setRouteAlt(options.getOrNull(1 - chosen)?.route?.line?.points)
    }

    /** What is going on (finding the route, waiting for GPS), Go dimmed until there is a route. */
    private fun routeStatus(s: String) = placeCard.showComputing(s, spin = !DriveState.moving)

    private fun clearPlace() {
        // (the card of a trip being started from elsewhere, closed: that trip is off, and its asker hears so)
        if (following != null) { following = null; followingRoute = null; routing = false; Trips.cancel("closed on the map") }
        selected = null
        preview = null
        options = emptyList()
        resetRouteRetry()
        root.removeCallbacks(firstOverview)
        root.removeCallbacks(countdown)
        placeCard.hideCard()
        phoneCard.hideCard()
        syncOver()
        input.setText("")
        map.setDestination(null)
        map.setRoute(null)
        map.setRouteAlt(null)
        panel.tools.recenter()
    }

    private fun go() {
        val p = selected ?: return
        val r = preview
        if (r == null) {
            panel.toast(getString(if (CarLocation.last == null) R.string.browse_waiting_gps else R.string.browse_computing))
            return
        }
        Trips.start(panel, p, r, remember = notKept != p.pos)
        afterStart()
    }

    /** A trip has started: from the card to the trip's panels. */
    private fun afterStart() {
        if (BuildConfig.DEBUG && simulate) NavSim.start(70.0)
        selected = null
        preview = null
        options = emptyList()
        root.removeCallbacks(firstOverview)
        root.removeCallbacks(countdown)
        placeCard.hideCard()
        phoneCard.hideCard()
        map.setRoute(null)
        map.setRouteAlt(null)
        map.setDestination(null)
        panel.tools.hideRecenter()
        map.setCamera(AuraMap.Camera.NAVIGATE)
        panel.syncNav()
    }

    /** Home/Work: route and start right away (like pressing the button in a car). */
    private fun driveTo(p: Place) {
        if (CarLocation.last == null) { select(p); return }
        selected = p
        preview = null // (another place's route must not stay behind this one's Go, should no route come)
        options = emptyList()
        resetRouteRetry()
        placeCard.setPlace(p.name, p.detail)
        routeStatus(getString(R.string.browse_computing))
        placeCard.showCard()
        syncOver()
        routing = true
        // (Back pressed meanwhile: no navigation the driver can no longer see starting)
        val here = { !panel.isGone && !panel.isOff && selected?.pos == p.pos }
        Trips.goTo(panel, p, source = Trips.SCREEN, wanted = here) { outcome, _ ->
            routing = false
            when (outcome) {
                Trips.Outcome.STARTED -> { resetRouteRetry(); afterStart() }
                Trips.Outcome.FAILED, Trips.Outcome.NO_FIX -> if (here()) routeFailed(getString(R.string.browse_no_route))
                Trips.Outcome.DROPPED -> {}
            }
        }
    }

    // ------------------------------------------------------------------------------------------ search
    private fun onQuery(q: String) {
        if (q.trim().length < 2) { Places.cancelSearch(); root.removeCallbacks(searchFit); map.setResults(emptyList()); lastResults = emptyList(); showSuggestions(); return }
        runSearch(q)
    }

    /** The rows there stay until the answer replaces them: a list that empties on every letter made the card collapse and grow. The engine's come at once, Photon's after them. */
    private fun runSearch(q: String) {
        // near the car, or near where the map is looked at when there is no fix yet
        val near = CarLocation.lastKnown() ?: map.target
        Places.typing(q, near, arabic) { list, last ->
            if (!search.isOn) return@typing
            when {
                list == null -> { showSuggestions(getString(R.string.browse_no_internet)); showPins(emptyList()) }
                list.isEmpty() -> { if (last) { search.show(emptyList(), getString(R.string.browse_nothing)); showPins(emptyList()) } }
                else -> {
                    search.show(list.mapIndexed { i, p ->
                        ResultLine(p.name, p.detail, near?.let { Units.distance(Geo.distance(it, p.pos), arabic) }, i + 1, 0) { select(p) }
                    })
                    showPins(list)
                }
            }
            if (last && list != null && list.isNotEmpty()) settleCamera()
        }
    }

    /** The rows' numbers on the map: what the panel shows and what is scrolled to, the first ten, so a pin always has a row. */
    private fun showPins(list: List<Place>) {
        lastResults = list
        map.setResults(list.take(PINS).map { it.pos })
    }

    /** The answer has settled, and he has stopped for a moment: the car and the first rows in view (map-*.json's `search`). A flight for every letter would read as the map stuttering. */
    private fun settleCamera() {
        root.removeCallbacks(searchFit)
        root.postDelayed(searchFit, SEARCH_FIT_MS)
    }

    private val searchFit = Runnable {
        if (!search.isOn) return@Runnable
        val car = CarLocation.lastKnown()
        val pts = ArrayList<LatLon>()
        // (a namesake 800 km away is a row, not something to fly out for: what is near, or the first row when nothing is)
        lastResults.take(3).filter { car == null || Geo.distance(car, it.pos) < SEARCH_NEAR_M }.mapTo(pts) { it.pos }
        if (car != null) pts.add(car)
        if (pts.size <= 1) lastResults.firstOrNull()?.takeIf { car == null || Geo.distance(car, it.pos) < SEARCH_NEAR_M }?.let { pts.add(it.pos) }
        if (pts.size <= 1 && car != null && lastResults.isNotEmpty()) return@Runnable
        if (pts.isEmpty()) return@Runnable
        searchFlew = true
        // (a keyboard over the map: what lies above it)
        root.getWindowVisibleDisplayFrame(frame)
        map.overview(pts, 410, 64, 60, maxOf(110, panel.shell.root.restHeight - frame.bottom + 20), 14.5)
        credit.text = MapStyle.attribution(pts.first())
    }

    /** With an empty query, his places as the panel opens (section 5): Home, Work and the last place he drove to, each straight to a trip. Not saved yet: its row says so, and opens the pick flow. [note] above them. */
    private fun showSuggestions(note: String? = null) {
        val home = Trips.saved("home")
        val work = Trips.saved("work")
        val lines = ArrayList<ResultLine>(3)
        lines.add(placeRow(getString(R.string.browse_home), R.drawable.ic_home, "home", home))
        lines.add(placeRow(getString(R.string.browse_work), R.drawable.ic_work, "work", work))
        Places.recents.firstOrNull { r -> listOfNotNull(home, work).none { Geo.distance(it.pos, r.pos) < SAME_PLACE_M } }?.let { last ->
            lines.add(ResultLine(last.name, "", null, 0, R.drawable.ic_pin) { closeSearch(); driveTo(last) })
        }
        search.show(lines, note)
    }

    private fun placeRow(title: String, icon: Int, which: String, saved: Place?) =
        ResultLine(title, if (saved == null) getString(R.string.browse_set_place) else "", null, 0, icon) {
            closeSearch()
            if (saved != null) driveTo(saved) else { pickFor = which; showPickHint() }
        }

    // ------------------------------------------------------------------------------------------ UI
    override fun build() {
        val layer = panel.browseLayer
        credit = label(Type.attribution, { Palette.ink3 }, gravity = Gravity.RIGHT).apply {
            textDirection = View.TEXT_DIRECTION_LTR
            text = MapStyle.attribution(CarLocation.lastKnown())
        }
        layer.addView(credit, Type.attribution.box(508, 490, 500))
        group = BrowseGroup(this, layer)
        group.hint(getString(R.string.home_where_to))
        group.onPill = { pillTapped() }

        noGps = NoGpsCard(this).apply { visibility = View.GONE }
        layer.addView(noGps, Surface.CARD.at(16, 112, 364, 92))

        search = SearchPanel(this).apply { visibility = View.GONE }
        layer.addView(search, Surface.CARD.at(16, 48, 364, 68))
        input.setOnFocusChangeListener { _, f ->
            panel.shell.panels.softInputChanged(panel) // (before the keyboard)
            if (f) openSearch()
        }
        input.setOnClickListener { if (DriveState.moving) pillTapped() else openSearch() }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (input.hasFocus()) onQuery(s?.toString().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        input.setOnEditorActionListener { _, action, ev ->
            if (action == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                if (input.text.trim().length >= 2) runSearch(input.text.toString())
                hideKeyboard() // the keyboard covers most of a car screen: show the results
                true
            } else false
        }

        placeCard = PlaceCard(this).apply { visibility = View.GONE }
        layer.addView(placeCard, Surface.CARD.at(16, 48, 364, 410))
        placeCard.close.setOnClickListener { clearPlace() }
        placeCard.save.setOnClickListener { v ->
            // (Home or Work being chosen, the field says which: the star keeps it as that at once)
            pickFor?.let { savePlace(it); return@setOnClickListener }
            Menu.show(v, listOf(
                Menu.Item(R.drawable.ic_home, getString(R.string.browse_home)) { savePlace("home") },
                Menu.Item(R.drawable.ic_work, getString(R.string.browse_work)) { savePlace("work") }))
        }
        placeCard.onChoice = { i -> choose(i) }
        placeCard.go.setOnClickListener { go() }
        if (BuildConfig.DEBUG) placeCard.go.setOnLongClickListener { simulate = true; go(); true }
        placeCard.retry.setOnClickListener { resetRouteRetry(); requestRoute() }

        phoneCard = FromPhoneCard(this).apply { visibility = View.GONE }
        layer.addView(phoneCard, Surface.CARD.at(16, 48, 364, 284))
        phoneCard.startNow.setOnClickListener { Trips.startNow() }
        phoneCard.cancel.setOnClickListener { clearPlace() }
        syncGroup()
    }

    /** Search or place card in: alpha, y -8 to 0, 180 `enter`, .7 of that moving (SPEC.md section 9). As one layer: a big surface. */
    private fun View.showCard() {
        if (isOn) return
        enter(dy = -8f, dur = cardMs(Tokens2.MS_cardIn))
    }

    /** Out: alpha, 120 `exit`. */
    private fun View.hideCard() {
        if (visibility != View.VISIBLE || getTag(R.id.aura_exiting) == true) return
        if (!isAttachedToWindow || !isShown) { hideNow(); return }
        exit(dur = cardMs(Tokens2.MS_cardOut)) { visibility = View.GONE }
    }

    private fun View.hideNow() {
        animate().cancel()
        setTag(R.id.aura_exiting, null)
        alpha = 1f
        translationY = 0f
        visibility = View.GONE
    }

    private fun cardMs(ms: Long) = if (DriveState.moving) (ms * 0.7f).toLong() else ms

    private companion object {
        /** Where the search's list starts on the screen, and what the card and a margin take under it above a keyboard. */
        const val SEARCH_LIST_TOP = 48 + 68
        const val SEARCH_LIST_FOOT = 16
        /** A place within this of a saved one is that one. */
        const val SAME_PLACE_M = 60.0
        /** The GPS gives a fix every 2 s on the unit. This long with none, and browse says so; */
        const val NO_GPS_MS = 30_000L
        /** its line is in minutes. */
        const val GPS_LINE_MS = 30_000L
        const val FIRST_OVERVIEW_MS = 700L
        const val SEARCH_FIT_MS = 700L
        const val PINS = 10
        const val SEARCH_NEAR_M = 60_000.0
    }
}
