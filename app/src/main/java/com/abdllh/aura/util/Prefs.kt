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
    }

    val raw: SharedPreferences get() = sp

    /** 2.0 has one accent, the logo's blue: what an old install saved stays in its file and is read by no one. */
    var accent: Int
        get() = Palette.DEFAULT_ACCENT
        set(v) { sp.edit().putInt("accent", v).apply() }

    /** The 30 ms tick under a row's or a dock cell's press (SPEC.md section 15). Its switch went in 2.0.0-beta.3: on, unless it was switched off before that. */
    val pressTick: Boolean get() = sp.getBoolean("pressTick", true)

    /** "auto" | "dark" | "light" (see [com.abdllh.aura.ui.Theme]) */
    var theme: String
        get() = sp.getString("theme", "auto") ?: "auto"
        set(v) { sp.edit().putString("theme", v).apply() }

    /** "system" | "ar" | "en" */
    var lang: String
        get() = sp.getString("lang", "system") ?: "system"
        set(v) { sp.edit().putString("lang", v).apply() }

    var clock24: Boolean
        get() = sp.getBoolean("clock24", false)
        set(v) { sp.edit().putBoolean("clock24", v).apply() }

    /** Home and work as typed up to 1.2: only read now (a unit may still hold one; the first use keeps it as a place, Places). */
    val homeAddress: String get() = sp.getString("homeAddress", "") ?: ""
    val workAddress: String get() = sp.getString("workAddress", "") ?: ""

    /** Screen changes animate. Its switch went in 2.0.0-beta.3 and a stored "off" must not hold with no way back: on, whatever the file says (system/Animations). */
    val screenAnimations: Boolean get() = true

    // ---- updater
    var updateRepo: String
        get() = sp.getString("updateRepo", BuildConfig.UPDATE_REPO) ?: BuildConfig.UPDATE_REPO
        set(v) { sp.edit().putString("updateRepo", v.trim()).apply() }

    /** Test hook (debug builds only): alternative API root such as http://10.0.2.2:8765 */
    var apiBase: String
        get() = sp.getString("apiBase", "") ?: ""
        set(v) { sp.edit().putString("apiBase", v.trim()).apply() }

    /** Betas on offer: chosen in a version that had the switch, or a beta is what runs (nothing else could bring the next one). */
    val updateBeta: Boolean get() = sp.getBoolean("updateBeta", false) || BuildConfig.VERSION_NAME.contains('-')

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

    // ---- safety
    var safeModeNotice: Boolean
        get() = sp.getBoolean("safeModeNotice", false)
        set(v) { sp.edit().putBoolean("safeModeNotice", v).apply() }
}
