package com.abdllh.aura.nav

import com.abdllh.aura.home.Entry
import com.abdllh.aura.home.Screens

/**
 * The name Maps had as an activity, for whoever starts it by name ([Entry]): it is [MapsPanel] now. (In a file of its own so that
 * MapsActivity.kt's history goes on in MapsPanel.kt, and work on the old file from other branches follows it there.)
 */
class MapsActivity : Entry(Screens.MAPS) {
    companion object {
        /** Extras: a place to show with its route and Go ([MapsPanel]; sent by [com.abdllh.aura.home.Screens.mapsPlace]). */
        const val EXTRA_LAT = "dest_lat"
        const val EXTRA_LON = "dest_lon"
        const val EXTRA_NAME = "dest_name"
        const val EXTRA_DETAIL = "dest_detail"
        /** false: not for the recent places (Apple's place data: companion.md 2.6). */
        const val EXTRA_KEEP = "dest_keep"
    }
}
