package com.abdllh.aura.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
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
import com.abdllh.aura.system.DriveState
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The user's car on a turntable, on the wall (SPEC.md section 10): drawn by [CarGlView] in its rect, the wall's own pixels under it until its first frame. Drag sideways to turn it, flick to spin it;
 * it comes to rest with inertia and, after a few idle seconds, swings back to its showroom angle, frame 108, in both languages. Adjacent frames blend while it moves; at rest the
 * crisp copy of the frame fades in, in one step. The first run bakes the frames ([CarEtc]): from its first few it turns on what is in, coarsely, and says how far it has come; before them a drag has that
 * word alone, and nothing shows at rest. While the car moves the turntable is locked ([locked]), and a drag hears that too. No frame goes to the GPU when nobody can take it: under another panel its thread
 * would hang in the swap two frames later.
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
    private var answered = false
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

    /** The car moves ([DriveState.moving], and no guess of a gear or a trip standing at a light is that): nobody turns it, and it goes back to its angle. */
    var locked = false
        set(v) {
            if (v == field) return
            field = v
            if (v) {
                lockedTimes++
                lockedAt = SystemClock.elapsedRealtime()
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
        now = WeakReference(this)
        // (the frames do not wait for the GL view, whose surface comes only with Home on screen: a start into another panel bakes them too)
        if (table != null) context.applicationContext.let { app -> Handler(Looper.getMainLooper()).postDelayed({ CarEtc.want(app, Palette.dark) }, BAKE_AFTER_MS) }
    }

    /** [onFirstFrame]: the car's first frame is up, or will not be. Once. */
    var onFirstFrame: (() -> Unit)? = null

    /** With no GL at all the car is its rest frame and no floor: its alpha is K, so the wall shows round it. */
    private fun showFallback() {
        val t = table ?: return
        val f = fallback ?: ImageView(context).also {
            fallback = it
            it.scaleType = ImageView.ScaleType.FIT_XY
            addView(it, at((t.x + t.scale * t.box[0] - CarGlView.RECT_X).toInt(), (t.y + t.scale * t.box[1] - CarGlView.RECT_Y).toInt(), (t.scale * t.width).toInt(), (t.scale * t.height).toInt()))
        }
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
    /** Enough frames are in for a finger to turn it: all of them, or the first of a bake, which go round the whole turn. */
    private fun turnable(): Boolean = gl?.let { it.framesIn || it.framesCount >= CarGlView.COARSE_FRAMES } == true

    private fun percent(): Int = (gl?.framesCount ?: 0) * 100 / n

    /** The word for a drag on frames that are not all in. Once per drag, a Snack, nothing at rest. */
    private fun sayNotReady() {
        val dark = Palette.dark
        if (gl == null || gl.broken || CarEtc.failed(dark)) {
            CarEtc.want(context, dark) // (a drag is asking again)
            Snack.show(context, R.string.car_turn_failed)
        } else {
            Snack.show(context, context.getString(R.string.car_turn_wait, percent().toString())) // (Western digits, as everywhere)
        }
    }

    /** A drag while it cannot turn has its answer too, once. */
    private fun refuse() {
        if (locked) {
            noteDrag("locked")
            Snack.show(context, R.string.car_turn_moving)
        } else {
            noteDrag(if (gl == null || gl.broken || CarEtc.failed(Palette.dark)) "failed" else "wait")
            sayNotReady()
        }
    }

    private fun noteDrag(how: String) {
        lastDragAt = SystemClock.elapsedRealtime()
        lastDragHow = how
        lastDragFrames = gl?.framesCount ?: 0
        synchronized(dragCounts) { dragCounts[how] = (dragCounts[how] ?: 0) + 1 }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (table == null) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stageDowns++
                lastDownAt = SystemClock.elapsedRealtime()
                moved = false
                answered = false
                downX = e.x; downY = e.y; lastX = e.x
                if (locked || !turnable()) {
                    dragging = false
                    return true
                }
                removeCallbacks(goHome)
                stopAnim()
                vel = 0f
                dragTo = pos
                dragging = true
                vt?.recycle()
                vt = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                val sideways = abs(e.x - downX) > slop && abs(e.x - downX) > abs(e.y - downY)
                if (!dragging) {
                    if (sideways && !answered) {
                        answered = true
                        refuse()
                    }
                    return true
                }
                vt?.addMovement(e)
                if (!moved && sideways) {
                    moved = true
                    lastX = e.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    kick()
                    if (framesIn) noteDrag("turned")
                    else {
                        // it turns, on what is in: the steps it takes need saying as well
                        noteDrag("coarse")
                        sayNotReady()
                    }
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
                if (!dragging) return true
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
        /** The bake starts by this time after the stage is built, if the GL view has not asked by then (its first frame comes before that with Home on screen). */
        private const val BAKE_AFTER_MS = 2000L

        /**
         * Diagnostics: has the GL view drawn at all (its first frame is the rest frame: with no frame baked this is true), and how fast its last run of draws went (the entrance's fade is one).
         * Whether the car can turn is in [report].
         */
        @Volatile var gpuDrawing = false
        @Volatile var lastFps = 0f

        // what the next Screen check tells of a drag, whoever made it (main thread writes)
        private var now: WeakReference<CarStage>? = null
        @Volatile private var windowDowns = 0
        @Volatile private var stageDowns = 0
        @Volatile private var lastDownAt = 0L
        @Volatile private var lastDragAt = 0L
        @Volatile private var lastDragHow = ""
        @Volatile private var lastDragFrames = 0
        @Volatile private var lockedTimes = 0
        @Volatile private var lockedAt = 0L
        private val dragCounts = HashMap<String, Int>()

        /** A finger came down on the window, from its root, before anything took it: on the car, with the car on screen, it should reach the stage ([stageDowns] in the report). */
        fun windowDown(x: Float, y: Float) {
            val s = now?.get() ?: return
            if (x >= CarGlView.RECT_X && x < CarGlView.RECT_X + CarGlView.RECT_W && y >= CarGlView.RECT_Y && y < CarGlView.RECT_Y + CarGlView.RECT_H && s.isShown) windowDowns++
        }

        /** Can the car turn, and if not why: the Screen check's report (Collect) answers this by itself. Any thread. */
        fun report(): JSONObject {
            val s = now?.get()
            val g = s?.gl
            val t = SystemClock.elapsedRealtime()
            fun ago(at: Long): Any = if (at == 0L) JSONObject.NULL else (t - at) / 1000
            val inAt = g?.framesInAt ?: 0L
            return JSONObject()
                .put("processUpSec", (t - Process.getStartElapsedRealtime()) / 1000)
                .put("bake", CarEtc.report())
                .put("framesInGpu", g?.framesCount ?: 0)
                .put("of", s?.n ?: 0)
                .put("allIn", g?.framesIn == true)
                .put("allInSecAfterProcess", if (inAt == 0L) JSONObject.NULL else (inAt - Process.getStartElapsedRealtime()) / 1000)
                .put("uploadError", g?.uploadError ?: JSONObject.NULL)
                .put("canTurn", s?.turnable() == true)
                .put("glDrawing", g?.drawing == true)
                .put("glBroken", g?.broken == true)
                .put("frameNow", s?.let { Math.floorMod(it.pos.roundToInt(), it.n) } ?: JSONObject.NULL)
                .put("locked", s?.locked == true)
                .put("moving", DriveState.moving)
                .put("lockedTimes", lockedTimes)
                .put("lockedSecAgo", ago(lockedAt))
                .put("windowDownsOnCar", windowDowns)
                .put("stageDowns", stageDowns)
                .put("lastDownSecAgo", ago(lastDownAt))
                .put("lastDrag", if (lastDragAt == 0L) JSONObject.NULL else JSONObject()
                    .put("secAgo", ago(lastDragAt))
                    .put("how", lastDragHow)
                    .put("framesInThen", lastDragFrames))
                .put("drags", synchronized(dragCounts) { JSONObject(dragCounts as Map<*, *>) })
        }

        /** There was a line here for how a start's car came on screen, by which of the old paths: there is one path now. */
        fun startNote(c: Context): String = ""
    }
}
