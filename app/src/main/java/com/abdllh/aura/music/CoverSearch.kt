package com.abdllh.aura.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import com.abdllh.aura.nav.Places
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.TextMatch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Album art for music that arrives without any: the unit's Bluetooth module passes a song's title and artist, never
 * its cover. The song is looked up by artist + title in public music catalogues — Apple's iTunes Search API first,
 * Deezer's search API as the fallback (both free, no account) — and the cover is kept in memory and on disk, so each
 * song is looked up once. Switchable in Settings (it sends the title and artist of the songs played over Bluetooth).
 * The catalogue's length of the song comes with it (the module does not tell it either): kept beside the cover.
 */
object CoverSearch {
    private const val TAG = "AuraCover"
    private const val SIZE = 600            // px; covers are shown at most ~300 dp
    private const val DISK_FILES = 150      // ~10 MB of JPEGs
    private const val RETRY_MISSING_MS = 6 * 3600_000L

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val mem = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashMap<String, Long>() // io thread only: songs no catalogue knows (asked again after a while)
    /** Main thread: the catalogue's length of the covers in [mem], and whether YouTube Music's catalogue gave it. */
    private val lengths = HashMap<String, Pair<Int, Boolean>>()

    /** The user's switch (on by default: without it Bluetooth songs have no picture at all). */
    var enabled: Boolean
        get() = Prefs.raw.getBoolean("btCoverOnline", true)
        set(v) { Prefs.raw.edit().putBoolean("btCoverOnline", v).apply() }

    /** A catalogue's answer: the cover's address and the song's length (0 when it gave none); [yt]: YouTube Music's. */
    private class Hit(val url: String, val sec: Int, val yt: Boolean = false)

    /** A cover with its song's length in seconds (0 unknown) and whether YouTube Music's catalogue told it. */
    /** [provisional]: another catalogue's answer while YouTube Music could not be asked: shown, not kept. */
    private class Found(val cover: Bitmap, val sec: Int, val yt: Boolean, val provisional: Boolean = false)

    /**
     * The cover of a song, or null, the song's length in seconds (0 when unknown) and whether that length is
     * YouTube Music's own (the song itself, for a phone that plays from there: believed over another catalogue's);
     * [done] runs on the main thread (at once when it is already known).
     */
    fun find(ctx: Context, title: String, artist: String, done: (Bitmap?, Int, Boolean) -> Unit) =
        find(ctx, title, artist) { b, sec, yt, _ -> done(b, sec, yt) }

    /** The same, and whether nothing came because nothing could be asked (no internet yet): worth asking again. */
    fun find(ctx: Context, title: String, artist: String, done: (Bitmap?, Int, Boolean, Boolean) -> Unit) {
        val key = "${norm(artist)}|${norm(title)}"
        if (norm(title).isEmpty()) { done(null, 0, false, false); return }
        mem.get(key)?.let { val l = lengths[key]; done(it, l?.first ?: 0, l?.second ?: false, false); return }
        val c = ctx.applicationContext
        val online = enabled
        io.execute {
            lastFailed = false
            val r = try { fromDisk(c, key) ?: if (online) fromNetwork(c, key, title, artist) else null } catch (t: Throwable) {
                Log.w(TAG, "cover for '$artist - $title': $t")
                lastFailed = true
                null
            }
            val failed = (r == null && lastFailed) || r?.provisional == true
            main.post {
                if (r != null && !r.provisional) { mem.put(key, r.cover); lengths[key] = r.sec to r.yt }
                done(r?.cover, r?.sec ?: 0, r?.yt ?: false, failed)
            }
        }
    }

    // ------------------------------------------------------------------------------------------ lookup
    private var offline = false // io thread: a catalogue could not be asked (no network...) during this lookup
    private var failure = ""    // io thread: why YouTube Music could not be asked, for the note
    @Volatile private var lastFailed = false

    /** The last lookup and how it went, for Settings → About ("" when there was none yet). */
    val note: String get() = Prefs.raw.getString("coverNote", "").orEmpty()

    private fun note(what: String) {
        Log.i(TAG, what)
        try {
            val at = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date())
            Prefs.raw.edit().putString("coverNote", "$at  $what").apply()
        } catch (_: Throwable) {
        }
    }

    private fun fromNetwork(c: Context, key: String, title: String, artist: String): Found? {
        val seen = missing[key]
        if (seen != null && System.currentTimeMillis() - seen < RETRY_MISSING_MS) return null
        val t = clean(title)
        val a = clean(artist)
        offline = false
        failure = ""
        val yt = ytmusic(title, artist)
        val hit = yt ?: itunes(t, a) ?: deezer(t, a)
        val from = when { yt != null -> "YouTube Music"; hit == null -> null; hit.url.contains("mzstatic") -> "Apple Music"; else -> "Deezer" }
        // (what the phone sent, letter for letter, where the answer came from, and why not from YouTube Music)
        val len = hit?.sec?.let { s -> String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60) }.orEmpty()
        val ytWhy = if (yt != null) "" else " (YouTube Music: ${failure.ifEmpty { "no match" }})"
        note("'$title' / '$artist' → " + (from?.let { "$it $len$ytWhy" } ?: (if (offline) "no answer" else "not found") + ytWhy))
        if (hit == null) {
            if (!offline) missing[key] = System.currentTimeMillis() // really unknown (not just no network right now)
            lastFailed = offline
            return null
        }
        val bytes = download(hit.url)
        val b = decode(bytes) ?: return null
        // (YouTube Music could not be asked: this answer is shown, and the song is asked about again later)
        val provisional = yt == null && failure.isNotEmpty()
        if (!provisional) try { save(c, key, bytes, hit.sec, hit.yt) } catch (_: Throwable) { }
        return Found(b, hit.sec, hit.yt, provisional)
    }

    /**
     * YouTube Music's catalogue first (1.10, see [YtMusic]: the search ytmusicapi makes): the owner's phone plays from
     * it, so the first song there with this name and artist is the song itself — its own cover, and its own length,
     * which the other catalogues often miss or give for another recording. The names the phone sends are that
     * catalogue's own, so they mostly match letter for letter.
     */
    private fun ytmusic(title: String, artist: String): Hit? {
        // What the phone sends is what the app it plays from shows: YouTube Music's own names, or — from the YouTube
        // app, or a video in YouTube Music — the video's title ("Artist - Song (Official Video) | 2023") with the
        // channel's name as the artist. So it is asked a few ways, best guess first, and the answers are scored
        // (across scripts) rather than matched letter for letter.
        val t = clean(title)
        val a = clean(artist)
        val parts = Regex("""\s+[-–—|]\s+""").split(t).map { clean(it) }.filter { it.isNotBlank() }
        val tries = LinkedHashSet<Pair<String, String>>() // (title, artist)
        tries.add(t to a)
        if (parts.size >= 2) { tries.add(parts[1] to parts[0]); tries.add(parts[0] to parts[1]) }
        if (a.isNotBlank()) tries.add((if (parts.size >= 2) parts[1] else t) to "") // the title alone
        for ((qt, qa) in tries.take(3)) {
            if (qt.isBlank()) continue
            val songs = try { YtMusic.find("$qa $qt".trim()) } catch (e: Throwable) {
                Log.i(TAG, "YouTube Music: $e")
                failure = e.message ?: e.javaClass.simpleName
                offline = true
                return null
            }
            val best = songs.take(10).filter { it.thumb.isNotEmpty() }.map { it to fit(qt, qa, it) }.maxByOrNull { it.second }
            if (best != null && best.second >= 0.0) return Hit(YtMusic.sized(best.first.thumb, SIZE), best.first.sec, yt = true)
        }
        return null
    }

    /** The same title, in the same script or the other (by consonants), with nothing much added or missing. */
    private fun sameTitle(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        if (TextMatch.arabic(x) != TextMatch.arabic(y)) {
            val p = TextMatch.skeleton(x)
            val q = TextMatch.skeleton(y)
            return p.length >= 3 && 1.0 - TextMatch.lev(p, q).toDouble() / maxOf(p.length, q.length) >= 0.85
        }
        return false
    }

    /** How well a found song answers (title, artist), the better the higher; negative when it does not. */
    private fun fit(title: String, artist: String, s: YtMusic.Song): Double {
        // the title alone (an artist field that is a channel's name): only the very same title — one that merely
        // contains a word of it ("Nobody" in "Qwzx Plor Nobody") is another song
        if (artist.isBlank()) return when {
            sameTitle(title, clean(s.title)) -> 0.8
            // or the phone's title holds both the song's name and its artist ("Artist - Song" with no artist field)
            within(title, s.title) && within(title, s.artist) -> 0.75
            else -> -1.0
        }
        val ts = sim(title, clean(s.title))
        if (ts < 0.75) return -1.0
        val sa = sim(artist, s.artist)
        if (sa < 0.5) return -1.0
        return ts * 0.7 + sa * 0.3
    }

    /**
     * How alike two names are, 0..1. In the same script by their words (consonants alone would make "Three Weeks"
     * look like "After Hours"); across scripts by their consonants ([TextMatch.score]), the only way there.
     */
    private fun sim(a: String, b: String): Double {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (TextMatch.arabic(x) != TextMatch.arabic(y)) return TextMatch.score(x, y)
        return when {
            x == y -> 1.0
            x.startsWith(y) || y.startsWith(x) -> 0.95
            x.contains(y) || y.contains(x) -> 0.9
            else -> TextMatch.overlap(x, y) * 0.85
        }
    }

    /** [part] appears in [whole] (as words in the same script, by its consonants across scripts). */
    private fun within(whole: String, part: String): Boolean {
        val w = norm(whole)
        val p = norm(clean(part))
        if (w.isEmpty() || p.length < 2) return false
        if (TextMatch.arabic(w) == TextMatch.arabic(p)) return " $w ".contains(" $p ")
        val ps = TextMatch.skeleton(p)
        return ps.length >= 4 && TextMatch.skeleton(w).contains(ps)
    }

    /** Parsed JSON of a catalogue answer, or null (and [offline] set) when it could not be fetched. */
    private fun ask(url: String): JSONObject? = try {
        JSONObject(http(url))
    } catch (t: Throwable) {
        offline = true
        null
    }

    /**
     * iTunes Search API, Saudi store first (Arabic music; it also finds Arabic-script queries under the Latin names the
     * catalogue uses), then the US store.
     */
    private fun itunes(title: String, artist: String): Hit? {
        val term = URLEncoder.encode("$artist $title".trim(), "UTF-8")
        for (country in listOf("sa", "us")) {
            val results = ask("https://itunes.apple.com/search?term=$term&media=music&entity=song&limit=10&country=$country")
                ?.optJSONArray("results") ?: continue
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                if (!matches(title, artist, r.optString("trackName"), r.optString("artistName"))) continue
                val art = r.optString("artworkUrl100")
                if (art.isNotEmpty()) return Hit(art.replace("100x100bb", "${SIZE}x${SIZE}bb"), (r.optLong("trackTimeMillis") / 1000).toInt())
            }
        }
        return null
    }

    /** Deezer search API (strong on Arabic and European catalogues). */
    private fun deezer(title: String, artist: String): Hit? {
        val q = URLEncoder.encode("$artist $title".trim(), "UTF-8")
        val data = ask("https://api.deezer.com/search?q=$q&limit=10")?.optJSONArray("data") ?: return null
        for (i in 0 until data.length()) {
            val r = data.optJSONObject(i) ?: continue
            val name = r.optJSONObject("artist")?.optString("name").orEmpty()
            if (!matches(title, artist, r.optString("title"), name)) continue
            val cover = r.optJSONObject("album")?.let { it.optString("cover_big").ifEmpty { it.optString("cover_medium") } }
            if (!cover.isNullOrEmpty()) return Hit(cover, r.optInt("duration"))
        }
        return null
    }

    /**
     * Same song? The title must match (allowing extras such as "feat." or "Remastered"); the artist, when known, too.
     * A phone often names Arabic songs in Arabic script while the catalogues use Latin transliterations
     * ("الأماكن" / "Al Amaken"): those are compared by their consonants ([TextMatch.skeleton]).
     */
    private fun matches(qTitle: String, qArtist: String, title: String, artist: String): Boolean {
        if (!same(qTitle, clean(title), words = 0.6)) return false
        return qArtist.isBlank() || same(qArtist, artist, words = 0.34)
    }

    private fun same(query: String, found: String, words: Double): Boolean {
        val q = norm(query)
        val r = norm(found)
        if (q.isEmpty() || r.isEmpty()) return false
        if (q == r || r.startsWith(q) || q.startsWith(r) || TextMatch.overlap(q, r) >= words) return true
        if (TextMatch.arabic(q) == TextMatch.arabic(r)) return false
        val a = TextMatch.skeleton(q)
        val b = TextMatch.skeleton(r)
        if (a.length < 2 || b.length < 2) return false
        val shorter = minOf(a.length, b.length)
        if ((a.contains(b) || b.contains(a)) && shorter >= 3 && shorter >= 0.6 * maxOf(a.length, b.length)) return true
        return 1.0 - TextMatch.lev(a, b).toDouble() / maxOf(a.length, b.length) >= 0.75
    }

    private fun norm(s: String) = TextMatch.norm(s)

    /** What a phone sends is often decorated: "(Official Video)", "[Lyrics]", "feat. …", "Artist - Topic". */
    private fun clean(s: String): String = s
        .replace(Regex("""[(\[{][^)\]}]*[)\]}]"""), " ")
        .replace(Regex("""(?i)\s+(feat\.?|ft\.?|featuring)\s+.*$"""), " ")
        .replace(Regex("""(?i)\s*-\s*topic$"""), " ")
        .replace(Regex("""(?i)vevo$"""), " ")
        .trim()

    // ------------------------------------------------------------------------------------------ network / disk
    private fun http(url: String): String = String(download(url), Charsets.UTF_8)

    private fun download(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", Places.USER_AGENT)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= SIZE && o.outHeight / (s * 2) >= SIZE) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /**
     * "covers2" since 1.10: what was kept before came from the other catalogues, often with no length or another
     * recording's; every song is looked up once more, YouTube Music first (the old folder goes).
     */
    private fun dir(c: Context): File {
        if (!cleaned) {
            cleaned = true
            try { File(c.cacheDir, "covers").takeIf { it.isDirectory }?.deleteRecursively() } catch (_: Throwable) { }
        }
        return File(c.cacheDir, "covers2")
    }

    private var cleaned = false

    private fun file(c: Context, key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(c), "$h.jpg")
    }

    /** The song's length beside its cover ("<hash>.len"; covers kept before there were lengths have none). */
    private fun lengthFile(cover: File) = File(cover.parentFile, cover.nameWithoutExtension + ".len")

    private fun fromDisk(c: Context, key: String): Found? {
        val f = file(c, key)
        if (!f.isFile) return null
        f.setLastModified(System.currentTimeMillis()) // recently used: kept longer
        val b = decode(f.readBytes()) ?: return null
        // "<seconds>", or "<seconds> y" when YouTube Music's catalogue gave them
        val len = try { lengthFile(f).takeIf { it.isFile }?.readText()?.trim().orEmpty() } catch (_: Throwable) { "" }
        return Found(b, len.substringBefore(' ').toIntOrNull() ?: 0, len.endsWith(" y"))
    }

    private fun save(c: Context, key: String, bytes: ByteArray, sec: Int, yt: Boolean) {
        val d = dir(c)
        d.mkdirs()
        val f = file(c, key)
        f.writeBytes(bytes)
        if (sec > 0) lengthFile(f).writeText(if (yt) "$sec y" else sec.toString())
        val covers = d.listFiles { x -> x.name.endsWith(".jpg") } ?: return
        if (covers.size > DISK_FILES) covers.sortedBy { it.lastModified() }.take(covers.size - DISK_FILES).forEach {
            it.delete()
            lengthFile(it).delete()
        }
    }
}
