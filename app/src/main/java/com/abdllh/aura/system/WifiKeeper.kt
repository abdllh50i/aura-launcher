package com.abdllh.aura.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.util.Prefs
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Keeps the car's Wi-Fi internet working. A phone hotspot (or the unit's Wi-Fi chip) sometimes ends up "connected, no
 * internet" after a while. While a Wi-Fi network that had working internet is connected, it is probed every 30 s
 * (bound to that network); after about a minute without internet the connection is restarted — first a re-association
 * (new DHCP lease), then Wi-Fi off and on — with growing pauses, and only a few times in a row, so a hotspot that
 * really has no data is not hammered. Networks that never had internet (a dash cam's Wi-Fi...) are left alone, and so
 * is everything while ZLink (wireless CarPlay) has the Wi-Fi.
 */
object WifiKeeper {
    private const val TAG = "AuraWifi"
    private const val PROBE_EVERY_MS = 30_000L
    private const val FAILS_BEFORE_FIX = 2   // two failed probes in a row: about a minute without internet
    private const val MAX_FIXES = 6          // then wait until the network changes or the internet comes back
    private val PROBES = listOf(
        "http://connectivitycheck.gstatic.com/generate_204" to 204,
        "http://www.msftconnecttest.com/connecttest.txt" to 200
    )

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private var net: Network? = null
    private var hadInternet = false // on the current network
    private var fails = 0
    private var fixes = 0
    private var nextFixAt = 0L
    private var lastFixAt = -1_000_000L
    private var probing = false

    /** The user's switch (on by default). */
    var enabled: Boolean
        get() = Prefs.raw.getBoolean("wifiKeeper", true)
        set(v) { Prefs.raw.edit().putBoolean("wifiKeeper", v).apply() }

    /** Something else owns the Wi-Fi right now (ZLink in use): no probing, no restarts. */
    @Volatile var paused = false

    // Keeps the Wi-Fi chip out of power saving (the unit's AIC8800 driver enables it by default, a known cause of
    // "connected, no internet" stalls). An ordinary high-performance Wi-Fi lock: released when the option is off.
    private var perfLock: WifiManager.WifiLock? = null

    /** Applies the switch: the power-saving lock with it (the probing checks the switch itself). */
    fun apply(c: Context) {
        try {
            if (enabled) {
                val l = perfLock ?: (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "aura:wifi").apply { setReferenceCounted(false) }
                    .also { perfLock = it }
                if (!l.isHeld) l.acquire()
            } else {
                perfLock?.let { if (it.isHeld) it.release() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wifi lock: $t")
        }
    }

    /**
     * After ZLink (it turned the Wi-Fi client off or used the radio for the phone's link): Wi-Fi back on if it was on,
     * and a clean off/on if it does not reconnect by itself (clears an access point / P2P group left behind).
     */
    fun restoreAfterZLink(ctx: Context, wasOn: Boolean) {
        if (!wasOn) return
        val c = ctx.applicationContext
        val wm = c.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        main.postDelayed({
            @Suppress("DEPRECATION")
            val ok = try { wm.isWifiEnabled || wm.setWifiEnabled(true) } catch (_: Throwable) { false }
            if (!ok) io.execute { LocalAdb.run("svc wifi enable") }
            main.postDelayed({
                if (net == null) {
                    Log.i(TAG, "no Wi-Fi after ZLink: restarting it")
                    io.execute { LocalAdb.run("svc wifi disable; sleep 3; svc wifi enable") }
                }
            }, 25_000L)
        }, 4000L)
    }

    fun start(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        apply(c)
        try {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback, main)
        } catch (t: Throwable) {
            Log.w(TAG, "callback: $t")
            return
        }
        main.postDelayed(tick, PROBE_EVERY_MS)
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network == net) return
            net = network
            fails = 0
            // a reconnect this class caused keeps its history (it has to be able to try the next step)
            if (SystemClock.elapsedRealtime() - lastFixAt > 120_000L) { hadInternet = false; fixes = 0; nextFixAt = 0L }
        }

        override fun onLost(network: Network) {
            if (network == net) { net = null; fails = 0 }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            main.postDelayed(this, PROBE_EVERY_MS)
            val n = net ?: return
            if (!enabled || paused || probing) return
            probing = true
            io.execute {
                val ok = probe(n)
                main.post { probing = false; result(n, ok) }
            }
        }
    }

    /** Internet through this very network (not whatever the default route is). */
    private fun probe(n: Network): Boolean {
        for ((url, want) in PROBES) {
            try {
                val c = n.openConnection(URL(url)) as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.instanceFollowRedirects = false
                c.useCaches = false
                try {
                    if (c.responseCode == want) return true
                } finally {
                    c.disconnect()
                }
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun result(n: Network, ok: Boolean) {
        if (com.abdllh.aura.BuildConfig.DEBUG) Log.d(TAG, "probe $n ok=$ok had=$hadInternet fails=$fails fixes=$fixes")
        if (n != net) return
        if (ok) {
            hadInternet = true
            fails = 0
            fixes = 0
            return
        }
        if (!hadInternet || fixes >= MAX_FIXES) return
        if (++fails < FAILS_BEFORE_FIX || SystemClock.elapsedRealtime() < nextFixAt) return
        fails = 0
        fix()
    }

    /** Restarts the connection: odd attempts re-associate, even ones switch the Wi-Fi off and on again. */
    private fun fix() {
        val c = app ?: return
        fixes++
        lastFixAt = SystemClock.elapsedRealtime()
        nextFixAt = lastFixAt + (60_000L shl (fixes - 1).coerceAtMost(4)) // 1, 2, 4, 8, 16 min
        Log.i(TAG, "connected without internet: restarting Wi-Fi (attempt $fixes)")
        val wm = c.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        @Suppress("DEPRECATION")
        try {
            if (fixes % 2 == 1) {
                if (wm.disconnect()) { main.postDelayed({ try { wm.reconnect() } catch (_: Throwable) { } }, 1500); return }
            } else {
                if (wm.setWifiEnabled(false)) { main.postDelayed({ try { wm.setWifiEnabled(true) } catch (_: Throwable) { } }, 3000); return }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fix: $t")
        }
        // not allowed through the API (not a system build): the same through the unit's shell
        io.execute { LocalAdb.run("svc wifi disable; sleep 3; svc wifi enable") }
    }
}
