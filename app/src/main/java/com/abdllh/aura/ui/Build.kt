package com.abdllh.aura.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.util.dp

/** Small view-building helpers shared by the home screen and the settings pages. */

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

fun flp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY): FrameLayout.LayoutParams = FrameLayout.LayoutParams(w, h, gravity)

fun Context.label(
    sizeSp: Float,
    color: Int = Palette.text,
    weight: Int = Fonts.REGULAR,
    lines: Int = 1,
    gravity: Int = Gravity.NO_GRAVITY
): AText = AText(this).apply {
    textSize = sizeSp
    setTextColor(color)
    this.weight = weight
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
    if (gravity != Gravity.NO_GRAVITY) this.gravity = gravity
}

fun Context.iconView(res: Int, sizeDp: Int, tint: Int = Palette.text): ImageView = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(tint)
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(sizeDp.dp, sizeDp.dp)
}

fun Context.spacer(wDp: Int = 0, hDp: Int = 0, weight: Float = 0f): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(if (weight > 0f && wDp == 0) 0 else wDp.dp, if (weight > 0f && hDp == 0) 0 else hDp.dp, weight)
}

/** Round icon button (quick toggles). [active] tints the icon with the accent colour. */
class RoundBtn(context: Context, private val res: Int, private val sizeDp: Int = 56) : FrameLayout(context) {
    private val img = ImageView(context)
    var active = false
        set(v) { field = v; restyle() }

    init {
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(sizeDp.dp, sizeDp.dp)
        img.setImageResource(res)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = (sizeDp * 0.27f).toInt().dp
        addView(img, flp(MATCH, MATCH).also { it.setMargins(pad, pad, pad, pad) })
        restyle()
        pressScale(0.92f)
    }

    fun setIcon(r: Int) { img.setImageResource(r) }

    private fun restyle() {
        val normal = Shapes.oval(Palette.card2, Palette.stroke)
        val pressed = Shapes.oval(Palette.card3, Palette.stroke)
        background = Shapes.pressable(
            if (active) Shapes.oval(Palette.withAlpha(Palette.accent, 0.22f), Palette.withAlpha(Palette.accent, 0.55f)) else normal,
            pressed
        )
        img.setColorFilter(if (active) Palette.accent else Palette.text)
    }

    fun refreshTheme() = restyle()
}

fun View.roundedClip(radiusDp: Float) {
    clipToOutline = true
    outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(v: View, o: android.graphics.Outline) {
            o.setRoundRect(0, 0, v.width, v.height, radiusDp.dp)
        }
    }
}

fun Drawable.tinted(color: Int): Drawable = mutate().also { it.setTint(color) }
