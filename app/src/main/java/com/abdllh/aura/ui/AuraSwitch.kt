package com.abdllh.aura.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.abdllh.aura.util.dp

/** Pill switch with an animated thumb. */
class AuraSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var checked = false
        private set

    var onToggle: ((Boolean) -> Unit)? = null

    private var t = 0f // 0 off .. 1 on
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        setOnClickListener { setChecked(!checked, true, true) }
        pressScale(0.94f)
    }

    fun setChecked(v: Boolean, animate: Boolean = false, notify: Boolean = false) {
        if (v == checked && !notify) {
            if (!animate) { t = if (v) 1f else 0f; invalidate() }
            return
        }
        checked = v
        anim?.cancel()
        val target = if (v) 1f else 0f
        if (animate && isAttachedToWindow) {
            anim = ValueAnimator.ofFloat(t, target).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            t = target
            invalidate()
        }
        if (notify) onToggle?.invoke(v)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(66.dp, 38.dp)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        paint.color = Palette.mix(Palette.card3, Palette.accent, t)
        c.drawRoundRect(rect, h / 2, h / 2, paint)
        val pad = 3.dp.toFloat()
        val d = h - pad * 2
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val travel = w - pad * 2 - d
        val x = if (rtl) (w - pad - d) - travel * t else pad + travel * t
        if (!Palette.dark) {
            paint.color = 0x26000000
            c.drawCircle(x + d / 2, h / 2 + 1.dp, d / 2, paint)
        }
        paint.color = if (t > 0.5f || !Palette.dark) 0xFFFFFFFF.toInt() else Palette.text2
        c.drawCircle(x + d / 2, h / 2, d / 2, paint)
    }
}
