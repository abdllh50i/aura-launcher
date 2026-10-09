package com.abdllh.aura.util

import java.text.Normalizer
import java.util.Locale

/**
 * Fuzzy text comparison that works across Arabic script and Latin transliterations ("الأماكن" ~ "Al Amaken"):
 * normalised text for same-script comparison, and a consonant [skeleton] for cross-script comparison.
 */
object TextMatch {
    /** Comparable text: lower case, no accents or Arabic diacritics, one form of alef / ya / ta marbuta, no punctuation. */
    fun norm(s: String): String {
        var t = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        t = t.replace(Regex("""\p{Mn}+"""), "") // accents and Arabic harakat
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه').replace("ـ", "")
        return t.replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
    }

    fun arabic(s: String) = s.any { it in '؀'..'ۿ' }

    /**
     * The consonants of a name, in one alphabet: Arabic letters and their usual Latin spellings land on the same
     * letters, vowels (and the letters usually written as vowels: ا و ي ع ة) are dropped, doubles collapsed.
     * "عبدالمجيد عبدالله" and "Abdul Majeed Abdullah" both give "bdlmjdbdlh". Expects [norm]ed text.
     */
    fun skeleton(s: String): String {
        val out = StringBuilder()
        fun add(c: Char) { if (out.isEmpty() || out[out.length - 1] != c) out.append(c) }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val n = if (i + 1 < s.length) s[i + 1] else ' '
            when {
                c in AR -> AR[c]?.let { add(it) }
                c == 'k' && n == 'h' -> { add('k'); i++ }
                c == 'g' && n == 'h' -> { add('g'); i++ }
                (c == 's' || c == 'c') && n == 'h' -> { add('s'); i++ }
                c == 't' && n == 'h' -> { add('t'); i++ }
                c == 'd' && n == 'h' -> { add('d'); i++ }
                c == 'p' && n == 'h' -> { add('f'); i++ }
                c == 'c' -> add(if (n == 'e' || n == 'i' || n == 'y') 's' else 'k')
                c == 'q' -> add('k')
                c == 'g' -> add('j') // ج is written j or (Egyptian) g; غ is gh
                c == 'x' -> { add('k'); add('s') }
                c == 'v' -> add('f')
                c == 'p' -> add('b')
                c in 'a'..'z' && c !in "aeiouyw" -> add(c)
            }
            i++
        }
        return out.toString()
    }

    private val AR: Map<Char, Char?> = mapOf(
        'ب' to 'b', 'ت' to 't', 'ث' to 't', 'ج' to 'j', 'ح' to 'h', 'خ' to 'k', 'د' to 'd', 'ذ' to 'z', 'ر' to 'r',
        'ز' to 'z', 'س' to 's', 'ش' to 's', 'ص' to 's', 'ض' to 'd', 'ط' to 't', 'ظ' to 'z', 'غ' to 'g', 'ف' to 'f',
        'ق' to 'k', 'ك' to 'k', 'ل' to 'l', 'م' to 'm', 'ن' to 'n', 'ه' to 'h', 'پ' to 'b', 'چ' to 's', 'گ' to 'j',
        'ڤ' to 'f', 'ا' to null, 'و' to null, 'ي' to null, 'ع' to null, 'ء' to null, 'ؤ' to null, 'ئ' to null
    )

    fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    fun overlap(a: String, b: String): Double {
        val x = a.split(' ').filter { it.isNotEmpty() }.toSet()
        val y = b.split(' ').filter { it.isNotEmpty() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / minOf(x.size, y.size)
    }

    /** How well [found] answers [query], 0..1 (same or different script). */
    fun score(query: String, found: String): Double {
        val q = norm(query)
        val r = norm(found)
        if (q.isEmpty() || r.isEmpty()) return 0.0
        if (q == r) return 1.0
        if (r.startsWith(q) || q.startsWith(r)) return 0.95
        if (r.contains(q) || q.contains(r)) return 0.9
        var best = overlap(q, r) * 0.85
        val a = skeleton(q)
        val b = skeleton(r)
        if (a.length >= 2 && b.length >= 2) {
            val sim = 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)
            val contains = (a.contains(b) || b.contains(a)) && minOf(a.length, b.length) >= 3
            best = maxOf(best, if (contains) maxOf(sim, 0.8) else sim * 0.9)
        }
        return best
    }
}
