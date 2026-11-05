package com.abdllh.aura.chrome

import com.abdllh.aura.home.Screens

/**
 * What shows when and where, for what stays over every panel, with no view in it.
 */
internal object ChromeRules {
    /** The tiles' capsule, on every screen. */
    private const val CAPSULE_X = 292
    private const val CAPSULE_W = 716

    /** The centre x of the tile at [index] of the row's six: each in its sixth of the capsule, so they are evenly spaced and the hits fill it. */
    fun tileX(index: Int, count: Int = 6): Int = CAPSULE_X + (CAPSULE_W * (2 * index + 1) + count) / (2 * count)

    /** The song's card: on Maps, with a song. Everywhere else he has the Music tile or Home's widget. */
    fun songCard(panel: String, song: Boolean): Boolean = song && panel == Screens.MAPS

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
}
