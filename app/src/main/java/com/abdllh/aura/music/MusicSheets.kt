package com.abdllh.aura.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.text.BidiFormatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.EqGlyph
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.Look
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Sheet
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.glyph
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.plateView
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.ui.scrollFade

/**
 * One row of a song (SPEC.md section 5, "Song row"), for the library and the queue: 76 high, cover 56 radius 12, `resultTitle` over `resultSub`, the length or the still `eq` at the far
 * end, the playing one on `selectedSoft` with its edge. A group's row has a glyph on a plate in the cover's place. Recycled by its list: [bind] catches a theme that changed while it was off
 * the list, since a theme's walk only reaches the rows in the tree.
 */
internal class SongRowView(ctx: Context) : LinearLayout(ctx) {
    private val box = FrameLayout(ctx)
    private val plate = ctx.plateView(Tokens2.R_small) { Palette.fill2 }
    private val glyphView = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }.glyph { Palette.ink2 }
    private val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val t1: AText = ctx.label(Type.resultTitle, { Palette.ink })
    private val t2: AText = ctx.label(Type.resultSub, { Palette.ink2 })
    private val dur: AText = ctx.label(Type.resultSub, { Palette.ink2 })
    private val eq = EqGlyph(ctx)
    private var onBg: LayerDrawable? = null
    private var onFill: Tint? = null
    private var onEdge: Tint? = null
    private var seenDark = Palette.dark
    private var now = false
    private var pictureKey: Any? = null
    private var shownGlyph = 0

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(10, 0, 10, 0)
        isClickable = true
        pressScale(PressGesture.SURFACE)
        layoutParams = AbsListView.LayoutParams(MATCH, HEIGHT)
        box.addView(plate, flp(MATCH, MATCH))
        box.addView(glyphView, flp(28, 28, Gravity.CENTER))
        box.addView(img, flp(MATCH, MATCH))
        addView(box, lp(COVER, COVER))
        val col = FrameLayout(ctx)
        col.addView(t1, flp(MATCH, Type.resultTitle.viewH).apply { topMargin = 0 })
        col.addView(t2, flp(MATCH, Type.resultSub.viewH).apply { topMargin = 38 })
        addView(col, lp(0, HEIGHT, 1f).apply { marginStart = 12; marginEnd = 12 })
        eq.look { color = Palette.ink2 }
        addView(eq, lp(28, 28))
        addView(dur, lp(WRAP, Type.resultSub.viewH))
    }

    private fun catchUp() {
        if (seenDark == Palette.dark) return
        seenDark = Palette.dark
        Look.apply(this)
        onFill?.recolour()
        onEdge?.recolour()
    }

    /** A song: [title], [sub], its length, or the still glyph when it is the one playing. */
    fun bind(title: CharSequence, sub: CharSequence, length: CharSequence, playing: Boolean) {
        catchUp()
        if (t1.text.toString() != title.toString()) t1.text = title
        if (t2.text.toString() != sub.toString()) t2.text = sub
        if (dur.text.toString() != length.toString()) dur.text = length
        dur.visibility = if (playing) GONE else VISIBLE
        eq.visibility = if (playing) VISIBLE else GONE
        if (playing != now) {
            now = playing
            background = if (playing) onBackground() else null
        }
    }

    private fun onBackground(): LayerDrawable = onBg ?: run {
        val f = Tint(context, R.drawable.plate_18) { Palette.selectedSoft }
        val e = Tint(context, R.drawable.ring_18) { Palette.selectedSoftEdge }
        onFill = f
        onEdge = e
        LayerDrawable(arrayOf(f.drawable, e.drawable)).also { onBg = it }
    }

    /** A group's row: a glyph on its plate, no picture. */
    fun bindGroup(icon: Int, title: CharSequence, sub: CharSequence) {
        catchUp()
        if (icon != shownGlyph) { shownGlyph = icon; glyphView.setImageResource(icon) }
        pictureKey = null
        img.setImageDrawable(null)
        t1.text = title
        t2.text = sub
        dur.visibility = GONE
        eq.visibility = GONE
        if (now) { now = false; background = null }
    }

    /** The picture, for [key]: at once when it is at hand, faded in when it comes later. [load] gets a callback. Nothing to do for the same one. */
    fun picture(key: Any?, showing: Boolean, load: ((Bitmap?) -> Unit) -> Unit) {
        if (shownGlyph != R.drawable.ic_music) { shownGlyph = R.drawable.ic_music; glyphView.setImageResource(R.drawable.ic_music) }
        if (key == pictureKey && img.drawable != null) return
        pictureKey = key
        img.animate().cancel()
        img.alpha = 1f
        img.setImageDrawable(null)
        // (out of sight no row holds a picture, they are all bound again when it comes back: it only gets to the cache)
        if (!showing) { load { }; return }
        var sync = true
        load { b ->
            if (b != null && pictureKey == key) {
                img.setImageBitmap(b)
                if (!sync) { img.alpha = 0f; img.fadeTo(1f) }
            }
        }
        sync = false
    }

    fun dropPicture() {
        img.animate().cancel()
        img.setImageDrawable(null)
        pictureKey = null
    }

    companion object {
        const val HEIGHT = 76
        const val COVER = 56
    }
}

/** The four places music comes from, as the sheet and the empty panel list them (SPEC.md section 12), in the panel's words. */
internal enum class SourceRow(val icon: Int) {
    NAGHM(R.drawable.ic_iphone), BT(R.drawable.ic_bluetooth), USB(R.drawable.ic_usb), YT(R.drawable.ic_yt)
}

/** One place to listen from: 84 high, plate 52 with its glyph, `resultTitle` over `sourceSub`, at the far end a check if chosen, or [chevron]. A hairline above all but the first. */
internal class SourceRowView(ctx: Context, val row: SourceRow, private val chevron: Boolean, first: Boolean, onTap: (SourceRow) -> Unit) : FrameLayout(ctx), Recolours {
    private val title: AText = ctx.label(Type.resultTitle, { Palette.ink })
    private val sub: AText = ctx.label(Type.sourceSub, { Palette.ink2 })
    private val on = ctx.plateView(Tokens2.R_inner) { Palette.selectedSoft }
    private val far: ImageView = if (chevron) ctx.iconView(R.drawable.ic_chev_right, 24) { Palette.ink3 } else ctx.iconView(R.drawable.ic_check, 26) { Palette.accentInk }

    init {
        isClickable = true
        pressScale(PressGesture.SURFACE)
        setOnClickListener { onTap(row) }
        on.visibility = INVISIBLE
        addView(on, flp(MATCH, MATCH))
        if (!first) addView(object : View(ctx) { override fun hasOverlappingRendering() = false }.look { setBackgroundColor(Palette.line) }, flp(MATCH, 1, Gravity.TOP).apply { setMargins(14, 0, 14, 0) })
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(14, 0, 14, 0)
        }
        val box = FrameLayout(ctx)
        box.addView(ctx.plateView(Tokens2.R_mid) { Palette.fill2 }, flp(MATCH, MATCH))
        box.addView(ctx.iconView(row.icon, 28) { Palette.ink }, flp(28, 28, Gravity.CENTER))
        inner.addView(box, lp(52, 52))
        val col = FrameLayout(ctx)
        col.addView(title, flp(MATCH, Type.resultTitle.viewH).apply { topMargin = 5 })
        col.addView(sub, flp(MATCH, Type.sourceSub.viewH).apply { topMargin = 41 })
        inner.addView(col, lp(0, HEIGHT, 1f).apply { marginStart = 14; marginEnd = 14 })
        if (!chevron) far.visibility = INVISIBLE
        inner.addView(far, lp(if (chevron) 24 else 26, if (chevron) 24 else 26))
        addView(inner, flp(MATCH, MATCH))
    }

    override fun onLook() {}

    fun set(titleText: CharSequence, subText: CharSequence, chosen: Boolean) {
        if (title.text.toString() != titleText.toString()) title.text = titleText
        if (sub.text.toString() != subText.toString()) sub.text = subText
        if (!chevron) {
            far.visibility = if (chosen) VISIBLE else INVISIBLE
            on.visibility = if (chosen) VISIBLE else INVISIBLE
        }
    }

    companion object {
        const val HEIGHT = 84
    }
}

/**
 * "From where": the source sheet (SPEC.md section 5, "Side sheet"; `music-sources`, `sources-on-trip`). A row is a choice, or what starts one: the panel knows ([MusicPanel.chooseRow]).
 * It opens from the panel's source tile, and from the mini's cover anywhere ([MusicPanel.openSources]).
 */
internal class SourceSheet(ctx: Context, private val music: MusicPanel) : Sheet(ctx, false, true) {
    private val rows: List<SourceRowView>

    init {
        sideHead(ctx.getString(R.string.music_sources_title))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 4, 8, 8) }
        rows = SourceRow.values().mapIndexed { i, r ->
            SourceRowView(ctx, r, chevron = false, first = i == 0) { music.chooseRow(it); close() }.also { col.addView(it, lp(MATCH, SourceRowView.HEIGHT)) }
        }
        panel.addView(col, lp(MATCH, WRAP))
    }

    override fun onOpen() = refresh()

    fun refresh() {
        for (r in rows) r.set(music.rowTitle(r.row, full = true), music.rowState(r.row), music.rowChosen(r.row))
    }
}

/**
 * "Up next": the queue sheet (`music-queue`, `queue-on-trip`). The unit's own player: its songs from the one playing on, shuffle and repeat in the head. A source whose list is on the phone or
 * in the app: the one song, and a line that says where the list is; no shuffle and repeat, which steer the unit's own player.
 */
internal class QueueSheet(ctx: Context, private val music: MusicPanel) : Sheet(ctx, false, true) {
    private val shuffle = IconButton(ctx, R.drawable.ic_shuffle, 28, 56, 56, token = { Palette.ink2 })
    private val repeat = IconButton(ctx, R.drawable.ic_repeat, 28, 56, 56, token = { Palette.ink2 })
    private val list = ListView(ctx)
    private val fade = ctx.scrollFade(Surface.OVERLAY, top = false)
    private val note: AText = ctx.label(Type.stateBody, { Palette.ink2 })
    private var items: List<Track> = emptyList()
    private val body = FrameLayout(ctx)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = if (music.queueIsLocal) items.size else 1
        override fun getItem(i: Int): Any = i
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? SongRowView) ?: SongRowView(context)
            if (music.queueIsLocal) {
                val t = items[i]
                row.bind(t.title, BidiFormatter.getInstance().unicodeWrap(t.artist), t.durationText, t.id == Player.current?.id)
                row.picture(t.albumId, true) { done -> ArtLoader.get(context, t, SongRowView.COVER, Tokens2.R_small, done) }
                row.setOnClickListener { music.playQueue(t) }
            } else {
                row.bind(music.songTitle, music.songArtist, "", true)
                val art = music.songArt
                row.picture(art, true) { done -> if (art != null) Backdrop.roundAsync(context, art, SongRowView.COVER, Tokens2.R_small) { done(it) } else done(null) }
                row.setOnClickListener { }
            }
            return row
        }
    }

    init {
        shuffle.setOnClickListener { Player.setShuffle(!Player.shuffle); paintTools() }
        repeat.setOnClickListener { Player.cycleRepeat(); paintTools() }
        shuffle.contentDescription = ctx.getString(R.string.music_shuffle)
        repeat.contentDescription = ctx.getString(R.string.music_repeat)
        // (repeat beside the close button, shuffle after it, as drawn)
        sideHead(ctx.getString(R.string.music_queue_title), repeat, shuffle)
        list.selector = ColorDrawable(0)
        list.isVerticalScrollBarEnabled = false
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.divider = null
        list.adapter = adapter
        body.setPadding(8, 4, 8, 8)
        body.addView(list, flp(MATCH, MATCH))
        body.addView(fade, flp(MATCH, 28, Gravity.BOTTOM))
        note.alignStart = true
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body, lp(MATCH, WRAP))
        col.addView(note, lp(MATCH, Type.stateBody.viewH).apply { setMargins(22, 0, 22, 14) })
        panel.addView(col, lp(MATCH, WRAP))
    }

    override fun onOpen() {
        val local = music.queueIsLocal
        items = if (local) Player.upcoming() else emptyList()
        adapter.notifyDataSetChanged()
        val shown = if (local) items.size.coerceIn(1, ROWS) else 1
        body.layoutParams = (body.layoutParams as LinearLayout.LayoutParams).apply { height = shown * SongRowView.HEIGHT + 12 }
        fade.visibility = if (local && items.size > ROWS) VISIBLE else GONE
        shuffle.visibility = if (local) VISIBLE else INVISIBLE
        repeat.visibility = if (local) VISIBLE else INVISIBLE
        note.visibility = if (local) GONE else VISIBLE
        note.text = if (local) "" else context.getString(music.queueElsewhere)
        paintTools()
        list.setSelection(0)
    }

    private fun paintTools() {
        shuffle.setGlyphToken(if (Player.shuffle) ({ Palette.accentInk }) else ({ Palette.ink2 }))
        repeat.setGlyphToken(if (Player.repeat != Player.Repeat.OFF) ({ Palette.accentInk }) else ({ Palette.ink2 }))
        repeat.setIcon(if (Player.repeat == Player.Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
    }

    /** The song changed while it is open. */
    fun refresh() {
        if (!isOpen) return
        items = if (music.queueIsLocal) Player.upcoming() else emptyList()
        adapter.notifyDataSetChanged()
    }

    private companion object {
        const val ROWS = 4
    }
}
