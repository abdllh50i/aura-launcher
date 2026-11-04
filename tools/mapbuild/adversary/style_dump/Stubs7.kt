@file:Suppress("unused")

package com.abdllh.aura.nav.offline

/** As when the unit's tile server is up, with the two addresses marked so that the plate page can fill them in. */
object TileServer {
    val running = true
    val tilesUrl = "TILES_URL/{z}/{x}/{y}.pbf"
    val glyphsUrl = "GLYPHS_URL/{fontstack}/{range}.pbf"
}
