package com.abdllh.aura.system

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.abdllh.aura.nav.CarLocation
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Parked or Moving, the table of SPEC.md section 6.0 and nothing else, with no clock and no Android of its own:
 *
 *  - 8 km/h or more for 2 s, or gear D or R known: Moving
 *  - under 5 km/h for 5 s, and gear not D or R: Parked
 *  - no speed and no gear (cold boot, no fix): Parked
 *  - no speed, gear D: Moving
 *  - between the two speeds: keeps what it was
 *
 * Starts Parked. [step] takes what is known now; the gear is only a gear read from the car, null when it is unknown or assumed. The 2 s want a second fix to say so: on the unit a fix comes every 2 s, so
 * with the last fix's speed standing for the time between, one fix's jump at a standstill would be the whole 2 s by itself. [fresh]: [kmh] comes from a new fix, not from asking again.
 */
class DriveRule {
    var moving = false
        private set
    private var fastSince = NEVER
    private var fastFixes = 0
    private var slowSince = NEVER

    fun step(kmh: Float?, gear: Gear.Pos?, now: Long, fresh: Boolean = true): Boolean {
        val forward = gear == Gear.Pos.D || gear == Gear.Pos.R
        if (kmh == null) {
            fastSince = NEVER
            slowSince = NEVER
            moving = forward
            return moving
        }
        when {
            kmh >= FAST_KMH -> {
                slowSince = NEVER
                if (fastSince == NEVER) { fastSince = now; fastFixes = 0 }
                if (fresh) fastFixes++
                if (now - fastSince >= FAST_MS && fastFixes >= 2) moving = true
            }
            kmh < SLOW_KMH -> {
                fastSince = NEVER
                if (slowSince == NEVER) slowSince = now
                if (now - slowSince >= SLOW_MS && !forward) moving = false
            }
            else -> {
                fastSince = NEVER
                slowSince = NEVER
            }
        }
        if (forward) moving = true
        return moving
    }

    /** When [step] should be asked again if nothing new comes, on the clock [step] was given; [NEVER] when nothing is pending. */
    fun wakeAt(): Long = when {
        !moving && fastSince != NEVER && fastFixes >= 2 -> fastSince + FAST_MS // (with one fix only the next one can say)
        moving && slowSince != NEVER -> slowSince + SLOW_MS
        else -> NEVER
    }

    companion object {
        const val NEVER = -1L
        const val FAST_KMH = 8f
        const val FAST_MS = 2000L
        const val SLOW_KMH = 5f
        const val SLOW_MS = 5000L
    }
}

/**
 * The one source of Parked and Moving, read by every screen, never computed twice (SPEC.md section 6.0). Its speed is the last fix's while
 * it is younger than 5 s, which is also what the design calls having GPS; its gear is [Gear.known], what the car itself says. [Gear]'s own
 * D from motion and its assumed P and D count as unknown here: they are guesses from the same speed, and a jump of the GPS at a standstill
 * must not skip the 2 s. One listener list, called on the main thread when the state changes.
 */
object DriveState {
    private const val FIX_MAX_AGE_MS = 5000L

    private val rule = DriveRule()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var kmh: Float? = null
    private var started = false

    val moving: Boolean get() = rule.moving
    val parked: Boolean get() = !rule.moving

    /** Gear P known: Music's full panel on a trip asks for this (section 6.0's table). */
    val parkKnown: Boolean get() = Gear.known == Gear.Pos.P

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun init(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        if (started) return
        started = true
        CarLocation.addListener(onFix)
        Gear.addListener(ask)
        ask()
    }

    private val onFix: (Location) -> Unit = { l ->
        if (Looper.myLooper() == main.looper) take(l) else main.post { take(l) }
    }

    private fun take(l: Location) {
        val age = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L
        main.removeCallbacks(fixGone)
        // (the last known one at a start, or a fix with no speed from the network, says nothing about now)
        if (age >= FIX_MAX_AGE_MS || !l.hasSpeed()) kmh = null
        else {
            kmh = l.speed * 3.6f
            main.postDelayed(fixGone, FIX_MAX_AGE_MS - age)
        }
        askNow(true)
    }

    private val fixGone = Runnable { kmh = null; ask() }

    private val again = Runnable { ask() }

    private val ask: () -> Unit = { askNow(false) }

    private fun askNow(newFix: Boolean) {
        val was = rule.moving
        val now = SystemClock.elapsedRealtime()
        rule.step(kmh, Gear.known, now, newFix)
        main.removeCallbacks(again)
        val wake = rule.wakeAt()
        if (wake != DriveRule.NEVER) main.postDelayed(again, (wake - now).coerceAtLeast(1L))
        if (rule.moving != was) for (l in listeners) l()
    }
}
