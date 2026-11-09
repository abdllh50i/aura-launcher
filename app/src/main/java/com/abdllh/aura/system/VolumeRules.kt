package com.abdllh.aura.system

/**
 * The volume's rules, with no Android in them (VolumeRulesTest): the 0..[maxLevel] scale over the firmware's 0..[max], and the guard
 * against the firmware's step that goes the wrong way. [CarAudio] tells it what happens (a steering-wheel key, the firmware's new
 * value, AMRI's own choice) and does what it answers.
 *
 * The scale: 0..max is the firmware's volume as it always was; above it the firmware stays at its top and [boost] steps of digital
 * gain are added ([VolumeBoost]). The firmware handles the wheel's keys itself (the setting service steps its audio IC's value by
 * one), so AMRI only adds: at the top a wheel up raises the boost; a wheel down while boosted lowers the boost, and the firmware's
 * own step down is put back at once.
 *
 * The guard: the firmware steps from its audio IC's last value, which is not always the volume the owner hears (a reading of the
 * setting service, `NewOsSystemVolumeManager.handlePanelKey`: getVolume() + 1), so a wheel up can land lower than before. A wheel up
 * followed within [WINDOW_MS] by a firmware value lower than before the key is put back to that value plus one; a wheel down that
 * ends higher, to that value minus one. Once per key, and AMRI's own echo is never news: it cannot loop with the firmware, so
 * there is no brake (beta.4's brake let every fifth press of a held key through, which is how he still heard the drop).
 *
 * The broadcast of a key and the firmware's report of its step race each other (one is a broadcast, the other a binder call):
 * a change no key explains is held for [CLAIM_MS], and a key that comes in that time is its cause.
 *
 * The heat protection is never fought (a reading of the firmware, not seen on his unit: system_server's NwdManagerService and the
 * setting service's CpuTempMonitor send com.nwd.action.ACTION_HIGHTEMP_TRIGGER, and the setting service sets the volume to its
 * extra_volume, 30 on his unit, whenever it is above that while the unit is hot). So: [heat] releases the boost and stops the
 * guard for [HEAT_MS]. A wheel step that lands exactly on a heat cap ([heatCaps]) waits [HEAT_WAIT_MS] for the broadcast: if it
 * comes, the drop is the heat protection's and is left; if not, it is the firmware's stale step and is corrected (beta.4 left
 * every drop to 30 alone, and his drops were to 30 with no heat at all). A boost the heat's drop leaves under the top is released.
 */
class VolumeRules(var max: Int = 40, val boostSteps: Int = BOOST_STEPS) {
    sealed class Act {
        /** Set the firmware's volume (AMRI's own set). */
        data class Firmware(val value: Int, val why: String) : Act()
        /** The digital boost in steps; 0 releases it. */
        data class Boost(val steps: Int, val why: String) : Act()
        /** Ask [recheck] again after this long. */
        data class Recheck(val afterMs: Long) : Act()
        /** A line for the volume log. */
        data class Note(val kind: String, val text: String) : Act()
    }

    /** The firmware's volume as last known (-1: not yet). */
    var fw = -1
        private set
    var boost = 0
        private set

    private var keyDir = 0
    private var keyAt = NEVER
    private var keyBefore = -1
    private var keyStepped = false
    private var keyCorrected = false
    private var keyBoostDown = false

    private var looseFrom = -1
    private var looseTo = -1
    private var looseAt = NEVER

    private var selfValue = -1
    private var selfAt = NEVER

    /** A correction that waits for the heat broadcast: the value to put back, since when, and what happened. */
    private var pendTarget = -1
    private var pendAt = NEVER
    private var pendText = ""
    private var pendBoostDown = false

    /** The values the unit's heat protection sets (persist.warntemp.asp.volume 30, .dsp.volume 20, .volume 30, .volume2 20). */
    var heatCaps: Set<Int> = setOf(30, 20)
    private var heatAt = NEVER

    /** The heat protection fired less than [HEAT_MS] ago. */
    fun hot(now: Long): Boolean = heatAt != NEVER && now - heatAt in 0..HEAT_MS

    fun maxLevel(): Int = max + boostSteps

    /** The unit's heat protection lowers the volume to [v] (its broadcast): no boost, and the guard leaves its drops alone. */
    fun heat(v: Int, now: Long): List<Act> {
        heatAt = now
        if (v in 1..max) heatCaps = heatCaps + v
        val out = ArrayList<Act>(2)
        out += Act.Note("heat", "the unit's heat protection sets the volume to $v")
        if (boost > 0) { boost = 0; out += Act.Boost(0, "the unit's heat protection") }
        return out
    }

    /** The number the row, the pill and Amri show: the firmware's, and above its top the boost. */
    fun level(): Int = if (fw >= max) max + boost else fw.coerceAtLeast(0)

    /** Where things stand at a start: the saved boost counts only while the firmware is at its top. */
    fun start(fwNow: Int, savedBoost: Int): List<Act> {
        fw = fwNow
        boost = if (fwNow >= max) savedBoost.coerceIn(0, boostSteps) else 0
        return listOf(Act.Boost(boost, "start at $fwNow"))
    }

    /** AMRI's own choice of level (the row, Amri, the phone): always the firmware's value, and the boost when it changes. */
    fun setLevel(n: Int, now: Long): List<Act> {
        val want = n.coerceIn(0, maxLevel())
        val out = ArrayList<Act>(2)
        if (want > max) {
            out += self(max, now, "level $want")
            if (boost != want - max) { boost = want - max; out += Act.Boost(boost, "level $want") }
        } else {
            if (boost != 0) { boost = 0; out += Act.Boost(0, "level $want") }
            out += self(want, now, "level $want")
        }
        return out
    }

    /** The mute key: no step to judge. */
    fun muteKey() { keyAt = NEVER }

    /** A steering-wheel key, [dir] +1 up or -1 down. The firmware takes its own step; this says what AMRI adds. */
    fun key(dir: Int, muted: Boolean, now: Long): List<Act> {
        val out = ArrayList<Act>(3)
        val loose = looseAt != NEVER && now - looseAt <= CLAIM_MS
        keyDir = dir
        keyAt = now
        keyStepped = false
        keyCorrected = false
        keyBoostDown = false
        keyBefore = if (loose) looseFrom else fw
        if (dir > 0) {
            // muted: the firmware's key turns the sound back on, that is the step
            if (!muted && keyBefore >= max && boost < boostSteps) {
                boost++
                out += Act.Boost(boost, "wheel up at the top")
            }
        } else if (boost > 0 && keyBefore >= max) {
            boost--
            keyBoostDown = true
            out += Act.Boost(boost, "wheel down while boosted")
        }
        if (loose) {
            val from = looseFrom
            val to = looseTo
            clearLoose()
            out += judge(from, to, now)
        }
        return out
    }

    /** The firmware's volume as it reports it (its callback, or its setting through the observer). */
    fun firmware(v: Int, now: Long): List<Act> {
        if (v == fw) return emptyList()
        val from = fw
        fw = v
        if (from < 0) return emptyList()
        if (v == selfValue && now - selfAt <= SELF_MS) return emptyList() // AMRI's own, echoed late
        if (keyAt != NEVER && now - keyAt <= WINDOW_MS) return judge(from, v, now)
        return loose(from, v, now)
    }

    /** A change no key explained was held: a key did not come, so it was somebody else's (another app, the firmware itself).
     *  Also the end of a wait for the heat broadcast: none came, so the drop to the cap is corrected after all. */
    fun recheck(now: Long): List<Act> {
        if (pendAt != NEVER && now - pendAt >= HEAT_WAIT_MS) {
            val target = pendTarget
            val text = pendText
            val boostDown = pendBoostDown
            pendAt = NEVER
            pendTarget = -1
            if (hot(now)) return left("heat", "$text: the heat broadcast came, the heat protection's, left")
            if (boostDown) return listOf(Act.Note("glitch", "$text: no heat broadcast, the firmware back to $target"), self(target, now, "wheel down while boosted"))
            if (fw == target) return emptyList()
            return listOf(Act.Note("glitch", "$text: no heat broadcast, corrected to $target"), self(target, now, "glitch corrected"))
        }
        if (looseAt == NEVER || now - looseAt < CLAIM_MS) return emptyList()
        clearLoose()
        if (boost > 0 && fw < max) {
            boost = 0
            return listOf(Act.Boost(0, "the firmware left its top by itself (now $fw)"))
        }
        return emptyList()
    }

    /** Not corrected: a note, and a boost the firmware's drop left under its top goes (the effect must not stay on at 30). */
    private fun left(kind: String, text: String): List<Act> {
        val out = arrayListOf<Act>(Act.Note(kind, text))
        if (boost > 0 && fw in 0 until max) { boost = 0; out += Act.Boost(0, "the firmware left its top ($fw)") }
        return out
    }

    private fun judge(from: Int, to: Int, now: Long): List<Act> {
        if (keyBoostDown) {
            if (to < max && !keyCorrected) {
                // the firmware's own step is to max - 1; any drop while hot is the heat protection's
                if (hot(now)) return left("heat", "wheel down while boosted, firmware $from -> $to: the unit is hot, left")
                keyCorrected = true
                if (to in heatCaps && to < max - 1) return waitForHeat(max, true, "wheel down while boosted, firmware $from -> $to", now)
                return listOf(self(max, now, "wheel down while boosted: the firmware back to $max"))
            }
            return emptyList()
        }
        val wrong = (keyDir > 0 && to < keyBefore) || (keyDir < 0 && to > keyBefore)
        if (wrong) {
            val dir = if (keyDir > 0) "up" else "down"
            if (keyDir > 0 && hot(now)) return left("heat", "wheel up at $keyBefore, firmware $from -> $to: the unit is hot, left")
            if (keyCorrected) return left("glitch", "wheel $dir at $keyBefore, firmware $from -> $to again: left")
            keyCorrected = true
            val target = if (keyDir > 0) minOf(max, keyBefore + 1) else maxOf(0, keyBefore - 1)
            if (keyDir > 0 && to in heatCaps) return waitForHeat(target, false, "wheel up at $keyBefore, firmware $from -> $to", now)
            return listOf(
                Act.Note("glitch", "wheel $dir at $keyBefore, firmware went to $to: corrected to $target"),
                self(target, now, "glitch corrected")
            )
        }
        if (!keyStepped) { keyStepped = true; return emptyList() } // the step the key asked for
        return loose(from, to, now) // a second change: maybe the next key's, its broadcast still on the way
    }

    /** A drop exactly to a heat cap: the heat protection's only if its broadcast comes ([recheck] decides). */
    private fun waitForHeat(target: Int, boostDown: Boolean, text: String, now: Long): List<Act> {
        pendTarget = target
        pendAt = now
        pendText = text
        pendBoostDown = boostDown
        return listOf(Act.Note("glitch", "$text: on a heat cap, waiting ${HEAT_WAIT_MS} ms for the heat broadcast"), Act.Recheck(HEAT_WAIT_MS + 20))
    }

    private fun loose(from: Int, to: Int, now: Long): List<Act> {
        looseFrom = from
        looseTo = to
        looseAt = now
        return listOf(Act.Recheck(CLAIM_MS + 20))
    }

    private fun clearLoose() { looseAt = NEVER; looseFrom = -1; looseTo = -1 }

    private fun self(v: Int, now: Long, why: String): Act {
        fw = v
        selfValue = v
        selfAt = now
        return Act.Firmware(v, why)
    }

    companion object {
        const val BOOST_STEPS = 10
        const val WINDOW_MS = 700L
        const val CLAIM_MS = 300L
        const val SELF_MS = 1500L
        /** How long a drop exactly to a heat cap waits for the heat broadcast before it counts as the firmware's stale step. */
        const val HEAT_WAIT_MS = 1200L
        /** How long after the heat broadcast the unit counts as hot (it repeats it every ~10 s while it stays hot above the cap). */
        const val HEAT_MS = 5 * 60_000L
        private const val NEVER = Long.MIN_VALUE / 2
    }
}
