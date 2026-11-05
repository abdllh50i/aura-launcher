package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.abdllh.aura.R
import kotlin.math.abs

/**
 * The slider: a track 7 high, filled in `accent` from the reading edge, the white knob 33 with its baked shadow on the value.
 * A drag moves the value by how far the finger goes, from where it was: a touch alone changes nothing, so a hand brushing the far
 * end of a bar in a moving car no longer sets it near the top. A clean tap still sets the value where it lands (gliding there). A
 * swipe that starts up or down is not taken: the page or sheet around it scrolls or closes as it would anywhere else.
 *
 * The view is the track grown by [INSET] at each end and 60 high (the hit is at least 48), so the knob and its shadow are never
 * cut, also at 0 and at the end: place it by the track with [at]. Any icons at its ends are views of their own beside it.
 */
class AuraSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Recolours {

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
    private val glide = Spring(0f, Tokens2.SPRING_slider[0], Tokens2.SPRING_slider[1], 0.0005f) { shown = it; invalidate() }
    private var tracking = false // a finger is down and may still become a drag
    private var dragging = false // it did
    private var downX = 0f
    private var downY = 0f
    private var startShown = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val knob: Bitmap? = Surfaces.res(context, R.drawable.knob_33)

    init {
        isClickable = true
    }

    /** The icons of 1.x's bar lie outside it now; this stays for old screens. */
    @Suppress("UNUSED_PARAMETER")
    fun setIcon(res: Int) {}

    /** Programmatic update (e.g. system volume changed): glides there unless the user is dragging. */
    fun setProgress(value: Int, animate: Boolean = true) {
        val v = value.coerceIn(0, max)
        progress = v
        if (dragging) return
        val target = v.toFloat() / max
        if (!animate || !isAttachedToWindow) glide.snapTo(target) else glide.animateTo(target)
    }

    override fun onLook() = invalidate()

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), HEIGHT)
    }

    override fun onDetachedFromWindow() {
        glide.snapTo(glide.target)
        super.onDetachedFromWindow()
    }

    private val rtl: Boolean get() = layoutDirection == LAYOUT_DIRECTION_RTL

    private val trackW: Int get() = (width - 2 * INSET).coerceAtLeast(1)

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val h = TRACK_H / 2f
        val l = INSET.toFloat()
        val r = (width - INSET).toFloat()
        rect.set(l, cy - h, r, cy + h)
        paint.color = Palette.track
        c.drawRoundRect(rect, h, h, paint)
        val fw = trackW * shown.coerceIn(0f, 1f)
        val at = if (rtl) r - fw else l + fw
        if (fw > 0.5f) {
            if (rtl) rect.set(at, cy - h, r, cy + h) else rect.set(l, cy - h, at, cy + h)
            paint.color = Palette.accent
            c.drawRoundRect(rect, h, h, paint)
        }
        val b = knob ?: return
        c.drawBitmap(b, at - b.width / 2f, cy - b.height / 2f, bitmapPaint)
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
                if (dragging) set(startShown + (if (rtl) downX - e.x else e.x - downX) / trackW)
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    onStop?.invoke(progress)
                } else if (tracking) {
                    // a clean tap: there, gliding
                    var f = ((e.x - INSET) / trackW).coerceIn(0f, 1f)
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

    companion object {
        /** The knob's half and its baked shadow's margin: 53 / 2. */
        const val INSET = 27
        const val HEIGHT = 60
        private const val TRACK_H = 7

        /** The view's rect in a FrameLayout from the mockup's 48 high rect of width [w] at ([x], [y]). */
        fun at(x: Int, y: Int, w: Int) = at(x - INSET, y - (HEIGHT - 48) / 2, w + 2 * INSET, HEIGHT)
    }
}
