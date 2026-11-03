package com.abdllh.aura.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.abdllh.aura.util.Prefs

/**
 * Dark / light / automatic appearance. "Automatic" follows the real daylight where the car is ([Daylight]): dark from
 * civil dusk to civil dawn.
 */
object Theme {
    const val AUTO = "auto"
    const val DARK = "dark"
    const val LIGHT = "light"

    fun isDarkNow(): Boolean = when (Prefs.theme) {
        DARK -> true
        LIGHT -> false
        else -> Daylight.isNightNow()
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
