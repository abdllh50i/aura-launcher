@file:Suppress("unused")

package com.abdllh.aura.nav

import com.abdllh.aura.ui.Palette

/** GuidanceBanner.kt's Guide, default accent. */
object Guide { fun color(): Int = Palette.DEFAULT_ACCENT }

/** Geo.kt's. */
class LatLon(val lat: Double, val lon: Double)
