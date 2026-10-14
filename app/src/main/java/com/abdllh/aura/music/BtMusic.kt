package com.abdllh.aura.music

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.abdllh.aura.BuildConfig
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Music from a phone over Bluetooth. On this firmware Bluetooth is not Android's stack: an AIC8800 module driven by
 * the GOC service package com.bt.bc03 plays the A2DP audio itself and exposes it through its exported binder
 * service (descriptor com.bt.BTFeature) and plain broadcasts — the same interface the stock "BT Music" app uses.
 *  - track:   broadcast com.bt.ACTION_AVRCP_MUSIC_ID3 (extra_avrcp_id3_title / _artist); query with code 60
 *  - state:   com.bt.ACTION_BT_MUSIC_PLAY / _PAUSE, Settings.System key_bt_a2dp_state (0 off .. 2 connected, 3 streaming)
 *  - control: playControl(int) code 23: 0 toggle, 1 pause, 2 next, 3 previous, 4 play
 *  - audio:   the service starts muted; setBtMusicMute(false) (code 39) after taking the source (app id 15) unmutes it
 *  - progress: BTPIMCallback.onGetA2dpProgress(totalSec, posSec) (code 4), from the module's "MQ" report
 * No album art, album name or seeking is available from this module: the cover is looked up online ([CoverSearch]).
 *
 * Where the song is: this unit's stack (the GOC one for the AIC8800, UartConfig bt_module=0) does not report it — the
 * stock BT music app shows its progress bar only with an RTL8761 module (persist.sys.bt.rtl8761, false here). So a
 * clock of its own: a song that follows another starts at 0 when its title arrives; pause and play stop and restart
 * the clock; "previous" after a few seconds restarts the same song (no new title then). The first song after
 * connecting (or after this process started) may already be half way: its position is unknown until the owner taps
 * the line being sung in the lyrics ([syncTo]) or the next song starts. Its length comes from the phone when the
 * module reports one, else from the catalogue the cover was found in or from the lyrics ([offerDuration]).
 */
object BtMusic {
    private const val TAG = "AuraBt"
    private const val PKG = "com.bt.bc03"
    private const val ACTION_SERVICE = "com.bt.ACTION_START_SERVICE"
    private const val DESCRIPTOR = "com.bt.BTFeature"
    private const val CALLBACK = "com.bt.BTPIMCallback"
    private const val TX_PLAY_CONTROL = 23
    private const val TX_IS_PLAYING = 24
    private const val TX_REGISTER_PIM = 33
    private const val TX_IS_A2DP = 36
    private const val TX_SET_MUTE = 39
    private const val TX_QUERY_ID3 = 60
    private const val BT_MUSIC_APP_ID = 15
    /** Where a song that just started is when its title arrives (the module asks the phone for it after the change). */
    private const val START_MS = 250L
    private const val REPEAT_SLACK_MS = 3000L
    /** "Previous" pressed: no new title within this long means the phone restarted the same song... */
    private const val PREV_CHECK_MS = 2500L
    /** ...about this long after the press. */
    private const val PREV_LAG_MS = 300L
    /** Debug builds: a progress report as the module would send it (the emulator has no module). */
    private const val ACTION_DEBUG_PROGRESS = "com.abdllh.aura.debug.BT_PROGRESS"

    const val TOGGLE = 0
    const val PAUSE = 1
    const val NEXT = 2
    const val PREVIOUS = 3
    const val PLAY = 4

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var app: Context? = null
    private var service: IBinder? = null
    private var binding = false

    var available = false
        private set
    var connected = false
        private set
    var playing = false
        private set
    var title = ""
        private set
    var artist = ""
        private set
    var phone = ""
        private set
    /** The song's length, 0 while unknown. */
    var durationSec = 0
        private set
    /** The length came from the phone (believed over a catalogue's or the lyrics'). */
    private var durationFromPhone = false
    /**
     * Where a length not from the phone came from ([FROM_CATALOGUE] … [FROM_SERVICE]; 0 none): YouTube Music's own
     * catalogue (what the phone plays from) is believed over the recording the lyrics were timed on, and that over
     * another catalogue's match.
     */
    private var durationFrom = 0
    /** The clock follows the song: its start was seen, the phone said where it is, or the owner set it ([syncTo]). */
    var positionKnown = false
        private set
    // the clock: the song was at [basePosMs] at [baseAt] (elapsedRealtime: a wall clock set by GPS would jump)
    private var basePosMs = 0L
    private var baseAt = 0L
    private var running = false
    /** The song before in this connection: "" right after connecting or starting (the next title is no seen start). */
    private var lastSong = ""
    private var prevSentAt = 0L
    private var prevSong = ""
    /** Cover of the current song (found online by title + artist), or null. */
    var art: Bitmap? = null
        private set
    private var artKey = ""

    private val song get() = "$title|$artist"

    /** The length the phone itself told (0 when none): a catalogue's guess must not steer the lyrics' search. */
    val phoneDurationSec get() = if (durationFromPhone) durationSec else 0

    /** Where the song is, in milliseconds (meaningful while [positionKnown]). */
    fun positionMs(): Long {
        val now = SystemClock.elapsedRealtime()
        var p = if (running) basePosMs + (now - baseAt) else basePosMs
        val d = durationSec * 1000L
        if (d > 0 && p > d) {
            if (durationFromPhone && running && p > d + REPEAT_SLACK_MS) {
                // played on past the phone's own length with no new title: the same song again (repeat one)
                basePosMs = p - d
                baseAt = now
                p = basePosMs
            } else p = d
        }
        return p.coerceAtLeast(0L)
    }

    private fun clockSet(ms: Long, known: Boolean) {
        basePosMs = ms
        baseAt = SystemClock.elapsedRealtime()
        positionKnown = known
    }

    /** Playing or not: the clock stops and goes on with it. */
    private fun setPlaying(p: Boolean) {
        if (p != running) {
            val now = SystemClock.elapsedRealtime()
            if (p) baseAt = now else basePosMs += now - baseAt
            running = p
        }
        playing = p
    }

    /** A new connection (or none): the next song's start is not seen, nor is where the current one is. */
    private fun forgetClock() {
        lastSong = ""
        positionKnown = false
    }

    /** The owner tapped the line sung at [ms]: that is where the song is now. */
    fun syncTo(ms: Long) {
        clockSet(ms.coerceAtLeast(0L), true)
        changed()
    }

    const val FROM_CATALOGUE = 1 // a music catalogue's match (it may be another recording, a live one...)
    const val FROM_LYRICS = 2    // the recording the lyrics were timed on (what the lines follow)
    const val FROM_SERVICE = 3   // YouTube Music's own catalogue: the song as the phone plays it

    /** A length for the song [forTitle]/[forArtist], used while the phone tells none; the better source wins ([from]). */
    fun offerDuration(sec: Int, forTitle: String, forArtist: String, from: Int) {
        if (durationFromPhone || sec !in 20..3600 || forTitle != title || forArtist != artist) return
        if (from < durationFrom) return
        durationFrom = from
        if (sec == durationSec) return
        durationSec = sec
        changed()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = main.post { for (l in listeners) l() }

    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        // (the firmware keeps the source across a restart: so does the music screen's choice)
        sourceActive = com.abdllh.aura.util.Prefs.raw.getString("musicSource", "local") == "bt"
        available = try { c.packageManager.getApplicationInfo(PKG, 0); true } catch (_: Throwable) { false }
        if (!available && !BuildConfig.DEBUG) return
        val f = IntentFilter().apply {
            addAction("com.bt.ACTION_AVRCP_MUSIC_ID3")
            addAction("com.bt.ACTION_BT_MUSIC_PLAY")
            addAction("com.bt.ACTION_BT_MUSIC_PAUSE")
            addAction("com.bt.ACTION_A2DP_ESTABLISHED")
            addAction("com.bt.ACTION_A2DP_RELEASE")
            addAction("com.bt.ACTION_AVRCP_ESTABLISHED")
            addAction("com.bt.ACTION_AVRCP_RELEASE")
            addAction("com.bt.ACTION_BT_CONNECTION_CHANGE")
            addAction("com.nwd.action.ACTION_KEY_VALUE")
            if (BuildConfig.DEBUG) addAction(ACTION_DEBUG_PROGRESS)
        }
        try { c.registerReceiver(receiver, f) } catch (t: Throwable) { Log.w(TAG, "receiver: $t") }
        if (!available) return // debug build without the module (the emulator): test broadcasts only
        try {
            c.contentResolver.registerContentObserver(Settings.System.getUriFor("key_bt_a2dp_state"), false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = readState()
            })
        } catch (_: Throwable) {
        }
        readState()
        bind()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                when (i.action) {
                    "com.bt.ACTION_AVRCP_MUSIC_ID3" -> {
                        if (BuildConfig.DEBUG && i.getBooleanExtra("sim", false)) {
                            available = true; connected = true
                            if (i.getBooleanExtra("sim_first", false)) forgetClock() // as if just connected
                            setPlaying(true)
                        }
                        val t = i.getStringExtra("extra_avrcp_id3_title").orEmpty()
                        val a = i.getStringExtra("extra_avrcp_id3_artist").orEmpty()
                        if (t != title || a != artist) { // (the module repeats the same song's info now and then)
                            // a song that follows another one starts now; the first one after connecting may be half way
                            val start = lastSong.isNotEmpty() && t.isNotBlank()
                            title = t
                            artist = a
                            durationSec = 0 // unknown until told (not the song before's)
                            durationFromPhone = false
                            durationFrom = 0
                            clockSet(if (start) START_MS else 0L, start)
                            if (t.isNotBlank()) lastSong = song
                        } else if (t.isNotBlank() && lastSong.isEmpty()) {
                            // the same song again after reconnecting: not its start, but the next title will be one
                            lastSong = song
                        }
                        findArt()
                    }
                    "com.nwd.action.ACTION_KEY_VALUE" -> {
                        // the steering wheel's or the panel's "previous" while the phone's music is the source goes
                        // straight to the module (its A2DPManager: keys 6 and 17): the same check as our own button
                        val k = (i.extras?.get("extra_key_value") as? Number)?.toInt() ?: return // (a byte; any number)
                        if ((k == 6 || k == 17) && sourceActive && connected) armPrevCheck()
                        return
                    }
                    "com.bt.ACTION_BT_MUSIC_PLAY" -> setPlaying(true)
                    "com.bt.ACTION_BT_MUSIC_PAUSE" -> setPlaying(false)
                    "com.bt.ACTION_A2DP_RELEASE" -> { connected = false; setPlaying(false); forgetClock() }
                    "com.bt.ACTION_A2DP_ESTABLISHED" -> { if (!connected) forgetClock(); connected = true }
                    "com.bt.ACTION_BT_CONNECTION_CHANGE" -> {
                        if (i.getIntExtra("extra_bt_connection_event", 0) == 1) phone = i.getStringExtra("extra_bt_device_name").orEmpty()
                        else { connected = false; setPlaying(false); forgetClock(); title = ""; artist = ""; findArt() }
                    }
                    ACTION_DEBUG_PROGRESS -> if (BuildConfig.DEBUG) { // (the emulator: a progress report as the module would send)
                        progress(i.getIntExtra("total", 0), i.getIntExtra("pos", 0))
                        return
                    }
                }
            } catch (_: Throwable) {
            }
            changed()
        }
    }

    /**
     * Looks up the current song's cover and length, once per song (the module itself never sends either) — again a
     * little later when nothing could be asked (the phone's internet comes up after the car starts: the first song
     * would otherwise go without).
     */
    private fun findArt() {
        val key = "$artist|$title"
        if (key == artKey) return
        artKey = key
        art = null
        main.removeCallbacks(artAgain)
        artTries = 0
        if (title.isBlank()) return
        lookUp(key)
    }

    private var artTries = 0
    private val artAgain = Runnable { if (artKey == "$artist|$title" && art == null) lookUp(artKey) }

    private fun lookUp(key: String) {
        val c = app ?: return
        val t = title
        val a = artist
        CoverSearch.find(c, t, a) { b, sec, yt, failed ->
            if (artKey != key) return@find
            art = b
            // (the catalogue's length, while nothing better; YouTube Music's is the song's own)
            if (sec > 0) offerDuration(sec, t, a, if (yt) FROM_SERVICE else FROM_CATALOGUE)
            if (b == null && failed && ++artTries <= 3) main.postDelayed(artAgain, 15_000L * artTries)
            changed()
        }
    }

    private fun readState() {
        val cr = app?.contentResolver ?: return
        val st = try { Settings.System.getInt(cr, "key_bt_a2dp_state", 0) } catch (_: Throwable) { 0 }
        if (st < 2 && connected) forgetClock()
        connected = st >= 2
        if (st == 3) setPlaying(true) else if (st < 2) setPlaying(false)
        phone = try { Settings.System.getString(cr, "bt_phone_name") } catch (_: Throwable) { null } ?: phone
        changed()
    }

    // ------------------------------------------------------------------------------------------ control
    /**
     * "Previous" was pressed: a phone restarts the song when it has played a few seconds (the same title: no news from
     * the module), else it goes to the song before (a new title: a seen start). Checked [PREV_CHECK_MS] later.
     */
    private fun armPrevCheck() {
        if (title.isBlank()) return
        prevSentAt = SystemClock.elapsedRealtime()
        prevSong = song
        main.removeCallbacks(prevCheck)
        main.postDelayed(prevCheck, PREV_CHECK_MS)
    }

    fun control(cmd: Int) {
        if (cmd == PREVIOUS) armPrevCheck()
        if (!transact(TX_PLAY_CONTROL) { it.writeInt(cmd) }) {
            // the firmware's broadcast uses its own numbering: 1 toggle, 2 pause, 3 next, 4 previous, 5 play
            val b = when (cmd) { TOGGLE -> 1; PAUSE -> 2; NEXT -> 3; PREVIOUS -> 4; else -> 5 }
            app?.sendBroadcast(Intent("com.nwd.ACTION_A2DP_CONTROL_COMMAND").putExtra("extra_command", b))
        }
    }

    private val prevCheck = Runnable {
        if (song != prevSong || title.isBlank()) return@Runnable // another song came: its start was seen
        val since = SystemClock.elapsedRealtime() - prevSentAt
        clockSet(if (running) (since - PREV_LAG_MS).coerceAtLeast(0L) else 0L, true)
        changed()
    }

    /**
     * The module's report of the phone's position (whole seconds). A clock that is already right within a second and a
     * half keeps running as it is: reports every second would make the lines jitter by half a second.
     */
    private fun progress(totalSec: Int, posSec: Int) {
        if (totalSec > 0) { durationSec = totalSec; durationFromPhone = true }
        val reported = posSec * 1000L + 500L // (somewhere in that second: its middle)
        if (!positionKnown || kotlin.math.abs(positionMs() - reported) > 1500L) clockSet(reported, true)
        changed()
    }

    /** The phone's music is the source AMRI chose (the panel's and the wheel's keys then go to the module). */
    private var sourceActive = false

    /** Makes the phone's music audible: take the Bluetooth music source (app id 15) and unmute the module. */
    fun activate() {
        val c = app ?: return
        sourceActive = true
        try {
            c.sendBroadcast(Intent("com.nwd.action.ACTION_APP_IN_OUT").putExtra("extra_app_id", BT_MUSIC_APP_ID)
                .putExtra("extra_app_operation", 1).putExtra("extra_app_event", 0))
            c.sendBroadcast(Intent("com.nwd.ACTION_MEDIA_PLAY").putExtra("extra_app_id", BT_MUSIC_APP_ID)
                .putExtra(com.abdllh.aura.media.MediaMonitor.EXTRA_SELF, true)) // not the stock screen (MusicKeys)
        } catch (_: Throwable) {
        }
        Player.pauseForOtherSource()
        transact(TX_SET_MUTE) { it.writeInt(0) }
        transact(TX_QUERY_ID3) { }
        if (connected && !playing) control(PLAY)
    }

    /** Leaves the Bluetooth source (local music takes over): pause the phone and mute the module. */
    fun deactivate() {
        sourceActive = false
        if (!available) return
        if (playing) control(PAUSE)
        transact(TX_SET_MUTE) { it.writeInt(1) }
    }

    // ------------------------------------------------------------------------------------------ phone calls
    /**
     * The same service holds the phone side (see phone/Calls): BTFeature.answerCall is 14, cancelCall 15 — which
     * declines a call that rings and ends one that is dialled or talked on. False when the service could not be told.
     */
    fun answerCall(): Boolean = transact(14) { }
    fun cancelCall(): Boolean = transact(15) { }

    // ------------------------------------------------------------------------------------------ binder
    private val pimCallback = object : Binder() {
        init { attachInterface(null, CALLBACK) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
            return try {
                data.enforceInterface(CALLBACK)
                if (code == 4) { // onGetA2dpProgress(totalSec, posSec)
                    val total = data.readInt()
                    val pos = data.readInt()
                    main.post { progress(total, pos) }
                }
                reply?.writeNoException()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            binding = false
            service = b
            transact(TX_REGISTER_PIM, oneway = false) { it.writeStrongBinder(pimCallback) }
            val a2dp = query(TX_IS_A2DP)
            if (a2dp != null) connected = a2dp
            query(TX_IS_PLAYING)?.let { setPlaying(it) }
            transact(TX_QUERY_ID3) { }
            changed()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
        override fun onBindingDied(name: ComponentName?) {
            service = null
            binding = false
            try { app?.unbindService(this) } catch (_: Throwable) { }
            main.postDelayed({ bind() }, 3000)
        }
    }

    private fun bind() {
        val c = app ?: return
        if (service != null || binding) return
        val i = Intent(ACTION_SERVICE).setPackage(PKG)
        try { c.startService(i) } catch (_: Throwable) { }
        binding = try { c.bindService(i, conn, Context.BIND_AUTO_CREATE) } catch (t: Throwable) { Log.w(TAG, "bind: $t"); false }
    }

    private fun query(code: Int): Boolean? {
        val b = service ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            b.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (_: Throwable) {
            null
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    /**
     * A void call. One-way by default: the result is not needed, and the module's service does slow work (UART,
     * audio focus) before it would answer, which must not stall the screen. Calls to one binder keep their order.
     */
    private fun transact(code: Int, oneway: Boolean = true, write: (Parcel) -> Unit): Boolean {
        val b = service ?: run { bind(); return false }
        val data = Parcel.obtain()
        val reply = if (oneway) null else Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            b.transact(code, data, reply, if (oneway) IBinder.FLAG_ONEWAY else 0)
            reply?.readException()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "transact $code: $t")
            if (!b.isBinderAlive) { service = null; bind() }
            false
        } finally {
            data.recycle(); reply?.recycle()
        }
    }
}
