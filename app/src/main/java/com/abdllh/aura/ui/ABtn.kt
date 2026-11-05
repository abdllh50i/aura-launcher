package com.abdllh.aura.ui


import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent

import com.abdllh.aura.R

/**
 * The app's button: every button is one of these, so a button looks and answers the same on every screen. [kind]: what it
 * does; [size]: its height, M for the buttons of a card or a dialog, L for the main action of a screen. Plates are the baked
 * bitmaps of SPEC.md section 5, one per height so a gradient never kinks: give it an exact or a wrapped width, never another
 * height. Optional icon 26 with a gap of 10, the group centred ([icon]); [loading]: a spinner 24 in the icon's place, the label
 * stays, not tappable. Disabled: alpha 0.4, no press.
 */
class ABtn @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AText(context, attrs), Recolours {

    /**
     * PRIMARY: the action (`btn-accent`). TONAL: a second choice beside it (`plate-20` in `fill2`). GHOST: cancel, dismiss (no
     * plate, `accentInk`). DANGER: a drastic choice offered beside others (`plate-18` in `redFill` with a 2 px ring). DANGER_FILLED:
     * the one drastic action of a screen (`btn-decline`). ANSWER: the call's Answer (`btn-answer-72`, [Size.CALL]). SELECTED: a tonal button that is on now, the call's Mute (the
     * solid `selected` plate with its 1 px edge).
     */
    enum class Kind { PRIMARY, TONAL, GHOST, DANGER, DANGER_FILLED, ANSWER, SELECTED }

    /** M 60, L 84; ARRIVAL 76 and CALL 72 are the two other heights there are bitmaps for. */
    enum class Size(@JvmField val height: Int) { M(60), L(84), ARRIVAL(76), CALL(72) }

    private val gesture = PressGesture(this, PressGesture.CONTROL)

    var kind: Kind = Kind.TONAL
        set(v) { field = v; restyle() }

    var size: Size = Size.M
        set(v) { field = v; restyle() }

    /** [Type.button], or [Type.go] on Go. */
    var role: Type.Role = Type.button
        set(v) { field = v; applyRole() }

    /** The room at each side of the group: 24, less on a banner's 138 wide buttons, whose icon and word would not fit with it. */
    var sidePad = SIDE
        set(v) { field = v; applySlot() }

    private var plate: Tint? = null
    private var ring: Tint? = null
    private var pressTint: Tint? = null

    private var iconDrawable: Drawable? = null
    private var iconPx = 26
    private val gap = 10
    private var spinnerAngle = 0f
    private var spinning = false
    // (by a delayed post, 30 turns of the wheel a second: the label is drawn again each time, and an animator asks for a frame right after
    // each frame, which on a slow frame leaves the main thread's other messages waiting behind the frames)
    private val spinTick = object : Runnable {
        override fun run() {
            spinnerAngle = (spinnerAngle + 12f) % 360f
            invalidate()
            if (spinning) postDelayed(this, SPIN_MS)
        }
    }
    private val spinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND }
    private val spinRect = RectF()

    /** Shows the spinner in the icon's place; the label stays, and it cannot be tapped meanwhile. */
    var loading = false
        set(v) {
            if (v == field) return
            field = v
            isClickable = !v
            applySlot()
            spinning()
            invalidate()
        }

    init {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        maxLines = 1
        applyRole()
        restyle()
    }

    private fun applyRole() {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, role.sp)
        typeface = Fonts.of(role)
        applySlot()
    }

    /** The icon's place, or none: symmetric padding, so a centred label has the room the group leaves it. */
    private fun applySlot() {
        val extra = if (iconDrawable != null || loading) (slotW() + gap) / 2 else 0
        val side = sidePad + extra
        setPaddingRelative(side, 0, side, 0)
    }

    private fun slotW() = if (loading) SPIN_PX else iconPx

    /** An icon at the label's start: [px] 26, or 32 on Go. 0: none. */
    fun icon(res: Int, px: Int = 26) {
        iconPx = px
        iconDrawable = if (res == 0) null else context.getDrawable(res)!!.mutate().also {
            it.layoutDirection = layoutDirection
            it.setTint(currentTextColor)
        }
        applySlot()
        invalidate()
    }

    fun restyle() {
        plate = null
        ring = null
        val bg: Drawable? = when (kind) {
            Kind.PRIMARY, Kind.DANGER_FILLED, Kind.ANSWER -> context.getDrawable(plateFor(kind, size))!!
            Kind.TONAL -> Tint(context, R.drawable.plate_20) { Palette.fill2 }.also { plate = it }.drawable
            Kind.GHOST -> null
            Kind.DANGER -> {
                val p = Tint(context, R.drawable.plate_18) { Palette.redFill }.also { plate = it }
                val r = Tint(context, R.drawable.ring_18_2) { Palette.redEdge }.also { ring = it }
                LayerDrawable(arrayOf(p.drawable, r.drawable))
            }
            Kind.SELECTED -> {
                val p = Tint(context, R.drawable.plate_18) { Palette.selected }.also { plate = it }
                val r = Tint(context, R.drawable.ring_18) { Palette.selectedEdge }.also { ring = it }
                LayerDrawable(arrayOf(p.drawable, r.drawable))
            }
        }
        background = bg
        val press = Tint(context, if (kind == Kind.DANGER || kind == Kind.SELECTED) R.drawable.plate_18 else R.drawable.plate_20) { Palette.fillPress }
        pressTint = press
        foreground = PressGesture.plate(press.drawable)
        applySlot()
        setTextColor(textToken())
        iconDrawable?.setTint(currentTextColor)
        requestLayout()
    }

    private fun textToken(): Int = when (kind) {
        Kind.PRIMARY, Kind.DANGER_FILLED, Kind.ANSWER -> Palette.onAccent
        Kind.TONAL -> Palette.ink
        Kind.GHOST -> Palette.accentInk
        Kind.DANGER -> Palette.redInk
        Kind.SELECTED -> Palette.selectedInk
    }

    /** Recoloured in place: no bitmap here differs by theme but its tint. */
    override fun onLook() {
        plate?.recolour()
        ring?.recolour()
        pressTint?.recolour()
        setTextColor(textToken())
        iconDrawable?.setTint(currentTextColor)
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    /** End is 58 high, a plate and a ring, which stretch: no gradient to kink. */
    fun setHeightOverride(px: Int) {
        heightOverride = px
        requestLayout()
    }

    private var heightOverride = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (heightOverride > 0) heightOverride else size.height
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        spinning()
    }

    override fun onDetachedFromWindow() {
        spinning = false
        removeCallbacks(spinTick)
        gesture.reset()
        super.onDetachedFromWindow()
    }

    private fun spinning() {
        val want = loading && isAttachedToWindow && isShown
        if (want == spinning) return
        spinning = want
        removeCallbacks(spinTick)
        if (want) postDelayed(spinTick, SPIN_MS)
    }

    override fun onDraw(c: Canvas) {
        val hasSlot = iconDrawable != null || loading
        if (!hasSlot) { super.onDraw(c); return }
        val slot = slotW()
        val shift = (slot + gap) / 2f
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val l = layout
        val textW = if (l != null && l.lineCount > 0) l.getLineWidth(0) else 0f
        val group = textW + slot + gap
        // the slot at the reading start of the group, the label after it; the label is centred by the TextView, so it is moved
        val slotLeft = if (rtl) (width + group) / 2f - slot else (width - group) / 2f
        val cy = height / 2f
        if (loading) {
            spinPaint.color = currentTextColor
            spinRect.set(slotLeft + 1.5f, cy - slot / 2f + 1.5f, slotLeft + slot - 1.5f, cy + slot / 2f - 1.5f)
            spinPaint.alpha = 64
            c.drawArc(spinRect, 0f, 360f, false, spinPaint)
            spinPaint.alpha = 255
            c.drawArc(spinRect, spinnerAngle, 100f, false, spinPaint)
        } else iconDrawable?.let { d ->
            val top = (height - slot) / 2
            d.setBounds(slotLeft.toInt(), top, slotLeft.toInt() + slot, top + slot)
            d.draw(c)
        }
        c.save()
        c.translate(if (rtl) -shift else shift, 0f)
        super.onDraw(c)
        c.restore()
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (!loading) gesture.onTouch(e)
        return super.dispatchTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = gesture.tolerant(e) { super.onTouchEvent(it) }

    private companion object {
        const val SIDE = 24
        const val SPIN_PX = 24
        const val SPIN_MS = 33L

        fun plateFor(kind: Kind, size: Size): Int = when (kind) {
            Kind.ANSWER -> R.drawable.btn_answer_72
            Kind.DANGER_FILLED -> when (size) {
                Size.L -> R.drawable.btn_decline_84
                Size.CALL -> R.drawable.btn_decline_72
                else -> R.drawable.btn_decline_60
            }
            else -> when (size) {
                Size.L -> R.drawable.btn_accent_84
                Size.ARRIVAL -> R.drawable.btn_accent_76
                else -> R.drawable.btn_accent_60
            }
        }
    }
}
