package com.abdllh.aura.voice

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Whose turn it is to use the car's voice. The assistant ([Amri]) and the navigation ([com.abdllh.aura.nav.NavSession]) each
 * have a text to speech engine of their own, and nothing kept a prompt from starting over an answer, or from being heard by
 * the recogniser while the owner talks. So one at a time: the assistant holds the turn from the moment it wakes (listening
 * counts) until it is done, a prompt while it plays. Who comes second waits for the other to end, and a turn nobody gives
 * back (an engine that never reports its end) ends by itself: nothing here can leave the car mute or the assistant deaf.
 * One exception, decided outside: a turn close by takes the voice from a wake nobody has spoken into ([WakeTurn]).
 * Main thread.
 */
object VoiceTurn {
    enum class Who { AMRI, NAV }

    private val main = Handler(Looper.getMainLooper())
    private val turns = Turns(
        now = { SystemClock.elapsedRealtime() },
        later = { ms, block -> main.postDelayed(block, ms) },
        log = { Log.i("AuraVoiceTurn", it) }
    )

    /** [who] may speak now: nobody else holds the turn. */
    fun free(who: Who): Boolean = turns.free(who)

    /** Takes the turn if it is free (or already [who]'s). */
    fun take(who: Who): Boolean = turns.take(who)

    /** Done speaking: whoever waits goes next. */
    fun release(who: Who) = turns.release(who)

    /** [block] as soon as [who] can have the turn, holding it: now, or when the other's turn ends. Only the latest waits. */
    fun whenFree(who: Who, block: () -> Unit) = turns.whenFree(who, block)

    /** What [who] was waiting for is not wanted any more. */
    fun forget(who: Who) = turns.forget(who)
}

/** Whether a wake gives the car's voice up for a turn prompt ([Amri.giveWayToPrompt]). No Android in here. */
internal object WakeTurn {
    /** He gets a moment to start speaking: the prompt comes 8 s ahead, and takes about 3. */
    const val GRACE_MS = 2_000L

    fun givesWay(listening: Boolean, byVoice: Boolean, speechBegun: Boolean, sinceWakeMs: Long): Boolean =
        listening && byVoice && !speechBegun && sinceWakeMs >= GRACE_MS
}

/** [VoiceTurn]'s rules, apart from Android's clock and handler. */
internal class Turns(
    private val now: () -> Long,
    private val later: (Long, () -> Unit) -> Unit,
    private val log: (String) -> Unit = {}
) {
    private var holder: VoiceTurn.Who? = null
    private var since = 0L
    private var generation = 0
    private val waiting = HashMap<VoiceTurn.Who, () -> Unit>()

    fun free(who: VoiceTurn.Who): Boolean = holder == null || holder == who

    fun take(who: VoiceTurn.Who): Boolean {
        if (!free(who)) return false
        if (holder != who) {
            holder = who
            since = now()
            log("$who takes the turn")
        }
        val g = ++generation
        // (given back in any case: an engine that never says it is done, a code path that forgets)
        later(if (who == VoiceTurn.Who.AMRI) AMRI_MAX_MS else NAV_MAX_MS) {
            if (generation == g && holder == who) {
                log("$who kept the turn too long")
                release(who)
            }
        }
        return true
    }

    fun release(who: VoiceTurn.Who) {
        if (holder != who) return
        holder = null
        generation++
        log("$who gives the turn back after ${now() - since} ms")
        // the other one first, if it waits
        val next = waiting.keys.firstOrNull { it != who } ?: waiting.keys.firstOrNull() ?: return
        val block = waiting.remove(next) ?: return
        take(next)
        block()
    }

    fun whenFree(who: VoiceTurn.Who, block: () -> Unit) {
        if (take(who)) { block(); return }
        log("$who waits for its turn")
        waiting[who] = block
    }

    fun forget(who: VoiceTurn.Who) {
        waiting.remove(who)
    }

    companion object {
        /** Listening 15 s, a slow answer 12 s, the answer 8 s: [Amri] always ends before this. */
        const val AMRI_MAX_MS = 40_000L
        /** The longest prompt is a few seconds. */
        const val NAV_MAX_MS = 15_000L
    }
}
