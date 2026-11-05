package com.abdllh.aura.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.abs

/**
 * A list that rests on its rows (SPEC.md sections 5 and 6.6, "scroll snaps to rows"): a fling goes on to the row it would reach, a slow release to the nearest. [pitch]: a list of even
 * rows; 0, the tops of its column's children, where a row taller than the screen is left where it is. No scrollbar, no glow and no fading edge (an offscreen layer per frame).
 */
class SnapScroll(ctx: Context) : ScrollView(ctx) {
    var pitch = 0

    /** Every position the list goes through. */
    var onMoved: ((Int) -> Unit)? = null

    private var flung = false

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = false
        isVerticalFadingEdgeEnabled = false
    }

    /** How far it can scroll. */
    val most: Int get() = ((getChildAt(0)?.height ?: 0) - height).coerceAtLeast(0)

    private fun stops(): List<Int> {
        val out = ArrayList<Int>()
        val top = most
        if (pitch > 0) {
            var y = 0
            while (y < top) { out.add(y); y += pitch }
        } else {
            (getChildAt(0) as? ViewGroup)?.let { tops(it, 0, top, out) }
            if (out.isEmpty() || out[0] != 0) out.add(0, 0)
        }
        out.add(top)
        return out
    }

    /** The tops of what is in [col]: a section longer than half a screen by its rows, anything else whole. */
    private fun tops(col: ViewGroup, at: Int, top: Int, out: MutableList<Int>) {
        for (i in 0 until col.childCount) {
            val c = col.getChildAt(i)
            if (c.visibility != View.VISIBLE || c.height <= 1 || at + c.top >= top) continue
            if (c is ViewGroup && c.childCount > 1 && c.height > height / 2) tops(c, at + c.top, top, out) else out.add(at + c.top)
        }
    }

    override fun fling(velocityY: Int) {
        flung = true
        val aim = scrollY + (velocityY * FLING_S).toInt()
        val s = stops()
        val to = if (velocityY > 0) s.firstOrNull { it >= aim } ?: most else s.lastOrNull { it <= aim } ?: 0
        smoothScrollTo(0, to)
    }

    private val settle = Runnable {
        if (flung) return@Runnable
        val y = scrollY
        val to = stops().minByOrNull { abs(it - y) } ?: return@Runnable
        if (to != y && abs(to - y) <= height / 2) smoothScrollTo(0, to)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { flung = false; removeCallbacks(settle) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> post(settle)
        }
        return super.onTouchEvent(e)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onMoved?.invoke(t)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        onMoved?.invoke(scrollY)
    }

    /** Whether it can move at all, at its top or at its bottom end: for its fades. */
    val roomAbove: Boolean get() = scrollY > 0
    val roomBelow: Boolean get() = scrollY < most

    private companion object {
        /** How far a fling is taken to reach, in seconds of its speed. */
        const val FLING_S = 0.18f
    }
}
