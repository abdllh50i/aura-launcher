package com.abdllh.aura.nav.guide

/**
 * A road's name for one language. [shown]: for the banner, whatever script there is. [spoken]: what the voice may say,
 * null when it cannot read it (a Latin name for the Arabic voice, an Arabic one for the English voice): the voice then
 * falls back to a landmark or the plain instruction. [ref]: the road's number, for a shield, empty when it has none.
 */
class RoadName(val shown: String, val spoken: String?, val ref: String, val kind: Kind) {
    enum class Kind { ARABIC, LATIN, NUMBER, NONE }

    companion object {
        val NONE = RoadName("", null, "", Kind.NONE)
    }
}

/**
 * Picks a road's name from Valhalla's joined lists: `name` "طريق الملك فهد; King Fahd Road", or "جيزان; فرسان; جيزان - فرسان;
 * Jazan - Farasan", `ref` "95; M5". By script for the language first, then the other script, then the number. A `ref` is
 * often not a number ("King Abdul Aziz street, Jubail, saudi arabia" on 3 ways in Jubail): that is no road number.
 */
object Names {
    private val ALL_DIGITS = Regex("""[\d\s\-/]+""")
    private val ROUTE_WORD = Regex("""(?i)(route|road|rd|hwy|highway)\s*(\d{1,5})""")
    private val REF = Regex("""[A-Za-zء-ي]{0,2}\s?\d{1,5}[A-Za-z]?""")
    /** A name that only says a road leaves here. */
    private val NO_NAME = setOf("exit", "exit to", "مخرج", "road", "طريق", "شارع")
    /** A name whose first word already says what it is. */
    private val ROAD_WORDS = setOf("طريق", "شارع", "جسر", "دوار", "نفق", "كوبري", "ممر", "كورنيش", "ميدان", "مخرج", "الطريق", "الشارع", "الجسر", "الدوار", "النفق", "تقاطع")
    /** The voice stops reading a road's name here: it takes longer than the turn does. */
    private const val SPOKEN_MAX = 38
    /** Without a road word a name is said as a road's beyond this long a stretch, else as a street's. */
    private const val ROAD_NOT_STREET_M = 5000.0

    fun hasArabic(s: String) = s.any { it in '؀'..'ۿ' }

    private fun parts(s: String) = s.split(';').map { it.trim() }.filter { it.isNotEmpty() }

    /** "٨" as "8". */
    fun westernDigits(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in '٠'..'٩') '0' + (c - '٠') else if (c in '۰'..'۹') '0' + (c - '۰') else c)
    }

    /** The road numbers in a `ref`: plain numbers first, since those the voice can read. */
    fun refs(ref: String): List<String> {
        val good = parts(ref).map { westernDigits(it) }.filter { REF.matches(it) }
        return good.filter { it.all { c -> c.isDigit() } } + good.filter { !it.all { c -> c.isDigit() } }
    }

    /**
     * [translate]: the Arabic the place index has for a Latin name ([RouteWorld.arabicRoadName]). [stepMetres]: how long the
     * road goes on from here, for what the voice calls a name with no road word in it.
     */
    fun pick(name: String, ref: String, arabic: Boolean, stepMetres: Double = 0.0, translate: (String) -> String? = { null }): RoadName {
        val all = parts(name).filter { it.lowercase() !in NO_NAME }
        val numbers = all.filter { ALL_DIGITS.matches(it) } + all.mapNotNull { ROUTE_WORD.matchEntire(it)?.groupValues?.get(2) }
        val words = all.filter { !ALL_DIGITS.matches(it) && !ROUTE_WORD.matches(it) }
        val refs = refs(ref) + numbers.filter { it !in refs(ref) }
        val shield = refs.firstOrNull().orEmpty()
        val mine = words.filter { hasArabic(it) == arabic }
        val other = words.filter { hasArabic(it) != arabic }
        if (mine.isNotEmpty()) {
            val n = mine.first()
            return RoadName(n, spokenForm(n, arabic, stepMetres), shield, if (arabic) RoadName.Kind.ARABIC else RoadName.Kind.LATIN)
        }
        if (arabic) for (n in other) {
            val t = translate(n)?.takeIf { hasArabic(it) } ?: continue
            return RoadName(t, spokenForm(t, true, stepMetres), shield, RoadName.Kind.ARABIC)
        }
        if (other.isNotEmpty()) return RoadName(other.first(), null, shield, if (arabic) RoadName.Kind.LATIN else RoadName.Kind.ARABIC)
        if (shield.isEmpty()) return RoadName.NONE
        val digits = shield.all { it.isDigit() }
        val shown = if (arabic) "طريق $shield" else "Route $shield"
        return RoadName(shown, if (digits || !arabic) shown else null, shield, RoadName.Kind.NUMBER)
    }

    /** "الخرمة" alone is odd to hear after "لف يمين على": "شارع الخرمة". Only what is said: the banner shows the name as it is. */
    private fun spokenForm(n: String, arabic: Boolean, stepMetres: Double): String? {
        if (!arabic) return n.takeIf { it.length <= SPOKEN_MAX + 8 }
        val first = n.substringBefore(' ')
        val said = if (first in ROAD_WORDS || first.startsWith("شارع") || first.startsWith("طريق")) n
            else if (stepMetres >= ROAD_NOT_STREET_M) "طريق $n" else "شارع $n"
        return said.takeIf { it.length <= SPOKEN_MAX }
    }

    /** The ferry's own line: of "جيزان; فرسان; جيزان - فرسان; Jazan - Farasan" the one that names both ends. */
    fun ferryName(name: String, arabic: Boolean): String {
        val p = parts(name)
        val mine = p.filter { hasArabic(it) == arabic }
        return (mine.firstOrNull { it.contains(" - ") } ?: mine.firstOrNull() ?: p.firstOrNull()).orEmpty()
    }
}
