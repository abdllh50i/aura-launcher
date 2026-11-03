package com.abdllh.aura.link

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.nav.Guide
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lift
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp
import java.util.Locale

/**
 * The one card about a place that came from outside ([PlaceInbox]), over any app, at the top like the call and message
 * cards and in their look (2.0 reskins it): who sent it, the place, what is going on, and the answers a driver can hit
 * without looking twice. With a time limit, a line runs out along its bottom edge. [show] false: this unit may not draw
 * over other apps. Main thread only.
 */
internal object TripCard {
    class Action(val label: String, val primary: Boolean, val run: () -> Unit)

    private const val TAG = "AuraTripCard"
    private var view: Card? = null
    private var attached = false

    /** [ms] > 0: goes by itself after that, [onTimeout] then. A card showing is replaced in place. */
    fun show(ctx: Context, caption: String, title: String, sub: String?, actions: List<Action>, ms: Long, onTimeout: (() -> Unit)?): Boolean {
        val c = ctx.applicationContext
        if (!Settings.canDrawOverlays(c)) { Log.w(TAG, "no overlay permission"); return false }
        var v = view
        if (v == null || v.dark != Palette.dark || v.accent != Palette.accent || v.lang != Prefs.lang) {
            remove()
            v = Card(LocaleHelper.wrap(c)).also { view = it }
        }
        v.bind(caption, title, sub, actions, ms, onTimeout)
        if (attached) { v.stay(); return true }
        val lp = WindowManager.LayoutParams(
            WRAP, WRAP, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            this.title = "AuraTrip"
        }
        return try {
            (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
            attached = true
            v.enter()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "show: $t")
            view = null
            false
        }
    }

    fun hide() {
        val v = view ?: return
        if (!attached) return
        v.leave { if (view === v) remove() }
    }

    private fun remove() {
        val v = view ?: return
        v.stop()
        if (attached) {
            try { (v.context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) { }
        }
        attached = false
        view = null
    }

    /** The card, with a little room around it for its shadow and its entrance (the window is no larger than that). */
    @SuppressLint("ViewConstructor")
    private class Card(c: Context) : FrameLayout(c) {
        val dark = Palette.dark
        val accent = Palette.accent
        val lang = Prefs.lang
        private val card = FrameLayout(c)
        private val from: AText = c.label(Type.CAPTION, Palette.text3)
        private val name: AText = c.label(Type.TITLE, Palette.text, lines = 2).apply { ellipsize = TextUtils.TruncateAt.END }
        private val sub: AText = c.label(Type.BODY, Palette.text2).apply { ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_LOCALE }
        private val buttons = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private val bar = View(c).apply { setBackgroundColor(Guide.color()) }
        private val rtl get() = layoutDirection == View.LAYOUT_DIRECTION_RTL
        private var endsAt = 0L
        private var total = 0L
        private var onTimeout: (() -> Unit)? = null
        private var leaving = false

        private val timeout = Runnable {
            val then = onTimeout
            onTimeout = null
            hide()
            try { then?.invoke() } catch (t: Throwable) { Log.w(TAG, "timeout: $t") }
        }

        /** The line moves on a step a second, each step a short glide (no animation for every vsync on top of the map). */
        private val barTick = object : Runnable {
            override fun run() {
                val left = (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L) / total.coerceAtLeast(1L).toFloat()
                bar.anim(Motion.SHORT, LinearInterpolator()).scaleX(left).start()
                if (left > 0f) postDelayed(this, 1000L)
            }
        }

        init {
            layoutDirection = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault())
            clipChildren = false
            card.background = Shapes.rect(Palette.overlay, Radius.XL, Palette.overlayStroke)
            card.roundedClip(Radius.XL)
            card.lift(Elev.OVERLAY, overMap = true)
            card.isClickable = true // (a touch on the card is not a touch on the map under it)

            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(Space.L.dp, Space.M.dp, Space.L.dp, Space.M.dp + 3.dp)
            }
            val icon = FrameLayout(c).apply { background = Shapes.oval(Palette.accentSoft()) }
            icon.addView(c.iconView(R.drawable.ic_pin, 28, Palette.accentFg), flp(28.dp, 28.dp, Gravity.CENTER))
            row.addView(icon, lp(56.dp, 56.dp))
            val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(from, lp(MATCH, WRAP))
            texts.addView(name, lp(MATCH, WRAP))
            texts.addView(sub, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
            row.addView(texts, lp(0, WRAP, 1f).apply { marginStart = Space.L.dp; marginEnd = Space.M.dp })
            row.addView(buttons, lp(WRAP, WRAP))
            card.addView(row, flp(MATCH, WRAP))
            bar.visibility = GONE
            card.addView(bar, flp(MATCH, 3.dp, Gravity.BOTTOM))

            val w = minOf(720.dp, resources.displayMetrics.widthPixels - 2 * SIDE_DP.dp)
            addView(card, flp(w, WRAP).apply { setMargins(SIDE_DP.dp, TOP_DP.dp, SIDE_DP.dp, BOTTOM_DP.dp) })
        }

        fun bind(caption: String, title: String, subText: String?, actions: List<Action>, ms: Long, after: (() -> Unit)?) {
            from.text = caption
            name.text = title
            sub.text = subText.orEmpty()
            sub.visibility = if (subText.isNullOrBlank()) GONE else VISIBLE
            buttons.removeAllViews()
            for ((i, a) in actions.withIndex()) {
                buttons.addView(ABtn(context).apply {
                    kind = if (a.primary) ABtn.Kind.PRIMARY else ABtn.Kind.TONAL
                    text = a.label
                    setOnClickListener {
                        removeCallbacks(timeout)
                        onTimeout = null
                        hide()
                        try { a.run() } catch (t: Throwable) { Log.w(TAG, "action: $t") }
                    }
                }, lp(WRAP, WRAP).apply { if (i > 0) marginStart = Space.S.dp })
            }
            removeCallbacks(timeout)
            removeCallbacks(barTick)
            bar.animate().cancel()
            onTimeout = after
            total = ms
            if (ms > 0L) {
                endsAt = SystemClock.elapsedRealtime() + ms
                bar.visibility = VISIBLE
                bar.scaleX = 1f
                bar.post { bar.pivotX = if (rtl) bar.width.toFloat() else 0f } // (it runs out toward the start)
                postDelayed(barTick, 200L)
                postDelayed(timeout, ms)
            } else {
                bar.visibility = GONE
            }
        }

        fun enter() {
            leaving = false
            card.enter(dy = -16f, scaleFrom = 0.96f)
        }

        fun stay() {
            if (!leaving) return
            leaving = false
            card.enter(dy = -16f, scaleFrom = 0.96f, dur = Motion.SHORT)
        }

        fun leave(done: () -> Unit) {
            removeCallbacks(timeout)
            removeCallbacks(barTick)
            leaving = true
            card.exit(dy = -10f, end = done)
        }

        fun stop() {
            removeCallbacks(timeout)
            removeCallbacks(barTick)
            card.animate().cancel()
            bar.animate().cancel()
        }

        companion object {
            const val SIDE_DP = 12
            const val TOP_DP = 16
            const val BOTTOM_DP = 18
        }
    }
}
