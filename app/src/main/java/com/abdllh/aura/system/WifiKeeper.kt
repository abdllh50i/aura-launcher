package com.abdllh.aura.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
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
import java.net.Inet6Address
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
 *  - NO_DATA: the phone answers but no internet comes through it: the connection is restarted as well. It can be the
 *    phone's coverage, but an iPhone's Personal Hotspot also keeps a client connected (its DNS answering) while it
 *    stops passing that client's traffic, until the client reconnects: what the owner had to do by hand;
 *  - V6: the web works over IPv4 but not over the IPv6 the phone hands out as well (after it moved to another cell,
 *    say). Apps try IPv6 first and wait on it, so to the owner the internet is gone while an IPv4-only check passes:
 *    the connection is restarted, the same remedy the owner used by hand;
 *  - WEB: names and addresses work but websites do not (a sign-in page, a blocked network): logged only.
 * Besides, a heartbeat to the phone every 1.5 s (a connect to its DNS port) keeps the link busy and catches a link
 * that froze (the phone silent with a strong signal: about once a minute in the owner's car) within ~5 s: re-associate.
 * Restarts alternate a re-association and Wi-Fi off/on, with growing pauses and only a few in a row. A drop also
 * logs a root snapshot (address, gateway and internet pings, IPv6) so the next log says where the traffic stops.
 * Networks that never had internet (a dash cam's Wi-Fi...) are only logged, and nothing is probed while ZLink has the
 * Wi-Fi.
 * When the web works again but Android still flags the network "no internet", Android is told to look again at once.
 */
object WifiKeeper {
    private const val TAG = "AuraWifi"
    private const val PROBE_EVERY_MS = 20_000L
    private const val RECHECK_MS = 5_000L        // a failed probe is confirmed soon after
    private const val FAILS_BEFORE_ACTING = 2
    private const val MAX_FIXES = 6              // then wait until the network changes or the internet comes back
    private const val QUIET_AFTER_FIX_MS = 90_000L // the disconnect/reconnect a restart causes is not logged
    private const val LOG_MAX = 80
    // The heartbeat to the phone (a TCP connect to its DNS port: answered at once, even refused, while the link
    // works). The owner's log showed the link to the iPhone freezing about once a minute (the phone itself silent,
    // a strong signal) and a reconnect curing it each time: the heartbeat keeps the link busy, and notices a freeze in
    // seconds instead of the 20 s probes (and their pauses).
    private const val BEAT_MS = 1_500L
    private const val BEAT_TIMEOUT_MS = 1_200
    private const val BEAT_MISSES = 3              // ~5 s of silence: frozen
    private const val BEAT_MIN_GAP_MS = 15_000L    // a reconnect from the heartbeat at most this often
    /** A plain-HTTP check page: the answer must be [code] (and start with [body] when set: a sign-in page says 200 too). */
    private class Probe(val host: String, val path: String, val code: Int, val body: String? = null)
    private val PROBES = listOf(
        Probe("connectivitycheck.gstatic.com", "/generate_204", 204),
        Probe("www.msftconnecttest.com", "/connecttest.txt", 200, "Microsoft Connect Test")
    )
    private val PUBLIC_IPS = listOf("8.8.8.8", "1.1.1.1")

    enum class Cause { LINK, NO_DATA, DNS, V6, WEB }
    enum class State { OFF, NO_WIFI, CHECKING, ONLINE, OFFLINE, PAUSED }

    /** A log line: [type] is start / join / leave / online / back / lost / fix, with up to two values. */
    class Event(val at: Long, val type: String, val a: String = "", val b: String = "")

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pool = Executors.newCachedThreadPool() // the probes of one check, side by side
    private var app: Context? = null
    @Volatile private var net: Network? = null
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

    // Asks Android to keep the Wi-Fi chip out of power saving. The unit's AIC8800 driver ignores that request (its
    // power-management handler does nothing and power saving stays on), so on the K2501 this lock changes nothing;
    // kept for units whose driver honours it. An ordinary high-performance Wi-Fi lock: released when the option is off.
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
        Thread(::heartbeat, "aura-wifi-beat").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * Every [BEAT_MS], while a Wi-Fi network is up: is the phone (the gateway) answering? It arms only once the phone
     * has answered it on this network (a router that ignores it never gets reconnected by it), and [BEAT_MISSES]
     * silences in a row are a frozen link: [frozen].
     */
    private fun heartbeat() {
        var on: Network? = null
        var candidates: List<InetAddress> = emptyList()
        var to: InetAddress? = null // the one that answered: armed
        var misses = 0
        while (true) {
            try { Thread.sleep(BEAT_MS) } catch (_: InterruptedException) { return }
            val n = net
            if (n == null || !enabled || paused) { on = null; misses = 0; continue }
            if (n != on) { // a new network: the phone's addresses (gateway, DNS: one on a hotspot), not armed yet
                on = n; to = null; misses = 0
                candidates = try {
                    val lp = (app?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.getLinkProperties(n)
                    // only what is on this very link: an outside DNS server (8.8.8.8) would make it an internet check
                    (lp?.routes.orEmpty().filter { it.isDefaultRoute && it.hasGateway() }.mapNotNull { it.gateway }
                        .filterIsInstance<Inet4Address>() + lp?.dnsServers.orEmpty().filterIsInstance<Inet4Address>())
                        .distinct().filter { lp != null && onLink(lp, it) }
                } catch (_: Throwable) { emptyList() }
            }
            val armed = to
            if (armed == null) {
                // arm on the first that answers (a gateway may ignore it while the DNS server answers, or the other way)
                to = candidates.firstOrNull { connect(n, it, 53, BEAT_TIMEOUT_MS) != false }
                continue
            }
            if (connect(n, armed, 53, BEAT_TIMEOUT_MS) != false) { misses = 0; continue }
            if (++misses >= BEAT_MISSES) {
                misses = 0
                main.post { frozen(n) }
            }
        }
    }

    /** [a] is on one of this network's own IPv4 subnets. */
    private fun onLink(lp: LinkProperties, a: Inet4Address): Boolean = lp.linkAddresses.any { la ->
        val mine = la.address as? Inet4Address ?: return@any false
        val bits = la.prefixLength.coerceIn(0, 32)
        val mask = if (bits == 0) 0 else -1 shl (32 - bits)
        fun int(x: Inet4Address) = x.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        (int(mine) and mask) == (int(a) and mask)
    }

    private val beatFixes = ArrayDeque<Long>() // the heartbeat's reconnects (main thread)

    /**
     * The phone went silent on a link that worked: reconnect now (what cured it in the owner's log every time). A
     * re-association first; when the one before did not hold two minutes, Wi-Fi off and on. At most six in ten
     * minutes: beyond that the 20 s probes and their growing pauses decide.
     */
    private fun frozen(n: Network) {
        if (n != net || !enabled || paused || !hadInternet) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFixAt < BEAT_MIN_GAP_MS) return
        while (beatFixes.isNotEmpty() && now - beatFixes.first() > 10 * 60_000L) beatFixes.removeFirst()
        if (beatFixes.size >= 6 || fixes >= MAX_FIXES) return
        if (downSince == 0L) downSince = now - BEAT_MS * BEAT_MISSES
        state = State.OFFLINE
        if (cause != Cause.LINK) {
            cause = Cause.LINK
            add(Event(System.currentTimeMillis(), "lost", Cause.LINK.name, rssi.toString()))
        }
        // fix() re-associates on odd attempts and switches the Wi-Fi off and on on even ones
        val lastHeld = beatFixes.isEmpty() || now - beatFixes.last() >= 2 * 60_000L
        if (lastHeld) { if (fixes % 2 == 1) fixes++ } else { if (fixes % 2 == 0) fixes++ }
        beatFixes.addLast(now)
        fix()
        schedule(4_000L) // "back after ..." as soon as it is
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
        val cm = app?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val lp = try { cm?.getLinkProperties(n) } catch (_: Throwable) { null }
        if (race(PROBES.map { p -> { http(n, p) == true } }, 10_000L)) {
            // the IPv6 the phone hands out as well, when it does: a page that has an IPv6 address must answer over it
            // (a page without one says nothing either way)
            if (lp == null || !hasGlobalV6(lp)) return Check(true, null)
            return if (v6Works(n)) Check(true, null) else Check(false, Cause.V6)
        }
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
     * The probe pages over IPv6, all of them (not the first answer: a page without an IPv6 address says nothing, and
     * one of the two has none): fine when one answered over IPv6, or when none has an IPv6 address at all.
     */
    private fun v6Works(n: Network): Boolean {
        val futures = PROBES.map { p -> pool.submit<Boolean?> { http(n, p, v6 = true) } }
        val until = SystemClock.elapsedRealtime() + 10_000L
        val results = futures.map { f ->
            try { f.get((until - SystemClock.elapsedRealtime()).coerceAtLeast(1L), TimeUnit.MILLISECONDS) } catch (_: Throwable) { f.cancel(true); false }
        }
        return results.any { it == true } || results.all { it == null }
    }

    /** The network has a global IPv6 address and an IPv6 default route (apps will try IPv6 first). */
    // emulator test: its network has no global IPv6 (so no IPv6 addresses either): one is pretended, which the test
    // blocks (setprop debug.aura.v6test 1; ip6tables -I OUTPUT -p tcp --dport 80 -j DROP)
    private fun v6TestOn() = com.abdllh.aura.BuildConfig.DEBUG && SystemProps.get("debug.aura.v6test") == "1"
    private fun v6Test(): InetAddress? = if (v6TestOn()) InetAddress.getByName("2001:4860:4860::8888") else null

    private fun hasGlobalV6(lp: LinkProperties): Boolean {
        if (v6TestOn()) return true
        val addr = lp.linkAddresses.any {
            val a = it.address
            a is Inet6Address && !a.isLinkLocalAddress && !a.isSiteLocalAddress && !a.isLoopbackAddress &&
                (a.address[0].toInt() and 0xFE) != 0xFC // not a unique local fc00::/7 address
        }
        return addr && lp.routes.any { it.isDefaultRoute && it.destination.address is Inet6Address }
    }

    /**
     * One plain HTTP request over a raw socket of this network: true it answered, false it did not, null the page has
     * no address of the family asked for ([v6]). Not HttpURLConnection: the release build forbids cleartext HTTP
     * (usesCleartextTraffic=false), which would fail every probe; raw sockets are not subject to it.
     */
    private fun http(n: Network, p: Probe, v6: Boolean = false): Boolean? {
        return try {
            val all = n.getAllByName(p.host) // through this network's own DNS
            val addr = if (v6) all.firstOrNull { it is Inet6Address } ?: v6Test() ?: return null
                else all.firstOrNull { it is Inet4Address } ?: all.firstOrNull() ?: return false
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
            // (nextFixAt too: the pause after one outage's restart must not hold back the cure of the next one, as it
            // did in the owner's log: a freeze waited 31 s for the pause left by the one before)
            fails = 0; fixes = 0; nextFixAt = 0L; downSince = 0L; cause = null
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
        val fixable = r.cause == Cause.LINK || r.cause == Cause.DNS || r.cause == Cause.NO_DATA || r.cause == Cause.V6
        if (r.cause != cause) {
            cause = r.cause
            add(Event(System.currentTimeMillis(), "lost", r.cause?.name ?: "", rssi.toString()))
            // what the outage looks like before anything is restarted, then the restart
            if (fixes == 0) snapshot(n) { if (fixable && net == n && cause != null) maybeFix(SystemClock.elapsedRealtime()) }
            else if (fixable) maybeFix(now)
        } else {
            changed()
            if (fixable) maybeFix(now)
        }
        schedule(PROBE_EVERY_MS)
    }

    /**
     * Where the traffic stops, logged once per outage (root shell, so the pings go out as the system's own traffic):
     * the car's address, the phone (gateway) answering a ping, the internet answering a ping (through the phone's
     * NAT), the number of global IPv6 addresses and the internet answering a ping over IPv6 (the probes already found
     * TCP failing). "gw=1 net=0" is a phone that is there but passes nothing; "net=1" would be TCP alone being blocked;
     * "v6=1 v6net=0" an IPv6 that stopped.
     */
    private fun snapshot(n: Network, then: () -> Unit) {
        val cm = app?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val lp = try { cm?.getLinkProperties(n) } catch (_: Throwable) { null }
        if (lp == null) { then(); return }
        val iface = lp.interfaceName ?: "wlan0"
        val gw = lp.routes.filter { it.isDefaultRoute && it.hasGateway() && it.gateway is Inet4Address }
            .firstOrNull()?.gateway?.hostAddress.orEmpty()
        io.execute {
            val cmd = "a=\$(ip -4 addr show $iface | grep -o 'inet [0-9.]*' | head -1 | cut -d' ' -f2); " +
                "g=0; [ -n '$gw' ] && ping -c 1 -W 2 '$gw' >/dev/null 2>&1 && g=1; " +
                "p=0; ping -c 1 -W 2 1.1.1.1 >/dev/null 2>&1 && p=1; " +
                "v=\$(ip -6 addr show $iface scope global 2>/dev/null | grep -c inet6); " +
                "q=-; [ \"\$v\" != 0 ] && { q=0; ping6 -c 1 -W 2 2606:4700:4700::1111 >/dev/null 2>&1 && q=1; }; " +
                "echo \"ip=\$a gw=\$g net=\$p v6=\$v v6net=\$q\""
            val r = LocalAdb.run(cmd, 12_000)
            val line = r.output.lines().lastOrNull { it.startsWith("ip=") }
            main.post {
                if (r.ok && line != null) add(Event(System.currentTimeMillis(), "diag", line.take(80)))
                then()
            }
        }
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
        // not allowed through the API (an app targeting Android 10): Wi-Fi off and on through the unit's shell, detached
        // so that nothing (a shell connection the restart itself takes down) can leave the Wi-Fi off
        logged("restart")
        io.execute {
            val r = LocalAdb.run("nohup sh -c 'svc wifi disable; sleep 3; svc wifi enable' >/dev/null 2>&1 & echo started")
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
    /** A line from elsewhere ([WifiOnStart]). Main thread. */
    fun record(type: String, a: String = "") = add(Event(System.currentTimeMillis(), type, a))

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
