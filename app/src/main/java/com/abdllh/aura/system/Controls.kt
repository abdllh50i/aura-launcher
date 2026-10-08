package com.abdllh.aura.system

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings

/** Brightness / volume helpers shared by the home card and the settings page. */
object Controls {
    private const val MIN_B = 30

    fun brightnessPercent(ctx: Context): Int = try {
        val raw = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        ((raw - MIN_B) * 100f / (255 - MIN_B)).toInt().coerceIn(0, 100)
    } catch (_: Throwable) {
        50
    }

    /** Returns false when the app lacks the "modify system settings" permission. */
    fun setBrightnessPercent(ctx: Context, pct: Int): Boolean {
        val raw = MIN_B + pct.coerceIn(0, 100) * (255 - MIN_B) / 100
        return try {
            if (!Settings.System.canWrite(ctx)) return false
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun requestWriteSettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }
    }

    private fun am(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun volumeMax(ctx: Context) = am(ctx).getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    fun muted(ctx: Context) = am(ctx).isStreamMute(AudioManager.STREAM_MUSIC)
    fun volume(ctx: Context) = if (muted(ctx)) 0 else am(ctx).getStreamVolume(AudioManager.STREAM_MUSIC)

    fun setVolume(ctx: Context, v: Int) {
        val a = am(ctx)
        a.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, volumeMax(ctx)), 0)
        if (v > 0 && a.isStreamMute(AudioManager.STREAM_MUSIC)) a.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
    }

    fun toggleMute(ctx: Context) = am(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)

    fun percentText(ctx: Context, v: Int) = "${(v * 100f / volumeMax(ctx)).toInt()}%"
}
