package com.abdllh.aura.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.util.Prefs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The car's gear for the home screen (P R N D), from what the unit really knows, best first:
 *  1. the reverse wire (every reverse camera has it): Settings.System mcu_backcar_state = 1, broadcast
 *     com.android.action.ACTION_BACKCAR_STATE_CHANGE (byte extra_back_state) — R, always right;
 *  2. the CAN box, when it reports the gear ([CanGear]) — P, R, N, D. For a Changan the firmware's CAN app passes a
 *     gear on only from a Raise box that sends the generic "basic info" frame (0x7D, subtype 6); its oudi, hiworld,
 *     binary, xinbas and daojun parsers never fill it, so on most Changan set-ups no gear ever comes;
 *  3. the handbrake wire, when the unit has one ([Source.HANDBRAKE]): the MCU reports it (Settings.System
 *     mcu_hand_break, 1 = pulled; broadcast com.nwd.action.ACTION_HAND_BREAK_STATE_CHANGE, extra_hand_break_state).
 *     Pulled while standing still is P. It is believed only once it has been seen both pulled and released, and never
 *     again after it said "pulled" while the car drove on (an unwired brake input, or the factory switch that lets
 *     video play while driving, reports "pulled" for ever);
 *  4. moving forward (GPS, over 8 km/h) is D, and D stays (shown lighter, [Source.HELD]) when the car stops after
 *     driving forward, for up to [HOLD_MS] or until the reverse gear: a stop is mostly a red light or traffic;
 *  5. otherwise a guess, shown lighter ([Source.GUESS]): P when the unit comes on (a car is started in P) and after a
 *     long stop (the hold ran out, or a minute after reversing): a car standing that long with the screen on is
 *     mostly parked. P and N cannot be told apart from outside the car.
 */
object Gear {
    enum class Pos { P, R, N, D }
    enum class Source { NONE, REVERSE, CAN, HANDBRAKE, MOTION, HELD, GUESS }
    private const val HOLD_MS = 3 * 60_000L
    /** Standing this long after reversing, the gear is guessed P. */
    private const val AFTER_REVERSE_MS = 60_000L

    private const val TAG = "AuraGear"
    private const val KEY_BACKCAR = "mcu_backcar_state"
    private const val KEY_HANDBRAKE = "mcu_hand_break"
    private const val ACTION_HANDBRAKE = "com.nwd.action.ACTION_HAND_BREAK_STATE_CHANGE"
    /** Which handbrake states have been seen (bit 1 released, bit 2 pulled), and whether it lied while driving. */
    private const val PREF_HB_SEEN = "handbrakeSeen"
    private const val PREF_HB_FAKE = "handbrakeFake"
    const val DEBUG_GEAR = "com.abdllh.aura.debug.GEAR" // emulator: --es gear P|R|N|D|none, --ez reverse true|false, --ei handbrake 0|1

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var app: Context? = null
    private var reverse = false
    private var canGear: Pos? = null
    private var moving = false
    private var kmh = 0f
    private var slowSince = 0L
    private var stoppedAt = 0L      // forward motion ended then (elapsedRealtime; 0: no D to hold)
    /** The car is most likely parked: the unit just came on, or it has stood a long time. */
    private var parkedGuess = true
    /** The handbrake as the MCU last reported it (null: never reported). */
    private var handbrake: Boolean? = null
    private var pulledWhileDrivingSince = 0L
    private val holdEnd = Runnable { if (!moving && !reverse) parkedGuess = true; update() }
    private val reverseEnd = Runnable { if (!moving && !reverse) { parkedGuess = true; update() } }

    @Volatile var current: Pos? = null; private set
    @Volatile var source = Source.NONE; private set
    /** The gear shown is known, not assumed (an assumed one is drawn lighter). */
    val sure: Boolean get() = source != Source.HELD && source != Source.GUESS
    /** The CAN app has sent a gear at least once (the box reports it). */
    @Volatile var canReports = false; private set

    /** The handbrake wire, for the settings page: null never reported, else pulled or not. */
    val handbrakeState: Boolean? get() = handbrake

    /** The handbrake said "pulled" while the car drove on: it is not believed. */
    val handbrakeFake: Boolean get() = Prefs.raw.getBoolean(PREF_HB_FAKE, false)

    /** The handbrake has been seen both ways and never claimed "pulled" while driving: it can be believed. */
    val handbrakeTrusted: Boolean
        get() = Prefs.raw.getInt(PREF_HB_SEEN, 0) == 3 && !Prefs.raw.getBoolean(PREF_HB_FAKE, false)

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        reverse = readReverse(c)
        readHandbrake(c)?.let { setHandbrake(it) }
        try {
            c.contentResolver.registerContentObserver(Settings.System.getUriFor(KEY_BACKCAR), false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) { setReverse(readReverse(c)) }
            })
            c.contentResolver.registerContentObserver(Settings.System.getUriFor(KEY_HANDBRAKE), false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) { readHandbrake(c)?.let { setHandbrake(it); update() } }
            })
            c.registerReceiver(receiver, IntentFilter().apply {
                addAction("com.android.action.ACTION_BACKCAR_STATE_CHANGE")
                addAction(ACTION_HANDBRAKE)
                // after the unit slept (ignition off) a held D has long run out: timers stood still meanwhile; and a
                // car whose screen comes on is mostly a car that was just started
                addAction(Intent.ACTION_SCREEN_ON)
                if (BuildConfig.DEBUG) addAction(DEBUG_GEAR)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "init: $t")
        }
        CanGear.start(c) { raw -> canGear = fromCan(raw); if (canGear != null) canReports = true; update() }
        CarLocation.addListener(onFix)
        update()
    }

    private fun readReverse(c: Context) = try { Settings.System.getInt(c.contentResolver, KEY_BACKCAR, 0) == 1 } catch (_: Throwable) { false }

    /** The handbrake as the MCU reported it last (null: the key is not there, it never reported one). */
    private fun readHandbrake(c: Context): Boolean? = try {
        val v = Settings.System.getInt(c.contentResolver, KEY_HANDBRAKE, -1)
        if (v < 0) null else v != 0
    } catch (_: Throwable) { null }

    /** A handbrake report: kept, and the states seen so far remembered (a wire that never moves is not believed). */
    private fun setHandbrake(pulled: Boolean) {
        handbrake = pulled
        val seen = Prefs.raw.getInt(PREF_HB_SEEN, 0)
        val now = seen or (if (pulled) 2 else 1)
        if (now != seen) Prefs.raw.edit().putInt(PREF_HB_SEEN, now).apply()
        if (!pulled) pulledWhileDrivingSince = 0L
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                when (i.action) {
                    "com.android.action.ACTION_BACKCAR_STATE_CHANGE" -> {
                        val v = (i.extras?.get("extra_back_state") as? Number)?.toInt() ?: return
                        setReverse(v == 1)
                        return
                    }
                    ACTION_HANDBRAKE -> {
                        val v = (i.extras?.get("extra_hand_break_state") as? Number)?.toInt() ?: return
                        setHandbrake(v != 0)
                    }
                    Intent.ACTION_SCREEN_ON -> if (!moving && !reverse) { parkedGuess = true; stoppedAt = 0L }
                    DEBUG_GEAR -> {
                        if (i.hasExtra("reverse")) setReverse(i.getBooleanExtra("reverse", false))
                        if (i.hasExtra("kmh")) onSpeed(i.getFloatExtra("kmh", 0f)) // emulator: a moving car
                        if (i.hasExtra("handbrake")) setHandbrake(i.getIntExtra("handbrake", 0) != 0)
                        i.getStringExtra("gear")?.let { g ->
                            canGear = Pos.values().firstOrNull { it.name == g }
                            if (canGear != null) canReports = true
                        }
                    }
                }
                update()
            } catch (t: Throwable) {
                Log.w(TAG, "receive: $t")
            }
        }
    }

    /** The CAN app's gear value (Raise boxes: 1 P, 2 R, 3 N, 4 D; 11..15 the forward gears of S / M). */
    private fun fromCan(raw: Int): Pos? = when (raw) {
        1 -> Pos.P
        2 -> Pos.R
        3 -> Pos.N
        4 -> Pos.D
        in 11..15 -> Pos.D
        else -> null
    }

    private fun setReverse(on: Boolean) {
        // after reversing, the gear at the next stop is not known: a minute standing still, and it is guessed P
        main.removeCallbacks(reverseEnd)
        if (on) { stoppedAt = 0L; parkedGuess = false } else if (reverse && !moving) main.postDelayed(reverseEnd, AFTER_REVERSE_MS)
        reverse = on
        update()
    }

    private val onFix: (Location) -> Unit = fix@{ l ->
        // an old fix (the last known one at start) says nothing about now
        if (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos > 10_000_000_000L) return@fix
        val kmh = if (l.hasSpeed()) l.speed * 3.6f else 0f
        main.post { onSpeed(kmh) }
    }

    /** Main thread. */
    private fun onSpeed(kmh: Float) {
        val now = SystemClock.elapsedRealtime()
        val was = moving
        this.kmh = kmh
        if (kmh >= 8f) { moving = true; slowSince = 0L; parkedGuess = false }
        else if (kmh < 3f) {
            if (slowSince == 0L) slowSince = now
            if (now - slowSince > 4000L) moving = false
        }
        // a handbrake that says "pulled" while the car drives on is no handbrake (not wired, or the factory switch
        // that lets video play while driving): it is not believed again
        if (handbrake == true && kmh >= 20f) {
            if (pulledWhileDrivingSince == 0L) pulledWhileDrivingSince = now
            else if (now - pulledWhileDrivingSince > 15_000L && !Prefs.raw.getBoolean(PREF_HB_FAKE, false)) {
                Prefs.raw.edit().putBoolean(PREF_HB_FAKE, true).apply()
                Log.i(TAG, "the handbrake reports pulled while driving: not believed")
            }
        } else if (kmh < 20f) pulledWhileDrivingSince = 0L
        // fixes stopping (a car park underground, the GPS switched off) end "moving" too
        main.removeCallbacks(stillness)
        if (moving) main.postDelayed(stillness, 6000L)
        if (moving != was) {
            if (was) stopped() else { stoppedAt = 0L; main.removeCallbacks(holdEnd) }
        }
        update() // also ends a held D that ran out while the unit slept (no change, nothing happens)
    }

    private val stillness = Runnable { if (moving) { moving = false; stopped(); update() } }

    /** Forward motion ended: D is held for a while (unless the reverse gear is in, which ends it anyway). */
    private fun stopped() {
        if (reverse) return
        stoppedAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(holdEnd)
        main.postDelayed(holdEnd, HOLD_MS + 50)
    }

    private fun update() {
        val held = stoppedAt > 0L && SystemClock.elapsedRealtime() - stoppedAt < HOLD_MS
        val (pos, src) = when {
            reverse -> Pos.R to Source.REVERSE
            canGear != null -> canGear to Source.CAN
            !moving && handbrake == true && handbrakeTrusted -> Pos.P to Source.HANDBRAKE
            moving -> Pos.D to Source.MOTION
            held -> Pos.D to Source.HELD
            parkedGuess -> Pos.P to Source.GUESS
            else -> null to Source.NONE
        }
        if (pos == current && src == source) return
        current = pos
        source = src
        for (l in listeners) l()
    }
}
