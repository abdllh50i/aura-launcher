package com.abdllh.aura.util

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formatting helpers. Digits are always Western (0-9), also in Arabic, to keep numerals crisp and consistent. */
object Fmt {
    fun locale(): Locale {
        val l = Locale.getDefault()
        return if (l.language == "ar") Locale.forLanguageTag("ar-u-nu-latn") else l
    }

    fun time(d: Date, is24: Boolean): String =
        SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.ENGLISH).format(d)

    fun ampm(d: Date): String =
        SimpleDateFormat("a", locale()).format(d)

    /**
     * A time as SPEC.md section 3 writes it, for the strip and the ETA: in Arabic an isolated left-to-right run followed by م or ص (7:38 م), in
     * English 7:38 PM, with [is24] 19:38 and no suffix. The big clock on Home has no suffix: [time].
     */
    fun clock(d: Date, is24: Boolean): String {
        val t = time(d, is24)
        if (is24) return t
        return if (Locale.getDefault().language == "ar") "\u2066$t\u2069 ${SimpleDateFormat("a", locale()).format(d)}"
        else "$t ${SimpleDateFormat("a", Locale.ENGLISH).format(d)}"
    }

    fun date(d: Date): String {
        val loc = locale()
        val pattern = DateFormat.getBestDateTimePattern(loc, "EEEEdMMMM")
        return SimpleDateFormat(pattern, loc).format(d)
    }

    fun dateTime(ms: Long): String {
        if (ms <= 0) return "-"
        val loc = locale()
        val pattern = DateFormat.getBestDateTimePattern(loc, "dMMMhm")
        return SimpleDateFormat(pattern, loc).format(Date(ms))
    }

    fun bytes(n: Long): String {
        if (n < 0) return "-"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            n >= gb -> String.format(Locale.ENGLISH, "%.2f GB", n / gb)
            n >= mb -> String.format(Locale.ENGLISH, "%.1f MB", n / mb)
            n >= kb -> String.format(Locale.ENGLISH, "%.0f KB", n / kb)
            else -> "$n B"
        }
    }

    fun percent(p: Float): String = String.format(Locale.ENGLISH, "%d%%", (p * 100).toInt().coerceIn(0, 100))
}
