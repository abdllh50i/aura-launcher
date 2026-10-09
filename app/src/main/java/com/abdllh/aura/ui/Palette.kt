package com.abdllh.aura.ui

import android.graphics.Color

/**
 * Colour tokens of the current theme (dark or light) plus the user-selected accent.
 * Views read the tokens when they are built; a theme change recreates the activity (see [Theme]).
 */
object Palette {
    @JvmField var dark = true

    @JvmField var bg = 0          // screen background
    @JvmField var card = 0        // panels and rows
    @JvmField var card2 = 0       // raised parts: inputs, chips, round buttons
    @JvmField var card3 = 0       // slider tracks, pressed chips
    @JvmField var stroke = 0      // hairlines
    @JvmField var text = 0
    @JvmField var text2 = 0
    @JvmField var text3 = 0
    @JvmField var press = 0       // pressed overlay on transparent buttons
    @JvmField var scrim = 0       // behind bottom sheets
    @JvmField var glass = 0       // floating cards on top of the map
    @JvmField var sheet = 0       // bottom sheets
    @JvmField var dock = 0        // bottom bar
    @JvmField var shadow = 0      // colour of the soft drop shadows

    // map
    @JvmField var mapLand = 0
    @JvmField var mapBlock = 0
    @JvmField var mapPark = 0
    @JvmField var mapWater = 0
    @JvmField var mapRoad = 0
    @JvmField var mapRoadMajor = 0
    @JvmField var mapCasing = 0

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
        0xFF8E959F.toInt()  // graphite
    )

    init {
        applyTheme(true)
    }

    fun applyTheme(isDark: Boolean) {
        dark = isDark
        if (isDark) {
            bg = 0xFF0B0C0E.toInt()
            card = 0xFF151719.toInt()
            card2 = 0xFF1D2024.toInt()
            card3 = 0xFF292D33.toInt()
            stroke = 0xFF24272C.toInt()
            text = 0xFFF2F3F5.toInt()
            text2 = 0xFFA3A8B0.toInt()
            text3 = 0xFF6C717A.toInt()
            press = 0x17FFFFFF
            scrim = 0xB3000000.toInt()
            glass = 0xF01A1D21.toInt()
            sheet = 0xFF141619.toInt()
            dock = 0xFF070809.toInt()
            shadow = 0xFF000000.toInt()
            mapLand = 0xFF16181C.toInt()
            mapBlock = 0xFF1B1E23.toInt()
            mapPark = 0xFF16231C.toInt()
            mapWater = 0xFF0F1C29.toInt()
            mapRoad = 0xFF2A2E35.toInt()
            mapRoadMajor = 0xFF3A3F48.toInt()
            mapCasing = 0xFF0E1013.toInt()
        } else {
            bg = 0xFFECEEF1.toInt()
            card = 0xFFFFFFFF.toInt()
            card2 = 0xFFF3F4F6.toInt()
            card3 = 0xFFE2E5E9.toInt()
            stroke = 0xFFDCE0E5.toInt()
            text = 0xFF15171B.toInt()
            text2 = 0xFF5D636D.toInt()
            text3 = 0xFF959BA5.toInt()
            press = 0x12000000
            scrim = 0x66000000
            glass = 0xF5FFFFFF.toInt()
            sheet = 0xFFF7F8FA.toInt()
            dock = 0xFFFFFFFF.toInt()
            shadow = 0xFF5A6270.toInt()
            mapLand = 0xFFE8EAED.toInt()
            mapBlock = 0xFFE1E4E8.toInt()
            mapPark = 0xFFD3E8CF.toInt()
            mapWater = 0xFFBCD7F0.toInt()
            mapRoad = 0xFFFFFFFF.toInt()
            mapRoadMajor = 0xFFFFFFFF.toInt()
            mapCasing = 0xFFD3D7DD.toInt()
        }
    }

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

    /** The accent a little lighter (pressed state of accent buttons). */
    fun accentPressed(): Int = mix(accent, if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.16f)

    /** Accent tint for selected chips/rows. */
    fun accentSoft(): Int = withAlpha(accent, if (dark) 0.20f else 0.13f)
}
