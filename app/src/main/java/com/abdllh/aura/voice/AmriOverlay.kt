package com.abdllh.aura.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.abdllh.aura.R
import com.abdllh.aura.system.Gear
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.OverlayWindow
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.overlayMs
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs

/**
 * The assistant on screen, over any app (SPEC.md sections 5 and 6.5): one card, 628 x 80 at (12, 426), the only thing that may cover pause and next, for the seconds he is talking to it. Its states are
 * one line that changes in place: listening, what it hears, which keeps its start and ends in an ellipsis until he stops, so nothing slides; what it looks for; the answer. A place adds Go, a question
 * yes and no, a fault a chip to Settings ([Action]). A long answer grows the card upward to 132 in place. Cancel at the far end. The card has no second mic: the listening ring is on the row's voice
 * button ([com.abdllh.aura.chrome.VoiceButton]), which hears the level through [level]. Hidden in reverse. The window ends at y 516, the shadow cut there, so the row stays live.
 */
internal object AmriOverlay {
    enum class Mode { LISTEN, DONE, ERROR }

    /** A button on the card. [primary]: Go, or yes; else a quiet one. */
    class Action(val label: String, val icon: Int = 0, val primary: Boolean = true, val run: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private var card: Card? = null
    private val hideLater = Runnable { hide() }
    private var watchingGear = false
    /** The card asks and waits for its answer: [show] with nothing to offer leaves it. */
    private var asking = false
    private var timeout: (() -> Unit)? = null
    private val timedOut = Runnable {
        val then = timeout
        timeout = null
        asking = false
        hide()
        then?.invoke()
    }

    /** Its card is up: a Snack waits ([com.abdllh.aura.ui.Snack.waitFor]), and so does the volume's, which lies in the same place. */
    val showing: Boolean get() = card?.window?.attached == true

    fun show(c: Context, mode: Mode, text: String, hideAfterMs: Int = 0, @Suppress("UNUSED_PARAMETER") sub: String? = null, actions: List<Action> = emptyList()) {
        val app = c.applicationContext
        if (!Settings.canDrawOverlays(app)) return
        if (Amri.reversing) { hide(); return } // nothing over the reversing camera
        if (asking && actions.isEmpty()) return
        if (!watchingGear) {
            watchingGear = true
            Gear.addListener { main.post { if (Amri.reversing) { endAsk(); hide() } } }
        }
        var v = card
        if (v == null || v.lang != Prefs.lang) {
            remove()
            v = Card(LocaleHelper.wrap(app)).also { card = it }
        }
        v.set(mode, text, actions)
        if (v.window.attached) v.stay()
        else if (v.window.add()) v.enter()
        else { card = null; return }
        main.removeCallbacks(hideLater)
        if (hideAfterMs > 0) main.postDelayed(hideLater, hideAfterMs.toLong())
    }

    /**
     * The card with a question, or a place with Go, until it is answered or [ms] are over and [onTimeout] runs. What Amri says meanwhile does not take it off. False: no such card can show here. A tap on
     * an action ends it; Cancel is [Amri.cancel]'s, which tells whoever asked.
     */
    fun ask(c: Context, text: String, actions: List<Action>, ms: Long, onTimeout: (() -> Unit)?): Boolean {
        val app = c.applicationContext
        if (!Settings.canDrawOverlays(app) || Amri.reversing) return false
        endAsk()
        show(c, Mode.DONE, text, actions = actions)
        if (!showing) return false
        asking = true
        timeout = onTimeout
        main.removeCallbacks(hideLater)
        main.postDelayed(timedOut, ms)
        return true
    }

    /** The question has gone: answered somewhere else, or put away. */
    fun endAsk() {
        if (!asking) return
        asking = false
        timeout = null
        main.removeCallbacks(timedOut)
        hide()
    }

    private fun answered(a: Action) {
        asking = false
        timeout = null
        main.removeCallbacks(timedOut)
        hide()
        a.run()
    }

    /** What the recogniser has heard so far. */
    fun text(t: String) { card?.heard(t) }

    /** The voice's level, 0..1, while listening: the ring on the row's button. */
    fun level(l: Float) {
        Amri.onLevel?.invoke(l)
    }

    fun hide() {
        main.removeCallbacks(hideLater)
        val v = card ?: return
        if (!v.window.attached) return
        v.leave { if (card === v) remove() }
    }

    private fun remove() {
        val v = card ?: return
        v.stop()
        v.window.remove()
        card = null
    }

    /** The card in its window: x 0..684, y 330..516, tall enough for the grown card at all times, its touches only on what shows. */
    private class Card(val c: Context) {
        val lang = Prefs.lang
        val window = OverlayWindow(c, "AuraVoice", 0, WINDOW_Y, WINDOW_W, WINDOW_H)
        private val stage = FrameLayout(c)
        private val tallPlate = SurfaceCard(c, Surface.OVERLAY)
        private val plate = SurfaceCard(c, Surface.OVERLAY)
        private val side = if (Fonts.arabicUi) Gravity.RIGHT else Gravity.LEFT
        private val line: AText = c.label(Type.transcript, { Palette.ink }, gravity = side or Gravity.CENTER_VERTICAL)
        private val lines: AText = c.label(Type.transcript, { Palette.ink }, lines = 2, gravity = side or Gravity.TOP)
        private val cancel = IconButton(c, R.drawable.ic_close, 28, 76, 80, token = { Palette.ink2 }).apply {
            contentDescription = c.getString(R.string.btn_cancel)
            setOnClickListener { Amri.cancel(c, byOwner = true) }
        }
        private val buttons = ArrayList<View>()
        private var shownActions: List<Action> = emptyList()
        private var tall = false
        private var leaving = false

        init {
            tallPlate.visibility = View.INVISIBLE
            lines.visibility = View.INVISIBLE
            stage.addView(plate, Surface.OVERLAY.at(CARD_X, CARD_Y, CARD_W, CARD_H))
            stage.addView(tallPlate, Surface.OVERLAY.at(CARD_X, TALL_Y, CARD_W, TALL_H))
            stage.addView(line, Type.transcript.box(TEXT_X, CARD_Y + 20, TEXT_W))
            stage.addView(lines, at(TEXT_X, TALL_Y + 26 + Type.transcript.viewTop, TEXT_W, Type.transcript.viewH + Type.transcript.line))
            stage.addView(cancel, at(CARD_X, CARD_Y, 76, 80))
            window.root.addView(stage, FrameLayout.LayoutParams(WINDOW_W, WINDOW_H))
            window.visible(CARD_X, CARD_Y, CARD_X + CARD_W, CARD_Y + CARD_H)
        }

        fun set(mode: Mode, text: String, actions: List<Action>) {
            window.syncLook()
            setActions(actions)
            val room = TEXT_W - line.paddingLeft
            val grow = mode != Mode.LISTEN && line.paint.measureText(text) > room
            setTall(grow, window.attached)
            line.text = text
            lines.text = text
        }

        /** Words as they come, on the one line. */
        fun heard(t: String) {
            setTall(false, true)
            line.text = t
        }

        private fun setActions(actions: List<Action>) {
            if (actions === shownActions || (actions.isEmpty() && shownActions.isEmpty())) return
            shownActions = actions
            for (b in buttons) stage.removeView(b)
            buttons.clear()
            var x = CARD_X + 84
            val y = CARD_Y + 8
            for ((i, a) in actions.withIndex()) {
                val w = if (actions.size == 1) 128 else 138
                val btn = ABtn(c).apply {
                    layoutDirection = if (Fonts.arabicUi) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    sidePad = 6
                    kind = if (a.primary && i == 0) ABtn.Kind.PRIMARY else ABtn.Kind.TONAL
                    size = ABtn.Size.M
                    setHeightOverride(64)
                    text = a.label
                    if (a.icon != 0) icon(a.icon, 26)
                    setOnClickListener { AmriOverlay.answered(a) }
                }
                stage.addView(btn, at(x, y, w, 64))
                buttons.add(btn)
                x += w + 8
            }
            // (the words keep the room that is left, at their reading edge in both languages)
            val pad = if (actions.isEmpty()) 0 else x - TEXT_X
            line.setPadding(pad, 0, 0, 0)
            lines.setPadding(pad, 0, 0, 0)
        }

        /** The grown plate fades in over the small one, which stays under it until then, so the part they share never lets the map through: no layout, nothing slides. */
        private fun setTall(on: Boolean, animate: Boolean) {
            if (on == tall) return
            tall = on
            line.visibility = if (on) View.INVISIBLE else View.VISIBLE
            lines.visibility = if (on) View.VISIBLE else View.INVISIBLE
            tallPlate.animate().cancel()
            if (on) {
                tallPlate.visibility = View.VISIBLE
                if (animate) {
                    tallPlate.alpha = 0f
                    tallPlate.fadeTo(1f, overlayMs(Tokens2.MS_cardIn), Motion.MOVE) { plate.visibility = View.INVISIBLE }
                } else {
                    tallPlate.alpha = 1f
                    plate.visibility = View.INVISIBLE
                }
            } else {
                plate.visibility = View.VISIBLE
                tallPlate.visibility = View.INVISIBLE
            }
            window.visible(CARD_X, if (on) TALL_Y else CARD_Y, CARD_X + CARD_W, CARD_Y + CARD_H)
        }

        fun enter() {
            leaving = false
            stage.enter(dy = ENTER_DY, dur = overlayMs(Tokens2.MS_cardIn))
        }

        /** Shown again while it is up (it heard more, it answers): the entrance plays on. Shown again while it was fading out, it turns round from where it is. */
        fun stay() {
            if (!leaving) return
            leaving = false
            stage.enter(dy = ENTER_DY, dur = overlayMs(Tokens2.MS_cardIn))
        }

        fun leave(done: () -> Unit) {
            if (leaving) return
            leaving = true
            stage.exit(dur = overlayMs(Tokens2.MS_bannerOut), end = done)
        }

        fun stop() {
            stage.animate().cancel()
            tallPlate.animate().cancel()
        }
    }

    /** The window: y 330..516 on the screen, x 0..684. */
    private const val WINDOW_Y = 330
    private const val WINDOW_W = 684
    private const val WINDOW_H = 186
    /** In the window: the card is x 12..640 and y 426..506 on the screen, and y 374..506 grown. */
    private const val CARD_X = 12
    private const val CARD_Y = 96
    private const val CARD_W = 628
    private const val CARD_H = 80
    private const val TALL_Y = 44
    private const val TALL_H = 132
    /** The line's box, ending 20 from the card's right edge. */
    private const val TEXT_X = 80
    private const val TEXT_W = 540
    private const val ENTER_DY = 12f
}
