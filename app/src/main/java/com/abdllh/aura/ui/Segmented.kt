package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.widget.LinearLayout
import com.abdllh.aura.util.dp

/**
 * Choices side by side in a pill; the chosen one sits on a filled indicator that slides to a new choice (its two edges
 * on springs of their own, so it stretches a little on the way, like a drop of liquid). [equal]: all choices as wide
 * (tabs); otherwise each as wide as its label.
 */
class Segmented(
    ctx: Context,
    labels: List<String>,
    private val equal: Boolean,
    textSp: Float = 16f,
    private val onSelect: (Int) -> Unit
) : LinearLayout(ctx) {
    private val items: List<AText>
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    // the indicator's edges (View's own left/right are its position in the parent)
    private var edgeL = 0f
    private var edgeR = 0f
    private var placed = false
    private val leftSpring = Spring(0f, 520f, 0.82f, 0.5f) { edgeL = it; invalidate() }
    private val rightSpring = Spring(0f, 520f, 0.82f, 0.5f) { edgeR = it; invalidate() }
    var selected = 0
        private set
    var indicator = Palette.accent
        set(v) { if (v != field) { field = v; restyleText(); invalidate() } }
    /** Text on the indicator ([Palette.onColor] of it when 0). */
    var onIndicator = 0
        set(v) { field = v; restyleText() }
    var trackColor = 0
        set(v) { field = v; invalidate() }
    private val pad = 4.dp

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
        setPadding(pad, pad, pad, pad)
        items = labels.mapIndexed { i, s ->
            ctx.label(textSp, Palette.text2, Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
                text = s
                isClickable = true
                setPadding(18.dp, 0, 18.dp, 0)
                setOnClickListener { if (i != selected) { select(i); onSelect(i) } }
            }
        }
        for (v in items) addView(v, if (equal) lp(0, MATCH, 1f) else lp(WRAP, MATCH))
        restyleText()
    }

    fun select(i: Int, animate: Boolean = true) {
        selected = i.coerceIn(0, items.size - 1)
        restyleText()
        val v = items[selected]
        if (v.width == 0) { placed = false; requestLayout(); return } // placed when laid out
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

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // (the indicator is in this view's own coordinates: moving the whole control changes nothing for it)
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

    private fun restyleText() {
        val on = if (onIndicator != 0) onIndicator else Palette.onColor(indicator)
        for ((i, v) in items.withIndex()) v.setTextColor(if (i == selected) on else Palette.text2)
    }

    override fun dispatchDraw(c: Canvas) {
        val h = height.toFloat()
        if (trackColor != 0) {
            p.color = trackColor
            r.set(0f, 0f, width.toFloat(), h)
            c.drawRoundRect(r, h / 2, h / 2, p)
        }
        if (placed && edgeR > edgeL) {
            p.color = indicator
            r.set(edgeL, pad.toFloat(), edgeR, h - pad)
            val rr = (h - 2 * pad) / 2
            c.drawRoundRect(r, rr, rr, p)
        }
        super.dispatchDraw(c)
    }
}
