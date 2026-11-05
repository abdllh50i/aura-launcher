package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Picture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.abdllh.aura.home.HomeActivity
import java.util.concurrent.Executors

/**
 * A theme change, the one path for every panel (SPEC.md section 8): nothing is rebuilt and the map is never restarted.
 *  1. Ready first, on a worker, before anything flips: the other theme's surfaces decoded, and whatever [readySteps] holds (the car's
 *     file and frames, the song's wash set). If one is not ready the theme waits.
 *  2. Ghost: the window's views recorded into a [Picture], in a view at the top of the tree on a hardware layer, plus what cannot be
 *     recorded ([ghostExtra]: the car's `TextureView`, as a bitmap at its rect).
 *  3. Swap in one frame, on what shows: [Palette] takes the new colours, and the shown panel and the chrome recolour in place ([Look]).
 *     Hidden panels are marked and catch up when shown; open windows of their own are told too; [map] gets its colours.
 *  4. Fade: the ghost's alpha 1 to 0 over 700 ms, curve `move`, on a moving car too. Touches go through it.
 *  5. Then: the old theme's bitmaps go.
 * [tick] asks once a minute (the shell's minute tick). With a turn within 300 m or a call ringing ([holdOff]) it waits, up to 2 minutes.
 * A panel not yet rebuilt says [com.abdllh.aura.home.Panel.recolours] false: it goes on restyling itself by its own [com.abdllh.aura.home.Panel.onLook].
 */
object ThemeSwap {
    private const val TAG = "AuraTheme"
    private const val HOLD_MAX_MS = 120_000L

    /** A turn nearer than this: the theme waits ([holdOff]). */
    const val NEAR_TURN_M = 300.0

    /** Before a flip: `step(night, done)`; `done` is called from any thread once what that theme needs is in memory. */
    val readySteps = ArrayList<(Boolean, () -> Unit) -> Unit>()

    /**
     * The one map gets its colours: `MapStyle.paints` through `style.transition = TransitionOptions(700, 0, false)` and
     * `layer.setProperties`, once, on the main thread. Its owner sets this. Never `setStyle`.
     */
    var map: ((Boolean) -> Unit)? = null

    /** What the picture cannot hold, drawn into it: the car's rest frame at its rect while Home shows. */
    var ghostExtra: ((Canvas) -> Unit)? = null

    /** A turn within 300 m, or a call ringing: not now. */
    var holdOff: (() -> Boolean)? = null

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var heldSince = 0L
    private var preparing = false

    /** At the minute's tick, with the window resumed. */
    fun tick(shell: HomeActivity) {
        val want = Theme.isDarkNow()
        if (want == Palette.dark) { heldSince = 0L; return }
        if (!shell.panels.top.recolours) return
        val now = SystemClock.uptimeMillis()
        if (holdOff?.invoke() == true) {
            if (heldSince == 0L) heldSince = now
            if (now - heldSince < HOLD_MAX_MS) return
        }
        heldSince = 0L
        run(shell)
    }

    /** The whole path, now (a choice in Settings or Controls comes here; the theme is already in Prefs). */
    fun run(shell: HomeActivity, done: (() -> Unit)? = null) {
        val night = Theme.isDarkNow()
        if (night == Palette.dark || preparing) { done?.invoke(); return }
        preparing = true
        val app: Context = shell.applicationContext
        worker.execute {
            try { Surfaces.prepare(app, night) } catch (t: Throwable) { Log.w(TAG, "surfaces: $t") }
            var left = readySteps.size
            if (left == 0) main.post { flip(shell, night, done) }
            else for (s in readySteps.toList()) s(night) {
                main.post { if (--left == 0) flip(shell, night, done) }
            }
        }
    }

    private fun flip(shell: HomeActivity, night: Boolean, done: (() -> Unit)?) {
        preparing = false
        if (shell.isDestroyed || Theme.isDarkNow() != night) { done?.invoke(); return }
        val t0 = SystemClock.uptimeMillis()
        val root = shell.root
        val w = root.width
        val h = root.height
        val g = if (shell.resumedNow && w > 0 && h > 0) holdGhost(root, w, h) else null
        val t1 = SystemClock.uptimeMillis()
        Theme.refresh()
        shell.windowLook(shell.panels.top.navBar)
        for (i in 0 until root.panelLayer.childCount) {
            val f = root.panelLayer.getChildAt(i)
            if (f.visibility == View.VISIBLE) Look.apply(f) else Look.markStale(f)
        }
        Look.apply(root.chrome)
        Look.apply(root.sheets)
        Look.applyToWindows()
        map?.invoke(night)
        val t2 = SystemClock.uptimeMillis()
        Log.i(TAG, "theme ${if (night) "night" else "day"}: picture ${t1 - t0} ms, recolour ${t2 - t1} ms")
        if (g == null) { after(night, done); return }
        g.animate().alpha(0f).setDuration(Tokens2.MS_theme).setInterpolator(Motion.MOVE).setStartDelay(0L).withEndAction {
            g.clear()
            after(night, done)
        }.start()
    }

    private fun after(night: Boolean, done: (() -> Unit)?) {
        worker.execute { Surfaces.free(!night) }
        done?.invoke()
    }

    /** What the window shows now, in its slot above everything, to be faded. The map is not in it: under the fade the map changes by itself. */
    private fun holdGhost(root: com.abdllh.aura.home.ShellRoot, w: Int, h: Int): com.abdllh.aura.chrome.Ghost? = try {
        root.ghost.clear() // (one that is still fading)
        val pic = Picture()
        val c = pic.beginRecording(w, h)
        for (layer in arrayOf(root.panelLayer, root.chrome, root.sheets, root.snack)) if (layer.visibility == View.VISIBLE) layer.draw(c)
        ghostExtra?.invoke(c)
        pic.endRecording()
        root.ghost.also { it.hold(pic) }
    } catch (t: Throwable) {
        Log.w(TAG, "ghost: $t")
        null // (no fade this once: a cut, not a failure)
    }
}
