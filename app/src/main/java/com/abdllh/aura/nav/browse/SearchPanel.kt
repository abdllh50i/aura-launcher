package com.abdllh.aura.nav.browse

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.TextField
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.ink
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.ui.scrollFade

/** One line of the search panel. [number] > 0: a result, with its number as on its pin; else one of his places, with [icon]. */
internal class ResultLine(val title: String, val sub: String, val distance: String?, val number: Int, val icon: Int, val onTap: () -> Unit)

/**
 * The search panel (SPEC.md section 6.3, simple/CHANGES.md section 5, `search-open`, `search`): a card at (16, 48), 364 wide, as high as what it holds. The field 348 x 52, three rows of 76, or what lies above the
 * keyboard: the panel's own keyboard is not in this version, the system's comes up and only shortens the list. More rows scroll under a baked fade, no saveLayer. The rows
 * are made once and filled again, so a letter typed costs no views, and a list changes in one layout. A tap on the map leaves it.
 */
internal class SearchPanel(private val ctx: Context) : SurfaceCard(ctx, Surface.CARD) {
    private val fieldBox = TextField(ctx, R.drawable.ic_search, ctx.getString(R.string.home_where_to))
    val input: EditText get() = fieldBox.input
    private val note: AText = ctx.label(Type.stateBody, { Palette.ink2 })
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = object : ScrollView(ctx) {
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            syncFade()
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = false
        addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
    }
    private val fade = ctx.scrollFade(Surface.CARD, top = false).apply { visibility = View.INVISIBLE }
    private val rows = ArrayList<Row>()
    private var shownRows = 0
    private var noteOn = false
    /** The list's most, in px: three rows, or what lies above the keyboard. */
    private var room = ROWS * ROW_H
    private var heightNow = 0

    init {
        addView(fieldBox, at(8, 8, W_INNER, TextField.HEIGHT))
        addView(scroll, at(8, LIST_TOP, W_INNER, ROW_H))
        addView(fade, at(8, LIST_TOP, W_INNER, FADE_H))
        list.addView(note, lp(MATCH, NOTE_H).apply { marginStart = 18; marginEnd = 18 })
        note.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
        note.alignStart = true
        note.visibility = View.GONE
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_FILTER
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        syncHeight()
    }

    /** What lies above the keyboard, in px of list. Part of a row is fine: it says there is more, under the fade. */
    fun setRoom(px: Int) {
        val r = px.coerceIn(ROW_H, ROWS * ROW_H)
        if (r == room) return
        room = r
        syncHeight()
    }

    /** The lines, in place of what was there; [noteText]: a line above them, when there is something to say. Each list starts at its top. */
    fun show(lines: List<ResultLine>, noteText: String? = null) {
        while (rows.size < lines.size) rows.add(Row(ctx).also { list.addView(it, lp(MATCH, ROW_H)) })
        for (i in rows.indices) {
            val r = rows[i]
            if (i < lines.size) {
                r.bind(lines[i], first = i == 0 && noteText == null)
                r.visibility = View.VISIBLE
            } else r.visibility = View.GONE
        }
        shownRows = lines.size
        noteOn = noteText != null
        note.text = noteText.orEmpty()
        note.visibility = if (noteOn) View.VISIBLE else View.GONE
        scroll.scrollTo(0, 0)
        syncHeight()
    }

    private fun syncHeight() {
        val content = shownRows * ROW_H + if (noteOn) NOTE_H else 0
        val listH = minOf(content, room)
        val h = LIST_TOP + listH + if (listH > 0) BOTTOM else 0
        (scroll.layoutParams as FrameLayout.LayoutParams).height = listH
        scroll.visibility = if (listH > 0) View.VISIBLE else View.GONE
        (fade.layoutParams as FrameLayout.LayoutParams).topMargin = LIST_TOP + listH - FADE_H
        if (h != heightNow) {
            heightNow = h
            layoutParams = Surface.CARD.at(16, 48, W, h)
        } else scroll.requestLayout()
        scroll.post { syncFade() }
    }

    private fun syncFade() {
        val more = scroll.canScrollVertically(1)
        val v = if (more) View.VISIBLE else View.INVISIBLE
        if (fade.visibility != v) fade.visibility = v
    }

    /** A row, 348 x 76: the disc at the reading edge, the title and the distance on one line, the address under them; a title alone stands in the row's middle. */
    private class Row(ctx: Context) : FrameLayout(ctx) {
        private val arabic = Fonts.arabicUi
        private val disc = Disc(ctx, 44, { Palette.accent })
        private val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        private val title: AText = ctx.label(Type.resultTitle, { Palette.ink })
        private val far: AText = ctx.label(Type.resultSub, { Palette.ink2 })
        private val sub: AText = ctx.label(Type.resultSub, { Palette.ink2 })
        private val hairline = View(ctx).look { setBackgroundColor(Palette.line) }
        private var tap: (() -> Unit)? = null

        init {
            val textX = if (arabic) 10 else 66
            addView(hairline, at(if (arabic) 10 else 60, 0, 278, 1))
            addView(disc, at(if (arabic) 348 - 10 - 44 else 10, 16, 44, 44))
            top.addView(title, lp(0, WRAP, 1f))
            top.addView(far, lp(WRAP, WRAP).apply { marginStart = 8 })
            addView(top, Type.resultTitle.box(textX, 3, 272).apply { height = WRAP })
            addView(sub, Type.resultSub.box(textX, 40, 272))
            pressScale(PressGesture.SURFACE)
            setOnClickListener { tap?.invoke() }
        }

        fun bind(l: ResultLine, first: Boolean) {
            tap = l.onTap
            hairline.visibility = if (first) View.INVISIBLE else View.VISIBLE
            if (l.number > 0) disc.number(l.number) else disc.glyph(l.icon, 26)
            title.text = android.text.BidiFormatter.getInstance(arabic).unicodeWrap(l.title)
            far.text = l.distance.orEmpty()
            far.visibility = if (l.distance == null) View.GONE else View.VISIBLE
            sub.text = l.sub
            sub.visibility = if (l.sub.isEmpty()) View.GONE else View.VISIBLE
            top.translationY = if (l.sub.isEmpty()) ALONE_DOWN else 0f
        }
    }

    private companion object {
        const val W = 364
        const val W_INNER = 348
        const val ROW_H = 76
        const val ROWS = 3
        const val LIST_TOP = 68
        const val BOTTOM = 8
        const val NOTE_H = 48
        const val FADE_H = 28
        /** A title with nothing under it, from its line at y 3 to the row's middle. */
        const val ALONE_DOWN = 18f
    }
}
