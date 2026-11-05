package com.abdllh.aura.home

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.music.MusicPanel
import com.abdllh.aura.nav.MapsPanel
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.settings.SettingsPanel
import com.abdllh.aura.system.Front
import com.abdllh.aura.system.IdleGc
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.afterNextDraw
import com.abdllh.aura.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The panels of the one window, Home always at the bottom. [show] opens one or brings it back, [back] and [finish] take
 * it off, and the window's own lifecycle reaches the top panel through [started], [resumed], [paused], [stopped], in
 * pairs, whatever comes in what order (a panel chosen by an intent while the window is stopped gets its [Panel.onShow]
 * when it starts; [firstArgs]).
 *
 * Nothing here may take the home screen down or leave it stuck. A panel that cannot be built or throws from a callback is
 * dropped, the panel under it comes back and Settings → About says so ([failedNote]); only the home panel's own failures go
 * through, since that is a crash the crash guard counts and answers. A move that does not end is ended ([MOVE_LIMIT_MS]),
 * and everything at rest is reached from one place ([settle]), wherever a move stopped.
 */
class PanelStack(private val shell: HomeActivity, private val home: Panel) {
    private val root: ShellRoot get() = shell.root
    private val main = Handler(Looper.getMainLooper())

    /** Bottom to top. */
    private val stack = arrayListOf(home)
    /** Every panel that exists: in the stack, on its way off, or kept. */
    private val built = LinkedHashMap<String, Panel>()
    private val asked = HashMap<Int, Panel>()
    private val windows = ArrayList<Owned>()
    private var firstArgs: Bundle? = null
    /** For Settings → About: how long each panel's first open took, how long its build ahead of time, and who could not. */
    private val firstOpen = HashMap<String, Long>()
    private val builtAhead = HashMap<String, Long>()
    private val noAhead = HashSet<String>()
    private var softInput = -1
    private var lastMove = 0
    private val overdue = Runnable { Log.w(TAG, "a move did not end"); settle() }

    private class Owned(val isOpen: () -> Boolean, val close: () -> Unit)

    val top: Panel get() = stack.last()

    fun holds(p: Panel): Boolean = p in stack

    /** The panel on its way off, over the top one (from before the one under it is told it is back). */
    var leaving: Panel? = null
        private set

    /** A trip is on ([tripChanged]). */
    private var tripOn = NavSession.state != NavSession.State.IDLE
    /** Maps, drawn under a panel that lies beside the column ([Panel.besideColumn]). */
    private var beside: Panel? = null
    private val navListener: () -> Unit = { tripChanged() }

    init {
        attach(home)
        applyTo(home)
        NavSession.addListener(navListener)
    }

    // ------------------------------------------------------------------------------------------ opening and closing
    /**
     * Panel [name] on top, with [args]. One in the stack already comes back, with what is above it gone, and is told
     * through [Panel.onArgs]; a panel still leaving turns back; Home means everything above it goes. Sheets, dialogs, menus
     * and the keyboard of what was on top close (a panel cannot open under them).
     */
    fun show(name: String, args: Bundle? = null) {
        root.closeSheets()
        if (name == Screens.HOME) { popTo(0); return }
        val at = stack.indexOfFirst { it.name == name }
        if (at >= 0) {
            popTo(at)
            if (args != null) call(top, "onArgs") { top.onArgs(args) }
            return
        }
        val t0 = SystemClock.uptimeMillis()
        val p = built[name] ?: make(name)
        if (p == null) { Snack.show(shell, R.string.panel_failed); return }
        val under = top
        val wasUnder = beside === p // (Maps, drawn under the panel on top: that one simply goes off it)
        closeWindows()
        under.frame.live = false
        leave(under)
        p.alive = true
        p.offDue = false
        p.newOnStack = true
        stack.add(p)
        if (!p.opaque) root.mapHost.covered = false // ("wake first")
        place(p)
        if (!arrive(p, args)) { failed(p); return }
        setBeside(besideFor(p))
        val ready = SystemClock.uptimeMillis() - t0
        move { done ->
            if (wasUnder) PanelMotion.between(under.name, p.name, true).closing(under.frame, p.frame, done)
            else PanelMotion.between(under.name, p.name, beside != null).opening(p.frame, under.frame, done)
        }
        if (shell.resumedNow) p.frame.afterNextDraw {
            val ms = SystemClock.uptimeMillis() - t0
            val first = firstOpen.getOrPut(name) { ms }
            lastOpen = "$name: $ms ms to its first frame ($ready ms before it); the first time $first ms" + (builtAhead[name]?.let { "; built ahead in $it ms" } ?: "")
            Log.i(TAG, lastOpen)
        }
    }

    /**
     * Builds a kept panel ahead of time, out of sight, while nothing else happens, so its first [show] has nothing to build: its
     * views are laid out with the others (a first build on the unit's main thread has no starting window to hide behind).
     */
    fun prepare(name: String) {
        if (built[name] != null) return
        val t0 = SystemClock.uptimeMillis()
        val p = make(name)
        if (p == null) { noAhead.add(name); return }
        builtAhead[name] = SystemClock.uptimeMillis() - t0
        Log.i(TAG, "$name: built ahead in ${builtAhead[name]} ms")
        // One real frame of it, one level of 255 over what shows: its letters in the GPU's store, its icons rasterised and its
        // shaders compiled, all of which a first open otherwise pays in its first frame. Then out of sight. (The frame's
        // recording does not outlive it: a later open records anew, and so does the first.)
        val f = p.frame
        f.live = false
        f.alpha = ONE_LEVEL
        val hide = Runnable { if (p !in stack) { rest(f); f.visibility = View.INVISIBLE } }
        f.afterNextDraw { main.removeCallbacks(hide); hide.run() }
        main.postDelayed(hide, HIDE_AFTER_MS)
    }

    /**
     * [prepare], [afterMs] from now or at the first quiet moment after that: the home screen at rest on top of a resumed window,
     * no sheet open, no finger on the screen for a while, and one panel at a time, in the order asked. A build holds the main
     * thread once, so not under anything the owner is doing. At any other screen it is off: the home panel asks again at its next
     * resume. A panel that could not be built is not tried again this way (each try would be that hold for nothing): only when it is asked for.
     */
    fun prepareWhenQuiet(name: String, afterMs: Long) {
        if (built[name] != null || name in noAhead) return
        quietFor[name] = SystemClock.uptimeMillis() + afterMs
        lookAgain()
    }

    /** What is to be built ahead of time, in the order asked, and not before when (uptimeMillis). */
    private val quietFor = LinkedHashMap<String, Long>()
    private var nextBuildAt = 0L
    private val whenQuiet = Runnable { buildWhatIsDue() }

    private fun lookAgain() {
        main.removeCallbacks(whenQuiet)
        val first = quietFor.values.minOrNull() ?: return
        main.postAtTime(whenQuiet, maxOf(first, nextBuildAt))
    }

    private fun buildWhatIsDue() {
        if (shell.isDestroyed || !shell.resumedNow || top !== home) return
        quietFor.keys.removeAll { built[it] != null }
        val now = SystemClock.uptimeMillis()
        val due = quietFor.entries.firstOrNull { it.value <= now }?.key
        if (due == null) { lookAgain(); return }
        if (leaving != null || root.sheetOpen || now - root.touchedAt < QUIET_AFTER_TOUCH_MS) {
            main.postDelayed(whenQuiet, QUIET_AGAIN_MS)
            return
        }
        quietFor.remove(due)
        prepare(due)
        nextBuildAt = SystemClock.uptimeMillis() + QUIET_AGAIN_MS
        lookAgain()
    }

    /** Back: a sheet, what the top panel uses it for, or the top panel leaves. At Home: nothing else. */
    fun back() {
        if (root.closeSheets()) return
        val p = top
        var used = false
        call(p, "onBack") { used = p.onBack() }
        if (!used && stack.size > 1) popTo(stack.lastIndex - 1)
    }

    /** [p] leaves the stack, from wherever it is in it. */
    fun finish(p: Panel) {
        val at = stack.indexOf(p)
        if (at <= 0) return // (Home does not leave)
        if (at == stack.lastIndex) { popTo(at - 1); return }
        stack.removeAt(at)
        if (!p.keep) p.alive = false
        p.offDue = true
        gone(p)
    }

    /** The panel at [index] back on top: the top one leaves by its move, those between it and that one at once. */
    private fun popTo(index: Int) {
        if (stack.lastIndex <= index) return
        val t0 = SystemClock.uptimeMillis()
        closeWindows()
        val off = stack.removeAt(stack.lastIndex)
        off.frame.live = false
        if (!off.keep) off.alive = false // (first: what it does on the way out sees that it is for good)
        off.offDue = true
        leave(off)
        while (stack.lastIndex > index) {
            val between = stack.removeAt(stack.lastIndex)
            if (!between.keep) between.alive = false
            between.offDue = true
            gone(between)
        }
        leaving = off
        val under = top
        under.frame.visibility = View.VISIBLE
        under.frame.live = true
        if (!under.opaque) root.mapHost.covered = false // ("wake first")
        if (!arrive(under, null)) { failed(under); return }
        val ready = SystemClock.uptimeMillis() - t0
        move { done -> PanelMotion.between(off.name, under.name, beside != null).closing(off.frame, under.frame, done) }
        if (shell.resumedNow) under.frame.afterNextDraw {
            Log.i(TAG, "back to ${under.name}: ${SystemClock.uptimeMillis() - t0} ms to its first frame ($ready ms before it)")
        }
    }

    /** A panel not known here (a name from outside, not a build's mistake): null, as one that cannot be built. */
    private fun make(name: String): Panel? = try {
        val p = when (name) {
            Screens.SETTINGS -> SettingsPanel(shell)
            Screens.MAPS -> MapsPanel(shell)
            Screens.MUSIC -> MusicPanel(shell)
            Screens.APPS -> AppsPanel(shell)
            else -> if (BuildConfig.DEBUG) debugPanel(name) else null
        }
        p?.also { attach(it) }
    } catch (t: Throwable) {
        report(name, "build", t)
        null
    }

    /** Debug builds: a panel from src/debug for trying this class out (am start ... --es panel probe). */
    private fun debugPanel(name: String): Panel? {
        val cls = when (name) { "probe" -> "ProbePanel"; "gallery" -> "GalleryPanel"; else -> return null }
        return Class.forName("com.abdllh.aura.debug.$cls").getConstructor(HomeActivity::class.java).newInstance(shell) as Panel
    }

    private fun attach(p: Panel) {
        val f = PanelFrame(shell, root, p)
        f.addView(p.root, MATCH, MATCH)
        root.panelLayer.addView(f, MATCH, MATCH)
        p.frame = f
        built[p.name] = p
    }

    private fun place(p: Panel) {
        val f = p.frame
        f.visibility = View.VISIBLE
        f.live = true
        val layer = root.panelLayer
        if (layer.getChildAt(layer.childCount - 1) !== f) f.bringToFront()
    }

    /** A panel fails: it goes as if it had never been asked for, and the one under it comes back. Home never gets here. */
    private fun failed(p: Panel) {
        stack.remove(p)
        p.alive = false
        p.frame.live = false
        leave(p)
        destroy(p)
        val under = top
        under.frame.visibility = View.VISIBLE
        under.frame.live = true
        leaving = null
        if (!arrive(under, null)) { failed(under); return }
        settle()
        Snack.show(shell, R.string.panel_failed)
    }

    // ------------------------------------------------------------------------------------------ the top panel's callbacks
    /** It is the top one now: the window follows it, and it goes as far as the window has. */
    private fun arrive(p: Panel, args: Bundle?): Boolean {
        com.abdllh.aura.ui.Look.catchUp(p.frame) // (a theme changed while it was hidden; before its first frame, by whichever way it came back)
        applyTo(p)
        if (!shell.startedNow) { firstArgs = args; return true }
        return bringUp(p, args)
    }

    private fun bringUp(p: Panel, args: Bundle?): Boolean {
        if (!p.shown) {
            p.shown = true
            if (!call(p, "onShow") { p.onShow(args) }) return false
            p.newOnStack = false
        }
        if (shell.resumedNow && !p.resumed) {
            p.resumed = true
            if (!call(p, "onResume") { p.onResume() }) return false
            call(p, "onLook") { p.onLook() }
        }
        return true
    }

    private fun leave(p: Panel) {
        if (p.resumed) { p.resumed = false; call(p, "onPause") { p.onPause() } }
        if (p.shown) { p.shown = false; call(p, "onHide") { p.onHide() } }
    }

    private fun applyTo(p: Panel) {
        followSoftInput(p)
        shell.windowLook(p.navBar)
        Front.setPanel(p.name)
        if (shell.startedNow) shell.drawWatch.expect()
    }

    /** [p]'s [Panel.softInput] is another now (a field of it took the focus, or lost it): the window follows, if it is on top. */
    fun softInputChanged(p: Panel) {
        if (p === top) followSoftInput(p)
    }

    private fun followSoftInput(p: Panel) {
        if (p.softInput == softInput) return
        softInput = p.softInput
        shell.window.setSoftInputMode(softInput)
        root.followsKeyboard = softInput and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST == android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    private inline fun call(p: Panel, what: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (t: Throwable) {
        if (p === home) throw t
        report(p.name, what, t)
        false
    }

    private fun report(name: String, what: String, t: Throwable) {
        Log.e(TAG, "$name.$what", t)
        try {
            val at = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date())
            shell.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FAILED, "$at  $name.$what: $t").apply()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------------------------------ beside the column, on a trip
    private fun besideFor(t: Panel): Panel? =
        if (tripOn && t.besideColumn && t.name != Screens.MAPS) built[Screens.MAPS] ?: make(Screens.MAPS) else null

    /** Maps under the panels from now on, drawn and taking the touches they leave (End, pause, next; the map under it), or not. Only alpha and order: no layout of its own. */
    private fun setBeside(want: Panel?) {
        val was = beside
        if (was === want) return
        beside = want
        if (was != null && was !== top) {
            was.frame.live = false
            was.frame.visibility = View.INVISIBLE
            call(was, "onBeside") { was.onBeside(false) }
        }
        if (want == null) return
        val f = want.frame
        f.visibility = View.VISIBLE
        f.live = true
        val layer = root.panelLayer
        if (layer.getChildAt(0) !== f) for (c in List(layer.childCount) { layer.getChildAt(it) }) if (c !== f) c.bringToFront()
        call(want, "onBeside") { want.onBeside(true) }
    }

    private fun tripChanged() {
        val on = NavSession.state != NavSession.State.IDLE
        if (on == tripOn) return
        tripOn = on
        val t = top
        call(t, "onTrip") { t.onTrip(on) }
        if (leaving == null) {
            setBeside(besideFor(t))
            root.mapHost.covered = t.opaque
        }
    }

    fun started() {
        val a = firstArgs
        firstArgs = null
        val p = top
        if (!bringUp(p, a)) failed(p)
    }

    fun resumed() {
        val p = top
        if (!bringUp(p, null)) failed(p)
    }

    fun paused() {
        settle() // (no move runs while the window is not resumed)
        val p = top
        if (p.resumed) { p.resumed = false; call(p, "onPause") { p.onPause() } }
    }

    fun stopped() {
        val p = top
        if (p.shown) { p.shown = false; call(p, "onHide") { p.onHide() } }
    }

    fun tick() {
        val p = top
        com.abdllh.aura.ui.ThemeSwap.tick(shell)
        call(p, "onTick") { p.onTick() }
        call(p, "onLook") { p.onLook() }
    }

    fun trim(level: Int) {
        for (p in built.values.toList()) call(p, "onTrimMemory") { p.onTrimMemory(level) }
    }

    /** The window ends. */
    fun destroyed() {
        NavSession.removeListener(navListener)
        main.removeCallbacks(overdue)
        main.removeCallbacks(whenQuiet)
        for (p in built.values.toList()) {
            p.alive = false
            destroy(p)
        }
    }

    // ------------------------------------------------------------------------------------------ permissions, windows
    fun askPermissions(p: Panel, perms: Array<String>, code: Int) {
        asked[code] = p
        shell.requestPermissions(perms, code)
    }

    fun answer(code: Int, grants: IntArray) {
        val p = asked.remove(code) ?: return
        if (!p.isGone) call(p, "onPermission") { p.onPermission(code, grants) }
    }

    /** [Panel.own]. */
    fun own(isOpen: () -> Boolean, close: () -> Unit) {
        windows.removeAll { !it.isOpen() }
        windows.add(Owned(isOpen, close))
    }

    private fun closeWindows() {
        for (o in windows) try { if (o.isOpen()) o.close() } catch (_: Throwable) { } // (its window may be gone meanwhile)
        windows.clear()
        val f = shell.currentFocus ?: return
        try { (shell.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(f.windowToken, 0) } catch (_: Throwable) { }
        f.clearFocus()
    }

    // ------------------------------------------------------------------------------------------ moves
    private fun move(run: (() -> Unit) -> Unit) {
        // (nobody to see it: a panel chosen while the window is stopped is just there. The owner's switch, Settings →
        // Display & sound, was about the window animation of activities: screens change in a cut, as the firmware has them)
        if (!shell.resumedNow || !Prefs.screenAnimations) { settle(); return }
        val id = ++lastMove
        main.removeCallbacks(overdue)
        main.postDelayed(overdue, MOVE_LIMIT_MS)
        try {
            run { if (id == lastMove) settle() }
        } catch (t: Throwable) {
            Log.w(TAG, "move: $t")
            settle()
        }
    }

    /**
     * Everything as it is at rest, from wherever the moves were: the top panel in place, those under it not drawn (a panel
     * covered is one more tree to draw at every frame otherwise, on the frames that count), those off the stack gone.
     */
    private fun settle() {
        main.removeCallbacks(overdue)
        lastMove++ // (an end still to come is nothing now)
        leaving = null
        if (shell.isDestroyed) return
        val t = top
        val b = besideFor(t)
        for (p in built.values.toList()) {
            rest(p.frame)
            when {
                p === t || p === b -> p.frame.visibility = View.VISIBLE
                p in stack -> p.frame.visibility = View.INVISIBLE
                else -> gone(p)
            }
        }
        setBeside(b)
        root.mapHost.covered = t.opaque
    }

    private fun rest(f: View) {
        f.animate().cancel()
        f.setTag(R.id.aura_exiting, null)
        f.alpha = 1f
        f.translationX = 0f
        f.translationY = 0f
        f.scaleX = 1f
        f.scaleY = 1f
        if (f.layerType != View.LAYER_TYPE_NONE) f.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private fun gone(p: Panel) {
        rest(p.frame)
        if (!p.keep) { destroy(p); return }
        p.frame.visibility = View.INVISIBLE
        if (p.offDue) {
            p.offDue = false
            call(p, "onOff") { p.onOff() }
        }
    }

    private fun destroy(p: Panel) {
        built.remove(p.name)
        root.panelLayer.removeView(p.frame)
        call(p, "onDestroy") { p.onDestroy() }
        if (!shell.isDestroyed) IdleGc.request("panel closed", GC_AFTER_MS)
    }

    companion object {
        private const val TAG = "AuraShell"
        private const val PREFS = "aura_shell"
        private const val KEY_FAILED = "panelFailed"
        /** Past this a move is over, whatever became of it: its panel is simply there. */
        private const val MOVE_LIMIT_MS = 2000L
        private const val GC_AFTER_MS = 3000L
        /** A finger down this recently: the owner is doing something ([prepareWhenQuiet]). */
        private const val QUIET_AFTER_TOUCH_MS = 1500L
        private const val QUIET_AGAIN_MS = 2000L
        private const val ONE_LEVEL = 1f / 255f
        private const val HIDE_AFTER_MS = 2000L

        /** The last panel opened, and how long that took (for Settings → About). */
        @Volatile var lastOpen = ""
            private set

        /** The last panel that failed, kept across starts (for Settings → About): a healthy unit has no such line. */
        fun failedNote(c: Context): String =
            try { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FAILED, "").orEmpty() } catch (_: Throwable) { "" }
    }
}
