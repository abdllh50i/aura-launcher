package com.abdllh.aura.ui

import android.content.Context
import android.util.AttributeSet
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
}
