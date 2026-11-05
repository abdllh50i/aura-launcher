package com.abdllh.aura.nav.drive

import com.abdllh.aura.nav.Instructions
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.nav.Route
import com.abdllh.aura.nav.Step
import com.abdllh.aura.nav.Turn
import com.abdllh.aura.nav.guide.Part
import com.abdllh.aura.nav.guide.Speech

/**
 * What the turn card's two lines say of a step (SPEC.md section 6.2), from the guidance's words where the route has them in this language ([Route.guide]), else [Instructions].
 * The road line: the road the manoeuvre leads onto, Latin names as the guidance shows them, or a roundabout's way out; a step with no name says what it can, with no invented street.
 * The hint line: one fact, the first that exists of a landmark, the "then" of what follows closely, the manoeuvre in words. So it is never empty and the card never changes size.
 */
internal object TurnWords {
    /** More than this to the next thing to do: the quiet card. */
    const val QUIET_M = 2000.0

    class Lines(@JvmField val road: String, @JvmField val hint: String)

    /** The next step takes the route to the left, so the camera moves the car right (map-*.json `leftTurnShift`). Not the arrival: a place on the left side of the road is no turn, and its camera has the car at x 701. */
    fun isLeft(s: Step): Boolean = s.type != "arrive" && when (Instructions.turn(s)) {
        Turn.LEFT, Turn.SLIGHT_LEFT, Turn.SHARP_LEFT, Turn.UTURN -> true
        Turn.ROUNDABOUT -> Instructions.exitAngle(s) < -30f
        else -> false
    }

    /** The road step [i] leads onto, as the sign shows it; "" when it has no name. */
    private fun named(r: Route, i: Int, arabic: Boolean): String {
        val g = r.guide?.takeIf { it.arabic == arabic }
        val told = g?.toldOfStep(i) ?: -1
        return if (g != null && told >= 0) g.told[told].road.shown else Instructions.road(r.steps[i])
    }

    /** The road of the next turn, for the map's names. */
    fun nextRoad(r: Route, next: Int, arabic: Boolean): String = if (r.steps[next].type == "arrive") "" else named(r, next, arabic)

    /** The road the car is on, "" when it has no name. */
    fun currentRoad(r: Route, next: Int, arabic: Boolean, along: Double = NavSession.progress?.along ?: 0.0): String {
        val k = r.steps.indexOfLast { it.at <= along + 3 }.coerceAtMost(next - 1)
        return if (k < 0) "" else named(r, k, arabic)
    }

    /** [keepThen]: "استمر، ثم %s". */
    fun lines(r: Route, p: NavSession.Progress, arabic: Boolean, destination: String?, keepThen: (String) -> String): Lines {
        val step = r.steps[p.next]
        val g = r.guide?.takeIf { it.arabic == arabic }
        val told = g?.toldOfStep(p.next) ?: -1
        val guided = g != null && told >= 0
        val action = (if (guided) g!!.actionWords(told).ifBlank { null } else null) ?: Instructions.action(step, arabic)
        val ring = Instructions.entersRoundabout(step)
        val arrive = step.type == "arrive"
        if (!arrive && p.toNext > QUIET_M) {
            val here = currentRoad(r, p.next, arabic, p.along).ifBlank { word("straight", arabic) }
            return Lines(here, keepThen(if (arabic) action else action.replaceFirstChar { it.lowercase() }))
        }
        val landmark = if (guided) g!!.landmark(told, p.along) else if (ring) word("ring.at", arabic) else null
        val name = when {
            arrive -> destination.orEmpty()
            ring -> (if (guided) g!!.ringWay(told) else "").ifBlank { Instructions.action(step, arabic) }
            else -> named(r, p.next, arabic).ifBlank { if (guided) g!!.banner(told, p.along).toward else Instructions.roadLine(step, arabic) }
        }
        if (name.isNotBlank()) return Lines(name, landmark.takeUnless { arrive } ?: thenWords(r, p, arabic, closely = true) ?: action)
        // no name, in any script (half the turns here): the road line says what it can, the hint what is left
        return if (landmark != null) Lines(landmark, action)
        else Lines(action, thenWords(r, p, arabic, closely = false) ?: destination?.takeIf { it.isNotBlank() } ?: word("straight", arabic))
    }

    /** "ثم لف يسار": the manoeuvre after this one; [closely]: only within 12 s at this speed, at least 250 m (the rule of the old banner). */
    private fun thenWords(r: Route, p: NavSession.Progress, arabic: Boolean, closely: Boolean): String? {
        val step = r.steps[p.next]
        if (step.type == "arrive") return null
        val then = r.following(p.next) ?: return null
        if (closely && then.at - (step.at + step.span) >= maxOf(250.0, p.speedKmh / 3.6 * 12)) return null
        val g = r.guide?.takeIf { it.arabic == arabic }
        val told = g?.toldOfStep(r.steps.indexOf(then)) ?: -1
        val next = (if (g != null && told >= 0) g.actionWords(told).ifBlank { null } else null) ?: Instructions.action(then, arabic)
        val line = "${word("then", arabic)} ${if (arabic) next else next.replaceFirstChar { it.lowercase() }}"
        return if (arabic) line else line.replaceFirstChar { it.uppercase() }
    }

    private fun word(id: String, arabic: Boolean) = Speech.text(listOf(Part.Clip(id)), arabic, said = false)
}
