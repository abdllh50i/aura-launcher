package com.abdllh.aura.nav

import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * What the map's additions ([MapExtras]) mean for the car right now, from its fixes: the limit of the street it is on,
 * and the camera or the bump coming up ahead — shown by [RoadHud] on the map screens, with a short tone the first time
 * each one comes within reach. It runs while fixes come, that is while the home screen or the map shows, or a route
 * is being driven; not while another app is in front without a route.
 */
object RoadWatch {
    private const val TAG = "AuraRoad"
    /** A camera is announced from this far, a bump from this far (metres along a straight line). */
    private const val CAMERA_M = 600.0
    private const val BUMP_M = 180.0
    /** Ahead: within this many degrees of where the car is heading. */
    private const val AHEAD_DEG = 55.0
    /** A limit is kept this long after the car left the marked stretch (a gap in the line, a wide junction). */
    private const val LIMIT_HOLD_MS = 4000L

    class State(val limit: Int, val spot: MapExtras.Spot?, val metres: Int, val speedKmh: Int) {
        /** Faster than the limit by more than a needle's width. */
        val over: Boolean get() = limit > 0 && speedKmh > limit + 3
    }

    var state = State(0, null, 0, 0)
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var started = false
    private var limit = 0
    private var limitAt = 0L
    private val announced = HashSet<MapExtras.Spot>()
    private var tone: ToneGenerator? = null

    fun addListener(l: () -> Unit) { listeners.add(l); start() }

    /** A camera just marked where the car is: it is not announced (it was marked a moment ago, right here). */
    fun quiet(s: MapExtras.Spot) { announced.add(s) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun start() {
        if (started) return
        started = true
        CarLocation.addListener { onFix(it) }
        MapExtras.addListener { announced.clear() }
    }

    private fun onFix(l: Location) {
        val here = LatLon(l.latitude, l.longitude)
        val speed = if (l.hasSpeed()) l.speed * 3.6 else 0.0
        val moving = speed > 8 && l.hasBearing()
        val heading = if (moving) l.bearing.toDouble() else null
        val now = SystemClock.elapsedRealtime()

        val found = MapExtras.limitAt(here, heading)
        if (found > 0) { limit = found; limitAt = now } else if (now - limitAt > LIMIT_HOLD_MS) limit = 0

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
        if (spot != null && announced.add(spot)) beep(spot.kind)
        // far from every announced one: they may be announced again (the same road, another day)
        if (announced.size > 64) announced.clear()
        announced.removeAll { Geo.distance(here, it.pos) > 1500 }

        val next = State(limit, spot, if (spot == null) 0 else best.toInt(), speed.toInt())
        val old = state
        state = next
        if (next.limit != old.limit || next.spot !== old.spot || next.metres / 10 != old.metres / 10 || next.over != old.over) {
            for (x in listeners) x()
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
