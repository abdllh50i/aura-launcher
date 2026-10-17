package com.abdllh.aura.ui

/*
 * The size tokens of the design system (1.12). Until 1.11 only the colours and the curves were tokens, and the app had
 * grown 27 text sizes, 18 corner radii, 11 elevations and 25 spacing values, most of them a step of 1-2 from another:
 * differences that small are not seen as a choice, only as an inconsistency. A new view takes its sizes from here;
 * docs/DEV.md "Design system (1.12)" says which token is for what.
 */

/**
 * Text roles: a size and a weight together, so one kind of text looks the same on every screen. Sizes are in sp, and
 * on the unit's 160 dpi panel 1 sp is 1 px: 14 is the smallest that reads at a glance from the driver's seat.
 * Build a label with `ctx.label(Type.BODY, colour)`.
 */
object Type {
    /** [tabular]: digits all as wide (numbers that change in place do not jiggle). */
    data class Role(val sp: Float, val weight: Int, val tabular: Boolean = false)

    /** Small print: units, captions under icons, timestamps. */
    @JvmField val CAPTION = Role(14f, Fonts.REGULAR)
    /** Secondary lines: subtitles, hints, list details. */
    @JvmField val FOOTNOTE = Role(15f, Fonts.REGULAR)
    /** Running text, values, dialog explanations. */
    @JvmField val BODY = Role(17f, Fonts.REGULAR)
    /** Buttons and other text that acts. */
    @JvmField val BUTTON = Role(17f, Fonts.MEDIUM)
    /** Row, list and media titles. */
    @JvmField val LABEL = Role(18f, Fonts.MEDIUM)
    /** Card and dialog titles. */
    @JvmField val TITLE = Role(21f, Fonts.MEDIUM)
    /** Sheet and page headings. */
    @JvmField val HEADLINE = Role(24f, Fonts.MEDIUM)
    /** Big numbers beside text (speed, ETA). */
    @JvmField val DISPLAY_S = Role(30f, Fonts.LIGHT, tabular = true)
    /** The guidance distance. */
    @JvmField val DISPLAY_M = Role(42f, Fonts.MEDIUM, tabular = true)
    /** The clock (light, not thin: thin strokes break up under glare). */
    @JvmField val DISPLAY_L = Role(58f, Fonts.LIGHT, tabular = true)

    /** The one size under [CAPTION]: the map's attribution, which must be there but not read while driving. */
    const val ATTRIBUTION_SP = 11f
}

/** Corner radii in dp. A radius inside another is the outer one minus the padding between them. */
object Radius {
    /** Progress bars, badges, the map attribution. */
    const val XS = 8f
    /** List thumbnails and small covers. */
    const val S = 12f
    /** Buttons, inputs, tonal tiles, segmented controls. */
    const val M = 16f
    /** Cards, settings rows, sidebar items, list rows, menus. */
    const val L = 20f
    /** Sheets, dialogs, the home map panel, floating map panels, the media card, the big cover. */
    const val XL = 24f
    /** Half the height whatever the height is (GradientDrawable clamps it): use [Shapes.pill], not a computed radius. */
    const val PILL = 999f
}

/**
 * Elevation levels in dp, for [lift]. On the dark theme a black shadow on a near-black screen is not seen but still
 * costs the GPU a tessellated shadow on every outline change, so [lift] leaves it out there unless the view floats
 * over the map, where it is the edge that keeps a panel apart from the roads.
 */
object Elev {
    /** Resting controls: round buttons, the gear, the car strip, the map panel, dock tiles. */
    const val REST = 2f
    /** Floating over content: the search pill, chips, the guidance banner, the media card, map panels. */
    const val FLOAT = 6f
    /** Above everything: menus, the volume HUD, the call banner, messages. */
    const val OVERLAY = 12f
}

/** Spacing in dp, a 4 dp grid. Gaps that mean the same thing (a sheet's inset, a page's inset) use the same step. */
object Space {
    const val XXS = 2
    const val XS = 4
    const val S = 8
    const val M = 12
    const val L = 16
    const val XL = 24
    const val XXL = 32
    /** The inset of screens, panels and sheets from their edges. */
    const val GUTTER = 16
}

/** Touch targets in dp: a finger in a moving car needs about 56 dp; a smaller visual sits inside a 56 dp view. */
object Touch {
    const val MIN = 56
    /** The main action of a screen (Go, End trip). */
    const val LARGE = 64
}
