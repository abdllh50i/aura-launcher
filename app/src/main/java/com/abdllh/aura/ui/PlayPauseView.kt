package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.min

/**
 * Play / pause that morphs: the triangle's two halves become the two bars and back (on spring 640/0.9), so a tap shows its effect
 * at once and smoothly. Drawn in a 24-unit box like the other icons and on their geometry (icons/play.svg, pause.svg), with their
 * rounded corners from a thin round-joined stroke, which costs no path work. [disc]: the big transport button, a disc of that
 * colour under the glyph. Left to right in every language.
 */
class PlayPauseView(ctx: Context) : View(ctx), Recolours {
    /** The glyph's colour token; [color] for one that is not a token (the cover's own). */
    var token: () -> Int = { Palette.ink }
        set(v) { field = v; recolour() }
    var color: Int
        get() = paint.color
        set(v) { if (v != paint.color) { token = { v } } }
    /** A disc under the glyph, filling the view: the big button's `ink`. */
    var disc: (() -> Int)? = null
        set(v) { field = v; invalidate() }
    /** The glyph's size in the view, in px; the view's smaller side if 0. */
    var glyphPx = 0
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var t = 0f // 0 = the play triangle, 1 = the pause bars
    private val spring = Spring(0f, Tokens2.SPRING_playPause[0], Tokens2.SPRING_playPause[1]) { t = it; invalidate() }
    var playing = false
        private set

    init {
        layoutDirection = LAYOUT_DIRECTION_LTR
        recolour()
    }

    private fun recolour() {
        paint.color = token()
        invalidate()
    }

    override fun onLook() = recolour()

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

    override fun hasOverlappingRendering() = false

    override fun onDraw(c: Canvas) {
        disc?.let {
            discPaint.color = it()
            c.drawCircle(width / 2f, height / 2f, min(width, height) / 2f, discPaint)
        }
        val s = if (glyphPx > 0) glyphPx.toFloat() else min(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        val u = s / 24f
        fun x(v: Float) = ox + v * u
        fun y(v: Float) = oy + v * u
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
        paint.strokeWidth = ROUND * 2f * u
        c.drawPath(path, paint)
    }

    private companion object {
        /** The corners' radius in icon units. Each outline below stands [ROUND] inside the icon's. */
        const val ROUND = 1.2f

        // per corner: play x, play y, pause x, pause y (icons/play.svg's triangle and pause.svg's bars 6.2-10.5, 13.5-17.8, y 4.4-19.6,
        // each inset by ROUND; the triangle is cut at x 13.5)
        val QUADS = arrayOf(
            floatArrayOf(9.2f, 5.05f, 7.4f, 5.6f, 13.5f, 7.93f, 9.3f, 5.6f, 13.5f, 16.07f, 9.3f, 18.4f, 9.2f, 18.95f, 7.4f, 18.4f),
            floatArrayOf(13.5f, 7.93f, 14.7f, 5.6f, 19.58f, 12f, 16.6f, 5.6f, 19.58f, 12f, 16.6f, 18.4f, 13.5f, 16.07f, 14.7f, 18.4f)
        )
    }
}
