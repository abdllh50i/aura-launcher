package com.abdllh.aura.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LocaleSpan
import android.text.style.ReplacementSpan
import android.text.style.TypefaceSpan
import java.util.Locale

/**
 * A number and its unit in one line (SPEC.md section 3): the number in the label's own role, the unit in a smaller one, with a 6 px gap: one
 * fixed-width label with spans, everywhere, so a number crossing 99 to 100 needs no layout. In a right-to-left line the number stands at the
 * reading edge and the unit after it. The number is its own run with [Locale.ENGLISH]: digits shaped in a run with Arabic can come out shorter
 * (section 3, "A number is its own run"). [ink] is the unit's colour as it is now: a text that is set anew at each fix follows the theme by itself.
 */
fun withUnit(number: CharSequence, unit: CharSequence, unitRole: Type.Role, ink: Int, moving: Boolean = false): CharSequence {
    val s = SpannableStringBuilder(number)
    s.setSpan(LocaleSpan(Locale.ENGLISH), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    val gapAt = s.length
    s.append(" ")
    s.setSpan(Gap(UNIT_GAP), gapAt, gapAt + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    val at = s.length
    s.append(unit)
    s.setSpan(AbsoluteSizeSpan(unitRole.px, false), at, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    if (android.os.Build.VERSION.SDK_INT >= 28) s.setSpan(TypefaceSpan(Fonts.of(unitRole, moving)), at, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    s.setSpan(ForegroundColorSpan(ink), at, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    return s
}

private const val UNIT_GAP = 6

/** A space of exact width: fonts disagree about a space, and a space in a run has no business changing with the size. */
private class Gap(private val px: Int) : ReplacementSpan() {
    override fun getSize(p: Paint, t: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = px

    override fun draw(c: Canvas, t: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {}
}
