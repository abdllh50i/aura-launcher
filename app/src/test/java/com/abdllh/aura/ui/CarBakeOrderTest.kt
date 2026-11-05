package com.abdllh.aura.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bake's order: every frame once, the showroom angle first, and what has been baked covers the whole turn from its first frames. */
class CarBakeOrderTest {
    private val n = 180
    private val first = 108
    private val o = CarBakeOrder.of(n, first)

    @Test fun everyFrameOnce() {
        assertEquals(n, o.size)
        assertEquals(n, o.toSet().size)
        assertTrue(o.all { it in 0 until n })
    }

    @Test fun theShowroomAngleFirst() {
        assertEquals(first, o[0])
    }

    /** The widest gap, in frames, round the turn among the first [k] of the order. */
    private fun widestGap(k: Int): Int {
        val sorted = o.take(k).sorted()
        return (sorted.indices).maxOf { i -> Math.floorMod(sorted[(i + 1) % k] - sorted[i], n).let { if (it == 0) n else it } }
    }

    @Test fun theFirstFramesGoRoundTheWholeTurn() {
        assertTrue(widestGap(CarGlView.COARSE_FRAMES) <= 32)
        assertTrue(widestGap(12) <= 16)
        assertTrue(widestGap(23) <= 8)
        assertTrue(widestGap(45) <= 4)
        assertTrue(widestGap(90) <= 2)
        assertEquals(1, widestGap(n))
    }
}
