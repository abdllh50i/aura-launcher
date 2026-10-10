package com.abdllh.aura.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.net.Uri
import android.speech.tts.TextToSpeech
import com.abdllh.aura.R

/** What Settings needs from the assistant's setup. */
object VoiceSetup {
    /** The Google app: Android's speech recogniser (the unit's firmware has none; it comes from the Play Store). */
    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

    /** Speech Services by Google: a voice that speaks Arabic (the firmware has no text-to-speech engine at all). */
    const val GOOGLE_TTS = "com.google.android.tts"

    /** The owner has recorded the wake word. */
    fun enrolled(c: Context): Boolean = WakeModel.file(c).isFile

    /** Is there any text-to-speech engine (for the spoken answers)? */
    fun hasVoice(c: Context): Boolean = try {
        c.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
    } catch (_: Throwable) {
        true // unknown: no hint
    }

    /** The Play Store page of [pkg] (or the web page of it). */
    fun openStore(c: Context, pkg: String) {
        for (uri in listOf("market://details?id=$pkg", "https://play.google.com/store/apps/details?id=$pkg")) {
            try {
                c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {
            }
        }
    }

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
