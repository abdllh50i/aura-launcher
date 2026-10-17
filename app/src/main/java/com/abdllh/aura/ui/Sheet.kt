package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Outline
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.util.dp
import kotlin.math.abs

/**
 * Bottom sheet over the home screen: dimmed scrim, rounded panel sliding up from the bottom edge, a grab handle,
 * tap outside or drag down to close. The panel moves on a spring: a fling carries its speed into the motion, and a
 * sheet closed half way while it opens turns back from where it is. Subclasses fill [panel].
 * Closing hands the screen back at once: from the moment [close] is called touches go through to what is under the
 * sheet (until 1.11 the first tap after closing was often lost to a sheet that was out of sight but not gone).
 */
open class Sheet(ctx: Context, fullHeight: Boolean) : FrameLayout(ctx) {
    // (a single fill: its alpha goes into the colour, where a plain View fading was drawn through a full-screen
    // offscreen buffer on every frame of every open, close and drag)
    private val scrim = object : View(ctx) { override fun hasOverlappingRendering() = false }
        .apply { setBackgroundColor(Palette.scrim); alpha = 0f }
    protected val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    var isOpen = false
        private set
    var onClosed: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var touching = false // a finger came down while the sheet was open and is not up yet
    private var vt: VelocityTracker? = null
    private var finished = true // closed and reported (onClosed) since the last open
    private var closeFrom = 1f // how far open the panel was when the close began
    private val spring = Spring(0f, 300f, 0.86f, restDelta = 1.5f) { y -> moved(y) }
        .also { s -> s.onRest = { if (isOpen) onOpened() else finish() } }

    init {
        visibility = GONE
        isClickable = true
        addView(scrim, flp(MATCH, MATCH))
        scrim.setOnClickListener { close() }
        panel.background = Shapes.topRounded(Palette.sheet, Radius.XL)
        panel.isClickable = true
        // a light-theme shadow only (on the dark one the scrim hides it), from a round rectangle reaching past the
        // bottom edge: a top-rounded outline is a path, whose shadow is the expensive kind, re-done as the panel moves
        panel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                val r = Radius.XL.dp
                o.setRoundRect(0, 0, v.width, v.height + r.toInt(), r)
            }
        }
        panel.lift(Elev.FLOAT)
        panel.addView(View(ctx).apply { background = Shapes.pill(Palette.withAlpha(Palette.text3, 0.5f)) },
            lp(44.dp, 5.dp).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 10.dp })
        addView(panel, flp(MATCH, if (fullHeight) MATCH else WRAP, Gravity.BOTTOM).apply {
            if (fullHeight) topMargin = 14.dp
            marginStart = 10.dp
            marginEnd = 10.dp
        })
    }

    /** How far down the panel goes to be out of sight. */
    private fun travel(): Float = (if (panel.height > 0) panel.height else resources.displayMetrics.heightPixels).toFloat()

    private fun moved(y: Float) {
        panel.translationY = y
        val k = (1f - y / travel()).coerceIn(0f, 1f)
        // closing, the dim clears ahead of the panel (squared, from where the close began, so it does not jump), so the
        // screen under it is back before the panel is gone
        scrim.alpha = if (isOpen || closeFrom <= 0f) k else (k * k / closeFrom).coerceAtMost(k)
        // the last pixels of a critically damped spring take as long as the rest of the way: out of sight is closed
        if (!isOpen && !dragging && y >= travel() - 2.dp) finish()
    }

    private fun finish() {
        if (finished || isOpen) return
        finished = true
        spring.cancel()
        panel.translationY = travel()
        scrim.alpha = 0f
        visibility = GONE
        onClosed?.invoke()
    }

    fun open(animate: Boolean = true) {
        if (isOpen) return
        isOpen = true
        finished = false
        visibility = VISIBLE
        onOpen()
        if (!animate) { spring.snapTo(0f); return }
        spring.stiffness = 300f
        spring.dampingRatio = 0.86f
        if (spring.isRunning) { spring.animateTo(0f); return } // closing half way: back up from there
        spring.snapTo(travel())
        if (panel.height == 0) post { if (isOpen) { spring.snapTo(travel()); spring.animateTo(0f) } } // (laid out first)
        else spring.animateTo(0f)
    }

    fun close() = close(0f)

    private fun close(velocity: Float) {
        if (!isOpen) return
        isOpen = false
        if (touching && !dragging) {
            // closed under a finger (Back, Home, the host): what it was on is told the touch is over, since the rest
            // of the gesture no longer reaches it (a drag of the sheet itself finishes in onTouchEvent instead)
            touching = false
            val now = SystemClock.uptimeMillis()
            val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            super.dispatchTouchEvent(c)
            c.recycle()
        }
        closeFrom = (1f - panel.translationY / travel()).coerceIn(0f, 1f)
        onClose()
        // quicker than the open, and moving from the first frame: from rest a spring starts slowly, so it is given
        // a push (or a fling's own speed, when that is more)
        spring.stiffness = 600f
        spring.dampingRatio = 1f
        spring.animateTo(travel(), maxOf(velocity, travel() * 3f))
    }

    protected open fun onOpen() {}
    /** The open movement has come to rest (e.g. pause what the sheet covers entirely). */
    protected open fun onOpened() {}
    protected open fun onClose() {}

    /**
     * Area at the top of the panel where a downward drag closes the sheet: the whole panel by default. A drag that
     * starts on something that can still scroll up (a list not at its top) scrolls it instead, whatever the zone.
     */
    protected open fun dragZone(): Int = panel.height

    // (the panel moves under the finger now: its children show their press only once a tap is clear, as in a list)
    override fun shouldDelayChildPressedState() = true

    // Closed, a sheet takes no new touch; one that began while it was open is cancelled once when it closes (see
    // [close]), so the control under the finger hears it end: a slider let go, a tile's press undone, no long-press.
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { if (!isOpen) return false; touching = true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        return if (!isOpen && !dragging) false else super.dispatchTouchEvent(e)
    }

    /** Whether a view under ([x], [y]) (in this sheet's coordinates) can still scroll up. */
    private fun scrollsUpAt(v: View, x: Float, y: Float): Boolean {
        if (!v.isShown) return false
        if (v !== this && v.canScrollVertically(-1)) return true
        if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) {
            val c = v.getChildAt(i)
            val cx = x + v.scrollX - c.left - c.translationX
            val cy = y + v.scrollY - c.top - c.translationY
            if (cx >= 0 && cy >= 0 && cx < c.width && cy < c.height && scrollsUpAt(c, cx, cy)) return true
        }
        return false
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val top = panel.top + panel.translationY
                val inZone = downY >= top && downY <= top + dragZone()
                val dy = e.y - downY
                if (isOpen && inZone && dy > slop && dy > abs(e.x - downX) && !scrollsUpAt(this, downX, downY)) {
                    dragging = true
                    spring.cancel()
                    downY = e.y - panel.translationY
                    vt?.recycle()
                    vt = VelocityTracker.obtain()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(e)
        if (!isOpen) {
            // closed meanwhile from outside (Home or Back while the finger was on it): let go, and finish closing
            dragging = false
            vt?.recycle(); vt = null
            if (!spring.isRunning || spring.target != travel()) spring.animateTo(travel())
            return true
        }
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY
                // pulled up past the top: it resists (a quarter of the movement)
                spring.snapTo(if (dy >= 0f) dy else dy / 4f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                vt?.computeCurrentVelocity(1000)
                val v = vt?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                if (panel.translationY > panel.height * 0.28f || v > 900) close(v)
                else {
                    spring.stiffness = 300f
                    spring.dampingRatio = 0.8f
                    spring.animateTo(0f, v)
                }
            }
        }
        return true
    }
}
