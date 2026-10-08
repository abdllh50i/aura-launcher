package com.abdllh.aura.settings

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
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

    override fun build(ctx: Context): View {
        this.ctx = ctx
        val col = ctx.pageColumn()

        // header card
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22.dp, 18.dp, 22.dp, 18.dp)
            background = Shapes.card(22f)
        }
        val badge = FrameLayout(ctx).apply { background = Shapes.oval(Palette.withAlpha(Palette.accent, 0.18f), Palette.withAlpha(Palette.accent, 0.5f)) }
        badge.addView(ctx.iconView(R.drawable.ic_layers, 28, Palette.accent).apply { layoutParams = FrameLayout.LayoutParams(28.dp, 28.dp, Gravity.CENTER) })
        head.addView(badge, lp(60.dp, 60.dp))
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ctx.label(22f, Palette.text, Fonts.MEDIUM).apply { text = "Aura ${BuildConfig.VERSION_NAME}" })
        t.addView(ctx.label(13.5f, Palette.text2, Fonts.REGULAR).apply {
            text = ctx.getString(R.string.upd_build_line, BuildConfig.VERSION_CODE, ctx.getString(if (Prefs.updateBeta) R.string.upd_channel_beta else R.string.upd_channel_stable))
        }, lp(MATCH, WRAP).apply { topMargin = 4.dp })
        head.addView(t, lp(0, WRAP, 1f).apply { marginStart = 18.dp })
        col.addView(head, lp(MATCH, WRAP))

        // dynamic status area
        holder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 20.dp, 22.dp, 20.dp)
            background = Shapes.rect(Palette.card, 22f, Palette.stroke)
        }
        col.addRow(holder, 14)

        // options
        col.addView(ctx.sectionTitle(ctx.getString(R.string.upd_options)), lp(MATCH, WRAP).apply { topMargin = 22.dp })
        col.addRow(ctx.switchRow(R.drawable.ic_refresh, ctx.getString(R.string.upd_auto), ctx.getString(R.string.upd_auto_sub), Prefs.updateAuto) { Prefs.updateAuto = it }, 8)
        col.addRow(ctx.switchRow(R.drawable.ic_zap, ctx.getString(R.string.upd_beta), ctx.getString(R.string.upd_beta_sub), Prefs.updateBeta) {
            Prefs.updateBeta = it
            act.rebuild()
        }, 10)
        col.addRow(ctx.settingRow(R.drawable.ic_github, ctx.getString(R.string.upd_source), Prefs.updateRepo, ctx.iconView(R.drawable.ic_edit, 22, Palette.text3)) {
            InputDialog.show(ctx, ctx.getString(R.string.upd_source), "owner/repo", Prefs.updateRepo) { v ->
                if (GitHub.isValidRepo(v)) { Prefs.updateRepo = v; Prefs.availableTag = ""; act.rebuild() }
                else android.widget.Toast.makeText(ctx, R.string.upd_err_repo, android.widget.Toast.LENGTH_LONG).show()
            }
        }, 10)
        col.addRow(ctx.hint(ctx.getString(R.string.upd_footer)), 14)
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
        holder.removeAllViews()
        when (s) {
            is UpdateManager.State.Idle -> idle(null)
            is UpdateManager.State.Checking -> busy(ctx.getString(R.string.upd_checking), null, indeterminate = true)
            is UpdateManager.State.UpToDate -> upToDate(s.checkedAt, s.noReleases)
            is UpdateManager.State.Available -> available(s.release)
            is UpdateManager.State.Downloading -> downloading(s)
            is UpdateManager.State.Verifying -> busy(ctx.getString(R.string.upd_verifying), s.release.tag, indeterminate = true)
            is UpdateManager.State.Installing -> busy(ctx.getString(R.string.upd_installing), s.release.tag, indeterminate = true)
            is UpdateManager.State.Failed -> failed(s)
        }
        if (lastKind != kind) {
            lastKind = kind
            holder.alpha = 0f
            holder.translationY = 8.dp.toFloat()
            holder.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }

    private fun idle(error: String?) {
        title(ctx.getString(R.string.upd_ready), Palette.text)
        subtitle(if (Prefs.lastUpdateCheck > 0) ctx.getString(R.string.upd_last_checked, Fmt.dateTime(Prefs.lastUpdateCheck)) else ctx.getString(R.string.upd_never_checked))
        checkButton()
    }

    private fun upToDate(at: Long, noReleases: Boolean) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ctx.iconView(R.drawable.ic_check_circle, 30, Palette.success), lp(30.dp, 30.dp).apply { marginEnd = 14.dp })
        row.addView(ctx.label(21f, Palette.text, Fonts.MEDIUM).apply { setText(R.string.upd_uptodate) })
        holder.addView(row, lp(MATCH, WRAP))
        subtitle(ctx.getString(R.string.upd_last_checked, Fmt.dateTime(at)))
        if (noReleases) subtitle(ctx.getString(R.string.upd_no_releases, Prefs.updateRepo))
        checkButton()
    }

    private fun available(r: Release) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ctx.iconView(R.drawable.ic_download, 30, Palette.accent), lp(30.dp, 30.dp).apply { marginEnd = 14.dp })
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ctx.label(21f, Palette.text, Fonts.MEDIUM).apply { text = ctx.getString(R.string.upd_available_title, r.tag.removePrefix("v")) })
        val meta = buildString {
            append(Fmt.bytes(r.apkSize))
            if (r.prerelease) append("  ·  ").append(ctx.getString(R.string.upd_channel_beta))
        }
        t.addView(ctx.label(13.5f, Palette.text2, Fonts.REGULAR).apply { text = meta }, lp(MATCH, WRAP).apply { topMargin = 3.dp })
        row.addView(t, lp(0, WRAP, 1f))
        holder.addView(row, lp(MATCH, WRAP))

        val notes = cleanNotes(r.notes)
        if (notes.isNotEmpty()) {
            holder.addView(ctx.label(13f, Palette.text3, Fonts.MEDIUM).apply { setText(R.string.upd_notes) }, lp(MATCH, WRAP).apply { topMargin = 16.dp })
            val box = ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                background = Shapes.rect(Palette.card2, 14f, Palette.stroke)
                setPadding(16.dp, 12.dp, 16.dp, 12.dp)
                addView(ctx.label(14.5f, Palette.text, Fonts.REGULAR, lines = 0).apply { text = notes; setLineSpacing(0f, 1.2f) })
            }
            holder.addView(box, lp(MATCH, 150.dp).apply { topMargin = 8.dp })
        }
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(ABtn(ctx).apply { kind = ABtn.Kind.PRIMARY; setText(R.string.upd_download); setOnClickListener { UpdateManager.downloadAndInstall() } }, lp(WRAP, WRAP))
        actions.addView(ABtn(ctx).apply { kind = ABtn.Kind.TONAL; setText(R.string.upd_check_again); setOnClickListener { UpdateManager.check() } }, lp(WRAP, WRAP).apply { marginStart = 12.dp })
        holder.addView(actions, lp(MATCH, WRAP).apply { topMargin = 18.dp })
    }

    private fun downloading(s: UpdateManager.State.Downloading) {
        val frac = if (s.total > 0) s.done.toFloat() / s.total else 0f
        title(ctx.getString(R.string.upd_downloading, s.release.tag.removePrefix("v")), Palette.text)
        val bar = ProgressBar(ctx).apply { set(frac) }
        holder.addView(bar, lp(MATCH, 10.dp).apply { topMargin = 16.dp })
        val speed = if (s.bytesPerSec > 0) "  ·  ${Fmt.bytes(s.bytesPerSec)}/s" else ""
        val text = "${Fmt.percent(frac)}  ·  ${Fmt.bytes(s.done)} / ${Fmt.bytes(s.total)}$speed"
        holder.addView(ctx.label(13.5f, Palette.text2, Fonts.REGULAR).apply { this.text = text }, lp(MATCH, WRAP).apply { topMargin = 10.dp })
        holder.addView(ABtn(ctx).apply { kind = ABtn.Kind.GHOST; setText(R.string.btn_cancel); setOnClickListener { UpdateManager.cancel() } }, lp(WRAP, WRAP).apply { topMargin = 16.dp })
    }

    private fun busy(text: String, tag: String?, indeterminate: Boolean) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(Spinner(ctx, 30), lp(30.dp, 30.dp).apply { marginEnd = 16.dp })
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ctx.label(20f, Palette.text, Fonts.MEDIUM).apply { this.text = text })
        if (tag != null) t.addView(ctx.label(13.5f, Palette.text2, Fonts.REGULAR).apply { this.text = tag.removePrefix("v") }, lp(MATCH, WRAP).apply { topMargin = 3.dp })
        row.addView(t, lp(0, WRAP, 1f))
        holder.addView(row, lp(MATCH, WRAP))
        if (indeterminate) holder.addView(ProgressBar(ctx).apply { setIndeterminate() }, lp(MATCH, 10.dp).apply { topMargin = 18.dp })
    }

    private fun failed(s: UpdateManager.State.Failed) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        row.addView(ctx.iconView(R.drawable.ic_alert, 28, Palette.warn), lp(28.dp, 28.dp).apply { marginEnd = 14.dp; topMargin = 2.dp })
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(ctx.label(20f, Palette.text, Fonts.MEDIUM).apply { setText(R.string.upd_failed) })
        t.addView(ctx.label(14.5f, Palette.text2, Fonts.REGULAR, lines = 0).apply { text = s.message; setLineSpacing(0f, 1.15f) }, lp(MATCH, WRAP).apply { topMargin = 6.dp })
        row.addView(t, lp(0, WRAP, 1f))
        holder.addView(row, lp(MATCH, WRAP))
        holder.addView(ABtn(ctx).apply {
            kind = ABtn.Kind.PRIMARY
            setText(R.string.upd_retry)
            setOnClickListener { if (s.release != null) UpdateManager.downloadAndInstall() else UpdateManager.check() }
        }, lp(WRAP, WRAP).apply { topMargin = 18.dp })
    }

    // ------------------------------------------------------------------------------------ bits
    private fun title(text: String, color: Int) {
        holder.addView(ctx.label(21f, color, Fonts.MEDIUM).apply { this.text = text }, lp(MATCH, WRAP))
    }

    private fun subtitle(text: String) {
        holder.addView(ctx.label(14f, Palette.text2, Fonts.REGULAR).apply { this.text = text }, lp(MATCH, WRAP).apply { topMargin = 6.dp })
    }

    private fun checkButton() {
        holder.addView(ABtn(ctx).apply {
            kind = ABtn.Kind.PRIMARY
            setText(R.string.upd_check)
            setOnClickListener { UpdateManager.check() }
        }, lp(WRAP, WRAP).apply { topMargin = 18.dp })
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
}
