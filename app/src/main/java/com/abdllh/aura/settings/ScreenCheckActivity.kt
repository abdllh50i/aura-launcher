package com.abdllh.aura.settings

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.abdllh.aura.R
import com.abdllh.aura.diag.ScreenCheck
import com.abdllh.aura.diag.TripRecorder
import com.abdllh.aura.music.LyricsServer
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings › About › Screen check: one button, a progress line with a cancel while it runs ([ScreenCheck] does the
 * work), and the last report, in two columns of short rows the owner can photograph. A page of its own and not an
 * eighth page of the sidebar: seven fill the screen. The trip recorder's switch is here too ([TripRecorder]).
 */
class ScreenCheckActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var scroll: ScrollView
    private lateinit var action: LinearLayout
    private lateinit var reportHolder: LinearLayout
    private lateinit var tripRow: SettingRow
    private var skipHolder: LinearLayout? = null
    private var lineView: AText? = null
    private var barView: ProgressBar? = null
    private var wheelTitle: AText? = null
    private var keysView: AText? = null
    private var showingRun = false
    private var cancelled = false
    private var report: JSONObject? = null
    private var sentState: JSONObject? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.refreshLocale(this)
        Theme.refresh()
        Theme.window(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val back = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(Space.S.dp, 0, Space.L.dp, 0)
            background = Shapes.ghost(Radius.M)
            isClickable = true
            contentDescription = getString(R.string.set_about)
            pressScale(Motion.PRESS_CONTROL)
            setOnClickListener { finish() }
            addView(iconView(R.drawable.ic_chevron_left, 30, Palette.text).also {
                if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) it.scaleX = -1f
            }, lp(30.dp, 30.dp))
            addView(label(Type.HEADLINE, Palette.text).apply { setText(R.string.diag_title) }, lp(WRAP, WRAP).apply { marginStart = Space.S.dp })
        }
        root.addView(back, lp(WRAP, Touch.LARGE.dp).apply { setMargins(Space.L.dp, Space.S.dp, Space.L.dp, 0) })

        val col = pageColumn().apply { setPaddingRelative(Space.XL.dp, Space.S.dp, Space.XL.dp, Space.XXL.dp) }
        action = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(action, lp(MATCH, WRAP))
        tripRow = tripSwitch()
        col.addRow(group(tripRow))
        skipHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(skipHolder, lp(MATCH, WRAP))
        reportHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(reportHolder, lp(MATCH, WRAP))
        scroll = scrolled(col)
        root.addView(scroll, lp(MATCH, 0, 1f))
        setContentView(root)

        showIdle()
        ScreenCheck.listener = { onStatus(it) }
        ScreenCheck.attach(this) { load(false) }
        if (ScreenCheck.running) onStatus(ScreenCheck.status)
        load(false)
    }

    override fun onResume() {
        super.onResume()
        ScreenCheck.pageResumed(this, true)
        refreshTrip()
    }

    override fun onPause() {
        ScreenCheck.pageResumed(this, false)
        super.onPause()
    }

    override fun onDestroy() {
        ScreenCheck.detach(this)
        ScreenCheck.listener = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** The step that asks for the wheel's keys: one that reaches this window as an Android key counts too. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) ScreenCheck.keyEvent(event.keyCode)
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------------------------------ the button
    private fun showIdle() {
        showingRun = false
        lineView = null
        barView = null
        wheelTitle = null
        keysView = null
        action.removeAllViews()
        val hasServer = LyricsServer.configured
        val start = ABtn(this).apply {
            kind = ABtn.Kind.PRIMARY
            size = ABtn.Size.L
            setText(if (hasServer) R.string.diag_start_send else R.string.diag_start_keep)
            setOnClickListener { pressed() }
        }
        action.addView(group(block(R.drawable.ic_zap, getString(R.string.diag_title), getString(R.string.diag_intro), start)), lp(MATCH, WRAP))
        action.addHint(getString(if (hasServer) R.string.diag_privacy else R.string.diag_no_server))
    }

    private fun pressed() {
        ScreenCheck.refusal()?.let { Snack.show(this, it, long = true); return }
        cancelled = false
        ScreenCheck.start(this)
    }

    private fun showRunning() {
        showingRun = true
        action.removeAllViews()
        val line = label(Type.BODY, Palette.text, lines = 2)
        val bar = ProgressBar(this)
        val cancel = ABtn(this).apply {
            kind = ABtn.Kind.TONAL
            setText(R.string.diag_cancel)
            setOnClickListener {
                cancelled = true
                ScreenCheck.cancel("cancel")
                lineView?.setText(R.string.diag_cancelling)
                isEnabled = false
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(line, lp(MATCH, WRAP))
            addView(bar, lp(MATCH, WRAP).apply { topMargin = Space.M.dp })
            val title = label(Type.TITLE, Palette.accentFg, lines = 2).apply { visibility = View.GONE }
            addView(title, lp(MATCH, WRAP).apply { topMargin = Space.L.dp })
            val keys = label(Type.BODY, Palette.text2, lines = 3).apply { visibility = View.GONE }
            addView(keys, lp(MATCH, WRAP).apply { topMargin = Space.S.dp })
            addView(cancel, lp(WRAP, WRAP).apply { topMargin = Space.L.dp })
            wheelTitle = title
            keysView = keys
        }
        lineView = line
        barView = bar
        action.addView(group(block(R.drawable.ic_zap, getString(R.string.diag_title), getString(R.string.diag_running_sub), body)), lp(MATCH, WRAP))
    }

    private fun onStatus(s: ScreenCheck.Status) {
        if (isDestroyed) return
        if (!s.running) {
            if (showingRun) {
                showIdle()
                load(true)
            } else if (!s.sending) load(false)
            return
        }
        if (!showingRun) showRunning()
        if (!cancelled && lineView?.text?.toString() != s.line) lineView?.text = s.line
        barView?.set(s.frac)
        val asking = s.wheelLeft > 0
        wheelTitle?.visibility = if (asking) View.VISIBLE else View.GONE
        keysView?.visibility = if (asking) View.VISIBLE else View.GONE
        if (asking) {
            wheelTitle?.text = getString(R.string.diag_wheel_title, s.wheelLeft.toString())
            keysView?.text = if (s.keys.isEmpty()) getString(R.string.diag_wheel_none) else getString(R.string.diag_wheel_seen, s.keys)
        }
    }

    // ------------------------------------------------------------------------------------------ the trip recorder
    private fun tripSwitch(): SettingRow = switchRow(R.drawable.ic_nav, getString(R.string.diag_trip), getString(R.string.diag_trip_sub), TripRecorder.on) { on ->
        TripRecorder.set(this, on)
        refreshTrip()
    }

    private fun refreshTrip() {
        // (it may have turned itself off meanwhile)
        ((tripRow.getChildAt(tripRow.childCount - 1)) as? com.abdllh.aura.ui.AuraSwitch)?.setChecked(TripRecorder.on)
        val sub = (tripRow.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? AText ?: return
        sub.text = if (TripRecorder.on) getString(R.string.diag_trip_on, TripRecorder.lines.toString(), TripRecorder.maxLines.toString()) else getString(R.string.diag_trip_sub)
    }

    // ------------------------------------------------------------------------------------------ the report
    private fun load(scrollThere: Boolean) {
        Thread({
            val last = try { ScreenCheck.lastReport(this) } catch (_: Throwable) { null }
            val skipped = try { ScreenCheck.skippedNames(this) } catch (_: Throwable) { emptyList<String>() }
            main.post {
                if (isDestroyed) return@post
                showSkipped(skipped)
                report = last?.first
                sentState = last?.second
                render()
                if (scrollThere) scroll.post { scroll.smoothScrollTo(0, (reportHolder.top - Space.S.dp).coerceAtLeast(0)) }
            }
        }, "aura-diag-page").apply { isDaemon = true; start() }
    }

    private fun showSkipped(names: List<String>) {
        val h = skipHolder ?: return
        h.removeAllViews()
        if (names.isEmpty()) return
        val again = ABtn(this).apply {
            kind = ABtn.Kind.TONAL
            setText(R.string.diag_retry_skipped)
            setOnClickListener {
                Thread({ ScreenCheck.forgetSkipped(this@ScreenCheckActivity) }).start()
                showSkipped(emptyList())
            }
        }
        h.addRow(group(settingRow(R.drawable.ic_alert, getString(R.string.diag_skipped_title), names.joinToString(" · "), again, Palette.warn)))
    }

    private fun render() {
        reportHolder.removeAllViews()
        val r = report ?: return
        reportHolder.addSection(getString(R.string.diag_last_title, r.optString("started").replace('T', ' ').removeSuffix("Z")))
        reportHolder.addRow(group(sendRow()))
        if (!r.optBoolean("realUnit")) reportHolder.addHint(getString(R.string.diag_emulator_note))
        r.optString("stopped").takeIf { it.isNotEmpty() }?.let { why ->
            reportHolder.addHint(getString(R.string.diag_stopped, when (why) {
                "cancel" -> getString(R.string.diag_stopped_cancel)
                "moving" -> getString(R.string.diag_stopped_moving)
                "left" -> getString(R.string.diag_stopped_left)
                ScreenCheck.DIED_MAIN -> getString(R.string.diag_stopped_main)
                ScreenCheck.DIED_UNIT -> getString(R.string.diag_stopped_unit)
                else -> why
            }))
        }
        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val a = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cols.addView(a, lp(0, WRAP, 1f))
        cols.addView(b, lp(0, WRAP, 1f).apply { marginStart = Space.M.dp })
        var ra = 0
        var rb = 0
        for (s in ReportText(this, r).sections()) {
            if (s.rows.isEmpty()) continue
            val into = if (ra <= rb) a else b
            into.addView(s.view(this), lp(MATCH, WRAP).apply { if (into.childCount > 0) topMargin = Space.M.dp })
            val weight = s.rows.size + 2
            if (into === a) ra += weight else rb += weight
        }
        reportHolder.addView(cols, lp(MATCH, WRAP).apply { topMargin = Space.M.dp })
    }

    /** How sending went, and a way to send (again) unless it arrived. */
    private fun sendRow(): View {
        val s = sentState
        val result = s?.optString("result").orEmpty()
        val at = s?.optLong("at")?.takeIf { it > 0 }?.let { SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(it)) }.orEmpty()
        val words = when {
            ScreenCheck.sending -> getString(R.string.diag_sending)
            s == null -> getString(R.string.diag_not_sent)
            result == "ok" -> getString(R.string.diag_sent_ok, at)
            result == "no_server" -> getString(R.string.diag_send_no_server)
            result == "cleartext" -> getString(R.string.lyr_srv_cleartext)
            result == "wrong_key" -> getString(R.string.lyr_srv_wrong_key)
            result == "old_server" -> getString(R.string.diag_send_old_server)
            result == "http" -> getString(R.string.diag_send_http, s.optInt("code").toString())
            else -> getString(R.string.diag_send_unreachable)
        }
        val button = if (result == "ok" || ScreenCheck.sending) null else ABtn(this).apply {
            kind = ABtn.Kind.TONAL
            setText(if (s == null) R.string.diag_send else R.string.diag_resend)
            setOnClickListener {
                ScreenCheck.send(this@ScreenCheckActivity)
                render()
            }
        }
        val icon = if (result == "ok") R.drawable.ic_check_circle else if (s == null) R.drawable.ic_globe else R.drawable.ic_alert
        return settingRow(icon, getString(R.string.diag_send_title), words, button ?: if (ScreenCheck.sending) Spinner(this, 24) else null,
            if (s != null && result != "ok") Palette.warn else Palette.text2)
    }
}

/** One card of the report: its title and short rows. */
internal class ReportSection(val title: String) {
    val rows = ArrayList<Pair<String, String>>()

    fun view(c: Context): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
        background = Shapes.card(Radius.L)
        addView(c.label(Type.LABEL, Palette.text).apply { text = title })
        for ((name, value) in rows) {
            // a short value beside its name, a long one under it, with the card's width
            val long = value.length > 26 || name.length + value.length > 44
            val row = LinearLayout(c).apply {
                orientation = if (long) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = if (long) Gravity.NO_GRAVITY else Gravity.CENTER_VERTICAL
            }
            row.addView(c.label(Type.FOOTNOTE, Palette.text2, lines = 2).apply { text = name }, if (long) lp(MATCH, WRAP) else lp(0, WRAP, 1f))
            row.addView(c.label(Type.FOOTNOTE, Palette.text, lines = if (long) 6 else 1).apply {
                text = value
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
                fontFeatureSettings = "tnum"
            }, if (long) lp(MATCH, WRAP) else lp(WRAP, WRAP).apply { marginStart = Space.M.dp })
            addView(row, lp(MATCH, WRAP).apply { topMargin = Space.S.dp })
        }
    }
}

/**
 * The report in words: what the owner reads off the screen. The JSON that goes to the server holds much more (the raw
 * dumps); here each row is what a plan needs from it, and a value nobody could read says so.
 */
internal class ReportText(private val c: Context, private val r: JSONObject) {
    private fun s(id: Int) = c.getString(id)
    private val none get() = s(R.string.diag_none)

    /** "12", "12.5": no needless zero, western digits in both languages (as photographed, as compared). */
    private fun num(v: Any?): String = when (v) {
        null, JSONObject.NULL -> none
        is Int, is Long -> v.toString()
        is Number -> String.format(Locale.ROOT, "%.1f", v.toDouble()).removeSuffix(".0")
        else -> v.toString()
    }

    private fun yes(v: Boolean) = s(if (v) R.string.diag_yes else R.string.diag_no)

    private fun JSONObject.at(vararg path: String): Any? {
        var o: Any? = this
        for (p in path) o = (o as? JSONObject)?.opt(p) ?: return null
        return if (o === JSONObject.NULL) null else o
    }

    private fun text(vararg path: String): String? = r.at(*path)?.toString()?.takeIf { it.isNotBlank() }

    private fun mb(kb: Any?): String = (kb as? Number)?.let { String.format(Locale.ROOT, "%.0f MB", it.toDouble() / 1024) } ?: none

    private fun ReportSection.row(name: Int, value: String?) { if (value != null) rows.add(s(name) to value) }
    private fun ReportSection.row(name: String, value: String?) { if (value != null) rows.add(name to value) }

    /** The status of a test whose numbers are missing, when they are: said instead of an empty card. */
    private fun ReportSection.status(test: String) {
        val st = text("tests", test, "status") ?: return
        if (st != "ok") rows.add(test to statusWords(st, text("tests", test, "why") ?: text("tests", test, "error")))
    }

    private fun statusWords(st: String, why: String?): String = when (st) {
        "ok" -> s(R.string.diag_st_ok)
        "crashed" -> s(R.string.diag_st_crashed)
        "hung" -> s(R.string.diag_st_hung)
        "skipped" -> s(R.string.diag_st_skipped)
        "failed" -> s(R.string.diag_st_failed)
        "interrupted" -> s(R.string.diag_st_interrupted)
        else -> s(R.string.diag_st_not_run)
    } + (why?.let { " ($it)" } ?: "")

    /** "58 fps · p95 17 ms". */
    private fun frames(o: JSONObject?): String? {
        if (o == null) return null
        return "${num(o.opt("fps"))} fps · p50 ${num(o.opt("p50"))} · p95 ${num(o.opt("p95"))} ms"
    }

    fun sections(): List<ReportSection> = listOf(summary(), device(), capacity(), gpu(), fill(), memory(), map("surface", R.string.diag_sec_map_surface), map("texture", R.string.diag_sec_map_texture),
        motion(), voice(), network(), gps(), car(), settings(), trip(), tests())

    private fun summary() = ReportSection(s(R.string.diag_sec_summary)).apply {
        val total = r.at("b_capacity", "androidTotalMb")
        if (total != null) row(R.string.diag_ram, "${num(total)} MB · ${num(r.at("b_capacity", "androidAvailMb"))} MB")
        row(R.string.diag_gpu, text("c_gpu", "es2", "renderer"))
        val es3 = r.at("c_gpu", "es3", "ok") as? Boolean
        if (es3 != null) row(R.string.diag_es3, if (es3) text("c_gpu", "es3", "version") ?: yes(true) else yes(false))
        val modes = r.at("c_gpu", "fill", "modes") as? JSONArray
        if (modes != null) {
            for (i in 0 until modes.length()) {
                val m = modes.optJSONObject(i) ?: continue
                if (m.optString("name") == "blend x4") row(R.string.diag_fill_4, "${num(m.opt("fps"))} fps · ${num(m.opt("mpix"))} Mpx/s")
            }
        }
        (r.at("e_map", "surface", "cap60", "map") as? JSONObject)?.let { row(R.string.diag_map_surface, frames(it)) }
        (r.at("e_map", "texture", "cap60", "map") as? JSONObject)?.let { row(R.string.diag_map_texture, frames(it)) }
        val sf = r.at("e_map", "surface", "surfaceFlinger") as? JSONObject
        if (sf != null) {
            row(R.string.diag_layers, if (sf.optBoolean("available")) "HWC ${sf.optInt("hwc")} · GPU ${sf.optInt("client")}" else none)
        }
        row(R.string.diag_amri_memory, r.at("d_memory", "first", "TOTAL")?.let { "${mb(it)} · ${s(R.string.diag_graphics)} ${mb(r.at("d_memory", "first", "Graphics"))}" })
        row(R.string.diag_write_speed, r.at("b_capacity", "write", "mbPerSecond")?.let { "${num(it)} MB/s" })
        row(R.string.diag_root, (r.at("a_identity", "rootShell") as? Boolean)?.let { yes(it) })
        val bad = (r.optJSONObject("tests")?.let { t -> t.keys().asSequence().count { t.optJSONObject(it)?.optString("status") != "ok" } }) ?: 0
        row(R.string.diag_tests_bad, bad.toString())
    }

    private fun device() = ReportSection(s(R.string.diag_sec_device)).apply {
        row(R.string.diag_app, text("a_identity", "appVersion")?.let { "$it (${text("a_identity", "appCode")})" })
        row(R.string.diag_rom_pack, text("a_identity", "romVersion") ?: none)
        row(R.string.diag_rom_copy, (r.at("a_identity", "romCopy") as? JSONObject)?.let { "${it.optString("versionName")} (${it.optString("versionCode")})" } ?: none)
        row(R.string.diag_updated, (r.at("a_identity", "updatedSystemApp") as? Boolean)?.let { yes(it) })
        row(R.string.diag_fingerprint, text("a_identity", "fingerprint"))
        row(R.string.diag_kernel, text("a_identity", "kernel")?.take(90))
        row("ro.debuggable", text("a_identity", "props", "ro.debuggable") ?: none)
        row("SELinux", text("a_identity", "selinux"))
        row("sys.task.monitor.enable", text("a_identity", "props", "sys.task.monitor.enable") ?: none)
        row("sys.whitelist.enable", text("a_identity", "props", "sys.whitelist.enable") ?: none)
        row(R.string.diag_screen, (r.at("a_identity", "display") as? JSONObject)?.let { "${it.optInt("width")} x ${it.optInt("height")} · ${num(it.opt("refreshRate"))} Hz" })
    }

    private fun capacity() = ReportSection(s(R.string.diag_sec_capacity)).apply {
        row(R.string.diag_mem_total, r.at("b_capacity", "MemTotalKb")?.let { mb(it) })
        row(R.string.diag_mem_avail, r.at("b_capacity", "MemAvailableKb")?.let { mb(it) })
        row(R.string.diag_low_memory_at, r.at("b_capacity", "lowMemoryUnderMb")?.let { "${num(it)} MB" })
        row(R.string.diag_swap, r.at("b_capacity", "SwapTotalKb")?.let { mb(it) })
        row(R.string.diag_data_free, (r.at("b_capacity", "storage", "data") as? JSONObject)?.let { "${num(it.opt("freeMb"))} / ${num(it.opt("totalMb"))} MB" })
        row(R.string.diag_sd_free, (r.at("b_capacity", "storage", "external") as? JSONObject)?.let { "${num(it.opt("freeMb"))} / ${num(it.opt("totalMb"))} MB" })
        row(R.string.diag_cpu, text("b_capacity", "cpuModel"))
        val freq = r.at("b_capacity", "cpuFreq") as? JSONArray
        row(R.string.diag_cores, r.at("b_capacity", "cores")?.let { n ->
            val max = freq?.optJSONObject(0)?.optString("maxKhz")?.toLongOrNull()
            num(n) + if (max != null) " · ${max / 1000} MHz" else ""
        })
        (r.at("b_capacity", "thermal") as? JSONArray)?.let { t ->
            var hot = Double.NaN
            for (i in 0 until t.length()) t.optJSONObject(i)?.optDouble("c")?.let { if (hot.isNaN() || it > hot) hot = it }
            if (!hot.isNaN()) row(R.string.diag_temp, "${num(hot)} °C")
        }
        (r.at("b_capacity", "write") as? JSONObject)?.let { w ->
            row(R.string.diag_write_speed, if (w.has("mbPerSecond")) "${num(w.opt("mbPerSecond"))} MB/s (${w.optInt("mb")} MB)" else none)
        }
    }

    private fun gpu() = ReportSection(s(R.string.diag_sec_gpu)).apply {
        status("gl.es2")
        status("gl.es3")
        status("gl.formats")
        row(R.string.diag_vendor, text("c_gpu", "es2", "vendor"))
        row(R.string.diag_renderer, text("c_gpu", "es2", "renderer"))
        row(R.string.diag_gl_version, text("c_gpu", "es2", "version"))
        (r.at("c_gpu", "es3", "ok") as? Boolean)?.let { row(R.string.diag_es3, if (it) text("c_gpu", "es3", "version") ?: yes(true) else "${yes(false)} (${text("c_gpu", "es3", "why").orEmpty()})") }
        val has = (r.at("c_gpu", "es3", "has") ?: r.at("c_gpu", "es2", "has")) as? JSONObject
        fun real(name: String, label: String) {
            val right = r.at("c_gpu", "formats", name, "right") as? Boolean ?: return
            row(label, yes(right))
        }
        real("etc2Rgba", "ETC2")
        real("astc4x4", "ASTC 4x4")
        real("astc8x8", "ASTC 8x8")
        if (has != null) row("EGL_KHR_partial_update", yes(has.optBoolean("eglPartialUpdate")))
        row(R.string.diag_max_texture, r.at("c_gpu", "es2", "limits", "textureSize")?.let { num(it) })
    }

    private fun fill() = ReportSection(s(R.string.diag_sec_fill)).apply {
        status("gl.fill")
        val f = r.at("c_gpu", "fill") as? JSONObject ?: return@apply
        row(R.string.diag_surface_size, "${f.optInt("width")} x ${f.optInt("height")}")
        val modes = f.optJSONArray("modes") ?: return@apply
        for (i in 0 until modes.length()) {
            val m = modes.optJSONObject(i) ?: continue
            row(m.optString("name"), "${num(m.opt("fps"))} fps · ${num(m.opt("mpix"))} Mpx/s")
        }
        row(R.string.diag_etc1_encode, f.opt("etc1EncodeMs")?.let { "${num(it)} ms" })
        f.optString("error").takeIf { it.isNotEmpty() }?.let { row(R.string.diag_error, it) }
    }

    private fun memory() = ReportSection(s(R.string.diag_sec_memory)).apply {
        if (r.at("d_memory", "available") == false) row(R.string.diag_amri_memory, s(R.string.diag_needs_root))
        row(R.string.diag_pss_first, r.at("d_memory", "first", "TOTAL")?.let { mb(it) })
        row(R.string.diag_pss_second, r.at("d_memory", "second", "TOTAL")?.let { mb(it) })
        row(R.string.diag_graphics, r.at("d_memory", "first", "Graphics")?.let { mb(it) })
        row(R.string.diag_native, r.at("d_memory", "first", "NativeHeap")?.let { mb(it) })
        row(R.string.diag_java, r.at("d_memory", "first", "JavaHeap")?.let { mb(it) })
        row(R.string.diag_hwui_gpu, text("d_memory", "hwuiGpuMemory"))
        (r.at("d_memory", "hwuiTextureLines") as? JSONArray)?.let { t ->
            if (t.length() > 0) row(R.string.diag_hwui_textures, (0 until t.length()).joinToString(" · ") { t.optString(it).trim() })
        }
        // (where memtrack reads 0 the Graphics row says nothing: the driver's own first lines, when root can read them)
        text("d_memory", "mali")?.let { m -> row("Mali", m.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.take(2).joinToString(" · ").take(120)) }
        row(R.string.diag_dexopt, text("a_identity", "dexopt")?.let { d ->
            Regex("status=([^\\]]+)").findAll(d).joinToString(" · ") { it.groupValues[1] }.ifEmpty { none }
        })
        row(R.string.diag_threads, r.at("d_memory", "threads")?.let { num(it) })
        (r.at("d_logs", "displayed") as? JSONArray)?.let { d ->
            if (d.length() > 0) row(R.string.diag_displayed, d.optString(d.length() - 1).substringAfter("Displayed ", d.optString(d.length() - 1)).takeLast(70))
        }
        (r.at("d_logs", "crashLines") as? JSONArray)?.let { row(R.string.diag_crash_lines, it.length().toString()) }
        row(R.string.diag_tombstones, text("d_logs", "ourTombstones")?.let { t -> t.lineSequence().count { it.startsWith("== ") }.toString() } ?: "0")
    }

    private fun map(which: String, title: Int) = ReportSection(s(title)).apply {
        status("map.$which")
        val m = r.at("e_map", which) as? JSONObject ?: return@apply
        row(R.string.diag_tiles, m.optString("tiles").takeIf { it.isNotEmpty() }?.let {
            s(when { it.startsWith("offline") -> R.string.diag_tiles_offline; it.endsWith("directly") -> R.string.diag_tiles_direct; else -> R.string.diag_tiles_server })
        })
        if (m.has("tilesCame") && !m.optBoolean("tilesCame") || m.optInt("features", 1) == 0) row(R.string.diag_warning, s(R.string.diag_map_empty))
        else row(R.string.diag_tiles_ms, m.opt("tilesMs")?.let { "${num(it)} ms" })
        row(R.string.diag_style_ms, m.opt("styleMs")?.let { "${num(it)} ms" })
        val fast = m.optJSONObject("cap60")
        val slow = m.optJSONObject("cap30")
        row(R.string.diag_map_60, frames(fast?.optJSONObject("map")))
        row(R.string.diag_hwui_fps, fast?.optJSONObject("hwui")?.let { "${num(it.opt("fps"))} fps" })
        row(R.string.diag_cpu_proc_sys, fast?.let { "${num(it.opt("cpuPct"))} % · ${num(it.opt("sysPct"))} %" })
        row(R.string.diag_map_30, frames(slow?.optJSONObject("map")))
        row(R.string.diag_cpu_proc_sys, slow?.let { "${num(it.opt("cpuPct"))} % · ${num(it.opt("sysPct"))} %" })
        val mem = m.optJSONObject("mem")
        row(R.string.diag_pss_after, mem?.opt("pssKb")?.let { mb(it) })
        row(R.string.diag_graphics, mem?.opt("graphicsKb")?.let { mb(it) })
        (m.opt("surfaceFlinger") as? JSONObject)?.let { sf ->
            val shown = sf.optJSONObject("latency")
            row(R.string.diag_shown_fps, shown?.opt("fps")?.let { "${num(it)} fps" })
            row(R.string.diag_layers, if (sf.optBoolean("available")) "HWC ${sf.optInt("hwc")} · GPU ${sf.optInt("client")}" else s(R.string.diag_needs_root))
        }
    }

    private fun motion() = ReportSection(s(R.string.diag_sec_motion)).apply {
        fun one(key: String, label: Int) {
            status("ui.$key")
            val h = r.at("f_motion", key, "hwui") as? JSONObject ?: return
            row(label, "p50 ${num(h.opt("p50"))} · p95 ${num(h.opt("p95"))} ms · ${num(h.opt("jankPct"))} %")
        }
        one("slide_layer", R.string.diag_m_slide_layer)
        one("slide_plain", R.string.diag_m_slide_plain)
        one("crossfade", R.string.diag_m_crossfade)
        one("bigtext", R.string.diag_m_bigtext)
        one("glass9", R.string.diag_m_glass9)
        one("fill", R.string.diag_m_fill)
        if (rows.isNotEmpty()) row(R.string.diag_motion_key, s(R.string.diag_motion_key_sub))
    }

    private fun voice() = ReportSection(s(R.string.diag_sec_voice)).apply {
        fun list(a: Any?): String = (a as? JSONArray)?.let { x -> (0 until x.length()).joinToString(" · ") { x.optString(it).substringBefore('/') } }?.ifEmpty { none } ?: none
        if (r.at("g_voice") == null) return@apply
        row(R.string.diag_tts, list(r.at("g_voice", "ttsEngines")))
        row(R.string.diag_recognisers, list(r.at("g_voice", "recognisers")))
        row("com.google.android.tts", text("g_voice", "googleTts") ?: s(R.string.diag_not_installed))
        row(R.string.diag_google_app, text("g_voice", "googleApp") ?: s(R.string.diag_not_installed))
        (r.at("g_voice", "ttsArabic") as? Number)?.let { row(R.string.diag_arabic_voice, yes(it.toInt() >= 0)) }
    }

    private fun network() = ReportSection(s(R.string.diag_sec_network)).apply {
        if (r.at("h_network") == null) return@apply
        row(R.string.diag_wifi, (r.at("h_network", "wifiOn") as? Boolean)?.let { yes(it) })
        row(R.string.diag_ip, text("h_network", "ip"))
        row(R.string.diag_gateway, text("h_network", "gateway"))
        row(R.string.diag_rtt, r.at("h_network", "gatewayPing", "avgMs")?.let { "${num(it)} ms" } ?: r.at("h_network", "gatewayPing")?.let { s(R.string.diag_no_answer) })
        row(R.string.diag_port, text("h_network", "port47100")?.let {
            when {
                it == "closed" -> s(R.string.diag_port_closed)
                it == "no answer" -> s(R.string.diag_no_answer)
                it.contains("linked") -> s(R.string.diag_port_linked)
                else -> s(R.string.diag_port_open)
            }
        })
        row(R.string.diag_naghm, text("h_network", "naghmNews") ?: none)
        (r.at("h_network", "hosts") as? JSONObject)?.let { hosts ->
            for (h in hosts.keys()) {
                val o = hosts.optJSONObject(h) ?: continue
                row(h, if (o.optBoolean("reached")) "${o.optInt("http")} · ${num(o.opt("totalMs"))} ms" else s(R.string.diag_no_answer))
            }
        }
    }

    private fun gps() = ReportSection(s(R.string.diag_sec_gps)).apply {
        val g = r.at("i_gps") as? JSONObject ?: return@apply
        if (!g.optBoolean("permission")) row(R.string.diag_gps, s(R.string.diag_gps_no_permission))
        else if (g.optBoolean("noFix")) row(R.string.diag_gps, s(R.string.diag_gps_none))
        else {
            row(R.string.diag_gps_rate, "${num(g.opt("rateHz"))} Hz · ${num(g.opt("fixes"))}")
            row(R.string.diag_gps_age, "${num(g.opt("lastAgeMs"))} ms")
            row(R.string.diag_gps_accuracy, g.opt("accuracyM")?.let { "${num(it)} m" })
        }
        row(R.string.diag_gps_sats, "${g.optInt("satellitesUsed")} / ${g.optInt("satellitesSeen")}")
    }

    private fun car() = ReportSection(s(R.string.diag_sec_car)).apply {
        if (r.at("j_car") == null) return@apply
        row(R.string.diag_gear, (text("j_car", "gear") ?: none) + when (text("j_car", "gearSource")) {
            "GUESS" -> " · " + s(R.string.diag_gear_guess)
            "HELD" -> " · " + s(R.string.diag_gear_held)
            "MOTION" -> " · " + s(R.string.diag_gear_motion)
            "HANDBRAKE" -> " · " + s(R.string.diag_gear_handbrake)
            "REVERSE" -> " · " + s(R.string.diag_gear_reverse)
            "CAN" -> " · CAN"
            else -> ""
        })
        row(R.string.diag_handbrake, when (text("j_car", "handbrake")) {
            "pulled" -> s(R.string.diag_hb_pulled)
            "released" -> s(R.string.diag_hb_released)
            null -> null
            else -> s(R.string.diag_hb_never)
        })
        row(R.string.diag_bt_on, text("j_car", "bluetoothOn")?.let { yes(it == "1") } ?: none)
        row(R.string.diag_bt_state, text("j_car", "firmwareSettings", "key_bt_a2dp_state") ?: none)
        val keys = r.at("j_car", "wheel", "keys") as? JSONArray
        row(R.string.diag_keys, if (keys == null || keys.length() == 0) s(R.string.diag_keys_none) else (0 until keys.length()).joinToString(" · ") {
            val k = keys.optJSONObject(it)
            "${k?.optInt("code")} ${k?.optString("name").orEmpty()}".trim()
        })
        (r.at("j_car", "wheel", "rawInput") as? JSONArray)?.let { if (it.length() > 0) row(R.string.diag_keys_raw, it.length().toString()) }
    }

    /** K: by their names in the code; a switch never touched is at its default and is not here. */
    private fun settings() = ReportSection(s(R.string.diag_sec_settings)).apply {
        row(R.string.diag_system_language, text("k_settings", "systemLanguage"))
        val set = r.at("k_settings", "set") as? JSONObject ?: return@apply
        for (k in set.keys().asSequence().sorted()) row(k, set.opt(k)?.toString()?.take(60))
        (r.at("k_settings", "system") as? JSONObject)?.let { g ->
            for (k in g.keys().asSequence().sorted()) if (!g.isNull(k)) row(k, g.opt(k)?.toString()?.take(60))
        }
    }

    private fun trip() = ReportSection(s(R.string.diag_sec_trip)).apply {
        val t = r.opt("trip") as? JSONArray ?: return@apply
        row(R.string.diag_trip_lines, t.length().toString())
        val last = t.optJSONObject(t.length() - 1) ?: return@apply
        row(R.string.diag_trip_last, "${num(last.opt("cpuPct"))} % · ${mb(last.opt("pssKb"))} · ${num(last.opt("availMb"))} MB")
    }

    private fun tests() = ReportSection(s(R.string.diag_sec_tests)).apply {
        val t = r.optJSONObject("tests") ?: return@apply
        var ok = 0
        for (name in t.keys()) {
            val o = t.optJSONObject(name) ?: continue
            if (o.optString("status") == "ok") ok++
            else row(name, statusWords(o.optString("status"), o.optString("why").takeIf { it.isNotEmpty() }))
        }
        row(R.string.diag_tests_ok, ok.toString())
        row(R.string.diag_took, "${r.optInt("tookSec")} s")
        (r.optString("previousRunDiedIn")).takeIf { it.isNotEmpty() }?.let { row(R.string.diag_previous_died, it) }
    }
}
