package com.abdllh.aura.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.system.Device
import org.json.JSONArray
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Aura Music's player: plays the library with MediaPlayer, keeps a queue with shuffle/repeat, publishes a
 * MediaSession and does what the stock NWD music app does so the firmware treats it as the music source (app id 2):
 *  - take the ARM source (MCU source 0) and announce the app (ACTION_APP_IN_OUT 2), stop other media, mute BT music;
 *  - set mask 1 of Settings.System nwd_arm_volume_type while it owns playback, which makes the firmware hand the
 *    steering-wheel/panel media keys to it as ACTION_KEY_VALUE broadcasts (handled here) instead of injecting them;
 *  - report the track to the instrument cluster (send_media_play_info / send_media_play_time);
 *  - stop when another source takes over, pause during phone calls.
 * [playing] is the intent to play: it is set before a track has loaded (the track starts once it is ready, unless it
 * was paused meanwhile) and cleared by pause, calls, focus loss and other sources. Main thread only.
 */
object Player {
    private const val TAG = "AuraPlayer"
    private const val MUSIC_APP_ID = 2
    private const val MAX_FAILURES = 5          // unplayable tracks in a row before giving up (USB stick pulled...)

    enum class Repeat { OFF, ALL, ONE }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var app: Context? = null
    private lateinit var store: SharedPreferences // queue, position, modes: a small file of its own (written often)
    private var mp: MediaPlayer? = null
    private var prepared = false
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null
    private var pausedByFocus = false
    private var pausedByCall = false
    private var owner = false        // we hold the music source / key routing
    private var failures = 0         // tracks in a row that could not be played
    private var retry: Runnable? = null
    private var ticks = 0

    var queue: List<Track> = emptyList()
        private set
    private var order: List<Int> = emptyList() // play order (shuffled indices)
    var index = -1                          // position in [order]
        private set
    var playing = false
        private set
    var shuffle = false
        private set
    var repeat = Repeat.ALL
        private set

    val current: Track? get() = order.getOrNull(index)?.let { queue.getOrNull(it) }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners) l() }

    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        store = c.getSharedPreferences("aura_music", Context.MODE_PRIVATE)
        shuffle = store.getBoolean("shuffle", false)
        repeat = try { Repeat.valueOf(store.getString("repeat", "ALL") ?: "ALL") } catch (_: Throwable) { Repeat.ALL }
        // The process died while it owned playback (update, crash): the firmware would keep sending the media keys to
        // a player that no longer listens, so hand them back.
        if (store.getBoolean("keyRouting", false)) {
            if (Device.isNwd) { setKeyRouting(c, false); sendPlayInfo(c, inOut = 1) }
            store.edit().putBoolean("keyRouting", false).apply()
        }
        val f = IntentFilter().apply {
            addAction("com.nwd.action.ACTION_KEY_VALUE")
            addAction("com.nwd.action.ACTION_CHANGE_SOURCE")
            addAction("com.nwd.ACTION_MEDIA_PLAY")
            addAction("com.nwd.music.stop")
            addAction("com.nwd.action.ACTION_MCU_POWER_OFF")
            addAction("com.nwd.ACTION_PLAY_COMMAND")
            addAction("com.nwd.action.music.on_widget_button_click")
            addAction("com.bt.ACTION_BT_INCOMING_CALL")
            addAction("com.bt.ACTION_BT_OUTGOING_NUMBER")
            addAction("com.bt.ACTION_BT_BEGIN_CALL_ONLINE")
            addAction("com.bt.ACTION_BT_END_CALL")
        }
        try { c.registerReceiver(receiver, f) } catch (t: Throwable) { Log.w(TAG, "receiver: $t") }
        // The last queue comes back (paused) as soon as the library is known, so the home screen's player card shows
        // the last song and its play button resumes it; later reloads drop what is gone (a USB stick pulled).
        Library.addListener { if (queue.isEmpty()) restore() else prune() }
        Library.load(c)
    }

    // ------------------------------------------------------------------------------------------ queue / transport
    fun play(tracks: List<Track>, start: Int) {
        if (tracks.isEmpty() || app == null) return
        cancelRetry()
        failures = 0
        queue = tracks
        buildOrder(start.coerceIn(0, tracks.size - 1))
        saveQueue()
        playing = true
        startCurrent()
    }

    fun toggle() { if (playing) pause() else resume() }

    fun resume() {
        val c = app ?: return
        if (current == null) {
            restore()
            if (current == null) return
        }
        cancelRetry()
        playing = true
        if (!prepared) { startCurrent(seek = store.getLong("pos", 0L)); return }
        if (!takeOver(c)) { playing = false; afterStateChange(); return }
        try { mp?.start() } catch (_: Throwable) { }
        afterStateChange()
    }

    fun pause() {
        cancelRetry()
        try { if (prepared && mp?.isPlaying == true) mp?.pause() } catch (_: Throwable) { }
        playing = false
        savePosition()
        afterStateChange()
    }

    fun next(user: Boolean = true) {
        if (order.isEmpty() || app == null) return
        cancelRetry()
        if (user) { failures = 0; playing = true } else if (!playing || !owner) return // nothing advances a paused player
        if (!user && repeat == Repeat.ONE) { startCurrent(); return }
        if (index + 1 < order.size) index++ else if (repeat != Repeat.OFF || user) index = 0 else { stop(); return }
        saveIndex()
        startCurrent()
    }

    fun previous() {
        if (order.isEmpty() || app == null) return
        cancelRetry()
        failures = 0
        playing = true
        if (position() > 4000) { // well into the song: back to its start
            if (prepared) { seekTo(0); if (!(mp?.isPlaying ?: false)) resume(); return }
            store.edit().putLong("pos", 0L).apply()
            startCurrent()
            return
        }
        index = if (index > 0) index - 1 else order.size - 1
        saveIndex()
        startCurrent()
    }

    fun seekTo(ms: Long) {
        try { if (prepared) mp?.seekTo(ms.toInt()) } catch (_: Throwable) { }
        publishSession()
        changed()
    }

    fun position(): Long = try {
        if (prepared) (mp?.currentPosition ?: 0).toLong() else if (app != null) store.getLong("pos", 0L) else 0L
    } catch (_: Throwable) { 0L }
    fun duration(): Long = try { if (prepared) (mp?.duration ?: 0).toLong() else current?.durationMs ?: 0L } catch (_: Throwable) { 0L }

    fun setShuffle(on: Boolean) {
        if (app == null) return
        shuffle = on
        store.edit().putBoolean("shuffle", on).apply()
        val cur = order.getOrNull(index) ?: 0
        buildOrder(cur)
        changed()
    }

    fun cycleRepeat() {
        if (app == null) return
        repeat = when (repeat) { Repeat.OFF -> Repeat.ALL; Repeat.ALL -> Repeat.ONE; Repeat.ONE -> Repeat.OFF }
        store.edit().putString("repeat", repeat.name).apply()
        changed()
    }

    fun stop() {
        cancelRetry()
        savePosition()
        releasePlayer()
        playing = false
        release()
        afterStateChange()
    }

    /** Another source (Bluetooth music in Aura) takes the speakers. */
    fun pauseForOtherSource() {
        if (playing) pause()
        release()
    }

    private fun buildOrder(start: Int) {
        val idx = queue.indices.toMutableList()
        if (shuffle) {
            idx.remove(start)
            idx.shuffle()
            idx.add(0, start)
            order = idx
            index = 0
        } else {
            order = idx
            index = start
        }
    }

    private fun releasePlayer() {
        val p = mp ?: return
        mp = null
        prepared = false
        try { p.stop() } catch (_: Throwable) { }
        try { p.release() } catch (_: Throwable) { }
    }

    /** Loads the current track and starts it once ready (if it is still meant to play then). */
    private fun startCurrent(seek: Long = 0L) {
        val c = app ?: return
        val t = current ?: return
        releasePlayer()
        if (!takeOver(c)) { // focus refused: nothing plays, and nothing pretends to
            playing = false
            afterStateChange()
            return
        }
        val p = MediaPlayer()
        mp = p
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        p.setOnPreparedListener {
            if (mp !== it) return@setOnPreparedListener
            prepared = true
            failures = 0
            if (seek > 0) it.seekTo(seek.toInt())
            if (playing && owner) it.start()
            afterStateChange()
        }
        p.setOnCompletionListener { if (mp === it) next(user = false) }
        p.setOnErrorListener { mp2, what, extra ->
            Log.w(TAG, "error $what/$extra on ${t.path}")
            if (mp === mp2) skipBroken(mp2)
            true
        }
        try {
            p.setDataSource(c, t.uri)
            p.prepareAsync()
        } catch (e: Throwable) {
            Log.w(TAG, "open ${t.path}: $e")
            skipBroken(p)
        }
        afterStateChange()
    }

    /** Moves past a track that cannot be played; gives up after a few in a row (e.g. the USB stick is gone). */
    private fun skipBroken(p: MediaPlayer) {
        cancelRetry()
        if (++failures >= minOf(queue.size, MAX_FAILURES)) {
            failures = 0
            main.post { if (mp === p) stop() } // not inside the start that is still running
            return
        }
        val r = Runnable { retry = null; if (mp === p) next(user = false) }
        retry = r
        main.postDelayed(r, 300)
    }

    private fun cancelRetry() {
        retry?.let { main.removeCallbacks(it) }
        retry = null
    }

    /** The library changed: forget queued tracks that are gone; stop if the one playing went with them. */
    private fun prune() {
        if (!Library.loaded) return
        val ids = HashSet<Long>(Library.tracks.size * 2)
        for (t in Library.tracks) ids.add(t.id)
        if (queue.all { it.id in ids }) return
        val cur = current
        val kept = queue.filter { it.id in ids }
        if (cur == null || cur.id !in ids) {
            if (owner || playing) stop()
            queue = kept
            if (kept.isEmpty()) { order = emptyList(); index = -1 } else buildOrder(0)
            store.edit().putLong("pos", 0L).apply()
        } else {
            queue = kept
            buildOrder(kept.indexOfFirst { it.id == cur.id }.coerceAtLeast(0))
        }
        saveQueue()
        afterStateChange()
    }

    // ------------------------------------------------------------------------------------------ firmware integration
    /** Takes the speakers: ARM source, app id 2, audio focus, other media stopped. False if focus was refused. */
    private fun takeOver(c: Context): Boolean {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener, main)
            .build().also { focus = it }
        if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) return false
        if (Device.isNwd) {
            try {
                val src = Settings.System.getInt(c.contentResolver, "mcu_current_source", 0)
                if (src != 0) c.sendBroadcast(Intent("com.nwd.action.ACTION_REQUEST_CHANGE_SOURCE").putExtra("extra_source_id", 0.toByte()))
                if (!owner) {
                    c.sendBroadcast(Intent("com.nwd.action.ACTION_APP_IN_OUT").putExtra("extra_app_id", MUSIC_APP_ID)
                        .putExtra("extra_app_operation", 1).putExtra("extra_app_event", 0))
                    c.sendBroadcast(Intent("com.nwd.ACTION_REQUEST_SHORT_MUTE").putExtra("extra_short_mute_time", 50))
                    // marked as Aura's: the same broadcasts from the stock player mean its screen came up (MusicKeys)
                    c.sendBroadcast(Intent("com.bt.ACTION_A2DP_MUTE").putExtra(MediaMonitor.EXTRA_SELF, true))
                    c.sendBroadcast(Intent("com.nwd.video.stop"))
                    c.sendBroadcast(Intent("com.nwd.ipod.stop"))
                    c.sendBroadcast(Intent("com.nwd.ACTION_MEDIA_PLAY").putExtra("extra_app_id", MUSIC_APP_ID).putExtra(MediaMonitor.EXTRA_SELF, true))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "source: $t")
            }
            if (!owner) setKeyRouting(c, true)
        }
        owner = true
        ensureSession(c)
        session?.isActive = true
        MusicService.start(c)
        return true
    }

    /** Gives the source back (stopped, or another Aura source took over). */
    private fun release() {
        val c = app ?: return
        if (!owner) return
        owner = false
        cancelRetry()
        if (Device.isNwd) setKeyRouting(c, false)
        focus?.let { (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        sendPlayInfo(c, inOut = 1)
        MusicService.stop(c)
    }

    /** Mask 1 of nwd_arm_volume_type: "music app plays" (the firmware then sends the media keys to us). */
    private fun setKeyRouting(c: Context, on: Boolean) {
        try {
            val cr = c.contentResolver
            val v = Settings.System.getInt(cr, "nwd_arm_volume_type", 0)
            val nv = if (on) v or 1 else v and 1.inv()
            if (nv != v) Settings.System.putInt(cr, "nwd_arm_volume_type", nv)
            store.edit().putBoolean("keyRouting", on).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "nwd_arm_volume_type: $t")
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { if (playing) pause(); release() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) { pausedByFocus = true; pause() }
            // navigation prompts and the like: the system lowers the music under them, no need to stop
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) { pausedByFocus = false; main.postDelayed({ resume() }, 600) }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try { handle(i) } catch (t: Throwable) { Log.w(TAG, "ignored ${i.action}: $t") }
        }
    }

    private fun handle(i: Intent) {
        when (i.action) {
            "com.nwd.action.ACTION_KEY_VALUE" -> {
                if (!owner) return
                when (i.getByteExtra("extra_key_value", 0).toInt()) {
                    5 -> next()
                    6 -> previous()
                    7 -> toggle()
                    -108 -> resume()
                    -107 -> pause()
                    32 -> setShuffle(!shuffle)
                    33 -> cycleRepeat()
                }
            }
            "com.nwd.action.ACTION_CHANGE_SOURCE" -> if (owner && i.getByteExtra("extra_source_id", 0).toInt() > 0) { pause(); release() }
            "com.nwd.ACTION_MEDIA_PLAY" -> if (owner && i.getIntExtra("extra_app_id", MUSIC_APP_ID) != MUSIC_APP_ID) { pause(); release() }
            "com.nwd.music.stop", "com.nwd.action.ACTION_MCU_POWER_OFF" -> if (owner) { pause(); release() }
            "com.nwd.ACTION_PLAY_COMMAND" -> when (i.getIntExtra("extra_command", 0)) {
                1 -> toggle(); 2 -> previous(); 3 -> next(); 4 -> resume(); 5 -> pause()
                6 -> seekTo(i.getIntExtra("extra_playtime", 0).toLong())
            }
            "com.nwd.action.music.on_widget_button_click" -> when (i.getIntExtra("extra_button", -1)) {
                0 -> next(); 1 -> previous(); 2 -> toggle(); 3 -> resume(); 4 -> pause()
            }
            "com.bt.ACTION_BT_INCOMING_CALL", "com.bt.ACTION_BT_OUTGOING_NUMBER", "com.bt.ACTION_BT_BEGIN_CALL_ONLINE" ->
                if (playing) { pausedByCall = true; pause() }
            "com.bt.ACTION_BT_END_CALL" -> if (pausedByCall) { pausedByCall = false; main.postDelayed({ resume() }, 1200) }
        }
    }

    // ------------------------------------------------------------------------------------------ reporting
    private val ticker = object : Runnable {
        override fun run() {
            val c = app ?: return
            if (!playing) return
            if (Device.isNwd && prepared) {
                try {
                    c.sendBroadcast(Intent("com.nwd.action.send_media_play_time")
                        .putExtra("extra_meida_duration", position()).putExtra("extra_media_total_time", duration()))
                } catch (_: Throwable) { }
            }
            if (++ticks % 15 == 0) savePosition() // and on pause / stop: no need to write every second
            main.postDelayed(this, 1000)
        }
    }

    private fun afterStateChange() {
        val c = app ?: return
        main.removeCallbacks(ticker)
        if (playing) main.postDelayed(ticker, 1000)
        if (owner) sendPlayInfo(c, inOut = 0)
        publishSession()
        changed()
    }

    private fun sendPlayInfo(c: Context, inOut: Int) {
        if (!Device.isNwd) return
        val t = current
        try {
            c.sendBroadcast(Intent("com.nwd.action.send_media_play_info")
                .putExtra(MediaMonitor.EXTRA_SELF, true) // the home screen's player card reads Aura Music directly
                .putExtra("extra_media_app_id", 0)
                .putExtra("extra_media_app_src_inout", inOut)
                .putExtra("extra_media_current_position", (order.getOrNull(index) ?: 0))
                .putExtra("extra_media_total_size", queue.size)
                .putExtra("extra_media_name", t?.title.orEmpty())
                .putExtra("extra_media_artist", t?.artist.orEmpty())
                .putExtra("extra_media_ablum", t?.album.orEmpty())
                .putExtra("extra_media_folder_name", "Unknown")
                .putExtra("extra_media_folder_index", 0)
                .putExtra("extra_media_source", 1))
        } catch (_: Throwable) {
        }
    }

    private fun ensureSession(c: Context) {
        if (session != null) return
        session = MediaSession(c, "AuraMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = stop()
            })
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        }
    }

    private fun publishSession() {
        loadArt()
        val s = session ?: return
        val t = current
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, t?.title.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, t?.artist.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_ALBUM, t?.album.orEmpty())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration())
        artFor?.let { md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        s.setMetadata(md.build())
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position(), if (playing) 1f else 0f)
            .build())
    }

    /** Loads the current track's cover (once per album) for the session and the home screen's player card. */
    private fun loadArt() {
        val c = app ?: return
        val t = current ?: return
        if (artKey == t.albumId) return
        artKey = t.albumId
        artFor = null
        ArtLoader.get(c, t, 300) { b -> if (current?.albumId == t.albumId) { artFor = b; if (b != null) { publishSession(); changed() } } }
    }

    private var artKey = -1L
    private var artFor: android.graphics.Bitmap? = null

    /** Cover of the current track, once loaded (the home screen's player card shows it). */
    val art: android.graphics.Bitmap? get() = artFor

    // ------------------------------------------------------------------------------------------ persistence
    /** A new queue (written only when the queue itself changes: it can hold thousands of ids). */
    private fun saveQueue() {
        val ids = JSONArray()
        for (t in queue) ids.put(t.id)
        store.edit().putString("queue", ids.toString()).putInt("index", order.getOrNull(index) ?: 0).putLong("pos", 0L).apply()
    }

    private fun saveIndex() {
        store.edit().putInt("index", order.getOrNull(index) ?: 0).putLong("pos", 0L).apply()
    }

    private fun savePosition() {
        if (prepared) store.edit().putLong("pos", position()).apply()
    }

    /** The queue of the last session (by media ids), once the library is loaded. */
    fun restore() {
        if (queue.isNotEmpty() || !Library.loaded || app == null) return
        try {
            val ids = JSONArray(store.getString("queue", "[]"))
            val byId = Library.tracks.associateBy { it.id }
            val list = (0 until ids.length()).mapNotNull { byId[ids.getLong(it)] }
            if (list.isEmpty()) return
            queue = list
            buildOrder(store.getInt("index", 0).coerceIn(0, list.size - 1))
            loadArt()
            changed()
        } catch (_: Throwable) {
        }
    }
}

