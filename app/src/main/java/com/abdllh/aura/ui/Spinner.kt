package com.abdllh.aura.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Indeterminate spinner, 24 or 36: a ring in `track` with a quarter of it in `ink2`. The ring is drawn once and the view turns, a
 * transform only. It turns only while it can be seen: an animator does not stop for a stopped activity on Android 10, so a check
 * left running behind Maps went on waking the main thread 60 times a second. Never on a driving screen: nothing spins there.
 */
class Spinner(ctx: Context, private val sizeDp: Int = 24) : View(ctx), Recolours {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    private var anim: ObjectAnimator? = null

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(sizeDp, sizeDp)

    override fun onLook() = invalidate()

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) { anim?.cancel(); anim = null; return }
        if (anim != null) return
        anim = ObjectAnimator.ofFloat(this, ROTATION, rotation, rotation + 360f).apply {
            duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val s = if (sizeDp >= 36) 4f else 3f
        p.strokeWidth = s
        r.set(s / 2, s / 2, width - s / 2, height - s / 2)
        p.color = Palette.track
        c.drawArc(r, 0f, 360f, false, p)
        p.color = Palette.ink2
        c.drawArc(r, -90f, 90f, false, p)
    }
}

/**
 * Determinate/indeterminate progress bar, filled from the start side (the right in Arabic). A new value glides there
 * on a spring, which keeps its speed when the next value comes before it arrived (a download reports every 400 ms);
 * a bar made for a value starts at it, not at empty. Nothing runs at rest: the spring stops where it arrives, and the
 * indeterminate sweep turns only while it is to be seen.
 */
class ProgressBar(ctx: Context) : View(ctx), Recolours {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var indeterminate = false
    private var seen = false
    private var phase = 0f
    private var sweep: ValueAnimator? = null
    private val glide = Spring(0f, 120f, 1f, 0.0005f) { invalidate() }

    fun set(fraction: Float) {
        indeterminate = false
        sweeping()
        val f = fraction.coerceIn(0f, 1f)
        if (seen && isAttachedToWindow) glide.animateTo(f) else glide.snapTo(f)
        invalidate()
    }

    fun setIndeterminate() {
        indeterminate = true
        sweeping()
        invalidate()
    }

    override fun onLook() = invalidate()

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        seen = isVisible
        if (!isVisible) glide.snapTo(glide.target)
        sweeping()
    }

    private fun sweeping() {
        val want = indeterminate && seen
        if (!want) { sweep?.cancel(); sweep = null; return }
        if (sweep != null) return
        sweep = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(getDefaultSize(0, w), HEIGHT)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        r.set(0f, 0f, w, h)
        p.color = Palette.track
        c.drawRoundRect(r, h / 2, h / 2, p)
        p.color = Palette.accent
        if (indeterminate) {
            val bw = w * 0.32f
            val x = -bw + (w + bw) * phase
            val from = maxOf(0f, x)
            val to = minOf(w, x + bw)
            if (to <= from) return
            if (rtl) r.set(w - to, 0f, w - from, h) else r.set(from, 0f, to, h)
        } else {
            val shown = glide.value
            if (shown <= 0f) return
            val len = maxOf(h, w * shown)
            if (rtl) r.set(w - len, 0f, w, h) else r.set(0f, 0f, len, h)
        }
        c.drawRoundRect(r, h / 2, h / 2, p)
    }

    private companion object {
        const val HEIGHT = 6
    }
}
