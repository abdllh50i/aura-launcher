package com.abdllh.aura.nav.browse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.glyph
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.plateView
import com.abdllh.aura.ui.pressScale
import kotlin.math.ceil

/**
 * A round plate with a glyph or a number on it, drawn: a place chip's blue disc, a search row's number. One view. Under radius 12 or drawn live, a plain circle
 * (SPEC.md section 2).
 */
internal class Disc(ctx: Context, private val size: Int, private val fill: () -> Int) : View(ctx), Recolours {
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = Type.resultSub.sp
        typeface = Fonts.get(600)
        Fonts.tune(this)
    }
    private var glyph: Drawable? = null
    private var glyphPx = 0
    private var glyphToken: () -> Int = { Palette.onAccent }
    private var number: String? = null

    fun glyph(res: Int, px: Int, token: () -> Int = { Palette.onAccent }) {
        glyph = context.getDrawable(res)!!.mutate().also { it.setTint(token()); it.layoutDirection = LAYOUT_DIRECTION_LTR }
        glyphPx = px
        glyphToken = token
        number = null
        invalidate()
    }

    fun number(n: Int) {
        number = n.toString()
        glyph = null
        invalidate()
    }

    override fun onLook() {
        glyph?.setTint(glyphToken())
        invalidate()
    }

    override fun hasOverlappingRendering() = false

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(size, size)

    override fun onDraw(c: Canvas) {
        val mid = size / 2f
        plate.color = fill()
        c.drawCircle(mid, mid, mid, plate)
        glyph?.let {
            val l = (size - glyphPx) / 2
            it.setBounds(l, l, l + glyphPx, l + glyphPx)
            it.draw(c)
        }
        number?.let {
            digits.color = Palette.onAccent
            c.drawText(it, mid, mid + 0.335f * digits.textSize, digits)
        }
    }
}

/** The surface of a search pill and a chip: small, and its alpha goes straight into what it draws (no layer for 140 ms of fade). */
private open class Pill(ctx: Context) : SurfaceCard(ctx, Surface.PILL) {
    override fun hasOverlappingRendering() = false
}

/** No fix for a while on browse (SPEC.md section 12, `empty-no-gps`): under the chips, 364 x 92. It clears itself. */
internal class NoGpsCard(ctx: Context) : SurfaceCard(ctx, Surface.CARD) {
    private val line: AText = ctx.label(Type.resultSub, { Palette.ink2 })

    init {
        val arabic = Fonts.arabicUi
        addView(ctx.plateView(18) { Palette.fill2 }, at(if (arabic) W - 16 - 52 else 16, 20, 52, 52))
        addView(ctx.iconView(R.drawable.ic_gps_off, 28) { Palette.ink }, at(if (arabic) W - 28 - 28 else 28, 32, 28, 28))
        val x = if (arabic) 16 else 80
        addView(ctx.label(Type.resultTitle, { Palette.ink }).apply { setText(R.string.browse_no_gps) }, Type.resultTitle.box(x, 12, W - 96))
        addView(line, Type.resultSub.box(x, 48, W - 96))
    }

    fun sub(text: String) { if (line.text != text) line.text = text }

    private companion object {
        const val W = 364
    }
}

/** One place chip's words. */
internal class ChipInfo(val icon: Int, val text: String, val onTap: () -> Unit)

/**
 * Browse's search pill and place chips over the map (SPEC.md section 6.3, `map-browse`, section 5's table): x 12..376, y 44..156. Moving, the pill's glyph is the mic and the chips
 * have no disc. Comes in by alpha, and goes at once: a card that takes their place is on its way in the same frame.
 */
internal class BrowseGroup(private val ctx: Context, private val layer: FrameLayout) {
    private val arabic = Fonts.arabicUi
    private val pill = Pill(ctx)
    private val pillGlyph = ImageView(ctx).apply { setImageResource(R.drawable.ic_search); scaleType = ImageView.ScaleType.FIT_CENTER }.glyph { Palette.ink2 }
    private val pillText: AText = ctx.label(Type.search, { Palette.ink2 })
    private val chips = ArrayList<Chip>()
    private var moving = false
    private var shown = true
    private var infos: List<ChipInfo> = emptyList()

    var onPill: (() -> Unit)? = null

    init {
        layer.addView(pill, Surface.PILL.at(X, 44, W, 52))
        pill.addView(pillGlyph, at(if (arabic) W - 16 - 26 else 16, 13, 26, 26))
        pill.addView(pillText, Type.search.box(if (arabic) 16 else 52, 10, W - 16 - 26 - 10 - 16))
        pill.pressScale(PressGesture.SURFACE)
        pill.setOnClickListener { onPill?.invoke() }
    }

    /** The field's words: where to, or what Home or Work being chosen asks for. */
    fun hint(text: String) { if (pillText.text != text) pillText.text = text }

    /** Moving ([com.abdllh.aura.system.DriveState]): the mic, and no discs. */
    fun setMoving(on: Boolean) {
        if (on == moving) return
        moving = on
        pillGlyph.setImageResource(if (on) R.drawable.ic_mic else R.drawable.ic_search)
        placeChips()
    }

    fun setChips(list: List<ChipInfo>) {
        if (list.size == infos.size && list.indices.all { list[it].text == infos[it].text && list[it].icon == infos[it].icon }) {
            infos = list // (their taps may hold another place of the same name)
            return
        }
        infos = list
        placeChips()
    }

    /** Flex in 364 with a gap of 6: each its content, what is left shared alike. A text at most 150; too much in all, and the last gives way (section 3: Home and Work never shrink first). */
    private fun placeChips() {
        while (chips.size < infos.size) chips.add(Chip(ctx).also { layer.addView(it, Surface.PILL.at(X, CHIP_Y, 100, CHIP_H)) })
        for (i in chips.indices) chips[i].visibility = if (i < infos.size && shown) View.VISIBLE else View.GONE
        if (infos.isEmpty()) return
        val widths = IntArray(infos.size) { i -> chips[i].bind(infos[i], moving) }
        val room = W - CHIP_GAP * (infos.size - 1)
        val sum = widths.sum()
        if (sum > room) widths[widths.lastIndex] = (widths.last() - (sum - room)).coerceAtLeast(CHIP_MIN)
        val extra = ((room - widths.sum()) / infos.size).coerceAtLeast(0)
        var at = 0
        for (i in infos.indices) {
            // (the last takes what rounding leaves, so the row ends exactly at the column's edge)
            val w = if (i == infos.lastIndex) W - at else widths[i] + extra
            val x = if (arabic) X + W - at - w else X + at
            chips[i].layoutParams = Surface.PILL.at(x, CHIP_Y, w, CHIP_H)
            at += w + CHIP_GAP
        }
    }

    /** [animate] false: at once. */
    fun show(on: Boolean, animate: Boolean = true) {
        if (on == shown) return
        shown = on
        val views = listOf<View>(pill) + chips.take(infos.size)
        for (v in views) {
            v.animate().cancel()
            if (on && animate && v.isAttachedToWindow) {
                v.alpha = 0f
                v.visibility = View.VISIBLE
                v.anim(Tokens2.MS_mapToolsIn, Motion.MOVE).alpha(1f).start()
            } else {
                v.alpha = 1f
                v.visibility = if (on) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    private class Chip(ctx: Context) : Pill(ctx) {
        private val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        private val disc = Disc(ctx, DISC, { Palette.accent })
        private val text: AText = ctx.label(Type.chip, { Palette.ink }).apply { maxWidth = TEXT_MAX }
        private var tap: (() -> Unit)? = null

        init {
            row.addView(disc, lp(DISC, DISC).apply { marginEnd = DISC_GAP })
            row.addView(text, lp(WRAP, Type.chip.viewH))
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            pressScale(PressGesture.CONTROL)
            setOnClickListener { tap?.invoke() }
        }

        /** Its words; what it wants of the row's width. */
        fun bind(i: ChipInfo, moving: Boolean): Int {
            tap = i.onTap
            disc.glyph(i.icon, 18)
            disc.visibility = if (moving) View.GONE else View.VISIBLE
            text.text = i.text
            val words = ceil(text.paint.measureText(i.text)).toInt().coerceAtMost(TEXT_MAX)
            return words + PAD + if (moving) 0 else DISC + DISC_GAP
        }

        private companion object {
            const val DISC = 26
            const val DISC_GAP = 5
            const val TEXT_MAX = 150
            const val PAD = 8
        }
    }

    private companion object {
        const val X = 12
        const val W = 364
        const val CHIP_Y = 104
        const val CHIP_H = 52
        const val CHIP_GAP = 6
        const val CHIP_MIN = 72
    }
}
