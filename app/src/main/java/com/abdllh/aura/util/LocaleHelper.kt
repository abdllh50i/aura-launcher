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
     * A configuration from the system (any: a new network code, a display) makes Android set Java's default back to the system's language, and in the one window no new activity comes to set it
     * again. All that asks the default would follow it: the strip's ص and م, every label's direction (`TEXT_DIRECTION_LOCALE`, so `USB والملفات` and `20 أغنية` read backwards). [AuraApp] calls this.
     */
    fun holdDefault() {
        val code = try { Prefs.lang } catch (_: Throwable) { return }
        if (code == "system") return
        val want = if (code == "ar") "ar" else "en"
        if (Locale.getDefault().language == want) return
        android.util.Log.i("AuraLocale", "${Locale.getDefault()} came back as the default: $want again")
        Locale.setDefault(Locale(want))
    }

    val isArabic: Boolean get() = Locale.getDefault().language == "ar"
}
