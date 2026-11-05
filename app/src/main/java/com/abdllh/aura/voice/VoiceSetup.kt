package com.abdllh.aura.voice

import android.app.Activity
import android.content.Context

/** What Settings needs from the assistant's setup. */
object VoiceSetup {
    /** The Google app: Android's speech recogniser (the unit's firmware has none; it comes from the Play Store). */
    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

    /** Speech Services by Google: a voice that speaks Arabic (the firmware has no text-to-speech engine at all). */
    const val GOOGLE_TTS = "com.google.android.tts"

    /** The owner has recorded the wake word. */
    fun enrolled(c: Context): Boolean = WakeModel.file(c).isFile

    /**
     * Records the wake word ([done] true when all recordings were taken). New recordings start without learned sounds
     * (they were weighed against the old ones, and the owner records again when it stopped hearing him).
     */
    fun enroll(act: Activity, done: (Boolean) -> Unit) = EnrollDialog.show(act) { ok ->
        if (ok) WakeNegatives.clear(WakeNegatives.file(act))
        done(ok)
    }
}
