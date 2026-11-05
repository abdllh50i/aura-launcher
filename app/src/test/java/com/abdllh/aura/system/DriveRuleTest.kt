package com.abdllh.aura.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC.md section 6.0's table, row by row, and what its rows leave to each other. */
class DriveRuleTest {
    private val r = DriveRule()

    @Test fun coldBootIsParked() {
        assertFalse(r.moving)
        assertFalse(r.step(null, null, 0))
    }

    @Test fun eightForTwoSecondsIsMoving() {
        assertFalse(r.step(8f, null, 0))
        assertFalse(r.step(30f, null, 1000))
        assertFalse(r.step(30f, null, 1999))
        assertTrue(r.step(30f, null, 2000))
    }

    @Test fun aJumpOfTheGpsIsNotMoving() {
        assertFalse(r.step(40f, null, 0))
        assertFalse(r.step(0f, null, 1000)) // (the 2 s start again)
        assertFalse(r.step(40f, null, 2000))
        assertFalse(r.step(40f, null, 3999))
        assertTrue(r.step(40f, null, 4000))
    }

    @Test fun underFiveForFiveSecondsIsParked() {
        moving()
        assertTrue(r.step(4.9f, null, 10_000))
        assertTrue(r.step(0f, null, 14_999))
        assertFalse(r.step(0f, null, 15_000))
    }

    @Test fun betweenTheTwoSpeedsItKeepsWhatItWas() {
        moving()
        for (t in 0..40) assertTrue(r.step(6f, null, 10_000L + t * 1000))
        val p = DriveRule()
        for (t in 0..40) assertFalse(p.step(7.9f, null, t * 1000L))
    }

    @Test fun betweenTheSpeedsStartsTheTimersAgain() {
        moving()
        assertTrue(r.step(3f, null, 10_000))
        assertTrue(r.step(6f, null, 14_000)) // (a crawl: the 5 s are over)
        assertTrue(r.step(3f, null, 15_000))
        assertTrue(r.step(3f, null, 19_999))
        assertFalse(r.step(3f, null, 20_000))
        assertFalse(r.step(9f, null, 21_000))
        assertFalse(r.step(6f, null, 22_000)) // (likewise on the way up)
        assertFalse(r.step(9f, null, 23_000))
        assertFalse(r.step(9f, null, 24_999))
        assertTrue(r.step(9f, null, 25_000))
    }

    @Test fun gearDOrRKnownIsMovingAtOnceAtAnySpeed() {
        assertTrue(DriveRule().step(0f, Gear.Pos.D, 0))
        assertTrue(DriveRule().step(0f, Gear.Pos.R, 0))
        assertTrue(DriveRule().step(null, Gear.Pos.R, 0))
    }

    @Test fun noSpeedGearDIsMoving() {
        assertTrue(r.step(null, Gear.Pos.D, 0))
    }

    @Test fun noSpeedAndNoGearIsParkedWhateverItWas() {
        moving()
        assertFalse(r.step(null, null, 10_000))
    }

    @Test fun noSpeedGearPOrNIsParked() {
        assertFalse(r.step(null, Gear.Pos.P, 0))
        assertTrue(r.step(null, Gear.Pos.D, 1000))
        assertFalse(r.step(null, Gear.Pos.N, 2000))
    }

    @Test fun aRedLightWithGearDStaysMovingAndParkLetsGo() {
        moving()
        assertTrue(r.step(0f, Gear.Pos.D, 10_000))
        assertTrue(r.step(0f, Gear.Pos.D, 60_000))
        // the 5 s under the speed are long over: P known, and it is Parked at once
        assertFalse(r.step(0f, Gear.Pos.P, 60_001))
    }

    @Test fun gearPKnownDoesNotHoldBackASpeedThatIsReal() {
        assertFalse(r.step(30f, Gear.Pos.P, 0))
        assertTrue(r.step(30f, Gear.Pos.P, 2000))
    }

    @Test fun anAssumedGearIsNoGear() {
        // (the caller gives null for it: Gear.known; this is the red light with gear unknown of the table's third column)
        moving()
        assertTrue(r.step(0f, null, 10_000))
        assertFalse(r.step(0f, null, 15_000))
    }

    @Test fun whenItWantsToBeAskedAgain() {
        assertEquals(DriveRule.NEVER, r.wakeAt())
        r.step(20f, null, 100)
        assertEquals(2100L, r.wakeAt())
        r.step(20f, null, 2100)
        assertEquals(DriveRule.NEVER, r.wakeAt())
        r.step(0f, null, 3000)
        assertEquals(8000L, r.wakeAt())
        r.step(0f, null, 8000)
        assertEquals(DriveRule.NEVER, r.wakeAt())
    }

    private fun moving() {
        r.step(30f, null, 0)
        assertTrue(r.step(30f, null, 2000))
    }
}
