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
     * The second order of final2/q-dock-order.png, the reverse of SPEC.md's table: left to right, nearest the driver first, the most used
     * nearest him, the places the same.
     */
    @JvmField val ORDER_NEAREST = listOf(
        Cell("home", R.drawable.ic_home),
        Cell("maps", R.drawable.ic_nav),
        Cell("music", R.drawable.ic_music),
        Cell("phone", R.drawable.ic_phone),
        Cell("apps", R.drawable.ic_apps),
        Cell("settings", R.drawable.ic_gear)
    )

    /** SPEC.md section 1's order. */
    @JvmField val ORDER_SPEC = listOf(
        Cell("settings", R.drawable.ic_gear),
        Cell("apps", R.drawable.ic_apps),
        Cell("phone", R.drawable.ic_phone),
        Cell("music", R.drawable.ic_music),
        Cell("maps", R.drawable.ic_nav),
        Cell("home", R.drawable.ic_home)
    )

    /**
     * The dock, left to right; each at the next of the row's fixed centres ([centreX]). Home's row is the same list without the `home` cell, from
     * the same first centre, and [com.abdllh.aura.chrome.ChromeRules.shifts] works out what the row does between the two: nothing else names the order.
     */
    @JvmField val DOCK = ORDER_NEAREST

    /** The centre x of the cell at [index] of [DOCK]: the first at the spec's first centre, 72 px apart. */
    fun centreX(index: Int): Int = Tokens2.X_settings + 72 * index

    /** The word being sung lit on its pill while the car moves. As the spec says: parked only. */
    const val LYRIC_WORD_MOVING = false

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
