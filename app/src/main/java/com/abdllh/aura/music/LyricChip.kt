package com.abdllh.aura.music

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Asked
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.ink
import com.abdllh.aura.ui.label
import com.abdllh.aura.system.DriveState

/**
 * One line of the lyrics under the car, while the Music panel lies collapsed over the map (SPEC.md section 5, "Lyric chip"; `music-driving`, `music-driving-free`): the `pill`
 * surface at (384, 456), 628 x 50, `lyricChip` at the reading edge. It steps with the song by its own time, a crossfade of 140, no travel and no scrolling, and redraws one label when it does.
 * Never two lines: a long line shows the part that holds the current word, cut at word boundaries with an ellipsis ([partsOf]). The word's pill is parked only ([Asked.LYRIC_WORD_MOVING]), and
 * only with real word times.
 *
 * It lies in Maps' panel ([MapsPanel] adds it, one line) and is driven by the Music panel, which is what knows the song; it holds nothing of its own but the text it shows.
 */
internal class LyricChip(ctx: Context) : SurfaceCard(ctx, Surface.PILL) {
    private var a = line(ctx)
    private var b = line(ctx)

    /** The Music panel's word: the chip may show. It still needs a line to show. */
    private var wanted = false
    private var hasLine = false
    private var shown = false

    private var lines: List<Lyrics.Line>? = null
    private var lineIndex = -2
    private var parts: List<Part> = emptyList()
    private var partIndex = -1
    private var wordTimes: LongArray? = null
    private var shownText: CharSequence = ""

    var onTap: (() -> Unit)? = null

    private fun line(ctx: Context) = ChipLine(ctx).also {
        it.setTextColor(Palette.ink)
        it.ink { Palette.ink }
        it.oneLine(Type.lyricChip)
        it.alignStart = true
        it.alpha = 0f
        addView(it, Type.lyricChip.box(SIDE, 0, TEXT_W))
    }

    init {
        visibility = GONE
        isClickable = true
        setOnClickListener { onTap?.invoke() }
        current = this
    }

    /** With a line to show, it shows ([on]); otherwise it goes. The way in: y +8 to 0, 180 `move`. */
    fun want(on: Boolean) {
        if (on == wanted) return
        wanted = on
        if (!on) { lines = null; lineIndex = -2; shownText = ""; hasLine = false; a.alpha = 0f; b.alpha = 0f; a.word(null); b.word(null) }
        sync()
    }

    private fun sync() {
        val on = wanted && hasLine
        if (on == shown) return
        shown = on
        animate().cancel()
        if (on) {
            visibility = VISIBLE
            alpha = 0f
            translationY = 8f
            animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(Tokens2.MS_cardIn).setInterpolator(Motion.MOVE).setUpdateListener(null).withEndAction(null).start()
        } else {
            animate().alpha(0f).setStartDelay(0).setDuration(Tokens2.MS_cardOut).setInterpolator(Motion.EXIT).setUpdateListener(null).withEndAction { if (!shown) visibility = GONE }.start()
        }
    }

    /** The song is at [ms], on [rows] (null: no timed lyrics to follow). Only a change touches a view. */
    fun at(rows: List<Lyrics.Line>?, ms: Long) {
        if (!wanted) return
        if (rows == null || rows.isEmpty()) {
            if (hasLine) { hasLine = false; sync() }
            lines = null
            lineIndex = -2
            return
        }
        val i = LyricsView.lineAt(rows, ms)
        if (rows !== lines || i != lineIndex) {
            lines = rows
            lineIndex = i
            val t = rows.getOrNull(i)?.text.orEmpty()
            if (t.isBlank()) {
                hasLine = false
                parts = emptyList()
                partIndex = -1
                wordTimes = null
                sync()
                return
            }
            parts = partsOf(t)
            partIndex = -1
            wordTimes = Words.times(rows, i)
            hasLine = true
        }
        if (parts.isEmpty()) return
        // the word now: by its real time, otherwise as far through the words as the line is through its time
        val now = ms + LyricsView.WORD_LEAD_MS
        val wt = wordTimes
        val total = parts.last().lastWord + 1
        val word = if (wt != null && wt.size == total + 1) {
            var w = 0
            while (w + 1 < total && wt[w + 1] <= now) w++
            w
        } else {
            val from = rows[i].ms
            val to = rows.getOrNull(i + 1)?.ms ?: (from + NO_NEXT_MS)
            (((now - from).toFloat() / (to - from).coerceAtLeast(1L)) * total).toInt().coerceIn(0, total - 1)
        }
        val p = parts.indexOfFirst { word <= it.lastWord }.coerceAtLeast(0)
        if (p != partIndex) {
            partIndex = p
            swap(parts[p].text)
        }
        // the word's pill: parked only, real times only, and only while it is being sung
        val onPill = (Asked.LYRIC_WORD_MOVING || DriveState.parked) && wt != null && wt.size == total + 1 && now >= wt[word] && now < wt[word + 1]
        a.word(if (onPill) parts[p].wordRange(word) else null)
        sync()
    }

    /** The song's colours for the word's pill. */
    fun wordColours(pill: Int, ink: Int) {
        for (l in arrayOf(a, b)) { l.pill = pill; l.pillInk = ink; l.invalidate() }
    }

    /** How long until the next word is due, while the word's pill is what shows: the panel's tick comes then. */
    fun msToNext(ms: Long): Long {
        val wt = wordTimes ?: return Long.MAX_VALUE
        if (!shown || !(Asked.LYRIC_WORD_MOVING || DriveState.parked)) return Long.MAX_VALUE
        val now = ms + LyricsView.WORD_LEAD_MS
        for (t in wt) if (t > now) return t - now
        return Long.MAX_VALUE
    }

    /** A crossfade, no travel. [a] is always the one showing. */
    private fun swap(text: CharSequence) {
        if (text == shownText) return
        shownText = text
        val out = a
        val inn = b
        a = inn
        b = out
        out.word(null)
        inn.word(null)
        inn.text = text
        inn.animate().cancel()
        out.animate().cancel()
        if (!shown || !isShown) {
            inn.alpha = 1f
            out.alpha = 0f
            return
        }
        inn.animate().alpha(1f).setStartDelay(0).setDuration(Tokens2.MS_swap).setInterpolator(Motion.MOVE).start()
        out.animate().alpha(0f).setStartDelay(0).setDuration(Tokens2.MS_swap).setInterpolator(Motion.MOVE).start()
    }

    /** A part of a line: its text, the last word in it, and where each of its words lies in that text. */
    private class Part(val text: String, val firstWord: Int, val lastWord: Int, private val starts: IntArray, private val ends: IntArray) {
        fun wordRange(word: Int): IntArray? {
            val k = word - firstWord
            return if (k in starts.indices) intArrayOf(starts[k], ends[k]) else null
        }
    }

    /** The line as it fits: whole, or cut at word boundaries with an ellipsis on the side that goes on. */
    private fun partsOf(text: String): List<Part> {
        val paint = a.paint
        val n = Words.count(text)
        val s = IntArray(n)
        val e = IntArray(n)
        if (n > 0) Words.bounds(text, s, e)
        if (n == 0 || paint.measureText(text) <= TEXT_W) return listOf(Part(text, 0, (n - 1).coerceAtLeast(0), s, e))
        val room = TEXT_W - paint.measureText("$ELLIPSIS $ELLIPSIS")
        val out = ArrayList<Part>()
        var first = 0
        while (first < n) {
            var last = first
            while (last + 1 < n && paint.measureText(text, s[first], e[last + 1]) <= room) last++
            val before = if (first > 0) "$ELLIPSIS " else ""
            val after = if (last < n - 1) " $ELLIPSIS" else ""
            val shift = before.length - s[first]
            out.add(Part(before + text.substring(s[first], e[last]) + after, first, last,
                IntArray(last - first + 1) { s[first + it] + shift }, IntArray(last - first + 1) { e[first + it] + shift }))
            first = last + 1
        }
        return out
    }

    override fun onDetachedFromWindow() {
        if (current === this) current = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        current = this
    }

    /** One label, and the word's pill under it when it has one. */
    private class ChipLine(c: Context) : AText(c) {
        var pill = Palette.wordPill
        var pillInk = Palette.wordPillInk
        private var range: IntArray? = null
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val from = RectF()
        private val to = RectF()
        private var hasWord = false
        private val glide = Spring(1f, Tokens2.SPRING_wordPill[0], Tokens2.SPRING_wordPill[1], 0.003f) { invalidate() }

        override fun hasOverlappingRendering() = false

        fun word(r: IntArray?) {
            val same = (r == null && range == null) || (r != null && range != null && r[0] == range!![0] && r[1] == range!![1])
            if (same) return
            val had = hasWord
            range = r
            hasWord = r != null && place(r)
            if (hasWord) {
                if (had) { from.set(rect); glide.snapTo(0f); glide.animateTo(1f) } else { from.set(to); glide.snapTo(1f) }
            }
            invalidate()
        }

        private fun place(r: IntArray): Boolean {
            val l = layout ?: return false
            if (r[1] > text.length) return false
            l.getSelectionPath(r[0], r[1], PATH)
            PATH.computeBounds(BOX, true)
            paint.getFontMetrics(FM)
            val base = l.getLineBaseline(0) + totalPaddingTop
            to.set(totalPaddingLeft + BOX.left - PILL_SIDE, (base + FM.ascent - PILL_ABOVE), totalPaddingLeft + BOX.right + PILL_SIDE, (base + FM.descent + PILL_BELOW))
            return true
        }

        override fun onDraw(c: Canvas) {
            val l = layout
            if (!hasWord || l == null) { super.onDraw(c); return }
            val g = glide.value
            rect.set(from.left + (to.left - from.left) * g, to.top, from.right + (to.right - from.right) * g, to.bottom)
            fill.color = pill
            val r = Tokens2.R_word.toFloat()
            c.drawRoundRect(rect, r, r, fill)
            val lit = currentTextColor
            var save = c.save()
            c.clipOutRect(rect)
            c.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            paint.color = lit
            l.draw(c)
            c.restoreToCount(save)
            save = c.save()
            c.clipRect(rect)
            c.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            paint.color = pillInk
            l.draw(c)
            c.restoreToCount(save)
            paint.color = lit
        }

        private companion object {
            const val PILL_SIDE = 10f
            const val PILL_ABOVE = 0f
            const val PILL_BELOW = 0f
            val PATH = android.graphics.Path()
            val BOX = RectF()
            val FM = Paint.FontMetrics()
        }
    }

    companion object {
        private const val SIDE = 18
        private const val TEXT_W = 592
        private const val ELLIPSIS = "…"
        /** The last line has no next one to say how long it lasts. */
        private const val NO_NEXT_MS = 6000L

        /** Where it lies in Maps' panel: its rect (SPEC.md section 6.4). */
        fun place(): FrameLayout.LayoutParams = Surface.PILL.at(384, 456, 628, 50)

        /** The one there is, in Maps' panel. */
        var current: LyricChip? = null
            private set
    }
}
