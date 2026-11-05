package com.abdllh.aura.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import com.abdllh.aura.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * A manoeuvre's glyph for every sign. The arrows are the one family of icons (SPEC.md section 5: `turn-right`, `ramp-left`...), which never mirror; only the roundabout is
 * drawn, at the angle its exit really leads: a dim ring, a short stub for each exit passed, a thick arc round to the way out and the arrows' open head.
 * [glyph] puts any other glyph of the family there (`gps-off`). Set on every fix: it redraws only when something changed.
 */
class ManeuverView(ctx: Context) : View(ctx) {
    var turn: Turn = Turn.STRAIGHT
        set(v) { if (v != field) { field = v; invalidate() } }
    var exit: Int = 0
        set(v) { if (v != field) { field = v; invalidate() } }
    /** A roundabout: where its exit leads, degrees (+ right, [Instructions.exitAngle]); NaN: by the exit's number. */
    var angle: Float = Float.NaN
        set(v) { if (v != field && !(v.isNaN() && field.isNaN())) { field = v; invalidate() } }
    var color: Int = 0xFFFFFFFF.toInt()
        set(v) { if (v != field) { field = v; invalidate() } }
    /** A glyph of its own instead of [turn]'s (a ramp's, a fork's, `gps-off`); 0: [turn]'s. */
    var glyph: Int = 0
        set(v) { if (v != field) { field = v; invalidate() } }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val oval = RectF()
    private var drawnRes = 0
    private var drawn: Drawable? = null

    /** Its alpha goes into what it draws: a fade of this view takes no off-screen buffer. */
    override fun hasOverlappingRendering() = false

    /** Shows [s]'s manoeuvre. */
    fun show(s: Step) {
        turn = Instructions.turn(s)
        exit = s.exit
        angle = Instructions.exitAngle(s)
        glyph = shapeOf(s)
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0) return
        c.save()
        c.translate((width - s) / 2f, (height - s) / 2f)
        if (glyph == 0 && turn == Turn.ROUNDABOUT) roundabout(c, s)
        else {
            val res = if (glyph != 0) glyph else glyphOf(turn)
            val d = if (res == drawnRes) drawn else context.getDrawable(res)?.mutate().also { drawn = it; drawnRes = res }
            d?.let {
                it.setBounds(0, 0, s.toInt(), s.toInt())
                it.setTint(color)
                it.draw(c)
            }
        }
        c.restore()
    }

    /**
     * Counter-clockwise, right-hand traffic: in from below, round to the exit, out the way it really leads. [exit] - 1 stubs stand for the exits passed, spread over the way round,
     * since the answer does not say where each arm is. In 64ths of the glyph, as the mockup's `roundIcon`.
     */
    private fun roundabout(c: Canvas, s: Float) {
        val u = s / 64f
        val cx = 32f * u
        val cy = 30f * u
        val r = 12f * u
        ring.color = color
        ring.alpha = 128
        ring.strokeWidth = 6f * u
        c.drawCircle(cx, cy, r, ring)
        // (degrees clockwise from up: a turn of a leaves at a, and a turn back at most 150 left, beside the way in and not over it)
        val out = if (!angle.isNaN()) maxOf(angle, -150f)
            else when (exit) { 1 -> 90f; 2 -> 0f; 3 -> -90f; 4 -> 180f; else -> 0f }
        stroke.color = color
        if (exit > 1) {
            stroke.alpha = 158
            stroke.strokeWidth = 5f * u
            for (k in 1 until exit) {
                val d = Math.toRadians((180f - (180f - out) * k / exit).toDouble())
                c.drawLine(cx + (r + 3f * u) * sin(d).toFloat(), cy - (r + 3f * u) * cos(d).toFloat(),
                    cx + (r + 10f * u) * sin(d).toFloat(), cy - (r + 10f * u) * cos(d).toFloat(), stroke)
            }
            stroke.alpha = 255
        }
        stroke.strokeWidth = 7f * u
        val a = Math.toRadians(out.toDouble())
        val ex = cx + (r + 18f * u) * sin(a).toFloat()
        val ey = cy - (r + 18f * u) * cos(a).toFloat()
        path.reset()
        path.moveTo(cx, 61f * u)
        path.lineTo(cx, cy + r)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        // (on screen 90 is down, and a negative sweep goes the way the ring is driven; the exit stands at out - 90)
        path.arcTo(oval, 90f, (out - 180f).let { if (it >= 0f) it - 360f else it })
        path.lineTo(ex, ey)
        // the open head
        val hl = 10.5f * u
        path.moveTo(ex + hl * sin(a + HEAD).toFloat(), ey - hl * cos(a + HEAD).toFloat())
        path.lineTo(ex, ey)
        path.lineTo(ex + hl * sin(a - HEAD).toFloat(), ey - hl * cos(a - HEAD).toFloat())
        c.drawPath(path, stroke)
    }

    companion object {
        private const val HEAD = 2.42

        fun glyphOf(t: Turn): Int = when (t) {
            Turn.STRAIGHT -> R.drawable.ic_go_straight
            Turn.SLIGHT_LEFT -> R.drawable.ic_slight_left
            Turn.LEFT -> R.drawable.ic_turn_left
            Turn.SHARP_LEFT -> R.drawable.ic_sharp_left
            // (right-hand traffic: a U-turn swings to the left)
            Turn.UTURN -> R.drawable.ic_uturn_left
            Turn.SLIGHT_RIGHT -> R.drawable.ic_slight_right
            Turn.RIGHT -> R.drawable.ic_turn_right
            Turn.SHARP_RIGHT -> R.drawable.ic_sharp_right
            Turn.ROUNDABOUT -> R.drawable.ic_round_straight
            Turn.ARRIVE -> R.drawable.ic_arrive
            Turn.REROUTE -> R.drawable.ic_reroute
        }

        /** A ramp, a fork or a merge has its own glyph, on the side its modifier says; with no side, the plain arrow. */
        fun shapeOf(s: Step): Int {
            val left = s.modifier.contains("left")
            val right = s.modifier.contains("right")
            if (!left && !right) return 0
            return when (s.type) {
                "on ramp", "off ramp" -> if (left) R.drawable.ic_ramp_left else R.drawable.ic_ramp_right
                "fork" -> if (left) R.drawable.ic_fork_left else R.drawable.ic_fork_right
                "merge" -> if (left) R.drawable.ic_merge_left else R.drawable.ic_merge_right
                else -> 0
            }
        }
    }
}
