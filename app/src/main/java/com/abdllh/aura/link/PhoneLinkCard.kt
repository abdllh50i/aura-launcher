package com.abdllh.aura.link

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.settings.block
import com.abdllh.aura.settings.group
import com.abdllh.aura.settings.switchRow
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp
import kotlin.math.ceil

/**
 * Settings › Vehicle & system › "ربط الجوال": the server's switch, what it is doing now, and the code that pairs a
 * phone, a QR the iPhone's camera opens in Safari with the six digits beside it. The code exists only while it is on
 * this card: leaving the page ends it ([Pairing]).
 */
class PhoneLinkCard(private val ctx: Context) {
    private val status: AText = ctx.label(Type.FOOTNOTE, Palette.text2, lines = 3)
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val qrRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
    private val qr = QrView(ctx)
    private val manual: AText = ctx.label(Type.BODY, Palette.text2, lines = 0)
    private val left: AText = ctx.label(Type.FOOTNOTE, Palette.text3).apply { fontFeatureSettings = "tnum" }
    private val showBtn = ABtn(ctx).apply { kind = ABtn.Kind.TONAL }
    private val forgetBtn = ABtn(ctx).apply { kind = ABtn.Kind.GHOST; setText(R.string.link_forget) }
    private var showing = false
    private var attached = false

    private val listener: () -> Unit = { refresh() }
    private val paired: (String) -> Unit = { device ->
        showing = false
        justPaired = device
        refresh()
    }
    private var justPaired = ""

    private val second = object : Runnable {
        override fun run() {
            if (!attached || !showing) return
            refreshCode()
            qrRow.postDelayed(this, 1000L)
        }
    }

    val view: View = ctx.group(
        ctx.switchRow(R.drawable.ic_smartphone, ctx.getString(R.string.link_title), ctx.getString(R.string.link_sub), PhoneServer.enabled) { on ->
            PhoneServer.enabled = on
            if (!on) setShowing(false)
            refresh()
        },
        ctx.block(null, null, null, body)
    )

    init {
        body.addView(status, lp(MATCH, WRAP))
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        showBtn.setOnClickListener { setShowing(!showing) }
        actions.addView(showBtn, lp(WRAP, WRAP))
        forgetBtn.setOnClickListener {
            com.abdllh.aura.ui.Confirm.show(ctx, ctx.getString(R.string.link_forget_title), ctx.getString(R.string.link_forget_text), ctx.getString(R.string.link_forget)) {
                Pairing.forgetAll()
                refresh()
            }
        }
        actions.addView(forgetBtn, lp(WRAP, WRAP).apply { marginStart = Space.S.dp })
        body.addView(actions, lp(MATCH, WRAP).apply { topMargin = Space.M.dp })

        // the code on white, its own square: a phone's camera reads that whatever the screen's theme is
        val square = android.widget.FrameLayout(ctx).apply { background = Shapes.rect(0xFFFFFFFF.toInt(), Radius.M) }
        square.addView(qr, com.abdllh.aura.ui.flp(MATCH, MATCH))
        qrRow.addView(square, lp(QR_DP.dp, QR_DP.dp))
        val notes = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        notes.addView(ctx.label(Type.LABEL, Palette.text, lines = 0).apply { setText(R.string.link_scan_hint) }, lp(MATCH, WRAP))
        notes.addView(manual, lp(MATCH, WRAP).apply { topMargin = Space.S.dp })
        notes.addView(left, lp(MATCH, WRAP).apply { topMargin = Space.S.dp })
        qrRow.addView(notes, lp(0, WRAP, 1f).apply { marginStart = Space.L.dp })
        body.addView(qrRow, lp(MATCH, WRAP).apply { topMargin = Space.M.dp })
    }

    fun attach() {
        attached = true
        PhoneServer.addListener(listener)
        PhoneServer.pairedListeners.add(paired)
        refresh()
    }

    fun detach() {
        attached = false
        PhoneServer.removeListener(listener)
        PhoneServer.pairedListeners.remove(paired)
        setShowing(false)
    }

    private fun setShowing(on: Boolean) {
        if (on == showing) return
        showing = on
        if (on) justPaired = "" else Pairing.hide()
        refresh()
    }

    private fun refresh() {
        val on = PhoneServer.enabled
        body.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        val address = PhoneServer.address()
        val lines = ArrayList<String>()
        lines.add(when (PhoneServer.status) {
            PhoneServer.Status.RUNNING -> if (address != null) ctx.getString(R.string.link_status_on, address) else ctx.getString(R.string.link_status_no_net)
            PhoneServer.Status.FAILED -> ctx.getString(R.string.link_status_failed, PhoneServer.failure)
            else -> ctx.getString(R.string.link_status_starting)
        })
        val devices = Pairing.devices()
        lines.add(if (devices.isEmpty()) ctx.getString(R.string.link_paired_none) else ctx.getString(R.string.link_paired, devices.joinToString("، ")))
        if (justPaired.isNotEmpty()) lines.add(ctx.getString(R.string.link_paired_now, justPaired))
        status.text = lines.joinToString("\n")
        forgetBtn.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
        val canShow = PhoneServer.status == PhoneServer.Status.RUNNING && address != null
        showBtn.visibility = if (canShow) View.VISIBLE else View.GONE
        showBtn.setText(if (showing) R.string.link_hide_code else R.string.link_show_code)
        if (showing && !canShow) showing = false
        qrRow.visibility = if (showing) View.VISIBLE else View.GONE
        qrRow.removeCallbacks(second)
        if (showing) {
            refreshCode()
            qrRow.postDelayed(second, 1000L)
        } else {
            Pairing.hide()
            qr.text = null
        }
    }

    /** The code, a new one when the last ended (its time, or its tries), and how long it has. */
    private fun refreshCode() {
        val now = SystemClock.elapsedRealtime()
        val code = Pairing.show(now)
        val url = PhoneServer.pairUrl(code)
        if (url == null) { showing = false; refresh(); return }
        qr.text = url
        manual.text = ctx.getString(R.string.link_manual, url.removePrefix("http://").substringBefore("/p/"), code)
        val sec = ceil(Pairing.left(now) / 1000.0).toInt()
        left.text = ctx.getString(R.string.link_code_left, String.format(java.util.Locale.ROOT, "%d:%02d", sec / 60, sec % 60))
    }

    companion object {
        private const val QR_DP = 232

        /** One line for Settings → About: the server and its last request; "" while it is off. */
        fun aboutLine(c: Context): String {
            if (!PhoneServer.enabled) return ""
            val state = when (PhoneServer.status) {
                PhoneServer.Status.RUNNING -> c.getString(R.string.link_status_on, PhoneServer.address() ?: "-")
                PhoneServer.Status.FAILED -> c.getString(R.string.link_status_failed, PhoneServer.failure)
                else -> c.getString(R.string.link_status_starting)
            }
            val last = PhoneServer.lastRequest
            return if (last.isEmpty()) state else "$state · ${c.getString(R.string.link_last_request, last)}"
        }
    }
}
