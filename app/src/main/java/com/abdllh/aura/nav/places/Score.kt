package com.abdllh.aura.nav.places

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Kinds of `p.kind`. */
internal const val KIND_REGION = 1
internal const val KIND_CITY = 2
internal const val KIND_TOWN = 3
internal const val KIND_VILLAGE = 4
internal const val KIND_DISTRICT = 5
internal const val KIND_POI = 6
internal const val KIND_ROAD = 7

/** Bit of p.src: the position is a geocoder's default (many rows on one point), about right at best. */
internal const val SRC_COARSE = 4

/** A candidate: the columns of table p that search reads, and what its names come to (worked out once: a row can be scored by several readings). */
internal class Row(
    @JvmField val id: Int, @JvmField val kind: Int, @JvmField val name: String, @JvmField val alt: String?, @JvmField val cat: Int,
    @JvmField val lat: Int, @JvmField val lon: Int, @JvmField val area: Int, @JvmField val rank: Int, @JvmField val src: Int
) {
    private var words1: List<String>? = null
    private var words2: List<String>? = null
    private var all: HashSet<String>? = null
    private var allSks: HashSet<String>? = null

    val n1: List<String> get() = words1 ?: Norm.wordSet(name).also { words1 = it }
    val n2: List<String> get() = words2 ?: Norm.wordSet(alt).also { words2 = it }

    /** Both names, and the Latin words also without their article. */
    val names: HashSet<String>
        get() = all ?: HashSet<String>().also { s ->
            for (l in arrayOf(n1, n2)) for (w in l) {
                s.add(w)
                if (!Norm.isAr(w)) s.add(Norm.latinBase(w))
            }
            all = s
        }

    val nameSks: HashSet<String>
        get() = allSks ?: HashSet<String>().also { s ->
            for (w in names) s.addAll(Norm.skeletons(w))
            allSks = s
        }

    /** The two sorted name keys of "one place once". */
    var key1: String? = null
    var key2: String? = null
}

/** A row of table area, and its words. */
internal class Area(@JvmField val id: Int, @JvmField val ar: String, @JvmField val en: String, @JvmField val cityAr: String, @JvmField val cityEn: String) {
    val words = HashSet<String>()
    val sks = HashSet<String>()
    val cityArWords = Norm.wordSet(cityAr)
    val cityEnWords = Norm.wordSet(cityEn)

    init {
        for (l in arrayOf(Norm.wordSet(ar), Norm.wordSet(en), cityArWords, cityEnWords)) for (w in l) {
            words.add(w)
            sks.addAll(Norm.skeletons(w))
        }
    }
}

/** A row of table cat. key is stable, id is not. */
class PlaceCat internal constructor(val id: Int, val key: String, val ar: String, val en: String, val icon: Int)

/** What a reading asks, worked out once for all its candidates. */
internal class PlanFacts(@JvmField val plan: Plan, syn: Syn) {
    @JvmField val asked = HashSet<String>()
    @JvmField val askedLong: List<String>
    @JvmField val askSks = HashSet<String>()
    @JvmField val askStop = HashSet<String>()
    @JvmField val requiredWords = HashSet<String>()
    @JvmField val catQuery = plan.required.isNotEmpty() && plan.required.all { it.cats.isNotEmpty() }

    init {
        for (g in plan.groups) {
            for (w in g.words) {
                asked.add(w)
                if (!Norm.isAr(w)) asked.add(Norm.latinBase(w))
            }
            askSks.addAll(g.sks)
        }
        askedLong = asked.filter { it.length >= 2 }
        for (w in plan.ws) if (syn.role(w) == Syn.ROLE_STOP) askStop.add(w)
        for (g in plan.required) requiredWords.addAll(g.words)
    }
}

/** The score of SEARCH_SPEC.md section 5, as search_ref.py has it: the same operations in the same order, or the third decimal moves. */
internal object Score {
    private const val DEG = Math.PI / 180.0 // Python's math.radians is x * (pi / 180); Math.toRadians was not always
    private const val EXACT_WEIGHT = 22.0
    private val EMPTY = HashSet<String>()

    fun kindWeight(kind: Int) = when (kind) {
        KIND_REGION -> 8.0
        KIND_CITY -> 30.0
        KIND_TOWN -> 22.0
        KIND_VILLAGE -> 10.0
        KIND_DISTRICT -> 14.0
        KIND_POI -> 10.0
        KIND_ROAD -> 8.0
        else -> 8.0
    }

    fun kmBetween(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val p1 = la1 * DEG
        val p2 = la2 * DEG
        val a = sin((p2 - p1) / 2)
        val b = sin((lo2 - lo1) * DEG / 2)
        return 12742.0 * asin(min(1.0, sqrt(a * a + cos(p1) * cos(p2) * (b * b))))
    }

    /**
     * Python's round(): the decimal nearest to the exact binary value, half to even, and the double nearest to that decimal. Ties in the sort go by this value, so it has
     * to be exact; BigDecimal only where the plain sum cannot tell.
     */
    fun pyRound(x: Double, digits: Int): Double {
        val p = when (digits) { 1 -> 10.0; 2 -> 100.0; 3 -> 1000.0; else -> Math.pow(10.0, digits.toDouble()) }
        val v = x * p
        val r = Math.rint(v)
        if (abs(abs(v - r) - 0.5) > 1e-6) return r / p
        return BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    }

    private fun holdsWord(g: Group, names: Set<String>): Boolean {
        if (names.containsAll(g.words)) return true
        for (x in g.alts) if (x in names) return true
        return false
    }

    /**
     * How well a row answers one group, 0..1: what the driver can see counts more than what only the index knew. In a query that only asks for a kind of place,
     * being that kind is most of the answer, and the word in the name as well is all of it. A word for one thing within a kind (role 5: شاورما, كرك, سمسا) works the
     * other way: the name first, the kind only when no name has it.
     */
    fun quality(g: Group, r: Row, areaWords: Set<String>, areaSks: Set<String>, syn: Syn, catQuery: Boolean): Double {
        val names = r.names
        val cat = r.cat
        if (catQuery) {
            val inCat = cat in g.cats
            val named = holdsWord(g, names)
            if (inCat && named) return 1.0
            if (inCat) return if (g.role == Syn.ROLE_SPECIFIC) 0.4 else 0.9
            if (named) return if (g.role == Syn.ROLE_SPECIFIC) 0.95 else 0.8
            return min(0.7, quality(g, r, areaWords, areaSks, syn, false))
        }
        if (g.words.size > 1) {
            if (names.containsAll(g.words)) return 1.0
            if (cat in g.cats) return 0.8
            if (areaWords.containsAll(g.words)) return 0.6
            return 0.5
        }
        val w = g.words[0]
        val wAr = Norm.isAr(w)
        if (w in names || (!wAr && Norm.latinBase(w) in names)) return 1.0
        val canon = syn[w]?.canon
        if (!canon.isNullOrEmpty()) {
            for (n in names) if (syn[n]?.canon == canon) return 1.0
        }
        var best = 0.0
        if (w.length >= 2) {
            for (n in names) if (n.startsWith(w)) best = maxOf(best, 0.8 + 0.09 * w.length / n.length)
        }
        if (cat in g.cats) best = maxOf(best, 0.8)
        if (w.length >= 4) { // one letter off is closer than the same consonants: العتيم is العثيم before it is إطعام, and a letter swapped is closer than a letter missing
            for (n in names) if (Norm.lev1(w, n)) best = maxOf(best, if (n.length == w.length) 0.9 else 0.86)
        }
        val nameSks = r.nameSks
        for (s in g.sks) {
            if (s.length < 2) continue
            if (s in nameSks) {
                // the same consonants: a long word in another spelling or script is that word (Jubail, Jubayl; ستاربكس, Starbucks)
                val otherScript = wAr != names.any { Norm.isAr(it) }
                best = maxOf(best, if (s.length >= 3) 0.92 else if (otherScript) 0.85 else 0.75)
            } else if (g.prefix && s.length >= 3 && nameSks.any { it.startsWith(s) }) {
                best = maxOf(best, 0.65)
            }
        }
        if (best >= 0.75) return best
        if (w in areaWords) return maxOf(best, 0.6)
        if (w.length >= 2 && areaWords.any { it.startsWith(w) }) return maxOf(best, 0.55)
        for (s in g.sks) if (s.length >= 2 && s in areaSks) return maxOf(best, 0.55)
        return maxOf(best, 0.5)
    }

    /** How much of a name the query covers. */
    private fun covered(words: List<String>, f: PlanFacts, r: Row, a: Area?, syn: Syn): Double {
        if (words.isEmpty()) return 0.0
        var hit = 0
        next@ for (w in words) {
            if (w in f.asked) { hit++; continue }
            for (x in f.askedLong) if (x.startsWith(w) || w.startsWith(x)) { hit++; continue@next }
            for (s in Norm.skeletons(w)) if (s.length >= 2 && s in f.askSks) { hit++; continue@next }
            val e = syn[w]
            // road words and titles do not make a name longer, nor do a word that says the row's own kind and its own town: "Nahdi pharmacy" is as much النهدي as "Nahdi"
            if (e != null && (e.role == Syn.ROLE_ROAD || e.role == Syn.ROLE_OPTIONAL || e.role == Syn.ROLE_STOP || e.role == Syn.ROLE_DISTRICT || r.cat in e.cats)) hit++
            else if (a != null && w in a.words) hit++
        }
        return hit.toDouble() / words.size
    }

    /** The query holds the whole name of the row's town. */
    private fun holdsTown(town: List<String>, f: PlanFacts, syn: Syn): Boolean {
        var any = false
        for (w in town) {
            if (syn.role(w) == Syn.ROLE_STOP) continue
            any = true
            if (w !in f.requiredWords) return false
        }
        return any
    }

    /** The score of one candidate by one reading. [km] is null without a position. */
    fun score(r: Row, f: PlanFacts, km: Double?, a: Area?, syn: Syn): Double {
        val plan = f.plan
        val required = plan.required
        val areaWords: Set<String> = a?.words ?: EMPTY
        val areaSks: Set<String> = a?.sks ?: EMPTY
        val kind = r.kind
        val cat = r.cat
        val names = r.names
        val catQuery = f.catQuery
        val namesTown = a != null && required.size > 1 && (holdsTown(a.cityArWords, f, syn) || holdsTown(a.cityEnWords, f, syn))

        // the mean over the groups that do not only say where: their word is in the row's district or town, and not in its name
        var named = 0.0
        var namedCount = 0
        var allSum = 0.0
        var inNameLowest = Double.MAX_VALUE
        var ofKind = true
        for (g in required) {
            val q = quality(g, r, areaWords, areaSks, syn, catQuery)
            allSum += q
            var where = false
            if (q < 0.75) {
                for (w in g.words) {
                    if (w in areaWords || (w.length >= 2 && areaWords.any { it.startsWith(w) })) {
                        where = true
                        break
                    }
                }
            }
            if (!where) {
                named += q
                namedCount++
                if (g.cats.isEmpty() && q < inNameLowest) inNameLowest = q
            }
            if (g.cats.isNotEmpty() && cat !in g.cats) ofKind = false
        }
        val t = if (namedCount > 0) named / namedCount else if (required.isNotEmpty()) allSum / required.size else 0.0
        var c = maxOf(covered(r.n1, f, r, a, syn), covered(r.n2, f, r, a, syn))
        // the name answers: the row is of the kind asked for, and every word that is not there for the kind or for the area is in the name, in full.
        // "مطعم البيك الخبر": البيك, a restaurant. "مطار": a street called طريق المطار is no answer of this sort
        var exact = if (c >= 1.0 && ofKind && inNameLowest != Double.MAX_VALUE && inNameLowest >= 0.9) 1.0 else 0.0
        var rankWeight = 15.0
        if (catQuery) { // "صيدلية": the nearest good pharmacy, not the one that happens to be called only that
            c = 0.0
            exact = 0.0
            rankWeight = 30.0
        }
        var s = 55.0 * t + 15.0 * c + EXACT_WEIGHT * exact + rankWeight * r.rank / 100.0 + kindWeight(kind)
        if (plan.roadHint && kind == KIND_ROAD) s += 20.0
        if (plan.districtHint && kind == KIND_DISTRICT) s += 40.0
        if (km != null) {
            var away = min(75.0, 11.0 * ln(1.0 + km))
            if (exact != 0.0 && (kind == KIND_CITY || kind == KIND_TOWN) && !plan.roadHint) { // a town's name asks for the town, however far: not for a street named after it,
                if (km < 150.0 || everyWordIsAName(required, names)) away = min(away, 6.0) // and not a far town with only the same consonants (KFC, Al Khafji)
            }
            if (exact != 0.0 && kind == KIND_DISTRICT && km < 60.0) away = min(away, 18.0) // a district's whole name asks for the district, before a street called after it; not for one in another town
            if (plan.other == "alias") { // another name of one known thing: how far it is says little, and a row with all of that name is the thing
                away = min(away, 15.0)
                if (plan.ws.all { it in names || it in f.askStop }) s += 30.0
            }
            if (catQuery && cat == syn.airport) away *= 0.3 // one drives far to an airport: the real one before a check-in spot that is nearer
            if (namesTown) away = min(away, 15.0) // "مطار الدمام": the driver said which town, so how far it is from him says little
            s -= away
        }
        return s
    }

    private fun everyWordIsAName(required: List<Group>, names: Set<String>): Boolean {
        for (g in required) for (w in g.words) {
            var found = false
            for (n in names) if (w == n || Norm.lev1(w, n)) { found = true; break }
            if (!found) return false
        }
        return true
    }

    private fun sortedKey(words: List<String>) = words.sorted().joinToString(" ")

    /** One place once: the same name, in either of a row's two names, within 150 m, or the same road name in one town, is one result (the best). */
    fun isSamePlace(r: Row, r2: Row): Boolean {
        val k1 = r.key1 ?: sortedKey(r.n1).also { r.key1 = it }
        val k2 = if (r.alt.isNullOrEmpty()) null else r.key2 ?: sortedKey(r.n2).also { r.key2 = it }
        val o1 = r2.key1 ?: sortedKey(r2.n1).also { r2.key1 = it }
        val o2 = if (r2.alt.isNullOrEmpty()) null else r2.key2 ?: sortedKey(r2.n2).also { r2.key2 = it }
        if (k1 != o1 && k1 != o2 && (k2 == null || (k2 != o1 && k2 != o2))) return false
        val km = kmBetween(r.lat / 1e6, r.lon / 1e6, r2.lat / 1e6, r2.lon / 1e6)
        if (r.kind == KIND_ROAD && r2.kind == KIND_ROAD && (r.area == r2.area || km < 5.0)) return true
        return km < 0.15
    }
}
