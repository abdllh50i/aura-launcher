package com.abdllh.aura.settings

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.nav.offline.OfflineMaps
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Confirm
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings › Navigation: the offline map of the Eastern Province ([OfflineMaps]): what is on the unit, the download
 * with its progress, pause, and delete. The bar is the update page's (it glides between reports; until 1.11 a
 * weighted pair of views that jumped and laid the card out again at every report).
 */
class OfflineCard(private val act: SettingsActivity, private val ctx: Context) {
    private val status: AText = ctx.label(Type.FOOTNOTE, Palette.text2, lines = 2)
    private val bar = ProgressBar(ctx)
    private val main = ABtn(ctx).apply { kind = ABtn.Kind.PRIMARY }
    private val remove = ABtn(ctx).apply { kind = ABtn.Kind.TONAL; setText(R.string.off_delete) }
    private val listener: () -> Unit = { refresh() }

    val view: View = ctx.block(R.drawable.ic_download, ctx.getString(R.string.off_title), null, LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(bar, lp(MATCH, WRAP).apply { bottomMargin = Space.L.dp }) // (gone, its gap goes with it)
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(main, lp(WRAP, WRAP))
        buttons.addView(remove, lp(WRAP, WRAP).apply { marginStart = Space.M.dp })
        addView(buttons, lp(MATCH, WRAP))
    }, live = status)

    fun attach() {
        OfflineMaps.listener = listener
        refresh()
    }

    fun detach() {
        if (OfflineMaps.listener === listener) OfflineMaps.listener = null
    }

    fun refresh() {
        val s = OfflineMaps.status
        val mb = { b: Long -> String.format(Locale.ROOT, "%d", (b + 524_288) / 1_048_576) }
        val pct = (s.fraction * 100).toInt()
        status.text = when (s.phase) {
            OfflineMaps.Phase.NONE -> ctx.getString(R.string.off_none)
            OfflineMaps.Phase.DOWNLOADING -> ctx.getString(R.string.off_downloading, pct.toString(), mb(s.sizeBytes)) +
                (if (s.error == "waiting") "\n" + ctx.getString(R.string.off_waiting) else "")
            OfflineMaps.Phase.INDEXING -> ctx.getString(R.string.off_indexing, pct.toString())
            OfflineMaps.Phase.READY -> ctx.getString(R.string.off_ready, mb(s.sizeBytes),
                if (s.updatedAt > 0) SimpleDateFormat("d/M/yyyy", Locale.ENGLISH).format(Date(s.updatedAt)) else "")
            OfflineMaps.Phase.PAUSED -> ctx.getString(R.string.off_paused, mb(s.sizeBytes))
            OfflineMaps.Phase.FAILED -> ctx.getString(if (s.error == "space") R.string.off_failed_space else R.string.off_failed_net)
        }
        val busy = s.phase == OfflineMaps.Phase.DOWNLOADING || s.phase == OfflineMaps.Phase.INDEXING
        bar.visibility = if (busy || s.phase == OfflineMaps.Phase.PAUSED) View.VISIBLE else View.GONE
        // (indexing reports no share before its first step: the bar sweeps until it does)
        if (s.phase == OfflineMaps.Phase.INDEXING && s.fraction <= 0f) bar.setIndeterminate() else bar.set(s.fraction)
        main.visibility = if (s.phase == OfflineMaps.Phase.READY) View.GONE else View.VISIBLE
        main.setText(when (s.phase) {
            OfflineMaps.Phase.DOWNLOADING, OfflineMaps.Phase.INDEXING -> R.string.off_pause
            OfflineMaps.Phase.PAUSED, OfflineMaps.Phase.FAILED -> R.string.off_resume
            else -> R.string.off_download
        })
        // (Pause is a second choice while it downloads; Download and Resume are the card's action)
        val k = if (busy) ABtn.Kind.TONAL else ABtn.Kind.PRIMARY
        if (main.kind != k) main.kind = k
        main.setOnClickListener { if (busy) OfflineMaps.pause() else OfflineMaps.download() }
        remove.visibility = if (s.phase != OfflineMaps.Phase.NONE && !busy) View.VISIBLE else View.GONE
        remove.setOnClickListener {
            Confirm.show(act, ctx.getString(R.string.off_delete_title), ctx.getString(R.string.off_delete_text), ctx.getString(R.string.off_delete), true) {
                OfflineMaps.delete { refresh() }
            }
        }
    }
}
