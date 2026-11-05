package com.abdllh.aura.ui

import android.view.View
import com.abdllh.aura.nav.NavSession

/**
 * A card beside the driving column, on a trip (SPEC.md sections 6.6, 9): under 300 m to a turn it fades away and the road is the whole free area, with touches through to the map; after the
 * turn it comes back as it was. Alpha and a small x, one layer: nothing is built or dropped. Heard while the panel is up ([on], [off]).
 */
class TurnAway(private val view: View, private val dx: Float) {
    private var away = false
    private var listening = false
    private val heard: () -> Unit = { check() }

    fun on() {
        if (listening) return
        listening = true
        NavSession.addListener(heard)
        check()
    }

    fun off() {
        if (!listening) return
        listening = false
        NavSession.removeListener(heard)
        if (away) {
            away = false
            view.animate().cancel()
            view.setTag(com.abdllh.aura.R.id.aura_exiting, null)
            view.alpha = 1f
            view.translationX = 0f
            view.visibility = View.VISIBLE
        }
    }

    private fun check() {
        val p = NavSession.progress
        val near = NavSession.state == NavSession.State.NAVIGATING && p != null && p.toNext < ThemeSwap.NEAR_TURN_M
        if (near == away) return
        away = near
        if (near) view.exit(dur = Tokens2.MS_bannerOut) { view.visibility = View.INVISIBLE }
        else view.enter(dx = dx, dur = Tokens2.MS_cardIn)
    }
}
