package com.abdllh.aura.ui

import android.graphics.Color

/** Central colour definitions (mirrors res/values/colors.xml) + the user-selected accent. */
object Palette {
    val bg = 0xFF0A0C0F.toInt()
    val card = 0xFF12161B.toInt()
    val card2 = 0xFF181D24.toInt()
    val card3 = 0xFF1F2530.toInt()
    val stroke = 0xFF222A33.toInt()
    val text = 0xFFF4F6F8.toInt()
    val text2 = 0xFF9AA5B1.toInt()
    val text3 = 0xFF66717E.toInt()
    val success = 0xFF2FD07A.toInt()
    val warn = 0xFFF5A524.toInt()
    val danger = 0xFFE5484D.toInt()

    val DEFAULT_ACCENT = 0xFF3E6AE1.toInt()

    @JvmField
    var accent: Int = DEFAULT_ACCENT

    /** Selectable accent colours shown in Settings. */
    val accents = intArrayOf(
        0xFF3E6AE1.toInt(), // blue
        0xFF2FB8FF.toInt(), // cyan
        0xFF2FD07A.toInt(), // green
        0xFFF5A524.toInt(), // amber
        0xFFE5484D.toInt(), // red
        0xFFA569FF.toInt(), // violet
        0xFFE8EAED.toInt()  // white
    )

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        )
    }

    /** Text colour that stays readable on top of [bg]. */
    fun onColor(bg: Int): Int {
        val l = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
        return if (l > 160) 0xFF0A0C0F.toInt() else 0xFFFFFFFF.toInt()
    }
}
