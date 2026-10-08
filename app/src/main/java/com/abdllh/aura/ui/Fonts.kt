package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Typeface

/**
 * Typeface provider. Latin text uses the Roboto weights shipped with Android (light/regular/medium);
 * Arabic text uses the bundled Noto Sans Arabic (SIL OFL) instead of the platform's traditional Naskh,
 * which looks dated in a modern UI. Missing glyphs fall back to the system font automatically.
 */
object Fonts {
    const val LIGHT = 0
    const val REGULAR = 1
    const val MEDIUM = 2
    const val BOLD = 3

    private var arabicBase: Typeface? = null
    private var arabicMode = false
    private val cache = HashMap<Int, Typeface>()

    fun init(ctx: Context) {
        arabicBase = try {
            Typeface.createFromAsset(ctx.assets, "fonts/NotoSansArabic-Regular.ttf")
        } catch (_: Throwable) {
            null
        }
        refreshLocale(ctx)
    }

    fun refreshLocale(ctx: Context) {
        arabicMode = ctx.resources.configuration.locales[0].language == "ar"
        cache.clear()
    }

    fun get(weight: Int): Typeface = cache.getOrPut(weight) { build(weight) }

    /** The bundled Arabic typeface regardless of the UI language (e.g. for the "العربية" option of the language picker). */
    fun arabic(): Typeface? = arabicBase

    /** Very thin numerals for the big clock (Latin digits only). */
    fun thin(): Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)

    fun light(): Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private fun build(weight: Int): Typeface {
        val ar = arabicBase
        if (arabicMode && ar != null) {
            return when (weight) {
                BOLD -> Typeface.create(ar, Typeface.BOLD)
                else -> ar
            }
        }
        return when (weight) {
            LIGHT -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            BOLD -> Typeface.create("sans-serif", Typeface.BOLD)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }
}
