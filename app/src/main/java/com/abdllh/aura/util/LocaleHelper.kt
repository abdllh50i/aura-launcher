package com.abdllh.aura.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** In-app language override ("system" | "ar" | "en"). */
object LocaleHelper {
    fun wrap(base: Context): Context {
        val code = try { Prefs.lang } catch (_: Throwable) { "system" }
        if (code == "system") {
            // an earlier override must not leak into the "System" choice
            try { Locale.setDefault(android.content.res.Resources.getSystem().configuration.locales.get(0)) } catch (_: Throwable) { }
            return base
        }
        val locale = when (code) {
            "ar" -> Locale("ar")
            else -> Locale("en")
        }
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }

    /**
     * For the Application's own context. At every update of the configuration (also one with nothing new in it, which no callback hears) the framework sets the process's default Locale from
     * that context's resources (ActivityThread.updateLocaleListFromAppContext), and a label's TEXT_DIRECTION_LOCALE, [com.abdllh.aura.nav.Units] and all the "is it Arabic" of the app read that
     * default: in a window whose language was not the system's the lines turned around after a while, "25 دقيقة" with its number on the wrong side, AM in an Arabic line. Prefs are not
     * up yet here. A process with the user's storage closed (BootHome's) stays as it is.
     */
    fun forApp(base: Context): Context = try {
        val code = base.getSharedPreferences("aura", Context.MODE_PRIVATE).getString("lang", "system") ?: "system"
        if (code == "system") base else {
            val locale = if (code == "ar") Locale("ar") else Locale("en")
            val cfg = Configuration(base.resources.configuration)
            cfg.setLocale(locale)
            cfg.setLayoutDirection(locale)
            base.createConfigurationContext(cfg)
        }
    } catch (_: Throwable) {
        base
    }

    val isArabic: Boolean get() = Locale.getDefault().language == "ar"
}
