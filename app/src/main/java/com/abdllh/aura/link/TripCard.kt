package com.abdllh.aura.link

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.OverlayWindow
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.TallHit
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.glyph
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.overlayMs
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs

/**
 * The one card about a place that came from outside ([PlaceInbox]), over any app: the banner of SPEC.md's `from-phone-on-trip`, in the call's and the message's place. Who sent it, the place, and the answers
 * a driver can hit without looking twice: the main one at x 405, and "no" as the close at x 551; alone and quiet, a cancel is a plain button at x 405. No countdown: it waits for one touch. With nothing to
 * press, what happened gets the card's whole width. [show] false: this unit may not draw over other apps. Main thread only.
 */
internal object TripCard {
    class Action(val label: String, val primary: Boolean, val run: () -> Unit)

    private const val TAG = "AuraTripCard"
    private var view: Card? = null

    fun show(ctx: Context, caption: String, title: String, sub: String?, actions: List<Action>, ms: Long, onTimeout: (() -> Unit)?): Boolean =
        show(ctx, caption, title, sub, actions, ms, onTimeout, R.drawable.ic_pin)

    /** [ms] > 0: goes by itself after that, [onTimeout] then. A card showing is replaced in place. [sub], when there is one, follows the caption on its line. */
    fun show(ctx: Context, caption: String, title: String, sub: String?, actions: List<Action>, ms: Long, onTimeout: (() -> Unit)?, icon: Int): Boolean {
        val c = ctx.applicationContext
        if (!Settings.canDrawOverlays(c)) { Log.w(TAG, "no overlay permission"); return false }
        var v = view
        if (v == null || v.lang != Prefs.lang) {
            remove()
            v = Card(LocaleHelper.wrap(c)).also { view = it }
        }
        v.bind(caption, title, sub, actions, ms, onTimeout, icon)
        if (v.window.attached) { v.stay(); return true }
        if (!v.window.add()) { view = null; return false }
        v.enter()
        return true
    }

    fun hide() {
        val v = view ?: return
        if (!v.window.attached) return
        v.leave { if (view === v) remove() }
    }

    private fun remove() {
        val v = view ?: return
        v.stop()
        v.window.remove()
        view = null
    }

    @SuppressLint("ViewConstructor")
    private class Card(c: Context) {
        val lang = Prefs.lang
        val window = OverlayWindow(c, "AuraTrip", WINDOW_X, WINDOW_Y, 1024 - WINDOW_X, WINDOW_H)
        private val stage = FrameLayout(c)
        private val plate = ImageView(c)
        private val from: AText = c.label(Type.msgFrom, { Palette.ink2Move }, gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        private val name: AText = c.label(Type.msgTitle, { Palette.ink }, gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        private val whole: AText = c.label(Type.msgTitle, { Palette.ink }, lines = 2, gravity = Gravity.RIGHT or Gravity.TOP)
        private val left = TallHit(c, BUTTON_Y, BUTTON_H)
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
            stage.addView(SurfaceCard(c, Surface.OVERLAY).apply { isClickable = true }, Surface.OVERLAY.at(ROOM, ROOM, CARD_W, CARD_H))
            val t = Tint(c, R.drawable.plate_18) { Palette.fill2 }
            plate.background = t.drawable
            plate.setPadding(13, 13, 13, 13)
            plate.look { t.recolour() }
            plate.glyph { Palette.ink }
            stage.addView(plate, at(ROOM + 546, ROOM + 21, 56, 56))
            stage.addView(from, Type.msgFrom.box(ROOM + TEXT_END - TEXT_W, ROOM + 14, TEXT_W))
            stage.addView(name, Type.msgTitle.box(ROOM + TEXT_END - TEXT_W, ROOM + 50, TEXT_W))
            stage.addView(whole, at(ROOM + TEXT_END - WHOLE_W, ROOM + 15 + Type.msgTitle.viewTop, WHOLE_W, Type.msgTitle.viewH + Type.msgTitle.line))
            stage.addView(left, at(ROOM + 13, ROOM, BUTTON_W, CARD_H))
            stage.addView(close, at(ROOM + 159, ROOM + 7, 76, 84))
            window.root.addView(stage, FrameLayout.LayoutParams(CARD_W + 2 * ROOM, WINDOW_H))
            window.visible(ROOM, ROOM, ROOM + CARD_W, ROOM + CARD_H)
        }

        fun bind(caption: String, title: String, sub: String?, actions: List<Action>, ms: Long, after: (() -> Unit)?, icon: Int) {
            window.syncLook()
            plate.setImageResource(icon)
            val words = actions.isNotEmpty()
            from.text = if (sub.isNullOrBlank()) caption else "$caption · $sub"
            name.text = title
            whole.text = title
            from.visibility = if (words) View.VISIBLE else View.INVISIBLE
            name.visibility = if (words) View.VISIBLE else View.INVISIBLE
            whole.visibility = if (words) View.INVISIBLE else View.VISIBLE
            left.removeAllViews()
            val main = actions.firstOrNull { it.primary } ?: actions.firstOrNull()
            if (main != null) left.addView(button(main), at(0, BUTTON_Y, BUTTON_W, BUTTON_H))
            val other = actions.firstOrNull { it !== main }
            close.visibility = if (other != null) View.VISIBLE else View.INVISIBLE
            close.contentDescription = other?.label
            close.setOnClickListener { if (other != null) tapped(other) }
            stage.removeCallbacks(timeout)
            onTimeout = after
            if (ms > 0L) stage.postDelayed(timeout, ms)
        }

        private fun button(a: Action) = ABtn(left.context).apply {
            layoutDirection = if (Fonts.arabicUi) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            sidePad = BUTTON_SIDE
            if (a.primary) {
                kind = ABtn.Kind.PRIMARY
                size = ABtn.Size.ARRIVAL // (the nearest bitmap: shrunk by its middle, which a gradient hides better than stretching)
                setHeightOverride(BUTTON_H)
            } else {
                kind = ABtn.Kind.TONAL
                size = ABtn.Size.CALL
            }
            text = a.label
            setOnClickListener { tapped(a) }
        }

        private fun tapped(a: Action) {
            stage.removeCallbacks(timeout)
            onTimeout = null
            hide()
            try { a.run() } catch (t: Throwable) { Log.w(TAG, "action: $t") }
        }

        fun enter() {
            leaving = false
            stage.enter(dy = -ENTER_DY, dur = overlayMs(Tokens2.MS_banner))
        }

        fun stay() {
            if (!leaving) return
            leaving = false
            stage.enter(dy = -ENTER_DY, dur = overlayMs(Tokens2.MS_banner))
        }

        fun leave(done: () -> Unit) {
            if (leaving) return
            leaving = true
            stage.removeCallbacks(timeout)
            stage.exit(dur = overlayMs(Tokens2.MS_bannerOut), end = done)
        }

        fun stop() {
            stage.removeCallbacks(timeout)
            stage.animate().cancel()
        }
    }

    /** The visible rect from the window's left and top: the overlay's outset. */
    private const val ROOM = 44
    private const val WINDOW_X = 348
    private const val WINDOW_Y = 4
    private const val WINDOW_H = 186
    private const val CARD_W = 616
    private const val CARD_H = 98
    private const val BUTTON_W = 138
    private const val BUTTON_H = 72
    private const val BUTTON_Y = 13
    private const val BUTTON_SIDE = 6
    private const val TEXT_END = 532
    private const val TEXT_W = 288
    private const val WHOLE_W = 508
    private const val ENTER_DY = 24f
}
