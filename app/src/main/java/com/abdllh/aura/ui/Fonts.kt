package com.abdllh.aura.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface

/**
 * Typeface provider. Latin text uses the Roboto weights shipped with Android (light/regular/medium);
 * Arabic text uses the bundled Readex Pro (SIL OFL) instead of the platform's traditional Naskh, which looks dated in a
 * modern UI: geometric and low-contrast like the عمري logo, wide open shapes that read at a glance. It is a variable
 * font (one file, weights 160–700), so every weight is a real one. Missing glyphs fall back to the system font.
 */
object Fonts {
    const val LIGHT = 0
    const val REGULAR = 1
    const val MEDIUM = 2
    const val BOLD = 3

    private const val ARABIC_FONT = "fonts/ReadexPro-VF.ttf"

    private var assets: AssetManager? = null
    private var arabicMode = false
    private val cache = HashMap<Int, Typeface>()
    private val arabicCache = HashMap<Int, Typeface?>()

    fun init(ctx: Context) {
        assets = ctx.applicationContext.assets
        refreshLocale(ctx)
    }

    fun refreshLocale(ctx: Context) {
        arabicMode = ctx.resources.configuration.locales[0].language == "ar"
        cache.clear()
    }

    fun get(weight: Int): Typeface = cache.getOrPut(weight) { build(weight) }

    /** The bundled Arabic typeface regardless of the UI language (e.g. for the "العربية" option of the language picker). */
    fun arabic(): Typeface? = arabicAt(400)

    /** Very thin numerals for the big clock (Latin digits only). */
    fun thin(): Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)

    fun light(): Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private fun build(weight: Int): Typeface {
        if (arabicMode) {
            arabicAt(when (weight) { LIGHT -> 300; MEDIUM -> 500; BOLD -> 650; else -> 400 })?.let { return it }
        }
        return when (weight) {
            LIGHT -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            BOLD -> Typeface.create("sans-serif", Typeface.BOLD)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }

    /** One weight of the variable font (null if it cannot be read: the system font is used then). */
    private fun arabicAt(wght: Int): Typeface? {
        val am = assets ?: return null
        return arabicCache.getOrPut(wght) {
            try {
                Typeface.Builder(am, ARABIC_FONT).setFontVariationSettings("'wght' $wght").build()
            } catch (_: Throwable) {
                null
            }
        }
    }
}
