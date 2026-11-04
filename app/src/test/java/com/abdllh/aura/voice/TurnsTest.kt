package com.abdllh.aura.voice

import com.abdllh.aura.voice.VoiceTurn.Who
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnsTest {
    private var clock = 0L
    private val timers = ArrayList<Pair<Long, () -> Unit>>()
    private val turns = Turns(now = { clock }, later = { ms, b -> timers.add((clock + ms) to b) })

    private fun advance(ms: Long) {
        clock += ms
        val due = timers.filter { it.first <= clock }
        timers.removeAll(due)
        due.forEach { it.second() }
    }

    @Test fun oneAtATime() {
        assertTrue(turns.take(Who.AMRI))
        assertFalse(turns.free(Who.NAV))
        assertFalse(turns.take(Who.NAV))
        assertTrue(turns.free(Who.AMRI))
        turns.release(Who.AMRI)
        assertTrue(turns.free(Who.NAV))
        assertTrue(turns.take(Who.NAV))
    }

    @Test fun aPromptWaitsForTheAssistantAndGoesWhenItEnds() {
        var said = 0
        turns.take(Who.AMRI)
        turns.whenFree(Who.NAV) { said++ }
        assertEquals(0, said)
        turns.release(Who.AMRI)
        assertEquals(1, said)
        // the prompt holds the turn now
        assertFalse(turns.free(Who.AMRI))
        turns.release(Who.NAV)
        assertTrue(turns.free(Who.AMRI))
    }

    @Test fun theAssistantWaitsForAPrompt() {
        var heard = false
        turns.take(Who.NAV)
        turns.whenFree(Who.AMRI) { heard = true }
        assertFalse(heard)
        turns.release(Who.NAV)
        assertTrue(heard)
    }

    @Test fun onlyTheLatestPromptWaits() {
        val said = ArrayList<String>()
        turns.take(Who.AMRI)
        turns.whenFree(Who.NAV) { said.add("old") }
        turns.whenFree(Who.NAV) { said.add("new") }
        turns.release(Who.AMRI)
        assertEquals(listOf("new"), said)
    }

    @Test fun aTurnNobodyGivesBackEndsByItself() {
        var said = false
        turns.take(Who.NAV)
        turns.whenFree(Who.AMRI) { said = true }
        advance(Turns.NAV_MAX_MS + 1)
        assertTrue(said)
        // (now the assistant's, and that one too)
        advance(Turns.AMRI_MAX_MS + 1)
        assertTrue(turns.free(Who.NAV))
    }

    @Test fun anOldTimerDoesNotEndALaterTurn() {
        turns.take(Who.NAV)
        turns.release(Who.NAV)
        advance(Turns.NAV_MAX_MS - 1000)
        turns.take(Who.NAV) // a new prompt
        advance(2000) // the first prompt's timer is due: it must not touch this one
        assertFalse(turns.free(Who.AMRI))
    }

    @Test fun aNewerPromptOverItsOwnKeepsTheTurn() {
        turns.take(Who.NAV)
        advance(Turns.NAV_MAX_MS - 1000)
        turns.take(Who.NAV)
        advance(2000)
        assertFalse(turns.free(Who.AMRI))
        advance(Turns.NAV_MAX_MS)
        assertTrue(turns.free(Who.AMRI))
    }

    @Test fun whoGivesBackWithoutHoldingChangesNothing() {
        turns.take(Who.AMRI)
        turns.release(Who.NAV)
        assertFalse(turns.free(Who.NAV))
    }

    @Test fun whatIsForgottenDoesNotRun() {
        var said = false
        turns.take(Who.AMRI)
        turns.whenFree(Who.NAV) { said = true }
        turns.forget(Who.NAV)
        turns.release(Who.AMRI)
        assertFalse(said)
        assertTrue(turns.free(Who.NAV))
    }
}
