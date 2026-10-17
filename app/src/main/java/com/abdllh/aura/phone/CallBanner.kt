package com.abdllh.aura.phone

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.system.VolumeHud
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.LayoutMove
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lift
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp
import java.util.Locale

/**
 * A phone call on screen, over any app: a card at the top with who it is, and the two buttons a driver can hit without
 * looking twice — decline and answer while it rings, hang up once it is answered (with the time it has lasted). It
 * shows what [Calls] knows and tells it what was pressed; nothing else.
 */
internal object CallBanner {
    private const val TAG = "AuraCallBanner"
    /** The window's distance from the top; the card sits [Card.TOP_DP] below that. */
    private const val WINDOW_Y = 0
    private val main = Handler(Looper.getMainLooper())
    private var view: Card? = null
    private var attached = false

    /** The card is on screen (the volume display then shows under it, not over the caller's name). */
    val shown: Boolean get() = attached

    /** Where the card ends, from the top of the screen in px (0 when there is none). */
    fun bottom(): Int {
        val v = view ?: return 0
        if (!attached) return 0
        // (not laid out yet, in the frame it was added: the height of a card with one line of each text)
        val h = v.cardBottom().takeIf { it > 0 } ?: (Card.TOP_DP + 100).dp
        return WINDOW_Y.dp + h
    }

    fun init() {
        Calls.addListener { main.post { sync() } }
    }

    private fun sync() {
        val c = Calls.context ?: return
        val state = Calls.state
        if (state == Calls.State.IDLE) { hide(); return }
        if (!Settings.canDrawOverlays(c)) return
        var v = view
        if (v == null || v.dark != Palette.dark || v.accent != Palette.accent || v.lang != Prefs.lang) {
            remove()
            v = Card(LocaleHelper.wrap(c)).also { view = it }
        }
        v.bind()
        // (a report while it comes in, the number and then the name, lets the entrance play on; a call back while it
        // was leaving turns it round)
        if (attached) { v.stay(); return }
        val lp = WindowManager.LayoutParams(
            WRAP, WRAP, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = WINDOW_Y.dp
            title = "AuraCall"
        }
        try {
            (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
            attached = true
            v.enter()
            VolumeHud.reposition()
        } catch (t: Throwable) {
            Log.w(TAG, "show: $t")
        }
    }

    private fun hide() {
        val v = view ?: return
        if (!attached) return
        v.leave { if (view === v) remove() }
    }

    private fun remove() {
        val v = view ?: return
        v.stop()
        val was = attached
        if (attached) {
            try { (v.context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) { }
        }
        attached = false
        view = null
        if (was) VolumeHud.reposition()
    }

    /**
     * The card, with room around it for its shadow and for its entrance. The room is kept small: the window is the
     * card's size plus that room, and a touch on the window's transparent edge reaches neither the card nor the app
     * under it (1.11's wide margins left a dead band under the card on the map for the whole call).
     */
    private class Card(c: Context) : FrameLayout(c) {
        val dark = Palette.dark
        val accent = Palette.accent
        val lang = Prefs.lang
        private val card = LinearLayout(c)
        private val avatar = FrameLayout(c)
        private val initial: AText = c.label(Type.DISPLAY_S, Palette.accentFg, gravity = Gravity.CENTER).apply { weight = Fonts.MEDIUM }
        private val person: ImageView = c.iconView(R.drawable.ic_user, 34, Palette.accentFg)
        private val name: AText = c.label(Type.HEADLINE, Palette.text)
        private val sub: AText = c.label(Type.BODY, Palette.text2)
        private val decline = round(R.drawable.ic_call_end, Palette.danger, R.string.call_decline) { hangUpTapped() }
        private val answer = round(R.drawable.ic_call, Palette.success, R.string.call_answer) { answerTapped() }
        // (a plain oval faded on every frame of the ring: no offscreen pass for a view that cannot overlap itself)
        private val pulse = object : View(c) { override fun hasOverlappingRendering() = false }
        private val answerBox = FrameLayout(c)
        private var pulsing: ValueAnimator? = null
        private var shown: Calls.State? = null
        private var first = true
        private var leaving = false
        /** When Answer was tapped, and when Hang up last moved (uptime ms): a tap on Hang up just after either is not meant for it. */
        private var answeredAt = -GUARD_MS
        private var movedAt = -GUARD_MS
        private val collapse = Runnable { collapse() }
        /** Answer's empty place is due to close ([collapse] is posted). */
        private var closing = false

        init {
            layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(Locale.getDefault())
            clipChildren = false
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            card.clipChildren = false
            card.clipToPadding = false // (the ring round the answer button grows past the card's inner edge)
            card.setPaddingRelative(Space.L.dp, Space.L.dp - 2.dp, Space.L.dp, Space.L.dp - 2.dp)
            // the overlays' own surface (the volume display and the assistant's pill have it too); round ends that run
            // round the avatar and the buttons
            card.background = Shapes.overlay()
            card.lift(Elev.OVERLAY, overMap = true)
            card.isClickable = true // (a touch on the card is not a touch on the map under it)

            avatar.background = Shapes.oval(Palette.accentSoft())
            avatar.addView(initial, flp(MATCH, MATCH))
            avatar.addView(person, flp(34.dp, 34.dp, Gravity.CENTER))
            card.addView(avatar, lp(72.dp, 72.dp))

            val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(name, lp(MATCH, WRAP))
            texts.addView(sub, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
            card.addView(texts, lp(0, WRAP, 1f).apply { marginStart = Space.L.dp; marginEnd = Space.L.dp })

            card.addView(decline, lp(BUTTON.dp, BUTTON.dp))
            pulse.background = Shapes.oval(Palette.success)
            pulse.alpha = 0f
            answerBox.clipChildren = false
            answerBox.addView(pulse, flp(BUTTON.dp, BUTTON.dp))
            answerBox.addView(answer, flp(BUTTON.dp, BUTTON.dp))
            card.addView(answerBox, lp(BUTTON.dp, BUTTON.dp).apply { marginStart = Space.L.dp })

            val w = minOf(640.dp, resources.displayMetrics.widthPixels - 2 * SIDE_DP.dp)
            // (the sides: the shadow; the top: the entrance, which comes down from it; the bottom: the shadow, which falls
            // downwards. The top band is the screen's edge, where there is nothing to tap.)
            addView(card, flp(w, WRAP).apply { setMargins(SIDE_DP.dp, TOP_DP.dp, SIDE_DP.dp, BOTTOM_DP.dp) })
        }

        /** The card's lower edge in this window (0 before it is laid out). */
        fun cardBottom(): Int = if (card.height == 0) 0 else card.bottom

        private fun round(icon: Int, color: Int, what: Int, tap: () -> Unit) = FrameLayout(context).apply {
            background = Shapes.pressable(Shapes.oval(color), Shapes.oval(Palette.mix(color, 0xFF000000.toInt(), 0.18f)))
            isClickable = true
            contentDescription = context.getString(what)
            pressScale(Motion.PRESS_ICON)
            addView(context.iconView(icon, 30, Palette.onColor(color)).apply { layoutParams = flp(30.dp, 30.dp, Gravity.CENTER) })
            setOnClickListener { tap() }
        }

        private fun answerTapped() {
            answeredAt = SystemClock.uptimeMillis()
            Calls.answer()
        }

        /**
         * A second tap on Answer (the phone was slow to react, or a bump) must not end the call it has just answered:
         * Hang up keeps its place while the call is taken (Answer's place stays empty), and only a second after the
         * answer does it move to the end; a tap on it within a second of the answer or of that move is not taken.
         */
        private fun hangUpTapped() {
            val now = SystemClock.uptimeMillis()
            if (now - answeredAt < GUARD_MS || now - movedAt < GUARD_MS) return
            Calls.hangUp()
        }

        fun bind() {
            val state = Calls.state
            val who = Calls.name.trim()
            val number = Calls.number.trim()
            // a number is read left to right, in Arabic too
            val digits = if (number.isEmpty()) "" else "⁦$number⁩"
            name.text = when {
                who.isNotEmpty() -> who
                number.isNotEmpty() -> digits
                else -> context.getString(R.string.call_unknown)
            }
            val letter = who.firstOrNull { it.isLetter() }
            initial.text = letter?.toString().orEmpty()
            initial.visibility = if (letter != null) View.VISIBLE else View.GONE
            person.visibility = if (letter != null) View.GONE else View.VISIBLE
            // (answered here a moment ago: the button has done its work, the phone's own word follows)
            val ringing = state == Calls.State.RINGING && !Calls.answering
            answerSlot(ringing, state)
            // (over: nothing left to press; its place is kept so the name does not jump as the card leaves)
            decline.visibility = if (state == Calls.State.ENDED) View.INVISIBLE else View.VISIBLE
            decline.contentDescription = context.getString(if (ringing) R.string.call_decline else R.string.call_hangup)
            line()
            val look = if (Calls.answering) null else state
            if (look != shown || first) {
                // the line under the name says what changed (answered, ended): it comes in, the rest stays put
                if (!first) { sub.alpha = 0f; sub.fadeTo(1f, Motion.MEDIUM) }
                first = false
                shown = look
                removeCallbacks(tick)
                if (state == Calls.State.TALKING) postDelayed(tick, 1000L)
                if (ringing) startPulse() else stopPulse()
            }
        }

        /**
         * Answer's place: shown while it rings; once answered (here or on the phone) the button fades and its place
         * stays empty, and a moment into the call it closes, Hang up sliding into it. A call that never rang here (one
         * the owner dialled) has no such place from the start.
         */
        private fun answerSlot(ringing: Boolean, state: Calls.State) {
            if (ringing) {
                removeCallbacks(collapse)
                closing = false
                if (answerBox.visibility != View.VISIBLE) {
                    // (the phone did not take the answer after all: it rings again, and the button is back)
                    val m = if (answerBox.visibility == View.GONE) LayoutMove(this, listOf(decline)) else null
                    answerBox.enter(scaleFrom = 0.85f, dur = Motion.SHORT)
                    m?.play(Motion.MEDIUM)
                    movedAt = SystemClock.uptimeMillis()
                } else if (first) {
                    answerBox.visibility = View.VISIBLE
                }
                return
            }
            if (first) {
                // (a card that comes up for a call already under way, or dialled: no empty place to close)
                answerBox.visibility = View.GONE
                return
            }
            if (answerBox.visibility == View.VISIBLE) {
                answerBox.exit(scaleTo = 0.85f, dur = Motion.XSHORT) { answerBox.visibility = View.INVISIBLE }
            }
            if (state == Calls.State.TALKING && answerBox.visibility != View.GONE && !closing) {
                // (once: the reports that follow, the name, the time, do not put it off)
                closing = true
                val since = SystemClock.uptimeMillis() - answeredAt
                postDelayed(collapse, (COLLAPSE_MS - since).coerceIn(GUARD_MS, COLLAPSE_MS))
            }
        }

        private fun collapse() {
            closing = false
            if (Calls.state != Calls.State.TALKING || answerBox.visibility == View.GONE) return
            val m = LayoutMove(this, listOf(decline))
            answerBox.animate().cancel()
            answerBox.visibility = View.GONE
            m.play(Motion.MEDIUM)
            movedAt = SystemClock.uptimeMillis()
        }

        /** What the call is doing, and the number when the name is what shows above. */
        private fun line() {
            val state = Calls.state
            val what = when (state) {
                Calls.State.RINGING -> context.getString(if (Calls.answering) R.string.call_answering else R.string.call_incoming)
                Calls.State.DIALING -> context.getString(R.string.call_dialing)
                Calls.State.TALKING -> clock((SystemClock.elapsedRealtime() - Calls.talkingSince) / 1000L)
                else -> context.getString(R.string.call_ended)
            }
            val number = Calls.number.trim()
            val withNumber = Calls.name.isNotBlank() && number.isNotEmpty() && state != Calls.State.ENDED
            sub.text = if (withNumber) "$what  ·  ⁦$number⁩" else what
            sub.setTextColor(when (state) {
                Calls.State.TALKING -> Palette.success
                Calls.State.ENDED -> Palette.text3
                else -> Palette.text2
            })
        }

        private fun clock(sec: Long): String {
            val s = sec.coerceAtLeast(0L)
            return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                else String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
        }

        private val tick = object : Runnable {
            override fun run() {
                if (Calls.state != Calls.State.TALKING) return
                line()
                postDelayed(this, 1000L)
            }
        }

        /** A ring that leaves the answer button and fades, again and again while it rings: where to press. */
        private fun startPulse() {
            if (pulsing != null) return
            pulsing = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1500L
                repeatCount = ValueAnimator.INFINITE
                interpolator = Motion.EXIT
                addUpdateListener {
                    val t = it.animatedValue as Float
                    pulse.scaleX = 1f + 0.42f * t
                    pulse.scaleY = 1f + 0.42f * t
                    pulse.alpha = 0.42f * (1f - t)
                }
                start()
            }
        }

        private fun stopPulse() {
            pulsing?.cancel()
            pulsing = null
            pulse.alpha = 0f
        }

        fun enter() {
            leaving = false
            card.enter(dy = -ENTER_DP, scaleFrom = 0.96f)
        }

        /**
         * Updated while it is up: it stays as it is, the entrance playing on (until 1.12 every report, the number and
         * then the name, cut the entrance to its end). Called back while it was leaving, it turns round from where it
         * is (a cancelled exit does not run its end, the removal).
         */
        fun stay() {
            if (!leaving) return
            leaving = false
            card.enter(dy = -ENTER_DP, scaleFrom = 0.96f, dur = Motion.SHORT)
        }

        fun leave(done: () -> Unit) {
            stopPulse()
            leaving = true
            card.exit(dy = -EXIT_DP, end = done)
        }

        fun stop() {
            stopPulse()
            removeCallbacks(tick)
            removeCallbacks(collapse)
            card.animate().cancel()
        }

        companion object {
            const val BUTTON = 68
            const val SIDE_DP = 12
            const val TOP_DP = 16
            const val BOTTOM_DP = 18
            /** How far it travels coming in and going (dp, upwards). */
            const val ENTER_DP = 16f
            const val EXIT_DP = 10f
            /** A tap on Hang up this soon after the answer or after it moved is not taken (ms). */
            const val GUARD_MS = 1000L
            /** How long into the answer Answer's empty place closes (ms). */
            const val COLLAPSE_MS = 1200L
        }
    }
}
