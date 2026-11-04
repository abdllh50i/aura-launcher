package com.abdllh.aura.nav.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignsTest {
    @Test fun whatIsNotRealIsNothing() {
        // (all real values of `destinations`)
        assertNull(Signs.read("", "Exit", true))
        assertNull(Signs.read("", "Exit to", true))
        assertNull(Signs.read("", "", true))
    }

    @Test fun exitNumbers() {
        assertEquals("8", Signs.read("8", "مخرج 8", true)!!.number)
        assertEquals("11", Signs.read("", "مخرج 11, Exit", true)!!.number)
        assertEquals("11", Signs.read("", "مخرج ١١", true)!!.number)
        assertEquals("880", Signs.read("880", "Exit to Riyadh", true)!!.number)
        assertEquals("3B", Signs.read("3B", "", true)!!.number)
    }

    @Test fun placesAndRoads() {
        assertEquals(listOf("الرياض"), Signs.read("880", "Exit to Riyadh", true)!!.toward)
        assertEquals(listOf("Riyadh"), Signs.read("", "Riyadh", false)!!.toward)
        assertEquals(listOf("طريق 95"), Signs.read("", "95", true)!!.toward)
        assertEquals(listOf("Route 95"), Signs.read("", "95", false)!!.toward)
        assertEquals(listOf("جسر الملك فهد"), Signs.read("", "جسر الملك فهد", true)!!.toward)
    }

    @Test fun theOtherScriptStaysOutOfTheSentence() {
        // "باتجاه King Faisal Street" reads wrong: the route tester's last item
        val s = Signs.read("", "King Faisal Street", true)!!
        assertTrue(s.toward.isEmpty())
        assertEquals(listOf("King Faisal Street"), s.other)
        val e = Signs.read("", "شارع الأمير محمد بن عبدالعزيز", false)!!
        assertTrue(e.toward.isEmpty())
        assertEquals(1, e.other.size)
    }

    @Test fun aTownWeKnowIsTranslatedTheOtherIsNot() {
        val s = Signs.read("", "Jeddah, Quadimah, Thuwal", true)!!
        assertEquals(listOf("جدة", "ثول"), s.toward)
        assertEquals(listOf("Quadimah"), s.other)
    }

    @Test fun osrmsFormToo() {
        val s = Signs.read("", "40: Dammam, Jubail", true)!!
        assertEquals(listOf("طريق 40", "الدمام"), s.toward) // (at most two, as today's sign)
        assertEquals("الدمام", s.place)
    }

    @Test fun whatTheRoadAlreadySaysIsNotRepeated() {
        val road = Names.pick("طريق الملك فهد", "605", true)
        val s = Signs.read("", "605", true)!!.without(road)
        assertTrue(s.toward.isEmpty())
    }

    @Test fun theArmsOfAJunction() {
        // real: d1's third turn, bearings 81, 173, 353, coming by 173 and leaving by 81
        val c = Crossing(intArrayOf(81, 173, 353), booleanArrayOf(true, false, true), 1, 0, Kit.at(0.0), 0, emptyList(), emptyList(), false)
        val s = GuideStep("turn", "right", 0, "", "", Kit.at(0.0), 100.0, 10.0, "", "", "", "driving", 353, 81, listOf(c))
        val v = Junctions.of(s, null)!!
        assertEquals(3, v.arms.size)
        assertEquals(88, v.arms.first { it.role == Arm.Role.WAY_OUT }.angle)
        assertEquals(180, kotlin.math.abs(v.arms.first { it.role == Arm.Role.WAY_IN }.angle))
        assertEquals(0, v.arms.first { it.role == Arm.Role.OPEN }.angle)
    }

    @Test fun noChoiceNoDrawing() {
        val c = Crossing(intArrayOf(10, 190), booleanArrayOf(true, false), 1, 0, Kit.at(0.0), 0, emptyList(), emptyList(), false)
        val s = GuideStep("turn", "right", 0, "", "", Kit.at(0.0), 100.0, 10.0, "", "", "", "driving", 0, 0, listOf(c))
        assertNull(Junctions.of(s, null))
        // the start of a trip has no way in
        val start = Crossing(intArrayOf(10), booleanArrayOf(true), -1, 0, Kit.at(0.0), 0, emptyList(), emptyList(), false)
        assertNull(Junctions.of(GuideStep("depart", "", 0, "", "", Kit.at(0.0), 100.0, 10.0, "", "", "", "driving", 0, 0, listOf(start)), null))
    }

    @Test fun aOneWayArmBesideAnotherIsOneRoad() {
        val c = Crossing(intArrayOf(0, 5, 90, 180), booleanArrayOf(true, false, true, false), 3, 2, Kit.at(0.0), 0, emptyList(), emptyList(), false)
        val s = GuideStep("turn", "right", 0, "", "", Kit.at(0.0), 100.0, 10.0, "", "", "", "driving", 0, 90, listOf(c))
        assertEquals(3, Junctions.of(s, null)!!.arms.size)
    }

    @Test fun theRealRingsDrawTheirArms() {
        val g = Fixtures.trip("d3").guidance(true)
        val i = g.told.indexOfFirst { it.step.entersRing }
        val v = g.junction(i)
        assertNotNull(v)
        // the router's third: two ways off on the way round, and the way out
        assertEquals(3, v!!.ringArms.size)
        assertEquals(Arm.Role.WAY_OUT, v.ringArms.last().role)
    }

    @Test fun lanesOnlyWhenTheyMatter() {
        fun lanes(vararg valid: Boolean) = valid.map { com.abdllh.aura.nav.Lane(listOf("straight"), it, "") }
        fun step(l: List<com.abdllh.aura.nav.Lane>) = GuideStep("fork", "slight right", 0, "", "", Kit.at(0.0), 100.0, 10.0, "", "", "", "driving", 0, 0,
            listOf(Crossing(intArrayOf(0, 180), booleanArrayOf(true, false), 1, 0, Kit.at(0.0), 0, emptyList(), l, false)))
        assertEquals(3, GuideAnswer.lanesOf(step(lanes(true, true, false))).size)
        assertTrue(GuideAnswer.lanesOf(step(lanes(true, true))).isEmpty()) // all lead the way: nothing to keep to
        assertTrue(GuideAnswer.lanesOf(step(lanes(false, false))).isEmpty()) // none: the data contradicts itself
        assertTrue(GuideAnswer.lanesOf(step(lanes(true))).isEmpty())
    }
}
