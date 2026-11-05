package com.abdllh.aura.settings

import android.content.Intent
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.home.Entry
import com.abdllh.aura.home.Screens
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.util.Prefs

/**
 * The name Settings keeps for whoever starts it from outside: Settings itself is a panel of the one window ([SettingsPanel]).
 * With it live what such a start asks for: the page ([EXTRA_PAGE]), and in a debug build the test hooks.
 */
class SettingsActivity : Entry(Screens.SETTINGS) {
    companion object {
        const val EXTRA_PAGE = "page"
        /** The seven pages of section 6.6, in the side list's order. The older names go on meaning where that thing is now. */
        const val PAGE_DISPLAY = 0
        const val PAGE_GENERAL = PAGE_DISPLAY
        const val PAGE_MAPS = 1
        const val PAGE_NAVIGATION = PAGE_MAPS
        const val PAGE_MUSIC = 2
        const val PAGE_VOICE = 3
        const val PAGE_PHONE = 4
        const val PAGE_VEHICLE = 5
        const val PAGE_ABOUT = 6
        /** Sub-pages, by name: the one that opens from the page it lies under. */
        const val PAGE_UPDATE = 100
        const val PAGE_PACK = 101
        const val REQ_MIC = 21
    }

    override fun beforeOpen(i: Intent) {
        if (!BuildConfig.DEBUG) return
        // test hooks:  am start -n .../.settings.SettingsActivity --es api_base http://10.0.2.2:8765 --es repo test/aura
        i.getStringExtra("api_base")?.let { Prefs.apiBase = it }
        i.getStringExtra("repo")?.let { Prefs.updateRepo = it }
        // screenshots:  --es theme dark|light|auto  --es lang ar|en|system  (then press Home)
        i.getStringExtra("theme")?.let { Prefs.theme = it; Theme.refresh() }
        i.getStringExtra("lang")?.let { Prefs.lang = it }
    }
}
