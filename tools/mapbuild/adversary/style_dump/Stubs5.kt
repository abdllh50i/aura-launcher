@file:Suppress("unused")

package com.abdllh.aura.ui

import android.graphics.Color

/** The values of the real Palette.kt that the style reads (mapLand, the default accent, mix). */
object Palette {
    @JvmField var dark = true
    @JvmField var mapLand = 0xFF1D2127.toInt()
    val DEFAULT_ACCENT = 0xFF3E6AE1.toInt()

    fun set(night: Boolean) {
        dark = night
        mapLand = if (night) 0xFF1D2127.toInt() else 0xFFF0EDE6.toInt()
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        )
    }
}
