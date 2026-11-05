package com.abdllh.aura.home

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.abdllh.aura.ui.Palette

/**
 * One screen of the one window of 2.0 (docs/DEV.md, "One window"): what used to be an activity, in [HomeActivity]'s
 * window, kept by [PanelStack]. It is a Context, the activity's wrapped, so code moved from an activity keeps working on
 * `this`: getString, resources, getSystemService, label(), iconView(), startActivity (the activity's, in its task), Snack
 * and Modal (they walk the wrappers to the activity). What a Context cannot give is here under the activity's own names:
 * [finish], [requestPermissions], [runOnUiThread], [window]. What must have the real activity takes [shell].
 *
 * Its callbacks are the activity's, kept in pairs by the stack whatever order the window's own come in:
 * [onShow] / [onHide] were onStart / onStop, [onResume] / [onPause] were onResume / onPause. All on the main thread.
 * Only the top panel is shown; one under it has been told [onHide], and is not drawn once the move over it has ended.
 *
 * A panel that throws from a callback is taken off the stack and the screen goes on without it ([PanelStack]); the home
 * panel's own failures stay what they always were, a crash the crash guard counts.
 */
abstract class Panel(val shell: HomeActivity, val name: String) : ContextWrapper(shell) {
    /** Its views, asked for once. Never replaced, a rebuild changes what is in it; the stack moves the frame around it. */
    abstract val root: View

    /** It covers the whole screen with its own room: nothing under it can be seen, and the map sleeps ([MapHost.covered]). */
    open val opaque: Boolean get() = false

    /** A touch on nothing of it stops here, and does not reach what is under it: the map, or another panel. */
    open val solid: Boolean get() = opaque

    /** Off the stack it stays built and hidden, for the next time. False: dropped, [onDestroy], its views let go of. */
    open val keep: Boolean get() = false

    /**
     * WindowManager.LayoutParams.SOFT_INPUT_* for the window while it is on top. With ADJUST_RESIZE only this panel
     * follows the keyboard: the map's slot and the panels under it keep their size, so nothing heavy is laid out again.
     * A mode that differs from the panel under it makes the move between them a relayout of the window, in the frame that
     * must answer: leave it alone, or differ only while a field has the focus ([PanelStack.softInputChanged], MapsPanel).
     */
    open val softInput: Int get() = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING

    /** The navigation bar's colour while it is on top (the emulator has one, the unit none). */
    open val navBar: Int get() = Palette.bg

    /**
     * It came to the top with what the caller of [PanelStack.show] gave, or the window started with it on top, or what
     * was over it has left (null then). Was onStart, plus handle(intent) when there are args. Also at the first time:
     * its views are built by then or here.
     */
    open fun onShow(args: Bundle?) {}

    /** [PanelStack.show] asked for it again while it is in the stack: was onNewIntent, with [args] in place of the intent. */
    open fun onArgs(args: Bundle) {}

    /** On top with the window resumed. */
    open fun onResume() {}

    open fun onPause() {}

    /** Something came over it, or it left, or the window stopped. [isGone] tells when this is for good, and for a kept one [isOff]. */
    open fun onHide() {}

    /**
     * A kept panel, off the stack and at rest (Back, the Home key, [finish]: it is seen no more until it is asked for again): what
     * belonged to that visit goes. It had its [onHide] before it began to leave, unless the window was stopped by then. What an activity
     * lost at its onDestroy, except what is heavy to build. Not told for one that came back on the way.
     */
    open fun onOff() {}

    /** Back, while it is on top: true when it used it (a list closed); false, and it leaves. */
    open fun onBack(): Boolean = false

    /** The answer to its own [requestPermissions]; not told once it is gone. */
    open fun onPermission(code: Int, grants: IntArray) {}

    /** Every minute (and when the time or the language changes) while it is on top, with the window resumed. */
    open fun onTick() {}

    /**
     * The theme or the accent may differ from what it was built with: compare, and take the new one if now is a good time.
     * On top only: after [onResume], and at every minute's tick, so what it puts off is asked again.
     */
    open fun onLook() {}

    open fun onTrimMemory(level: Int) {}

    /** Off the stack and not kept, or the window is ending: everything it holds goes. */
    open fun onDestroy() {}

    /**
     * For a late answer (a thread, a server, a post): the panel was dropped or the window ended, nothing of it is to be
     * touched. A kept panel that is only hidden is not gone: for "still in front" ask [isTop].
     */
    val isGone: Boolean get() = shell.isDestroyed || !alive

    val isTop: Boolean get() = !isGone && shell.panels.top === this

    /** Off the stack: Back or the Home key have taken it off, not only something come over it. */
    val isOff: Boolean get() = !shell.panels.holds(this)

    /** Leaves the stack (what Back does, without asking [onBack]). */
    fun finish() = shell.panels.finish(this)

    fun requestPermissions(perms: Array<String>, code: Int) = shell.panels.askPermissions(this, perms, code)

    fun runOnUiThread(r: () -> Unit) = shell.runOnUiThread(r)

    val window: Window get() = shell.window

    /**
     * During [onShow]: it has just come onto the stack from off it, which is what a new activity used to be, rather than the
     * window starting again with it on top, or what was over it leaving.
     */
    var newOnStack = false
        internal set

    // (the stack's: where it has it)
    internal var alive = true
    internal var shown = false
    internal var resumed = false
    /** [onOff] is still to come. */
    internal var offDue = false
    internal lateinit var frame: PanelFrame

    companion object {
        /**
         * A dialog or a menu opened in the shell's window: they are windows of their own, on top of every panel, and would
         * stay over whatever comes next (an activity's went with the activity). They close when the top panel changes.
         * Anywhere else (a screen that is still an activity) this does nothing.
         */
        fun own(ctx: Context, isOpen: () -> Boolean, close: () -> Unit) {
            var c: Context? = ctx
            while (c is ContextWrapper) {
                if (c is HomeActivity) { c.ownWindow(isOpen, close); return }
                c = c.baseContext
            }
        }
    }
}
