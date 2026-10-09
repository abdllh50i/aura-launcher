package com.abdllh.aura.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Turn arrow for the guidance banner (drawn, so every manoeuvre and roundabout exit gets a crisp arrow). */
class ManeuverView(ctx: Context) : View(ctx) {
    // set on every GPS fix: redraw only when something changed
    var turn: Turn = Turn.STRAIGHT
        set(v) { if (v != field) { field = v; invalidate() } }
    var exit: Int = 0
        set(v) { if (v != field) { field = v; invalidate() } }
    var color: Int = 0xFFFFFFFF.toInt()
        set(v) { if (v != field) { field = v; invalidate() } }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val head = Path()
    private val oval = RectF()

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0) return
        c.save()
        c.translate((width - s) / 2f, (height - s) / 2f)
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = s * 0.11f
        when (turn) {
            Turn.ARRIVE -> arrive(c, s)
            Turn.ROUNDABOUT -> roundabout(c, s)
            Turn.UTURN -> uturn(c, s)
            else -> bend(c, s, when (turn) {
                Turn.SLIGHT_LEFT -> -45f
                Turn.LEFT -> -90f
                Turn.SHARP_LEFT -> -135f
                Turn.SLIGHT_RIGHT -> 45f
                Turn.RIGHT -> 90f
                Turn.SHARP_RIGHT -> 135f
                else -> 0f
            })
        }
        c.restore()
    }

    /** A stem going up, bending by [angle] degrees (negative = left), with an arrow head. */
    private fun bend(c: Canvas, s: Float, angle: Float) {
        val x0 = s * 0.5f - s * 0.12f * (angle / 135f)
        val y0 = s * 0.92f
        val yb = if (angle == 0f) s * 0.20f else s * 0.52f
        path.reset()
        path.moveTo(x0, y0)
        path.lineTo(x0, yb)
        var ex = x0
        var ey = yb
        if (angle != 0f) {
            val len = s * 0.30f
            val a = Math.toRadians((angle - 90).toDouble())
            ex = x0 + (len * cos(a)).toFloat()
            ey = yb + (len * sin(a)).toFloat()
            path.lineTo(ex, ey)
        }
        c.drawPath(path, stroke)
        arrowHead(c, ex, ey, angle, s)
    }

    private fun arrowHead(c: Canvas, x: Float, y: Float, angle: Float, s: Float) {
        val a = Math.toRadians((angle - 90).toDouble())
        val len = s * 0.20f
        val wide = s * 0.15f
        val tipX = x + (len * 0.55f * cos(a)).toFloat()
        val tipY = y + (len * 0.55f * sin(a)).toFloat()
        val bx = x - (len * 0.45f * cos(a)).toFloat()
        val by = y - (len * 0.45f * sin(a)).toFloat()
        val nx = (-sin(a)).toFloat() * wide
        val ny = cos(a).toFloat() * wide
        head.reset()
        head.moveTo(tipX, tipY)
        head.lineTo(bx + nx, by + ny)
        head.lineTo(bx - nx, by - ny)
        head.close()
        c.drawPath(head, fill)
    }

    private fun uturn(c: Canvas, s: Float) {
        val r = s * 0.17f
        val xr = s * 0.64f
        val xl = xr - 2 * r
        path.reset()
        path.moveTo(xr, s * 0.92f)
        path.lineTo(xr, s * 0.40f)
        oval.set(xl, s * 0.40f - r, xr, s * 0.40f + r)
        path.arcTo(oval, 0f, -180f)
        path.lineTo(xl, s * 0.62f)
        c.drawPath(path, stroke)
        arrowHead(c, xl, s * 0.66f, 180f, s)
    }

    /** Roundabout (counter-clockwise, right-hand traffic) with the exit drawn by its number. */
    private fun roundabout(c: Canvas, s: Float) {
        val cx = s * 0.5f
        val cy = s * 0.44f
        val r = s * 0.19f
        ring.color = color
        ring.alpha = 110
        ring.strokeWidth = stroke.strokeWidth
        c.drawCircle(cx, cy, r, ring)
        // entry from below, around counter-clockwise to the exit
        val exitAngle = when (exit) { 1 -> 0f; 2 -> -90f; 3 -> -180f; 4 -> 90f; else -> -90f } // degrees, 0 = right, -90 = up
        path.reset()
        path.moveTo(cx, s * 0.94f)
        path.lineTo(cx, cy + r)
        val sweep = ((exitAngle - 90f) - 360f) % 360f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        path.arcTo(oval, 90f, if (sweep == 0f) -360f else sweep)
        val a = Math.toRadians(exitAngle.toDouble())
        val ex = cx + ((r + s * 0.20f) * cos(a)).toFloat()
        val ey = cy + ((r + s * 0.20f) * sin(a)).toFloat()
        path.lineTo(ex, ey)
        c.drawPath(path, stroke)
        arrowHead(c, ex, ey, exitAngle + 90f, s)
    }

    private fun arrive(c: Canvas, s: Float) {
        val cx = s * 0.5f
        val r = s * 0.24f
        path.reset()
        path.addCircle(cx, s * 0.38f, r, Path.Direction.CW)
        c.drawCircle(cx, s * 0.38f, r * 0.36f, fill)
        c.drawPath(path, stroke)
        c.drawLine(cx, s * 0.38f + r, cx, s * 0.90f, stroke)
    }
}
