package com.abdllh.aura.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.settings.SettingsActivity
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.AuraSlider
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
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

/** Brightness + volume sliders, mute, and an "update available" pill. */
class ControlsCard(ctx: Context, private val host: HomeHost) : FrameLayout(ctx) {
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val brightness = AuraSlider(ctx).apply { max = 100; setIcon(R.drawable.ic_sun) }
    private val volume = AuraSlider(ctx).apply { setIcon(R.drawable.ic_volume) }
    private val brightValue: AText = ctx.label(14f, Palette.text2, Fonts.MEDIUM, gravity = Gravity.END or Gravity.CENTER_VERTICAL)
    private val volValue: AText = ctx.label(14f, Palette.text2, Fonts.MEDIUM, gravity = Gravity.END or Gravity.CENTER_VERTICAL)
    private val muteChip: LinearLayout
    private val muteIcon: ImageView
    private val updatePill: LinearLayout
    private val updateText: AText

    private val volReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = syncVolume()
    }

    init {
        background = Shapes.card(26f)
        roundedClip(26f)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(18.dp, 16.dp, 18.dp, 14.dp) }

        col.addView(row(ctx, brightness, brightValue), lp(MATCH, 46.dp))
        col.addView(row(ctx, volume, volValue), lp(MATCH, 46.dp).apply { topMargin = 12.dp })
        col.addView(android.view.View(ctx), lp(MATCH, 0, 1f))

        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        muteIcon = ImageView(ctx).apply { setImageResource(R.drawable.ic_volume_off); setColorFilter(Palette.text2); scaleType = ImageView.ScaleType.FIT_CENTER }
        muteChip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 0, 18.dp, 0)
            isClickable = true
            pressScale(0.96f)
            setOnClickListener { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0); syncVolume() }
            addView(muteIcon, lp(22.dp, 22.dp))
            addView(ctx.label(14f, Palette.text, Fonts.MEDIUM).apply { setText(R.string.ctl_mute) }, lp(WRAP, WRAP).apply { marginStart = 8.dp })
        }
        updateText = ctx.label(14f, Palette.accent, Fonts.MEDIUM)
        updatePill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 0, 18.dp, 0)
            isClickable = true
            pressScale(0.96f)
            setOnClickListener { host.openSettings(SettingsActivity.PAGE_UPDATE) }
            addView(ctx.iconView(R.drawable.ic_download, 20, Palette.accent))
            addView(updateText, lp(WRAP, WRAP).apply { marginStart = 8.dp })
        }
        chips.addView(muteChip, lp(WRAP, 44.dp))
        chips.addView(updatePill, lp(WRAP, 44.dp).apply { marginStart = 10.dp })
        col.addView(chips, lp(MATCH, WRAP))
        addView(col, flp(MATCH, MATCH))

        brightness.onChange = { v, user -> if (user) setBrightness(v); brightValue.text = "$v%" }
        volume.onChange = { v, user ->
            if (user) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                if (v > 0 && audio.isStreamMute(AudioManager.STREAM_MUSIC)) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            volValue.text = percentOf(v)
        }
        refresh()
    }

    private fun row(ctx: Context, slider: AuraSlider, value: AText): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(slider, lp(0, MATCH, 1f))
        addView(value, lp(52.dp, MATCH).apply { marginStart = 6.dp })
    }

    private fun percentOf(v: Int): String {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return "${(v * 100f / max).toInt()}%"
    }

    private fun readBrightness(): Int = try {
        val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        ((raw - MIN_B) * 100f / (255 - MIN_B)).toInt().coerceIn(0, 100)
    } catch (_: Throwable) { 50 }

    private fun setBrightness(pct: Int) {
        val raw = MIN_B + pct * (255 - MIN_B) / 100
        try {
            if (!Settings.System.canWrite(context)) throw SecurityException("WRITE_SETTINGS")
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
        } catch (_: SecurityException) {
            host.toast(context.getString(R.string.err_write_settings))
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) { }
        } catch (_: Throwable) { }
    }

    private fun syncVolume() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volume.max = max
        val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        val cur = if (muted) 0 else audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        volume.setProgress(cur)
        volValue.text = percentOf(cur)
        volume.setIcon(if (muted || cur == 0) R.drawable.ic_volume_off else R.drawable.ic_volume)
        muteChip.background = if (muted) Shapes.rect(Palette.withAlpha(Palette.accent, 0.20f), 22f, Palette.withAlpha(Palette.accent, 0.5f)) else Shapes.rect(Palette.card2, 22f, Palette.stroke)
        muteIcon.setColorFilter(if (muted) Palette.accent else Palette.text2)
    }

    fun refresh() {
        val b = readBrightness()
        brightness.setProgress(b, false)
        brightValue.text = "$b%"
        syncVolume()
        val has = UpdateManager.hasUpdateBadge()
        updatePill.visibility = if (has) VISIBLE else GONE
        if (has) {
            updateText.text = context.getString(R.string.ctl_update_available, Prefs.availableTag)
            updatePill.background = Shapes.rect(Palette.withAlpha(Palette.accent, 0.16f), 22f, Palette.withAlpha(Palette.accent, 0.5f))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try { context.registerReceiver(volReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION")) } catch (_: Throwable) { }
    }

    override fun onDetachedFromWindow() {
        try { context.unregisterReceiver(volReceiver) } catch (_: Throwable) { }
        super.onDetachedFromWindow()
    }

    companion object {
        private const val MIN_B = 30
    }
}
