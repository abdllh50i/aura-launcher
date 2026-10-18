package com.abdllh.aura.music

import com.abdllh.aura.util.Prefs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * When each word of a timed line is sung (1.15, word-by-word lyrics). A line's words are its whitespace-separated
 * tokens in order; their times are an array one longer than the words: word j starts at [j], the last word's singing
 * ends at the last entry (song time, ms).
 *
 * The times come from the owner's server ("enhanced" LRC: `<mm:ss.xx>` before each word, heard by listening or
 * estimated there) or, for LRCLIB's timed lyrics and older servers, are estimated here by the very rule the server
 * uses for its own estimates ([estimate]), so a song looks the same whichever of the two worked them out.
 */
internal object Words {
    /** "Word-by-word lyrics" (Settings → Display & sound → Music), on by default; off: whole lines as before 1.15. */
    var on: Boolean
        get() = Prefs.raw.getBoolean("lyricsWords", true)
        set(v) { Prefs.raw.edit().putBoolean("lyricsWords", v).apply() }

    /** Number of whitespace-separated tokens in [s]. */
    fun count(s: CharSequence): Int {
        var n = 0
        var inWord = false
        for (i in 0 until s.length) {
            val w = !Character.isWhitespace(s[i])
            if (w && !inWord) n++
            inWord = w
        }
        return n
    }

    /** The token boundaries of [s] into [starts] / [ends] (ends exclusive); both sized [count]. */
    fun bounds(s: CharSequence, starts: IntArray, ends: IntArray) {
        var n = 0
        var inWord = false
        for (i in 0 until s.length) {
            val w = !Character.isWhitespace(s[i])
            if (w && !inWord) starts[n] = i
            if (!w && inWord) { ends[n] = i; n++ }
            inWord = w
        }
        if (inWord) { ends[n] = s.length }
    }

    /** A token's weight in the estimate: (its letters and digits, at least 1)^0.8 + 0.6. */
    private fun weight(s: String, from: Int, to: Int): Double {
        var letters = 0
        var i = from
        while (i < to) {
            val cp = Character.codePointAt(s, i)
            if (Character.isLetterOrDigit(cp)) letters++
            i += Character.charCount(cp)
        }
        return max(letters, 1).toDouble().pow(0.8) + 0.6
    }

    /**
     * The times of line [i]'s words: the server's when it gave them for exactly these words (a missing end is
     * estimated), else estimated. Null for a line without words.
     */
    fun times(lines: List<Lyrics.Line>, i: Int): LongArray? {
        val l = lines[i]
        val text = l.text
        val n = count(text)
        if (n == 0) return null
        val t0 = l.ms
        // (the next line's start; the last line has six seconds)
        var tNext = t0 + 6000L
        if (i + 1 < lines.size) tNext = lines[i + 1].ms
        val starts = IntArray(n)
        val ends = IntArray(n)
        bounds(text, starts, ends)
        val g = DoubleArray(n) { weight(text, starts[it], ends[it]) }
        val given = l.words
        if (given != null && given.size == n + 1) {
            val out = given.copyOf()
            for (j in 1 until n) if (out[j] < out[j - 1]) out[j] = out[j - 1]
            if (out[n] < 0L || out[n] < out[n - 1]) {
                // no end given: the last word sung for its own share of the estimate
                val room = (tNext - out[n - 1]) / 1000.0 - 0.15
                val sing = max(min(max(0.16 * g[n - 1], 0.3), room), 0.3)
                out[n] = out[n - 1] + (sing * 1000).roundToLong()
            }
            return out
        }
        return estimate(t0, tNext, g)
    }

    /**
     * The server's rule for words it did not hear: sing = clamp(0.16 * Σg, 0.5, (tNext - t0) - 0.15) s, at least
     * 0.3 s; word j starts at t0 + sing * (Σ_{m<j} g_m) / Σg, the last one ends at t0 + sing.
     */
    fun estimate(t0: Long, tNext: Long, g: DoubleArray): LongArray {
        val n = g.size
        val total = g.sum()
        var sing = max(0.16 * total, 0.5)
        sing = min(sing, (tNext - t0) / 1000.0 - 0.15)
        sing = max(sing, 0.3)
        val out = LongArray(n + 1)
        var cum = 0.0
        for (j in 0 until n) {
            out[j] = t0 + (sing * 1000.0 * cum / total).roundToLong()
            cum += g[j]
        }
        out[n] = t0 + (sing * 1000.0).roundToLong()
        return out
    }

    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")

    /**
     * The words of an LRC line's text with `<mm:ss.xx>` tags before its words ([lineMs]: the line's own first time;
     * [shift]: added to every time, for a line listed at several times): the text without the tags (tokens joined by
     * single spaces), and the times (null when the tags do not give exactly one start per word).
     */
    fun parseTagged(raw: String, shift: Long): Pair<String, LongArray?> {
        val tags = WORD_TAG.findAll(raw).toList()
        if (tags.isEmpty()) return raw.trim() to null
        val tokens = ArrayList<String>()
        val times = ArrayList<Long>()
        var end = -1L
        var ok = raw.substring(0, tags[0].range.first).isBlank()
        if (!ok) tokens.addAll(raw.substring(0, tags[0].range.first).trim().split(Regex("\\s+")))
        for ((k, m) in tags.withIndex()) {
            val segEnd = if (k + 1 < tags.size) tags[k + 1].range.first else raw.length
            val seg = raw.substring(m.range.last + 1, segEnd).trim()
            val t = tagMs(m) + shift
            if (seg.isEmpty()) { if (k == tags.size - 1) end = t; continue }
            val parts = seg.split(Regex("\\s+"))
            if (parts.size != 1) ok = false
            tokens.addAll(parts)
            times.add(t)
        }
        val text = tokens.joinToString(" ")
        if (!ok || times.size != tokens.size || tokens.isEmpty()) return text to null
        val out = LongArray(times.size + 1)
        for (j in times.indices) out[j] = times[j]
        out[times.size] = end
        return text to out
    }

    private fun tagMs(m: MatchResult): Long {
        val frac = m.groupValues[3]
        val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
        return m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms
    }

    /** Two lines' words compared: whitespace runs as one space, ends trimmed. */
    fun sameText(a: String, b: String): Boolean {
        var i = 0
        var j = 0
        fun skip(s: String, k: Int): Int { var x = k; while (x < s.length && Character.isWhitespace(s[x])) x++; return x }
        i = skip(a, i); j = skip(b, j)
        while (i < a.length && j < b.length) {
            val wa = Character.isWhitespace(a[i])
            val wb = Character.isWhitespace(b[j])
            if (wa && wb) { i = skip(a, i); j = skip(b, j); if (i >= a.length || j >= b.length) break; continue }
            if (wa || wb || a[i] != b[j]) return false
            i++; j++
        }
        return skip(a, i) >= a.length && skip(b, j) >= b.length
    }

    /**
     * The server's word times ([enhanced], an LRC with word tags) given to the matching [lines] (same words, start
     * within a second): the lines' own texts and times stay, only their words are taken.
     */
    fun attach(lines: List<Lyrics.Line>, enhanced: List<Lyrics.Line>): List<Lyrics.Line> {
        if (enhanced.isEmpty()) return lines
        var p = 0
        var any = false
        val out = ArrayList<Lyrics.Line>(lines.size)
        for (l in lines) {
            while (p < enhanced.size && enhanced[p].ms < l.ms - 1000L) p++
            var found: LongArray? = null
            var q = p
            while (q < enhanced.size && enhanced[q].ms <= l.ms + 1000L) {
                val e = enhanced[q]
                if (e.words != null && sameText(e.text, l.text)) {
                    // (the line's own time is believed: the words move with it)
                    val d = l.ms - e.ms
                    found = LongArray(e.words.size) { k -> if (k == e.words.size - 1 && e.words[k] < 0L) -1L else e.words[k] + d }
                    p = q + 1
                    break
                }
                q++
            }
            if (found != null) any = true
            out.add(if (found != null) Lyrics.Line(l.ms, l.text, found) else l)
        }
        return if (any) out else lines
    }
}
