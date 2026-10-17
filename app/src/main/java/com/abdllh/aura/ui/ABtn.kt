package com.abdllh.aura.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import com.abdllh.aura.util.dp

/**
 * The app's button: every button is one of these, so a button looks and answers the same on every screen (1.11 had
 * five hand-built looks beside it, several under the car's 56 dp touch target, only this one with a disabled state).
 * [kind]: what it does; [size]: M for the buttons of a card or a dialog, L for the main action of a screen.
 */
class ABtn @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AText(context, attrs) {

    /**
     * PRIMARY: the action (accent fill). TONAL: a second choice beside it. GHOST: cancel, dismiss. DANGER: a
     * drastic choice offered beside others (red text on a red tint); DANGER_FILLED: the one drastic action of a
     * screen (End trip).
     */
    enum class Kind { PRIMARY, TONAL, GHOST, DANGER, DANGER_FILLED }

    /** M: [Touch.MIN] tall, [Type.BUTTON]. L: [Touch.LARGE] tall, 20 sp. */
    enum class Size { M, L }

    var kind: Kind = Kind.TONAL
        set(v) { field = v; restyle() }

    var size: Size = Size.M
        set(v) { field = v; resize() }

    init {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        resize()
        restyle()
        pressScale(Motion.PRESS_CONTROL)
    }

    private fun resize() {
        val large = size == Size.L
        textSize = if (large) 20f else Type.BUTTON.sp
        weight = Type.BUTTON.weight
        minHeight = (if (large) Touch.LARGE else Touch.MIN).dp
        val side = (if (large) 28 else Space.XL).dp
        setPadding(side, Space.S.dp, side, Space.S.dp)
    }

    fun restyle() {
        val r = Radius.M
        when (kind) {
            Kind.PRIMARY -> {
                background = Shapes.accent(r)
                setTextColor(Palette.onColor(Palette.accent))
            }
            Kind.TONAL -> {
                background = Shapes.tonal(r)
                setTextColor(Palette.text)
            }
            Kind.GHOST -> {
                background = Shapes.pressable(
                    Shapes.rect(0x00000000, r, Palette.stroke),
                    Shapes.rect(Palette.press, r, Palette.stroke)
                )
                setTextColor(Palette.text2)
            }
            Kind.DANGER -> {
                background = Shapes.pressable(
                    Shapes.rect(Palette.withAlpha(Palette.danger, 0.14f), r, Palette.withAlpha(Palette.danger, 0.5f)),
                    Shapes.rect(Palette.withAlpha(Palette.danger, 0.26f), r, Palette.withAlpha(Palette.danger, 0.5f))
                )
                setTextColor(Palette.danger)
            }
            Kind.DANGER_FILLED -> {
                background = Shapes.pressable(
                    Shapes.rect(Palette.danger, r),
                    Shapes.rect(Palette.mix(Palette.danger, if (Palette.dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.16f), r)
                )
                setTextColor(Palette.onColor(Palette.danger))
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }
}
