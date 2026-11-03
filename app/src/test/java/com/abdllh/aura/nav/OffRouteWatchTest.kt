package com.abdllh.aura.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Drives made up fix by fix, a fix a second, the way NavSession feeds the watch. The noise is about what a good receiver
 * gives on an open road: 3 m of position, 2 degrees of course. How the unit's own GPS behaves has never been measured.
 */
class OffRouteWatchTest {
    private val start = LatLon(27.0, 49.5)
    /** 40 km north-east, in steps of 100 m. */
    private val motorway = Polyline((0..400).map { Geo.offset(start, 45.0, it * 100.0) })

    /** The second the watch says the car is leaving, or null. */
    private fun drive(line: Polyline, seconds: Int, course: (Int) -> Double, kmh: (Int) -> Double = { 120.0 }, accuracy: Double = 5.0,
                      noise: Random? = null, from: Int = 0, place: (Int) -> LatLon): Int? {
        val w = OffRouteWatch()
        var seg = from
        for (t in 0 until seconds) {
            var here = place(t)
            if (noise != null) here = Geo.offset(here, noise.nextDouble() * 360, abs(noise.nextGaussian()) * 3.0)
            val heading = course(t) + (noise?.nextGaussian()?.times(2.0) ?: 0.0)
            val snap = w.locate(line, here, seg, heading, kmh(t), accuracy)!!
            if (w.leaving) return t
            if (!w.off) seg = snap.segment
        }
        return null
    }

    @Test fun onTheRoadWithNoisyGpsNoAlarm() {
        for (seed in 0L until 40L) {
            val at = drive(motorway, 600, course = { 45.0 }, noise = Random(seed)) { t -> Geo.offset(start, 45.0, t * 33.3) }
            assertNull("seed $seed", at)
        }
    }

    @Test fun inTheOuterLaneOfAWideRoadNoAlarm() {
        // 15 m beside the line all the way
        assertNull(drive(motorway, 300, course = { 45.0 }) { t -> Geo.offset(Geo.offset(start, 45.0, t * 33.3), 135.0, 15.0) })
    }

    @Test fun aLaneChangeNoAlarm() {
        // from 15 m beside the line to 18.5 m in 3 s, with the course off by what that takes
        val at = drive(motorway, 60, course = { t -> if (t in 20..23) 49.0 else 45.0 }) { t ->
            val side = 15.0 + (t - 20).coerceIn(0, 3) * 3.5 / 3
            Geo.offset(Geo.offset(start, 45.0, t * 33.3), 135.0, side)
        }
        assertNull(at)
    }

    @Test fun aMissedExitIsNoticedSoonerThanByDistance() {
        // the car takes a slip road (or stays on the road when the route took one): 7.5 degrees off the line at 120 km/h.
        // By distance alone: 40 m after 9.2 s, and three fixes in a row: 11 s
        val at = drive(motorway, 40, course = { 52.5 }) { t -> Geo.offset(start, 52.5, t * 33.3) }!!
        assertTrue("$at s", at <= 7)
    }

    @Test fun aShallowExitAlsoWithNoisyGps() {
        // 5 degrees: by distance alone 13.8 s, and two more fixes
        var total = 0
        val runs = 30
        for (seed in 0L until runs) {
            val at = drive(motorway, 40, course = { 50.0 }, noise = Random(seed)) { t -> Geo.offset(start, 50.0, t * 33.3) }!!
            total += at
            assertTrue("seed $seed: $at s", at <= 18)
        }
        assertTrue("mean ${total / runs} s", total / runs <= 11)
    }

    @Test fun aUTurnIsNoticedAtOnce() {
        // on the line, going back along it: the old test never saw this, the car stays within 40 m of the line
        val middle = Geo.offset(start, 45.0, 20_000.0)
        val seg = motorway.snap(middle, 0, 0, motorway.points.size)!!.segment
        val at = drive(motorway, 10, course = { 225.0 }, kmh = { 58.0 }, from = seg) { t -> Geo.offset(middle, 225.0, t * 16.0) }
        assertEquals(2, at) // the third fix
    }

    @Test fun theOtherCarriagewayIsAgainstTheRoute() {
        // 15 m beside the line, the other way
        val middle = Geo.offset(Geo.offset(start, 45.0, 20_000.0), 135.0, 15.0)
        val seg = motorway.snap(middle, 0, 0, motorway.points.size)!!.segment
        val at = drive(motorway, 10, course = { 225.0 }, noise = Random(1), from = seg) { t -> Geo.offset(middle, 225.0, t * 33.3) }
        assertEquals(2, at)
    }

    @Test fun aCrossStreetIsNoticedBeforeTheDistance() {
        // a right angle at 50 km/h off the line: 40 m takes 3 s, and three fixes past that 5 s
        val at = drive(motorway, 20, course = { 135.0 }, kmh = { 50.0 }) { t -> Geo.offset(start, 135.0, t * 13.9) }
        assertEquals(2, at)
    }

    @Test fun slowlyNothingButTheDistanceCounts() {
        // going the wrong way along the line at parking speed
        val middle = Geo.offset(start, 45.0, 20_000.0)
        val seg = motorway.snap(middle, 0, 0, motorway.points.size)!!.segment
        assertNull(drive(motorway, 30, course = { 225.0 }, kmh = { 15.0 }, from = seg) { t -> Geo.offset(middle, 225.0, t * 4.0) })
    }

    @Test fun farFromTheLineItCountsAtAnySpeed() {
        val w = OffRouteWatch()
        val far = Geo.offset(start, 135.0, 100.0)
        w.locate(motorway, far, 0, null, 6.0, 5.0)
        assertTrue(w.off); assertFalse(w.leaving)
        w.locate(motorway, far, 0, null, 6.0, 5.0)
        assertFalse(w.leaving)
        w.locate(motorway, far, 0, null, 6.0, 5.0)
        assertTrue(w.leaving)
        assertEquals("too far", w.why)
    }

    @Test fun oneOrTwoBadFixesAreForgotten() {
        val w = OffRouteWatch()
        val far = Geo.offset(start, 135.0, 100.0)
        repeat(2) { w.locate(motorway, far, 0, 135.0, 100.0, 5.0); assertFalse(w.leaving) }
        w.locate(motorway, start, 0, 45.0, 100.0, 5.0)
        assertFalse(w.off)
        repeat(2) { w.locate(motorway, far, 0, 135.0, 100.0, 5.0); assertFalse(w.leaving) }
    }

    @Test fun throughARoundaboutNoAlarm() {
        // a ring of 25 m radius driven at 25 km/h: the course goes all the way round while the car stays on the line
        val centre = LatLon(27.0, 49.5)
        val ring = (0..72).map { Geo.offset(centre, it * 5.0, 25.0) }
        val line = Polyline(listOf(Geo.offset(ring[0], 270.0, 500.0)) + ring)
        val w = OffRouteWatch()
        var seg = 0
        for (t in 0 until 22) { // once round
            val onRing = t * 16.0 // 7 m/s on a 157 m ring
            w.locate(line, Geo.offset(centre, onRing, 25.0), seg, (onRing + 90) % 360, 25.0, 5.0)!!.also { if (!w.off) seg = it.segment }
            assertFalse("second $t", w.leaving)
        }
    }

    @Test fun aRightAngleInTheRouteNoAlarm() {
        // the route turns at a junction; the car's course swings through it, lagging a fix behind the line
        val corner = Geo.offset(start, 45.0, 500.0)
        val line = Polyline(listOf(start, corner, Geo.offset(corner, 135.0, 500.0)))
        val w = OffRouteWatch()
        var seg = 0
        val track = (0..10).map { Geo.offset(start, 45.0, 400.0 + it * 10.0) } + (1..10).map { Geo.offset(corner, 135.0, it * 10.0) }
        for ((t, here) in track.withIndex()) {
            val course = if (t <= 11) 45.0 else 135.0 // (a fix late)
            w.locate(line, here, seg, course, 36.0, 5.0)!!.also { if (!w.off) seg = it.segment }
            assertFalse("fix $t", w.leaving)
        }
    }

    @Test fun aRoutesOwnUTurnNoAlarmAndNoJumpAhead() {
        // out on one carriageway, a U-turn at its end, back on the other 15 m away. The car still going out is nearer the way
        // back (its 80 segments of look-ahead reach it): that used to throw the distance along the route kilometres ahead
        val out = (0..50).map { Geo.offset(start, 45.0, it * 100.0) }
        val back = (0..50).map { Geo.offset(Geo.offset(out.last(), 135.0, 15.0), 225.0, it * 100.0) }
        val line = Polyline(out + back)
        val w = OffRouteWatch()
        var seg = 10
        for (t in 0 until 90) {
            val walked = 1000.0 + t * 33.3
            val here = Geo.offset(Geo.offset(start, 45.0, walked), 135.0, 9.0)
            val snap = w.locate(line, here, seg, 45.0, 120.0, 5.0)!!
            assertFalse("second $t", w.leaving)
            assertEquals("second $t", walked, snap.along, 50.0)
            if (!w.off) seg = snap.segment
        }
    }

    @Test fun aPoorFixCannotDrift() {
        // accuracy of 50 m: nothing under 50 m can be told from the road
        val w = OffRouteWatch()
        val p = Geo.offset(start, 45.0, 2000.0)
        for (i in 0 until 6) {
            w.locate(motorway, Geo.offset(p, 135.0, 30.0 + i * 3.0), 19, 53.0, 100.0, 50.0)
            assertFalse(w.leaving)
        }
    }

    @Test fun onlyTheWiderLookFindsTheLineAfterAGap() {
        // a tunnel: the car comes out further along than the 80 segments of the usual search
        val w = OffRouteWatch()
        val out = Geo.offset(start, 45.0, 15_000.0)
        val snap = w.locate(motorway, out, 0, 45.0, 90.0, 5.0)!!
        assertEquals(15_000.0, snap.along, 5.0)
        assertFalse(w.off)
    }
}
