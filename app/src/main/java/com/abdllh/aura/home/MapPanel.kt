package com.abdllh.aura.home

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.nav.AuraMap
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.nav.Instructions
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.ManeuverView
import com.abdllh.aura.nav.MapGuard
import com.abdllh.aura.nav.MapsActivity
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.nav.Places
import com.abdllh.aura.nav.Units
import com.abdllh.aura.settings.SettingsActivity
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.MapBackdropView
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.elevate
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

/**
 * End side of the home screen: a live map with the car (Aura Maps, read-only here), "Where to?" with Home and Work
 * beside it in one row along the top, and the player along the bottom, so the map shows between them (the row and
 * the player covered half of it until 1.11). While navigating it follows the route and shows the next manoeuvre.
 * Tapping it opens Maps.
 * The live map is started by [startLive], after the home screen's entrance: MapLibre's start (its library, the style,
 * the first tiles) took the processor from the entrance and the car on every cold start, when the map could not be
 * seen yet anyway; the placeholder shows meanwhile.
 */
class MapPanel(ctx: Context, private val host: HomeHost) : FrameLayout(ctx) {
    private val backdrop = MapBackdropView(ctx) // shows until the live map has drawn (and when it cannot)
    private var live: AuraMap? = null
    val media = MediaCard(ctx, host)
    /** "Where to?", Home and Work (hidden while navigating). */
    val places: LinearLayout
    private val banner: LinearLayout
    private val bannerIcon: ManeuverView
    private val bannerDist: AText
    private val bannerText: AText
    private val homeIcon: ImageView
    private val workIcon: ImageView
    private val arabic get() = Locale.getDefault().language == "ar"

    // the activity's lifecycle as far as it went, replayed on the live map when it is started late
    private var savedState: Bundle? = null
    private var created = false
    private var started = false
    private var resumed = false
    private var destroyed = false

    init {
        background = Shapes.rect(Palette.mapLand, Radius.XL)
        roundedClip(Radius.XL)
        if (!Palette.dark) {
            // on the light theme the map's land is close to the background colour: a hairline edge and a low shadow
            lift(Elev.REST)
            foreground = Shapes.rect(0, Radius.XL, Palette.stroke)
        }
        addView(backdrop, flp(MATCH, MATCH))
        // the map itself takes no touches: a tap anywhere opens Maps (a tint shows the press: the largest target on
        // the screen answered nothing until Maps was up)
        addView(View(ctx).apply {
            background = Shapes.pressable(ColorDrawable(0), ColorDrawable(Palette.press))
            isClickable = true
            setOnClickListener { openMaps(false) }
        }, flp(MATCH, MATCH))

        val top = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        places = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val search = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(Space.L.dp, 0, Space.XL.dp, 0)
            background = Shapes.glassPressable(Radius.PILL)
            lift(Elev.FLOAT)
            isClickable = true
            pressScale(Motion.PRESS_SURFACE)
            setOnClickListener { openMaps(true) }
            addView(ctx.iconView(R.drawable.ic_search, 24, Palette.text2), lp(24.dp, 24.dp))
            addView(ctx.label(Type.BODY, Palette.text2).apply { setText(R.string.home_where_to) }, lp(WRAP, WRAP).apply { marginStart = Space.M.dp })
        }
        search.minimumWidth = 240.dp
        places.addView(search, lp(WRAP, Touch.MIN.dp))
        homeIcon = ctx.iconView(R.drawable.ic_home, 24, Palette.accentFg)
        workIcon = ctx.iconView(R.drawable.ic_briefcase, 24, Palette.accentFg)
        places.addView(place(ctx, homeIcon, R.string.home_home) { go("home") }, lp(Touch.MIN.dp, Touch.MIN.dp).apply { marginStart = Space.S.dp })
        places.addView(place(ctx, workIcon, R.string.home_work) { go("work") }, lp(Touch.MIN.dp, Touch.MIN.dp).apply { marginStart = Space.S.dp })
        top.addView(places, lp(WRAP, WRAP))

        // guidance banner (navigation running)
        banner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(12.dp, 10.dp, 20.dp, 10.dp)
            background = Shapes.glassPressable(24f)
            elevate(8f)
            isClickable = true
            setOnClickListener { openMaps(false) }
            visibility = View.GONE
        }
        bannerIcon = ManeuverView(ctx).apply { color = Palette.accent }
        banner.addView(bannerIcon, lp(56.dp, 56.dp))
        val bt = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        bannerDist = ctx.label(24f, Palette.text, Fonts.MEDIUM)
        bannerText = ctx.label(15f, Palette.text2, Fonts.REGULAR)
        bt.addView(bannerDist, lp(WRAP, WRAP))
        bt.addView(bannerText, lp(WRAP, WRAP))
        banner.addView(bt, lp(WRAP, WRAP).apply { marginStart = 12.dp })
        top.addView(banner, lp(WRAP, WRAP))
        addView(top, flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(INSET.dp, INSET.dp, INSET.dp, 0) })

        // the street's limit and the camera or bump ahead (the owner's additions to the map), and "a camera here"
        // under them (Settings → Navigation hides it): one column, so the camera button moves up where there is no sign
        val hud = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            // (a gap between the two only while both show)
            dividerDrawable = GradientDrawable().apply { setSize(0, Space.S.dp) }
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
        hud.addView(com.abdllh.aura.nav.RoadHud(ctx, small = true), lp(WRAP, WRAP))
        hud.addView(com.abdllh.aura.nav.CameraMark(ctx, small = true), lp(WRAP, WRAP))
        addView(hud, flp(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(INSET.dp, INSET.dp, INSET.dp, 0) })
        // the player spans the map's width: the song's name gets twice the room it had in a card capped at 460 dp
        addView(media, flp(MATCH, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(INSET.dp, 0, INSET.dp, INSET.dp) })
        refresh()
    }

    // ------------------------------------------------------------------------------------------ lifecycle (from HomeActivity)
    fun onCreate(b: Bundle?) {
        savedState = b
        created = true
    }

    /** Builds the live map and brings it to where the activity is (HomeActivity calls it after the entrance). */
    fun startLive() {
        if (live != null || destroyed || !created) return
        val ctx = context
        // (also when another app does the navigating: the home screen keeps its own live map)
        if (MapGuard.homeMapAllowed(ctx)) {
            val m = AuraMap(ctx, interactive = false, texture = true, guarded = true)
            m.navTopPadding = 0.08 // the player covers the lower part: keep the car in the middle of what is visible
            m.onFirstFrame = { post { backdrop.visibility = View.GONE } } // no need to keep drawing it under an opaque map
            live = m
            addView(m.view, 1, flp(MATCH, MATCH)) // (over the placeholder, under the press tint and the cards)
        }
        val m = live ?: return
        m.onReady = { syncNav(); CarLocation.last?.let { onFix(it) } }
        m.onCreate(savedState)
        if (started) m.onStart()
        if (resumed) m.onResume()
    }

    fun onStart() {
        started = true
        live?.onStart()
        CarLocation.acquire(context, "home")
        CarLocation.addListener(fixListener)
        NavSession.addListener(navListener)
        syncNav()
    }

    fun onResume() {
        resumed = true
        live?.onResume()
    }

    fun onPause() {
        resumed = false
        live?.onPause()
    }

    fun onStop() {
        started = false
        CarLocation.removeListener(fixListener)
        CarLocation.release("home")
        NavSession.removeListener(navListener)
        live?.onStop()
        if (live != null) backdrop.visibility = View.VISIBLE // until the map has drawn again after the restart
    }

    fun onDestroy() {
        destroyed = true
        live?.onDestroy()
        live = null
    }

    fun onLowMemory() = live?.onLowMemory()

    private val fixListener: (Location) -> Unit = { onFix(it) }
    private val navListener: () -> Unit = { syncNav() }

    private fun onFix(l: Location) {
        if (NavSession.state == NavSession.State.NAVIGATING) return // navigation moves the car itself, on the route
        live?.setCar(LatLon(l.latitude, l.longitude), if (l.hasBearing()) l.bearing.toDouble() else 0.0, if (l.hasSpeed()) l.speed * 3.6 else 0.0)
    }

    private var shownRoute: Any? = null

    private fun syncNav() {
        val m = live
        val nav = NavSession.state == NavSession.State.NAVIGATING
        places.visibility = if (nav) View.GONE else View.VISIBLE
        banner.visibility = if (nav) View.VISIBLE else View.GONE
        if (m == null) return
        if (!nav) {
            shownRoute = null
            if (m.camera == AuraMap.Camera.NAVIGATE) {
                m.setRoute(null)
                m.setDestination(null)
                m.setCamera(AuraMap.Camera.FOLLOW)
            }
            return
        }
        val r = NavSession.route ?: return
        val p = NavSession.progress ?: return
        if (r !== shownRoute) { // a new trip or a new route after leaving the old one
            shownRoute = r
            m.setRouteAhead(r.line) // (what is ahead of the car only: the map cuts it at the car)
            m.setDestination(NavSession.destination?.pos)
        }
        if (m.camera != AuraMap.Camera.NAVIGATE) m.setCamera(AuraMap.Camera.NAVIGATE)
        m.setCar(p.position, p.bearing, p.speedKmh.toDouble())
        val step = r.steps[p.next]
        bannerIcon.turn = Instructions.turn(step)
        bannerIcon.exit = step.exit
        bannerDist.text = Units.distance(p.toNext, arabic)
        bannerText.text = Instructions.text(step, arabic)
    }

    /** Home or Work: a round glass button with its icon, the name for the screen reader. */
    private fun place(ctx: Context, icon: ImageView, name: Int, onClick: () -> Unit): FrameLayout =
        FrameLayout(ctx).apply {
            background = Shapes.glassPressable(Radius.PILL)
            lift(Elev.FLOAT)
            isClickable = true
            contentDescription = ctx.getString(name)
            pressScale(Motion.PRESS_ICON)
            setOnClickListener { onClick() }
            addView(icon, flp(24.dp, 24.dp, Gravity.CENTER))
        }

    fun refresh() {
        val homeSet = Places.home != null || Prefs.homeAddress.isNotBlank()
        val workSet = Places.work != null || Prefs.workAddress.isNotBlank()
        homeIcon.setColorFilter(if (homeSet) Palette.accentFg else Palette.text3)
        workIcon.setColorFilter(if (workSet) Palette.accentFg else Palette.text3)
    }

    private fun openMaps(search: Boolean) {
        if (!Prefs.builtInMaps) { if (!Actions.nav(context)) host.toast(context.getString(R.string.err_no_nav)); return }
        MapsActivity.open(context) { if (search) putExtra("search", true) }
    }

    /** Home / Work: Aura Maps drives there at once; with an external navigation app the saved address is handed over. */
    private fun go(which: String) {
        val label = if (which == "home") R.string.home_home else R.string.home_work
        if (Prefs.builtInMaps) {
            val saved = if (which == "home") Places.home else Places.work
            val legacy = if (which == "home") Prefs.homeAddress else Prefs.workAddress
            if (saved == null && legacy.isBlank()) {
                MapsActivity.open(context) { putExtra("pick", which) } // choose it on the map
                return
            }
            MapsActivity.open(context) { putExtra("go", which) }
            return
        }
        if (AppRepo.navPackage(context) == null) { host.toast(context.getString(R.string.err_no_nav)); return }
        if (!Actions.driveWithApp(context, which)) {
            // no such place yet: it is chosen with a pin on AMRI's map (Settings → Navigation)
            host.toast(context.getString(R.string.home_set_address_hint, context.getString(label)), outlives = true)
            host.openSettings(SettingsActivity.PAGE_NAVIGATION)
        }
    }

    private companion object {
        const val INSET = 12 // dp, the cards from the panel's edges
    }
}
