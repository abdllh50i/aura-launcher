package com.abdllh.aura.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import com.abdllh.aura.util.dp

/** Pill/rounded button used across the settings screens. */
class ABtn @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AText(context, attrs) {

    enum class Kind { PRIMARY, TONAL, GHOST, DANGER }

    var kind: Kind = Kind.TONAL
        set(v) { field = v; restyle() }

    init {
        weight = Fonts.MEDIUM
        gravity = Gravity.CENTER
        textSize = 15f
        isClickable = true
        isFocusable = true
        minHeight = 48.dp
        setPadding(22.dp, 10.dp, 22.dp, 10.dp)
        restyle()
        pressScale(0.96f)
    }

    fun restyle() {
        when (kind) {
            Kind.PRIMARY -> {
                background = Shapes.accent(14f)
                setTextColor(Palette.onColor(Palette.accent))
            }
            Kind.TONAL -> {
                background = Shapes.pressable(Shapes.rect(Palette.card2, 14f), Shapes.rect(Palette.card3, 14f))
                setTextColor(Palette.text)
            }
            Kind.GHOST -> {
                background = Shapes.pressable(
                    Shapes.rect(0x00000000, 14f, Palette.stroke),
                    Shapes.rect(Palette.press, 14f, Palette.stroke)
                )
                setTextColor(Palette.text2)
            }
            Kind.DANGER -> {
                background = Shapes.pressable(
                    Shapes.rect(Palette.withAlpha(Palette.danger, 0.14f), 14f, Palette.withAlpha(Palette.danger, 0.5f)),
                    Shapes.rect(Palette.withAlpha(Palette.danger, 0.26f), 14f, Palette.withAlpha(Palette.danger, 0.5f))
                )
                setTextColor(Palette.danger)
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }
}
