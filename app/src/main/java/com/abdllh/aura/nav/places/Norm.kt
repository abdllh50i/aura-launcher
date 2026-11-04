package com.abdllh.aura.nav.places

import java.text.Normalizer
import java.util.Locale

/**
 * The text functions of tools/mapbuild/SEARCH_SPEC.md section 2, an exact port of search_ref.py: places.db's index was made with
 * these, so a word that folds differently here is a word that is never found. [VERSION] must equal the file's
 * meta.norm_version or the file is refused ([PlacesDb]); a change here means a rebuild and a new number on both sides.
 * No Android in here: src/test runs it against tools/mapbuild/tests/norm_vectors.json.
 *
 * One known difference from Python: lengths count UTF-16 units, not code points. Only a word with a letter outside the BMP can
 * tell, and places.db has one such row (U+10A75 in "𐩵 DAWA").
 */
internal object Norm {
    const val VERSION = 3

    /** A token of `f.k` that starts with this is a consonant skeleton. */
    const val SK = "§"
    /** A token that starts with this is a category id. */
    const val CT = "¤"
    /** The consonant skeleton of a word of a town's or a district's name. */
    const val PL = "¶"

    private const val TATWEEL = 0x0640
    private const val DROP = '\u0000'
    private const val SLOW = '￿'

    /** Lower case and NFKD happen first, in that order: `´` is a space and a mark by then, `℃` an upper-case C, as in Python. */
    private fun core(s: String): StringBuilder {
        val d = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        val out = StringBuilder(d.length + 4)
        var prev = 0 // 0 nothing yet or a space, 1 Arabic, 2 anything else
        var i = 0
        while (i < d.length) {
            val cp = d.codePointAt(i)
            i += Character.charCount(cp)
            if (isApostrophe(cp)) continue
            val t = Character.getType(cp)
            if (t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt() || t == Character.FORMAT.toInt() || cp == TATWEEL) continue
            val f = fold(cp)
            if (f < 0) continue
            if (isLetterOrNumber(t)) {
                val now = if (f in 0x0600..0x06FF) 1 else 2
                if (prev != 0 && now != prev) out.append(' ')
                out.appendCodePoint(f)
                prev = now
            } else {
                out.append(' ')
                prev = 0
            }
        }
        return out
    }

    private fun isApostrophe(cp: Int) = cp == 0x27 || cp == 0x2019 || cp == 0x2018 || cp == 0x02BC || cp == 0x02BB || cp == 0x60 || cp == 0xB4

    /** The categories L* and N*: Java's numbers 1..5 and 9..11. */
    private fun isLetterOrNumber(t: Int) = t in 1..5 || t in 9..11

    /** One form of each letter, ASCII digits; -1 for what goes (a bare hamza). */
    private fun fold(c: Int): Int = when (c) {
        0x0671 -> 0x0627 // wasla alef
        0x0649, 0x06CC, 0x06D0 -> 0x064A // alef maksura, Persian ya
        0x0629, 0x06C3, 0x06BE, 0x06C1, 0x06D5 -> 0x0647 // ta marbuta, other ha forms
        0x06A9, 0x06AA -> 0x0643 // keheh
        0x06A4 -> 0x0641 // veh
        0x06AF, 0x06A8 -> 0x0642 // gaf: said and written for qaf in the Gulf
        0x067E -> 0x0628 // peh
        0x0686 -> 0x062C // tcheh
        0x0698 -> 0x0632 // jeh
        0x06BA -> 0x0646
        0x0679 -> 0x062A
        0x0688 -> 0x062F
        0x0691 -> 0x0631
        0x0621 -> -1 // bare hamza
        in 0x0660..0x0669 -> 0x30 + c - 0x0660
        in 0x06F0..0x06F9 -> 0x30 + c - 0x06F0
        else -> c
    }

    /** What one character becomes by itself, or [SLOW] when it becomes more than one (ٵ). */
    private fun one(cp: Int): Char {
        val s = core(String(Character.toChars(cp)))
        return when (s.length) { 0 -> DROP; 1 -> s[0]; else -> SLOW }
    }

    // Nearly all names are ASCII and Arabic letters and norm() runs on up to 200 names a search: the two tables, made once by the
    // slow road itself, so they cannot say anything else. (Only U+0600-06FF and ASCII: no neighbour changes what these become.)
    private val ASCII = CharArray(0x80) { one(it) }
    private val ARABIC = CharArray(0x100) { one(0x0600 + it) }

    /** Comparable text: lower case, no marks, one form of each letter, ASCII digits, words split by single spaces, and where the script changes. */
    fun norm(s: String): String {
        val out = StringBuilder(s.length + 2)
        var prev = 0
        for (i in s.indices) {
            val c = s[i].code
            val m = when {
                c < 0x80 -> ASCII[c]
                c in 0x0600..0x06FF -> ARABIC[c - 0x0600]
                else -> SLOW
            }
            if (m == DROP) continue
            if (m == SLOW) return collapse(core(s))
            if (m == ' ') {
                if (out.isNotEmpty() && out[out.length - 1] != ' ') out.append(' ')
                prev = 0
            } else {
                val now = if (m.code in 0x0600..0x06FF) 1 else 2
                if (prev != 0 && now != prev) out.append(' ')
                out.append(m)
                prev = now
            }
        }
        if (out.isNotEmpty() && out[out.length - 1] == ' ') out.setLength(out.length - 1)
        return out.toString()
    }

    private fun collapse(s: CharSequence): String {
        val out = StringBuilder(s.length)
        for (i in s.indices) {
            val c = s[i]
            if (c == ' ') {
                if (out.isNotEmpty() && out[out.length - 1] != ' ') out.append(' ')
            } else out.append(c)
        }
        if (out.isNotEmpty() && out[out.length - 1] == ' ') out.setLength(out.length - 1)
        return out.toString()
    }

    fun isAr(w: String) = w.isNotEmpty() && w[0] in '؀'..'ۿ'

    /** Any Arabic letter in it (a shown name: which script it is in). */
    fun hasArabic(s: String): Boolean {
        for (c in s) if (c in '؀'..'ۿ') return true
        return false
    }

    private val WORD_CANON = hashMapOf(
        "بو" to "ابو", "abou" to "abu", "abo" to "abu", "abdul" to "abd", "abdel" to "abd", "abdal" to "abd", "abdol" to "abd", "abdu" to "abd", "um" to "umm"
    )

    /** A word without its Arabic article, in its one spelling. The index holds these, so a query does too. */
    fun base(w0: String): String {
        val w = WORD_CANON[w0] ?: w0
        if (isAr(w)) {
            if (w == "الله") return w
            if (w.startsWith("ال") && w.length >= 4) return w.substring(2)
            if (w.startsWith("لل") && w.length >= 5) return w.substring(2)
        }
        return w
    }

    /** A Latin word without its article (Alkhobar, Ar-Riyadh written together), for skeletons and the index's second form. */
    fun latinBase(w: String): String {
        if (w.length >= 5 && (w.startsWith("al") || w.startsWith("el"))) return w.substring(2)
        if (w.length >= 6 && w[0] == 'a') {
            for (d in DOUBLED) if (w.startsWith(d, 1) && w.startsWith(d, 3)) return w.substring(3)
            if (w[1] == w[2] && w[1] in "rsdtnz") return w.substring(2)
        }
        return w
    }

    private val DOUBLED = arrayOf("sh", "dh", "th")
    private val ABD_LATIN = arrayOf("abdul", "abdel", "abdal", "abdol", "abdur", "abdus", "abdu")

    /** ^(?:abdul|abdel|abdal|abdol|abdur|abdus|abdu)(.{3,})$ of the reference, its group, or null. */
    private fun abdLatinRest(b: String): String? {
        if (!b.startsWith("abd")) return null
        for (p in ABD_LATIN) if (b.startsWith(p) && b.length - p.length >= 3) return b.substring(p.length)
        return null
    }

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** Python's `isascii() and isalpha()`. */
    private fun isAsciiLetters(s: String): Boolean {
        if (s.isEmpty()) return false
        for (c in s) if (!isAsciiLetter(c)) return false
        return true
    }

    /**
     * The words of a text as the index holds them: norm, base, and joined names apart ("عبدالعزيز" is عبد + عزيز, "Abdulaziz" is
     * abd + aziz, "ابوبكر" is ابو + بكر), so that a name written either way finds the other. Single Latin letters belong together: H&M, K.F.C.
     */
    fun queryWords(text: String): ArrayList<String> {
        val out = ArrayList<String>(4)
        val n = norm(text)
        var spelled = 0 // how many single Latin letters the last word of out was built from
        var from = 0
        while (from < n.length) {
            var to = n.indexOf(' ', from)
            if (to < 0) to = n.length
            val b = base(n.substring(from, to))
            from = to + 1
            if (isAr(b)) {
                if (b.startsWith("عبد") && b.length > 4) {
                    out.add("عبد")
                    out.add(base(b.substring(3)))
                    continue
                }
                if (b.startsWith("ابو") && b.length >= 6) {
                    out.add("ابو")
                    out.add(base(b.substring(3)))
                    continue
                }
            } else {
                val rest = abdLatinRest(b)
                if (rest != null) {
                    out.add("abd")
                    out.add(latinBase(rest))
                    continue
                }
            }
            val letter = b.length == 1 && isAsciiLetter(b[0])
            if (letter && spelled > 0 && out.isNotEmpty() && isAsciiLetters(out[out.size - 1]) && out[out.size - 1].length == spelled) {
                out[out.size - 1] = out[out.size - 1] + b
                spelled++
                continue
            }
            spelled = if (letter) 1 else 0
            out.add(b)
        }
        return out
    }

    /** [queryWords] of a column that can be empty or null. */
    fun wordSet(name: String?): List<String> = if (name.isNullOrEmpty()) emptyList() else queryWords(name)

    /** Python's `str.isdigit()` for one character: Nd, and the few others with a digit's value that NFKD leaves alone. */
    fun isDigit(c: Char): Boolean {
        val v = c.code
        if (v < 0x80) return v in 0x30..0x39
        return Character.isDigit(c) || v in 0x1369..0x1371 || v == 0x19DA || v in 0x24F5..0x24FD || v == 0x24FF ||
            v in 0x2776..0x277E || v in 0x2780..0x2788 || v in 0x278A..0x2792
    }

    /** Python's `str.isdigit()`. */
    fun isDigits(w: String): Boolean {
        if (w.isEmpty()) return false
        for (c in w) if (!isDigit(c)) return false
        return true
    }

    private fun skAr(c: Char): Char = when (c) {
        'ب' -> 'b'
        'ت', 'ث', 'ط' -> 't'
        'ج', 'ق', 'ك', 'خ', 'غ' -> 'k'
        'ح', 'ه' -> 'h'
        'د', 'ذ', 'ض', 'ظ', 'ز' -> 'd'
        'ر' -> 'r'
        'س', 'ش', 'ص' -> 's'
        'ف' -> 'f'
        'ل' -> 'l'
        'م' -> 'm'
        'ن' -> 'n'
        else -> DROP
    }

    private fun skLatin2(a: Char, b: Char): Char = when {
        a == 'k' && b == 'h' || a == 'g' && b == 'h' || a == 'c' && b == 'k' -> 'k'
        a == 's' && b == 'h' || a == 'c' && b == 'h' -> 's'
        a == 't' && b == 'h' -> 't'
        a == 'd' && b == 'h' -> 'd'
        a == 'p' && b == 'h' -> 'f'
        else -> DROP
    }

    private fun skLatin1(c: Char): Char = when (c) {
        'b', 'p' -> 'b'
        'd', 'z' -> 'd'
        'f', 'v' -> 'f'
        'g', 'j', 'k', 'q' -> 'k'
        'h' -> 'h'
        'l' -> 'l'
        'm' -> 'm'
        'n' -> 'n'
        'r' -> 'r'
        's' -> 's'
        't' -> 't'
        else -> DROP
    }

    /**
     * The consonants of one word in one alphabet, so that an Arabic word and its Latin spelling land on the same letters.
     * Expects norm and base or latinBase to have run. Words with digits have none.
     */
    fun skeleton(w: String): String {
        val out = StringBuilder(w.length)
        val n = w.length
        if (isAr(w)) {
            for (c in w) {
                if (isDigit(c)) return ""
                val k = skAr(c)
                if (k != DROP) out.append(k)
            }
        } else {
            var prev = DROP
            var i = 0
            while (i < n) {
                val c = w[i]
                if (isDigit(c)) return ""
                var k = if (i + 1 < n) skLatin2(c, w[i + 1]) else DROP
                if (k != DROP) {
                    i += 2
                } else {
                    i++
                    if (c == 'c') {
                        k = if (i < n && w[i] in "eiy") 's' else 'k'
                    } else if (c == 'x') {
                        if (prev != 'k') out.append('k')
                        k = 's'
                    } else {
                        k = skLatin1(c)
                        if (k == DROP) {
                            prev = DROP // a vowel between two equal consonants keeps both
                            continue
                        }
                    }
                }
                if (k != prev) out.append(k)
                prev = k
            }
        }
        if (out.isNotEmpty() && out[out.length - 1] == 'h') out.setLength(out.length - 1)
        return out.toString()
    }

    /** The skeleton of a word, and for a Latin word ending in -at (a ta marbuta written out: Rawdat, Madinat) the one without its t. */
    fun skeletons(w: String): List<String> {
        val ar = isAr(w)
        val b = if (ar) base(w) else latinBase(w)
        val s = skeleton(b)
        if (s.isEmpty()) return emptyList()
        if (!ar && b.endsWith("at") && b.length >= 4 && s.endsWith("t") && s.length >= 2) return listOf(s, s.substring(0, s.length - 1))
        return listOf(s)
    }

    /** True when a and b differ by at most one edit. */
    fun lev1(a0: String, b0: String): Boolean {
        var a = a0
        var b = b0
        var la = a.length
        var lb = b.length
        if (la - lb > 1 || lb - la > 1) return false
        if (la > lb) {
            val t = a; a = b; b = t
            val l = la; la = lb; lb = l
        }
        var i = 0
        while (i < la && a[i] == b[i]) i++
        // a[i + 1:] == b[i + 1:] for the same length, a[i:] == b[i + 1:] for one letter more
        val from = if (la == lb) i + 1 else i
        if (from > la) return la == lb // (both ran out: the same word)
        return a.regionMatches(from, b, i + 1, la - from)
    }

    private val SOUND_ALIKE = arrayOf("تثط", "سصث", "دذضظز", "قكجغ", "هح", "اع")
    private const val LONG_VOWELS = "اوي"

    /** The spellings one letter from a word that an index can be asked for: with a letter less, and with a letter swapped for one that sounds alike. */
    fun oneEditForms(w: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(x: String) { if (x.length >= 2) out.add(x) }
        if (w.length >= 4) {
            for (i in w.indices) add(w.substring(0, i) + w.substring(i + 1))
        } else if (w.length == 3 && w[1] in LONG_VOWELS) { // "هات" for هت
            add(w.substring(0, 1) + w.substring(2))
        }
        if (isAr(w)) {
            for (i in w.indices) for (group in SOUND_ALIKE) if (w[i] in group) {
                for (x in group) if (x != w[i]) add(w.substring(0, i) + x + w.substring(i + 1))
            }
            if (w.length <= 7) { // a long vowel left out: "دنكن" for دانكن
                for (i in 1 until w.length) for (x in LONG_VOWELS) add(w.substring(0, i) + x + w.substring(i))
            }
        }
        return out.toList()
    }
}
