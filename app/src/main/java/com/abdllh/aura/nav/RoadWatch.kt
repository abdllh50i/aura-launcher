package com.abdllh.aura.nav

import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.nav.guide.AlongSpot
import com.abdllh.aura.nav.guide.Guidance
import com.abdllh.aura.nav.guide.LimitNow
import com.abdllh.aura.nav.guide.Limits
import com.abdllh.aura.nav.guide.SpotAlert
import com.abdllh.aura.nav.guide.SpotRow
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * What the map's additions ([MapExtras]) mean for the car right now, from its fixes: the limit of the street it is on,
 * and the camera or the bump coming up ahead — shown by the speed card on the map screens, with a short tone the first time
 * each one comes within reach. On a route the rest comes from the route where the owner's live set says nothing: with the
 * guidance part's words ([Route.guide]) [Limits.at] and [Guidance.nextAlert], by distance along the road, else
 * OpenStreetMap's limits by place ([RouteLimits]). It runs while fixes come, that is while the home screen or the map shows,
 * or a route is being driven; not while another app is in front without a route.
 */
object RoadWatch {
    private const val TAG = "AuraRoad"
    /** A camera is known from this far (the Saher card, SPEC.md section 5), a bump from this far (metres along a straight line). */
    private const val CAMERA_M = 1000.0
    private const val BUMP_M = 180.0
    /** The tone for a camera comes this far before it. */
    private const val CAMERA_TONE_M = 600.0
    /** Ahead: within this many degrees of where the car is heading. */
    private const val AHEAD_DEG = 55.0
    /** A limit is kept this long after the car left the marked stretch (a gap in the line, a wide junction). */
    private const val LIMIT_HOLD_MS = 4000L
    /** A spot of the route this near one of the owner's own of its kind is that one: one tone, his limit. */
    private const val SAME_SPOT_M = 40.0

    /**
     * [limitFromMap]: [limit] is OpenStreetMap's (true only where the owner set none), not his own. [estimate]: no limit is known here,
     * and this is what the road's class suggests: a guess must never read as the law (maps.md 11), so it is not [limit], the sign does not
     * show it and it makes nobody [over]. For a screen that marks it as a guess (`Words` "limit.estimated").
     */
    class State(val limit: Int, val spot: MapExtras.Spot?, val metres: Int, val speedKmh: Int, val limitFromMap: Boolean = false, val estimate: Int = 0) {
        /** Faster than the limit by more than a needle's width. */
        val over: Boolean get() = limit > 0 && speedKmh > limit + 3
    }

    var state = State(0, null, 0, 0)
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var started = false
    private val limit = LimitHold(LIMIT_HOLD_MS)
    private val announced = HashSet<MapExtras.Spot>()
    /** The route's spots ([Guidance.spots]) as the speed card and [announced] know a spot: one object for each, as long as the route's words last. */
    private val routeSpots = IdentityHashMap<AlongSpot, MapExtras.Spot>()
    private var routeSpotsOf: Guidance? = null
    private var tone: ToneGenerator? = null

    fun addListener(l: () -> Unit) { listeners.add(l); start() }

    /** A camera just marked where the car is: it is not announced (it was marked a moment ago, right here). */
    fun quiet(s: MapExtras.Spot) { announced.add(s) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun start() {
        if (started) return
        started = true
        CarLocation.addListener { onFix(it) }
        // A changed set keeps what was announced of it: marking or taking back a camera must not make the bump or
        // camera just announced beep again. Spots compare by identity: the published ones are the same objects after
        // the driver's own changed, removed ones drop out, and a newly fetched set (new objects) starts afresh.
        MapExtras.addListener { announced.retainAll(MapExtras.spots.toHashSet() + routeSpots.values) }
    }

    private fun onFix(l: Location) {
        val here = LatLon(l.latitude, l.longitude)
        val speed = if (l.hasSpeed()) l.speed * 3.6 else 0.0
        val moving = speed > 8 && l.hasBearing()
        val heading = if (moving) l.bearing.toDouble() else null
        val now = SystemClock.elapsedRealtime()

        // the route being driven, and where on it the car is (not when it has left it: then only its place says anything)
        val route = if (NavSession.state == NavSession.State.NAVIGATING) NavSession.route else null
        val along = if (route != null) NavSession.progress?.takeIf { !it.offRoute }?.along else null
        val guide = route?.guide?.takeIf { along != null }

        // the owner's limit first, then what is known of the route being driven
        val owner = MapExtras.limitAt(here, heading)
        val word = limitOf(route, guide, along, here, heading, owner)
        limit.update(owner, if (word.source == LimitNow.Source.KNOWN) word.kmh else 0, now)
        val estimate = if (word.source == LimitNow.Source.ESTIMATED && limit.kmh == 0) word.kmh else 0

        // the nearest camera or bump ahead (standing still, nothing is "ahead")
        var spot: MapExtras.Spot? = null
        var best = Double.MAX_VALUE
        if (heading != null) for (s in MapExtras.spots) {
            val reach = when (s.kind) { MapExtras.Kind.CAMERA -> CAMERA_M; MapExtras.Kind.BUMP -> BUMP_M; else -> continue }
            // (a cheap box first: most spots are far away)
            if (abs(s.pos.lat - here.lat) > 0.01 || abs(s.pos.lon - here.lon) > 0.01) continue
            val d = Geo.distance(here, s.pos)
            if (d > reach || d >= best) continue
            if (d > 25 && abs(Geo.angleDiff(heading, Geo.bearing(here, s.pos))) > AHEAD_DEG) continue
            best = d
            spot = s
        }
        // what lies on the route's line, going its way. The owner's live set stays on top: his of the pack's build are his live set's to say
        // (he may have taken one away since), and a spot about as near as one of his gives way to it
        if (heading != null && guide != null && along != null) {
            guide.nextAlert(along, CAMERA_M) { it.owner && MapExtras.updatedAt > 0 }?.let { a ->
                if (spot == null || a.metres + SAME_SPOT_M < best) {
                    spot = routeSpot(guide, a)
                    best = a.metres
                }
            }
        }
        spot?.let { if ((it.kind != MapExtras.Kind.CAMERA || best <= CAMERA_TONE_M) && announced.add(it)) beep(it.kind) }
        // far from every announced one: they may be announced again (the same road, another day)
        if (announced.size > 64) announced.clear()
        announced.removeAll { Geo.distance(here, it.pos) > 1500 }

        val next = debugSays ?: State(limit.kmh, spot, if (spot == null) 0 else best.toInt(), speed.toInt(), limit.fromMap, estimate)
        val old = state
        state = next
        if (next.limit != old.limit || next.limitFromMap != old.limitFromMap || next.estimate != old.estimate || next.spot !== old.spot || next.metres / 10 != old.metres / 10 || next.over != old.over) {
            for (x in listeners) x()
        }
    }

    /** Debug builds, the emulator's way to a sign and a Saher card (`NavDebug --es road`): what it says from now on instead of what it works out. */
    fun debugSay(s: State?) {
        debugSays = s
        state = s ?: State(0, null, 0, 0)
        for (x in listeners) x()
    }

    private var debugSays: State? = null

    /** Whose word the limit is: his first; on a route with its words, the road's and then its class's; on any route, the limits by place. */
    private fun limitOf(route: Route?, guide: Guidance?, along: Double?, here: LatLon, heading: Double?, owner: Int): LimitNow {
        if (guide != null && along != null) {
            val w = Limits.at(guide.route, along, owner)
            if (w.source == LimitNow.Source.MINE || w.source == LimitNow.Source.KNOWN) return w
            // (the road's facts are from the same walk; where they could not be placed the limits by place may still know)
            val known = route?.limits?.at(here, heading) ?: 0
            return if (known > 0) LimitNow(known, LimitNow.Source.KNOWN) else w
        }
        return Limits.pick(owner, route?.limits?.at(here, heading) ?: 0, null)
    }

    /** The route's spot as one object for as long as it is on this route; the owner's own when it is that one; one already announced when a reroute brought it back. */
    private fun routeSpot(g: Guidance, a: SpotAlert): MapExtras.Spot {
        if (routeSpotsOf !== g) { routeSpotsOf = g; routeSpots.clear() }
        return routeSpots.getOrPut(a.spot) {
            val kind = if (a.spot.kind == SpotRow.CAMERA) MapExtras.Kind.CAMERA else MapExtras.Kind.BUMP
            fun same(s: MapExtras.Spot) = s.kind == kind && Geo.distance(s.pos, a.spot.pos) < SAME_SPOT_M
            MapExtras.spots.firstOrNull(::same) ?: announced.firstOrNull(::same) ?: MapExtras.Spot(kind, a.spot.pos, a.kmh)
        }
    }

    /** Two short tones for a camera, one for a bump (the media stream: what the unit's speakers play). */
    private fun beep(kind: MapExtras.Kind) {
        try {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 85).also { tone = it }
            t.startTone(if (kind == MapExtras.Kind.CAMERA) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 320)
        } catch (t: Throwable) {
            Log.w(TAG, "tone: $t")
        }
    }
}
