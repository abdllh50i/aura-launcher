package com.abdllh.aura.link

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.Look
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.ink
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.plateView
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import java.util.Locale

/**
 * The one banner about a place that came from outside ([PlaceInbox]), over any app (SPEC.md section 6.3, `from-phone-on-trip`): the same place and the same
 * bones as a call's, 628 x 98 at (384, 44), beside the column, so the turn card is never covered. Who sent it, the place, the answer at x 397 and a close at
 * x 543 in both languages. With a time limit it just waits that long: no line, no count, no camera change. [show] false: this unit may not draw over other apps.
 * The window is the banner with its baked shadow, cut at the column's edge, so that a touch on the column is the column's. Main thread only.
 */
internal object TripCard {
    class Action(val label: String, val primary: Boolean, val run: () -> Unit)

    private const val TAG = "AuraTripCard"
    private var view: Card? = null
    private var attached = false

    fun show(ctx: Context, caption: String, title: String, sub: String?, actions: List<Action>, ms: Long, onTimeout: (() -> Unit)?): Boolean =
        show(ctx, caption, title, sub, actions, ms, onTimeout, R.drawable.ic_pin)

    /** [ms] > 0: goes by itself after that, [onTimeout] then. A banner showing is changed in place. [sub], when there is one, follows the caption on its line. */
    fun show(ctx: Context, caption: String, title: String, sub: String?, actions: List<Action>, ms: Long, onTimeout: (() -> Unit)?, icon: Int): Boolean {
        val c = ctx.applicationContext
        if (!Settings.canDrawOverlays(c)) { Log.w(TAG, "no overlay permission"); return false }
        var v = view
        if (v == null || v.lang != Prefs.lang) {
            remove()
            v = Card(LocaleHelper.wrap(c)).also { view = it }
        }
        v.bind(caption, title, sub, actions, ms, onTimeout, icon)
        if (attached) { v.stay(); return true }
        val lp = WindowManager.LayoutParams(
            Card.WINDOW_W, Card.WINDOW_H, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = Card.WINDOW_X
            y = 0
            this.title = "AuraTrip"
        }
        return try {
            (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
            attached = true
            Look.opened(v)
            v.onLookNow()
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
        Look.closed(v)
        if (attached) {
            try { (v.context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) { }
        }
        attached = false
        view = null
    }

    @SuppressLint("ViewConstructor")
    private class Card(c: Context) : FrameLayout(c) {
        val lang = Prefs.lang
        private val card = SurfaceCard(c, Surface.OVERLAY)
        private val plate = c.plateView(18) { Palette.fill2 }
        private val glyph = c.iconView(R.drawable.ic_pin, 30) { Palette.ink }
        private val from = c.label(Type.msgFrom, { Palette.ink2Move })
        private val name = c.label(Type.msgTitle, { Palette.ink })
        private val answer = ABtn(c).apply { size = ABtn.Size.ARRIVAL; setHeightOverride(ANSWER_H) }
        private val close = IconButton(c, R.drawable.ic_close, 28, 76, 84, token = { Palette.ink2 })
        private var onTimeout: (() -> Unit)? = null
        private var leaving = false

        private val timeout = Runnable {
            val then = onTimeout
            onTimeout = null
            hide()
            try { then?.invoke() } catch (t: Throwable) { Log.w(TAG, "timeout: $t") }
        }

        init {
            layoutDirection = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault())
            // (the banner's visible rect at (384, 44), so the window's own left edge cuts the shadow at x 376)
            addView(card, Surface.OVERLAY.at(BANNER_X - WINDOW_X, BANNER_Y, BANNER_W, BANNER_H))
            card.addView(plate, at(558, 21, 56, 56))
            card.addView(glyph, at(571, 34, 30, 30))
            card.addView(answer, at(13, 13, 138, ANSWER_H))
            card.addView(close, at(159, 7, 76, 84))
        }

        /** The text's room: from what the answers leave to the plate. */
        private fun place(left: Int) {
            card.removeView(from)
            card.removeView(name)
            val w = 544 - left
            card.addView(from, Type.msgFrom.box(left, 14, w))
            card.addView(name, Type.msgTitle.box(left, 50, w))
        }

        fun bind(caption: String, title: String, sub: String?, actions: List<Action>, ms: Long, after: (() -> Unit)?, icon: Int) {
            glyph.setImageResource(icon)
            from.text = if (sub.isNullOrBlank()) caption else "$caption · $sub"
            name.text = title
            val first = actions.firstOrNull { it.primary } ?: actions.firstOrNull()
            val second = actions.firstOrNull { it !== first }
            place(if (first == null) 13 else if (second != null || first.primary) 243 else 159)
            answer.visibility = if (first == null) View.GONE else View.VISIBLE
            if (first != null) {
                answer.kind = if (first.primary) ABtn.Kind.PRIMARY else ABtn.Kind.TONAL
                answer.setPaddingRelative(4, 0, 4, 0) // (138 wide: "بدّل الطريق" has no room for a button's usual sides)
                answer.text = first.label
                answer.setOnClickListener { act(first) }
            }
            close.visibility = if (second == null && first?.primary != true) View.GONE else View.VISIBLE
            close.setOnClickListener { if (second != null) act(second) else hide() }
            removeCallbacks(timeout)
            onTimeout = after
            if (ms > 0L) postDelayed(timeout, ms)
        }

        private fun act(a: Action) {
            removeCallbacks(timeout)
            onTimeout = null
            hide()
            try { a.run() } catch (t: Throwable) { Log.w(TAG, "action: $t") }
        }

        fun onLookNow() = Look.apply(this)

        fun enter() {
            leaving = false
            card.enter(dy = -24f, dur = BANNER_IN_MS)
        }

        fun stay() {
            if (!leaving) return
            leaving = false
            card.enter(dy = -24f, dur = BANNER_IN_MS)
        }

        fun leave(done: () -> Unit) {
            removeCallbacks(timeout)
            leaving = true
            card.exit(dur = BANNER_OUT_MS, end = done)
        }

        fun stop() {
            removeCallbacks(timeout)
            card.animate().cancel()
        }

        companion object {
            const val BANNER_X = 384
            const val BANNER_Y = 44
            const val BANNER_W = 628
            const val BANNER_H = 98
            const val WINDOW_X = 376
            const val WINDOW_W = 1024 - WINDOW_X
            const val WINDOW_H = BANNER_Y + BANNER_H + 44
            const val ANSWER_H = 72
            const val BANNER_IN_MS = 200L
            const val BANNER_OUT_MS = 140L
        }
    }
}
