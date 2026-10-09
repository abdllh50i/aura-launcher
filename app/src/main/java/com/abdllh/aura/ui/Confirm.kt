package com.abdllh.aura.ui

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.util.dp

/** A small centred card that asks before something drastic: title, explanation, Cancel and the action. */
object Confirm {
    fun show(ctx: Context, title: CharSequence, text: CharSequence, action: CharSequence, danger: Boolean = true, onYes: () -> Unit) {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28.dp, 24.dp, 28.dp, 22.dp)
            background = Shapes.rect(Palette.sheet, 26f, if (Palette.dark) Palette.stroke else 0)
        }
        card.addView(ctx.label(22f, Palette.text, Fonts.MEDIUM, lines = 2).apply { this.text = title }, lp(MATCH, WRAP))
        card.addView(ctx.label(16f, Palette.text2, Fonts.REGULAR, lines = 0).apply {
            this.text = text
            setLineSpacing(0f, 1.15f)
        }, lp(MATCH, WRAP).apply { topMargin = 10.dp })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(ABtn(ctx).apply {
            kind = ABtn.Kind.TONAL
            setText(R.string.btn_cancel)
            setPadding(26.dp, 0, 26.dp, 0)
            setOnClickListener { d.dismiss() }
        }, lp(WRAP, 56.dp))
        row.addView(ABtn(ctx).apply {
            kind = if (danger) ABtn.Kind.DANGER else ABtn.Kind.PRIMARY
            this.text = action
            setPadding(26.dp, 0, 26.dp, 0)
            setOnClickListener { d.dismiss(); onYes() }
        }, lp(WRAP, 56.dp).apply { marginStart = 12.dp })
        card.addView(row, lp(MATCH, WRAP).apply { topMargin = 24.dp })
        d.setContentView(card)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(560.dp, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }
}
