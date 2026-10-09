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
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.abdllh.aura.R
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.settings.SettingsActivity
import com.abdllh.aura.system.CarAudio
import com.abdllh.aura.system.CrashGuard
import com.abdllh.aura.system.NwdBridge
import com.abdllh.aura.ui.BackdropView
import com.abdllh.aura.ui.CarFrames
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Theme
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
    private var toast: Toast? = null

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
        CarFrames.warmUp(this, resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        setContentView(buildUi())
        mapPanel.onCreate(savedInstanceState)
        MediaMonitor.start(this)
        NwdBridge.startStockServicesOncePerBoot(this)
        AppRepo.preload(this) // the app grid opens instantly instead of querying the package manager on the UI thread
        if (Prefs.safeModeNotice) {
            Prefs.safeModeNotice = false
            toast(getString(R.string.safe_mode_notice))
        }
    }

    private fun buildUi(): View {
        val r = FrameLayout(this)
        r.addView(BackdropView(this), MATCH, MATCH)

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
        super.onStop()
    }

    override fun onDestroy() {
        if (::mapPanel.isInitialized) mapPanel.onDestroy()
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
            recreate() // the language changed in Settings
            return
        }
        if (builtAccent != Prefs.accent || Theme.isDarkNow() != builtDark) restyle() // changed in Settings
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
            root?.postDelayed({ if (!isFinishing) askLocationOnce() }, 2600)
        }
    }

    private fun refreshAll() {
        carPanel.refresh()
        mapPanel.refresh()
        dock.rebuild()
        if (controls.isOpen) controls.refresh()
    }

    /** Staggered entrance: the car turns towards the screen while the map, player and dock settle in. */
    private fun animateIn() {
        val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
        fun rise(v: View, dy: Int, delay: Long, dur: Long = 520) {
            v.alpha = 0f
            v.translationY = dy.dp.toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(dur).setInterpolator(ease).start()
        }
        carPanel.stage.playIntro(180)
        rise(carPanel.header, -10, 60)
        rise(carPanel.quick, 16, 760)

        mapPanel.alpha = 0f
        mapPanel.scaleX = 0.965f
        mapPanel.scaleY = 0.965f
        mapPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(560).setInterpolator(ease).withLayer().start()
        rise(mapPanel.search, -12, 380)
        rise(mapPanel.chips, -12, 450)
        rise(mapPanel.media, 22, 540)

        dock.translationY = DOCK_DP.dp.toFloat()
        dock.animate().translationY(0f).setStartDelay(80).setDuration(460).setInterpolator(ease).start()
        for (i in 0 until dock.apps.childCount) {
            val v = dock.apps.getChildAt(i)
            v.alpha = 0f
            v.translationY = 18.dp.toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(260L + 45L * i).setDuration(420)
                .setInterpolator(OvershootInterpolator(1.3f)).start()
        }
    }

    override fun onPause() {
        try { unregisterReceiver(tick) } catch (_: Throwable) { }
        try { unregisterReceiver(volume) } catch (_: Throwable) { }
        CarAudio.removeListener(carAudio)
        mapPanel.onPause()
        resumedNow = false
        UpdateManager.unobserve(updateObserver)
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

    override fun toast(msg: String) {
        toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
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
        if (startedNow) mapPanel.onStart()
        if (resumedNow) mapPanel.onResume()
        refreshAll()
        if (reopen) controls.open(false)
        if (snap != null) {
            val cover = ImageView(this).apply { setImageBitmap(snap); scaleType = ImageView.ScaleType.FIT_XY }
            ui.addView(cover, MATCH, MATCH)
            cover.animate().alpha(0f).setDuration(420).setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction { ui.removeView(cover) }.start()
        }
    }

    companion object {
        /** Height of the dock (big touch targets: the unit's 1024x600 panel is about 120 dpi, 1 dp = 1 px). */
        const val DOCK_DP = 92
    }
}
