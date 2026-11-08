package com.abdllh.aura.system

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.database.ContentObserver
import android.media.AudioManager
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
 * The car's real volume. On the NWD unit the amplifier volume is not Android's music stream: it belongs to the
 * firmware's setting service (com.nwd.setting.service, the same path the stock floating volume bar uses):
 *  - set:     SettingFeature.setAudioParam(14 = system volume, value)  (binder code 6), range 0..mcu_max_volume
 *  - read:    Settings.System "mcu_system_volume", "mcu_max_volume" (default 40), "mcu_mute_state" (1 = muted)
 *  - mute:    broadcast com.nwd.action.ACTION_SET_MUTE, boolean extra "extra_mute"
 *  - observe: AudioSettingCallback.notifyAudioParam(type, value) (code 1) + content observers on the keys above.
 * The service is exported without a permission, so raw Binder transactions are enough (no firmware SDK needed).
 * On anything that is not an NWD unit (the emulator) it falls back to Android's music stream.
 *
 * The level (2.0 beta 4): what the row, the volume pill, Amri and the phone show is [level], 0..[maxLevel] = the firmware's
 * 0..max and above it [VolumeBoost]'s steps; [VolumeRules] decides the steps and guards the wheel's (the firmware's own) steps,
 * [VolumeLog] keeps the last 200 events. The wheel's keys stay the firmware's: AMRI only hears them (ACTION_KEY_VALUE 14 up,
 * 15 down, 2 mute) and adds what the rules say.
 *
 * Debug builds on the emulator (setprop debug.aura.fakefw 1, then start the app): a stand-in for the firmware, as below in "the
 * stand-in": its setAudioParam answers with the callback, and am broadcast -a com.abdllh.aura.debug.WHEEL --es key up|down|mute
 * [--ei repeat 8 --ei every 250] [--ei glitch 7] [--es first fw] [--ei delay 40] sends the firmware's key broadcast and takes the
 * firmware's step (from a stale value [glitch] away, its report first with first=fw); -a com.abdllh.aura.debug.FW --ei vol N
 * is a change by somebody else; -a com.abdllh.aura.debug.HEAT [--ei vol 30] [--es first fw] is the heat protection's broadcast
 * and its set.
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
    private const val ACTION_KEY = "com.nwd.action.ACTION_KEY_VALUE"
    /** The unit's heat protection (system_server's NwdManagerService, the setting service's CpuTempMonitor): extra_volume (int, 30 on his unit), extra_360apk_foreground (int). */
    private const val ACTION_HEAT = "com.nwd.action.ACTION_HIGHTEMP_TRIGGER"
    const val DEBUG_WHEEL = "com.abdllh.aura.debug.WHEEL"
    const val DEBUG_FW = "com.abdllh.aura.debug.FW"
    const val DEBUG_HEAT = "com.abdllh.aura.debug.HEAT"
    /** How long the pill says the unit is hot after its heat protection lowered the volume. */
    private const val HOT_SHOW_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val paramListeners = CopyOnWriteArrayList<(Int, Int) -> Unit>()
    @Volatile private var selfAt = -1_000_000L // when Aura itself last changed the volume or the mute
    private var app: Context? = null
    private var service: IBinder? = null
    private var binding = false
    @Volatile private var known = -1 // last volume we know of (callback on a binder thread, observer, our own set)
    @Volatile private var hotAt = -1_000_000L // when the unit's heat protection last lowered the volume (its broadcast)
    private var nwd = false
    private var fake = false
    private val rules = VolumeRules()

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        fake = BuildConfig.DEBUG && SystemProps.get("debug.aura.fakefw") == "1"
        nwd = fake || Device.isNwd && try { ctx.packageManager.getApplicationInfo(SERVICE_PKG, 0); true } catch (_: Throwable) { false }
        VolumeLog.init(ctx)
        // (Android's own stream: the emulator's volume; and muting by the wheel's key sends only the second)
        try {
            ctx.applicationContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { notifyChanged() }
            }, IntentFilter().apply {
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
            })
        } catch (t: Throwable) {
            Log.w(TAG, "receiver: $t")
        }
        if (!nwd) return
        val cr = ctx.contentResolver
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                val v = readKey(KEY_VOLUME, known)
                known = v
                fromFirmware(v, SystemClock.elapsedRealtime(), "obs")
            }
        }
        try {
            cr.registerContentObserver(Settings.System.getUriFor(KEY_VOLUME), false, obs)
            cr.registerContentObserver(Settings.System.getUriFor(KEY_MUTE), false, obs)
        } catch (t: Throwable) {
            Log.w(TAG, "observer: $t")
        }
        known = readKey(KEY_VOLUME, -1)
        try {
            ctx.applicationContext.registerReceiver(keys, IntentFilter().apply {
                addAction(ACTION_KEY)
                addAction(ACTION_HEAT)
                if (fake) { addAction(DEBUG_WHEEL); addAction(DEBUG_FW); addAction(DEBUG_HEAT) }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "keys: $t")
        }
        rules.max = max()
        rules.heatCaps = HEAT_PROPS.map { (k, d) -> SystemProps.get(k, d.toString()).trim().toIntOrNull() ?: d }.filter { it in 1..rules.max }.toSet()
        val acts = rules.start(volume(), if (off()) 0 else VolumeBoost.saved(ctx))
        VolumeLog.add("start", known, if (fake) "stand-in firmware" else if (off()) "kill switch on: no boost, no guard" else null, rules.level(), rules.fw, rules.boost)
        run(acts)
        if (!fake) bind()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() = main.post { for (l in listeners) l() }

    /** Every audio parameter the firmware reports (type, value), on the main thread: 14 media, 15 navigation, 16 phone. */
    fun addParamListener(l: (Int, Int) -> Unit) { paramListeners.add(l) }

    /** Did Aura itself change the volume or the mute just now (its row, its voice)? */
    fun changedByAura(withinMs: Long = 1500L): Boolean = SystemClock.elapsedRealtime() - selfAt < withinMs

    /** When the unit's heat protection last lowered the volume (elapsedRealtime; the pill shows its word while [hot]). */
    fun hotAt(): Long = hotAt
    fun hot(): Boolean = SystemClock.elapsedRealtime() - hotAt < HOT_SHOW_MS

    /** The heat caps' properties and their defaults (NwdManagerService: asp/dsp; the setting service's CpuTempMonitor: volume/volume2). */
    private val HEAT_PROPS = listOf("persist.warntemp.asp.volume" to 30, "persist.warntemp.dsp.volume" to 20, "persist.warntemp.volume" to 30, "persist.warntemp.volume2" to 20)

    // ------------------------------------------------------------------------------------------ public API
    /** The firmware's top (40 on his unit). */
    fun max(): Int = if (nwd) readKey(KEY_MAX, 40).coerceAtLeast(1) else am()?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15

    /** The firmware's own volume, 0..[max]. */
    fun volume(): Int {
        if (!nwd) return am()?.let { if (it.isStreamMute(AudioManager.STREAM_MUSIC)) 0 else it.getStreamVolume(AudioManager.STREAM_MUSIC) } ?: 0
        if (known < 0) known = readKey(KEY_VOLUME, 0)
        return known.coerceIn(0, max())
    }

    /** The top of the scale everybody shows: the firmware's and the boost's steps above it (50 on his unit). */
    fun maxLevel(): Int = if (nwd) max() + VolumeRules.BOOST_STEPS else max()

    /** The steps of boost above the firmware's top now (0 below it). */
    fun boost(): Int {
        if (!nwd) return 0
        return if (volume() >= max()) rules.boost else 0
    }

    /** The number the row, the pill, Amri and the phone show: 0..[maxLevel]. */
    fun level(): Int {
        val v = volume()
        return if (nwd && v >= max()) max() + rules.boost else v
    }

    fun muted(): Boolean = if (nwd) readKey(KEY_MUTE, 0) == 1 else am()?.isStreamMute(AudioManager.STREAM_MUSIC) == true

    /** AMRI's own choice (the row, Amri, the phone), on the level's scale; the level it goes to. */
    fun setLevel(n: Int): Int {
        val want = n.coerceIn(0, maxLevel())
        if (!nwd) { setFirmware(want); return want }
        // the rules live on the main thread; there at once (the row's held button reads the new level right after)
        if (Looper.myLooper() == Looper.getMainLooper()) applyLevel(want) else main.post { applyLevel(want) }
        return want
    }

    fun stepLevel(delta: Int): Int = setLevel(level() + delta)

    private fun applyLevel(want: Int) {
        rules.max = max()
        run(rules.setLevel(want, SystemClock.elapsedRealtime()))
        notifyChanged()
    }

    fun setMuted(mute: Boolean) {
        selfAt = SystemClock.elapsedRealtime()
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

    /** For the Screen check: the level, the boost's effect and the last events. */
    fun report(): org.json.JSONObject = org.json.JSONObject()
        .put("level", level()).put("maxLevel", maxLevel()).put("firmware", volume()).put("firmwareMax", max())
        .put("muted", muted()).put("boost", VolumeBoost.state()).put("standIn", fake).put("nwdPath", nwd)
        .put("heatCaps", org.json.JSONArray(rules.heatCaps.sorted())).put("hotNow", rules.hot(SystemClock.elapsedRealtime()))
        .put("lastHeatSecondsAgo", if (hotAt < 0) org.json.JSONObject.NULL else (SystemClock.elapsedRealtime() - hotAt) / 1000)
        .put("events", VolumeLog.json())

    // ------------------------------------------------------------------------------------------ the rules
    /** The firmware's value as it reports it ([src] cb: its callback, obs: its setting), on the main thread. */
    private fun fromFirmware(v: Int, t: Long, src: String) {
        rules.max = max()
        // logged as reported, before AMRI's answer to it (which the next lines show)
        VolumeLog.add(src, v, null, if (v >= rules.max) rules.max + rules.boost else v, v, rules.boost)
        val acts = rules.firmware(v, t)
        if (!off()) run(acts)
        notifyChanged()
    }

    private val keys = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try { // anything can send these: a bad extra must not take the home screen down
                when (i.action) {
                    ACTION_KEY -> {
                        val k = (i.extras?.get("extra_key_value") as? Number)?.toInt() ?: return
                        val t = SystemClock.elapsedRealtime()
                        when (k) {
                            14 -> onKey(+1, t)
                            15 -> onKey(-1, t)
                            2 -> { rules.muteKey(); VolumeLog.add("key-mute", null, null, rules.level(), rules.fw, rules.boost) }
                        }
                    }
                    ACTION_HEAT -> onHeat((i.extras?.get("extra_volume") as? Number)?.toInt() ?: 0, i.extras?.get("extra_360apk_foreground"))
                    DEBUG_WHEEL -> if (fake) standInWheel(i)
                    DEBUG_FW -> if (fake) standInReport(i.getIntExtra("vol", volume()))
                    DEBUG_HEAT -> if (fake) standInHeat(i.getIntExtra("vol", 30), i.getStringExtra("first") == "fw")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "key: $t")
            }
        }
    }

    /** The kill switch: the stock launcher is the home screen, and the wheel is the firmware's alone (no boost, no guard). */
    private fun off() = SystemProps.get(Device.DISABLED_PROP) == "1"

    private fun onKey(dir: Int, t: Long) {
        rules.max = max()
        if (off()) {
            VolumeLog.add(if (dir > 0) "key-up" else "key-down", null, "kill switch on: left to the firmware", rules.level(), rules.fw, rules.boost)
            if (rules.boost > 0) run(rules.setLevel(rules.max, t)) // a boost left from before: released
            return
        }
        val acts = rules.key(dir, muted(), t)
        VolumeLog.add(if (dir > 0) "key-up" else "key-down", null, null, rules.level(), rules.fw, rules.boost)
        run(acts)
        if (acts.any { it is VolumeRules.Act.Boost || it is VolumeRules.Act.Firmware }) notifyChanged()
    }

    /**
     * The unit's heat protection lowers the volume to [v]: never fought (no boost, the guard leaves it), shown by the pill, and logged
     * with every thermal zone's temperature (read off the main thread).
     */
    private fun onHeat(v: Int, fg: Any?) {
        val t = SystemClock.elapsedRealtime()
        hotAt = t
        rules.max = max()
        run(rules.heat(v, t))
        notifyChanged()
        Thread {
            val zones = try {
                val a = com.abdllh.aura.diag.Collect.thermal()
                (0 until a.length()).joinToString(", ") { n -> a.getJSONObject(n).let { "${it.optString("zone")} ${it.opt("c")}" } }
            } catch (e: Throwable) { "zones: $e" }
            VolumeLog.add("heat-temps", v, "360 app in front: ${fg ?: "?"}; $zones", rules.level(), rules.fw, rules.boost)
        }.start()
    }

    private fun run(acts: List<VolumeRules.Act>) {
        for (a in acts) when (a) {
            is VolumeRules.Act.Firmware -> {
                setFirmware(a.value)
                VolumeLog.add("self", a.value, a.why, rules.level(), rules.fw, rules.boost)
            }
            is VolumeRules.Act.Boost -> {
                app?.let { VolumeBoost.apply(it, a.steps) }
                val back = VolumeBoost.readBack()?.let { ", engine reads +${String.format(java.util.Locale.US, "%.1f", it)}" } ?: ""
                val db = String.format(java.util.Locale.US, "%.1f", VolumeBoost.gainDb())
                VolumeLog.add("boost", a.steps, "${a.why} (${VolumeBoost.kind}, +$db dB$back)", rules.level(), rules.fw, rules.boost)
            }
            is VolumeRules.Act.Recheck -> main.postDelayed({
                val more = rules.recheck(SystemClock.elapsedRealtime())
                if (more.isNotEmpty()) { run(more); notifyChanged() }
            }, a.afterMs)
            is VolumeRules.Act.Note -> VolumeLog.add(a.kind, null, a.text, rules.level(), rules.fw, rules.boost)
        }
    }

    /** The firmware's volume, set by AMRI (setAudioParam 14). */
    private fun setFirmware(v: Int) {
        selfAt = SystemClock.elapsedRealtime()
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
        if (fake) { main.postDelayed({ standInReport(value) }, 30); notifyChanged(); return }
        if (!transact(TX_SET_AUDIO_PARAM) { it.writeInt(PARAM_SYSTEM_VOLUME); it.writeInt(value) }) {
            // service not bound yet: the firmware's broadcast does the same absolute set
            app?.sendBroadcast(Intent("com.nwd.action.ACTION_REQUEST_SET_SYSTEM_VOLUME").putExtra("extra_volume", value))
        }
        notifyChanged()
    }

    // ------------------------------------------------------------------------------------------ the stand-in (debug, emulator)
    /** What the firmware does for a wheel key: its broadcast, and its own step from its audio IC's value ([glitch]: a stale one). */
    private fun standInWheel(i: Intent) {
        val c = app ?: return
        val key = i.getStringExtra("key") ?: "up"
        val code = when (key) { "up" -> 14; "down" -> 15; else -> 2 }
        val repeat = i.getIntExtra("repeat", 1).coerceIn(1, 60)
        val every = i.getIntExtra("every", 250).toLong().coerceAtLeast(30L)
        val glitch = i.getIntExtra("glitch", 0)
        val fwFirst = i.getStringExtra("first") == "fw"
        val delay = i.getIntExtra("delay", 40).toLong().coerceAtLeast(0L)
        for (n in 0 until repeat) {
            main.postDelayed({
                val bc = Intent(ACTION_KEY).setPackage(c.packageName).putExtra("extra_key_type", 0.toByte()).putExtra("extra_key_value", code.toByte())
                val step = Runnable {
                    if (code == 2) return@Runnable
                    val cur = known.coerceAtLeast(0)
                    val ic = if (n == 0 && glitch != 0) (if (code == 14) cur - glitch else cur + glitch) else cur
                    standInReport((if (code == 14) ic + 1 else ic - 1).coerceIn(0, max()))
                }
                if (fwFirst) { step.run(); main.postDelayed({ c.sendBroadcast(bc) }, delay) }
                else { c.sendBroadcast(bc); main.postDelayed(step, delay) }
            }, n * every)
        }
    }

    /** The heat protection's broadcast and the setting service's set to [v] when above it ([fwFirst]: the set reported first). */
    private fun standInHeat(v: Int, fwFirst: Boolean) {
        val c = app ?: return
        val bc = Intent(ACTION_HEAT).setPackage(c.packageName).putExtra("extra_volume", v).putExtra("extra_360apk_foreground", 0)
        val set = Runnable { if (known > v) standInReport(v) }
        if (fwFirst) { set.run(); main.postDelayed({ c.sendBroadcast(bc) }, 60) }
        else { c.sendBroadcast(bc); main.postDelayed(set, 60) }
    }

    /** The stand-in's notifyAudioParam(14, v). */
    private fun standInReport(v: Int) {
        known = v
        fromFirmware(v, SystemClock.elapsedRealtime(), "cb")
        if (paramListeners.isNotEmpty()) for (l in paramListeners) l(PARAM_SYSTEM_VOLUME, v)
    }

    // ------------------------------------------------------------------------------------------ binder
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK_DESCRIPTOR); return true }
            return try {
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                if (code == 1) { // notifyAudioParam(type, value)
                    val type = data.readInt()
                    val value = data.readInt()
                    if (type == PARAM_SYSTEM_VOLUME) {
                        known = value
                        val t = SystemClock.elapsedRealtime()
                        main.post { fromFirmware(value, t, "cb") }
                    }
                    if (paramListeners.isNotEmpty()) main.post { for (l in paramListeners) l(type, value) }
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
        if (fake || service != null || binding) return
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
