package com.abdllh.aura.nav.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaTest {
    private val table = EtaTable.DEFAULT

    private fun step(metres: Double, seconds: Double, mode: String = "driving") = Kit.step(0.0, "turn", "right", duration = seconds, length = metres, mode = mode)

    @Test fun theFactorsOfRouteRequestJson() {
        assertEquals(1.27, table.factor(120.0), 1e-9)
        assertEquals(1.27, table.factor(110.0), 1e-9)
        assertEquals(1.127, table.factor(100.0), 1e-9)
        assertEquals(1.178, table.factor(70.0), 1e-9)
        assertEquals(1.038, table.factor(50.0), 1e-9)
        assertEquals(1.27, table.factor(20.0), 1e-9)
        assertEquals(1.27, table.factor(0.0), 1e-9)
    }

    @Test fun aStepByItsOwnSpeed() {
        // 1 km in 30 s is 120 km/h
        assertEquals(30 * 1.27, table.seconds(step(1000.0, 30.0)), 1e-6)
        // 1 km in 120 s is 30 km/h
        assertEquals(120 * 1.27, table.seconds(step(1000.0, 120.0)), 1e-6)
        // 1 km in 72 s is 50 km/h
        assertEquals(72 * 1.038, table.seconds(step(1000.0, 72.0)), 1e-6)
    }

    @Test fun aFerryKeepsItsTimetable() {
        assertEquals(5830.0, table.seconds(step(46_084.0, 5830.0, "ferry")), 1e-6)
    }

    @Test fun nothingBreaksOnNoTime() {
        assertEquals(0.0, table.seconds(step(0.0, 0.0)), 0.0)
    }

    @Test fun theRealTripsComeNearerOsrm() {
        // Jubail-Dammam: Valhalla 50.0 min, OSRM 59. Dammam-Riyadh: 191.8, OSRM 237.7
        assertEquals(59.0, table.seconds(Fixtures.trip("jubail-dammam").route) / 60, 3.0)
        assertEquals(237.7, table.seconds(Fixtures.trip("dammam-riyadh").route) / 60, 8.0)
    }

    @Test fun timeLeftFollowsTheLine() {
        val r = Fixtures.trip("jubail-dammam").route
        assertEquals(table.seconds(r), table.secondsLeft(r, 0.0), 1.0)
        assertEquals(0.0, table.secondsLeft(r, r.line.length), 1.0)
        val mid = table.secondsLeft(r, r.line.length / 2)
        assertTrue(mid > 0.25 * table.seconds(r) && mid < 0.75 * table.seconds(r))
    }

    @Test fun replaceableByHisOwnDrives() {
        val mine = EtaTable.parse("""[{"from":100,"factor":1.05},{"from":0,"factor":1.5}]""")
        assertNotNull(mine)
        assertEquals(1.05, mine!!.factor(120.0), 1e-9)
        assertEquals(1.5, mine.factor(40.0), 1e-9)
        assertEquals(30 * 1.05, mine.seconds(step(1000.0, 30.0)), 1e-6)
        // a bad file leaves the default in place
        assertNull(EtaTable.parse("not json"))
        assertNull(EtaTable.parse("[]"))
        assertNull(EtaTable.parse("""[{"from":0,"factor":-1}]"""))
        assertNull(EtaTable.parse("""[{"from":0}]"""))
    }

    @Test fun theFastestComesFirst() {
        // real: the router's second is the faster of Khobar corniche to Dammam corniche
        val c = Choices.rank(Fixtures.trip("khobar-corniche-dammam-corniche").answer.routes)
        assertEquals(1, c[0].index)
        assertEquals(0, c[1].index)
        assertTrue(c[0].fastest)
        assertTrue(c[1].slowerBy > 0)
        assertEquals("الأسرع", c[0].note(true))
    }

    @Test fun theDifferenceInWords() {
        fun route(km: Double, min: Double) = GuideRoute(com.abdllh.aura.nav.Polyline(emptyList()), listOf(Kit.step(0.0, "turn", "right", length = km * 1000, duration = min * 60)), km * 1000, min * 60, emptySet())
        val a = route(100.0, 60.0)
        val c = Choices.rank(listOf(route(110.0, 70.0), a, route(90.0, 60.3)))
        assertEquals(a, c[0].route)
        assertEquals("نفس الوقت، أقصر بـ 10 كم", c[1].note(true))
        // (both at 85 to 110 km/h: 70 min and 60 by the same factor)
        assertEquals("أبطأ بـ 11 دقيقة، أطول بـ 10 كم", c[2].note(true))
        assertEquals("slower by 11 min, longer by 10 km", c[2].note(false))
    }

    @Test fun plainMinutes() {
        fun route(sec: Double) = GuideRoute(com.abdllh.aura.nav.Polyline(emptyList()), listOf(Kit.step(0.0, "turn", "right", length = 50_000.0, duration = sec)), 50_000.0, sec, emptySet())
        assertEquals("أبطأ بدقيقة", Choices.rank(listOf(route(1500.0), route(1547.0)))[1].note(true))
        assertEquals("أبطأ بدقيقتين", Choices.rank(listOf(route(1500.0), route(1595.0)))[1].note(true))
    }

    @Test fun whatAChoiceCarries() {
        val c = Choices.rank(Fixtures.trip("causeway").answer.routes)
        assertTrue(c[0].hasToll)
        assertTrue(Choices.rank(Fixtures.trip("ferry").answer.routes)[0].hasFerry)
    }
}
