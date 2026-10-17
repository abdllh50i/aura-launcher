package com.abdllh.aura.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.abdllh.aura.R
import com.abdllh.aura.util.dp

/**
 * A short message in Aura's own style, in place of Android's Toast (which the platform draws in its own font and
 * colours: Arabic in the dated system Naskh the app replaced, see [Fonts]). A pill of the overlay family
 * ([Shapes.overlay]) at the bottom of the activity, over everything in it, that touches go through; it rises in,
 * stays 2.5 s (4 s when [long]) and goes. A new message while one shows changes its text in place instead of
 * coming in again. Without an activity to show it in (a service, a receiver, an activity that is finishing:
 * the message must outlive it) it falls back to Android's Toast.
 */
object Snack {
    /** Default distance from the bottom of the activity, in dp (the home screen passes its dock's height plus 16). */
    const val ABOVE_DP = 24

    fun show(ctx: Context, msg: Int, long: Boolean = false, icon: Int = 0, aboveDp: Int = ABOVE_DP) =
        show(ctx, ctx.getString(msg), long, icon, aboveDp)

    fun show(ctx: Context, msg: CharSequence, long: Boolean = false, icon: Int = 0, aboveDp: Int = ABOVE_DP) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { show(ctx, msg, long, icon, aboveDp) }
            return
        }
        val a = activityOf(ctx)
        val content = a?.takeIf { !it.isFinishing && !it.isDestroyed }?.findViewById<ViewGroup>(android.R.id.content)
        if (content == null) {
            Toast.makeText(ctx.applicationContext, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            return
        }
        val now = content.getTag(R.id.aura_snack) as? Pill
        val pill = if (now != null && now.parent === content) now.also { it.change(msg, icon) }
        else Pill(a).also {
            it.change(msg, icon, fade = false)
            content.setTag(R.id.aura_snack, it)
            content.addView(it, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = aboveDp.dp
                leftMargin = Space.GUTTER.dp
                rightMargin = Space.GUTTER.dp
            })
            it.enter(dy = 16f, scaleFrom = 0.97f)
        }
        pill.announceForAccessibility(msg)
        pill.removeCallbacks(pill.hide)
        pill.postDelayed(pill.hide, if (long) 4000L else 2500L)
    }

    private fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private class Pill(a: Activity) : LinearLayout(a) {
        private val img = ImageView(a)
        private val text = a.label(Type.BODY, Palette.text, lines = 2)

        val hide = Runnable {
            exit(dy = 10f) {
                (parent as? ViewGroup)?.let { p ->
                    if (p.getTag(R.id.aura_snack) === this) p.setTag(R.id.aura_snack, null)
                    p.removeView(this)
                }
            }
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Touch.MIN.dp
            setPaddingRelative(Space.XL.dp, Space.M.dp, Space.XL.dp, Space.M.dp)
            background = Shapes.overlay()
            lift(Elev.OVERLAY, overMap = true)
            img.visibility = GONE
            addView(img, lp(24.dp, 24.dp).apply { marginEnd = Space.M.dp })
            text.maxWidth = (560 - 2 * Space.XL).dp
            addView(text, lp(WRAP, WRAP))
        }

        fun change(msg: CharSequence, icon: Int, fade: Boolean = true) {
            if (getTag(R.id.aura_exiting) == true) enter() // (it was going: it comes back from where it is)
            if (!fade) { set(msg, icon); return }
            text.fadeTo(0f, Motion.XSHORT, Motion.EXIT) { set(msg, icon); text.fadeTo(1f) }
        }

        private fun set(msg: CharSequence, icon: Int) {
            text.text = msg
            if (icon != 0) { img.icon(icon, Palette.text2); img.visibility = VISIBLE } else img.visibility = GONE
        }
    }
}
