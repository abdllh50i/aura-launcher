@file:Suppress("unused")

package com.abdllh.aura.nav.offline

/** As when the unit's tile server is up, with the two addresses marked so that the plate page can fill them in. */
object TileServer {
    val running = true
    val tilesUrl = "TILES_URL/{z}/{x}/{y}.pbf"
    val glyphsUrl = "GLYPHS_URL/{fontstack}/{range}.pbf"
    val placesUrl = "PLACES_URL/{z}/{x}/{y}.pbf"
    val offline = false
}

/** A unit with a pack: the style of the pack's fonts and places. */
object PackTiles {
    val present = true
    val hasFonts = true
    val hasPlaces = true
}

object KsaOutline { fun contains(lat: Double, lon: Double) = true }
