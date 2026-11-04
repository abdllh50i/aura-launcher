package com.abdllh.aura.link

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.R
import com.abdllh.aura.home.Actions
import com.abdllh.aura.home.AppRepo
import com.abdllh.aura.home.Screens
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.nav.Place
import com.abdllh.aura.nav.Trips
import com.abdllh.aura.nav.Units
import com.abdllh.aura.phone.Calls
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.voice.Amri
import org.json.JSONObject
import java.util.Locale

/**
 * What the unit does with a place that comes from outside the Maps screen (companion.md 5.5). Idle, from a paired phone:
 * Maps comes forward, the route starts, and for [CANCEL_MS] the card offers to cancel. Unpaired, or a place that is only
 * a guess: the card asks first. On a trip: the card asks, and if nobody answers in [REPLACE_MS] the trip goes on; a
 * route never changes silently. In a call or in reverse: it waits, then the card asks. No route: the place stays on the
 * map with its retry and Go. Without a fix: the route's start waits for the first one.
 *
 * The phone hears each step as it really happens ([tell], the "nav" events), "started" only once [NavSession] runs. Only
 * one place at a time: a newer one replaces one still waiting. The voice's "ودني ..." comes through here too. Main
 * thread only ([NavBridge] posts).
 */
internal object PlaceInbox {
    private const val TAG = "AuraPlaceInbox"
    private const val CANCEL_MS = 5_000L
    private const val REPLACE_MS = 20_000L
    private const val ASK_MS = 30_000L
    private const val FAILED_MS = 6_000L
    /** A place held for a call or for reverse is old news after this. */
    private const val HOLD_MS = 30 * 60_000L
    private const val KEY_LAST = "phoneLastPlace"

    /**
     * [id]: the phone's, for its events. [src]: where it came from. [from]: the phone's own fix. [paired]: acts at once.
     * [ask]: always ask (a guess). [voice]: asked for by voice, not by the phone. [told]: each step, for the voice's answer.
     */
    class Incoming(
        val id: String, val place: Place, val src: String, val from: LatLon?, val paired: Boolean, val peer: String, val ask: Boolean,
        val voice: Boolean = false, val told: ((String) -> Unit)? = null
    )

    private enum class Stage { HELD, ASKING, REPLACING, ON_MAP, TRIP, CANCEL_WINDOW }

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var current: Incoming? = null
    private var stage = Stage.TRIP
    private var heldSince = 0L
    private var shownPhase: Trips.Phase? = null
    /** The card of a route being found, put away for a call or for reverse. */
    private var cardAway = false
    private var watching = false
    /** A place whose route failed (or that waits for Go on the map): told "started" if the driver starts it there. */
    private var onMap: Incoming? = null

    private val tripsListener: () -> Unit = {
        val x = current
        val a = Trips.attempt
        if (x != null && stage == Stage.TRIP && a != null && a.place === x.place && a.phase != shownPhase) {
            shownPhase = a.phase
            tellPhase(x, a.phase)
            app?.let { progressCard(it, x, a.phase) }
        }
    }

    private val navListener: () -> Unit = {
        try {
            val x = onMap
            val d = NavSession.destination
            if (x != null && d != null && NavSession.state == NavSession.State.NAVIGATING && samePlace(d, x.place)) {
                onMap = null
                if (current === x) current = null
                tell(x, "started", trip())
                note(x, "started")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "nav: $t")
        }
    }

    private fun samePlace(a: Place, b: Place) = Geo.distance(a.pos, b.pos) < 1.0

    private fun words(c: Context): Context = LocaleHelper.wrap(c.applicationContext)

    /** Something outside has a place for the driver. */
    fun receive(ctx: Context, x: Incoming) {
        val c = ctx.applicationContext
        app = c
        if (!watching) {
            watching = true
            Trips.addListener(tripsListener)
            NavSession.addListener(navListener)
        }
        current?.let { old -> leave(old, "replaced") }
        onMap = null
        current = x
        Log.i(TAG, "a place from ${x.src} (${if (x.voice) "voice" else if (x.paired) "paired" else "not paired"})")
        if (!x.voice) note(x, "received")
        decide(c, x, afterHold = false)
    }

    /** Ended here by a newer place or a stop: whatever of it was going on goes. */
    private fun leave(x: Incoming, how: String) {
        // (its route is being found: dropping that comes back to [cancelled], which tells)
        if (stage == Stage.TRIP && Trips.attempt?.place === x.place) { Trips.cancel(how); return }
        // (its trip already runs: the newer place's own card decides about that)
        if (stage != Stage.CANCEL_WINDOW) { tell(x, how, null); note(x, how) }
        finishWith(x)
    }

    private fun finishWith(x: Incoming) {
        if (current === x) current = null
        main.removeCallbacks(tick)
        TripCard.hide()
    }

    private fun blocker(): String? = when {
        Amri.reversing -> "reverse"
        Amri.inCall || Calls.busy -> "call"
        else -> null
    }

    private fun decide(c: Context, x: Incoming, afterHold: Boolean) {
        val busy = blocker()
        when {
            busy != null -> hold(x, busy)
            NavSession.state == NavSession.State.NAVIGATING -> replaceCard(c, x)
            afterHold || !x.paired || x.ask -> askCard(c, x)
            else -> begin(c, x)
        }
    }

    // ------------------------------------------------------------------------------------------ waiting
    private fun hold(x: Incoming, why: String) {
        stage = Stage.HELD
        heldSince = SystemClock.elapsedRealtime()
        tell(x, "held", JSONObject().put("reason", why))
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1000L)
    }

    /** Once a second while something waits or a card is up: the end of the call or of reverse; their start, which no card covers. */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            try {
                val x = current ?: return
                val c = app ?: return
                val busy = blocker()
                when (stage) {
                    Stage.HELD -> {
                        if (busy == null) { decide(c, x, afterHold = true); return }
                        if (SystemClock.elapsedRealtime() - heldSince > HOLD_MS) { tell(x, "expired", null); note(x, "expired"); finishWith(x); return }
                    }
                    Stage.ASKING, Stage.REPLACING -> if (busy != null) { TripCard.hide(); hold(x, busy); return }
                    // (the route goes on being found underneath; only its card makes way)
                    Stage.TRIP -> {
                        val a = Trips.attempt?.takeIf { it.place === x.place } ?: return
                        if (busy != null && !cardAway) { cardAway = true; TripCard.hide() }
                        else if (busy == null && cardAway) { cardAway = false; progressCard(c, x, a.phase) }
                    }
                    // (the trip has started; its cancel is on the Maps screen's End as well)
                    Stage.CANCEL_WINDOW -> if (busy != null) { current = null; TripCard.hide(); return }
                    else -> return
                }
                main.postDelayed(this, 1000L)
            } catch (t: Throwable) {
                Log.w(TAG, "tick: $t")
            }
        }
    }

    // ------------------------------------------------------------------------------------------ the cards
    private fun caption(c: Context, x: Incoming) = words(c).getString(if (x.voice) R.string.link_from_voice else R.string.link_from_phone)

    /** The place's detail and how far it is from the car, whatever is known of them. */
    private fun where(x: Incoming): String {
        val arabic = Locale.getDefault().language == "ar"
        val far = CarLocation.lastKnown()?.let { Units.distance(Geo.distance(it, x.place.pos), arabic) }
        // (each part whole: an Arabic address beside "12 km" must not take the number into its own direction)
        val bidi = android.text.BidiFormatter.getInstance(arabic)
        return listOfNotNull(x.place.detail.ifBlank { null }, far).joinToString(" · ") { bidi.unicodeWrap(it) }
    }

    private fun askCard(c: Context, x: Incoming) {
        val w = words(c)
        val shown = TripCard.show(c, caption(c, x), w.getString(R.string.link_ask, x.place.name), where(x), listOf(
            TripCard.Action(w.getString(R.string.link_start), true) { if (current === x) begin(c, x) },
            TripCard.Action(w.getString(R.string.link_no), false) { if (current === x) { tell(x, "ignored", null); note(x, "ignored"); finishWith(x) } }
        ), ASK_MS) {
            if (current === x) { tell(x, "expired", null); note(x, "expired"); finishWith(x) }
        }
        if (!shown) { onTheMap(c, x); return }
        stage = Stage.ASKING
        tell(x, "card", JSONObject().put("kind", "ask"))
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1000L)
    }

    private fun replaceCard(c: Context, x: Incoming) {
        val w = words(c)
        val now = NavSession.destination?.name.orEmpty()
        val shown = TripCard.show(c, caption(c, x), x.place.name, w.getString(R.string.link_replace_q, now), listOf(
            TripCard.Action(w.getString(R.string.link_replace), true) { if (current === x) begin(c, x) },
            TripCard.Action(w.getString(R.string.link_keep), false) { if (current === x) { tell(x, "ignored", JSONObject().put("kept", true)); note(x, "ignored"); finishWith(x) } }
        ), REPLACE_MS) {
            // nobody answered: the trip goes on
            if (current === x) { tell(x, "ignored", JSONObject().put("kept", true).put("by", "timeout")); note(x, "ignored"); finishWith(x) }
        }
        if (!shown) { // no card can ask: the trip goes on
            tell(x, "ignored", JSONObject().put("kept", true).put("reason", "no_card"))
            note(x, "ignored")
            finishWith(x)
            return
        }
        stage = Stage.REPLACING
        tell(x, "card", JSONObject().put("kind", "replace"))
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1000L)
    }

    /** No way to draw over other apps here: the place on the map with its Go, which is the same tap. */
    private fun onTheMap(c: Context, x: Incoming) {
        stage = Stage.ON_MAP
        openOnMap(c, x)
        onMap = x
        tell(x, "card", JSONObject().put("kind", "map"))
    }

    private fun openOnMap(c: Context, x: Incoming) {
        Screens.mapsPlace(c, x.place.pos.lat, x.place.pos.lon, x.place.name, x.place.detail, mayKeep(x))
    }

    private fun progressCard(c: Context, x: Incoming, phase: Trips.Phase) {
        if (blocker() != null) { cardAway = true; return }
        val w = words(c)
        TripCard.show(c, caption(c, x), x.place.name,
            w.getString(if (phase == Trips.Phase.WAITING_FIX) R.string.maps_waiting_gps else R.string.maps_routing),
            listOf(TripCard.Action(w.getString(R.string.link_cancel), false) { if (current === x) cancelled(x, "card") }), 0L, null)
    }

    // ------------------------------------------------------------------------------------------ going
    private fun begin(c: Context, x: Incoming) {
        if (handedOver(c, x)) return
        stage = Stage.TRIP
        cardAway = false
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1000L)
        shownPhase = null // ([tripsListener] tells each phase, the first one too, and shows the card)
        Trips.goTo(c, x.place, x.from, if (x.voice) Trips.VOICE else Trips.PHONE, waitForFix = true, remember = mayKeep(x)) { outcome, why ->
            if (current === x) when (outcome) {
                Trips.Outcome.STARTED -> started(c, x)
                Trips.Outcome.FAILED, Trips.Outcome.NO_FIX -> failed(c, x, if (outcome == Trips.Outcome.NO_FIX) "no_fix" else if (why == "timeout") "timeout" else "no_route")
                Trips.Outcome.DROPPED -> cancelled(x, why ?: "dropped")
            }
        }
        // the way it has always come forward; it shows the place while the route is found
        if (Trips.attempt?.place === x.place) Screens.maps(c)
    }

    /** Apple's terms keep its place data off the unit's lists (companion.md 2.6). */
    private fun mayKeep(x: Incoming) = !x.src.contains("apple", ignoreCase = true)

    private fun tellPhase(x: Incoming, phase: Trips.Phase) =
        tell(x, if (phase == Trips.Phase.WAITING_FIX) "waiting_fix" else "routing", JSONObject().put("place", x.place.toJson()))

    /** The navigation app chosen in Settings, when it is not AMRI Maps. It says nothing back: told "handed", not "started". */
    private fun handedOver(c: Context, x: Incoming): Boolean {
        if (Prefs.builtInMaps) return false
        val pkg = AppRepo.navPackage(c) ?: return false
        if (!Actions.driveWithApp(c, x.place.pos)) return false
        tell(x, "handed", JSONObject().put("app", pkg))
        note(x, "handed")
        finishWith(x)
        return true
    }

    private fun trip(): JSONObject {
        val r = NavSession.route
        val o = JSONObject()
        if (r != null) {
            o.put("distanceM", Math.round(r.distance)).put("durationS", Math.round(r.duration))
                .put("etaLocal", Units.arrival(r.duration, Prefs.clock24))
        }
        return o
    }

    private fun started(c: Context, x: Incoming) {
        tell(x, "started", trip())
        val w = words(c)
        val line = w.getString(R.string.link_started, x.place.name)
        if (!x.voice) {
            note(x, "started")
            NavSession.announce(line)
        }
        stage = Stage.CANCEL_WINDOW
        // (nothing over the camera or a call's card: then the trip just runs, with its End button on the Maps screen)
        val busy = blocker()
        val shown = busy == null && TripCard.show(c, caption(c, x), line, where(x),
            listOf(TripCard.Action(w.getString(R.string.link_cancel), false) { if (current === x) cancelled(x, "card") }), CANCEL_MS) {
            if (current === x) current = null
        }
        if (shown) {
            main.removeCallbacks(tick)
            main.postDelayed(tick, 1000L)
        } else {
            current = null
            if (busy == null) Snack.show(w, line, long = true, outlives = true) // (no card here: this says what happened)
            else TripCard.hide()
        }
    }

    private fun cancelled(x: Incoming, by: String) {
        // (its route is being found: dropping that comes back here, with nothing left to drop)
        if (stage == Stage.TRIP && Trips.attempt?.place === x.place) { Trips.cancel(by); return }
        if (stage == Stage.CANCEL_WINDOW) NavSession.stop()
        tell(x, if (by == "replaced") "replaced" else "cancelled", JSONObject().put("by", by))
        note(x, "cancelled")
        finishWith(x)
    }

    /** No route, or no fix for too long: he hears so, and the place stays on the screen with the map's own retry. */
    private fun failed(c: Context, x: Incoming, why: String) {
        // (it was to replace the trip he is on: that trip goes on, with its panels, and no second place on top of them)
        val onTrip = NavSession.state == NavSession.State.NAVIGATING
        tell(x, "failed", JSONObject().put("reason", why).also { if (onTrip) it.put("kept", true) })
        note(x, "failed $why")
        val w = words(c)
        finishWith(x)
        // (a call or reverse meanwhile: not over them; the phone has been told)
        if (blocker() != null) return
        if (onTrip) {
            TripCard.show(c, caption(c, x), w.getString(R.string.link_failed_kept, x.place.name), null, emptyList(), FAILED_MS, null)
            return
        }
        openOnMap(c, x)
        onMap = x
        TripCard.show(c, caption(c, x), w.getString(R.string.link_failed, x.place.name), null, emptyList(), FAILED_MS, null)
    }

    // ------------------------------------------------------------------------------------------ stopping
    /** The phone's or the voice's "stop": what waits goes, and the trip ends. True when a trip or an attempt was there. */
    fun stop(by: String): Boolean {
        var did = false
        current?.let { x ->
            if (stage != Stage.CANCEL_WINDOW) { tell(x, "cancelled", JSONObject().put("by", by)); note(x, "cancelled"); did = true }
            finishWith(x)
        }
        onMap = null
        if (Trips.attempt != null) { Trips.cancel(by); did = true }
        if (NavSession.state != NavSession.State.IDLE) { NavSession.stop(); did = true }
        if (com.abdllh.aura.nav.TripMemory.dropResume(by)) did = true // (a kept trip still waiting for its fix or its card)
        return did
    }

    /** The voice's cancel: what it asked for and has not started yet. */
    fun dropVoice() {
        current?.takeIf { it.voice && stage != Stage.CANCEL_WINDOW }?.let { cancelled(it, "voice") }
    }

    /** What waits for the driver, for GET /v1/state. */
    fun pendingJson(): JSONObject? {
        val x = current ?: return null
        return JSONObject().put("id", x.id).put("stage", stage.name.lowercase(Locale.ROOT)).put("place", x.place.toJson())
    }

    // ------------------------------------------------------------------------------------------ reports
    /** A step of [x], to whoever waits for it. */
    private fun tell(x: Incoming, state: String, extra: JSONObject?) {
        Log.i(TAG, "${x.id}: $state")
        try { x.told?.invoke(state) } catch (t: Throwable) { Log.w(TAG, "told: $t") }
        if (!x.voice) PhoneServer.navEvent(x.id, state, extra)
    }

    /** Kept for Settings → About: the owner's photo of that page is the only report from the road. */
    private fun note(x: Incoming, state: String) {
        try {
            Prefs.raw.edit().putString(KEY_LAST, JSONObject().put("at", System.currentTimeMillis()).put("name", x.place.name)
                .put("state", state).put("peer", x.peer).put("paired", x.paired).toString()).apply()
        } catch (_: Throwable) {
        }
    }

    /** As kept, for GET /v1/diag. */
    val lastRaw: String get() = Prefs.raw.getString(KEY_LAST, "").orEmpty()

    /** "18:22، الراشد مول، عبر الشبكة (بدأت)", or "" when none ever came. */
    fun lastLine(c: Context): String = try {
        val o = JSONObject(Prefs.raw.getString(KEY_LAST, "{}") ?: "{}")
        if (!o.has("at")) "" else {
            val state = o.optString("state")
            val w = when {
                state == "started" -> R.string.link_note_started
                state.startsWith("failed") -> R.string.link_note_failed
                state == "cancelled" -> R.string.link_note_cancelled
                state == "ignored" || state == "expired" || state == "replaced" -> R.string.link_note_not_started
                state == "handed" -> R.string.link_note_handed
                else -> R.string.link_note_received
            }
            c.getString(R.string.link_note, PhoneServer.clock(o.getLong("at")), o.optString("name"), c.getString(w))
        }
    } catch (_: Throwable) {
        ""
    }
}
