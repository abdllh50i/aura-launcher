package com.abdllh.aura.nav

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.elevate
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp
import java.util.Locale

/**
 * Aura's own maps: search a place, see the route, drive with turn-by-turn guidance. Full screen MapLibre map with
 * glass panels on top, sized for a car screen.
 *  extras: "search" = open the search; "go" = "home" | "work" to drive there at once; "simulate" (debug builds).
 */
class MapsActivity : Activity() {
    private lateinit var map: AuraMap
    private lateinit var root: FrameLayout
    private lateinit var searchCard: LinearLayout
    private lateinit var input: EditText
    private lateinit var clearBtn: View
    private lateinit var results: LinearLayout
    private lateinit var resultsCard: ScrollView
    private lateinit var placeCard: LinearLayout
    private lateinit var placeName: AText
    private lateinit var placeDetail: AText
    private lateinit var placeRoute: AText
    private lateinit var goBtn: AText
    private lateinit var banner: LinearLayout
    private lateinit var bannerIcon: ManeuverView
    private lateinit var bannerDist: AText
    private lateinit var bannerText: AText
    private lateinit var bannerThen: AText
    private lateinit var tripBar: LinearLayout
    private lateinit var tripEta: AText
    private lateinit var tripLeft: AText
    private lateinit var speedBubble: LinearLayout
    private lateinit var speedText: AText
    private lateinit var recenterBtn: View
    private lateinit var compassBtn: ImageView
    private lateinit var voiceBtn: ImageView
    private lateinit var arrivedCard: LinearLayout

    private var selected: Place? = null
    private var preview: Route? = null
    private var routing = false
    private val arabic get() = Locale.getDefault().language == "ar"
    private val rtl get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    private var searchRunnable: Runnable? = null
    private var lastRouteDraw = 0L
    private var drawnRoute: Route? = null
    private var drawnAlong = 0.0
    private var tripShown = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.refreshLocale(this)
        Theme.refresh()
        Theme.window(this, Palette.bg)
        map = AuraMap(this, interactive = true, texture = false)
        map.startPadding = panelWidth() + 32.dp // the car sits in the part of the map the panels leave free
        map.onCreate(savedInstanceState)
        setContentView(buildUi())
        map.onUserMoved = { recenterBtn.visibility = View.VISIBLE }
        map.onLongPress = { p -> dropPin(p) }
        map.onReady = { syncNav(); CarLocation.last?.let { onFix(it) } }
        if (!CarLocation.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (BuildConfig.DEBUG && i?.hasExtra("no3d") == true) MapStyle.buildings3d = false
        if (BuildConfig.DEBUG) i?.getStringExtra("mock_loc")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.let { ll ->
            // emulator testing:  am start -n com.abdllh.aura/.nav.MapsActivity --es mock_loc "24.7136,46.6753"
            if (ll.size >= 2) CarLocation.publish(Location("mock").apply {
                latitude = ll[0]; longitude = ll[1]; accuracy = 5f; time = System.currentTimeMillis()
                if (ll.size >= 3) bearing = ll[2].toFloat()
            })
        }
        when (val which = i?.getStringExtra("go")) {
            "home", "work" -> {
                val saved = if (which == "home") Places.home else Places.work
                val legacy = if (which == "home") Prefs.homeAddress else Prefs.workAddress
                when {
                    saved != null -> driveTo(saved)
                    legacy.isNotBlank() -> { // an address typed in 1.0: find it once and keep the place
                        toast(getString(R.string.maps_searching))
                        Places.geocode(legacy, CarLocation.lastKnown(), arabic) { p ->
                            if (isDestroyed || isFinishing) return@geocode
                            if (p == null) { toast(getString(R.string.maps_no_results)); openSearch(); return@geocode }
                            if (which == "home") Places.home = p else Places.work = p
                            driveTo(p)
                        }
                    }
                    else -> openSearch()
                }
            }
        }
        if (i?.getBooleanExtra("search", false) == true) openSearch()
        if (BuildConfig.DEBUG) i?.getStringExtra("q")?.let { q -> openSearch(); input.setText(q); runSearch(q); hideKeyboard() } // tests
        i?.getStringExtra("pick")?.let { which -> // Home / Work chosen on the map, then back to the home screen
            pickFor = which
            finishAfterPick = true
            showPickHint()
        }
        i?.removeExtra("go")
        i?.removeExtra("search")
        i?.removeExtra("pick")
    }

    private var pickFor: String? = null
    private var finishAfterPick = false

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
        val pin = selected
        Places.reverse(p, arabic) { found ->
            if (isDestroyed || selected !== pin || found == null) return@reverse
            val named = Place(found.name, found.detail, p)
            selected = named
            placeName.text = named.name
            placeDetail.text = named.detail
            placeDetail.visibility = if (named.detail.isBlank()) View.GONE else View.VISIBLE
            input.setText(named.name)
        }
    }

    /** Saved as Home / Work; when Maps was opened just to choose it, back to where the user came from. */
    private fun savePlace(which: String) {
        val p = selected ?: return
        if (which == "home") Places.home = p else Places.work = p
        toast(getString(R.string.maps_saved))
        pickFor = null
        input.setHint(R.string.home_where_to)
        if (finishAfterPick) { finishAfterPick = false; finish() }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        if (CarLocation.hasPermission(this)) CarLocation.acquire(this, "maps")
        else toast(getString(R.string.maps_location_needed))
    }

    // ------------------------------------------------------------------------------------------ lifecycle
    override fun onStart() {
        super.onStart()
        map.onStart()
        CarLocation.acquire(this, "maps")
        CarLocation.addListener(fixListener)
        NavSession.addListener(navListener)
        syncNav()
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }

    override fun onStop() {
        CarLocation.removeListener(fixListener)
        CarLocation.release("maps")
        NavSession.removeListener(navListener)
        map.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        searchRunnable?.let { root.removeCallbacks(it) }
        Places.cancelSearch()
        map.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() { super.onLowMemory(); map.onLowMemory() }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) map.onLowMemory() // the map engine drops its tile caches
    }

    @Deprecated("back steps out of search / preview first")
    override fun onBackPressed() {
        when {
            resultsCard.visibility == View.VISIBLE -> closeSearch()
            selected != null && NavSession.state == NavSession.State.IDLE -> clearPlace()
            else -> finish()
        }
    }

    // ------------------------------------------------------------------------------------------ location / nav
    private val fixListener: (Location) -> Unit = { onFix(it) }

    private fun onFix(l: Location) {
        if (NavSession.state != NavSession.State.NAVIGATING) { // (navigation moves the car itself, on the route)
            val speed = if (l.hasSpeed()) l.speed * 3.6 else 0.0
            map.setCar(LatLon(l.latitude, l.longitude), if (l.hasBearing()) l.bearing.toDouble() else 0.0, speed)
        }
        // the preview route once a fix is there; a failed one is retried with growing pauses, not on every fix
        if (selected != null && preview == null && !routing && NavSession.state == NavSession.State.IDLE &&
            SystemClock.elapsedRealtime() >= routeRetryAt) requestRoute()
        goBtn.alpha = if (CarLocation.last != null) 1f else 0.5f
    }

    private var routeFails = 0
    private var routeRetryAt = 0L

    private fun routeFailed() {
        routeFails++
        routeRetryAt = SystemClock.elapsedRealtime() + (5_000L shl (routeFails - 1).coerceAtMost(4)) // 5 s ... 80 s
        placeRoute.text = getString(R.string.maps_route_failed)
    }

    private fun resetRouteRetry() {
        routeFails = 0
        routeRetryAt = 0L
    }

    private val navListener: () -> Unit = { syncNav() }

    /** Shows the navigation UI for NavSession's state. */
    private fun syncNav() {
        if (!::banner.isInitialized) return
        val st = NavSession.state
        val navigating = st != NavSession.State.IDLE
        map.setFps(if (st == NavSession.State.NAVIGATING) 60 else 30) // a turning, tilted map needs the frames
        searchCard.visibility = if (navigating) View.GONE else View.VISIBLE
        if (navigating) { resultsCard.visibility = View.GONE; placeCard.visibility = View.GONE; hideKeyboard() }
        banner.visibility = if (st == NavSession.State.NAVIGATING) View.VISIBLE else View.GONE
        tripBar.visibility = if (st == NavSession.State.NAVIGATING) View.VISIBLE else View.GONE
        speedBubble.visibility = if (st == NavSession.State.NAVIGATING) View.VISIBLE else View.GONE
        (voiceBtn.tag as? View ?: voiceBtn).visibility = if (navigating) View.VISIBLE else View.GONE // the round button around the icon
        arrivedCard.visibility = if (st == NavSession.State.ARRIVED) View.VISIBLE else View.GONE
        val voice = NavSession.voiceOn && !NavSession.voiceMissing
        voiceBtn.setImageResource(if (voice) R.drawable.ic_volume else R.drawable.ic_volume_off)
        if (navigating && NavSession.voiceOn && NavSession.voiceMissing && !voiceHintShown) {
            voiceHintShown = true
            toast(getString(R.string.maps_voice_missing))
        }
        if (!navigating) {
            if (tripShown) { // the trip has just ended (Done, End, or by itself after arriving): back to browsing
                tripShown = false
                map.setRoute(preview?.line?.points)
                map.setDestination(selected?.pos)
                if (selected == null) input.setText("")
                recenterBtn.visibility = View.GONE
                map.setCamera(AuraMap.Camera.FOLLOW)
            }
            return
        }
        tripShown = true
        val r = NavSession.route ?: return
        val p = NavSession.progress
        map.setDestination(NavSession.destination?.pos)
        if (map.camera != AuraMap.Camera.FREE && map.camera != AuraMap.Camera.OVERVIEW && map.camera != AuraMap.Camera.NAVIGATE) {
            map.setCamera(AuraMap.Camera.NAVIGATE)
        }
        if (p == null) { map.setRoute(r.line.points); return }
        map.setCar(p.position, p.bearing, p.speedKmh.toDouble())
        // the part still ahead: a new route at once, otherwise every 150 m or 10 s (long routes are big to rebuild)
        val now = SystemClock.elapsedRealtime()
        if (r !== drawnRoute || p.along - drawnAlong > 150 || now - lastRouteDraw > 10_000) {
            drawnRoute = r
            drawnAlong = p.along
            lastRouteDraw = now
            map.setRoute(r.line.tail(p.along))
        }
        val step = r.steps[p.next]
        bannerIcon.turn = Instructions.turn(step)
        bannerIcon.exit = step.exit
        bannerDist.text = if (NavSession.rerouting) "…" else Units.distance(p.toNext, arabic)
        bannerText.text = if (NavSession.rerouting) getString(R.string.maps_rerouting) else Instructions.text(step, arabic)
        val then = r.steps.getOrNull(p.next + 1)
        if (then != null && then.type != "arrive" && then.at - step.at < 250) {
            bannerThen.visibility = View.VISIBLE
            bannerThen.text = "${getString(R.string.maps_then)}: ${Instructions.text(then, arabic)}"
        } else bannerThen.visibility = View.GONE
        tripEta.text = Units.arrival(p.timeLeft, Prefs.clock24)
        tripLeft.text = "${Units.duration(p.timeLeft, arabic)} · ${Units.distance(p.remaining, arabic)}"
        speedText.text = "${p.speedKmh}"
    }

    // ------------------------------------------------------------------------------------------ actions
    private fun openSearch() {
        if (NavSession.state != NavSession.State.IDLE) return
        resultsCard.visibility = View.VISIBLE
        placeCard.visibility = View.GONE
        input.requestFocus()
        input.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) }
        showSuggestions()
    }

    private fun closeSearch() {
        // a search still under way must not fill the list later (over Home / Work / recents)
        searchRunnable?.let { root.removeCallbacks(it) }
        Places.cancelSearch()
        resultsCard.visibility = View.GONE
        hideKeyboard()
        input.clearFocus()
        if (selected != null) placeCard.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        try { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0) } catch (_: Throwable) { }
    }

    private fun select(p: Place) {
        selected = p
        preview = null
        resetRouteRetry()
        closeSearch()
        input.setText(p.name)
        placeName.text = p.name
        placeDetail.text = p.detail
        placeDetail.visibility = if (p.detail.isBlank()) View.GONE else View.VISIBLE
        placeRoute.text = getString(R.string.maps_routing)
        placeCard.visibility = View.VISIBLE
        map.setDestination(p.pos)
        map.setRoute(null)
        val car = CarLocation.last
        map.overview(listOfNotNull(p.pos, car?.let { LatLon(it.latitude, it.longitude) }), panelWidth() + 40.dp, 90.dp, 150.dp, 60.dp)
        requestRoute()
    }

    private fun requestRoute() {
        val p = selected ?: return
        val car = CarLocation.last
        if (car == null) { placeRoute.text = getString(R.string.maps_waiting_gps); return }
        routing = true
        placeRoute.text = getString(R.string.maps_routing)
        Router.route(LatLon(car.latitude, car.longitude), p.pos, if (car.hasBearing() && car.speed > 2) car.bearing.toDouble() else null) { r, _ ->
            routing = false
            if (isDestroyed || selected !== p) return@route
            if (r == null) { routeFailed(); return@route }
            resetRouteRetry()
            preview = r
            placeRoute.text = "${Units.duration(r.duration, arabic)} · ${Units.distance(r.distance, arabic)} · ${getString(R.string.maps_arrival)} ${Units.arrival(r.duration, Prefs.clock24)}"
            map.setRoute(r.line.points)
            // + the place itself: the route ends on the nearest road, the pin can stand well off it
            map.overview(r.line.points + p.pos, panelWidth() + 40.dp, 90.dp, 150.dp, 60.dp)
        }
    }

    private fun clearPlace() {
        selected = null
        preview = null
        resetRouteRetry()
        placeCard.visibility = View.GONE
        input.setText("")
        map.setDestination(null)
        map.setRoute(null)
        map.setCamera(AuraMap.Camera.FOLLOW)
        recenterBtn.visibility = View.GONE
    }

    private fun go() {
        val p = selected ?: return
        val r = preview
        if (r == null) {
            toast(getString(if (CarLocation.last == null) R.string.maps_waiting_gps else R.string.maps_routing))
            return
        }
        NavSession.start(this, p, r)
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("simulate", false) == true) NavSim.start(70.0)
        selected = null
        preview = null
        recenterBtn.visibility = View.GONE
        drawnRoute = null
        map.setCamera(AuraMap.Camera.NAVIGATE)
        syncNav()
    }

    /** Home/Work: route and start right away (like pressing the button in a car). */
    private fun driveTo(p: Place) {
        val car = CarLocation.last
        if (car == null) { select(p); return }
        selected = p
        resetRouteRetry()
        placeName.text = p.name
        placeDetail.text = p.detail
        placeRoute.text = getString(R.string.maps_routing)
        placeCard.visibility = View.VISIBLE
        routing = true
        Router.route(LatLon(car.latitude, car.longitude), p.pos, if (car.hasBearing() && car.speed > 2) car.bearing.toDouble() else null) { r, _ ->
            routing = false
            // (Back pressed meanwhile: no navigation the driver can no longer see starting)
            if (isDestroyed || isFinishing || selected !== p) return@route
            if (r == null) { routeFailed(); return@route }
            resetRouteRetry()
            preview = r
            go()
        }
    }

    private fun endNav() {
        NavSession.stop()
        map.setRoute(null)
        map.setDestination(null)
        map.setCamera(AuraMap.Camera.FOLLOW)
        recenterBtn.visibility = View.GONE
        syncNav()
    }

    private fun panelWidth() = 460.dp

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------------------------------ search
    private fun onQuery(q: String) {
        clearBtn.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        searchRunnable?.let { root.removeCallbacks(it) }
        if (q.trim().length < 2) { Places.cancelSearch(); showSuggestions(); return }
        val r = Runnable { runSearch(q) }
        searchRunnable = r
        root.postDelayed(r, 350)
    }

    private fun runSearch(q: String) {
        results.removeAllViews()
        results.addView(note(getString(R.string.maps_searching)))
        val near = CarLocation.lastKnown()
        Places.search(q, near, arabic) { list ->
            if (resultsCard.visibility != View.VISIBLE) return@search
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
            results.addView(label(14f, Palette.text3, Fonts.MEDIUM).apply { setText(R.string.maps_recents); setPaddingRelative(20.dp, 14.dp, 20.dp, 4.dp) })
            for (p in rec) results.addView(row(R.drawable.ic_clock, p.name, p.detail, near?.let { Units.distance(Geo.distance(it, p.pos), arabic) }) { select(p) })
        }
    }

    // ------------------------------------------------------------------------------------------ UI
    private fun buildUi(): View {
        root = FrameLayout(this)
        root.setBackgroundColor(Palette.mapLand)
        root.addView(map.view, MATCH, MATCH)

        val attribution = label(10.5f, Palette.text3, Fonts.REGULAR).apply {
            text = MapStyle.ATTRIBUTION
            setPadding(8.dp, 3.dp, 8.dp, 3.dp)
            background = Shapes.rect(Palette.withAlpha(Palette.glass, 0.7f), 8f)
        }
        root.addView(attribution, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 12.dp, 8.dp); marginEnd = 12.dp })

        // ---- start column: search / results / guidance banner
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Shapes.glass(30f)
            elevate(8f)
        }
        searchCard.addView(roundIcon(R.drawable.ic_chevron_left, 60, flipRtl = true) { onBackPressed() }, lp(60.dp, 60.dp).apply { marginStart = 4.dp })
        input = EditText(this).apply {
            setHint(R.string.home_where_to)
            setHintTextColor(Palette.text3)
            setTextColor(Palette.text)
            textSize = 19f
            typeface = Fonts.get(Fonts.REGULAR)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = null
            setPadding(4.dp, 0, 4.dp, 0)
            setOnFocusChangeListener { _, f -> if (f) openSearch() }
            setOnClickListener { openSearch() }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { if (hasFocus()) onQuery(s?.toString().orEmpty()) }
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
        clearBtn = roundIcon(R.drawable.ic_close, 56) { input.setText(""); openSearch() }.apply { visibility = View.GONE }
        searchCard.addView(clearBtn, lp(56.dp, 56.dp).apply { marginEnd = 6.dp })
        col.addView(searchCard, lp(panelWidth(), 68.dp))

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8.dp, 0, 8.dp) }
        resultsCard = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) {
                val max = (root.height - 120.dp).coerceAtLeast(200.dp)
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(h).takeIf { it > 0 } ?: max, max), MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = Shapes.glass(26f)
            elevate(8f)
            visibility = View.GONE
            addView(results, MATCH, WRAP)
        }
        col.addView(resultsCard, lp(panelWidth(), WRAP).apply { topMargin = 10.dp })

        banner = buildBanner()
        col.addView(banner, lp(panelWidth(), WRAP))
        root.addView(col, flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(16.dp, 16.dp, 16.dp, 0); marginStart = 16.dp })

        // ---- bottom start: place card / trip bar / arrived
        placeCard = buildPlaceCard()
        root.addView(placeCard, flp(panelWidth(), WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(16.dp, 0, 16.dp, 16.dp); marginStart = 16.dp })
        tripBar = buildTripBar()
        root.addView(tripBar, flp(panelWidth(), WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(16.dp, 0, 16.dp, 16.dp); marginStart = 16.dp })
        arrivedCard = buildArrivedCard()
        root.addView(arrivedCard, flp(panelWidth(), WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(16.dp, 0, 16.dp, 16.dp); marginStart = 16.dp })

        // ---- end column: map buttons
        val btns = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        compassBtn = ImageView(this)
        btns.addView(mapButton(R.drawable.ic_compass, compassBtn) {
            if (NavSession.state == NavSession.State.NAVIGATING) map.setCamera(AuraMap.Camera.NAVIGATE) else map.northUp()
        })
        btns.addView(mapButton(R.drawable.ic_plus, null) { map.zoomBy(1.0) }, lp(66.dp, 66.dp).apply { topMargin = 12.dp })
        btns.addView(mapButton(R.drawable.ic_minus, null) { map.zoomBy(-1.0) }, lp(66.dp, 66.dp).apply { topMargin = 12.dp })
        voiceBtn = ImageView(this)
        btns.addView(mapButton(R.drawable.ic_volume, voiceBtn) {
            if (NavSession.voiceMissing) { // no text-to-speech engine on the unit: offer Google's (it speaks Arabic)
                toast(getString(R.string.maps_voice_missing))
                openSpeechStore()
                return@mapButton
            }
            NavSession.voiceOn = !NavSession.voiceOn
            voiceBtn.setImageResource(if (NavSession.voiceOn) R.drawable.ic_volume else R.drawable.ic_volume_off)
        }.also { (it as View).visibility = View.GONE; voiceBtn.tag = it }, lp(66.dp, 66.dp).apply { topMargin = 12.dp })
        // from the top, so the column (4 buttons while navigating) stays clear of the speed bubble below it
        root.addView(btns, flp(WRAP, WRAP, Gravity.END or Gravity.TOP).apply { setMargins(0, 24.dp, 16.dp, 0); marginEnd = 16.dp })

        recenterBtn = pill(R.drawable.ic_nav, R.string.maps_recenter) {
            recenterBtn.visibility = View.GONE
            map.setCamera(if (NavSession.state == NavSession.State.NAVIGATING) AuraMap.Camera.NAVIGATE else AuraMap.Camera.FOLLOW)
        }.apply { visibility = View.GONE }
        // beside the speed bubble on the free side: the cards on the start side never cover it
        root.addView(recenterBtn, flp(WRAP, 60.dp, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 126.dp, 56.dp); marginEnd = 126.dp })

        speedBubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Shapes.oval(Palette.glass)
            elevate(8f)
            visibility = View.GONE
        }
        speedText = label(30f, Palette.text, Fonts.MEDIUM, gravity = Gravity.CENTER)
        speedBubble.addView(speedText, lp(WRAP, WRAP))
        speedBubble.addView(label(12f, Palette.text2, Fonts.REGULAR, gravity = Gravity.CENTER).apply { setText(R.string.maps_kmh) }, lp(WRAP, WRAP))
        root.addView(speedBubble, flp(92.dp, 92.dp, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 18.dp, 40.dp); marginEnd = 18.dp })
        return root
    }

    private fun buildBanner(): LinearLayout {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Shapes.rect(if (Palette.dark) 0xF0181B20.toInt() else 0xF2FFFFFF.toInt(), 26f)
            elevate(10f)
            visibility = View.GONE
            setPadding(20.dp, 16.dp, 20.dp, 16.dp)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bannerIcon = ManeuverView(this).apply { color = Palette.accent }
        top.addView(bannerIcon, lp(80.dp, 80.dp))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bannerDist = label(36f, Palette.text, Fonts.MEDIUM)
        bannerText = label(19f, Palette.text2, Fonts.REGULAR, lines = 2)
        t.addView(bannerDist, lp(WRAP, WRAP))
        t.addView(bannerText, lp(MATCH, WRAP).apply { topMargin = 2.dp })
        top.addView(t, lp(0, WRAP, 1f).apply { marginStart = 16.dp })
        b.addView(top, lp(MATCH, WRAP))
        bannerThen = label(15f, Palette.text3, Fonts.MEDIUM).apply { visibility = View.GONE }
        b.addView(bannerThen, lp(MATCH, WRAP).apply { topMargin = 10.dp })
        return b
    }

    private fun buildPlaceCard(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Shapes.glass(26f)
            elevate(10f)
            visibility = View.GONE
            setPadding(22.dp, 18.dp, 22.dp, 18.dp)
            isClickable = true
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        placeName = label(22f, Palette.text, Fonts.MEDIUM, lines = 2)
        placeDetail = label(15f, Palette.text2, Fonts.REGULAR, lines = 2)
        texts.addView(placeName, lp(MATCH, WRAP))
        texts.addView(placeDetail, lp(MATCH, WRAP).apply { topMargin = 3.dp })
        head.addView(texts, lp(0, WRAP, 1f))
        head.addView(roundIcon(R.drawable.ic_close, 52) { clearPlace() }, lp(52.dp, 52.dp))
        c.addView(head, lp(MATCH, WRAP))
        placeRoute = label(17f, Palette.accent, Fonts.MEDIUM)
        c.addView(placeRoute, lp(MATCH, WRAP).apply { topMargin = 12.dp })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        goBtn = label(20f, Palette.onColor(Palette.accent), Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
            setText(R.string.maps_go)
            background = Shapes.accent(18f)
            isClickable = true
            pressScale(0.96f)
            setOnClickListener { go() }
            if (BuildConfig.DEBUG) setOnLongClickListener { intent.putExtra("simulate", true); go(); true }
        }
        actions.addView(goBtn, lp(0, 64.dp, 1f))
        actions.addView(textButton(R.string.maps_set_home) { savePlace("home") }, lp(WRAP, 64.dp).apply { marginStart = 10.dp })
        actions.addView(textButton(R.string.maps_set_work) { savePlace("work") }, lp(WRAP, 64.dp).apply { marginStart = 10.dp })
        c.addView(actions, lp(MATCH, WRAP).apply { topMargin = 16.dp })
        return c
    }

    private fun buildTripBar(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Shapes.glass(26f)
            elevate(10f)
            visibility = View.GONE
            setPaddingRelative(22.dp, 14.dp, 14.dp, 14.dp)
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tripEta = label(30f, Palette.text, Fonts.MEDIUM)
        tripLeft = label(16f, Palette.text2, Fonts.REGULAR)
        t.addView(tripEta, lp(WRAP, WRAP))
        t.addView(tripLeft, lp(WRAP, WRAP).apply { topMargin = 2.dp })
        c.addView(t, lp(0, WRAP, 1f))
        c.addView(roundIcon(R.drawable.ic_layers, 60) {
            NavSession.route?.let { r -> map.overview(r.line.points, panelWidth() + 40.dp, 90.dp, 150.dp, 60.dp); recenterBtn.visibility = View.VISIBLE }
        }, lp(60.dp, 60.dp))
        val end = label(19f, 0xFFFFFFFF.toInt(), Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
            setText(R.string.maps_end)
            background = Shapes.pressable(Shapes.rect(Palette.danger, 18f), Shapes.rect(Palette.mix(Palette.danger, 0xFF000000.toInt(), 0.15f), 18f))
            isClickable = true
            pressScale(0.95f)
            setOnClickListener { endNav() }
        }
        c.addView(end, lp(118.dp, 62.dp).apply { marginStart = 10.dp })
        return c
    }

    private fun buildArrivedCard(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Shapes.glass(26f)
            elevate(10f)
            visibility = View.GONE
            setPaddingRelative(22.dp, 16.dp, 14.dp, 16.dp)
        }
        c.addView(iconView(R.drawable.ic_check_circle, 34, Palette.success), lp(34.dp, 34.dp))
        c.addView(label(21f, Palette.text, Fonts.MEDIUM).apply { setText(R.string.maps_arrived) }, lp(0, WRAP, 1f).apply { marginStart = 14.dp })
        c.addView(label(18f, Palette.onColor(Palette.accent), Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
            setText(R.string.maps_done)
            background = Shapes.accent(18f)
            isClickable = true
            pressScale(0.95f)
            setOnClickListener { endNav() }
        }, lp(118.dp, 60.dp))
        return c
    }

    private fun row(icon: Int, title: String, detail: String, distance: String?, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 74.dp
            setPaddingRelative(16.dp, 8.dp, 18.dp, 8.dp)
            background = Shapes.ghost(18f)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val badge = FrameLayout(this).apply { background = Shapes.oval(Palette.card2) }
        badge.addView(iconView(icon, 22, Palette.text2).apply { layoutParams = flp(22.dp, 22.dp, Gravity.CENTER) })
        r.addView(badge, lp(46.dp, 46.dp))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(label(17.5f, Palette.text, Fonts.MEDIUM).apply { text = title }, lp(MATCH, WRAP))
        if (detail.isNotBlank()) t.addView(label(14f, Palette.text2, Fonts.REGULAR).apply { text = detail }, lp(MATCH, WRAP).apply { topMargin = 2.dp })
        r.addView(t, lp(0, WRAP, 1f).apply { marginStart = 14.dp })
        if (distance != null) r.addView(label(14.5f, Palette.text3, Fonts.MEDIUM).apply { text = distance }, lp(WRAP, WRAP).apply { marginStart = 10.dp })
        return r
    }

    private fun note(s: String) = label(16f, Palette.text2, Fonts.REGULAR).apply { text = s; setPaddingRelative(22.dp, 18.dp, 22.dp, 18.dp) }

    private fun roundIcon(icon: Int, size: Int, flipRtl: Boolean = false, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
        background = Shapes.ghostOval()
        isClickable = true
        pressScale(0.9f)
        setOnClickListener { onClick() }
        addView(iconView(icon, (size * 0.45f).toInt(), Palette.text).apply {
            layoutParams = flp((size * 0.45f).toInt().dp, (size * 0.45f).toInt().dp, Gravity.CENTER)
            if (flipRtl && rtl) scaleX = -1f
        })
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

    private fun mapButton(icon: Int, img: ImageView?, onClick: () -> Unit): View = FrameLayout(this).apply {
        background = Shapes.pressable(Shapes.oval(Palette.glass), Shapes.oval(Palette.mix(Palette.glass, Palette.text, 0.08f)))
        elevate(6f)
        isClickable = true
        pressScale(0.9f)
        setOnClickListener { onClick() }
        val v = img ?: ImageView(this@MapsActivity)
        v.setImageResource(icon)
        v.setColorFilter(Palette.text)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(v, flp(28.dp, 28.dp, Gravity.CENTER))
        layoutParams = lp(66.dp, 66.dp)
    }

    private fun pill(icon: Int, text: Int, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(20.dp, 0, 24.dp, 0)
        background = Shapes.glassPressable(30f)
        elevate(8f)
        isClickable = true
        pressScale(0.95f)
        setOnClickListener { onClick() }
        addView(iconView(icon, 22, Palette.accent), lp(22.dp, 22.dp))
        addView(label(17f, Palette.text, Fonts.MEDIUM).apply { setText(text) }, lp(WRAP, WRAP).apply { marginStart = 10.dp })
    }

    private fun textButton(text: Int, onClick: () -> Unit): AText = label(16f, Palette.text, Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
        setText(text)
        setPadding(18.dp, 0, 18.dp, 0)
        background = Shapes.tonal(18f)
        isClickable = true
        pressScale(0.95f)
        setOnClickListener { onClick() }
    }

    companion object {
        fun open(ctx: Context, extras: Intent.() -> Unit = {}) {
            try {
                ctx.startActivity(Intent(ctx, MapsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(extras))
            } catch (_: Throwable) {
            }
        }
    }
}
