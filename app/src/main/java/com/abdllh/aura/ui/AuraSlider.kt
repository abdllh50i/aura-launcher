package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.abdllh.aura.util.dp
import kotlin.math.abs

/**
 * Chunky touch-friendly slider: a rounded bar that fills from the start edge, with an icon inside.
 * Large enough to hit while driving; mirrors automatically in RTL.
 * A drag moves the value by how far the finger goes, from where it was: a touch alone changes nothing, so a hand
 * brushing the far end of the volume bar in a moving car no longer sets it near the top. A clean tap still sets the
 * value where it lands (gliding there). A swipe that starts up or down is not taken: the page or sheet around it
 * scrolls or closes as it would anywhere else.
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
    // (a spring, not an animator: steering-wheel volume steps several times a second keep their speed from one to
    // the next instead of each starting again from a standstill)
    private val glide = Spring(0f, 380f, 1f, 0.0005f) { shown = it; invalidate() }
    private var icon: Drawable? = null
    private var iconCovered: Boolean? = null
    private var tracking = false // a finger is down and may still become a drag
    private var dragging = false // it did
    private var downX = 0f
    private var downY = 0f
    private var startShown = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
    }

    fun setIcon(res: Int) {
        icon = context.getDrawable(res)?.mutate()
        iconCovered = null
        invalidate()
    }

    /** Programmatic update (e.g. system volume changed) — glides there unless the user is dragging. */
    fun setProgress(value: Int, animate: Boolean = true) {
        val v = value.coerceIn(0, max)
        progress = v
        if (dragging) return
        val target = v.toFloat() / max
        if (!animate || !isAttachedToWindow) glide.snapTo(target) else glide.animateTo(target)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else Touch.MIN.dp
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    override fun onDetachedFromWindow() {
        glide.snapTo(glide.target)
        super.onDetachedFromWindow()
    }

    private val rtl: Boolean get() = layoutDirection == LAYOUT_DIRECTION_RTL

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        trackPaint.color = Palette.card3
        c.drawRoundRect(rect, r, r, trackPaint)

        val fw = w * shown.coerceIn(0f, 1f)
        if (fw > 0.5f) {
            // the whole track's shape cut by a plain rectangle: rounded at the track's end, square where the fill
            // stops; a rectangular clip is a scissor, where the rounded clip path it replaces built a new
            // anti-aliased mask on every frame of a drag
            c.save()
            if (rtl) c.clipRect(w - fw, 0f, w, h) else c.clipRect(0f, 0f, fw, h)
            fillPaint.color = Palette.accent
            c.drawRoundRect(rect, r, r, fillPaint)
            c.restore()
        }

        icon?.let { d ->
            val s = (h * 0.5f).toInt()
            val pad = ((h - s) / 2f).toInt()
            val left = if (rtl) (w - pad - s).toInt() else pad
            d.setBounds(left, pad, left + s, pad + s)
            val iconCenter = left + s / 2f
            val covered = if (rtl) iconCenter >= w - fw else iconCenter <= fw
            if (covered != iconCovered) {
                iconCovered = covered
                d.setTint(if (covered) Palette.onColor(Palette.accent) else Palette.text2)
            }
            d.draw(c)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                tracking = true
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dx = e.x - downX
                val dy = e.y - downY
                if (!dragging) {
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        glide.cancel()
                        startShown = shown
                        downX = e.x // (measured from here: the slop crossed is no jump)
                    } else if (abs(dy) > slop) {
                        tracking = false // up or down first: a scroll, the parent takes it
                        return false
                    }
                }
                if (dragging) set(startShown + (if (rtl) downX - e.x else e.x - downX) / width.coerceAtLeast(1))
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    onStop?.invoke(progress)
                } else if (tracking) {
                    // a clean tap: there, gliding
                    var f = (e.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    if (rtl) f = 1f - f
                    val v = Math.round(f * max)
                    glide.animateTo(v.toFloat() / max)
                    if (v != progress) { progress = v; onChange?.invoke(v, true) }
                    onStop?.invoke(v)
                }
                tracking = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) { dragging = false; onStop?.invoke(progress) }
                tracking = false
            }
        }
        return true
    }

    private fun set(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        glide.snapTo(f)
        val v = Math.round(f * max)
        if (v != progress) {
            progress = v
            onChange?.invoke(v, true)
        }
    }
}
