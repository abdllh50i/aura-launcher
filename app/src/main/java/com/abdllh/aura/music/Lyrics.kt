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

    /** One sub-word syllable timing (ms) inside a word-timed lyric line (Apple Music TTML / LyricsPlus). */
    class Syllable(val startMs: Long, val endMs: Long, val text: String)

    /**
     * A line and the time it is sung at (0 for all of them when the lyrics are not timed). [words] (1.15): when each of
     * its words starts, and where the last one ends (-1: not given), as a real word-timed source or the owner's server
     * gave them; null when none were given. Since 1.16 these are only ever real times: a line without them is lit whole,
     * never word by word on a guess. [ends] (1.16): where each word's singing ends when a pause follows it (-1: it runs
     * on to the next word), so the light waits through the pause. [endMs] and [syllables] (2.0-beta.6): explicit line end
     * and per-word syllable timings from Apple Music TTML / LyricsPlus so held notes stretch without drifting ahead.
     */
    class Line(
        val ms: Long,
        val text: String,
        val words: LongArray? = null,
        val ends: LongArray? = null,
        val endMs: Long = -1L,
        val syllables: Array<Array<Syllable>?>? = null
    )

    /**
     * [synced]: the lines carry times. [instrumental]: a song without words. [durationSec]: the length of the recording
     * the lines were timed on (0 when unknown). [source]: "lrclib", one of the owner's server's sources, or "aligned"
     * (timed by listening), whose [covered] says how much of the song was heard (1 for any other source).
     * [words] (1.15): "heard" or "estimated" when the server gave word times, else "". [offsetMs]: the owner's own
     * correction of this timing (a Bluetooth song's line tapped while its clock was known), kept with these lines only.
     */
    class Result(
        val synced: Boolean, val lines: List<Line>, val instrumental: Boolean = false, val durationSec: Int = 0,
        val source: String = SOURCE_LRCLIB, val covered: Float = 1f, val words: String = "", val offsetMs: Long = 0L
    ) {
        /** Worth asking the owner's server about: untimed lines, or a timing by listening that is not complete yet. */
        val improvable: Boolean get() = !instrumental && lines.isNotEmpty() && (!synced || (source == SOURCE_ALIGNED && covered < LyricsSync.DONE_AT))

        /** The share of the lines with words that carry their words' real times (1.16; 0 for untimed lyrics). */
        val wordsCovered: Float get() {
            if (!synced) return 0f
            var sung = 0
            var known = 0
            for (l in lines) if (l.text.isNotBlank()) { sung++; if (l.words != null) known++ }
            return if (sung == 0) 0f else known.toFloat() / sung
        }

        /** Timed lines whose words are not (nearly all) known: worth asking the server, or listening, for them (1.16). */
        val wantsWords: Boolean get() = synced && !instrumental && lines.isNotEmpty() && wordsCovered < WORDS_DONE
    }

    /** The share of a song's lines with real word times from which nothing more is asked or listened for. */
    const val WORDS_DONE = 0.9f

    const val SOURCE_LRCLIB = "lrclib"
    const val SOURCE_ALIGNED = "aligned"
    /** 1.19: the lines Naghm found on the phone, sent over its link ([PhoneLink.lyrics]). */
    const val SOURCE_NAGHM = "naghm"
    /** 2.0-beta.6: real word-/syllable-synced lyrics from Apple Music / LyricsPlus / BetterLyrics TTML. */
    const val SOURCE_APPLE = "apple"
    const val SOURCE_LYRICSPLUS = "lyricsplus"
    const val SOURCE_TTML = "ttml"

    private val LYRICSPLUS_HOSTS = arrayOf(
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.atomix.one"
    )
    private val BETTERLYRICS_ENDPOINTS = arrayOf(
        "https://lyrics-api.boidu.dev/getLyrics",
        "https://api.betterlyrics.org/getLyrics"
    )

    private val io = Executors.newSingleThreadExecutor()
    /** The owner's server's later questions, apart: a slow or absent server must not hold the next song's lookup. */
    private val serverIo = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }

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
                // what was found is kept (and the server may have better by now: timed lines for untimed ones, or
                // the words' real times)
                val r = kept.result
                main.post { done(r, false) }
                if (server && asks(r)) serverIo.execute { better(f, title, artist, durationSec, album, r) }
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
                    if (server && asks(r)) serverIo.execute { better(f, title, artist, durationSec, album, r) }
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
        val record = serverJson(found)
        var r = parse(record) ?: return
        if (!r.synced) return
        val moreTimed = !shown.synced || r.covered > shown.covered + 0.001f
        // (1.16: the words' real times — another source's lyrics then replace these, as better timed ones do)
        val moreWords = r.wordsCovered > shown.wordsCovered + 0.05f
        if (!moreTimed && !moreWords) return
        // the very lines shown, now with their words: the owner's correction of their timing stays with them
        if (shown.offsetMs != 0L && sameLines(shown.lines, r.lines)) {
            record.put("offsetMs", shown.offsetMs)
            r = parse(record) ?: return
        }
        write(f, record)
        main.post { onTimed?.invoke(title, artist, durationSec, r) }
    }

    /**
     * Worth asking the owner's server about: untimed or incompletely heard lines ([Result.improvable]), or timed ones
     * whose words' times are not known while the word-by-word light is on (1.16).
     */
    private fun asks(r: Result) = r.improvable || (r.wantsWords && Words.on)

    /** The same lines (text, and time within a second: word times may light a line a little earlier). */
    fun sameLines(a: List<Line>, b: List<Line>) =
        a.size == b.size && a.indices.all { Words.sameText(a[it].text, b[it].text) && abs(a[it].ms - b[it].ms) <= 2000L }

    /**
     * A timing made by listening ([LyricsSync]) kept as the song's lyrics: the next play is timed at once. [lines] are
     * the lines shown with their times; [covered], how much of the song was heard.
     */
    fun keepAligned(c: Context, title: String, artist: String, durationSec: Int, lines: List<Line>, covered: Float,
                    enhanced: String? = null, words: String = "") {
        val f = File(dir(c.applicationContext), "${key(title, artist, durationSec)}.json")
        val lrc = lines.joinToString("\n") { l -> "[%02d:%05.2f]%s".format(java.util.Locale.ROOT, l.ms / 60_000, (l.ms % 60_000) / 1000.0, l.text) }
        val o = JSONObject().put("syncedLyrics", lrc).put("plainLyrics", lines.joinToString("\n") { it.text })
            .put("source", SOURCE_ALIGNED).put("covered", covered.toDouble()).put("server", System.currentTimeMillis())
        if (enhanced != null) o.put("enhanced", enhanced).put("words", words)
        // (a new timing: the owner's correction of the one before, if any, goes with it — the record is replaced)
        io.execute { write(f, o) }
    }

    /**
     * The owner's correction of a song's timing (1.15: lyrics time minus the song's clock, ms; 0 removes it), kept in
     * the song's record with the very lines it was made on: lyrics that replace them (another source, a new timing)
     * are written as a new record without it. Nothing is written when the record no longer holds those lines.
     */
    fun keepOffset(c: Context, title: String, artist: String, durationSec: Int, offsetMs: Long, lines: List<Line>) {
        val f = File(dir(c.applicationContext), "${key(title, artist, durationSec)}.json")
        io.execute {
            try {
                if (!f.isFile) return@execute
                val o = JSONObject(f.readText())
                val kept = parse(o) ?: return@execute
                if (!kept.synced || kept.lines.size != lines.size ||
                    kept.lines.indices.any { kept.lines[it].ms != lines[it].ms || kept.lines[it].text != lines[it].text }) {
                    Log.i(TAG, "timing correction for '$title' not kept: its lyrics changed")
                    return@execute
                }
                if (offsetMs == 0L) o.remove("offsetMs") else o.put("offsetMs", offsetMs)
                write(f, o)
            } catch (t: Throwable) {
                Log.i(TAG, "timing correction: $t")
            }
        }
    }

    /**
     * The owner's "no lyrics for this song" (the More sheet), kept by the song's key as its timing correction is. In a file of its own: a new record of the song, or an emptied cache, must not bring
     * them back, and the four old preference files take no new keys ([com.abdllh.aura.system.PrefTypes]).
     */
    fun isOff(c: Context, title: String, artist: String, durationSec: Int) = offFile(c).contains(offKey(title, artist, durationSec))

    fun setOff(c: Context, title: String, artist: String, durationSec: Int, off: Boolean) {
        val k = offKey(title, artist, durationSec)
        offFile(c).edit().apply { if (off) putBoolean(k, true) else remove(k) }.apply()
    }

    private fun offFile(c: Context) = c.applicationContext.getSharedPreferences("aura_lyrics_off", Context.MODE_PRIVATE)

    /** The server's answer in the shape of an LRCLIB record (the one way lyrics are kept here). */
    private fun serverJson(s: LyricsServer.Found): JSONObject = JSONObject().apply {
        if (s.synced != null) put("syncedLyrics", s.synced)
        if (s.plain != null) put("plainLyrics", s.plain)
        if (s.duration > 0) put("duration", s.duration)
        // (1.15: the words' times, when the server gave them, and whether they were heard or estimated)
        if (s.enhanced != null) { put("enhanced", s.enhanced); put("words", s.words) }
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
     * Looks up lyrics with real word/syllable timings first (LyricsPlus Apple Music / Musixmatch, then BetterLyrics TTML),
     * falling back to LRCLIB (exact match, cleaned searches, raw fallback) and finally any line-synced LyricsPlus result.
     * Null when every asked provider confirmed nothing; throws when a provider went unanswered without a valid result.
     */
    private fun fetch(title: String, artist: String, durationSec: Int): JSONObject? {
        val (cleanTitle, cleanArtist) = LyricsEngine.splitArtistTitle(title, artist)
        var fallbackSynced: LyricsEngine.ParsedLyrics? = null

        // 1. Real word-/syllable-synced lyrics: LyricsPlus (Apple Music, LyricsPlus, Musixmatch, Spotify)
        val lp = fetchLyricsPlus(cleanTitle, cleanArtist, durationSec)
        if (lp != null) {
            if (lp.wordSynced) return richJson(lp, durationSec)
            if (lp.synced) fallbackSynced = lp
        }

        // 2. Real word-/syllable-synced lyrics: BetterLyrics TTML (Apple Music TTML)
        val bl = fetchBetterLyrics(cleanTitle, cleanArtist, durationSec)
        if (bl != null) {
            if (bl.wordSynced) return richJson(bl, durationSec)
            if (fallbackSynced == null && bl.synced) fallbackSynced = bl
        }

        // 3. LRCLIB (line-synced and plain database)
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

        var plainFallback: JSONObject? = null
        fun considerLrclib(o: JSONObject?): JSONObject? {
            if (o == null || !hasWords(o)) return null
            if (o.optBoolean("instrumental") || o.optString("syncedLyrics").isNotBlank()) return o
            if (plainFallback == null) plainFallback = o
            return null
        }

        // Exact match first (cleaned, then raw if different)
        if (cleanArtist.isNotBlank() && durationSec > 0) {
            val r = ask("$API/get?track_name=${enc(cleanTitle)}&artist_name=${enc(cleanArtist)}&duration=$durationSec")
            if (r?.first == 200) considerLrclib(JSONObject(r.second))?.let { return it }
        }
        if (artist.isNotBlank() && durationSec > 0 && (cleanTitle != title.trim() || cleanArtist != artist.trim())) {
            val r = ask("$API/get?track_name=${enc(title)}&artist_name=${enc(artist)}&duration=$durationSec")
            if (r?.first == 200) considerLrclib(JSONObject(r.second))?.let { return it }
        }

        // Searches: cleaned free text, cleaned field query, then raw free text if cleaned changed it
        val searches = LinkedHashSet<String>()
        searches.add("q=${enc("$cleanTitle $cleanArtist".trim())}")
        if (cleanArtist.isNotBlank()) searches.add("track_name=${enc(cleanTitle)}&artist_name=${enc(cleanArtist)}")
        if (cleanTitle != title.trim() || cleanArtist != artist.trim()) searches.add("q=${enc("$title $artist".trim())}")
        for (q in searches) {
            val r = ask("$API/search?$q") ?: continue
            if (r.first != 200) continue
            considerLrclib(best(JSONArray(r.second), cleanTitle, cleanArtist, durationSec))?.let { return it }
        }

        if (fallbackSynced != null) return richJson(fallbackSynced, durationSec)
        if (plainFallback != null) return plainFallback
        if (unanswered) throw IllegalStateException("LRCLIB did not answer every question")
        return null
    }

    /** Queries LyricsPlus mirrors for Apple Music / Musixmatch syllable-synced lyrics. */
    private fun fetchLyricsPlus(title: String, artist: String, durationSec: Int): LyricsEngine.ParsedLyrics? {
        if (title.isBlank()) return null
        val baseQuery = "title=${enc(title)}&artist=${enc(artist)}&source=apple,lyricsplus,musixmatch,spotify"
        for (host in LYRICSPLUS_HOSTS) {
            val urlWithDur = if (durationSec > 0) "$host/v2/lyrics/get?$baseQuery&duration=$durationSec" else "$host/v2/lyrics/get?$baseQuery"
            try {
                var r = http(urlWithDur, connectMs = 5000, readMs = 7000)
                if (r.first == 404 && durationSec > 0) {
                    r = http("$host/v2/lyrics/get?$baseQuery", connectMs = 5000, readMs = 7000)
                }
                if (r.first == 200 && r.second.isNotBlank()) {
                    LyricsEngine.parseLyricsPlus(r.second)?.let {
                        Log.d(TAG, "LyricsPlus (${it.source}, words=${it.wordSynced}) for '$title'")
                        return it
                    }
                    return null
                }
                // 404 means the catalog has no match; mirrors back the same catalog so skip remaining mirrors
                if (r.first == 404) return null
            } catch (t: Throwable) {
                Log.d(TAG, "LyricsPlus $host for '$title': $t")
            }
        }
        return null
    }

    /** Queries BetterLyrics TTML endpoints for Apple Music word-/syllable-synced TTML lyrics. */
    private fun fetchBetterLyrics(title: String, artist: String, durationSec: Int): LyricsEngine.ParsedLyrics? {
        if (title.isBlank()) return null
        val baseQuery = "s=${enc(title)}&a=${enc(artist)}"
        for (endpoint in BETTERLYRICS_ENDPOINTS) {
            val urlWithDur = if (durationSec > 0) "$endpoint?$baseQuery&d=$durationSec" else "$endpoint?$baseQuery"
            try {
                var r = http(urlWithDur, connectMs = 5000, readMs = 7000)
                if (r.first == 404 && durationSec > 0) {
                    r = http("$endpoint?$baseQuery", connectMs = 5000, readMs = 7000)
                }
                if (r.first == 200 && r.second.isNotBlank()) {
                    LyricsEngine.parseBetterLyrics(r.second)?.let {
                        Log.d(TAG, "BetterLyrics TTML (words=${it.wordSynced}) for '$title'")
                        return it
                    }
                    return null
                }
                if (r.first == 404) return null
            } catch (t: Throwable) {
                Log.d(TAG, "BetterLyrics $endpoint for '$title': $t")
            }
        }
        return null
    }

    /** Serializes real word-/syllable-synced [LyricsEngine.ParsedLyrics] into our on-disk cache JSON format. */
    private fun richJson(parsed: LyricsEngine.ParsedLyrics, durationSec: Int): JSONObject {
        val lines = LyricsEngine.toLines(parsed)
        val arr = JSONArray()
        val lrc = StringBuilder()
        val plain = StringBuilder()
        for (l in lines) {
            val item = JSONObject().put("ms", l.ms).put("text", l.text)
            if (l.endMs > 0L) item.put("endMs", l.endMs)
            l.words?.let { w ->
                val wa = JSONArray()
                for (t in w) wa.put(t)
                item.put("words", wa)
            }
            l.ends?.let { e ->
                val ea = JSONArray()
                for (t in e) ea.put(t)
                item.put("ends", ea)
            }
            l.syllables?.let { allSyls ->
                val sa = JSONArray()
                for (wordSyls in allSyls) {
                    if (wordSyls == null) { sa.put(JSONObject.NULL); continue }
                    val wsa = JSONArray()
                    for (s in wordSyls) wsa.put(JSONArray().put(s.startMs).put(s.endMs).put(s.text))
                    sa.put(wsa)
                }
                item.put("syllables", sa)
            }
            arr.put(item)
            if (lrc.isNotEmpty()) lrc.append('\n')
            lrc.append("[%02d:%05.2f]%s".format(java.util.Locale.ROOT, l.ms / 60_000, (l.ms % 60_000) / 1000.0, l.text))
            if (plain.isNotEmpty()) plain.append('\n')
            plain.append(l.text)
        }
        return JSONObject().apply {
            put("richLines", arr)
            put("syncedLyrics", lrc.toString())
            put("plainLyrics", plain.toString())
            if (durationSec > 0) put("duration", durationSec)
            put("source", parsed.source)
            if (parsed.wordSynced) put("words", parsed.source)
            put("covered", 1.0)
        }
    }

    /**
     * The search result for this very song (its name, its artist when known, its length): timed lyrics first. With no
     * length to go by (a Bluetooth phone tells none), the recording most entries agree on: a song is usually there
     * many times, mostly as its album recording, now and then as a video or live one with another length (whose
     * timing would be off by its intro).
     */
    private fun best(all: JSONArray, title: String, artist: String, durationSec: Int): JSONObject? {
        val t = norm(LyricsEngine.cleanTitle(title))
        val a = norm(LyricsEngine.cleanArtist(artist))
        val aRtl = LyricsEngine.isRtlText(artist)
        val found = ArrayList<JSONObject>()
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i) ?: continue
            if (!hasWords(o)) continue
            val ot = norm(LyricsEngine.cleanTitle(o.optString("trackName")))
            if (ot.isEmpty() || !(ot.contains(t) || t.contains(ot))) continue // another song
            val oaRaw = o.optString("artistName")
            val oa = norm(LyricsEngine.cleanArtist(oaRaw))
            if (a.isNotEmpty() && oa.isNotEmpty() && !(oa.contains(a) || a.contains(oa))) {
                // Allow cross-script artist names (e.g. Arabic vs Latin transliteration) when title is a strong match
                val crossScript = aRtl != LyricsEngine.isRtlText(oaRaw)
                val strongTitle = ot == t || (minOf(ot.length, t.length) >= 4 && (ot.contains(t) || t.contains(ot)))
                if (!crossScript || !strongTitle) continue
            }
            val d = o.optDouble("duration", 0.0)
            if (durationSec > 0 && d > 0 && abs(d - durationSec) > 8) continue // another recording of it
            found.add(o)
        }
        val want = if (durationSec > 0) durationSec.toDouble() else agreedLength(found)
        return found.minByOrNull { o ->
            val d = o.optDouble("duration", 0.0)
            val ot = norm(LyricsEngine.cleanTitle(o.optString("trackName")))
            val titlePenalty = if (ot == t) 0.0 else 4.0
            (if (o.optString("syncedLyrics").isNotBlank()) 0.0 else 100.0) + titlePenalty + (if (want > 0 && d > 0) abs(d - want) else 50.0)
        }
    }

    /** The length most of the timed entries share (within 3 s of each other), 0 when none has one. */
    private fun agreedLength(found: List<JSONObject>): Double {
        val lengths = found.filter { it.optString("syncedLyrics").isNotBlank() }.map { it.optDouble("duration", 0.0) }.filter { it > 0 }
        return lengths.maxByOrNull { d -> lengths.count { abs(it - d) <= 3.0 } } ?: 0.0
    }

    /** For comparing names: lower case, Arabic letters in one form (أ إ آ ٱ → ا, ة → ه, ى ئ → ي, ؤ → و), no marks or punctuation. */
    private fun norm(s: String): String = LyricsEngine.normText(s)

    private fun hasWords(o: JSONObject) =
        o.optBoolean("instrumental") || o.has("richLines") || o.optString("syncedLyrics").isNotBlank() || o.optString("plainLyrics").isNotBlank()

    private fun parse(o: JSONObject): Result? {
        if (o.optBoolean("none")) return null
        val sec = o.optDouble("duration", 0.0).let { if (it.isNaN()) 0 else it.toInt() }
        // (kept before 1.13: LRCLIB's record as it came, no source)
        val source = o.optString("source").ifBlank { SOURCE_LRCLIB }
        val covered = o.optDouble("covered", 1.0).let { if (it.isNaN()) 1f else it.toFloat() }
        if (o.optBoolean("instrumental")) return Result(false, emptyList(), instrumental = true, durationSec = sec, source = source)
        val rich = o.optJSONArray("richLines")
        if (rich != null && rich.length() > 0) {
            val lines = parseRichLines(rich)
            if (lines.isNotEmpty()) {
                return Result(true, lines, durationSec = sec, source = source, covered = covered,
                    words = o.optString("words"), offsetMs = o.optLong("offsetMs", 0L))
            }
        }
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) {
            var lines = parseLrc(synced)
            // (1.15: the server's word times, given to the lines they belong to)
            val enhanced = o.optString("enhanced").takeIf { !o.isNull("enhanced") && it.isNotBlank() }
            if (enhanced != null) lines = Words.attach(lines, parseLrc(enhanced))
            if (lines.isNotEmpty()) return Result(true, lines, durationSec = sec, source = source, covered = covered,
                words = if (enhanced != null) o.optString("words") else "", offsetMs = o.optLong("offsetMs", 0L))
        }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return Result(false, plain.lines().map { Line(0L, it.trim()) }, durationSec = sec, source = source, covered = 0f)
    }

    private fun parseRichLines(arr: JSONArray): List<Line> {
        val out = ArrayList<Line>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val ms = item.optLong("ms", 0L)
            val endMs = item.optLong("endMs", -1L)
            val text = item.optString("text", "")
            val wa = item.optJSONArray("words")
            val words = wa?.let { a -> LongArray(a.length()) { idx -> a.optLong(idx, -1L) } }
            val ea = item.optJSONArray("ends")
            val ends = ea?.let { a -> LongArray(a.length()) { idx -> a.optLong(idx, -1L) } }
            val sa = item.optJSONArray("syllables")
            val syllables = sa?.let { a ->
                Array(a.length()) { idx ->
                    val wsa = a.optJSONArray(idx) ?: return@Array null
                    Array(wsa.length()) { sIdx ->
                        val tup = wsa.optJSONArray(sIdx)
                        Syllable(tup?.optLong(0, 0L) ?: 0L, tup?.optLong(1, 0L) ?: 0L, tup?.optString(2, "").orEmpty())
                    }
                }
            }
            out.add(Line(ms, text, words, ends, endMs, syllables))
        }
        return out
    }

    /**
     * "[01:02.34] words" (several times on one line allowed); metadata tags ([ar:], [offset:]...) are left out. Word
     * tags ("<01:02.34>word", enhanced LRC: the server's "enhanced" field, now and then LRCLIB's own text) become the
     * line's [Line.words] and leave its text.
     */
    fun parseLrc(text: String): List<Line> = LyricsEngine.parseLrc(text)

    /** What was kept for the song (its result may be "none"), or null when nothing was. */
    private fun read(f: File): Kept? = try {
        if (!f.isFile) null else JSONObject(f.readText()).let { Kept(parse(it), it.optLong("server", 0L)) }
    } catch (_: Throwable) {
        null
    }

    private fun http(url: String, connectMs: Int = 8000, readMs: Int = 12000): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.setRequestProperty("User-Agent", Places.USER_AGENT)
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            Log.d(TAG, "$code ${url.substringAfter("://").take(70)}")
            return code to body
        } finally {
            c.disconnect()
        }
    }

    // (a space as %20: URLEncoder writes "+", which LRCLIB takes as a plus, and then knows no "هلا+باللي")
    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")

    private fun key(title: String, artist: String, durationSec: Int): String {
        // (|3: 2.0-beta.6 upgrades cache entries to real word-/syllable-synced Apple Music TTML / LyricsPlus)
        val s = "${title.trim().lowercase()}|${artist.trim().lowercase()}|${durationSec / 3}|3"
        return MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Kept on |2 so existing "No lyrics for this song" preferences survive the lyrics cache upgrade. */
    private fun offKey(title: String, artist: String, durationSec: Int): String {
        val s = "${title.trim().lowercase()}|${artist.trim().lowercase()}|${durationSec / 3}|2"
        return MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun dir(c: Context) = File(c.cacheDir, "lyrics")
}
