package com.abdllh.aura.home

import android.app.Activity
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
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.settings.SettingsActivity
import com.abdllh.aura.system.CarAudio
import com.abdllh.aura.system.CrashGuard
import com.abdllh.aura.system.NwdBridge
import com.abdllh.aura.ui.CarEtc
import com.abdllh.aura.ui.CarFrames
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.lp
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

/** The home screen: the car on the start side, the map with the player on the end side, the dock, and two sheets. */
class HomeActivity : Activity(), HomeHost {
    private var root: FrameLayout? = null
    private lateinit var carPanel: CarPanel
    private lateinit var mapPanel: MapPanel
    private lateinit var dock: Dock
    private lateinit var controls: ControlsSheet
    private lateinit var drawer: AppDrawer
    private var introPlayed = false
    private var builtLang = ""
    private var builtAccent = 0
    private var builtDark = true
    private var restyling = false
    private var resumedNow = false
    private var startedNow = false
    private var packagesWatched = false

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!::carPanel.isInitialized) return
            carPanel.refresh()
            // "Auto" reached sunrise or sunset; not while a sheet is in use (the next minute's tick retries)
            if (Theme.isDarkNow() != builtDark && !drawer.isOpen && !controls.isOpen) restyle()
        }
    }
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

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashGuard.isCrashLoop()) {
            CrashGuard.bailOut(this)
            finish()
            return
        }
        Fonts.refreshLocale(this)
        Theme.refresh()
        builtLang = Prefs.lang
        builtAccent = Prefs.accent
        builtDark = Palette.dark
        Theme.window(this, Palette.dock)
        // The CPU's car frames only matter until the GPU's are there: from the second start on they are, and decoding
        // the intro's frames anyway took two threads through the whole entrance.
        if (!CarEtc.builtBefore(this, Palette.dark)) CarFrames.warmUp(this, resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        setContentView(buildUi())
        mapPanel.onCreate(savedInstanceState)
        MediaMonitor.start(this)
        NwdBridge.startStockServicesOncePerBoot(this)
        AppRepo.preload(this) // the app grid opens instantly instead of querying the package manager on the UI thread
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

    // (no backdrop view: the window's background is the theme's, and a view painting the same colour over the whole
    // screen was a second full-screen fill in every frame of the map)
    private fun buildUi(): View {
        val r = FrameLayout(this)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        carPanel = CarPanel(this, this)
        content.addView(carPanel, lp(0, MATCH, 0.40f))
        mapPanel = MapPanel(this, this)
        content.addView(mapPanel, lp(0, MATCH, 0.60f).apply { topMargin = 12.dp; bottomMargin = 12.dp; marginEnd = 12.dp })
        col.addView(content, lp(MATCH, 0, 1f))
        dock = Dock(this, this)
        col.addView(dock, lp(MATCH, DOCK_DP.dp))
        r.addView(col, MATCH, MATCH)

        controls = ControlsSheet(this, this)
        r.addView(controls, MATCH, MATCH)
        drawer = AppDrawer(this, this)
        r.addView(drawer, MATCH, MATCH)
        root = r
        return r
    }

    override fun onStart() {
        super.onStart()
        NwdBridge.notifyHomeForeground(this)
        mapPanel.onStart()
        startedNow = true
    }

    override fun onStop() {
        mapPanel.onStop()
        startedNow = false
        // a sheet left open under an app opened from it (so it did not close during the app's opening) goes now
        if (::drawer.isInitialized) closeSheets()
        super.onStop()
    }

    override fun onDestroy() {
        if (::mapPanel.isInitialized) mapPanel.onDestroy()
        if (packagesWatched) try { unregisterReceiver(packages) } catch (_: Throwable) { }
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapPanel.isInitialized) mapPanel.onLowMemory()
    }

    /** Asks once for the location (the home map shows the car; Maps needs it anyway). */
    private fun askLocationOnce() {
        if (CarLocation.hasPermission(this) || Prefs.raw.getBoolean("askedLocation", false)) return
        Prefs.raw.edit().putBoolean("askedLocation", true).apply()
        requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 7)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        if (CarLocation.hasPermission(this)) CarLocation.acquire(this, "home")
    }

    override fun onResume() {
        super.onResume()
        if (builtLang != Prefs.lang) {
            AppRepo.clear() // (the app names are the old language's)
            recreate() // the language changed in Settings
            return
        }
        if (builtAccent != Prefs.accent || Theme.isDarkNow() != builtDark) restyle() // changed in Settings
        com.abdllh.aura.system.Animations.ensureAnimators()
        com.abdllh.aura.system.ZLinkGuard.onHomeShown() // back from ZLink: it is switched off again after a while
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        registerReceiver(tick, f)
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
            root?.postDelayed({ if (!isDestroyed) mapPanel.startLive() }, LIVE_MAP_AFTER_MS)
            root?.postDelayed({ if (!isDestroyed) drawer.prewarm() }, PREWARM_AFTER_MS)
            root?.postDelayed({ if (!isFinishing) askLocationOnce() }, 2600)
        }
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
     */
    private fun animateIn() {
        carPanel.stage.playIntro(100)
        // (a part hidden for now, the row while navigating, stays hidden)
        fun rise(v: View, dy: Float, delay: Long) { if (v.visibility == View.VISIBLE) v.enter(dy = dy, delay = delay) }
        rise(carPanel.header, -8f, 0L)

        mapPanel.translationY = 16f.dp
        mapPanel.animate().translationY(0f).setStartDelay(0L).setDuration(Motion.LONG).setInterpolator(Motion.EMPHASIZED).start()
        // (each card on its own, so its own shadow stays outside the layer it fades in)
        if (mapPanel.places.visibility == View.VISIBLE) {
            for (i in 0 until mapPanel.places.childCount) rise(mapPanel.places.getChildAt(i), -8f, 80L + 30L * i)
        }
        rise(mapPanel.media, 12f, 140L)
        rise(carPanel.quick, 12f, 160L)

        dock.translationY = DOCK_DP.dp.toFloat()
        dock.animate().translationY(0f).setStartDelay(0L).setDuration(Motion.LONG).setInterpolator(Motion.EMPHASIZED).start()
        for (i in 0 until dock.apps.childCount) {
            val v = dock.apps.getChildAt(i)
            v.translationY = 18.dp.toFloat()
            v.animate().translationY(0f).setStartDelay(60L + 25L * i).setDuration(Motion.MEDIUM).setInterpolator(Motion.EMPHASIZED).start()
        }
    }

    override fun onPause() {
        try { unregisterReceiver(tick) } catch (_: Throwable) { }
        try { unregisterReceiver(volume) } catch (_: Throwable) { }
        CarAudio.removeListener(carAudio)
        mapPanel.onPause()
        resumedNow = false
        UpdateManager.unobserve(updateObserver)
        com.abdllh.aura.system.ZLinkGuard.onHomeHidden()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // keep only the resting views of the car while another app is in front
            CarFrames.meta(this)?.let { m -> CarFrames.trim(listOf(m.defaultFrame, m.defaultFrameRtl)) }
        }
        // the stopped home map keeps its tile caches until told (Maps may be running its own engine meanwhile)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && ::mapPanel.isInitialized) mapPanel.onLowMemory()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeSheets()
    }

    private fun closeSheets(): Boolean {
        var closed = false
        if (::drawer.isInitialized && drawer.isOpen) { drawer.close(); closed = true }
        if (::controls.isInitialized && controls.isOpen) { controls.close(); closed = true }
        return closed
    }

    @Deprecated("launcher: back does nothing on the home screen")
    override fun onBackPressed() {
        closeSheets()
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
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, page))
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
        val r = root
        if (r == null || r.width == 0 || r.height == 0 || !r.isAttachedToWindow) { rebuild(null); return }
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
        if (isDestroyed) return
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
        Theme.window(this, Palette.dock)
        val ui = buildUi() as FrameLayout
        setContentView(ui)
        mapPanel.onCreate(null)
        mapPanel.startLive() // (no entrance to wait for)
        if (startedNow) mapPanel.onStart()
        if (resumedNow) mapPanel.onResume()
        refreshAll()
        if (reopen) controls.open(false)
        ui.postDelayed({ if (!isDestroyed) drawer.prewarm() }, PREWARM_AFTER_MS)
        if (snap != null) {
            val cover = ImageView(this).apply { setImageBitmap(snap); scaleType = ImageView.ScaleType.FIT_XY }
            ui.addView(cover, MATCH, MATCH)
            cover.fadeTo(0f, Motion.LONG) { ui.removeView(cover) }
        }
    }

    companion object {
        /** Height of the dock (big touch targets: the unit's 1024x600 panel is about 120 dpi, 1 dp = 1 px). */
        const val DOCK_DP = 92
        /** The live map starts once the entrance has settled. */
        private const val LIVE_MAP_AFTER_MS = 700L
        /** The app drawer's cells are built while nothing else happens. */
        private const val PREWARM_AFTER_MS = 2500L
    }
}
