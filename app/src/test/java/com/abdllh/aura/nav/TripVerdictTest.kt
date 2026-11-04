package com.abdllh.aura.nav

import com.abdllh.aura.nav.TripRule.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class TripVerdictTest {
    private val min = 60_000L

    private fun sameBoot(gapMs: Long, toDest: Double = 5000.0, fromKept: Double = 100.0) =
        TripRule.verdict(true, keptElapsed = 1_000_000L, keptAt = 5_000_000L, nowElapsed = 1_000_000L + gapMs, fixUtc = 0L, toDest = toDest, fromKept = fromKept)

    private fun afterPowerOff(gapMs: Long, toDest: Double = 5000.0, fromKept: Double = 100.0) =
        TripRule.verdict(false, keptElapsed = 900_000_000L, keptAt = 5_000_000L, nowElapsed = 40_000L, fixUtc = 5_000_000L + gapMs, toDest = toDest, fromKept = fromKept)

    @Test fun aRestartOnTheWayResumes() {
        assertEquals(Verdict.RESUME, sameBoot(5_000L))
        assertEquals(Verdict.RESUME, sameBoot(29 * min))
    }

    @Test fun aFuelStopResumes() {
        assertEquals(Verdict.RESUME, afterPowerOff(12 * min))
    }

    @Test fun anOldTripDoesNot() {
        assertEquals(Verdict.TOO_OLD, sameBoot(31 * min))
        assertEquals(Verdict.TOO_OLD, afterPowerOff(9 * 60 * min)) // yesterday's drive to work, this morning
    }

    @Test fun onlyTheMonotonicClockCountsInTheSameBoot() {
        // a wall clock that jumped by days between the two is not looked at
        assertEquals(Verdict.RESUME, TripRule.verdict(true, 1_000_000L, 5_000_000L, 1_010_000L, 99_999_999_999L, 5000.0, 100.0))
    }

    @Test fun aClockThatRunsBackwardsDoesNot() {
        assertEquals(Verdict.WRONG_TIME, sameBoot(-1L))
        assertEquals(Verdict.WRONG_TIME, afterPowerOff(-3 * 60 * min)) // the fix says it was earlier than the kept trip
        assertEquals(Verdict.RESUME, afterPowerOff(-1 * min)) // (a minute: two clocks)
    }

    @Test fun nextToThePlaceHeIsThere() {
        assertEquals(Verdict.ARRIVED, sameBoot(5_000L, toDest = 150.0))
        assertEquals(Verdict.RESUME, sameBoot(5_000L, toDest = 310.0))
        // (after a power-off he has parked: a pin 350 m from where he stands is where he was going)
        assertEquals(Verdict.ARRIVED, afterPowerOff(5 * min, toDest = 350.0))
        assertEquals(Verdict.ARRIVED, afterPowerOff(5 * min, toDest = 990.0))
        assertEquals(Verdict.RESUME, afterPowerOff(5 * min, toDest = 1010.0))
    }

    @Test fun aCarFarFromWhereItStoppedIsAnotherStory() {
        assertEquals(Verdict.MOVED, afterPowerOff(5 * min, fromKept = 5000.0))
        // (in the same boot the car may well have driven on while the process was gone)
        assertEquals(Verdict.RESUME, sameBoot(5 * min, fromKept = 5000.0))
    }
}
