package com.abdllh.aura.settings

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.nav.MapExtras
import com.abdllh.aura.ui.AText
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
 * Settings › Navigation: the owner's additions to the map ([MapExtras]: cameras, bumps, signals, streets' limits):
 * how many are on the unit, from when, and the button that fetches the newest.
 */
class ExtrasCard(private val ctx: Context) {
    private val status: AText = ctx.label(15f, Palette.text2, Fonts.REGULAR, lines = 3)
    private val update: AText = ctx.label(16f, Palette.onColor(Palette.accent), Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
        background = Shapes.accent(16f)
        setPadding(22.dp, 0, 22.dp, 0)
        isClickable = true
        pressScale(0.95f)
        setOnClickListener { fetch() }
    }
    private val changed: () -> Unit = { refresh(null) }
    private var busy = false

    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp, 16.dp, 20.dp, 16.dp)
        background = Shapes.card(18f)
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ctx.iconView(R.drawable.ic_speed_camera, 27, Palette.text2), lp(27.dp, 27.dp).apply { marginEnd = 18.dp })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ctx.label(18.5f, Palette.text, Fonts.MEDIUM).apply { setText(R.string.extras_title) }, lp(MATCH, WRAP))
        texts.addView(status, lp(MATCH, WRAP).apply { topMargin = 3.dp })
        head.addView(texts, lp(0, WRAP, 1f))
        addView(head, lp(MATCH, WRAP))
        addView(update, lp(WRAP, 50.dp).apply { topMargin = 14.dp })
    }

    fun attach() {
        MapExtras.removeListener(changed) // (the page attaches when built and again when shown)
        MapExtras.addListener(changed)
        refresh(null)
    }

    fun detach() = MapExtras.removeListener(changed)

    private fun fetch() {
        if (busy) return
        busy = true
        refresh(null)
        MapExtras.refresh { ok, _ ->
            busy = false
            refresh(ctx.getString(if (ok) R.string.extras_done else R.string.extras_failed))
        }
    }

    /** [note]: what the last press of the button came to, shown under the counts until the next one. */
    private fun refresh(note: String?) {
        update.setText(if (busy) R.string.extras_updating else R.string.extras_update)
        update.alpha = if (busy) 0.6f else 1f
        val n = MapExtras.spots.size + MapExtras.speeds.size
        // (as text: the app's digits are 0-9 in Arabic too, and %d would write ٠-٩)
        val counts = if (n == 0) ctx.getString(R.string.extras_none) else ctx.getString(R.string.extras_summary,
            "${MapExtras.count(MapExtras.Kind.CAMERA)}", "${MapExtras.count(MapExtras.Kind.BUMP)}", "${MapExtras.count(MapExtras.Kind.SIGNAL)}", "${MapExtras.speeds.size}")
        val at = MapExtras.updatedAt
        val since = if (n > 0 && at > 0) "\n" + ctx.getString(R.string.extras_updated, SimpleDateFormat("d/M/yyyy HH:mm", Locale.ENGLISH).format(Date(at))) else ""
        status.text = counts + since + (if (note != null) "\n$note" else "")
    }
}
