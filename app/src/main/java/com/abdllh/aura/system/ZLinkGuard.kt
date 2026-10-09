package com.abdllh.aura.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.abdllh.aura.util.Prefs
import java.io.File

/**
 * ZLink (wireless CarPlay / Android Auto / HiCar) stays off until the user opens it.
 *
 * On this firmware it is not ZLink itself that jumps up when a phone connects over Bluetooth: the native CarPlay
 * daemon (/system/bin/z-link, run while sys.nwd.support.carplay=true) wakes ZLink's service, which then takes the
 * screen and the Wi-Fi radio (it turns the Wi-Fi client off to host the phone's link). The firmware's own switch for
 * all of it is Settings.System "phone_connect_style" — the car settings' "Phone link": 0 none, 1 HiCar,
 * 2 EasyConnect, 3 CarPlay. With 0 the NWD setting service disables the ZLink app and stops the daemon, at once and
 * again on every boot. Aura keeps it at 0; opening ZLink from Aura puts the original value back first (the firmware
 * enables ZLink again), and once the user is back on the home screen with no phone link running — or the car is
 * switched off, or the unit restarts — it goes back to 0 and the Wi-Fi client is brought back.
 * Switching the option off restores the original setting (stock behaviour).
 */
object ZLinkGuard {
    private const val TAG = "AuraZLink"
    const val PKG = "com.zjinnova.zlink"
    private const val STYLE = "phone_connect_style"
    private const val RECHECK = "recheck_phone_connect_style"
    private const val NONE = 0
    private const val CARPLAY = 3
    private const val PREF_ON = "zlinkGuard"
    private const val PREF_ORIG = "zlinkOrigStyle"  // the value before Aura changed it (absent: never changed)
    private const val PREF_SESSION = "zlinkSession" // boot id of a session the user opened, "" = none
    private const val PREF_WIFI = "zlinkWifiWasOn"
    private const val HOME_GRACE_MS = 120_000L

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null

    /** The user's switch (on by default: the owner asked for ZLink to stay off). */
    var enabled: Boolean
        get() = Prefs.raw.getBoolean(PREF_ON, true)
        set(v) { Prefs.raw.edit().putBoolean(PREF_ON, v).apply() }

    /** The user opened ZLink and is (or may be) using it: it has the Wi-Fi radio. */
    val inSession: Boolean get() = Prefs.raw.getString(PREF_SESSION, "").orEmpty().isNotEmpty()

    fun available(c: Context) = Device.isNwd && installed(c)

    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        if (!available(c)) return
        // a session from before a restart has ended with it
        val s = Prefs.raw.getString(PREF_SESSION, "").orEmpty()
        if (s.isNotEmpty() && s != bootId()) Prefs.raw.edit().putString(PREF_SESSION, "").apply()
        WifiKeeper.paused = inSession
        try {
            c.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c2: Context, i: Intent) { if (inSession) endSession(c) } // car switched off
            }, IntentFilter("com.nwd.action.ACTION_MCU_POWER_OFF"))
        } catch (t: Throwable) {
            Log.w(TAG, "receiver: $t")
        }
        apply(c)
    }

    /** Brings the firmware setting in line with the switch. */
    fun apply(c: Context) {
        if (!available(c)) return
        if (enabled) { if (!inSession) setOff(c) } else restore(c)
    }

    /** Opens ZLink for the user (after switching the phone link back on when it is off). */
    fun open(ctx: Context): Boolean {
        val c = ctx.applicationContext
        if (!available(c) || !enabled) return launch(ctx)
        startSession(c)
        if (launchIntent(c) != null) return launch(ctx)
        val orig = Prefs.raw.getInt(PREF_ORIG, CARPLAY)
        put(c, STYLE, if (orig > 0) orig else CARPLAY) // the firmware enables ZLink again
        var waited = 0
        val poll = object : Runnable {
            override fun run() {
                if (launchIntent(c) != null) { launch(ctx); return }
                waited += 250
                if (waited < 8000) { main.postDelayed(this, 250); return }
                Log.w(TAG, "ZLink did not come back")
                try { android.widget.Toast.makeText(c, com.abdllh.aura.R.string.zlink_failed, android.widget.Toast.LENGTH_LONG).show() } catch (_: Throwable) { }
            }
        }
        main.postDelayed(poll, 250)
        return true
    }

    /** Aura's home screen is in front; the session ends after a while there unless a phone link is running. */
    fun onHomeShown() {
        if (!inSession) return
        main.removeCallbacks(homeCheck)
        main.postDelayed(homeCheck, HOME_GRACE_MS)
    }

    fun onHomeHidden() = main.removeCallbacks(homeCheck)

    private val homeCheck: Runnable = Runnable {
        val c = app ?: return@Runnable
        if (!inSession) return@Runnable
        if (linkActive()) { main.postDelayed(homeCheck, HOME_GRACE_MS); return@Runnable }
        endSession(c)
    }

    /** CarPlay / Android Auto connected (the firmware's own marker; "none" or empty otherwise). */
    private fun linkActive(): Boolean {
        val t = SystemProps.get("zj.phonelink.type").trim().lowercase()
        return t.isNotEmpty() && t != "none" && t != "0"
    }

    private fun startSession(c: Context) {
        val wifiOn = try { (c.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled } catch (_: Throwable) { true }
        if (!inSession) Prefs.raw.edit().putBoolean(PREF_WIFI, wifiOn).apply()
        Prefs.raw.edit().putString(PREF_SESSION, bootId().ifEmpty { "on" }).apply()
        WifiKeeper.paused = true
    }

    private fun endSession(c: Context) {
        main.removeCallbacks(homeCheck)
        Prefs.raw.edit().putString(PREF_SESSION, "").apply()
        if (enabled) setOff(c)
        WifiKeeper.paused = false
        WifiKeeper.restoreAfterZLink(c, Prefs.raw.getBoolean(PREF_WIFI, true))
    }

    private fun setOff(c: Context) {
        val cur = get(c, STYLE)
        if (cur == NONE) return
        if (!Prefs.raw.contains(PREF_ORIG)) Prefs.raw.edit().putInt(PREF_ORIG, cur).apply()
        put(c, RECHECK, 0) // the firmware must not work the value out again at boot
        put(c, STYLE, NONE)
        Log.i(TAG, "phone link off (was $cur)")
    }

    private fun restore(c: Context) {
        if (!Prefs.raw.contains(PREF_ORIG)) return // never changed
        val orig = Prefs.raw.getInt(PREF_ORIG, CARPLAY)
        if (orig >= 0) put(c, STYLE, orig) else { put(c, STYLE, CARPLAY); put(c, RECHECK, 1) } // -1: firmware's own pick
        Prefs.raw.edit().remove(PREF_ORIG).apply()
        Log.i(TAG, "phone link restored to $orig")
    }

    private fun get(c: Context, key: String): Int = try { Settings.System.getInt(c.contentResolver, key, -1) } catch (_: Throwable) { -1 }

    private fun put(c: Context, key: String, v: Int) {
        try { Settings.System.putInt(c.contentResolver, key, v) } catch (t: Throwable) { Log.w(TAG, "$key=$v: $t") }
    }

    private fun installed(c: Context) = try { c.packageManager.getApplicationInfo(PKG, PackageManager.MATCH_DISABLED_COMPONENTS); true } catch (_: Throwable) { false }

    private fun launchIntent(c: Context): Intent? = try { c.packageManager.getLaunchIntentForPackage(PKG) } catch (_: Throwable) { null }

    private fun launch(ctx: Context): Boolean = try {
        val i = launchIntent(ctx) ?: throw IllegalStateException("no launcher activity")
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        true
    } catch (_: Throwable) {
        false
    }

    private fun bootId(): String = try { File("/proc/sys/kernel/random/boot_id").readText().trim() } catch (_: Throwable) { "" }
}
