package com.abdllh.aura.home

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import com.abdllh.aura.system.Gear
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp

/**
 * P R N D, like the gear lever: the current gear lit (R in the warning colour), the others dim; nothing lit while the
 * gear is unknown. Always left to right, also in Arabic (the letters are the lever's).
 */
class GearView(ctx: Context) : LinearLayout(ctx) {
    private val letters: List<AText> = Gear.Pos.values().map { p ->
        ctx.label(19f, Palette.text3, Fonts.MEDIUM, gravity = Gravity.CENTER).apply { text = p.name }
    }
    private val listener: () -> Unit = { show() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = LAYOUT_DIRECTION_LTR
        for ((i, t) in letters.withIndex()) addView(t, lp(36.dp, 36.dp).apply { if (i > 0) marginStart = 4.dp })
        show()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Gear.addListener(listener)
        show()
    }

    override fun onDetachedFromWindow() {
        Gear.removeListener(listener)
        super.onDetachedFromWindow()
    }

    private fun show() {
        val g = Gear.current
        for ((i, t) in letters.withIndex()) {
            val on = g != null && i == g.ordinal
            val lit = if (g == Gear.Pos.R) Palette.warn else Palette.text
            t.setTextColor(if (on) lit else Palette.text3)
            t.weight = if (on) Fonts.BOLD else Fonts.MEDIUM
            t.alpha = if (on) 1f else 0.55f
            t.background = if (on) Shapes.rect(Palette.withAlpha(lit, if (Palette.dark) 0.16f else 0.12f), 11f) else null
        }
        contentDescription = g?.name ?: ""
    }
}
