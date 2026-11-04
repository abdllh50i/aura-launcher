package com.abdllh.aura.nav.places

/** A row of table syn: what a word means (SEARCH_SPEC.md section 3). */
internal class SynEntry(@JvmField val cats: IntArray, @JvmField val role: Int, @JvmField val canon: String)

/** Table syn: normalised word or phrase to categories, role and canonical word. Fill it in `ORDER BY w`: [byCanon] keeps that order. */
internal class Syn(@JvmField val airport: Int) {
    private val words = HashMap<String, SynEntry>(1536)
    private val canons = HashMap<String, ArrayList<String>>()
    var maxLen = 1
        private set

    fun add(w: String, cats: String?, role: Int, canon: String?) {
        val ids = if (cats.isNullOrEmpty()) NO_CATS else cats.trim().split(WHITE).filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
        words[w] = SynEntry(ids, role, canon ?: "")
        var spaces = 1
        for (c in w) if (c == ' ') spaces++
        if (spaces > maxLen) maxLen = spaces
        if (!canon.isNullOrEmpty() && (role == 0 || role == ROLE_SPECIFIC) && ' ' !in w) canons.getOrPut(canon) { ArrayList(2) }.add(w)
    }

    operator fun get(w: String): SynEntry? = words[w]

    /** The word's role, 0 when it has no entry. */
    fun role(w: String): Int = words[w]?.role ?: 0

    fun byCanon(canon: String): List<String> = canons[canon] ?: emptyList()

    companion object {
        const val ROLE_ROAD = 1
        const val ROLE_OPTIONAL = 2
        const val ROLE_STOP = 3
        const val ROLE_DISTRICT = 4
        const val ROLE_SPECIFIC = 5
        const val ROLE_FILLER = 6
        const val ROLE_LETTER = 7
        const val ROLE_ALIAS = 8
        val NO_CATS = IntArray(0)
        private val WHITE = Regex("\\s+")
    }
}

/** One thing the query asks for: a word or phrase, with the categories it can also mean. */
internal class Group(
    @JvmField val words: List<String>,
    @JvmField val cats: IntArray,
    @JvmField var role: Int,
    @JvmField val prefix: Boolean,
    @JvmField var optional: Boolean,
    /** The same word in its other spellings (شاورمه, شاورما). */
    @JvmField val alts: List<String> = emptyList()
) {
    @JvmField val sks: List<String> = if (words.size == 1) Norm.skeletons(words[0]) else emptyList()
}

/** A reading of a query. */
internal class Plan(@JvmField val ws: List<String>, @JvmField val typing: Boolean) {
    @JvmField var groups: List<Group> = emptyList()
    @JvmField var required: List<Group> = emptyList()
    @JvmField var roadHint = false
    @JvmField var districtHint = false
    @JvmField val fillers = ArrayList<Group>(2)
    /** What kind of other reading this is, "" for the query as written. */
    @JvmField var other = ""
    /** With a value: the first stages also take skeletons of at least so many letters. */
    @JvmField var skeletonFrom = 0
}

/** The query plan and the FTS4 strings of SEARCH_SPEC.md section 4, as search_ref.py has them. */
internal object QueryPlan {
    private const val ROAD = Syn.ROLE_ROAD
    private const val OPTIONAL = Syn.ROLE_OPTIONAL
    private const val STOP = Syn.ROLE_STOP
    private const val DISTRICT = Syn.ROLE_DISTRICT
    private const val SPECIFIC = Syn.ROLE_SPECIFIC
    private const val FILLER = Syn.ROLE_FILLER
    private const val LETTER = Syn.ROLE_LETTER
    private const val ALIAS = Syn.ROLE_ALIAS

    /** "لاقرب", "لصيدليه", "لمول" as a recogniser writes them: the word with ل in front of it, when that word means something and the whole does not. */
    private fun withoutLi(w: String, syn: Syn): String {
        if (w.length >= 4 && w[0] == 'ل' && syn[w] == null) {
            val rest = w.substring(1)
            val e = syn[rest]
            if (e != null && e.role != LETTER && e.role != ALIAS && (w.length >= 5 || e.role != STOP)) return rest
        }
        return w
    }

    /** Python's `str.isspace()` for the last character (Kotlin's also takes every Zs; only NEL is missing). */
    private fun isSpace(c: Char) = c.isWhitespace() || c == '\u0085'

    /** Groups of a query. Words that only a road, a title or a company name carry are optional; stop words vanish; words of speech at either end of the query are optional. */
    fun plan(q: String, syn: Syn): Plan {
        val typing = !(q.isNotEmpty() && isSpace(q[q.length - 1]))
        val ws = Norm.queryWords(q).map { withoutLi(it, syn) }
        val p = Plan(ws, typing)
        val groups = ArrayList<Group>(ws.size)
        val n = ws.size
        var i = 0
        while (i < n) {
            var hit: SynEntry? = null
            var ln = minOf(syn.maxLen, n - i)
            while (ln > 0) {
                val e = syn[if (ln == 1) ws[i] else ws.subList(i, i + ln).joinToString(" ")]
                if (e != null && e.role != LETTER && e.role != ALIAS) {
                    hit = e
                    break
                }
                ln--
            }
            if (hit == null) {
                groups.add(Group(listOf(ws[i]), Syn.NO_CATS, 0, i == n - 1 && typing, false))
                i++
                continue
            }
            val role = hit.role
            val words = ws.subList(i, i + ln).toList()
            val last = i + ln == n
            if (role == STOP) {
                i += ln
                continue
            }
            if (role == ROAD) p.roadHint = true
            if (role == DISTRICT) p.districtHint = true
            val alts = if (hit.canon.isNotEmpty() && (role == 0 || role == SPECIFIC) && ln == 1) syn.byCanon(hit.canon).filter { it != words[0] } else emptyList()
            groups.add(Group(words, hit.cats, role, last && typing && ln == 1, role == ROAD || role == OPTIONAL || role == DISTRICT, alts))
            i += ln
        }
        // words of speech: optional at the ends of the query, plain words in the middle
        var first = 0
        while (first < groups.size && groups[first].role == FILLER) first++
        var end = groups.size
        while (end > first && groups[end - 1].role == FILLER) end--
        for (j in groups.indices) {
            val g = groups[j]
            if (g.role == FILLER) {
                if (j < first || j >= end) {
                    g.optional = true
                    p.fillers.add(g)
                } else g.role = 0
            }
        }
        // a single letter being typed says nothing yet
        p.groups = groups.filter { g -> !(g.prefix && g.words[0].length < 2 && !Norm.isDigits(g.words[0]) && g.words.size == 1) }
        p.required = p.groups.filter { !it.optional }
        if (p.required.isEmpty()) p.required = p.groups // "طريق" alone: what was optional is all there is
        return p
    }

    private fun term(w: String, prefix: Boolean): String = if (prefix || (w.length >= 3 && !Norm.isDigits(w))) "$w*" else w

    /**
     * The MATCH string. Standard query syntax: OR binds tighter than the implicit AND.
     * [looseFrom]: also the word's consonants, from a skeleton of so many letters.
     * [inNames]: every word must be in the name. FTS4 cannot put a column on a quoted phrase, so a phrase stays as it is.
     * [noCats]: the words themselves, without the kinds of place they also mean.
     */
    fun fts(plan: Plan, looseFrom: Int = 0, inNames: Boolean = false, noCats: Boolean = false): String {
        val out = StringBuilder()
        val single = inNames || plan.required.size == 1
        for (g in plan.required) {
            val alts = ArrayList<String>(4)
            if (g.words.size == 1) {
                val w = g.words[0]
                alts.add(term(w, g.prefix))
                if (!Norm.isAr(w)) { // "alkhobar" also finds "Al Khobar"
                    val lb = Norm.latinBase(w)
                    if (lb != w) alts.add(term(lb, g.prefix))
                }
                for (x in g.alts) alts.add(term(x, false))
            } else {
                alts.add("\"" + g.words.joinToString(" ") + "\"")
            }
            if (!noCats) for (c in g.cats) alts.add(Norm.CT + c)
            val loose = if (looseFrom != 0) looseFrom else plan.skeletonFrom
            if (loose != 0) {
                for (i in g.sks.indices) {
                    val s = g.sks[i]
                    if (s.length >= loose) alts.add(Norm.SK + (if (s.length >= 3 && i == 0) "$s*" else s))
                }
            }
            if (out.isNotEmpty()) out.append(' ')
            for (i in alts.indices) {
                if (i > 0) out.append(" OR ")
                // a word alone must be in the name, not only in the area: "الخبر" is the town, not every shop in it
                if (single && !alts[i].startsWith("\"")) out.append("k:")
                out.append(alts[i])
            }
        }
        return out.toString()
    }

    /**
     * For a query that found little: the words one letter away from each word, and all that begin with its first three letters; what comes back is
     * checked by [editOk]. ("ايثرا" finds "اثراء"; "النهضي" finds "النهدي"; "بيتزا هات" finds "بيتزا هت".)
     */
    fun ftsEdit(plan: Plan): String {
        val parts = ArrayList<String>(plan.required.size)
        for (g in plan.required) {
            for (w in g.words) {
                val alts = ArrayList<String>()
                if (w.length >= 3 && !Norm.isDigits(w)) {
                    if (w.length >= 4) alts.add(w.substring(0, 3) + "*") else alts.add(term(w, g.prefix))
                    alts.addAll(Norm.oneEditForms(w))
                } else if (g.words.size == 1) {
                    alts.add(term(w, g.prefix))
                }
                if (alts.isEmpty()) continue
                if (g.words.size == 1) for (c in g.cats) alts.add(Norm.CT + c)
                parts.add(alts.joinToString(" OR ") { "k:$it" })
            }
        }
        return parts.joinToString(" ")
    }

    /** Is a row from [ftsEdit] what was meant: every required word is one edit from a word of its name, or starts one, or says its kind. */
    fun editOk(plan: Plan, r: Row): Boolean {
        for (g in plan.required) {
            if (r.cat in g.cats) continue
            for (w in g.words) if (!oneOf(w, r.n1) && !oneOf(w, r.n2)) return false
        }
        return true
    }

    private fun oneOf(w: String, names: List<String>): Boolean {
        for (n in names) if (Norm.lev1(w, n) || n.startsWith(w)) return true
        return false
    }

    /** Every word of a reading, the optional ones too, in the name. */
    private fun ftsAllWords(plan: Plan) = plan.ws.filter { it != "ال" && !Norm.isDigits(it) }.joinToString(" ") { "k:$it" }

    /**
     * Is a town or district found by its consonants the word that was typed? In the script of the query it must be one edit from a word of the name; only a
     * row with no name in that script goes by the consonants. (Al Malqa is Al Malga. ارامكو is not اليرموك, nor KFC Al Khafji.)
     */
    fun placeOk(plan: Plan, r: Row): Boolean {
        val w = plan.required[0].words[0]
        val ar = Norm.isAr(w)
        val lw = Norm.latinBase(w)
        var any = false
        for (list in arrayOf(r.n1, r.n2)) for (n in list) {
            if (Norm.isAr(n) != ar) continue
            any = true
            if (n == w || Norm.lev1(w, n)) return true
            val ln = Norm.latinBase(n)
            if (ln == lw || Norm.lev1(lw, ln)) return true
        }
        return !any
    }

    /** A word alone may be a town or district written another way ("Al Malqa", "Al Malga District"): its consonants, among places only, whatever else shares them. */
    fun placeString(plan: Plan): String? {
        if (plan.required.size != 1 || plan.required[0].cats.isNotEmpty() || plan.required[0].words.size != 1) return null
        val alts = plan.required[0].sks.filter { it.length >= 3 }
        return if (alts.isEmpty()) null else alts.joinToString(" OR ") { "k:" + Norm.PL + it }
    }

    /** Does a far row have a chance against 150 near ones? Not when only a kind of place is asked for, unless it is an airport. This saves most of the time of the commonest searches (مسجد, مطعم). */
    fun wantsBest(plan: Plan, syn: Syn): Boolean {
        if (plan.required.isNotEmpty() && plan.required.all { it.cats.isNotEmpty() }) return plan.required.any { syn.airport in it.cats }
        return true
    }

    /** The MATCH strings every search runs, in order. */
    fun firstStages(plan: Plan): List<String> {
        val out = ArrayList<String>(4)
        val plain = fts(plan)
        if (plan.fillers.isNotEmpty()) {
            // "روح الشرق", "ابي طالب": the rows that hold the words of speech too, exactly and in the name
            val strict = StringBuilder(fts(plan, inNames = true))
            for (g in plan.fillers) if (g.words.size == 1) strict.append(" k:").append(g.words[0])
            out.add(strict.toString())
        }
        if (plan.required.any { it.role == SPECIFIC }) {
            // شاورما: the shops called that, or the many near restaurants would crowd them out of the candidates
            out.add(fts(plan, inNames = true, noCats = true))
        }
        if (plan.other == "alias" && plan.ws.size > 1) out.add(ftsAllWords(plan))
        if (plan.required.size > 1) {
            // the rows that hold all the words in their name, so that a thing itself is never crowded out by the many rows that are merely in it
            val names = fts(plan, inNames = true)
            if (names != plain) out.add(names)
        }
        out.add(plain)
        return out
    }

    /**
     * Other readings of the same words. A row found by one is scored by the reading that suits it best:
     * a known place's other name (جسر البحرين); letters said one by one (كي اف سي is kfc); a name written apart, as a recogniser writes it (ستار بكس, ماك دونالدز, لو لو).
     */
    fun otherPlans(plan: Plan, syn: Syn): List<Plan> {
        val ws = plan.ws
        val n = ws.size
        val end = if (plan.typing) "" else " "
        val out = ArrayList<Plan>(4)
        fun add(kind: String, words: List<String>, skeletonFrom: Int = 0) {
            val p = plan(words.joinToString(" ") + end, syn)
            if (p.required.isNotEmpty() && out.none { it.ws == p.ws } && p.ws != ws) {
                p.other = kind
                p.skeletonFrom = skeletonFrom
                out.add(p)
            }
        }
        // another name is for the whole query, words of speech aside
        val core = ws.filter { val r = syn.role(it); r != STOP && r != FILLER }
        val e = syn[core.joinToString(" ")]
        if (e != null && e.role == ALIAS) {
            for (target in e.canon.split('|')) add("alias", target.trim().split(' ').filter { it.isNotEmpty() })
        }
        var i = 0
        while (i < n) {
            var j = i
            val said = StringBuilder()
            while (j < n) {
                val x = syn[ws[j]]
                if (x == null || x.role != LETTER) break
                said.append(x.canon)
                j++
            }
            if (j - i >= 2 && said.length >= 2) add("letters", ws.subList(0, i) + said.toString() + ws.subList(j, n))
            i = maxOf(j, i + 1)
        }
        val plain = HashSet<String>()
        for (g in plan.groups) {
            if (g.words.size == 1 && g.cats.isEmpty() && (g.role == 0 || g.role == FILLER) && !Norm.isDigits(g.words[0])) plain.add(g.words[0])
        }
        for (k in 0 until n - 1) {
            val a = ws[k]
            val b = ws[k + 1]
            if (a in plain && b in plain && Norm.isAr(a) == Norm.isAr(b)) add("joined", ws.subList(0, k) + (a + b) + ws.subList(k + 2, n), 4)
        }
        return if (out.size > 4) out.subList(0, 4) else out
    }
}
