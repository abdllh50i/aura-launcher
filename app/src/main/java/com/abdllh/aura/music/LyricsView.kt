package com.abdllh.aura.music

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp

/**
 * The song's lyrics on the music screen. Timed lyrics: the line being sung is lit and glides to the middle, the others
 * dimmed and a little smaller (a few seconds after the owner scrolls by hand, it follows the song again); a tapped
 * line is where the song goes ([onLineTap]: the unit's own songs jump there, Bluetooth ones set their clock — the
 * phone does not say where a song is, see [BtMusic]). [needSync]: the song's position is unknown, so nothing is lit
 * and a hint asks for a tap on the line being sung. Untimed: just the text.
 */
internal class LyricsView(c: Context) : FrameLayout(c) {
    private val scroll = ScrollView(c).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = true // the lines fade at the panel's edges instead of being cut
        setFadingEdgeLength(70.dp)
    }
    private val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val status = c.label(19f, Palette.text2, Fonts.REGULAR, lines = 3, gravity = Gravity.CENTER)
    private val hint: LinearLayout
    private val done: AText
    private var lines: List<Lyrics.Line> = emptyList()
    private var views: List<AText> = emptyList()
    private var synced = false
    private var current = -1
    private var touchedAt = 0L
    private var glide: ValueAnimator? = null
    var onLineTap: ((Lyrics.Line) -> Unit)? = null

    /** Unknown position: no line lit, the hint shown (Bluetooth: until a line is tapped or the next song starts). */
    var needSync = false
        set(v) {
            if (v == field) return
            field = v
            showHint()
            if (v) { for (t in views) t.animate().alpha(IDLE).scaleX(1f).scaleY(1f).setDuration(200).start(); current = -1 }
        }

    init {
        scroll.addView(list, LayoutParams(MATCH, WRAP))
        addView(scroll, flp(MATCH, MATCH))
        addView(status, flp(MATCH, WRAP, Gravity.CENTER).apply { setMargins(28.dp, 0, 28.dp, 0) })
        scroll.setOnTouchListener { _, _ -> touchedAt = SystemClock.elapsedRealtime(); glide?.cancel(); false }

        hint = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(14.dp, 10.dp, 18.dp, 10.dp)
            background = Shapes.rect(Palette.withAlpha(Palette.card, if (Palette.dark) 0.9f else 0.95f), 22f, if (Palette.dark) 0x18FFFFFF else 0)
            elevation = 6f.dp
            visibility = View.GONE
            addView(c.iconView(R.drawable.ic_lyrics, 22, Palette.accent), lp(22.dp, 22.dp))
            addView(c.label(15f, Palette.text, Fonts.MEDIUM, lines = 2).apply { setText(R.string.lyrics_tap_sync) },
                lp(WRAP, WRAP).apply { marginStart = 10.dp })
        }
        addView(hint, flp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(18.dp, 14.dp, 18.dp, 0) })
        done = c.label(15f, Palette.onColor(Palette.accent), Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
            setText(R.string.lyrics_synced)
            background = Shapes.rect(Palette.accent, 20f)
            setPadding(18.dp, 9.dp, 18.dp, 9.dp)
            alpha = 0f
        }
        addView(done, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 18.dp })
    }

    /** Room above the first line and below the last, so that either can stand in the middle. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        list.setPadding(24.dp, (h * 0.38f).toInt(), 24.dp, (h * 0.5f).toInt())
    }

    /** A message instead of lyrics (looking, none, no internet...). */
    fun showStatus(text: String) {
        lines = emptyList(); views = emptyList(); current = -1
        list.removeAllViews()
        status.text = text
        if (status.visibility != View.VISIBLE) { status.visibility = View.VISIBLE; status.alpha = 0f; status.animate().alpha(1f).setDuration(200).start() }
        scroll.visibility = View.GONE
        showHint()
    }

    fun show(r: Lyrics.Result) {
        lines = r.lines
        synced = r.synced
        current = -1
        list.removeAllViews()
        views = lines.map { l ->
            context.label(if (synced) 26f else 22f, Palette.text, Fonts.MEDIUM, lines = 0, gravity = Gravity.CENTER).apply {
                text = l.text.ifBlank { "♪" }
                setPadding(10.dp, 10.dp, 10.dp, 10.dp)
                setLineSpacing(0f, 1.1f)
                alpha = if (synced) IDLE else 0.92f
                if (synced) {
                    background = Shapes.ghost(18f)
                    isClickable = true
                    setOnClickListener { onLineTap?.invoke(l) }
                }
            }
        }
        for (v in views) list.addView(v, lp(MATCH, WRAP).apply { topMargin = 2.dp })
        status.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        scroll.alpha = 0f
        scroll.animate().alpha(1f).setDuration(Motion.MEDIUM).start()
        scroll.scrollTo(0, 0)
        showHint()
    }

    /** Whether the hint is (or is becoming) shown: its visibility stays VISIBLE while it fades out. */
    private var hintShown = false

    private fun showHint() {
        val want = needSync && synced && views.isNotEmpty() && scroll.visibility == View.VISIBLE
        if (want == hintShown) return
        hintShown = want
        hint.animate().cancel()
        if (want) {
            hint.visibility = View.VISIBLE
            hint.alpha = 0f
            hint.translationY = (-10).dp.toFloat()
            hint.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(Motion.MEDIUM).setInterpolator(Motion.EMPHASIZED)
                .withEndAction(null).start()
        } else {
            hint.animate().alpha(0f).setStartDelay(0).setDuration(160).withEndAction { if (!hintShown) hint.visibility = View.GONE }.start()
        }
    }

    /** The owner set where the song is: a short "synced" note. */
    fun flashSynced() {
        done.animate().cancel()
        done.translationY = 8.dp.toFloat()
        // (a view's animator keeps its last start delay: each step sets its own)
        done.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200).setInterpolator(Motion.EMPHASIZED).withEndAction {
            done.animate().alpha(0f).setStartDelay(1100).setDuration(260).withEndAction(null).start()
        }.start()
    }

    /** The song is at [ms]: the line being sung, lit and brought to the middle (unless the owner is scrolling). */
    fun at(ms: Long) {
        if (!synced || lines.isEmpty() || needSync) return
        // the last line that has started (before the first one: none lit)
        var lo = 0
        var hi = lines.size - 1
        var i = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].ms <= ms) { i = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (i == current) return
        views.getOrNull(current)?.animate()?.alpha(DIM)?.scaleX(SMALL)?.scaleY(SMALL)?.setDuration(320)?.setInterpolator(Motion.STANDARD)?.start()
        if (current == -1) for ((k, t) in views.withIndex()) if (k != i) { t.alpha = DIM; t.scaleX = SMALL; t.scaleY = SMALL }
        current = i
        val v = views.getOrNull(i) ?: return
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Motion.STANDARD).start()
        if (SystemClock.elapsedRealtime() - touchedAt > FOLLOW_AFTER_TOUCH_MS) glideTo(v)
    }

    /** A slow glide (the platform's smooth scroll is short and abrupt for this). */
    private fun glideTo(v: View) {
        val to = (v.top + v.height / 2 - (scroll.height * 0.42f).toInt()).coerceAtLeast(0)
        val from = scroll.scrollY
        if (from == to) return
        glide?.cancel()
        glide = ValueAnimator.ofInt(from, to).apply {
            duration = 620
            interpolator = Motion.STANDARD
            addUpdateListener { scroll.scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        glide?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DIM = 0.34f       // lines around the one being sung
        const val IDLE = 0.62f      // all lines while none is lit (not started, position unknown)
        const val SMALL = 0.95f
        const val FOLLOW_AFTER_TOUCH_MS = 4000L
    }
}
