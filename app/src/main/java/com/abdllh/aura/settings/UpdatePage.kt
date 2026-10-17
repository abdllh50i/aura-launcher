package com.abdllh.aura.settings

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.update.GitHub
import com.abdllh.aura.update.Release
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.Fmt
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

/** Software update screen: check GitHub, show release notes, download, verify, install. */
class UpdatePage(private val act: SettingsActivity) : Page(R.string.set_update, R.drawable.ic_download) {
    private lateinit var holder: LinearLayout
    private lateinit var ctx: Context
    private var observer: ((UpdateManager.State) -> Unit)? = null
    private var lastKind: Class<*>? = null
    // the download's bar and line, changed in place on every report (UpdateManager sends one every 400 ms)
    private var dlBar: ProgressBar? = null
    private var dlText: AText? = null

    override fun build(ctx: Context): View {
        this.ctx = ctx
        lastKind = null
        val col = ctx.pageColumn()

        // header card
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(20.dp, Space.L.dp, 20.dp, Space.L.dp)
            background = Shapes.card()
        }
        val badge = FrameLayout(ctx).apply { background = Shapes.oval(Palette.accentSoft()) }
        badge.addView(ctx.iconView(R.drawable.ic_layers, 28, Palette.accentFg).apply { layoutParams = FrameLayout.LayoutParams(28.dp, 28.dp, Gravity.CENTER) })
        head.addView(badge, lp(Touch.MIN.dp, Touch.MIN.dp))
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ctx.label(Type.TITLE, Palette.text).apply { text = "${ctx.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}" })
        t.addView(ctx.label(Type.FOOTNOTE, Palette.text2).apply {
            text = ctx.getString(R.string.upd_build_line, BuildConfig.VERSION_CODE.toString(), ctx.getString(if (Prefs.updateBeta) R.string.upd_channel_beta else R.string.upd_channel_stable))
        }, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
        head.addView(t, lp(0, WRAP, 1f).apply { marginStart = 18.dp })
        col.addRow(head)

        // dynamic status area
        holder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(20.dp, Space.L.dp + Space.XS.dp, 20.dp, Space.L.dp + Space.XS.dp)
            background = Shapes.card()
        }
        col.addRow(holder)

        // options
        col.addSection(ctx.getString(R.string.upd_options))
        col.addRow(ctx.group(
            ctx.switchRow(R.drawable.ic_refresh, ctx.getString(R.string.upd_auto), ctx.getString(R.string.upd_auto_sub), Prefs.updateAuto) { Prefs.updateAuto = it },
            ctx.switchRow(R.drawable.ic_zap, ctx.getString(R.string.upd_beta), ctx.getString(R.string.upd_beta_sub), Prefs.updateBeta) {
                Prefs.updateBeta = it
                UpdateManager.forget() // a release found on the other channel must not stay on offer
                act.rebuild()
            },
            ctx.settingRow(R.drawable.ic_github, ctx.getString(R.string.upd_source), Prefs.updateRepo, ctx.iconView(R.drawable.ic_edit, 22, Palette.text3)) {
                InputDialog.show(ctx, ctx.getString(R.string.upd_source), "owner/repo", Prefs.updateRepo) { v ->
                    if (GitHub.isValidRepo(v)) { Prefs.updateRepo = v.trim(); UpdateManager.forget(); act.rebuild() }
                    else com.abdllh.aura.ui.Snack.show(ctx, R.string.upd_err_repo, long = true)
                }
            }
        ))
        col.addHint(ctx.getString(R.string.upd_footer))
        return col
    }

    override fun onShow() {
        val o: (UpdateManager.State) -> Unit = { render(it) }
        observer = o
        UpdateManager.observe(o)
    }

    override fun onHide() {
        observer?.let { UpdateManager.unobserve(it) }
        observer = null
    }

    // ------------------------------------------------------------------------------------ states
    private fun render(s: UpdateManager.State) {
        if (!::holder.isInitialized) return
        val kind = s.javaClass
        // The download's next report: the bar glides on and the line changes, nothing else. Built anew each time
        // (until 1.11), the bar grew from empty 2.5 times a second and Cancel was a new button under a finger that
        // had pressed the old one.
        val bar = dlBar
        if (s is UpdateManager.State.Downloading && lastKind == kind && bar != null && bar.isAttachedToWindow) {
            val f = fraction(s)
            bar.set(f)
            val line = progressLine(s, f)
            if (dlText?.text?.toString() != line) dlText?.text = line
            return
        }
        dlBar = null
        dlText = null
        holder.removeAllViews()
        when (s) {
            is UpdateManager.State.Idle -> idle()
            is UpdateManager.State.Checking -> busy(ctx.getString(R.string.upd_checking), null)
            is UpdateManager.State.UpToDate -> upToDate(s.checkedAt, s.noReleases)
            is UpdateManager.State.Available -> available(s.release)
            is UpdateManager.State.Downloading -> downloading(s)
            is UpdateManager.State.Verifying -> busy(ctx.getString(R.string.upd_verifying), s.release.tag)
            is UpdateManager.State.Installing -> busy(ctx.getString(R.string.upd_installing), s.release.tag)
            is UpdateManager.State.Failed -> failed(s)
        }
        if (lastKind != kind) {
            // (not on the first showing: the card is there with the page, not arriving after it)
            if (lastKind != null) holder.enter(dy = Space.S.toFloat())
            lastKind = kind
        }
    }

    private fun idle() {
        title(ctx.getString(R.string.upd_ready))
        subtitle(if (Prefs.lastUpdateCheck > 0) ctx.getString(R.string.upd_last_checked, Fmt.dateTime(Prefs.lastUpdateCheck)) else ctx.getString(R.string.upd_never_checked))
        checkButton()
    }

    private fun upToDate(at: Long, noReleases: Boolean) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ctx.iconView(R.drawable.ic_check_circle, 28, Palette.success), lp(28.dp, 28.dp).apply { marginEnd = Space.M.dp })
        row.addView(ctx.label(Type.TITLE, Palette.text).apply { setText(R.string.upd_uptodate) })
        holder.addView(row, lp(MATCH, WRAP))
        subtitle(ctx.getString(R.string.upd_last_checked, Fmt.dateTime(at)))
        if (noReleases) subtitle(ctx.getString(R.string.upd_no_releases, Prefs.updateRepo))
        checkButton()
    }

    private fun available(r: Release) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ctx.iconView(R.drawable.ic_download, 28, Palette.accentFg), lp(28.dp, 28.dp).apply { marginEnd = Space.M.dp })
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ctx.label(Type.TITLE, Palette.text).apply { text = ctx.getString(R.string.upd_available_title, r.tag.removePrefix("v")) })
        val meta = buildString {
            append(Fmt.bytes(r.apkSize))
            if (r.prerelease) append("  ·  ").append(ctx.getString(R.string.upd_channel_beta))
        }
        t.addView(ctx.label(Type.FOOTNOTE, Palette.text2).apply { text = meta }, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
        row.addView(t, lp(0, WRAP, 1f))
        holder.addView(row, lp(MATCH, WRAP))

        // What's new, in the page itself: the first lines, and the rest on "Show all". (It was a small box that
        // scrolled on its own inside the scrolling page, where a swipe got stuck in one or the other.)
        val notes = cleanNotes(r.notes)
        if (notes.isNotEmpty()) {
            holder.addView(ctx.label(Type.CAPTION, Palette.text3).apply { setText(R.string.upd_notes) }, lp(MATCH, WRAP).apply { topMargin = Space.L.dp })
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Shapes.rect(Palette.card2, Radius.M)
                setPaddingRelative(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp)
            }
            val text = ctx.label(Type.FOOTNOTE, Palette.text, lines = NOTES_LINES).apply { this.text = notes; ellipsize = TextUtils.TruncateAt.END }
            box.addView(text, lp(MATCH, WRAP))
            val more = ABtn(ctx).apply {
                kind = ABtn.Kind.GHOST
                setText(R.string.upd_notes_more)
                visibility = View.GONE
                setOnClickListener {
                    text.maxLines = Int.MAX_VALUE
                    visibility = View.GONE
                }
            }
            box.addView(more, lp(WRAP, WRAP).apply { topMargin = Space.M.dp })
            // (offered once the notes are laid out and found longer than what shows)
            text.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    text.removeOnLayoutChangeListener(this)
                    val lay = text.layout ?: return
                    if (lay.lineCount > 0 && lay.getEllipsisCount(lay.lineCount - 1) > 0) more.visibility = View.VISIBLE
                }
            })
            holder.addView(box, lp(MATCH, WRAP).apply { topMargin = Space.S.dp })
        }
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(ABtn(ctx).apply { kind = ABtn.Kind.PRIMARY; setText(R.string.upd_download); setOnClickListener { UpdateManager.downloadAndInstall() } }, lp(WRAP, WRAP))
        actions.addView(ABtn(ctx).apply { kind = ABtn.Kind.TONAL; setText(R.string.upd_check_again); setOnClickListener { UpdateManager.check() } }, lp(WRAP, WRAP).apply { marginStart = Space.M.dp })
        holder.addView(actions, lp(MATCH, WRAP).apply { topMargin = Space.L.dp })
    }

    private fun fraction(s: UpdateManager.State.Downloading) = if (s.total > 0) s.done.toFloat() / s.total else 0f

    private fun progressLine(s: UpdateManager.State.Downloading, f: Float): String {
        val speed = if (s.bytesPerSec > 0) "  ·  ${Fmt.bytes(s.bytesPerSec)}/s" else ""
        return "${Fmt.percent(f)}  ·  ${Fmt.bytes(s.done)} / ${Fmt.bytes(s.total)}$speed"
    }

    private fun downloading(s: UpdateManager.State.Downloading) {
        val f = fraction(s)
        title(ctx.getString(R.string.upd_downloading, s.release.tag.removePrefix("v")))
        val bar = ProgressBar(ctx).apply { set(f) }
        holder.addView(bar, lp(MATCH, WRAP).apply { topMargin = Space.L.dp })
        val line = ctx.label(Type.FOOTNOTE, Palette.text2).apply { text = progressLine(s, f); fontFeatureSettings = "tnum" }
        holder.addView(line, lp(MATCH, WRAP).apply { topMargin = Space.S.dp })
        holder.addView(ABtn(ctx).apply { kind = ABtn.Kind.GHOST; setText(R.string.btn_cancel); setOnClickListener { UpdateManager.cancel() } }, lp(WRAP, WRAP).apply { topMargin = Space.L.dp })
        dlBar = bar
        dlText = line
    }

    private fun busy(text: String, tag: String?) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(Spinner(ctx, 28), lp(28.dp, 28.dp).apply { marginEnd = Space.M.dp })
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ctx.label(Type.TITLE, Palette.text).apply { this.text = text })
        if (tag != null) t.addView(ctx.label(Type.FOOTNOTE, Palette.text2).apply { this.text = tag.removePrefix("v") }, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
        row.addView(t, lp(0, WRAP, 1f))
        holder.addView(row, lp(MATCH, WRAP))
        holder.addView(ProgressBar(ctx).apply { setIndeterminate() }, lp(MATCH, WRAP).apply { topMargin = Space.L.dp })
    }

    private fun failed(s: UpdateManager.State.Failed) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        row.addView(ctx.iconView(R.drawable.ic_alert, 28, Palette.warn), lp(28.dp, 28.dp).apply { marginEnd = Space.M.dp })
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ctx.label(Type.TITLE, Palette.text).apply { setText(R.string.upd_failed) })
        t.addView(ctx.label(Type.FOOTNOTE, Palette.text2, lines = 0).apply { text = s.message }, lp(MATCH, WRAP).apply { topMargin = Space.XS.dp })
        row.addView(t, lp(0, WRAP, 1f))
        holder.addView(row, lp(MATCH, WRAP))
        holder.addView(ABtn(ctx).apply {
            kind = ABtn.Kind.PRIMARY
            setText(R.string.upd_retry)
            setOnClickListener { if (s.release != null) UpdateManager.downloadAndInstall() else UpdateManager.check() }
        }, lp(WRAP, WRAP).apply { topMargin = Space.L.dp })
    }

    // ------------------------------------------------------------------------------------ bits
    private fun title(text: String) {
        holder.addView(ctx.label(Type.TITLE, Palette.text).apply { this.text = text }, lp(MATCH, WRAP))
    }

    private fun subtitle(text: String) {
        holder.addView(ctx.label(Type.FOOTNOTE, Palette.text2).apply { this.text = text }, lp(MATCH, WRAP).apply { topMargin = Space.XS.dp })
    }

    private fun checkButton() {
        holder.addView(ABtn(ctx).apply {
            kind = ABtn.Kind.PRIMARY
            setText(R.string.upd_check)
            setOnClickListener { UpdateManager.check() }
        }, lp(WRAP, WRAP).apply { topMargin = Space.L.dp })
    }

    private fun cleanNotes(raw: String): String = raw
        .replace("\r", "")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("(?im)^\\s*sha-?256.*$"), "")
        .trim()
        .take(1500)

    private companion object {
        /** Lines of the release notes shown before "Show all". */
        const val NOTES_LINES = 8
    }
}
