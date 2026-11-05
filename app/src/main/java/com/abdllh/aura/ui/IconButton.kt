package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import com.abdllh.aura.R

/**
 * The one icon button (SPEC.md section 5), one view with no children: a glyph of [glyphPx] centred in a hit area of [hitW] x
 * [hitH]. Mirrors by the icon's own autoMirrored, a call site never flips it.
 *  - [Kind.BARE]: the glyph alone.
 *  - [Kind.TONAL]: on a plate, `fill2` unless [plateToken] says otherwise (the mini's 56 plate is `fill`).
 *  - [Kind.PILL]: on the `pill` 9-patch; the view is the hit grown by the outset, and the hit is the visible rect ([Surface.at]).
 *  - [Kind.ACTIVE]: on the solid `selected` plate with its 1 px edge, glyph `selectedInk`.
 * A plate is [plateW] x [plateH] with visual radius [plateRadius], centred in the hit; [centerDx] and [centerDy] move glyph and plate off
 * the hit's middle (the volume's hits run to the screen's edge, their glyphs sit on the row's centre). [active] turns any of them
 * ACTIVE and back. [token] is the glyph's colour when not active: `icon` by default. Disabled: alpha 0.4, no press.
 */
class IconButton @JvmOverloads constructor(
    ctx: Context,
    icon: Int,
    private val glyphPx: Int,
    private val hitW: Int,
    private val hitH: Int,
    private val kind: Kind = Kind.BARE,
    press: Float = PressGesture.ICON,
    private val plateW: Int = 56,
    private val plateH: Int = 56,
    plateRadius: Int = Tokens2.R_inner,
    plateToken: () -> Int = { if (kind == Kind.TONAL) Palette.fill2 else Palette.fill },
    private val centerDy: Int = 0,
    private val centerDx: Int = 0,
    private var token: () -> Int = { Palette.icon }
) : View(ctx), Recolours {
    enum class Kind { BARE, TONAL, PILL, ACTIVE }

    private val outset = if (kind == Kind.PILL) Surface.PILL.outset else 0
    private val gesture = PressGesture(this, press)
    private var glyph: Drawable = load(icon)
    private val plate: Tint? = if (kind == Kind.TONAL) Tint(ctx, Plates.res(plateRadius), plateToken) else null
    private var selected: Tint? = null
    private var edge: Tint? = null

    /** Shows it selected: the solid plate, the white glyph. */
    var active = kind == Kind.ACTIVE
        set(v) {
            if (v == field) return
            field = v
            needSelected()
            tintGlyph()
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
        if (kind == Kind.PILL) background = SurfaceBackground(ctx, Surface.PILL)
        needSelected()
        tintGlyph()
    }

    private fun load(res: Int): Drawable = context.getDrawable(res)!!.mutate().also {
        it.callback = this
        it.layoutDirection = layoutDirection
    }

    private fun needSelected() {
        if (!active || selected != null) return
        selected = Tint(context, R.drawable.plate_18) { Palette.selected }
        edge = Tint(context, R.drawable.ring_18) { Palette.selectedEdge }
    }

    fun setIcon(res: Int) {
        glyph = load(res)
        tintGlyph()
        invalidate()
    }

    /** The glyph's colour changes token (Locate turns `accentInk` while the camera is free). */
    fun setGlyphToken(t: () -> Int) {
        token = t
        tintGlyph()
        invalidate()
    }

    private fun tintGlyph() = glyph.setTint(if (active) Palette.selectedInk else token())

    override fun onLook() {
        plate?.recolour()
        selected?.recolour()
        edge?.recolour()
        (background as? SurfaceBackground)?.recolour()
        tintGlyph()
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    override fun hasOverlappingRendering() = false

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(hitW + 2 * outset, hitH + 2 * outset)

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        glyph.layoutDirection = layoutDirection
        invalidate()
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === glyph || super.verifyDrawable(who)

    override fun onDraw(c: Canvas) {
        val cx = width / 2 + centerDx
        val cy = height / 2 + centerDy
        val p = plate
        if (p != null && !active) {
            p.drawable.setBounds(cx - plateW / 2, cy - plateH / 2, cx - plateW / 2 + plateW, cy - plateH / 2 + plateH)
            p.drawable.draw(c)
        }
        if (active) {
            val s = selected!!.drawable
            val e = edge!!.drawable
            s.setBounds(cx - SELECTED_W / 2, cy - SELECTED_H / 2, cx + SELECTED_W / 2, cy + SELECTED_H / 2)
            e.bounds = s.bounds
            s.draw(c)
            e.draw(c)
        }
        val half = glyphPx / 2
        glyph.setBounds(cx - half, cy - half, cx - half + glyphPx, cy - half + glyphPx)
        glyph.draw(c)
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (outset > 0 && e.actionMasked == MotionEvent.ACTION_DOWN &&
            (e.x < outset || e.y < outset || e.x >= width - outset || e.y >= height - outset)) return false
        gesture.onTouch(e)
        return super.dispatchTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = gesture.tolerant(e) { super.onTouchEvent(it) }

    override fun onDetachedFromWindow() {
        gesture.reset()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** The selected plate: section 1. */
        const val SELECTED_W = 64
        const val SELECTED_H = 60
    }
}
