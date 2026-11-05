package com.abdllh.aura.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.abdllh.aura.util.Prefs

/**
 * Dark / light / automatic appearance. "Automatic" follows the real sun where the car is ([Daylight]): night from sunset to sunrise.
 * A change of it goes through [ThemeSwap]; [refresh] alone only assigns the tokens.
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
        changed?.invoke()
        return true
    }

    /** The tokens changed, by whichever path: what lasts through every old screen's own restyle (the chrome) follows from here. */
    var changed: (() -> Unit)? = null

    /**
     * Window background and system bar colours for the current theme. [fill] false: no background at all, what is in the
     * window draws its own (the one window of 2.0: its panels' rooms, and the map where they leave it).
     */
    @Suppress("DEPRECATION")
    fun window(a: Activity, navBar: Int = Palette.bg, fill: Boolean = true) {
        val w = a.window
        w.setBackgroundDrawable(if (fill) ColorDrawable(Palette.bg) else null)
        w.statusBarColor = Palette.bg
        w.navigationBarColor = navBar
        var flags = w.decorView.systemUiVisibility
        flags = if (Palette.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        w.decorView.systemUiVisibility = flags
    }
}
