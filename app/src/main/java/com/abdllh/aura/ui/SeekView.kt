package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View

/**
 * The song's place (SPEC.md section 5, 340 x 30): the two times in `musicTime` at the ends, a track 5 high with the knob 17 between
 * them. Left to right in every language, like the times. Tap or drag seeks; while held the bar thickens and the knob grows, on a
 * spring. Times are drawn here, not in labels of their own: one view, and a tick redraws it and lays out nothing. Plex's digits are
 * all as wide, so the track does not breathe as they change.
 */
class SeekView(ctx: Context) : View(ctx), Recolours {
    var progress = 0f
        set(v) { val n = v.coerceIn(0f, 1f); if (n != field) { field = n; invalidate() } }
    /** The played part and the knob. */
    var color: (() -> Int) = { Palette.ink }
        set(v) { field = v; invalidate() }
    var onSeek: ((Float) -> Unit)? = null
    var enabledSeek = true
        set(v) { if (v != field) { field = v; invalidate() } }
    var dragging = false
        private set
    private var now = ""
    private var total = ""
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = Type.musicTime.sp
        typeface = Fonts.of(Type.musicTime)
        Fonts.tune(this)
    }
    private val r = RectF()
    /** 0 resting, 1 held. */
    private var grab = 0f
    private val grabSpring = Spring(0f, 700f, 0.85f) { grab = it; invalidate() }

    init { layoutDirection = LAYOUT_DIRECTION_LTR }

    fun setTimes(now: String, total: String) {
        if (now == this.now && total == this.total) return
        this.now = now
        this.total = total
        invalidate()
    }

    override fun onLook() = invalidate()

    private fun left() = if (now.isEmpty()) KNOB_HALF else tp.measureText(now) + GAP

    private fun right() = width - if (total.isEmpty()) KNOB_HALF else tp.measureText(total) + GAP

    override fun onDraw(c: Canvas) {
        val y = height / 2f
        val h = TRACK_H + 3f * grab
        val a = left()
        val b = right()
        val base = y + 0.335f * tp.textSize
        tp.color = Palette.ink2
        if (now.isNotEmpty()) c.drawText(now, 0f, base, tp)
        if (total.isNotEmpty()) c.drawText(total, width - tp.measureText(total), base, tp)
        r.set(a, y - h / 2, b, y + h / 2)
        p.color = Palette.track
        c.drawRoundRect(r, h / 2, h / 2, p)
        val at = a + (b - a) * progress
        p.color = color()
        if (progress > 0f) {
            r.set(a, y - h / 2, at, y + h / 2)
            c.drawRoundRect(r, h / 2, h / 2, p)
        }
        if (enabledSeek) c.drawCircle(at, y, KNOB_HALF + 3f * grab, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!enabledSeek) return false
        val a = left()
        val b = right()
        val f = ((e.x - a) / (b - a).coerceAtLeast(1f)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); progress = f; grabSpring.animateTo(1f) }
            MotionEvent.ACTION_MOVE -> progress = f
            MotionEvent.ACTION_UP -> { dragging = false; progress = f; grabSpring.animateTo(0f); onSeek?.invoke(f) }
            MotionEvent.ACTION_CANCEL -> { dragging = false; grabSpring.animateTo(0f) }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        grabSpring.snapTo(0f)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TRACK_H = 5f
        const val KNOB_HALF = 8.5f
        const val GAP = 10f
    }
}
