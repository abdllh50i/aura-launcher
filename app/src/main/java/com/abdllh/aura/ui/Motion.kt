package com.abdllh.aura.ui

import android.animation.TimeInterpolator
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.RectF
import android.os.Build
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.abdllh.aura.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Motion shared by every screen: the easing curves and durations, and a spring for things that move with the hand
 * (presses, sheets, sliding indicators). Fast in, gentle settle: things arrive quickly and come to rest softly.
 * Which curve and duration goes where (entrances, exits, moves in place) is in docs/DEV.md, "Design system (1.12)".
 */
object Motion {
    /** Arriving: fast start, long soft settle. */
    @JvmField val EMPHASIZED: TimeInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    /** Leaving: moving from the first frame (a slow start reads as a late answer to the tap), then eased out. */
    @JvmField val EXIT: TimeInterpolator = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    /** Moving within the screen. */
    @JvmField val STANDARD: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** The quickest exits (a menu or a dialog card going after a choice was made in it). */
    const val XSHORT = 100L
    /** Exits, fades in place, small changes. */
    const val SHORT = 160L
    /** Entrances. */
    const val MEDIUM = 280L
    /** A whole layout moving ([LayoutMove]). */
    const val LONG = 420L
    /** Slow changes nobody waits for: a backdrop or a cover crossfading behind the content. */
    const val AMBIENT = 600L

    // How far a pressed view sinks ([pressScale]). One depth per kind of control, so like controls answer alike; a big
    // view sinks less, since the same scale moves its edges further (a 62 dp tile at 0.86 lost 9 px and looked squashed).
    /** Round icon buttons up to 64 dp: dock buttons, transport, close, quick buttons. */
    const val PRESS_ICON = 0.92f
    /** Buttons, chips, pills, tiles, segmented choices, dock app tiles. */
    const val PRESS_CONTROL = 0.95f
    /** Cards, rows, list cells, the media card, the search pill. */
    const val PRESS_SURFACE = 0.98f
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
            // (the first frame's time is the vsync's, earlier than now: measured from now, the first step was 0 and
            // every press and sheet started a frame late)
            last = 0L
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
        // (a long stall must not throw it far)
        var dt = if (last == 0L) 1f / 60f else ((frameTimeNanos - last) / 1e9f).coerceIn(0f, 0.064f)
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

/** True when this view or one inside it matches [p]. */
private fun View.holds(p: (View) -> Boolean): Boolean {
    if (p(this)) return true
    if (this is ViewGroup) for (i in 0 until childCount) if (getChildAt(i).holds(p)) return true
    return false
}

/**
 * Press feedback: the view sinks under the finger ([Motion.PRESS_ICON] / [Motion.PRESS_CONTROL] /
 * [Motion.PRESS_SURFACE]) and comes back when let go (from where it was, however quick the tap), settling with one
 * small overshoot, not a wobble. Cheap: only the view's scale moves, no layout.
 * Inside something that scrolls, the sink waits for the tap timeout as Android's pressed tint does, so a drag that
 * starts on a row scrolls the list without the row sinking and bouncing back. A view with text sinks through a
 * hardware layer: text drawn at a new size on every frame makes this GPU rasterise a new set of letters each time.
 */
fun View.pressScale(scale: Float = Motion.PRESS_CONTROL) {
    val s = (getTag(R.id.aura_press_spring) as? Spring) ?: Spring(1f, 1200f, 1f) { v -> scaleX = v; scaleY = v }.also { sp ->
        setTag(R.id.aura_press_spring, sp)
        sp.onRest = {
            if (sp.value == 1f && getTag(R.id.aura_press_layer) == true) {
                setLayerType(View.LAYER_TYPE_NONE, null)
                setTag(R.id.aura_press_layer, null)
            }
        }
    }
    var pending = false
    val sink = Runnable {
        pending = false
        if (layerType == View.LAYER_TYPE_NONE && holds { it is TextView } && !holds { it is TextureView || it is SurfaceView }) {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setTag(R.id.aura_press_layer, true)
        }
        s.stiffness = 1200f // (in fast and without overshoot: 95 % in about 80 ms)
        s.dampingRatio = 1f
        s.animateTo(scale)
    }
    val release = Runnable {
        removeCallbacks(sink)
        pending = false
        s.stiffness = 560f
        s.dampingRatio = 0.7f
        s.animateTo(1f)
    }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(release)
                val inScroller = generateSequence(v.parent) { it.parent }.any { it is ViewGroup && it.shouldDelayChildPressedState() }
                if (inScroller) { pending = true; postDelayed(sink, ViewConfiguration.getTapTimeout().toLong()) } else sink.run()
            }
            MotionEvent.ACTION_UP -> if (pending) {
                // a tap quicker than the timeout still shows, as briefly as Android shows its pressed tint then
                removeCallbacks(sink)
                sink.run()
                postDelayed(release, ViewConfiguration.getPressedStateDuration().toLong())
            } else release.run()
            MotionEvent.ACTION_CANCEL -> release.run()
            MotionEvent.ACTION_MOVE -> {
                // Only leaving the view lets go: a finger wobbling on a tile in a moving car is still a press. A scroll
                // needs no check of its own here: the parent that takes the touch sends ACTION_CANCEL, which lets go.
                if (e.x < -v.width * 0.2f || e.y < -v.height * 0.2f || e.x > v.width * 1.2f || e.y > v.height * 1.2f) {
                    release.run() // slid off: no press any more, and back without the overshoot of a let-go
                    s.dampingRatio = 0.9f
                }
            }
        }
        false
    }
}

/**
 * animate() with its duration, curve and delay all given anew (a ViewPropertyAnimator keeps the last ones it was
 * given: a fade-out called after an entrance otherwise inherits the entrance's curve and delay) and, when the view's
 * alpha moves and it draws overlapping content (a view group, a view with a background), through a hardware layer for
 * the run: one texture faded instead of every child blended on its own at every step. The way to fade a view group.
 */
fun View.anim(dur: Long, interp: TimeInterpolator, delay: Long = 0L): ViewPropertyAnimator {
    val a = animate().setDuration(dur).setInterpolator(interp).setStartDelay(delay).setUpdateListener(null)
    if (hasOverlappingRendering() && layerType == View.LAYER_TYPE_NONE && this !is TextureView && this !is SurfaceView) a.withLayer()
    return a
}

/**
 * An entrance: from transparent, [dx] / [dy] dp away and at [scaleFrom], to its place over [dur] on
 * [Motion.EMPHASIZED]. Called while the view is still leaving ([exit]), it turns back from where it is.
 */
fun View.enter(dx: Float = 0f, dy: Float = 0f, scaleFrom: Float = 1f, delay: Long = 0L, dur: Long = Motion.MEDIUM, end: (() -> Unit)? = null) {
    animate().cancel()
    if (getTag(R.id.aura_exiting) != true) {
        val d = resources.displayMetrics.density
        alpha = 0f
        translationX = dx * d
        translationY = dy * d
        scaleX = scaleFrom
        scaleY = scaleFrom
    }
    setTag(R.id.aura_exiting, null)
    visibility = View.VISIBLE
    anim(dur, Motion.EMPHASIZED, delay).alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
        .withEndAction { end?.invoke() }.start()
}

/**
 * An exit: to transparent, [dx] / [dy] dp away and at [scaleTo], over [dur] on [Motion.EXIT], at once (no delay).
 * [end] runs when it is gone (remove the view or make it GONE there); not when an [enter] took it back meanwhile.
 */
fun View.exit(dx: Float = 0f, dy: Float = 0f, scaleTo: Float = 1f, dur: Long = Motion.SHORT, end: (() -> Unit)? = null) {
    val d = resources.displayMetrics.density
    setTag(R.id.aura_exiting, true)
    anim(dur, Motion.EXIT).alpha(0f).translationX(dx * d).translationY(dy * d).scaleX(scaleTo).scaleY(scaleTo)
        .withEndAction { setTag(R.id.aura_exiting, null); end?.invoke() }.start()
}

/** A fade in place (a crossfade, a dimmed state): [Motion.STANDARD] over [dur] unless told otherwise. */
fun View.fadeTo(alpha: Float, dur: Long = Motion.SHORT, interp: TimeInterpolator = Motion.STANDARD, delay: Long = 0L, end: (() -> Unit)? = null) {
    anim(dur, interp, delay).alpha(alpha).withEndAction { end?.invoke() }.start()
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

    /**
     * The leaving view's picture, recorded rather than drawn: drawing a panel of covers and text into a bitmap on the
     * CPU, in the tap's own frame, took longer than a frame on the unit, so every move began with a dropped frame;
     * recording costs a millisecond or two and the GPU plays it back.
     */
    private fun ghost(v: View): View? {
        if (!v.isShown || v.width == 0 || v.height == 0) return null
        return try {
            val pic = Picture()
            v.draw(pic.beginRecording(v.width, v.height))
            pic.endRecording()
            val at = seen(v)
            val o = IntArray(2)
            root.getLocationInWindow(o)
            object : View(root.context) {
                override fun onDraw(c: Canvas) { c.drawPicture(pic) }
            }.apply {
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
        for (g in ghosts) g.anim(Motion.SHORT, Motion.EXIT).alpha(0f).withEndAction { root.removeView(g) }.start()
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
                        // its shadow is put away while it moves (the outline changes on every frame, and the shadow
                        // would be tessellated again each time), and back when it is there; kept in a tag, so a move
                        // that interrupts this one puts back the real one, not the 0 of the move before
                        if (v.getTag(R.id.aura_move_elevation) == null) v.setTag(R.id.aura_move_elevation, v.elevation)
                        v.elevation = 0f
                        v.invalidateOutline() // (rounded corners follow the scale: see roundedClip)
                        v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(durationMs)
                            .setInterpolator(Motion.EMPHASIZED)
                            .setUpdateListener { v.invalidateOutline() }
                            // (scaled from its middle again afterwards, as views are)
                            .withEndAction {
                                v.animate().setUpdateListener(null)
                                if (Build.VERSION.SDK_INT >= 28) v.resetPivot() else { v.pivotX = v.width / 2f; v.pivotY = v.height / 2f }
                                (v.getTag(R.id.aura_move_elevation) as? Float)?.let { v.elevation = it }
                                v.setTag(R.id.aura_move_elevation, null)
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
fun View.riseIn(dyDp: Float = 14f, delayMs: Long = 0L, durationMs: Long = Motion.MEDIUM) =
    enter(dy = dyDp, delay = delayMs, dur = durationMs)
