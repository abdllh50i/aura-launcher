package com.abdllh.aura.home

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
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
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.MapBackdropView
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.elevate
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp
import java.util.Locale

/**
 * End side of the home screen: a live map with the car (Aura Maps, read-only here), the "Where to?" pill, Home/Work
 * and the floating player. While navigating it follows the route and shows the next manoeuvre. Tapping it opens Maps.
 */
class MapPanel(ctx: Context, private val host: HomeHost) : FrameLayout(ctx) {
    private val backdrop = MapBackdropView(ctx) // shows until the live map has drawn (and when it cannot)
    private var live: AuraMap? = null
    val media = MediaCard(ctx, host)
    val search: LinearLayout
    val chips: LinearLayout
    private val banner: LinearLayout
    private val bannerIcon: ManeuverView
    private val bannerDist: AText
    private val bannerText: AText
    private val homeTitle: AText
    private val workTitle: AText
    private val homeIcon: ImageView
    private val workIcon: ImageView
    private val arabic get() = Locale.getDefault().language == "ar"

    init {
        background = Shapes.rect(Palette.mapLand, 24f)
        roundedClip(24f)
        if (!Palette.dark) elevate(5f) // on the light theme the map is close to the background colour
        addView(backdrop, flp(MATCH, MATCH))
        if (Prefs.builtInMaps && MapGuard.homeMapAllowed(ctx)) {
            val m = AuraMap(ctx, interactive = false, texture = true, guarded = true)
            m.navTopPadding = 0.08 // the player covers the lower part: keep the car in the middle of what is visible
            m.onFirstFrame = { post { backdrop.visibility = View.GONE } } // no need to keep drawing it under an opaque map
            live = m
            addView(m.view, flp(MATCH, MATCH))
        }
        // the map itself takes no touches: a tap anywhere opens Maps
        addView(View(ctx).apply { isClickable = true; setOnClickListener { openMaps(false) } }, flp(MATCH, MATCH))

        val top = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        search = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(18.dp, 0, 26.dp, 0)
            background = Shapes.glassPressable(31f)
            elevate(6f)
            isClickable = true
            pressScale(0.98f)
            setOnClickListener { openMaps(true) }
            addView(ctx.iconView(R.drawable.ic_search, 25, Palette.text2), lp(25.dp, 25.dp))
            addView(ctx.label(19f, Palette.text2, Fonts.REGULAR).apply { setText(R.string.home_where_to) }, lp(WRAP, WRAP).apply { marginStart = 14.dp })
        }
        search.minimumWidth = 300.dp
        top.addView(search, lp(WRAP, 62.dp))

        chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        homeTitle = ctx.label(16.5f, Palette.text, Fonts.MEDIUM)
        workTitle = ctx.label(16.5f, Palette.text, Fonts.MEDIUM)
        homeIcon = ctx.iconView(R.drawable.ic_home, 22, Palette.accent)
        workIcon = ctx.iconView(R.drawable.ic_briefcase, 22, Palette.accent)
        chips.addView(chip(ctx, homeIcon, homeTitle, R.string.home_home) { go("home") }, lp(WRAP, 52.dp))
        chips.addView(chip(ctx, workIcon, workTitle, R.string.home_work) { go("work") }, lp(WRAP, 52.dp).apply { marginStart = 10.dp })
        top.addView(chips, lp(WRAP, WRAP).apply { topMargin = 10.dp })

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
        addView(top, flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(14.dp, 14.dp, 14.dp, 0) })

        addView(media, flp(MATCH, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(14.dp, 0, 14.dp, 14.dp) })
        live?.onReady = { syncNav(); CarLocation.last?.let { onFix(it) } }
        refresh()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // the player takes the map's width up to a comfortable maximum
        (media.layoutParams as LayoutParams).width = minOf(460.dp, MeasureSpec.getSize(widthMeasureSpec) - 28.dp)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    // ------------------------------------------------------------------------------------------ lifecycle (from HomeActivity)
    fun onCreate(b: Bundle?) = live?.onCreate(b)

    fun onStart() {
        live?.onStart()
        CarLocation.acquire(context, "home")
        CarLocation.addListener(fixListener)
        NavSession.addListener(navListener)
        syncNav()
    }

    fun onResume() = live?.onResume()
    fun onPause() = live?.onPause()

    fun onStop() {
        CarLocation.removeListener(fixListener)
        CarLocation.release("home")
        NavSession.removeListener(navListener)
        live?.onStop()
    }

    fun onDestroy() {
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
        search.visibility = if (nav) View.GONE else View.VISIBLE
        chips.visibility = if (nav) View.GONE else View.VISIBLE
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
            m.setRoute(r.line.points)
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

    private fun chip(ctx: Context, icon: ImageView, title: AText, text: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(16.dp, 0, 20.dp, 0)
            background = Shapes.glassPressable(26f)
            elevate(4f)
            isClickable = true
            pressScale(0.96f)
            setOnClickListener { onClick() }
            addView(icon, lp(22.dp, 22.dp))
            title.setText(text)
            addView(title, lp(WRAP, WRAP).apply { marginStart = 9.dp })
        }

    fun refresh() {
        val homeSet = Places.home != null || Prefs.homeAddress.isNotBlank()
        val workSet = Places.work != null || Prefs.workAddress.isNotBlank()
        homeTitle.setTextColor(if (homeSet) Palette.text else Palette.text2)
        workTitle.setTextColor(if (workSet) Palette.text else Palette.text2)
        homeIcon.setColorFilter(if (homeSet) Palette.accent else Palette.text3)
        workIcon.setColorFilter(if (workSet) Palette.accent else Palette.text3)
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
                host.toast(context.getString(R.string.maps_set_place_hint))
                MapsActivity.open(context) { putExtra("search", true) }
                return
            }
            MapsActivity.open(context) { putExtra("go", which) }
            return
        }
        val address = if (which == "home") Prefs.homeAddress else Prefs.workAddress
        if (address.isBlank()) {
            host.toast(context.getString(R.string.home_set_address_hint, context.getString(label)))
            host.openSettings(SettingsActivity.PAGE_NAVIGATION)
            return
        }
        val pkg = AppRepo.navPackage(context)
        if (pkg == null) { host.toast(context.getString(R.string.err_no_nav)); return }
        val uri = if (pkg == "com.waze") "waze://?q=${Uri.encode(address)}&navigate=yes" else "google.navigation:q=${Uri.encode(address)}"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i) } catch (_: Throwable) { Actions.nav(context) }
    }
}
