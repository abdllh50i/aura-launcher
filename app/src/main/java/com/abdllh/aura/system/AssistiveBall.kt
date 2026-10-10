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
 * The firmware's floating "assistive touch" circle (it opens a panel of shortcuts), drawn by the stock launcher's
 * SuspensionService (com.nwd.fushion.assistivetouch). The car settings app switches it with the broadcasts
 * com.nwd.action.suspension.DISPLAY_LISTVIEW / HIDE_THE_LISTVIEW to the launcher (its "Assistive touch" option, on the
 * page with the CAN settings); the service keeps the state in Settings.System key_white_window_state (1 shown) and
 * reads it again at every start. AMRI turns it off once (the owner switched it on by accident and asked for it to go)
 * and offers the switch in Settings › Display & sound.
 */
object AssistiveBall {
    private const val TAG = "AuraBall"
    private const val KEY = "key_white_window_state"
    private const val SHOW = "com.nwd.action.suspension.DISPLAY_LISTVIEW"
    private const val HIDE = "com.nwd.action.suspension.HIDE_THE_LISTVIEW"
    private const val PREF_OFF_ONCE = "assistiveBallOffOnce"

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    val available: Boolean get() = Device.isNwd

    /** The stored state (the car settings app also wrote "open" for on in some versions). */
    fun shown(ctx: Context): Boolean = try {
        Settings.System.getString(ctx.contentResolver, KEY)?.trim().let { it == "1" || it == "open" }
    } catch (_: Throwable) {
        false
    }

    /** Shows or removes the circle now, and stores the state for the next start (root: a private system setting). */
    fun set(ctx: Context, on: Boolean) {
        val c = ctx.applicationContext
        send(c, on)
        worker.execute {
            val r = LocalAdb.run("settings put system $KEY ${if (on) 1 else 0}")
            Log.i(TAG, "state ${if (on) 1 else 0}: ${r.ok} ${r.output}")
        }
    }

    /** Once per install: off. Sent again a little later, in case the launcher's service was still starting. */
    fun offOnce(ctx: Context) {
        if (!available || Prefs.raw.getBoolean(PREF_OFF_ONCE, false)) return
        Prefs.raw.edit().putBoolean(PREF_OFF_ONCE, true).apply()
        if (!shown(ctx)) return
        val c = ctx.applicationContext
        set(c, false)
        main.postDelayed({ send(c, false) }, 8_000L)
    }

    private fun send(c: Context, on: Boolean) {
        try {
            c.sendBroadcast(Intent(if (on) SHOW else HIDE).setPackage(Device.STOCK_LAUNCHER))
        } catch (t: Throwable) {
            Log.w(TAG, "broadcast: $t")
        }
    }
}
