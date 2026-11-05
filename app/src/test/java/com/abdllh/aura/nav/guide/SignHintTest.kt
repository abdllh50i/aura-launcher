package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The turn card's two lines: one fact in the hint, and what to do with no landmark in it (SPEC.md section 6.2). */
class SignHintTest {
    private val turnM = Kit.line.cumulative[30]
    private val turn get() = Kit.step(turnM, "turn", "right")

    @Test fun theHintIsTheSignalAloneCountedFromTheCar() {
        val g = Guidance.build(Kit.route(turn), true, Kit.world(listOf(Kit.signal(1000.0, 1), Kit.signal(2000.0, 2), Kit.signal(turnM, 3))))
        assertEquals("عند الإشارة الثالثة", g.landmark(1, 500.0))
        assertEquals("عند الإشارة الثانية", g.landmark(1, 1500.0))
        assertEquals("عند الإشارة", g.landmark(1, 2500.0))
        assertEquals("لف يمين", g.actionWords(1))
    }

    @Test fun nothingMarksItNoHint() {
        val g = Guidance.build(Kit.route(turn), true, Kit.world(emptyList()))
        assertNull(g.landmark(1, 500.0))
        assertEquals("لف يمين", g.actionWords(1))
    }

    @Test fun theEnglishHasNoTrailingComma() {
        val g = Guidance.build(Kit.route(turn), false, Kit.world(listOf(Kit.signal(turnM, 1))))
        assertEquals("At the signal", g.landmark(1, 500.0))
        assertEquals("Turn right", g.actionWords(1))
    }

    @Test fun durationsAreSpelledWithTheArabicAgreement() {
        assertEquals("دقيقة", Units.spelled(40.0, true))
        assertEquals("دقيقتين", Units.spelled(120.0, true))
        assertEquals("3 دقائق", Units.spelled(180.0, true))
        assertEquals("10 دقائق", Units.spelled(600.0, true))
        assertEquals("11 دقيقة", Units.spelled(660.0, true))
        assertEquals("18 min", Units.spelled(1080.0, false))
    }
}
