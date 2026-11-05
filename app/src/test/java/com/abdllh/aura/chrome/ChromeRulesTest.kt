package com.abdllh.aura.chrome

import com.abdllh.aura.chrome.ChromeRules.Glance
import com.abdllh.aura.home.Screens
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeRulesTest {
    private val nearest = listOf(Screens.HOME, Screens.MAPS, Screens.MUSIC, Screens.PHONE, Screens.APPS, Screens.SETTINGS)
    private val spec = listOf(Screens.SETTINGS, Screens.APPS, Screens.PHONE, Screens.MUSIC, Screens.MAPS, Screens.HOME)

    @Test fun glanceByTheTable() {
        for (p in listOf(Screens.HOME, Screens.MAPS, Screens.MUSIC)) assertEquals(p, Glance.PRAYER, ChromeRules.glance(p, false, true))
        for (p in listOf(Screens.APPS, Screens.SETTINGS, Screens.PHONE)) {
            assertEquals(p, Glance.MINI, ChromeRules.glance(p, false, true))
            assertEquals("no song", Glance.PRAYER, ChromeRules.glance(p, false, false))
            assertEquals("on a trip the column has the mini", Glance.PRAYER, ChromeRules.glance(p, true, true))
        }
    }

    @Test fun prayerQuietOnlyMovingAndFarOff() {
        assertFalse(ChromeRules.prayerQuiet(false, 300))
        assertFalse(ChromeRules.prayerQuiet(true, 45))
        assertTrue(ChromeRules.prayerQuiet(true, 46))
    }

    @Test fun stripSpeedByTheTable() {
        for (p in listOf(Screens.HOME, Screens.MAPS, Screens.MUSIC, Screens.PHONE, Screens.APPS, Screens.SETTINGS)) {
            assertFalse(p, ChromeRules.stripSpeed(p, false, false, false))
            assertFalse(p, ChromeRules.stripSpeed(p, false, true, true))
        }
        assertTrue("Home on a trip", ChromeRules.stripSpeed(Screens.HOME, true, true, false))
        assertFalse("Home with no trip: the search's slot", ChromeRules.stripSpeed(Screens.HOME, true, false, false))
        assertFalse("Maps: its card", ChromeRules.stripSpeed(Screens.MAPS, true, true, false))
        assertTrue("Maps: a search or a place card over it", ChromeRules.stripSpeed(Screens.MAPS, true, false, true))
        assertFalse("Music: its card", ChromeRules.stripSpeed(Screens.MUSIC, true, false, false))
        for (p in listOf(Screens.PHONE, Screens.APPS, Screens.SETTINGS)) {
            assertTrue(p, ChromeRules.stripSpeed(p, true, false, false))
            assertFalse("$p beside the column", ChromeRules.stripSpeed(p, true, true, false))
        }
    }

    @Test fun theRowShiftsByACellOnlyInTheOrderThatNeedsIt() {
        assertArrayEquals(intArrayOf(0, 72, 72, 72, 72, 72), ChromeRules.shifts(nearest))
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0, 0), ChromeRules.shifts(spec))
        // (the home cell anywhere else: those before it stay, those after it move)
        assertArrayEquals(intArrayOf(0, 0, 0, 72, 72, 72), ChromeRules.shifts(listOf(Screens.MAPS, Screens.MUSIC, Screens.HOME, Screens.PHONE, Screens.APPS, Screens.SETTINGS)))
    }

    @Test fun coverageOfAGlyphByThePlate() {
        assertEquals(1f, ChromeRules.coverage(406f, 406f), 0f)
        assertEquals(1f, ChromeRules.coverage(406f, 419f), 0f) // (the glyph is still inside the plate)
        assertEquals(0f, ChromeRules.coverage(406f, 478f), 0f) // (the next cell)
        assertEquals(0f, ChromeRules.coverage(406f, 334f), 0f)
        assertEquals(0.5f, ChromeRules.coverage(406f, 438f), 0f)
    }

    @Test fun theCapsuleSaysMinutesThenHours() {
        assertTrue(ChromeRules.leftInMinutes(38))
        assertTrue(ChromeRules.leftInMinutes(99))
        assertFalse(ChromeRules.leftInMinutes(100))
        assertEquals(2, ChromeRules.leftHours(100))
        assertEquals(9, ChromeRules.leftHours(540))
        assertEquals(9, ChromeRules.leftHours(530))
    }
}
