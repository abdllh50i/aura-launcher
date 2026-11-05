package com.abdllh.aura.chrome

import com.abdllh.aura.home.Screens
import kotlin.math.abs

/**
 * What shows when, for what stays over every panel: the tables of SPEC.md sections 1, 5 and 6.0 with no view in them.
 */
internal object ChromeRules {
    /** Between two centres of the row. */
    const val CELL_W = 72

    enum class Glance { PRAYER, MINI }

    /**
     * Prayer on Home and Maps; the mini-player on Apps, Settings and Phone with a song. On a trip always the prayer: the column has the
     * mini already. Music has its own player, so the prayer. The same parked and moving.
     */
    fun glance(panel: String, trip: Boolean, song: Boolean): Glance = when {
        trip || !song -> Glance.PRAYER
        panel == Screens.APPS || panel == Screens.SETTINGS || panel == Screens.PHONE -> Glance.MINI
        else -> Glance.PRAYER
    }

    /** The glance's prayer steps back while moving with more than 45 minutes to go, so it does not compete with the road name. */
    fun prayerQuiet(moving: Boolean, minutes: Int): Boolean = moving && minutes > 45

    /**
     * The strip's speed: whenever the car is Moving and no speed card is on screen. Home on a trip; Home with no trip has it in the search's
     * slot. Maps and Music have the column's card, unless a search or place card lies over it there ([mapsCardHidden]). Phone, Apps and
     * Settings with no trip; on a trip they lie beside the column.
     */
    fun stripSpeed(panel: String, moving: Boolean, trip: Boolean, mapsCardHidden: Boolean): Boolean = when {
        !moving -> false
        panel == Screens.HOME -> trip
        panel == Screens.MAPS -> mapsCardHidden
        panel == Screens.MUSIC -> false
        else -> !trip
    }

    /**
     * The dock in [order], and Home's row, which has no `home` cell. Home's tiles take the first five centres in the same order, so with the
     * home cell first each tile stands one cell to the left of its own dock cell and with the home cell last on it. For each cell, how far
     * in px, and 0 for the home cell itself: going from Home's row to the dock, that part of the row moves by it, tiles and glyphs together.
     */
    fun shifts(order: List<String>): IntArray {
        var tile = 0
        return IntArray(order.size) { i ->
            if (order[i] == Screens.HOME) 0 else (i - tile++) * CELL_W
        }
    }

    /** How much of a dock glyph of [GLYPH] px lies on the selected plate of [PLATE_W] px, by their centres: 0 to 1. */
    fun coverage(glyphCentre: Float, plateCentre: Float): Float {
        val inside = (GLYPH + PLATE_W) / 2f - abs(glyphCentre - plateCentre)
        return (inside / GLYPH).coerceIn(0f, 1f)
    }

    const val GLYPH = 38
    const val PLATE_W = 64

    /** Home's prayer capsule, under 100 minutes in minutes and then in hours: section 5. True: minutes. */
    fun leftInMinutes(minutes: Int): Boolean = minutes < 100

    fun leftHours(minutes: Int): Int = (minutes + 30) / 60
}
