package com.abdllh.aura.ui

import android.content.Context
import android.widget.ImageView
import com.abdllh.aura.R

/**
 * The owner's open answers, each in one place. Whichever way he answers, it is a change here and nothing else.
 */
object Asked {
    /** The row's tiles (home/Screens' names), left to right, nearest the driver first, the most used nearest him. Nothing else names the order. */
    @JvmField val ORDER_NEAREST = listOf("home", "maps", "music", "phone", "apps", "settings")

    /** A message's text shown while the car moves. As the spec says: `رسالة جديدة`, the text only when parked. */
    const val MESSAGE_TEXT_MOVING = false
}

/**
 * The still `eq` glyph beside the song that plays, where three bars used to move: nothing perpetual (SPEC.md section 4), and with it
 * go 26 frames a second. [animating] is what old callers set; there is nothing to animate.
 */
class EqGlyph(ctx: Context) : ImageView(ctx) {
    var color = 0
        set(v) { if (v != field) { field = v; setColorFilter(v) } }
    var animating = false

    init {
        setImageResource(R.drawable.ic_eq)
        scaleType = ScaleType.FIT_CENTER
    }
}
