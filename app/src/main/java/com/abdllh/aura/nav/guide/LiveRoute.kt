package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Route
import com.abdllh.aura.nav.RouteLimits
import com.abdllh.aura.nav.Step

/**
 * An answer as the running app knows a route ([Route], [Step]): NavSession, the banner, the map and RoadWatch work on it unchanged,
 * with a name picked by script instead of a raw list, the scaled time, the signs' real words, the roundabout's number only where it
 * can be trusted, and OSM's limits from the facts. The first and smallest step of the wiring: no new word reaches the driver by it
 * until the screens and the voice ask [Guidance]. [Route.guide] carries that.
 */
object LiveRoute {
    fun of(route: GuideRoute, arabic: Boolean, table: EtaTable = EtaTable.DEFAULT): Route {
        val steps = route.steps.mapIndexed { i, s ->
            val next = route.steps.getOrNull(i + 1)
            val road = Names.pick(s.name, s.ref, arabic, s.distance)
            val sign = Signs.read(s.exits, s.destinations, arabic)?.without(road)
            Step(s.type, s.modifier, if (s.entersRing) Ring.number(s, next) else s.exit, road.shown, road.ref, s.pos, s.distance, table.seconds(s),
                lanes = GuideAnswer.lanesOf(s), destinations = sign?.toward?.joinToString(", ").orEmpty(), exits = sign?.number.orEmpty()).also {
                it.at = s.at
                it.span = s.span
                it.angle = s.angle
                it.silent = s.silent
            }
        }
        val out = Route(route.line, steps, route.distance, steps.sumOf { it.duration })
        out.limits = route.facts?.let { limits(route, it) }
        return out
    }

    /** The facts in the form [RoadWatch][com.abdllh.aura.nav.RoadWatch] already reads: it takes the owner's limit first. */
    private fun limits(route: GuideRoute, facts: RoadFacts): RouteLimits? {
        val pts = route.line.points
        return RouteLimits.of(pts, facts.stretches.mapNotNull { s ->
            pts.getOrNull(s.endIndex)?.let { RouteLimits.Row(it, if (s.limit > 0) "maxspeed=${s.limit}" else "") }
        })
    }
}
