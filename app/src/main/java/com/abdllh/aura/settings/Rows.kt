package com.abdllh.aura.settings

import android.content.Context
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
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.dp

/** Row/section builders for the settings pages. */

fun Context.pageColumn(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(0, 0, 0, 24.dp)
}

fun Context.sectionTitle(text: CharSequence): AText = label(14.5f, Palette.text3, Fonts.MEDIUM).apply {
    this.text = text
    isAllCaps = false
    letterSpacing = 0.04f
    setPadding(6.dp, 0, 6.dp, 0)
}

fun LinearLayout.addRow(v: View, topDp: Int = 10) {
    addView(v, lp(MATCH, WRAP).apply { topMargin = topDp.dp })
}

/** Generic row: optional icon, title + subtitle, optional trailing view; clickable when [onClick] is set. */
fun Context.settingRow(
    icon: Int?,
    title: CharSequence,
    subtitle: CharSequence? = null,
    trailing: View? = null,
    iconTint: Int = Palette.text2,
    onClick: (() -> Unit)? = null
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = 78.dp
    setPadding(20.dp, 10.dp, 20.dp, 10.dp)
    background = if (onClick != null) Shapes.clickableCard(18f) else Shapes.card(18f)
    if (icon != null) addView(iconView(icon, 27, iconTint), lp(27.dp, 27.dp).apply { marginEnd = 18.dp })
    val t = LinearLayout(this@settingRow).apply { orientation = LinearLayout.VERTICAL }
    t.addView(label(18.5f, Palette.text, Fonts.MEDIUM, lines = 2).apply { text = title })
    if (!subtitle.isNullOrEmpty()) t.addView(label(14.5f, Palette.text2, Fonts.REGULAR, lines = 3).apply { text = subtitle }, lp(MATCH, WRAP).apply { topMargin = 3.dp })
    addView(t, lp(0, WRAP, 1f))
    if (trailing != null) addView(trailing, lp(WRAP, WRAP).apply { marginStart = 14.dp })
    if (onClick != null) {
        isClickable = true
        pressScale(0.985f)
        setOnClickListener { onClick() }
    }
}

fun Context.chevron(): ImageView = iconView(com.abdllh.aura.R.drawable.ic_chevron_right, 26, Palette.text3).also {
    if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) it.scaleX = -1f
}

fun Context.valueText(text: CharSequence): AText = label(16.5f, Palette.text2, Fonts.REGULAR).apply { this.text = text }

fun Context.switchRow(icon: Int?, title: CharSequence, subtitle: CharSequence?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
    val sw = AuraSwitch(this)
    sw.setChecked(checked)
    sw.onToggle = onChange
    val row = settingRow(icon, title, subtitle, sw)
    row.isClickable = true
    row.setOnClickListener { sw.performClick() }
    return row
}

fun Context.sliderRow(icon: Int, title: CharSequence, slider: AuraSlider, valueView: AText): LinearLayout {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp, 14.dp, 20.dp, 16.dp)
        background = Shapes.card(18f)
    }
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(iconView(icon, 26, Palette.text2), lp(26.dp, 26.dp).apply { marginEnd = 14.dp })
    head.addView(label(18f, Palette.text, Fonts.MEDIUM).apply { text = title }, lp(0, WRAP, 1f))
    head.addView(valueView, lp(WRAP, WRAP))
    col.addView(head, lp(MATCH, WRAP))
    col.addView(slider, lp(MATCH, 60.dp).apply { topMargin = 12.dp })
    return col
}

fun Context.hint(text: CharSequence): AText = label(14.5f, Palette.text3, Fonts.REGULAR, lines = 0).apply {
    this.text = text
    setPadding(8.dp, 0, 8.dp, 0)
    setLineSpacing(0f, 1.15f)
}
