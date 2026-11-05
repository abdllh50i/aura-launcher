package com.abdllh.aura.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.MotionEvent
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.nav.MapController
import com.abdllh.aura.system.CrashGuard
import com.abdllh.aura.system.NwdBridge
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.afterNextDraw
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs

/**
 * The shell: the one window of 2.0, which holds the one map ([MapController]), the panels ([PanelStack], Home at the bottom), the
 * chrome and the sheets ([ShellRoot]), and hands what the system tells an activity on to the map and to the top panel. Nothing of any
 * screen is here: the home screen itself is [HomePanel]. It keeps this name and its manifest entry for good: the home intent, BootHome,
 * HomeGuard, stock Android's preferred home and every script know it.
 */
class HomeActivity : Activity() {
    lateinit var root: ShellRoot
        private set
    lateinit var panels: PanelStack
        private set
    lateinit var map: MapController
        private set
    /** Is the window still drawing? ([PanelStack] and this class tell it when a frame should come.) */
    internal lateinit var drawWatch: DrawWatch
        private set
    private var builtLang = ""
    private var counted = false
    private var firstResume = true

    var startedNow = false
        private set
    var resumedNow = false
        private set

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (::panels.isInitialized) {
                panels.tick()
                drawWatch.expect()
            }
        }
    }

    // (a change of the system's configuration puts the system's language back as the process's default, and what asks the default, the map's style and the route's words, would not follow the UI's)
    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        java.util.Locale.setDefault(resources.configuration.locales[0])
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        java.util.Locale.setDefault(resources.configuration.locales[0])
        super.onCreate(savedInstanceState)
        if (CrashGuard.bailedOut) { // (AuraApp has done it: nothing else of AMRI started in this process)
            finish()
            return
        }
        if (CrashGuard.isCrashLoop()) {
            CrashGuard.bailOut(this)
            finish()
            return
        }
        // (before the by-name check: home is wanted either way, and a re-ask that fails is then a start that never drew)
        com.abdllh.aura.guard.GuardMarks.homeWanted()
        if (Screens.homeByName(this)) return
        Screens.homeBuilt(intent, alive)
        alive++
        counted = true
        current = this
        Fonts.refreshLocale(this)
        Theme.refresh()
        builtLang = Prefs.lang
        root = ShellRoot(this)
        // (no background, and alpha in the window: the map shows where nothing is drawn. Each panel draws its own room, the home screen
        // a wall with its card open. From the start, as the format would otherwise change under a running window with the map's first frame)
        window.setFormat(PixelFormat.TRANSLUCENT)
        setContentView(root)
        drawWatch = DrawWatch(this)
        map = MapController(this)
        panels = PanelStack(this, HomePanel(this))
        MediaMonitor.start(this)
        NwdBridge.startStockServicesOncePerBoot(this)
        com.abdllh.aura.chrome.Chrome.attach(this) // (what stays over every panel)
        // what this start is for: a recreate brings back what was on top; the intent says it once (a recreate reads it again)
        val again = reopen
        reopen = null
        if (again != null) panels.show(again.first, again.second)
        else Screens.panelIn(intent)?.let { name ->
            panels.show(name, intent.extras)
            intent.removeExtra(Screens.EXTRA_PANEL)
        }
    }

    override fun onStart() {
        super.onStart()
        startedNow = true
        map.started()
        panels.started()
    }

    override fun onResume() {
        java.util.Locale.setDefault(resources.configuration.locales[0])
        super.onResume()
        resumedNow = true
        if (languageChanged()) return
        com.abdllh.aura.system.Animations.ensureAnimators()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        registerReceiver(tick, f)
        map.resumed()
        panels.resumed()
        drawWatch.expect()
        if (firstResume) {
            firstResume = false
            // whichever panel it is (the guard's "OK" follows in about 20 s)
            root.afterNextDraw { if (!isDestroyed) com.abdllh.aura.guard.GuardMarks.homeDrawn() }
        }
    }

    override fun onPause() {
        try { unregisterReceiver(tick) } catch (_: Throwable) { }
        resumedNow = false
        if (::panels.isInitialized) { panels.paused(); map.paused() }
        super.onPause()
    }

    override fun onStop() {
        startedNow = false
        if (::panels.isInitialized) { panels.stopped(); map.stopped() }
        // a sheet left open under an app opened from it (so it did not close during the app's opening) goes now
        if (::root.isInitialized) root.closeSheets()
        super.onStop()
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && ::drawWatch.isInitialized) drawWatch.expect()
        return super.dispatchTouchEvent(e)
    }

    override fun onDestroy() {
        if (::drawWatch.isInitialized) drawWatch.destroyed()
        if (counted) { counted = false; alive-- }
        if (current === this) current = null
        // (a system configuration change: what was on top is back in the new window)
        if (::panels.isInitialized) {
            if (isChangingConfigurations && reopen == null && panels.top.name != Screens.HOME) reopen = panels.top.name to null
            panels.destroyed()
            map.destroyed()
        }
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::panels.isInitialized) { panels.trim(TRIM_LOW_MEMORY); map.trim(TRIM_LOW_MEMORY) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::panels.isInitialized) { panels.trim(level); map.trim(level) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!::panels.isInitialized) return
        // (the Home key's intent names no panel: Home. Not setIntent: the one a recreate reads stays the first)
        val name = Screens.panelIn(intent)
        panels.show(name ?: Screens.HOME, if (name == null) null else intent.extras)
    }

    @Deprecated("launcher: back does nothing on the home screen")
    override fun onBackPressed() {
        if (::panels.isInitialized) panels.back()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        if (::panels.isInitialized) panels.answer(code, grants)
    }

    /** The name of the top panel ([com.abdllh.aura.system.Front]). */
    val topPanel: String get() = if (::panels.isInitialized) panels.top.name else Screens.HOME

    /** A window of its own opened over the panels ([Panel.own]). */
    fun ownWindow(isOpen: () -> Boolean, close: () -> Unit) {
        if (::panels.isInitialized) panels.own(isOpen, close)
    }

    /** The system bars for the top panel, and no window background: the map, and each panel's own room ([HomePanel]). */
    fun windowLook(navBar: Int) = Theme.window(this, navBar, fill = false)

    /**
     * The language is not the one this window was built in (Settings, a debug hook): the whole window again, the language
     * lives in the activity's resources, with the same panel on top, told [args]. True when so. The only time the map is
     * rebuilt in normal use (home-shell.md 7.5); NavSession goes on through it.
     */
    fun languageChanged(args: Bundle? = null): Boolean {
        if (builtLang == Prefs.lang) return false
        AppRepo.clear() // (the app names are the old language's)
        reopen = panels.top.name to args
        recreate()
        return true
    }

    companion object {
        /** The shell that is alive, if one is: [Screens] then needs no activity start at all. */
        var current: HomeActivity? = null
            private set
        /** Home activities alive in this process: one at most ([Screens.homeBuilt]; a recreate destroys before it builds). */
        private var alive = 0
        /** What a recreate is to bring back on top (the new instance takes it in its onCreate). */
        private var reopen: Pair<String, Bundle?>? = null
        /** [onLowMemory] told to the panels as the level that means the same. */
        const val TRIM_LOW_MEMORY = android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
    }
}
