package com.abdllh.aura.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Airplane mode off and the Wi-Fi on: the one step of [WifiOnStart], apart from it because the guard's process runs it
 * too when the main process did not get there. Holds nothing and needs nothing of the main process. Blocking (up to
 * about 18 s): call it off the main thread.
 */
object WifiStep {
    private const val TAG = "AuraWifiOn"

    /** [airplane]: airplane mode was on. [acted]: anything needed doing. [ok]: the Wi-Fi is on now. */
    class Outcome(val airplane: Boolean, val acted: Boolean, val ok: Boolean)

    fun run(c: Context, why: String): Outcome {
        val airplane = airplaneOn(c)
        val wifi = wifiOn(c)
        Log.i(TAG, "$why: airplane=$airplane wifi=$wifi")
        if (!airplane && wifi) return Outcome(false, false, true)
        if (airplane) airplaneOff(c)
        // leaving airplane mode brings the Wi-Fi back when it was on before; when it was not, it is switched on (also
        // possible in airplane mode, should that not have gone)
        var ok = airplane && waitFor(8_000L) { wifiOn(c) }
        if (!ok) { wifiEnable(c); ok = waitFor(10_000L) { wifiOn(c) } }
        Log.i(TAG, "$why: done ok=$ok airplane=${airplaneOn(c)} wifi=${wifiOn(c)}")
        return Outcome(airplane, true, ok)
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
}
