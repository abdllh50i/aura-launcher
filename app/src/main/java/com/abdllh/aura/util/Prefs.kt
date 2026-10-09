package com.abdllh.aura.util

import android.content.Context
import android.content.SharedPreferences
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.ui.Palette

/** All launcher preferences in one place. Initialise once from [com.abdllh.aura.AuraApp]. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("aura", Context.MODE_PRIVATE)
        // 1.0 offered a near-white accent, which disappears on the light theme: it became "graphite"
        if (sp.getInt("accent", 0) == 0xFFE8EAED.toInt()) accent = 0xFF8E959F.toInt()
        Palette.accent = accent
    }

    val raw: SharedPreferences get() = sp

    var accent: Int
        get() = sp.getInt("accent", Palette.DEFAULT_ACCENT)
        set(v) { sp.edit().putInt("accent", v).apply(); Palette.accent = v }

    /** "auto" | "dark" | "light" (see [com.abdllh.aura.ui.Theme]) */
    var theme: String
        get() = sp.getString("theme", "auto") ?: "auto"
        set(v) { sp.edit().putString("theme", v).apply() }

    /** "system" | "ar" | "en" */
    var lang: String
        get() = sp.getString("lang", "system") ?: "system"
        set(v) { sp.edit().putString("lang", v).apply() }

    var clock24: Boolean
        get() = sp.getBoolean("clock24", true)
        set(v) { sp.edit().putBoolean("clock24", v).apply() }

    var navPackage: String
        get() = sp.getString("navPackage", "") ?: ""
        set(v) { sp.edit().putString("navPackage", v).apply() }

    var homeAddress: String
        get() = sp.getString("homeAddress", "") ?: ""
        set(v) { sp.edit().putString("homeAddress", v).apply() }

    var workAddress: String
        get() = sp.getString("workAddress", "") ?: ""
        set(v) { sp.edit().putString("workAddress", v).apply() }

    /** Hide Android's top status bar system-wide (swipe down to show it). */
    var hideStatusBar: Boolean
        get() = sp.getBoolean("hideStatusBar", true)
        set(v) { sp.edit().putBoolean("hideStatusBar", v).apply() }

    /** Use Aura's built-in maps for the Maps button and Home/Work (false = an installed navigation app). */
    var builtInMaps: Boolean
        get() = sp.getBoolean("builtInMaps", true)
        set(v) { sp.edit().putBoolean("builtInMaps", v).apply() }

    var dockLabels: Boolean
        get() = sp.getBoolean("dockLabels", false)
        set(v) { sp.edit().putBoolean("dockLabels", v).apply() }

    var hiddenApps: Set<String>
        get() = sp.getStringSet("hiddenApps", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("hiddenApps", HashSet(v)).apply() }

    // ---- updater
    var updateRepo: String
        get() = sp.getString("updateRepo", BuildConfig.UPDATE_REPO) ?: BuildConfig.UPDATE_REPO
        set(v) { sp.edit().putString("updateRepo", v.trim()).apply() }

    /** Test hook (debug builds only): alternative API root such as http://10.0.2.2:8765 */
    var apiBase: String
        get() = sp.getString("apiBase", "") ?: ""
        set(v) { sp.edit().putString("apiBase", v.trim()).apply() }

    var updateAuto: Boolean
        get() = sp.getBoolean("updateAuto", true)
        set(v) { sp.edit().putBoolean("updateAuto", v).apply() }

    var updateBeta: Boolean
        get() = sp.getBoolean("updateBeta", false)
        set(v) { sp.edit().putBoolean("updateBeta", v).apply() }

    /** Time of the last *successful* check. */
    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) { sp.edit().putLong("lastUpdateCheck", v).apply() }

    /** Time of the last attempt, successful or not (keeps the automatic check from hammering GitHub while it fails). */
    var lastUpdateAttempt: Long
        get() = sp.getLong("lastUpdateAttempt", 0L)
        set(v) { sp.edit().putLong("lastUpdateAttempt", v).apply() }

    /** JSON of the newest release found by the last check (restored on start so "update available" survives restarts). */
    var releaseCache: String
        get() = sp.getString("releaseCache", "") ?: ""
        set(v) { sp.edit().putString("releaseCache", v).apply() }

    /** Tag name of the newest release seen by the last check ("" when up to date / never checked). */
    var availableTag: String
        get() = sp.getString("availableTag", "") ?: ""
        set(v) { sp.edit().putString("availableTag", v).apply() }

    // ---- setup / safety
    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(v) { sp.edit().putBoolean("setupDone", v).apply() }

    var setupBannerDismissed: Boolean
        get() = sp.getBoolean("setupBannerDismissed", false)
        set(v) { sp.edit().putBoolean("setupBannerDismissed", v).apply() }

    var safeModeNotice: Boolean
        get() = sp.getBoolean("safeModeNotice", false)
        set(v) { sp.edit().putBoolean("safeModeNotice", v).apply() }

    var adbBridge: Boolean
        get() = sp.getBoolean("adbBridge", true)
        set(v) { sp.edit().putBoolean("adbBridge", v).apply() }
}
