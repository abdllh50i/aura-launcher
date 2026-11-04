package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.abdllh.aura.nav.Polyline
import org.junit.Test

class WarningsTest {
    private fun camera(metres: Double, right: Double = 0.0, dir: Int? = 90, kmh: Int = 0) = SpotRow(SpotRow.CAMERA, Kit.at(metres, right), dir, kmh, false, 0)
    private fun onRoad(vararg spots: SpotRow) = Guidance.build(Kit.route(), true, Kit.world(spots.toList())).spots

    @Test fun aSpotCountsOnlyGoingTheRoutesWay() {
        assertEquals(1, onRoad(camera(1000.0)).size)
        assertEquals("the other carriageway", 0, onRoad(camera(1000.0, dir = 270)).size)
        assertEquals("a road across", 0, onRoad(camera(1000.0, dir = 0)).size)
        assertEquals("near enough the same way", 1, onRoad(camera(1000.0, dir = 120)).size)
    }

    @Test fun aSpotCountsOnlyNearTheLine() {
        assertEquals(1, onRoad(camera(1000.0, right = 20.0)).size)
        assertEquals("a parallel road", 0, onRoad(camera(1000.0, right = 35.0)).size)
    }

    @Test fun withoutADirectionEitherWay() {
        // (no `dir`: a two-way road. One rule with RoadAhead: a camera stands beside its road, up to 25 m off, and the owner's own, placed by finger, have none; better told once too often)
        assertEquals(1, onRoad(camera(1000.0, right = 25.0, dir = null)).size)
        assertEquals(0, onRoad(camera(1000.0, right = 35.0, dir = null)).size)
    }

    @Test fun theDistanceIsAlongTheRoad() {
        val s = onRoad(camera(3000.0), camera(1000.0))
        assertEquals(listOf(1000.0, 3000.0).map { it }, s.map { Math.round(it.along / 10) * 10.0 })
    }

    @Test fun aRoadDrivenOutAndBackHasTheSpotTwice() {
        val out = (0..40).map { LatLon(27.0, 49.0 + it * 0.001) }
        val line = com.abdllh.aura.nav.Polyline(out + out.reversed())
        val r = GuideRoute(line, listOf(Kit.step(0.0, "depart")), line.length, 600.0, emptySet())
        val s = Guidance.build(r, true, Kit.world(listOf(camera(1000.0, dir = null)))).spots
        assertEquals(2, s.size)
    }

    @Test fun whatIsSaidForACamera() {
        val g = Guidance.build(Kit.route(), true, Kit.world(listOf(camera(1500.0, kmh = 80))))
        val at = g.spots[0].along
        assertNull(g.nextAlert(at - 700))
        val a = g.nextAlert(at - 500)
        assertNotNull(a)
        assertEquals(80, a!!.kmh)
        assertEquals("ساهر بعد 500 متر، السرعة 80", Speech.text(a.parts(), true))
        assertEquals("Speed camera in 500 metres, limit 80", Speech.text(a.parts(), false))
        assertNull("passed", g.nextAlert(at + 5))
    }

    @Test fun aCameraWithoutAnyLimit() {
        val g = Guidance.build(Kit.route(), true, Kit.world(listOf(camera(1500.0))))
        assertEquals("ساهر بعد 500 متر", Speech.text(g.nextAlert(g.spots[0].along - 500)!!.parts(), true))
    }

    @Test fun aCameraTakesTheRoadsLimit() {
        val r = Kit.route().apply { facts = RoadFacts(listOf(RoadStretch(0.0, line.length, 100, "trunk", "road", false, false, false))) }
        val g = Guidance.build(r, true, Kit.world(listOf(camera(1500.0))))
        assertEquals(100, g.nextAlert(1000.0)!!.kmh)
    }

    @Test fun whatIsSaidForABump() {
        val g = Guidance.build(Kit.route(), true, Kit.world(listOf(SpotRow(SpotRow.BUMP, Kit.at(1500.0), 90, 120, false, 0))))
        val at = g.spots[0].along
        assertNull("too far yet", g.nextAlert(at - 300))
        val a = g.nextAlert(at - 150)!!
        assertEquals("مطب بعد 150 مترًا", Speech.text(a.parts(), true))
        // (a bump's kmh is its road's limit, the data's way: never said)
        assertEquals(0, a.kmh)
    }

    @Test fun aSignalForAChip() {
        val g = Guidance.build(Kit.route(), true, Kit.world(listOf(Kit.signal(1500.0, 1), Kit.signal(1500.0, 2, dir = 270))))
        assertEquals(1, g.spots.size)
        val a = g.nextSignal(g.spots[0].along - 300)!!
        assertEquals("إشارة بعد 300 متر", Speech.text(a.parts(), true))
        assertNull(g.nextSignal(g.spots[0].along + 1))
    }

    // ---------------------------------------------------------------- the limit
    @Test fun theLimitsOrder() {
        assertEquals(LimitNow.Source.MINE, Limits.pick(80, 120, "motorway").source)
        assertEquals(80, Limits.pick(80, 120, "motorway").kmh)
        assertEquals(LimitNow.Source.KNOWN, Limits.pick(0, 120, "motorway").source)
        assertEquals(120, Limits.pick(0, 120, "primary").kmh)
        val e = Limits.pick(0, 0, "motorway")
        assertEquals(LimitNow.Source.ESTIMATED, e.source)
        assertEquals(120, e.kmh)
        assertEquals(LimitNow.Source.NONE, Limits.pick(0, 0, null).source)
        assertEquals(0, Limits.pick(0, 0, null).kmh)
    }

    @Test fun theSignCanSayWhichItIs() {
        assertNull(Limits.pick(80, 0, null).label)
        assertEquals("من الخريطة", Words.text(Limits.pick(0, 120, null).label!!, true))
        assertEquals("تقريبي", Words.text(Limits.pick(0, 0, "primary").label!!, true))
    }

    @Test fun estimatedByClass() {
        assertEquals(120, Limits.estimated("motorway"))
        assertEquals(100, Limits.estimated("trunk"))
        assertEquals(80, Limits.estimated("primary"))
        assertEquals(40, Limits.estimated("residential"))
        assertEquals(60, Limits.estimated("motorway", "ramp"))
        assertEquals(0, Limits.estimated("something else"))
    }

    @Test fun theLimitAlongARealTrip() {
        val r = Fixtures.trip("jubail-dammam").route
        val known = Limits.at(r, 30_000.0, 0)
        assertEquals(LimitNow.Source.KNOWN, known.source)
        assertEquals(120, known.kmh)
        assertEquals(LimitNow.Source.MINE, Limits.at(r, 30_000.0, 100).source)
        // with no facts, the answer's own "motorway"
        val bare = Fixtures.trip("dammam-riyadh").route
        val e = Limits.at(bare, 100_000.0, 0)
        assertEquals(LimitNow.Source.ESTIMATED, e.source)
        assertEquals(120, e.kmh)
    }

    // ---------------------------------------------------------------- before a trip: the spec's rules on real routes
    private fun kinds(id: String) = Fixtures.trip(id).guidance(true).warnings.map { it.kind }

    @Test fun aPublicRoadThroughABaseStaysSilent() {
        // the spec's own example, on King Abdul Aziz street (primary) and the expressway
        assertTrue(kinds("jubail-dammam").isEmpty())
    }

    @Test fun aMallInTownIsNoBase() {
        // Jubail Center: inside a wedge of the naval base's simplified ring, 74 m from its edge
        assertTrue(kinds("j1").isEmpty())
    }

    @Test fun aRouteIntoABaseWarns() {
        assertTrue(kinds("gate-air").contains(TripWarning.Kind.MILITARY_ROAD))
    }

    @Test fun aGuardedGateWarns() {
        assertTrue(kinds("gate-camp").contains(TripWarning.Kind.GUARD))
    }

    @Test fun theCauseway() {
        assertTrue(kinds("causeway").contains(TripWarning.Kind.CAUSEWAY))
        // its booth is its own: one word
        assertFalse(kinds("causeway").contains(TripWarning.Kind.TOLL))
        // k6 drives "طريق جسر الملك فهد" on land
        assertFalse(kinds("k6").contains(TripWarning.Kind.CAUSEWAY))
    }

    @Test fun aRoadToTheBorderDrawsTheZoneItEndsIn() {
        // a fact of the data for now: OSM's "Border Exclusion Zone" (1118 km2) is kind 3, and the Salwa post stands in it
        assertTrue(kinds("salwa").contains(TripWarning.Kind.MILITARY_END))
    }

    @Test fun theHaramOnTheWay() {
        val w = Fixtures.trip("jed-taif").guidance(true).warnings.first { it.kind == TripWarning.Kind.HARAM_ROUTE }
        assertEquals("الطريق يدخل حدود الحرم، والدخول للمسلمين فقط", Speech.text(w.parts(), true))
        assertTrue("about 45 km inside the grown ring", w.metres in 40_000.0..50_000.0)
    }

    private val makkah = ZoneRow(642, ZoneRow.HARAM_MAKKAH, "حدود الحرم المكي", null, GeoBox(21.0, 39.5, 21.6, 40.1),
        listOf(LatLon(21.0, 39.5), LatLon(21.0, 40.1), LatLon(21.6, 40.1), LatLon(21.6, 39.5)))
    private val inside = LatLon(21.42, 39.82)
    private val outside = LatLon(21.54, 39.17)

    @Test fun theRuleOfTheHaram() {
        // his "go round it" with a destination outside: excluded
        val around = Haram.plan(outside, LatLon(21.27, 40.41), true, listOf(makkah))
        assertEquals(1, around.exclude.size)
        assertNull(around.destinationInside)
        // a destination inside would answer NoRoute: not excluded, and said
        val to = Haram.plan(outside, inside, true, listOf(makkah))
        assertTrue(to.exclude.isEmpty())
        assertEquals(makkah, to.destinationInside)
        // nor when the car is inside
        val from = Haram.plan(inside, outside, true, listOf(makkah))
        assertTrue(from.exclude.isEmpty())
        assertEquals(makkah, from.startInside)
        // the setting off: nothing excluded, still known
        assertTrue(Haram.plan(outside, inside, false, listOf(makkah)).exclude.isEmpty())
        assertEquals(makkah, Haram.plan(outside, inside, false, listOf(makkah)).destinationInside)
    }

    private fun routeIn(vararg zones: ZoneRow) = Guidance.build(Kit.route(), true, Kit.world(zones = zones.toList())).warnings

    /** A box from [from] to [to] metres along the road (which may be past its ends), 500 m either side of it. */
    private fun zoneAround(from: Double, to: Double, kind: Int): ZoneRow {
        val perM = (Kit.line.points[1].lon - Kit.line.points[0].lon) / Kit.line.cumulative[1]
        val w = 49.0 + from * perM
        val e = 49.0 + to * perM
        val n = 27.0 + 500 / 110_540.0
        val s = 27.0 - 500 / 110_540.0
        return ZoneRow(1, kind, "z", null, GeoBox(s, w, n, e), listOf(LatLon(n, w), LatLon(n, e), LatLon(s, e), LatLon(s, w)))
    }

    @Test fun aDestinationInsideTheHaramSaysThat() {
        val w = routeIn(zoneAround(Kit.line.length - 3000, Kit.line.length + 3000, ZoneRow.HARAM_MAKKAH))
        assertEquals(listOf(TripWarning.Kind.HARAM_END), w.map { it.kind })
        assertEquals("وجهتك داخل حدود الحرم، والدخول للمسلمين فقط", Speech.text(w[0].parts(), true))
    }

    @Test fun zonesWithAndWithoutFacts() {
        val mid = zoneAround(3000.0, 5000.0, ZoneRow.MILITARY)
        val end = zoneAround(Kit.line.length - 3000, Kit.line.length + 3000, ZoneRow.PRIVATE)
        // no classes known: passing through is not told, ending inside is
        assertTrue(routeIn(mid).isEmpty())
        assertEquals(listOf(TripWarning.Kind.PRIVATE_END), routeIn(end).map { it.kind })
        fun withClass(c: String) = Kit.route().apply { facts = RoadFacts(listOf(RoadStretch(0.0, line.length, 0, c, "road", false, false, false))) }
        assertTrue(Guidance.build(withClass("motorway"), true, Kit.world(zones = listOf(mid))).warnings.isEmpty())
        assertTrue(Guidance.build(withClass("trunk"), true, Kit.world(zones = listOf(mid))).warnings.isEmpty())
        assertEquals(listOf(TripWarning.Kind.MILITARY_ROAD), Guidance.build(withClass("tertiary"), true, Kit.world(zones = listOf(mid))).warnings.map { it.kind })
    }

    @Test fun leavingAZoneIsNotEntering() {
        val start = zoneAround(-3000.0, 3000.0, ZoneRow.MILITARY)
        val r = Kit.route().apply { facts = RoadFacts(listOf(RoadStretch(0.0, line.length, 0, "residential", "road", false, false, false))) }
        assertTrue(Guidance.build(r, true, Kit.world(zones = listOf(start))).warnings.isEmpty())
    }

    /** The tester's real rows at the south end of Khobar's corniche: a line that ends on them never saw the sea. */
    @Test fun theCornicheIsNotTheCauseway() {
        val line = Polyline(listOf(LatLon(26.30, 50.20), LatLon(26.27, 50.21), LatLon(26.24841, 50.220668)))
        val r = GuideRoute(line, emptyList(), line.length, 0.0, setOf("SA"))
        assertFalse(Guidance.build(r, true, Kit.world()).warnings.any { it.kind == TripWarning.Kind.CAUSEWAY })
        val south = Polyline(listOf(LatLon(26.25, 50.21), LatLon(26.200089, 50.220032)))
        assertFalse(Guidance.build(GuideRoute(south, emptyList(), south.length, 0.0, setOf("SA")), true, Kit.world()).warnings.any { it.kind == TripWarning.Kind.CAUSEWAY })
    }

    @Test fun leavingTheHaramOrAnErrandInsideIsNotComingIn() {
        val around = zoneAround(-3000.0, Kit.line.length + 3000, ZoneRow.HARAM_MAKKAH)
        assertTrue("wholly inside", routeIn(around).isEmpty())
        val start = zoneAround(-3000.0, 3000.0, ZoneRow.HARAM_MAKKAH)
        assertTrue("only driving out of it", routeIn(start).isEmpty())
    }

    @Test fun gatesByTheirKindAndRoad() {
        fun warned(sub: Int, road: Int, metres: Double = 3000.0) = Guidance.build(Kit.route(), true, Kit.world(gates = listOf(GateRow(Kit.at(metres), sub, road)))).warnings.map { it.kind }
        assertEquals(listOf(TripWarning.Kind.GUARD), warned(GateRow.CHECKPOINT, 6))
        assertEquals(listOf(TripWarning.Kind.GUARD), warned(GateRow.PRIVATE, 8))
        assertTrue("a checkpoint on the motorway is driven through", warned(GateRow.CHECKPOINT, 0).isEmpty())
        assertTrue("a plain gate", warned(1, 8).isEmpty())
        assertTrue("the car's own yard, leaving", warned(GateRow.PRIVATE, 8, 100.0).isEmpty())
        assertEquals(listOf(TripWarning.Kind.BORDER), warned(GateRow.BORDER, 1))
        assertEquals(listOf(TripWarning.Kind.TOLL), warned(GateRow.TOLL, 1))
        // beside the road is not on it
        assertTrue(Guidance.build(Kit.route(), true, Kit.world(gates = listOf(GateRow(Kit.at(3000.0, 40.0), GateRow.PRIVATE, 8)))).warnings.isEmpty())
    }

    @Test fun aTrackAtTheEnd() {
        fun route(lastUse: String, from: Double) = Kit.route().apply {
            facts = RoadFacts(listOf(RoadStretch(0.0, from, 0, "primary", "road", false, false, false), RoadStretch(from, line.length, 0, "unclassified", lastUse, false, false, false)))
        }
        val w = Guidance.build(route("track", Kit.line.length - 400), true).warnings
        assertEquals(listOf(TripWarning.Kind.TRACK_END), w.map { it.kind })
        assertEquals(400.0, w[0].metres, 1.0)
        assertEquals("آخر الطريق ترابي", Speech.text(w[0].parts(), true))
        assertTrue(Guidance.build(route("road", Kit.line.length - 400), true).warnings.isEmpty())
        assertTrue("a few metres", Guidance.build(route("track", Kit.line.length - 20), true).warnings.isEmpty())
    }

    @Test fun aDestinationFarFromTheRoad() {
        // (Jana Island answered Ok, 22.5 km from the road it ended on)
        val g = Guidance.build(Kit.route(), true, NoWorld, 22_517.0)
        assertEquals(listOf(TripWarning.Kind.FAR_FROM_ROAD), g.warnings.map { it.kind })
        assertEquals(22_517.0, g.warnings[0].metres, 0.1)
    }

    @Test fun ownerSignalsAndCamerasCount() {
        val s = onRoad(SpotRow(SpotRow.SIGNAL, Kit.at(1000.0), null, 0, true, 7))
        assertEquals(1, s.size)
        assertTrue(s[0].owner)
    }
}
