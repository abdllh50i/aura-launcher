package com.abdllh.aura.voice

/** What becomes of the music [Amri] paused to listen, once the request is over. No Android in here. */
internal object WakeMusic {
    enum class Then { LEAVE, PLAY, AFTER_CALL }

    /**
     * Exactly what was comes back, and only that. [ownerStopped]: the request itself was to stop it (pause, mute).
     * [otherPlaying]: something else plays by now (the owner started it, or a source opened for the request): that is not
     * interrupted. [inCall]: a phone call came meanwhile: when it ends, as the local player does for its own pause.
     */
    fun after(paused: Boolean, ownerStopped: Boolean, otherPlaying: Boolean, inCall: Boolean): Then = when {
        !paused || ownerStopped || otherPlaying -> Then.LEAVE
        inCall -> Then.AFTER_CALL
        else -> Then.PLAY
    }
}
