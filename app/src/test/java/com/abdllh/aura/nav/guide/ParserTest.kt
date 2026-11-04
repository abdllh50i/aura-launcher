package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {
    @Test fun miniJson() {
        val j = MiniJson.parse("""{"a":1.5,"b":"x\nyA\"z","c":[true,false,null,-2e3],"d":{"e":[]}, "f" : "طريق"}""")
        assertEquals(1.5, j.num("a")!!, 0.0)
        assertEquals("x\nyA\"z", j.str("b"))
        assertEquals(listOf(true, false, null, -2000.0), j.list("c"))
        assertTrue(j.obj("d").list("e").isEmpty())
        assertEquals("طريق", j.str("f"))
    }

    @Test fun whatIsSkippedIsNotKept() {
        val j = MiniJson.parse("""{"a":{"weight":[1,{"x":"]"}],"b":2},"weight":"}"}""") { k, _ -> k == "weight" }
        assertEquals(2.0, j.obj("a").num("b")!!, 0.0)
        assertNull(j.obj("a")!!["weight"])
    }

    @Test(expected = Exception::class) fun badJsonThrows() { MiniJson.parse("""{"a":""") }

    @Test fun garbageIsNoAnswerAndNoCrash() {
        assertNull(GuideAnswer.parse(""))
        assertNull(GuideAnswer.parse("not json"))
        assertNull(GuideAnswer.parse("""{"a":"""))
        assertNull(GuideAnswer.parse("""[1,2,3]"""))
        assertNull(GuideAnswer.parse("""{"code":"NoSegment","message":"One of the supplied input coordinates could not snap to street segment."}"""))
        assertNull(GuideAnswer.parse("""{"code":"Ok","routes":[]}"""))
        assertNull(GuideAnswer.parse("""{"code":"Ok","routes":[{"legs":[]}]}"""))
        // odd values where numbers should be: read as nothing, not thrown
        assertNotNull(GuideAnswer.parse("""{"code":"Ok","routes":[{"distance":"x","legs":[{"steps":[{"maneuver":{"type":"arrive","location":["a"]},"name":5,"intersections":[{"bearings":["x"],"entry":[1]}]}]}]}]}"""))
    }

    @Test fun theRawAnswersReadWhole() {
        for (id in listOf("j1", "j4", "k1")) {
            val text = Fixtures.raw("raw/$id.json")
            val a = GuideAnswer.parse(text)
            assertNotNull(id, a)
            val r = a!!.routes[0]
            assertTrue(r.line.points.size > 10)
            assertEquals(r.distance, r.steps.sumOf { it.distance }, 5.0)
            // every step on the line, in order, where the answer's own location says
            var last = -1.0
            for (s in r.steps) {
                assertTrue(s.at >= last)
                last = s.at
                assertTrue(Geo.distance(r.line.at(s.at).first, s.pos) < 2.0)
            }
            assertEquals(r.distance, r.line.length, r.distance * 0.002)
            assertEquals("depart", r.steps.first().type)
            assertEquals("arrive", r.steps.last().type)
        }
    }

    @Test fun warning401IsKeptAndSaysNothing() {
        val k1 = GuideAnswer.parse(Fixtures.raw("raw/k1.json"))!!
        assertTrue(k1.relaxed)
        assertEquals(listOf(401), k1.warnings)
        assertFalse(GuideAnswer.parse(Fixtures.raw("raw/j1.json"))!!.relaxed)
    }

    @Test fun theTrimmedAnswersReadLikeTheRawOnes() {
        // the same trip through both
        val raw = GuideAnswer.parse(Fixtures.raw("raw/j4.json"))!!.routes[0]
        val cut = Fixtures.trip("j4").route
        assertEquals(raw.steps.size, cut.steps.size)
        for (i in raw.steps.indices) {
            assertEquals(raw.steps[i].type, cut.steps[i].type)
            assertEquals(raw.steps[i].at, cut.steps[i].at, 0.01)
            assertEquals(raw.steps[i].silent, cut.steps[i].silent)
            assertEquals(raw.steps[i].angle, cut.steps[i].angle, 0.01)
            assertEquals(raw.steps[i].name, cut.steps[i].name)
        }
        val a = Guidance.build(raw, true)
        val b = Guidance.build(cut, true)
        assertEquals(a.told.map { Speech.text(a.spoken(a.told.indexOf(it), it.step.at - 300), true) }, b.told.map { Speech.text(b.spoken(b.told.indexOf(it), it.step.at - 300), true) })
    }

    @Test fun modesAndNames() {
        val f = Fixtures.trip("ferry").route
        assertTrue(f.hasFerry)
        val s = f.steps.first { it.onFerry }
        assertEquals("notification", s.type)
        assertFalse(s.silent)
        assertFalse(f.steps[f.steps.indexOf(s) + 1].silent)
    }

    @Test fun aRoundaboutsWayRound() {
        val r = Fixtures.trip("d6").route
        val s = r.steps.first { it.entersRing }
        val next = r.steps[r.steps.indexOf(s) + 1]
        assertTrue(next.silent)
        assertEquals(next.at - s.at, s.span, 0.1)
        assertFalse(s.angle.isNaN())
    }

    @Test fun factsFromThisLineOnly() {
        val line = Kit.line
        val good = """{"edges":[{"begin_shape_index":0,"end_shape_index":100,"speed_limit":120,"road_class":"motorway","use":"road","bridge":false,"tunnel":false,"toll":true,"names":["x"]},{"begin_shape_index":100,"end_shape_index":200,"road_class":"primary","use":"ramp","bridge":true,"tunnel":false,"toll":false,"end_node":{"type":"x"}}]}"""
        val f = RoadFacts.parse(good, line)
        assertNotNull(f)
        assertEquals(2, f!!.stretches.size)
        assertEquals(120, f.at(100.0)!!.limit)
        assertEquals("motorway", f.at(100.0)!!.roadClass)
        assertTrue(f.at(100.0)!!.toll)
        assertEquals(0, f.at(15_000.0)!!.limit)
        assertTrue(f.at(15_000.0)!!.bridge)
        // past the end, the last; ahead of its start, none
        assertEquals("primary", f.at(line.length + 5)!!.roadClass)
        // (another shape: the last index does not meet this line's end)
        assertNull(RoadFacts.parse(good.replace("\"end_shape_index\":200", "\"end_shape_index\":180"), line))
        assertNull(RoadFacts.parse("""{"edges":[]}""", line))
        assertNull(RoadFacts.parse("nonsense", line))
    }

    /** What it costs here, for the docs: the largest reference answer. Not the unit: an A53 is many times slower. */
    @Test fun howLongOnThisPc() {
        val f = java.io.File("D:\\k2501-work\\mapbuild\\out\\ref-routes\\dammam-jeddah.json")
        org.junit.Assume.assumeTrue(f.exists())
        val text = f.readText()
        repeat(5) { GuideAnswer.parse(text) } // (warm up)
        val t0 = System.nanoTime()
        val a = GuideAnswer.parse(text)!!
        val t1 = System.nanoTime()
        val g = Guidance.build(a.routes[0], true, Fixtures.trip("dammam-jeddah").world)
        val t2 = System.nanoTime()
        println("dammam-jeddah ${text.length} chars: parse ${(t1 - t0) / 1_000_000} ms, build ${(t2 - t1) / 1_000_000} ms, ${g.told.size} told, ${g.spots.size} spots")
    }

    @Test fun zonesContain() {
        val z = ZoneRow(1, ZoneRow.MILITARY, null, null, GeoBox(0.0, 0.0, 1.0, 1.0), listOf(com.abdllh.aura.nav.LatLon(0.0, 0.0), com.abdllh.aura.nav.LatLon(0.0, 1.0), com.abdllh.aura.nav.LatLon(1.0, 1.0), com.abdllh.aura.nav.LatLon(1.0, 0.0)))
        assertTrue(z.contains(com.abdllh.aura.nav.LatLon(0.5, 0.5)))
        assertFalse(z.contains(com.abdllh.aura.nav.LatLon(1.5, 0.5)))
        assertTrue(z.holds(com.abdllh.aura.nav.LatLon(0.5, 0.5)))
        // inside, but on its edge: a wedge of a simplified ring
        assertTrue(z.contains(com.abdllh.aura.nav.LatLon(0.0005, 0.5)))
        assertFalse(z.holds(com.abdllh.aura.nav.LatLon(0.0005, 0.5)))
    }
}
