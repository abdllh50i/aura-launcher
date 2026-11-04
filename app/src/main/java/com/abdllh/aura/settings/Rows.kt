package com.abdllh.aura.settings

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.AuraSlider
import com.abdllh.aura.ui.AuraSwitch
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceColumn
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Tones
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.ink
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.lp

/*
 * Row and section builders for the settings pages (SPEC.md section 5, "Settings row"). The rows of a section sit together in one
 * card ([group], the `card` surface) with hairlines between them, as in a car's or a phone's own settings. The page's rhythm is set
 * here, not by each call: a section's title, its card close under it, a hint close under the card, and the next section well apart.
 * A row is 83 high at least, its label in `rowLabel`, an optional `rowHint` under it, its control at the far end, a hairline `line`
 * inset 22. All colours follow the theme (ui/Look.kt): nothing is built again.
 */

/** Where a row's content starts from the card's edge, and how far in its hairline runs. */
private const val INSET = 22
private const val ICON = 26
private const val ICON_GAP = 18
private const val ROW_MIN = 83
private const val GAP = 18

/** One row of a [group]. [textStart] (dp): where its title starts. */
class SettingRow(ctx: Context, val textStart: Int) : LinearLayout(ctx)

/** A section's title (its own class: [addRow] puts a card closer under a title than under another card). */
private class SectionTitle(ctx: Context) : AText(ctx)

fun Context.pageColumn(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(0, 0, 0, Space.XXL)
}

/** Adds a section title: well apart from what is above it (nothing above: at the top). */
fun LinearLayout.addSection(title: CharSequence) {
    val t = SectionTitle(context).apply {
        oneLine(Type.sectionHead)
        ink { Palette.ink2 }
        alignStart = true
        text = title
        setPaddingRelative(INSET, 0, INSET, 0)
    }
    addView(t, lp(MATCH, Type.sectionHead.viewH).apply { topMargin = if (childCount == 0) 0 else Space.XXL })
}

/**
 * Adds a card under what is there: close under a section title, a step apart from another card. A card's bounds are its visible rect
 * plus the outset; the margins take that off here, so the page lines up by what shows.
 */
fun LinearLayout.addRow(v: View) {
    val above = if (childCount == 0) null else getChildAt(childCount - 1)
    val o = if (v is SurfaceColumn) v.surface.outset else 0
    addView(v, lp(MATCH, WRAP).apply {
        leftMargin = -o
        rightMargin = -o
        topMargin = -o + when (above) { null -> 0; is SectionTitle -> Space.S; else -> Space.M } - if (above is SurfaceColumn) above.surface.outset else 0
    })
}

/** Adds a hint close under the card it explains. */
fun LinearLayout.addHint(text: CharSequence) {
    val above = if (childCount == 0) null else getChildAt(childCount - 1)
    addView(context.hint(text), lp(MATCH, WRAP).apply { topMargin = (if (above == null) 0 else Space.S) - if (above is SurfaceColumn) above.surface.outset else 0 })
}

/**
 * The rows of one section in one card, a hairline between two rows. A null row is left out (an option this unit does not have).
 * Its visible rect is its bounds less the outset, which [addRow] takes care of; elsewhere give it an exact rect with [Surface.at].
 * [fill]: the rows share that height evenly (a page that fills its card: 414 / 4 is 103.5, 414 / 5 is 82.8).
 */
fun Context.group(vararg rows: View?, fill: Int = 0): LinearLayout = SurfaceColumn(this, Surface.CARD).apply {
    var first = true
    for (r in rows) {
        if (r == null) continue
        if (!first) {
            addView(View(context).apply { look { setBackgroundColor(Palette.line) } }, lp(MATCH, 1).apply {
                leftMargin = INSET
                rightMargin = INSET
            })
        }
        first = false
        addView(r, if (fill > 0) lp(MATCH, 0, 1f) else lp(MATCH, WRAP))
    }
    if (fill > 0) minimumHeight = fill + 2 * surface.outset
}

/** A row's press: `fillPress` over it, a plate inset 8 so it never pokes out of the card's corner; it does not sink. */
private fun View.rowPress() {
    val plate = Tint(context, R.drawable.plate_18) { Palette.fillPress }
    background = PressGesture.plate(plate.drawable, 8)
    look { plate.recolour() }
    isClickable = true
}

/**
 * Generic row: optional icon, title + subtitle, optional trailing view; tappable when [onClick] is set. A tapped row shows the
 * pressed plate; it does not sink as a card would, since a row that shrank inside its card would tear its hairlines.
 * A trailing value never squeezes the title: it is held to a part of the row, and shortened in its middle.
 */
fun Context.settingRow(
    icon: Int?,
    title: CharSequence,
    subtitle: CharSequence? = null,
    trailing: View? = null,
    iconTint: Int = Palette.ink2,
    onClick: (() -> Unit)? = null
): SettingRow = SettingRow(this, if (icon != null) INSET + ICON + ICON_GAP else INSET).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = ROW_MIN
    setPaddingRelative(INSET, Space.S, INSET, Space.S)
    if (icon != null) addView(iconView(icon, ICON, iconTint), lp(ICON, ICON).apply { marginEnd = ICON_GAP })
    addView(titles(title, subtitle), lp(0, WRAP, 1f))
    if (trailing != null) {
        if (trailing is AText) trailing.maxWidth = 300
        addView(trailing, trailingLp(trailing))
    }
    if (onClick != null) {
        rowPress()
        setOnClickListener { Tones.tick(context); onClick() }
    }
}

/** A switch's view is its track and the room for its thumb's shadow: that room lies in the row's padding. */
private fun trailingLp(v: View): LinearLayout.LayoutParams = if (v is AuraSwitch) lp(WRAP, WRAP).apply {
    marginStart = GAP - AuraSwitch.OUTSET
    marginEnd = -AuraSwitch.OUTSET
} else lp(WRAP, WRAP).apply { marginStart = GAP }

private fun Context.titles(title: CharSequence?, subtitle: CharSequence?, live: AText? = null): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (title != null) addView(label(Type.rowLabel, { Palette.ink }, lines = 2).apply { text = title })
    val sub = live ?: if (subtitle.isNullOrEmpty()) null else label(Type.rowHint, { Palette.ink2 }, lines = 3).apply { text = subtitle }
    if (sub != null) addView(sub, lp(MATCH, WRAP))
}

/**
 * A row with a control under its title (a status with its buttons): the head as in [settingRow], [body] below it. Without a [title]
 * it is the body alone, in the row's insets. [live]: a subtitle the caller keeps changing (a status), in place of [subtitle].
 */
fun Context.block(icon: Int?, title: CharSequence?, subtitle: CharSequence?, body: View?, live: AText? = null): SettingRow =
    SettingRow(this, if (icon != null) INSET + ICON + ICON_GAP else INSET).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(INSET, Space.L, INSET, Space.L)
        if (title != null) {
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (icon != null) head.addView(iconView(icon, ICON) { Palette.ink2 }, lp(ICON, ICON).apply { marginEnd = ICON_GAP })
            head.addView(titles(title, subtitle, live), lp(0, WRAP, 1f))
            addView(head, lp(MATCH, WRAP))
        }
        if (body != null) addView(body, lp(MATCH, WRAP).apply { if (title != null) topMargin = Space.M })
    }

/**
 * A row with its control at the far end, inline with the label: the theme's choices, the map's view. [controlW]: its width, 440 in
 * Settings (section 6.6), 400 beside the column on a trip.
 */
fun Context.controlRow(title: CharSequence, control: View, controlW: Int = 440): SettingRow = SettingRow(this, INSET).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = ROW_MIN
    setPaddingRelative(INSET, 0, INSET, 0)
    addView(label(Type.rowLabel, { Palette.ink }).apply { text = title }, lp(0, Type.rowLabel.viewH, 1f))
    addView(control, lp(controlW, WRAP).apply { marginStart = GAP })
}

/** The chevron of a row that opens something; it points by the language through the icon's own autoMirrored. */
fun Context.chevron(): ImageView = iconView(R.drawable.ic_chev_right, 24) { Palette.ink3 }

/**
 * A row's value at its end: one line, shortened in the middle (a build id keeps its start and its end). A value with
 * no letter to set its direction (a version, "1.12.0 (1120099)") reads left to right also in Arabic, where it would
 * otherwise take the interface's direction and show "(1120099) 1.12.0"; an Arabic value still reads right to left.
 */
fun Context.valueText(text: CharSequence): AText = label(Type.rowValue, { Palette.ink2 }).apply {
    this.text = text
    ellipsize = TextUtils.TruncateAt.MIDDLE
    textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
}

/** A value and a chevron side by side, the far end of a row that opens a sub-page. */
fun Context.valueChevron(text: CharSequence?): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (!text.isNullOrEmpty()) addView(valueText(text), lp(WRAP, Type.rowValue.viewH))
    addView(chevron(), lp(24, 24).apply { marginStart = Space.M })
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
    row.rowPress()
    row.setOnClickListener { Tones.tick(this); sw.setChecked(!sw.checked, animate = true, notify = true) }
    return row
}

/**
 * The slider's whole control, as Settings and Controls draw it: optional views at its two ends, the slider between them with its
 * track running edge to edge of the room left. [slider]'s own bounds are wider than its track, so that goes in negative margins.
 */
fun Context.sliderControl(slider: AuraSlider, start: View? = null, end: View? = null): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (start != null) addView(start, lp(WRAP, WRAP))
    addView(slider, lp(0, AuraSlider.HEIGHT, 1f).apply {
        marginStart = -AuraSlider.INSET + if (start != null) Space.M else 0
        marginEnd = -AuraSlider.INSET + if (end != null) Space.M else 0
    })
    if (end != null) addView(end, lp(WRAP, WRAP))
}

/** A slider inline with its title, its value before it in the reading order (the slider's own icons, if any, by [sliderControl]). */
fun Context.sliderRow(title: CharSequence, slider: AuraSlider, valueView: AText): SettingRow {
    valueView.gravity = Gravity.CENTER
    return controlRow(title, sliderControl(slider, start = FrameLayout(this).apply { addView(valueView, FrameLayout.LayoutParams(VALUE_W, Type.rowValue.viewH)) }))
}

private const val VALUE_W = 34

fun Context.hint(text: CharSequence): AText = label(Type.rowHint, { Palette.ink2 }, lines = 0).apply {
    this.text = text
    setPaddingRelative(INSET, 0, INSET, 0)
}
