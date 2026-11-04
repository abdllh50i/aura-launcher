package com.abdllh.aura.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Place
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * AMRI Link v2: the unit's side of "set a place on the phone, directions start on the screen". A small HTTP/1.1 server
 * on every interface, port 47101, in the manner of [com.abdllh.aura.nav.offline.TileServer]; the phone connects out to
 * it, because that is the one thing a page in Safari, a Shortcut, a share extension and an app can all do while iOS has
 * the app itself asleep. Naghm's link ([com.abdllh.aura.music.PhoneLink], the unit dialling out to port 47100) stays as
 * it is.
 *
 * Off until the owner switches it on in Settings (the 2.0 design decides whether it stays a switch); then one blocked
 * thread at idle. At most [MAX_CONNECTIONS] connections at background priority, the next is told 503 at once. A whole
 * request has [HEAD_MS] to arrive (a watchdog closes the socket, so a client that sends one byte a second gets no
 * further), a body is at most [BODY_CAP], an answer that nobody reads is cut after [WRITE_MS]. Every connection closes
 * after its answer. Nothing in here may reach the home screen: every thread catches everything, and the app's main
 * thread is only ever asked through [NavBridge], with a time limit.
 *
 * Who may do what ([Peer], [Pairing]): a paired token acts at once. No token: the Wi-Fi's gateway (on the hotspot that
 * is the phone itself, and any app on it) gets a card on the screen that waits for a tap; any other private address gets
 * only the ping and the pairing while the code is showing; other addresses nothing.
 * For web pages: a POST must say application/json and no CORS allowance is ever sent, so a preflight fails; a mismatched
 * Origin is refused; Host must be an address or this unit's name (DNS rebinding).
 *
 * Debug builds, for `adb forward` (whose connections come from loopback, refused otherwise):
 * `setprop debug.aura.phoneserver 1` treats loopback as any other private peer, `gateway` as the gateway; and with
 * either, /debug/... (the parser, the QR, a pairing code) for scripts/phone-server-test.py.
 */
object PhoneServer {
    private const val TAG = "AuraPhoneServer"
    const val PORT = 47101
    private const val PROTO = 2
    private const val KEY_ON = "phoneServerOn"
    private const val MAX_CONNECTIONS = 4
    /** Of those, how many can be event streams: the oldest goes for a new one, so a request always finds a way in. */
    private const val MAX_STREAMS = 2
    private const val HEAD_MS = 10_000L
    private const val WRITE_MS = 20_000L
    private const val BODY_CAP = 64 * 1024
    private const val HEAD_CAP = 16 * 1024
    private const val HEARTBEAT_S = 15L
    private const val MAX_IDS = 64
    /** A short link's redirect, at most two hops of 3 s + 3 s: the parser's own limit. An answer may take that long. */
    private const val WORK_MS = 30_000L

    /** Who is asking. */
    internal enum class Peer { GATEWAY, PRIVATE, REFUSED }

    enum class Status { OFF, STARTING, RUNNING, FAILED }

    @Volatile var status = Status.OFF; private set
    /** Why it could not open its port, when [status] is [Status.FAILED]. */
    @Volatile var failure = ""; private set
    @Volatile private var server: ServerSocket? = null
    private var app: Context? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val open = ConcurrentHashMap.newKeySet<Socket>()

    private val pool by lazy {
        ThreadPoolExecutor(0, MAX_CONNECTIONS, 5L, TimeUnit.SECONDS, SynchronousQueue<Runnable>()) { r ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "aura-phone").apply { isDaemon = true }
        }
    }
    private val watchdog by lazy {
        ScheduledThreadPoolExecutor(1) { r ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "aura-phone-guard").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(5L, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { NavBridge.post("status") { for (l in listeners) l() } }

    // ------------------------------------------------------------------------------------------ on and off
    /** The owner's switch in Settings. Off by default. */
    var enabled: Boolean
        get() = Prefs.raw.getBoolean(KEY_ON, false)
        set(v) { Prefs.raw.edit().putBoolean(KEY_ON, v).apply(); apply() }

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        if (BuildConfig.DEBUG) debugHooks(ctx.applicationContext)
        apply()
    }

    private fun apply() {
        if (enabled && SystemProps.get(Device.DISABLED_PROP) != "1") start() else stop()
    }

    @Synchronized
    private fun start() {
        if (server != null || status == Status.STARTING) return
        status = Status.STARTING
        failure = ""
        changed()
        Thread(::serve, "aura-phone-accept").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun stop() {
        try { server?.close() } catch (_: Throwable) { }
        server = null
        for (s in open) close(s)
        open.clear()
        for (st in streams) st.end()
        if (status != Status.OFF) { status = Status.OFF; changed() }
    }

    /** Opens the port (a few tries: an earlier process's socket may still be going) and takes connections until stopped. */
    private fun serve() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var ss: ServerSocket? = null
        for (attempt in 1..3) {
            try {
                ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT), 8) }
                break
            } catch (t: Throwable) {
                failure = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "bind: $t")
                try { Thread.sleep(attempt * 1000L) } catch (_: InterruptedException) { }
            }
        }
        val socket = ss
        if (socket == null) {
            // (switched off and on quickly: another of these threads may hold the port by now)
            synchronized(this) { if (server == null) status = if (enabled) Status.FAILED else Status.OFF }
            changed()
            return
        }
        synchronized(this) {
            if (!enabled) {
                try { socket.close() } catch (_: Throwable) { }
                status = Status.OFF
                changed()
                return
            }
            server = socket
            status = Status.RUNNING
        }
        changed()
        Log.i(TAG, "listening on $PORT")
        while (server === socket) {
            try {
                val s = socket.accept()
                try {
                    pool.execute { connection(s) }
                } catch (_: RejectedExecutionException) {
                    // (full: told at once, on this thread; a fresh socket's buffer takes these few bytes without waiting)
                    try {
                        s.soTimeout = 1000
                        s.getOutputStream().write(("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 2\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                    } catch (_: Throwable) { }
                    close(s)
                }
            } catch (t: Throwable) {
                if (server !== socket) break
                Log.w(TAG, "accept: $t")
                try { Thread.sleep(200) } catch (_: InterruptedException) { }
            }
        }
        Log.i(TAG, "stopped")
    }

    private fun close(s: Socket) { try { s.close() } catch (_: Throwable) { } }

    // ------------------------------------------------------------------------------------------ what About and Settings show
    /** The address a phone reaches this unit at on the network it shares with it: Wi-Fi first. */
    fun address(): String? = try {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
        val sorted = all.sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
        sorted.asSequence().flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { it is Inet4Address && isPrivate(it) }?.hostAddress
    } catch (_: Throwable) {
        null
    }

    /** The address of the page, for a code being shown; null without a network. */
    fun pairUrl(code: String): String? = address()?.let { "http://$it:$PORT/p/$code" }

    @Volatile private var lastAt = 0L
    @Volatile private var lastPeer = ""

    /** "18:22 from 172.20.10.1": the last request, for Settings → About. */
    val lastRequest: String
        get() = if (lastAt == 0L) "" else "${clock(lastAt)}  $lastPeer"

    internal fun clock(at: Long): String = java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(java.util.Date(at))

    // ------------------------------------------------------------------------------------------ peers
    private fun gateway(): InetAddress? = try {
        val wifi = app?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val g = wifi?.dhcpInfo?.gateway ?: 0
        if (g == 0) null else InetAddress.getByAddress(byteArrayOf(g.toByte(), (g shr 8).toByte(), (g shr 16).toByte(), (g ushr 24).toByte()))
    } catch (_: Throwable) {
        null
    }

    /** The same private addresses as Naghm's (CarLink.swift), without "this device itself" outside a debug build. */
    internal fun isPrivate(a: InetAddress): Boolean = when (a) {
        is Inet4Address -> {
            val b0 = a.address[0].toInt() and 0xFF
            val b1 = a.address[1].toInt() and 0xFF
            b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168) || (b0 == 169 && b1 == 254)
        }
        is Inet6Address -> a.isLinkLocalAddress || (a.address[0].toInt() and 0xFE) == 0xFC
        else -> false
    }

    /** [debug]: the value of debug.aura.phoneserver (always empty in a release build). */
    internal fun classify(a: InetAddress, gateway: InetAddress?, debug: String): Peer = when {
        a.isLoopbackAddress -> when (debug) { "gateway" -> Peer.GATEWAY; "1" -> Peer.PRIVATE; else -> Peer.REFUSED }
        gateway != null && a == gateway -> Peer.GATEWAY
        isPrivate(a) -> Peer.PRIVATE
        else -> Peer.REFUSED
    }

    private fun debugProp(): String = if (BuildConfig.DEBUG) SystemProps.get("debug.aura.phoneserver") else ""

    // ------------------------------------------------------------------------------------------ a connection
    /** A socket and its watchdog: past its time the socket is closed, whatever the thread on it is waiting for. */
    private class Conn(val socket: Socket) {
        private var guard: Future<*>? = null
        val out: OutputStream get() = socket.getOutputStream()
        fun arm(ms: Long) { disarm(); guard = watchdog.schedule({ close(socket) }, ms, TimeUnit.MILLISECONDS) }
        fun disarm() { guard?.cancel(false); guard = null }
    }

    private class Request(
        val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray,
        val peer: Peer, val peerAddress: String
    ) {
        val host get() = headers["host"]
        val bearer: String? get() = headers["authorization"]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        val paired: Boolean by lazy { Pairing.known(bearer) }
        /** The body as JSON; null when it does not say it is JSON or is not. */
        val json: JSONObject? by lazy {
            try { JSONObject(String(body, Charsets.UTF_8)) } catch (_: Throwable) { null }
        }
        val saysJson: Boolean get() = headers["content-type"]?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) == true
    }

    private fun connection(s: Socket) {
        open.add(s)
        val c = Conn(s)
        try {
            c.arm(HEAD_MS)
            s.tcpNoDelay = true
            s.soTimeout = HEAD_MS.toInt()
            // (the ROM's restore at work: AMRI is on its way out, nothing new starts here)
            if (SystemProps.get(Device.DISABLED_PROP) == "1") { stop(); return }
            val addr = s.inetAddress
            val peer = classify(addr, gateway(), debugProp())
            if (peer == Peer.REFUSED) { reply(c, 403, reason("refused")); return }
            val r = read(c, peer, addr.hostAddress.orEmpty()) ?: return
            lastAt = System.currentTimeMillis()
            lastPeer = r.peerAddress
            c.arm(WORK_MS)
            answer(c, r)
        } catch (t: Throwable) {
            Log.d(TAG, "connection: $t")
        } finally {
            c.disarm()
            open.remove(s)
            close(s)
        }
    }

    /** A request: the head line by line within the limits, then the body. Null when it was answered here (or never came). */
    private fun read(c: Conn, peer: Peer, address: String): Request? {
        val inp = c.socket.getInputStream()
        val headBytes = ByteArrayOutputStream(512)
        val buf = ByteArray(1024)
        var extra = ByteArray(0)
        while (true) {
            val n = inp.read(buf)
            if (n < 0) return null
            val from = maxOf(0, headBytes.size() - 3)
            headBytes.write(buf, 0, n)
            val all = headBytes.toByteArray()
            val end = endOfHead(all, from)
            if (end >= 0) {
                extra = all.copyOfRange(end, all.size)
                val text = String(all, 0, end, Charsets.ISO_8859_1)
                return parse(c, text, extra, inp, peer, address)
            }
            if (all.size > HEAD_CAP) { reply(c, 431, reason("head_too_large")); return null }
        }
    }

    private fun endOfHead(b: ByteArray, from: Int): Int {
        var i = from
        while (i + 1 < b.size) {
            if (b[i] == '\n'.code.toByte() && b[i + 1] == '\n'.code.toByte()) return i + 2
            if (i + 3 < b.size && b[i] == '\r'.code.toByte() && b[i + 1] == '\n'.code.toByte() &&
                b[i + 2] == '\r'.code.toByte() && b[i + 3] == '\n'.code.toByte()) return i + 4
            i++
        }
        return -1
    }

    private fun parse(c: Conn, head: String, extra: ByteArray, inp: InputStream, peer: Peer, address: String): Request? {
        val lines = head.split('\n').map { it.trimEnd('\r') }
        val first = lines.firstOrNull().orEmpty().split(' ')
        if (first.size != 3 || !first[2].startsWith("HTTP/1.")) { reply(c, 400, reason("bad_request")); return null }
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val colon = l.indexOf(':')
            if (colon <= 0) continue
            val k = l.substring(0, colon).trim().lowercase(Locale.ROOT)
            // (two Host headers: which one a browser meant cannot be told)
            if (k == "host" && k in headers) { reply(c, 400, reason("bad_request")); return null }
            headers[k] = l.substring(colon + 1).trim()
        }
        val path = first[1].substringBefore('?').substringBefore('#')
        var body = ByteArray(0)
        if (first[0] == "POST" || first[0] == "PUT") {
            if ("transfer-encoding" in headers) { reply(c, 411, reason("length_required")); return null }
            val length = headers["content-length"]?.toIntOrNull()
            if (length == null || length < 0) { reply(c, 411, reason("length_required")); return null }
            if (length > BODY_CAP) { reply(c, 413, reason("too_large")); return null }
            if (headers["expect"].equals("100-continue", ignoreCase = true)) {
                c.out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                c.out.flush()
            }
            body = ByteArray(length)
            var got = minOf(extra.size, length)
            System.arraycopy(extra, 0, body, 0, got)
            while (got < length) {
                val n = inp.read(body, got, length - got)
                if (n < 0) return null
                got += n
            }
        }
        return Request(first[0], path, headers, body, peer, address)
    }

    // ------------------------------------------------------------------------------------------ answers
    private fun reason(why: String, extra: (JSONObject.() -> Unit)? = null) = JSONObject().put("reason", why).also { extra?.invoke(it) }

    private fun status(code: Int) = when (code) {
        200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 411 -> "Length Required"; 413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"; 422 -> "Unprocessable Entity"; 431 -> "Request Header Fields Too Large"
        503 -> "Service Unavailable"; else -> "Error"
    }

    private fun head(code: Int, type: String?, length: Long?, extra: String = ""): ByteArray {
        val b = StringBuilder("HTTP/1.1 $code ${status(code)}\r\n")
        if (type != null) b.append("Content-Type: $type\r\n")
        if (length != null) b.append("Content-Length: $length\r\n")
        b.append("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n")
        b.append(extra)
        b.append("\r\n")
        return b.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun send(c: Conn, code: Int, type: String?, body: ByteArray, extra: String = "") {
        c.arm(WRITE_MS)
        val o = c.out
        o.write(head(code, type, body.size.toLong(), extra))
        o.write(body)
        o.flush()
    }

    private fun reply(c: Conn, code: Int, o: JSONObject) =
        send(c, code, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

    private val IP_HOST = Regex("""^(?:\d{1,3}(?:\.\d{1,3}){3}|\[[0-9a-fA-F:.%]+\])(?::\d+)?$""")

    /** An address, or this unit's own name: a page on another name that resolves here is DNS rebinding. */
    private fun hostAllowed(host: String?): Boolean {
        if (host == null) return false
        if (IP_HOST.matches(host)) return true
        val name = host.substringBefore(':').lowercase(Locale.ROOT)
        val unit = Pairing.unit
        return name == unit || name == "$unit.local" || (name == "localhost" && debugProp().isNotEmpty())
    }

    /** No Origin (an app, a Shortcut, a plain GET), or the page's own: nothing else. */
    private fun originAllowed(r: Request): Boolean {
        val origin = r.headers["origin"] ?: return true
        return origin == "http://${r.host}"
    }

    private fun answer(c: Conn, r: Request) {
        if (!hostAllowed(r.host)) { reply(c, 403, reason("host")); return }
        if (!originAllowed(r)) { reply(c, 403, reason("origin")); return }
        val isGet = r.method == "GET"
        val isPost = r.method == "POST"
        val isPut = r.method == "PUT"
        // (OPTIONS too: a preflight gets no allowance, so a page elsewhere cannot go on)
        if (!isGet && !isPost && !isPut) { reply(c, 405, reason("method")); return }
        if ((isPost || isPut) && !r.saysJson) { reply(c, 415, reason("json_only")); return }
        val p = r.path
        when {
            p == "/" || Regex("""^/p/\d{6}$""").matches(p) -> if (isGet) page(c) else wrong(c)
            p == "/favicon.ico" || p.startsWith("/apple-touch-icon") -> send(c, 204, null, ByteArray(0))
            p == "/v1/ping" -> if (isGet) ping(c) else wrong(c)
            p == "/v1/pair" -> if (isPost) pair(c, r) else wrong(c)
            p == "/v1/nav/go" -> if (isPost) navGo(c, r) else wrong(c)
            p == "/v1/share" -> if (isPost) share(c, r) else wrong(c)
            p == "/v1/nav/saved" -> if (isPost) navSaved(c, r) else wrong(c)
            p == "/v1/nav/stop" -> if (isPost) navStop(c, r) else wrong(c)
            p == "/v1/search" -> if (isPost) search(c, r) else wrong(c)
            p == "/v1/state" -> if (isGet) state(c, r) else wrong(c)
            p == "/v1/events" -> if (isGet) events(c, r) else wrong(c)
            p == "/v1/places" -> if (isGet) placesGet(c, r) else if (isPut) placesPut(c, r) else wrong(c)
            p == "/v1/remote" -> if (isPost) remote(c, r) else wrong(c)
            p == "/v1/diag" -> if (isGet) diag(c, r) else wrong(c)
            BuildConfig.DEBUG && p.startsWith("/debug/") && debugProp().isNotEmpty() -> debugRoute(c, r)
            else -> reply(c, 404, reason("not_found"))
        }
    }

    private fun wrong(c: Conn) = reply(c, 405, reason("method"))

    /**
     * The main thread is held up: what was asked will still happen when it comes free, so "busy" would be a lie. The
     * phone hears it is on its way ([NavBridge.ask] waits for a limited time, the work stays posted), the events tell the rest.
     */
    private fun pending(c: Conn, id: String?) =
        reply(c, 202, JSONObject().put("state", "pending").also { o -> id?.let { o.put("id", it) } })

    /** The token, or told what is missing. */
    private fun needToken(c: Conn, r: Request): Boolean {
        if (r.paired) return true
        if (r.peer == Peer.GATEWAY) reply(c, 401, reason("pair")) else reply(c, 403, reason("refused"))
        return false
    }

    /** The token, or the gateway (whose place then waits for a tap on the screen). */
    private fun needTokenOrGateway(c: Conn, r: Request): Boolean {
        if (r.paired || r.peer == Peer.GATEWAY) return true
        reply(c, 403, reason("refused"))
        return false
    }

    private fun bodyOf(c: Conn, r: Request): JSONObject? {
        val o = r.json
        if (o == null) reply(c, 400, reason("bad_json"))
        return o
    }

    private fun page(c: Conn) {
        val html = try {
            app?.assets?.open("remote.html")?.use { it.readBytes() }
        } catch (t: Throwable) {
            Log.w(TAG, "page: $t")
            null
        }
        if (html == null) { reply(c, 503, reason("no_page")); return }
        // one file, everything inline, nothing from anywhere else; never in a frame
        send(c, 200, "text/html; charset=utf-8", html,
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n" +
                "X-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n")
    }

    private fun ping(c: Conn) {
        reply(c, 200, JSONObject().put("app", "AMRI").put("unit", Pairing.unit).put("name", Device.model)
            .put("version", BuildConfig.VERSION_NAME).put("proto", PROTO))
    }

    private fun pair(c: Conn, r: Request) {
        val b = bodyOf(c, r) ?: return
        val now = SystemClock.elapsedRealtime()
        if (!Pairing.showing(now)) { reply(c, 403, reason("no_code")); return }
        val device = text(b.optString("device"), 40).ifEmpty { "phone" }
        val got = Pairing.redeem(b.optString("code").trim(), device, now)
        val token = got.token
        if (token == null) { reply(c, 403, reason("bad_code") { put("triesLeft", got.triesLeft) }); return }
        Log.i(TAG, "paired from ${r.peerAddress}")
        reply(c, 200, JSONObject().put("token", token).put("unit", Pairing.unit).put("name", Device.model))
        NavBridge.post("paired") { for (l in pairedListeners) l(device) }
    }

    internal val pairedListeners = CopyOnWriteArrayList<(String) -> Unit>()

    // ------------------------------------------------------------------------------------------ retries
    private class Seen(@Volatile var state: String)

    private val seen = object : LinkedHashMap<String, Seen>(MAX_IDS, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>?) = size > MAX_IDS
    }

    /** First time for this id (the last [MAX_IDS] are remembered)? A retry gets "duplicate" and starts nothing. */
    private fun firstTime(id: String): Boolean = synchronized(seen) {
        if (id in seen) false else { seen[id] = Seen("accepted"); true }
    }

    private fun lastState(id: String): String = synchronized(seen) { seen[id]?.state.orEmpty() }

    /** The id of a request: what the phone sent, or one of ours for a caller that sent none (no retry is told apart then). */
    private fun idOf(c: Conn, b: JSONObject): String? {
        val id = b.optString("id")
        if (id.length > 64) { reply(c, 400, reason("bad_id")); return null }
        return id.ifEmpty { "u" + java.lang.Long.toString(SystemClock.elapsedRealtimeNanos(), 36) }
    }

    /** Said about a place being handled, to every event stream ([PlaceInbox]). Any thread. */
    internal fun navEvent(id: String, state: String, extra: JSONObject?) {
        synchronized(seen) { seen[id]?.state = state }
        val o = extra ?: JSONObject()
        o.put("id", id).put("state", state)
        publish("nav", o)
    }

    // ------------------------------------------------------------------------------------------ a place
    /** One line of plain text from outside: no line breaks or control characters, not endless. */
    private fun text(s: String?, max: Int): String =
        s.orEmpty().replace(Regex("""[\p{Cntrl}  ]+"""), " ").trim().take(max)

    private fun finite(v: Double) = !v.isNaN() && !v.isInfinite()

    private fun placeOf(o: JSONObject?): Place? {
        if (o == null || !o.has("lat") || !o.has("lon")) return null
        val lat = o.optDouble("lat", Double.NaN)
        val lon = o.optDouble("lon", Double.NaN)
        if (!finite(lat) || !finite(lon) || lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        return Place(text(o.optString("name"), 120), text(o.optString("detail"), 240), LatLon(lat, lon))
    }

    /** The phone's own fix, good as the route's origin only when it is recent and close: else as if none was sent. */
    private fun originOf(o: JSONObject?): LatLon? {
        val p = placeOf(o) ?: return null
        val acc = o!!.optDouble("acc", Double.NaN)
        val age = o.optDouble("ageSec", Double.NaN)
        return if (finite(acc) && finite(age) && acc < 100.0 && age < 60.0 && age >= 0.0) p.pos else null
    }

    private fun pin(): String = app?.let { LocaleHelper.wrap(it).getString(R.string.maps_dropped_pin) }.orEmpty()

    private fun named(p: Place) = if (p.name.isBlank()) Place(pin(), p.detail, p.pos) else p

    private fun navGo(c: Conn, r: Request) {
        if (!needTokenOrGateway(c, r)) return
        val b = bodyOf(c, r) ?: return
        val mode = b.optString("mode", "start")
        if (mode != "start") { reply(c, 400, reason("bad_mode")); return }
        val place = placeOf(b.optJSONObject("place"))
        if (place == null) { reply(c, 400, reason("bad_place")); return }
        val id = idOf(c, b) ?: return
        if (!firstTime(id)) { reply(c, 202, JSONObject().put("id", id).put("state", "duplicate").put("last", lastState(id))); return }
        val src = text(b.optJSONObject("place")?.optString("src"), 32).ifEmpty { "phone" }
        NavBridge.place(app ?: return, PlaceInbox.Incoming(id, named(place), src, originOf(b.optJSONObject("from")), r.paired, r.peerAddress, ask = false))
        reply(c, 202, JSONObject().put("id", id).put("state", "accepted").also { if (!r.paired) it.put("needs", "tap") })
    }

    /** Raw text and a link, read here (what a Shortcut or a page has to send). A short link's redirect may take seconds. */
    private fun share(c: Conn, r: Request) {
        if (!needTokenOrGateway(c, r)) return
        val b = bodyOf(c, r) ?: return
        val id = idOf(c, b) ?: return
        val shared = listOf(b.optString("text"), b.optString("url")).filter { it.isNotBlank() }.joinToString("\n")
        if (shared.isBlank()) { reply(c, 400, reason("nothing_shared")); return }
        if (!firstTime(id)) { reply(c, 202, JSONObject().put("id", id).put("state", "duplicate").put("last", lastState(id))); return }
        when (val parsed = ShareParser.parse(shared)) {
            is ShareParser.Result.Found -> {
                val place = named(Place(text(parsed.name, 120), text(parsed.detail, 240), LatLon(parsed.lat, parsed.lon)))
                val guess = parsed.confidence == ShareParser.Confidence.GUESS
                NavBridge.place(app ?: return, PlaceInbox.Incoming(id, place, parsed.src, originOf(b.optJSONObject("from")), r.paired, r.peerAddress, ask = guess))
                reply(c, 202, JSONObject().put("id", id).put("state", "accepted").put("place", place.toJson())
                    .put("confidence", if (guess) "guess" else "exact").also { if (!r.paired || guess) it.put("needs", "tap") })
            }
            is ShareParser.Result.Search -> {
                // a name, no place: the list on the screen for him to pick from; that takes a paired phone
                val shown = r.paired && NavBridge.ask(1500) { NavBridge.searchHere(app, parsed.q) } == ""
                setState(id, "search")
                reply(c, 200, JSONObject().put("id", id).put("state", "search").put("q", parsed.q).put("onScreen", shown))
            }
            is ShareParser.Result.Unresolved -> {
                setState(id, "unresolved")
                reply(c, 422, JSONObject().put("id", id).put("state", "unresolved").put("reason", parsed.reason).also { o -> parsed.q?.let { o.put("q", it) } })
            }
            is ShareParser.Result.Nothing -> {
                setState(id, "unresolved")
                reply(c, 422, JSONObject().put("id", id).put("state", "unresolved").put("reason", parsed.reason))
            }
        }
    }

    private fun setState(id: String, state: String) { synchronized(seen) { seen[id]?.state = state } }

    private fun navSaved(c: Conn, r: Request) {
        if (!needToken(c, r)) return
        val b = bodyOf(c, r) ?: return
        val which = b.optString("which")
        if (which != "home" && which != "work") { reply(c, 400, reason("bad_which")); return }
        val id = idOf(c, b) ?: return
        if (!NavBridge.hasSaved(which)) { reply(c, 409, reason("not_set") { put("which", which) }); return }
        if (!firstTime(id)) { reply(c, 202, JSONObject().put("id", id).put("state", "duplicate").put("last", lastState(id))); return }
        NavBridge.saved(app ?: return, which, id, r.peerAddress, pin())
        reply(c, 202, JSONObject().put("id", id).put("state", "accepted"))
    }

    private fun navStop(c: Conn, r: Request) {
        if (!needToken(c, r)) return
        val b = bodyOf(c, r) ?: return
        val id = idOf(c, b) ?: return
        if (!firstTime(id)) { reply(c, 200, JSONObject().put("id", id).put("state", "duplicate")); return }
        val was = NavBridge.ask(1500) { PlaceInbox.stop("phone") }
        if (was == null) { pending(c, id); return }
        reply(c, 200, JSONObject().put("id", id).put("state", if (was) "stopped" else "idle"))
    }

    /**
     * A name to search: by default on the screen, the phone as its keyboard (Maps' "q"). `"on":"phone"`: the results
     * come back to the phone and the screen is left alone (the web remote's list). Both go through the unit's own search.
     */
    private fun search(c: Conn, r: Request) {
        if (!needToken(c, r)) return
        val b = bodyOf(c, r) ?: return
        val q = text(b.optString("q"), 120)
        if (q.length < 2) { reply(c, 400, reason("bad_query")); return }
        if (b.optString("on", "screen") == "phone") {
            val list = NavBridge.lookup(q)
            // (the search service did not answer in time and no offline map could: not the same as "nothing found")
            if (list == null) { reply(c, 200, JSONObject().put("state", "no_answer")); return }
            reply(c, 200, JSONObject().put("state", "ok").put("results", list))
            return
        }
        when (val refused = NavBridge.ask(1500) { NavBridge.searchHere(app, q) }) {
            null -> pending(c, null)
            "" -> reply(c, 202, JSONObject().put("state", "accepted"))
            else -> reply(c, 409, reason(refused))
        }
    }

    private fun state(c: Conn, r: Request) {
        if (!needToken(c, r)) return
        val o = NavBridge.ask(3000) { NavBridge.snapshot() }
        if (o == null) reply(c, 503, reason("busy")) else reply(c, 200, o)
    }

    private fun placesGet(c: Conn, r: Request) {
        if (!needToken(c, r)) return
        reply(c, 200, NavBridge.places())
    }

    /** Only what the body names changes. Places from Apple's data are not kept here (its terms: companion.md 2.6). */
    private fun placesPut(c: Conn, r: Request) {
        if (!needToken(c, r)) return
        val b = bodyOf(c, r) ?: return
        fun apple(o: JSONObject?) = o?.optString("src").orEmpty().contains("apple", ignoreCase = true)
        fun many(key: String): List<Place>? {
            val a = b.optJSONArray(key) ?: return null
            return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.takeIf { !apple(it) }?.let { placeOf(it) } }.map(::named)
        }
        for (k in listOf("home", "work")) {
            if (!b.has(k) || b.isNull(k)) continue
            val o = b.optJSONObject(k)
            if (apple(o)) { reply(c, 409, reason("apple_not_kept")); return }
            if (placeOf(o) == null) { reply(c, 400, reason("bad_place")); return }
        }
        // (a key with null clears that place; a key that is not there leaves it)
        fun one(key: String): Pair<Boolean, Place?> = b.has(key) to placeOf(b.optJSONObject(key))?.let(::named)
        val home = one("home")
        val work = one("work")
        val favourites = many("favourites")
        val recents = many("recents")
        val now = NavBridge.ask(1500) { NavBridge.setPlaces(home, work, favourites, recents); NavBridge.places() }
        if (now == null) pending(c, null) else reply(c, 200, now)
    }

    private fun remote(c: Conn, r: Request) {
        if (!needToken(c, r)) return
        val b = bodyOf(c, r) ?: return
        val id = idOf(c, b) ?: return
        val cmd = b.optString("cmd")
        if (!NavBridge.isCommand(cmd)) { reply(c, 400, reason("bad_cmd")); return }
        if (!firstTime(id)) { reply(c, 200, JSONObject().put("id", id).put("state", "duplicate")); return }
        val o = NavBridge.ask(1500) { NavBridge.remote(app, cmd) }
        if (o == null) { pending(c, id); return }
        reply(c, if (o.optString("state") == "ok") 200 else 409, o.put("id", id))
    }

    private fun diag(c: Conn, r: Request) {
        if (!needToken(c, r)) return
        send(c, 200, "text/plain; charset=utf-8", NavBridge.diag(app).toByteArray(Charsets.UTF_8))
    }

    // ------------------------------------------------------------------------------------------ events
    private class Stream(val conn: Conn) {
        val queue = LinkedBlockingQueue<String>(64)
        fun end() { queue.clear(); queue.offer(END_MARK); close(conn.socket) }
    }

    private const val END_MARK = "\u0000end"
    private val streams = CopyOnWriteArrayList<Stream>()

    /** To every stream, from any thread; a stream that does not keep up loses the oldest. */
    internal fun publish(event: String, data: JSONObject) {
        if (streams.isEmpty()) return
        val text = "event: $event\ndata: $data\n\n"
        for (s in streams) {
            if (!s.queue.offer(text)) { s.queue.poll(); s.queue.offer(text) }
        }
    }

    private fun events(c: Conn, r: Request) {
        if (!needToken(c, r)) return
        val st = Stream(c)
        val old = synchronized(streams) {
            val out = ArrayList<Stream>()
            while (streams.size >= MAX_STREAMS) out.add(streams.removeAt(0))
            streams.add(st)
            out
        }
        for (s in old) s.end()
        try {
            c.arm(WRITE_MS)
            val o = c.out
            o.write(head(200, "text/event-stream; charset=utf-8", null))
            o.write("retry: 3000\n\n".toByteArray())
            o.flush()
            c.disarm()
            // the snapshot on connect; the live feed runs on the main thread only while somebody listens
            NavBridge.post("feed") {
                for (e in NavBridge.snapshotEvents()) st.queue.offer("event: ${e.first}\ndata: ${e.second}\n\n")
                NavBridge.feed(true)
            }
            while (true) {
                val msg = st.queue.poll(HEARTBEAT_S, TimeUnit.SECONDS) ?: ": alive\n\n"
                if (msg == END_MARK) break
                c.arm(WRITE_MS) // (a phone gone without a word: the write that blocks is cut)
                o.write(msg.toByteArray(Charsets.UTF_8))
                o.flush()
                c.disarm()
            }
        } finally {
            streams.remove(st)
            if (streams.isEmpty()) NavBridge.post("feed") { if (streams.isEmpty()) NavBridge.feed(false) }
        }
    }

    /** How many phones listen now. */
    val listening: Int get() = streams.size

    // ------------------------------------------------------------------------------------------ debug builds
    private fun debugHooks(c: Context) {
        try {
            c.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(x: Context, i: Intent) {
                    try {
                        if (i.hasExtra("on")) enabled = i.getBooleanExtra("on", false)
                    } catch (t: Throwable) {
                        Log.w(TAG, "debug: $t")
                    }
                }
            }, IntentFilter("com.abdllh.aura.debug.LINK"))
        } catch (_: Throwable) { }
    }

    private fun debugRoute(c: Conn, r: Request) {
        val b = if (r.method == "POST") bodyOf(c, r) ?: return else JSONObject()
        when (r.path) {
            // the parser, with the redirects of a fixture instead of the network ("redirects": {url: {code, location}})
            "/debug/parse" -> {
                val hops = b.optJSONObject("redirects")
                val fetch = if (b.optBoolean("network", false)) ShareParser.network else ShareParser.Fetch { url ->
                    val h = hops?.optJSONObject(url) ?: throw IllegalStateException("no such fixture")
                    ShareParser.Hop(h.optInt("code"), if (h.isNull("location")) null else h.optString("location"))
                }
                reply(c, 200, resultJson(ShareParser.parse(b.optString("text"), fetch)))
            }
            // a code as a picture: what a camera would see
            "/debug/qr" -> {
                val m = Qr.encode(b.optString("text"))
                if (m == null) { reply(c, 422, reason("too_long")); return }
                val png = ByteArrayOutputStream()
                val bmp = Qr.bitmap(m, b.optInt("scale", 6))
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png)
                bmp.recycle()
                send(c, 200, "image/png", png.toByteArray(), "X-Qr-Size: ${m.size}\r\n")
            }
            "/debug/code" -> reply(c, 200, JSONObject().put("code", Pairing.show(SystemClock.elapsedRealtime())))
            // the main thread held up, as a map's first frame on a slow GPU does
            "/debug/block" -> { NavBridge.post("block") { Thread.sleep(b.optLong("ms", 3000L)) }; reply(c, 200, JSONObject()) }
            // what the voice would make of a sentence, and the one place it would take a search's answer to mean
            "/debug/command" -> reply(c, 200, JSONObject().put("command", com.abdllh.aura.voice.Command.parse(b.optString("text"))?.let { it.javaClass.simpleName + " " + it }))
            "/debug/answer" -> {
                val a = b.optJSONArray("list")
                com.abdllh.aura.nav.Places.debugAnswer = a?.let { l -> (0 until l.length()).mapNotNull { placeOf(l.optJSONObject(it)) } }
                reply(c, 200, JSONObject())
            }
            "/debug/winner" -> {
                val a = b.optJSONArray("list") ?: JSONArray()
                val list = (0 until a.length()).mapNotNull { placeOf(a.optJSONObject(it)) }
                val w = com.abdllh.aura.nav.Places.clearWinner(b.optString("q"), list, placeOf(b.optJSONObject("near"))?.pos)
                reply(c, 200, JSONObject().put("winner", w?.name ?: JSONObject.NULL))
            }
            "/debug/hidecode" -> { Pairing.hide(); reply(c, 200, JSONObject()) }
            "/debug/forget" -> { Pairing.forgetAll(); reply(c, 200, JSONObject()) }
            "/debug/peer" -> {
                val a = InetAddress.getByName(b.optString("address"))
                val g = b.optString("gateway").ifEmpty { null }?.let { InetAddress.getByName(it) }
                reply(c, 200, JSONObject().put("peer", classify(a, g, b.optString("debug")).name))
            }
            else -> reply(c, 404, reason("not_found"))
        }
    }

    private fun resultJson(r: ShareParser.Result): JSONObject = when (r) {
        is ShareParser.Result.Found -> JSONObject().put("kind", "place").put("lat", r.lat).put("lon", r.lon).put("name", r.name)
            .put("detail", r.detail).put("src", r.src).put("confidence", r.confidence.name.lowercase(Locale.ROOT))
        is ShareParser.Result.Search -> JSONObject().put("kind", "search").put("q", r.q).put("src", r.src)
        is ShareParser.Result.Unresolved -> JSONObject().put("kind", "unresolved").put("reason", r.reason).put("url", r.url).also { o -> r.q?.let { o.put("q", it) } }
        is ShareParser.Result.Nothing -> JSONObject().put("kind", "nothing").put("reason", r.reason)
    }
}
