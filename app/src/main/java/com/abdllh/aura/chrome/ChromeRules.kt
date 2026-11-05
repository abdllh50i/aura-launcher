package com.abdllh.aura.chrome

import com.abdllh.aura.home.Screens

/**
 * What shows when and where, for what stays over every panel, with no view in it.
 */
internal object ChromeRules {
    /** The tiles' capsule, on every screen. */
    private const val CAPSULE_X = 292
    private const val CAPSULE_W = 716
    private const val TILES = 6

    /** The centre x of the tile at [index]: each in its sixth of the capsule, so they are evenly spaced and the hits fill it. */
    fun tileX(index: Int): Int = CAPSULE_X + (CAPSULE_W * (2 * index + 1) + TILES) / (2 * TILES)

    /** The song's card: on Maps, with a song. Everywhere else he has the Music tile or Home's widget. */
    fun songCard(panel: String, song: Boolean): Boolean = song && panel == Screens.MAPS

    /**
     * The strip's speed: whenever the car is Moving and no speed card is on screen. Home on a trip; Home with no trip has it in the search's
     * slot. Maps has the column's card, unless a search or place card lies over it there ([mapsCardHidden]). Every other panel covers the whole screen, on a trip too.
     */
    fun stripSpeed(panel: String, moving: Boolean, trip: Boolean, mapsCardHidden: Boolean): Boolean = when {
        !moving -> false
        panel == Screens.HOME -> trip
        panel == Screens.MAPS -> mapsCardHidden
        else -> true
    }
}
