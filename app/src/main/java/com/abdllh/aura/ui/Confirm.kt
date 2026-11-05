package com.abdllh.aura.ui

import android.content.Context
import com.abdllh.aura.R

/** A small centred card that asks before something drastic: title, explanation, the action nearest the driver, and Cancel. */
object Confirm {
    fun show(ctx: Context, title: CharSequence, text: CharSequence, action: CharSequence, danger: Boolean = true, onYes: () -> Unit) {
        val card = Modal.card(ctx)
        val m = Modal(ctx, card)
        card.addView(Modal.title(ctx, title), lp(MATCH, WRAP))
        card.addView(Modal.body(ctx, text), lp(MATCH, WRAP).apply { topMargin = 8 })
        val yes = ABtn(ctx).apply {
            kind = if (danger) ABtn.Kind.DANGER else ABtn.Kind.PRIMARY
            this.text = action
            // (the action starts at once; the card goes meanwhile)
            setOnClickListener { m.close(); onYes() }
        }
        val no = ABtn(ctx).apply {
            kind = ABtn.Kind.TONAL
            setText(R.string.btn_cancel)
            setOnClickListener { m.close() }
        }
        card.addView(Modal.buttons(ctx, yes, no), lp(MATCH, WRAP).apply { topMargin = 20 })
        m.show()
    }
}
