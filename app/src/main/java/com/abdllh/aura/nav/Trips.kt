package com.abdllh.aura.nav

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.util.Prefs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Starting a trip, whoever asks and whatever is on the screen: the Maps screen with its Home and Work, the voice, the
 * phone ([com.abdllh.aura.link.PlaceInbox]). A route is looked for from the car (or, while the unit has no fix of its
 * own, from where the asker says it is) and [NavSession] starts on it; neither needs a screen. A screen that wants to
 * show the way there listens ([addListener]) and reads [attempt]. One attempt at a time: a newer one replaces it.
 * Main thread only, like [NavSession].
 */
object Trips {
    private const val TAG = "AuraTrips"
    const val SCREEN = "maps"
    const val VOICE = "voice"
    const val PHONE = "phone"
    /** Brought back by [TripMemory] after the process went. */
    const val RESUMED = "resumed"
    /** How long a place waits for the unit's first fix before it is left on the map for the driver. */
    private const val FIX_WAIT_MS = 10 * 60_000L
    /** A router that never answers must not keep a place waiting for ever (BRouter's own limit is well under this). */
    private const val ROUTE_WAIT_MS = 60_000L
    /** A fix older than this is where the car was: the asker's own fresh position is the better origin. */
    private const val FIX_FRESH_MS = 120_000L

    enum class Phase { WAITING_FIX, ROUTING }

    /**
     * How an attempt ended: [STARTED] the trip is on [NavSession]; [FAILED] no route; [NO_FIX] no position to route
     * from; [DROPPED] cancelled, replaced by a newer one, or no longer wanted by its asker.
     */
    enum class Outcome { STARTED, FAILED, NO_FIX, DROPPED }

    class Attempt internal constructor(
        val place: Place,
        val source: String,
        internal val origin: LatLon?,
        internal val remember: Boolean,
        internal val wanted: () -> Boolean,
        internal val done: ((Outcome, String?) -> Unit)?
    ) {
        var phase = Phase.ROUTING
            internal set
    }

    /** The attempt under way, if any. */
    var attempt: Attempt? = null
        private set

    /** Who started the trip now on [NavSession] (for the reports). */
    var startedBy = ""
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var fixWait: ((Location) -> Unit)? = null
    private val tooLong = Runnable { attempt?.let { finish(it, if (it.phase == Phase.WAITING_FIX) Outcome.NO_FIX else Outcome.FAILED, "timeout") } }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun changed() {
        for (l in listeners) try { l() } catch (t: Throwable) { Log.w(TAG, "listener: $t") }
    }

    /**
     * Route to [place] and start. [origin]: where the asker says it is, used only when the unit has no fresh fix of
     * its own. [waitForFix]: with no position at all, wait for the first fix (else [Outcome.NO_FIX] at once).
     * [remember]: the place may go to the recent places. [wanted] is asked once the route is there (a screen the driver
     * has left wants no trip started that he can no longer see). [done]: how it ended.
     */
    fun goTo(
        ctx: Context,
        place: Place,
        origin: LatLon? = null,
        source: String = SCREEN,
        waitForFix: Boolean = false,
        remember: Boolean = true,
        wanted: () -> Boolean = { true },
        done: ((Outcome, String?) -> Unit)? = null
    ) {
        val c = ctx.applicationContext
        attempt?.let { finish(it, Outcome.DROPPED, "replaced") }
        val a = Attempt(place, source, origin, remember, wanted, done)
        attempt = a
        val car = CarLocation.last
        when {
            car != null && (origin == null || fresh(car)) -> route(c, a, LatLon(car.latitude, car.longitude), heading(car))
            origin != null -> route(c, a, origin, null)
            waitForFix -> waitFix(c, a)
            else -> finish(a, Outcome.NO_FIX, "no_fix")
        }
    }

    /** The route is there already (Go on the Maps screen): the trip starts on it. */
    fun start(ctx: Context, place: Place, route: Route, source: String = SCREEN, remember: Boolean = true) {
        NavSession.start(ctx, place, route, remember)
        startedBy = source
        // (debug builds: a drive along the route, wherever the trip came from; km/h in the property)
        if (BuildConfig.DEBUG) SystemProps.get("debug.aura.navsim").toDoubleOrNull()?.let { NavSim.start(it) }
        Log.i(TAG, "started by $source")
    }

    /** Drops the attempt under way; its asker hears [Outcome.DROPPED] with [why]. */
    fun cancel(why: String) {
        attempt?.let { finish(it, Outcome.DROPPED, why) }
    }

    private fun route(c: Context, a: Attempt, from: LatLon, heading: Double?) {
        a.phase = Phase.ROUTING
        main.removeCallbacks(tooLong)
        main.postDelayed(tooLong, ROUTE_WAIT_MS)
        changed()
        Router.route(from, a.place.pos, heading) { r, err ->
            if (attempt !== a) return@route // cancelled or replaced meanwhile
            when {
                !a.wanted() -> finish(a, Outcome.DROPPED, "not wanted")
                r == null -> finish(a, Outcome.FAILED, err ?: "no_route")
                else -> try {
                    start(c, a.place, r, a.source, a.remember)
                    finish(a, Outcome.STARTED, null)
                } catch (t: Throwable) {
                    // (what could not start did not start, and its asker hears that; the home screen stays up)
                    Log.w(TAG, "start: $t")
                    try { NavSession.stop() } catch (_: Throwable) { }
                    finish(a, Outcome.FAILED, "start")
                }
            }
        }
    }

    /** No position yet: the first fix starts the route. The GPS is held on meanwhile: no screen may be holding it. */
    private fun waitFix(c: Context, a: Attempt) {
        a.phase = Phase.WAITING_FIX
        CarLocation.acquire(c, "trips")
        val l: (Location) -> Unit = { fix ->
            // (a fix comes on the main thread, inside CarLocation's loop over its listeners: not from in there)
            main.post {
                if (attempt === a && a.phase == Phase.WAITING_FIX) {
                    stopWaiting()
                    route(c, a, LatLon(fix.latitude, fix.longitude), heading(fix))
                }
            }
        }
        fixWait = l
        CarLocation.addListener(l)
        main.postDelayed(tooLong, FIX_WAIT_MS)
        changed()
    }

    private fun stopWaiting() {
        fixWait?.let { CarLocation.removeListener(it); CarLocation.release("trips") }
        fixWait = null
    }

    private fun finish(a: Attempt, outcome: Outcome, why: String?) {
        if (attempt !== a) return
        attempt = null
        main.removeCallbacks(tooLong)
        stopWaiting()
        Log.i(TAG, "${a.source}: $outcome ${why.orEmpty()}")
        try { a.done?.invoke(outcome, why) } catch (t: Throwable) { Log.w(TAG, "done: $t") }
        changed()
    }

    private fun heading(l: Location): Double? = if (l.hasBearing() && l.speed > 2) l.bearing.toDouble() else null

    private fun fresh(l: Location) = SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos < FIX_FRESH_MS * 1_000_000L

    // ------------------------------------------------------------------------------------------ Home and Work
    /** The saved Home or Work ([which] "home" or "work"), if it has its pin. */
    fun saved(which: String): Place? = if (which == "home") Places.home else Places.work

    /** An address typed in 1.0 for it, not yet looked up. */
    fun legacy(which: String): String = if (which == "home") Prefs.homeAddress else Prefs.workAddress

    /** Finds the address typed in 1.0 once, and keeps the place; [done] on the main thread, null when it was not found. */
    fun findLegacy(which: String, arabic: Boolean, done: (Place?) -> Unit) {
        Places.geocode(legacy(which), CarLocation.lastKnown(), arabic) { p ->
            if (p != null) { if (which == "home") Places.home = p else Places.work = p }
            done(p)
        }
    }
}
