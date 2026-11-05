package com.abdllh.aura.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.abdllh.aura.R

/**
 * A short message in AMRI's own style, in place of Android's Toast (which the platform draws in its own font and colours). The
 * `pill` 9-patch, 60 high and up to 620 wide, centred in the free area at y 446 (SPEC.md section 5), one view in one [SnackHost] over
 * everything in the window, that touches go through. In: alpha and y +8 to 0, 160 `enter`; it stays 2.5 s (4 s when [long]) and goes,
 * alpha and y +8, 120 `exit`. A new message while one shows changes its text in place instead of coming in again. While [waitFor]
 * says so (a voice card lies there) a message waits. Without an activity to show it in (a service, a receiver, an activity that is
 * finishing: the message must outlive it) it falls back to Android's Toast.
 * A caller that leaves the screen right after the message (finishes, or starts another screen over it) passes [outlives]: a Snack
 * belongs to its window and would go, or be covered, with it, unread; a Toast stays on top.
 */
object Snack {
    /** 1.x placed it from the bottom. It has one place now; the argument stays for old callers. */
    const val ABOVE_DP = 24

    /** True while something lies where a Snack shows: the voice card ([com.abdllh.aura.voice.AmriOverlay] says). */
    var waitFor: (() -> Boolean)? = null

    private val main = Handler(Looper.getMainLooper())

    fun show(ctx: Context, msg: Int, long: Boolean = false, icon: Int = 0, aboveDp: Int = ABOVE_DP, outlives: Boolean = false) =
        show(ctx, ctx.getString(msg), long, icon, aboveDp, outlives)

    fun show(ctx: Context, msg: CharSequence, long: Boolean = false, icon: Int = 0, @Suppress("UNUSED_PARAMETER") aboveDp: Int = ABOVE_DP, outlives: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { show(ctx, msg, long, icon, aboveDp, outlives) }
            return
        }
        val a = if (outlives) null else activityOf(ctx)?.takeIf { !it.isFinishing && !it.isDestroyed }
        val content = a?.findViewById<ViewGroup>(android.R.id.content)
        if (a == null || content == null) {
            Toast.makeText(ctx.applicationContext, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            return
        }
        if (waitFor?.invoke() == true) {
            // (a message older than a sentence is not worth showing)
            main.postDelayed({ show(ctx, msg, long, icon, aboveDp, false) }, WAIT_MS)
            return
        }
        // (the shell has one slot for it, under the Night Panel, the Flyer and the Ghost; an old screen that is still an activity gets one of its own)
        val slot = (content.getChildAt(0) as? com.abdllh.aura.home.ShellRoot)?.snack
        val host = slot ?: (content.getTag(R.id.aura_snack) as? SnackHost)?.takeIf { it.parent === content } ?: SnackHost(a).also {
            content.setTag(R.id.aura_snack, it)
            content.addView(it, flp(MATCH, MATCH))
        }
        if (slot == null && content.getChildAt(content.childCount - 1) !== host) host.bringToFront()
        host.say(msg, icon, if (long) LONG_MS else SHORT_MS)
    }

    private const val SHORT_MS = 2500L
    private const val LONG_MS = 4000L
    private const val WAIT_MS = 600L

    private fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

/**
 * One view over the window's content, holding the one [Snack] pill at its place. It is not a target: every touch goes through to
 * what is under it, also on the pill.
 */
class SnackHost(a: Context) : FrameLayout(a), Recolours {
    private val pill = Pill(a)

    init {
        visibility = GONE
        val o = Surface.PILL.outset
        addView(pill, FrameLayout.LayoutParams(WRAP, HEIGHT + 2 * o, Gravity.LEFT or Gravity.TOP).apply { topMargin = Y - o })
    }

    override fun dispatchTouchEvent(e: MotionEvent) = false

    override fun onLook() = Unit

    // (it lies beside the shell's root, not in it: a theme change finds it among the windows of their own)
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Look.opened(this)
    }

    override fun onDetachedFromWindow() {
        Look.closed(this)
        super.onDetachedFromWindow()
    }

    // (centred in the free area x 384..1012, whatever its width: fixed here, so a new message lays out only the pill)
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val w = pill.measuredWidth
        val x = Tokens2.FREE_X + (Tokens2.FREE_W - w) / 2
        pill.layout(x, pill.top, x + w, pill.bottom)
    }

    private val hide = Runnable {
        pill.exit(dy = 8f, dur = Tokens2.MS_cardOut) { visibility = GONE }
    }

    fun say(msg: CharSequence, icon: Int, ms: Long) {
        val coming = visibility != View.VISIBLE || pill.getTag(R.id.aura_exiting) == true
        visibility = VISIBLE
        pill.change(msg, icon, fade = !coming)
        if (coming) pill.enter(dy = 8f, dur = IN_MS)
        pill.announceForAccessibility(msg)
        removeCallbacks(hide)
        postDelayed(hide, ms)
    }

    private inner class Pill(a: Context) : LinearLayout(a), Recolours {
        private val img = ImageView(a)
        private val text = a.label(Type.chip, { Palette.ink })
        private val plate = SurfaceBackground(a, Surface.PILL)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = plate
            val o = Surface.PILL.outset
            setPadding(o + 22, o, o + 22, o)
            img.visibility = GONE
            addView(img, lp(26, 26).apply { marginEnd = 10 })
            addView(text, lp(WRAP, Type.chip.viewH))
        }

        override fun onLook() {
            plate.recolour()
            img.setColorFilter(Palette.ink2)
        }

        fun change(msg: CharSequence, icon: Int, fade: Boolean) {
            if (!fade) { set(msg, icon); return }
            text.fadeTo(0f, Motion.XSHORT, Motion.EXIT) { set(msg, icon); text.fadeTo(1f) }
        }

        private fun set(msg: CharSequence, icon: Int) {
            text.text = msg
            text.maxWidth = MAX_W - 44 - if (icon != 0) 36 else 0
            if (icon != 0) { img.icon(icon, Palette.ink2); img.visibility = VISIBLE } else img.visibility = GONE
        }
    }

    private companion object {
        const val HEIGHT = 60
        const val MAX_W = 620
        const val Y = 446
        const val IN_MS = 160L
    }
}
