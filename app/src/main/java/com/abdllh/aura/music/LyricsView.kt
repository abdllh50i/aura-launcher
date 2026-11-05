package com.abdllh.aura.music

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.text.PrecomputedText
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The song's lyrics on the Music panel (SPEC.md section 5, "Lyrics"; parked only). Three lines in the column at the reading edge: the line sung `lyricPast`, the line being sung
 * `lyricLit` in the larger role, keeping its slot in the middle, and the line coming `lyricDim`, while the list goes by on spring 110/1. A tapped line is where
 * the song goes ([onLineTap]), a line held is [onLineHold]. [needSync]: the song's position is unknown, nothing is lit and five lines are text to tap. Untimed: the same five. [big]: the full lyrics,
 * five lines, the third lit, with their own rects; the panel keeps one of each, fed alike, and the one out of sight follows the song without moving anything, so it is in place the moment it is shown.
 *
 * Every line is laid out once, at the larger role, and shown at the smaller by a transform: nothing is measured when a line lights, its rows do not break anew under
 * the eye, and a line that wraps in the column wraps the same when it is sung. Only alpha and transforms move, on a layer for those whose scale changes. Lines outside the
 * window have alpha 0, so they come and go by their own colour and nothing is cut at an edge (the fading edge of 1.x went); a hand on the list brings them all back until
 * the list follows again, 4 s later.
 *
 * New lyrics become lines without stopping the screen: their text is measured on a worker ([PrecomputedText]), the lines are made a dozen a frame, and the copy out of
 * sight makes its own only when the screen is idle or the moment it is shown.
 *
 * The word ([wordsOn], only with real word times, [Words]): the word being sung stands on a pill, Canvas round rect of [Tokens2.R_wordFull], in the song's colour, with the
 * words before it lit and those after it `lyricDim`. No fill runs through the word: a frame only when the word changes, and while the pill's rect goes to the next one
 * on spring 700/0.85, about 120 ms. Only the line's own view redraws ([Line.onDraw]).
 */
internal class LyricsView(c: Context, private val big: Boolean = false) : FrameLayout(c), Recolours {
    private val lineRole = if (big) Type.lyricFull else Type.lyric
    private val nowRole = if (big) Type.lyricFullNow else Type.lyricNow
    /** No line can be lit, beside the song: five lines to read and tap, closer together. */
    private val asList: Boolean get() = !big && (!synced || needSync)
    private val gap: Int get() = if (big) GAP_BIG else if (asList) GAP_LIST else GAP
    /** Where the first line's box lies in this view, and where the line being sung stands below it. */
    private val firstTop: Int get() = if (big) FIRST_TOP_BIG else if (asList) FIRST_TOP_LIST else FIRST_TOP
    private val slot = if (big) SLOT_BIG else 0
    private val lastBottom = if (big) LAST_BOTTOM_BIG else LAST_BOTTOM_LIST
    private val rtlUi = Fonts.arabicUi
    /** A line at the smaller role is the larger one at this scale. */
    private val small = lineRole.px.toFloat() / nowRole.px
    private val rowPitch = nowRole.line + ROW_EXTRA
    private val ascent = nowRole.px * 1.085f
    private val smallDy = lineRole.baseline - ascent * small

    private val scroll = ScrollView(c).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = false
    }
    private val list = LineList(c)
    private val statusBox: LinearLayout
    private val status = c.label(if (big) Type.stateTitle else Type.stateBody, { Palette.ink2 }, lines = 2)
    /** While the song is listened to for its timing: a quiet note at the column's foot. */
    private val note: LinearLayout
    private val noteIcon: ImageView
    private val noteText: AText
    private var lines: List<Lyrics.Line> = emptyList()
    private var views: List<Line> = emptyList()
    private var synced = false
    private var current = -1
    private var touchedAt = 0L
    /** The five lines only: off while a hand is on the list, and until it follows again. */
    private var windowOn = true
    var onLineTap: ((Lyrics.Line) -> Unit)? = null
    /**
     * A timed line held down (1.15: the music screen offers to forget a saved timing; 1.18: on Naghm's song it measures
     * the Bluetooth sound's delay with it); true when it did something.
     */
    var onLineHold: ((Lyrics.Line) -> Boolean)? = null

    // ---- the song's clock for the words (1.15)
    /** Word by word: the owner's setting (Settings → Music). Off: whole lines. */
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
        // (a tick's report a few ms behind the clock carried on would move the word back and forth)
        if (p < drawnMs && p > drawnMs - 250L) p = drawnMs
        drawnMs = p
        return p
    }

    /** The list goes by on this: retargeted half way it keeps its speed, and it leaves at once rather than trailing the line. */
    private val follow = Spring(0f, 110f, 1f, 0.5f) { scroll.scrollTo(0, it.roundToInt()) }

    /** Unknown position: no line lit, all of them there to be read and tapped (Bluetooth: until a line is tapped or the next song starts). */
    var needSync = false
        set(v) {
            if (v == field) return
            field = v
            // (the window again, from the top or round the line just tapped: a hand may have let them all out)
            windowOn = true
            pad() // (three lines or five: another gap)
            if (v) {
                views.getOrNull(current)?.karaoke = false
                current = -1
                paint(animate = true)
                glideTop()
            }
        }

    init {
        scroll.addView(list, LayoutParams(MATCH, WRAP))
        addView(scroll, flp(MATCH, MATCH))
        scroll.setOnTouchListener { _, e ->
            touchedAt = SystemClock.elapsedRealtime()
            follow.cancel()
            removeCallbacks(followAgain)
            if (windowOn && e.actionMasked == MotionEvent.ACTION_MOVE) { windowOn = false; paint(animate = true) }
            // (after a hand scroll the lit line comes back by itself, even through a long stretch without a new line)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) postDelayed(followAgain, FOLLOW_AFTER_TOUCH_MS)
            false
        }

        statusBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START; visibility = View.GONE }
        status.alignStart = true
        statusBox.addView(status, lp(MATCH, WRAP))
        addView(statusBox, flp(MATCH, WRAP, Gravity.TOP).apply { setMargins(SIDE, (if (big) FIRST_TOP_BIG else STATUS_TOP) + STATUS_DY, SIDE, 0) })

        noteIcon = c.iconView(R.drawable.ic_mic, 24) { Palette.ink3 }
        noteText = c.label(Type.rowHint, { Palette.ink3 })
        note = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(noteIcon, lp(24, 24))
            addView(noteText, lp(WRAP, Type.rowHint.viewH).apply { marginStart = 8 })
        }
        addView(note, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(SIDE, 0, SIDE, NOTE_BOTTOM) })
    }

    /** The song's colours for the word on its pill. */
    fun wordColours(pill: Int, ink: Int) {
        wordFill = pill
        wordInk = ink
        views.getOrNull(current)?.let { if (it.karaoke) it.invalidate() }
    }

    private var wordFill = Palette.wordPill
    private var wordInk = Palette.wordPillInk

    /** Being sung, its word moving on the pill: the seek's tick waits (SPEC.md section 4: a tick on the left and a pill on the right would redraw the whole panel). */
    val pillMoving: Boolean get() = views.getOrNull(current)?.moving == true

    private var noteShown = false

    /**
     * The timing by listening at work ([text]: what it does, with how much is timed; [icon]: the microphone while it
     * listens), or nothing (null). It comes and goes; a new text replaces the old in place.
     */
    fun note(text: CharSequence?, icon: Int = R.drawable.ic_mic) {
        val want = text != null
        if (want) { noteText.text = text; noteIcon.setImageResource(icon) }
        if (want == noteShown) return
        noteShown = want
        if (want) note.enter(dy = 6f) else note.exit(dy = 4f) { if (!noteShown) note.visibility = View.GONE }
    }

    override fun onLook() {
        for (v in views) v.setTextColor(Palette.lyricLit)
        for (v in views) v.colourNote()
        paint(animate = false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pad()
    }

    /** Room above the first line and below the last, so that the last can stand in the slot. */
    private fun pad() {
        val h = height
        if (h == 0) return
        val bottom = (h - firstTop - slot).coerceAtLeast(0)
        if (list.paddingTop == firstTop && list.paddingBottom == bottom) return
        list.setPadding(0, firstTop, 0, bottom)
        centerOnLayout = true
    }

    /** A message instead of lyrics (still looking), at the top line's place; nothing at all with an empty one. */
    fun showStatus(text: String) {
        drop()
        lines = emptyList(); views = emptyList(); current = -1
        list.removeAllViews()
        status.text = text
        // (a step back may be on its way, [dim]: it is turned round from where it is)
        statusBox.animate().cancel()
        if (statusBox.visibility != View.VISIBLE) statusBox.alpha = 0f
        statusBox.visibility = View.VISIBLE
        if (isShown) statusBox.fadeTo(1f, Motion.SHORT) else statusBox.alpha = 1f
        scroll.visibility = View.GONE
    }

    /**
     * Another song is being looked up: what shows steps back, still there, until the answer replaces it, instead of a
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
    /** The lit line goes to its slot at the next layout (the lines were just added, or the room around them changed). */
    private var centerOnLayout = false

    private fun drop() {
        gen++
        pending = null; texts = null; times = null; made = ArrayList()
        onLaid = null
        removeCallbacks(buildStep); removeCallbacks(idleBuild)
        follow.cancel()
    }

    /** Told once the lyrics given last stand as lines in their places (made, measured, laid out). */
    private var onLaid: (() -> Unit)? = null

    /**
     * [f] once there is nothing left to make or lay out here (at once if that is so now): the panel's move to the lyrics then has no layout in it. This view must be in sight for the lines to be made at once,
     * at any alpha.
     */
    fun whenLaid(f: () -> Unit) {
        if (pending == null && (views.isEmpty() || (!centerOnLayout && list.laidFor(views.size)))) { onLaid = null; f() } else onLaid = f
    }

    fun show(r: Lyrics.Result) {
        drop()
        val g = gen
        lines = r.lines
        synced = r.synced
        current = -1
        windowOn = true
        views = emptyList() // (what shows stays until the new lines are made: see attach)
        pending = r
        pad()
        val plain = r.lines.map { it.text.ifBlank { BLANK } }
        val params = if (Build.VERSION.SDK_INT >= 28) template().textMetricsParams else null
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
        val t = texts ?: r.lines.map { it.text.ifBlank { BLANK } }
        val wt = times ?: if (r.synced) r.lines.indices.map { Words.times(r.lines, it) } else null
        for (k in made.size until r.lines.size) made.add(line(r.lines[k], t[k], wt?.getOrNull(k)))
        attach()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // (made a dozen a frame from the next frame on, not inside the change that showed it)
        if (isVisible && pending != null && texts != null) { removeCallbacks(idleBuild); removeCallbacks(buildStep); post(buildStep) }
    }

    /** The new lines take the old ones' place, each as [at] has left it, the lit one in its slot. */
    private fun attach() {
        pending = null
        list.removeAllViews()
        views = made
        made = ArrayList()
        for (v in views) list.addView(v)
        statusBox.animate().cancel()
        statusBox.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        centerOnLayout = true
        // (the panel is about to bring them in itself: [whenLaid])
        if (isShown && onLaid == null) { scroll.animate().cancel(); scroll.alpha = 0f; scroll.fadeTo(1f, Motion.SHORT) }
        else { scroll.animate().cancel(); scroll.alpha = 1f }
    }

    private fun style(v: Line) {
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, nowRole.sp)
        v.typeface = Fonts.of(nowRole)
        v.setTextColor(Palette.lyricLit)
        v.alignStart = true
        // (a fixed direction rule, the same detached as on the screen: a measured text must match its line exactly)
        v.textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        v.setLineSpacing(rowPitch - nowRole.viewH.toFloat(), 1f)
        // (1.15: whole words per row: a word hyphenated across two rows cannot be lit as one)
        v.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        if (Build.VERSION.SDK_INT >= 28) v.isFallbackLineSpacing = false
        v.setPadding(SIDE, PILL_ROOM_TOP, SIDE, PILL_ROOM_BOTTOM)
    }

    private var templateLine: Line? = null

    /** A line as the lyrics' lines are styled, for the measuring done off the main thread. */
    private fun template(): Line = templateLine ?: Line(context, this).also { style(it); templateLine = it }

    private fun line(l: Lyrics.Line, text: CharSequence, wordTimes: LongArray?): Line = Line(context, this).apply {
        style(this)
        // (measured with settings this line does not have after all: as plain text, measured here)
        try { setText(text) } catch (_: IllegalArgumentException) { setText(text.toString()) }
        note = l.text.isBlank()
        words(wordTimes, l.ends)
        if (synced) takeTaps(this)
        // (as it is before [paint] knows better: no line shows at the wrong size for a frame)
        wantAlpha = -1f
    }

    private fun takeTaps(v: Line) {
        v.isClickable = true
        v.setOnClickListener(tapLine)
        v.setOnLongClickListener(holdLine)
    }

    /** A tapped line: its time as the lyrics have it now (a timing by listening can be refined while it shows). */
    private val tapLine = View.OnClickListener { v -> if (v.alpha > 0.05f) lines.getOrNull(views.indexOf(v))?.let { onLineTap?.invoke(it) } }
    private val holdLine = View.OnLongClickListener { v -> lines.getOrNull(views.indexOf(v))?.let { onLineHold?.invoke(it) } ?: false }

    /**
     * Times for the lines shown (1.13: the owner's server timed them, by listening or from another source). When they
     * are the very lines on the screen, untimed until now, nothing is made again and nothing jumps: the lines stay
     * where they are, take taps from now on, and the line being sung lights and goes to its slot as with any timed lyrics.
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
        pad() // (five lines become three: another gap)
        views.getOrNull(current)?.karaoke = false
        current = -1
        for (v in views) takeTaps(v)
    }

    // ---- following the song
    /**
     * The song is at [ms]: the line being sung, lit and brought to its slot (unless the owner is scrolling).
     * [playing]: whether the song plays (the words' clock runs on from [ms] meanwhile); unchanged when not said.
     */
    fun at(ms: Long, playing: Boolean = songPlaying) {
        // the words' clock: re-based on every report; a jump (a seek, a tap) or a pause moves the word at once
        val now = SystemClock.uptimeMillis()
        val carried = if (songPlaying) clockMs + (now - clockAt) else clockMs
        val moved = playing != songPlaying || kotlin.math.abs(carried - ms) > 250L
        clockMs = ms
        clockAt = now
        songPlaying = playing
        if (moved) {
            drawnMs = Long.MIN_VALUE
            views.getOrNull(current)?.wordStep()
        }
        if (!synced || lines.isEmpty() || needSync) return
        val i = lineAt(lines, ms)
        if (i == current) return
        views.getOrNull(current)?.karaoke = false
        current = i
        val v = views.getOrNull(i)
        v?.karaoke = wordsOn && v?.times != null
        val live = isShown
        paint(animate = live)
        if (!live) settleScroll()
        else if (SystemClock.elapsedRealtime() - touchedAt > FOLLOW_AFTER_TOUCH_MS) glide()
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

    /** Brought into sight: the lines are made if they were not yet, and the line being sung stands in its slot at once. */
    fun settle() {
        finishNow()
        follow.cancel()
        windowOn = true
        paint(animate = false)
        settleScroll()
    }

    /** The extra height of the line being sung over its smaller self: all that follow lie that much lower. */
    private fun extraOf(k: Int): Float {
        val r = list.rowsOf(k)
        return (litHeight(r) - plainHeight(r)).toFloat()
    }

    private fun plainHeight(rows: Int) = lineRole.line + ((rows - 1) * rowPitch * small).toInt()

    private fun litHeight(rows: Int) = if (rows <= 1) nowRole.line else rows * rowPitch

    /** Each line to where it is with [current] where it is now: [animate] for those that are in sight. Only what changed. */
    private fun paint(animate: Boolean) {
        val vs = views
        if (vs.isEmpty()) return
        val dimA = Color.alpha(Palette.lyricDim) / 255f
        val pastA = Color.alpha(Palette.lyricPast) / 255f
        // (with no line lit, untimed or the place unknown, it is the first five until a hand brings out the rest: no sixth line cut by the row below)
        val top = if (synced && !needSync && windowOn) targetOf(current) else 0
        val extra = if (current in vs.indices && list.laidFor(vs.size)) extraOf(current) else 0f
        val laid = list.laidFor(vs.size)
        for ((k, v) in vs.withIndex()) {
            val lit = k == current
            val s = if (lit) 1f else small
            val after = current in 0 until k
            val y = if (lit) 0f else smallDy + if (after) extra else 0f
            val a = when {
                lit -> 1f
                windowOn && laid && !inWindow(k, top, after, extra) -> 0f
                !synced || needSync || current < 0 -> dimA
                k < current -> pastA
                else -> dimA
            }
            if (v.wantAlpha == a && v.wantScale == s && v.wantY == y) continue
            val was = v.wantAlpha
            v.wantAlpha = a; v.wantScale = s; v.wantY = y
            if (!animate || was < 0f || (was == 0f && a == 0f)) {
                v.animate().cancel()
                v.alpha = a; v.scaleX = s; v.scaleY = s; v.translationY = y
            } else if (lit) move(v, a, s, y, Motion.MEDIUM, Motion.ENTER)
            else if (v.scaleX != s) move(v, a, s, y, Motion.SHORT, Motion.EXIT)
            else move(v, a, s, y, Motion.SHORT, Motion.MOVE)
        }
    }

    /** With the list at [top], whether line [k] is one the column shows: beside the song the line sung, the line being sung and the line coming; otherwise as many of five as fit. */
    private fun inWindow(k: Int, top: Int, after: Boolean, extra: Float): Boolean {
        if (!big && !asList) return if (current >= 0) kotlin.math.abs(k - current) <= 1 else k <= 2
        val t = list.baseOf(k) + (if (after) extra else 0f) - top
        val b = t + plainHeight(list.rowsOf(k))
        return t >= firstTop - 1 && b <= lastBottom
    }

    /**
     * A line to [alpha], [scale] and its place. While its size changes it is drawn from a picture of itself (a layer): text redrawn at a
     * new size on every frame costs this GPU a new set of letters each time.
     */
    private fun move(v: View, alpha: Float, scale: Float, y: Float, ms: Long, interp: TimeInterpolator) {
        v.animate().cancel()
        val a = v.animate().alpha(alpha).scaleX(scale).scaleY(scale).translationY(y).setStartDelay(0).setDuration(ms).setInterpolator(interp)
        if (v.scaleX != scale) a.withLayer()
        a.start()
    }

    private fun targetOf(k: Int): Int {
        if (k < 0 || !list.laidFor(views.size)) return 0
        // (a line of more rows stands with its middle where a line of one row would, and the three stay centred: `music-long-line`)
        val rise = if (big) 0 else (list.rowsOf(k) - 1) * rowPitch / 2
        return (list.baseOf(k) - firstTop - slot + rise).coerceIn(0, (list.height - scroll.height).coerceAtLeast(0))
    }

    private fun settleScroll() {
        follow.cancel()
        if (list.height == 0 || scroll.height == 0) { centerOnLayout = true; return } // (laid out first)
        scroll.scrollTo(0, targetOf(current))
    }

    private fun glide() {
        // (lines not laid out yet, just added: they are put in place by the layout instead of from the top)
        if (!list.laidFor(views.size) || scroll.height == 0) { centerOnLayout = true; return }
        if (!follow.isRunning) follow.snapTo(scroll.scrollY.toFloat())
        follow.animateTo(targetOf(current).toFloat())
    }

    private fun glideTop() {
        if (scroll.height == 0) return
        if (!follow.isRunning) follow.snapTo(scroll.scrollY.toFloat())
        follow.animateTo(0f)
    }

    private val followAgain = Runnable {
        // (nothing to follow: the lines stay where his hand left them, all of them, to be read and tapped)
        if (!isShown || !synced || needSync) return@Runnable
        windowOn = true
        paint(animate = true)
        glide()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // New lines, or new room around them: every line in its place and the lit one in its slot before the first frame shows them.
        // (Lyrics that arrived while the song played left it below the screen, then whipped there at the next line.)
        if (centerOnLayout && views.isNotEmpty() && list.laidFor(views.size)) {
            centerOnLayout = false
            paint(animate = false)
            if (SystemClock.elapsedRealtime() - touchedAt > FOLLOW_AFTER_TOUCH_MS) {
                follow.cancel()
                scroll.scrollTo(0, if (synced && !needSync) targetOf(current) else 0)
            }
        }
        val f = onLaid
        if (f != null && pending == null && !centerOnLayout && views.isNotEmpty() && list.laidFor(views.size)) { onLaid = null; post { f() } }
    }

    override fun onDetachedFromWindow() {
        follow.cancel()
        removeCallbacks(followAgain); removeCallbacks(buildStep); removeCallbacks(idleBuild)
        super.onDetachedFromWindow()
    }

    /**
     * One line. Its dimming is applied to what it draws, not through an off-screen copy of the whole line: a screen of dimmed lines going by was a dozen of those a frame.
     * Its pressed tint is drawn by the list ([LineList]). Room is kept round the text for the pill, top, bottom and both sides.
     */
    private class Line(c: Context, private val owner: LyricsView) : AText(c) {
        override fun hasOverlappingRendering() = false
        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            (parent as? LineList)?.pressed(this, pressed)
        }

        /** Where [paint] last sent it. */
        var wantAlpha = -1f
        var wantScale = -1f
        var wantY = Float.NaN

        /** A line with nothing to sing: the `music` glyph, since the font has no note mark (SPEC.md section 3). */
        var note = false
        private var noteTint: Tint? = null

        fun colourNote() { noteTint?.recolour() }

        // ---- the word (1.15; 1.16: real times only; 2.0: on its pill, no fill)
        /** When each word starts, and where the last ends (song ms); null: a line without words or untimed lyrics. */
        var times: LongArray? = null
            private set
        /** Where a word's singing ends before a pause (song ms, -1: it runs on to the next word); null: none known. */
        private var pauses: LongArray? = null
        /** The line being sung, with the words on and real times: drawn word by word. Otherwise as any line. */
        var karaoke = false
            set(v) {
                if (v == field) return
                field = v
                removeCallbacks(tick)
                shownWord = -1
                sungWords = -1
                pillWord = -1
                fromWord = -1
                pillA.snapTo(0f)
                pillG.snapTo(1f)
                if (v) wordStep() else invalidate()
            }
        private var n = 0
        private var starts = NO_INTS
        private var ends = NO_INTS
        /** Each word's box in this view (left, top, right, bottom), its row, and whether it reads right to left. */
        private var box = NO_FLOATS
        private var row = NO_INTS
        private var rtl = NO_BOOLS
        private var boxesOf: android.text.Layout? = null
        private var boxesH = -1

        /** The word being sung (-1: none, a pause), how many are sung, the word the pill is on or was last on, and the one it is gliding from (-1: none). */
        private var shownWord = -1
        private var sungWords = -1
        private var pillWord = -1
        private var fromWord = -1
        private val pillNow = RectF()
        private val pillA = Spring(0f, Tokens2.SPRING_wordPill[0], 1f, 0.01f) { invalidate() }
        private val pillG = Spring(1f, Tokens2.SPRING_wordPill[0], Tokens2.SPRING_wordPill[1], 0.003f) { invalidate() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        val moving: Boolean get() = pillG.isRunning || pillA.isRunning

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
                box = FloatArray(4 * cnt)
            }
            Words.bounds(text, starts, ends)
            n = cnt
            times = t
            pauses = p?.takeIf { it.size == cnt }
            boxesOf = null
            if (karaoke) wordStep()
        }

        /** Where word [j]'s singing ends: its own end before a pause, else the next word's start (the last: the line's end). */
        private fun endOf(t: LongArray, j: Int): Long {
            val p = pauses?.getOrNull(j) ?: -1L
            val next = if (j + 1 < n) t[j + 1] else t[n]
            return minOf(if (p > t[j] && p < next) p else next, t[j] + MAX_WORD_MS)
        }

        private val tick = Runnable { wordStep() }

        /** The word at the song's time, on the pill; the next look when the next word is due. Nothing per frame but the pill's own move. */
        fun wordStep() {
            removeCallbacks(tick)
            val t = times
            if (!karaoke || t == null || n == 0) return
            val now = owner.songNow() + WORD_LEAD_MS
            var cur = -1
            while (cur + 1 < n && t[cur + 1] <= now) cur++
            val curEnd = if (cur >= 0) endOf(t, cur) else 0L
            val singing = cur >= 0 && now < curEnd
            val sung = if (cur < 0) 0 else if (singing) cur else cur + 1
            val word = if (singing) cur else -1
            if (word != shownWord || sung != sungWords) {
                val l = layout
                if (l != null && (l !== boxesOf || height != boxesH)) measureWords(l)
                wordChanged(word)
                sungWords = sung
                invalidate()
            }
            if (owner.songPlaying) {
                val next = if (singing) minOf(curEnd, t.getOrElse(cur + 1) { curEnd }) else t.getOrNull(cur + 1) ?: return
                postDelayed(tick, (next - now).coerceIn(16L, 3000L))
            }
        }

        private fun wordChanged(word: Int) {
            shownWord = word
            if (word < 0) { pillA.animateTo(0f); return }
            if (boxesOf == null || boxesBad) return
            val from = pillWord
            pillWord = word
            if (from in 0 until word && row[from] == row[word] && pillA.value > 0.5f) {
                fromWord = from
                pillG.snapTo(0f)
                pillG.animateTo(1f)
            } else {
                // (after a pause, on a new row, a jump: it comes in where it is, no flying across the line)
                fromWord = -1
                pillG.snapTo(1f)
                if (from >= 0 && row[from] != row[word]) pillA.snapTo(0f)
                if (isShown) pillA.animateTo(1f) else pillA.snapTo(1f)
            }
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
                box[4 * j] = px + left
                // (the first and last rows reach the view's edges: Arabic marks stand above the line's own top)
                box[4 * j + 1] = if (r == 0) 0f else py + l.getLineTop(r)
                box[4 * j + 2] = px + right
                box[4 * j + 3] = if (r >= last) height.toFloat() else py + l.getLineBottom(r)
            }
        }

        private fun drawText(c: Canvas, l: android.text.Layout) {
            val save = c.save()
            c.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            l.draw(c)
            c.restoreToCount(save)
        }

        override fun onDraw(c: Canvas) {
            if (note) { drawNote(c); return }
            val t = times
            val l = layout
            if (!karaoke || t == null || l == null || n == 0) { super.onDraw(c); return }
            if (l !== boxesOf || height != boxesH) measureWords(l)
            if (boxesBad || sungWords < 0) { super.onDraw(c); return }
            val lit = currentTextColor
            val dim = Palette.lyricDim
            val a = pillA.value.coerceIn(0f, 1f)
            val w = pillWord
            if (a < 0.01f || w < 0) drawPlain(c, l, lit, dim) else drawOnPill(c, l, w, a, lit, dim)
            paint.color = lit
        }

        /** No pill (before the first word, a pause): what is sung lit, what is to come dim, one rect clip for each. */
        private fun drawPlain(c: Canvas, l: android.text.Layout, lit: Int, dim: Int) {
            val sung = sungWords.coerceIn(0, n)
            if (sung >= n) { part(c, l, 0f, 0f, 0f, width.toFloat(), height.toFloat(), lit); return }
            val top = box[4 * sung + 1]
            val bottom = box[4 * sung + 3]
            if (row[sung] > 0) part(c, l, 0f, 0f, 0f, width.toFloat(), top, lit)
            if (bottom < height) part(c, l, 0f, 0f, bottom, width.toFloat(), height.toFloat(), dim)
            if (rtl[sung]) {
                part(c, l, 0f, box[4 * sung + 2], top, width.toFloat(), bottom, lit)
                part(c, l, 0f, 0f, top, box[4 * sung + 2], bottom, dim)
            } else {
                part(c, l, 0f, 0f, top, box[4 * sung], bottom, lit)
                part(c, l, 0f, box[4 * sung], top, width.toFloat(), bottom, dim)
            }
        }

        /**
         * The word [w] on its pill. The pill needs room the line's plain space does not have, and a layout may not change while the song plays: so the words on its row part by
         * translation, as the mockup's inline pill pushes them, what comes after the word [gap] further along than the word itself, and all of it from one laid-out text with rect clips. A row
         * that fills the column has less room to part in, and the pill keeps to what there is. While it glides from the word before, the two words and what follows move with it, and their
         * colours cross, so no text is ever lit on the pill or dark off it. No layout, no size change, and nothing but this view redraws.
         */
        private fun drawOnPill(c: Canvas, l: android.text.Layout, w: Int, a: Float, lit: Int, dim: Int) {
            val full = PILL_GAP
            val rw = row[w]
            val room = ((l.width - l.getLineWidth(rw)) / 2f).coerceAtLeast(0f)
            val gap = minOf(full, room)
            val dir = if (rtl[w]) -1f else 1f
            val top = box[4 * w + 1]
            val bottom = box[4 * w + 3]
            val rowsBelow = bottom < height
            // (the rows above are all sung, those below all to come)
            if (rw > 0) part(c, l, 0f, 0f, 0f, width.toFloat(), top, lit)
            if (rowsBelow) part(c, l, 0f, 0f, bottom, width.toFloat(), height.toFloat(), dim)
            val p = fromWord
            val gliding = p >= 0 && p < w && pillG.isRunning
            val g = if (gliding) pillG.value else 1f
            val k = g.coerceIn(0f, 1f)
            val pausing = shownWord < 0
            val w0 = wordEdge(w, start = true)
            val w1 = wordEdge(w, start = false)
            val startEdge = if (dir < 0f) width.toFloat() else 0f
            val endEdge = if (dir < 0f) 0f else width.toFloat()
            // the pill, under the text
            val side = PILL_SIDE * (gap / full).coerceAtLeast(MIN_SIDE)
            wordRect(w, dir * gap * a, side, pillNow)
            if (gliding) {
                wordRect(p, dir * gap * a, side, TMP_RECT)
                pillNow.left = TMP_RECT.left + (pillNow.left - TMP_RECT.left) * g
                pillNow.right = TMP_RECT.right + (pillNow.right - TMP_RECT.right) * g
            }
            fill.color = Palette.withAlpha(owner.wordFill, a)
            val r = Tokens2.R_wordFull.toFloat()
            c.drawRoundRect(pillNow, r, r, fill)
            // the row, part by part, reading on: each part with where it is along the way and its colour
            val wordInk = if (pausing) Palette.mix(lit, owner.wordInk, a) else Palette.mix(dim, owner.wordInk, a * k)
            if (gliding) {
                val p0 = wordEdge(p, start = true)
                span(c, l, startEdge, p0, 0f, top, bottom, lit)
                span(c, l, p0, w0, dir * gap * a * (1f - g), top, bottom, Palette.mix(owner.wordInk, lit, k))
                span(c, l, w0, w1, dir * gap * a * (2f - g), top, bottom, wordInk)
            } else {
                span(c, l, startEdge, w0, 0f, top, bottom, lit)
                span(c, l, w0, w1, dir * gap * a, top, bottom, wordInk)
            }
            span(c, l, w1, endEdge, dir * gap * a * 2f, top, bottom, dim)
        }

        /**
         * Where word [j]'s part of its row begins or ends, in its reading: half way through the space to its neighbour, so that a letter reaching past its own box goes with its word, and the
         * row's end where it has none on that side.
         */
        private fun wordEdge(j: Int, start: Boolean): Float {
            val own = if (rtl[j] == start) box[4 * j + 2] else box[4 * j]
            val k = if (start) j - 1 else j + 1
            if (k !in 0 until n || row[k] != row[j]) return if (rtl[j] == start) width.toFloat() else 0f
            val other = if (rtl[k] == !start) box[4 * k + 2] else box[4 * k]
            return (own + other) / 2f
        }

        /** A part of a row between two x, in either order, moved along by [dx]. */
        private fun span(c: Canvas, l: android.text.Layout, x0: Float, x1: Float, dx: Float, top: Float, bottom: Float, colour: Int) =
            part(c, l, dx, minOf(x0, x1), top, maxOf(x0, x1), bottom, colour)

        /** One rect of the text in one colour, moved by [dx]. */
        private fun part(c: Canvas, l: android.text.Layout, dx: Float, left: Float, top: Float, right: Float, bottom: Float, colour: Int) {
            if (right <= left || bottom <= top) return
            val save = c.save()
            c.translate(dx, 0f)
            c.clipRect(left, top, right, bottom)
            paint.color = colour
            drawText(c, l)
            c.restoreToCount(save)
        }

        private fun drawNote(c: Canvas) {
            val t = noteTint ?: Tint(context, R.drawable.ic_music) { Palette.lyricLit }.also { noteTint = it }
            val s = (textSize * 0.8f).toInt()
            val left = if (owner.rtlUi) width - totalPaddingRight - s else totalPaddingLeft
            val top = totalPaddingTop + (owner.nowRole.viewH - s) / 2
            t.drawable.setBounds(left, top, left + s, top + s)
            t.drawable.draw(c)
        }

        /** The pill at word [j], moved along by [dx]: the word's box, [side] wider each side, 2 px above and 6 below the text's own box. */
        private fun wordRect(j: Int, dx: Float, side: Float, out: RectF) {
            val l = layout ?: return
            paint.getFontMetrics(TMP_FM)
            val base = l.getLineBaseline(row[j]) + totalPaddingTop
            out.set(box[4 * j] + dx - side, base + TMP_FM.ascent - PILL_ABOVE, box[4 * j + 2] + dx + side, base + TMP_FM.descent + PILL_BELOW)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(tick)
            pillA.cancel()
            pillG.cancel()
            super.onDetachedFromWindow()
        }
    }

    /**
     * The lines, each where it lies with none lit (the line being sung and those after it are moved by a transform, [paint]), and behind the one under the finger a tint, drawn
     * here: one shape for all the lines instead of a pressable background made for each. It comes at once and lets go over [Motion.SHORT].
     */
    private inner class LineList(c: Context) : ViewGroup(c) {
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private var lit: View? = null
        private var litAlpha = 0f
        private var letGo: ValueAnimator? = null
        private var bases = IntArray(0)
        private var rows = IntArray(0)
        private var laid = 0

        fun rowsOf(k: Int) = rows.getOrElse(k) { 1 }

        fun baseOf(k: Int) = bases.getOrElse(k) { 0 }

        /** Its lines have been measured, those [count]. */
        fun laidFor(count: Int) = laid == count && count > 0 && isLaidOut && !isLayoutRequested

        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val count = childCount
            if (bases.size < count) { bases = IntArray(count); rows = IntArray(count) }
            val cw = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            val ch = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            var y = paddingTop
            for (k in 0 until count) {
                val v = getChildAt(k)
                v.measure(cw, ch)
                val n = (v as TextView).lineCount.coerceAtLeast(1)
                bases[k] = y
                rows[k] = n
                y += plainHeight(n) + gap
            }
            laid = count
            // (the larger line's extra at the end of the list: it must still reach its slot)
            setMeasuredDimension(width, y + paddingBottom + (nowRole.line - lineRole.line))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, right: Int, b: Int) {
            for (k in 0 until childCount) {
                val v = getChildAt(k)
                val top = bases[k] - PILL_ROOM_TOP
                v.layout(0, top, v.measuredWidth, top + v.measuredHeight)
                // (it shrinks toward the reading edge and the text's own top)
                v.pivotX = if (rtlUi) v.measuredWidth - SIDE.toFloat() else SIDE.toFloat()
                v.pivotY = PILL_ROOM_TOP.toFloat()
            }
        }

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
                // (as the line is seen: at its smaller scale, from the reading edge)
                val y = v.top + v.translationY + PILL_ROOM_TOP
                r.set(SIDE / 2f, y - 4f, width - SIDE / 2f, y + (v.height - PILL_ROOM_TOP - PILL_ROOM_BOTTOM) * v.scaleY + 4f)
                tint.color = Palette.fillPress
                tint.alpha = (Color.alpha(Palette.fillPress) * litAlpha).toInt()
                val rad = Tokens2.R_mid.toFloat()
                c.drawRoundRect(r, rad, rad, tint)
            }
            super.dispatchDraw(c)
        }

        override fun onDetachedFromWindow() {
            letGo?.cancel()
            super.onDetachedFromWindow()
        }
    }

    companion object {
        /** The column, from the view's top, for the panel's rect: the line being sung at y 243, the line sung above it at y 163, the line coming at y 337. */
        const val TOP = 44
        const val HEIGHT = 464
        /** Sideways room round the text for the pill: the view is the column grown by this each side. */
        const val SIDE = 16
        const val TOP_BIG = 72
        const val HEIGHT_BIG = 380
        private const val FIRST_TOP = 243 - TOP
        private const val GAP = 28
        /** No line lit, the five: y 118 to 434. */
        private const val FIRST_TOP_LIST = 118 - TOP
        private const val LAST_BOTTOM_LIST = 450 - TOP
        private const val GAP_LIST = 14
        private const val FIRST_TOP_BIG = 86 - TOP_BIG
        private const val SLOT_BIG = 234 - 86
        private const val LAST_BOTTOM_BIG = 450 - TOP_BIG
        private const val GAP_BIG = 22
        /** A row of a line that wraps: the role's line box and this, line 74 (`music-long-line`). */
        private const val ROW_EXTRA = 8
        private const val PILL_ROOM_TOP = 6
        private const val PILL_ROOM_BOTTOM = 8
        private const val PILL_SIDE = 16f
        private const val PILL_ABOVE = 2f
        private const val PILL_BELOW = 6f
        /** How far the word on its pill moves along its row, and what follows it twice that: the pill's padding and the mockup's margin of 3. */
        private const val PILL_GAP = 19f
        /** With no room to part in, the pill keeps this share of its padding. */
        private const val MIN_SIDE = 0.35f
        /** "Still looking" stands at the top line's place. */
        private const val STATUS_TOP = 163 - TOP
        private const val STATUS_DY = 8
        private const val NOTE_BOTTOM = 16
        private const val BLANK = " "

        /** Lit a little before its time: the eye needs the light a little before the ear hears the word. */
        const val LEAD_MS = 150L
        const val FOLLOW_AFTER_TOUCH_MS = 4000L
        const val LINES_PER_FRAME = 12
        const val IDLE_BUILD_MS = 1500L
        /** Measures the lines' text off the main thread (one for both copies: they ask one after the other). */
        val worker = Executors.newSingleThreadExecutor()

        /** The last line that has started, a moment early (before the first one: -1). The chip's line is this too. */
        fun lineAt(lines: List<Lyrics.Line>, ms: Long): Int {
            val t = ms + LEAD_MS
            var lo = 0
            var hi = lines.size - 1
            var i = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (lines[mid].ms <= t) { i = mid; lo = mid + 1 } else hi = mid - 1
            }
            return i
        }

        // ---- the word (1.15, 1.16)
        /** A word lights a little before it is heard, as a line is lit early ([LEAD_MS]), but less. */
        const val WORD_LEAD_MS = 60L
        /** A word stays on the pill at most this long (a long pause before the next word is not sung). */
        const val MAX_WORD_MS = 3000L
        val TMP_FM = Paint.FontMetrics()
        // (the main thread's, reused: the words' boxes are measured there, once per layout)
        val TMP_PATH = android.graphics.Path()
        val TMP_RECT = RectF()
        val NO_INTS = IntArray(0)
        val NO_FLOATS = FloatArray(0)
        val NO_BOOLS = BooleanArray(0)
    }
}
