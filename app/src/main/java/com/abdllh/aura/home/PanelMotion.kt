package com.abdllh.aura.home

import android.view.View
import com.abdllh.aura.R
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.afterNextDraw
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit

/**
 * How one panel takes over from another, by the pair. Two for now: the design's exact laws per pair (its section 9)
 * replace what [between] answers, nothing else has to change. The stack asks only while the window is resumed
 * (otherwise there is no move at all), and it cleans up after any move whatever it does: a move that throws or never
 * calls `done` ends at the stack's limit, with every view at rest.
 *
 * Rules a move keeps: only alpha and transforms of the two views it is given (they are the panels' frames, not their
 * roots); a later call on the same view takes over from where it is (the second tap turns it back, it is never queued),
 * and the one it replaced does not call its `done`. The surface under the window knows nothing of any of this.
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

    interface Move {
        /** [to] comes over [under], which is still drawn; [done] when it is at rest. */
        fun opening(to: View, under: View, done: () -> Unit)

        /** [from] goes off [under], which is visible again by now; [done] when it is gone. */
        fun closing(from: View, under: View, done: () -> Unit)
    }

    /** The move between panels [from] and [to], whichever way it is going. */
    fun between(from: String, to: String): Move = if (from == Screens.MAPS || to == Screens.MAPS) OverMap else Rise

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
     * To and from Maps, whose panel is nothing but cards over the map: what covered the map (Home's wall with all that is on it,
     * Settings' room) fades away over it, as one texture with a hole where the card is, and the cards come in; the way back the
     * same in reverse. The map itself knows nothing of this: its camera eases on the map's own frames, from the same moment
     * ([com.abdllh.aura.nav.MapController]). Nothing pops at the end: what stays under a panel at rest is not drawn any more, and by
     * then it is transparent. Layers: these two, for the move only.
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
