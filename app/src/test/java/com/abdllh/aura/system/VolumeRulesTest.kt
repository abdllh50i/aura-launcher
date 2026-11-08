package com.abdllh.aura.system

import com.abdllh.aura.system.VolumeRules.Act
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 0..50 scale's steps and the wheel's guard (VolumeRules), as the firmware's events come on his unit: max 40, 10 steps of boost. */
class VolumeRulesTest {
    private fun rules(fw: Int, boost: Int = 0) = VolumeRules(40).also { it.start(fw, boost) }
    private fun List<Act>.boosts() = filterIsInstance<Act.Boost>().map { it.steps }
    private fun List<Act>.sets() = filterIsInstance<Act.Firmware>().map { it.value }

    @Test fun scaleIsFiftyAtTheTop() {
        val r = rules(40, 10)
        assertEquals(50, r.maxLevel())
        assertEquals(50, r.level())
        assertEquals(22, rules(22).level())
    }

    @Test fun aSavedBoostCountsOnlyAtTheTop() {
        assertEquals(0, rules(30, 6).boost)
        assertEquals(6, rules(40, 6).boost)
    }

    @Test fun wheelUpAtTheTopRaisesTheBoost() {
        val r = rules(40)
        assertEquals(listOf(1), r.key(+1, false, 0).boosts())
        assertTrue(r.firmware(40, 40).isEmpty()) // the firmware stays at its top
        assertEquals(41, r.level())
    }

    @Test fun wheelUpBelowTheTopIsTheFirmwares() {
        val r = rules(39)
        assertTrue(r.key(+1, false, 0).isEmpty())
        assertTrue(r.firmware(40, 40).isEmpty())
        assertEquals(40, r.level())
        assertEquals(listOf(1), r.key(+1, false, 300).boosts())
        assertEquals(41, r.level())
    }

    @Test fun heldUpWalksThroughTheTop() {
        val r = rules(38)
        val levels = ArrayList<Int>()
        var t = 0L
        repeat(6) {
            r.key(+1, false, t)
            if (r.fw < 40) r.firmware(r.fw + 1, t + 40)
            levels += r.level()
            t += 250
        }
        assertEquals(listOf(39, 40, 41, 42, 43, 44), levels)
    }

    @Test fun theFirmwaresReportBeforeTheKeyIsThatKeys() {
        val r = rules(39)
        assertTrue(r.firmware(40, 0).any { it is Act.Recheck })
        assertTrue(r.key(+1, false, 60).boosts().isEmpty()) // the key that took 39 to 40, not one more step
        assertEquals(40, r.level())
        assertTrue(r.recheck(400).isEmpty())
    }

    @Test fun mutedUpAtTheTopOnlyUnmutes() {
        val r = rules(40)
        assertTrue(r.key(+1, true, 0).boosts().isEmpty())
    }

    @Test fun upStopsAtFifty() {
        val r = rules(40, 10)
        assertTrue(r.key(+1, false, 0).boosts().isEmpty())
        assertEquals(50, r.level())
    }

    @Test fun wheelDownWhileBoostedPutsTheFirmwareBack() {
        val r = rules(40, 5)
        assertEquals(listOf(4), r.key(-1, false, 0).boosts())
        assertEquals(listOf(40), r.firmware(39, 40).sets()) // the firmware's own step down, put back at once
        assertTrue(r.firmware(40, 80).isEmpty()) // its echo
        assertEquals(44, r.level())
    }

    @Test fun wheelDownWhileBoostedWhenTheReportCameFirst() {
        val r = rules(40, 5)
        r.firmware(39, 0)
        val acts = r.key(-1, false, 120)
        assertEquals(listOf(4), acts.boosts())
        assertEquals(listOf(40), acts.sets())
        assertEquals(44, r.level())
        assertTrue(r.recheck(500).isEmpty()) // claimed: the boost stays
        assertEquals(4, r.boost)
    }

    @Test fun heldDownWalksFromFiftyIntoTheFirmware() {
        val r = rules(40, 10)
        val levels = ArrayList<Int>()
        var t = 0L
        repeat(12) {
            r.key(-1, false, t)
            r.firmware(r.fw - 1, t + 40) // the firmware always takes its step down
            levels += r.level()
            t += 250
        }
        assertEquals(listOf(49, 48, 47, 46, 45, 44, 43, 42, 41, 40, 39, 38), levels)
    }

    @Test fun upThatLandsLowerIsCorrected() {
        val r = rules(30)
        r.key(+1, false, 0)
        val acts = r.firmware(23, 60)
        assertEquals(listOf(31), acts.sets())
        assertTrue(acts.any { it is Act.Note && it.kind == "glitch" })
        assertTrue(r.firmware(31, 100).isEmpty()) // AMRI's own, echoed: not news
        assertEquals(31, r.level())
    }

    @Test fun downThatLandsHigherIsCorrected() {
        val r = rules(30)
        r.key(-1, false, 0)
        assertEquals(listOf(29), r.firmware(36, 60).sets())
    }

    @Test fun aGlitchReportedBeforeItsKeyIsCorrected() {
        val r = rules(30)
        r.firmware(23, 0)
        assertEquals(listOf(31), r.key(+1, false, 90).sets())
    }

    @Test fun aGlitchAfterTheStepIsCorrectedToOneAboveTheStart() {
        val r = rules(30)
        r.key(+1, false, 0)
        assertTrue(r.firmware(31, 40).isEmpty())
        assertEquals(listOf(31), r.firmware(24, 200).sets())
    }

    @Test fun aGlitchAtTheTopGoesBackToTheTop() {
        val r = rules(40, 2)
        assertEquals(listOf(3), r.key(+1, false, 0).boosts())
        assertEquals(listOf(40), r.firmware(33, 50).sets())
        assertEquals(43, r.level())
    }

    @Test fun onceAKeyTheFirmwareIsNotFought() {
        val r = rules(30)
        r.key(+1, false, 0)
        assertEquals(listOf(31), r.firmware(23, 50).sets())
        val again = r.firmware(23, 120) // the firmware says 23 again: left, no second set
        assertTrue(again.sets().isEmpty())
        assertTrue(again.any { it is Act.Note })
    }

    @Test fun theBrakeStopsTheGuardAfterFour() {
        val r = rules(30)
        var t = 0L
        repeat(4) {
            r.key(+1, false, t)
            assertEquals(1, r.firmware(r.fw - 7, t + 50).sets().size)
            t += 1000
        }
        r.key(+1, false, t)
        val fifth = r.firmware(r.fw - 7, t + 50)
        assertTrue(fifth.sets().isEmpty())
        assertTrue(fifth.any { it is Act.Note && it.text.contains("paused") })
        // paused for a minute, then on again
        t += 61_000
        r.key(+1, false, t)
        assertEquals(1, r.firmware(r.fw - 7, t + 50).sets().size)
    }

    @Test fun aLowerValueLongAfterTheKeyIsNotTheKeys() {
        val r = rules(30)
        r.key(+1, false, 0)
        r.firmware(31, 40)
        assertTrue(r.firmware(20, 900).sets().isEmpty()) // past the 700 ms: somebody else's change
        assertEquals(20, r.level())
    }

    @Test fun leavingTheTopByItselfReleasesTheBoost() {
        val r = rules(40, 3)
        assertTrue(r.firmware(25, 0).any { it is Act.Recheck })
        assertTrue(r.recheck(100).isEmpty()) // a key may still come
        assertEquals(listOf(0), r.recheck(330).boosts())
        assertEquals(25, r.level())
    }

    @Test fun amrisOwnLevel() {
        val r = rules(30)
        val up = r.setLevel(45, 0)
        assertEquals(listOf(40), up.sets())
        assertEquals(listOf(5), up.boosts())
        assertEquals(45, r.level())
        val down = r.setLevel(38, 100)
        assertEquals(listOf(0), down.boosts())
        assertEquals(listOf(38), down.sets())
        assertEquals(38, r.level())
        r.setLevel(99, 200)
        assertEquals(50, r.level())
        assertTrue(r.firmware(40, 250).isEmpty())
    }

    // ---- the heat protection (ACTION_HIGHTEMP_TRIGGER sets 30 on his unit): never fought

    @Test fun theHeatBroadcastReleasesTheBoost() {
        val r = rules(40, 5)
        assertEquals(listOf(0), r.heat(30, 0).boosts())
        assertTrue(r.firmware(30, 60).sets().isEmpty())
        assertEquals(30, r.level())
    }

    @Test fun aWheelUpThatLandsOnTheHeatCapIsLeft() {
        val r = rules(32)
        r.key(+1, false, 0)
        assertTrue(r.firmware(30, 60).sets().isEmpty()) // its report before its broadcast: still not fought
        assertEquals(30, r.level())
    }

    @Test fun whileHotTheGuardLeavesWheelUpDrops() {
        val r = rules(35)
        r.heat(30, 0)
        r.firmware(30, 50)
        r.key(+1, false, 1000)
        assertTrue(r.firmware(31, 1040).isEmpty())
        r.key(+1, false, 2000)
        assertTrue(r.firmware(25, 2050).sets().isEmpty())
        r.key(+1, false, VolumeRules.HEAT_MS + 3000) // later, no longer hot: guarded again
        assertEquals(listOf(26), r.firmware(22, VolumeRules.HEAT_MS + 3050).sets())
    }

    @Test fun aGlitchOffTheCapIsStillCorrected() {
        val r = rules(32)
        r.key(+1, false, 0)
        assertEquals(listOf(33), r.firmware(27, 60).sets())
    }

    @Test fun wheelDownWhileBoostedThenTheHeatDropReleasesTheBoostInsteadOfFortyAgain() {
        val r = rules(40, 3)
        assertEquals(listOf(2), r.key(-1, false, 0).boosts())
        val acts = r.firmware(30, 50)
        assertTrue(acts.sets().isEmpty())
        assertEquals(listOf(0), acts.boosts())
        assertEquals(30, r.level())
    }

    @Test fun aDropLeftUnderTheTopTakesTheBoostWithIt() {
        val r = rules(40, 2)
        assertEquals(listOf(3), r.key(+1, false, 0).boosts())
        assertEquals(listOf(40), r.firmware(33, 50).sets())
        val again = r.firmware(33, 120) // the same drop again in the key's window: left, and the boost goes with it
        assertTrue(again.sets().isEmpty())
        assertEquals(listOf(0), again.boosts())
        assertEquals(33, r.level())
    }

    @Test fun rowPlusAtFortyIsFortyOneAndMinusBackReleases() {
        val r = rules(40)
        assertEquals(listOf(1), r.setLevel(r.level() + 1, 0).boosts())
        assertEquals(41, r.level())
        assertEquals(listOf(0), r.setLevel(r.level() - 1, 200).boosts())
        assertEquals(40, r.level())
    }
}
