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
 */
object Lyrics {
    private const val TAG = "AuraLyrics"
    private const val API = "https://lrclib.net/api"
    private const val RETRY_MISSING_MS = 3 * 24 * 3600_000L // a song nobody had added: asked again after 3 days

    /** A line and the time it is sung at (0 for all of them when the lyrics are not timed). */
    class Line(val ms: Long, val text: String)

    /** [synced]: the lines carry times. [instrumental]: a song without words. */
    class Result(val synced: Boolean, val lines: List<Line>, val instrumental: Boolean = false)

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /**
     * The lyrics of a song ([durationSec] 0 when unknown), then [done] on the main thread: null when there are none,
     * [failed] true when the server could not be asked (no internet, busy).
     */
    fun get(c: Context, title: String, artist: String, durationSec: Int, done: (Result?, failed: Boolean) -> Unit) {
        val app = c.applicationContext
        if (title.isBlank()) { done(null, false); return }
        val key = key(title, artist, durationSec)
        wanted = key
        io.execute {
            // a song skipped while this waited in line: not looked up any more (each lookup can take long)
            if (wanted != key) return@execute
            val f = File(dir(app), "$key.json")
            val kept = read(f)
            // what was found is kept; "nothing" only for a while (somebody may add the song)
            val fresh = kept != null && (kept.result != null || System.currentTimeMillis() - f.lastModified() < RETRY_MISSING_MS)
            val result = if (fresh) kept!!.result else fetchAndKeep(f, title, artist, durationSec)
            val failed = !fresh && result == null && lastFailed
            main.post { done(result, failed) }
        }
    }

    private class Kept(val result: Result?)

    @Volatile private var wanted: String? = null // the song asked for last

    private var lastFailed = false // (the io thread's)

    private fun fetchAndKeep(f: File, title: String, artist: String, durationSec: Int): Result? {
        lastFailed = false
        val json = try {
            fetch(title, artist, durationSec)
        } catch (t: Throwable) {
            Log.i(TAG, "lyrics for '$title': $t")
            lastFailed = true
            return null
        }
        val r = json?.let { parse(it) }
        try {
            f.parentFile?.mkdirs()
            f.writeText((json ?: JSONObject().put("none", true)).toString())
        } catch (_: Throwable) { }
        return r
    }

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

    /** The search result for this very song (its name, its artist when known, its length): timed lyrics first. */
    private fun best(all: JSONArray, title: String, artist: String, durationSec: Int): JSONObject? {
        val t = norm(title)
        val a = norm(artist)
        var best: JSONObject? = null
        var bestRank = Double.MAX_VALUE
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i) ?: continue
            if (!hasWords(o)) continue
            val ot = norm(o.optString("trackName"))
            if (ot.isEmpty() || !(ot.contains(t) || t.contains(ot))) continue // another song
            val oa = norm(o.optString("artistName"))
            if (a.isNotEmpty() && oa.isNotEmpty() && !(oa.contains(a) || a.contains(oa))) continue
            val d = o.optDouble("duration", 0.0)
            if (durationSec > 0 && d > 0 && abs(d - durationSec) > 8) continue // another recording of it
            val rank = (if (o.optString("syncedLyrics").isNotBlank()) 0.0 else 100.0) +
                (if (durationSec > 0 && d > 0) abs(d - durationSec) else 50.0)
            if (rank < bestRank) { bestRank = rank; best = o }
        }
        return best
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
        if (o.optBoolean("instrumental")) return Result(false, emptyList(), instrumental = true)
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Result(true, lines)
        }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return Result(false, plain.lines().map { Line(0L, it.trim()) })
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
        if (!f.isFile) null else Kept(parse(JSONObject(f.readText())))
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
        val s = "${title.trim().lowercase()}|${artist.trim().lowercase()}|${durationSec / 3}"
        return MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun dir(c: Context) = File(c.cacheDir, "lyrics")
}
