package com.abdllh.aura.settings

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.nav.MapExtras
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
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
 * Settings › Navigation: the owner's additions to the map ([MapExtras]: cameras, bumps, signals, streets' limits):
 * how many are on the unit, from when, and the button that fetches the newest.
 */
class ExtrasCard(private val ctx: Context) {
    private val status: AText = ctx.label(Type.FOOTNOTE, Palette.text2, lines = 3)
    private val update = ABtn(ctx).apply {
        kind = ABtn.Kind.PRIMARY
        setOnClickListener { fetch() }
    }
    private val spinner = Spinner(ctx, 24).apply { visibility = View.GONE }
    private val changed: () -> Unit = { refresh(null) }
    private var busy = false

    val view: View = ctx.block(R.drawable.ic_speed_camera, ctx.getString(R.string.extras_title), null, LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(update, lp(WRAP, WRAP))
        addView(spinner, lp(24.dp, 24.dp).apply { marginStart = Space.L.dp })
    }, live = status)

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
        update.isEnabled = !busy
        spinner.visibility = if (busy) View.VISIBLE else View.GONE
        // (what was published: the cameras marked from this car are counted on their own row below)
        val n = MapExtras.count(MapExtras.Kind.CAMERA) + MapExtras.count(MapExtras.Kind.BUMP) + MapExtras.count(MapExtras.Kind.SIGNAL) + MapExtras.speeds.size
        // (as text: the app's digits are 0-9 in Arabic too, and %d would write ٠-٩)
        val counts = if (n == 0) ctx.getString(R.string.extras_none) else ctx.getString(R.string.extras_summary,
            "${MapExtras.count(MapExtras.Kind.CAMERA)}", "${MapExtras.count(MapExtras.Kind.BUMP)}", "${MapExtras.count(MapExtras.Kind.SIGNAL)}", "${MapExtras.speeds.size}")
        val at = MapExtras.updatedAt
        val since = if (n > 0 && at > 0) "\n" + ctx.getString(R.string.extras_updated, SimpleDateFormat("d/M/yyyy HH:mm", Locale.ENGLISH).format(Date(at))) else ""
        status.text = counts + since + (if (note != null) "\n$note" else "")
    }
}
