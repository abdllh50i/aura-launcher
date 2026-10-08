package com.abdllh.aura.system

import android.content.Context
import android.content.Intent
import android.util.Log
import com.abdllh.aura.util.Prefs
import java.io.File

/**
 * Compatibility shims for the NWD head-unit firmware. The stock launcher (com.android.launcher, "AppID 4" in the
 * firmware's application table) does two jobs besides drawing the home screen, and Aura repeats them so nothing is lost
 * when Aura is the home app:
 *  1. it tells KernelService "the home screen is in front" (ACTION_APP_IN_OUT, id 4) every time it starts, so the
 *     firmware's source stack knows the user is on Home;
 *  2. it starts three services that live in its package: the floating volume bar, the boot tip and the assistive touch.
 * Everything here is a no-op on devices that are not NWD units.
 */
object NwdBridge {
    private const val TAG = "AuraNwd"
    private const val ACTION_APP_IN_OUT = "com.nwd.action.ACTION_APP_IN_OUT"
    private const val HOME_APP_ID = 4

    private val STOCK_SERVICES = listOf(
        "com.nwd.volumeview.VolumeService",
        "com.nwd.action.ACTION_BOOT_TIP",
        "com.nwd.action.SuspensionService"
    )

    /** The home screen came to the foreground. */
    fun notifyHomeForeground(ctx: Context) {
        if (!Device.isNwd) return
        try {
            ctx.sendBroadcast(
                Intent(ACTION_APP_IN_OUT)
                    .putExtra("extra_app_id", HOME_APP_ID)
                    .putExtra("extra_app_operation", 1)
                    .putExtra("extra_app_event", 0)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "app in/out: $t")
        }
    }

    /** Starts the stock launcher's helper services once per boot (the stock launcher did this in onCreate). */
    fun startStockServicesOncePerBoot(ctx: Context) {
        if (!Device.isNwd) return
        val bootId = try { File("/proc/sys/kernel/random/boot_id").readText().trim() } catch (_: Throwable) { "" }
        if (bootId.isNotEmpty() && Prefs.raw.getString("nwdBootId", "") == bootId) return
        var started = 0
        for (action in STOCK_SERVICES) {
            try {
                if (ctx.startService(Intent(action).setPackage(Device.STOCK_LAUNCHER)) != null) started++
            } catch (t: Throwable) {
                Log.w(TAG, "service $action: $t")
            }
        }
        if (started > 0 && bootId.isNotEmpty()) Prefs.raw.edit().putString("nwdBootId", bootId).apply()
    }
}
