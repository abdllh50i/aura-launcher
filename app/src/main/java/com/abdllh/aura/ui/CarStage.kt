package com.abdllh.aura.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The user's car on a turntable, on the wall (SPEC.md section 10): drawn by [CarGlView] in its rect, the wall's own pixels under it until its first frame. Drag sideways to turn it, flick to spin it;
 * it comes to rest with inertia and, after a few idle seconds, swings back to its showroom angle, frame 108, in both languages. Adjacent frames blend while it moves; at rest the
 * crisp copy of the frame fades in, in one step. Until the frames are in the GPU (the first run bakes them) it is the rest frame and a finger does nothing, with no word about it. While the car moves
 * the turntable is locked ([locked]). No frame goes to the GPU when nobody can take it: under another panel its thread would hang in the swap two frames later.
 */
class CarStage(context: Context) : FrameLayout(context) {
    private val table = CarTable.of(context)
    private val n = table?.count ?: 1
    private val home = (table?.defaultFrame ?: 0).toFloat()
    private val step = 360f / n
    private val framesPerPx = DEG_PER_PX / step
    private val maxVel = MAX_DEG_S / step
    private val minVel = MIN_DEG_S / step

    private var pos = home
    private var dragTo = home
    private var vel = 0f
    private var dragging = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var anim: ValueAnimator? = null
    private var ticking = false
    private var lastFrameNs = 0L
    private val goHome = Runnable { settleHome() }
    private val frameCallback = Choreographer.FrameCallback { t -> tick(t) }

    private val state = CarGlView.State().apply { rest = true; restMix = 1f }
    private var restAsked = home.toInt()
    private var restFor = home.toInt()
    private var restMix = 1f
    private var restAnim: ValueAnimator? = null
    private var builtDark = Palette.dark

    /** What is seen of the car, the shader's `u_shown` before [dim]: the entrance, and leaving Home. */
    var presence = 1f
        set(v) {
            if (v == field) return
            field = v
            redraw()
        }

    /** At night, moving: .55, and one redraw. */
    var dim = 1f
        set(v) {
            if (v == field) return
            field = v
            redraw()
        }

    /** The car moves: nobody turns it, and it goes back to its angle. */
    var locked = false
        set(v) {
            if (v == field) return
            field = v
            if (v) {
                removeCallbacks(goHome)
                dragging = false
                moved = false
                vel = 0f
                settleHome()
            }
        }

    /** The wall's own pixels under the GL view until its first frame: the hole never shows the void. */
    private val under = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        look { setImageBitmap(Surfaces.themed(context, "wall-gl")) }
    }
    val gl: CarGlView? = table?.let { CarGlView(context, Palette.dark) }
    private var fallback: ImageView? = null

    init {
        isClickable = true
        setWillNotDraw(false)
        contentDescription = context.getString(R.string.home_car)
        addView(under, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        gl?.let { g ->
            addView(g, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            g.onReady = {
                gpuDrawing = true
                under.visibility = INVISIBLE // (no layout: this often comes in the entrance)
                onFirstFrame?.invoke()
                onFirstFrame = null
                invalidate()
            }
            g.onLost = {
                under.visibility = VISIBLE
                restAsked = -1
                restFor = -1
                invalidate()
            }
            g.onFailed = {
                gpuDrawing = false
                showFallback()
                onFirstFrame?.invoke()
                onFirstFrame = null
            }
            g.onRestReady = { i ->
                if (i == Math.floorMod(pos.roundToInt(), n) && !moving()) {
                    if (restFor != i) {
                        restFor = i
                        restAsked = i
                        restAnim?.cancel()
                        restAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = REST_MS
                            interpolator = Motion.MOVE
                            addUpdateListener { restMix = it.animatedValue as Float; redraw() }
                            start()
                        }
                    } else redraw()
                }
            }
            g.onFramesIn = { lastFps = g.fps }
        }
        look { recolour() }
    }

    /** [onFirstFrame]: the car's first frame is up, or will not be. Once. */
    var onFirstFrame: (() -> Unit)? = null

    /** With no GL at all the car is its rest frame and no floor: its alpha is K, so the wall shows round it. */
    private fun showFallback() {
        val t = table ?: return
        val f = fallback ?: ImageView(context).also { fallback = it; addView(it, at((t.box[0] - CarGlView.RECT_X).toInt(), (t.box[1] - CarGlView.RECT_Y).toInt(), t.width, t.height)) }
        under.visibility = VISIBLE
        try {
            f.setImageBitmap(context.assets.open("car/rest-${if (Palette.dark) "night" else "day"}.webp").use { BitmapFactory.decodeStream(it) })
        } catch (_: Throwable) { }
    }

    private fun recolour() {
        if (builtDark == Palette.dark) return
        builtDark = Palette.dark
        restAsked = -1
        restFor = -1
        restMix = 0f
        gl?.setDark(Palette.dark)
        if (gl == null || fallback != null) showFallback()
        redraw()
    }

    /** What is on screen now, for a picture the window's Canvas cannot record: the theme change's ghost. */
    fun picture() = gl?.picture()

    /** With the wall, as one: the wall's own pixels if the GL view is not up yet. A TextureView's alpha takes a layer of its rect, for the 240 ms of a fade only. */
    fun fade(a: Float) {
        under.alpha = a
        fallback?.alpha = a
        gl?.alpha = a
    }

    val framesIn: Boolean get() = gl?.framesIn == true

    private fun moving() = anim != null || dragging || vel != 0f

    // ------------------------------------------------------------------------------------------ motion
    /** The entrance's spin, when its frames are in the GPU already: from 60 degrees before the showroom angle, over [SPIN_MS]. */
    fun spinIn(): Boolean {
        val g = gl ?: return false
        val from = home - CarGlView.SPIN_FRAMES
        if (!g.hasFrames(from.toInt(), home.toInt())) return false
        pos = from
        animatePos(home, SPIN_MS, Motion.ENTER)
        return true
    }

    /** The turntable back at its angle, at once: any tap in the entrance. */
    fun skipSpin() {
        if (anim == null) return
        stopAnim()
        pos = home
        redraw()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        restAnim?.cancel()
        removeCallbacks(goHome)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        ticking = false
        super.onDetachedFromWindow()
    }

    private fun stopAnim() {
        val a = anim
        anim = null
        a?.cancel()
    }

    private fun animatePos(target: Float, durationMs: Long, interp: android.animation.TimeInterpolator, then: (() -> Unit)? = null) {
        stopAnim()
        val from = pos
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = interp
            addUpdateListener {
                pos = from + (target - from) * (it.animatedValue as Float)
                redraw()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (anim !== a) return
                    anim = null
                    pos = target
                    redraw()
                    then?.invoke()
                }
            })
            start()
        }
    }

    /** Swings back to the showroom angle the short way round. */
    private fun settleHome() {
        if (dragging) return
        val target = home + n * Math.round((pos - home) / n)
        val dist = abs(target - pos)
        if (dist < 0.01f) { pos = home; redraw(); return }
        animatePos(target, (520 + dist * step * 5.5f).toLong().coerceAtMost(1500), PathInterpolator(0.45f, 0f, 0.2f, 1f)) {
            pos = home
            redraw()
        }
    }

    private fun kick() {
        if (ticking) return
        ticking = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun tick(now: Long) {
        ticking = false
        val dt = if (lastFrameNs == 0L) 1f / 60f else ((now - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastFrameNs = now
        var more = false
        // Touch panels report unevenly: the car eases towards the finger every display frame instead of jumping with each report (1:1, in a few frames).
        if (dragging && moved) {
            val d = dragTo - pos
            if (abs(d) > 0.004f) {
                pos += d * (1f - exp(-dt * FOLLOW))
                more = true
            } else {
                pos = dragTo
            }
        }
        if (!dragging && anim == null && vel != 0f) {
            pos += vel * dt
            vel *= exp(-dt * FRICTION)
            if (abs(vel) < minVel) {
                vel = 0f
                // come to rest exactly on a frame (a static blend would look soft), then wait before going home
                animatePos(pos.roundToInt().toFloat(), 220, DecelerateInterpolator()) { scheduleHome() }
            } else {
                more = true
            }
        }
        redraw()
        if (more) {
            ticking = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            lastFrameNs = 0L
        }
    }

    private fun scheduleHome() {
        removeCallbacks(goHome)
        postDelayed(goHome, IDLE_MS)
    }

    // ------------------------------------------------------------------------------------------ touch: a drag anywhere in the rect; a tap does nothing
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (table == null || locked || !framesIn) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(goHome)
                stopAnim()
                vel = 0f
                dragTo = pos
                dragging = true
                moved = false
                downX = e.x; downY = e.y; lastX = e.x
                vt?.recycle()
                vt = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (!dragging) return true
                if (!moved && abs(e.x - downX) > slop && abs(e.x - downX) > abs(e.y - downY)) {
                    moved = true
                    lastX = e.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    kick()
                }
                if (moved) {
                    dragTo += (e.x - lastX) * framesPerPx
                    lastX = e.x
                    kick()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) return true
                dragging = false
                if (moved) {
                    vt?.let {
                        it.addMovement(e)
                        it.computeCurrentVelocity(1000)
                        vel = (it.xVelocity * framesPerPx).coerceIn(-maxVel, maxVel)
                    }
                    if (abs(vel) < minVel) {
                        vel = 0f
                        // rest where the finger left it
                        animatePos(dragTo.roundToInt().toFloat(), 200, DecelerateInterpolator()) { scheduleHome() }
                    }
                    kick()
                } else {
                    animatePos(pos.roundToInt().toFloat(), 200, DecelerateInterpolator()) { scheduleHome() }
                }
                vt?.recycle(); vt = null
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                vt?.recycle(); vt = null
                animatePos(dragTo.roundToInt().toFloat(), 200, DecelerateInterpolator()) { scheduleHome() }
            }
        }
        return true
    }

    // ------------------------------------------------------------------------------------------ drawing
    override fun onDraw(c: Canvas) {
        pushGl()
    }

    private fun redraw() {
        if (isShown) pushGl() else invalidate()
    }

    /** The state to the GPU: frames, blend, how much is seen, and the crisp copy of a resting frame. */
    private fun pushGl() {
        val g = gl ?: return
        val base = floor(pos)
        val t = pos - base
        val a = Math.floorMod(base.toInt(), n)
        val atRest = !moving() && t < 0.001f
        if (atRest) {
            if (restAsked != a) {
                restAsked = a
                if (restFor != a) { restFor = -1; restMix = 0f }
                restAnim?.cancel()
                g.prepareRest(a)
            }
        } else if (restAsked != -1) {
            restAsked = -1
            restFor = -1
            restMix = 0f
            restAnim?.cancel()
        }
        state.a = a
        state.b = (a + 1) % n
        state.t = t
        state.shown = presence * dim
        state.rest = restFor == a
        state.restMix = restMix
        g.render(state)
        lastFps = g.fps
    }

    companion object {
        private const val DEG_PER_PX = 0.44f      // turn per pixel of drag
        private const val MAX_DEG_S = 440f        // fastest spin, degrees per second
        private const val MIN_DEG_S = 6.4f        // slower than this the spin stops
        private const val FRICTION = 2.4f         // per second (exponential decay of the spin)
        private const val IDLE_MS = 3200L
        private const val FOLLOW = 24f            // per second: how fast the car catches up with the finger
        private const val REST_MS = 120L
        private const val SPIN_MS = 1100L

        /** Diagnostics: is the GPU drawing the car, and how fast it drew while the car last turned. */
        @Volatile var gpuDrawing = false
        @Volatile var lastFps = 0f

        /** There was a line here for how a start's car came on screen, by which of the old paths: there is one path now. */
        fun startNote(c: Context): String = ""
    }
}
