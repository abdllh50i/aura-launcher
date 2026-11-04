package com.abdllh.aura.ui

import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import com.abdllh.aura.R

/**
 * Press feedback a view composes (it used to take the view's only OnTouchListener): the view sinks under the finger and comes
 * back when let go, from where it was, however quick the tap, settling with one small overshoot, not a wobble. Cheap: only the
 * view's scale moves, no layout. A component calls [onTouch] from its dispatchTouchEvent and keeps its own touch listener free;
 * for any other view [View.pressScale] makes one and listens.
 *
 * Three depths, by kind ([ICON] glyph-only targets and tiles, [CONTROL] buttons, segments and map buttons, [SURFACE] cards and
 * rows); [NONE] for a settings row, which shows a plate instead of sinking, because a row's hairlines must not tear.
 *
 * First frame: in on spring 1200/1.0, out on 560/0.7. Inside something that scrolls the sink waits for the tap timeout as
 * Android's pressed tint does, so a drag that starts on a row scrolls the list without the row bobbing. A view with text sinks
 * through a hardware layer, only while pressed: text drawn at a new size on every frame makes this GPU rasterise a new set of
 * letters each time. A finger wobbling up to 20 % outside the view is still a press in a moving car; past that it lets go.
 */
class PressGesture(private val view: View, @JvmField var scale: Float = CONTROL) {
    private val spring = Spring(1f, 1200f, 1f) { v -> view.scaleX = v; view.scaleY = v }.also { sp ->
        sp.onRest = {
            if (sp.value == 1f && view.getTag(R.id.aura_press_layer) == true) {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
                view.setTag(R.id.aura_press_layer, null)
            }
        }
    }
    private var pending = false
    private var down = false

    private val sink = Runnable {
        pending = false
        if (view.layerType == View.LAYER_TYPE_NONE && view.holds { it is TextView } && !view.holds { it is TextureView || it is SurfaceView }) {
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            view.setTag(R.id.aura_press_layer, true)
        }
        spring.stiffness = Tokens2.SPRING_pressIn[0] // (in fast and without overshoot: 95 % in about 80 ms)
        spring.dampingRatio = Tokens2.SPRING_pressIn[1]
        spring.animateTo(scale)
    }

    private val release = Runnable {
        view.removeCallbacks(sink)
        pending = false
        spring.stiffness = Tokens2.SPRING_pressOut[0]
        spring.dampingRatio = Tokens2.SPRING_pressOut[1]
        spring.animateTo(1f)
    }

    fun onTouch(e: MotionEvent) {
        if (scale >= 1f) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!view.isEnabled) return
                down = true
                view.removeCallbacks(release)
                val inScroller = generateSequence(view.parent) { it.parent }.any { it is ViewGroup && it.shouldDelayChildPressedState() }
                if (inScroller) { pending = true; view.postDelayed(sink, ViewConfiguration.getTapTimeout().toLong()) } else sink.run()
            }
            MotionEvent.ACTION_UP -> if (down) {
                down = false
                if (pending) {
                    // a tap quicker than the timeout still shows, as briefly as Android shows its pressed tint then
                    view.removeCallbacks(sink)
                    sink.run()
                    view.postDelayed(release, ViewConfiguration.getPressedStateDuration().toLong())
                } else release.run()
            }
            MotionEvent.ACTION_CANCEL -> if (down) { down = false; release.run() }
            MotionEvent.ACTION_MOVE -> if (down && outside(e)) {
                // Only leaving the view lets go: a finger wobbling on a tile in a moving car is still a press. A scroll
                // needs no check of its own here: the parent that takes the touch sends ACTION_CANCEL, which lets go.
                down = false
                release.run() // slid off: no press any more, and back without the overshoot of a let-go
                spring.dampingRatio = 0.9f
            }
        }
    }

    private fun outside(e: MotionEvent) = e.x < -view.width * SLOP || e.y < -view.height * SLOP || e.x > view.width * (1f + SLOP) || e.y > view.height * (1f + SLOP)

    /**
     * For a component's onTouchEvent: a finger inside the 20 % round the view still counts as on it, so a wobble past the edge
     * does not lose the click (a View lets go 8 px outside).
     */
    inline fun tolerant(e: MotionEvent, block: (MotionEvent) -> Boolean): Boolean {
        val dx = nudgeX(e)
        val dy = nudgeY(e)
        e.offsetLocation(dx, dy)
        val r = block(e)
        e.offsetLocation(-dx, -dy)
        return r
    }

    fun nudgeX(e: MotionEvent): Float = nudge(e.x, view.width)

    fun nudgeY(e: MotionEvent): Float = nudge(e.y, view.height)

    private fun nudge(p: Float, size: Int): Float = when {
        p < 0f && p >= -size * SLOP -> -p
        p >= size && p <= size * (1f + SLOP) -> size - 1f - p
        else -> 0f
    }

    /** Back at rest at once: the view went away under the finger. */
    fun reset() {
        down = false
        pending = false
        view.removeCallbacks(sink)
        view.removeCallbacks(release)
        spring.snapTo(1f)
        if (view.getTag(R.id.aura_press_layer) == true) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
            view.setTag(R.id.aura_press_layer, null)
        }
    }

    companion object {
        const val ICON = Tokens2.PRESS_icon
        const val CONTROL = Tokens2.PRESS_control
        const val SURFACE = Tokens2.PRESS_surface
        const val NONE = 1f
        private const val SLOP = 0.2f

        /**
         * What a press shows that is not the scale: nothing at rest, [plate] at once when pressed, fading out in 140 ms on
         * release. As the view's foreground or a part of its background, inset as it should show.
         */
        fun plate(plate: Drawable, inset: Int = 0): StateListDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), if (inset == 0) plate else InsetDrawable(plate, inset))
            setExitFadeDuration(Tokens2.MS_release.toInt())
        }
    }
}

/** True when this view or one inside it matches [p]. */
private fun View.holds(p: (View) -> Boolean): Boolean {
    if (p(this)) return true
    if (this is ViewGroup) for (i in 0 until childCount) if (getChildAt(i).holds(p)) return true
    return false
}

/**
 * [PressGesture] on any view through its one touch listener. A component of ui/ composes the gesture instead, and its listener
 * stays free.
 */
fun View.pressScale(scale: Float = Motion.PRESS_CONTROL): PressGesture {
    val g = (getTag(R.id.aura_press_gesture) as? PressGesture)?.also { it.scale = scale } ?: PressGesture(this, scale).also { setTag(R.id.aura_press_gesture, it) }
    setOnTouchListener { _, e -> g.onTouch(e); false }
    return g
}
