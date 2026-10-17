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

/**
 * A label at a literal size. Prefer the overload that takes a [Type] role; this one stays for the screens that have
 * not moved to the roles yet.
 * Without a [gravity] its text starts at the layout's start edge whatever script it is in (see [AText.alignStart]).
 */
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
    alignStart = true
    if (gravity != Gravity.NO_GRAVITY) this.gravity = gravity
}

/**
 * A label in one of the [Type] roles (the preferred way since 1.12). Text that may wrap ([lines] other than 1) gets
 * 1.15 line spacing: Arabic's marks above and below the letters touch the next line at 1.0.
 */
fun Context.label(
    role: Type.Role,
    color: Int = Palette.text,
    lines: Int = 1,
    gravity: Int = Gravity.NO_GRAVITY
): AText = label(role.sp, color, role.weight, lines, gravity).apply {
    if (role.tabular) fontFeatureSettings = "tnum"
    if (lines != 1) setLineSpacing(0f, 1.15f)
}

/** Gives an existing label a [Type] role (size, weight, figures). */
fun AText.textRole(role: Type.Role) {
    textSize = role.sp
    weight = role.weight
    fontFeatureSettings = if (role.tabular) "tnum" else null
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

/**
 * Round icon button (quick toggles). [active] tints the icon with the accent colour (its foreground form,
 * [Palette.accentFg]). [flat]: no surface of its own (it sits in a strip that has one); active, a soft accent circle
 * shows.
 */
class RoundBtn(context: Context, private val res: Int, private val sizeDp: Int = Touch.MIN, private val flat: Boolean = false) : FrameLayout(context) {
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
        pressScale(Motion.PRESS_ICON)
    }

    fun setIcon(r: Int) { img.setImageResource(r) }

    private fun restyle() {
        if (flat) {
            background = Shapes.pressable(Shapes.oval(if (active) Palette.accentSoft() else 0), Shapes.oval(Palette.press))
            img.setColorFilter(if (active) Palette.accentFg else Palette.text)
            return
        }
        // light theme: white buttons lifted by a soft shadow; dark theme: flat tonal buttons
        background = Shapes.pressable(
            if (active) Shapes.oval(Palette.mix(if (Palette.dark) Palette.card2 else Palette.card, Palette.accent, if (Palette.dark) 0.18f else 0.12f))
            else Shapes.oval(if (Palette.dark) Palette.card2 else Palette.card),
            Shapes.oval(Palette.card3)
        )
        lift(Elev.REST)
        img.setColorFilter(if (active) Palette.accentFg else Palette.text)
    }

    fun refreshTheme() = restyle()
}

/**
 * Rounded corners of [radiusDp] as seen: a view that is shown scaled ([LayoutMove]) keeps its roundness when its
 * outline is asked for again on the way (invalidateOutline), instead of corners that swell and shrink with it.
 */
fun View.roundedClip(radiusDp: Float) {
    clipToOutline = true
    outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(v: View, o: android.graphics.Outline) {
            val s = v.scaleX
            o.setRoundRect(0, 0, v.width, v.height, if (s > 0.05f) radiusDp.dp / s else radiusDp.dp)
        }
    }
}

fun Drawable.tinted(color: Int): Drawable = mutate().also { it.setTint(color) }
