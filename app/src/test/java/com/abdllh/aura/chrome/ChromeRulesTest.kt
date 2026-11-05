package com.abdllh.aura.chrome

import com.abdllh.aura.home.Screens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeRulesTest {
    @Test fun sixTilesEvenlyInTheCapsule() {
        val x = (0 until 6).map { ChromeRules.tileX(it) }
        assertEquals(listOf(352, 471, 590, 710, 829, 948), x)
        for (i in 1 until x.size) assertTrue("pitch ${x[i] - x[i - 1]}", x[i] - x[i - 1] in 119..120)
        assertEquals("centred in x 292..1008", 650 * 2, x.first() + x.last())
    }

    @Test fun theSongCardOnMapsOnly() {
        assertTrue(ChromeRules.songCard(Screens.MAPS, true))
        assertFalse("no song", ChromeRules.songCard(Screens.MAPS, false))
        for (p in listOf(Screens.HOME, Screens.MUSIC, Screens.PHONE, Screens.APPS, Screens.SETTINGS)) assertFalse(p, ChromeRules.songCard(p, true))
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
        for (p in listOf(Screens.MUSIC, Screens.PHONE, Screens.APPS, Screens.SETTINGS)) {
            assertTrue(p, ChromeRules.stripSpeed(p, true, false, false))
            assertTrue("$p on a trip: the whole screen", ChromeRules.stripSpeed(p, true, true, false))
        }
    }
}
