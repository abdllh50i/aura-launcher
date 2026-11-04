package com.abdllh.aura.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingRuleTest {
    private fun song(from: String?, playing: Boolean) = NowPlaying("t", "a", null, playing, from)

    private val own = "com.abdllh.aura"
    private val app = "com.google.android.apps.youtube.music"

    private fun pick(bt: NowPlaying? = null, aura: NowPlaying? = null, session: NowPlaying? = null, broadcast: NowPlaying? = null,
                     last: String? = null, chosenBt: Boolean = false) =
        NowPlayingRule.pick(bt, aura, session, broadcast, last, chosenBt)

    @Test fun whatPlaysWins() {
        val bt = song(MediaMonitor.SOURCE_BT, true)
        val aura = song(own, true)
        val session = song(app, true)
        val broadcast = song(null, true) // (one that said it plays: broadcastsThatSayNothing)
        assertSame(bt, pick(bt, aura, session, broadcast, last = own))
        assertSame(aura, pick(song(MediaMonitor.SOURCE_BT, false), aura, session, broadcast))
        assertSame(session, pick(song(MediaMonitor.SOURCE_BT, false), song(own, false), session, broadcast))
        assertSame(broadcast, pick(song(MediaMonitor.SOURCE_BT, false), song(own, false), song(app, false), broadcast))
    }

    /** The stock Bluetooth app tells every title with no state: that was "playing" for ever, and took the card and the assistant's resume. */
    @Test fun broadcastsThatSayNothing() {
        assertFalse(NowPlayingRule.broadcastPlaying(null, null))
        assertFalse(NowPlayingRule.broadcastPlaying("what", null))
        // (what it said last stands: a title on its own says nothing about that)
        assertTrue(NowPlayingRule.broadcastPlaying(null, true))
        assertFalse(NowPlayingRule.broadcastPlaying(null, false))
        assertTrue(NowPlayingRule.broadcastPlaying(true, false))
        assertFalse(NowPlayingRule.broadcastPlaying(false, true))
        assertTrue(NowPlayingRule.broadcastPlaying(1, null))
        assertTrue(NowPlayingRule.broadcastPlaying(3, null))
        assertFalse(NowPlayingRule.broadcastPlaying(2, true))
    }

    @Test fun aBroadcastThatSaidNothingTakesNothingFromThePausedPhone() {
        val bt = song(MediaMonitor.SOURCE_BT, false)
        val silent = song(null, false)
        assertSame(bt, pick(bt, song(own, false), null, silent, last = MediaMonitor.SOURCE_BT))
        assertSame(bt, pick(bt, song(own, false), null, silent, chosenBt = true))
    }

    /** The bug: the phone paused handed the card (and the play button) to the old local queue. */
    @Test fun thePhonePausedStaysThePhone() {
        val bt = song(MediaMonitor.SOURCE_BT, false)
        assertSame(bt, pick(bt, song(own, false), last = MediaMonitor.SOURCE_BT))
        // (also over another app's old paused session)
        assertSame(bt, pick(bt, song(own, false), song(app, false), last = MediaMonitor.SOURCE_BT))
    }

    @Test fun theLocalPlayerPausedStaysTheLocalPlayer() {
        val aura = song(own, false)
        assertSame(aura, pick(song(MediaMonitor.SOURCE_BT, false), aura, song(app, false), last = own))
    }

    @Test fun whatPlayedLastAndIsGoneGivesWay() {
        val aura = song(own, false)
        assertSame(aura, pick(null, aura, last = MediaMonitor.SOURCE_BT)) // (the phone left)
        val session = song(app, false)
        assertSame(session, pick(null, aura, session, last = "gone.app"))
    }

    @Test fun anAppKnownOnlyByItsBroadcasts() {
        val broadcast = song(null, false)
        assertSame(broadcast, pick(song(MediaMonitor.SOURCE_BT, false), song(own, false), null, broadcast, last = NowPlayingRule.BROADCAST))
    }

    @Test fun beforeAnythingPlayedTheSourceChosenLast() {
        val bt = song(MediaMonitor.SOURCE_BT, false)
        val aura = song(own, false)
        assertSame(bt, pick(bt, aura, chosenBt = true))
        assertSame(aura, pick(bt, aura, chosenBt = false))
    }

    @Test fun theOldOrderOtherwise() {
        val bt = song(MediaMonitor.SOURCE_BT, false)
        val aura = song(own, false)
        val session = song(app, false)
        val broadcast = song(null, false)
        assertSame(session, pick(bt, aura, session, broadcast))
        assertSame(broadcast, pick(bt, aura, null, broadcast))
        assertSame(aura, pick(bt, aura))
        assertSame(bt, pick(bt))
        assertNull(pick())
    }
}
