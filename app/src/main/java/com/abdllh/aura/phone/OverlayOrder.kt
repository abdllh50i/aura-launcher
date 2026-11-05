package com.abdllh.aura.phone

import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.voice.Amri

/**
 * Who has the screen when two windows want it, all in one place (SPEC.md section 1): call over voice over message over phone place. A call and a message share the banner's place, and a call
 * simply wins: [messageWaits] holds the message, until the call is over and also for a turn under 300 m, when the driver's eyes belong to the road, and for reverse, where nothing lies over
 * the camera. The volume over another app lies in the column, nowhere near the banner or the voice card, so it always shows.
 */
internal object OverlayOrder {
    /** A manoeuvre this near, in metres, holds a message back. */
    private const val NEAR_TURN_M = 300.0

    private val turnNear: Boolean
        get() = NavSession.state == NavSession.State.NAVIGATING && NavSession.progress?.let { it.toNext < NEAR_TURN_M } == true

    fun messageWaits(): Boolean = Calls.state != Calls.State.IDLE || Amri.inCall || Amri.reversing || turnNear
}
