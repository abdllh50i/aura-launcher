package com.abdllh.aura.settings

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.AuraSlider
import com.abdllh.aura.ui.AuraSwitch
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.ui.textRole
import com.abdllh.aura.util.dp

/*
 * Row and section builders for the settings pages. Since 1.12 the rows of a section sit together in one card
 * ([group]) with hairlines between them, as in a car's or a phone's own settings: until 1.11 every row was a card of
 * its own, so a long section read as a busy column of pills, each with its own stroke on the dark theme, and the gaps
 * between them had grown six different sizes. The page's rhythm is now set here, not by each call: a section's
 * title, its card close under it, a hint close under the card, and the next section well apart.
 */

/** Where a row's content starts from the card's edge: the card's radius, so the icon stands clear of the corner. */
private const val INSET = 20
private const val ICON = 26
private const val ICON_GAP = 18

/** One row of a [group]. [textStart] (dp): where its title starts, which the hairline above it lines up with. */
class SettingRow(ctx: Context, val textStart: Int) : LinearLayout(ctx)

/** A section's title (its own class: [addRow] puts a card closer under a title than under another card). */
private class SectionTitle(ctx: Context) : AText(ctx)

fun Context.pageColumn(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(0, 0, 0, Space.XXL.dp)
}

/** Adds a section title: well apart from what is above it (nothing above: at the top). */
fun LinearLayout.addSection(title: CharSequence) {
    val t = SectionTitle(context).apply {
        textRole(Type.FOOTNOTE)
        weight = Fonts.MEDIUM
        setTextColor(Palette.text3)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        alignStart = true
        text = title
        setPaddingRelative(INSET.dp, 0, INSET.dp, 0)
    }
    addView(t, lp(MATCH, WRAP).apply { topMargin = if (childCount == 0) 0 else Space.XXL.dp })
}

/** Adds a card under what is there: close under a section title, a step apart from another card. */
fun LinearLayout.addRow(v: View) {
    val above = if (childCount == 0) null else getChildAt(childCount - 1)
    addView(v, lp(MATCH, WRAP).apply {
        topMargin = when (above) { null -> 0; is SectionTitle -> Space.S.dp; else -> Space.M.dp }
    })
}

/** Adds a hint close under the card it explains. */
fun LinearLayout.addHint(text: CharSequence) {
    addView(context.hint(text), lp(MATCH, WRAP).apply { topMargin = if (childCount == 0) 0 else Space.S.dp })
}

/**
 * The rows of one section in one card, a hairline between two rows (from where the lower one's title starts, so the
 * icons stand in an unbroken column). The card clips to its corners: a pressed first or last row tints exactly the
 * card's corner, not a square past it. A null row is left out (an option this unit does not have).
 */
fun Context.group(vararg rows: View?): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = Shapes.card(Radius.L)
    roundedClip(Radius.L)
    for (r in rows) {
        if (r == null) continue
        if (childCount > 0) {
            addView(View(context).apply { setBackgroundColor(Palette.stroke) },
                lp(MATCH, 1).apply { marginStart = ((r as? SettingRow)?.textStart ?: INSET).dp })
        }
        addView(r, lp(MATCH, WRAP))
    }
}

/**
 * Generic row: optional icon, title + subtitle, optional trailing view; tappable when [onClick] is set. A tapped row
 * shows the pressed tint over its whole width; it does not sink as a card would, since a row that shrank inside its
 * card would open gaps at the card's edges and tear its hairlines.
 * A trailing value never squeezes the title: it is held to a part of the row, and shortened in its middle.
 */
fun Context.settingRow(
    icon: Int?,
    title: CharSequence,
    subtitle: CharSequence? = null,
    trailing: View? = null,
    iconTint: Int = Palette.text2,
    onClick: (() -> Unit)? = null
): SettingRow = SettingRow(this, if (icon != null) INSET + ICON + ICON_GAP else INSET).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = 72.dp
    setPaddingRelative(INSET.dp, Space.M.dp, INSET.dp, Space.M.dp)
    if (icon != null) addView(iconView(icon, ICON, iconTint), lp(ICON.dp, ICON.dp).apply { marginEnd = ICON_GAP.dp })
    addView(titles(title, subtitle), lp(0, WRAP, 1f))
    if (trailing != null) {
        if (trailing is AText) trailing.maxWidth = 300.dp
        addView(trailing, lp(WRAP, WRAP).apply { marginStart = Space.L.dp })
    }
    if (onClick != null) {
        background = Shapes.ghost(0f)
        isClickable = true
        setOnClickListener { onClick() }
    }
}

private fun Context.titles(title: CharSequence?, subtitle: CharSequence?, live: AText? = null): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (title != null) addView(label(Type.LABEL, Palette.text, lines = 2).apply { text = title })
    val sub = live ?: if (subtitle.isNullOrEmpty()) null else label(Type.FOOTNOTE, Palette.text2, lines = 3).apply { text = subtitle }
    if (sub != null) addView(sub, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
}

/**
 * A row with a control under its title (the theme's choices, the swatches, a status with its buttons): the head as
 * in [settingRow], [body] below it. Without a [title] it is the body alone, in the row's insets. [live]: a subtitle
 * the caller keeps changing (a status), in place of [subtitle].
 */
fun Context.block(icon: Int?, title: CharSequence?, subtitle: CharSequence?, body: View?, live: AText? = null): SettingRow =
    SettingRow(this, if (icon != null) INSET + ICON + ICON_GAP else INSET).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(INSET.dp, Space.L.dp, INSET.dp, Space.L.dp)
        if (title != null) {
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (icon != null) head.addView(iconView(icon, ICON, Palette.text2), lp(ICON.dp, ICON.dp).apply { marginEnd = ICON_GAP.dp })
            head.addView(titles(title, subtitle, live), lp(0, WRAP, 1f))
            addView(head, lp(MATCH, WRAP))
        }
        if (body != null) addView(body, lp(MATCH, WRAP).apply { if (title != null) topMargin = Space.L.dp })
    }

fun Context.chevron(): ImageView = iconView(com.abdllh.aura.R.drawable.ic_chevron_right, 24, Palette.text3).also {
    if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) it.scaleX = -1f
}

/**
 * A row's value at its end: one line, shortened in the middle (a build id keeps its start and its end). A value with
 * no letter to set its direction (a version, "1.12.0 (1120099)") reads left to right also in Arabic, where it would
 * otherwise take the interface's direction and show "(1120099) 1.12.0"; an Arabic value still reads right to left.
 */
fun Context.valueText(text: CharSequence): AText = label(Type.BODY, Palette.text2).apply {
    this.text = text
    ellipsize = TextUtils.TruncateAt.MIDDLE
    textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
}

/**
 * A row with a switch. The whole row is the switch's target: a tap anywhere on it flips it, and the switch shows the
 * press (its thumb stretches) whether the finger is on the switch or on the row's words, since the row passes its
 * pressed state down to it.
 */
fun Context.switchRow(icon: Int?, title: CharSequence, subtitle: CharSequence?, checked: Boolean, onChange: (Boolean) -> Unit): SettingRow {
    val sw = AuraSwitch(this)
    sw.setChecked(checked)
    sw.onToggle = onChange
    // (not a target of its own: the row takes every tap, and a ViewGroup only passes its pressed state to children
    // that are not clickable)
    sw.isClickable = false
    val row = settingRow(icon, title, subtitle, sw)
    row.background = Shapes.ghost(0f)
    row.isClickable = true
    row.setOnClickListener { sw.setChecked(!sw.checked, animate = true, notify = true) }
    return row
}

/** A slider under its title, with the value at the title's end (the slider carries the icon inside it). */
fun Context.sliderRow(title: CharSequence, slider: AuraSlider, valueView: AText): SettingRow = SettingRow(this, INSET).apply {
    orientation = LinearLayout.VERTICAL
    setPaddingRelative(INSET.dp, Space.L.dp, INSET.dp, Space.L.dp)
    val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(label(Type.LABEL, Palette.text).apply { text = title }, lp(0, WRAP, 1f))
    valueView.fontFeatureSettings = "tnum" // (a number that changes under the finger does not jiggle)
    head.addView(valueView, lp(WRAP, WRAP))
    addView(head, lp(MATCH, WRAP))
    addView(slider, lp(MATCH, Touch.MIN.dp).apply { topMargin = Space.M.dp })
}

fun Context.hint(text: CharSequence): AText = label(Type.FOOTNOTE, Palette.text3, lines = 0).apply {
    this.text = text
    setPaddingRelative(INSET.dp, 0, INSET.dp, 0)
}
