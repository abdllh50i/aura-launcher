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
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
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
 * plus settings on the end side. The three parts are a row, so the tiles are centred in the room the two sides
 * leave: centred on the whole bar (until 1.11), six pins and Apps ran under the volume capsule.
 */
class Dock(ctx: Context, private val host: HomeHost) : FrameLayout(ctx) {

    /** A dock entry; [id] is its pin (null for the Apps button, which is always there). [style] null: the app's own icon. */
    private class App(val id: String?, val label: String, val style: DockTiles.Style?, val pkg: String?, val action: () -> Unit)

    private val pinsListener: () -> Unit = { post { rebuild() } }

    val apps = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private val volText: AText = ctx.label(Type.LABEL, Palette.text, gravity = Gravity.CENTER)
    private val volIcon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val badge: View
    private var builtKey = "" // what the tiles were built from (see refresh)

    init {
        setBackgroundColor(Palette.dock)
        // set off from the screen above by a hairline on both themes: a shadow along the whole width was redrawn with
        // every frame of the map above it, and could not be seen on the dark theme anyway
        addView(View(ctx).apply { setBackgroundColor(Palette.divider) }, flp(MATCH, 1.dp.coerceAtLeast(1), Gravity.TOP))

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // the start edge is the clock's above it; the end edge lines up with the map panel's
            setPaddingRelative(20.dp, 0, Space.M.dp, 0)
        }

        // start: controls
        row.addView(button(ctx, R.drawable.ic_sliders, R.string.ctl_title, round = true) { host.openControls() }, lp(60.dp, 60.dp))

        val mid = FrameLayout(ctx)
        mid.addView(apps, flp(WRAP, MATCH, Gravity.CENTER))
        row.addView(mid, lp(0, MATCH, 1f).apply { marginStart = Space.M.dp; marginEnd = Space.M.dp })

        // end: volume ‹ level › in one capsule (always left = quieter, like a physical knob) and settings
        val end = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val vol = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = LAYOUT_DIRECTION_LTR
            background = Shapes.pill(Palette.card2)
        }
        vol.addView(button(ctx, R.drawable.ic_chevron_left, R.string.ctl_vol_down) { step(-1) }, lp(Touch.MIN.dp, 60.dp))
        val level = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            pressScale(Motion.PRESS_ICON)
            setOnClickListener { Controls.toggleMute(context); syncVolume() }
        }
        level.addView(volIcon.apply { setColorFilter(Palette.text) }, lp(26.dp, 26.dp))
        level.addView(volText, lp(38.dp, WRAP).apply { marginStart = Space.XS.dp })
        vol.addView(level, lp(WRAP, 60.dp))
        vol.addView(button(ctx, R.drawable.ic_chevron_right, R.string.ctl_vol_up) { step(+1) }, lp(Touch.MIN.dp, 60.dp))
        end.addView(vol, lp(WRAP, WRAP))

        val settingsWrap = FrameLayout(ctx)
        settingsWrap.addView(button(ctx, R.drawable.ic_settings, R.string.dock_settings, round = true) { host.openSettings(0) }, flp(60.dp, 60.dp, Gravity.CENTER))
        badge = View(ctx).apply { background = Shapes.oval(Palette.accent, Palette.dock, 2) }
        settingsWrap.addView(badge, flp(14.dp, 14.dp, Gravity.TOP or Gravity.END).apply { setMargins(0, 2.dp, 2.dp, 0); marginEnd = 2.dp })
        end.addView(settingsWrap, lp(64.dp, 64.dp).apply { marginStart = Space.M.dp })
        row.addView(end, lp(WRAP, MATCH))
        addView(row, flp(MATCH, MATCH))
        rebuild()
    }

    /** [round]: a button of its own (a tonal circle); otherwise a bare icon inside a capsule that has the surface. */
    private fun button(ctx: Context, icon: Int, desc: Int, round: Boolean = false, onClick: () -> Unit): FrameLayout = FrameLayout(ctx).apply {
        background = if (round) Shapes.tonalOval() else Shapes.ghost(Radius.PILL)
        isClickable = true
        contentDescription = ctx.getString(desc)
        pressScale(Motion.PRESS_ICON)
        setOnClickListener { onClick() }
        val s = if (round) 28 else 26
        addView(ctx.iconView(icon, s, Palette.text).apply { layoutParams = flp(s.dp, s.dp, Gravity.CENTER) })
    }

    private fun step(dir: Int) {
        Controls.stepVolume(context, dir)
        syncVolume()
    }

    /** The volume as the car's own step number (what the steering-wheel keys and the knob move by one). */
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
        // a deliberate dark neutral (ink on the light theme): the slate grey it replaces read as a disabled button
        // beside the bright app tiles
        val apps = if (Palette.dark) DockTiles.Style(R.drawable.ic_grid, 0xFF3A3F4A.toInt(), 0xFF262A31.toInt(), R.string.dock_apps)
        else DockTiles.Style(R.drawable.ic_grid, 0xFF2B2F36.toInt(), 0xFF15171B.toInt(), R.string.dock_apps)
        list.add(App(null, ctx.getString(R.string.dock_apps), apps, null) { host.openDrawer() })
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

    /**
     * Back on the home screen: the tiles are built again only when what they show has changed. Rebuilding them on
     * every return (until 1.11) asked the package manager about every pin and laid the dock out again in the very
     * frames of the window animation, so coming home hitched. Pins changing and apps installed or removed elsewhere
     * call [rebuild] themselves.
     */
    fun refresh() {
        if (key() != builtKey) rebuild()
        else {
            syncVolume()
            refreshBadge()
        }
    }

    private fun key() = DockPins.get().joinToString("|") + "|" + Prefs.dockLabels

    fun rebuild() {
        builtKey = key()
        apps.removeAllViews()
        for ((i, it) in items().withIndex()) {
            apps.addView(tile(context, it), lp(WRAP, MATCH).apply { if (i > 0) marginStart = Space.S.dp })
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
        val labels = Prefs.dockLabels
        val size = if (labels) 52 else 62
        // a fixed width: a long name is cut short instead of widening its tile and pushing the others aside
        minimumWidth = (size + if (labels) 24 else 16).dp
        setPadding(Space.S.dp, 0, Space.S.dp, 0)
        isClickable = true
        contentDescription = item.label
        pressScale(Motion.PRESS_CONTROL)
        setOnClickListener { item.action() }
        val id = item.id
        if (id != null) setOnLongClickListener { v ->
            com.abdllh.aura.ui.Menu.show(v, listOf(com.abdllh.aura.ui.Menu.Item(R.drawable.ic_close, ctx.getString(R.string.dock_unpin)) { DockPins.remove(id) }))
            true
        }
        val s = item.style
        // flat tiles on both themes: a shadow in each tile's own colour was one more caster per tile redrawn under the
        // live map's frames, and the gradients read on white without it
        val icon: View = if (s != null) FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(s.from, s.to)).apply { cornerRadius = (size * 0.3f).dp }
            val g = if (labels) 28 else 33
            addView(ctx.iconView(s.icon, g, 0xFFFFFFFF.toInt()).apply { layoutParams = flp(g.dp, g.dp, Gravity.CENTER) })
        } else ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pkg = item.pkg
            if (pkg != null) IconLoader.get(ctx, pkg, size.dp) { b -> if (b != null) setImageBitmap(b) }
        }
        addView(icon, lp(size.dp, size.dp))
        if (labels) {
            addView(ctx.label(Type.CAPTION, Palette.text2, gravity = Gravity.CENTER).apply { text = item.label }, lp(MATCH, WRAP).apply { topMargin = Space.XS.dp })
        }
    }

}

