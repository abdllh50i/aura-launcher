package com.abdllh.aura.ui

import android.animation.TimeInterpolator
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import com.abdllh.aura.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Motion shared by every screen: the easing curves and durations, and a spring for things that move with the hand
 * (presses, sheets, sliding indicators). Fast in, gentle settle: things arrive quickly and come to rest softly.
 */
object Motion {
    /** Arriving: fast start, long soft settle. */
    @JvmField val EMPHASIZED: TimeInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    /** Leaving: slow start, quick exit. */
    @JvmField val EXIT: TimeInterpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    /** Moving within the screen. */
    @JvmField val STANDARD: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    const val SHORT = 160L
    const val MEDIUM = 320L
    const val LONG = 480L
}

/**
 * A damped spring driving one value frame by frame (Choreographer: it needs no animator and keeps its speed when the
 * target changes half way, so a press released early bounces back from where it was, as fast as it was going).
 * [stiffness] in 1/s² for a unit mass; [dampingRatio] 1 = no overshoot, lower = a little bounce.
 */
class Spring(
    initial: Float,
    var stiffness: Float = 520f,
    var dampingRatio: Float = 0.8f,
    private val restDelta: Float = 0.001f,
    private val apply: (Float) -> Unit
) : Choreographer.FrameCallback {
    var value = initial
        private set
    var target = initial
        private set
    var velocity = 0f
    private var running = false
    private var last = 0L
    var onRest: (() -> Unit)? = null

    val isRunning get() = running

    fun animateTo(to: Float, startVelocity: Float = velocity) {
        target = to
        velocity = startVelocity
        if (!running) {
            running = true
            last = System.nanoTime()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun snapTo(to: Float) {
        cancel()
        value = to
        target = to
        velocity = 0f
        apply(to)
    }

    fun cancel() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        var dt = ((frameTimeNanos - last) / 1e9f).coerceIn(0f, 0.064f) // (a long stall must not throw it far)
        last = frameTimeNanos
        val c = 2f * dampingRatio * sqrt(stiffness)
        while (dt > 0f) { // small steps: stable for stiff springs too
            val h = min(dt, 0.004f)
            val a = -stiffness * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
            dt -= h
        }
        val done = abs(value - target) < restDelta && abs(velocity) < restDelta * 10f
        if (done) { value = target; velocity = 0f }
        apply(value)
        if (done) { running = false; onRest?.invoke() } else Choreographer.getInstance().postFrameCallback(this)
    }
}

/**
 * Press feedback: the view sinks under the finger and springs back with a small bounce when let go (from where it
 * was, however quick the tap). Cheap: only the view's scale moves, no layout.
 */
fun View.pressScale(scale: Float = 0.96f) {
    val s = (getTag(R.id.aura_press_spring) as? Spring) ?: Spring(1f, 900f, 0.9f) { v -> scaleX = v; scaleY = v }
        .also { setTag(R.id.aura_press_spring, it) }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { s.stiffness = 900f; s.dampingRatio = 0.9f; s.animateTo(scale) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                s.stiffness = 420f
                s.dampingRatio = 0.48f
                s.animateTo(1f)
            }
            MotionEvent.ACTION_MOVE -> if (e.x < -v.width * 0.2f || e.y < -v.height * 0.2f || e.x > v.width * 1.2f || e.y > v.height * 1.2f) {
                s.dampingRatio = 0.9f; s.animateTo(1f) // slid off: no press any more
            }
        }
        false
    }
}

/** Fades and slides a view in (after [delayMs]): for things that appear in place of others. */
fun View.riseIn(dyDp: Float = 14f, delayMs: Long = 0L, durationMs: Long = Motion.MEDIUM) {
    animate().cancel()
    alpha = 0f
    translationY = dyDp * resources.displayMetrics.density
    animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(durationMs).setInterpolator(Motion.EMPHASIZED).start()
}
