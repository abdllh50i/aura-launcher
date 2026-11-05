package com.abdllh.aura.settings

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.nav.pack.PackManager
import com.abdllh.aura.nav.pack.PackManager.State
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.Confirm
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.ProgressBar
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Spinner
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.Fmt

/**
 * The map pack, a sub-page of Maps, in place of the old offline card: what is on the unit, what can come and what that takes, by the internet or a stick. All of it is [PackManager]'s, which
 * starts nothing by itself and never touches the map in use: this page only says what it says and passes on what is pressed. It changes in place at each report (three a second at most).
 */
class PackPage(private val act: SettingsPanel) : Page(R.string.off_section) {
    private lateinit var ctx: Context
    private lateinit var title: com.abdllh.aura.ui.AText
    private lateinit var sub: com.abdllh.aura.ui.AText
    private lateinit var bar: ProgressBar
    private lateinit var spinner: Spinner
    private lateinit var note: com.abdllh.aura.ui.AText
    private lateinit var main: ABtn
    private lateinit var second: ABtn
    private lateinit var old: SettingRow
    private lateinit var oldWord: com.abdllh.aura.ui.AText
    private val heard: () -> Unit = { show() }
    private var asked = false

    override fun onShow() {
        PackManager.removeListener(heard)
        PackManager.addListener(heard)
        show()
        // (once a visit, with nothing going on: what can be offered is what he came to see, and there is no button to ask again. With a map on the unit
        // only with the internet on: no word of a missing internet on a page whose map works)
        val s = PackManager.status.state
        if (!asked && (s == State.NONE || ((s == State.INSTALLED || s == State.NO_SPACE) && com.abdllh.aura.update.UpdateManager.isOnline()))) {
            asked = true
            PackManager.checkUpdate()
        }
    }

    override fun onHide() { PackManager.removeListener(heard) }

    override fun build(ctx: Context): View {
        this.ctx = ctx
        val col = ctx.pageColumn()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(PAD, Space.XS, PAD, Space.M)
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title = ctx.label(Type.stateTitle, { Palette.ink })
        spinner = Spinner(ctx, 24)
        head.addView(title, lp(0, Type.stateTitle.viewH, 1f))
        head.addView(spinner, lp(24, 24).apply { marginStart = Space.M })
        box.addView(head, lp(MATCH, WRAP))
        bar = ProgressBar(ctx)
        box.addView(bar, lp(MATCH, WRAP).apply { topMargin = Space.M })
        sub = ctx.label(Type.stateBody, { Palette.ink2 }, lines = 0)
        box.addView(sub, lp(MATCH, WRAP).apply { topMargin = Space.S })
        note = ctx.label(Type.rowHint, { Palette.ink2 }, lines = 0)
        box.addView(note, lp(MATCH, WRAP).apply { topMargin = Space.S })
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        main = ABtn(ctx).apply { kind = ABtn.Kind.PRIMARY }
        second = ABtn(ctx).apply { kind = ABtn.Kind.TONAL }
        buttons.addView(main, lp(WRAP, WRAP))
        buttons.addView(second, lp(WRAP, WRAP).apply { marginStart = Space.M })
        box.addView(buttons, lp(MATCH, WRAP).apply { topMargin = Space.L })
        col.addView(box, lp(MATCH, WRAP))

        oldWord = ctx.label(Type.rowHint, { Palette.ink2 })
        old = ctx.settingRow(null, ctx.getString(R.string.st_pack_old), null, ABtn(ctx).apply {
            kind = ABtn.Kind.TONAL
            setText(R.string.st_pack_old_btn)
            setOnClickListener { PackManager.freeOldMap() }
        })
        col.addView(old, lp(MATCH, WRAP))
        return col
    }

    private fun button(b: ABtn, text: Int, kind: ABtn.Kind, run: () -> Unit) {
        b.visibility = View.VISIBLE
        b.setText(text)
        if (b.kind != kind) b.kind = kind
        b.setOnClickListener { run() }
    }

    private fun show() {
        if (!::title.isInitialized) return
        val s = PackManager.status
        val trip = NavSession.state != NavSession.State.IDLE
        val offer = s.offer ?: s.usbOffer
        // (no speed yet to tell: the first report of a chunk says 0)
        title.text = if (s.state == State.FETCHING && s.bytesPerSec <= 0) ctx.getString(R.string.st_pack_going, "${s.percent}") else PackManager.line(s)
        spinner.visibility = if (s.state == State.CHECKING) View.VISIBLE else View.GONE
        val moving = s.state == State.FETCHING || s.state == State.WAITING || s.state == State.TRIP_HOLD || s.state == State.PAUSED || s.state == State.VERIFYING ||
            (s.state == State.FAILED && s.stagingId != null)
        bar.visibility = if (moving) View.VISIBLE else View.GONE
        if (moving) bar.set(s.percent / 100f)

        sub.text = when {
            moving && s.total > 0 -> ctx.getString(R.string.st_pack_of, Fmt.bytes(s.done), Fmt.bytes(s.total))
            s.state == State.INSTALLED || s.currentId != null && offer == null ->
                ctx.getString(R.string.st_pack_here, Fmt.bytes(s.currentBytes), s.currentBuilt?.take(10).orEmpty())
            else -> ""
        }
        sub.visibility = if (sub.text.isEmpty()) View.GONE else View.VISIBLE
        note.text = when {
            s.problem != null && s.state != State.FAILED -> PackManager.text(s.problem)
            offer != null && (s.state == State.OFFER || s.state == State.NO_SPACE) -> PackManager.advice(offer)
            s.state == State.NONE || s.state == State.INSTALLED -> ctx.getString(R.string.st_pack_free, Fmt.bytes(free()))
            else -> ""
        }
        note.visibility = if (note.text.isEmpty()) View.GONE else View.VISIBLE

        main.visibility = View.GONE
        second.visibility = View.GONE
        when (s.state) {
            State.NONE -> {
                button(main, R.string.st_pack_check_first, ABtn.Kind.PRIMARY) { PackManager.checkUpdate() }
                button(second, R.string.st_pack_usb, ABtn.Kind.TONAL) { PackManager.scanUsb() }
            }
            State.CHECKING -> {}
            State.OFFER -> {
                if (s.offer != null) button(main, if (s.offer.isUpdate) R.string.st_pack_update else R.string.st_pack_download, ABtn.Kind.PRIMARY) { PackManager.startDownload() }
                if (s.usbOffer != null) button(if (s.offer != null) second else main, R.string.st_pack_usb_go, if (s.offer != null) ABtn.Kind.TONAL else ABtn.Kind.PRIMARY) { PackManager.startUsb() }
                else button(second, R.string.st_pack_usb, ABtn.Kind.TONAL) { PackManager.scanUsb() }
            }
            State.NO_SPACE -> {}
            State.FETCHING, State.WAITING, State.TRIP_HOLD, State.VERIFYING -> {
                button(main, R.string.st_pack_pause, ABtn.Kind.TONAL) { PackManager.pause() }
                button(second, R.string.btn_cancel, ABtn.Kind.TONAL) { cancel() }
            }
            State.PAUSED -> {
                button(main, R.string.st_pack_resume, ABtn.Kind.PRIMARY) { PackManager.resume() }
                button(second, R.string.btn_cancel, ABtn.Kind.TONAL) { cancel() }
            }
            State.FAILED -> {
                button(main, R.string.st_pack_resume, ABtn.Kind.PRIMARY) { PackManager.resume() }
                if (s.stagingId != null) button(second, R.string.btn_cancel, ABtn.Kind.TONAL) { cancel() }
            }
            State.INSTALLED -> {
                button(main, R.string.st_pack_usb, ABtn.Kind.TONAL) { PackManager.scanUsb() }
                button(second, R.string.st_pack_delete, ABtn.Kind.DANGER) { delete(trip) }
            }
        }
        old.visibility = if (s.oldMapBytes > 0) View.VISIBLE else View.GONE
        if (s.oldMapBytes > 0) (old.getChildAt(0) as? LinearLayout)?.let { oldLine(it, Fmt.bytes(s.oldMapBytes)) }
    }

    /** The old map's space, under its row's title, in place. */
    private fun oldLine(column: LinearLayout, size: String) {
        val text = ctx.getString(R.string.st_pack_old_sub, size)
        if (oldWord.parent == null) column.addView(oldWord, lp(MATCH, Type.rowHint.viewH).apply { topMargin = -2; bottomMargin = -2 })
        oldWord.text = text
    }

    private fun free(): Long = try { ctx.filesDir.usableSpace } catch (_: Throwable) { 0L }

    private fun cancel() {
        Confirm.show(act, ctx.getString(R.string.st_pack_cancel_title), ctx.getString(R.string.st_pack_cancel_text), ctx.getString(R.string.st_pack_cancel_btn)) { PackManager.cancel() }
    }

    private fun delete(trip: Boolean) {
        if (trip) { Snack.show(ctx, R.string.pack_err_trip); return }
        Confirm.show(act, ctx.getString(R.string.st_pack_delete_title), ctx.getString(R.string.st_pack_delete_text), ctx.getString(R.string.st_pack_delete)) {
            if (!PackManager.deleteCurrent()) Snack.show(ctx, R.string.pack_err_trip)
        }
    }

    private companion object {
        const val PAD = 22
    }
}
