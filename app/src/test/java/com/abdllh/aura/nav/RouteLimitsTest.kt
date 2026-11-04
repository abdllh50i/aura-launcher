package com.abdllh.aura.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLimitsTest {
    @Test fun readsTheTagsOfARow() {
        // rows as BRouter's measured answers have them (Jubail to Dammam)
        assertEquals(120, RouteLimits.kmh("highway=motorway surface=asphalt maxspeed=120 oneway=yes"))
        assertEquals(0, RouteLimits.kmh("highway=secondary surface=asphalt oneway=yes"))
        assertEquals(0, RouteLimits.kmh(""))
    }

    @Test fun wordsAndRoughBucketsAreNoLimit() {
        assertEquals(0, RouteLimits.kmh("highway=primary maxspeed=urban"))
        assertEquals(0, RouteLimits.kmh("highway=primary maxspeed=rural"))
        assertEquals(0, RouteLimits.kmh("highway=primary maxspeed=unknown"))
        assertEquals(0, RouteLimits.kmh("highway=service maxspeed=10")) // (BRouter's bucket for 5 to 15)
        assertEquals(20, RouteLimits.kmh("highway=service maxspeed=20"))
    }

    @Test fun theDirectionDrivenCounts() {
        val tags = "highway=primary maxspeed=80 maxspeed:forward=60 maxspeed:backward=100"
        assertEquals(60, RouteLimits.kmh(tags))
        assertEquals(100, RouteLimits.kmh("reversedirection=yes $tags"))
        assertEquals(80, RouteLimits.kmh("reversedirection=yes highway=primary maxspeed=80 maxspeed:forward=60"))
    }

    // a line going east along the 27th parallel: about 991 m between points
    private val line = (0..4).map { LatLon(27.0, 49.0 + it * 0.01) }
    private fun row(i: Int, tags: String) = RouteLimits.Row(line[i], tags)

    @Test fun eachStretchHasItsOwnLimit() {
        val l = RouteLimits.of(line, listOf(row(1, "maxspeed=120"), row(2, "highway=x"), row(4, "maxspeed=80")))
        assertNotNull(l)
        assertEquals(120, l!!.at(LatLon(27.0, 49.005), 90.0))
        assertEquals(0, l.at(LatLon(27.0, 49.015), 90.0))
        assertEquals(80, l.at(LatLon(27.0, 49.025), 90.0))
        assertEquals(80, l.at(LatLon(27.0, 49.035), 90.0))
        assertEquals(991.0 * 3, l.metres, 30.0)
    }

    @Test fun onlyNearTheLineAndGoingItsWay() {
        val l = RouteLimits.of(line, listOf(row(4, "maxspeed=120")))!!
        val on = LatLon(27.0, 49.02)
        assertEquals(120, l.at(on, 90.0))
        assertEquals(120, l.at(on, null)) // (standing: no course to compare)
        assertEquals(120, l.at(on, 90.0 + 30.0))
        assertEquals(0, l.at(on, 270.0)) // the other way: not this carriageway
        assertEquals(0, l.at(on, 0.0)) // a road across
        assertEquals(120, l.at(Geo.offset(on, 0.0, 20.0), 90.0))
        assertEquals(0, l.at(Geo.offset(on, 0.0, 40.0), 90.0)) // a road beside it
    }

    @Test fun aLongSegmentIsFoundInItsMiddle() {
        // (a motorway's 2 km between two nodes: neither end is anywhere near the car)
        val l = RouteLimits.of(line, listOf(RouteLimits.Row(line[4], "maxspeed=120")))!!
        val long = RouteLimits.of(listOf(line[0], line[4]), listOf(RouteLimits.Row(line[4], "maxspeed=100")))!!
        assertEquals(120, l.at(LatLon(27.0, 49.02), 90.0))
        assertEquals(100, long.at(LatLon(27.0, 49.02), 90.0))
    }

    @Test fun noLimitsNoObject() {
        assertNull(RouteLimits.of(line, listOf(row(4, "highway=x"))))
        assertNull(RouteLimits.of(line, emptyList()))
    }

    @Test fun aRowOffTheLineEndsTheReading() {
        val l = RouteLimits.of(line, listOf(row(1, "maxspeed=120"), RouteLimits.Row(LatLon(10.0, 10.0), "maxspeed=60"), row(4, "maxspeed=80")))!!
        assertEquals(120, l.at(LatLon(27.0, 49.005), 90.0))
        assertEquals(0, l.at(LatLon(27.0, 49.025), 90.0)) // never a wrong number further on
    }

    @Test fun theOwnersLimitComesFirstAndHolds() {
        val h = LimitHold(4000)
        h.update(0, 120, 0)
        assertEquals(120, h.kmh); assertTrue(h.fromMap)
        h.update(80, 120, 1000)
        assertEquals(80, h.kmh); assertFalse(h.fromMap)
        // a hole in his line: his number stays through it, the map's does not flash
        h.update(0, 120, 3000)
        assertEquals(80, h.kmh); assertFalse(h.fromMap)
        h.update(0, 120, 5001)
        assertEquals(120, h.kmh); assertTrue(h.fromMap)
    }

    @Test fun aLimitGoesAfterItsHold() {
        val h = LimitHold(4000)
        h.update(0, 100, 0)
        h.update(0, 0, 3000)
        assertEquals(100, h.kmh)
        h.update(0, 0, 4001)
        assertEquals(0, h.kmh); assertFalse(h.fromMap)
    }
}
