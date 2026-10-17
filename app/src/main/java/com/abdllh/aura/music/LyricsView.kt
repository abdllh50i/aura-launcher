package com.abdllh.aura.music

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
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
    private val done: AText
    private val doneBg = Shapes.pill(Palette.accent)
    private var lines: List<Lyrics.Line> = emptyList()
    private var views: List<Line> = emptyList()
    private var synced = false
    private var current = -1
    private var touchedAt = 0L
    var onLineTap: ((Lyrics.Line) -> Unit)? = null

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
            if (v) { for (t in views) fade(t, IDLE, 1f, 0L, Motion.STANDARD); current = -1 }
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
        doneBg.setColor(fill)
        done.setTextColor(Palette.onColor(fill))
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
            needSync -> maxOf((h * 0.08f).toInt(), 64.dp) // (below the hint)
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
            for (v in views) v.isClickable = false
            lines = emptyList(); views = emptyList(); current = -1
            follow.cancel()
            scroll.fadeTo(0.25f)
        } else if (statusBox.visibility == View.VISIBLE) statusBox.fadeTo(0.25f)
    }

    // ---- making the lines
    /** Lyrics waiting to become lines, their texts once measured, and the lines made so far. */
    private var pending: Lyrics.Result? = null
    private var texts: List<CharSequence>? = null
    private var made = ArrayList<Line>()
    /** Counts the lyrics shown: a measuring that comes back for lyrics already replaced is dropped. */
    private var gen = 0
    /** The lit line goes to the middle at the next layout (the lines were just added, or the room around them changed). */
    private var centerOnLayout = false

    private fun drop() {
        gen++
        pending = null; texts = null; made = ArrayList()
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
            post {
                if (g != gen) return@post
                texts = out
                if (isShown) buildStep.run() else postDelayed(idleBuild, IDLE_BUILD_MS)
            }
        }
    }

    private val buildStep = object : Runnable {
        override fun run() {
            val r = pending ?: return
            val t = texts ?: return
            val end = minOf(made.size + LINES_PER_FRAME, r.lines.size)
            for (k in made.size until end) made.add(line(r.lines[k], t[k]))
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
        for (k in made.size until r.lines.size) made.add(line(r.lines[k], t[k]))
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
        val pad = (if (big) Space.M else Space.S).dp
        v.setPadding(pad, pad, pad, pad)
    }

    private var templates = arrayOfNulls<Line>(2)

    /** A line as the lyrics' lines are styled, for the measuring done off the main thread. */
    private fun template(timed: Boolean): Line =
        templates[if (timed) 1 else 0] ?: Line(context).also { style(it, timed); templates[if (timed) 1 else 0] = it }

    private fun line(l: Lyrics.Line, text: CharSequence): Line = Line(context).apply {
        style(this, synced)
        // (measured with settings this line does not have after all: as plain text, measured here)
        try { setText(text) } catch (_: IllegalArgumentException) { setText(text.toString()) }
        if (synced) {
            isClickable = true
            setOnClickListener { onLineTap?.invoke(l) }
        }
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
    /** The song is at [ms]: the line being sung, lit and brought to the middle (unless the owner is scrolling). */
    fun at(ms: Long) {
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
        } else views.getOrNull(current)?.let { fade(it, dim, small, if (live) Motion.SHORT else 0L, Motion.EXIT) }
        current = i
        val v = views.getOrNull(i) ?: return
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
    private class Line(c: Context) : AText(c) {
        override fun hasOverlappingRendering() = false
        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            (parent as? LineList)?.pressed(this, pressed)
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
    }
}
