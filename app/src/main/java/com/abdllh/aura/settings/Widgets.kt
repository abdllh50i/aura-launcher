package com.abdllh.aura.settings

import android.content.Context
import android.text.InputType
import com.abdllh.aura.R
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Modal
import com.abdllh.aura.ui.TextField
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.lp

/** Small modal text-input card (addresses, the update repository): a [Modal], so it comes and goes like the others. */
object InputDialog {
    fun show(ctx: Context, title: String, hint: String, initial: String, onOk: (String) -> Unit) {
        val card = Modal.card(ctx)
        val m = Modal(ctx, card).followKeyboard()
        card.addView(Modal.title(ctx, title), lp(MATCH, WRAP))
        val field = TextField(ctx, hint = hint)
        val input = field.input
        input.setText(initial)
        input.setSelection(initial.length)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
        card.addView(field, lp(MATCH, TextField.HEIGHT).apply { topMargin = 14 })
        val yes = ABtn(ctx).apply {
            kind = ABtn.Kind.PRIMARY
            setText(R.string.btn_save)
            // (the value is taken now; the card goes meanwhile)
            setOnClickListener { val v = input.text.toString().trim(); m.close(); onOk(v) }
        }
        val no = ABtn(ctx).apply { kind = ABtn.Kind.TONAL; setText(R.string.btn_cancel); setOnClickListener { m.close() } }
        card.addView(Modal.buttons(ctx, yes, no), lp(MATCH, WRAP).apply { topMargin = 20 })
        m.show()
        input.requestFocus()
    }
}
