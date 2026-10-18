package com.abdllh.aura.music

import com.abdllh.aura.util.Prefs

/**
 * When each word of a timed line is sung (word-by-word lyrics, 1.15). A line's words are its whitespace-separated
 * tokens in order; their times are an array one longer than the words: word j starts at [j], the last word's singing
 * ends at the last entry (song time, ms).
 *
 * Since 1.16 these are only ever REAL times: from a source timed word by word by hand (NetEase, found by the owner's
 * server) or heard by the server in the song itself. 1.15 spread a line's words over its time by their length
 * when nothing better was known, and the light ran on through a singer's pause as if there had been none (the owner:
 * "made up"); a line without real times is now lit whole, as before 1.15. A word followed by a pause also knows where
 * its singing ends ([Lyrics.Line.ends]): the light stops there and waits for the next word.
 */
internal object Words {
    /** "Word-by-word lyrics" (Settings → Display & sound → Music), on by default; off: whole lines, as before 1.15. */
    var on: Boolean
        get() = Prefs.raw.getBoolean("lyricsWords", true)
        set(v) { Prefs.raw.edit().putBoolean("lyricsWords", v).apply() }

    /** A last word whose end was not given fills for this long at most (its start is real; the end a bound). */
    private const val LAST_WORD_MS = 900L

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

    /**
     * The times of line [i]'s words when it has real ones (never backwards; a missing end of the last word: at most
     * [LAST_WORD_MS] after its start, before the next line), else null: the line is lit whole.
     */
    fun times(lines: List<Lyrics.Line>, i: Int): LongArray? {
        val l = lines[i]
        val given = l.words ?: return null
        val n = count(l.text)
        if (n == 0 || given.size != n + 1) return null
        val out = given.copyOf()
        for (j in 1 until n) if (out[j] < out[j - 1]) out[j] = out[j - 1]
        if (out[n] < 0L || out[n] < out[n - 1]) {
            val next = if (i + 1 < lines.size) lines[i + 1].ms else Long.MAX_VALUE
            out[n] = maxOf(out[n - 1] + 200L, minOf(out[n - 1] + LAST_WORD_MS, next - 150L))
        }
        return out
    }

    /** Where each word of line [i] stops before a pause (-1: it runs on to the next word); null when none is given. */
    fun ends(lines: List<Lyrics.Line>, i: Int): LongArray? {
        val l = lines[i]
        val e = l.ends ?: return null
        return if (l.words != null && e.size == count(l.text)) e else null
    }

    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")

    /** A line's text with its word tags taken out, its words' times (as [Lyrics.Line.words]) and pauses ([Lyrics.Line.ends]). */
    class Tagged(val text: String, val times: LongArray?, val ends: LongArray?)

    /**
     * The words of an LRC line's text with `<mm:ss.xx>` tags before its words ([shift]: added to every time, for a
     * line listed at several times): the text without the tags (tokens joined by single spaces), the times (null when
     * the tags do not give exactly one start per word), and where a word's singing ends before a pause (an empty tag
     * in the middle of the line: the server writes one where the gap to the next word is long).
     */
    fun parseTagged(raw: String, shift: Long): Tagged {
        val tags = WORD_TAG.findAll(raw).toList()
        if (tags.isEmpty()) return Tagged(raw.trim(), null, null)
        val tokens = ArrayList<String>()
        val times = ArrayList<Long>()
        val pauses = HashMap<Int, Long>()
        var end = -1L
        var ok = raw.substring(0, tags[0].range.first).isBlank()
        if (!ok) tokens.addAll(raw.substring(0, tags[0].range.first).trim().split(Regex("\\s+")))
        for ((k, m) in tags.withIndex()) {
            val segEnd = if (k + 1 < tags.size) tags[k + 1].range.first else raw.length
            val seg = raw.substring(m.range.last + 1, segEnd).trim()
            val t = tagMs(m) + shift
            if (seg.isEmpty()) {
                if (k == tags.size - 1) end = t
                else if (tokens.isNotEmpty()) pauses[tokens.size - 1] = t
                continue
            }
            val parts = seg.split(Regex("\\s+"))
            if (parts.size != 1) ok = false
            tokens.addAll(parts)
            times.add(t)
        }
        val text = tokens.joinToString(" ")
        if (!ok || times.size != tokens.size || tokens.isEmpty()) return Tagged(text, null, null)
        val out = LongArray(times.size + 1)
        for (j in times.indices) out[j] = times[j]
        out[times.size] = end
        val ends = if (pauses.isEmpty()) null else LongArray(times.size) { j -> pauses[j]?.takeIf { it > out[j] } ?: -1L }
        return Tagged(text, out, ends)
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

    /** How far a line's own time may be from its words' line in the server's answer (the same line, timed alike). */
    private const val ATTACH_MS = 1500L
    /** A line is lit from its first word on when that comes earlier than the line's own time, by this much at most. */
    private const val EARLIER_MS = 2000L

    /**
     * The server's word times ([enhanced], an LRC with word tags) given to the matching [lines] (same words, start
     * within [ATTACH_MS]). The words are real song times and are not moved with the line (1.15 moved them by the
     * difference of the two lines' times; both are now the same clock's). A line whose first word is sung before the
     * line's own time (a source's line times are coarser than its words) is lit from that word on, never before the
     * line ahead of it.
     */
    fun attach(lines: List<Lyrics.Line>, enhanced: List<Lyrics.Line>): List<Lyrics.Line> {
        if (enhanced.isEmpty()) return lines
        var p = 0
        var any = false
        val out = ArrayList<Lyrics.Line>(lines.size)
        var prevMs = Long.MIN_VALUE
        for (l in lines) {
            while (p < enhanced.size && enhanced[p].ms < l.ms - ATTACH_MS) p++
            var found: Lyrics.Line? = null
            var q = p
            while (q < enhanced.size && enhanced[q].ms <= l.ms + ATTACH_MS) {
                val e = enhanced[q]
                if (e.words != null && sameText(e.text, l.text)) { found = e; p = q + 1; break }
                q++
            }
            val line = if (found != null) {
                any = true
                val first = found.words!![0]
                val ms = if (first < l.ms) maxOf(first, l.ms - EARLIER_MS, prevMs + 1) else l.ms
                Lyrics.Line(ms, l.text, found.words, found.ends)
            } else l
            prevMs = line.ms
            out.add(line)
        }
        return if (any) out else lines
    }
}
