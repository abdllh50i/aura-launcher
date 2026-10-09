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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps the car's Wi-Fi internet working, and keeps a log of what happened to it (shown in Settings).
 *
 * The internet usually comes from the phone's hotspot. While a Wi-Fi network is connected it is probed every 20 s
 * (bound to that network). When the web does not answer twice in a row, a quick diagnosis says why:
 *  - LINK: the phone does not answer over Wi-Fi at all (a stalled Wi-Fi link): the connection is restarted;
 *  - DNS: the internet works but the phone stopped answering name lookups: the connection is restarted (new lease);
 *  - NO_DATA: the phone answers but its hotspot has no internet (weak coverage, data off or used up, or a stuck
 *    hotspot): after a minute one reconnect, in case the phone's side is stuck, then only waiting;
 *  - WEB: names and addresses work but websites do not (a sign-in page, a blocked network): logged only.
 * Restarts alternate a re-association and Wi-Fi off/on, with growing pauses and only a few in a row. Networks that
 * never had internet (a dash cam's Wi-Fi...) are only logged, and nothing is probed while ZLink has the Wi-Fi.
 * When the web works again but Android still flags the network "no internet", Android is told to look again at once.
 */
object WifiKeeper {
    private const val TAG = "AuraWifi"
    private const val PROBE_EVERY_MS = 20_000L
    private const val RECHECK_MS = 5_000L        // a failed probe is confirmed soon after
    private const val FAILS_BEFORE_ACTING = 2
    private const val MAX_FIXES = 6              // then wait until the network changes or the internet comes back
    private const val NO_DATA_GRACE_MS = 60_000L
    private const val QUIET_AFTER_FIX_MS = 90_000L // the disconnect/reconnect a restart causes is not logged
    private const val LOG_MAX = 80
    /** A plain-HTTP check page: the answer must be [code] (and start with [body] when set: a sign-in page says 200 too). */
    private class Probe(val host: String, val path: String, val code: Int, val body: String? = null)
    private val PROBES = listOf(
        Probe("connectivitycheck.gstatic.com", "/generate_204", 204),
        Probe("www.msftconnecttest.com", "/connecttest.txt", 200, "Microsoft Connect Test")
    )
    private val PUBLIC_IPS = listOf("8.8.8.8", "1.1.1.1")

    enum class Cause { LINK, NO_DATA, DNS, WEB }
    enum class State { OFF, NO_WIFI, CHECKING, ONLINE, OFFLINE, PAUSED }

    /** A log line: [type] is start / join / leave / online / back / lost / fix, with up to two values. */
    class Event(val at: Long, val type: String, val a: String = "", val b: String = "")

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pool = Executors.newCachedThreadPool() // the probes of one check, side by side
    private var app: Context? = null
    private var net: Network? = null
    private var hadInternet = false // on the current network
    private var fails = 0
    private var firstFailAt = 0L
    private var downSince = 0L      // a confirmed outage started (elapsed realtime), 0 = none
    private var fixes = 0
    private var nextFixAt = 0L
    private var lastFixAt = -1_000_000L
    private var fixSsid = ""        // the network a restart was for (its reconnect keeps the history)
    private var probing = false
    private val events = ArrayList<Event>()

    @Volatile var state = State.CHECKING; private set
    @Volatile var cause: Cause? = null; private set
    @Volatile var ssid = ""; private set
    @Volatile var rssi = 0; private set

    /** How long the internet has been down, in seconds (0 while it works). Main thread. */
    fun downSeconds(): Long = if (downSince > 0) (SystemClock.elapsedRealtime() - downSince) / 1000 else 0L

    /** Called on the main thread when the state or the log changes (the settings page, while it is showing). */
    var listener: (() -> Unit)? = null

    /** The user's switch (on by default). */
    var enabled: Boolean
        get() = Prefs.raw.getBoolean("wifiKeeper", true)
        set(v) { Prefs.raw.edit().putBoolean("wifiKeeper", v).apply() }

    /** Something else owns the Wi-Fi right now (ZLink in use): no probing, no restarts. */
    @Volatile var paused = false

    // Keeps the Wi-Fi chip out of power saving (the unit's AIC8800 driver enables it by default, a known cause of
    // "connected, no internet" stalls). An ordinary high-performance Wi-Fi lock: released when the option is off.
    private var perfLock: WifiManager.WifiLock? = null

    fun log(): List<Event> = synchronized(events) { ArrayList(events) }

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
        checkNow()
    }

    /** A probe right away (the settings page's button, the switch). */
    fun checkNow() {
        if (app == null) return
        main.removeCallbacks(tick)
        main.post(tick)
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
        io.execute { load(c) } // goes before the history saved by add(): the io thread runs in order
        add(Event(System.currentTimeMillis(), "start"))
        apply(c)
        try {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback, main)
        } catch (t: Throwable) {
            Log.w(TAG, "callback: $t")
            return
        }
        schedule(PROBE_EVERY_MS)
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network == net) return
            net = network
            fails = 0
            state = State.CHECKING
            readWifi()
            // The reconnect a restart of this class caused (the same network, right after it) keeps its history: the
            // outage goes on (and ends with "back after ..."), and the next step can be tried.
            if (!ours()) {
                hadInternet = false; fixes = 0; nextFixAt = 0L
                downSince = 0L; cause = null
                add(Event(System.currentTimeMillis(), "join", ssid, rssi.toString()))
            } else {
                changed()
            }
            schedule(3000L) // know quickly whether this one has internet
        }

        override fun onLost(network: Network) {
            if (network != net) return
            net = null
            fails = 0
            state = State.NO_WIFI
            if (SystemClock.elapsedRealtime() - lastFixAt >= QUIET_AFTER_FIX_MS) {
                downSince = 0L; cause = null
                add(Event(System.currentTimeMillis(), "leave"))
            } else {
                changed()
            }
        }
    }

    private fun ours() = SystemClock.elapsedRealtime() - lastFixAt < QUIET_AFTER_FIX_MS && ssid == fixSsid

    private fun schedule(delay: Long) {
        main.removeCallbacks(tick)
        main.postDelayed(tick, delay)
    }

    private val tick = Runnable {
        val n = net
        when {
            n == null -> { setState(State.NO_WIFI); schedule(PROBE_EVERY_MS) }
            !enabled -> { setState(State.OFF); schedule(PROBE_EVERY_MS) }
            paused -> { setState(State.PAUSED); schedule(PROBE_EVERY_MS) }
            probing -> Unit // its result schedules the next one
            else -> {
                probing = true
                io.execute {
                    val r = try { check(n) } catch (t: Throwable) { Log.w(TAG, "check: $t"); Check(false, Cause.LINK) }
                    main.post { probing = false; result(n, r) }
                }
            }
        }
    }

    private class Check(val web: Boolean, val cause: Cause?)

    /**
     * The web through this very network (not whatever the default route is); when it fails, why. Everything runs side
     * by side with deadlines, so a dead network is diagnosed in about 15 s.
     */
    private fun check(n: Network): Check {
        if (race(PROBES.map { p -> { http(n, p) } }, 10_000L)) return Check(true, null)
        val cm = app?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val lp = try { cm?.getLinkProperties(n) } catch (_: Throwable) { null }
        val servers = lp?.dnsServers.orEmpty().take(2)
        // the phone's IPv4 address (an IPv6 router address is link-local: no use for a plain connect)
        val gw = lp?.routes.orEmpty().filter { it.isDefaultRoute && it.hasGateway() }
            .sortedBy { if (it.gateway is Inet4Address) 0 else 1 }.firstOrNull()?.gateway
        val dns = pool.submit<Int> { servers.map { dnsQuery(n, it) }.maxOrNull() ?: -1 }
        val ip = pool.submit<Boolean> {
            race(PUBLIC_IPS.map { addr -> { connect(n, InetAddress.getByName(addr), 443, 2500) == true } }, 3000L)
        }
        // The phone answering at all (even "nothing here") means the Wi-Fi link itself works. ping cannot be tied to a
        // network (SO_BINDTODEVICE is refused to apps): only when this network is the one everything goes through.
        val onlyPath = try { cm?.activeNetwork == n } catch (_: Throwable) { false }
        val phone = pool.submit<Boolean> { gw != null && (connect(n, gw, 53, 1500) != false || (onlyPath && ping(gw))) }
        val dnsR = try { dns.get(6, TimeUnit.SECONDS) } catch (_: Throwable) { -1 }
        val ipOk = try { ip.get(6, TimeUnit.SECONDS) } catch (_: Throwable) { false }
        val phoneOk = try { phone.get(6, TimeUnit.SECONDS) } catch (_: Throwable) { false }
        if (com.abdllh.aura.BuildConfig.DEBUG) Log.d(TAG, "diagnosis: dns=$dnsR ip=$ipOk phone=$phoneOk gw=$gw servers=$servers")
        return Check(false, when {
            ipOk -> if (dnsR == 1) Cause.WEB else Cause.DNS
            dnsR >= 0 || phoneOk -> Cause.NO_DATA
            else -> Cause.LINK
        })
    }

    /** Runs [tasks] side by side: true as soon as one says true, false when all said false or time is up. */
    private fun race(tasks: List<() -> Boolean>, deadlineMs: Long): Boolean {
        val cs = ExecutorCompletionService<Boolean>(pool)
        val futures = tasks.map { t -> cs.submit { t() } }
        val until = SystemClock.elapsedRealtime() + deadlineMs
        try {
            repeat(futures.size) {
                val left = until - SystemClock.elapsedRealtime()
                if (left <= 0) return false
                val f = cs.poll(left, TimeUnit.MILLISECONDS) ?: return false
                if (try { f.get() } catch (_: Throwable) { false }) return true
            }
            return false
        } finally {
            for (f in futures) f.cancel(true)
        }
    }

    /**
     * One plain HTTP request over a raw socket of this network. Not HttpURLConnection: the release build forbids
     * cleartext HTTP (usesCleartextTraffic=false), which would fail every probe; raw sockets are not subject to it.
     */
    private fun http(n: Network, p: Probe): Boolean {
        return try {
            val all = n.getAllByName(p.host) // through this network's own DNS
            val addr = all.firstOrNull { it is Inet4Address } ?: all.firstOrNull() ?: return false
            n.socketFactory.createSocket().use { s ->
                s.connect(InetSocketAddress(addr, 80), 5000)
                s.soTimeout = 5000
                val out = s.getOutputStream()
                out.write("GET ${p.path} HTTP/1.1\r\nHost: ${p.host}\r\nUser-Agent: Aura\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val code = r.readLine()?.split(' ')?.getOrNull(1)?.toIntOrNull()
                if (code != p.code) return false
                val body = p.body ?: return true
                while (true) {
                    val line = r.readLine() ?: return false
                    if (line.isEmpty()) break
                }
                val buf = CharArray(256)
                val k = r.read(buf)
                k > 0 && String(buf, 0, k).contains(body)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** true = connected, null = the host answered but refused (it is there), false = no answer. */
    private fun connect(n: Network, to: InetAddress, port: Int, timeout: Int): Boolean? = try {
        n.socketFactory.createSocket().use { it.connect(InetSocketAddress(to, port), timeout) }
        true
    } catch (e: ConnectException) {
        if (e.message?.contains("refused", ignoreCase = true) == true) null else false
    } catch (_: Throwable) {
        false
    }

    private fun ping(to: InetAddress): Boolean = try {
        val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-w", "2", to.hostAddress ?: "").redirectErrorStream(true).start()
        val done = p.waitFor(3, TimeUnit.SECONDS)
        if (!done) p.destroy()
        done && p.exitValue() == 0
    } catch (_: Throwable) {
        false
    }

    /** A plain DNS question for the probe's host: -1 no reply, 0 a reply without an address, 1 resolved. */
    private fun dnsQuery(n: Network, server: InetAddress): Int {
        val id = (SystemClock.elapsedRealtimeNanos() and 0xFFFF).toInt()
        val q = ByteArrayOutputStream()
        q.write(id shr 8); q.write(id and 0xFF)
        q.write(0x01); q.write(0x00)          // recursion desired
        q.write(0); q.write(1)                // one question
        repeat(6) { q.write(0) }
        for (part in "connectivitycheck.gstatic.com".split('.')) { q.write(part.length); q.write(part.toByteArray()) }
        q.write(0)
        q.write(0); q.write(1)                // A
        q.write(0); q.write(1)                // IN
        val out = q.toByteArray()
        return try {
            DatagramSocket().use { s ->
                n.bindSocket(s)
                s.soTimeout = 2000
                s.send(DatagramPacket(out, out.size, server, 53))
                val buf = ByteArray(512)
                val p = DatagramPacket(buf, buf.size)
                val until = SystemClock.elapsedRealtime() + 2000
                while (true) {
                    s.receive(p)
                    val rid = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                    if (p.length >= 12 && rid == id) break
                    if (SystemClock.elapsedRealtime() > until) return -1
                }
                val rcode = buf[3].toInt() and 0x0F
                val answers = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
                if (rcode == 0 && answers > 0) 1 else 0
            }
        } catch (_: Throwable) {
            -1
        }
    }

    private fun result(n: Network, r: Check) {
        if (com.abdllh.aura.BuildConfig.DEBUG) Log.d(TAG, "probe $n web=${r.web} cause=${r.cause} had=$hadInternet fails=$fails fixes=$fixes")
        // a network that came meanwhile gets its own first look soon (its quick check found this one running)
        if (n != net) { schedule(if (net != null) 1000L else PROBE_EVERY_MS); return }
        // switched off, or ZLink took the Wi-Fi, while this check ran: nothing more to do with it
        if (!enabled || paused) { schedule(PROBE_EVERY_MS); return }
        readWifi()
        val now = SystemClock.elapsedRealtime()
        if (r.web) {
            val wasDown = downSince
            fails = 0; fixes = 0; downSince = 0L; cause = null
            state = State.ONLINE
            when {
                wasDown > 0 -> add(Event(System.currentTimeMillis(), "back", ((now - wasDown) / 1000).toString()))
                !hadInternet -> add(Event(System.currentTimeMillis(), "online", ssid))
                else -> changed()
            }
            hadInternet = true
            revalidate(n)
            schedule(PROBE_EVERY_MS)
            return
        }
        if (fails++ == 0) firstFailAt = now
        if (fails < FAILS_BEFORE_ACTING) { schedule(RECHECK_MS); return }
        if (downSince == 0L) downSince = firstFailAt
        state = State.OFFLINE
        if (r.cause != cause) {
            cause = r.cause
            add(Event(System.currentTimeMillis(), "lost", r.cause?.name ?: "", rssi.toString()))
        } else {
            changed()
        }
        when (r.cause) {
            Cause.LINK, Cause.DNS -> maybeFix(now)
            // the phone itself has no data: one reconnect after a minute (in case its hotspot is stuck), then waiting
            Cause.NO_DATA -> if (fixes == 0 && now - downSince >= NO_DATA_GRACE_MS) maybeFix(now)
            else -> Unit
        }
        schedule(PROBE_EVERY_MS)
    }

    /** The web works again: if Android still flags the network "no internet", it re-checks now instead of minutes later. */
    private fun revalidate(n: Network) {
        val cm = app?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            val caps = cm.getNetworkCapabilities(n) ?: return
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) cm.reportNetworkConnectivity(n, true)
        } catch (t: Throwable) {
            Log.w(TAG, "revalidate: $t")
        }
    }

    private fun maybeFix(now: Long) {
        if (!hadInternet || fixes >= MAX_FIXES || now < nextFixAt) return
        fix()
    }

    /** Restarts the connection: odd attempts re-associate, even ones switch the Wi-Fi off and on again. */
    private fun fix() {
        val c = app ?: return
        fixes++
        lastFixAt = SystemClock.elapsedRealtime()
        fixSsid = ssid
        nextFixAt = lastFixAt + (60_000L shl (fixes - 1).coerceAtMost(4)) // 1, 2, 4, 8, 16 min
        val reassociate = fixes % 2 == 1
        Log.i(TAG, "connected without internet ($cause): restarting Wi-Fi (attempt $fixes)")
        fun logged(how: String) = add(Event(System.currentTimeMillis(), "fix", fixes.toString(), how))
        val wm = c.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        try {
            if (wm != null && reassociate) {
                if (wm.disconnect()) {
                    logged("reconnect")
                    main.postDelayed({ try { wm.reconnect() } catch (_: Throwable) { } }, 1500)
                    return
                }
            } else if (wm != null) {
                if (wm.setWifiEnabled(false)) {
                    logged("restart")
                    main.postDelayed({ try { wm.setWifiEnabled(true) } catch (_: Throwable) { } }, 3000)
                    return
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fix: $t")
        }
        // not allowed through the API (an app targeting Android 10): Wi-Fi off and on through the unit's shell
        logged("restart")
        io.execute {
            val r = LocalAdb.run("svc wifi disable; sleep 3; svc wifi enable")
            if (!r.ok) {
                Log.w(TAG, "restart through adb failed: ${r.output}")
                main.post { add(Event(System.currentTimeMillis(), "fixfail", r.output.take(80))) }
            }
        }
    }

    private fun readWifi() {
        try {
            val info = (app?.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo ?: return
            @Suppress("DEPRECATION")
            val s = info.ssid?.removeSurrounding("\"").orEmpty()
            if (s.isNotEmpty() && s != "<unknown ssid>") ssid = s
            if (info.rssi > -127 && info.rssi < 0) rssi = info.rssi
        } catch (_: Throwable) {
        }
    }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        changed()
    }

    private fun changed() {
        listener?.invoke()
    }

    // ------------------------------------------------------------------------------------------------------ the log
    private fun add(e: Event) {
        synchronized(events) {
            events.add(e)
            while (events.size > LOG_MAX) events.removeAt(0)
        }
        val c = app
        if (c != null) io.execute { save(c, log()) } // the list as it is then (with the loaded history in it)
        changed()
    }

    private fun file(c: Context) = File(c.filesDir, "wifi-log.txt")

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private fun save(c: Context, list: List<Event>) {
        try {
            val f = file(c)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(list.joinToString("\n") { "${it.at}\t${it.type}\t${clean(it.a)}\t${clean(it.b)}" })
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (t: Throwable) {
            Log.w(TAG, "save log: $t")
        }
    }

    private fun load(c: Context) {
        try {
            val f = file(c)
            if (!f.exists()) return
            val loaded = f.readLines().mapNotNull { line ->
                val p = line.split('\t')
                val at = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                Event(at, p.getOrNull(1) ?: return@mapNotNull null, p.getOrNull(2).orEmpty(), p.getOrNull(3).orEmpty())
            }
            synchronized(events) {
                events.addAll(0, loaded.takeLast(LOG_MAX))
                while (events.size > LOG_MAX) events.removeAt(0)
            }
            main.post { changed() }
        } catch (t: Throwable) {
            Log.w(TAG, "load log: $t")
        }
    }
}
