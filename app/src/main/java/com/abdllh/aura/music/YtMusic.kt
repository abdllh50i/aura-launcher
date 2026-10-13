package com.abdllh.aura.music

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.HomeGuard
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * YouTube Music from inside AMRI Music: its catalogue is searched here, and what is chosen is played by YouTube's own
 * app on the unit.
 *
 * The search is the one the ytmusicapi library makes (github.com/sigma67/ytmusicapi): the request YouTube Music's own
 * web page sends (POST music.youtube.com/youtubei/v1/search as the WEB_REMIX client, the "songs" filter), with no
 * account. It answers with the songs' names, artists, albums, lengths, pictures and ids — nothing that can be
 * played. It is not a published interface: Google may change it, and then the search finds nothing until this is
 * updated (the rest of AMRI Music does not depend on it).
 *
 * Playing is not done here, on purpose: a song's sound comes from YouTube Music's own app (com.google.android.apps
 * .youtube.music from the Play Store, with the owner's account; the plain YouTube app when that is the one there),
 * handed the song as the link its own share button gives (music.youtube.com/watch?v=…). AMRI then reads that app's
 * media session, like any player's ([MediaMonitor.app]): the song, its length and where it is — the real position,
 * which a phone over this unit's Bluetooth never tells — and steers it (pause, next, seek). The app comes to the
 * front to take the song; AMRI Music comes back as soon as the song plays ([play]).
 *
 * The same search names a Bluetooth song exactly ([CoverSearch]): the owner's phone plays from this catalogue, so the
 * length and the cover found here are the song's own.
 */
object YtMusic {
    private const val TAG = "AuraYt"
    const val PKG = "com.google.android.apps.youtube.music"
    const val PKG_YOUTUBE = "com.google.android.youtube"
    private const val SEARCH = "https://music.youtube.com/youtubei/v1/search?alt=json&prettyPrint=false"
    /** ytmusicapi's filter for "songs" (not videos, albums, playlists...). */
    private const val SONGS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    private const val AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    /** The app has this long to start playing what it was handed before AMRI Music stops waiting to come back. */
    private const val HAND_MS = 12_000L

    class Song(val id: String, val title: String, val artist: String, val album: String, val sec: Int, val thumb: String)

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** The packages whose session is "YouTube" for AMRI Music (debug builds: another player can stand in). */
    val players: Set<String> by lazy {
        val test = if (com.abdllh.aura.BuildConfig.DEBUG) com.abdllh.aura.system.SystemProps.get("debug.aura.ytpkg") else ""
        if (test.isNotBlank()) setOf(PKG, PKG_YOUTUBE, test) else setOf(PKG, PKG_YOUTUBE)
    }

    /** The app that will play: YouTube Music, else YouTube; null when neither is on the unit. */
    fun player(c: Context): String? = listOf(PKG, PKG_YOUTUBE).firstOrNull { p ->
        try { c.packageManager.getApplicationInfo(p, 0).enabled } catch (_: Throwable) { false }
    }

    // ------------------------------------------------------------------------------------------ search
    /** Songs for [query], best first; [done] on the main thread: the songs, or null when YouTube could not be asked. */
    fun search(query: String, done: (List<Song>?) -> Unit) {
        val q = query.trim()
        if (q.isEmpty()) { done(emptyList()); return }
        io.execute {
            val r = try { find(q) } catch (t: Throwable) { Log.i(TAG, "search '$q': $t"); null }
            main.post { done(r) }
        }
    }

    /** The search itself (blocking; throws when the answer could not be had). */
    fun find(query: String): List<Song> {
        val arabic = query.any { it in '؀'..'ۿ' }
        val body = JSONObject()
            .put("context", JSONObject()
                .put("client", JSONObject()
                    .put("clientName", "WEB_REMIX")
                    // (the web client's version is a date; today's, as ytmusicapi does)
                    .put("clientVersion", "1." + SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date()) + ".01.00")
                    // names come in the language asked for: Arabic for an Arabic query, so they read like the phone's
                    .put("hl", if (arabic) "ar" else "en")
                    .put("gl", "SA"))
                .put("user", JSONObject()))
            .put("query", query)
            .put("params", SONGS)
        val c = URL(SEARCH).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("User-Agent", AGENT)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Origin", "https://music.youtube.com")
            c.setRequestProperty("X-Origin", "https://music.youtube.com")
            c.setRequestProperty("Cookie", "SOCS=CAI") // (no consent page instead of an answer)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return parse(JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }))
        } finally {
            c.disconnect()
        }
    }

    /** The songs in an answer; a row that does not look like one is skipped (the page's layout is Google's to change). */
    private fun parse(o: JSONObject): List<Song> {
        val out = ArrayList<Song>()
        val sections = o.optJSONObject("contents")?.optJSONObject("tabbedSearchResultsRenderer")?.optJSONArray("tabs")
            ?.optJSONObject(0)?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents") ?: return out
        for (s in 0 until sections.length()) {
            val rows = sections.optJSONObject(s)?.optJSONObject("musicShelfRenderer")?.optJSONArray("contents") ?: continue
            for (i in 0 until rows.length()) {
                try {
                    val r = rows.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    val id = r.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
                    if (id.isEmpty()) continue
                    val cols = r.optJSONArray("flexColumns") ?: continue
                    val title = text(cols, 0)
                    // "artist • album • 3:54" (a song's row under the songs filter)
                    val parts = text(cols, 1).split(" • ").map { it.trim() }.filter { it.isNotEmpty() }
                    val sec = parts.lastOrNull()?.let { seconds(it) } ?: 0
                    val names = if (sec > 0) parts.dropLast(1) else parts
                    val thumbs = r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
                    if (title.isNotBlank()) out.add(Song(id, title, names.getOrNull(0).orEmpty(), names.getOrNull(1).orEmpty(), sec, thumb))
                } catch (_: Throwable) {
                }
            }
        }
        return out
    }

    private fun text(cols: JSONArray, i: Int): String {
        val runs = cols.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs") ?: return ""
        val b = StringBuilder()
        for (k in 0 until runs.length()) b.append(runs.optJSONObject(k)?.optString("text").orEmpty())
        return b.toString()
    }

    /** "3:54" or "1:02:03" in seconds; 0 for anything else. */
    private fun seconds(s: String): Int {
        if (!Regex("""\d{1,2}(:\d{2}){1,2}""").matches(s)) return 0
        return s.split(":").fold(0) { acc, p -> acc * 60 + p.toInt() }
    }

    /** A picture's address at [px] (the answer gives small ones; the size is part of the address). */
    fun sized(url: String, px: Int): String =
        if (Regex("""=w\d+-h\d+""").containsMatchIn(url)) url.replace(Regex("""=w\d+-h\d+"""), "=w$px-h$px") else url

    // ------------------------------------------------------------------------------------------ pictures
    private val thumbs = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** A result's small picture (kept in memory); [done] on the main thread, at once when it is there already. */
    fun thumb(url: String, done: (Bitmap?) -> Unit) {
        if (url.isEmpty()) { done(null); return }
        thumbs.get(url)?.let { done(it); return }
        io.execute {
            val b = try {
                val c = URL(sized(url, 160)).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 10000
                try { c.inputStream.use { BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
            } catch (_: Throwable) {
                null
            }
            main.post {
                if (b != null) thumbs.put(url, b)
                done(b)
            }
        }
    }

    // ------------------------------------------------------------------------------------------ playing
    /** The song AMRI Music last handed over, until the app plays it (or the wait is over). */
    private var handed: Song? = null
    private var handedAt = 0L
    /** What the app played when the song was handed over. */
    private var handedOver = ""
    private var back: (() -> Unit)? = null

    /**
     * Hands [song] to YouTube's app, which comes to the front for it; [onPlaying] runs once the app plays it (AMRI
     * Music comes back then). False when no YouTube app is on the unit.
     */
    fun play(c: Context, song: Song, onPlaying: () -> Unit): Boolean {
        val pkg = player(c) ?: return false
        app = c.applicationContext
        main.removeCallbacks(stillPlaying)
        val link = if (pkg == PKG) "https://music.youtube.com/watch?v=${song.id}" else "https://www.youtube.com/watch?v=${song.id}"
        // the speakers are the app's now: AMRI's own player lets go, the phone's music is muted, and the unit's sound
        // goes to Android (the firmware does not switch for an app that plays without its screen)
        Player.pauseForOtherSource()
        BtMusic.deactivate()
        takeSpeakers(c)
        handed = song
        handedAt = SystemClock.elapsedRealtime()
        handedOver = MediaMonitor.app(players)?.title.orEmpty()
        back = onPlaying
        HomeGuard.launching(pkg)
        return try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "handed ${song.id} to $pkg")
            main.removeCallbacks(giveUp)
            main.postDelayed(giveUp, HAND_MS)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "start $pkg: $t")
            handed = null
            back = null
            false
        }
    }

    /** The app did not start playing in time (it asks for something on its own screen): the owner stays there. */
    private val giveUp = Runnable {
        if (handed != null) note("no song played within ${HAND_MS / 1000} s of the hand-over")
        handed = null
        back = null
    }

    /**
     * Called by [MediaMonitor] when the app's session changes: once it plays something new after a hand-over, AMRI
     * Music is brought back.
     */
    fun onSession() {
        val song = handed ?: return
        val s = MediaMonitor.app(players) ?: return
        if (!s.playing) return
        // the answer to the hand-over is a song that starts: another one than what played before (its name as the
        // app tells it may be spelled unlike the search's), or the same one from its beginning
        val started = s.title != handedOver || (MediaMonitor.appPosition(players) ?: Long.MAX_VALUE) < 5000L
        if (!started || SystemClock.elapsedRealtime() - handedAt < 400L) return
        Log.i(TAG, "${s.pkg} plays '${s.title}' (asked for '${song.title}')")
        handed = null
        main.removeCallbacks(giveUp)
        val b = back
        back = null
        note("${s.pkg.substringAfterLast('.')} played after ${SystemClock.elapsedRealtime() - handedAt} ms")
        // (a moment for its first frames: the app must not find itself paused by losing the screen mid-start)
        main.postDelayed({
            b?.invoke()
            returnedAt = SystemClock.elapsedRealtime()
            main.postDelayed(stillPlaying, 3500L)
        }, 600L)
    }

    private var returnedAt = 0L

    /**
     * AMRI Music is back in front: an app that stopped at that very moment is one that plays in front only (YouTube
     * without a Premium account). Said once, so the owner knows why it is silent.
     */
    private val stillPlaying = Runnable {
        val s = MediaMonitor.app(players) ?: return@Runnable
        if (s.playing) return@Runnable
        note("${s.pkg.substringAfterLast('.')} stopped once out of sight")
        val c = app ?: return@Runnable
        try { android.widget.Toast.makeText(c, com.abdllh.aura.R.string.yt_needs_front, android.widget.Toast.LENGTH_LONG).show() } catch (_: Throwable) { }
    }

    private var app: Context? = null

    /** How the last hand-over went, for Settings → About ("" when there never was one). */
    val note: String get() = com.abdllh.aura.util.Prefs.raw.getString("ytNote", "").orEmpty()

    private fun note(what: String) {
        try {
            val at = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date())
            com.abdllh.aura.util.Prefs.raw.edit().putString("ytNote", "$at  $what").apply()
        } catch (_: Throwable) {
        }
    }

    private fun takeSpeakers(c: Context) {
        if (!Device.isNwd) return
        try {
            val src = Settings.System.getInt(c.contentResolver, "mcu_current_source", 0)
            if (src != 0) c.sendBroadcast(Intent("com.nwd.action.ACTION_REQUEST_CHANGE_SOURCE").putExtra("extra_source_id", 0.toByte()))
        } catch (t: Throwable) {
            Log.w(TAG, "source: $t")
        }
    }

    /** The Play Store's page of YouTube Music (the unit has the store). */
    fun openStore(c: Context) {
        for (u in listOf("market://details?id=$PKG", "https://play.google.com/store/apps/details?id=$PKG")) {
            try {
                c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {
            }
        }
    }
}
