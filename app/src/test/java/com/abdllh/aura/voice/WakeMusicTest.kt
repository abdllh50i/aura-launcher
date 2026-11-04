package com.abdllh.aura.voice

import com.abdllh.aura.voice.WakeMusic.Then
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeMusicTest {
    private fun after(paused: Boolean = true, ownerStopped: Boolean = false, otherPlaying: Boolean = false, inCall: Boolean = false) =
        WakeMusic.after(paused, ownerStopped, otherPlaying, inCall)

    @Test fun whatWasPausedPlaysAgain() = assertEquals(Then.PLAY, after())

    /** Music paused by the owner before the wake was not the assistant's: a false wake leaves it paused. */
    @Test fun nothingPausedNothingStarts() = assertEquals(Then.LEAVE, after(paused = false))

    @Test fun pauseOrMuteMeansIt() = assertEquals(Then.LEAVE, after(ownerStopped = true))

    @Test fun anotherSourceIsNotInterrupted() = assertEquals(Then.LEAVE, after(otherPlaying = true))

    @Test fun notDuringACall() {
        assertEquals(Then.AFTER_CALL, after(inCall = true))
        assertEquals(Then.LEAVE, after(inCall = true, ownerStopped = true))
    }
}
