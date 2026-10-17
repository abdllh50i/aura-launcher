package com.abdllh.aura.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.util.dp

/** A small centred card that asks before something drastic: title, explanation, Cancel and the action. */
object Confirm {
    fun show(ctx: Context, title: CharSequence, text: CharSequence, action: CharSequence, danger: Boolean = true, onYes: () -> Unit) {
        val card = Modal.card(ctx)
        val m = Modal(ctx, card)
        card.addView(ctx.label(Type.TITLE, Palette.text, lines = 2).apply { this.text = title }, lp(MATCH, WRAP))
        card.addView(ctx.label(Type.BODY, Palette.text2, lines = 0).apply { this.text = text }, lp(MATCH, WRAP).apply { topMargin = 10.dp })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(ABtn(ctx).apply {
            kind = ABtn.Kind.TONAL
            setText(R.string.btn_cancel)
            setOnClickListener { m.close() }
        }, lp(WRAP, WRAP))
        row.addView(ABtn(ctx).apply {
            kind = if (danger) ABtn.Kind.DANGER else ABtn.Kind.PRIMARY
            this.text = action
            // (the action starts at once; the card goes meanwhile)
            setOnClickListener { m.close(); onYes() }
        }, lp(WRAP, WRAP).apply { marginStart = Space.M.dp })
        card.addView(row, lp(MATCH, WRAP).apply { topMargin = Space.XL.dp })
        m.show()
    }
}
