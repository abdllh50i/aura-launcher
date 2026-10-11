package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.min

/**
 * Play / pause that morphs: the triangle's two halves become the two bars and back (on a spring), so a tap shows its
 * effect at once and smoothly. Drawn in a 24-unit box like the other icons.
 */
class PlayPauseView(ctx: Context) : View(ctx) {
    var color = 0xFFFFFFFF.toInt()
        set(v) { if (v != field) { field = v; paint.color = v; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val path = Path()
    private var t = 0f // 0 = the play triangle, 1 = the pause bars
    private val spring = Spring(0f, 640f, 0.9f) { t = it; invalidate() }
    var playing = false
        private set

    fun setPlaying(p: Boolean, animate: Boolean = true) {
        val to = if (p) 1f else 0f
        if (p == playing && spring.target == to) return
        playing = p
        if (animate && isAttachedToWindow && isShown) spring.animateTo(to) else spring.snapTo(to)
    }

    override fun onDetachedFromWindow() {
        spring.snapTo(if (playing) 1f else 0f)
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        fun x(u: Float) = ox + u / 24f * s
        fun y(u: Float) = oy + u / 24f * s
        path.reset()
        // each shape: four corners, the triangle's half -> its bar
        for (q in QUADS) {
            for (k in 0 until 4) {
                val px = q[k * 4] + (q[k * 4 + 2] - q[k * 4]) * t
                val py = q[k * 4 + 1] + (q[k * 4 + 3] - q[k * 4 + 1]) * t
                if (k == 0) path.moveTo(x(px), y(py)) else path.lineTo(x(px), y(py))
            }
            path.close()
        }
        c.drawPath(path, paint)
    }

    private companion object {
        // per corner: play x, play y, pause x, pause y (the triangle 8,5 / 19,12 / 8,19 cut at x 13.5; bars 6-10, 14-18)
        val QUADS = arrayOf(
            floatArrayOf(8f, 5f, 6f, 5f, 13.5f, 8.5f, 10f, 5f, 13.5f, 15.5f, 10f, 19f, 8f, 19f, 6f, 19f),
            floatArrayOf(13.5f, 8.5f, 14f, 5f, 19f, 12f, 18f, 5f, 19f, 12f, 18f, 19f, 13.5f, 15.5f, 14f, 19f)
        )
    }
}
