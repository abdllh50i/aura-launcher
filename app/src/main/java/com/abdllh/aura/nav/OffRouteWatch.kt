package com.abdllh.aura.nav

import kotlin.math.abs
import kotlin.math.sign

/**
 * Where the car is on the route, and whether it has left it. Three signs, each confirmed over [CONFIRM] fixes in a row
 * because one fix proves nothing:
 *  - far: further from the line than the fix's own uncertainty allows. The old test, and alone it missed a lot: a slip road
 *    leaving at 5 to 10 degrees takes 9 to 16 s at 120 km/h to get there, and a U-turn onto the other carriageway never
 *    gets there at all, with the distance along the route simply running backwards.
 *  - against the route: moving, and no stretch of the line near the car goes anywhere near the car's way. All of the line
 *    near the car counts, not only the nearest stretch: inside a roundabout, through a junction and past a route's own
 *    U-turn some stretch always fits, which is why those give no false alarm.
 *  - drifting: faster, the course pointing away from the line on the side the car already is, the distance grown over the
 *    last fixes and well past a road's width. A GPS position wandering in a built-up street grows too, but its course
 *    (the Doppler shift's, not worked out from the positions) still follows the road.
 *
 * What it cannot see: a parallel road close by (within the limit) going the same way. That needs the road network.
 * No Android in here; one instance per trip, main thread.
 */
internal class OffRouteWatch {
    /** This fix counts as off the route (no prompts, the car drawn where it is). */
    var off = false
        private set
    /** Confirmed: time to look for a new route. */
    var leaving = false
        private set
    /** Which sign said so, for the report from the unit. */
    var why = ""
        private set

    private var farFixes = 0
    private var againstFixes = 0
    private var awayFixes = 0
    private val offsets = DoubleArray(CONFIRM)
    private var seen = 0

    fun reset() {
        off = false
        leaving = false
        why = ""
        farFixes = 0
        againstFixes = 0
        awayFixes = 0
        seen = 0
    }

    /**
     * One fix: where it is on [line] (searching from the segment [from]), or null with no line to speak of. [heading]: the
     * car's course, null without one. [off], [leaving] and [why] are this fix's verdict afterwards.
     */
    fun locate(line: Polyline, here: LatLon, from: Int, heading: Double?, speedKmh: Double, accuracy: Double): Polyline.Snap? {
        val limit = maxOf(MIN_LIMIT_M, accuracy * 1.5)
        var snap = line.snap(here, from) ?: return null
        val course = heading?.takeIf { speedKmh >= AGAINST_KMH }
        // the nearest stretch going the car's way: a route that comes back beside itself (its own U-turn, a dead end with
        // a loop) has the stretch the car is on within reach of a nearer one that goes the other way
        val along = course?.let { line.snapAlong(here, it, limit, FITS_DEG, from) }
        if (along != null) snap = along
        else if (snap.offset > limit) {
            // beyond the usual search window (a GPS gap in a tunnel...): look along the rest of the route once
            val ahead = line.points.size
            val wide = course?.let { line.snapAlong(here, it, limit, FITS_DEG, from, ahead = ahead) } ?: line.snap(here, from, ahead = ahead)
            if (wide != null && wide.offset <= limit) snap = wide
        }
        val fits = course == null || along != null || abs(Geo.angleDiff(course, snap.bearing)) <= FITS_DEG
        val away = if (heading == null) Double.NaN else courseAway(here, snap, heading)
        fix(snap.offset, limit, accuracy, speedKmh, fits, away)
        return snap
    }

    /** How far the course points away from the line, toward the side the car is on (negative: back toward the line). */
    private fun courseAway(here: LatLon, snap: Polyline.Snap, heading: Double): Double {
        if (Geo.distance(here, snap.point) < 0.5) return 0.0
        val side = Geo.angleDiff(snap.bearing, Geo.bearing(snap.point, here)).sign // + the car is right of the line
        return Geo.angleDiff(snap.bearing, heading) * side
    }

    private fun fix(offset: Double, limit: Double, accuracy: Double, speedKmh: Double, fits: Boolean, courseAway: Double) {
        val far = offset > limit
        farFixes = if (far) farFixes + 1 else 0
        againstFixes = if (speedKmh >= AGAINST_KMH && !fits) againstFixes + 1 else 0
        awayFixes = if (speedKmh >= DRIFT_KMH && courseAway >= DRIFT_DEG) awayFixes + 1 else 0
        // (against the offset a few fixes back: one fix's noise is about as large as the car's sideways speed)
        val grown = if (seen >= CONFIRM) offset - offsets[seen % CONFIRM] else 0.0
        offsets[seen % CONFIRM] = offset
        seen++
        val against = againstFixes >= CONFIRM
        val drifting = awayFixes >= CONFIRM && grown >= DRIFT_GROWN_M && offset >= maxOf(DRIFT_M, accuracy)
        off = far || against || drifting
        leaving = farFixes >= CONFIRM || against || drifting
        why = when {
            !leaving -> ""
            against -> "against the route"
            drifting -> "drifting off"
            else -> "too far"
        }
    }

    companion object {
        const val CONFIRM = 3
        /** The least distance from the line that counts as far (a fix with a poor accuracy allows more). */
        const val MIN_LIMIT_M = 40.0
        /** A course means something from about this speed (under 6 km/h it is noise; this leaves a wide margin). */
        const val AGAINST_KMH = 20.0
        /** How far from the car's way a stretch of the line may run and still fit: more than course noise, less than a side road. */
        const val FITS_DEG = 60.0
        private const val DRIFT_KMH = 30.0
        /** A course this far off the line is the car's own and not noise. */
        private const val DRIFT_DEG = 4.0
        /** Grown this much over [CONFIRM] fixes: at least 2 m/s sideways. A lane change is 3.5 m in about as long. */
        private const val DRIFT_GROWN_M = 6.0
        /** Past a carriageway's width: a car in the outer lane of a wide road sits some way from the road's middle line. */
        private const val DRIFT_M = 20.0
    }
}
