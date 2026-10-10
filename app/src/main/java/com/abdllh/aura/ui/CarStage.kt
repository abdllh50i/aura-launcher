package com.abdllh.aura.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import com.abdllh.aura.util.dp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The user's car on a turntable (frames from [CarFrames]). Drag sideways to turn it, flick to spin it; it comes to
 * rest with inertia and, after a few idle seconds, swings back to its showroom angle. While it turns the view eases
 * out to frame the whole turntable, and back in when it returns. Adjacent frames are cross-faded during motion.
 */
class CarStage(context: Context) : View(context) {
    var onTap: (() -> Unit)? = null

    private val meta = CarFrames.meta(context)
    private val n = meta?.count ?: 1
    // the feel is set in degrees; the frames' step turns it into frames
    private val step = meta?.yawStep ?: 4f
    private val framesPerPx = DEG_PER_PX / step
    private val maxVel = MAX_DEG_S / step
    private val minVel = MIN_DEG_S / step
    private val lookAhead = (24f / step).roundToInt().coerceAtLeast(2)
    private val standIn = (32f / step).roundToInt().coerceAtLeast(2)

    private var home = 0f            // resting frame (depends on the layout direction)
    private var homeResolved = false
    private var pos = 0f             // current position in frames (not wrapped)
    private var dragTo = 0f          // where the finger has turned the car to: [pos] follows it on every display frame
    private var vel = 0f             // frames per second
    private var zoom = 0f            // 0 = hero framing of the resting view, 1 = framing of the whole turntable
    private var zoomTarget = 0f
    private var shown = 1f           // fade-in of the car and its floor light
    private var dragging = false
    private var moved = false
    private var wasMoving = false    // the current touch began on a moving car
    private var motionDir = 0        // last direction of motion (+1 / -1), for prefetching
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var downAt = 0L
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var anim: ValueAnimator? = null
    private var ticking = false
    private var lastFrameNs = 0L

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadeStart = Paint()
    private val fadeEnd = Paint()
    private val fadeBottom = Paint()
    private val mat = Matrix()
    private val floorMat = Matrix()
    private var floorShader: RadialGradient? = null
    private val pt = FloatArray(2)

    // framing (computed in onSizeChanged): scale + the frame point that sits at the view centre
    private var heroScale = 1f
    private var fitScale = 1f
    private var heroCx = 0f
    private var heroCy = 0f
    private var fitCx = 0f
    private var fitCy = 0f

    private val onFrameReady: () -> Unit = { if (isShown) invalidate() }
    private val goHome = Runnable { settleHome() }
    private val frameCallback = Choreographer.FrameCallback { t -> tick(t) }

    init {
        isClickable = true
        contentDescription = context.getString(com.abdllh.aura.R.string.home_car)
    }

    private fun resolveHome() {
        val m = meta ?: return
        if (homeResolved) return
        homeResolved = true
        home = (if (isRtl()) m.defaultFrameRtl else m.defaultFrame).toFloat()
        pos = home
        computeFraming()
    }

    // from the configuration: the view's own direction is only resolved at its first measure, after attach/intro
    private fun isRtl() = resources.configuration.layoutDirection == LAYOUT_DIRECTION_RTL

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CarFrames.addListener(onFrameReady)
        resolveHome()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        CarFrames.removeListener(onFrameReady)
        removeCallbacks(goHome)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        ticking = false
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        resolveHome()
        computeFraming()
        val bg = Palette.bg
        val e = EDGE.dp
        fadeStart.shader = LinearGradient(0f, 0f, e, 0f, bg, bg and 0x00FFFFFF, Shader.TileMode.CLAMP)
        fadeEnd.shader = LinearGradient(w - e, 0f, w.toFloat(), 0f, bg and 0x00FFFFFF, bg, Shader.TileMode.CLAMP)
        fadeBottom.shader = LinearGradient(0f, h - 22.dp.toFloat(), 0f, h.toFloat(), bg and 0x00FFFFFF, bg, Shader.TileMode.CLAMP)
    }

    private fun computeFraming() {
        val m = meta ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val b = m.boxes[Math.floorMod(home.roundToInt(), n)]
        val u = m.union
        heroScale = minOf(w * 0.84f / (b[2] - b[0]), h * 0.74f / (b[3] - b[1]))
        fitScale = minOf(w * 0.88f / (u[2] - u[0]), h * 0.74f / (u[3] - u[1]))
        heroCx = (b[0] + b[2]) / 2f
        heroCy = (b[1] + b[3]) / 2f
        fitCx = (u[0] + u[2]) / 2f
        fitCy = (u[1] + u[3]) / 2f
    }

    // ------------------------------------------------------------------------------------------ animation
    /** Intro: the car fades in and turns its face towards the screen, ending on the showroom angle. */
    fun playIntro(delayMs: Long = 120) {
        if (meta == null) return
        resolveHome()
        removeCallbacks(goHome)
        stopAnim()
        vel = 0f
        val dir = CarFrames.introDirection(isRtl())
        motionDir = dir
        val start = home - dir * CarFrames.introFrames()
        pos = start
        shown = 0f
        zoom = 1f
        zoomTarget = 0f
        invalidate()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1900
            startDelay = delayMs
            interpolator = PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)
            addUpdateListener {
                val f = it.animatedValue as Float
                pos = start + (home - start) * f
                shown = (f * 3.2f).coerceAtMost(1f)
                zoom = 1f - f
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (anim !== a) return
                    anim = null
                    pos = home
                    shown = 1f
                    zoom = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    /**
     * Stops the running animation without its end action. (cancel() calls onAnimationEnd synchronously, while [anim]
     * still points at it, so the end handlers' `anim !== a` guard only works if the reference is cleared first.)
     */
    private fun stopAnim() {
        val a = anim
        anim = null
        a?.cancel()
    }

    private fun animatePos(target: Float, durationMs: Long, interp: android.animation.TimeInterpolator, then: (() -> Unit)? = null) {
        stopAnim()
        val from = pos
        if (target != from) motionDir = sign(target - from).toInt()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = interp
            addUpdateListener {
                pos = from + (target - from) * (it.animatedValue as Float)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (anim !== a) return
                    anim = null
                    pos = target
                    invalidate()
                    then?.invoke()
                }
            })
            start()
        }
    }

    /** Swings back to the showroom angle the short way round and eases back into the hero framing. */
    private fun settleHome() {
        if (dragging) return
        val target = home + n * Math.round((pos - home) / n)
        val dist = abs(target - pos)
        zoomTarget = 0f
        kick()
        if (dist < 0.01f) { pos = Math.floorMod(home.roundToInt(), n).toFloat(); return }
        animatePos(target, (520 + dist * step * 5.5f).toLong().coerceAtMost(1500), PathInterpolator(0.45f, 0f, 0.2f, 1f)) {
            pos = Math.floorMod(home.roundToInt(), n).toFloat()
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
        // Touch panels report unevenly (some well below the display rate): the car eases towards the finger every
        // display frame instead of jumping with each report.
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
                // come to rest exactly on a frame (a static cross-fade would look soft), then wait before going home
                animatePos(pos.roundToInt().toFloat(), 220, DecelerateInterpolator()) { scheduleHome() }
            } else {
                more = true
            }
        }
        val dz = zoomTarget - zoom
        if (abs(dz) > 0.002f) {
            zoom += dz * (1f - exp(-dt * 7f))
            more = true
        } else {
            zoom = zoomTarget
        }
        invalidate()
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

    // ------------------------------------------------------------------------------------------ touch
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (meta == null) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(goHome)
                // a touch that only stops the car (mid-spin, swinging home, intro) is not a tap on it
                wasMoving = vel != 0f || anim != null
                if (anim != null) { stopAnim(); shown = 1f; kick() }
                vel = 0f
                dragTo = pos
                dragging = true
                moved = false
                downX = e.x; downY = e.y; lastX = e.x
                downAt = SystemClock.uptimeMillis()
                vt?.recycle()
                vt = VelocityTracker.obtain().also { it.addMovement(e) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (!moved && abs(e.x - downX) > slop && abs(e.x - downX) > abs(e.y - downY)) {
                    moved = true
                    lastX = e.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    zoomTarget = 1f
                    kick()
                }
                if (moved) {
                    val dx = e.x - lastX
                    if (dx != 0f) motionDir = sign(dx).toInt()
                    dragTo += dx * framesPerPx
                    lastX = e.x
                    kick()
                }
            }
            MotionEvent.ACTION_UP -> {
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
                } else if (wasMoving) {
                    animatePos(pos.roundToInt().toFloat(), 200, DecelerateInterpolator()) { scheduleHome() }
                } else {
                    if (SystemClock.uptimeMillis() - downAt < 500) performClick()
                    scheduleHome() // no-op when it is already resting on the showroom angle
                }
                vt?.recycle(); vt = null
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                vt?.recycle(); vt = null
                if (moved || wasMoving) animatePos(dragTo.roundToInt().toFloat(), 200, DecelerateInterpolator()) { scheduleHome() }
                else scheduleHome()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    // ------------------------------------------------------------------------------------------ drawing
    override fun onDraw(c: Canvas) {
        val m = meta ?: return
        if (width == 0 || height == 0) return
        val p = pos
        val base = floor(p)
        val t = p - base
        val a = Math.floorMod(base.toInt(), n)
        val b = (a + 1) % n
        // Until the raw frame file is there, frames are decoded: requests go last-in-first-out, so the look-ahead in
        // the direction of motion is queued farthest first, then the frame being left, then the frame on screen.
        if (!CarFrames.instant) {
            CarFrames.setFocus(a)
            val dir = if (vel != 0f) sign(vel).toInt() else motionDir
            if (dir != 0) for (k in lookAhead downTo 1) CarFrames.request(Math.floorMod(a + dir * k, n))
            else CarFrames.request(Math.floorMod(a - 1, n))
            CarFrames.request(b)
            CarFrames.request(a)
        }

        // framing
        val s = heroScale + (fitScale - heroScale) * zoom
        val fx = heroCx + (fitCx - heroCx) * zoom
        val fy = heroCy + (fitCy - heroCy) * zoom
        val cx = width / 2f
        val cy = height * 0.53f
        mat.setScale(s, s)
        mat.postTranslate(cx - fx * s, cy - fy * s)

        // floor light under the turntable
        pt[0] = m.width / 2f; pt[1] = m.height * FLOOR_Y
        mat.mapPoints(pt)
        val r = minOf(m.width * FLOOR_R * s, width * 0.46f) // fades out before the view edges
        val fs = floorShader ?: RadialGradient(0f, 0f, 1f, intArrayOf(floorColor(1f), floorColor(0.45f), floorColor(0f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP).also { floorShader = it; floorPaint.shader = it }
        floorMat.setScale(r, r)
        floorMat.postTranslate(pt[0], pt[1])
        fs.setLocalMatrix(floorMat)
        floorPaint.alpha = (255 * shown).toInt()
        c.save()
        c.scale(1f, 0.22f, pt[0], pt[1])
        c.drawCircle(pt[0], pt[1], r, floorPaint)
        c.restore()

        // the car: frame a, cross-faded into frame b while moving (exact frames once the raw file is there)
        val ba: Bitmap? = CarFrames.frameNow(a) ?: CarFrames.nearest(if (t >= 0.5f) b else a, standIn)
        if (ba != null) {
            bmpPaint.alpha = (255 * shown).toInt()
            c.drawBitmap(ba, mat, bmpPaint)
            if (t > 0.02f) {
                CarFrames.frameNow(b)?.let { bb ->
                    if (bb !== ba) {
                        bmpPaint.alpha = (255 * shown * t).toInt()
                        c.drawBitmap(bb, mat, bmpPaint)
                    }
                }
            }
        }

        // soften the edges where a wide view of the car would otherwise be cut by the panel
        val e = EDGE.dp
        c.drawRect(0f, 0f, e, height.toFloat(), fadeStart)
        c.drawRect(width - e, 0f, width.toFloat(), height.toFloat(), fadeEnd)
        c.drawRect(0f, height - 22.dp.toFloat(), width.toFloat(), height.toFloat(), fadeBottom)
    }

    private fun floorColor(k: Float): Int =
        if (Palette.dark) Palette.withAlpha(0xFFFFFFFF.toInt(), 0.085f * k) else Palette.withAlpha(0xFFFFFFFF.toInt(), 0.95f * k)

    companion object {
        private const val DEG_PER_PX = 0.44f      // turn per pixel of drag
        private const val MAX_DEG_S = 440f        // fastest spin, degrees per second
        private const val MIN_DEG_S = 6.4f        // slower than this the spin stops
        private const val FRICTION = 2.4f         // per second (exponential decay of the spin)
        private const val IDLE_MS = 3200L
        private const val FOLLOW = 24f            // per second: how fast the car catches up with the finger
        private const val FLOOR_Y = 262f / 360f   // the turntable floor's centre, as a fraction of the frame height
        private const val FLOOR_R = 300f / 640f   // radius of the floor light, as a fraction of the frame width
        private const val EDGE = 20f              // width of the edge fades, dp
    }
}
