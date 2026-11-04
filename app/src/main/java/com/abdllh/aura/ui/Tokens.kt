package com.abdllh.aura.ui

import kotlin.math.ceil
import kotlin.math.floor

/*
 * The size tokens of the design system. Their numbers come from design/tokens/tokens.json through the generated
 * DesignTokens.kt (node design/tokens/gen_tokens_kt.mjs): a new view takes its sizes from here. SPEC.md section 2 says which
 * token is for what; docs/DEV.md "Design system (2.0)" how they are used.
 */

/**
 * Text roles: a size, a weight and a line box together, so one kind of text looks the same on every screen. On the unit's
 * 160 dpi panel 1 sp is 1 px. Build a label with `ctx.label(Type.rowLabel, Palette.ink)`. The roles themselves are generated
 * ([TypeRoles]); the upper-case names are the 1.x roles, kept on their nearest new role while old screens use them.
 */
object Type : TypeRoles() {
    /**
     * [size] in the Arabic UI, [latin] in the English one (Latin words in the Arabic UI use the Arabic size, Arabic words in
     * the English UI the English size), [weight] 400, 500 or 600, [night] its weight at night on moving screens, [line] the
     * line box the layout tables give.
     */
    class Role(
        @JvmField val name: String,
        @JvmField val size: Int,
        @JvmField val weight: Int,
        @JvmField val line: Int,
        @JvmField val latin: Int,
        @JvmField val night: Int = weight
    ) {
        /** Its size in the UI's language, in px. */
        val px: Int get() = if (Fonts.arabicUi) size else latin

        val sp: Float get() = px.toFloat()

        /** A one-line label's view height: 1.5 em rounded up (the font's ascent and descent), whatever the line box. */
        val viewH: Int get() = ceil(px * 1.5f).toInt()

        /** The view's top from the line box's top, rounded down: 33 in 38 gives 2, 108 over 78 gives -15. */
        val viewTop: Int get() = floor((line - viewH) / 2f).toInt()

        /** The baseline from the line box's top. */
        val baseline: Float get() = line / 2f + 0.335f * px
    }

    /** Small print: units, captions under icons, timestamps. */
    @JvmField val CAPTION = rowHint
    /** Secondary lines: subtitles, hints, list details. */
    @JvmField val FOOTNOTE = rowHint
    /** Running text, values, dialog explanations. */
    @JvmField val BODY = rowValue
    /** Buttons and other text that acts. */
    @JvmField val BUTTON = button
    /** Row, list and media titles. */
    @JvmField val LABEL = rowLabel
    /** Card and dialog titles. */
    @JvmField val TITLE = stateTitle
    /** Sheet and page headings. */
    @JvmField val HEADLINE = pageTitle
    /** Big numbers beside text (speed, ETA). */
    @JvmField val DISPLAY_S = etaValue
    /** The guidance distance. */
    @JvmField val DISPLAY_M = dist
    /** The clock. */
    @JvmField val DISPLAY_L = clockHome

    /** The one size under the floor of 20: the map's attribution, which must be there but not read while driving. */
    const val ATTRIBUTION_SP = 14f
}

/**
 * Corner radii in dp, visual: every corner of 12 or more is continuous and baked ([Plates]); a live round rect only under 12 or
 * on Canvas (the word pill). The upper-case names are the 1.x steps, on the nearest of the design's.
 */
object Radius {
    const val CARD = Tokens2.R_card.toFloat()
    const val TILE = Tokens2.R_tile.toFloat()
    const val INNER = Tokens2.R_inner.toFloat()
    const val MID = Tokens2.R_mid.toFloat()
    const val SMALL = Tokens2.R_small.toFloat()

    /** Progress bars, badges, the map attribution. */
    const val XS = Tokens2.R_gear.toFloat()
    /** List thumbnails and small covers. */
    const val S = SMALL
    /** Buttons, inputs, tonal tiles, segmented controls. */
    const val M = INNER
    /** Cards, settings rows, sidebar items, list rows, menus. */
    const val L = TILE
    /** Sheets, dialogs, the home map panel, floating map panels, the media card, the big cover. */
    const val XL = CARD
    /** Half the height whatever the height is (GradientDrawable clamps it): use [Shapes.pill], not a computed radius. */
    const val PILL = 999f
}

/**
 * Elevation levels in dp, for [lift]: only old screens. Every shadow of the new surfaces is baked (SPEC.md section 4); this goes
 * with the last old screen.
 */
object Elev {
    /** Resting controls: round buttons, the gear, the car strip, the map panel, dock tiles. */
    const val REST = 2f
    /** Floating over content: the search pill, chips, the guidance banner, the media card, map panels. */
    const val FLOAT = 6f
    /** Above everything: menus, the volume HUD, the call banner, messages. */
    const val OVERLAY = 12f
}

/** Spacing in dp, a 4 dp grid. [GUTTER] and [MARGIN]: the grid of section 1. */
object Space {
    const val XXS = 2
    const val XS = 4
    const val S = 8
    const val M = 12
    const val L = 16
    const val XL = 24
    const val XXL = 32
    /** The content's margin from the screen's edge. */
    const val MARGIN = Tokens2.MARGIN_X
    /** Between two cards. */
    const val CARD_GAP = Tokens2.GUTTER
    /** The inset of screens, panels and sheets from their edges. */
    const val GUTTER = Tokens2.MARGIN_X
}

/** Touch targets in dp: at least [MIN] everywhere, [LARGE] for what is used all the time; a smaller visual sits inside. */
object Touch {
    const val MIN = Tokens2.TOUCH_MIN
    /** The main action of a screen (Go, End trip). */
    const val LARGE = Tokens2.TOUCH_FREQUENT
}
