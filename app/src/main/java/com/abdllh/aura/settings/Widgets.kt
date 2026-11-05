package com.abdllh.aura.settings

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import com.abdllh.aura.R
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Modal
import com.abdllh.aura.ui.TextField
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.lp

/** Small modal text-input card for the lyrics server's address and key: a [Modal], so it comes and goes like the others, with the keyboard those want (no suggestions, no capitals). */
object InputDialog {
    fun show(ctx: Context, title: String, hint: String, initial: String, uri: Boolean, onOk: (String) -> Unit) {
        val card = Modal.card(ctx)
        val m = Modal(ctx, card).followKeyboard()
        card.addView(Modal.title(ctx, title), lp(MATCH, WRAP))
        val field = TextField(ctx, hint = hint)
        val input = field.input
        input.setText(initial)
        input.setSelection(initial.length)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            (if (uri) InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        input.textDirection = View.TEXT_DIRECTION_LTR
        card.addView(field, lp(MATCH, TextField.HEIGHT).apply { topMargin = 14 })
        // (the value is taken now; the card goes meanwhile)
        val save = { val v = input.text.toString().trim(); m.close(); onOk(v) }
        // (the keyboard's own Done saves too)
        input.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        input.setOnEditorActionListener { _, action, e ->
            // (a keyboard's Done, or Enter on a hardware keyboard: no action then, the key itself, down and up)
            when {
                action == EditorInfo.IME_ACTION_DONE -> { save(); true }
                e?.keyCode == KeyEvent.KEYCODE_ENTER -> { if (e.action == KeyEvent.ACTION_DOWN) save(); true }
                else -> false
            }
        }
        val yes = ABtn(ctx).apply { kind = ABtn.Kind.PRIMARY; setText(R.string.btn_save); setOnClickListener { save() } }
        val no = ABtn(ctx).apply { kind = ABtn.Kind.TONAL; setText(R.string.btn_cancel); setOnClickListener { m.close() } }
        card.addView(Modal.buttons(ctx, yes, no), lp(MATCH, WRAP).apply { topMargin = 20 })
        m.show()
        input.requestFocus()
    }
}
