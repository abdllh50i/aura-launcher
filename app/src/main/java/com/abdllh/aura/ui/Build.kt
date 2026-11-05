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

/** Small view-building helpers shared by every screen. */

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
 * A label in one of the [Type] roles, built as SPEC.md section 3 says: one line, 1.5 em tall, or for text that wraps
 * ([lines] other than 1) the role's line box with no multiplier. Its colour here is fixed; `.ink { Palette.ink2 }` makes it
 * follow the theme. [moving]: on a moving screen, in the role's weight for that, heavier at night.
 */
fun Context.label(
    role: Type.Role,
    color: Int = Palette.text,
    lines: Int = 1,
    gravity: Int = Gravity.NO_GRAVITY,
    moving: Boolean = false
): AText = AText(this).apply {
    setTextColor(color)
    if (lines == 1) oneLine(role, moving) else wrapping(role, lines, moving)
    alignStart = true
    if (gravity != Gravity.NO_GRAVITY) this.gravity = gravity
}

/** A label of a role whose colour is a token, in this theme and the next. */
fun Context.label(role: Type.Role, token: () -> Int, lines: Int = 1, gravity: Int = Gravity.NO_GRAVITY, moving: Boolean = false): AText =
    label(role, token(), lines, gravity, moving).ink(token)

/** Gives an existing label a [Type] role (size and weight). */
fun AText.textRole(role: Type.Role) {
    textSize = role.sp
    weight = role.weight
}

fun Context.iconView(res: Int, sizeDp: Int, tint: Int = Palette.text): ImageView = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(tint)
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(sizeDp.dp, sizeDp.dp)
}

/** A glyph whose colour is a token, in this theme and the next. */
fun Context.iconView(res: Int, sizeDp: Int, token: () -> Int): ImageView = iconView(res, sizeDp, token()).glyph(token)

fun Context.spacer(wDp: Int = 0, hDp: Int = 0, weight: Float = 0f): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(if (weight > 0f && wDp == 0) 0 else wDp.dp, if (weight > 0f && hDp == 0) 0 else hDp.dp, weight)
}

/**
 * Rounded corners of [radiusDp] as seen: a view that is shown scaled ([LayoutMove]) keeps its roundness when its
 * outline is asked for again on the way (invalidateOutline), instead of corners that swell and shrink with it.
 * Old screens only: a new surface is baked (SPEC.md section 4).
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
