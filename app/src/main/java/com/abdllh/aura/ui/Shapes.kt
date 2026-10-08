package com.abdllh.aura.ui

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import com.abdllh.aura.util.dp

/** Programmatic drawables (keeps accent-aware shapes out of XML). */
object Shapes {
    fun rect(fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp.dp
            setColor(fill)
            if (stroke != 0) setStroke(strokeDp.dp.coerceAtLeast(1), stroke)
        }

    fun oval(fill: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (stroke != 0) setStroke(strokeDp.dp.coerceAtLeast(1), stroke)
        }

    /** Dark glassy card: soft vertical gradient + hairline border. */
    fun card(radiusDp: Float = 24f, top: Int = 0xFF171C23.toInt(), bottom: Int = 0xFF11151A.toInt()): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp.dp
            setStroke(1.dp.coerceAtLeast(1), Palette.stroke)
        }

    fun pressable(normal: Drawable, pressed: Drawable): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }

    fun clickableCard(radiusDp: Float = 24f): Drawable =
        pressable(
            card(radiusDp),
            card(radiusDp, 0xFF1D232B.toInt(), 0xFF171C22.toInt())
        )
}

/** Gentle "squish" feedback on press — cheap (view property animation, no layout). */
fun View.pressScale(scale: Float = 0.97f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(scale).scaleY(scale).setDuration(80)
                    .setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(200)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()
        }
        false
    }
}

fun ImageView.icon(res: Int, tint: Int = Palette.text) {
    setImageResource(res)
    setColorFilter(tint)
}
