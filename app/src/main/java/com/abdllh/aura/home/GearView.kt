package com.abdllh.aura.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.widget.LinearLayout
import com.abdllh.aura.system.Gear
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lift
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp
import kotlin.math.abs

/**
 * P R N D, like the gear lever: a capsule whose lit circle slides to the current gear (R in the warning colour, the
 * others in the accent) and fades out while the gear is unknown. A D only assumed (the car stopped after driving
 * forward, [Gear.Source.HELD]) gets a ring instead of a filled circle. Always left to right, also in Arabic (the
 * letters are the lever's).
 * Each letter takes its lit colour by how much of the circle is over it, frame by frame with the circle: set at once
 * (until 1.11), the new letter went white on the light capsule before the circle got there. All four letters keep one
 * weight, so a shift redraws them and lays nothing out.
 */
class GearView(ctx: Context) : LinearLayout(ctx) {
    private val letters: List<AText> = Gear.Pos.values().map { p ->
        ctx.label(Type.BUTTON, Palette.text3, gravity = Gravity.CENTER).apply { text = p.name }
    }
    private val listener: () -> Unit = { show(true) }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cx = 0f        // the lit circle's centre
    private var lit = 0f       // its opacity (0 while the gear is unknown)
    private var color = Palette.accent
    private var ring = false
    private var at = -1        // the letter it stands on
    private val move = Spring(0f, 420f, 0.78f, 0.5f) { cx = it; tint(); invalidate() }
    private val fade = Spring(0f, 300f, 1f) { lit = it; tint(); invalidate() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = LAYOUT_DIRECTION_LTR
        setWillNotDraw(false)
        setPadding(5.dp, 5.dp, 5.dp, 5.dp)
        background = Shapes.pill(if (Palette.dark) Palette.card2 else Palette.card, if (Palette.dark) Palette.stroke else 0)
        lift(Elev.REST)
        for ((i, t) in letters.withIndex()) addView(t, lp(CELL.dp, CELL.dp).apply { if (i > 0) marginStart = 2.dp })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Gear.addListener(listener)
        show(false)
    }

    override fun onDetachedFromWindow() {
        Gear.removeListener(listener)
        move.snapTo(move.target)
        fade.snapTo(fade.target)
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed) show(false)
    }

    private fun centre(i: Int): Float = letters[i].let { (it.left + it.right) / 2f }

    private fun show(animate: Boolean) {
        val g = Gear.current
        val sure = Gear.source != Gear.Source.HELD
        val laidOut = letters[0].width > 0
        color = if (g == Gear.Pos.R) Palette.warn else Palette.accent
        ring = g != null && !sure
        if (g != null && laidOut) {
            val x = centre(g.ordinal)
            if (!animate || at < 0 || lit < 0.05f) move.snapTo(x) else move.animateTo(x)
            at = g.ordinal
        }
        val target = if (g != null) 1f else 0f
        if (animate && laidOut) fade.animateTo(target) else fade.snapTo(target)
        tint()
        contentDescription = g?.name ?: ""
        invalidate()
    }

    /** The letters' colours for where the circle is now: lit as far as it covers them (only a redraw). */
    private fun tint() {
        if (letters[0].width == 0) return
        // on the filled circle the letter is the fill's text colour; inside the ring, the gear's colour as text
        val on = when {
            !ring -> Palette.onColor(color)
            color == Palette.accent -> Palette.accentFg
            else -> color
        }
        val shown = lit.coerceIn(0f, 1f)
        for ((i, t) in letters.withIndex()) {
            val k = (1f - abs(cx - centre(i)) / CELL.dp).coerceIn(0f, 1f) * shown
            t.setTextColor(Palette.mix(Palette.text3, on, k))
        }
    }

    override fun dispatchDraw(c: Canvas) {
        if (lit > 0.01f && at >= 0) {
            val r = CELL.dp / 2f
            val cy = height / 2f
            p.color = color
            p.alpha = (255 * lit.coerceIn(0f, 1f)).toInt()
            if (ring) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f.dp
                c.drawCircle(cx, cy, r - 1f.dp, p)
                p.style = Paint.Style.FILL
            } else c.drawCircle(cx, cy, r, p)
        }
        super.dispatchDraw(c)
    }

    private companion object {
        const val CELL = 34 // dp, one letter
    }
}
