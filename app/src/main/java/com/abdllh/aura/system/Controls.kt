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

    // Volume is the car's amplifier volume (see CarAudio), not Android's music stream.
    @Suppress("UNUSED_PARAMETER") fun volumeMax(ctx: Context) = CarAudio.max()
    @Suppress("UNUSED_PARAMETER") fun muted(ctx: Context) = CarAudio.muted()
    @Suppress("UNUSED_PARAMETER") fun volume(ctx: Context) = if (CarAudio.muted()) 0 else CarAudio.volume()
    @Suppress("UNUSED_PARAMETER") fun setVolume(ctx: Context, v: Int) = CarAudio.setVolume(v)
    @Suppress("UNUSED_PARAMETER") fun toggleMute(ctx: Context) = CarAudio.toggleMute()
    /** One step from the real level (not the 0 shown while muted), which also turns the sound back on. */
    @Suppress("UNUSED_PARAMETER") fun stepVolume(ctx: Context, dir: Int) = CarAudio.setVolume(CarAudio.volume() + dir)

    fun percentText(ctx: Context, v: Int) = "${(v * 100f / volumeMax(ctx)).toInt()}%"
}
