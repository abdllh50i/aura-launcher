package com.abdllh.aura.settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Modal
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.icon
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.dp

/**
 * Row of colour dots; the chosen one has a ring round it and a tick on it. The colour keeps its size whichever is
 * chosen (the ring is drawn in the margin round the dot, not inside it, where it made the chosen colour look smaller
 * and heavier), and every dot is a [Touch.MIN] target. A pick changes the two dots concerned, nothing is rebuilt.
 */
class Swatches(ctx: Context, private val colors: IntArray, selected: Int, private val onPick: (Int) -> Unit) : LinearLayout(ctx) {
    private var sel = selected
    private val dots = ArrayList<FrameLayout>()
    private val ticks = ArrayList<ImageView>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for ((i, c) in colors.withIndex()) {
            val tick = ImageView(ctx).apply { icon(R.drawable.ic_check, Palette.onColor(c)) }
            val dot = FrameLayout(ctx).apply {
                isClickable = true
                contentDescription = String.format("#%06X", c and 0xFFFFFF)
                pressScale(Motion.PRESS_ICON)
                addView(tick, flp(22.dp, 22.dp, Gravity.CENTER))
                setOnClickListener { pick(c) }
            }
            dots.add(dot)
            ticks.add(tick)
            addView(dot, lp(Touch.MIN.dp, Touch.MIN.dp).apply { if (i > 0) marginStart = Space.S.dp })
            paint(i, animate = false)
        }
    }

    private fun pick(c: Int) {
        if (c == sel) return
        val was = colors.indexOf(sel)
        sel = c
        if (was >= 0) paint(was, animate = false)
        paint(colors.indexOf(c), animate = true)
        onPick(c)
    }

    private fun paint(i: Int, animate: Boolean) {
        val c = colors[i]
        val on = c == sel
        val inset = 5.dp
        val fill = InsetDrawable(Shapes.oval(c), inset)
        dots[i].background = if (on) LayerDrawable(arrayOf(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke((2.5f * resources.displayMetrics.density).toInt().coerceAtLeast(2), Palette.text)
        }, fill)) else fill
        val t = ticks[i]
        if (!on) { t.animate().cancel(); t.visibility = View.INVISIBLE }
        else if (animate) t.enter(scaleFrom = 0.5f)
        else { t.alpha = 1f; t.visibility = View.VISIBLE }
    }
}

/**
 * Indeterminate spinner: a rotating accent arc. It turns only while it can be seen: an animator does not stop for a
 * stopped activity on Android 10, so a check left running behind Maps went on waking the main thread 60 times a second.
 */
class Spinner(ctx: Context, private val sizeDp: Int = 28) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    private var a = 0f
    private var anim: ValueAnimator? = null

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(sizeDp.dp, sizeDp.dp)

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) { anim?.cancel(); anim = null; return }
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { a = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val s = 3.dp.toFloat()
        p.strokeWidth = s
        r.set(s, s, width - s, height - s)
        p.color = Palette.track
        c.drawArc(r, 0f, 360f, false, p)
        p.color = Palette.accent
        c.drawArc(r, a, 100f, false, p)
    }
}

/**
 * Determinate/indeterminate progress bar, filled from the start side (the right in Arabic). A new value glides there
 * on a spring, which keeps its speed when the next value comes before it arrived (a download reports every 400 ms);
 * a bar made for a value starts at it, not at empty. Nothing runs at rest: the spring stops where it arrives, and the
 * indeterminate sweep turns only while it is to be seen.
 */
class ProgressBar(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var indeterminate = false
    private var seen = false
    private var phase = 0f
    private var sweep: ValueAnimator? = null
    private val glide = Spring(0f, 120f, 1f, 0.0005f) { invalidate() }

    fun set(fraction: Float) {
        indeterminate = false
        sweeping()
        val f = fraction.coerceIn(0f, 1f)
        if (seen && isAttachedToWindow) glide.animateTo(f) else glide.snapTo(f)
        invalidate()
    }

    fun setIndeterminate() {
        indeterminate = true
        sweeping()
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        seen = isVisible
        if (!isVisible) glide.snapTo(glide.target)
        sweeping()
    }

    private fun sweeping() {
        val want = indeterminate && seen
        if (!want) { sweep?.cancel(); sweep = null; return }
        if (sweep != null) return
        sweep = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(getDefaultSize(0, w), Space.S.dp)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        r.set(0f, 0f, w, h)
        p.color = Palette.track
        c.drawRoundRect(r, h / 2, h / 2, p)
        p.color = Palette.accent
        if (indeterminate) {
            val bw = w * 0.32f
            val x = -bw + (w + bw) * phase
            val from = maxOf(0f, x)
            val to = minOf(w, x + bw)
            if (to <= from) return
            if (rtl) r.set(w - to, 0f, w - from, h) else r.set(from, 0f, to, h)
        } else {
            val shown = glide.value
            if (shown <= 0f) return
            val len = maxOf(h, w * shown)
            if (rtl) r.set(w - len, 0f, w, h) else r.set(0f, 0f, len, h)
        }
        c.drawRoundRect(r, h / 2, h / 2, p)
    }
}

/** Small modal text-input card (addresses, the update repository): a [Modal], so it comes and goes like the others. */
object InputDialog {
    fun show(ctx: Context, title: String, hint: String, initial: String, onOk: (String) -> Unit) {
        val card = Modal.card(ctx)
        val m = Modal(ctx, card)
        m.dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        card.addView(ctx.label(Type.TITLE, Palette.text).apply { text = title })
        val input = EditText(ctx).apply {
            setText(initial)
            setSelection(initial.length)
            this.hint = hint
            setHintTextColor(Palette.text3)
            setTextColor(Palette.text)
            textSize = Type.BODY.sp
            typeface = Fonts.get(Type.BODY.weight)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            isSingleLine = true
            setPadding(Space.L.dp, 0, Space.L.dp, 0)
            background = Shapes.rect(Palette.card2, Radius.M, Palette.stroke)
        }
        card.addView(input, lp(MATCH, Touch.MIN.dp).apply { topMargin = Space.L.dp })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(ABtn(ctx).apply { kind = ABtn.Kind.GHOST; setText(R.string.btn_cancel); setOnClickListener { m.close() } }, lp(WRAP, WRAP))
        row.addView(ABtn(ctx).apply {
            kind = ABtn.Kind.PRIMARY
            setText(R.string.btn_save)
            // (the value is taken now; the card goes meanwhile)
            setOnClickListener { val v = input.text.toString().trim(); m.close(); onOk(v) }
        }, lp(WRAP, WRAP).apply { marginStart = Space.M.dp })
        card.addView(row, lp(MATCH, WRAP).apply { topMargin = Space.XL.dp })
        m.show()
        input.requestFocus()
    }
}
