package com.abdllh.aura.home

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.system.CarAudio
import com.abdllh.aura.system.NwdBridge
import com.abdllh.aura.ui.CarEtc
import com.abdllh.aura.ui.CarFrames
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.afterNextDraw
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.lp
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

/**
 * The home screen, the panel at the bottom of the stack: the car on the start side, the map with the player on the end
 * side, the dock, and two sheets. Its views are built on the activity itself ([shell]), as they always were (the car's GL
 * view asks its context for the window).
 */
class HomePanel(shell: HomeActivity, savedInstanceState: Bundle?) : Panel(shell, Screens.HOME), HomeHost {
    private val room = FrameLayout(shell)
    private lateinit var carPanel: CarPanel
    private lateinit var mapPanel: MapPanel
    private lateinit var dock: Dock
    private lateinit var controls: ControlsSheet
    private lateinit var drawer: AppDrawer
    private var introPlayed = false
    /** Over the screen until its entrance starts, after a cold start on the light theme ([coverStart]). */
    private var startCover: View? = null
    private var builtAccent = 0
    private var builtDark = true
    private var restyling = false
    private var resumedNow = false
    private var startedNow = false
    private var packagesWatched = false

    override val root: View get() = room
    override val opaque: Boolean get() = true // (the map is its own, in its card, until step 3)
    override val keep: Boolean get() = true
    override val navBar: Int get() = Palette.dock

    private val volume = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!::dock.isInitialized) return
            dock.syncVolume()
            if (controls.isOpen) controls.syncVolume()
        }
    }
    // A pinned app installed or removed anywhere (the system's app manager, an update): the dock follows at once,
    // also while the home screen is in the background, since it no longer rebuilds itself on every return.
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return // an update: the app stays
            val pkg = i.data?.schemeSpecificPart ?: return
            if (::dock.isInitialized && DockPins.contains(pkg)) dock.rebuild()
        }
    }
    private val carAudio: () -> Unit = {
        if (::dock.isInitialized) {
            dock.syncVolume()
            if (controls.isOpen) controls.syncVolume()
        }
    }
    private val updateObserver: (UpdateManager.State) -> Unit = {
        if (::dock.isInitialized) {
            dock.refreshBadge()
            carPanel.refreshUpdate()
        }
    }

    init {
        builtAccent = Prefs.accent
        builtDark = Palette.dark
        // (with the GPU's frame file there, the GPU draws the car from its first frame: see CarStage.playIntro)
        if (!CarEtc.readyNow(this, Palette.dark, Palette.bg)) CarFrames.warmUp(this, resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        buildUi()
        if (!processStarted) {
            processStarted = true
            if (!Palette.dark) coverStart()
        }
        mapPanel.onCreate(savedInstanceState)
        try {
            registerReceiver(packages, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            })
            packagesWatched = true
        } catch (_: Throwable) { }
        if (Prefs.safeModeNotice) {
            Prefs.safeModeNotice = false
            toast(getString(R.string.safe_mode_notice))
        }
    }

    /**
     * The light theme's way out of the starting window. That window can only be drawn in the XML colours, the dark
     * theme's, so every boot on the light theme (all day under "auto") opened on near-black and then cut to the white
     * screen at once. The first frame now shows the same near-black over the built screen, and it fades off as the
     * entrance starts ([animateIn]): a plain fill on top, which costs one translucent rectangle per frame while it
     * fades (fading the whole screen in instead would draw it all through a layer). In the panel: should the window start
     * on another panel, this waits under it for the home screen's entrance.
     */
    private fun coverStart() {
        // (one flat colour: faded as it is, not through an offscreen copy of the whole screen)
        val c = object : View(shell) { override fun hasOverlappingRendering() = false }
        c.setBackgroundColor(getColor(R.color.bg))
        room.addView(c, MATCH, MATCH)
        startCover = c
    }

    // (the panel's own room is the screen's one full-screen fill: the window has no background of its own, and a second
    // view painting the same colour over the whole screen was a second fill in every frame of the map)
    private fun buildUi() {
        room.setBackgroundColor(Palette.bg)
        val col = LinearLayout(shell).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(shell).apply { orientation = LinearLayout.HORIZONTAL }
        carPanel = CarPanel(shell, this)
        content.addView(carPanel, lp(0, MATCH, 0.40f))
        mapPanel = MapPanel(shell, this)
        content.addView(mapPanel, lp(0, MATCH, 0.60f).apply { topMargin = 12.dp; bottomMargin = 12.dp; marginEnd = 12.dp })
        col.addView(content, lp(MATCH, 0, 1f))
        dock = Dock(shell, this)
        col.addView(dock, lp(MATCH, DOCK_DP.dp))
        room.addView(col, MATCH, MATCH)

        controls = ControlsSheet(shell, this)
        shell.root.sheets.addView(controls, MATCH, MATCH)
        drawer = AppDrawer(shell, this)
        shell.root.sheets.addView(drawer, MATCH, MATCH)
    }

    override fun onShow(args: Bundle?) {
        NwdBridge.notifyHomeForeground(this)
        mapPanel.onStart()
        startedNow = true
    }

    override fun onHide() {
        mapPanel.onStop()
        startedNow = false
    }

    override fun onDestroy() {
        if (::mapPanel.isInitialized) mapPanel.onDestroy()
        if (packagesWatched) try { unregisterReceiver(packages) } catch (_: Throwable) { }
    }

    /** Asks once for the location (the home map shows the car; Maps needs it anyway). */
    private fun askLocationOnce() {
        if (CarLocation.hasPermission(this) || Prefs.raw.getBoolean("askedLocation", false)) return
        Prefs.raw.edit().putBoolean("askedLocation", true).apply()
        requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 7)
    }

    override fun onPermission(code: Int, grants: IntArray) {
        // (under another panel: the map asks for it itself when the home screen is back, onShow)
        if (startedNow && CarLocation.hasPermission(this)) CarLocation.acquire(this, "home")
    }

    override fun onResume() {
        if (shell.languageChanged()) return // the language changed in Settings
        com.abdllh.aura.system.ZLinkGuard.onHomeShown() // back from ZLink: it is switched off again after a while
        try {
            // muting (Mute tile, steering-wheel key) only sends STREAM_MUTE_CHANGED, not VOLUME_CHANGED
            registerReceiver(volume, IntentFilter("android.media.VOLUME_CHANGED_ACTION").apply { addAction("android.media.STREAM_MUTE_CHANGED_ACTION") })
        } catch (_: Throwable) { }
        CarAudio.addListener(carAudio)
        mapPanel.onResume()
        resumedNow = true
        refreshAll()
        UpdateManager.observe(updateObserver)
        UpdateManager.autoCheckIfDue()
        if (!introPlayed) {
            introPlayed = true
            animateIn()
            room.postDelayed({ if (!isGone) drawer.prewarm() }, PREWARM_AFTER_MS)
            room.postDelayed({ if (!isGone) askLocationOnce() }, 2600)
        }
        // (not in the start's first seconds, and once it is there this is one look in a map)
        shell.panels.prepareWhenQuiet(Screens.MUSIC, PREPARE_MUSIC_AFTER_MS)
    }

    override fun onTick() {
        if (::carPanel.isInitialized) carPanel.refresh()
        UpdateManager.autoCheckIfDue() // (also with no other screen ever opened: a drive spent on this one)
    }

    /**
     * "Auto" reached sunrise or sunset, or the theme or accent changed in Settings; not while a sheet is in use (the next
     * minute's tick retries). Coming out from under a panel that is leaving: built again there, before it goes, and no
     * picture to take (it would be of that panel).
     */
    override fun onLook() {
        if (Theme.isDarkNow() == builtDark && Prefs.accent == builtAccent) return
        if (drawer.isOpen || controls.isOpen) return
        if (shell.panels.leaving != null) rebuild(null) else restyle()
    }

    private fun refreshAll() {
        carPanel.refresh()
        mapPanel.refresh()
        dock.refresh()
        if (controls.isOpen) controls.refresh()
    }

    /**
     * Entrance, settled within about half a second (it kept the player and the quick buttons out of reach for a
     * second until 1.11): the header, the map's row and player, the quick buttons and the dock's tiles one after the
     * other while the car turns towards the screen. Each card fades through its own layer; the map panel only moves
     * (it holds the live map's surface, which a fade would draw through a layer at every frame) and the dock's tiles
     * only rise with the dock.
     * Everything is put where it starts from at once, so the first frame shows that, and it all starts moving after
     * that frame: on a cold start the first frame held the main thread for up to 650 ms, and an entrance started
     * before it was over by the time it showed (the cards appeared all at once, with no stagger).
     */
    private fun animateIn() {
        carPanel.stage.playIntro(100)
        // (a part hidden for now, a place button while navigating, stays hidden)
        val cards = ArrayList<Triple<View, Float, Long>>()
        fun rise(v: View, dy: Float, delay: Long) {
            if (v.visibility != View.VISIBLE) return
            v.alpha = 0f
            v.translationY = dy.dp
            cards.add(Triple(v, dy, delay))
        }
        rise(carPanel.header, -8f, 0L)
        // (each card on its own, so its own shadow stays outside the layer it fades in)
        for (i in 0 until mapPanel.places.childCount) rise(mapPanel.places.getChildAt(i), -8f, 80L + 30L * i)
        rise(mapPanel.media, 12f, 140L)
        rise(carPanel.quick, 12f, 160L)
        mapPanel.translationY = 16f.dp
        dock.translationY = DOCK_DP.dp.toFloat()
        for (i in 0 until dock.apps.childCount) dock.apps.getChildAt(i).translationY = 18.dp.toFloat()

        val r = room
        r.afterNextDraw {
            if (isGone) return@afterNextDraw
            for ((v, dy, delay) in cards) v.enter(dy = dy, delay = delay)
            mapPanel.animate().translationY(0f).setStartDelay(0L).setDuration(Motion.LONG).setInterpolator(Motion.EMPHASIZED).start()
            dock.animate().translationY(0f).setStartDelay(0L).setDuration(Motion.LONG).setInterpolator(Motion.EMPHASIZED).start()
            for (i in 0 until dock.apps.childCount) {
                dock.apps.getChildAt(i).animate().translationY(0f).setStartDelay(60L + 25L * i).setDuration(Motion.MEDIUM)
                    .setInterpolator(Motion.EMPHASIZED).start()
            }
            // (on a spring, which steps at most a few frames' worth per frame: the frames after the first are heavy
            // too, and a timed fade jumped from black to white across them)
            startCover?.let { c ->
                Spring(1f, COVER_STIFFNESS, 1f, 0.01f) { c.alpha = it }.apply {
                    onRest = { r.removeView(c) }
                    animateTo(0f)
                }
            }
            startCover = null
            com.abdllh.aura.nav.MapLib.preload(this) // (the map below will want it: off the main thread, and not before this frame)
            // (counted from here too: from onResume, the first frame's wait brought it into the middle of the entrance)
            r.postDelayed({ if (!isGone) mapPanel.startLive() }, LIVE_MAP_AFTER_MS)
        }
    }

    override fun onPause() {
        try { unregisterReceiver(volume) } catch (_: Throwable) { }
        CarAudio.removeListener(carAudio)
        mapPanel.onPause()
        resumedNow = false
        UpdateManager.unobserve(updateObserver)
        com.abdllh.aura.system.ZLinkGuard.onHomeHidden()
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // keep only the resting views of the car while another app is in front
            CarFrames.meta(this)?.let { m -> CarFrames.trim(listOf(m.defaultFrame, m.defaultFrameRtl)) }
        }
        // the stopped home map keeps its tile caches until told (Maps may be running its own engine meanwhile)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && ::mapPanel.isInitialized) mapPanel.onLowMemory()
    }

    // ---- HomeHost
    override fun openDrawer() {
        if (controls.isOpen) controls.close()
        drawer.open()
    }

    override fun openControls() {
        if (drawer.isOpen) drawer.close()
        controls.open()
    }

    override fun openSettings(page: Int) {
        Screens.settings(this, page)
    }

    override fun toast(msg: String, outlives: Boolean) {
        Snack.show(this, msg, aboveDp = DOCK_DP + 16, outlives = outlives)
    }

    /**
     * Applies a new theme or accent in place: the screen is captured, the views are rebuilt with the new colours,
     * and the capture fades out on top of them (no activity restart, no flash).
     */
    override fun restyle() {
        if (restyling) return
        if (Theme.isDarkNow() == builtDark && Prefs.accent == builtAccent) return
        val r = room
        if (r.width == 0 || r.height == 0 || !r.isAttachedToWindow) { rebuild(null); return }
        restyling = true
        val snap = try { Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { null }
        if (snap == null) { rebuild(null); return }
        val at = IntArray(2)
        r.getLocationInWindow(at)
        val main = Handler(Looper.getMainLooper())
        val fallback = Runnable { if (restyling) rebuild(null) } // the copy never answered
        try {
            PixelCopy.request(window, Rect(at[0], at[1], at[0] + r.width, at[1] + r.height), snap, { result ->
                main.removeCallbacks(fallback)
                if (restyling) rebuild(if (result == PixelCopy.SUCCESS) snap else null)
            }, main)
            main.postDelayed(fallback, 600)
        } catch (_: Throwable) {
            rebuild(null)
        }
    }

    private fun rebuild(snap: Bitmap?) {
        restyling = false
        if (isGone) return
        val reopen = ::controls.isInitialized && controls.isOpen
        // the live map of the old view tree owns a GL surface: shut it down before the views are replaced
        if (::mapPanel.isInitialized) {
            if (resumedNow) mapPanel.onPause()
            if (startedNow) mapPanel.onStop()
            mapPanel.onDestroy()
        }
        Theme.refresh()
        builtDark = Palette.dark
        builtAccent = Prefs.accent
        shell.windowLook(navBar)
        room.removeAllViews()
        shell.root.sheets.removeAllViews()
        buildUi()
        mapPanel.onCreate(null)
        mapPanel.startLive() // (no entrance to wait for)
        if (startedNow) mapPanel.onStart()
        if (resumedNow) mapPanel.onResume()
        refreshAll()
        if (reopen) controls.open(false)
        room.postDelayed({ if (!isGone) drawer.prewarm() }, PREWARM_AFTER_MS)
        if (snap != null) {
            val cover = ImageView(shell).apply { setImageBitmap(snap); scaleType = ImageView.ScaleType.FIT_XY }
            shell.root.addView(cover, MATCH, MATCH)
            cover.fadeTo(0f, Motion.LONG) { shell.root.removeView(cover) }
        }
    }

    companion object {
        /** The home screen has been built once in this process: later builds have no starting window before them. */
        private var processStarted = false
        /** The light theme's start cover fades on this spring: critically damped, mostly gone in about 0.4 s. */
        private const val COVER_STIFFNESS = 100f
        /** Height of the dock (big touch targets: the unit's 1024x600 panel is about 120 dpi, 1 dp = 1 px). */
        const val DOCK_DP = 92
        /** The live map starts once the entrance has settled. */
        private const val LIVE_MAP_AFTER_MS = 700L
        /** The app drawer's cells are built while nothing else happens. */
        private const val PREWARM_AFTER_MS = 2500L
        /** The music screen is built ahead of time ([PanelStack.prepareWhenQuiet]). */
        private const val PREPARE_MUSIC_AFTER_MS = 6000L
    }
}
