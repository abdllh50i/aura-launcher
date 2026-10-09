package com.abdllh.aura.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.abdllh.aura.util.Prefs
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos

/**
 * Dark / light / automatic appearance. "Automatic" follows the daylight: light between sunrise and sunset,
 * estimated from the date for the latitude of the Gulf (about 05:15-18:45 in June, 06:45-17:15 in December).
 */
object Theme {
    const val AUTO = "auto"
    const val DARK = "dark"
    const val LIGHT = "light"

    fun isDarkNow(): Boolean = when (Prefs.theme) {
        DARK -> true
        LIGHT -> false
        else -> isNight(Calendar.getInstance())
    }

    fun isNight(c: Calendar): Boolean {
        val day = c.get(Calendar.DAY_OF_YEAR)
        val season = cos(2.0 * PI * (day - 172) / 365.0) // 1 at the June solstice, -1 in December
        val sunrise = 6.0 - 0.75 * season
        val sunset = 18.0 + 0.75 * season
        val h = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0
        return h < sunrise || h >= sunset
    }

    /** Loads the tokens of the theme that should be showing now. Returns true when it differs from the loaded one. */
    fun refresh(): Boolean {
        val want = isDarkNow()
        if (want == Palette.dark) return false
        Palette.applyTheme(want)
        return true
    }

    /** Window background and system bar colours for the current theme. */
    @Suppress("DEPRECATION")
    fun window(a: Activity, navBar: Int = Palette.bg) {
        val w = a.window
        w.setBackgroundDrawable(ColorDrawable(Palette.bg))
        w.statusBarColor = Palette.bg
        w.navigationBarColor = navBar
        var flags = w.decorView.systemUiVisibility
        flags = if (Palette.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        w.decorView.systemUiVisibility = flags
    }
}
