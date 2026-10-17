package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.abdllh.aura.util.dp

/**
 * Pill switch. The thumb travels on a spring (with a hair of overshoot, as the press and the segmented indicator
 * settle) and, while the finger is on it, stretches towards where it will go: the press shows in the switch itself.
 */
class AuraSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var checked = false
        private set

    var onToggle: ((Boolean) -> Unit)? = null

    private var t = 0f // 0 off .. 1 on (a little past either end while it settles)
    private var stretch = 0f // 0 at rest .. 1 pressed
    private val move = Spring(0f, 700f, 0.74f, 0.002f) { t = it; invalidate() }
    private val press = Spring(0f, 900f, 0.9f, 0.002f) { stretch = it; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        setOnClickListener { setChecked(!checked, true, true) }
    }

    fun setChecked(v: Boolean, animate: Boolean = false, notify: Boolean = false) {
        if (v == checked && !notify) {
            if (!animate) move.snapTo(if (v) 1f else 0f)
            return
        }
        checked = v
        val target = if (v) 1f else 0f
        if (animate && isAttachedToWindow) move.animateTo(target) else move.snapTo(target)
        if (notify) onToggle?.invoke(v)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> press.animateTo(1f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press.animateTo(0f)
        }
        return super.onTouchEvent(e)
    }

    override fun onDetachedFromWindow() {
        // (a spring left running would keep posting frames for a view nobody sees)
        move.snapTo(if (checked) 1f else 0f)
        press.snapTo(0f)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(66.dp, 38.dp)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val k = t.coerceIn(0f, 1f)
        rect.set(0f, 0f, w, h)
        paint.color = Palette.mix(Palette.control, Palette.accent, k)
        c.drawRoundRect(rect, h / 2, h / 2, paint)
        val pad = 3.dp.toFloat()
        val d = h - pad * 2
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val travel = w - pad * 2 - d
        // the thumb (its start edge at x, in the off-to-on direction) grows by a fifth towards where it goes
        val grow = d * 0.2f * stretch
        val towardOn = !checked // pressed now, it heads for the other side
        val along = travel * t
        val lo = along - if (towardOn) 0f else grow
        val hi = along + d + if (towardOn) grow else 0f
        val l = if (rtl) w - pad - hi else pad + lo
        val r = if (rtl) w - pad - lo else pad + hi
        val cy = h / 2
        if (!Palette.dark) {
            paint.color = 0x26000000
            rect.set(l, cy - d / 2 + 1.dp, r, cy + d / 2 + 1.dp)
            c.drawRoundRect(rect, d / 2, d / 2, paint)
        }
        paint.color = if (k > 0.5f || !Palette.dark) 0xFFFFFFFF.toInt() else Palette.text2
        rect.set(l, cy - d / 2, r, cy + d / 2)
        c.drawRoundRect(rect, d / 2, d / 2, paint)
    }
}
