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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The car's gear for the home screen (P R N D), from what the unit really knows, best first:
 *  1. the reverse wire (every reverse camera has it): Settings.System mcu_backcar_state = 1, broadcast
 *     com.android.action.ACTION_BACKCAR_STATE_CHANGE (byte extra_back_state) — R, always right;
 *  2. the CAN box, when it reports the gear ([CanGear]) — P, R, N, D;
 *  3. without it: moving forward (GPS, over 8 km/h) is D. Standing still without the CAN gear is unknown (nothing lit):
 *     P, N and D at a red light cannot be told apart from outside the car.
 */
object Gear {
    enum class Pos { P, R, N, D }
    enum class Source { NONE, REVERSE, CAN, MOTION }

    private const val TAG = "AuraGear"
    private const val KEY_BACKCAR = "mcu_backcar_state"
    const val DEBUG_GEAR = "com.abdllh.aura.debug.GEAR" // emulator: --es gear P|R|N|D|none, --ez reverse true|false

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var app: Context? = null
    private var reverse = false
    private var canGear: Pos? = null
    private var moving = false
    private var slowSince = 0L

    @Volatile var current: Pos? = null; private set
    @Volatile var source = Source.NONE; private set
    /** The CAN app has sent a gear at least once (the box reports it). */
    @Volatile var canReports = false; private set

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        reverse = readReverse(c)
        try {
            c.contentResolver.registerContentObserver(Settings.System.getUriFor(KEY_BACKCAR), false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) { reverse = readReverse(c); update() }
            })
            c.registerReceiver(receiver, IntentFilter().apply {
                addAction("com.android.action.ACTION_BACKCAR_STATE_CHANGE")
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

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                when (i.action) {
                    "com.android.action.ACTION_BACKCAR_STATE_CHANGE" -> {
                        val v = (i.extras?.get("extra_back_state") as? Number)?.toInt() ?: return
                        reverse = v == 1
                    }
                    DEBUG_GEAR -> {
                        if (i.hasExtra("reverse")) reverse = i.getBooleanExtra("reverse", false)
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

    private val onFix: (Location) -> Unit = fix@{ l ->
        // an old fix (the last known one at start) says nothing about now
        if (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos > 10_000_000_000L) return@fix
        val kmh = if (l.hasSpeed()) l.speed * 3.6f else 0f
        val now = SystemClock.elapsedRealtime()
        val was = moving
        if (kmh >= 8f) { moving = true; slowSince = 0L }
        else if (kmh < 3f) {
            if (slowSince == 0L) slowSince = now
            if (now - slowSince > 4000L) moving = false
        }
        // fixes stopping (a car park underground, the GPS switched off) end "moving" too
        main.removeCallbacks(stillness)
        if (moving) main.postDelayed(stillness, 6000L)
        if (moving != was) main.post { update() }
    }

    private val stillness = Runnable { if (moving) { moving = false; update() } }

    private fun update() {
        val (pos, src) = when {
            reverse -> Pos.R to Source.REVERSE
            canGear != null -> canGear to Source.CAN
            moving -> Pos.D to Source.MOTION
            else -> null to Source.NONE
        }
        if (pos == current && src == source) return
        current = pos
        source = src
        for (l in listeners) l()
    }
}
