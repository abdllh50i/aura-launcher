package com.abdllh.aura.nav.browse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.glyph
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.plateView
import com.abdllh.aura.ui.pressScale

/**
 * A round plate with a glyph or a number on it, drawn: a search row's number, or the glyph of one of his places. One view. Under radius 12 or drawn live, a plain circle
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
    private var number: String? = null

    fun glyph(res: Int, px: Int) {
        glyph = context.getDrawable(res)!!.mutate().also { it.setTint(Palette.onAccent); it.layoutDirection = LAYOUT_DIRECTION_LTR }
        glyphPx = px
        number = null
        invalidate()
    }

    fun number(n: Int) {
        number = n.toString()
        glyph = null
        invalidate()
    }

    override fun onLook() {
        glyph?.setTint(Palette.onAccent)
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

/** No fix for a while on browse (SPEC.md section 12, `empty-no-gps`): under the search pill, 364 x 92. It clears itself. */
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

/**
 * Browse's one search pill over the map (simple/CHANGES.md section 5, `map-browse`): 364 x 52 at (16, 48). Moving, its glyph is the mic. His places are the search panel's first rows now, not chips.
 * Comes in by alpha, and goes at once: a card that takes its place is on its way in the same frame.
 */
internal class BrowseGroup(ctx: Context, layer: FrameLayout) {
    // (its alpha goes straight into what it draws: no layer for 140 ms of fade)
    private val pill = object : SurfaceCard(ctx, Surface.PILL) { override fun hasOverlappingRendering() = false }
    private val pillGlyph = ImageView(ctx).apply { setImageResource(R.drawable.ic_search); scaleType = ImageView.ScaleType.FIT_CENTER }.glyph { Palette.ink2 }
    private val pillText: AText = ctx.label(Type.search, { Palette.ink2 })
    private var moving = false
    private var shown = true

    var onPill: (() -> Unit)? = null

    init {
        val arabic = Fonts.arabicUi
        layer.addView(pill, Surface.PILL.at(16, 48, W, 52))
        pill.addView(pillGlyph, at(if (arabic) W - 16 - 26 else 16, 13, 26, 26))
        pill.addView(pillText, Type.search.box(if (arabic) 16 else 52, 10, W - 16 - 26 - 10 - 16))
        pill.pressScale(PressGesture.SURFACE)
        pill.setOnClickListener { onPill?.invoke() }
    }

    /** The field's words: where to, or what Home or Work being chosen asks for. */
    fun hint(text: String) { if (pillText.text != text) pillText.text = text }

    /** Moving ([com.abdllh.aura.system.DriveState]): the mic. */
    fun setMoving(on: Boolean) {
        if (on == moving) return
        moving = on
        pillGlyph.setImageResource(if (on) R.drawable.ic_mic else R.drawable.ic_search)
    }

    fun show(on: Boolean) {
        if (on == shown) return
        shown = on
        pill.animate().cancel()
        if (on && pill.isAttachedToWindow) {
            pill.alpha = 0f
            pill.visibility = View.VISIBLE
            pill.anim(Tokens2.MS_mapToolsIn, Motion.MOVE).alpha(1f).start()
        } else {
            pill.alpha = 1f
            pill.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
    }

    private companion object {
        const val W = 364
    }
}
