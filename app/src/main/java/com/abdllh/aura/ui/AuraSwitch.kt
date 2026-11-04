package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.abdllh.aura.R

/**
 * The switch: a track 66 x 38 (`track` off, `accent` on) and the white thumb 32 with its baked shadow, at the far end when on.
 * The thumb travels on a spring (with a hair of overshoot, as the press and the segmented thumb settle) and, while the finger is
 * on it, stretches towards where it will go: the press shows in the switch itself.
 * The view is the track grown by [OUTSET] on every side, or the thumb's shadow would be cut at the view's edge: place it by the
 * track with [at]. A row is the target ([com.abdllh.aura.settings.switchRow]) and passes its pressed state down.
 */
class AuraSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Recolours {

    var checked = false
        private set

    var onToggle: ((Boolean) -> Unit)? = null

    private var t = 0f // 0 off .. 1 on (a little past either end while it settles)
    private var stretch = 0f // 0 at rest .. 1 pressed
    private var towardOn = true // where the stretch reaches: pressed, the thumb heads for the other side
    private val move = Spring(0f, 700f, 0.74f, 0.002f) { t = it; invalidate() }
    private val press = Spring(0f, 900f, 0.9f, 0.002f) { stretch = it; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val thumb: Bitmap? = Surfaces.res(context, R.drawable.thumb_32)
    private val src = Rect()
    private val dst = RectF()

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

    override fun onLook() = invalidate()

    // The stretch follows the pressed state rather than the touch: Android delays it inside a scroller (a scroll that
    // starts on the switch does not make it bulge and snap back) and clears it on a cancel or a slide-off. Where it
    // heads is taken at the press, since the click flips [checked] while the stretch is still going down.
    override fun setPressed(p: Boolean) {
        if (p != isPressed) {
            if (p) towardOn = !checked
            press.animateTo(if (p) 1f else 0f)
        }
        super.setPressed(p)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    override fun onDetachedFromWindow() {
        // (a spring left running would keep posting frames for a view nobody sees)
        move.snapTo(if (checked) 1f else 0f)
        press.snapTo(0f)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(TRACK_W + 2 * OUTSET, TRACK_H + 2 * OUTSET)
    }

    override fun onDraw(c: Canvas) {
        val k = t.coerceIn(0f, 1f)
        val o = OUTSET.toFloat()
        rect.set(o, o, o + TRACK_W, o + TRACK_H)
        paint.color = Palette.mix(Palette.track, Palette.accent, k)
        c.drawRoundRect(rect, TRACK_H / 2f, TRACK_H / 2f, paint)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val travel = TRACK_W - 2 * PAD - THUMB.toFloat()
        // the thumb (its start edge at x, in the off-to-on direction) grows by a fifth towards where it goes
        val grow = THUMB * 0.2f * stretch
        val along = travel * t
        val lo = along - if (towardOn) 0f else grow
        val hi = along + THUMB + if (towardOn) grow else 0f
        val l = o + if (rtl) TRACK_W - PAD - hi else PAD + lo
        val r = o + if (rtl) TRACK_W - PAD - lo else PAD + hi
        val top = o + (TRACK_H - THUMB) / 2f
        val b = thumb ?: return
        // the baked disc in three parts while it stretches, whole at rest
        val m = THUMB_MARGIN
        if (r - l <= THUMB + 0.01f) {
            c.drawBitmap(b, l - m, top - m, bitmapPaint)
        } else {
            val half = b.width / 2
            src.set(0, 0, half, b.height)
            dst.set(l - m, top - m, l - m + half, top - m + b.height)
            c.drawBitmap(b, src, dst, bitmapPaint)
            src.set(half, 0, half + 1, b.height)
            dst.set(l - m + half, top - m, r + m - half, top - m + b.height)
            c.drawBitmap(b, src, dst, bitmapPaint)
            src.set(half, 0, b.width, b.height)
            dst.set(r + m - half, top - m, r + m, top - m + b.height)
            c.drawBitmap(b, src, dst, bitmapPaint)
        }
    }

    companion object {
        const val TRACK_W = 66
        const val TRACK_H = 38
        private const val THUMB = 32
        private const val PAD = 3
        /** `thumb-32` is 52 px: the disc and 10 of shadow all round. */
        private const val THUMB_MARGIN = 10
        const val OUTSET = 10

        /** The view's rect in a FrameLayout from the track's, as the layout tables give it. */
        fun at(x: Int, y: Int) = at(x - OUTSET, y - OUTSET, TRACK_W + 2 * OUTSET, TRACK_H + 2 * OUTSET)
    }
}
