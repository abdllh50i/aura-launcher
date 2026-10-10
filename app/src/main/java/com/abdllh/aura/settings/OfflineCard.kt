package com.abdllh.aura.settings

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.nav.offline.OfflineMaps
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Confirm
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings › Navigation: the offline map of the Eastern Province ([OfflineMaps]): what is on the unit, the download
 * with its progress, pause, and delete.
 */
class OfflineCard(private val act: SettingsActivity, private val ctx: Context) {
    private val status: AText = ctx.label(15f, Palette.text2, Fonts.REGULAR, lines = 2)
    private val fill = View(ctx)
    private val rest = View(ctx)
    private val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Shapes.rect(Palette.card2, 4f)
        clipToOutline = true
        addView(fill, lp(0, MATCH, 0f))
        addView(rest, lp(0, MATCH, 1f))
    }
    private val main: AText = button(true)
    private val remove: AText = button(false).apply { setText(R.string.off_delete) }
    private val listener: () -> Unit = { refresh() }

    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp, 16.dp, 20.dp, 16.dp)
        background = Shapes.card(18f)
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ctx.iconView(R.drawable.ic_download, 27, Palette.text2), lp(27.dp, 27.dp).apply { marginEnd = 18.dp })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ctx.label(18.5f, Palette.text, Fonts.MEDIUM).apply { setText(R.string.off_title) }, lp(MATCH, WRAP))
        texts.addView(status, lp(MATCH, WRAP).apply { topMargin = 3.dp })
        head.addView(texts, lp(0, WRAP, 1f))
        addView(head, lp(MATCH, WRAP))
        addView(bar, lp(MATCH, 8.dp).apply { topMargin = 14.dp })
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(main, lp(WRAP, 50.dp))
        buttons.addView(remove, lp(WRAP, 50.dp).apply { marginStart = 12.dp })
        addView(buttons, lp(MATCH, WRAP).apply { topMargin = 14.dp })
    }

    private fun button(primary: Boolean): AText = ctx.label(16f, if (primary) Palette.onColor(Palette.accent) else Palette.text, Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
        background = if (primary) Shapes.accent(16f) else Shapes.tonal(16f)
        setPadding(22.dp, 0, 22.dp, 0)
        isClickable = true
        pressScale(0.95f)
    }

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
        fill.background = Shapes.rect(Palette.accent, 4f)
        (fill.layoutParams as LinearLayout.LayoutParams).weight = s.fraction
        (rest.layoutParams as LinearLayout.LayoutParams).weight = 1f - s.fraction
        bar.requestLayout()
        main.visibility = if (s.phase == OfflineMaps.Phase.READY) View.GONE else View.VISIBLE
        main.setText(when (s.phase) {
            OfflineMaps.Phase.DOWNLOADING, OfflineMaps.Phase.INDEXING -> R.string.off_pause
            OfflineMaps.Phase.PAUSED, OfflineMaps.Phase.FAILED -> R.string.off_resume
            else -> R.string.off_download
        })
        main.setOnClickListener { if (busy) OfflineMaps.pause() else OfflineMaps.download() }
        remove.visibility = if (s.phase != OfflineMaps.Phase.NONE && !busy) View.VISIBLE else View.GONE
        remove.setOnClickListener {
            Confirm.show(act, ctx.getString(R.string.off_delete_title), ctx.getString(R.string.off_delete_text), ctx.getString(R.string.off_delete), true) {
                OfflineMaps.delete { refresh() }
            }
        }
    }
}
