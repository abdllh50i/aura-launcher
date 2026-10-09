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
 *  - progress: BTPIMCallback.onGetA2dpProgress(totalSec, posSec) (code 4), when the phone reports it
 * No album art, album name or seeking is available from this module: the cover is looked up online ([CoverSearch]).
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
    var durationSec = 0
        private set
    var positionSec = 0
        private set
    private var positionAt = 0L
    /** Cover of the current song (found online by title + artist), or null. */
    var art: Bitmap? = null
        private set
    private var artKey = ""

    /** Seconds played, extrapolated between the phone's progress reports. */
    fun position(): Int = if (playing && positionAt > 0) (positionSec + ((System.currentTimeMillis() - positionAt) / 1000).toInt()).coerceAtMost(durationSec.coerceAtLeast(positionSec))
        else positionSec

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = main.post { for (l in listeners) l() }

    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
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
                        if (BuildConfig.DEBUG && i.getBooleanExtra("sim", false)) { available = true; connected = true; playing = true }
                        val t = i.getStringExtra("extra_avrcp_id3_title").orEmpty()
                        val a = i.getStringExtra("extra_avrcp_id3_artist").orEmpty()
                        if (t != title || a != artist) { // (the module repeats the same song's info now and then)
                            title = t
                            artist = a
                            positionSec = 0
                            positionAt = System.currentTimeMillis()
                        }
                        findArt()
                    }
                    "com.bt.ACTION_BT_MUSIC_PLAY" -> { playing = true; positionAt = System.currentTimeMillis() }
                    "com.bt.ACTION_BT_MUSIC_PAUSE" -> { positionSec = position(); playing = false }
                    "com.bt.ACTION_A2DP_RELEASE" -> { connected = false; playing = false }
                    "com.bt.ACTION_A2DP_ESTABLISHED" -> connected = true
                    "com.bt.ACTION_BT_CONNECTION_CHANGE" -> {
                        if (i.getIntExtra("extra_bt_connection_event", 0) == 1) phone = i.getStringExtra("extra_bt_device_name").orEmpty()
                        else { connected = false; playing = false; title = ""; artist = ""; findArt() }
                    }
                }
            } catch (_: Throwable) {
            }
            changed()
        }
    }

    /** Looks up the current song's cover, once per song (the module itself never sends one). */
    private fun findArt() {
        val c = app ?: return
        val key = "$artist|$title"
        if (key == artKey) return
        artKey = key
        art = null
        if (title.isBlank()) return
        CoverSearch.find(c, title, artist) { b -> if (artKey == key) { art = b; changed() } }
    }

    private fun readState() {
        val cr = app?.contentResolver ?: return
        val st = try { Settings.System.getInt(cr, "key_bt_a2dp_state", 0) } catch (_: Throwable) { 0 }
        connected = st >= 2
        if (st == 3) playing = true else if (st < 2) playing = false
        phone = try { Settings.System.getString(cr, "bt_phone_name") } catch (_: Throwable) { null } ?: phone
        changed()
    }

    // ------------------------------------------------------------------------------------------ control
    fun control(cmd: Int) {
        if (!transact(TX_PLAY_CONTROL) { it.writeInt(cmd) }) {
            // the firmware's broadcast uses its own numbering: 1 toggle, 2 pause, 3 next, 4 previous, 5 play
            val b = when (cmd) { TOGGLE -> 1; PAUSE -> 2; NEXT -> 3; PREVIOUS -> 4; else -> 5 }
            app?.sendBroadcast(Intent("com.nwd.ACTION_A2DP_CONTROL_COMMAND").putExtra("extra_command", b))
        }
    }

    /** Makes the phone's music audible: take the Bluetooth music source (app id 15) and unmute the module. */
    fun activate() {
        val c = app ?: return
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
        if (!available) return
        if (playing) control(PAUSE)
        transact(TX_SET_MUTE) { it.writeInt(1) }
    }

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
                    main.post { durationSec = total; positionSec = pos; positionAt = System.currentTimeMillis(); for (l in listeners) l() }
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
            query(TX_IS_PLAYING)?.let { playing = it }
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
