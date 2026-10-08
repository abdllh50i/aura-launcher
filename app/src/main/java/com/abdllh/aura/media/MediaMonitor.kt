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

    private fun publish() {
        val sessionInfo = controller?.let { fromController(it) }
        // A live MediaSession wins over the last broadcast, unless it is paused and the broadcast says playing.
        current = when {
            sessionInfo != null && (sessionInfo.playing || broadcastInfo?.playing != true) -> sessionInfo
            broadcastInfo != null -> broadcastInfo
            else -> sessionInfo
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
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { controller = null; publish() }
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
        controller?.unregisterCallback(callback)
        val pick = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        controller = pick
        pick?.registerCallback(callback, main)
        publish()
    }

    private fun fromController(c: MediaController): NowPlaying? {
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        return NowPlaying(title, artist, art, playing, c.packageName)
    }

    // ---------------------------------------------------------------- transport
    enum class Key(val code: Int) { PREV(KeyEvent.KEYCODE_MEDIA_PREVIOUS), PLAY_PAUSE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), NEXT(KeyEvent.KEYCODE_MEDIA_NEXT) }

    fun send(ctx: Context, key: Key) {
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
