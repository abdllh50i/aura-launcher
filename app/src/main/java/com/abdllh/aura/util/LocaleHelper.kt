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

    private var appCode: String? = null
    private var appRes: android.content.res.Resources? = null

    /**
     * What [com.abdllh.aura.AuraApp.getResources] answers: the application's resources in AMRI's language. At every configuration change, a window's bounds being one, whether or not it calls
     * onConfigurationChanged, Android sets the process's default locale to the first locale of the application context's resources (ActivityThread.updateLocaleListFromAppContext). With an AMRI
     * language other than the system's, that was the system's: every label's direction (TEXT_DIRECTION_LOCALE), every "ص" and "م", the guidance's words and what the map asks of the default went
     * over to it until the next start. Only the locale is overridden, so whatever else changes in the system still comes through. A language chosen later is answered from then on.
     */
    fun appResources(base: Context): android.content.res.Resources {
        val code = try { Prefs.lang } catch (_: Throwable) { return base.resources }
        if (code != "ar" && code != "en") return base.resources
        appRes?.takeIf { appCode == code }?.let { return it }
        val cfg = Configuration().apply { setLocale(Locale(code)) }
        return base.createConfigurationContext(cfg).resources.also { appCode = code; appRes = it }
    }

    val isArabic: Boolean get() = Locale.getDefault().language == "ar"
}
