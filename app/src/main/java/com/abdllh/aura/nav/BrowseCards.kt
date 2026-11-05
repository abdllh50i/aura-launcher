package com.abdllh.aura.nav

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
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
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lift
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

/**
 * Search, its results and the place's card over [MapsPanel], with the visit's logic: what was chosen, its route, Go, Home and Work, what the phone sent. Today's look, for browse's builder
 * to replace (SPEC.md section 6.3); the logic above "UI" stays. It reaches the rest only through [MapsPanel.cardOverColumn], [MapsPanel.overview], [MapsPanel.syncNav] and panel.tools.
 */
internal class BrowseCards(panel: MapsPanel) : MapsPart(panel) {
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

    private var selected: Place? = null
    private var preview: Route? = null
    private var routing = false
    private var routeAsk = 0
    private var searchRunnable: Runnable? = null
    private var pickFor: String? = null
    private var finishAfterPick = false
    /** The place sent here that must not go to the recent places when Go starts it ([MapsActivity.EXTRA_KEEP]). */
    private var notKept: LatLon? = null
    /** Debug builds: the trip this starts drives itself. */
    var simulate = false

    /** He is choosing a place or searching: no new look under his hands, no `drive-free`. */
    val inUse: Boolean get() = resultsCard.isOn || selected != null || pickFor != null || input.hasFocus() || routing

    /** The place card, for the old locate button's lift. */
    val bottomCard: View? get() = if (placeCard.isOn) placeCard else null

    init {
        // a new size of the screen (the keyboard came or went): the results measure their room again
        root.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot && resultsCard.isOn) resultsCard.post { resultsCard.requestLayout() } }
    }

    private fun syncOver() = panel.cardOverColumn(resultsCard.isOn || placeCard.isOn)

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
                            if (!panel.isTop) return@findLegacy
                            if (p == null) { panel.toast(getString(R.string.maps_no_results)); openSearch(); return@findLegacy }
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

    fun onBack(): Boolean = when {
        resultsCard.isOn -> { closeSearch(); true }
        selected != null && NavSession.state == NavSession.State.IDLE -> { clearPlace(); true }
        else -> false
    }

    /** Back or the Home key took the panel off the stack: what the visit chose. */
    fun forgetVisit() {
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
        resetRouteRetry()
        map.setRoute(null)
        map.setDestination(null)
    }

    /** As it was built, at rest and not seen. */
    fun fresh() {
        input.clearFocus()
        input.setHint(R.string.home_where_to)
        input.setText("")
        spinner.visibility = View.GONE
        results.alpha = 1f
        results.removeAllViews()
        resultsCard.reveal(false, -8f, animate = false)
        placeCard.reveal(false, 16f, animate = false)
        syncClear(animate = false)
        syncOver()
    }

    fun destroy() {
        searchRunnable?.let { root.removeCallbacks(it) }
        root.removeCallbacks(firstOverview)
        Places.cancelSearch()
    }

    override fun sync() {
        val st = NavSession.state
        val navigating = st == NavSession.State.NAVIGATING
        val active = st != NavSession.State.IDLE
        // (a trip started from elsewhere while this screen was away: nothing chosen earlier stays behind it)
        if (navigating && selected != null && !routing && following == null) { selected = null; preview = null; map.setRoute(null); map.setDestination(null) }
        searchCard.reveal(!active, -12f)
        // (a trip started from outside while the search was open: its keyboard-time fade must not hide the speed)
        if (active) {
            resultsCard.reveal(false, -8f)
            placeCard.reveal(false, 16f)
            panel.tools.forSearch(false)
            syncOver()
        }
    }

    override fun tripChanged(on: Boolean) {
        if (on) hideKeyboard() // (once: it is a call to the keyboard's service)
        else if (selected == null) input.setText("")
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
            placeName.setIfChanged(named.name)
            placeDetail.setIfChanged(named.detail)
            placeDetail.visibility = if (named.detail.isBlank()) View.GONE else View.VISIBLE
            input.setText(named.name)
        }
    }

    fun poiTapped(name: String, kind: String, at: LatLon) {
        if (NavSession.state == NavSession.State.IDLE) select(Place(name, Poi.label(this, kind), at))
    }

    /** The preview route once a fix is there; a failed one is retried with growing pauses, not on every fix. */
    fun onFix() {
        // (not for a place whose trip is being started from elsewhere: [Trips] is finding its route)
        if (selected != null && preview == null && !routing && following == null && NavSession.state == NavSession.State.IDLE &&
            SystemClock.elapsedRealtime() >= routeRetryAt) requestRoute()
    }

    // ------------------------------------------------------------------------------------------ the place
    /** Choosing Home / Work: the search field says how, for as long as it takes. */
    private fun showPickHint() {
        val which = pickFor ?: return
        input.setHint(if (which == "work") R.string.maps_pick_work_short else R.string.maps_pick_home_short)
        panel.toast(getString(if (which == "work") R.string.maps_pick_work else R.string.maps_pick_home))
    }

    /** Saved as Home / Work; when Maps was opened just to choose it, back to where the user came from. */
    private fun savePlace(which: String) {
        val p = selected ?: return
        if (which == "home") Places.home = p else Places.work = p
        panel.toast(getString(R.string.maps_saved), outlives = finishAfterPick)
        pickFor = null
        input.setHint(R.string.home_where_to)
        if (finishAfterPick) { finishAfterPick = false; panel.finish() }
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

    /** The place of a trip being started from elsewhere (the phone, the voice), while this screen shows it. */
    private var following: Place? = null

    /**
     * A trip being started from elsewhere ([Trips]): while it has no route yet its place is on the card as if it were
     * chosen here, with what is going on; closing the card drops it. This screen's own [driveTo] shows itself.
     */
    fun syncAttempt() {
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

    private fun openSearch() {
        if (NavSession.state != NavSession.State.IDLE) return
        resultsCard.reveal(true, -8f)
        placeCard.reveal(false, 16f)
        panel.tools.forSearch(true)
        input.requestFocus()
        input.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) }
        if (input.text.trim().length < 2) showSuggestions()
        syncOver()
    }

    private fun closeSearch() {
        // a search still under way must not fill the list later (over Home / Work / recents)
        searchRunnable?.let { root.removeCallbacks(it) }
        Places.cancelSearch()
        searching(false)
        resultsCard.reveal(false, -8f)
        hideKeyboard()
        input.clearFocus()
        panel.tools.forSearch(false)
        if (selected != null) placeCard.reveal(true, 16f, delay = 60)
        syncOver()
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
        syncOver()
        map.setDestination(p.pos)
        map.setRoute(null)
        // The place at once; the place with the car once the route has had a moment to come (it brings its own
        // overview): two flights one after the other read as the map stuttering.
        root.removeCallbacks(firstOverview)
        if (CarLocation.last == null) panel.overview(listOf(p.pos))
        else root.postDelayed(firstOverview, 700)
    }

    private val firstOverview = Runnable {
        val p = selected ?: return@Runnable
        val car = CarLocation.last
        panel.overview(listOfNotNull(p.pos, car?.let { LatLon(it.latitude, it.longitude) }))
    }

    private fun requestRoute() {
        val p = selected ?: return
        val car = CarLocation.last
        if (car == null) { routeStatus(getString(R.string.maps_waiting_gps)); return }
        routing = true
        routeStatus(getString(R.string.maps_routing))
        val ask = ++routeAsk
        Router.route(LatLon(car.latitude, car.longitude), p.pos, if (car.hasBearing() && car.speed > 2) car.bearing.toDouble() else null) { r, err ->
            if (ask != routeAsk) return@route // (this screen has asked again: the newer one's answer is the one, and it is still routing)
            // (another asker took the engine: no answer yet, not a failure)
            if (err == Router.NO_ANSWER_YET) {
                Router.afterOthers {
                    if (ask == routeAsk) {
                        routing = false
                        if (!panel.isGone && selected?.pos == p.pos && preview == null && following == null && NavSession.state == NavSession.State.IDLE) requestRoute()
                    }
                }
                return@route
            }
            routing = false
            // (the same spot: a dropped pin is replaced by its named place while the route is on its way)
            if (panel.isGone || selected?.pos != p.pos) return@route
            if (r == null) { routeFailed(); return@route }
            resetRouteRetry()
            preview = r
            root.removeCallbacks(firstOverview)
            showPreview(r)
            map.setRoute(r.line.points)
            // + the place itself: the route ends on the nearest road, the pin can stand well off it
            panel.overview(r.line.points + p.pos)
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
        syncOver()
        input.setText("")
        map.setDestination(null)
        map.setRoute(null)
        panel.tools.recenter()
    }

    private fun go() {
        val p = selected ?: return
        val r = preview
        if (r == null) {
            panel.toast(getString(if (CarLocation.last == null) R.string.maps_waiting_gps else R.string.maps_routing))
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
        root.removeCallbacks(firstOverview)
        panel.tools.hideRecenter()
        map.setCamera(AuraMap.Camera.NAVIGATE)
        panel.syncNav()
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
        syncOver()
        routing = true
        // (Back pressed meanwhile: no navigation the driver can no longer see starting)
        val here = { panel.isTop && selected?.pos == p.pos }
        Trips.goTo(panel, p, source = Trips.SCREEN, wanted = here) { outcome, _ ->
            routing = false
            when (outcome) {
                Trips.Outcome.STARTED -> { resetRouteRetry(); afterStart() }
                Trips.Outcome.FAILED, Trips.Outcome.NO_FIX -> if (here()) routeFailed()
                Trips.Outcome.DROPPED -> {}
            }
        }
    }

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
    override fun build() {
        // ---- start column: search / results
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Shapes.glass(Radius.PILL)
            lift(Elev.FLOAT, overMap = true)
        }
        searchCard.addView(roundIcon(R.drawable.ic_chevron_left, Touch.MIN, R.string.maps_close, flipRtl = true) { panel.shell.panels.back() },
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
        col.addView(searchCard, lp(panel.cardW, 68.dp))

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
        col.addView(resultsCard, lp(panel.cardW, WRAP).apply { topMargin = Space.M.dp })
        panel.browseLayer.addView(col, flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(Space.L.dp, MapsPanel.TOP_EDGE.dp, Space.L.dp, MapsPanel.BOTTOM_EDGE.dp); marginStart = Space.L.dp })

        // ---- bottom start: the place card, in one place with the trip's
        placeCard = buildPlaceCard()
        panel.browseLayer.addView(placeCard, bottomCard())
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

    private companion object {
        /** A search result's height. */
        private const val ROW_DP = 74
    }
}
