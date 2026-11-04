package com.abdllh.aura.ui

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView
import com.abdllh.aura.R

/** TextView that takes its typeface from [Fonts] by weight or by role. Use app:weight in XML. */
open class AText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextView(context, attrs, defStyle) {

    var weight: Int = Fonts.REGULAR
        set(value) {
            field = value
            typeface = Fonts.get(value)
        }

    /**
     * Text starts at the layout's start edge, whatever script it is in. A TextView's own START follows the text:
     * a Latin place name, song or repository in the Arabic UI went to the left while the Arabic around it stood on
     * the right. Only the alignment follows the layout; the text direction stays the text's own, so a Latin line
     * keeps its punctuation where it belongs. A gravity with another horizontal side (centre, end) set later wins.
     */
    var alignStart = false
        set(v) { field = v; applyAlign() }

    init {
        var w = Fonts.REGULAR
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.AText)
            w = a.getInt(R.styleable.AText_weight, Fonts.REGULAR)
            a.recycle()
        }
        weight = w
        includeFontPadding = false
        Fonts.tune(paint)
    }

    /**
     * A one-line label of a role (SPEC.md section 3): 1.5 em tall, placed by [Type.Role.box] so that its centre is the line
     * box's. An emoji in a title cannot move its baseline. [moving]: its weight on a moving screen, heavier at night.
     * The line's direction is the language's: a name inside it keeps its own by BidiFormatter.unicodeWrap, where it comes from.
     */
    fun oneLine(role: Type.Role, moving: Boolean = false) {
        textDirection = TEXT_DIRECTION_LOCALE
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, role.sp)
        typeface = Fonts.of(role, moving)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (Build.VERSION.SDK_INT >= 28) isFallbackLineSpacing = false
    }

    /** Text that wraps (only Settings' hints, the no-phone text, parked lyric lines): the role's line box, no multiplier. */
    fun wrapping(role: Type.Role, lines: Int = 0, moving: Boolean = false) {
        textDirection = TEXT_DIRECTION_LOCALE
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, role.sp)
        typeface = Fonts.of(role, moving)
        if (lines > 0) {
            maxLines = lines
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        if (Build.VERSION.SDK_INT >= 28) lineHeight = role.line
    }

    /** Letter-spacing in px, only ever on digits (section 3: clock -1, speed -0.5, a three-digit limit -0.8). */
    fun digitSpacing(px: Float) {
        letterSpacing = px / textSize
    }

    override fun setGravity(gravity: Int) {
        super.setGravity(gravity)
        applyAlign()
    }

    private fun applyAlign() {
        // (also called from TextView's constructor, before this class's fields are set: alignStart is false then)
        if (!alignStart) return
        val h = gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK
        textAlignment = if (h == 0 || h == Gravity.START) TEXT_ALIGNMENT_VIEW_START else TEXT_ALIGNMENT_GRAVITY
    }
}
