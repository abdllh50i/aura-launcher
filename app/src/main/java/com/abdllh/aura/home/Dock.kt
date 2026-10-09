package com.abdllh.aura.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.system.Controls
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

/**
 * Bottom bar: the controls button on the start side, colourful app tiles in the middle, and volume (‹ level ›)
 * plus settings on the end side.
 */
class Dock(ctx: Context, private val host: HomeHost) : FrameLayout(ctx) {

    /** A dock entry; [id] is its pin (null for the Apps button, which is always there). [style] null: the app's own icon. */
    private class App(val id: String?, val label: String, val style: DockTiles.Style?, val pkg: String?, val action: () -> Unit)

    private val pinsListener: () -> Unit = { post { rebuild() } }

    val apps = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private val volText: AText = ctx.label(18f, Palette.text, Fonts.MEDIUM, gravity = Gravity.CENTER)
    private val volIcon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val badge: View

    init {
        setBackgroundColor(Palette.dock)
        addView(View(ctx).apply { setBackgroundColor(if (Palette.dark) 0xFF17191C.toInt() else Palette.stroke) }, flp(MATCH, 1.dp.coerceAtLeast(1), Gravity.TOP))
        setPaddingRelative(14.dp, 0, 10.dp, 0)

        // start: controls
        val controls = button(ctx, R.drawable.ic_sliders, R.string.ctl_title) { host.openControls() }
        addView(controls, flp(68.dp, 68.dp, Gravity.START or Gravity.CENTER_VERTICAL))

        addView(apps, flp(WRAP, MATCH, Gravity.CENTER))

        // end: volume ‹ level › (always left = quieter, like a physical knob) and settings
        val end = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val vol = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = LAYOUT_DIRECTION_LTR }
        vol.addView(button(ctx, R.drawable.ic_chevron_left, R.string.ctl_vol_down) { step(-1) }, lp(60.dp, 68.dp))
        val level = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { Controls.toggleMute(context); syncVolume() }
        }
        level.addView(volIcon.apply { setColorFilter(Palette.text) }, lp(28.dp, 28.dp))
        level.addView(volText, lp(40.dp, WRAP).apply { marginStart = 4.dp })
        vol.addView(level, lp(WRAP, 68.dp))
        vol.addView(button(ctx, R.drawable.ic_chevron_right, R.string.ctl_vol_up) { step(+1) }, lp(60.dp, 68.dp))
        end.addView(vol, lp(WRAP, WRAP))

        val settingsWrap = FrameLayout(ctx)
        settingsWrap.addView(button(ctx, R.drawable.ic_settings, R.string.dock_settings) { host.openSettings(0) }, flp(68.dp, 68.dp, Gravity.CENTER))
        badge = View(ctx).apply { background = Shapes.oval(Palette.accent, Palette.dock, 2) }
        settingsWrap.addView(badge, flp(13.dp, 13.dp, Gravity.TOP or Gravity.END).apply { setMargins(0, 12.dp, 12.dp, 0); marginEnd = 12.dp })
        end.addView(settingsWrap, lp(68.dp, 68.dp).apply { marginStart = 6.dp })
        addView(end, flp(WRAP, MATCH, Gravity.END or Gravity.CENTER_VERTICAL))
        rebuild()
    }

    private fun button(ctx: Context, icon: Int, desc: Int, onClick: () -> Unit): FrameLayout = FrameLayout(ctx).apply {
        background = Shapes.ghost(16f)
        isClickable = true
        contentDescription = ctx.getString(desc)
        pressScale(0.88f)
        setOnClickListener { onClick() }
        addView(ctx.iconView(icon, 31, Palette.text).apply { layoutParams = flp(31.dp, 31.dp, Gravity.CENTER) })
    }

    private fun step(dir: Int) {
        Controls.stepVolume(context, dir)
        syncVolume()
    }

    fun syncVolume() {
        val v = Controls.volume(context)
        volText.text = "$v"
        volIcon.setImageResource(
            when {
                Controls.muted(context) || v == 0 -> R.drawable.ic_volume_off
                v < Controls.volumeMax(context) / 2 -> R.drawable.ic_volume_low
                else -> R.drawable.ic_volume
            }
        )
    }

    /** The pinned apps (long-press in the app drawer adds one, long-press here removes it), then the Apps button. */
    private fun items(): List<App> {
        val ctx = context
        val list = ArrayList<App>()
        for (id in DockPins.get()) {
            val style = DockTiles.style(id)
            when (id) {
                DockPins.MUSIC -> list.add(App(id, ctx.getString(R.string.dock_music), style, null) { com.abdllh.aura.music.MusicActivity.open(ctx) })
                DockPins.MAPS -> list.add(App(id, ctx.getString(R.string.dock_nav), style, null) {
                    if (!Actions.nav(ctx)) host.toast(ctx.getString(R.string.err_no_nav))
                })
                else -> {
                    if (!AppRepo.isInstalled(ctx, id)) continue // uninstalled since it was pinned
                    val label = style?.let { ctx.getString(it.label) } ?: AppRepo.label(ctx, id)
                    list.add(App(id, label, style, id) { if (!AppRepo.launch(ctx, id)) host.toast(ctx.getString(R.string.err_app_missing)) })
                }
            }
        }
        list.add(App(null, ctx.getString(R.string.dock_apps), DockTiles.Style(R.drawable.ic_grid, 0xFF6C7380.toInt(), 0xFF4A505B.toInt(), R.string.dock_apps), null) {
            host.openDrawer()
        })
        return list
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DockPins.addListener(pinsListener)
    }

    override fun onDetachedFromWindow() {
        DockPins.removeListener(pinsListener)
        super.onDetachedFromWindow()
    }

    fun rebuild() {
        apps.removeAllViews()
        for ((i, it) in items().withIndex()) {
            apps.addView(tile(context, it), lp(WRAP, MATCH).apply { if (i > 0) marginStart = 8.dp })
        }
        syncVolume()
        refreshBadge()
    }

    fun refreshBadge() {
        badge.visibility = if (UpdateManager.hasUpdateBadge()) VISIBLE else GONE
    }

    private fun tile(ctx: Context, item: App): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(8.dp, 0, 8.dp, 0)
        isClickable = true
        contentDescription = item.label
        pressScale(0.86f)
        setOnClickListener { item.action() }
        val id = item.id
        if (id != null) setOnLongClickListener { v ->
            com.abdllh.aura.ui.Menu.show(v, listOf(com.abdllh.aura.ui.Menu.Item(R.drawable.ic_close, ctx.getString(R.string.dock_unpin)) { DockPins.remove(id) }))
            true
        }
        val labels = Prefs.dockLabels
        val size = if (labels) 52 else 62
        val s = item.style
        val icon: View = if (s != null) FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(s.from, s.to)).apply { cornerRadius = (size * 0.3f).dp }
            addView(ctx.iconView(s.icon, if (labels) 28 else 33, 0xFFFFFFFF.toInt()).apply {
                layoutParams = flp((if (labels) 28 else 33).dp, (if (labels) 28 else 33).dp, Gravity.CENTER)
            })
        } else ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pkg = item.pkg
            if (pkg != null) IconLoader.get(ctx, pkg, size.dp) { b -> if (b != null) setImageBitmap(b) }
        }
        addView(icon, lp(size.dp, size.dp))
        if (labels) {
            addView(ctx.label(12.5f, Palette.text2, Fonts.REGULAR, gravity = Gravity.CENTER).apply {
                text = item.label
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, lp(WRAP, WRAP).apply { topMargin = 4.dp })
        }
    }
}
