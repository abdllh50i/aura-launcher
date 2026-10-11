package com.abdllh.aura.ui

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.util.dp

/**
 * Bottom sheet over the home screen: dimmed scrim, rounded panel sliding up from the bottom edge, a grab handle,
 * tap outside or drag down to close. The panel moves on a spring: a fling carries its speed into the motion, and a
 * sheet closed half way while it opens turns back from where it is. Subclasses fill [panel].
 */
open class Sheet(ctx: Context, fullHeight: Boolean) : FrameLayout(ctx) {
    private val scrim = View(ctx).apply { setBackgroundColor(Palette.scrim); alpha = 0f }
    protected val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    var isOpen = false
        private set
    var onClosed: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downY = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null
    private val spring = Spring(0f, 300f, 0.86f, restDelta = 0.5f) { y ->
        panel.translationY = y
        scrim.alpha = (1f - y / travel()).coerceIn(0f, 1f)
    }.also { s -> s.onRest = { if (!isOpen) { visibility = GONE; onClosed?.invoke() } } }

    init {
        visibility = GONE
        isClickable = true
        addView(scrim, flp(MATCH, MATCH))
        scrim.setOnClickListener { close() }
        panel.background = Shapes.topRounded(Palette.sheet, 26f)
        panel.isClickable = true
        panel.elevate(18f)
        panel.addView(View(ctx).apply { background = Shapes.rect(Palette.withAlpha(Palette.text3, 0.5f), 3f) },
            lp(44.dp, 5.dp).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 10.dp })
        addView(panel, flp(MATCH, if (fullHeight) MATCH else WRAP, Gravity.BOTTOM).apply {
            if (fullHeight) topMargin = 14.dp
            marginStart = 10.dp
            marginEnd = 10.dp
        })
    }

    /** How far down the panel goes to be out of sight. */
    private fun travel(): Float = (if (panel.height > 0) panel.height else resources.displayMetrics.heightPixels).toFloat()

    fun open(animate: Boolean = true) {
        if (isOpen) return
        isOpen = true
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
        onClose()
        spring.stiffness = 360f
        spring.dampingRatio = 1f
        spring.animateTo(travel(), velocity)
    }

    protected open fun onOpen() {}
    protected open fun onClose() {}

    /** Area at the top of the panel where a downward drag closes the sheet. */
    protected open fun dragZone(): Int = 64.dp

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val inZone = downY >= panel.top + panel.translationY && downY <= panel.top + panel.translationY + dragZone()
                if (isOpen && inZone && e.y - downY > slop) {
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
