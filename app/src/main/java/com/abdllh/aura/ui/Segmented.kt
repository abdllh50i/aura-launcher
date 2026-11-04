package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.R
import kotlin.math.max
import kotlin.math.min

/**
 * Choices side by side on a track (`plate-18` in `fill`); the chosen one sits on the thumb (`seg-on-52`), which slides to a new
 * choice with its two edges on springs of their own, so it stretches a little on the way, like a drop of liquid. [equal]: all
 * choices as wide (tabs); otherwise each as wide as its label. Height 60, at most [maxW] wide (SPEC.md section 5).
 *
 * The chosen label is medium, the others regular, and a label is drawn in the thumb's text colour by how much of it the thumb
 * covers. Both faces of a label are there at rest and only their alpha moves: no layout and no text drawn again while the thumb
 * slides, and no jump of weight.
 */
class Segmented(
    ctx: Context,
    labels: List<String>,
    private val equal: Boolean,
    @Suppress("UNUSED_PARAMETER") textSp: Float = 0f,
    private val maxW: Int = 440,
    private val fixedH: Int = 60,
    private val onSelect: (Int) -> Unit
) : LinearLayout(ctx), Recolours {
    private inner class Item(ctx: Context, i: Int, s: String) : FrameLayout(ctx) {
        val off = ctx.label(Type.segment, Palette.ink, gravity = Gravity.CENTER)
        val on = ctx.label(Type.segment, Palette.onAccent, gravity = Gravity.CENTER)
        private val gesture = PressGesture(this, PressGesture.CONTROL)

        init {
            off.text = s
            on.text = s
            on.typeface = Fonts.get(500)
            on.alpha = 0f
            isClickable = true
            setPadding(10, 0, 10, 0)
            addView(off, flp(MATCH, MATCH))
            addView(on, flp(MATCH, MATCH))
            setOnClickListener { if (i != selected) { select(i); onSelect(i) } }
        }

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            gesture.onTouch(e)
            return super.dispatchTouchEvent(e)
        }
    }

    private val items: List<Item>
    private val track = Tint(ctx, R.drawable.plate_18) { Palette.fill }
    private val thumb: Drawable = ctx.getDrawable(R.drawable.seg_on_52)!!
    // the thumb's edges (View's own left/right are its position in the parent)
    private var edgeL = 0f
    private var edgeR = 0f
    private var placed = false
    private val leftSpring = Spring(0f, Tokens2.SPRING_segment[0], Tokens2.SPRING_segment[1], 0.5f) { edgeL = it; moved() }
    private val rightSpring = Spring(0f, Tokens2.SPRING_segment[0], Tokens2.SPRING_segment[1], 0.5f) { edgeR = it; moved() }
    var selected = 0
        private set

    /** Old screens set the thumb's colour here. The thumb is one bitmap in 2.0, the logo's blue. */
    var indicator = 0
    var onIndicator = 0
    var trackColor = 0

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
        background = track.drawable
        setPadding(PAD, PAD, PAD, PAD)
        items = labels.mapIndexed { i, s -> Item(ctx, i, s) }
        for ((i, v) in items.withIndex()) {
            addView(v, (if (equal) lp(0, MATCH, 1f) else lp(WRAP, MATCH)).apply { if (i > 0) marginStart = GAP })
        }
        tint()
    }

    /** The label of choice [i], in its plain face. */
    fun item(i: Int): AText = items[i].off

    fun select(i: Int, animate: Boolean = true) {
        selected = i.coerceIn(0, items.size - 1)
        val v = items[selected]
        if (v.width == 0) { placed = false; tint(); return } // placed when laid out
        val l = v.left.toFloat()
        val rt = v.right.toFloat()
        if (!animate || !placed || !isShown) {
            leftSpring.snapTo(l); rightSpring.snapTo(rt); placed = true
        } else {
            // the leading edge runs ahead, the trailing one follows
            val goingEnd = l > edgeL
            leftSpring.stiffness = if (goingEnd) 320f else 620f
            rightSpring.stiffness = if (goingEnd) 620f else 320f
            leftSpring.animateTo(l); rightSpring.animateTo(rt)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var w = widthMeasureSpec
        if (MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(w) > maxW) w = MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.getMode(w))
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(fixedH, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // (the thumb is in this view's own coordinates: moving the whole control changes nothing for it)
        val v = items[selected]
        val nl = v.left.toFloat()
        val nr = v.right.toFloat()
        when {
            !placed -> { leftSpring.snapTo(nl); rightSpring.snapTo(nr); placed = true }
            leftSpring.target == nl && rightSpring.target == nr -> {} // on its way there, or there
            leftSpring.isRunning || rightSpring.isRunning -> { leftSpring.animateTo(nl); rightSpring.animateTo(nr) }
            else -> { leftSpring.snapTo(nl); rightSpring.snapTo(nr) } // resized (another language, a label...)
        }
    }

    private fun moved() {
        tint()
        invalidate()
    }

    /** Each label's two faces by the share of it the thumb covers (before placing: the chosen one). */
    private fun tint() {
        for ((i, v) in items.withIndex()) {
            val cover = if (!placed || v.width == 0) (if (i == selected) 1f else 0f)
            else ((min(edgeR, v.right.toFloat()) - max(edgeL, v.left.toFloat())).coerceAtLeast(0f) / v.width)
            v.on.alpha = cover
            v.off.alpha = 1f - cover
        }
    }

    override fun onLook() {
        track.recolour()
        for (v in items) { v.off.setTextColor(Palette.ink); v.on.setTextColor(Palette.onAccent) }
        invalidate()
    }

    override fun dispatchDraw(c: Canvas) {
        if (placed && edgeR > edgeL) {
            thumb.setBounds(edgeL.toInt(), PAD, edgeR.toInt(), height - PAD)
            thumb.draw(c)
        }
        super.dispatchDraw(c)
    }

    override fun onDetachedFromWindow() {
        leftSpring.snapTo(leftSpring.target)
        rightSpring.snapTo(rightSpring.target)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val PAD = 4
        const val GAP = 4
    }
}
