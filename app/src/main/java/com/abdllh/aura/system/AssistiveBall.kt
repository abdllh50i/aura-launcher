package com.abdllh.aura.system

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.abdllh.aura.util.Prefs
import java.util.concurrent.Executors

/**
 * The firmware's floating circles, both drawn by the stock launcher's process:
 *  - the CAN float menu, a circle with the car's controls (CanService's CanUiOperationManager / CanFloatMenu). It is
 *    shown while Settings.System car_config_can_float_menu_display is 1, which choosing or resetting the car in the
 *    CAN settings sets (CanConfigUtil.resetCarConfig turns it on); the broadcast com.nwd.action.CAN_FLOAT_MENU_SHOW_ACTION
 *    makes the menu read the setting again and show or close itself. This is the circle the owner asked to be removed.
 *  - the assistive touch (SuspensionService, com.nwd.fushion.assistivetouch), a dot with a panel of shortcuts: shown
 *    while key_white_window_state is 1, read when the service starts. The car settings' "Assistive touch" option sends
 *    HIDE_THE_LISTVIEW (removes the dot) or DISPLAY_LISTVIEW (opens the panel); stopping the service removes the dot and
 *    its panels (onDestroy).
 * AMRI turns both off once, now and at the next start (the owner switched the circle on by accident and asked for it to
 * go). Back on: the car settings' own options, which AMRI leaves alone after that.
 */
object AssistiveBall {
    private const val TAG = "AuraBall"
    private const val CAN_KEY = "car_config_can_float_menu_display"
    private const val CAN_SHOW = "com.nwd.action.CAN_FLOAT_MENU_SHOW_ACTION"
    private const val KEY = "key_white_window_state"
    private const val HIDE = "com.nwd.action.suspension.HIDE_THE_LISTVIEW"
    private const val SERVICE = "com.nwd.fushion.assistivetouch.SuspensionService"
    // 1.4.1 switched only the assistive touch, and the CAN menu's circle stayed: a second, complete pass
    private const val PREF_OFF_ONCE = "circlesOff1"
    private const val PREF_OFF_TRIES = "circlesOffTries"

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** Removes the circles now and for the next start; [done] (worker thread): done as root. */
    private fun off(c: Context, done: (Boolean) -> Unit) {
        val svc = "${Device.STOCK_LAUNCHER}/$SERVICE"
        worker.execute {
            // as root: private system settings, and another app's service
            val r = LocalAdb.run("settings put system $CAN_KEY 0; am broadcast -a $CAN_SHOW >/dev/null 2>&1; " +
                "settings put system $KEY 0; am broadcast -a $HIDE -p ${Device.STOCK_LAUNCHER} >/dev/null 2>&1; " +
                "am stopservice -n $svc >/dev/null 2>&1")
            Log.i(TAG, "off: ${r.ok} ${r.output}")
            if (!r.ok) main.post { fallback(c) } // no root shell: what an app may do itself
            done(r.ok)
        }
    }

    /** Without the root shell: the settings if the system lets AMRI write them, then the broadcasts and the service. */
    private fun fallback(c: Context) {
        try {
            Settings.System.putInt(c.contentResolver, CAN_KEY, 0)
            Settings.System.putInt(c.contentResolver, KEY, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "fallback settings: $t")
        }
        try {
            c.sendBroadcast(Intent(CAN_SHOW))
            c.sendBroadcast(Intent(HIDE).setPackage(Device.STOCK_LAUNCHER))
            c.stopService(Intent().setClassName(Device.STOCK_LAUNCHER, SERVICE))
        } catch (t: Throwable) {
            Log.w(TAG, "fallback: $t")
        }
    }

    /**
     * Once per install: off, completely. Marked done only once it went through as root (a restart within the delay, or
     * a root shell that is not up yet, tries again at the next start; three tries at most).
     */
    fun offOnce(ctx: Context) {
        if (!Device.isNwd || Prefs.raw.getBoolean(PREF_OFF_ONCE, false)) return
        // a little after the start: the launcher's own start-up (its CAN service draws the menu) is over by then
        val c = ctx.applicationContext
        main.postDelayed({
            off(c) { ok ->
                val tries = Prefs.raw.getInt(PREF_OFF_TRIES, 0) + 1
                Prefs.raw.edit().putInt(PREF_OFF_TRIES, tries).putBoolean(PREF_OFF_ONCE, ok || tries >= 3).apply()
            }
        }, 15_000L)
    }
}
