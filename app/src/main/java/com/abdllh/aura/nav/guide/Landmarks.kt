package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.LatLon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Where a place stands against a turn, as the driver comes to it: he passes it and then turns ("بعد"), it is on the corner ("عند"), or he turns first ("قبل"). */
enum class Relation { BEFORE_IT, AT_IT, AFTER_IT }

/** A place chosen to name a turn by. */
class PoiMark(val poi: PoiRow, val relation: Relation, val phrase: List<Part>)

/**
 * "بعد محطة الدريس": a place named at a turn, when the voice has no road name to give. Only places a driver can see from the
 * road, on the side he turns to, and within about 35 m of the turn (maps.md 13 item 3), with more for what has a yard around its
 * point. Search's data is 18 % junk in this province by the tester's count, mostly among the low ranks, and tenants of a mall sit
 * on the mall's point: so a short list of kinds, chains by name, and no coarse position, ever. It fires seldom, and that is right:
 * of 242 real turns on the test trips, 30 have anything at all within 35 m on their side.
 */
object Landmarks {
    /** A chain people steer by: the phrase said for it, whatever way its row is spelled. [cats]: where to look for it, so "الدواء" in any name is not the pharmacy. */
    class Brand(val key: String, val ar: String, val en: String, val cats: Set<String>, val spelled: List<String>)

    private val FUEL = setOf("fuel")
    private val FOOD = setOf("fast_food", "restaurant", "burger", "chicken", "coffee", "pizza")
    private val PHARMACY = setOf("pharmacy")
    private val SHOPS = setOf("supermarket", "grocery", "mall", "dept_store")
    private val BANK = setOf("bank", "atm")

    val BRANDS = listOf(
        Brand("aldrees", "محطة الدريس", "the Aldrees station", FUEL, listOf("الدريس", "aldrees", "al drees", "al-drees")),
        Brand("sasco", "محطة ساسكو", "the SASCO station", FUEL, listOf("ساسكو", "sasco")),
        Brand("aramco", "محطة أرامكو", "the Aramco station", FUEL, listOf("ارامكو", "أرامكو", "aramco")),
        Brand("naft", "محطة نفط", "the Naft station", FUEL, listOf("نفط", "naft")),
        Brand("darb", "محطة درب", "the Darb station", FUEL, listOf("درب", "darb")),
        Brand("petromin", "محطة بترومين", "the Petromin station", FUEL, listOf("بترومين", "petromin")),
        Brand("albaik", "البيك", "Albaik", FOOD, listOf("البيك", "albaik", "al baik", "al-baik")),
        Brand("kudu", "كودو", "Kudu", FOOD, listOf("كودو", "kudu")),
        Brand("herfy", "هرفي", "Herfy", FOOD, listOf("هرفي", "herfy")),
        Brand("mcdonalds", "ماكدونالدز", "McDonald's", FOOD, listOf("ماكدونالدز", "ماكدونالد", "mcdonald")),
        Brand("kfc", "كنتاكي", "KFC", FOOD, listOf("كنتاكي", "kfc")),
        Brand("burgerking", "برجر كنج", "Burger King", FOOD, listOf("برجر كنج", "برغر كنغ", "برجر كينج", "burger king")),
        Brand("hardees", "هارديز", "Hardee's", FOOD, listOf("هارديز", "hardee")),
        Brand("shawarmer", "شاورمر", "Shawarmer", FOOD, listOf("شاورمر", "shawarmer")),
        Brand("starbucks", "ستاربكس", "Starbucks", FOOD, listOf("ستاربكس", "starbucks")),
        Brand("dunkin", "دانكن", "Dunkin'", FOOD, listOf("دانكن", "dunkin")),
        Brand("timhortons", "تيم هورتنز", "Tim Hortons", FOOD, listOf("تيم هورتنز", "تيم هورتونز", "tim hortons")),
        Brand("barns", "بارنز", "Barn's", FOOD, listOf("بارنز", "barn's", "barns")),
        Brand("nahdi", "صيدلية النهدي", "Nahdi pharmacy", PHARMACY, listOf("النهدي", "nahdi")),
        Brand("aldawaa", "صيدلية الدواء", "Al-Dawaa pharmacy", PHARMACY, listOf("الدواء", "dawaa")),
        Brand("panda", "بنده", "Panda", SHOPS, listOf("بنده", "panda")),
        Brand("othaim", "العثيم", "Al Othaim", SHOPS, listOf("العثيم", "othaim")),
        Brand("tamimi", "التميمي", "Tamimi", SHOPS, listOf("التميمي", "tamimi")),
        Brand("danube", "الدانوب", "Danube", SHOPS, listOf("الدانوب", "danube")),
        Brand("carrefour", "كارفور", "Carrefour", SHOPS, listOf("كارفور", "carrefour")),
        Brand("lulu", "لولو", "Lulu", SHOPS, listOf("لولو", "lulu")),
        Brand("rajhi", "مصرف الراجحي", "Al Rajhi Bank", BANK, listOf("الراجحي", "rajhi")),
        Brand("aljazira", "بنك الجزيرة", "Bank AlJazira", BANK, listOf("بنك الجزيرة", "aljazira", "al jazira"))
    )

    /** The kinds a place may name a turn by when it is no chain (it needs a name the voice can read, or a word of [Words] for its kind). */
    private val NAMED_KINDS = setOf("fuel", "mosque", "pharmacy", "hospital", "mall", "supermarket", "bank", "hotel", "university", "police")
    /** With no readable name, the kind alone says enough: there is seldom a second one on a corner. */
    private val KIND_ALONE = mapOf("fuel" to "kind.fuel", "mosque" to "kind.mosque", "pharmacy" to "kind.pharmacy")
    /** What is asked of the world: the named kinds, and where chains are. */
    val CATS: Set<String> = NAMED_KINDS + FOOD + SHOPS + BANK

    /** Ranks under this are mostly junk (the tester: 45-59 is 10 of 31 junk, under that worse). */
    const val MIN_RANK = 45
    private const val REACH_M = 35.0
    /** A yard around the point: from the road a station's point is that far. */
    private const val REACH_YARD_M = 50.0
    private val YARD_KINDS = setOf("fuel", "mosque", "hospital", "mall", "university")
    /** Nearer than this along the road is "on the corner": a place's point is not more exact than that. */
    private const val CORNER_M = 12.0
    /** A name longer than this takes longer to hear than the turn takes to come. */
    private const val NAME_MAX = 24
    /** Box asked of the world around a turn. */
    const val ASK_M = 55.0
    /** Trailing branch numbers ("اسواق المزرعة 3") and what follows a dash ("Tim Hortons - Al Saria Square") say nothing on the road. */
    private val BRANCH_TAIL = Regex("""\s*(\d+|-.*)$""")

    fun brandOf(p: PoiRow): Brand? {
        val hay = (p.name + " " + p.alt.orEmpty()).lowercase()
        return BRANDS.firstOrNull { b -> p.cat in b.cats && b.spelled.any { hay.contains(it) } }
    }

    /** How a place is called in this language, as parts; null when the voice has no way to say it. */
    fun phrase(p: PoiRow, arabic: Boolean): List<Part>? {
        brandOf(p)?.let { return listOf(Part.Clip("brand.${it.key}")) }
        if (p.cat !in NAMED_KINDS) return null
        val own = listOfNotNull(p.name, p.alt).firstOrNull { Names.hasArabic(it) == arabic }?.replace(BRANCH_TAIL, "")?.trim()
        if (own != null && own.isNotEmpty() && own.length <= NAME_MAX) {
            return listOf(Part.Name(if (arabic && p.cat == "fuel" && !own.contains("محطة")) "محطة $own" else own))
        }
        return KIND_ALONE[p.cat]?.let { listOf(Part.Clip(it)) }
    }

    /**
     * The place that best names a turn at [at], the car coming on [bearing] and turning to [side] (+1 right, -1 left), among
     * [rows] from the world: its rank decides, then how near it is. Null when none will do.
     */
    fun pick(at: LatLon, bearing: Double, side: Int, rows: List<PoiRow>, arabic: Boolean): PoiMark? {
        var best: PoiMark? = null
        var bestD = Double.MAX_VALUE
        val k = cos(Math.toRadians(at.lat))
        val h = Math.toRadians(bearing)
        for (p in rows) {
            if (p.coarse || p.rank < MIN_RANK) continue
            val d = Geo.distance(at, p.pos)
            if (d > (if (p.cat in YARD_KINDS) REACH_YARD_M else REACH_M)) continue
            val x = (p.pos.lon - at.lon) * k * 111_320.0
            val y = (p.pos.lat - at.lat) * 110_540.0
            val ahead = x * sin(h) + y * cos(h)
            val right = x * cos(h) - y * sin(h)
            if ((right > 0) != (side > 0)) continue
            val b = best
            if (b != null && (p.rank < b.poi.rank || (p.rank == b.poi.rank && d >= bestD))) continue
            val phrase = phrase(p, arabic) ?: continue
            val rel = if (abs(ahead) < CORNER_M) Relation.AT_IT else if (ahead < 0) Relation.AFTER_IT else Relation.BEFORE_IT
            best = PoiMark(p, rel, phrase)
            bestD = d
        }
        return best
    }
}
