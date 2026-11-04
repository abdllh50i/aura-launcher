package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Lane

/**
 * An exit's sign as far as it is real: [number] from `exits` or a "مخرج 11" in the destinations ("" when none), [toward] the
 * places or roads it names that the language can read, [other] the ones in the other script (shown on their own line if at
 * all: "باتجاه King Faisal Street" reads wrong, the route tester's last item). Data is sparse here: 54 of 1126 steps have
 * destinations on the reference trips, 13 an exit number. Never made up.
 */
class ExitSign(val number: String, val toward: List<String>, val other: List<String>) {
    /** What the voice may read of it: a place, not a road number. */
    val place: String? get() = toward.firstOrNull { !it.startsWith(ROAD_AR) && !it.startsWith(ROAD_EN) }

    /** Without what the road's own name and number already say. */
    fun without(road: RoadName): ExitSign = ExitSign(number, toward.filter { it != road.shown && it != "$ROAD_AR ${road.ref}" && it != "$ROAD_EN ${road.ref}" }, other)

    private companion object {
        const val ROAD_AR = "طريق"
        const val ROAD_EN = "Route"
    }
}

object Signs {
    /** Leftovers that say a ramp leaves here and nothing more. */
    private val EMPTY_WORDS = setOf("exit", "exit to", "مخرج", "to")
    private val EXIT_TO = Regex("""(?i)^exit to\s+(.+)$""")
    private val EXIT_NUMBER = Regex("""^(?:مخرج|م|exit)\s*([0-9٠-٩]{1,4}[A-Za-z]?)$""", RegexOption.IGNORE_CASE)
    private val ROAD_NUMBER = Regex("""^[A-Za-z]{0,2}\s?\d{1,5}$""")
    /** Names on Saudi signs in Latin letters, for an Arabic line: not a guess, the same place. */
    private val CITIES_AR = mapOf(
        "riyadh" to "الرياض", "dammam" to "الدمام", "jeddah" to "جدة", "makkah" to "مكة المكرمة", "mecca" to "مكة المكرمة",
        "madinah" to "المدينة المنورة", "medina" to "المدينة المنورة", "jubail" to "الجبيل", "khobar" to "الخبر", "al khobar" to "الخبر",
        "dhahran" to "الظهران", "hofuf" to "الهفوف", "al hofuf" to "الهفوف", "al ahsa" to "الأحساء", "qatif" to "القطيف", "taif" to "الطائف", "tabuk" to "تبوك",
        "buraydah" to "بريدة", "qassim" to "القصيم", "hail" to "حائل", "abha" to "أبها", "jazan" to "جازان", "jizan" to "جازان", "najran" to "نجران",
        "yanbu" to "ينبع", "al kharj" to "الخرج", "kharj" to "الخرج", "al baha" to "الباحة", "khafji" to "الخفجي", "bahrain" to "البحرين", "kuwait" to "الكويت",
        "qatar" to "قطر", "thuwal" to "ثول", "rabigh" to "رابغ", "abqaiq" to "بقيق", "safwa" to "صفوى", "saihat" to "سيهات", "ras tanura" to "رأس تنورة"
    )
    private val CITIES_EN: Map<String, String> = CITIES_AR.entries.associate { (k, v) -> v to k.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } }

    /** The sign of a step, null when it says nothing real. */
    fun read(exits: String, destinations: String, arabic: Boolean): ExitSign? {
        var number = exits.split(';', ',').map { Names.westernDigits(it.trim()) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val toward = ArrayList<String>()
        val other = ArrayList<String>()
        // ("40: Dammam, Jubail": the road's number, then the places. OSRM's form; Valhalla's come without the colon)
        val all = destinations.replace(':', ',').split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        for (raw in all) {
            val d = EXIT_TO.matchEntire(raw)?.groupValues?.get(1)?.trim() ?: raw
            if (d.lowercase() in EMPTY_WORDS) continue
            val n = EXIT_NUMBER.matchEntire(d)
            if (n != null) {
                if (number.isEmpty()) number = Names.westernDigits(n.groupValues[1])
                continue
            }
            val western = Names.westernDigits(d)
            if (ROAD_NUMBER.matches(western)) {
                val road = if (arabic) "طريق $western" else "Route $western"
                if (road !in toward) toward.add(road)
                continue
            }
            val mine = Names.hasArabic(d) == arabic
            val said = if (mine) d else if (arabic) CITIES_AR[d.lowercase()] else CITIES_EN[d]
            if (said != null) { if (said !in toward) toward.add(said) } else if (d !in other) other.add(d)
        }
        if (number.isEmpty() && toward.isEmpty() && other.isEmpty()) return null
        return ExitSign(number, toward.take(2), other.take(2))
    }
}

/** One arm of a junction drawn from where the car comes. [angle]: degrees from straight on, + right. */
class Arm(val angle: Int, val role: Role) {
    enum class Role { WAY_IN, WAY_OUT, OPEN, NO_ENTRY }
}

/**
 * All arms of the junction with the way out marked, for a view another team draws (maps.md 13 item 4). It works where lanes do not:
 * every crossing of an answer has its arms. [lanes]: only when the data has them and they matter, which here is nearly never.
 * [ringArms], at a roundabout: the arms a car passes on the way round, in order; the last is the way out.
 */
class JunctionView(val arms: List<Arm>, val ringArms: List<Arm>, val lanes: List<Lane>)

object Junctions {
    /** Arms this close are one road on a small drawing. */
    private const val SAME_ARM_DEG = 12

    private fun relative(bearing: Int, heading: Int): Int {
        var d = (bearing - heading) % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    /** The step's manoeuvre as a junction; null at the very start of a trip or with fewer than three arms (no choice to show). */
    fun of(s: GuideStep, next: GuideStep?): JunctionView? {
        val c = s.crossings.firstOrNull() ?: return null
        if (c.into !in c.bearings.indices) return null
        // the car comes along the arm it arrives by, in that arm's opposite direction
        val heading = (c.bearings[c.into] + 180) % 360
        val arms = armsOf(c, heading)
        if (arms.size < 3 && !s.entersRing) return null
        val ring = if (!s.entersRing) emptyList() else buildList {
            for ((k, x) in s.crossings.withIndex()) {
                if (k == 0) continue
                for (j in x.bearings.indices) if (j != x.into && j != x.out && x.entry.getOrElse(j) { false }) add(Arm(relative(x.bearings[j], heading), Arm.Role.OPEN))
            }
            val e = next?.takeIf { it.leavesRing }?.crossings?.firstOrNull()
            if (e != null && e.out in e.bearings.indices) add(Arm(relative(e.bearings[e.out], heading), Arm.Role.WAY_OUT))
        }
        return JunctionView(arms, ring, GuideAnswer.lanesOf(s))
    }

    private fun armsOf(c: Crossing, heading: Int): List<Arm> {
        val out = ArrayList<Arm>(c.bearings.size)
        for (j in c.bearings.indices) {
            val role = when {
                j == c.into -> Arm.Role.WAY_IN
                j == c.out -> Arm.Role.WAY_OUT
                c.entry.getOrElse(j) { false } -> Arm.Role.OPEN
                else -> Arm.Role.NO_ENTRY
            }
            out.add(Arm(relative(c.bearings[j], heading), role))
        }
        // a one-way arm beside another arm is that road's other side, not an arm of its own on a small drawing
        return out.filter { a -> a.role != Arm.Role.NO_ENTRY || out.none { b -> b !== a && b.role != Arm.Role.NO_ENTRY && kotlin.math.abs(relative(a.angle, b.angle)) < SAME_ARM_DEG } }
    }
}
