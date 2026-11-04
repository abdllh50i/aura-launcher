package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Geo
import kotlin.math.abs

/**
 * What to say at a roundabout. The router's exit number counts every arm, driveways and service roads too: on 8 of 60 rings of
 * the test trips it differs from OSM's road exits (d3 says the third where one road leaves). The answer does not say which arm is
 * what, but it does show where they are: two arms nearer than [SAME_ROAD_M] along the ring are a driveway beside a road, or a road's
 * two sides, and then nobody can count them from the car. So the number is said only where the arms are well apart, and the
 * direction measured on the line ([GuideStep.angle]) takes over where they are not. On all 60: 6 of the 8 bad numbers go and
 * 1 good one with them; the other 2 are a first exit that OSM calls a service road, still the first arm a driver meets ([RingTest]).
 * A first exit that runs straight on is no choice worth counting: "كمّل على طول".
 */
object Ring {
    enum class Way { STRAIGHT, SLIGHT_RIGHT, RIGHT, SLIGHT_LEFT, LEFT, BACK, NONE }

    private const val STRAIGHT_DEG = 25.0
    private const val SQUARE_DEG = 60.0
    private const val BACK_DEG = 150.0
    private const val SAME_ROAD_M = 17.0
    /** Past this nobody counts, and it is not in the clips. */
    private const val MOST = 4

    fun way(angle: Double): Way = when {
        angle.isNaN() -> Way.NONE
        abs(angle) <= STRAIGHT_DEG -> Way.STRAIGHT
        abs(angle) >= BACK_DEG -> Way.BACK
        angle >= SQUARE_DEG -> Way.RIGHT
        angle > 0 -> Way.SLIGHT_RIGHT
        angle <= -SQUARE_DEG -> Way.LEFT
        else -> Way.SLIGHT_LEFT
    }

    /** Where the car may leave on the way round, in order, the way out last ([next]: the step that leaves). */
    private fun armsAround(step: GuideStep, next: GuideStep?): List<com.abdllh.aura.nav.LatLon> {
        val out = ArrayList<com.abdllh.aura.nav.LatLon>()
        for ((k, c) in step.crossings.withIndex()) {
            if (k == 0) continue
            for (j in c.bearings.indices) if (j != c.into && j != c.out && c.entry.getOrElse(j) { false }) out.add(c.pos)
        }
        if (next != null && next.leavesRing) next.crossings.firstOrNull()?.let { out.add(it.pos) }
        return out
    }

    /** The answer's own count, checked: the arms it passes. */
    fun counted(step: GuideStep, next: GuideStep?): Int = armsAround(step, next).size

    /** Two arms too close to be told apart from the car. */
    fun armsTooClose(step: GuideStep, next: GuideStep?): Boolean =
        armsAround(step, next).zipWithNext().any { (a, b) -> Geo.distance(a, b) < SAME_ROAD_M }

    /** The number to say at this roundabout, 0 when the direction must do. */
    fun number(step: GuideStep, next: GuideStep?): Int {
        val n = step.exit
        if (n !in 1..MOST || counted(step, next) != n || armsTooClose(step, next)) return 0
        if (n == 1 && way(step.angle) == Way.STRAIGHT) return 0
        return n
    }

    /** The word for a direction, or null when there is none to give. */
    fun wordFor(w: Way): String? = when (w) {
        Way.STRAIGHT -> "ring.straight"
        Way.RIGHT -> "ring.right"
        Way.LEFT -> "ring.left"
        Way.SLIGHT_RIGHT -> "ring.slight_right"
        Way.SLIGHT_LEFT -> "ring.slight_left"
        Way.BACK -> "ring.back"
        Way.NONE -> null
    }
}
