package com.abdllh.aura.ui

import android.os.Build
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import com.abdllh.aura.util.dp

/** Programmatic drawables built from the current theme tokens (keeps theme/accent-aware shapes out of XML). */
object Shapes {
    fun rect(fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp.dp
            setColor(fill)
            if (stroke != 0) setStroke(strokeDp.dp.coerceAtLeast(1), stroke)
        }

    /** Rectangle with only the top corners rounded (bottom sheets). */
    fun topRounded(fill: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val r = radiusDp.dp
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(fill)
        }

    fun oval(fill: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (stroke != 0) setStroke(strokeDp.dp.coerceAtLeast(1), stroke)
        }

    /** Panel surface: flat fill; a hairline only on the dark theme (the light theme separates by tone and shadow). */
    fun card(radiusDp: Float = 22f): GradientDrawable =
        rect(Palette.card, radiusDp, if (Palette.dark) Palette.stroke else 0)

    fun pressable(normal: Drawable, pressed: Drawable): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }

    fun clickableCard(radiusDp: Float = 22f): Drawable =
        pressable(card(radiusDp), rect(Palette.mix(Palette.card, Palette.text, 0.05f), radiusDp, if (Palette.dark) Palette.stroke else 0))

    /** Floating card on top of the map. */
    fun glass(radiusDp: Float): GradientDrawable =
        rect(Palette.glass, radiusDp, if (Palette.dark) 0x12FFFFFF else 0)

    fun glassPressable(radiusDp: Float): Drawable =
        pressable(glass(radiusDp), rect(Palette.mix(Palette.glass, Palette.text, 0.07f), radiusDp, if (Palette.dark) 0x12FFFFFF else 0))

    /** Transparent button that only shows a tint while pressed. */
    fun ghost(radiusDp: Float): Drawable = pressable(rect(0x00000000, radiusDp), rect(Palette.press, radiusDp))

    fun ghostOval(): Drawable = pressable(oval(0x00000000), oval(Palette.press))

    /** Filled secondary button / chip. */
    fun tonal(radiusDp: Float): Drawable = pressable(rect(Palette.card2, radiusDp), rect(Palette.card3, radiusDp))

    fun tonalOval(): Drawable = pressable(oval(Palette.card2), oval(Palette.card3))

    fun accent(radiusDp: Float): Drawable = pressable(rect(Palette.accent, radiusDp), rect(Palette.accentPressed(), radiusDp))
}

/** Gentle "squish" feedback on press — cheap (view property animation, no layout). */
fun View.pressScale(scale: Float = 0.97f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(scale).scaleY(scale).setDuration(90)
                    .setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(OvershootInterpolator(2.6f)).start()
        }
        false
    }
}

/** Soft drop shadow (rendered by the GPU from the view outline; the background drawable defines the shape). */
fun View.elevate(dp: Float) {
    elevation = dp.dp
    if (Build.VERSION.SDK_INT >= 28) {
        outlineAmbientShadowColor = Palette.shadow
        outlineSpotShadowColor = Palette.shadow
    }
}

fun ImageView.icon(res: Int, tint: Int = Palette.text) {
    setImageResource(res)
    setColorFilter(tint)
}
