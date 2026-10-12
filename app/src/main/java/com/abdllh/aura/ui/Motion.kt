package com.abdllh.aura.ui

import android.animation.TimeInterpolator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
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
    /** Leaving: moving from the first frame (a slow start reads as a late answer to the tap), then eased out. */
    @JvmField val EXIT: TimeInterpolator = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    /** Moving within the screen. */
    @JvmField val STANDARD: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    const val SHORT = 160L
    const val MEDIUM = 280L
    const val LONG = 420L
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
 * Press feedback: the view sinks under the finger and comes back when let go (from where it was, however quick the
 * tap), settling with one small overshoot, not a wobble. Cheap: only the view's scale moves, no layout.
 */
fun View.pressScale(scale: Float = 0.96f) {
    val s = (getTag(R.id.aura_press_spring) as? Spring) ?: Spring(1f, 900f, 0.9f) { v -> scaleX = v; scaleY = v }
        .also { setTag(R.id.aura_press_spring, it) }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { s.stiffness = 900f; s.dampingRatio = 0.9f; s.animateTo(scale) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                s.stiffness = 560f
                s.dampingRatio = 0.7f
                s.animateTo(1f)
            }
            MotionEvent.ACTION_MOVE -> if (e.x < -v.width * 0.2f || e.y < -v.height * 0.2f || e.x > v.width * 1.2f || e.y > v.height * 1.2f) {
                s.dampingRatio = 0.9f; s.animateTo(1f) // slid off: no press any more
            }
        }
        false
    }
}

/**
 * A layout change that moves instead of jumping. Made before the change, it remembers where the [moving] views are
 * and keeps a picture of the [leaving] ones; [play], called after the change, shows each view coming from its old
 * place while the pictures fade out where they stood. Only translation, scale and alpha move: no layout runs while
 * it plays. [resizing]: views that change size too (a cover); the others keep theirs and travel by their centres.
 * The views' parents must not clip their children (a view on its way is outside the place the new layout gave it).
 * They leave at once and settle softly: a move that starts slowly reads as the screen hesitating.
 */
class LayoutMove(
    private val root: FrameLayout,
    private val moving: List<View>,
    private val resizing: Set<View> = emptySet(),
    leaving: List<View> = emptyList()
) {
    private val before = moving.map { if (it.isShown && it.width > 0) seen(it) else null }
    private var ghosts = leaving.mapNotNull { ghost(it) }

    /** The change left them where they were after all: their pictures go at once, nothing fades. */
    fun keepLeaving() {
        for (g in ghosts) root.removeView(g)
        ghosts = emptyList()
    }

    /** Where a view is seen now (its own translation and scale included), in the window. */
    private fun seen(v: View): RectF {
        val at = IntArray(2)
        v.getLocationInWindow(at)
        return RectF(at[0].toFloat(), at[1].toFloat(), at[0] + v.width * v.scaleX, at[1] + v.height * v.scaleY)
    }

    private fun ghost(v: View): View? {
        if (!v.isShown || v.width == 0 || v.height == 0) return null
        return try {
            val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(b))
            val at = seen(v)
            val o = IntArray(2)
            root.getLocationInWindow(o)
            ImageView(root.context).apply {
                setImageBitmap(b)
                translationX = at.left - o[0]
                translationY = at.top - o[1]
                // (from the left edge in every language: the translation is in screen coordinates)
                root.addView(this, FrameLayout.LayoutParams(v.width, v.height, Gravity.LEFT or Gravity.TOP))
            }
        } catch (_: Throwable) {
            null // (a picture that cannot be drawn this way: the view just goes)
        }
    }

    fun play(durationMs: Long = Motion.LONG) {
        for (g in ghosts) g.animate().alpha(0f).setDuration(Motion.SHORT).withEndAction { root.removeView(g) }.start()
        val o = root.viewTreeObserver
        o.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                (if (o.isAlive) o else root.viewTreeObserver).removeOnPreDrawListener(this)
                for ((i, v) in moving.withIndex()) {
                    val old = before[i] ?: continue
                    if (!v.isShown || v.width == 0 || v.height == 0) continue
                    v.animate().cancel()
                    v.translationX = 0f; v.translationY = 0f
                    if (v in resizing) {
                        v.pivotX = 0f; v.pivotY = 0f
                        v.scaleX = 1f; v.scaleY = 1f
                        val now = seen(v)
                        v.scaleX = old.width() / now.width()
                        v.scaleY = old.height() / now.height()
                        v.translationX = old.left - now.left
                        v.translationY = old.top - now.top
                        v.invalidateOutline() // (rounded corners follow the scale: see roundedClip)
                        v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(durationMs)
                            .setInterpolator(Motion.EMPHASIZED)
                            .setUpdateListener { v.invalidateOutline() }
                            // (scaled from its middle again afterwards, as views are)
                            .withEndAction {
                                v.animate().setUpdateListener(null)
                                if (Build.VERSION.SDK_INT >= 28) v.resetPivot() else { v.pivotX = v.width / 2f; v.pivotY = v.height / 2f }
                                v.invalidateOutline()
                            }
                            .start()
                    } else {
                        // (its scale is left alone: a button let go a moment ago is still springing back)
                        val now = seen(v)
                        v.translationX = old.centerX() - now.centerX()
                        v.translationY = old.centerY() - now.centerY()
                        v.animate().translationX(0f).translationY(0f).setStartDelay(0).setDuration(durationMs)
                            .setInterpolator(Motion.EMPHASIZED).start()
                    }
                }
                return true
            }
        })
        root.invalidate()
    }
}

/** Fades and slides a view in (after [delayMs]): for things that appear in place of others. */
fun View.riseIn(dyDp: Float = 14f, delayMs: Long = 0L, durationMs: Long = Motion.MEDIUM) {
    animate().cancel()
    alpha = 0f
    translationY = dyDp * resources.displayMetrics.density
    animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(durationMs).setInterpolator(Motion.EMPHASIZED).start()
}
