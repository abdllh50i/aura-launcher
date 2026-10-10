package com.abdllh.aura.music

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp

/**
 * The song's lyrics on the music screen. Timed lyrics: the line being sung is lit and brought to the middle, the
 * others dimmed (a few seconds after the owner scrolls by hand, it follows the song again). Untimed: just the text.
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
    private var lines: List<Lyrics.Line> = emptyList()
    private var views: List<AText> = emptyList()
    private var synced = false
    private var current = -1
    private var touchedAt = 0L

    init {
        scroll.addView(list, LayoutParams(MATCH, WRAP))
        addView(scroll, flp(MATCH, MATCH))
        addView(status, flp(MATCH, WRAP, Gravity.CENTER).apply { setMargins(28.dp, 0, 28.dp, 0) })
        scroll.setOnTouchListener { _, _ -> touchedAt = SystemClock.elapsedRealtime(); false }
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
        status.visibility = View.VISIBLE
        scroll.visibility = View.GONE
    }

    fun show(r: Lyrics.Result) {
        lines = r.lines
        synced = r.synced
        current = -1
        list.removeAllViews()
        views = lines.map { l ->
            context.label(if (synced) 25f else 22f, Palette.text, Fonts.MEDIUM, lines = 0, gravity = Gravity.CENTER).apply {
                text = l.text.ifBlank { "♪" }
                setPadding(0, 9.dp, 0, 9.dp)
                setLineSpacing(0f, 1.1f)
                alpha = if (synced) DIM else 0.92f
            }
        }
        for (v in views) list.addView(v, lp(MATCH, WRAP))
        status.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        scroll.scrollTo(0, 0)
    }

    /** The song is at [ms]: the line being sung, lit and brought to the middle (unless the owner is scrolling). */
    fun at(ms: Long) {
        if (!synced || lines.isEmpty()) return
        // the last line that has started (before the first one: none lit)
        var lo = 0
        var hi = lines.size - 1
        var i = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].ms <= ms) { i = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (i == current) return
        views.getOrNull(current)?.animate()?.alpha(DIM)?.setDuration(250)?.start()
        current = i
        val v = views.getOrNull(i) ?: return
        v.animate().alpha(1f).setDuration(250).start() // (lit, not enlarged: a long line would lose its ends)
        if (SystemClock.elapsedRealtime() - touchedAt > FOLLOW_AFTER_TOUCH_MS) {
            scroll.smoothScrollTo(0, (v.top + v.height / 2 - (scroll.height * 0.42f).toInt()).coerceAtLeast(0))
        }
    }

    private companion object {
        const val DIM = 0.34f
        const val FOLLOW_AFTER_TOUCH_MS = 4000L
    }
}
