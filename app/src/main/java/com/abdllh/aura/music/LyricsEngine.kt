package com.abdllh.aura.music

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Pure-Kotlin lyrics parsing, metadata normalization, real word/syllable progress math, and display interlude engine
 * shared by [Lyrics], [PhoneLink], and [LyricsView]. Has zero Android or `org.json` dependencies so JVM unit tests
 * exercise the exact production code.
 */
internal object LyricsEngine {
    class WordTiming(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val syllables: List<Lyrics.Syllable> = emptyList()
    )

    class ParsedLine(
        val ms: Long,
        val endMs: Long,
        val text: String,
        val words: List<WordTiming> = emptyList()
    )

    class ParsedLyrics(
        val synced: Boolean,
        val lines: List<ParsedLine>,
        val source: String,
        val wordSynced: Boolean
    )

    /** One row displayed in [LyricsView]: either a real [Lyrics.Line] ([sourceIndex] >= 0) or a synthesized interlude (-1). */
    class DisplayRow(
        val sourceIndex: Int,
        val ms: Long,
        val endMs: Long,
        val line: Lyrics.Line,
        val isInterlude: Boolean
    )

    private val INTERLUDE_MARKERS = setOf("", "♪", "♫", "🎵", "🎶", "...", "…", "•••", "---", "•")

    fun isInterludeText(raw: String): Boolean = raw.trim() in INTERLUDE_MARKERS

    // ------------------------------------------------------------------------------------------ script & metadata helpers

    fun isArabicChar(ch: Char): Boolean {
        val code = ch.code
        return code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0x08A0..0x08FF ||
            code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF
    }

    fun isRtlText(s: String, fallback: Boolean = false): Boolean {
        for (ch in s) {
            if (isArabicChar(ch) || ch.code in 0x0590..0x05FF) return true
            if ((ch in 'A'..'Z') || (ch in 'a'..'z')) return false
        }
        return fallback
    }

    fun dominantRtl(lines: List<Lyrics.Line>, fallback: Boolean = false): Boolean {
        var rtlCount = 0
        var ltrCount = 0
        for (l in lines) {
            if (isInterludeText(l.text)) continue
            for (ch in l.text) {
                if (isArabicChar(ch) || ch.code in 0x0590..0x05FF) rtlCount++
                else if ((ch in 'A'..'Z') || (ch in 'a'..'z')) ltrCount++
            }
        }
        return when {
            rtlCount > ltrCount -> true
            ltrCount > rtlCount -> false
            else -> fallback
        }
    }

    /** Count of visible alphanumeric characters in [token], ignoring Arabic harakat and tatweel. */
    fun visibleLength(token: String): Int {
        var visible = 0
        for (ch in token) {
            val code = ch.code
            if (code in 0x064B..0x065F || code == 0x0670 || code == 0x0640) continue
            if (Character.isLetterOrDigit(ch)) visible++
        }
        return visible
    }

    /** Normalized form for matching song and artist names across Arabic orthographic variants and punctuation. */
    fun normText(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            val code = ch.code
            when {
                code in 0x064B..0x065F || code == 0x0670 || ch == 'ـ' -> {}
                ch == 'أ' || ch == 'إ' || ch == 'آ' || ch == 'ٱ' -> b.append('ا')
                ch == 'ة' -> b.append('ه')
                ch == 'ى' || ch == 'ئ' -> b.append('ي')
                ch == 'ؤ' -> b.append('و')
                Character.isLetterOrDigit(ch) -> b.append(ch)
            }
        }
        return b.toString()
    }

    private val NOISE_BRACKETS = Regex(
        """[\(\[\{][^)\]}]*(?:official|video|audio|lyric|lyrics|visualizer|hd|4k|hq|remaster|live|acoustic|version|edit|mix|feat\.?|ft\.?|prod\.?|حصري|حصرى|فيديو|كليب|كلمات|أوديو|اوديو|لايف|حفلة|مهرجان|النسخة|الأصلية|الاصليه|موسيقى|مترجم|مع\s*الكلمات)[^)\]}]*[\)\]\}]""",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_PIPE = Regex("""\s*\|\s*.*$""")
    private val TRAILING_DASH_NOISE = Regex(
        """\s*[-–—]\s*(?:official|music\s*video|lyric|lyrics|audio|hd|4k|visualizer|remaster|حصري|فيديو\s*كليب|كلمات|مع\s*الكلمات).*$""",
        RegexOption.IGNORE_CASE
    )
    private val FEAT_SUFFIX = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_NOISE = Regex("""\s*(?:-\s*topic|vevo|official\s*channel|official|music\s*channel)\s*$""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE = Regex("""\s+""")

    fun cleanTitle(raw: String): String {
        var s = raw.trim()
        s = NOISE_BRACKETS.replace(s, " ")
        s = TRAILING_PIPE.replace(s, "")
        s = TRAILING_DASH_NOISE.replace(s, "")
        s = FEAT_SUFFIX.replace(s, "")
        s = MULTI_SPACE.replace(s, " ").trim(' ', '-', '–', '—', '|')
        return s.ifBlank { raw.trim() }
    }

    fun cleanArtist(raw: String): String {
        var s = raw.trim()
        s = ARTIST_NOISE.replace(s, "")
        // Keep the lead artist when YouTube concatenates multiple comma/ampersand artists
        val comma = s.indexOf(',')
        if (comma > 1) s = s.substring(0, comma)
        s = MULTI_SPACE.replace(s, " ").trim(' ', '-', '–', '—', '|')
        return s.ifBlank { raw.trim() }
    }

    /** Splits `"Artist - Title"` embedded in a YouTube title when [artist] is empty or looks like a channel name. */
    fun splitArtistTitle(title: String, artist: String): Pair<String, String> {
        val ct = cleanTitle(title)
        val ca = cleanArtist(artist)
        val sep = Regex("""\s+[-–—]\s+""").find(ct)
        if (sep != null) {
            val left = ct.substring(0, sep.range.first).trim()
            val right = ct.substring(sep.range.last + 1).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) {
                val nl = normText(left)
                val na = normText(ca)
                if (ca.isBlank()) return right to cleanArtist(left)
                if (na.isNotEmpty() && (nl == na || nl.contains(na) || na.contains(nl))) return right to ca
            }
        }
        return ct to ca
    }

    // ------------------------------------------------------------------------------------------ real word & syllable progress

    /**
     * Exact singing progress in `0f..1f` at [nowMs] for a real-timed word spanning `[startMs, endMs]`.
     * When [syllables] has 2+ sub-word intervals, sweeps piecewise weighted by visible character length so held
     * syllables stretch accurately without drifting ahead onto later syllables or words.
     */
    fun wordProgress(nowMs: Long, startMs: Long, endMs: Long, syllables: Array<Lyrics.Syllable>?): Float {
        if (nowMs <= startMs) return 0f
        if (nowMs >= endMs) return 1f
        if (syllables == null || syllables.size < 2) {
            val dur = max(50L, endMs - startMs).toFloat()
            return ((nowMs - startMs) / dur).coerceIn(0f, 1f)
        }
        var totalWeight = 0f
        for (syl in syllables) totalWeight += max(1, visibleLength(syl.text)).toFloat()
        if (totalWeight <= 0f) return 0f
        var accum = 0f
        for (syl in syllables) {
            val share = max(1, visibleLength(syl.text)).toFloat() / totalWeight
            if (nowMs < syl.startMs) return accum.coerceIn(0f, 1f)
            if (nowMs <= syl.endMs) {
                val dur = max(40L, syl.endMs - syl.startMs).toFloat()
                val frac = ((nowMs - syl.startMs) / dur).coerceIn(0f, 1f)
                return (accum + share * frac).coerceIn(0f, 1f)
            }
            accum += share
        }
        return 1f
    }

    /** True while the singer is sustaining a long word or syllable (`>= 700ms` syllable or `>= 750ms` word) at [nowMs]. */
    fun isWordSustaining(nowMs: Long, startMs: Long, endMs: Long, syllables: Array<Lyrics.Syllable>?): Boolean {
        if (nowMs <= startMs || nowMs >= endMs) return false
        if (syllables != null && syllables.size >= 2) {
            for (syl in syllables) {
                if (nowMs in syl.startMs..syl.endMs) return (syl.endMs - syl.startMs) >= 700L
            }
            return false
        }
        return (endMs - startMs) >= 750L
    }

    // ------------------------------------------------------------------------------------------ display rows & interludes

    /**
     * Builds display rows for [LyricsView] from [lines], inserting breathing Apple Music interlude rows (`•••`) for
     * instrumental intros (`>= 4s`) and long instrumental breaks (`>= 6s`) without mutating the underlying [Lyrics.Line] list.
     */
    fun buildDisplayRows(lines: List<Lyrics.Line>, synced: Boolean): List<DisplayRow> {
        if (lines.isEmpty()) return emptyList()
        if (!synced) {
            return lines.mapIndexed { idx, l ->
                DisplayRow(idx, 0L, 0L, l, isInterludeText(l.text))
            }
        }
        val out = ArrayList<DisplayRow>(lines.size + 4)

        fun appendInterlude(sourceIndex: Int, startMs: Long, endMs: Long, raw: Lyrics.Line?) {
            val safeEnd = max(startMs + 800L, endMs)
            val prev = out.lastOrNull()
            if (prev != null && prev.isInterlude) {
                val keepIdx = if (prev.sourceIndex >= 0) prev.sourceIndex else sourceIndex
                val keepLine = if (prev.sourceIndex >= 0) prev.line else (raw ?: Lyrics.Line(prev.ms, ""))
                out[out.lastIndex] = DisplayRow(keepIdx, prev.ms, max(prev.endMs, safeEnd), keepLine, isInterlude = true)
            } else {
                out.add(DisplayRow(sourceIndex, startMs, safeEnd, raw ?: Lyrics.Line(startMs, ""), isInterlude = true))
            }
        }

        val first = lines[0]
        if (first.ms >= 4000L && !isInterludeText(first.text)) {
            appendInterlude(-1, 0L, first.ms, null)
        }

        for (i in lines.indices) {
            val l = lines[i]
            val start = l.ms
            val next = lines.getOrNull(i + 1)
            val nextStart = max(start + 400L, next?.ms ?: (start + 4500L))
            val nextIsInterlude = next != null && isInterludeText(next.text)

            if (isInterludeText(l.text)) {
                appendInterlude(i, start, nextStart, l)
                continue
            }

            val wordEnd = l.words?.lastOrNull()?.takeIf { it > start } ?: -1L
            val explicitEnd = when {
                l.endMs > start -> l.endMs
                wordEnd > start -> wordEnd
                else -> -1L
            }

            val singEnd: Long
            var synthInterlude = false
            if (explicitEnd > start) {
                singEnd = min(nextStart, explicitEnd)
                if (next != null && !nextIsInterlude && next.ms - singEnd >= 5800L) {
                    synthInterlude = true
                }
            } else {
                val wordCount = max(1, Words.count(l.text))
                val naturalCeiling = max(2800L, wordCount * 700L + 1800L)
                if (next != null && !nextIsInterlude && (next.ms - start) > naturalCeiling + 5500L) {
                    singEnd = start + naturalCeiling
                    synthInterlude = true
                } else {
                    singEnd = max(start + 350L, nextStart)
                }
            }

            out.add(DisplayRow(i, start, singEnd, l, isInterlude = false))
            if (synthInterlude && next != null) {
                appendInterlude(-1, singEnd + 500L, next.ms, null)
            }
        }
        return out
    }

    // ------------------------------------------------------------------------------------------ convert ParsedLyrics -> Lyrics.Line

    fun toLines(parsed: ParsedLyrics): List<Lyrics.Line> {
        val out = ArrayList<Lyrics.Line>(parsed.lines.size)
        for (pl in parsed.lines) {
            if (!parsed.wordSynced || pl.words.isEmpty() || isInterludeText(pl.text)) {
                out.add(Lyrics.Line(pl.ms, pl.text, endMs = pl.endMs))
                continue
            }
            val tokenCount = Words.count(pl.text)
            if (tokenCount != pl.words.size || tokenCount == 0) {
                out.add(Lyrics.Line(pl.ms, pl.text, endMs = pl.endMs))
                continue
            }
            val starts = LongArray(tokenCount + 1)
            val ends = LongArray(tokenCount)
            var hasSyl = false
            val syls = arrayOfNulls<Array<Lyrics.Syllable>>(tokenCount)
            for (j in 0 until tokenCount) {
                val w = pl.words[j]
                val s = if (j > 0 && w.startMs < starts[j - 1]) starts[j - 1] else w.startMs
                starts[j] = s
                val e = max(s + 40L, w.endMs)
                ends[j] = e
                if (w.syllables.size >= 2) {
                    hasSyl = true
                    syls[j] = w.syllables.toTypedArray()
                }
            }
            starts[tokenCount] = max(starts[tokenCount - 1] + 50L, ends[tokenCount - 1])
            // Mark pause ends strictly when a word finishes before the next word (or is the final word)
            for (j in 0 until tokenCount - 1) {
                if (ends[j] >= starts[j + 1] - 15L) ends[j] = -1L
            }
            val hasEnds = ends.any { it > 0L }
            val lineEnd = if (pl.endMs > pl.ms) pl.endMs else starts[tokenCount]
            out.add(Lyrics.Line(pl.ms, pl.text, starts, if (hasEnds) ends else null, lineEnd, if (hasSyl) syls else null))
        }
        return out
    }

    // ------------------------------------------------------------------------------------------ LyricsPlus JSON parser

    /**
     * Parses a LyricsPlus (`/v2/lyrics/get`) JSON response into [ParsedLyrics].
     * Merges consecutive sub-word `syllabus` items without trailing/leading whitespace into intact words (preserving
     * Arabic cursive shaping) while retaining exact sub-word [Lyrics.Syllable] timings.
     */
    fun parseLyricsPlus(json: String): ParsedLyrics? {
        val root = JsonReader.parse(json) as? Map<*, *> ?: return null
        val arr = root["lyrics"] as? List<*> ?: return null
        if (arr.isEmpty()) return null
        val meta = root["metadata"] as? Map<*, *>
        val rawSource = (meta?.get("source") as? String)?.trim().orEmpty()
        val source = when {
            rawSource.contains("apple", ignoreCase = true) -> Lyrics.SOURCE_APPLE
            rawSource.isNotEmpty() -> rawSource.lowercase()
            else -> Lyrics.SOURCE_LYRICSPLUS
        }

        val lines = ArrayList<ParsedLine>(arr.size)
        var anyWordTimed = false
        for (entry in arr) {
            val o = entry as? Map<*, *> ?: continue
            val lineMs = asLong(o["time"], -1L)
            if (lineMs < 0L) continue
            val lineDur = asLong(o["duration"], 0L)
            val lineEnd = if (lineDur > 0L) lineMs + lineDur else -1L
            val syllabus = o["syllabus"] as? List<*>

            if (!syllabus.isNullOrEmpty()) {
                val words = mergeSyllables(syllabus)
                if (words.isNotEmpty()) {
                    anyWordTimed = true
                    val text = words.joinToString(" ") { it.text }
                    val firstStart = words.first().startMs
                    val lastEnd = words.last().endMs
                    val resolvedStart = if (firstStart in 0L..lineMs) firstStart else lineMs
                    val resolvedEnd = max(lineEnd, lastEnd)
                    lines.add(ParsedLine(resolvedStart, resolvedEnd, text, words))
                    continue
                }
            }
            val text = (o["text"] as? String)?.trim().orEmpty()
            if (text.isNotEmpty() || lineDur > 0L) {
                lines.add(ParsedLine(lineMs, lineEnd, text))
            }
        }
        if (lines.none { !isInterludeText(it.text) }) return null
        lines.sortBy { it.ms }
        return ParsedLyrics(synced = true, lines = lines, source = source, wordSynced = anyWordTimed)
    }

    private fun mergeSyllables(syllabus: List<*>): List<WordTiming> {
        val words = ArrayList<WordTiming>()
        val currentText = StringBuilder()
        val currentSyls = ArrayList<Lyrics.Syllable>()
        var wordStart = -1L
        var wordEnd = -1L

        fun flush() {
            val t = currentText.toString().trim()
            if (t.isNotEmpty() && wordStart >= 0L) {
                val pieces = t.split(MULTI_SPACE).filter { it.isNotEmpty() }
                if (pieces.size == 1) {
                    val safeEnd = max(wordStart + 40L, wordEnd)
                    val sylList = if (currentSyls.size >= 2) ArrayList(currentSyls) else emptyList()
                    words.add(WordTiming(wordStart, safeEnd, pieces[0], sylList))
                } else if (pieces.size > 1) {
                    // Rare case: a single syllable entry contained an internal space; split cleanly proportional to length
                    val safeEnd = max(wordStart + 50L * pieces.size, wordEnd)
                    val totalLen = pieces.sumOf { max(1, visibleLength(it)) }.toDouble()
                    var cursor = wordStart
                    for ((idx, p) in pieces.withIndex()) {
                        val w = max(1, visibleLength(p))
                        val end = if (idx == pieces.lastIndex) safeEnd else cursor + ((safeEnd - wordStart) * (w / totalLen)).roundToLong()
                        words.add(WordTiming(cursor, max(cursor + 40L, end), p))
                        cursor = end
                    }
                }
            }
            currentText.setLength(0)
            currentSyls.clear()
            wordStart = -1L
            wordEnd = -1L
        }

        for (item in syllabus) {
            val s = item as? Map<*, *> ?: continue
            if (s["isBackground"] == true) continue
            val raw = s["text"] as? String ?: continue
            if (raw.isEmpty()) continue
            val start = asLong(s["time"], -1L)
            if (start < 0L) continue
            val dur = asLong(s["duration"], 0L)
            val end = max(start + 40L, start + dur)

            val leadingSpace = raw.first().isWhitespace()
            val trailingSpace = raw.last().isWhitespace()
            val trimmed = raw.trim()

            if (leadingSpace && currentText.isNotEmpty()) flush()
            if (trimmed.isEmpty()) {
                if (currentText.isNotEmpty()) flush()
                continue
            }

            if (wordStart < 0L) wordStart = start
            wordEnd = max(wordEnd, end)
            currentText.append(trimmed)
            currentSyls.add(Lyrics.Syllable(start, end, trimmed))

            if (trailingSpace) flush()
        }
        flush()
        return words
    }

    // ------------------------------------------------------------------------------------------ BetterLyrics TTML parser

    /**
     * Parses a BetterLyrics JSON wrapper (`{"ttml": "<tt ...>...</tt>"}`) or raw TTML XML directly into [ParsedLyrics].
     */
    fun parseBetterLyrics(jsonOrTtml: String): ParsedLyrics? {
        val trimmed = jsonOrTtml.trim()
        if (trimmed.startsWith("<")) return parseTtml(trimmed)
        val root = JsonReader.parse(trimmed) as? Map<*, *> ?: return null
        val ttml = (root["ttml"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return parseTtml(ttml)
    }

    private class RawSpan(val start: Long, val end: Long, val text: String, val spaceBefore: Boolean)

    /**
     * Parses Apple Music / W3C TTML XML (`<p begin=... end=...><span begin=... end=...>word</span></p>`) using standard
     * SAX parsing (built into both Android and JDK 17). Skips background/romanization/translation roles and merges
     * adjacent syllable spans without separating whitespace into intact words.
     */
    fun parseTtml(ttml: String): ParsedLyrics? {
        if (ttml.isBlank()) return null
        val lines = ArrayList<ParsedLine>()
        var anyWordTimed = false

        val handler = object : DefaultHandler() {
            private var inP = false
            private var pStart = -1L
            private var pEnd = -1L
            private val pPlain = StringBuilder()

            private var skipDepth = 0
            private var spanDepth = 0
            private var curSpanStart = -1L
            private var curSpanEnd = -1L
            private val curSpanText = StringBuilder()

            private val spans = ArrayList<RawSpan>()
            private var sawWhitespaceBetweenSpans = false

            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                val tag = qName.substringAfter(':').lowercase()
                if (skipDepth > 0) {
                    skipDepth++
                    return
                }
                val role = attr(attrs, "role")
                if (role != null && (role.equals("x-bg", true) || role.equals("x-roman", true) || role.equals("x-translation", true))) {
                    skipDepth = 1
                    return
                }
                if (tag == "p") {
                    inP = true
                    pStart = parseClock(attr(attrs, "begin"))
                    pEnd = parseClock(attr(attrs, "end"))
                    pPlain.setLength(0)
                    spans.clear()
                    sawWhitespaceBetweenSpans = false
                    spanDepth = 0
                } else if (inP && tag == "span") {
                    val b = parseClock(attr(attrs, "begin"))
                    val e = parseClock(attr(attrs, "end"))
                    if (b >= 0L) {
                        spanDepth++
                        curSpanStart = b
                        curSpanEnd = e
                        curSpanText.setLength(0)
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (skipDepth > 0 || !inP || length <= 0) return
                val text = String(ch, start, length)
                if (spanDepth > 0) {
                    curSpanText.append(text)
                } else {
                    pPlain.append(text)
                    if (text.any { it.isWhitespace() }) sawWhitespaceBetweenSpans = true
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                val tag = qName.substringAfter(':').lowercase()
                if (skipDepth > 0) {
                    skipDepth--
                    return
                }
                if (inP && tag == "span" && spanDepth > 0) {
                    val raw = curSpanText.toString()
                    if (raw.isNotEmpty() && curSpanStart >= 0L) {
                        val end = if (curSpanEnd > curSpanStart) curSpanEnd else curSpanStart + 180L
                        spans.add(RawSpan(curSpanStart, end, raw, sawWhitespaceBetweenSpans))
                        sawWhitespaceBetweenSpans = false
                    }
                    curSpanText.setLength(0)
                    spanDepth--
                } else if (inP && tag == "p") {
                    inP = false
                    if (spans.isNotEmpty()) {
                        val words = mergeTtmlSpans(spans)
                        if (words.isNotEmpty()) {
                            anyWordTimed = true
                            val start = if (pStart >= 0L) min(pStart, words.first().startMs) else words.first().startMs
                            val end = max(pEnd, words.last().endMs)
                            lines.add(ParsedLine(start, end, words.joinToString(" ") { it.text }, words))
                            return
                        }
                    }
                    val text = MULTI_SPACE.replace(pPlain.toString(), " ").trim()
                    if (pStart >= 0L && text.isNotEmpty()) {
                        lines.add(ParsedLine(pStart, pEnd, text))
                    }
                }
            }

            private fun mergeTtmlSpans(rawSpans: List<RawSpan>): List<WordTiming> {
                val out = ArrayList<WordTiming>()
                val buf = StringBuilder()
                val syls = ArrayList<Lyrics.Syllable>()
                var wStart = -1L
                var wEnd = -1L

                fun flush() {
                    val t = buf.toString().trim()
                    if (t.isNotEmpty() && wStart >= 0L) {
                        val parts = t.split(MULTI_SPACE).filter { it.isNotEmpty() }
                        if (parts.size == 1) {
                            out.add(WordTiming(wStart, max(wStart + 40L, wEnd), parts[0], if (syls.size >= 2) ArrayList(syls) else emptyList()))
                        } else if (parts.size > 1) {
                            val safeEnd = max(wStart + 50L * parts.size, wEnd)
                            val totalLen = parts.sumOf { max(1, visibleLength(it)) }.toDouble()
                            var cursor = wStart
                            for ((idx, part) in parts.withIndex()) {
                                val w = max(1, visibleLength(part))
                                val end = if (idx == parts.lastIndex) safeEnd else cursor + ((safeEnd - wStart) * (w / totalLen)).roundToLong()
                                out.add(WordTiming(cursor, max(cursor + 40L, end), part))
                                cursor = end
                            }
                        }
                    }
                    buf.setLength(0)
                    syls.clear()
                    wStart = -1L
                    wEnd = -1L
                }

                for (sp in rawSpans) {
                    val leading = sp.spaceBefore || sp.text.first().isWhitespace()
                    val trailing = sp.text.last().isWhitespace()
                    val trimmed = sp.text.trim()
                    if (leading && buf.isNotEmpty()) flush()
                    if (trimmed.isEmpty()) {
                        if (buf.isNotEmpty()) flush()
                        continue
                    }
                    if (wStart < 0L) wStart = sp.start
                    wEnd = max(wEnd, sp.end)
                    buf.append(trimmed)
                    syls.add(Lyrics.Syllable(sp.start, max(sp.start + 40L, sp.end), trimmed))
                    if (trailing) flush()
                }
                flush()
                return out
            }

            private fun attr(attrs: Attributes, name: String): String? {
                for (i in 0 until attrs.length) {
                    val q = attrs.getQName(i)
                    if (q == name || q.substringAfter(':') == name) return attrs.getValue(i)
                }
                return null
            }
        }

        return try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Throwable) {}
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Throwable) {}
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Throwable) {}
            factory.newSAXParser().parse(InputSource(StringReader(ttml)), handler)
            if (lines.none { !isInterludeText(it.text) }) null
            else {
                lines.sortBy { it.ms }
                ParsedLyrics(synced = true, lines = lines, source = Lyrics.SOURCE_TTML, wordSynced = anyWordTimed)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Parses TTML clock expressions (`hh:mm:ss.fff`, `mm:ss.fff`, `ss.fff`, `12.3s`, `1234ms`) into ms, or `-1L`. */
    fun parseClock(raw: String?): Long {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return -1L
        if (s.endsWith("ms", ignoreCase = true)) {
            return s.dropLast(2).toDoubleOrNull()?.roundToLong()?.coerceAtLeast(0L) ?: -1L
        }
        if (s.endsWith("s", ignoreCase = true)) {
            return s.dropLast(1).toDoubleOrNull()?.let { (it * 1000.0).roundToLong().coerceAtLeast(0L) } ?: -1L
        }
        val parts = s.split(':')
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: return -1L
                val m = parts[1].toLongOrNull() ?: return -1L
                val sec = parts[2].toDoubleOrNull() ?: return -1L
                ((h * 3600L + m * 60L) * 1000L + (sec * 1000.0).roundToLong()).coerceAtLeast(0L)
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: return -1L
                val sec = parts[1].toDoubleOrNull() ?: return -1L
                (m * 60_000L + (sec * 1000.0).roundToLong()).coerceAtLeast(0L)
            }
            1 -> parts[0].toDoubleOrNull()?.let { (it * 1000.0).roundToLong().coerceAtLeast(0L) } ?: -1L
            else -> -1L
        }
    }

    // ------------------------------------------------------------------------------------------ LRC / Enhanced LRC parser

    private val LRC_TAG_RE = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val LRC_OFFSET_RE = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    /**
     * Parses standard LRC (`[mm:ss.xx] text`) and Enhanced LRC (`<mm:ss.xx>word`), honoring `[offset:]` and multi-timestamp
     * lines. Pure Kotlin so JVM tests exercise the exact production parser.
     */
    fun parseLrc(text: String): List<Lyrics.Line> {
        val out = ArrayList<Lyrics.Line>()
        var offset = 0L
        for (raw in text.lines()) {
            val line = raw.trim()
            LRC_OFFSET_RE.find(line)?.let { offset = it.groupValues[1].toLongOrNull() ?: 0L }
            val times = LRC_TAG_RE.findAll(line).toList()
            if (times.isEmpty()) continue
            val rest = line.substring(times.last().range.last + 1)
            val tagged = rest.indexOf('<') >= 0
            var first = -1L
            for (m in times) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                val at = (min * 60_000 + sec * 1000 + ms - offset).coerceAtLeast(0L)
                if (first < 0L) first = at
                if (!tagged) { out.add(Lyrics.Line(at, rest.trim())); continue }
                val w = Words.parseTagged(rest, at - first - offset)
                out.add(Lyrics.Line(at, w.text, w.times, w.ends))
            }
        }
        out.sortBy { it.ms }
        return out
    }

    // ------------------------------------------------------------------------------------------ Naghm PhoneLink rich JSON

    /**
     * Parses a Naghm `lyrics` JSON message (both legacy line-only format and optional word/syllable-timed format) into
     * a [Lyrics.Result]. Pure Kotlin so JVM tests exercise the exact same parser as [PhoneLink.lyricsOf].
     */
    fun parsePhoneLyricsJson(json: String, durationSec: Int = 0): Lyrics.Result? {
        val root = JsonReader.parse(json) as? Map<*, *> ?: return null
        val arr = root["lines"] as? List<*> ?: return null
        val lines = ArrayList<Lyrics.Line>(arr.size)
        var timed = false
        var anyWords = false
        for (entry in arr) {
            val o = entry as? Map<*, *> ?: continue
            val atSec = asDouble(o["time"], -1.0)
            if (atSec >= 0.0) timed = true
            val ms = if (atSec >= 0.0) (atSec * 1000.0).roundToLong() else 0L
            val endSec = asDouble(o["end"], -1.0)
            val endMs = if (endSec > atSec && endSec > 0.0) (endSec * 1000.0).roundToLong() else -1L
            val text = (o["text"] as? String)?.trim().orEmpty()
            val rawWords = o["words"] as? List<*>
            if (!rawWords.isNullOrEmpty() && text.isNotEmpty()) {
                val parsedWords = ArrayList<WordTiming>(rawWords.size)
                for (we in rawWords) {
                    val wm = we as? Map<*, *> ?: continue
                    val wt = asDouble(wm["t"] ?: wm["time"], -1.0)
                    if (wt < 0.0) continue
                    val weSec = asDouble(wm["e"] ?: wm["end"], -1.0)
                    val wText = (wm["w"] ?: wm["text"]) as? String ?: continue
                    val wStart = (wt * 1000.0).roundToLong()
                    val wEnd = if (weSec > wt) (weSec * 1000.0).roundToLong() else wStart + 250L
                    val rawSyls = wm["s"] as? List<*>
                    val syls = ArrayList<Lyrics.Syllable>()
                    if (rawSyls != null) {
                        for (se in rawSyls) {
                            if (se is List<*> && se.size >= 3) {
                                val st = asDouble(se[0], -1.0)
                                val seEnd = asDouble(se[1], -1.0)
                                val stxt = se[2] as? String ?: continue
                                if (st >= 0.0) syls.add(Lyrics.Syllable((st * 1000.0).roundToLong(), max((st * 1000.0).roundToLong() + 40L, (seEnd * 1000.0).roundToLong()), stxt))
                            }
                        }
                    }
                    parsedWords.add(WordTiming(wStart, wEnd, wText.trim(), syls))
                }
                if (parsedWords.isNotEmpty() && Words.count(text) == parsedWords.size) {
                    anyWords = true
                    val built = toLines(ParsedLyrics(true, listOf(ParsedLine(ms, endMs, text, parsedWords)), Lyrics.SOURCE_NAGHM, true))
                    lines.add(built[0])
                    continue
                }
            }
            lines.add(Lyrics.Line(ms, text, endMs = endMs))
        }
        if (lines.none { it.text.isNotBlank() }) return null
        val syncedFlag = (root["synced"] as? Boolean) ?: timed
        return Lyrics.Result(
            synced = syncedFlag && timed,
            lines = lines,
            durationSec = durationSec,
            source = Lyrics.SOURCE_NAGHM,
            words = if (anyWords) "naghm" else ""
        )
    }

    // ------------------------------------------------------------------------------------------ lightweight pure JSON reader

    private fun asLong(v: Any?, fallback: Long): Long = when (v) {
        is Number -> v.toDouble().roundToLong()
        is String -> v.toDoubleOrNull()?.roundToLong() ?: fallback
        else -> fallback
    }

    private fun asDouble(v: Any?, fallback: Double): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: fallback
        else -> fallback
    }

    /** Zero-dependency recursive-descent JSON reader (RFC 8259) so parsing works identically on Android and JVM tests. */
    private class JsonReader(private val s: String) {
        private var i = 0

        private fun ws() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
            }
        }

        private fun value(): Any? {
            ws()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> if (s.startsWith("true", i)) { i += 4; true } else null
                'f' -> if (s.startsWith("false", i)) { i += 5; false } else null
                'n' -> if (s.startsWith("null", i)) { i += 4; null } else null
                else -> num()
            }
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (i < s.length) {
                ws()
                if (i >= s.length || s[i] != '"') break
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') break
                i++
                m[k] = value()
                ws()
                if (i >= s.length) break
                val sep = s[i++]
                if (sep == '}') return m
                if (sep != ',') break
            }
            return m
        }

        private fun arr(): List<Any?> {
            val a = ArrayList<Any?>()
            i++ // [
            ws()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (i < s.length) {
                a.add(value())
                ws()
                if (i >= s.length) break
                val sep = s[i++]
                if (sep == ']') return a
                if (sep != ',') break
            }
            return a
        }

        private fun str(): String {
            val b = StringBuilder()
            i++ // "
            while (i < s.length && s[i] != '"') {
                val c = s[i++]
                if (c == '\\' && i < s.length) {
                    when (val esc = s[i++]) {
                        '"' -> b.append('"')
                        '\\' -> b.append('\\')
                        '/' -> b.append('/')
                        'n' -> b.append('\n')
                        't' -> b.append('\t')
                        'r' -> b.append('\r')
                        'b' -> b.append('\b')
                        'f' -> b.append('\u000C')
                        'u' -> if (i + 4 <= s.length) {
                            val hex = s.substring(i, i + 4).toIntOrNull(16)
                            if (hex != null) b.append(hex.toChar())
                            i += 4
                        }
                        else -> b.append(esc)
                    }
                } else {
                    b.append(c)
                }
            }
            if (i < s.length && s[i] == '"') i++
            return b.toString()
        }

        private fun num(): Double? {
            val from = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return if (i > from) s.substring(from, i).toDoubleOrNull() else null
        }

        companion object {
            fun parse(text: String): Any? = try { JsonReader(text).value() } catch (_: Throwable) { null }
        }
    }
}
