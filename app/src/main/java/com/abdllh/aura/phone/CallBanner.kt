package com.abdllh.aura.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.OverlayWindow
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.TallHit
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
 * A phone call on screen, over everything (SPEC.md sections 5 and 6.5): a banner at x 384..1012, y 44, beside the driver's column. While it rings, Answer at x 397 and Decline at x 543; in a call
 * Mute at x 397 and `قفل` at x 543, in the same places, so the red stays where it was and a double press on Answer can only reach Mute, which is not taken for 600 ms after the answer. 3 s after
 * answering the name goes and the banner shrinks to x 384..694, short of the car's line. No timer: a number that ticks every second is moving text. It shows what [Calls] knows and tells it what was
 * pressed; nothing else.
 */
internal object CallBanner {
    private val main = Handler(Looper.getMainLooper())
    private var view: Card? = null

    fun init() {
        Calls.addListener { main.post { sync() } }
    }

    private fun sync() {
        val c = Calls.context ?: return
        val state = Calls.state
        if (state == Calls.State.IDLE) { hide(); return }
        if (!Settings.canDrawOverlays(c)) return
        var v = view
        // (a new call while the small one was going: it comes in whole)
        if (v == null || v.lang != Prefs.lang || (v.small && state != Calls.State.TALKING && state != Calls.State.ENDED)) {
            remove()
            v = Card(LocaleHelper.wrap(c)).also { view = it }
        }
        // (over, in the small form: nothing to read and nothing left to press)
        if (state == Calls.State.ENDED && v.small) { hide(); return }
        v.bind()
        // (a report while it comes in, the number and then the name, lets the entrance play on; a call back while it was leaving turns it round)
        if (v.window.attached) { v.stay(); return }
        if (v.window.add()) v.enter() else view = null
    }

    private fun hide() {
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

    /** The banner, wide over small: the small one is covered until the wide one fades off it, and no pixel of the part they share ever lets the map through. */
    private class Card(c: Context) {
        val lang = Prefs.lang
        val window = OverlayWindow(c, "AuraCall", WINDOW_X, 0, WIDE_W + 2 * ROOM, WINDOW_H, keepScreenOn = true)
        private val stage = FrameLayout(c)
        private val narrow = SurfaceCard(c, Surface.OVERLAY)
        private val wide = SurfaceCard(c, Surface.OVERLAY)
        private val avatar = Avatar(c)
        private val name: AText = c.label(Type.callName, { Palette.ink }, gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        private val sub: AText = c.label(Type.callSub, { Palette.ink2 }, gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        private val leftSlot = TallHit(c, BUTTON_Y, BUTTON_H)
        private val rightSlot = TallHit(c, BUTTON_Y, BUTTON_H)
        private val answer = button(c, ABtn.Kind.ANSWER, R.drawable.ic_phone, 28, R.string.call_answer) { answerTapped() }
        private val mute = button(c, ABtn.Kind.TONAL, R.drawable.ic_mic_off, 26, R.string.call_mute) { muteTapped() }
        private val hang = button(c, ABtn.Kind.DANGER_FILLED, R.drawable.ic_hang, 30, R.string.call_decline) { hangUpTapped() }
        private val shrink = Runnable { shrink() }
        private var shrinkAsked = false
        private var ringingShown: Boolean? = null
        /** When Answer was tapped, or Answer and Decline became Mute and `قفل`: a tap on those just after it is not meant for them. */
        private var changedAt = -Tokens2.MS_endGuard
        private var leaving = false

        /** It has shrunk, or is on its way there. */
        var small = false
            private set

        init {
            stage.addView(narrow, Surface.OVERLAY.at(ROOM, ROOM, NARROW_W, CARD_H))
            stage.addView(wide, Surface.OVERLAY.at(ROOM, ROOM, WIDE_W, CARD_H))
            stage.addView(avatar, at(ROOM + 546, ROOM + 15, Avatar.SIZE, Avatar.SIZE))
            stage.addView(name, Type.callName.box(ROOM + TEXT_END - TEXT_W, ROOM + 10, TEXT_W))
            stage.addView(sub, Type.callSub.box(ROOM + TEXT_END - TEXT_W, ROOM + 52, TEXT_W))
            leftSlot.addView(answer, at(0, BUTTON_Y, BUTTON_W, BUTTON_H))
            leftSlot.addView(mute, at(0, BUTTON_Y, BUTTON_W, BUTTON_H))
            rightSlot.addView(hang, at(0, BUTTON_Y, BUTTON_W, BUTTON_H))
            stage.addView(leftSlot, at(ROOM + LEFT_X, ROOM, BUTTON_W, CARD_H))
            stage.addView(rightSlot, at(ROOM + RIGHT_X, ROOM, BUTTON_W, CARD_H))
            mute.visibility = View.INVISIBLE
            window.root.addView(stage, FrameLayout.LayoutParams(WIDE_W + 2 * ROOM, WINDOW_H))
            window.visible(ROOM, ROOM, ROOM + WIDE_W, ROOM + CARD_H)
        }

        private fun button(c: Context, kind: ABtn.Kind, icon: Int, px: Int, word: Int, tap: () -> Unit) = ABtn(c).apply {
            // (the word and its icon in reading order, the button itself in its place)
            layoutDirection = if (Fonts.arabicUi) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            sidePad = BUTTON_SIDE
            this.kind = kind
            size = ABtn.Size.CALL
            setText(word)
            icon(icon, px)
            setOnClickListener { tap() }
        }

        private fun answerTapped() {
            changedAt = SystemClock.uptimeMillis()
            Calls.answer()
        }

        private fun guarded() = SystemClock.uptimeMillis() - changedAt < Tokens2.MS_endGuard

        private fun hangUpTapped() {
            if (!guarded()) Calls.hangUp()
        }

        private fun muteTapped() {
            if (!guarded()) Calls.setMicMuted(!Calls.micMuted)
        }

        fun bind() {
            window.syncLook()
            val state = Calls.state
            val who = Calls.name.trim()
            val number = Calls.number.trim()
            // (a number is read left to right, in Arabic too)
            name.text = when {
                who.isNotEmpty() -> who
                number.isNotEmpty() -> "⁦$number⁩"
                else -> name.context.getString(R.string.call_unknown)
            }
            sub.setText(when (state) {
                Calls.State.RINGING -> if (Calls.answering) R.string.call_answering else R.string.call_sub_incoming
                Calls.State.DIALING -> R.string.call_dialing
                Calls.State.TALKING -> R.string.call_sub_talking
                else -> R.string.call_ended
            })
            avatar.ring = state == Calls.State.RINGING || state == Calls.State.TALKING
            val ringing = state == Calls.State.RINGING && !Calls.answering
            if (ringing != ringingShown) {
                val first = ringingShown == null
                ringingShown = ringing
                if (!first) changedAt = SystemClock.uptimeMillis()
                slot(if (ringing) answer else mute, if (ringing) mute else answer, !first)
                hang.setText(if (ringing) R.string.call_decline else R.string.call_hang)
            }
            val wantKind = if (Calls.micMuted) ABtn.Kind.SELECTED else ABtn.Kind.TONAL
            if (mute.kind != wantKind) mute.kind = wantKind
            // (over: nothing left to press, its place kept so nothing jumps as the card leaves)
            if (state == Calls.State.ENDED) {
                leftSlot.visibility = View.INVISIBLE
                rightSlot.visibility = View.INVISIBLE
            } else {
                leftSlot.visibility = View.VISIBLE
                rightSlot.visibility = View.VISIBLE
            }
            if (state == Calls.State.TALKING && !shrinkAsked && !small) {
                // (once: the reports that follow, the name, do not put it off; a card that comes up for a call under way goes small at once)
                shrinkAsked = true
                val left = Tokens2.MS_bannerShrink - (SystemClock.elapsedRealtime() - Calls.talkingSince)
                stage.postDelayed(shrink, left.coerceAtLeast(0L))
            } else if (state != Calls.State.TALKING && shrinkAsked) {
                // (the phone took it back)
                shrinkAsked = false
                stage.removeCallbacks(shrink)
            }
        }

        /** Answer and Mute have one place, and one fades into the other. */
        private fun slot(on: View, off: View, animate: Boolean) {
            off.animate().cancel()
            on.animate().cancel()
            if (!animate) {
                off.visibility = View.INVISIBLE
                on.visibility = View.VISIBLE
                on.alpha = 1f
                return
            }
            on.visibility = View.VISIBLE
            on.bringToFront()
            on.fadeTo(1f, Tokens2.MS_swap, Motion.MOVE)
            off.fadeTo(0f, Tokens2.MS_swap, Motion.MOVE) { off.visibility = View.INVISIBLE; off.alpha = 1f }
        }

        /** One crossfade: the name goes, the wide plate fades off the small one under it, the two buttons do not move, and the window's width is set once at the end. */
        private fun shrink() {
            if (Calls.state != Calls.State.TALKING || small) return
            small = true
            window.visible(ROOM, ROOM, ROOM + NARROW_W, ROOM + CARD_H)
            val out = overlayMs(Tokens2.MS_cardOut)
            for (v in arrayOf(name, sub, avatar)) v.fadeTo(0f, out, Motion.EXIT) { v.visibility = View.INVISIBLE }
            wide.fadeTo(0f, overlayMs(Tokens2.MS_cardIn), Motion.MOVE) {
                wide.visibility = View.INVISIBLE
                window.narrow(NARROW_W + 2 * ROOM)
            }
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
            stage.removeCallbacks(shrink)
            stage.exit(dur = overlayMs(Tokens2.MS_bannerOut), end = done)
        }

        fun stop() {
            stage.removeCallbacks(shrink)
            stage.animate().cancel()
        }
    }

    /** The disc with the person, drawn once; the 2 px ring outside it while it rings and in a call's first seconds. No pulse on a moving car. */
    private class Avatar(c: Context) : View(c), Recolours {
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        private val glyph = c.getDrawable(R.drawable.ic_person)!!.mutate().apply { setBounds(16, 16, 52, 52) }

        var ring = false
            set(v) {
                if (v == field) return
                field = v
                invalidate()
            }

        init { onLook() }

        override fun onLook() {
            disc.color = Palette.fill2
            line.color = Palette.green
            glyph.setTint(Palette.ink)
            invalidate()
        }

        override fun hasOverlappingRendering() = false

        override fun onDraw(c: Canvas) {
            val m = SIZE / 2f
            c.drawCircle(m, m, 32f, disc)
            if (ring) c.drawCircle(m, m, 33f, line)
            glyph.draw(c)
        }

        companion object {
            /** The disc 64 and the ring outside it. */
            const val SIZE = 68
        }
    }

    /** The visible rect from the window's left and top: the overlay's outset. */
    private const val ROOM = 44
    /** The window: x 340..1024 ringing, x 340..738 small; y 0..186, the shadow's room. */
    private const val WINDOW_X = 340
    private const val WINDOW_H = 186
    private const val WIDE_W = 628
    private const val NARROW_W = 310
    private const val CARD_H = 98
    private const val BUTTON_W = 138
    private const val BUTTON_H = 72
    private const val BUTTON_Y = 13
    private const val BUTTON_SIDE = 6
    /** In the visible rect: x 397 and x 543 on the screen. */
    private const val LEFT_X = 13
    private const val RIGHT_X = 159
    /** The name's right edge beside the avatar, in both languages, and its width: longer goes to an ellipsis. */
    private const val TEXT_END = 534
    private const val TEXT_W = 220
    private const val ENTER_DY = 24f
}
