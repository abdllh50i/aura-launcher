package com.abdllh.aura.nav.offline

import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.nav.Places
import com.abdllh.aura.util.Prefs
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * The map's tiles and font glyphs, served on this device (http://127.0.0.1:47821): from the offline map when it has
 * them, else from OpenFreeMap. The map style points here instead of at OpenFreeMap, because OpenFreeMap publishes a
 * new version of the planet every week under a new address: tiles kept under those addresses (MapLibre's own offline
 * regions) would stop being found a week after the download. Here a tile is just z/x/y.
 *  - A fixed port keeps the addresses (and so MapLibre's cache of them) the same across restarts.
 *  - Every connection has its own thread: a tile on disk never waits behind one that is being fetched online.
 *  - When the internet is down, OpenFreeMap is not asked again for half a minute (no 5-15 s waits per tile), and the
 *    tile address is refreshed in the background, never while a tile waits.
 */
object TileServer {
    private const val TAG = "AuraTiles"
    private const val PORT = 47821
    private const val TILEJSON = "https://tiles.openfreemap.org/planet"
    private const val FONTS = "https://tiles.openfreemap.org/fonts/"
    private const val KEY_TEMPLATE = "ofmTileTemplate"
    private const val KEY_TEMPLATE_AT = "ofmTileTemplateAt"
    private const val TEMPLATE_MAX_AGE_MS = 24 * 3600_000L
    private const val DOWN_PAUSE_MS = 30_000L

    @Volatile var port = 0; private set
    /** The offline map, when there is one (set by [OfflineMaps]). */
    @Volatile var store: TileStore? = null

    private val pool = Executors.newCachedThreadPool()
    @Volatile private var template: String? = null
    @Volatile private var templateAt = 0L
    private val refreshing = AtomicBoolean(false)
    @Volatile private var lastRefreshTry = 0L
    /** Upstream is not asked before this (elapsed realtime): it just failed (no internet, a stalled hotspot). */
    @Volatile private var downUntil = 0L

    val running: Boolean get() = port != 0
    val tilesUrl: String get() = "http://127.0.0.1:$port/t/{z}/{x}/{y}.pbf"
    val glyphsUrl: String get() = "http://127.0.0.1:$port/f/{fontstack}/{range}.pbf"

    @Synchronized
    fun start(): Boolean {
        if (port != 0) return true
        val ss = try {
            ServerSocket(PORT, 64, InetAddress.getByName("127.0.0.1"))
        } catch (t: Throwable) {
            try { ServerSocket(0, 64, InetAddress.getByName("127.0.0.1")) } catch (t2: Throwable) { Log.w(TAG, "no server: $t2"); return false }
        }
        port = ss.localPort
        template = Prefs.raw.getString(KEY_TEMPLATE, null)
        templateAt = Prefs.raw.getLong(KEY_TEMPLATE_AT, 0L)
        Thread({
            while (true) {
                try {
                    val s = ss.accept()
                    pool.execute { serve(s) }
                } catch (t: Throwable) {
                    Log.w(TAG, "accept: $t")
                    try { Thread.sleep(200) } catch (_: InterruptedException) { }
                }
            }
        }, "aura-tiles").apply { isDaemon = true; start() }
        Log.i(TAG, "serving on $port")
        return true
    }

    private class Reply(val code: Int, val body: ByteArray = ByteArray(0), val cache: Boolean = true)

    private fun serve(s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 20_000
                val inp = BufferedInputStream(sock.getInputStream())
                val request = line(inp) ?: return
                while (true) { val h = line(inp) ?: break; if (h.isEmpty()) break } // headers: not needed
                val path = request.split(' ').getOrNull(1) ?: return
                val out = sock.getOutputStream()
                when {
                    path.startsWith("/t/") -> tile(path, out)
                    path.startsWith("/f/") -> write(out, glyphs(path))
                    else -> write(out, Reply(404, cache = false))
                }
            } catch (t: Throwable) {
                Log.d(TAG, "serve: $t")
            }
        }
    }

    /** /t/z/x/y.pbf: from the store, else streamed from OpenFreeMap. */
    private fun tile(path: String, out: OutputStream) {
        val p = path.removePrefix("/t/").removeSuffix(".pbf").split('/')
        val z = p.getOrNull(0)?.toIntOrNull()
        val x = p.getOrNull(1)?.toIntOrNull()
        val y = p.getOrNull(2)?.toIntOrNull()
        if (z == null || x == null || y == null) { write(out, Reply(404, cache = false)); return }
        store?.let { st -> try { st.tile(TileMath.key(z, x, y)) } catch (_: Throwable) { null } }?.let {
            write(out, if (it.isEmpty()) Reply(204) else Reply(200, it)); return
        }
        val t = tileTemplate()
        if (t == null || upstreamDown()) { write(out, Reply(503, cache = false)); return }
        stream(t.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y"), out)
    }

    /** /f/{fontstack}/{range}.pbf (the font stack URL-encoded by MapLibre) */
    private fun glyphs(path: String): Reply {
        val rest = path.removePrefix("/f/")
        val cut = rest.lastIndexOf('/')
        if (cut <= 0) return Reply(404, cache = false)
        val stack = URLDecoder.decode(rest.substring(0, cut), "UTF-8")
        val range = rest.substring(cut + 1).removeSuffix(".pbf")
        val name = "$stack/$range"
        store?.let { st -> try { st.glyph(name) } catch (_: Throwable) { null } }?.let { return Reply(200, it) }
        // not on the unit and no internet: "no glyphs here" lets the map draw (a missing range would hold up every
        // tile whose labels need it), asked again next time
        if (upstreamDown()) return Reply(204, cache = false)
        val r = fetch(glyphUrl(stack, range))
        if (r.code == 200) store?.let { st -> try { st.putGlyph(name, r.body) } catch (_: Throwable) { } } // small, kept for offline
        return if (r.code == 503) Reply(204, cache = false) else r
    }

    fun glyphUrl(stack: String, range: String) = FONTS + URLEncoder.encode(stack, "UTF-8").replace("+", "%20") + "/$range.pbf"

    private fun upstreamDown() = SystemClock.elapsedRealtime() < downUntil

    private fun markDown() { downUntil = SystemClock.elapsedRealtime() + DOWN_PAUSE_MS }

    /**
     * OpenFreeMap's current tile address ("https://tiles.openfreemap.org/planet/<version>/{z}/{x}/{y}.pbf"). The last
     * one known is returned at once; when it is a day old a new one is looked up in the background.
     */
    fun tileTemplate(): String? {
        val known = template
        val now = System.currentTimeMillis()
        if (known != null) {
            if (now - templateAt >= TEMPLATE_MAX_AGE_MS && !upstreamDown() &&
                SystemClock.elapsedRealtime() - lastRefreshTry > 10 * 60_000L && refreshing.compareAndSet(false, true)) {
                lastRefreshTry = SystemClock.elapsedRealtime()
                pool.execute { try { freshTemplate() } finally { refreshing.set(false) } }
            }
            return known
        }
        return if (upstreamDown()) null else freshTemplate()
    }

    /** Looks the tile address up now (the offline download starts with a current one); null without internet. */
    fun freshTemplate(): String? {
        val r = fetch(TILEJSON, json = true)
        if (r.code != 200) return null
        return try {
            val body = if (gzip(r.body)) GZIPInputStream(r.body.inputStream()).use { it.readBytes() } else r.body
            val t = JSONObject(String(body, Charsets.UTF_8)).getJSONArray("tiles").getString(0)
            val now = System.currentTimeMillis()
            template = t
            templateAt = now
            Prefs.raw.edit().putString(KEY_TEMPLATE, t).putLong(KEY_TEMPLATE_AT, now).apply()
            t
        } catch (t: Throwable) {
            Log.d(TAG, "tilejson: $t")
            null
        }
    }

    private fun open(url: String, json: Boolean = false): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", Places.USER_AGENT)
        c.setRequestProperty("Accept-Encoding", "gzip") // set by hand: the body is not unpacked, it is kept as is
        if (json) c.setRequestProperty("Accept", "application/json")
        return c
    }

    /** One upstream request, buffered; the body as sent (gzip stays gzip). 503 when there is no internet. */
    private fun fetch(url: String, json: Boolean = false): Reply {
        var c: HttpURLConnection? = null
        return try {
            c = open(url, json)
            when (val code = c.responseCode) {
                200 -> Reply(200, c.inputStream.use { it.readBytes() })
                204 -> Reply(204)
                404 -> Reply(404)
                else -> Reply(if (code >= 500) 503 else code, cache = false)
            }
        } catch (t: Throwable) {
            markDown()
            Reply(503, cache = false)
        } finally {
            c?.disconnect()
        }
    }

    /** A tile from upstream passed on as it arrives (the map's own read timeout keeps running while it comes). */
    private fun stream(url: String, out: OutputStream) {
        var c: HttpURLConnection? = null
        try {
            c = open(url)
            val code = c.responseCode
            if (code != 200) {
                write(out, when (code) { 204 -> Reply(204); 404 -> Reply(404); else -> Reply(503, cache = false) })
                return
            }
            val length = c.contentLengthLong
            if (length < 0) { write(out, Reply(200, c.inputStream.use { it.readBytes() })); return }
            val gz = c.contentEncoding.equals("gzip", ignoreCase = true)
            head(out, 200, length, gz, true)
            c.inputStream.use { it.copyTo(out, 16 * 1024) }
            out.flush()
        } catch (t: Throwable) {
            markDown()
            try { write(out, Reply(503, cache = false)) } catch (_: Throwable) { } // (if nothing was sent yet)
        } finally {
            c?.disconnect()
        }
    }

    private fun gzip(b: ByteArray) = b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()

    private fun head(out: OutputStream, code: Int, length: Long, gz: Boolean, cache: Boolean) {
        val status = when (code) { 200 -> "OK"; 204 -> "No Content"; 404 -> "Not Found"; 503 -> "Service Unavailable"; else -> "Error" }
        val sb = StringBuilder("HTTP/1.1 $code $status\r\n")
        if (code == 200) {
            sb.append("Content-Type: application/x-protobuf\r\n")
            if (gz) sb.append("Content-Encoding: gzip\r\n")
        }
        sb.append("Content-Length: $length\r\n")
        // what is missing is asked for again (it may come with the internet or the download); the rest keeps a day
        sb.append(if (cache && code != 503) "Cache-Control: max-age=86400\r\n" else "Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun write(out: OutputStream, r: Reply) {
        val body = if (r.code == 204) ByteArray(0) else r.body
        head(out, r.code, body.size.toLong(), gzip(body), r.cache)
        out.write(body)
        out.flush()
    }

    private fun line(inp: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) return b.toString("ISO-8859-1").trimEnd('\r')
            if (b.size() > 8192) return null
            b.write(c)
        }
    }
}
