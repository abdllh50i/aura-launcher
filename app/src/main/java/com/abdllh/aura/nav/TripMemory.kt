package com.abdllh.aura.nav

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.link.TripCard
import com.abdllh.aura.phone.Calls
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.voice.Amri
import org.json.JSONObject
import java.io.File

/**
 * The trip being driven, kept on disk so that it outlives the process: an update installed on the way, a crash, and (the
 * owner powers the unit off with the car) a fuel stop. Written when a trip starts and once a minute while it runs, gone when
 * it ends. At the next start of the process [resume] brings it back with a new route from where the car is then, if
 * [TripRule] agrees it is still the same drive, and says what it did in the Screen check's report ([NavNote]). In the same boot (an
 * update, a crash) the trip just goes on. After a power-off the car has been parked: it may well be the end of the drive, so
 * the card asks first ([ASK_MS], no answer is no), and nothing is said until he answers yes.
 *
 * It may never get in the way: nothing runs when nothing is kept; any failure leaves no trip; and a kept trip that takes the
 * process down is tried twice ([MAX_TRIES]) and then dropped, so it cannot walk the home screen into the crash guard.
 */
internal object TripMemory {
    private const val TAG = "AuraTrip"
    private const val KEY = "navTrip"
    private const val BEAT_MS = 60_000L
    /** The fix a cold start waits for (GPS from cold can take minutes), and how often a route is tried. */
    private const val WAIT_FIX_MS = 4 * 60_000L
    private const val ROUTE_TRIES = 3
    private const val ROUTE_RETRY_MS = 20_000L
    /** How long the card asks whether to go on. */
    private const val ASK_MS = 15_000L

    const val MAX_TRIES = 2

    // ------------------------------------------------------------------------------------------ on disk
    /**
     * [at]: when, by the fix's clock where there is one (GPS's is true from the first fix on, the unit's may not be yet).
     * [remember]: the place may go to the recent places (no for Apple's: it is kept here only as long as its trip runs).
     */
    private class Kept(val dest: Place, val boot: String, val at: Long, val elapsed: Long, val pos: LatLon?, val tries: Int, val remember: Boolean)

    private var beatAt = 0L
    private var remember = true

    /** A trip started (NavSession): the tries go on counting when it is the resumed one, until it has run for a while. */
    fun started(dest: Place, fix: Location?, mayRemember: Boolean) {
        val tries = if (handingOver) read()?.tries ?: 0 else 0
        if (!handingOver) cancelResume()
        remember = mayRemember
        write(now(dest, fix, tries))
        beatAt = SystemClock.elapsedRealtime()
    }

    /** Every fix of a trip: on disk at most once a minute. A trip that has run this long is not what takes the process down. */
    fun beat(dest: Place, fix: Location) {
        val now = SystemClock.elapsedRealtime()
        if (now - beatAt < BEAT_MS) return
        beatAt = now
        write(now(dest, fix, 0))
    }

    /** The trip ended: arrived, or ended by the driver. */
    fun forget() {
        cancelResume()
        try { Prefs.raw.edit().remove(KEY).apply() } catch (t: Throwable) { Log.w(TAG, "forget: $t") }
    }

    private fun now(dest: Place, fix: Location?, tries: Int) = Kept(dest, bootId(), fix?.time ?: System.currentTimeMillis(),
        SystemClock.elapsedRealtime(), fix?.let { LatLon(it.latitude, it.longitude) }, tries, remember)

    private fun write(k: Kept, commit: Boolean = false) {
        try {
            val o = JSONObject().put("dest", k.dest.toJson()).put("boot", k.boot).put("at", k.at).put("elapsed", k.elapsed).put("tries", k.tries).put("remember", k.remember)
            if (k.pos != null) o.put("lat", k.pos.lat).put("lon", k.pos.lon)
            val e = Prefs.raw.edit().putString(KEY, o.toString())
            if (commit) e.commit() else e.apply()
        } catch (t: Throwable) {
            Log.w(TAG, "write: $t")
        }
    }

    private fun read(): Kept? = try {
        Prefs.raw.getString(KEY, null)?.let { s ->
            val o = JSONObject(s)
            val dest = Place.fromJson(o.optJSONObject("dest")) ?: return@let null
            val pos = if (o.has("lat") && o.has("lon")) LatLon(o.getDouble("lat"), o.getDouble("lon")) else null
            Kept(dest, o.optString("boot"), o.optLong("at"), o.optLong("elapsed"), pos, o.optInt("tries"), o.optBoolean("remember", true))
        }
    } catch (t: Throwable) {
        Log.w(TAG, "unreadable: $t")
        null
    }

    private fun bootId() = try { File("/proc/sys/kernel/random/boot_id").readText().trim() } catch (_: Throwable) { "" }

    // ------------------------------------------------------------------------------------------ at the process's start
    private val main = Handler(Looper.getMainLooper())
    private var resuming = false
    private var asking = false
    private var handingOver = false
    private var listener: ((Location) -> Unit)? = null

    /** At the process's start: a trip was kept? When not, this is all it does. */
    fun resume(ctx: Context) {
        try {
            val c = ctx.applicationContext
            if (!Prefs.raw.contains(KEY)) return
            val k = read()
            if (k == null) { forget(); return }
            if (k.tries >= MAX_TRIES) {
                NavNote.add("trip to ${k.dest.name} not resumed: tried $MAX_TRIES times")
                forget()
                return
            }
            // (a trip of the driver's own, begun or asked for in these first seconds: what it starts takes this one's place)
            if (NavSession.state != NavSession.State.IDLE || Trips.attempt != null) return
            if (!CarLocation.hasPermission(c)) { giveUp("no location permission"); return }
            val boot = bootId()
            val sameBoot = boot.isNotEmpty() && boot == k.boot
            // before anything else can go wrong (commit: a crash right after must count)
            write(Kept(k.dest, k.boot, k.at, k.elapsed, k.pos, k.tries + 1, k.remember), commit = true)
            Log.i(TAG, "a trip was kept (${if (sameBoot) "same boot" else "another boot"}): waiting for a fix")
            resuming = true
            CarLocation.acquire(c, HOLDER)
            val l: (Location) -> Unit = { fix -> onFix(c, k, sameBoot, fix) }
            listener = l
            CarLocation.addListener(l)
            main.postDelayed(noFix, WAIT_FIX_MS)
            CarLocation.last?.let { onFix(c, k, sameBoot, it) }
        } catch (t: Throwable) {
            Log.w(TAG, "resume: $t")
            giveUp("failed: $t")
        }
    }

    private val noFix = Runnable { giveUp("no fix") }

    private fun onFix(c: Context, k: Kept, sameBoot: Boolean, fix: Location) {
        if (listener == null) return
        // (nothing over the camera or a call's card: it goes, and the next fix after them looks and asks again)
        if (asking) {
            if (blocked()) { asking = false; TripCard.hide() }
            return
        }
        // (a last-known position is no news of where the car is now; after a power-off only GPS's own clock is true)
        val age = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000L
        if (age > 60_000L) return
        if (!sameBoot && fix.provider != LocationManager.GPS_PROVIDER && !BuildConfig.DEBUG) return
        val here = LatLon(fix.latitude, fix.longitude)
        val v = TripRule.verdict(sameBoot, k.elapsed, k.at, SystemClock.elapsedRealtime(), fix.time, Geo.distance(here, k.dest.pos),
            k.pos?.let { Geo.distance(here, it) } ?: 0.0)
        Log.i(TAG, "kept trip to '${k.dest.name}': $v")
        if (v != TripRule.Verdict.RESUME) { giveUp(v.name.lowercase()); return }
        if (!sameBoot) {
            if (!blocked()) ask(c, k)
            return
        }
        go(c, k, true, fix)
    }

    private fun go(c: Context, k: Kept, sameBoot: Boolean, fix: Location) {
        stopWaiting()
        val moving = fix.hasBearing() && fix.hasSpeed() && fix.speed > 2f
        askRoute(c, k, sameBoot, LatLon(fix.latitude, fix.longitude), if (moving) fix.bearing.toDouble() else null, ROUTE_TRIES)
    }

    private fun blocked() = Amri.reversing || Amri.inCall || Calls.busy

    /** After a power-off: does he want to go on? The fixes go on coming, so what he says starts from where the car is by then. */
    private fun ask(c: Context, k: Kept) {
        val w = LocaleHelper.wrap(c)
        asking = true
        val shown = TripCard.show(c, w.getString(R.string.trip_resume_caption), w.getString(R.string.trip_resume_q, k.dest.name), null, listOf(
            TripCard.Action(w.getString(R.string.trip_resume_yes), true) {
                if (asking) {
                    asking = false
                    val fix = CarLocation.last
                    if (fix == null) giveUp("no fix") else go(c, k, false, fix)
                }
            },
            TripCard.Action(w.getString(R.string.link_no), false) { if (asking) { asking = false; giveUp("declined") } }
        ), ASK_MS) { if (asking) { asking = false; giveUp("no answer") } }
        if (!shown) { asking = false; giveUp("no card to ask with"); return }
        // (another card may have taken the card's place, and with it its time: this ends the question all the same)
        main.removeCallbacks(askOver)
        main.postDelayed(askOver, ASK_MS + 2_000L)
    }

    private val askOver = Runnable { if (asking) { asking = false; giveUp("no answer") } }

    private fun askRoute(c: Context, k: Kept, sameBoot: Boolean, here: LatLon, heading: Double?, tries: Int) {
        Router.route(here, k.dest.pos, heading) { r, err ->
            when {
                !resuming -> {} // (the driver ended it, or another trip began)
                NavSession.state != NavSession.State.IDLE || Trips.attempt != null -> finish()
                r != null -> try {
                    handingOver = true
                    Trips.start(c, k.dest, r, Trips.RESUMED, k.remember)
                    NavNote.add("trip to ${k.dest.name} resumed (${if (sameBoot) "same boot" else "after a power-off"})")
                    finish()
                } catch (t: Throwable) {
                    Log.w(TAG, "start: $t")
                    giveUp("failed: $t")
                } finally {
                    handingOver = false
                }
                // (another asker took the engine: no answer yet, and no try spent)
                err == Router.NO_ANSWER_YET -> Router.afterOthers { if (resuming) askRoute(c, k, sameBoot, CarLocation.last?.let { LatLon(it.latitude, it.longitude) } ?: here, heading, tries) }
                // (from where the car is by then: it may be driving)
                tries > 1 -> main.postDelayed({ if (resuming) askRoute(c, k, sameBoot, CarLocation.last?.let { LatLon(it.latitude, it.longitude) } ?: here, heading, tries - 1) }, ROUTE_RETRY_MS)
                else -> giveUp("no route: $err")
            }
        }
    }

    private fun stopWaiting() {
        main.removeCallbacks(noFix)
        listener?.let { CarLocation.removeListener(it) }
        listener = null
    }

    private fun finish() {
        stopWaiting()
        main.removeCallbacks(askOver)
        if (asking) { asking = false; TripCard.hide() }
        resuming = false
        CarLocation.release(HOLDER)
    }

    private fun giveUp(why: String) {
        val k = read()
        Log.i(TAG, "kept trip not resumed: $why")
        if (k != null) NavNote.add("trip to ${k.dest.name} not resumed: $why")
        finish()
        try { Prefs.raw.edit().remove(KEY).apply() } catch (_: Throwable) { }
    }

    /** The driver started another trip, or ended this one, while it was being brought back. */
    private fun cancelResume() {
        if (resuming) finish()
    }

    /** The phone's or the voice's "stop" while a kept trip is on its way back: it does not come. True when one was. */
    fun dropResume(by: String): Boolean {
        if (!resuming) return false
        giveUp("stopped by $by")
        return true
    }

    private const val HOLDER = "resume"
}

/** Whether a kept trip is still the same drive ([TripMemory]). No Android in here. */
internal object TripRule {
    /** A fuel stop, or the time a restart takes. Beyond it, guidance starting by itself would surprise. */
    const val RESUME_WITHIN_MS = 30 * 60_000L
    /** So close to the place, he is there: parked before the arrival was noticed. */
    const val THERE_M = 300.0
    /** The same after a power-off: he has parked, and the place's pin is rarely where the car ends up. */
    const val THERE_PARKED_M = 1000.0
    /** The unit was off, so the car stood: a fix this far from where it stopped is a wrong fix, or it was towed. */
    const val STOOD_M = 3000.0

    enum class Verdict { RESUME, TOO_OLD, ARRIVED, MOVED, WRONG_TIME }

    /**
     * In the same boot the monotonic clock says how long ago it was (a wall clock cannot be trusted on a head unit: it is set
     * late, from GPS or the network). After a power-off only the fix's own time can say.
     */
    fun verdict(sameBoot: Boolean, keptElapsed: Long, keptAt: Long, nowElapsed: Long, fixUtc: Long, toDest: Double, fromKept: Double): Verdict {
        val age = if (sameBoot) nowElapsed - keptElapsed else fixUtc - keptAt
        return when {
            // (a fix's time a little before the kept one: two clocks; further back: one of them is wrong)
            !sameBoot && age < -2 * 60_000L -> Verdict.WRONG_TIME
            sameBoot && age < 0 -> Verdict.WRONG_TIME
            age > RESUME_WITHIN_MS -> Verdict.TOO_OLD
            toDest < (if (sameBoot) THERE_M else THERE_PARKED_M) -> Verdict.ARRIVED
            !sameBoot && fromKept > STOOD_M -> Verdict.MOVED
            else -> Verdict.RESUME
        }
    }
}
