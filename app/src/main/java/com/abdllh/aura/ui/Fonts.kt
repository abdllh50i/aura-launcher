package com.abdllh.aura.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface

/**
 * One family for both scripts, IBM Plex Sans Arabic (SIL OFL, assets/fonts): the three weights of the design, loaded once and
 * handed out by weight or by role (SPEC.md section 3). Every weight has ascent 1.085 em and descent 0.415 em, so one line needs
 * 1.5 em and no metrics patch; every Western digit is 0.600 em wide, so numbers are tabular with no feature flag. Missing glyphs
 * (an emoji) fall back to the system font, and [AText] keeps those from moving a baseline.
 *
 * [LIGHT] to [BOLD] are the old names of the weights; there are three weights now and the old names fall on them.
 */
object Fonts {
    const val LIGHT = 0
    const val REGULAR = 1
    const val MEDIUM = 2
    const val BOLD = 3

    private const val FILE_400 = "fonts/IBMPlexSansArabic-Regular.ttf"
    private const val FILE_500 = "fonts/IBMPlexSansArabic-Medium.ttf"
    private const val FILE_600 = "fonts/IBMPlexSansArabic-SemiBold.ttf"

    private var assets: AssetManager? = null
    private var w400: Typeface? = null
    private var w500: Typeface? = null
    private var w600: Typeface? = null

    /** The UI's language is Arabic: a role takes its Arabic size. Other text inside it keeps that size, whatever its script. */
    @JvmField var arabicUi = true

    fun init(ctx: Context) {
        assets = ctx.applicationContext.assets
        refreshLocale(ctx)
    }

    fun refreshLocale(ctx: Context) {
        arabicUi = ctx.resources.configuration.locales[0].language == "ar"
    }

    /** By weight: 400, 500, 600, or one of the old names. */
    fun get(weight: Int): Typeface = when (weight) {
        BOLD -> semibold()
        MEDIUM -> medium()
        LIGHT, REGULAR -> regular()
        else -> if (weight >= 600) semibold() else if (weight == 500) medium() else regular()
    }

    /** By role; [moving]: the weight it has on a moving screen, which is heavier at night only (section 3). */
    fun of(role: Type.Role, moving: Boolean = false): Typeface = get(if (moving && Palette.dark) role.night else role.weight)

    /**
     * Text as drawn: its real outlines at their real places. Hinted, on a panel of 1 dp = 1 px, stems snap to the pixel and advances to
     * the whole pixel: letters narrow and uneven, a long line 2 px off. Every text of the UI goes through here.
     */
    fun tune(p: android.graphics.Paint) {
        p.isSubpixelText = true
        p.hinting = android.graphics.Paint.HINTING_OFF
    }

    /** The family's Arabic face, whatever the UI's language. There is only the one now. */
    fun arabic(): Typeface? = regular()

    /** The old clock's thin and light cuts: Plex has neither here, and the clock has a role of its own. */
    fun thin(): Typeface = regular()

    fun light(): Typeface = regular()

    private fun regular(): Typeface = w400 ?: load(FILE_400)?.also { w400 = it } ?: Typeface.DEFAULT

    private fun medium(): Typeface = w500 ?: load(FILE_500)?.also { w500 = it } ?: Typeface.DEFAULT

    private fun semibold(): Typeface = w600 ?: load(FILE_600)?.also { w600 = it } ?: Typeface.DEFAULT_BOLD

    /** Null before [init], or if the file cannot be read: a plain system font then, rather than no screen at all. */
    private fun load(file: String): Typeface? = try {
        assets?.let { Typeface.createFromAsset(it, file) }
    } catch (_: Throwable) {
        null
    }
}
