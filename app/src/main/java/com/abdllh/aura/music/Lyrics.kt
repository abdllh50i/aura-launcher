package com.abdllh.aura.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.abdllh.aura.nav.Places
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * A song's lyrics, timed line by line when they can be, from LRCLIB (lrclib.net: a free, open lyrics database, no key,
 * many Arabic songs among them): looked up by title, artist and length, kept on the unit (what was found, and for a
 * while that nothing was). A busy or unreachable server is not "nothing": the next play asks again.
 *
 * Since 1.13 the owner's own server ([LyricsServer], when one is set) comes after LRCLIB: the order is what is kept
 * here, LRCLIB, then the server (timed lyrics first, else plain), then nothing. LRCLIB's plain lyrics show at once and
 * the server is asked meanwhile for timed ones; when they come (found somewhere else, or made by listening:
 * [LyricsSync]) they are kept in the song's place here and handed to the screen ([onTimed]), which then times the
 * lines it already shows.
 */
object Lyrics {
    private const val TAG = "AuraLyrics"
    private const val API = "https://lrclib.net/api"
    private const val RETRY_MISSING_MS = 3 * 24 * 3600_000L // a song nobody had added: asked again after 3 days

    /** A line and the time it is sung at (0 for all of them when the lyrics are not timed). */
    class Line(val ms: Long, val text: String)

    /**
     * [synced]: the lines carry times. [instrumental]: a song without words. [durationSec]: the length of the recording
     * the lines were timed on (0 when unknown). [source]: "lrclib", one of the owner's server's sources, or "aligned"
     * (timed by listening), whose [covered] says how much of the song was heard (1 for any other source).
     */
    class Result(
        val synced: Boolean, val lines: List<Line>, val instrumental: Boolean = false, val durationSec: Int = 0,
        val source: String = SOURCE_LRCLIB, val covered: Float = 1f
    ) {
        /** Worth asking the owner's server about: untimed lines, or a timing by listening that is not complete yet. */
        val improvable: Boolean get() = !instrumental && lines.isNotEmpty() && (!synced || (source == SOURCE_ALIGNED && covered < LyricsSync.DONE_AT))
    }

    const val SOURCE_LRCLIB = "lrclib"
    const val SOURCE_ALIGNED = "aligned"

    private val io = Executors.newSingleThreadExecutor()
    /** The owner's server's later questions, apart: a slow or absent server must not hold the next song's lookup. */
    private val serverIo = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Timed lines for a song whose untimed (or partly timed) lines were already handed out, on the main thread: the
     * song as it was asked for (title, artist, length) and the new lyrics. Set by the music screen while it is up.
     */
    var onTimed: ((title: String, artist: String, durationSec: Int, Result) -> Unit)? = null

    /**
     * The lyrics of a song ([durationSec] 0 when unknown), then [done] on the main thread: null when there are none,
     * [failed] true when the server could not be asked (no internet, busy).
     */
    fun get(c: Context, title: String, artist: String, durationSec: Int, album: String = "", done: (Result?, failed: Boolean) -> Unit) {
        val app = c.applicationContext
        if (title.isBlank()) { done(null, false); return }
        val key = key(title, artist, durationSec)
        wanted = key
        io.execute {
            // a song skipped while this waited in line: not looked up any more (each lookup can take long)
            if (wanted != key) return@execute
            val f = File(dir(app), "$key.json")
            val kept = read(f)
            val server = LyricsServer.configured
            if (kept?.result != null) {
                // what was found is kept (and the server may have better by now: timed lines for untimed ones)
                val r = kept.result
                main.post { done(r, false) }
                if (server && r.improvable) serverIo.execute { better(f, title, artist, durationSec, album, r) }
                return@execute
            }
            // "nothing" is kept for a while (somebody may add the song): LRCLIB's, and the server's when it was asked
            val noneLately = kept != null && System.currentTimeMillis() - f.lastModified() < RETRY_MISSING_MS
            if (noneLately && (!server || kept!!.serverAt > 0L)) { main.post { done(null, false) }; return@execute }
            var lrclibFailed = false
            if (!noneLately) {
                val json = try {
                    fetch(title, artist, durationSec)
                } catch (t: Throwable) {
                    Log.i(TAG, "lyrics for '$title': $t")
                    lrclibFailed = true
                    null
                }
                val r = json?.let { parse(it) }
                if (json != null && r != null) {
                    write(f, json)
                    main.post { done(r, false) }
                    if (server && r.improvable) serverIo.execute { better(f, title, artist, durationSec, album, r) }
                    return@execute
                }
                // (a "none" only when every question got its answer)
                if (!lrclibFailed) write(f, JSONObject().put("none", true))
            }
            if (!server || wanted != key) { main.post { done(null, lrclibFailed) }; return@execute }
            // LRCLIB has nothing: the owner's server (timed first, else plain)
            val found = try {
                LyricsServer.lyrics(title, artist, album, durationSec)
            } catch (t: Throwable) {
                Log.i(TAG, "lyrics server for '$title': $t")
                main.post { done(null, lrclibFailed) } // (LRCLIB's "none" stays kept: the server is asked again next time)
                return@execute
            }
            val record = found?.let { serverJson(it) }
            val r = record?.let { parse(it) }
            if (record != null && r != null) write(f, record)
            else if (!lrclibFailed) write(f, JSONObject().put("none", true).put("server", System.currentTimeMillis()))
            main.post { done(r, r == null && lrclibFailed) }
        }
    }

    /**
     * The owner's server, about lyrics already handed out: timed lines for untimed ones (found by one of its sources,
     * or made by listening), or more of a timing by listening. Kept, and given to the screen ([onTimed]).
     */
    private fun better(f: File, title: String, artist: String, durationSec: Int, album: String, shown: Result) {
        val found = try { LyricsServer.lyrics(title, artist, album, durationSec) } catch (t: Throwable) { Log.i(TAG, "lyrics server: $t"); return } ?: return
        if (found.synced == null) return
        val r = parse(serverJson(found)) ?: return
        if (!r.synced || (shown.synced && r.covered <= shown.covered + 0.001f)) return
        write(f, serverJson(found))
        main.post { onTimed?.invoke(title, artist, durationSec, r) }
    }

    /**
     * A timing made by listening ([LyricsSync]) kept as the song's lyrics: the next play is timed at once. [lines] are
     * the lines shown with their times; [covered], how much of the song was heard.
     */
    fun keepAligned(c: Context, title: String, artist: String, durationSec: Int, lines: List<Line>, covered: Float) {
        val f = File(dir(c.applicationContext), "${key(title, artist, durationSec)}.json")
        val lrc = lines.joinToString("\n") { l -> "[%02d:%05.2f]%s".format(java.util.Locale.ROOT, l.ms / 60_000, (l.ms % 60_000) / 1000.0, l.text) }
        val o = JSONObject().put("syncedLyrics", lrc).put("plainLyrics", lines.joinToString("\n") { it.text })
            .put("source", SOURCE_ALIGNED).put("covered", covered.toDouble()).put("server", System.currentTimeMillis())
        io.execute { write(f, o) }
    }

    /** The server's answer in the shape of an LRCLIB record (the one way lyrics are kept here). */
    private fun serverJson(s: LyricsServer.Found): JSONObject = JSONObject().apply {
        if (s.synced != null) put("syncedLyrics", s.synced)
        if (s.plain != null) put("plainLyrics", s.plain)
        if (s.duration > 0) put("duration", s.duration)
        put("source", s.source)
        put("covered", s.covered.toDouble())
        put("server", System.currentTimeMillis())
    }

    private fun write(f: File, o: JSONObject) {
        try {
            f.parentFile?.mkdirs()
            f.writeText(o.toString())
        } catch (_: Throwable) { }
    }

    /** What is kept for a song: its lyrics, or none ([result] null); [serverAt]: when the owner's server was asked. */
    private class Kept(val result: Result?, val serverAt: Long)

    @Volatile private var wanted: String? = null // the song asked for last

    /**
     * LRCLIB's record of the song, or null when it has none (an exception: a question went unanswered). The server
     * is often busy (503), each kind of question on its own: so a busy answer is asked once more, then the next kind.
     * No album: a file's "album" is often its folder's name, and the exact match then misses.
     */
    private fun fetch(title: String, artist: String, durationSec: Int): JSONObject? {
        // "not there" is believed only when every question got its answer (a busy one may have had the song)
        var unanswered = false
        fun ask(url: String): Pair<Int, String>? = try {
            var r = http(url)
            if (r.first == 503 || r.first == 429) { Thread.sleep(1500); r = http(url) }
            if (r.first !in 200..299 && r.first != 404) unanswered = true
            r
        } catch (t: Throwable) {
            Log.i(TAG, "lyrics: $t")
            unanswered = true
            null
        }
        // the exact match first (it wants the artist and the length)
        if (artist.isNotBlank() && durationSec > 0) {
            val r = ask("$API/get?track_name=${enc(title)}&artist_name=${enc(artist)}&duration=$durationSec")
            if (r?.first == 200) JSONObject(r.second).takeIf { hasWords(it) }?.let { return it }
        }
        // then searches: free text (the lightest for the server), then by field
        val searches = listOfNotNull("q=${enc("$title $artist".trim())}",
            if (artist.isNotBlank()) "track_name=${enc(title)}&artist_name=${enc(artist)}" else null)
        for (q in searches) {
            val r = ask("$API/search?$q") ?: continue
            if (r.first != 200) continue
            best(JSONArray(r.second), title, artist, durationSec)?.let { return it }
        }
        if (unanswered) throw IllegalStateException("LRCLIB did not answer every question")
        return null
    }

    /**
     * The search result for this very song (its name, its artist when known, its length): timed lyrics first. With no
     * length to go by (a Bluetooth phone tells none), the recording most entries agree on: a song is usually there
     * many times, mostly as its album recording, now and then as a video or live one with another length (whose
     * timing would be off by its intro).
     */
    private fun best(all: JSONArray, title: String, artist: String, durationSec: Int): JSONObject? {
        val t = norm(title)
        val a = norm(artist)
        val found = ArrayList<JSONObject>()
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i) ?: continue
            if (!hasWords(o)) continue
            val ot = norm(o.optString("trackName"))
            if (ot.isEmpty() || !(ot.contains(t) || t.contains(ot))) continue // another song
            val oa = norm(o.optString("artistName"))
            if (a.isNotEmpty() && oa.isNotEmpty() && !(oa.contains(a) || a.contains(oa))) continue
            val d = o.optDouble("duration", 0.0)
            if (durationSec > 0 && d > 0 && abs(d - durationSec) > 8) continue // another recording of it
            found.add(o)
        }
        val want = if (durationSec > 0) durationSec.toDouble() else agreedLength(found)
        return found.minByOrNull { o ->
            val d = o.optDouble("duration", 0.0)
            (if (o.optString("syncedLyrics").isNotBlank()) 0.0 else 100.0) + (if (want > 0 && d > 0) abs(d - want) else 50.0)
        }
    }

    /** The length most of the timed entries share (within 3 s of each other), 0 when none has one. */
    private fun agreedLength(found: List<JSONObject>): Double {
        val lengths = found.filter { it.optString("syncedLyrics").isNotBlank() }.map { it.optDouble("duration", 0.0) }.filter { it > 0 }
        return lengths.maxByOrNull { d -> lengths.count { abs(it - d) <= 3.0 } } ?: 0.0
    }

    /** For comparing names: lower case, Arabic letters in one form (أ إ آ → ا, ة → ه, ى → ي), no marks or punctuation. */
    private fun norm(s: String): String {
        val b = StringBuilder()
        for (ch in s.lowercase()) {
            when {
                ch in 'ً'..'ْ' || ch == 'ـ' -> {}
                ch == 'أ' || ch == 'إ' || ch == 'آ' -> b.append('ا')
                ch == 'ة' -> b.append('ه')
                ch == 'ى' -> b.append('ي')
                ch.isLetterOrDigit() -> b.append(ch)
            }
        }
        return b.toString()
    }

    private fun hasWords(o: JSONObject) =
        o.optBoolean("instrumental") || o.optString("syncedLyrics").isNotBlank() || o.optString("plainLyrics").isNotBlank()

    private fun parse(o: JSONObject): Result? {
        if (o.optBoolean("none")) return null
        val sec = o.optDouble("duration", 0.0).let { if (it.isNaN()) 0 else it.toInt() }
        // (kept before 1.13: LRCLIB's record as it came, no source)
        val source = o.optString("source").ifBlank { SOURCE_LRCLIB }
        val covered = o.optDouble("covered", 1.0).let { if (it.isNaN()) 1f else it.toFloat() }
        if (o.optBoolean("instrumental")) return Result(false, emptyList(), instrumental = true, durationSec = sec, source = source)
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Result(true, lines, durationSec = sec, source = source, covered = covered)
        }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return Result(false, plain.lines().map { Line(0L, it.trim()) }, durationSec = sec, source = source, covered = 0f)
    }

    private val TAG_RE = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** "[01:02.34] words" (several times on one line allowed); metadata tags ([ar:], [offset:]...) are left out. */
    fun parseLrc(text: String): List<Line> {
        val out = ArrayList<Line>()
        var offset = 0L
        for (raw in text.lines()) {
            val line = raw.trim()
            Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE).find(line)?.let { offset = it.groupValues[1].toLongOrNull() ?: 0L }
            val times = TAG_RE.findAll(line).toList()
            if (times.isEmpty()) continue
            val words = line.substring(times.last().range.last + 1).trim()
            for (m in times) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out.add(Line((min * 60_000 + sec * 1000 + ms - offset).coerceAtLeast(0L), words))
            }
        }
        out.sortBy { it.ms }
        return out
    }

    /** What was kept for the song (its result may be "none"), or null when nothing was. */
    private fun read(f: File): Kept? = try {
        if (!f.isFile) null else JSONObject(f.readText()).let { Kept(parse(it), it.optLong("server", 0L)) }
    } catch (_: Throwable) {
        null
    }

    private fun http(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", Places.USER_AGENT)
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            Log.d(TAG, "$code ${url.substringAfter("/api/").take(60)}")
            return code to body
        } finally {
            c.disconnect()
        }
    }

    // (a space as %20: URLEncoder writes "+", which LRCLIB takes as a plus, and then knows no "هلا+باللي")
    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")

    private fun key(title: String, artist: String, durationSec: Int): String {
        // (|2: picked by the agreed length since 1.6: what 1.5.4 kept may be a video's timing)
        val s = "${title.trim().lowercase()}|${artist.trim().lowercase()}|${durationSec / 3}|2"
        return MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun dir(c: Context) = File(c.cacheDir, "lyrics")
}
