package com.abdllh.aura.voice

import android.app.Activity
import android.content.Context
import android.media.AudioDeviceInfo
import com.abdllh.aura.R

/** What Settings needs from the assistant's setup. */
object VoiceSetup {
    /** The owner has recorded the wake word. */
    fun enrolled(c: Context): Boolean = WakeModel.file(c).isFile

    /** Records the wake word ([done] true when all recordings were taken). */
    fun enroll(act: Activity, done: (Boolean) -> Unit) = EnrollDialog.show(act, done)

    /** A microphone's name for the owner. */
    fun name(c: Context, d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> c.getString(R.string.voice_mic_builtin)
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> c.getString(R.string.voice_mic_headset)
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> c.getString(R.string.voice_mic_bt)
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB · ${d.productName}"
        else -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "#${d.id}"
    }
}
