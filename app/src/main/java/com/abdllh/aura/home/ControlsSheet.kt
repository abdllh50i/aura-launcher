package com.abdllh.aura.home

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import com.abdllh.aura.music.BtMusic
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.settings.sliderControl
import com.abdllh.aura.system.Controls
import com.abdllh.aura.system.DriveState
import com.abdllh.aura.ui.AuraSlider
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Segmented
import com.abdllh.aura.ui.Sheet
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.ThemeSwap
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.glyph
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.Prefs

/**
 * Controls, the side sheet from the strip's left end (SPEC.md section 5, `controls`, `controls-on-trip`): brightness, the theme, and four tiles: Wi-Fi, Bluetooth, the hotspot, screen off. At x 12 with no trip
 * and at x 384 beside the column on one. Volume and mute are in the row now, the camera and the car's settings in Apps, the update note in Settings. A tile that opens another app leaves the sheet
 * open under it: it goes when the window stops ([HomeActivity]), so its closing does not compete with the app's opening. A panel closes it at once ([PanelStack.show]).
 */
class ControlsSheet(ctx: Context, private val host: HomeHost) : Sheet(ctx, false, side = true) {
    private val brightness = AuraSlider(ctx).apply { max = 100 }
    private val theme = Segmented(ctx, listOf(ctx.getString(R.string.theme_auto), ctx.getString(R.string.controls_day), ctx.getString(R.string.controls_night)), equal = true) { i -> themeChosen(i) }
    private val wifi = Tile(ctx, R.drawable.ic_wifi, R.string.ctl_wifi) { Actions.wifi(context) }
    private val bluetooth = Tile(ctx, R.drawable.ic_bluetooth, R.string.ctl_bluetooth) { Actions.bluetooth(context) }
    private val hotspot = Tile(ctx, R.drawable.ic_hotspot, R.string.controls_hotspot) { Actions.hotspot(context) }
    private val screenOff = Tile(ctx, R.drawable.ic_power, R.string.ctl_screen_off) { screenOffTapped() }

    // Brightness is a system setting (a provider write per value): a drag writes at most every BRIGHT_GAP_MS and its
    // last value when the finger lifts, instead of on every 1 % step.
    private var brightSent = -1
    private var brightSentAt = 0L
    private var askedWrite = false
    private val brightLater = Runnable { writeBrightness(brightness.progress) }
    private val themeLater = Runnable { applyTheme() }

    init {
        sideHead(ctx.getString(R.string.ctl_title))
        val body = FrameLayout(ctx)
        val dim = ImageView(ctx).apply { setImageResource(R.drawable.ic_sun_dim) }.glyph { Palette.ink }.apply { layoutParams = lp(24, 24) }
        val full = ImageView(ctx).apply { setImageResource(R.drawable.ic_sun) }.glyph { Palette.ink }.apply { layoutParams = lp(28, 28) }
        body.addView(ctx.sliderControl(brightness, dim, full, gap = 18), at(22, 8, 396, AuraSlider.HEIGHT))
        body.addView(object : View(ctx) { override fun hasOverlappingRendering() = false }.look { setBackgroundColor(Palette.line) }, at(22, 76, 396, 1))
        body.addView(theme, at(14, 88, 412, 60))
        for ((i, t) in listOf(wifi, bluetooth, hotspot, screenOff).withIndex()) {
            // (first at the reading edge)
            body.addView(t, at(if (Fonts.arabicUi) 329 - 105 * i else 14 + 105 * i, 168, Tile.W, Tile.H))
        }
        panel.addView(body, lp(SIDE_W, BODY_H))

        brightness.onChange = { v, user ->
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

    /** The same order as the segments: automatic, day, night. */
    private fun themeChosen(i: Int) {
        Prefs.theme = MODES[i]
        // (the thumb is seen landing first; a newer choice replaces this)
        removeCallbacks(themeLater)
        postDelayed(themeLater, Motion.MEDIUM)
    }

    /** The one path when what is on top takes it in place; until every panel does, what is on top restyles itself as it always did. */
    private fun applyTheme() {
        val shell = HomeActivity.current ?: return
        val top = shell.panels.top
        when {
            top.recolours -> ThemeSwap.run(shell)
            top === host -> host.restyle()
            else -> top.onLook()
        }
    }

    /** Moving: the Night Panel. Parked: the backlight off, as the stock launcher's icon does it, until a touch wakes it. */
    private fun screenOffTapped() {
        close()
        if (DriveState.moving || !Actions.hasScreenOff(context)) HomeActivity.current?.root?.night?.show()
        else Actions.screenOff(context)
    }

    /** Volume is the row's now: this stays for whoever still tells it. */
    fun syncVolume() = Unit

    fun refresh() {
        placeOnTrip(NavSession.state != NavSession.State.IDLE)
        val b = Controls.brightnessPercent(context)
        brightness.setProgress(b, false)
        brightSent = b
        askedWrite = false
        theme.select(MODES.indexOf(Prefs.theme).coerceAtLeast(0), false)
        wifi.on = Actions.wifiConnected(context)
        bluetooth.on = BtMusic.available && BtMusic.connected
        hotspot.on = Actions.hotspotOn(context)
    }

    override fun onOpen() = refresh()

    /** 97 x 132: the glyph on a 48 plate, `accent` when on, the word under it in up to two lines. */
    private class Tile(ctx: Context, icon: Int, word: Int, private val tap: () -> Unit) : FrameLayout(ctx), Recolours {
        private val plate = Tint(ctx, R.drawable.plate_18) { Palette.fill }
        private val iconPlate = Tint(ctx, R.drawable.plate_16) { if (on) Palette.accent else Palette.fill2 }
        private val glyphView = ImageView(ctx).apply { setImageResource(icon) }
        private val gesture = PressGesture(this, PressGesture.CONTROL)

        var on = false
            set(v) {
                if (v == field) return
                field = v
                onLook()
            }

        init {
            isClickable = true
            contentDescription = ctx.getString(word)
            addView(View(ctx).apply { background = plate.drawable }, at(0, 0, W, H))
            addView(View(ctx).apply { background = iconPlate.drawable }, at(24, 10, 48, 48))
            addView(glyphView, at(34, 20, 28, 28))
            addView(ctx.label(Type.controlLabel, { Palette.ink }, lines = 2, gravity = Gravity.CENTER_HORIZONTAL).apply { setText(word) }, at(0, 64 + Type.controlLabel.viewTop, W, 66))
            setOnClickListener { tap() }
            onLook()
        }

        override fun onLook() {
            plate.recolour()
            iconPlate.recolour()
            glyphView.setColorFilter(if (on) Palette.onAccent else Palette.ink)
        }

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            gesture.onTouch(e)
            return super.dispatchTouchEvent(e)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean = gesture.tolerant(e) { super.onTouchEvent(it) }

        companion object {
            const val W = 97
            const val H = 132
        }
    }

    private companion object {
        val MODES = listOf(Theme.AUTO, Theme.LIGHT, Theme.DARK)
        const val SIDE_W = 440
        /** Under the head's 64: 376 in all. */
        const val BODY_H = 312
        const val BRIGHT_GAP_MS = 50L
    }
}
