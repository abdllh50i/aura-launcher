package com.abdllh.aura.nav.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopsTest {
    private val longRoad = com.abdllh.aura.nav.Polyline((0..800).map { com.abdllh.aura.nav.LatLon(27.0, 49.0 + it * 0.001) }) // about 79 km

    private fun stationAt(metres: Double, right: Double, coarse: Boolean = false) =
        PoiRow(metres.toLong(), "محطة الدريس", null, "fuel", com.abdllh.aura.nav.Geo.offset(longRoad.at(metres).first, 180.0, right), 40, coarse)

    private fun road(motorway: Boolean = false, vararg rows: PoiRow): Guidance {
        val r = GuideRoute(longRoad, listOf(GuideStep("depart", "", 0, "", "", longRoad.points[0], longRoad.length, 3000.0, "", "", "", "driving", 0, 90,
            listOf(Crossing(intArrayOf(90), booleanArrayOf(true), -1, 0, longRoad.points[0], 0, if (motorway) listOf("motorway") else emptyList(), emptyList(), false)))), longRoad.length, 3000.0, setOf("SA"))
        GuideAnswer.prepare(r)
        return Guidance.build(r, true, Kit.world(pois = rows.toList()))
    }

    @Test fun theNextStationInWords() {
        val g = road(false, stationAt(38_400.0, 50.0))
        val s = g.nextFuel(0.0)
        assertNotNull(s)
        assertEquals("أقرب محطة بعد 38 كم", g.fuelText(s!!, 0.0))
        assertEquals("أقرب محطة بعد 38 كيلومترًا", Speech.text(g.fuelSpoken(s, 0.0), true))
    }

    @Test fun onlyOnALongRoad() {
        val g = road(false, stationAt(75_000.0, 50.0))
        assertNotNull(g.nextFuel(0.0))
        // under 30 km to go: no point
        assertNull(g.nextFuel(longRoad.length - 20_000))
    }

    @Test fun aStationPassedIsGone() {
        val g = road(false, stationAt(10_000.0, 50.0), stationAt(20_000.0, 50.0))
        assertEquals(10_000.0, g.nextFuel(0.0)!!.along, 5.0)
        assertEquals(20_000.0, g.nextFuel(9_900.0)!!.along, 5.0)
    }

    @Test fun onAMotorwayOnlyYourOwnSide() {
        assertNotNull(road(true, stationAt(10_000.0, 200.0)).nextFuel(0.0))
        assertNull(road(true, stationAt(10_000.0, -200.0)).nextFuel(0.0))
        // elsewhere either side, nearer
        assertNotNull(road(false, stationAt(10_000.0, -150.0)).nextFuel(0.0))
        assertNull(road(false, stationAt(10_000.0, -250.0)).nextFuel(0.0))
    }

    @Test fun aGeocodersPileIsNoStation() {
        assertNull(road(false, stationAt(10_000.0, 50.0, coarse = true)).nextFuel(0.0))
    }

    @Test fun theRealLongRoadsHaveStations() {
        val g = Fixtures.trip("dammam-riyadh").guidance(true)
        assertTrue(g.stopsOf("fuel").size > 20)
        assertNotNull(g.nextFuel(100_000.0))
    }

    @Test fun thePrayerStop() {
        val mosque = PoiRow(1, "مسجد الراجحي", null, "mosque", com.abdllh.aura.nav.Geo.offset(longRoad.at(40_000.0).first, 180.0, 50.0), 40, false)
        val g = road(false, mosque)
        // 10:00 in Saudi time, Dhuhr at about 11:25 there
        val now = java.time.ZonedDateTime.of(2026, 11, 4, 10, 0, 0, 0, java.time.ZoneOffset.ofHours(3)).toInstant().toEpochMilli()
        val here = com.abdllh.aura.nav.LatLon(27.0, 49.0)
        val p = g.prayerStop(now, here, 0.0, 3000.0, ramadan = false)
        assertEquals(PrayerTimes.Prayer.DHUHR, p.prayer)
        assertFalse("not within the 50 minutes left", p.beforeArrival)
        assertEquals(40_000.0, p.metresToMosque, 10.0)
        assertTrue(p.whenText(now, true), p.whenText(now, true).startsWith("الظهر بعد 1 س"))
        assertEquals("أقرب مسجد على طريقك بعد 40 كم", p.mosqueText(true))
    }

    @Test fun aPrayerDuringTheTripLooksPastWhereTheCarWillBe() {
        val early = PoiRow(1, "مسجد", null, "mosque", com.abdllh.aura.nav.Geo.offset(longRoad.at(10_000.0).first, 180.0, 50.0), 40, false)
        val late = PoiRow(2, "مسجد", null, "mosque", com.abdllh.aura.nav.Geo.offset(longRoad.at(60_000.0).first, 180.0, 50.0), 40, false)
        val g = road(false, early, late)
        val now = java.time.ZonedDateTime.of(2026, 11, 4, 11, 0, 0, 0, java.time.ZoneOffset.ofHours(3)).toInstant().toEpochMilli()
        // Dhuhr about 28 min away, 3600 s to go: by then the car is well past the first mosque
        val p = g.prayerStop(now, com.abdllh.aura.nav.LatLon(27.0, 49.0), 0.0, 3600.0, ramadan = false)
        assertTrue(p.beforeArrival)
        assertEquals(60_000.0, p.metresToMosque, 10.0)
    }

    @Test fun noMosqueIsNoCrash() {
        val g = road(false)
        val p = g.prayerStop(1_800_000_000_000L, com.abdllh.aura.nav.LatLon(27.0, 49.0), 0.0, 3000.0, ramadan = false)
        assertNull(p.mosque)
        assertNull(p.mosqueText(true))
    }

    @Test fun theSideOfTheDestination() {
        fun card(modifier: String, dest: com.abdllh.aura.nav.LatLon?): ArrivalCard {
            val line = Kit.line
            val end = GuideStep("arrive", modifier, 0, "", "", line.points.last(), 0.0, 0.0, "", "", "", "driving", 90, 0, emptyList())
            val r = GuideRoute(line, listOf(Kit.step(0.0, "depart"), end), line.length, 600.0, emptySet())
            GuideAnswer.prepare(r)
            return Guidance.build(r, true).arrival(dest)
        }
        assertEquals(ArrivalCard.Side.RIGHT, card("right", null).side)
        assertEquals(ArrivalCard.Side.LEFT, card("left", null).side)
        assertEquals(ArrivalCard.Side.AHEAD, card("", null).side)
        // the answer gave none: from the line and the place asked for
        val end = Kit.line.points.last()
        assertEquals(ArrivalCard.Side.RIGHT, card("", com.abdllh.aura.nav.Geo.offset(end, 180.0, 30.0)).side)
        assertEquals(ArrivalCard.Side.LEFT, card("", com.abdllh.aura.nav.Geo.offset(end, 0.0, 30.0)).side)
        assertEquals(ArrivalCard.Side.AHEAD, card("", com.abdllh.aura.nav.Geo.offset(end, 90.0, 30.0)).side)
        assertEquals("on the point", ArrivalCard.Side.AHEAD, card("", end).side)
        assertEquals("وجهتك على يمينك", Words.text(card("right", null).side.word, true, false))
    }

    @Test fun parkingNearArrival() {
        val end = Kit.line.points.last()
        fun parking(metres: Double, coarse: Boolean = false) = PoiRow(1, "مواقف", null, "parking", com.abdllh.aura.nav.Geo.offset(end, 0.0, metres), 30, coarse)
        fun card(vararg p: PoiRow) = Guidance.build(Kit.route(), true, Kit.world(pois = p.toList())).arrival(null)
        assertEquals(120.0, card(parking(120.0)).parkingMetres, 1.0)
        assertEquals("أقرب مواقف 120 م", card(parking(120.0)).parkingText(true))
        assertNull("too far to walk", card(parking(600.0)).parking)
        assertNull(card(parking(100.0, coarse = true)).parking)
        assertNull(card().parkingText(true))
        assertEquals("it is right there", "أقرب مواقف 10 م", card(parking(1.0)).parkingText(true))
    }
}
