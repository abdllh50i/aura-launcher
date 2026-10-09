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

    /** The user's switch (on by default: without it Bluetooth songs have no picture at all). */
    var enabled: Boolean
        get() = Prefs.raw.getBoolean("btCoverOnline", true)
        set(v) { Prefs.raw.edit().putBoolean("btCoverOnline", v).apply() }

    /** The cover of a song, or null; [done] runs on the main thread (at once when it is already known). */
    fun find(ctx: Context, title: String, artist: String, done: (Bitmap?) -> Unit) {
        val key = "${norm(artist)}|${norm(title)}"
        if (norm(title).isEmpty()) { done(null); return }
        mem.get(key)?.let { done(it); return }
        val c = ctx.applicationContext
        val online = enabled
        io.execute {
            val b = try { fromDisk(c, key) ?: if (online) fromNetwork(c, key, title, artist) else null } catch (t: Throwable) {
                Log.w(TAG, "cover for '$artist - $title': $t")
                null
            }
            main.post {
                if (b != null) mem.put(key, b)
                done(b)
            }
        }
    }

    // ------------------------------------------------------------------------------------------ lookup
    private var offline = false // io thread: a catalogue could not be asked (no network...) during this lookup

    private fun fromNetwork(c: Context, key: String, title: String, artist: String): Bitmap? {
        val seen = missing[key]
        if (seen != null && System.currentTimeMillis() - seen < RETRY_MISSING_MS) return null
        val t = clean(title)
        val a = clean(artist)
        offline = false
        val url = itunes(t, a) ?: deezer(t, a)
        if (url == null) {
            if (!offline) missing[key] = System.currentTimeMillis() // really unknown (not just no network right now)
            return null
        }
        val bytes = download(url)
        val b = decode(bytes) ?: return null
        try { save(c, key, bytes) } catch (_: Throwable) { }
        return b
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
    private fun itunes(title: String, artist: String): String? {
        val term = URLEncoder.encode("$artist $title".trim(), "UTF-8")
        for (country in listOf("sa", "us")) {
            val results = ask("https://itunes.apple.com/search?term=$term&media=music&entity=song&limit=10&country=$country")
                ?.optJSONArray("results") ?: continue
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                if (!matches(title, artist, r.optString("trackName"), r.optString("artistName"))) continue
                val art = r.optString("artworkUrl100")
                if (art.isNotEmpty()) return art.replace("100x100bb", "${SIZE}x${SIZE}bb")
            }
        }
        return null
    }

    /** Deezer search API (strong on Arabic and European catalogues). */
    private fun deezer(title: String, artist: String): String? {
        val q = URLEncoder.encode("$artist $title".trim(), "UTF-8")
        val data = ask("https://api.deezer.com/search?q=$q&limit=10")?.optJSONArray("data") ?: return null
        for (i in 0 until data.length()) {
            val r = data.optJSONObject(i) ?: continue
            val name = r.optJSONObject("artist")?.optString("name").orEmpty()
            if (!matches(title, artist, r.optString("title"), name)) continue
            val cover = r.optJSONObject("album")?.let { it.optString("cover_big").ifEmpty { it.optString("cover_medium") } }
            if (!cover.isNullOrEmpty()) return cover
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

    private fun dir(c: Context) = File(c.cacheDir, "covers")

    private fun file(c: Context, key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(c), "$h.jpg")
    }

    private fun fromDisk(c: Context, key: String): Bitmap? {
        val f = file(c, key)
        if (!f.isFile) return null
        f.setLastModified(System.currentTimeMillis()) // recently used: kept longer
        return decode(f.readBytes())
    }

    private fun save(c: Context, key: String, bytes: ByteArray) {
        val d = dir(c)
        d.mkdirs()
        file(c, key).writeBytes(bytes)
        val files = d.listFiles() ?: return
        if (files.size > DISK_FILES) files.sortedBy { it.lastModified() }.take(files.size - DISK_FILES).forEach { it.delete() }
    }
}
