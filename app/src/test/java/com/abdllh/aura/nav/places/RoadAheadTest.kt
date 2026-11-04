package com.abdllh.aura.nav.places

import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Polyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A road going north from 26N 50E, and things beside it. */
class RoadAheadTest {
    /** Metres north of the start, and east of the road. */
    private fun at(north: Double, east: Double = 0.0): LatLon = Geo.offset(Geo.offset(LatLon(26.0, 50.0), 0.0, north), 90.0, east)
    private fun e6(x: Double) = (x * 1e6).toInt()
    private fun north(km: Double, step: Double = 50.0) = Polyline(List((km * 1000 / step).toInt() + 1) { at(it * step) })

    private fun spot(kind: Int, north: Double, east: Double = 0.0, dir: Int? = null, kmh: Int? = null, j: Int? = null): SpotRow {
        val p = at(north, east)
        return SpotRow(kind, e6(p.lat), e6(p.lon), dir, kmh, 1, j)
    }

    private fun spots(line: Polyline, vararg rows: SpotRow) = RoadAhead.spotsAlong(line, rows.sortedBy { it.lat })

    @Test fun aCameraIsOnTheRouteOnlyGoingItsWay() {
        val line = north(2.0)
        assertEquals(1, spots(line, spot(1, 1000.0, 12.0, dir = 0)).cameras.size)
        assertEquals(1, spots(line, spot(1, 1000.0, 12.0, dir = null)).cameras.size) // null means either way
        assertEquals(0, spots(line, spot(1, 1000.0, 12.0, dir = 180)).cameras.size) // the other carriageway's
        assertEquals(0, spots(line, spot(1, 1000.0, 45.0, dir = 0)).cameras.size) // too far off the road
    }

    @Test fun whereItIsAlongTheRoad() {
        val h = spots(north(2.0), spot(1, 1234.0, 5.0)).cameras.single()
        assertEquals(1234.0, h.along, 2.0)
        assertEquals(5.0, h.offset, 1.0)
    }

    @Test fun signalNodesOfOneJunctionAreOneSignal() {
        val line = north(3.0)
        val s = spots(line,
            spot(2, 1000.0, j = 7), spot(2, 1040.0, j = 7), spot(2, 1070.0, 3.0, j = 7), // three nodes, one junction
            spot(2, 2000.0, j = 9))
        assertEquals(2, s.junctions.size)
        assertEquals(listOf(7, 9), s.junctions.map { it.junction })
        assertEquals(1000.0, s.junctions[0].along, 2.0)
        assertEquals(2, s.junctionsBetween(0.0, 2500.0))
        assertEquals(1, s.junctionsBetween(1500.0, 2500.0))
        assertEquals(0, s.junctionsBetween(2100.0, 2500.0))
    }

    @Test fun aRouteThatComesBackPassesTheJunctionAgain() {
        // up 1.5 km, back down: the same junction twice, as two signals on the way
        val up = List(31) { at(it * 50.0) }
        val line = Polyline(up + up.reversed().drop(1))
        assertEquals(2, spots(line, spot(2, 1000.0, j = 4)).junctions.size)
    }

    @Test fun aSignalForTheOtherWayIsNotOurs() {
        val line = north(2.0)
        assertEquals(0, spots(line, spot(2, 1000.0, dir = 180, j = 1)).junctions.size)
        assertEquals(1, spots(line, spot(2, 1000.0, dir = 0, j = 1)).junctions.size)
        assertEquals(1, spots(line, spot(2, 1000.0, dir = 90, j = 1)).junctions.size) // the crossing road's node: the same junction
    }

    @Test fun onASignalOrABumpTheLimitIsTheRoads() {
        val s = spots(north(2.0), spot(3, 500.0, kmh = 120), spot(2, 1000.0, kmh = 36, j = 1), spot(1, 1500.0, kmh = 80))
        val bump = s.bumps.single()
        assertEquals(120, bump.roadKmh)
        assertNull(bump.cameraKmh)
        assertEquals(36, s.junctions.single().roadKmh)
        assertEquals(80, s.cameras.single().cameraKmh)
        assertNull(s.cameras.single().roadKmh)
    }

    @Test fun twoBumpsOnOnePointAreOneWarning() {
        val s = spots(north(2.0), spot(3, 500.0), spot(3, 512.0), spot(3, 700.0))
        assertEquals(2, s.bumps.size)
    }

    @Test fun nothingNearANothing() {
        assertTrue(spots(north(1.0)).hits.isEmpty())
        assertTrue(RoadAhead.spotsAlong(Polyline(emptyList()), emptyList()).hits.isEmpty())
        assertNull(RoadAhead.latBand(Polyline(listOf(at(0.0)))))
    }

    @Test fun aLongRouteCostsLittle() {
        val line = north(1000.0, 200.0)
        val rows = List(4000) { spot(2, it * 250.0, if (it % 2 == 0) 0.0 else 500.0, j = it) }
        val t0 = System.nanoTime()
        val s = RoadAhead.spotsAlong(line, rows.sortedBy { it.lat })
        assertEquals(2000, s.junctions.size)
        assertTrue("${(System.nanoTime() - t0) / 1000000} ms", System.nanoTime() - t0 < 2_000_000_000L)
    }

    // ------------------------------------------------------------------------------------------- gates

    @Test fun aGuardOffTheMainRoadsWarns() {
        assertTrue(RoadAhead.warnsGate(3, 3)) // gate-camp's "Saudi Aramco main gate": a checkpoint on a secondary road
        assertTrue(RoadAhead.warnsGate(2, 8))
        assertFalse(RoadAhead.warnsGate(3, 0)) // a checkpoint on a motorway (Jubail-Dammam's): every car goes through
        assertFalse(RoadAhead.warnsGate(3, 1))
        assertFalse(RoadAhead.warnsGate(1, 6)) // a plain gate
        assertFalse(RoadAhead.warnsGate(5, 3)) // a toll booth
    }

    @Test fun gatesOnTheWayOnly() {
        val p = at(500.0)
        val q = at(900.0, 60.0)
        val hits = RoadAhead.gatesAlong(north(2.0), listOf(GateRow(e6(p.lat), e6(p.lon), 3, 3), GateRow(e6(q.lat), e6(q.lon), 3, 3)))
        assertEquals(1, hits.size)
        assertEquals(500.0, hits[0].along, 2.0)
        assertTrue(hits[0].warn)
    }

    // ------------------------------------------------------------------------------------------- zones

    /** A box from [south] to [northM] metres, 500 m either side of the road. */
    private fun zone(kind: Int, south: Double, northM: Double, west: Double = -500.0, east: Double = 500.0): ZoneRow {
        val c = listOf(at(south, west), at(south, east), at(northM, east), at(northM, west), at(south, west))
        val ring = c.joinToString(",", "[", "]") { "[${it.lon},${it.lat}]" }
        return ZoneRow(1, kind, "z", null, c.minOf { it.lat }, c.maxOf { it.lat }, c.minOf { it.lon }, c.maxOf { it.lon }, ring)
    }

    @Test fun theHaramWarnsAnyRouteThatComesIn() {
        val h = RoadAhead.zoneAlong(zone(1, 1000.0, 2000.0), north(5.0)) { 0 }!!
        assertTrue(h.warn)
        assertTrue(h.entered)
        assertFalse(h.endsInside)
        assertEquals(1000.0, h.enterAlong, 2.0)
        assertEquals(2000.0, h.lastAlong, 2.0)
        assertEquals(1000.0, h.insideMetres, 3.0)
    }

    @Test fun aHighwayThroughABaseSaysNothing() {
        // Jubail-Dammam crosses the naval base's polygon
        val h = RoadAhead.zoneAlong(zone(3, 1000.0, 2000.0), north(5.0)) { 0 }!!
        assertFalse(h.warn)
        assertEquals(0.0, h.offMainMetres!!, 0.0)
        assertEquals(1000.0, h.insideMetres, 3.0)
    }

    @Test fun leavingTheMainRoadsInsideWarns() {
        val line = north(5.0)
        // a primary road (2) from 1.5 km on
        val h = RoadAhead.zoneAlong(zone(3, 1000.0, 2000.0), line) { seg -> if (line.cumulative[seg] >= 1500.0) 2 else 1 }!!
        assertTrue(h.warn)
        assertEquals(500.0, h.offMainMetres!!, 60.0)
    }

    @Test fun aSlipRoadsEndIsNotLeaving() {
        val line = north(5.0, 10.0)
        val h = RoadAhead.zoneAlong(zone(3, 1000.0, 2000.0), line) { seg -> if (line.cumulative[seg] in 1500.0..1520.0) 2 else 0 }!!
        assertFalse(h.warn)
    }

    @Test fun aRouteThatEndsInsideWarnsWithOrWithoutClasses() {
        val line = north(1.5)
        assertTrue(RoadAhead.zoneAlong(zone(3, 1000.0, 2000.0), line) { 0 }!!.warn)
        val h = RoadAhead.zoneAlong(zone(4, 1000.0, 2000.0), line, null)!!
        assertTrue(h.warn)
        assertTrue(h.endsInside)
        assertNull(h.offMainMetres)
    }

    @Test fun withoutClassesACrossingIsToldButNotWarned() {
        val h = RoadAhead.zoneAlong(zone(3, 1000.0, 2000.0), north(5.0), null)!!
        assertFalse(h.warn)
        assertNull(h.offMainMetres)
        assertEquals(1000.0, h.insideMetres, 3.0)
    }

    @Test fun aRouteThatOnlyDrivesOutSaysNothing() {
        val h = RoadAhead.zoneAlong(zone(3, -500.0, 500.0), north(3.0)) { 8 }!!
        assertTrue(h.startsInside)
        assertFalse(h.entered)
        assertFalse(h.warn)
        assertEquals(0.0, h.offMainMetres!!, 0.0)
    }

    @Test fun aStretchCanCrossASmallZoneWithBothEndsOutside() {
        // two points 5 km apart, a zone of 200 m between them
        val line = Polyline(listOf(at(0.0), at(5000.0)))
        val h = RoadAhead.zoneAlong(zone(1, 2000.0, 2200.0), line, null)
        assertNotNull(h)
        assertTrue(h!!.warn)
        assertEquals(200.0, h.insideMetres, 3.0)
    }

    @Test fun neverInsideIsNull() {
        assertNull(RoadAhead.zoneAlong(zone(1, 1000.0, 2000.0, 300.0, 800.0), north(5.0), null))
        assertNull(RoadAhead.zoneAlong(zone(1, 9000.0, 9900.0), north(5.0), null))
    }

    @Test fun ringsParseAsTheyAreStored() {
        val r = RoadAhead.parseRing("[[39.5,21.2],[39.6,21.2],[39.6,21.3],[39.5,21.2]]")!!
        assertEquals(8, r.size)
        assertEquals(39.5, r[0], 0.0)
        assertEquals(21.2, r[1], 0.0)
        assertTrue(RoadAhead.inside(21.22, 39.55, r))
        assertFalse(RoadAhead.inside(21.29, 39.51, r))
        assertNull(RoadAhead.parseRing(""))
        assertNull(RoadAhead.parseRing("[[1,2]]"))
        assertNull(RoadAhead.parseRing("[[1,2],[3,4],[5]]"))
    }

    @Test fun classNumbersAreGateRoads() {
        assertEquals(0, RoadAhead.classNumber("motorway"))
        assertEquals(1, RoadAhead.classNumber("trunk"))
        assertEquals(2, RoadAhead.classNumber("primary"))
        assertEquals(6, RoadAhead.classNumber("residential"))
        assertEquals(8, RoadAhead.classNumber("service_other"))
    }

    // ------------------------------------------------------------------------------------------- what is ahead

    @Test fun aPlaceAheadAndItsSide() {
        val line = north(2.0)
        val p = at(1000.0, 80.0)
        val on = RoadAhead.nearestOn(line, 0, line.points.size - 1, 0.0, p.lat, p.lon, 200.0)!!
        assertEquals(1000.0, on.along, 3.0)
        assertEquals(80.0, on.offset, 2.0)
        assertTrue(on.onRight) // east of a road going north
        val q = at(1000.0, -80.0)
        assertFalse(RoadAhead.nearestOn(line, 0, line.points.size - 1, 0.0, q.lat, q.lon, 200.0)!!.onRight)
    }

    @Test fun whatIsBehindOrFarIsNot() {
        val line = north(2.0)
        val p = at(500.0, 20.0)
        assertNull(RoadAhead.nearestOn(line, 0, line.points.size - 1, 1000.0, p.lat, p.lon, 200.0))
        val q = at(1000.0, 400.0)
        assertNull(RoadAhead.nearestOn(line, 0, line.points.size - 1, 0.0, q.lat, q.lon, 200.0))
    }

    @Test fun stretchesCoverTheWayAheadAndStopWhenAsked() {
        val line = north(30.0, 100.0)
        val seen = ArrayList<IntArray>()
        RoadAhead.stretches(line, 10_050.0, 12_000.0, 200.0) { s, n, _, _, first, last ->
            seen.add(intArrayOf(s, n, first, last))
            true
        }
        assertEquals(100, seen.first()[2]) // the segment the 10 km is on
        for (k in 1 until seen.size) assertEquals(seen[k - 1][3], seen[k][2]) // no gap
        assertTrue(line.cumulative[seen.last()[3]] >= 22_050.0 - 5000.0)
        assertTrue(line.cumulative[seen.last()[2]] < 22_050.0)
        var n = 0
        RoadAhead.stretches(line, 0.0, 30_000.0, 200.0) { _, _, _, _, _, _ -> n++; false }
        assertEquals(1, n)
    }
}
