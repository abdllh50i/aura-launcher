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

    val isArabic: Boolean get() = Locale.getDefault().language == "ar"
}
