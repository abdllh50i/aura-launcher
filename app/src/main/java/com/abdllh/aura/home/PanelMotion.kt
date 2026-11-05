package com.abdllh.aura.home

import android.animation.TimeInterpolator
import android.view.View
import com.abdllh.aura.R
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.afterNextDraw
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit

/**
 * How one panel takes over from another, by the pair (SPEC.md section 9, "Between screens"). The stack asks only while the window is resumed
 * (otherwise there is no move at all), and it cleans up after any move whatever it does: a move that throws or never calls `done` ends at the stack's limit, with every view at rest.
 *
 * Rules a move keeps: only alpha and transforms of what it is given; a later call on the same view takes over from where it is (the second tap turns it back, it is never queued),
 * and the one it replaced does not call its `done`. The surface under the window knows nothing of any of this: the camera has its spring from the same moment ([com.abdllh.aura.nav.MapController]), and the rows' change is
 * the chrome's, by [com.abdllh.aura.system.Front].
 */
object PanelMotion {
    const val OPEN_MS = 200L
    const val CLOSE_MS = 180L
    private const val RISE_DP = 8f
    /** What covered the map goes, or comes back, in this long. */
    private const val WALL_MS = 240L
    /** What lies over the map comes in after the wall has begun to go, */
    private const val OVER_DELAY_MS = 60L
    /** and leaves before it is back. */
    private const val OVER_OUT_MS = 120L
    /** The cover's flight: to Music, and every other. */
    private const val FLIGHT_FAR_MS = 320L
    private const val FLIGHT_MS = 240L

    interface Move {
        /** [to] comes over [under], which is still drawn; [done] when it is at rest. */
        fun opening(to: View, under: View, done: () -> Unit)

        /** [from] goes off [under], which is visible again by now; [done] when it is gone. */
        fun closing(from: View, under: View, done: () -> Unit)
    }

    /** The move between panels [from] and [to], whichever way it is going. */
    fun between(from: String, to: String): Move = when {
        from == Screens.HOME || to == Screens.HOME -> WithHome(if (from == Screens.HOME) to else from)
        from == Screens.MAPS || to == Screens.MAPS -> OverMap
        else -> Rise
    }

    /**
     * Home and any other panel, by section 9's tables. Home knows its own parts ([HomePanel.leaving], [HomePanel.returning]). The other panel comes and goes as one layer, so a first open pays its cost
     * once, and each move starts after the frame that holds that cost: Maps at 60 for 200 `move` with its cards rising 8 (its scrims rise with them, at an alpha where it cannot be seen), and out in 120 `exit`; Music's wash
     * and all on it at 0 for 240 `move`, out in 180 `exit`; a room at 0 for 200 `move`, rising 8, out in 180 `exit`. Home's own texts have gone by 120 and the rows have changed by 160, whatever the other panel's first frame costs.
     */
    private class WithHome(private val other: String) : Move {
        private val overMap get() = other == Screens.MAPS

        override fun opening(to: View, under: View, done: () -> Unit) {
            val home = (under as? PanelFrame)?.panel as? HomePanel
            var left = 2
            val one = { if (--left == 0) done() }
            if (home != null) {
                home.leaving(overMap, one)
                (to as? PanelFrame)?.panel?.let { p -> home.flyOut(p.flightCover, p.flightRadius, if (other == Screens.MUSIC) FLIGHT_FAR_MS else FLIGHT_MS) }
            } else one()
            val dy = if (other == Screens.MUSIC) 0f else RISE_DP
            val delay = if (overMap) Tokens2.MS_columnDelay else 0L
            val ms = if (other == Screens.MUSIC) WALL_MS else OPEN_MS
            if (to.getTag(R.id.aura_exiting) == true) { // (it was leaving: it turns back from where it is)
                to.setTag(R.id.aura_exiting, null)
                come(to, dy, delay, ms, Motion.MOVE, one)
                return
            }
            to.alpha = 0f
            to.translationY = dy * to.resources.displayMetrics.density
            to.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            to.afterNextDraw {
                if (to.alpha == 0f && to.getTag(R.id.aura_exiting) != true) come(to, dy, delay, ms, Motion.MOVE, one)
            }
        }

        override fun closing(from: View, under: View, done: () -> Unit) {
            val home = (under as? PanelFrame)?.panel as? HomePanel
            var left = 2
            val one = { if (--left == 0) done() }
            from.setTag(R.id.aura_exiting, true)
            from.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            if (home != null) {
                home.returning(overMap, one)
                (from as? PanelFrame)?.panel?.let { p -> home.flyBack(p.flightCover, p.flightRadius, FLIGHT_MS) }
            } else one()
            from.exit(dy = if (other == Screens.MUSIC) 0f else RISE_DP, dur = if (other == Screens.MAPS) Tokens2.MS_homeOut else CLOSE_MS, end = one)
        }

        private fun come(v: View, dy: Float, delay: Long, ms: Long, curve: TimeInterpolator, end: () -> Unit) {
            v.animate().alpha(1f).translationY(0f).setDuration(ms).setStartDelay(delay).setInterpolator(curve).setUpdateListener(null).withEndAction(end).start()
        }
    }

    /**
     * Alpha and a small rise, as one texture. Each starts after the frame that holds its cost: a panel's first frame
     * (layout, its layer drawn for the first time) or the frame of the one that comes back, which on this GPU can take
     * longer than the whole move. Started before it, a timed move would have run its course by the time anything showed
     * (DEV.md, the entrance's start). Until then nothing has moved: the press on what opened it answered the tap.
     */
    private object Rise : Move {
        override fun opening(to: View, under: View, done: () -> Unit) {
            if (to.getTag(R.id.aura_exiting) == true) { // (it was leaving: it turns back from where it is)
                to.enter(dy = RISE_DP, dur = OPEN_MS, end = done)
                return
            }
            to.alpha = 0f
            to.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            to.afterNextDraw {
                if (to.alpha == 0f && to.getTag(R.id.aura_exiting) != true) to.enter(dy = RISE_DP, dur = OPEN_MS, end = done)
            }
        }

        override fun closing(from: View, under: View, done: () -> Unit) {
            from.setTag(R.id.aura_exiting, true)
            from.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            under.afterNextDraw {
                if (from.getTag(R.id.aura_exiting) == true) from.exit(dy = RISE_DP, dur = CLOSE_MS, end = done)
            }
        }
    }

    /**
     * To and from Maps, from another panel than Home, whose panel is nothing but cards over the map: what covered the map (Settings' room) fades away over it, as one texture, and the cards come in; the way back the
     * same in reverse. The map itself knows nothing of this. Nothing pops at the end: what stays under a panel at rest is not drawn any more, and by then it is transparent. Layers: these two, for the move only.
     */
    private object OverMap : Move {
        override fun opening(to: View, under: View, done: () -> Unit) {
            if (to.getTag(R.id.aura_exiting) == true) { // (it was leaving: both turn back from where they are)
                to.enter(dy = RISE_DP, dur = OPEN_MS, end = done)
                wall(under, 0f)
                return
            }
            to.alpha = 0f
            to.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            under.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            to.afterNextDraw {
                if (to.alpha == 0f && to.getTag(R.id.aura_exiting) != true) {
                    to.enter(dy = RISE_DP, dur = OPEN_MS, delay = OVER_DELAY_MS, end = done)
                    wall(under, 0f)
                }
            }
        }

        override fun closing(from: View, under: View, done: () -> Unit) {
            from.setTag(R.id.aura_exiting, true)
            from.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            // (at rest it is at 1: it starts from nothing. Still on its way off, it turns back from where it is)
            if (under.alpha == 1f) under.alpha = 0f
            under.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            under.afterNextDraw {
                if (from.getTag(R.id.aura_exiting) == true) {
                    from.exit(dy = RISE_DP, dur = OVER_OUT_MS)
                    wall(under, 1f, done)
                }
            }
        }

        private fun wall(v: View, to: Float, end: (() -> Unit)? = null) {
            v.anim(WALL_MS, Motion.STANDARD).alpha(to).withEndAction { end?.invoke() }.start()
        }
    }
}
