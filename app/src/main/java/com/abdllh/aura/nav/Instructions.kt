package com.abdllh.aura.nav

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Arrow shown for a manoeuvre. */
enum class Turn { STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, UTURN, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, ROUNDABOUT, ARRIVE }

/** Turn-by-turn wording in Arabic and English, built from OSRM manoeuvres. */
object Instructions {
    fun turn(s: Step): Turn = when {
        s.type == "arrive" -> Turn.ARRIVE
        s.type == "roundabout" || s.type == "rotary" || s.type == "roundabout turn" -> Turn.ROUNDABOUT
        else -> when (s.modifier) {
            "slight left" -> Turn.SLIGHT_LEFT
            "left" -> Turn.LEFT
            "sharp left" -> Turn.SHARP_LEFT
            "slight right" -> Turn.SLIGHT_RIGHT
            "right" -> Turn.RIGHT
            "sharp right" -> Turn.SHARP_RIGHT
            "uturn" -> Turn.UTURN
            else -> Turn.STRAIGHT
        }
    }

    private fun road(s: Step) = s.name.ifBlank { s.ref }

    private fun ordinalAr(n: Int) = listOf("الأول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن").getOrElse(n - 1) { "رقم $n" }
    private fun ordinalEn(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }

    private fun sideAr(m: String) = if (m.contains("left")) "اليسار" else "اليمين"
    private fun sideEn(m: String) = if (m.contains("left")) "left" else "right"

    private fun turnAr(m: String) = when (m) {
        "slight left" -> "انعطف قليلًا لليسار"
        "left" -> "انعطف يسارًا"
        "sharp left" -> "انعطف بحدة لليسار"
        "slight right" -> "انعطف قليلًا لليمين"
        "right" -> "انعطف يمينًا"
        "sharp right" -> "انعطف بحدة لليمين"
        "uturn" -> "استدر للخلف"
        else -> "استمر للأمام"
    }

    private fun turnEn(m: String) = when (m) {
        "uturn" -> "Make a U-turn"
        "straight", "" -> "Continue straight"
        else -> "Turn $m"
    }

    /** The banner text for an upcoming manoeuvre. */
    fun text(s: Step, arabic: Boolean): String {
        val r = road(s)
        return if (arabic) {
            val onto = if (r.isNotBlank()) " إلى $r" else ""
            when (s.type) {
                "arrive" -> "وصلت إلى وجهتك"
                "depart" -> if (r.isNotBlank()) "انطلق على $r" else "انطلق"
                "new name", "continue" -> if (s.modifier.contains("left") || s.modifier.contains("right")) turnAr(s.modifier) + onto
                    else if (r.isNotBlank()) "استمر على $r" else "استمر للأمام"
                "merge" -> "اندمج نحو ${sideAr(s.modifier)}$onto"
                "on ramp" -> "اسلك المدخل على ${sideAr(s.modifier)}$onto"
                "off ramp" -> "اسلك المخرج على ${sideAr(s.modifier)}$onto"
                "fork" -> "ابقَ على ${sideAr(s.modifier)}$onto"
                "end of road" -> "في نهاية الطريق، ${turnAr(s.modifier)}$onto"
                "roundabout", "rotary" -> if (s.exit > 0) "عند الدوّار، اسلك المخرج ${ordinalAr(s.exit)}$onto" else "ادخل الدوّار$onto"
                "roundabout turn" -> "عند الدوّار، ${turnAr(s.modifier)}$onto"
                "exit roundabout", "exit rotary" -> "اخرج من الدوّار$onto"
                else -> turnAr(s.modifier) + onto
            }
        } else {
            val onto = if (r.isNotBlank()) " onto $r" else ""
            when (s.type) {
                "arrive" -> "You have arrived"
                "depart" -> if (r.isNotBlank()) "Head out on $r" else "Head out"
                "new name", "continue" -> if (s.modifier.contains("left") || s.modifier.contains("right")) turnEn(s.modifier) + onto
                    else if (r.isNotBlank()) "Continue on $r" else "Continue straight"
                "merge" -> "Merge ${sideEn(s.modifier)}$onto"
                "on ramp" -> "Take the ramp on the ${sideEn(s.modifier)}$onto"
                "off ramp" -> "Take the exit on the ${sideEn(s.modifier)}$onto"
                "fork" -> "Keep ${sideEn(s.modifier)}$onto"
                "end of road" -> "At the end of the road, ${turnEn(s.modifier).replaceFirstChar { it.lowercase() }}$onto"
                "roundabout", "rotary" -> if (s.exit > 0) "At the roundabout, take the ${ordinalEn(s.exit)} exit$onto" else "Enter the roundabout$onto"
                "roundabout turn" -> "At the roundabout, ${turnEn(s.modifier).replaceFirstChar { it.lowercase() }}$onto"
                "exit roundabout", "exit rotary" -> "Exit the roundabout$onto"
                else -> turnEn(s.modifier) + onto
            }
        }
    }

    /** Spoken prompt: "In 300 metres, turn right onto …" (or just the instruction when it is due now). */
    fun spoken(s: Step, metres: Double, arabic: Boolean): String {
        val t = text(s, arabic)
        if (metres < 60 || s.type == "arrive") return t
        val d = Units.spokenDistance(metres, arabic)
        return if (arabic) "بعد $d، $t" else "In $d, ${t.replaceFirstChar { it.lowercase() }}"
    }
}

/** Distances, durations and arrival times (always Western digits, like the rest of the UI). */
object Units {
    fun distance(m: Double, arabic: Boolean): String = when {
        m >= 10_000 -> "${(m / 1000).roundToInt()} ${if (arabic) "كم" else "km"}"
        m >= 950 -> String.format(Locale.ROOT, "%.1f %s", m / 1000, if (arabic) "كم" else "km")
        m >= 300 -> "${(m / 50).roundToInt() * 50} ${if (arabic) "م" else "m"}"
        else -> "${((m / 10).roundToInt() * 10).coerceAtLeast(0)} ${if (arabic) "م" else "m"}"
    }

    fun spokenDistance(m: Double, arabic: Boolean): String = when {
        m >= 950 -> {
            val km = String.format(Locale.ROOT, "%.1f", m / 1000).removeSuffix(".0")
            if (arabic) "$km كيلومتر" else "$km kilometres"
        }
        else -> {
            val r = if (m >= 300) (m / 100).roundToInt() * 100 else (m / 50).roundToInt().coerceAtLeast(1) * 50
            if (arabic) "$r متر" else "$r metres"
        }
    }

    fun duration(sec: Double, arabic: Boolean): String {
        val min = (sec / 60).roundToInt().coerceAtLeast(1)
        return if (min < 60) "$min ${if (arabic) "د" else "min"}"
        else "${min / 60} ${if (arabic) "س" else "h"} ${min % 60} ${if (arabic) "د" else "min"}"
    }

    fun arrival(secFromNow: Double, clock24: Boolean): String =
        SimpleDateFormat(if (clock24) "HH:mm" else "h:mm a", Locale.ENGLISH).format(Date(System.currentTimeMillis() + (secFromNow * 1000).toLong()))
}
