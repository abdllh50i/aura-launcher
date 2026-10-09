package com.abdllh.aura.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The car's real volume. On the NWD unit the amplifier volume is not Android's music stream: it belongs to the
 * firmware's setting service (com.nwd.setting.service, the same path the stock floating volume bar uses):
 *  - set:     SettingFeature.setAudioParam(14 = system volume, value)  (binder code 6), range 0..mcu_max_volume
 *  - read:    Settings.System "mcu_system_volume", "mcu_max_volume" (default 40), "mcu_mute_state" (1 = muted)
 *  - mute:    broadcast com.nwd.action.ACTION_SET_MUTE, boolean extra "extra_mute"
 *  - observe: AudioSettingCallback.notifyAudioParam(type, value) (code 1) + content observers on the keys above.
 * The service is exported without a permission, so raw Binder transactions are enough (no firmware SDK needed).
 * On anything that is not an NWD unit (the emulator) it falls back to Android's music stream.
 */
object CarAudio {
    private const val TAG = "AuraAudio"
    private const val SERVICE_ACTION = "com.nwd.setting.service.ACTION_SETTING_SERVICE"
    private const val SERVICE_PKG = "com.nwd.setting.service"
    private const val DESCRIPTOR = "com.nwd.setting.service.SettingFeature"
    private const val CALLBACK_DESCRIPTOR = "com.nwd.setting.service.AudioSettingCallback"
    private const val TX_SET_AUDIO_PARAM = 6
    private const val TX_SET_MUTE = 10
    private const val TX_REGISTER_CALLBACK = 24
    private const val TX_UNREGISTER_CALLBACK = 25
    private const val PARAM_SYSTEM_VOLUME = 14
    private const val KEY_VOLUME = "mcu_system_volume"
    private const val KEY_MAX = "mcu_max_volume"
    private const val KEY_MUTE = "mcu_mute_state"

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var app: Context? = null
    private var service: IBinder? = null
    private var binding = false
    @Volatile private var known = -1 // last volume we know of (callback on a binder thread, observer, our own set)
    private var nwd = false

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        nwd = Device.isNwd && try { ctx.packageManager.getApplicationInfo(SERVICE_PKG, 0); true } catch (_: Throwable) { false }
        if (!nwd) return
        val cr = ctx.contentResolver
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                known = readKey(KEY_VOLUME, known)
                notifyChanged()
            }
        }
        try {
            cr.registerContentObserver(Settings.System.getUriFor(KEY_VOLUME), false, obs)
            cr.registerContentObserver(Settings.System.getUriFor(KEY_MUTE), false, obs)
        } catch (t: Throwable) {
            Log.w(TAG, "observer: $t")
        }
        known = readKey(KEY_VOLUME, -1)
        bind()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() = main.post { for (l in listeners) l() }

    // ------------------------------------------------------------------------------------------ public API
    fun max(): Int = if (nwd) readKey(KEY_MAX, 40).coerceAtLeast(1) else am()?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15

    fun volume(): Int {
        if (!nwd) return am()?.let { if (it.isStreamMute(AudioManager.STREAM_MUSIC)) 0 else it.getStreamVolume(AudioManager.STREAM_MUSIC) } ?: 0
        if (known < 0) known = readKey(KEY_VOLUME, 0)
        return known.coerceIn(0, max())
    }

    fun muted(): Boolean = if (nwd) readKey(KEY_MUTE, 0) == 1 else am()?.isStreamMute(AudioManager.STREAM_MUSIC) == true

    fun setVolume(v: Int) {
        val value = v.coerceIn(0, max())
        if (!nwd) {
            val a = am() ?: return
            a.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
            if (value > 0 && a.isStreamMute(AudioManager.STREAM_MUSIC)) a.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            notifyChanged()
            return
        }
        known = value
        if (value > 0 && muted()) setMuted(false) // a level picked while muted means "sound back on"
        if (!transact(TX_SET_AUDIO_PARAM) { it.writeInt(PARAM_SYSTEM_VOLUME); it.writeInt(value) }) {
            // service not bound yet: the firmware's broadcast does the same absolute set
            app?.sendBroadcast(Intent("com.nwd.action.ACTION_REQUEST_SET_SYSTEM_VOLUME").putExtra("extra_volume", value))
        }
        notifyChanged()
    }

    fun step(delta: Int) = setVolume(volume() + delta)

    fun setMuted(mute: Boolean) {
        if (!nwd) {
            am()?.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            notifyChanged()
            return
        }
        if (!transact(TX_SET_MUTE) { it.writeInt(if (mute) 1 else 0) }) {
            app?.sendBroadcast(Intent("com.nwd.action.ACTION_SET_MUTE").putExtra("extra_mute", mute))
        }
        main.postDelayed({ notifyChanged() }, 300)
    }

    fun toggleMute() = setMuted(!muted())

    // ------------------------------------------------------------------------------------------ binder
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK_DESCRIPTOR); return true }
            return try {
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                if (code == 1) { // notifyAudioParam(type, value)
                    val type = data.readInt()
                    val value = data.readInt()
                    if (type == PARAM_SYSTEM_VOLUME) { known = value; notifyChanged() }
                }
                reply?.writeNoException()
                true
            } catch (t: Throwable) {
                false
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            binding = false
            service = b
            transact(TX_REGISTER_CALLBACK, oneway = false) { it.writeStrongBinder(callback) }
            known = readKey(KEY_VOLUME, known)
            notifyChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }

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
        binding = try {
            c.bindService(Intent(SERVICE_ACTION).setPackage(SERVICE_PKG), connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.w(TAG, "bind: $t")
            false
        }
    }

    /** A void call; one-way by default (a slider drag sends many, and a stalled service must not freeze the screen). */
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
            data.recycle()
            reply?.recycle()
        }
    }

    private fun readKey(key: String, def: Int): Int = try {
        Settings.System.getInt(app?.contentResolver, key, def)
    } catch (_: Throwable) {
        def
    }

    private fun am(): AudioManager? = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
}
