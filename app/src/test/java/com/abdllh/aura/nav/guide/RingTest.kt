package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RingTest {
    @Test fun wayFromTheAngle() {
        assertEquals(Ring.Way.STRAIGHT, Ring.way(10.0))
        assertEquals(Ring.Way.SLIGHT_RIGHT, Ring.way(45.0))
        assertEquals(Ring.Way.RIGHT, Ring.way(90.0))
        assertEquals(Ring.Way.SLIGHT_LEFT, Ring.way(-30.0))
        assertEquals(Ring.Way.LEFT, Ring.way(-120.0))
        assertEquals(Ring.Way.BACK, Ring.way(-175.0))
        assertEquals(Ring.Way.BACK, Ring.way(170.0))
        assertEquals(Ring.Way.NONE, Ring.way(Double.NaN))
    }

    /** A ring with one way off every [spacing] metres: [arms] on the way round, and the way out. */
    private fun ringStep(exit: Int, arms: Int, spacing: Double, angle: Double = -90.0): Pair<GuideStep, GuideStep> {
        val cross = (0..arms).map { Crossing(intArrayOf(0, 90, 180), booleanArrayOf(it > 0, true, false), 2, 1, Kit.at(3000.0 + it * spacing), -1, emptyList(), emptyList(), false) }
        val s = GuideStep("roundabout", "right", exit, "", "", Kit.at(3000.0), 40.0, 5.0, "", "", "", "driving", 90, 90, cross)
        val out = Crossing(intArrayOf(0, 90, 180), booleanArrayOf(true, true, false), 2, 1, Kit.at(3000.0 + (arms + 1) * spacing), -1, emptyList(), emptyList(), false)
        val e = GuideStep("exit roundabout", "right", 0, "", "", Kit.at(3000.0 + (arms + 1) * spacing), 400.0, 20.0, "", "", "", "driving", 90, 90, listOf(out))
        s.angle = angle
        return s to e
    }

    @Test fun theNumberWhenItsArmsAreApart() {
        val (s, e) = ringStep(3, 2, 30.0)
        assertEquals(3, Ring.counted(s, e))
        assertEquals(3, Ring.number(s, e))
    }

    @Test fun armsTooCloseAreNoCount() {
        // a driveway beside a road: d3's arms are 6 m apart
        val (s, e) = ringStep(3, 2, 6.0)
        assertTrue(Ring.armsTooClose(s, e))
        assertEquals(0, Ring.number(s, e))
    }

    @Test fun theAnswerDisagreeingWithItsOwnArmsIsNoCount() {
        val (s, e) = ringStep(3, 3, 30.0)
        assertEquals(4, Ring.counted(s, e))
        assertEquals(0, Ring.number(s, e))
    }

    @Test fun aFirstExitStraightOnIsNoChoice() {
        val (s, e) = ringStep(1, 0, 30.0, angle = 10.0)
        assertEquals(1, Ring.counted(s, e))
        assertEquals(0, Ring.number(s, e))
        val (t, f) = ringStep(1, 0, 30.0, angle = 70.0)
        assertEquals(1, Ring.number(t, f))
    }

    @Test fun pastTheFourthNobodyCounts() {
        val (s, e) = ringStep(5, 4, 30.0)
        assertEquals(0, Ring.number(s, e))
    }

    @Test fun exitNumberWordsOnARoute() {
        val line = Kit.route().line
        val s = Kit.step(3000.0, "roundabout", "right", exit = 2, crossings = listOf(Kit.crossing(3000.0)))
        val e = Kit.step(3100.0, "exit roundabout", "right")
        val g = Guidance.build(Kit.route(s, e), true)
        // (a straight line: straight on, and exit 2 of a cross is straight on)
        assertEquals(line.length, g.route.line.length, 0.1)
        assertEquals(1, g.told.count { it.step.entersRing })
        val w = g.banner(1, 2000.0).action
        assertTrue(w, w.startsWith("عند الدوار"))
    }

    @Test fun d3sBadNumberIsNotSaid() {
        // OSM: one road leaves where the router says the third (26.39400,50.03734)
        val g = Fixtures.trip("d3").guidance(true)
        val ring = g.told.first { it.step.entersRing && Geo.distance(it.step.pos, LatLon(26.39400, 50.03734)) < 60 }
        assertEquals(3, ring.step.exit)
        assertEquals(0, ring.ringNumber)
        assertFalse(g.banner(g.told.indexOf(ring), 0.0).action.contains("الثالث"))
    }

    @Test fun k7sThreeRings() {
        // 4/3/2 said against 3/2/1 of OSM's road exits
        val g = Fixtures.trip("k7").guidance(true)
        val bad = osmRows().filter { it.trip == "k7" && it.said != it.main }
        assertEquals(3, bad.size)
        for (b in bad) {
            val ring = g.told.first { it.step.entersRing && Geo.distance(it.step.pos, b.at) < 60 }
            assertEquals("said ${b.said}, OSM ${b.main}", 0, ring.ringNumber)
        }
    }

    private class Row(val trip: String, val at: LatLon, val said: Int, val all: Int, val main: Int)

    private fun osmRows(): List<Row> = (MiniJson.parse(Fixtures.raw("rings.json")) as List<*>).map { r ->
        val at = r.list("at")
        Row(r.str("trip"), LatLon(at[0] as Double, at[1] as Double), r.num("said")!!.toInt(), r.num("all_exits")!!.toInt(), r.num("main_exits")!!.toInt())
    }

    private class Tally(var rings: Int = 0, var kept: Int = 0, var keptWrong: Int = 0, var droppedRight: Int = 0, var droppedWrong: Int = 0)

    private fun tally(answers: (String) -> GuideRoute?): Tally {
        val t = Tally()
        for (row in osmRows()) {
            val route = answers(row.trip) ?: continue
            val i = route.steps.indexOfFirst { it.entersRing && Geo.distance(it.pos, row.at) < 60 && it.exit == row.said }
            if (i < 0) continue
            t.rings++
            val n = Ring.number(route.steps[i], route.steps.getOrNull(i + 1))
            val right = row.said == row.main
            when {
                n > 0 && right -> t.kept++
                n > 0 -> { t.kept++; t.keptWrong++ }
                right -> t.droppedRight++
                else -> t.droppedWrong++
            }
        }
        return t
    }

    @Test fun againstOsmsOwnCountOnTheFixtures() {
        val ids = Fixtures.ids.toSet()
        val t = tally { if (it in ids) Fixtures.trip(it).route else null }
        println("rings on the fixtures: ${t.rings}, number kept ${t.kept} (wrong ${t.keptWrong}), number dropped: right ${t.droppedRight}, wrong ${t.droppedWrong}")
        assertTrue(t.rings >= 15)
        // (the one left: d7's first exit, which OSM calls a service road. Still the first arm the driver meets)
        assertTrue(t.keptWrong <= 1)
    }

    /** All 56 test trips, where this PC has them: the figure in docs/DEV.md. */
    @Test fun againstOsmsOwnCountOnAllTripsOnThisPc() {
        val dir = File("D:\\k2501-work\\mapbuild\\out\\route-tests\\var-req")
        org.junit.Assume.assumeTrue(dir.isDirectory)
        val cache = HashMap<String, GuideRoute?>()
        val t = tally { id -> cache.getOrPut(id) { File(dir, "$id.json").takeIf { it.exists() }?.let { GuideAnswer.parse(it.readText())?.routes?.get(0) } } }
        println("rings on all trips: ${t.rings}, number kept ${t.kept} (wrong ${t.keptWrong}), number dropped: right ${t.droppedRight}, wrong ${t.droppedWrong}")
        assertTrue(t.rings >= 55)
        assertTrue(t.keptWrong <= 2)
    }
}
