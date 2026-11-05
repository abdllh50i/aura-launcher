package com.abdllh.aura.ui

import android.graphics.Color

/**
 * The colours of the current theme: one of the two generated [ThemeColors] ([ColorTokens] gives each by name, `Palette.ink`;
 * the values and what each is for are SPEC.md section 2), and what measures them. [applyTheme] assigns the other set in place;
 * what is on screen follows through [Look] (ui/Look.kt), nothing is rebuilt ([ThemeSwap]).
 *
 * Under "the old names" is what 1.x called them, worked out from the same tokens, so old screens keep building until they are
 * rebuilt; the block goes with the last of them.
 *
 * One accent, the logo's blue: there is no choosing it any more. [accent] as a fill, [accentInk] as text or a glyph.
 */
object Palette : ColorTokens() {
    @JvmField var dark = true

    // ---- the old names
    @JvmField var card = 0        // panels and rows
    @JvmField var card2 = 0       // raised parts: inputs, chips, round buttons
    @JvmField var card3 = 0       // slider tracks, pressed chips
    @JvmField var stroke = 0      // hairlines
    @JvmField var text = 0
    @JvmField var text2 = 0
    @JvmField var text3 = 0
    @JvmField var press = 0       // pressed overlay on transparent buttons
    @JvmField var glass = 0       // floating cards on top of the map
    @JvmField var glassStroke = 0 // their edge
    @JvmField var sheet = 0       // bottom sheets and modal cards
    @JvmField var shadow = 0      // colour of the soft drop shadows
    @JvmField var divider = 0     // lines between parts of one surface
    @JvmField var control = 0     // the "off" track of a switch
    @JvmField var overlay = 0     // the system-level overlays
    @JvmField var overlayStroke = 0
    @JvmField var success = 0
    @JvmField var danger = 0
    @JvmField var accentFg = 0

    /** The one accent. */
    val DEFAULT_ACCENT = Tokens2.NIGHT.accent

    /** What Settings' swatches offer until that page is rebuilt: the one accent. */
    val accents = intArrayOf(DEFAULT_ACCENT)

    init {
        applyTheme(true)
    }

    fun applyTheme(isDark: Boolean) {
        dark = isDark
        c = if (isDark) Tokens2.NIGHT else Tokens2.DAY
        card = cardFlat
        card2 = over(fill, cardFlat)
        card3 = over(fill2, cardFlat)
        stroke = over(line, cardFlat)
        text = ink
        text2 = ink2
        text3 = ink3
        press = fillPress
        glass = withAlpha(cardFlat, 0.96f)
        glassStroke = line
        sheet = cardFlat
        shadow = if (isDark) 0xFF000000.toInt() else 0xFF5A6270.toInt()
        divider = dockEdge
        control = over(track, cardFlat)
        overlay = cardFlat
        overlayStroke = line
        success = green
        danger = red
        accentFg = accentInk
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

    /** A translucent [top] over an opaque [under], as one opaque colour. */
    fun over(top: Int, under: Int): Int {
        val a = Color.alpha(top) / 255f
        return Color.rgb(
            (Color.red(under) + (Color.red(top) - Color.red(under)) * a).toInt(),
            (Color.green(under) + (Color.green(top) - Color.green(under)) * a).toInt(),
            (Color.blue(under) + (Color.blue(top) - Color.blue(under)) * a).toInt()
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
     * Text colour that stays readable on a fill of [bg]: white while it has 3:1 there (the labels on fills are 24 px medium or
     * larger), else near-black. Measured, not guessed from brightness.
     */
    fun onColor(bg: Int): Int = if (contrast(0xFFFFFFFF.toInt(), bg) >= 3.0) 0xFFFFFFFF.toInt() else 0xFF0A0C0F.toInt()

    /** [c] mixed toward white ([lighten]) or black only as far as it takes to reach [ratio] on [on]. */
    fun reach(c: Int, on: Int, ratio: Double, lighten: Boolean): Int {
        val toward = if (lighten) Color.WHITE else Color.BLACK
        var k = 0f
        var out = c
        while (k < 0.8f && contrast(out, on) < ratio) {
            k += 0.04f
            out = mix(c, toward, k)
        }
        return out
    }

    /** The accent a little deeper (a pressed accent). */
    fun accentPressed(): Int = accentLo

    /** Accent tint for selected chips and rows (with [accentInk] on it). */
    fun accentSoft(): Int = selectedSoft

    /**
     * The pairs of section 2 that are two tokens, whichever theme is loaded: what falls under its ratio, as lines to log. Debug
     * builds ask at start ([com.abdllh.aura.AuraApp]) and the gallery shows them.
     */
    fun contrastFaults(): List<String> {
        val out = ArrayList<String>()
        for (t in arrayOf(Tokens2.NIGHT, Tokens2.DAY)) {
            val name = if (t === Tokens2.NIGHT) "night" else "day"
            fun need(what: String, a: Int, b: Int, ratio: Double) {
                val got = contrast(a, b)
                if (got < ratio) out.add("$name: $what ${"%.2f".format(got)} < $ratio")
            }
            need("ink on dock", t.ink, t.dock, 4.5)
            need("ink2 on dock", t.ink2, t.dock, 4.5)
            need("ink3 on dock", t.ink3, t.dock, 3.0)
            need("icon on dock", t.icon, t.dock, 4.5)
            need("ink on card", t.ink, t.cardFlat, 4.5)
            need("ink2 on card", t.ink2, t.cardFlat, 4.5)
            need("white on accent", t.onAccent, t.accent, 4.5)
            need("selected plate on dock", t.selected, t.dock, 3.0)
            need("selected ink on it", t.selectedInk, t.selected, 4.5)
            need("word on its pill", t.wordPillInk, t.wordPill, 4.5)
            need("limit number on its face", t.limitInk, t.limitFace, 4.5)
            need("white on answer", t.onAccent, t.answerLo, 4.5)
            need("white on decline", t.onAccent, t.declineLo, 4.5)
            need("glyph on the bump plate", t.onWarn, t.warn, 4.5)
            need("glyph on the play disc", t.wash, t.ink, 4.5)
        }
        return out
    }
}
