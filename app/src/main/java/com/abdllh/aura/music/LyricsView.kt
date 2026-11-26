package com.abdllh.aura.music

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.text.PrecomputedText
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Apple Music / Naghm-style synced lyrics on the AMRI 2.0 Music panel (both side column and full-screen view).
 *
 * - Real word-/syllable-by-syllable feathered liquid light sweep with subtle upward micro-lift, pop scale, and
 *   breathing luminous glow on held notes whenever real word/syllable timings exist (LyricsPlus Apple Music,
 *   BetterLyrics TTML, Enhanced LRC, LyricsServer, or Naghm). Never fakes word drift on line-only lyrics: line-only
 *   tracks glow whole with luminous bloom and gentle active lift.
 * - Elastic staggered Apple Music wave cascade on line transitions: active line rises and expands first while lines
 *   below ripple smoothly into place with per-distance depth-of-field hierarchy, using pure GPU transforms and layers.
 * - Breathing 3-dot Apple Music interludes (`•••`) for instrumental intros and long breaks.
 * - Zero per-frame allocations inside `onDraw` and zero re-layout on line changes for smooth 60 fps on K2501 ES 2.0.
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
    /** A row is laid out from the font's ascent, which is a little over what the role's line box leaves above the baseline. */
    private val litDy = kotlin.math.round(nowRole.baseline - ascent)

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
    /** The underlying lyrics lines from [Lyrics.Result] (kept intact for [timeLines] comparisons and tap callbacks). */
    private var sourceLines: List<Lyrics.Line> = emptyList()
    /** Display rows shown in [list], including synthesized breathing interludes (`•••`). */
    private var displayRows: List<LyricsEngine.DisplayRow> = emptyList()
    private var views: List<Line> = emptyList()
    private var synced = false
    private var current = -1
    private var touchedAt = 0L
    /** Distance-based windowing: off while a hand is on the list, and until it follows again. */
    private var windowOn = true
    /** Brief window after a line change where the wave transition starts, so the 1s seek tick yields one frame. */
    private var waveBusyUntil = 0L

    var onLineTap: ((Lyrics.Line) -> Unit)? = null
    /**
     * A timed line held down (1.15: the music screen offers to forget a saved timing; 1.18: on Naghm's song it measures
     * the Bluetooth sound's delay with it); true when it did something.
     */
    var onLineHold: ((Lyrics.Line) -> Boolean)? = null

    // ---- the song's clock for the words
    /** Word by word: the owner's setting (Settings → Music). Off: whole lines. */
    var wordsOn = true
        set(v) {
            if (v == field) return
            field = v
            views.getOrNull(current)?.let { it.karaoke = v && !it.note && it.times != null }
        }
    private var clockMs = 0L
    private var clockAt = 0L
    /** The song plays: the clock runs on from its last report ([at]) between the screen's ticks. */
    private var songPlaying = false
    private var drawnMs = Long.MIN_VALUE

    /** Where the song is now, for the words: the last report carried on, never a small step back. */
    fun songNow(): Long {
        var p = if (songPlaying) clockMs + (SystemClock.uptimeMillis() - clockAt) else clockMs
        // (a tick's report a few ms behind the clock carried on would jitter the sweep back and forth)
        if (p < drawnMs && p > drawnMs - 250L) p = drawnMs
        drawnMs = p
        return p
    }

    /** Elastic Apple Music scroll spring: fast initial response with a soft, organic landing. */
    private val follow = Spring(0f, 132f, 0.88f, 0.5f) { scroll.scrollTo(0, it.roundToInt()) }

    /** Unknown position: no line lit, all of them there to be read and tapped (Bluetooth: until a line is tapped or the next song starts). */
    var needSync = false
        set(v) {
            if (v == field) return
            field = v
            windowOn = true
            pad()
            if (v) {
                views.getOrNull(current)?.let { it.activeLine = false; it.karaoke = false }
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

    /** The song's accent hue from [Backdrop], used for subtle luminous glow accents in Day theme. */
    fun wordColours(pill: Int, ink: Int) {
        wordFill = pill
        wordInk = ink
        views.getOrNull(current)?.let {
            it.invalidateShader()
            it.invalidate()
        }
    }

    private var wordFill = Palette.wordPill
    private var wordInk = Palette.wordPillInk

    /**
     * Briefly true right as a line-transition wave starts so the 1-second seek tick waits a beat and does not compete
     * with the cascade. Never stalls continuously during 60 fps word sweeps.
     */
    val pillMoving: Boolean get() = SystemClock.uptimeMillis() < waveBusyUntil

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
        for (v in views) {
            v.setTextColor(Palette.lyricLit)
            v.invalidateShader()
            v.invalidate()
        }
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
        sourceLines = emptyList(); displayRows = emptyList(); views = emptyList(); current = -1
        list.removeAllViews()
        status.text = text
        statusBox.animate().cancel()
        if (statusBox.visibility != View.VISIBLE) statusBox.alpha = 0f
        statusBox.visibility = View.VISIBLE
        if (isShown) statusBox.fadeTo(1f, Motion.SHORT) else statusBox.alpha = 1f
        scroll.visibility = View.GONE
    }

    /**
     * Another song is being looked up: what shows steps back, still there, until the answer replaces it, instead of a
     * "looking" line flashing between two songs that both have lyrics (the cached answer comes a moment later).
     */
    fun dim() {
        if (scroll.visibility == View.VISIBLE && list.childCount > 0) {
            for (v in views) { v.isClickable = false; v.isLongClickable = false; v.activeLine = false; v.karaoke = false }
            sourceLines = emptyList(); displayRows = emptyList(); views = emptyList(); current = -1
            follow.cancel()
            scroll.fadeTo(0.25f)
        } else if (statusBox.visibility == View.VISIBLE) statusBox.fadeTo(0.25f)
    }

    // ---- making the lines
    private var pending: Lyrics.Result? = null
    private var pendingRows: List<LyricsEngine.DisplayRow>? = null
    private var texts: List<CharSequence>? = null
    private var times: List<LongArray?>? = null
    private var made = ArrayList<Line>()
    private var gen = 0
    private var centerOnLayout = false

    private fun drop() {
        gen++
        pending = null; pendingRows = null; texts = null; times = null; made = ArrayList()
        onLaid = null
        removeCallbacks(buildStep); removeCallbacks(idleBuild)
        follow.cancel()
    }

    private var onLaid: (() -> Unit)? = null

    fun whenLaid(f: () -> Unit) {
        if (pending == null && (views.isEmpty() || (!centerOnLayout && list.laidFor(views.size)))) { onLaid = null; f() } else onLaid = f
    }

    fun show(r: Lyrics.Result) {
        drop()
        val g = gen
        sourceLines = r.lines
        val rows = LyricsEngine.buildDisplayRows(r.lines, r.synced)
        displayRows = rows
        synced = r.synced
        current = -1
        windowOn = true
        views = emptyList()
        pending = r
        pendingRows = rows
        pad()
        val plain = rows.map { if (it.isInterlude) BLANK else it.line.text.ifBlank { BLANK } }
        val params = if (Build.VERSION.SDK_INT >= 28) template().textMetricsParams else null
        worker.execute {
            val out: List<CharSequence> = if (params == null || Build.VERSION.SDK_INT < 28) plain
                else plain.map { t -> try { PrecomputedText.create(t, params) } catch (_: Throwable) { t } }
            val wt = if (r.synced) rows.map { row ->
                if (!row.isInterlude && row.sourceIndex in r.lines.indices) Words.times(r.lines, row.sourceIndex) else null
            } else null
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
            val rows = pendingRows ?: return
            val t = texts ?: return
            val end = minOf(made.size + LINES_PER_FRAME, rows.size)
            for (k in made.size until end) made.add(line(rows[k], t[k], times?.getOrNull(k)))
            if (made.size < rows.size) postOnAnimation(this) else attach()
        }
    }

    private val idleBuild = Runnable { if (pending != null && texts != null) buildStep.run() }

    private fun finishNow() {
        val r = pending ?: return
        val rows = pendingRows ?: return
        removeCallbacks(buildStep); removeCallbacks(idleBuild)
        val t = texts ?: rows.map { if (it.isInterlude) BLANK else it.line.text.ifBlank { BLANK } }
        val wt = times ?: if (r.synced) rows.map { row ->
            if (!row.isInterlude && row.sourceIndex in r.lines.indices) Words.times(r.lines, row.sourceIndex) else null
        } else null
        for (k in made.size until rows.size) made.add(line(rows[k], t[k], wt?.getOrNull(k)))
        attach()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && pending != null && texts != null) { removeCallbacks(idleBuild); removeCallbacks(buildStep); post(buildStep) }
    }

    private fun attach() {
        pending = null
        pendingRows = null
        list.removeAllViews()
        views = made
        made = ArrayList()
        for (v in views) list.addView(v)
        statusBox.animate().cancel()
        statusBox.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        centerOnLayout = true
        if (isShown && onLaid == null) { scroll.animate().cancel(); scroll.alpha = 0f; scroll.fadeTo(1f, Motion.SHORT) }
        else { scroll.animate().cancel(); scroll.alpha = 1f }
    }

    private fun style(v: Line) {
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, nowRole.sp)
        v.typeface = Fonts.of(nowRole)
        v.setTextColor(Palette.lyricLit)
        v.alignStart = true
        v.textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        v.setLineSpacing(rowPitch - nowRole.viewH.toFloat(), 1f)
        v.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        if (Build.VERSION.SDK_INT >= 28) v.isFallbackLineSpacing = false
        v.setPadding(SIDE, PILL_ROOM_TOP, SIDE, PILL_ROOM_BOTTOM)
    }

    private var templateLine: Line? = null

    private fun template(): Line = templateLine ?: Line(context, this).also { style(it); templateLine = it }

    private fun line(row: LyricsEngine.DisplayRow, text: CharSequence, wordTimes: LongArray?): Line = Line(context, this).apply {
        style(this)
        try { setText(text) } catch (_: IllegalArgumentException) { setText(text.toString()) }
        note = row.isInterlude
        interludeStartMs = row.ms
        interludeEndMs = row.endMs
        if (!row.isInterlude) words(wordTimes, row.line.ends, row.line.syllables)
        if (synced) takeTaps(this)
        wantAlpha = -1f
    }

    private fun takeTaps(v: Line) {
        v.isClickable = true
        v.setOnClickListener(tapLine)
        v.setOnLongClickListener(holdLine)
    }

    private val tapLine = View.OnClickListener { v ->
        if (v.alpha > 0.05f) {
            val row = displayRows.getOrNull(views.indexOf(v)) ?: return@OnClickListener
            val target = if (row.sourceIndex in sourceLines.indices) sourceLines[row.sourceIndex] else row.line
            onLineTap?.invoke(target)
        }
    }
    private val holdLine = View.OnLongClickListener { v ->
        val row = displayRows.getOrNull(views.indexOf(v)) ?: return@OnLongClickListener false
        if (row.sourceIndex in sourceLines.indices) onLineHold?.invoke(sourceLines[row.sourceIndex]) ?: false else false
    }

    /**
     * Times (or newly arrived real word/syllable timings) for the lines already shown: updates word/syllable timings
     * in place with zero layout jump whenever the same lines are already displayed.
     */
    fun timeLines(r: Lyrics.Result) {
        if (!r.synced) return
        val same = pending == null && sourceLines.size == r.lines.size && views.size == displayRows.size &&
            r.lines.indices.all { Words.sameText(sourceLines[it].text, r.lines[it].text) }
        if (!same || !synced) { show(r); return }
        sourceLines = r.lines
        val updatedRows = ArrayList<LyricsEngine.DisplayRow>(displayRows.size)
        for ((k, row) in displayRows.withIndex()) {
            val v = views[k]
            if (!row.isInterlude && row.sourceIndex in r.lines.indices) {
                val srcLine = r.lines[row.sourceIndex]
                val nextMs = r.lines.getOrNull(row.sourceIndex + 1)?.ms ?: (srcLine.ms + 4500L)
                val endMs = if (srcLine.endMs > srcLine.ms) srcLine.endMs else nextMs
                updatedRows.add(LyricsEngine.DisplayRow(row.sourceIndex, srcLine.ms, endMs, srcLine, false))
                v.words(Words.times(r.lines, row.sourceIndex), srcLine.ends, srcLine.syllables)
            } else {
                updatedRows.add(row)
            }
        }
        displayRows = updatedRows
        views.getOrNull(current)?.let { it.karaoke = wordsOn && !it.note && it.times != null }
    }

    // ---- following the song
    fun at(ms: Long, playing: Boolean = songPlaying) {
        val now = SystemClock.uptimeMillis()
        val carried = if (songPlaying) clockMs + (now - clockAt) else clockMs
        val moved = playing != songPlaying || abs(carried - ms) > 250L
        clockMs = ms
        clockAt = now
        songPlaying = playing
        if (moved) {
            drawnMs = Long.MIN_VALUE
            views.getOrNull(current)?.syncFrame()
        }
        if (!synced || displayRows.isEmpty() || needSync) return
        val i = rowAt(displayRows, ms)
        if (i == current) return
        val prev = current
        val delta = if (prev >= 0) i - prev else 0
        views.getOrNull(prev)?.let { it.activeLine = false; it.karaoke = false }
        current = i
        val v = views.getOrNull(i)
        if (v != null) {
            v.activeLine = true
            v.karaoke = wordsOn && !v.note && v.times != null
            v.syncFrame()
        }
        val live = isShown
        if (live) {
            waveBusyUntil = SystemClock.uptimeMillis() + WAVE_BUSY_MS
            paint(animate = true, lineDelta = delta)
            if (SystemClock.elapsedRealtime() - touchedAt > FOLLOW_AFTER_TOUCH_MS) glide()
        } else {
            paint(animate = false)
            settleScroll()
        }
    }

    /**
     * How long until the next line/interlude is due (from [ms]): lets [MusicPanel] schedule its tick right on the beat.
     */
    fun msToNext(ms: Long): Long {
        if (!synced || needSync || displayRows.isEmpty()) return Long.MAX_VALUE
        val next = displayRows.getOrNull(current + 1) ?: return Long.MAX_VALUE
        return next.ms - LEAD_MS - ms
    }

    /** Brought into sight: the lines are made if they were not yet, and the line being sung stands in its slot at once. */
    fun settle() {
        finishNow()
        follow.cancel()
        windowOn = true
        paint(animate = false)
        settleScroll()
        views.getOrNull(current)?.syncFrame()
    }

    private fun extraOf(k: Int): Float {
        val r = list.rowsOf(k)
        return (litHeight(r) - plainHeight(r)).toFloat()
    }

    private fun plainHeight(rows: Int) = lineRole.line + ((rows - 1) * rowPitch * small).toInt()

    private fun litHeight(rows: Int) = if (rows <= 1) nowRole.line else rows * rowPitch

    /**
     * Positions and styles every line with Apple Music depth-of-field hierarchy and elastic staggered wave transitions
     * on line changes. Only animates GPU transform & alpha properties (with hardware layer during scale changes).
     */
    private fun paint(animate: Boolean, lineDelta: Int = 0) {
        val vs = views
        if (vs.isEmpty()) return
        val dark = Palette.dark
        val browseA = if (dark) 0.78f else 0.84f
        val up1A = if (dark) 0.50f else 0.56f
        val up2A = if (dark) 0.30f else 0.36f
        val up3A = if (dark) 0.16f else 0.22f
        val past1A = if (dark) 0.28f else 0.34f
        val past2A = if (dark) 0.15f else 0.20f
        val past3A = if (dark) 0.09f else 0.12f

        val top = if (synced && !needSync && windowOn) targetOf(current) else 0
        val extra = if (current in vs.indices && list.laidFor(vs.size)) extraOf(current) else 0f
        val laid = list.laidFor(vs.size)
        val cascade = animate && lineDelta != 0 && abs(lineDelta) <= 4 && windowOn
        val leavingIdx = if (cascade) current - lineDelta else -1

        for ((k, v) in vs.withIndex()) {
            val lit = k == current
            val dist = if (current >= 0) abs(k - current) else k
            val after = current in 0 until k
            val s = when {
                lit -> 1f
                !windowOn -> small * 1.03f
                dist <= 1 -> small
                else -> small * 0.955f
            }
            val lift = if (lit && !v.note) -ACTIVE_LIFT_PX else 0f
            val y = (if (lit) litDy else smallDy + if (after) extra else 0f) + lift
            val a = when {
                lit -> 1f
                windowOn && laid && !inWindow(k, top, after, extra) -> 0f
                !windowOn -> browseA
                !synced || needSync || current < 0 -> up1A
                k < current -> when (dist) { 1 -> past1A; 2 -> past2A; else -> past3A }
                else -> when (dist) { 1 -> up1A; 2 -> up2A; else -> up3A }
            }

            val rippleLine = cascade && a > 0f && dist in 1..4 &&
                ((lineDelta > 0 && k > current) || (lineDelta < 0 && k < current))
            if (!rippleLine && v.wantAlpha == a && v.wantScale == s && v.wantY == y) continue

            val was = v.wantAlpha
            v.wantAlpha = a; v.wantScale = s; v.wantY = y

            if (!animate || was < 0f || (was == 0f && a == 0f)) {
                v.animate().cancel()
                v.alpha = a; v.scaleX = s; v.scaleY = s; v.translationY = y
            } else if (lit) {
                move(v, a, s, y, dur = WAVE_ENTER_MS, interp = WAVE_ENTER, delay = 0L)
            } else if (k == leavingIdx) {
                move(v, a, s, y, dur = WAVE_EXIT_MS, interp = WAVE_EXIT, delay = 0L)
            } else if (rippleLine) {
                // Classic Apple Music cascade: lines below lag behind the active line slightly then glide elastically into place
                val lagDir = if (lineDelta > 0) 1f else -1f
                val lagPx = (dist * 9f).coerceAtMost(28f)
                v.animate().cancel()
                v.translationY = v.translationY + lagDir * lagPx
                move(v, a, s, y, dur = WAVE_CASCADE_MS, interp = WAVE_ENTER, delay = dist * WAVE_STAGGER_MS)
            } else if (v.scaleX != s) {
                move(v, a, s, y, dur = Motion.MEDIUM, interp = WAVE_EXIT, delay = 0L)
            } else {
                move(v, a, s, y, dur = Motion.MEDIUM, interp = Motion.MOVE, delay = 0L)
            }
        }
    }

    /** Whether line [k] fits cleanly inside the visible bounds without clipping at the top or bottom edge. */
    private fun inWindow(k: Int, top: Int, after: Boolean, extra: Float): Boolean {
        if (!big && !asList) {
            val t = list.baseOf(k) + (if (after) extra else 0f) - top
            val b = t + plainHeight(list.rowsOf(k))
            val viewH = if (height > 0) height else HEIGHT
            return t >= 12f && b <= viewH - 12f
        }
        val t = list.baseOf(k) + (if (after) extra else 0f) - top
        val b = t + plainHeight(list.rowsOf(k))
        return t >= firstTop - 1 && b <= lastBottom
    }

    /**
     * Animates a line to [alpha], [scale], and [y]. Uses a hardware layer whenever [scale] changes so the GPU scales
     * a single texture instead of re-rasterizing text glyphs each frame.
     */
    private fun move(v: View, alpha: Float, scale: Float, y: Float, dur: Long, interp: TimeInterpolator, delay: Long) {
        v.animate().cancel()
        val a = v.animate().alpha(alpha).scaleX(scale).scaleY(scale).translationY(y)
            .setStartDelay(delay).setDuration(dur).setInterpolator(interp)
        if (v.scaleX != scale) a.withLayer()
        a.start()
    }

    private fun targetOf(k: Int): Int {
        if (k < 0 || !list.laidFor(views.size)) return 0
        val rise = if (big) 0 else (list.rowsOf(k) - 1) * rowPitch / 2
        return (list.baseOf(k) - firstTop - slot + rise).coerceIn(0, (list.height - scroll.height).coerceAtLeast(0))
    }

    private fun settleScroll() {
        follow.cancel()
        if (list.height == 0 || scroll.height == 0) { centerOnLayout = true; return }
        scroll.scrollTo(0, targetOf(current))
    }

    private fun glide() {
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
        if (!isShown || !synced || needSync) return@Runnable
        windowOn = true
        paint(animate = true)
        glide()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
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
     * One lyric line (or breathing interlude row). Draws Apple Music feathered liquid word sweeps (when real word or
     * syllable timings exist), full-line luminous bloom (when line-synced only), or animated 3-dot interludes (`•••`)
     * with zero per-frame object allocations.
     */
    private class Line(c: Context, private val owner: LyricsView) : AText(c) {
        override fun hasOverlappingRendering() = false
        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            (parent as? LineList)?.pressed(this, pressed)
        }

        var wantAlpha = -1f
        var wantScale = -1f
        var wantY = Float.NaN

        /** Instrumental interlude line rendered as 3 breathing Apple Music dots (`•••`). */
        var note = false
        var interludeStartMs = 0L
        var interludeEndMs = 0L

        /** True while this row is the currently singing row (word-timed, line-only, or interlude). */
        var activeLine = false
            set(v) {
                if (v == field) return
                field = v
                if (!v) stopLoop()
                invalidate()
                if (v && note) syncFrame()
            }

        /** When each word starts, and where the last ends (song ms); null: line-synced only or untimed. */
        var times: LongArray? = null
            private set
        /** Where a word's singing ends before a pause (song ms, -1: runs on to next word); null: none known. */
        private var pauses: LongArray? = null
        /** Optional per-word sub-syllable intervals (Apple Music TTML / LyricsPlus). */
        private var syllables: Array<Array<Lyrics.Syllable>?>? = null

        /** Active line with real word timings and word-by-word setting enabled. */
        var karaoke = false
            set(v) {
                if (v == field) return
                field = v
                if (!v) stopLoop()
                invalidate()
                if (v) syncFrame()
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
        private var boxesBad = false

        // Reusable GPU shader + matrix + interlude dot paint (zero allocations inside onDraw)
        private var sweepShader: LinearGradient? = null
        private var shaderLit = 0
        private var shaderDim = 0
        private val sweepMatrix = Matrix()
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var looping = false

        fun invalidateShader() {
            sweepShader = null
        }

        private fun ensureShader(lit: Int, dim: Int): LinearGradient {
            val cur = sweepShader
            if (cur != null && shaderLit == lit && shaderDim == dim) return cur
            shaderLit = lit
            shaderDim = dim
            return LinearGradient(0f, 0f, 1f, 0f, lit, dim, Shader.TileMode.CLAMP).also { sweepShader = it }
        }

        fun words(t: LongArray?, p: LongArray? = null, s: Array<Array<Lyrics.Syllable>?>? = null) {
            val text = text
            val cnt = if (t == null) 0 else Words.count(text)
            if (t == null || cnt == 0 || t.size != cnt + 1) {
                times = null; pauses = null; syllables = null; n = 0; invalidate(); return
            }
            if (starts.size < cnt) {
                starts = IntArray(cnt); ends = IntArray(cnt); row = IntArray(cnt); rtl = BooleanArray(cnt)
                box = FloatArray(4 * cnt)
            }
            Words.bounds(text, starts, ends)
            n = cnt
            times = t
            pauses = p?.takeIf { it.size == cnt }
            syllables = s?.takeIf { it.size == cnt }
            boxesOf = null
            if (karaoke) syncFrame() else invalidate()
        }

        /** Exact end time for word [j], honoring real syllable/pause end times for long held notes without artificial cutoffs. */
        private fun endOf(t: LongArray, j: Int): Long {
            val p = pauses?.getOrNull(j) ?: -1L
            val next = if (j + 1 < n) t[j + 1] else t[n]
            val hasRealEnd = (p > t[j] && p <= next) || (syllables?.getOrNull(j) != null)
            val rawEnd = if (p > t[j] && p < next) p else next
            val cap = if (hasRealEnd || j == n - 1) MAX_HELD_WORD_MS else MAX_IMPLICIT_WORD_MS
            return minOf(maxOf(t[j] + 40L, rawEnd), t[j] + cap)
        }

        private val frameTick = Runnable {
            looping = false
            invalidate()
            scheduleNext()
        }

        private fun stopLoop() {
            looping = false
            removeCallbacks(frameTick)
        }

        /** Triggers an immediate redraw and arms the 60 fps animation loop only while actively singing or pulsing. */
        fun syncFrame() {
            stopLoop()
            invalidate()
            scheduleNext()
        }

        private fun scheduleNext() {
            if (!isAttachedToWindow || !owner.songPlaying) return
            if (note && activeLine) {
                val now = owner.songNow() + LEAD_MS
                if (now <= interludeEndMs + 120L) {
                    looping = true
                    postOnAnimation(frameTick)
                }
                return
            }
            val t = times
            if (!karaoke || t == null || n == 0) return
            val now = owner.songNow() + WORD_LEAD_MS
            val lastEnd = endOf(t, n - 1)
            if (now > lastEnd + 80L) return

            // Run at 60 fps while any word is actively sweeping or about to start; sleep cleanly through silent pauses
            var sweeping = false
            var nextWake = Long.MAX_VALUE
            for (j in 0 until n) {
                val ws = t[j]
                val we = endOf(t, j)
                if (now in (ws - 60L)..(we + 40L)) {
                    sweeping = true
                    break
                }
                if (ws > now && ws < nextWake) nextWake = ws
            }
            looping = true
            if (sweeping) {
                postOnAnimation(frameTick)
            } else if (nextWake < Long.MAX_VALUE) {
                postDelayed(frameTick, (nextWake - now - 40L).coerceIn(16L, 2000L))
            }
        }

        /**
         * Measures each word's exact layout box once per Layout on a plain-text StaticLayout copy matching Android 10
         * PrecomputedText line breaks.
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
            if (note) { drawInterludeDots(c); return }
            val t = times
            val l = layout
            if (!karaoke || t == null || l == null || n == 0) {
                drawLineOnly(c)
                return
            }
            if (l !== boxesOf || height != boxesH) measureWords(l)
            if (boxesBad) {
                drawLineOnly(c)
                return
            }
            drawAppleMusicWords(c, l, t)
        }

        /** Line-synced mode (or inactive line): full active line glows warmly without any fake per-word drift. */
        private fun drawLineOnly(c: Canvas) {
            val lit = currentTextColor
            paint.shader = null
            if (activeLine) {
                val glowColor = if (Palette.dark) Palette.withAlpha(Color.WHITE, 0.44f)
                    else Palette.withAlpha(owner.wordFill, 0.20f)
                paint.setShadowLayer(if (Palette.dark) 13f else 7f, 0f, 0f, glowColor)
                super.onDraw(c)
                paint.clearShadowLayer()
            } else {
                paint.clearShadowLayer()
                super.onDraw(c)
            }
            paint.color = lit
        }

        /**
         * Authentic Naghm / Apple Music real word-/syllable-synced rendering:
         * - Unsung words sit softly dimmed (`0.30` alpha).
         * - Already-sung words stand bright (`1.0` alpha) with a subtle lingering halo and tiny rest lift.
         * - Currently singing word sweeps smoothly via a feathered GPU [LinearGradient] in its natural script direction
         *   (RTL for Arabic, LTR for English), following exact sub-word syllables so held notes stretch and breathe
         *   with a gentle upward micro-lift, pop scale, and luminous glow.
         */
        private fun drawAppleMusicWords(c: Canvas, l: android.text.Layout, t: LongArray) {
            val lit = currentTextColor
            val dark = Palette.dark
            val unsung = Palette.withAlpha(lit, if (dark) 0.30f else 0.32f)
            val glowBase = if (dark) Color.WHITE else owner.wordFill
            val shader = ensureShader(lit, unsung)
            val now = owner.songNow() + WORD_LEAD_MS
            val breathSec = SystemClock.uptimeMillis() / 1000.0
            val padL = totalPaddingLeft.toFloat()

            val rowCount = l.lineCount
            var wordCursor = 0
            for (r in 0 until rowCount) {
                val rStart = wordCursor
                while (wordCursor < n && row[wordCursor] == r) wordCursor++
                val rEnd = wordCursor
                if (rStart >= rEnd) continue

                val rowTop = box[4 * rStart + 1]
                val rowBottom = box[4 * rStart + 3]

                // Check fast-path: entire row unsung or entire row already finished singing
                var allUnsung = true
                var allSung = true
                for (j in rStart until rEnd) {
                    val ws = t[j]
                    val we = endOf(t, j)
                    val p = LyricsEngine.wordProgress(now, ws, we, syllables?.getOrNull(j))
                    if (p > 0.001f) allUnsung = false
                    if (p < 0.999f) allSung = false
                }

                if (allUnsung) {
                    val save = c.save()
                    c.clipRect(0f, rowTop, width.toFloat(), rowBottom)
                    paint.shader = null
                    paint.clearShadowLayer()
                    paint.color = unsung
                    drawText(c, l)
                    c.restoreToCount(save)
                    continue
                }

                if (allSung) {
                    val save = c.save()
                    c.translate(0f, -0.8f)
                    c.clipRect(0f, rowTop, width.toFloat(), rowBottom)
                    paint.shader = null
                    if (dark) paint.setShadowLayer(6f, 0f, 0f, Palette.withAlpha(glowBase, 0.22f))
                    else paint.clearShadowLayer()
                    paint.color = lit
                    drawText(c, l)
                    paint.clearShadowLayer()
                    c.restoreToCount(save)
                    continue
                }

                // Active row: draw each word in its own clean clip slice (preserving full Arabic cursive shaping)
                for (j in rStart until rEnd) {
                    val ws = t[j]
                    val we = endOf(t, j)
                    val syls = syllables?.getOrNull(j)
                    val p = LyricsEngine.wordProgress(now, ws, we, syls)
                    val e0 = wordEdge(j, start = true)
                    val e1 = wordEdge(j, start = false)
                    val clipL = minOf(e0, e1)
                    val clipR = maxOf(e0, e1)
                    if (clipR <= clipL) continue

                    if (p <= 0.001f) {
                        val save = c.save()
                        c.clipRect(clipL, rowTop, clipR, rowBottom)
                        paint.shader = null
                        paint.clearShadowLayer()
                        paint.color = unsung
                        drawText(c, l)
                        c.restoreToCount(save)
                    } else if (p >= 0.999f) {
                        val save = c.save()
                        c.translate(0f, -0.8f)
                        c.clipRect(clipL, rowTop, clipR, rowBottom)
                        paint.shader = null
                        if (dark) paint.setShadowLayer(6f, 0f, 0f, Palette.withAlpha(glowBase, 0.22f))
                        else paint.clearShadowLayer()
                        paint.color = lit
                        drawText(c, l)
                        paint.clearShadowLayer()
                        c.restoreToCount(save)
                    } else {
                        val sustained = LyricsEngine.isWordSustaining(now, ws, we, syls)
                        val wave = if (sustained) sin(breathSec * 4.2).toFloat() else 0f
                        val baseArc = -3.0f * sin(min(1f, p * 1.05f) * PI.toFloat() * 0.72f)
                        val restLift = -0.8f * p
                        val holdLift = if (sustained) -(1.3f + 0.6f * wave) else 0f
                        val liftY = baseArc + restLift + holdLift

                        val popDelta = if (sustained) (0.036f + 0.010f * wave) else (0.024f * sin(p * PI.toFloat()))
                        val popScale = 1f + popDelta

                        val glowAlpha = if (dark) {
                            if (sustained) 0.56f + 0.14f * wave else 0.26f + 0.34f * sin(p * PI.toFloat())
                        } else {
                            if (sustained) 0.30f + 0.06f * wave else 0.20f * sin(p * PI.toFloat())
                        }
                        val glowRadius = if (sustained) 14f else 10f

                        // Position the reusable unit LinearGradient in layout coordinates so it sweeps across this exact word
                        val lx0 = box[4 * j] - padL
                        val lx1 = box[4 * j + 2] - padL
                        val w = (lx1 - lx0).coerceAtLeast(4f)
                        val feather = maxOf(w * 0.30f, 16f)
                        if (rtl[j]) {
                            val head = lx1 - p * (w + feather)
                            val tail = head + feather
                            sweepMatrix.setScale(-feather, 1f)
                            sweepMatrix.postTranslate(tail, 0f)
                        } else {
                            val head = lx0 + p * (w + feather)
                            val tail = head - feather
                            sweepMatrix.setScale(feather, 1f)
                            sweepMatrix.postTranslate(tail, 0f)
                        }
                        shader.setLocalMatrix(sweepMatrix)

                        val cx = (box[4 * j] + box[4 * j + 2]) * 0.5f
                        val cy = (rowTop + rowBottom) * 0.5f
                        val save = c.save()
                        c.translate(0f, liftY)
                        c.scale(popScale, popScale, cx, cy)
                        c.clipRect(clipL, rowTop, clipR, rowBottom)
                        paint.color = lit
                        paint.shader = shader
                        if (glowAlpha > 0.02f) paint.setShadowLayer(glowRadius, 0f, 0f, Palette.withAlpha(glowBase, glowAlpha))
                        else paint.clearShadowLayer()
                        drawText(c, l)
                        paint.shader = null
                        paint.clearShadowLayer()
                        c.restoreToCount(save)
                    }
                }
            }
            paint.shader = null
            paint.clearShadowLayer()
            paint.color = lit
        }

        /**
         * Where word [j]'s part of its row begins or ends, in its reading direction: half way through the space to its
         * neighbour so overhanging glyphs/diacritics stay with their word.
         */
        private fun wordEdge(j: Int, start: Boolean): Float {
            val own = if (rtl[j] == start) box[4 * j + 2] else box[4 * j]
            val k = if (start) j - 1 else j + 1
            if (k !in 0 until n || row[k] != row[j]) return if (rtl[j] == start) width.toFloat() else 0f
            val other = if (rtl[k] == !start) box[4 * k + 2] else box[4 * k]
            return (own + other) / 2f
        }

        /**
         * Breathing Apple Music 3-dot interlude (`•••`): fills sequentially in the UI's reading direction with subtle
         * vertical phase-shifted waves and a gentle shrink right before the next sung line arrives.
         */
        private fun drawInterludeDots(c: Canvas) {
            val lit = Palette.lyricLit
            val dark = Palette.dark
            val radius = (textSize * 0.165f).coerceIn(5.5f, 8f)
            val step = radius * 3.5f
            val cy = totalPaddingTop + owner.nowRole.viewH * 0.52f
            val anchorX = if (owner.rtlUi) width - totalPaddingRight - radius - 2f else totalPaddingLeft + radius + 2f
            val dir = if (owner.rtlUi) -1f else 1f

            val active = activeLine
            val now = owner.songNow() + LEAD_MS
            val dur = maxOf(600L, interludeEndMs - interludeStartMs).toFloat()
            val progress = if (active) ((now - interludeStartMs) / dur).coerceIn(0f, 1f) else 0f
            val exitScale = if (active && progress > 0.92f) (1f - (progress - 0.92f) * 2.5f).coerceIn(0.45f, 1f) else 1f
            val breathSec = SystemClock.uptimeMillis() / 1000.0

            for (slot in 0..2) {
                val fill = if (active) (progress * 3f - slot).coerceIn(0f, 1f) else 0f
                val wave = if (active && owner.songPlaying) sin(breathSec * 2.8 - slot * 0.65).toFloat() else 0f
                val scale = (if (active) (0.82f + 0.24f * fill + 0.08f * wave) * exitScale else 0.84f).coerceAtLeast(0.45f)
                val alpha = if (active) (0.28f + 0.72f * fill) else 0.42f
                val dy = if (active) -2.6f * wave else 0f
                val cx = anchorX + dir * slot * step

                dotPaint.color = Palette.withAlpha(lit, alpha)
                if (active && dark && fill > 0.08f) {
                    dotPaint.setShadowLayer(8f, 0f, 0f, Palette.withAlpha(Color.WHITE, 0.48f * fill))
                } else {
                    dotPaint.clearShadowLayer()
                }
                c.drawCircle(cx, cy + dy, radius * scale, dotPaint)
            }
            dotPaint.clearShadowLayer()
            if (active && owner.songPlaying && !looping && progress < 1f) scheduleNext()
        }

        override fun onDetachedFromWindow() {
            stopLoop()
            super.onDetachedFromWindow()
        }
    }

    /**
     * The lines, each where it lies with none lit (the line being sung and those after it are moved by a transform, [paint]),
     * and behind the one under the finger a press tint.
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
            setMeasuredDimension(width, y + paddingBottom + (nowRole.line - lineRole.line))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, right: Int, b: Int) {
            for (k in 0 until childCount) {
                val v = getChildAt(k)
                val top = bases[k] - PILL_ROOM_TOP
                v.layout(0, top, v.measuredWidth, top + v.measuredHeight)
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
        const val TOP = 44
        const val HEIGHT = 464
        const val SIDE = 16
        const val TOP_BIG = 72
        const val HEIGHT_BIG = 380
        private const val FIRST_TOP = 243 - TOP
        private const val GAP = 28
        private const val FIRST_TOP_LIST = 118 - TOP
        private const val LAST_BOTTOM_LIST = 450 - TOP
        private const val GAP_LIST = 14
        private const val FIRST_TOP_BIG = 86 - TOP_BIG
        private const val SLOT_BIG = 234 - 86
        private const val LAST_BOTTOM_BIG = 450 - TOP_BIG
        private const val GAP_BIG = 22
        private const val ROW_EXTRA = 8
        /** Vertical headroom inside each Line view for upward micro-lift (-4 px) and luminous glow halos. */
        private const val PILL_ROOM_TOP = 10
        private const val PILL_ROOM_BOTTOM = 10
        private const val STATUS_TOP = 163 - TOP
        private const val STATUS_DY = 8
        private const val NOTE_BOTTOM = 16
        private const val BLANK = " "

        /** Subtle upward float on the active line (px). */
        private const val ACTIVE_LIFT_PX = 3f
        /** Apple Music wave transition timings and curves. */
        private val WAVE_ENTER: TimeInterpolator = PathInterpolator(0.18f, 0.92f, 0.30f, 1.0f)
        private val WAVE_EXIT: TimeInterpolator = PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f)
        private const val WAVE_ENTER_MS = 440L
        private const val WAVE_EXIT_MS = 340L
        private const val WAVE_CASCADE_MS = 460L
        private const val WAVE_STAGGER_MS = 44L
        private const val WAVE_BUSY_MS = 200L

        /** Lit a little before its time: the eye needs the light a little before the ear hears the word. */
        const val LEAD_MS = 150L
        const val FOLLOW_AFTER_TOUCH_MS = 4000L
        const val LINES_PER_FRAME = 12
        const val IDLE_BUILD_MS = 1500L
        val worker = Executors.newSingleThreadExecutor()

        /** The last line that has started, a moment early (before the first one: -1). */
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

        private fun rowAt(rows: List<LyricsEngine.DisplayRow>, ms: Long): Int {
            val t = ms + LEAD_MS
            var lo = 0
            var hi = rows.size - 1
            var i = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (rows[mid].ms <= t) { i = mid; lo = mid + 1 } else hi = mid - 1
            }
            return i
        }

        /** A word lights a little before it is heard, as a line is lit early ([LEAD_MS]), but less. */
        const val WORD_LEAD_MS = 60L
        /** Real word/syllable end cap (supports long sustained vocal notes up to 12 s). */
        const val MAX_HELD_WORD_MS = 12_000L
        /** Implicit word end cap when no explicit end time was provided before a long gap. */
        const val MAX_IMPLICIT_WORD_MS = 3500L

        val TMP_PATH = android.graphics.Path()
        val TMP_RECT = RectF()
        val NO_INTS = IntArray(0)
        val NO_FLOATS = FloatArray(0)
        val NO_BOOLS = BooleanArray(0)
    }
}
