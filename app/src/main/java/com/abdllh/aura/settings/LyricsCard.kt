package com.abdllh.aura.settings

import android.Manifest
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.music.LyricsServer
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Modal
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp
import com.abdllh.aura.voice.Amri

/**
 * Settings › Display & sound › Music: the owner's own lyrics server (1.13; tools/lyrics-server on his PC, published
 * through his domain): its address and key, a test that says whether it answers and takes the key, and the switch
 * for timing lyrics by listening ([com.abdllh.aura.music.LyricsSync], on by default since 1.15). Kept in a file of its
 * own so the music section of [DisplaySoundPage] has one line for it.
 */
class LyricsCard private constructor(private val act: SettingsActivity, private val ctx: Context) {
    private val status: AText = ctx.label(Type.FOOTNOTE, Palette.text2, lines = 3)
    private val test = ABtn(ctx).apply {
        kind = ABtn.Kind.TONAL
        setText(R.string.lyr_srv_test)
        setOnClickListener { check() }
    }
    private val spinner = Spinner(ctx, 24).apply { visibility = View.GONE }
    private val address = ctx.valueText("")
    private val key = ctx.valueText("")
    private var busy = false

    val view: View = ctx.group(
        ctx.block(R.drawable.ic_lyrics, ctx.getString(R.string.lyr_srv_title), null, LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(test, lp(WRAP, WRAP))
            addView(spinner, lp(24.dp, 24.dp).apply { marginStart = Space.L.dp })
        }, live = status),
        ctx.settingRow(R.drawable.ic_globe, ctx.getString(R.string.lyr_srv_address), null, address) {
            ask(ctx.getString(R.string.lyr_srv_address), ctx.getString(R.string.lyr_srv_address_hint), LyricsServer.address, uri = true) {
                LyricsServer.address = it
                refresh(null)
            }
        },
        ctx.settingRow(R.drawable.ic_lock, ctx.getString(R.string.lyr_srv_key), null, key) {
            ask(ctx.getString(R.string.lyr_srv_key), ctx.getString(R.string.lyr_srv_key_hint), LyricsServer.key, uri = false) {
                LyricsServer.key = it
                refresh(null)
            }
        },
        // (on by default since 1.15; shown on only where it can work: without the microphone permission it listens to
        // nothing, and turning it on asks for that permission)
        ctx.switchRow(R.drawable.ic_mic, ctx.getString(R.string.lyr_srv_listen), ctx.getString(R.string.lyr_srv_listen_sub),
            LyricsServer.listen && Amri.hasMicPermission(ctx)) { on ->
            when {
                !on -> LyricsServer.listen = false
                !LyricsServer.configured -> { Snack.show(ctx, R.string.lyr_srv_listen_needs); act.rebuild() }
                Amri.hasMicPermission(ctx) -> LyricsServer.listen = true
                // the microphone permission first (asked once; on when it is given, the switch back off when not)
                else -> {
                    act.afterMic = { LyricsServer.listen = true; act.rebuild() }
                    act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), SettingsActivity.REQ_MIC)
                }
            }
        }
    )

    init { refresh(null) }

    /** Does the server answer, with this key? The answer stays under the title until the address or key changes. */
    private fun check() {
        if (busy) return
        busy = true
        refresh(null)
        LyricsServer.check { res, info ->
            busy = false
            refresh(when (res) {
                LyricsServer.Check.OK -> ctx.getString(R.string.lyr_srv_ok, info)
                LyricsServer.Check.WRONG_KEY -> ctx.getString(R.string.lyr_srv_wrong_key)
                LyricsServer.Check.NOT_SERVER -> ctx.getString(R.string.lyr_srv_not_server, info)
                LyricsServer.Check.UNREACHABLE -> ctx.getString(R.string.lyr_srv_unreachable)
                LyricsServer.Check.CLEARTEXT -> ctx.getString(R.string.lyr_srv_cleartext)
                LyricsServer.Check.NO_ADDRESS -> ctx.getString(R.string.lyr_srv_no_address)
            })
        }
    }

    private fun refresh(note: String?) {
        test.setText(if (busy) R.string.lyr_srv_testing else R.string.lyr_srv_test)
        test.isEnabled = !busy
        spinner.visibility = if (busy) View.VISIBLE else View.GONE
        address.text = LyricsServer.address.ifBlank { ctx.getString(R.string.lyr_srv_none) }
        // (the key shown in part: enough to see which one is set)
        val k = LyricsServer.key
        key.text = if (k.isBlank()) ctx.getString(R.string.lyr_srv_none) else k.take(3) + "•".repeat((k.length - 3).coerceIn(3, 8))
        status.text = note ?: when {
            LyricsServer.base() == null -> ctx.getString(R.string.lyr_srv_off)
            LyricsServer.cleartextBlocked() -> ctx.getString(R.string.lyr_srv_cleartext)
            else -> ctx.getString(R.string.lyr_srv_set)
        }
    }

    /**
     * A small input card, like [InputDialog] but with the keyboard an address or a key wants (no suggestions, no
     * capitals): the update repository's postal-address keyboard offered words for a key.
     */
    private fun ask(title: String, hint: String, initial: String, uri: Boolean, onOk: (String) -> Unit) {
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
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (uri) InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            isSingleLine = true
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(Space.L.dp, 0, Space.L.dp, 0)
            background = Shapes.rect(Palette.card2, Radius.M, Palette.stroke)
        }
        card.addView(input, lp(MATCH, Touch.MIN.dp).apply { topMargin = Space.L.dp })
        val save = { val v = input.text.toString().trim(); m.close(); onOk(v) }
        // (the keyboard's own Done saves too: on the unit's short screen the keyboard covers the card's buttons)
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setOnEditorActionListener { _, action, e ->
            // (a keyboard's Done, or Enter on a hardware keyboard: no action then, the key itself, down and up)
            when {
                action == EditorInfo.IME_ACTION_DONE -> { save(); true }
                e?.keyCode == KeyEvent.KEYCODE_ENTER -> { if (e.action == KeyEvent.ACTION_DOWN) save(); true }
                else -> false
            }
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(ABtn(ctx).apply { kind = ABtn.Kind.GHOST; setText(R.string.btn_cancel); setOnClickListener { m.close() } }, lp(WRAP, WRAP))
        row.addView(ABtn(ctx).apply {
            kind = ABtn.Kind.PRIMARY
            setText(R.string.btn_save)
            setOnClickListener { save() }
        }, lp(WRAP, WRAP).apply { marginStart = Space.M.dp })
        card.addView(row, lp(MATCH, WRAP).apply { topMargin = Space.XL.dp })
        m.show()
        input.requestFocus()
    }

    companion object {
        /** The card and its hint, at the end of the music section ([DisplaySoundPage]'s one line for it). */
        fun addTo(act: SettingsActivity, ctx: Context, col: LinearLayout) {
            col.addRow(LyricsCard(act, ctx).view)
            col.addHint(ctx.getString(R.string.lyr_srv_hint))
        }
    }
}
