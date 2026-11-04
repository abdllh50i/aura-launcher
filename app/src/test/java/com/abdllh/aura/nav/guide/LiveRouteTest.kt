package com.abdllh.aura.nav.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRouteTest {
    @Test fun theRunningAppsRoute() {
        val g = Fixtures.trip("jubail-dammam").route
        val r = LiveRoute.of(g, true)
        assertEquals(g.steps.size, r.steps.size)
        assertEquals(EtaTable.DEFAULT.seconds(g), r.duration, 0.1)
        assertEquals(EtaTable.DEFAULT.seconds(g), r.timeLeft(0.0), 1.0)
        assertTrue(r.steps.none { ";" in it.name || ";" in it.ref })
        assertEquals("طريق الظهران الجبيل السريع", r.steps.first { it.ref == "613" }.name)
    }

    @Test fun whereAndWhatKeepTheirMeaning() {
        val g = Fixtures.trip("d6").route
        val r = LiveRoute.of(g, true)
        for (i in g.steps.indices) {
            assertEquals(g.steps[i].at, r.steps[i].at, 0.0)
            assertEquals(g.steps[i].silent, r.steps[i].silent)
            assertEquals(g.steps[i].span, r.steps[i].span, 0.0)
        }
    }

    @Test fun theRingsNumberOnlyWhenItCanBeTrusted() {
        val g = Fixtures.trip("d3").route
        val r = LiveRoute.of(g, true)
        val i = g.steps.indexOfFirst { it.entersRing }
        assertEquals(3, g.steps[i].exit)
        assertEquals(0, r.steps[i].exit)
        assertFalse(r.steps[i].angle.isNaN())
    }

    @Test fun limitsReachTheRoadWatch() {
        val r = LiveRoute.of(Fixtures.trip("jubail-dammam").route, true)
        assertNotNull(r.limits)
        // on the expressway, going its way
        val g = Fixtures.trip("jubail-dammam").route
        val (p, heading) = g.line.at(30_000.0)
        assertEquals(120, r.limits!!.at(p, heading))
    }

    @Test fun noFactsNoLimits() {
        assertEquals(null, LiveRoute.of(Fixtures.trip("dammam-jeddah").route, true).limits)
    }

    @Test fun signsWithoutTheirNoise() {
        val g = Fixtures.trip("dammam-jeddah").route
        val r = LiveRoute.of(g, true)
        assertTrue(r.steps.none { it.destinations.contains("Exit") })
        assertTrue(r.steps.any { it.exits == "32" })
    }

    @Test fun lanesOnlyWhenTheyMatter() {
        val r = LiveRoute.of(Fixtures.trip("dammam-jeddah").route, true)
        assertTrue(r.steps.all { it.lanes.isEmpty() || (it.lanes.size >= 2 && it.lanes.any { l -> !l.valid }) })
    }
}
