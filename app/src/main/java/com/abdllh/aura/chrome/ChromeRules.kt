package com.abdllh.aura.chrome

import com.abdllh.aura.home.Screens
import kotlin.math.abs

/**
 * What shows when, for what stays over every panel: the tables of SPEC.md sections 1, 5 and 6.0, as CHANGES.md section 2 leaves them, with no view in them.
 */
internal object ChromeRules {
    /** Between two centres of the row, and a cell's hit. */
    const val CELL_W = 80

    /**
     * The mini player at the row's right end: on every screen a song plays. On the Music panel in full nothing, because the song is the whole screen; lying collapsed over the map ([musicLies]: moving, or on a
     * trip, and until he brings it up again) it stays. Say if he wants it back on the panel: this is the one condition.
     */
    fun mini(panel: String, song: Boolean, musicLies: Boolean): Boolean = song && (panel != Screens.MUSIC || musicLies)

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

    /** How much of a dock glyph of [GLYPH] px lies on the selected plate of [PLATE_W] px, by their centres: 0 to 1. */
    fun coverage(glyphCentre: Float, plateCentre: Float): Float {
        val inside = (GLYPH + PLATE_W) / 2f - abs(glyphCentre - plateCentre)
        return (inside / GLYPH).coerceIn(0f, 1f)
    }

    const val GLYPH = 38
    const val PLATE_W = 64
}
