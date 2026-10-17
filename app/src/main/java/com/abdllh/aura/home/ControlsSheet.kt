package com.abdllh.aura.home

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.settings.SettingsActivity
import com.abdllh.aura.system.Controls
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.AuraSlider
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Sheet
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Theme
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
 * "Controls" sheet: brightness and volume, appearance, mute, screen off and shortcuts to Wi-Fi, Bluetooth, car and
 * settings. A shortcut that opens another screen leaves the sheet open under it: it goes when the home screen stops
 * (HomeActivity), so its closing does not compete with the other screen's opening.
 */
class ControlsSheet(ctx: Context, private val host: HomeHost) : Sheet(ctx, false) {
    private val brightness = AuraSlider(ctx).apply { max = 100; setIcon(R.drawable.ic_sun) }
    private val volume = AuraSlider(ctx).apply { setIcon(R.drawable.ic_volume) }
    private val brightValue: AText = ctx.label(Type.BODY, Palette.text2).apply { fontFeatureSettings = "tnum" }
    private val volValue: AText = ctx.label(Type.BODY, Palette.text2).apply { fontFeatureSettings = "tnum" }
    private val updatePill: LinearLayout
    private val updateText: AText = ctx.label(Type.BUTTON, Palette.accentFg)

    private class Tile(val view: LinearLayout, val icon: ImageView, val text: AText)

    private var themeTile: Tile? = null
    private var muteTile: Tile? = null
    private var wifiTile: Tile? = null

    // Brightness is a system setting (a provider write per value): a drag writes at most every BRIGHT_GAP_MS and its
    // last value when the finger lifts, instead of on every 1 % step.
    private var brightSent = -1
    private var brightSentAt = 0L
    private var askedWrite = false
    private val brightLater = Runnable { writeBrightness(brightness.progress) }

    init {
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.XL.dp, Space.XS.dp, Space.XL.dp, Space.XL.dp)
        }

        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ctx.label(Type.HEADLINE).apply { setText(R.string.ctl_title) }, lp(0, WRAP, 1f))
        updatePill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(Space.L.dp, 0, 20.dp, 0)
            isClickable = true
            pressScale(Motion.PRESS_CONTROL)
            background = Shapes.accentSoft(Radius.PILL)
            setOnClickListener { host.openSettings(SettingsActivity.PAGE_UPDATE) }
            addView(ctx.iconView(R.drawable.ic_download, 22, Palette.accentFg), lp(22.dp, 22.dp))
            addView(updateText, lp(WRAP, WRAP).apply { marginStart = Space.S.dp })
        }
        head.addView(updatePill, lp(WRAP, Touch.MIN.dp).apply { marginEnd = Space.M.dp })
        val closeBtn = FrameLayout(ctx).apply {
            background = Shapes.tonalOval()
            isClickable = true
            contentDescription = ctx.getString(R.string.btn_cancel)
            pressScale(Motion.PRESS_ICON)
            setOnClickListener { close() }
            addView(ctx.iconView(R.drawable.ic_close, 24, Palette.text).apply { layoutParams = flp(24.dp, 24.dp, Gravity.CENTER) })
        }
        head.addView(closeBtn, lp(Touch.MIN.dp, Touch.MIN.dp))
        content.addView(head, lp(MATCH, WRAP))

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val sliders = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        sliders.addView(sliderBlock(ctx, R.string.ctl_brightness, brightness, brightValue), lp(MATCH, WRAP))
        sliders.addView(sliderBlock(ctx, R.string.ctl_volume, volume, volValue), lp(MATCH, WRAP).apply { topMargin = Space.L.dp })
        body.addView(sliders, lp(0, WRAP, 1f))
        body.addView(grid(ctx), lp(0, WRAP, 1.3f).apply { marginStart = Space.XL.dp })
        content.addView(body, lp(MATCH, WRAP).apply { topMargin = Space.M.dp })
        panel.addView(content, lp(MATCH, WRAP))

        brightness.onChange = { v, user ->
            brightValue.text = "$v%"
            if (user) {
                removeCallbacks(brightLater)
                val wait = BRIGHT_GAP_MS - (SystemClock.uptimeMillis() - brightSentAt)
                if (wait <= 0) writeBrightness(v) else postDelayed(brightLater, wait)
            }
        }
        brightness.onStop = { v ->
            removeCallbacks(brightLater)
            writeBrightness(v)
        }
        // the car's own step number, as the dock shows it and the steering-wheel keys move it
        volume.onChange = { v, user ->
            if (user) Controls.setVolume(context, v)
            volValue.text = "$v"
        }
    }

    private fun writeBrightness(v: Int) {
        if (v == brightSent) return
        brightSentAt = SystemClock.uptimeMillis()
        if (Controls.setBrightnessPercent(context, v)) { brightSent = v; return }
        if (askedWrite) return // (once per open, not at every step of the drag)
        askedWrite = true
        host.toast(context.getString(R.string.err_write_settings), outlives = true)
        Controls.requestWriteSettings(context)
    }

    private fun sliderBlock(ctx: Context, title: Int, slider: AuraSlider, value: AText): LinearLayout {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ctx.label(Type.LABEL).apply { setText(title) }, lp(0, WRAP, 1f))
        row.addView(value, lp(WRAP, WRAP))
        col.addView(row, lp(MATCH, WRAP))
        col.addView(slider, lp(MATCH, Touch.LARGE.dp).apply { topMargin = Space.S.dp })
        return col
    }

    private fun grid(ctx: Context): LinearLayout {
        val tiles = ArrayList<Tile>()
        themeTile = tile(ctx, R.drawable.ic_contrast, R.string.theme_auto) { cycleTheme() }.also { tiles.add(it) }
        muteTile = tile(ctx, R.drawable.ic_volume_off, R.string.ctl_mute) { Controls.toggleMute(context); syncVolume() }.also { tiles.add(it) }
        if (Actions.hasScreenOff(ctx)) tiles.add(tile(ctx, R.drawable.ic_power, R.string.ctl_screen_off) { close(); Actions.screenOff(context) })
        wifiTile = tile(ctx, R.drawable.ic_wifi, R.string.ctl_wifi) { Actions.wifi(context) }.also { tiles.add(it) }
        tiles.add(tile(ctx, R.drawable.ic_bluetooth, R.string.ctl_bluetooth) { Actions.bluetooth(context) })
        if (AppRepo.isInstalled(ctx, Known.MYCAR) || AppRepo.isInstalled(ctx, Known.CAR_SETTING)) {
            tiles.add(tile(ctx, R.drawable.ic_car, R.string.ctl_car) { if (!Actions.car(context)) host.toast(context.getString(R.string.err_app_missing)) })
        }
        if (AppRepo.isInstalled(ctx, Known.CAM360)) {
            tiles.add(tile(ctx, R.drawable.ic_camera, R.string.dock_camera) { AppRepo.launch(context, Known.CAM360) })
        }
        tiles.add(tile(ctx, R.drawable.ic_settings, R.string.dock_settings) { host.openSettings(0) })

        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val cols = 4
        var row: LinearLayout? = null
        for ((i, t) in tiles.withIndex()) {
            if (i % cols == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, lp(MATCH, WRAP).apply { if (i > 0) topMargin = Space.M.dp })
            }
            row!!.addView(t.view, lp(0, TILE_DP.dp, 1f).apply { if (i % cols != 0) marginStart = Space.M.dp })
        }
        val rem = tiles.size % cols
        if (rem != 0) for (k in 0 until cols - rem) row!!.addView(View(ctx), lp(0, TILE_DP.dp, 1f).apply { marginStart = Space.M.dp })
        return grid
    }

    private fun tile(ctx: Context, icon: Int, label: Int, onClick: () -> Unit): Tile {
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Space.XS.dp, 0, Space.XS.dp, 0)
            isClickable = true
            pressScale(Motion.PRESS_CONTROL)
            setOnClickListener { onClick() }
        }
        val img = ctx.iconView(icon, 30, Palette.text)
        val txt = ctx.label(Type.FOOTNOTE, Palette.text2, gravity = Gravity.CENTER).apply { setText(label) }
        v.addView(img, lp(30.dp, 30.dp))
        v.addView(txt, lp(WRAP, WRAP).apply { topMargin = Space.S.dp })
        val t = Tile(v, img, txt)
        style(t, false)
        return t
    }

    private fun style(t: Tile, active: Boolean) {
        t.view.background = when {
            active -> Shapes.accentSoft(Radius.M)
            // on the white light-theme sheet a tile's edge shows by a hairline (its tone alone is a few levels off)
            Palette.dark -> Shapes.tonal(Radius.M)
            else -> Shapes.pressable(Shapes.rect(Palette.card2, Radius.M, Palette.stroke), Shapes.rect(Palette.card3, Radius.M, Palette.stroke))
        }
        t.icon.setColorFilter(if (active) Palette.accentFg else Palette.text)
        t.text.setTextColor(if (active) Palette.accentFg else Palette.text2)
    }

    private fun cycleTheme() {
        Prefs.theme = when (Prefs.theme) {
            Theme.AUTO -> Theme.DARK
            Theme.DARK -> Theme.LIGHT
            else -> Theme.AUTO
        }
        syncTheme()
        host.restyle()
    }

    private fun syncTheme() {
        val t = themeTile ?: return
        when (Prefs.theme) {
            Theme.DARK -> { t.icon.setImageResource(R.drawable.ic_moon); t.text.setText(R.string.theme_dark) }
            Theme.LIGHT -> { t.icon.setImageResource(R.drawable.ic_sun); t.text.setText(R.string.theme_light) }
            else -> { t.icon.setImageResource(R.drawable.ic_contrast); t.text.setText(R.string.theme_auto) }
        }
    }

    fun syncVolume() {
        volume.max = Controls.volumeMax(context)
        val v = Controls.volume(context)
        volume.setProgress(v)
        volValue.text = "$v"
        val muted = Controls.muted(context) || v == 0
        volume.setIcon(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        muteTile?.let { style(it, Controls.muted(context)) }
    }

    fun refresh() {
        val b = Controls.brightnessPercent(context)
        brightness.setProgress(b, false)
        brightValue.text = "$b%"
        brightSent = b
        askedWrite = false
        syncVolume()
        syncTheme()
        wifiTile?.let { style(it, Actions.wifiConnected(context)) }
        val has = UpdateManager.hasUpdateBadge()
        updatePill.visibility = if (has) VISIBLE else GONE
        if (has) updateText.text = context.getString(R.string.ctl_update_available, Prefs.availableTag.removePrefix("v"))
    }

    override fun onOpen() = refresh()

    private companion object {
        const val TILE_DP = 100
        const val BRIGHT_GAP_MS = 50L
    }
}
