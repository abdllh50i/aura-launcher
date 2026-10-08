package com.abdllh.aura.settings

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.dp

/** Segmented control: a pill with N options, one selected. */
class Segmented(
    ctx: Context,
    private val options: List<String>,
    selected: Int,
    private val arabicIndex: Int = -1,
    private val onSelect: (Int) -> Unit
) : LinearLayout(ctx) {
    private var sel = selected
    private val cells = ArrayList<AText>()

    init {
        orientation = HORIZONTAL
        background = Shapes.rect(Palette.card2, 16f, Palette.stroke)
        setPadding(4.dp, 4.dp, 4.dp, 4.dp)
        for ((i, t) in options.withIndex()) {
            val c = ctx.label(15f, Palette.text2, Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
                text = t
                if (i == arabicIndex) Fonts.arabic()?.let { typeface = it; textSize = 17f }
                isClickable = true
                setPadding(18.dp, 0, 18.dp, 0)
                pressScale(0.97f)
                setOnClickListener { select(i, true) }
            }
            cells.add(c)
            addView(c, lp(WRAP, 42.dp, 1f).apply { width = 0 })
        }
        select(sel, false)
    }

    private fun select(i: Int, notify: Boolean) {
        sel = i
        for ((k, c) in cells.withIndex()) {
            if (k == i) {
                c.background = Shapes.rect(Palette.accent, 12f)
                c.setTextColor(Palette.onColor(Palette.accent))
            } else {
                c.background = null
                c.setTextColor(Palette.text2)
            }
        }
        if (notify) onSelect(i)
    }
}

/** Row of colour dots; the selected one gets a ring. */
class Swatches(ctx: Context, private val colors: IntArray, selected: Int, private val onPick: (Int) -> Unit) : LinearLayout(ctx) {
    private var sel = selected

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        build()
    }

    private fun build() {
        removeAllViews()
        for ((i, c) in colors.withIndex()) {
            val dot = FrameLayout(context).apply {
                isClickable = true
                pressScale(0.9f)
                background = if (c == sel) Shapes.oval(c, 0xFFFFFFFF.toInt(), 3) else Shapes.oval(c, Palette.stroke, 1)
                setOnClickListener { sel = c; build(); onPick(c) }
            }
            addView(dot, lp(38.dp, 38.dp).apply { if (i > 0) marginStart = 12.dp })
        }
    }
}

/** Indeterminate spinner: a rotating accent arc. */
class Spinner(ctx: Context, private val sizeDp: Int = 28) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    private var a = 0f
    private var anim: ValueAnimator? = null

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(sizeDp.dp, sizeDp.dp)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { a = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val s = 3.dp.toFloat()
        p.strokeWidth = s
        r.set(s, s, width - s, height - s)
        p.color = Palette.stroke
        c.drawArc(r, 0f, 360f, false, p)
        p.color = Palette.accent
        c.drawArc(r, a, 100f, false, p)
    }
}

/** Determinate/indeterminate progress bar. */
class ProgressBar(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var frac = 0f
    private var shown = 0f
    private var indeterminate = false
    private var phase = 0f
    private var anim: ValueAnimator? = null

    fun set(fraction: Float) { indeterminate = false; frac = fraction.coerceIn(0f, 1f); invalidate() }
    fun setIndeterminate() { indeterminate = true; invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; if (indeterminate || shown != frac) invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(getDefaultSize(0, w), 10.dp)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(0f, 0f, w, h)
        p.color = Palette.card3
        c.drawRoundRect(r, h / 2, h / 2, p)
        p.color = Palette.accent
        if (indeterminate) {
            val bw = w * 0.32f
            val x = -bw + (w + bw) * phase
            r.set(maxOf(0f, x), 0f, minOf(w, x + bw), h)
            if (r.width() > 0) c.drawRoundRect(r, h / 2, h / 2, p)
        } else {
            shown += (frac - shown) * 0.25f
            if (kotlin.math.abs(frac - shown) < 0.002f) shown = frac
            r.set(0f, 0f, maxOf(h, w * shown), h)
            if (shown > 0f) c.drawRoundRect(r, h / 2, h / 2, p)
        }
    }
}

/** Small modal text-input dialog (dark, rounded) for addresses and the update repository. */
object InputDialog {
    fun show(ctx: Context, title: String, hint: String, initial: String, onOk: (String) -> Unit) {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(ColorDrawable(0))
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Shapes.card(24f, 0xFF1A2028.toInt(), 0xFF14181E.toInt())
            setPadding(26.dp, 22.dp, 26.dp, 20.dp)
        }
        box.addView(ctx.label(20f, Palette.text, Fonts.MEDIUM).apply { text = title })
        val input = EditText(ctx).apply {
            setText(initial)
            setSelection(initial.length)
            this.hint = hint
            setHintTextColor(Palette.text3)
            setTextColor(Palette.text)
            textSize = 17f
            typeface = Fonts.get(Fonts.REGULAR)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            isSingleLine = true
            setPadding(18.dp, 0, 18.dp, 0)
            background = Shapes.rect(Palette.card2, 14f, Palette.stroke)
        }
        box.addView(input, lp(MATCH, 52.dp).apply { topMargin = 16.dp })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val cancel = ABtn(ctx).apply { kind = ABtn.Kind.GHOST; setText(R.string.btn_cancel); setOnClickListener { d.dismiss() } }
        val ok = ABtn(ctx).apply { kind = ABtn.Kind.PRIMARY; setText(R.string.btn_save); setOnClickListener { onOk(input.text.toString().trim()); d.dismiss() } }
        row.addView(cancel, lp(WRAP, WRAP))
        row.addView(ok, lp(WRAP, WRAP).apply { marginStart = 10.dp })
        box.addView(row, lp(MATCH, WRAP).apply { topMargin = 18.dp })
        d.setContentView(box)
        d.window?.setLayout(560.dp, WindowManager.LayoutParams.WRAP_CONTENT)
        d.show()
    }
}
