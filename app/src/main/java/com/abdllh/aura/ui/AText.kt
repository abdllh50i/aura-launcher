package com.abdllh.aura.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView
import com.abdllh.aura.R

/** TextView that picks the right typeface/weight for the current language. Use app:weight in XML. */
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
