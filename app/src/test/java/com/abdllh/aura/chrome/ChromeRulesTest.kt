package com.abdllh.aura.chrome

import com.abdllh.aura.home.Screens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeRulesTest {
    @Test fun theMiniOnEveryScreenWithASong() {
        for (p in listOf(Screens.HOME, Screens.MAPS, Screens.PHONE, Screens.APPS, Screens.SETTINGS)) {
            assertTrue(p, ChromeRules.mini(p, song = true))
            assertFalse("$p, no song", ChromeRules.mini(p, song = false))
        }
    }

    @Test fun notOnTheMusicPanel() {
        assertFalse("the song is the whole screen", ChromeRules.mini(Screens.MUSIC, song = true))
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

    @Test fun coverageOfAGlyphByThePlate() {
        assertEquals(1f, ChromeRules.coverage(408f, 408f), 0f)
        assertEquals(1f, ChromeRules.coverage(408f, 421f), 0f) // (the glyph is still inside the plate)
        assertEquals(0f, ChromeRules.coverage(408f, 488f), 0f) // (the next cell)
        assertEquals(0f, ChromeRules.coverage(408f, 328f), 0f)
        assertEquals(0.5f, ChromeRules.coverage(408f, 440f), 0f)
    }
}
