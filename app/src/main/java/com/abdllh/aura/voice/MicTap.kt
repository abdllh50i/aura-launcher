package com.abdllh.aura.voice

/**
 * The wake word's microphone, lent to whatever else needs to hear the car (the lyrics' timing): the unit records for
 * one listener at a time, and the assistant's recording runs whenever the assistant is on. [listener] gets every
 * buffer the microphone gives (samples, how many, the sample rate), on the microphone's own thread: it must copy what
 * it keeps and return at once, or the wake word falls behind. [active] says whether that recording is running now, so
 * a listener knows when it has to record by itself (the assistant switched off) and when it must not (it is running).
 */
object MicTap {
    @Volatile var listener: ((ShortArray, Int, Int) -> Unit)? = null

    /** The assistant's microphone is recording now. */
    @Volatile var active = false
        internal set
}
