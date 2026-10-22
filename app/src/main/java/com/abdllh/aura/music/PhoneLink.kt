package com.abdllh.aura.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.system.SystemProps
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Naghm, the owner's iPhone music app, says what it plays and exactly where the song is — what the unit's Bluetooth
 * module never tells (see [BtMusic]) — and takes seek / play / pause / next / previous from here. The unit is on the
 * iPhone's Personal Hotspot, so the phone is the Wi-Fi's gateway: Naghm listens there on TCP [PORT]. One JSON object
 * per line both ways (Naghm's CAR_LINK.md). Emulator: `setprop debug.aura.phonelink 10.0.2.2:47100`.
 *
 * Everything the screen reads is set on the main thread; the socket is read on [worker] and written on [writer].
 */
object PhoneLink {
    private const val TAG = "AuraPhoneLink"
    const val PORT = 47100
    /** Between two tries while Naghm is not there (a refused connection to the gateway costs nothing). */
    private const val RETRY_MS = 5_000L
    private const val PING_MS = 5_000L
    /** Naghm answers every ping: this long without a word and the connection is taken for dead. */
    private const val READ_TIMEOUT_MS = 16_000

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val writer = Executors.newSingleThreadExecutor()
    private val artLoader = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    @Volatile private var socket: Socket? = null

    /** A connection is open and Naghm said hello. */
    var connected = false
        private set
    var id = ""
        private set
    var title = ""
        private set
    var artist = ""
        private set
    var artUrl = ""
        private set
    /** The song's cover as Naghm shows it (its own picture, not a catalogue's guess), or null while it loads. */
    var art: Bitmap? = null
        private set
    var durationMs = 0L
        private set
    var playing = false
        private set
    private var basePosMs = 0L
    private var baseAt = 0L
    /** For Settings → About: the link's last news (kept across restarts, for reports from the unit). */
    val note: String get() = com.abdllh.aura.util.Prefs.raw.getString("phoneLinkNote", "").orEmpty()
    private fun note(what: String) {
        try {
            val at = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date())
            com.abdllh.aura.util.Prefs.raw.edit().putString("phoneLinkNote", "$at  $what").apply()
        } catch (_: Throwable) {
        }
    }

    /** Naghm is there and has a song. */
    val active get() = connected && id.isNotEmpty() && title.isNotEmpty()

    /** Where Naghm's song is now (ms): its last report moved on by the time since. */
    fun positionMs(): Long {
        val p = if (playing) basePosMs + (SystemClock.elapsedRealtime() - baseAt) else basePosMs
        return if (durationMs > 0) p.coerceIn(0L, durationMs) else p.coerceAtLeast(0L)
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners) l() }

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        Thread(::worker, "aura-phonelink").apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------------------------------------ commands
    /** Moves Naghm's song to [ms]; the clock here goes there at once (Naghm confirms a moment later). */
    fun seek(ms: Long) {
        send(JSONObject().put("type", "seek").put("position", ms / 1000.0))
        basePosMs = ms.coerceAtLeast(0L)
        baseAt = SystemClock.elapsedRealtime()
        changed()
    }
    /** "play", "pause", "toggle", "next" or "previous". */
    fun control(what: String) = send(JSONObject().put("type", what))

    private fun send(message: JSONObject) {
        val s = socket ?: return
        writer.execute {
            try {
                val out = s.getOutputStream()
                out.write((message.toString() + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (t: Throwable) {
                try { s.close() } catch (_: Throwable) { }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ connection
    private fun worker() {
        while (true) {
            val target = target()
            if (target != null) {
                try { session(target.first, target.second) } catch (t: Throwable) { Log.d(TAG, "session: $t") }
            }
            try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { }
        }
    }

    /** The phone: the Wi-Fi's gateway (the iPhone itself on its hotspot), or a debug build's chosen address. */
    private fun target(): Pair<String, Int>? {
        if (BuildConfig.DEBUG) {
            SystemProps.get("debug.aura.phonelink").split(":").takeIf { it.size == 2 }?.let { (h, p) ->
                p.toIntOrNull()?.let { return h to it }
            }
        }
        val wifi = app?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val gateway = try { wifi.dhcpInfo?.gateway ?: 0 } catch (_: Throwable) { 0 }
        if (gateway == 0) return null
        val ip = "${gateway and 0xFF}.${gateway shr 8 and 0xFF}.${gateway shr 16 and 0xFF}.${gateway ushr 24 and 0xFF}"
        return ip to PORT
    }

    private fun session(host: String, port: Int) {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 3000)
            s.soTimeout = READ_TIMEOUT_MS
        } catch (t: Throwable) {
            try { s.close() } catch (_: Throwable) { }
            return
        }
        socket = s
        Log.i(TAG, "connected to $host:$port")
        send(JSONObject().put("type", "hello").put("app", "AMRI").put("name", Build.MODEL ?: "K2501")
            .put("version", BuildConfig.VERSION_NAME).put("protocol", 1))
        main.post(pinger)
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val message = try { JSONObject(line) } catch (_: Throwable) { continue }
                main.post { handle(message, host) }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "read: $t")
        } finally {
            socket = null
            try { s.close() } catch (_: Throwable) { }
            main.post {
                main.removeCallbacks(pinger)
                val was = connected
                connected = false
                id = ""; title = ""; artist = ""; artUrl = ""; art = null; durationMs = 0L; playing = false
                if (was) { note("lost $host"); changed() }
            }
        }
    }

    private val pinger = object : Runnable {
        override fun run() {
            if (socket == null) return
            send(JSONObject().put("type", "ping"))
            main.postDelayed(this, PING_MS)
        }
    }

    private fun handle(message: JSONObject, host: String) {
        when (message.optString("type")) {
            "hello" -> {
                connected = true
                note("${message.optString("app", "Naghm")} ${message.optString("version")} at $host")
                changed()
            }
            "state" -> {
                connected = true
                val newId = text(message, "id")
                val newArt = text(message, "art")
                id = newId
                title = text(message, "title")
                artist = text(message, "artist")
                durationMs = (message.optDouble("duration", 0.0) * 1000).toLong().coerceAtLeast(0L)
                playing = message.optBoolean("playing", false)
                basePosMs = (message.optDouble("position", 0.0) * 1000).toLong().coerceAtLeast(0L)
                baseAt = SystemClock.elapsedRealtime()
                if (newArt != artUrl) { artUrl = newArt; art = null; loadArt(newArt) }
                changed()
            }
        }
    }

    /** A JSON string, "" for a missing value or null (optString would give "null"). */
    private fun text(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key, "")

    private fun loadArt(url: String) {
        if (!url.startsWith("https://")) return
        artLoader.execute {
            val bitmap = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.inputStream.use { input ->
                    val bytes = input.readBytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 600) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            } catch (t: Throwable) { null }
            main.post { if (artUrl == url && bitmap != null) { art = bitmap; changed() } }
        }
    }
}
