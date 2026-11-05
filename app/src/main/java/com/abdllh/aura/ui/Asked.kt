package com.abdllh.aura.ui

import android.content.Context
import android.widget.ImageView
import com.abdllh.aura.R

/**
 * The owner's open answers, each in one place. Whichever way he answers, it is a change here and nothing else.
 */
object Asked {
    /** One cell of the dock: the panel it opens (home/Screens' names) and its glyph. */
    class Cell(@JvmField val panel: String, @JvmField val icon: Int)

    /**
     * The dock, left to right, nearest the driver first, the most used nearest him: each at the next of the row's fixed centres ([centreX]). Home's row is the same list without the `home` cell, with its
     * tiles at their own wider centres ([tileX]); the row's change crossfades in place. Nothing else names the order.
     */
    @JvmField val ORDER_NEAREST = listOf(
        Cell("home", R.drawable.ic_home),
        Cell("maps", R.drawable.ic_nav),
        Cell("music", R.drawable.ic_music),
        Cell("phone", R.drawable.ic_phone),
        Cell("apps", R.drawable.ic_apps),
        Cell("settings", R.drawable.ic_gear)
    )

    /** The centre x of the cell at [index] of [ORDER_NEAREST], 80 px apart. */
    fun centreX(index: Int): Int = Tokens2.X_home + 80 * index

    /** The centre x of Home's tile at [index], with no home cell: the map's cell is the one fixed point, and they spread over the capsule from it, 121 px apart. */
    fun tileX(index: Int): Int = Tokens2.X_map + 121 * index

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
