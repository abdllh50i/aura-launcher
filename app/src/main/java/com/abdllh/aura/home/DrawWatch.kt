package com.abdllh.aura.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.ViewTreeObserver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Is the one window still drawing? When the compositor gives no buffer for 4 s, HWUI writes the window's surface off for good
 * (`dequeueBuffer failed, error = -110; switching to fallback`, `reserveNext failed`: ThreadedRenderer disables itself and
 * releases the Surface), and ViewRootImpl draws nothing from then on, until the window next relayouts and gets a Surface
 * again. An activity per screen never noticed: the next screen had a window of its own, and Home a new surface when it came
 * back. The one window would stay on its last picture until another app came over it, with every touch still working under
 * that picture. Seen on a loaded emulator, not known on the unit.
 *
 * So whoever expects a frame says so ([expect]: a panel change, the minute's tick, a finger coming down), and if none was
 * drawn [LIMIT_MS] later the window is made to relayout, which is free when nothing was wrong. If that does not bring it back
 * the whole window is built again, once for this process, and Settings → About tells ([note]). Nothing here can loop.
 */
internal class DrawWatch(private val shell: HomeActivity) {
    private val main = Handler(Looper.getMainLooper())
    private var drawnAt = 0L
    private var askedAt = 0L
    private var waiting = false
    private var tries = 0

    init {
        shell.root.viewTreeObserver.addOnDrawListener(ViewTreeObserver.OnDrawListener {
            drawnAt = SystemClock.uptimeMillis()
            tries = 0
        })
    }

    /** A frame should come now (one is asked for, also on a screen where nothing else moves). */
    fun expect() {
        if (waiting || shell.isDestroyed) return
        waiting = true
        askedAt = SystemClock.uptimeMillis()
        shell.root.invalidate()
        main.postDelayed(look, LIMIT_MS)
    }

    private val look = Runnable {
        waiting = false
        if (drawnAt >= askedAt || shell.isDestroyed || !shell.resumedNow) return@Runnable
        // (with the display off nothing draws, rightly)
        if (shell.window?.windowManager?.defaultDisplay?.state != Display.STATE_ON) return@Runnable
        lost()
    }

    private fun lost() {
        tries++
        Log.w(TAG, "the window drew nothing for $LIMIT_MS ms (try $tries)")
        note(shell, "drew nothing for $LIMIT_MS ms, try $tries")
        when {
            tries == 1 -> {
                // (a new layout of the window, as a change of its attributes asks: it brings the Surface back)
                shell.window.attributes = shell.window.attributes
                expect()
            }
            !rebuilt -> {
                rebuilt = true
                shell.recreate()
            }
        }
    }

    fun destroyed() = main.removeCallbacks(look)

    companion object {
        private const val TAG = "AuraShell"
        private const val PREFS = "aura_shell"
        private const val KEY = "windowLost"
        private const val LIMIT_MS = 3000L

        /** The whole window was built again for this: once per process. */
        private var rebuilt = false

        private fun note(c: Context, what: String) {
            try {
                val at = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date())
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, "$at  $what").apply()
            } catch (_: Throwable) {
            }
        }

        /** The last time the window stopped drawing, kept across starts (for Settings → About): a healthy unit has no such line. */
        fun note(c: Context): String =
            try { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty() } catch (_: Throwable) { "" }
    }
}
