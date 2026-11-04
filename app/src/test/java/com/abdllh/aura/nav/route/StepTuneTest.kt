package com.abdllh.aura.nav.route

import org.junit.Assert.assertEquals
import org.junit.Test

class StepTuneTest {
    private val all = "613; طريق الظهران الجبيل السريع; Dhahran Jubail Expressway"

    @Test
    fun pickByScript() {
        assertEquals("طريق الظهران الجبيل السريع", RoadName.pick(all, "", true))
        assertEquals("Dhahran Jubail Expressway", RoadName.pick(all, "", false))
    }

    @Test
    fun theOtherScriptWhenThatIsAll() {
        assertEquals("King Fahd Road", RoadName.pick("King Fahd Road", "", true))
        assertEquals("طريق الملك فهد", RoadName.pick("طريق الملك فهد", "", false))
    }

    @Test
    fun thenTheNumber() {
        assertEquals("40", RoadName.pick("", "40; 605", true))
        assertEquals("605", RoadName.pick("605", "", true))
        assertEquals("", RoadName.pick("", "", true))
        // a word always before a number
        assertEquals("طريق 6", RoadName.pick("613; طريق 6", "40", true))
    }

    @Test
    fun aRefThatIsNoNumber() {
        assertEquals("", RoadName.ref("King Abdul Aziz street, Jubail, saudi arabia"))
        assertEquals("", RoadName.pick("", "King Abdul Aziz street, Jubail, saudi arabia", true))
        assertEquals("م 80", RoadName.ref("م 80"))
        assertEquals("M5", RoadName.ref("M5;40"))
    }

    @Test
    fun scaleBandsOfTheTable() {
        assertEquals(1.27, EtaScale.factor(140.0), 0.0)
        assertEquals(1.27, EtaScale.factor(110.0), 0.0)
        assertEquals(1.127, EtaScale.factor(109.9), 0.0)
        assertEquals(1.127, EtaScale.factor(85.0), 0.0)
        assertEquals(1.178, EtaScale.factor(60.0), 0.0)
        assertEquals(1.038, EtaScale.factor(35.0), 0.0)
        assertEquals(1.27, EtaScale.factor(34.9), 0.0)
        assertEquals(1.27, EtaScale.factor(0.0), 0.0)
    }

    @Test
    fun aStepByItsOwnSpeed() {
        // 1,000 m in 30 s is 120 km/h
        assertEquals(30 * 1.27, EtaScale.seconds(1000.0, 30.0), 1e-9)
        // a step with no time stays so (an arrival)
        assertEquals(0.0, EtaScale.seconds(0.0, 0.0), 0.0)
        assertEquals(0.0, EtaScale.seconds(100.0, 0.0), 0.0)
    }

    @Test
    fun orderFastestFirstWithinReason() {
        // the tests' case: an alternate shorter and faster
        assertEquals(listOf(1, 0), RouteOrder.of(doubleArrayOf(33400.0, 32400.0), doubleArrayOf(1572.0, 1494.0)))
        // Riyadh-Salwa: 50 km longer for 1 % less time stays behind
        assertEquals(listOf(0, 1), RouteOrder.of(doubleArrayOf(473820.0, 523430.0), doubleArrayOf(19398.0, 19236.0)))
        // exactly as Valhalla said when it is the fastest
        assertEquals(listOf(0, 2, 1), RouteOrder.of(doubleArrayOf(100.0, 100.0, 100.0), doubleArrayOf(10.0, 30.0, 20.0)))
        assertEquals(listOf(0), RouteOrder.of(doubleArrayOf(100.0), doubleArrayOf(10.0)))
    }
}
