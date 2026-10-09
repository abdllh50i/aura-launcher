package com.abdllh.aura.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.util.dp

/**
 * Bottom sheet over the home screen: dimmed scrim, rounded panel sliding up from the bottom edge, a grab handle,
 * tap outside or drag down to close. Subclasses fill [panel].
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

    fun open(animate: Boolean = true) {
        if (isOpen) return
        isOpen = true
        visibility = VISIBLE
        onOpen()
        panel.animate().cancel()
        scrim.animate().cancel()
        if (!animate) {
            panel.translationY = 0f
            scrim.alpha = 1f
            return
        }
        panel.translationY = (if (panel.height > 0) panel.height else resources.displayMetrics.heightPixels).toFloat()
        scrim.animate().alpha(1f).setDuration(240).start()
        panel.animate().translationY(0f).setDuration(380).setInterpolator(PathInterpolator(0.2f, 0.9f, 0.25f, 1f)).setListener(null).start()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        onClose()
        scrim.animate().cancel()
        scrim.animate().alpha(0f).setDuration(200).start()
        panel.animate().translationY(panel.height.toFloat()).setDuration(240).setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    panel.animate().setListener(null)
                    if (!isOpen) { visibility = GONE; onClosed?.invoke() }
                }
            }).start()
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
                val inZone = downY >= panel.top && downY <= panel.top + dragZone()
                if (inZone && e.y - downY > slop) {
                    dragging = true
                    downY = e.y
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
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = (e.y - downY).coerceAtLeast(0f)
                panel.translationY = dy
                scrim.alpha = 1f - (dy / panel.height.coerceAtLeast(1)).coerceIn(0f, 1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                vt?.computeCurrentVelocity(1000)
                val v = vt?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                if (panel.translationY > panel.height * 0.28f || v > 900) close()
                else {
                    panel.animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).setListener(null).start()
                    scrim.animate().alpha(1f).setDuration(220).start()
                }
            }
        }
        return true
    }
}
