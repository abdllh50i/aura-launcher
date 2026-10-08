package com.abdllh.aura.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.abdllh.aura.util.dp

/**
 * Chunky touch-friendly slider: a rounded bar that fills from the start edge, with an icon inside.
 * Large enough to hit while driving; mirrors automatically in RTL.
 */
class AuraSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var max = 100
        set(v) { field = v.coerceAtLeast(1); invalidate() }

    var progress = 0
        private set

    /** (value, fromUser) */
    var onChange: ((Int, Boolean) -> Unit)? = null
    var onStop: ((Int) -> Unit)? = null

    private var shown = 0f // 0..1 fraction currently drawn
    private var anim: ValueAnimator? = null
    private var icon: Drawable? = null
    private var dragging = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()

    init {
        isClickable = true
    }

    fun setIcon(res: Int) {
        icon = context.getDrawable(res)?.mutate()
        invalidate()
    }

    /** Programmatic update (e.g. system volume changed) — animates unless the user is dragging. */
    fun setProgress(value: Int, animate: Boolean = true) {
        val v = value.coerceIn(0, max)
        progress = v
        if (dragging) return
        val target = v.toFloat() / max
        anim?.cancel()
        if (!animate || !isAttachedToWindow) {
            shown = target
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 48.dp
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    private val rtl: Boolean get() = layoutDirection == LAYOUT_DIRECTION_RTL

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        trackPaint.color = Palette.card3
        c.drawRoundRect(rect, r, r, trackPaint)

        val fw = w * shown
        if (fw > 0.5f) {
            clip.reset()
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            fillPaint.color = Palette.accent
            if (rtl) c.drawRect(w - fw, 0f, w, h, fillPaint) else c.drawRect(0f, 0f, fw, h, fillPaint)
            c.restore()
        }

        icon?.let { d ->
            val s = (h * 0.5f).toInt()
            val pad = ((h - s) / 2f).toInt()
            val left = if (rtl) (w - pad - s).toInt() else pad
            d.setBounds(left, pad, left + s, pad + s)
            val iconCenter = left + s / 2f
            val covered = if (rtl) iconCenter >= w - fw else iconCenter <= fw
            d.setTint(if (covered) Palette.onColor(Palette.accent) else Palette.text2)
            d.draw(c)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                anim?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.x, true)
                return true
            }
            MotionEvent.ACTION_MOVE -> update(e.x, true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    onStop?.invoke(progress)
                }
            }
        }
        return true
    }

    private fun update(x: Float, fromUser: Boolean) {
        val w = width.toFloat().coerceAtLeast(1f)
        var f = (x / w).coerceIn(0f, 1f)
        if (rtl) f = 1f - f
        shown = f
        val v = Math.round(f * max)
        if (v != progress) {
            progress = v
            onChange?.invoke(v, fromUser)
        }
        invalidate()
    }
}
