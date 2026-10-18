package com.abdllh.aura.music

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.text.PrecomputedText
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.abdllh.aura.R
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lift
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The song's lyrics on the music screen. Timed lyrics: the line being sung is lit and glides to the middle, the others
 * dimmed and a little smaller (a few seconds after the owner scrolls by hand, it follows the song again); a tapped
 * line is where the song goes ([onLineTap]: the unit's own songs jump there, Bluetooth ones set their clock — the
 * phone does not say where a song is, see [BtMusic]). [needSync]: the song's position is unknown, so nothing is lit
 * and a hint asks for that tap. Untimed: just the text.
 *
 * [big]: the lyrics alone on the whole screen, in large type. The music screen keeps one of each, fed alike; the one
 * out of sight follows the song without moving anything (no animation runs where nobody looks), so it is in place
 * the moment it is shown.
 *
 * New lyrics become lines without stopping the screen (1.12): their text is measured on a worker thread
 * ([PrecomputedText]: the Arabic shaping was most of the cost), the lines are made a dozen a frame, and the copy out
 * of sight makes its own only when the screen is idle (or the moment it is shown). Before, both copies made 60–90
 * lines each, with three drawables apiece, in one message on the main thread, right in the middle of the new cover's
 * animation. A tapped line's tint is drawn by the list behind it, not a background per line.
 *
 * Word by word (1.15, [wordsOn]): on the line being sung the words already sung are lit, the one being sung stands on
 * a pill of the cover's colour and fills with that colour in its own reading direction, the ones to come stay clearly
 * dimmer ([Line.onDraw]). Only lines whose words' times are real (1.16: a word-timed source or heard by the owner's
 * server, [Words]) are drawn so; the others are lit whole. The song's clock is the one that lights the line ([at]),
 * carried on from its last report between the music screen's ticks.
 */
internal class LyricsView(c: Context, private val big: Boolean = false) : FrameLayout(c) {
    private val scroll = ScrollView(c).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = true // the lines fade at the edges instead of being cut
        // (alone on the screen the fade is short: it would otherwise take a line at each end of the few that fit)
        setFadingEdgeLength((if (big) 40 else 70).dp)
    }
    private val list = LineList(c)
    private val statusBox: LinearLayout
    private val status = c.label(if (big) Type.TITLE else Type.BODY, Palette.text2, lines = 3, gravity = Gravity.CENTER)
    private val retry = ABtn(c).apply { kind = ABtn.Kind.TONAL; setText(R.string.lyrics_retry) }
    private val hint: LinearLayout
    private val hintIcon: ImageView
    /** While the song is listened to for its timing (1.13): a quiet note where the sync hint would be. */
    private val note: LinearLayout
    private val noteIcon: ImageView
    private val noteText: AText
    private val done: AText
    private val doneBg = Shapes.pill(Palette.accent)
    private var lines: List<Lyrics.Line> = emptyList()
    private var views: List<Line> = emptyList()
    private var synced = false
    private var current = -1
    private var touchedAt = 0L
    var onLineTap: ((Lyrics.Line) -> Unit)? = null
    /** A timed line held down (1.15: the music screen offers to forget a saved timing); true when it did something. */
    var onLineHold: (() -> Boolean)? = null

    // ---- the song's clock for the words (1.15)
    /** Word by word: the owner's setting (Settings → Display & sound → Music). Off: whole lines, as before 1.15. */
    var wordsOn = true
        set(v) {
            if (v == field) return
            field = v
            views.getOrNull(current)?.let { it.karaoke = v && it.times != null }
        }
    private var clockMs = 0L
    private var clockAt = 0L
    /** The song plays: the clock runs on from its last report ([at]) between the screen's ticks. */
    private var songPlaying = false
    private var drawnMs = Long.MIN_VALUE

    /** Where the song is now, for the words: the last report carried on, never a small step back. */
    fun songNow(): Long {
        var p = if (songPlaying) clockMs + (SystemClock.uptimeMillis() - clockAt) else clockMs
        // (a tick's report a few ms behind the clock carried on would move the fill's edge back and forth)
        if (p < drawnMs && p > drawnMs - 250L) p = drawnMs
        drawnMs = p
        return p
    }

    private val dim = if (big) 0.30f else 0.34f   // lines around the one being sung
    private val small = if (big) 0.92f else 0.95f

    /**
     * Brings the lit line to the middle. A spring, not an animator restarted at each line: retargeted half way (lines a
     * few hundred ms apart: a chorus, rap) it keeps its speed instead of stopping and starting again, and it leaves at
     * once rather than trailing the highlight. About 95 % there in 450 ms, no overshoot.
     */
    private val follow = Spring(0f, 110f, 1f, 0.5f) { scroll.scrollTo(0, it.roundToInt()) }

    /** Unknown position: no line lit, the hint shown (Bluetooth: until a line is tapped or the next song starts). */
    var needSync = false
        set(v) {
            if (v == field) return
            field = v
            showHint()
            pad()
            if (v) { for (t in views) { t.karaoke = false; fade(t, IDLE, 1f, 0L, Motion.STANDARD) }; current = -1 }
        }

    init {
        scroll.addView(list, LayoutParams(MATCH, WRAP))
        addView(scroll, flp(MATCH, MATCH))
        scroll.setOnTouchListener { _, e ->
            touchedAt = SystemClock.elapsedRealtime()
            follow.cancel()
            removeCallbacks(followAgain)
            // (after a hand scroll the lit line comes back by itself, even through a long stretch without a new line)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) postDelayed(followAgain, FOLLOW_AFTER_TOUCH_MS)
            false
        }

        statusBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE }
        statusBox.addView(c.iconView(R.drawable.ic_lyrics, 40, Palette.text3), lp(40.dp, 40.dp))
        statusBox.addView(status, lp(MATCH, WRAP).apply { topMargin = Space.M.dp })
        statusBox.addView(retry, lp(WRAP, WRAP).apply { topMargin = Space.L.dp })
        addView(statusBox, flp(MATCH, WRAP, Gravity.CENTER).apply { setMargins(28.dp, 0, 28.dp, 0) })

        hintIcon = c.iconView(R.drawable.ic_lyrics, 22, Palette.accentFg)
        hint = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(Space.L.dp, Space.S.dp, Space.L.dp, Space.S.dp)
            minimumHeight = 44.dp
            background = Shapes.pill(Palette.withAlpha(Palette.card, if (Palette.dark) 0.9f else 0.95f), if (Palette.dark) Palette.stroke else 0)
            lift(Elev.FLOAT)
            visibility = View.GONE
            addView(hintIcon, lp(22.dp, 22.dp))
            addView(c.label(Type.BUTTON, Palette.text, lines = 2).apply { setText(R.string.lyrics_tap_sync) },
                lp(WRAP, WRAP).apply { marginStart = Space.S.dp })
        }
        // (clear of the corner buttons the music screen puts over the lyrics)
        addView(hint, flp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(72.dp, Space.M.dp, 72.dp, 0) })
        // the first lines start below the hint once it is measured (out of the layout pass that measured it)
        hint.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) post { pad() } }
        noteIcon = c.iconView(R.drawable.ic_mic, 18, Palette.accentFg)
        noteText = c.label(Type.FOOTNOTE, Palette.text2, lines = 1)
        note = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(Space.M.dp, Space.XS.dp, Space.M.dp, Space.XS.dp)
            minimumHeight = 32.dp
            background = Shapes.pill(Palette.withAlpha(Palette.card, if (Palette.dark) 0.85f else 0.92f), if (Palette.dark) Palette.stroke else 0)
            visibility = View.GONE
            addView(noteIcon, lp(18.dp, 18.dp))
            addView(noteText, lp(WRAP, WRAP).apply { marginStart = Space.XS.dp })
        }
        // (where the sync hint is: listening needs a known position, so the two never show together)
        addView(note, flp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(72.dp, Space.M.dp, 72.dp, 0) })
        done = c.label(Type.BUTTON, Palette.onColor(Palette.accent), gravity = Gravity.CENTER).apply {
            setText(R.string.lyrics_synced)
            background = doneBg
            setPadding(Space.L.dp, Space.S.dp, Space.L.dp, Space.S.dp)
            alpha = 0f
        }
        addView(done, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Space.L.dp })
    }

    /** The cover's colour, as the rest of the screen has it: the hint's icon and the "synced" note. */
    fun accent(fill: Int, text: Int) {
        hintIcon.setColorFilter(text)
        noteIcon.setColorFilter(text)
        doneBg.setColor(fill)
        done.setTextColor(Palette.onColor(fill))
        // (1.16: the word being sung is drawn in the cover's colour, on a pill of it)
        wordFill = fill
        wordText = text
        views.getOrNull(current)?.let { if (it.karaoke) it.invalidate() }
    }

    /** The cover's colours for the word being sung ([accent]): the pill behind it, and its lit part. */
    private var wordFill = Palette.accent
    private var wordText = Palette.accent

    private var noteShown = false

    /**
     * The timing by listening at work ([text]: what it does, with how much is timed; [icon]: the microphone while it
     * listens), or nothing (null). It comes and goes like the hint; a new text replaces the old in place.
     */
    fun note(text: CharSequence?, icon: Int = R.drawable.ic_mic) {
        val want = text != null
        if (want) { noteText.text = text; noteIcon.setImageResource(icon) }
        if (want == noteShown) return
        noteShown = want
        if (want) note.enter(dy = -8f) else note.exit(dy = -6f) { if (!noteShown) note.visibility = View.GONE }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pad()
    }

    /**
     * Room above the first line and below the last, so that either can stand in the middle. Lines to be read, not
     * followed (untimed ones, or timed ones waiting for the owner's tap), start near the top instead: the whole screen
     * of lyrics showed two and a half lines under 40 % of nothing exactly when one had to be found and tapped.
     */
    private fun pad() {
        val h = height
        if (h == 0) return
        val side = (if (big) 56 else 24).dp
        val top = when {
            synced && !needSync -> (h * (if (big) 0.30f else 0.38f)).toInt()
            // (below the hint, which takes two lines in Arabic: before it is measured, room for one)
            needSync -> maxOf((h * 0.08f).toInt(), if (hint.height > 0) hint.bottom + Space.S.dp else 64.dp)
            else -> (h * 0.08f).toInt()
        }
        val bottom = (h * 0.5f).toInt()
        if (list.paddingTop == top && list.paddingBottom == bottom && list.paddingLeft == side) return
        list.setPadding(side, top, side, bottom)
        centerOnLayout = true // (the lit line stays where it was, in the middle)
    }

    /** A message instead of lyrics (looking, none, no internet...); [onRetry]: a button that asks again. */
    fun showStatus(text: String, onRetry: (() -> Unit)? = null) {
        drop()
        lines = emptyList(); views = emptyList(); current = -1
        list.removeAllViews()
        status.text = text
        retry.visibility = if (onRetry != null) View.VISIBLE else View.GONE
        retry.setOnClickListener { onRetry?.invoke() }
        if (statusBox.visibility != View.VISIBLE || statusBox.alpha < 1f) {
            if (statusBox.visibility != View.VISIBLE) statusBox.alpha = 0f
            statusBox.visibility = View.VISIBLE
            if (isShown) statusBox.fadeTo(1f, Motion.MEDIUM) else { statusBox.animate().cancel(); statusBox.alpha = 1f }
        }
        scroll.visibility = View.GONE
        showHint()
    }

    /**
     * Another song is being looked up: what shows steps back, still there, until the answer replaces it — instead of a
     * "looking" line flashing between two songs that both have lyrics (the cached answer comes a moment later). Its
     * lines no longer follow the song or take taps (they are the song before's).
     */
    fun dim() {
        if (scroll.visibility == View.VISIBLE && list.childCount > 0) {
            for (v in views) { v.isClickable = false; v.isLongClickable = false; v.karaoke = false }
            lines = emptyList(); views = emptyList(); current = -1
            follow.cancel()
            scroll.fadeTo(0.25f)
        } else if (statusBox.visibility == View.VISIBLE) statusBox.fadeTo(0.25f)
    }

    // ---- making the lines
    /** Lyrics waiting to become lines, their texts once measured, and the lines made so far. */
    private var pending: Lyrics.Result? = null
    private var texts: List<CharSequence>? = null
    /** The words' times of the lines waiting to be made (worked out with their texts; null for untimed lyrics). */
    private var times: List<LongArray?>? = null
    private var made = ArrayList<Line>()
    /** Counts the lyrics shown: a measuring that comes back for lyrics already replaced is dropped. */
    private var gen = 0
    /** The lit line goes to the middle at the next layout (the lines were just added, or the room around them changed). */
    private var centerOnLayout = false

    private fun drop() {
        gen++
        shiftOnLayout = 0
        pending = null; texts = null; times = null; made = ArrayList()
        removeCallbacks(buildStep); removeCallbacks(idleBuild)
        follow.cancel()
    }

    fun show(r: Lyrics.Result) {
        drop()
        val g = gen
        lines = r.lines
        synced = r.synced
        current = -1
        views = emptyList() // (what shows stays until the new lines are made: see attach)
        pending = r
        pad()
        val plain = r.lines.map { it.text.ifBlank { "♪" } }
        val params = if (Build.VERSION.SDK_INT >= 28) template(r.synced).textMetricsParams else null
        worker.execute {
            val out: List<CharSequence> = if (params == null || Build.VERSION.SDK_INT < 28) plain
                else plain.map { t -> try { PrecomputedText.create(t, params) } catch (_: Throwable) { t } }
            val wt = if (r.synced) r.lines.indices.map { Words.times(r.lines, it) } else null
            post {
                if (g != gen) return@post
                texts = out
                times = wt
                if (isShown) buildStep.run() else postDelayed(idleBuild, IDLE_BUILD_MS)
            }
        }
    }

    private val buildStep = object : Runnable {
        override fun run() {
            val r = pending ?: return
            val t = texts ?: return
            val end = minOf(made.size + LINES_PER_FRAME, r.lines.size)
            for (k in made.size until end) made.add(line(r.lines[k], t[k], times?.getOrNull(k)))
            if (made.size < r.lines.size) postOnAnimation(this) else attach()
        }
    }

    /** The copy out of sight makes its lines once the screen has been calm for a moment. */
    private val idleBuild = Runnable { if (pending != null && texts != null) buildStep.run() }

    /** Shown now: the lines that are still to come are made at once. */
    private fun finishNow() {
        val r = pending ?: return
        removeCallbacks(buildStep); removeCallbacks(idleBuild)
        val t = texts ?: r.lines.map { it.text.ifBlank { "♪" } }
        val wt = times ?: if (r.synced) r.lines.indices.map { Words.times(r.lines, it) } else null
        for (k in made.size until r.lines.size) made.add(line(r.lines[k], t[k], wt?.getOrNull(k)))
        attach()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // (made a dozen a frame from the next frame on, not inside the change that showed it)
        if (isVisible && pending != null && texts != null) { removeCallbacks(idleBuild); removeCallbacks(buildStep); post(buildStep) }
    }

    /** The new lines take the old ones' place, each as [at] has left it, the lit one in the middle. */
    private fun attach() {
        pending = null
        list.removeAllViews()
        views = made
        made = ArrayList()
        for ((k, v) in views.withIndex()) {
            list.addView(v, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
            val lit = k == current
            v.alpha = if (!synced) 0.92f else if (current < 0) IDLE else if (lit) 1f else dim
            val s = if (!synced || current < 0 || lit) 1f else small
            v.scaleX = s; v.scaleY = s
            // (lyrics that came while a line was being sung: that line goes word by word at once, not from the next)
            if (lit && synced) v.karaoke = wordsOn && v.times != null
        }
        statusBox.animate().cancel()
        statusBox.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        centerOnLayout = true
        if (isShown) { scroll.animate().cancel(); scroll.alpha = 0f; scroll.fadeTo(1f, Motion.MEDIUM) }
        else { scroll.animate().cancel(); scroll.alpha = 1f }
        showHint()
    }

    private fun style(v: Line, timed: Boolean) {
        v.textSize = if (big) (if (timed) BIG_TIMED_SP else BIG_PLAIN_SP) else (if (timed) Type.HEADLINE.sp else Type.TITLE.sp)
        v.setTextColor(Palette.text)
        v.weight = Fonts.MEDIUM
        v.gravity = Gravity.CENTER
        // (a fixed direction rule, the same detached as on the screen: a measured text must match its line exactly)
        v.textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        v.setLineSpacing(0f, 1.1f)
        // (1.15: whole words per row: a word hyphenated across two rows cannot be lit as one)
        v.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        val pad = (if (big) Space.M else Space.S).dp
        v.setPadding(pad, pad, pad, pad)
    }

    private var templates = arrayOfNulls<Line>(2)

    /** A line as the lyrics' lines are styled, for the measuring done off the main thread. */
    private fun template(timed: Boolean): Line =
        templates[if (timed) 1 else 0] ?: Line(context, this).also { style(it, timed); templates[if (timed) 1 else 0] = it }

    private fun line(l: Lyrics.Line, text: CharSequence, wordTimes: LongArray?): Line = Line(context, this).apply {
        style(this, synced)
        // (measured with settings this line does not have after all: as plain text, measured here)
        try { setText(text) } catch (_: IllegalArgumentException) { setText(text.toString()) }
        words(wordTimes, l.ends)
        if (synced) takeTaps(this)
    }

    private fun takeTaps(v: Line) {
        v.isClickable = true
        v.setOnClickListener(tapLine)
        v.setOnLongClickListener(holdLine)
    }

    /** A tapped line: its time as the lyrics have it now (a timing by listening can be refined while it shows). */
    private val tapLine = View.OnClickListener { v -> lines.getOrNull(views.indexOf(v))?.let { onLineTap?.invoke(it) } }
    private val holdLine = View.OnLongClickListener { onLineHold?.invoke() ?: false }

    /** Pixels the lines are moved down by at the next layout (new room above them that must not move what shows). */
    private var shiftOnLayout = 0

    /**
     * Times for the lines shown (1.13: the owner's server timed them, by listening or from another source). When they
     * are the very lines on the screen, untimed until now, nothing is made again and nothing jumps: the lines stay
     * where they are (the room above them grows as timed lyrics have it, and the scroll moves with it in the same
     * frame), take taps from now on, and the line being sung lights and glides to the middle as with any timed lyrics.
     * Lines already timed take the new times as they are. Anything else is shown anew ([show]).
     */
    fun timeLines(r: Lyrics.Result) {
        if (!r.synced) return
        val same = pending == null && views.size == r.lines.size && lines.size == r.lines.size &&
            r.lines.indices.all { lines[it].text == r.lines[it].text }
        if (!same) { show(r); return }
        lines = r.lines
        // the words' times of the new timing (a handful of lines' arithmetic: here, at once)
        for ((k, v) in views.withIndex()) v.words(Words.times(r.lines, k), r.lines[k].ends)
        // (a line with words may now start at its first word: the next tick lights the right one)
        views.getOrNull(current)?.let { it.karaoke = wordsOn && it.times != null }
        if (synced) return // (more of a timing: the next tick lights the right line)
        synced = true
        views.getOrNull(current)?.karaoke = false
        current = -1
        for (v in views) takeTaps(v)
        val before = list.paddingTop
        pad()
        centerOnLayout = false // (no jump to the lit line: it glides there when it is lit)
        shiftOnLayout += list.paddingTop - before
        showHint()
    }

    // ---- the hint and the "synced" note
    /** Whether the hint is (or is becoming) shown: its visibility stays VISIBLE while it fades out. */
    private var hintShown = false

    private fun showHint() {
        val want = needSync && synced && lines.isNotEmpty()
        if (want == hintShown) return
        hintShown = want
        if (want) hint.enter(dy = -10f) else hint.exit(dy = -6f) { if (!hintShown) hint.visibility = View.GONE }
    }

    private val doneOut = Runnable { done.exit(dy = 6f, dur = Motion.MEDIUM) }

    /** The owner set where the song is: a short "synced" note. */
    fun flashSynced() {
        removeCallbacks(doneOut)
        done.enter(dy = 8f) { postDelayed(doneOut, 1100L) }
    }

    // ---- following the song
    /**
     * The song is at [ms]: the line being sung, lit and brought to the middle (unless the owner is scrolling).
     * [playing]: whether the song plays (the words' clock runs on from [ms] meanwhile); unchanged when not said.
     */
    fun at(ms: Long, playing: Boolean = songPlaying) {
        // the words' clock: re-based on every report; a jump (a seek, a tap) or a pause redraws the line being sung
        val now = SystemClock.uptimeMillis()
        val carried = if (songPlaying) clockMs + (now - clockAt) else clockMs
        val moved = playing != songPlaying || kotlin.math.abs(carried - ms) > 250L
        clockMs = ms
        clockAt = now
        songPlaying = playing
        if (moved) {
            drawnMs = Long.MIN_VALUE
            views.getOrNull(current)?.let { if (it.karaoke) it.invalidate() }
        }
        if (!synced || lines.isEmpty() || needSync) return
        // the last line that has started, a moment early (before the first one: none lit). A line lit as its first
        // word is heard already reads as late: the eye needs the light a little before the ear hears the word.
        val t = ms + LEAD_MS
        var lo = 0
        var hi = lines.size - 1
        var i = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].ms <= t) { i = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (i == current) return
        // out of sight (the other size is on the screen): the same end state, nothing animated
        val live = isShown
        if (current == -1) {
            // the first line lit: the others step back too; only those in sight fade, the rest just take their state
            val top = scroll.scrollY
            val bottom = top + scroll.height
            for ((k, v) in views.withIndex()) if (k != i) {
                val seen = live && v.bottom > top && v.top < bottom
                fade(v, dim, small, if (seen) Motion.SHORT else 0L, Motion.STANDARD)
            }
        } else views.getOrNull(current)?.let { it.karaoke = false; fade(it, dim, small, if (live) Motion.SHORT else 0L, Motion.EXIT) }
        current = i
        val v = views.getOrNull(i) ?: return
        v.karaoke = wordsOn && v.times != null
        fade(v, 1f, 1f, if (live) Motion.MEDIUM else 0L, Motion.EMPHASIZED)
        if (!live) scrollNow(v)
        else if (SystemClock.elapsedRealtime() - touchedAt > FOLLOW_AFTER_TOUCH_MS) glideTo(v)
    }

    /**
     * How long until the next line is to be lit (as [at] lights it), from [ms]: the music screen's tick comes then,
     * instead of up to a tick late. [Long.MAX_VALUE] when no line is coming.
     */
    fun msToNext(ms: Long): Long {
        if (!synced || needSync || lines.isEmpty()) return Long.MAX_VALUE
        val next = lines.getOrNull(current + 1) ?: return Long.MAX_VALUE
        return next.ms - LEAD_MS - ms
    }

    /** Brought into sight: the lines are made if they were not yet, and the line being sung stands in the middle at once. */
    fun settle() {
        finishNow()
        follow.cancel()
        views.getOrNull(current)?.let { scrollNow(it) }
    }

    /**
     * A line to [alpha] and [scale]. While it moves it is drawn from a picture of itself (a layer): text redrawn at a
     * new size on every frame costs this GPU a new set of letters each time.
     */
    private fun fade(v: View, alpha: Float, scale: Float, ms: Long, interp: TimeInterpolator) {
        v.animate().cancel()
        if (ms <= 0L) { v.alpha = alpha; v.scaleX = scale; v.scaleY = scale; return }
        v.animate().alpha(alpha).scaleX(scale).scaleY(scale).setStartDelay(0).setDuration(ms).setInterpolator(interp).withLayer().start()
    }

    private fun target(v: View): Int =
        (v.top + v.height / 2 - (scroll.height * 0.42f).toInt()).coerceIn(0, (list.height - scroll.height).coerceAtLeast(0))

    private fun scrollNow(v: View) {
        follow.cancel()
        if (v.height == 0 || scroll.height == 0) { centerOnLayout = true; return } // (laid out first)
        scroll.scrollTo(0, target(v))
    }

    private fun glideTo(v: View) {
        // (lines not laid out yet, just added: they are put in place by the layout instead of from the top)
        if (v.height == 0 || !v.isLaidOut || scroll.height == 0) { centerOnLayout = true; return }
        if (!follow.isRunning) follow.snapTo(scroll.scrollY.toFloat())
        follow.animateTo(target(v).toFloat())
    }

    private val followAgain = Runnable { if (isShown) views.getOrNull(current)?.let { glideTo(it) } }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // the lines just timed in place: what showed stays where it was (see timeLines)
        if (shiftOnLayout != 0) {
            follow.cancel()
            scroll.scrollTo(0, scroll.scrollY + shiftOnLayout)
            shiftOnLayout = 0
        }
        // New lines, or new room around them: the lit line is put in the middle before the first frame shows them.
        // (Lyrics that arrived while the song played left it below the screen, then whipped there at the next line.)
        if (centerOnLayout && views.isNotEmpty() && SystemClock.elapsedRealtime() - touchedAt > FOLLOW_AFTER_TOUCH_MS) {
            centerOnLayout = false
            follow.cancel()
            val v = views.getOrNull(current)
            scroll.scrollTo(0, if (v != null) target(v) else 0)
        }
    }

    override fun onDetachedFromWindow() {
        follow.cancel()
        removeCallbacks(followAgain); removeCallbacks(buildStep); removeCallbacks(idleBuild); removeCallbacks(doneOut)
        super.onDetachedFromWindow()
    }

    /**
     * One line. Its dimming is applied to what it draws, not through an off-screen copy of the whole line on every
     * frame (what a view with a background gets by default): a screen of dimmed lines scrolling was a dozen of those
     * a frame. Its pressed tint is drawn by the list ([LineList]).
     */
    private class Line(c: Context, private val owner: LyricsView) : AText(c) {
        override fun hasOverlappingRendering() = false
        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            (parent as? LineList)?.pressed(this, pressed)
        }

        // ---- word by word (1.15; 1.16: real times only, pauses, the word being sung on a pill)
        /** When each word starts, and where the last ends (song ms); null: a line without words or untimed lyrics. */
        var times: LongArray? = null
            private set
        /** Where a word's singing ends before a pause (song ms, -1: it runs on to the next word); null: none known. */
        private var pauses: LongArray? = null
        /** The line being sung, with the words effect on: drawn word by word. Otherwise as any line. */
        var karaoke = false
            set(v) { if (v != field) { field = v; invalidate() } }
        private var n = 0
        private var starts = NO_INTS
        private var ends = NO_INTS
        /** Each word's box in this view (left, top, right, bottom), its row, and whether it reads right to left. */
        private var box = NO_FLOATS
        private var row = NO_INTS
        private var rtl = NO_BOOLS
        /** The lit parts of a frame (left, top, right, bottom each), made in place. */
        private var lit = NO_FLOATS
        private var boxesOf: android.text.Layout? = null
        private var boxesH = -1
        /** Half the gap kept around a word's box (so a word's ink at its edge takes its own colour). */
        private var padX = 0f

        /**
         * The words' times for the text this line shows (null, or a count that does not match: no words), and where
         * a word stops before a pause ([p], one per word, -1 none).
         */
        fun words(t: LongArray?, p: LongArray? = null) {
            val text = text
            val cnt = if (t == null) 0 else Words.count(text)
            if (t == null || cnt == 0 || t.size != cnt + 1) { times = null; pauses = null; n = 0; invalidate(); return }
            if (starts.size < cnt) {
                starts = IntArray(cnt); ends = IntArray(cnt); row = IntArray(cnt); rtl = BooleanArray(cnt)
                box = FloatArray(4 * cnt); lit = FloatArray(4 * cnt)
            }
            Words.bounds(text, starts, ends)
            n = cnt
            times = t
            pauses = p?.takeIf { it.size == cnt }
            boxesOf = null
            if (karaoke) invalidate()
        }

        /** Where word [j]'s singing ends: its own end before a pause, else the next word's start (the last: the line's end). */
        private fun endOf(t: LongArray, j: Int): Long {
            val p = pauses?.getOrNull(j) ?: -1L
            val next = if (j + 1 < n) t[j + 1] else t[n]
            return minOf(if (p > t[j] && p < next) p else next, t[j] + MAX_WORD_MS)
        }

        /** The words' boxes could not be matched to what shows: the line is lit whole. */
        private var boxesBad = false

        /**
         * The words' boxes from the laid-out text: once per layout (a new width or text makes a new one). Measured on
         * a copy of the layout made from the plain text with the same settings: the shown one is made from the text
         * measured off the main thread (PrecomputedText), and on Android 10 its horizontal positions all came back as
         * the row's end (every word's box was the whole row). The copy must break the rows exactly where the shown
         * one does, or the words are not lit one by one.
         */
        private fun measureWords(l: android.text.Layout) {
            boxesOf = l
            boxesH = height
            boxesBad = false
            val s = text.toString()
            val m = try {
                android.text.StaticLayout.Builder.obtain(s, 0, s.length, paint, l.width)
                    .setAlignment(l.alignment)
                    .setTextDirection(if (Build.VERSION.SDK_INT >= 29) textDirectionHeuristic else android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
                    .setIncludePad(includeFontPadding)
                    .setBreakStrategy(breakStrategy)
                    .setHyphenationFrequency(hyphenationFrequency)
                    .setJustificationMode(justificationMode)
                    .build()
            } catch (_: Throwable) { null }
            if (m == null || m.lineCount != l.lineCount) { boxesBad = true; return }
            for (r in 0 until m.lineCount) if (m.getLineEnd(r) != l.getLineEnd(r)) { boxesBad = true; return }
            val px = totalPaddingLeft.toFloat()
            val py = totalPaddingTop.toFloat()
            val last = l.lineCount - 1
            padX = textSize * 0.08f
            for (j in 0 until n) {
                // (the selection's own shape: right for either direction and for a Latin word in an Arabic line)
                m.getSelectionPath(starts[j], ends[j], TMP_PATH)
                TMP_PATH.computeBounds(TMP_RECT, true)
                val r = m.getLineForOffset(starts[j])
                row[j] = r
                rtl[j] = m.isRtlCharAt(starts[j])
                var left = TMP_RECT.left
                var right = TMP_RECT.right
                if (right <= left) {
                    val a = m.getPrimaryHorizontal(starts[j]); val b = m.getPrimaryHorizontal(ends[j])
                    left = minOf(a, b); right = maxOf(a, b)
                }
                box[4 * j] = px + left - padX
                // (the first and last rows reach the view's edges: Arabic marks stand above the line's own top)
                box[4 * j + 1] = if (r == 0) 0f else py + l.getLineTop(r)
                box[4 * j + 2] = px + right + padX
                box[4 * j + 3] = if (r >= last) height.toFloat() else py + l.getLineBottom(r)
            }
        }

        private fun drawText(c: Canvas, l: android.text.Layout) {
            val save = c.save()
            c.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            l.draw(c)
            c.restoreToCount(save)
        }

        /**
         * The line being sung (1.16). The words sung are at full strength, those still to come clearly dimmer, and the
         * word being sung stands on a soft pill of the cover's colour while it fills with that colour in its own
         * reading direction (a soft edge, drawn in a few steps). The pill glides from one word to the next when they
         * follow each other, comes in with a short fade after a pause, and goes when a pause begins: the light waits
         * with the singer. The text is drawn a few times, each time clipped to a part of it; nothing is laid out
         * again, nothing allocated, and nothing is drawn at another size (a scaled word costs this GPU a new set of
         * letters every frame). The next frame is asked for only while something moves.
         */
        override fun onDraw(c: Canvas) {
            val t = times
            val l = layout
            if (!karaoke || t == null || l == null || n == 0) { super.onDraw(c); return }
            if (l !== boxesOf || height != boxesH) measureWords(l)
            if (boxesBad) { super.onDraw(c); return }
            val now = owner.songNow() + WORD_LEAD_MS
            // the word being sung: the last one started, while its singing lasts (to the next word, or to its own
            // end when a pause follows it)
            var cur = -1
            while (cur + 1 < n && t[cur + 1] <= now) cur++
            val curEnd = if (cur >= 0) endOf(t, cur) else 0L
            val singing = cur >= 0 && now < curEnd
            val sung = if (cur < 0) 0 else if (singing) cur else cur + 1     // words fully sung
            // after its end a word lets go of the pill and of its colour over a moment
            val since = if (cur >= 0 && !singing) now - curEnd else Long.MAX_VALUE
            val leaving = since < LET_GO_MS
            if (sung >= n && !leaving) { super.onDraw(c); return } // (all of it sung: the whole line lit)

            val paint = paint
            val color = currentTextColor
            val upcoming = Palette.withAlpha(color, UPCOMING)
            val accent = owner.wordText

            // 1. the pill behind the word being sung (or the one letting go of it)
            if (singing || leaving) {
                val age = if (singing) now - t[cur] else 0L
                var a = 1f
                var j0 = cur
                var g = 1f // how far the pill has glided from the word before (1: there)
                if (singing) {
                    // (from the word before when it ran right up to this one on the same row: a glide, else a fade in)
                    val prev = cur - 1
                    if (prev >= 0 && row[prev] == row[cur] && t[cur] - endOf(t, prev) < GLIDE_GAP_MS && age < GLIDE_MS) {
                        j0 = prev
                        g = Motion.STANDARD.getInterpolation(age.toFloat() / GLIDE_MS)
                    } else if (age < PILL_IN_MS) a = Motion.STANDARD.getInterpolation(age.toFloat() / PILL_IN_MS)
                } else a = 1f - (since.toFloat() / LET_GO_MS)
                pillRect(j0, cur, g)
                val fill = owner.wordFill
                paint.color = Palette.withAlpha(fill, (if (Palette.dark) PILL_DARK else PILL_LIGHT) * a)
                val r = PILL_R.height() / 2f
                c.drawRoundRect(PILL_R, r, r, paint)
            }

            // the lit parts: the words sung, row by row (one span per row when no word still to come lies in it)
            var nLit = 0
            var j = 0
            while (j < sung) {
                var k = j
                var minL = box[4 * j]
                var maxR = box[4 * j + 2]
                while (k + 1 < sung && row[k + 1] == row[j]) { k++; minL = minOf(minL, box[4 * k]); maxR = maxOf(maxR, box[4 * k + 2]) }
                var clean = true
                var q = sung
                while (q < n && row[q] == row[j]) {
                    if (box[4 * q] + padX < maxR - padX && box[4 * q + 2] - padX > minL + padX) { clean = false; break }
                    q++
                }
                if (clean) {
                    lit[4 * nLit] = minL; lit[4 * nLit + 1] = box[4 * j + 1]; lit[4 * nLit + 2] = maxR; lit[4 * nLit + 3] = box[4 * j + 3]
                    nLit++
                } else for (m in j..k) {
                    System.arraycopy(box, 4 * m, lit, 4 * nLit, 4)
                    nLit++
                }
                j = k + 1
            }

            // 2. everything not sung (the word being sung is drawn by itself, 4.)
            var save = c.save()
            for (m in 0 until nLit) c.clipOutRect(lit[4 * m], lit[4 * m + 1], lit[4 * m + 2], lit[4 * m + 3])
            if (singing) c.clipOutRect(box[4 * cur], box[4 * cur + 1], box[4 * cur + 2], box[4 * cur + 3])
            paint.color = upcoming
            drawText(c, l)
            c.restoreToCount(save)
            // 3. the words sung (the one just let go of passing from the cover's colour to the text's)
            paint.color = color
            for (m in 0 until nLit) {
                save = c.save()
                c.clipRect(lit[4 * m], lit[4 * m + 1], lit[4 * m + 2], lit[4 * m + 3])
                drawText(c, l)
                c.restoreToCount(save)
            }
            if (leaving && cur in 0 until n) {
                paint.color = Palette.mix(accent, color, since.toFloat() / LET_GO_MS)
                save = c.save()
                c.clipRect(box[4 * cur], box[4 * cur + 1], box[4 * cur + 2], box[4 * cur + 3])
                drawText(c, l)
                c.restoreToCount(save)
            }
            // 4. the word being sung: its lit part in the cover's colour, then its edge in steps down to the rest
            if (singing) {
                val bl = box[4 * cur]
                val bt = box[4 * cur + 1]
                val br = box[4 * cur + 2]
                val bb = box[4 * cur + 3]
                val p = ((now - t[cur]).toFloat() / (curEnd - t[cur]).coerceAtLeast(1L)).coerceIn(0f, 1f)
                val e = textSize * EDGE_EM
                val w = br - bl
                val right = rtl[cur]
                // (the edge's middle travels from just before the word to just after it)
                val mid = if (right) br + e / 2f - p * (w + e) else bl - e / 2f + p * (w + e)
                val litEnd = if (right) mid + e / 2f else mid - e / 2f   // lit before this (in reading order)
                val restFrom = if (right) mid - e / 2f else mid + e / 2f // still to come after this
                val litL = if (right) maxOf(bl, litEnd) else bl
                val litR = if (right) br else minOf(br, litEnd)
                if (litR > litL) {
                    paint.color = accent
                    save = c.save()
                    c.clipRect(litL, bt, litR, bb)
                    drawText(c, l)
                    c.restoreToCount(save)
                }
                val step = e / EDGE_STEPS
                for (s in 0 until EDGE_STEPS) {
                    val a = if (right) litEnd - (s + 1) * step else litEnd + s * step
                    val sl = maxOf(bl, a)
                    val sr = minOf(br, a + step)
                    if (sr <= sl) continue
                    paint.color = Palette.mix(accent, upcoming, (s + 0.5f) / EDGE_STEPS)
                    save = c.save()
                    c.clipRect(sl, bt, sr, bb)
                    drawText(c, l)
                    c.restoreToCount(save)
                }
                val rl = if (right) bl else maxOf(bl, restFrom)
                val rr = if (right) minOf(br, restFrom) else br
                if (rr > rl) {
                    paint.color = upcoming
                    save = c.save()
                    c.clipRect(rl, bt, rr, bb)
                    drawText(c, l)
                    c.restoreToCount(save)
                }
            }
            paint.color = color
            // the next frame: every frame while a word fills or the pill moves; else when the next word starts
            if (owner.songPlaying) {
                if (singing || leaving) postInvalidateOnAnimation()
                else {
                    val next = t.getOrNull(sung) ?: return
                    if (sung < n) postInvalidateDelayed((next - now).coerceIn(16L, 2000L))
                }
            }
        }

        /** The pill at word [to], or on its way there from word [from] ([g]: 0 there, 1 arrived), into [PILL_R]. */
        private fun pillRect(from: Int, to: Int, g: Float) {
            val ex = textSize * PILL_PAD_EM
            fun lerp(a: Float, b: Float) = a + (b - a) * g
            val l = lerp(box[4 * from], box[4 * to]) + padX - ex
            val r = lerp(box[4 * from + 2], box[4 * to + 2]) - padX + ex
            // (around the letters' own middle on the row: a row's box holds the spacing below it, and a word's box
            // reaches the view's edges on the first and last rows)
            paint.getFontMetrics(TMP_FM)
            val mid = layout.getLineBaseline(row[to]) + totalPaddingTop + (TMP_FM.ascent + TMP_FM.descent) / 2f
            val h = textSize * PILL_H_EM
            PILL_R.set(l, mid - h / 2f, r, mid + h / 2f)
        }
    }

    /**
     * The lines, and behind the one under the finger a tint, drawn here: one shape for all the lines instead of a
     * pressable background (three drawables) made for each. It comes at once and lets go over [Motion.SHORT], as
     * [Shapes.pressable] does.
     */
    private class LineList(c: Context) : LinearLayout(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private var lit: View? = null
        private var litAlpha = 0f
        private var letGo: ValueAnimator? = null

        init { orientation = VERTICAL }

        fun pressed(v: View, on: Boolean) {
            if (on) {
                letGo?.cancel()
                lit = v
                litAlpha = 1f
                invalidate()
            } else if (lit === v && litAlpha > 0f) {
                letGo?.cancel()
                letGo = ValueAnimator.ofFloat(litAlpha, 0f).apply {
                    duration = Motion.SHORT
                    interpolator = Motion.STANDARD
                    addUpdateListener { litAlpha = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }

        override fun dispatchDraw(c: Canvas) {
            val v = lit
            if (v != null && litAlpha > 0f && v.parent === this) {
                // (as the line is seen: it may be at its smaller, dimmed scale)
                val cx = (v.left + v.right) / 2f
                val cy = (v.top + v.bottom) / 2f
                val hw = v.width * v.scaleX / 2f
                val hh = v.height * v.scaleY / 2f
                r.set(cx - hw, cy - hh, cx + hw, cy + hh)
                paint.color = Palette.press
                paint.alpha = (android.graphics.Color.alpha(Palette.press) * litAlpha).toInt()
                val rad = Radius.M.dp
                c.drawRoundRect(r, rad, rad, paint)
            }
            super.dispatchDraw(c)
        }

        override fun onDetachedFromWindow() {
            letGo?.cancel()
            super.onDetachedFromWindow()
        }
    }

    private companion object {
        const val IDLE = 0.85f      // all lines while none is lit (not started, position unknown): text to read
        /** Lit a little before its time (see [at]). */
        const val LEAD_MS = 150L
        const val FOLLOW_AFTER_TOUCH_MS = 4000L
        const val LINES_PER_FRAME = 12
        const val IDLE_BUILD_MS = 1500L
        /** The lyrics alone on the screen: larger than any text role, they are the screen's whole content. */
        const val BIG_TIMED_SP = 34f
        const val BIG_PLAIN_SP = 28f
        /** Measures the lines' text off the main thread (one for both copies: they ask one after the other). */
        val worker = Executors.newSingleThreadExecutor()

        // ---- word by word (1.15, 1.16)
        /** A word fills a little before it is heard, as a line is lit early ([LEAD_MS]), but less. */
        const val WORD_LEAD_MS = 60L
        /** A word's fill takes at most this long (a long pause before the next word is not sung). */
        const val MAX_WORD_MS = 3000L
        /**
         * The words still to come on the line being sung: clearly under the ones sung (1.15's 0.6 read as nearly
         * the same in the car), still over the other lines' [dim].
         */
        const val UPCOMING = 0.5f
        /** The fill's soft edge, in ems of the line's text, drawn in [EDGE_STEPS] steps. */
        const val EDGE_EM = 0.5f
        const val EDGE_STEPS = 3
        /** The pill behind the word being sung: the cover's colour this strong (dark and light screens). */
        const val PILL_DARK = 0.34f
        const val PILL_LIGHT = 0.26f
        /** Its size: this much wider than the word on each side, this tall (ems of the line's text). */
        const val PILL_PAD_EM = 0.16f
        const val PILL_H_EM = 1.32f
        /** It glides to the next word over this long when the two follow each other on a row (gap under [GLIDE_GAP_MS]). */
        const val GLIDE_MS = 150L
        const val GLIDE_GAP_MS = 120L
        /** ...else it fades in over this long, and when a pause begins it (and the word's colour) lets go over [LET_GO_MS]. */
        const val PILL_IN_MS = 120L
        const val LET_GO_MS = 180L
        val PILL_R = RectF()
        val TMP_FM = Paint.FontMetrics()
        // (the main thread's, reused: the words' boxes are measured there, once per layout)
        val TMP_PATH = android.graphics.Path()
        val TMP_RECT = RectF()
        val NO_INTS = IntArray(0)
        val NO_FLOATS = FloatArray(0)
        val NO_BOOLS = BooleanArray(0)
    }
}
