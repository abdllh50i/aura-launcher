package com.abdllh.aura.nav

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Arrow shown for a manoeuvre ([REROUTE]: a new route is being found). */
enum class Turn { STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, UTURN, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, ROUNDABOUT, ARRIVE, REROUTE }

/**
 * Turn-by-turn wording in Arabic and English, built from OSRM manoeuvres. The banner shows a step in parts (the
 * [action], then the road as its own line, [roadLine]); the voice and the notification say it as one sentence
 * ([text], [spoken]). The Arabic is the way a Gulf driver is told the way, in plain MSA: no translated English turns
 * of phrase, no commas inside a sentence that already has one after "بعد 300 متر".
 */
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

    /**
     * Where a roundabout's exit leads, degrees (+ right): measured on the route ([Step.angle]), else from OSRM's
     * modifier; NaN when neither says (the arrow then goes by the exit's number).
     */
    fun exitAngle(s: Step): Float = when {
        !s.angle.isNaN() -> s.angle.toFloat()
        else -> when (s.modifier) {
            "straight" -> 0f
            "slight right" -> 45f
            "right" -> 90f
            "sharp right" -> 135f
            "uturn" -> 180f
            "sharp left" -> -135f
            "left" -> -90f
            "slight left" -> -45f
            else -> Float.NaN
        }
    }

    fun road(s: Step) = s.name.ifBlank { s.ref }

    /** Up to two of a ramp's signposted destinations ("40: Dammam, Jubail" → "Dammam، Jubail"). */
    private fun toward(s: Step, arabic: Boolean): String =
        s.destinations.substringAfter(':').split(',', ';').map { it.trim() }.filter { it.isNotBlank() }.distinct().take(2)
            .joinToString(if (arabic) "، " else ", ")

    private fun ordinalAr(n: Int) = listOf("الأول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن").getOrElse(n - 1) { "رقم $n" }
    private fun ordinalEn(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }

    private fun isLeft(m: String) = m.contains("left")

    private fun turnAr(m: String) = when (m) {
        "slight left" -> "انعطف يسارًا قليلًا"
        "left" -> "انعطف يسارًا"
        "sharp left" -> "انعطف يسارًا بحدّة"
        "slight right" -> "انعطف يمينًا قليلًا"
        "right" -> "انعطف يمينًا"
        "sharp right" -> "انعطف يمينًا بحدّة"
        "uturn" -> "استدر للخلف"
        else -> "استمر مباشرةً"
    }

    private fun turnEn(m: String) = when (m) {
        "uturn" -> "Make a U-turn"
        "slight left" -> "Bear left"
        "slight right" -> "Bear right"
        "sharp left" -> "Make a sharp left"
        "sharp right" -> "Make a sharp right"
        "left" -> "Turn left"
        "right" -> "Turn right"
        else -> "Continue straight"
    }

    /** A "continue" or "new name" step that is really a turn (OSRM also gives a U-turn at a dead end this way). */
    private fun turning(m: String) = m.contains("left") || m.contains("right") || m == "uturn"

    /** What to do, without the road: the banner's main line ("انعطف يمينًا", "اسلك المخرج الثاني"). */
    fun action(s: Step, arabic: Boolean): String {
        val m = s.modifier
        val side = isLeft(m)
        return if (arabic) when (s.type) {
            "arrive" -> when { m.contains("left") -> "وجهتك على اليسار"; m.contains("right") -> "وجهتك على اليمين"; else -> "وجهتك أمامك" }
            "depart" -> "انطلق"
            "new name", "continue" -> if (turning(m)) turnAr(m) else "استمر مباشرةً"
            "merge" -> if (side) "اندمج يسارًا" else "اندمج يمينًا"
            "on ramp" -> "اسلك المدخل على ${if (side) "اليسار" else "اليمين"}"
            "off ramp" -> "اسلك المخرج${if (s.exits.isNotBlank()) " ${s.exits}" else ""} على ${if (side) "اليسار" else "اليمين"}"
            "fork" -> if (side) "الزم اليسار" else "الزم اليمين"
            "end of road" -> "في نهاية الطريق ${turnAr(m)}"
            "roundabout", "rotary" -> if (s.exit > 0) "اسلك المخرج ${ordinalAr(s.exit)}" else "ادخل الدوّار"
            "roundabout turn" -> "عند الدوّار ${turnAr(m)}"
            "exit roundabout", "exit rotary" -> "اخرج من الدوّار"
            else -> turnAr(m)
        } else when (s.type) {
            "arrive" -> when { m.contains("left") -> "Your destination is on the left"; m.contains("right") -> "Your destination is on the right"; else -> "Your destination is ahead" }
            "depart" -> "Head out"
            "new name", "continue" -> if (turning(m)) turnEn(m) else "Continue straight"
            "merge" -> "Merge ${if (side) "left" else "right"}"
            "on ramp" -> "Take the ramp on the ${if (side) "left" else "right"}"
            "off ramp" -> if (s.exits.isNotBlank()) "Take exit ${s.exits} on the ${if (side) "left" else "right"}" else "Take the exit on the ${if (side) "left" else "right"}"
            "fork" -> "Keep ${if (side) "left" else "right"}"
            "end of road" -> "At the end of the road, ${turnEn(m).replaceFirstChar { it.lowercase() }}"
            "roundabout", "rotary" -> if (s.exit > 0) "Take the ${ordinalEn(s.exit)} exit" else "Enter the roundabout"
            "roundabout turn" -> "At the roundabout, ${turnEn(m).replaceFirstChar { it.lowercase() }}"
            "exit roundabout", "exit rotary" -> "Exit the roundabout"
            else -> turnEn(m)
        }
    }

    private fun signposted(s: Step) = s.type == "on ramp" || s.type == "off ramp" || s.type == "fork" || s.type == "merge"

    /**
     * The banner's second line: the road the manoeuvre leads onto; for a ramp without one, where it goes ("باتجاه
     * الدمام"); on arrival, the destination's name ([destination]). Empty when there is nothing to say.
     */
    fun roadLine(s: Step, arabic: Boolean, destination: String? = null): String {
        if (s.type == "arrive") return destination.orEmpty()
        val r = road(s)
        if (r.isNotBlank()) return r
        val t = if (signposted(s)) toward(s, arabic) else ""
        return if (t.isBlank()) "" else if (arabic) "باتجاه $t" else "Toward $t"
    }

    /** The whole instruction as a sentence (the notification, the home map before 1.12, the voice). */
    fun text(s: Step, arabic: Boolean): String = sentence(s, arabic, withRoad = true, withToward = true)

    private fun sentence(s: Step, arabic: Boolean, withRoad: Boolean, withToward: Boolean): String {
        val r = if (withRoad) road(s) else ""
        val a = action(s, arabic)
        if (s.type == "arrive") return a
        val roundabout = s.type == "roundabout" || s.type == "rotary"
        val head = when {
            roundabout && s.exit > 0 -> if (arabic) "عند الدوّار $a" else "At the roundabout, ${a.replaceFirstChar { it.lowercase() }}"
            else -> a
        }
        if (s.type == "depart") return when {
            r.isBlank() -> head
            arabic -> "انطلق على $r"
            else -> "Head out on $r"
        }
        if (r.isNotBlank()) return if (arabic) "$head إلى $r" else "$head onto $r"
        val t = if (withToward && signposted(s)) toward(s, arabic) else ""
        return if (t.isBlank()) head else if (arabic) "$head باتجاه $t" else "$head toward $t"
    }

    /**
     * Spoken prompt: "بعد 300 متر، انعطف يمينًا إلى …" (or just the instruction when it is due now), and [then] the
     * manoeuvre right after it when that one follows closely. A name the voice cannot read (a Latin name for the
     * Arabic voice, an Arabic one for the English voice) is left out, and so is a ramp's list of destinations (too
     * long to listen to): the banner shows both.
     */
    fun spoken(s: Step, metres: Double, arabic: Boolean, then: Step? = null): String {
        val t = sentence(s, arabic, withRoad = readable(road(s), arabic), withToward = false)
        val now = if (metres < 60) t else {
            val d = Units.spokenDistance(metres, arabic)
            if (arabic) "بعد $d، $t" else "In $d, ${t.replaceFirstChar { it.lowercase() }}"
        }
        if (then == null) return now
        val next = action(then, arabic)
        return if (arabic) "$now، ثم $next" else "$now, then ${next.replaceFirstChar { it.lowercase() }}"
    }

    /** On a long stretch: "استمر على طريق الجبيل لمسافة 12 كيلومترًا". */
    fun continueFor(road: String, metres: Double, arabic: Boolean): String {
        val d = Units.spokenDistance(metres, arabic)
        val on = readable(road, arabic)
        return if (arabic) (if (on) "استمر على $road لمسافة $d" else "استمر لمسافة $d")
        else (if (on) "Continue on $road for $d" else "Continue for $d")
    }

    /** "وصلت إلى مستشفى الجبيل" — with the destination's name when it has a short one the voice can read. */
    fun arrived(name: String?, arabic: Boolean): String {
        val n = name?.trim().orEmpty()
        val named = n.isNotBlank() && n.length <= 28 && readable(n, arabic)
        return if (arabic) (if (named) "وصلت إلى $n" else "وصلت إلى وجهتك")
        else (if (named) "You have arrived at $n" else "You have arrived")
    }

    private fun readable(name: String, arabic: Boolean) = name.isNotBlank() && Places.isArabic(name) == arabic
}

/** Distances, durations and arrival times (always Western digits, like the rest of the UI). */
object Units {
    fun distance(m: Double, arabic: Boolean): String = when {
        m >= 10_000 -> "${(m / 1000).roundToInt()} ${if (arabic) "كم" else "km"}"
        m >= 950 -> "${String.format(Locale.ROOT, "%.1f", m / 1000).removeSuffix(".0")} ${if (arabic) "كم" else "km"}"
        m >= 300 -> "${(m / 50).roundToInt() * 50} ${if (arabic) "م" else "m"}"
        else -> "${((m / 10).roundToInt() * 10).coerceAtLeast(0)} ${if (arabic) "م" else "m"}"
    }

    /**
     * A distance as it is said: "كيلومتر ونصف", "كيلومترين", "5 كيلومترات", "12 كيلومترًا" (the voice reads "1.5
     * كيلومتر" as "one point five kilometre"). Half kilometres below 3 km, whole ones above.
     */
    fun spokenDistance(m: Double, arabic: Boolean): String {
        if (m < 950) {
            val r = if (m >= 300) (m / 100).roundToInt() * 100 else (m / 50).roundToInt().coerceAtLeast(1) * 50
            return if (arabic) "$r ${if (r % 100 in 11..99) "مترًا" else "متر"}" else "$r metres"
        }
        val halves = if (m < 3000) (m / 500).roundToInt() else (m / 1000).roundToInt() * 2
        val whole = halves / 2
        val half = halves % 2 == 1
        if (!arabic) {
            val km = if (half) "$whole.5" else "$whole"
            return if (km == "1") "1 kilometre" else "$km kilometres"
        }
        val base = when {
            whole == 1 -> "كيلومتر واحد"
            whole == 2 -> "كيلومترين"
            whole % 100 in 3..10 -> "$whole كيلومترات"
            whole % 100 in 11..99 -> "$whole كيلومترًا"
            else -> "$whole كيلومتر"
        }
        if (!half) return base
        return if (whole == 1) "كيلومتر ونصف" else "$base ونصف"
    }

    /** "12 دقيقة", "5 دقائق", "1 س 20 د" / "12 min", "1 h 20 min". */
    fun duration(sec: Double, arabic: Boolean): String {
        val min = (sec / 60).roundToInt().coerceAtLeast(1)
        if (min < 60) return if (arabic) "$min ${if (min in 3..10) "دقائق" else "دقيقة"}" else "$min min"
        val h = min / 60
        val m = min % 60
        return if (arabic) "$h س" + (if (m > 0) " $m د" else "") else "$h h" + (if (m > 0) " $m min" else "")
    }

    /** The clock at arrival; on the 12-hour clock with "ص" / "م" in Arabic (an English "PM" in an Arabic line read wrong). */
    fun arrival(secFromNow: Double, clock24: Boolean, arabic: Boolean = Locale.getDefault().language == "ar"): String {
        val at = Date(System.currentTimeMillis() + (secFromNow * 1000).toLong())
        if (clock24) return SimpleDateFormat("HH:mm", Locale.ENGLISH).format(at)
        val pm = Calendar.getInstance().apply { time = at }.get(Calendar.AM_PM) == Calendar.PM
        return SimpleDateFormat("h:mm", Locale.ENGLISH).format(at) + if (arabic) (if (pm) " م" else " ص") else (if (pm) " PM" else " AM")
    }
}
