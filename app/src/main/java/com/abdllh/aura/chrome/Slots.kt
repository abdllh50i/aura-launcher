package com.abdllh.aura.chrome

import android.animation.TimeInterpolator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Picture
import android.location.Location
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.nav.ManeuverView
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.nav.RoadWatch
import com.abdllh.aura.nav.Units
import com.abdllh.aura.phone.Calls
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.LimitSign
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.label
import kotlin.math.roundToInt

/**
 * The theme change's picture, topmost and not drawn at rest (SPEC.md sections 6.0 and 8): [com.abdllh.aura.ui.ThemeSwap] puts what the window showed in it, on a hardware layer, and fades it.
 * Touches go through it. INVISIBLE where the spec says GONE, here and in [Flyer]: it is drawn no more than a GONE view, and coming back it asks for no layout, in the frame that counts.
 */
class Ghost(ctx: Context) : View(ctx) {
    private var picture: Picture? = null

    init { visibility = INVISIBLE }

    fun hold(p: Picture) {
        animate().cancel()
        picture = p
        alpha = 1f
        visibility = VISIBLE
        if (layerType != LAYER_TYPE_HARDWARE) setLayerType(LAYER_TYPE_HARDWARE, null)
        invalidate()
    }

    fun clear() {
        animate().cancel()
        picture = null
        setLayerType(LAYER_TYPE_NONE, null)
        visibility = INVISIBLE
    }

    override fun onDraw(c: Canvas) {
        picture?.let { c.drawPicture(it) }
    }
}

/**
 * The cover's flight, one bitmap on one layer, not drawn at rest (SPEC.md sections 4 and 9): a square copy of the cover, [SIZE] px, scaled from one rect to another, its corners by a rounded
 * outline that is asked for anew at each frame, for the flight only. Nothing here knows about songs: the screen gives what to fly and from where to where, in the window's coordinates, and shows
 * the real cover under it for the last 60 ms.
 */
class Flyer(ctx: Context) : ImageView(ctx) {
    private var radiusNow = 0f
    private var end: (() -> Unit)? = null
    private var move: (Float) -> Unit = { }

    private val run = Tweens(onRest = {
        visibility = INVISIBLE
        setLayerType(LAYER_TYPE_NONE, null)
        setImageBitmap(null)
        end?.invoke()
        end = null
    })
    private val part = run.part(0f) { k -> move(k) }

    init {
        visibility = INVISIBLE
        scaleType = ScaleType.FIT_XY
        pivotX = 0f
        pivotY = 0f
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                if (v.width > 0) o.setRoundRect(0, 0, v.width, v.height, if (v.scaleX > 0.05f) radiusNow / v.scaleX else radiusNow)
            }
        }
        clipToOutline = true
    }

    /** Over [ms] on [curve]: from a square at ([x0], [y0]) of [s0] px and radius [r0] to ([x1], [y1]), [s1], [r1]; [done] at the end. A second flight turns this one back from where it is. */
    fun fly(bitmap: Bitmap, x0: Float, y0: Float, s0: Float, r0: Float, x1: Float, y1: Float, s1: Float, r1: Float, ms: Long, curve: TimeInterpolator = Motion.MOVE, done: () -> Unit = {}) {
        end = done
        setImageBitmap(bitmap)
        visibility = VISIBLE
        if (layerType != LAYER_TYPE_HARDWARE) setLayerType(LAYER_TYPE_HARDWARE, null)
        val fromX = if (run.isRunning) translationX else x0
        val fromY = if (run.isRunning) translationY else y0
        val fromS = if (run.isRunning) scaleX * SIZE else s0
        val fromR = if (run.isRunning) radiusNow else r0
        move = { k ->
            val s = fromS + (s1 - fromS) * k
            translationX = fromX + (x1 - fromX) * k
            translationY = fromY + (y1 - fromY) * k
            scaleX = s / SIZE
            scaleY = s / SIZE
            radiusNow = fromR + (r1 - fromR) * k
            invalidateOutline()
        }
        part.snap(0f)
        part.to(1f, 0L, ms, curve)
    }

    companion object {
        /** A square copy of the big cover, as section 4 has it. */
        const val SIZE = 204
    }
}

/**
 * Black, with speed, limit, arrow and distance only, in `nightPanelInk`, never white (SPEC.md section 6.9), not drawn until asked and built at that first time. In: a long press on the strip's left end, or by [show]
 * from Controls and voice. Out: a touch anywhere. By itself it opens for a call, for a Saher camera under 400 m and for a turn under 300 m, in 140 ms, and comes back 5 s after, unless he touched.
 */
class NightPanel(ctx: Context) : FrameLayout(ctx) {
    private var built = false
    private lateinit var arrow: ManeuverView
    private lateinit var dist: AText
    private lateinit var speed: AText
    private lateinit var sign: LimitSign
    private var woken = false
    private val back = Runnable {
        woken = false
        if (isUp) fadeTo(1f, Tokens2.MS_swap, Motion.MOVE)
    }

    val isUp: Boolean get() = visibility == VISIBLE

    /** The map's owner: frames down to 15 a second while this covers it, and back. */
    var onUp: ((Boolean) -> Unit)? = null

    init {
        visibility = INVISIBLE
        isClickable = true
    }

    override fun hasOverlappingRendering() = false

    private fun build() {
        if (built) return
        built = true
        setBackgroundColor(Palette.nightPanelBg)
        arrow = ManeuverView(context).apply { color = Palette.nightPanelInk; visibility = INVISIBLE }
        addView(arrow, at(64, 150, 150, 150))
        dist = context.label(Type.nightDist, Palette.nightPanelInk).apply { textDirection = TEXT_DIRECTION_LTR; textAlignment = TEXT_ALIGNMENT_VIEW_START }
        addView(dist, Type.nightDist.box(64, 316, 400))
        speed = context.label(Type.nightPanel, Palette.nightPanelInk).apply { textDirection = TEXT_DIRECTION_LTR; textAlignment = TEXT_ALIGNMENT_VIEW_START }
        addView(speed, Type.nightPanel.box(520, 210, 170))
        sign = LimitSign(context, LimitSign.Spec(84, Type.limit, 6f, 5f, -0.8f), dim = true)
        addView(sign, at(690, 222, 84, 84))
    }

    fun show() {
        build()
        if (isUp) return
        woken = false
        removeCallbacks(back)
        alpha = 1f
        visibility = VISIBLE
        sync()
        CarLocation.addListener(onFix)
        NavSession.addListener(onNav)
        RoadWatch.addListener(onNav)
        Calls.addListener(onNav)
        onUp?.invoke(true)
    }

    fun hide() {
        if (!isUp) return
        removeCallbacks(back)
        animate().cancel()
        visibility = INVISIBLE
        CarLocation.removeListener(onFix)
        NavSession.removeListener(onNav)
        RoadWatch.removeListener(onNav)
        Calls.removeListener(onNav)
        onUp?.invoke(false)
    }

    private val onFix: (Location) -> Unit = { post { sync() } }
    private val onNav: () -> Unit = { post { sync() } }

    private fun sync() {
        if (!isUp) return
        val fix = CarLocation.last
        val kmh = fix?.takeIf { it.hasSpeed() }?.let { (it.speed * 3.6f).roundToInt().toString() } ?: "—"
        if (kmh != speed.text.toString()) speed.text = kmh
        val w = RoadWatch.state
        sign.visibility = if (w.limit > 0) VISIBLE else INVISIBLE
        sign.kmh = w.limit
        val p = NavSession.progress
        val step = p?.let { NavSession.route?.steps?.getOrNull(it.next) }
        if (p != null && step != null && NavSession.state == NavSession.State.NAVIGATING) {
            arrow.visibility = VISIBLE
            arrow.show(step)
            val d = Units.distance(p.toNext, Fonts.arabicUi)
            if (d != dist.text.toString()) dist.text = d
        } else {
            arrow.visibility = INVISIBLE
            if (dist.text.isNotEmpty()) dist.text = ""
        }
        val camera = w.spot != null && w.spot.kind == com.abdllh.aura.nav.MapExtras.Kind.CAMERA && w.metres < WAKE_SAHER_M
        val turn = p != null && p.toNext < WAKE_TURN_M && NavSession.state == NavSession.State.NAVIGATING
        val call = Calls.state == Calls.State.RINGING || Calls.state == Calls.State.TALKING || Calls.state == Calls.State.DIALING
        if (call || camera || turn) wake()
    }

    /** A moment that must be seen: the screen under it, then back in 5 s, unless he touched. */
    private fun wake() {
        removeCallbacks(back)
        postDelayed(back, WAKE_MS)
        if (woken) return
        woken = true
        fadeTo(0f, Tokens2.MS_swap, Motion.MOVE)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) hide()
        return true
    }

    override fun onDetachedFromWindow() {
        hide()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val WAKE_MS = 5000L
        const val WAKE_SAHER_M = 400
        const val WAKE_TURN_M = 300.0
    }
}
