package com.abdllh.aura.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.abdllh.aura.util.Prefs
import java.io.File
import java.util.concurrent.Executors

/**
 * The Wi-Fi on as soon as the car is started (Settings → Vehicle & system, on by default).
 *
 * The firmware's sleep at ignition off (NwdManagerService.handleAutoSleep) turns airplane mode on, and its wake-up
 * (handleAutoWakeup) turns it off again. A unit that powered off meanwhile ("power off when the car is switched off",
 * or the hours after which a sleeping unit powers off) starts cold with airplane mode still on: nothing at boot turns
 * it off (the service's initSettings does not), so the Wi-Fi stayed off until the owner turned it on by hand.
 * Here, once each boot has settled and after every wake-up: airplane mode off (the way the firmware's wake-up does it),
 * then the Wi-Fi on when it still is not. What was done goes into the Wi-Fi log (Settings → Vehicle & system).
 */
object WifiOnStart {
    private const val TAG = "AuraWifiOn"
    private const val KEY = "wifiOnStart"
    private const val KEY_BOOT = "wifiOnStartBoot"     // the boot already seen to (its boot id)
    private const val AFTER_BOOT_MS = 6_000L            // after sys.boot_completed: the Wi-Fi service is up by then
    private const val AFTER_WAKE_MS = 12_000L           // after a wake-up: the firmware's own airplane-mode-off goes first
    private const val ACTION_NWD_WAKE = "com.nwd.ACTION_AUTO_SLEEP_WAKE_STATE"

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var app: Context? = null

    /** The switch (on by default). */
    var enabled: Boolean
        get() = Prefs.raw.getBoolean(KEY, true)
        set(v) { Prefs.raw.edit().putBoolean(KEY, v).apply() }

    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        try {
            c.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(ACTION_NWD_WAKE)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "receiver: $t")
        }
        // once per boot: AMRI's process may start again later in the same boot (an update, a crash), and the owner may
        // have switched the Wi-Fi off on purpose by then
        val boot = bootId()
        if (boot.isEmpty() || Prefs.raw.getString(KEY_BOOT, "") != boot) {
            Prefs.raw.edit().putString(KEY_BOOT, boot).apply()
            afterBoot { ensure("boot") }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // the screen on again, or the firmware's "device ready" after its wake-up: the car was started
            main.removeCallbacks(onWake)
            main.postDelayed(onWake, AFTER_WAKE_MS)
        }
    }

    private val onWake = Runnable { ensure("wake") }

    private fun afterBoot(then: () -> Unit) {
        val poll = object : Runnable {
            var tries = 0
            override fun run() {
                if (SystemProps.get("sys.boot_completed") == "1" || ++tries > 60) main.postDelayed(then, AFTER_BOOT_MS)
                else main.postDelayed(this, 2000L)
            }
        }
        main.post(poll)
    }

    /** Airplane mode off and the Wi-Fi on, unless the unit is going to sleep again or ZLink has the Wi-Fi. */
    fun ensure(why: String) {
        val c = app ?: return
        if (!enabled || WifiKeeper.paused) return
        // the screen off: the unit is going to sleep (the firmware's airplane mode belongs to that), not starting
        val awake = try { (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive } catch (_: Throwable) { true }
        if (!awake) return
        io.execute {
            val airplane = airplaneOn(c)
            val wifi = wifiOn(c)
            Log.i(TAG, "$why: airplane=$airplane wifi=$wifi")
            if (!airplane && wifi) return@execute
            if (airplane) airplaneOff(c)
            // leaving airplane mode brings the Wi-Fi back when it was on before; when it was not, it is switched on (also
            // possible in airplane mode, should that not have gone)
            var ok = airplane && waitFor(8_000L) { wifiOn(c) }
            if (!ok) { wifiEnable(c); ok = waitFor(10_000L) { wifiOn(c) } }
            Log.i(TAG, "$why: done ok=$ok airplane=${airplaneOn(c)} wifi=${wifiOn(c)}")
            main.post { WifiKeeper.record("wifion", if (!ok) "fail" else if (airplane) "airplane" else "wifi") }
        }
    }

    private fun airplaneOn(c: Context) = try {
        Settings.Global.getInt(c.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    } catch (_: Throwable) { false }

    private fun wifiOn(c: Context) = try {
        (c.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    } catch (_: Throwable) { true }

    /**
     * ConnectivityManager.setAirplaneMode where AMRI holds the permission for it (a system API: a unit whose ROM copy
     * of AMRI was granted it), else the unit's shell (root): `cmd connectivity airplane-mode disable`, which writes the
     * setting and tells every radio, as the firmware's wake-up does.
     */
    private fun airplaneOff(c: Context) {
        try {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            ConnectivityManager::class.java.getMethod("setAirplaneMode", Boolean::class.javaPrimitiveType).invoke(cm, false)
            return
        } catch (t: Throwable) {
            Log.i(TAG, "airplane mode through the API: ${t.cause ?: t}")
        }
        val r = LocalAdb.run("cmd connectivity airplane-mode disable")
        if (!r.ok) Log.w(TAG, "airplane mode through the shell: ${r.output}")
    }

    private fun wifiEnable(c: Context) {
        @Suppress("DEPRECATION")
        val ok = try { (c.getSystemService(Context.WIFI_SERVICE) as WifiManager).setWifiEnabled(true) } catch (_: Throwable) { false }
        if (ok) return
        // refused to an app targeting Android 10 unless it is a system app: the unit's shell
        val r = LocalAdb.run("svc wifi enable")
        if (!r.ok) Log.w(TAG, "wifi through the shell: ${r.output}")
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + ms
        while (true) {
            if (cond()) return true
            if (SystemClock.elapsedRealtime() > until) return false
            try { Thread.sleep(400L) } catch (_: InterruptedException) { return cond() }
        }
    }

    private fun bootId() = try { File("/proc/sys/kernel/random/boot_id").readText().trim() } catch (_: Throwable) { "" }
}
