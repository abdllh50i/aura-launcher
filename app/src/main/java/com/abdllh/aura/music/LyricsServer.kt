package com.abdllh.aura.music

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.security.NetworkSecurityPolicy
import android.util.Log
import com.abdllh.aura.nav.Places
import com.abdllh.aura.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * The owner's own lyrics server (tools/lyrics-server: a program on his PC, reached through his own domain). It knows
 * more places to find lyrics than LRCLIB (Megalobiz, NetEase, Musixmatch when it answers, Genius as the last resort),
 * and it makes plain lyrics timed by listening to the song in the car ([LyricsSync]). Nothing here runs while no key
 * (or no address) is set: the unit then does exactly what it did before 1.13.
 *
 * Every request but the health check carries the server's key (header X-Lyrics-Key), which the server prints when it
 * starts. Calls block: they are made on the lyrics' own worker threads, never on the main thread.
 */
object LyricsServer {
    private const val TAG = "AuraLyricsSrv"

    /**
     * The owner's own server (his PC, published through his domain on 2026-10-18): the address field starts with it, so
     * only the key is typed on the screen. Nothing reaches it without the key.
     */
    private const val OWNER_ADDRESS = "https://lyric.abod50i.online"

    /** The address in Settings ("lyrics.example.com", "https://…", or for tests "10.0.2.2:8790"). */
    var address: String
        get() = Prefs.raw.getString("lyricsServer", OWNER_ADDRESS) ?: OWNER_ADDRESS
        set(v) { Prefs.raw.edit().putString("lyricsServer", v.trim()).apply() }

    var key: String
        get() = Prefs.raw.getString("lyricsServerKey", "") ?: ""
        set(v) { Prefs.raw.edit().putString("lyricsServerKey", v.trim().lowercase()).apply() }

    /**
     * "Sync lyrics by listening": what the car plays (its own sound since 1.16, the microphone when that cannot be had,
     * or a song's file) goes to the server. On by default since 1.15 (the owner asked that untimed lyrics get timed);
     * the switch turns it off. Only while lyrics without timing, or since 1.16 timed lyrics without their words' real
     * times, show on the music screen ([LyricsSync]); never in a call; the microphone's own rules are unchanged (not
     * while the assistant listens, nor while reversing); and only with the microphone permission already given.
     */
    var listen: Boolean
        get() = Prefs.raw.getBoolean("lyricsListen", true)
        set(v) { Prefs.raw.edit().putBoolean("lyricsListen", v).apply() }

    val configured: Boolean get() = base() != null && key.isNotBlank()

    /**
     * The address as the root of a URL. Without a scheme a name is taken as https (a domain behind a tunnel), an
     * address with a port or a bare IP as http (the PC on the same network, the emulator's 10.0.2.2).
     */
    fun base(): String? {
        val a = address.trim().trimEnd('/')
        if (a.isEmpty()) return null
        if (a.contains("://")) return a
        val hostPort = a.substringBefore('/')
        val host = hostPort.substringBefore(':')
        val local = hostPort.contains(':') || host == "localhost" || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        return (if (local) "http://" else "https://") + a
    }

    /**
     * Plain http to another machine is refused by release builds (network_security.xml lets only this unit itself
     * through): such an address can never work there, and the owner is told to use the domain's https address.
     */
    fun cleartextBlocked(): Boolean {
        val b = base() ?: return false
        if (!b.startsWith("http://")) return false
        val host = try { Uri.parse(b).host } catch (_: Throwable) { null } ?: return false
        return try { !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host) } catch (_: Throwable) { false }
    }

    // ------------------------------------------------------------------------------------------ the test button
    enum class Check { OK, WRONG_KEY, NOT_SERVER, UNREACHABLE, CLEARTEXT, NO_ADDRESS }

    private val checker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Does the server answer, and does it take the key? [done] on the main thread, with the server's words. */
    fun check(done: (Check, String) -> Unit) {
        val b = base()
        if (b == null) { done(Check.NO_ADDRESS, ""); return }
        if (cleartextBlocked()) { done(Check.CLEARTEXT, ""); return }
        checker.execute {
            val (res, info) = try {
                val h = request("GET", "$b/health", withKey = false, readMs = 10_000)
                val o = h.second.takeIf { h.first == 200 }?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (o?.optString("service") != "amri-lyrics") Check.NOT_SERVER to "HTTP ${h.first}"
                else {
                    val p = request("GET", "$b/api/ping", readMs = 10_000)
                    when (p.first) {
                        200 -> Check.OK to JSONObject(p.second).let { "${it.optString("version")} · ${it.optString("model")}" }
                        401 -> Check.WRONG_KEY to ""
                        else -> Check.NOT_SERVER to "HTTP ${p.first}"
                    }
                }
            } catch (t: Throwable) {
                Log.i(TAG, "check: $t")
                Check.UNREACHABLE to (t.javaClass.simpleName)
            }
            main.post { done(res, info) }
        }
    }

    // ------------------------------------------------------------------------------------------ lyrics
    /**
     * The server's answer: timed text (LRC) or plain, where it came from, and its id for the timing by listening.
     * [enhanced] (1.15, asked for with `words=1`): the timed text with a `<mm:ss.xx>` tag before each word, and
     * [words] "heard" or "estimated"; null from servers older than that.
     */
    class Found(val synced: String?, val plain: String?, val source: String, val id: String, val covered: Float, val duration: Double,
                val enhanced: String? = null, val words: String = "")

    /** Asks the server for a song's lyrics (blocking). Null when it has none; an exception when it could not be asked. */
    fun lyrics(title: String, artist: String, album: String, durationSec: Int): Found? {
        val b = base() ?: return null
        val q = "title=${enc(title)}&artist=${enc(artist)}" + (if (album.isNotBlank()) "&album=${enc(album)}" else "") +
            (if (durationSec > 0) "&duration=$durationSec" else "") + "&words=1"
        val (code, body) = request("GET", "$b/api/lyrics?$q", readMs = 45_000)
        if (code != 200) throw IllegalStateException("lyrics server: HTTP $code")
        val o = JSONObject(body)
        val synced = o.optString("synced").takeIf { !o.isNull("synced") && it.isNotBlank() }
        val plain = o.optString("plain").takeIf { !o.isNull("plain") && it.isNotBlank() }
        Log.d(TAG, "lyrics '$title': ${o.optString("source")}${if (o.optBoolean("cached")) " (kept)" else ""}")
        if (synced == null && plain == null) return null
        val dur = o.optJSONObject("match")?.optDouble("duration", 0.0) ?: 0.0
        return Found(synced, plain, o.optString("source"), o.optString("id"), o.optDouble("covered", 1.0).toFloat(), if (dur.isNaN()) 0.0 else dur,
            enhanced(o), o.optString("words"))
    }

    /** The "enhanced" field (word times), when there is one. */
    private fun enhanced(o: JSONObject): String? = o.optString("enhanced").takeIf { !o.isNull("enhanced") && it.isNotBlank() }

    // ------------------------------------------------------------------------------------------ timing by listening
    /**
     * How far the server got with a song: a time per line (ms, null: none yet), the share of lines timed, and (1.15,
     * the state asked for with `words=1`) the words' times as enhanced LRC ([enhanced], [words] as in [Found]).
     * 1.16: [mode] "timed" when the server kept the line times it was given (only the words are worked out; [covered]
     * is then the share of the sung lines whose words have real times), "untimed" or "" (an older server) otherwise;
     * [heard] the words written down for the song so far (-1: not said); [diag] the last piece's numbers.
     */
    class State(val id: String, val times: List<Long?>?, val covered: Float, val show: Boolean, val done: Boolean, val pending: Int,
                val enhanced: String? = null, val words: String = "", val mode: String = "", val heard: Int = -1, val diag: Diag? = null)

    /**
     * What the server made of the last piece it worked on (1.16, for the log and Settings): its level ([rms], [peak]; the
     * separated singing [vocal]; dBFS, NaN when not said), the words written down ([asr]), the lines given words
     * ([aligned]), and how it was heard ([src]: "capture" or "mic").
     */
    class Diag(val rms: Double, val peak: Double, val vocal: Double, val asr: Int, val aligned: Int, val src: String) {
        override fun toString(): String = "rms %.1f, peak %.1f, singing %.1f dBFS; %d words written down, %d lines given words (%s)"
            .format(java.util.Locale.ROOT, rms, peak, vocal, asr, aligned, src.ifEmpty { "?" })
    }

    /**
     * The song and the lines the app shows (the server times exactly these). [times] (1.16): the lines' times as shown,
     * on the song's clock (ms, null for a line that has none there): the server keeps them and works out only the
     * words, each line's around its own time ("timed"); without them it times the lines too ("untimed").
     */
    fun syncStart(title: String, artist: String, durationSec: Int, lines: List<String>, times: List<Long?>? = null): State {
        val b = base() ?: throw IllegalStateException("no server")
        val o = JSONObject().put("title", title).put("artist", artist).put("duration", durationSec).put("lines", JSONArray(lines))
        if (times != null) o.put("times", JSONArray().apply { for (t in times) put(t ?: JSONObject.NULL) })
        return state(request("POST", "$b/api/sync/start", body = o.toString().toByteArray(Charsets.UTF_8), type = "application/json"))
    }

    /**
     * What the car heard from [posMs] of the song on: 16-bit mono PCM at [rate]; [src] how it was heard ("capture": the
     * car's own sound, "mic": the microphone), for the server's diagnostics only.
     */
    fun syncChunk(id: String, posMs: Long, pcm: ShortArray, count: Int, rate: Int, src: String): State {
        val b = base() ?: throw IllegalStateException("no server")
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) { val v = pcm[i].toInt(); bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte() }
        return state(request("POST", "$b/api/sync/chunk?id=${enc(id)}&pos=$posMs&rate=$rate&src=${enc(src)}", body = bytes,
            type = "application/octet-stream", readMs = 30_000))
    }

    /** The song's own file from the unit (the server decodes it, times the lines, and deletes it). */
    fun syncFile(id: String, name: String, length: Long, open: () -> InputStream): State {
        val b = base() ?: throw IllegalStateException("no server")
        return state(request("POST", "$b/api/sync/file?id=${enc(id)}&name=${enc(name)}", stream = open, length = length,
            type = "application/octet-stream", readMs = 90_000))
    }

    fun syncState(id: String): State {
        val b = base() ?: throw IllegalStateException("no server")
        return state(request("GET", "$b/api/sync?id=${enc(id)}&words=1"))
    }

    private fun state(r: Pair<Int, String>): State {
        if (r.first !in 200..299) throw IllegalStateException("lyrics server: HTTP ${r.first}")
        val o = JSONObject(r.second)
        val arr = o.optJSONArray("times")
        val times = if (arr == null) null else (0 until arr.length()).map { if (arr.isNull(it)) null else arr.optLong(it) }
        val d = o.optJSONObject("diag")
        val diag = if (d == null) null else Diag(d.optDouble("rms", Double.NaN), d.optDouble("peak", Double.NaN),
            d.optDouble("vocal", Double.NaN), d.optInt("asr", -1), d.optInt("aligned", -1), d.optString("src"))
        return State(o.optString("id"), times, o.optDouble("covered", 0.0).toFloat(), o.optBoolean("show"),
            o.optBoolean("done"), o.optInt("pending"), enhanced(o), o.optString("words"), o.optString("mode"),
            if (o.has("heard") && !o.isNull("heard")) o.optInt("heard", -1) else -1, diag)
    }

    // ------------------------------------------------------------------------------------------ HTTP
    /**
     * One request on a connection of its own (a kept one went stale when the PC's server restarted: "unexpected end of
     * stream"); a question (GET) that fails that way is asked once more.
     */
    private fun request(
        method: String, url: String, withKey: Boolean = true, body: ByteArray? = null, type: String? = null,
        stream: (() -> InputStream)? = null, length: Long = -1, readMs: Int = 20_000
    ): Pair<Int, String> = try {
        once(method, url, withKey, body, type, stream, length, readMs)
    } catch (e: java.io.IOException) {
        if (method != "GET" || e is java.net.SocketTimeoutException) throw e
        Log.i(TAG, "$e: once more")
        once(method, url, withKey, body, type, stream, length, readMs)
    }

    private fun once(
        method: String, url: String, withKey: Boolean, body: ByteArray?, type: String?,
        stream: (() -> InputStream)?, length: Long, readMs: Int
    ): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = readMs
        c.requestMethod = method
        c.setRequestProperty("User-Agent", Places.USER_AGENT)
        c.setRequestProperty("Connection", "close")
        if (withKey) c.setRequestProperty("X-Lyrics-Key", key)
        try {
            if (body != null || stream != null) {
                c.doOutput = true
                if (type != null) c.setRequestProperty("Content-Type", type)
                if (body != null) c.setFixedLengthStreamingMode(body.size)
                else if (length >= 0) c.setFixedLengthStreamingMode(length)
                c.outputStream.use { out ->
                    if (body != null) out.write(body)
                    else stream!!().use { it.copyTo(out, 64 * 1024) }
                }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            return code to text
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")
}
