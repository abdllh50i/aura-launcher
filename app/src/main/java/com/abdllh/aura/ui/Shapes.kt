package com.abdllh.aura.ui

import android.os.Build
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
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

    /** Ends rounded to half the height, whatever height the view ends up with (a computed radius stops being one). */
    fun pill(fill: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable = rect(fill, Radius.PILL, stroke, strokeDp)

    /** Panel surface: flat fill; a hairline only on the dark theme (the light theme separates by tone and shadow). */
    fun card(radiusDp: Float = Radius.L): GradientDrawable =
        rect(Palette.card, radiusDp, if (Palette.dark) Palette.stroke else 0)

    /**
     * Normal and pressed states. The pressed tint comes on at once (the touch must show on its first frame) and lets
     * go over [Motion.SHORT], together with the press scale springing back, instead of vanishing in one frame.
     */
    fun pressable(normal: Drawable, pressed: Drawable): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
            setExitFadeDuration(Motion.SHORT.toInt())
        }

    fun clickableCard(radiusDp: Float = Radius.L): Drawable =
        pressable(card(radiusDp), rect(Palette.mix(Palette.card, Palette.text, 0.05f), radiusDp, if (Palette.dark) Palette.stroke else 0))

    /** Floating card on top of the map. */
    fun glass(radiusDp: Float): GradientDrawable = rect(Palette.glass, radiusDp, Palette.glassStroke)

    fun glassPressable(radiusDp: Float): Drawable =
        pressable(glass(radiusDp), rect(Palette.mix(Palette.glass, Palette.text, 0.07f), radiusDp, Palette.glassStroke))

    /**
     * The surface of the system-level overlays (volume, call, voice, [Snack] messages): one family over any screen,
     * a pill with an edge (the edge, not a shadow, is what separates it on the dark theme).
     */
    fun overlay(): GradientDrawable = pill(Palette.overlay, Palette.overlayStroke)

    /** Transparent button that only shows a tint while pressed. */
    fun ghost(radiusDp: Float): Drawable = pressable(rect(0x00000000, radiusDp), rect(Palette.press, radiusDp))

    fun ghostOval(): Drawable = pressable(oval(0x00000000), oval(Palette.press))

    /** Filled secondary button / chip. */
    fun tonal(radiusDp: Float): Drawable = pressable(rect(Palette.card2, radiusDp), rect(Palette.card3, radiusDp))

    fun tonalOval(): Drawable = pressable(oval(Palette.card2), oval(Palette.card3))

    fun accent(radiusDp: Float): Drawable = pressable(rect(Palette.accent, radiusDp), rect(Palette.accentPressed(), radiusDp))

    /** A selected chip, tile or row: the accent's tint, a little deeper while pressed. Its text and icons: [Palette.accentFg]. */
    fun accentSoft(radiusDp: Float): Drawable =
        pressable(rect(Palette.accentSoft(), radiusDp), rect(Palette.withAlpha(Palette.accent, 0.30f), radiusDp))

    /**
     * [d] drawn [visualDp] tall in the middle of a view that is [touchDp] tall: the control looks its size and the
     * finger gets a [Touch.MIN] target.
     */
    fun touchInset(d: Drawable, visualDp: Int, touchDp: Int = Touch.MIN): Drawable {
        val v = ((touchDp - visualDp) / 2).coerceAtLeast(0).dp
        return InsetDrawable(d, 0, v, 0, v)
    }
}

/**
 * A soft drop shadow at one of the [Elev] levels (rendered by the GPU from the view outline; the background drawable
 * defines the shape). On the dark theme only views that float over the map get one ([overMap]): elsewhere it cannot
 * be seen on the near-black screen, and it would still be tessellated on every outline change.
 */
fun View.lift(level: Float, overMap: Boolean = false) {
    elevation = if (Palette.dark && !overMap) 0f else level.dp
    if (Build.VERSION.SDK_INT >= 28) {
        outlineAmbientShadowColor = Palette.shadow
        outlineSpotShadowColor = Palette.shadow
    }
}

/** Soft drop shadow of any size on both themes (the shadow language before 1.12). */
@Deprecated("Use lift() with an Elev level: it leaves out the shadows the dark theme cannot show.", ReplaceWith("lift(Elev.FLOAT)"))
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
