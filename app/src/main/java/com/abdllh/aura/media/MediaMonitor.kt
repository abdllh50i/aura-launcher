package com.abdllh.aura.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import java.util.concurrent.CopyOnWriteArrayList

data class NowPlaying(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val sourcePkg: String?
)

/**
 * Tracks what is playing. Two sources: (1) standard MediaSessions (any modern player; for a system app the
 * sessions can be read directly) and (2) the classic "com.android.music.*" / NWD broadcasts that the
 * head-unit's own Music, Radio and BT-music apps send.
 */
object MediaMonitor {
    private const val TAG = "AuraMedia"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(NowPlaying?) -> Unit>()
    private var app: Context? = null
    private var started = false

    @Volatile var current: NowPlaying? = null
        private set

    private var controller: MediaController? = null
    private var broadcastInfo: NowPlaying? = null

    fun observe(l: (NowPlaying?) -> Unit) {
        listeners.add(l)
        l(current)
    }

    fun unobserve(l: (NowPlaying?) -> Unit) {
        listeners.remove(l)
    }

    /** Package marker for music from a phone over Bluetooth (the firmware's BT module, see BtMusic). */
    const val SOURCE_BT = "bt"

    /** Extra on Aura Music's own send_media_play_info broadcasts (for the cluster), which this monitor skips. */
    const val EXTRA_SELF = "com.abdllh.aura.extra.SELF"

    private fun btInfo(): NowPlaying? {
        val b = com.abdllh.aura.music.BtMusic
        if (!b.available || !b.connected || (b.title.isBlank() && b.artist.isBlank())) return null
        return NowPlaying(b.title.ifBlank { b.artist }, if (b.title.isBlank()) "" else b.artist, b.art, b.playing, SOURCE_BT)
    }

    /** Aura Music's own player (read directly: no session access needed). */
    private fun auraInfo(): NowPlaying? {
        val p = com.abdllh.aura.music.Player
        val t = p.current ?: return null
        return NowPlaying(t.title, t.artist, p.art, p.playing, app?.packageName)
    }

    private fun publish() {
        val sessionInfo = controller?.let { fromController(it) }
        val bt = btInfo()
        val aura = auraInfo()
        // What plays wins: Bluetooth music, Aura Music, another app's session or broadcast; then what is paused.
        current = when {
            bt != null && bt.playing -> bt
            aura != null && aura.playing -> aura
            sessionInfo != null && (sessionInfo.playing || broadcastInfo?.playing != true) -> sessionInfo
            broadcastInfo != null -> broadcastInfo
            sessionInfo != null -> sessionInfo
            aura != null -> aura
            else -> bt
        }
        val c = current
        main.post { listeners.forEach { it(c) } }
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        registerBroadcasts()
        registerSessions()
        com.abdllh.aura.music.BtMusic.addListener { publish() }
        com.abdllh.aura.music.Player.addListener { publish() }
        publish() // what already plays (Aura Music may have started before the home screen)
    }

    // ---------------------------------------------------------------- broadcasts
    private const val ACTION_NWD_INFO = "com.nwd.action.send_media_play_info"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Any app may send these broadcasts and unparcelling a hostile extra throws: never let that kill the launcher.
            try {
                handle(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "ignored ${intent.action}: $t")
            }
        }
    }

    private fun handle(intent: Intent) {
        val e = intent.extras
        if (e?.getBoolean(EXTRA_SELF, false) == true) return // Aura Music's own report: it is read directly (auraInfo)
        fun s(vararg keys: String): String {
            for (k in keys) {
                val v = e?.get(k)
                if (v is String && v.isNotBlank()) return v
            }
            return ""
        }
        // The NWD apps report through OuterBroadcastSender.sendMediaPlayInfo: extra_media_name / _artist, and
        // extra_media_app_src_inout = 1 when the source was closed.
        if (intent.action == ACTION_NWD_INFO && (e?.get("extra_media_app_src_inout") as? Int) == 1) {
            broadcastInfo = null
            publish()
            return
        }
        val title = s("extra_media_name", "track", "title", "name", "song", "songname", "music_name")
        val artist = s("extra_media_artist", "artist", "singer", "artistname")
        val playing = when (val p = e?.get("playing") ?: e?.get("isPlaying") ?: e?.get("state")) {
            is Boolean -> p
            is Int -> p == 1 || p == 3
            else -> broadcastInfo?.playing ?: true
        }
        if (intent.action == "com.android.music.playstatechanged" && title.isEmpty()) {
            broadcastInfo = broadcastInfo?.copy(playing = playing) ?: return
        } else if (title.isNotEmpty() || artist.isNotEmpty()) {
            broadcastInfo = NowPlaying(title.ifEmpty { artist }, if (title.isEmpty()) "" else artist, null, playing, null)
        } else {
            return
        }
        publish()
    }

    private fun registerBroadcasts() {
        val f = IntentFilter().apply {
            addAction("com.android.music.metachanged")
            addAction("com.android.music.playstatechanged")
            addAction("com.android.music.playbackcomplete")
            addAction("com.nwd.action.send_media_play_info")
            addAction("com.nwd.btmusic.playinfo.changed")
        }
        try { app?.registerReceiver(receiver, f) } catch (t: Throwable) { Log.w(TAG, "register: $t") }
    }

    // ---------------------------------------------------------------- media sessions
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessions(list) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = changed()
        override fun onPlaybackStateChanged(state: PlaybackState?) = changed()
        override fun onSessionDestroyed() = requery() // (which one went is not told: the list is asked for again)
    }

    private fun changed() {
        // the home card follows the app that plays (another one may have started since the list last changed)
        controller = others.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controller?.takeIf { it in others } ?: others.firstOrNull()
        publish()
        try { com.abdllh.aura.music.YtMusic.onSession() } catch (t: Throwable) { Log.w(TAG, "yt: $t") }
    }

    /** Every other app's session, the one that plays first (the home card shows [controller]; AMRI Music may want one app's). */
    private var others: List<MediaController> = emptyList()
    private var requery: () -> Unit = {}

    /** What one app plays, as its own session tells it. */
    class AppSession(val pkg: String, val title: String, val artist: String, val album: String, val art: Bitmap?,
                     val playing: Boolean, val durationMs: Long)

    private fun of(pkgs: Set<String>): MediaController? =
        others.firstOrNull { it.packageName in pkgs && it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: others.firstOrNull { it.packageName in pkgs }

    /** The session of the first of [pkgs] that has one (the one playing, when two do); null when none has a song. */
    fun app(pkgs: Set<String>): AppSession? {
        val c = of(pkgs) ?: return null
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        return AppSession(c.packageName, title,
            md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
            md.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            c.playbackState?.state == PlaybackState.STATE_PLAYING,
            md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L))
    }

    /** Where that app's song is now (ms): its last report carried on by the clock while it plays; null when it tells none. */
    fun appPosition(pkgs: Set<String>): Long? {
        val st = of(pkgs)?.playbackState ?: return null
        if (st.position < 0L) return null
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            pos += ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        return pos.coerceAtLeast(0L)
    }

    enum class Do { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS }

    /** Steers that app through its session; false when it has none. */
    fun appControl(pkgs: Set<String>, what: Do, seekToMs: Long = -1L): Boolean {
        val c = of(pkgs) ?: return false
        return try {
            val t = c.transportControls
            if (seekToMs >= 0L) t.seekTo(seekToMs) else when (what) {
                Do.PLAY -> t.play()
                Do.PAUSE -> t.pause()
                Do.TOGGLE -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                Do.NEXT -> t.skipToNext()
                Do.PREVIOUS -> t.skipToPrevious()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "control: $t")
            false
        }
    }

    private fun registerSessions() {
        val ctx = app ?: return
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val listener = ComponentName(ctx, AuraNotificationListener::class.java)
        // System app (MEDIA_CONTENT_CONTROL) may pass null; otherwise notification access is required.
        for (component in listOf<ComponentName?>(null, listener)) {
            try {
                msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
                onSessions(msm.getActiveSessions(component))
                requery = { try { onSessions(msm.getActiveSessions(component)) } catch (t: Throwable) { Log.w(TAG, "sessions: $t") } }
                return
            } catch (_: SecurityException) {
                // try the next option
            } catch (t: Throwable) {
                Log.w(TAG, "sessions: $t")
                return
            }
        }
    }

    private fun onSessions(list: List<MediaController>?) {
        for (c in others) try { c.unregisterCallback(callback) } catch (_: Throwable) { }
        // Aura Music is read directly (auraInfo), so only other apps' sessions count here.
        others = list?.filter { it.packageName != app?.packageName }.orEmpty()
        controller = others.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: others.firstOrNull()
        // (each of them is listened to: AMRI Music follows one app's session, whichever the home card shows)
        for (c in others) try { c.registerCallback(callback, main) } catch (_: Throwable) { }
        changed()
    }

    private fun fromController(c: MediaController): NowPlaying? {
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        return NowPlaying(title, artist, art, playing, c.packageName)
    }

    /**
     * Where the current song is and its length (ms), or null when its source does not tell (a Bluetooth song whose
     * position is not known yet, a broadcast-only app, a session without a length).
     */
    fun progress(): Pair<Long, Long>? {
        val c = current ?: return null
        return when (c.sourcePkg) {
            SOURCE_BT -> {
                val b = com.abdllh.aura.music.BtMusic
                if (b.positionKnown && b.durationSec > 0) b.positionMs() to b.durationSec * 1000L else null
            }
            app?.packageName -> {
                val p = com.abdllh.aura.music.Player
                val d = p.duration()
                if (p.current != null && d > 0) p.position().coerceIn(0L, d) to d else null
            }
            null -> null
            else -> {
                val ctl = controller?.takeIf { it.packageName == c.sourcePkg } ?: return null
                val st = ctl.playbackState ?: return null
                val d = ctl.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (d <= 0L || st.position < 0L) return null
                var pos = st.position
                if (st.state == PlaybackState.STATE_PLAYING) {
                    pos += ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
                }
                pos.coerceIn(0L, d) to d
            }
        }
    }

    // ---------------------------------------------------------------- transport
    enum class Key(val code: Int) { PREV(KeyEvent.KEYCODE_MEDIA_PREVIOUS), PLAY_PAUSE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), NEXT(KeyEvent.KEYCODE_MEDIA_NEXT) }

    fun send(ctx: Context, key: Key) {
        if (current?.sourcePkg == SOURCE_BT) {
            val b = com.abdllh.aura.music.BtMusic
            b.control(when (key) { Key.PREV -> b.PREVIOUS; Key.NEXT -> b.NEXT; Key.PLAY_PAUSE -> b.TOGGLE })
            return
        }
        if (current?.sourcePkg == ctx.packageName || current == null) {
            val p = com.abdllh.aura.music.Player
            if (p.current != null || com.abdllh.aura.music.Library.tracks.isNotEmpty()) {
                when (key) {
                    Key.PREV -> p.previous()
                    Key.NEXT -> p.next()
                    Key.PLAY_PAUSE -> when {
                        p.current != null -> p.toggle()
                        else -> { p.resume(); if (p.current == null) p.play(com.abdllh.aura.music.Library.tracks, 0) }
                    }
                }
                return
            }
        }
        val c = controller
        if (c != null) {
            try {
                when (key) {
                    Key.PREV -> c.transportControls.skipToPrevious()
                    Key.NEXT -> c.transportControls.skipToNext()
                    Key.PLAY_PAUSE -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
                }
                return
            } catch (_: Throwable) {
            }
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key.code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key.code))
    }
}
