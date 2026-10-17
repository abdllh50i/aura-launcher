package com.abdllh.aura.ui

import android.graphics.Color

/**
 * Colour tokens of the current theme (dark or light) plus the user-selected accent.
 * Views read the tokens when they are built; a theme change recreates the activity (see [Theme]).
 *
 * Contrast rules (1.12, measured with [contrast]): body text ([text], [text2]) is at least 4.5:1 on every surface of
 * its theme; [text3] is at least 4:1 and only for text of 14 sp or more that is never the only sign of something
 * tappable; text on a filled colour is [onColor] of it; the accent as a *foreground* (a label, an icon, a highlighted
 * word) is [accentFg], never [accent], which is a fill (buttons, the slider's fill, indicators, the route).
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
    @JvmField var scrim = 0       // behind bottom sheets and modals
    @JvmField var glass = 0       // floating cards on top of the map
    @JvmField var glassStroke = 0 // their edge (on the dark map a shadow does not show, the edge does)
    @JvmField var sheet = 0       // bottom sheets and modal cards
    @JvmField var dock = 0        // bottom bar
    @JvmField var shadow = 0      // colour of the soft drop shadows
    @JvmField var divider = 0     // lines between parts of one surface (the dock's top edge)
    @JvmField var track = 0       // the empty part of thin progress bars and level meters (translucent: any surface)
    @JvmField var control = 0     // the "off" track of a switch: it must show on a card without being a colour
    @JvmField var overlay = 0     // the system-level overlays (volume, call, voice, messages) over any screen
    @JvmField var overlayStroke = 0

    // Status colours, themed: the dark theme's bright ones are about 2:1 on white, so the light theme has deeper ones
    // (each at least 4.5:1 on white, and white on them at least as much, so they serve as text and as fills).
    @JvmField var success = 0
    @JvmField var warn = 0
    @JvmField var danger = 0

    // map
    @JvmField var mapLand = 0
    @JvmField var mapBlock = 0
    @JvmField var mapPark = 0
    @JvmField var mapWater = 0
    @JvmField var mapRoad = 0
    @JvmField var mapRoadMajor = 0
    @JvmField var mapCasing = 0

    val DEFAULT_ACCENT = 0xFF3E6AE1.toInt()

    /** The graphite swatch until 1.11 (white on it was 3.0:1); a saved one is moved to [accents]' graphite. */
    const val OLD_GRAPHITE = 0xFF8E959F.toInt()

    /**
     * The owner's accent: a fill. Assigning it also works out [accentFg] for the loaded theme, so every assignment
     * (Prefs at start, the swatches in Settings) keeps the two together.
     */
    var accent: Int = DEFAULT_ACCENT
        set(v) { field = v; accentFg = foreground(v) }

    /**
     * The accent as text or an icon: the accent itself when it already reads at 4.5:1 on its own tinted surface
     * ([accentSoft] over a card), else lighter on the dark theme and deeper on the light one, just as far as needed.
     */
    @JvmField var accentFg = 0

    /** Selectable accent colours shown in Settings. */
    val accents = intArrayOf(
        0xFF3E6AE1.toInt(), // blue
        0xFF2FB8FF.toInt(), // cyan
        0xFF2FD07A.toInt(), // green
        0xFFF5A524.toInt(), // amber
        0xFFE5484D.toInt(), // red
        0xFFA569FF.toInt(), // violet
        0xFF767D87.toInt()  // graphite (white on it 4.2:1)
    )

    init {
        applyTheme(true)
    }

    fun applyTheme(isDark: Boolean) {
        dark = isDark
        if (isDark) {
            bg = 0xFF0B0C0E.toInt()
            card = 0xFF17191D.toInt()
            card2 = 0xFF1D2024.toInt()
            card3 = 0xFF292D33.toInt()
            stroke = 0xFF2A2E34.toInt()
            text = 0xFFF2F3F5.toInt()
            text2 = 0xFFA3A8B0.toInt()
            text3 = 0xFF80868F.toInt()
            press = 0x17FFFFFF
            scrim = 0xB3000000.toInt()
            glass = 0xF2121417.toInt()
            glassStroke = 0x1FFFFFFF
            sheet = 0xFF141619.toInt()
            dock = 0xFF070809.toInt()
            shadow = 0xFF000000.toInt()
            divider = 0xFF1A1C20.toInt()
            track = 0x26FFFFFF
            control = 0xFF3A3F47.toInt()
            overlay = 0xF51C1F24.toInt()
            overlayStroke = 0x1AFFFFFF
            success = 0xFF2FD07A.toInt()
            warn = 0xFFF5A524.toInt()
            danger = 0xFFE5484D.toInt()
            mapLand = 0xFF1D2127.toInt()
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
            text2 = 0xFF4F555E.toInt()
            text3 = 0xFF6E747E.toInt()
            press = 0x12000000
            scrim = 0x66000000
            glass = 0xF5FFFFFF.toInt()
            glassStroke = 0
            sheet = 0xFFFFFFFF.toInt() // (white, so the card2 tiles on it show as tiles)
            dock = 0xFFFFFFFF.toInt()
            shadow = 0xFF5A6270.toInt()
            divider = 0x14000000
            track = 0x1A000000
            control = 0xFFC5CBD3.toInt()
            overlay = 0xFAFFFFFF.toInt()
            overlayStroke = 0x14000000
            success = 0xFF17804A.toInt()
            warn = 0xFFA86200.toInt()
            danger = 0xFFC9363B.toInt()
            mapLand = 0xFFF0EDE6.toInt()
            mapBlock = 0xFFE1E4E8.toInt()
            mapPark = 0xFFD3E8CF.toInt()
            mapWater = 0xFFBCD7F0.toInt()
            mapRoad = 0xFFFFFFFF.toInt()
            mapRoadMajor = 0xFFFFFFFF.toInt()
            mapCasing = 0xFFD3D7DD.toInt()
        }
        accentFg = foreground(accent)
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

    private fun lin(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    /** WCAG relative luminance (0 black .. 1 white) of an opaque colour. */
    fun luminance(c: Int): Double = 0.2126 * lin(Color.red(c)) + 0.7152 * lin(Color.green(c)) + 0.0722 * lin(Color.blue(c))

    /** WCAG contrast ratio of two opaque colours (1 .. 21): 4.5 for text, 3 for large text and icons. */
    fun contrast(a: Int, b: Int): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    /**
     * Text colour that stays readable on a fill of [bg]: white while it has 3:1 there (the labels on fills are 17 sp
     * medium or larger), else near-black. Measured, not guessed from brightness: the YIQ guess this replaced put
     * white on cyan and green at about 2:1.
     */
    fun onColor(bg: Int): Int = if (contrast(0xFFFFFFFF.toInt(), bg) >= 3.0) 0xFFFFFFFF.toInt() else 0xFF0A0C0F.toInt()

    private fun foreground(c: Int): Int {
        val under = mix(card, c, if (dark) 0.20f else 0.13f) // where accent text sits: a tint over a card
        val toward = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        var t = 0f
        var fg = c
        while (t < 0.8f && contrast(fg, under) < 4.5) {
            t += 0.04f
            fg = mix(c, toward, t)
        }
        return fg
    }

    /** The accent a little lighter (pressed state of accent buttons). */
    fun accentPressed(): Int = mix(accent, if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.16f)

    /** Accent tint for selected chips/rows (with [accentFg] on it). */
    fun accentSoft(): Int = withAlpha(accent, if (dark) 0.20f else 0.13f)
}
