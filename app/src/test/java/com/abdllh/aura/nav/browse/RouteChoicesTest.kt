package com.abdllh.aura.nav.browse

import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Polyline
import com.abdllh.aura.nav.Route
import com.abdllh.aura.nav.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteChoicesTest {
    /** North from (26.3, 50.1) for [km], on a meridian [eastM] metres to the east. */
    private fun north(km: Double, eastM: Double = 0.0, fromKm: Double = 0.0) =
        listOf(LatLon(26.3 + fromKm / 111.32, 50.1 + eastM / 99_800.0), LatLon(26.3 + (fromKm + km) / 111.32, 50.1 + eastM / 99_800.0))

    private fun step(name: String, m: Double, ref: String = "") = Step("turn", "straight", 0, name, ref, LatLon(0.0, 0.0), m, m / 10)

    private fun route(pts: List<LatLon>, vararg steps: Step) = Route(Polyline(pts), steps.toList(), 1000.0, 100.0)

    @Test fun theSameLineIsAllShared() {
        val a = north(5.0)
        assertEquals(1.0, RouteChoices.shared(a, a), 0.001)
    }

    @Test fun aRoadTwoHundredMetresOverIsNotShared() {
        assertEquals(0.0, RouteChoices.shared(north(5.0), north(5.0, eastM = 200.0)), 0.001)
    }

    @Test fun halfOfItAlongTheFirst() {
        val first = north(10.0)
        // 5 km on the first, then 5 km on its own road 500 m to the east
        val second = north(5.0) + north(5.0, eastM = 500.0, fromKm = 5.0)
        val s = RouteChoices.shared(first, second)
        assertTrue("shared $s", s in 0.4..0.6)
    }

    @Test fun aRouteOverTheLimitIsNotOffered() {
        val first = route(north(10.0), step("A", 10_000.0))
        val nearlyTheSame = route(north(9.0) + north(1.0, eastM = 500.0, fromKm = 9.0), step("A", 9000.0), step("B", 1000.0))
        assertEquals(1, RouteChoices.offer(listOf(first, nearlyTheSame)).size)
    }

    @Test fun aRouteUnderTheLimitIsOfferedSecond() {
        val first = route(north(10.0), step("A", 10_000.0))
        val other = route(north(10.0, eastM = 500.0), step("B", 10_000.0))
        val o = RouteChoices.offer(listOf(first, other))
        assertEquals(2, o.size)
        assertEquals("A", o[0].road)
        assertEquals("B", o[1].road)
    }

    @Test fun theFirstRouteThatQualifiesIsTheSecond() {
        val first = route(north(10.0), step("A", 10_000.0))
        val same = route(north(10.0), step("A", 10_000.0))
        val other = route(north(10.0, eastM = 500.0), step("C", 10_000.0))
        val o = RouteChoices.offer(listOf(first, same, other))
        assertEquals(2, o.size)
        assertEquals("C", o[1].road)
    }

    @Test fun eachIsNamedByTheLongestRoadTheOtherDoesNotUse() {
        val steps = listOf(step("Shared", 9000.0), step("Mine", 1000.0), step("Short", 200.0))
        val theirs = listOf(step("Shared", 9000.0), step("Theirs", 4000.0))
        assertEquals("Mine", RouteChoices.road(steps, theirs))
        assertEquals("Theirs", RouteChoices.road(theirs, steps))
    }

    @Test fun withNothingOfItsOwnItIsItsLongestRoad() {
        val steps = listOf(step("A", 100.0), step("B", 900.0))
        assertEquals("B", RouteChoices.road(steps, steps))
    }

    @Test fun oneRouteAloneIsItsLongestRoadAndARefWhenItHasNoName() {
        assertEquals("40", RouteChoices.road(listOf(step("", 5000.0, "40"), step("A", 100.0)), null))
    }

    @Test fun noNamesNoRoad() {
        assertEquals("", RouteChoices.road(listOf(step("", 100.0)), null))
    }

    @Test fun nothingToOffer() {
        assertTrue(RouteChoices.offer(emptyList()).isEmpty())
    }
}
