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
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
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
    private val main = Handler(Looper.getMainLooper())
    private var view: Card? = null
    private var attached = false

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
        if (attached) { v.stay(); return }
        val lp = WindowManager.LayoutParams(
            WRAP, WRAP, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 6.dp
            title = "AuraCall"
        }
        try {
            (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
            attached = true
            v.enter()
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
        if (attached) {
            try { (v.context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) { }
        }
        attached = false
        view = null
    }

    /** The card, with room around it for its shadow and for the ring that pulses round the answer button. */
    private class Card(c: Context) : FrameLayout(c) {
        val dark = Palette.dark
        val accent = Palette.accent
        val lang = Prefs.lang
        private val card = LinearLayout(c)
        private val avatar = FrameLayout(c)
        private val initial: AText = c.label(30f, accent, Fonts.MEDIUM, gravity = Gravity.CENTER)
        private val person: ImageView = c.iconView(R.drawable.ic_user, 34, accent)
        private val name: AText = c.label(25f, Palette.text, Fonts.MEDIUM)
        private val sub: AText = c.label(16.5f, Palette.text2, Fonts.REGULAR)
        private val decline = round(R.drawable.ic_call_end, Palette.danger, R.string.call_decline) { Calls.hangUp() }
        private val answer = round(R.drawable.ic_call, Palette.success, R.string.call_answer) { Calls.answer() }
        private val pulse = View(c)
        private val answerBox = FrameLayout(c)
        private var pulsing: ValueAnimator? = null
        private var shown: Calls.State? = null
        private var first = true
        private val arabic get() = Locale.getDefault().language == "ar"

        init {
            layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(Locale.getDefault())
            clipChildren = false
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            card.clipChildren = false
            card.clipToPadding = false // (the ring round the answer button grows past the card's inner edge)
            card.setPaddingRelative(16.dp, 14.dp, 16.dp, 14.dp)
            card.background = Shapes.rect(if (dark) 0xF7191C20.toInt() else 0xFAFFFFFF.toInt(), 38f, if (dark) 0x1FFFFFFF else 0x12000000)
            card.elevation = 16.dp.toFloat()
            card.isClickable = true // (a touch on the card is not a touch on the map under it)

            avatar.background = Shapes.oval(Palette.withAlpha(accent, if (dark) 0.22f else 0.14f))
            avatar.addView(initial, flp(MATCH, MATCH))
            avatar.addView(person, flp(34.dp, 34.dp, Gravity.CENTER))
            card.addView(avatar, lp(72.dp, 72.dp))

            val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(name, lp(MATCH, WRAP))
            texts.addView(sub, lp(MATCH, WRAP).apply { topMargin = 2.dp })
            card.addView(texts, lp(0, WRAP, 1f).apply { marginStart = 16.dp; marginEnd = 16.dp })

            card.addView(decline, lp(BUTTON.dp, BUTTON.dp))
            pulse.background = Shapes.oval(Palette.success)
            answerBox.clipChildren = false
            answerBox.addView(pulse, flp(BUTTON.dp, BUTTON.dp))
            answerBox.addView(answer, flp(BUTTON.dp, BUTTON.dp))
            card.addView(answerBox, lp(BUTTON.dp, BUTTON.dp).apply { marginStart = 18.dp })

            val w = minOf(640.dp, resources.displayMetrics.widthPixels - 40.dp)
            addView(card, flp(w, WRAP).apply { setMargins(20.dp, 10.dp, 20.dp, 26.dp) })
        }

        private fun round(icon: Int, color: Int, what: Int, tap: () -> Unit) = FrameLayout(context).apply {
            background = Shapes.pressable(Shapes.oval(color), Shapes.oval(Palette.mix(color, 0xFF000000.toInt(), 0.18f)))
            isClickable = true
            contentDescription = context.getString(what)
            pressScale(0.93f)
            addView(context.iconView(icon, 30, 0xFFFFFFFF.toInt()).apply { layoutParams = flp(30.dp, 30.dp, Gravity.CENTER) })
            setOnClickListener { tap() }
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
            answerBox.visibility = if (ringing) View.VISIBLE else View.GONE
            // (over: nothing left to press; its place is kept so the name does not jump as the card leaves)
            decline.visibility = if (state == Calls.State.ENDED) View.INVISIBLE else View.VISIBLE
            decline.contentDescription = context.getString(if (ringing) R.string.call_decline else R.string.call_hangup)
            line()
            val look = if (Calls.answering) null else state
            if (look != shown || first) {
                // the line under the name says what changed (answered, ended): it comes in, the rest stays put
                if (!first) { sub.alpha = 0f; sub.animate().alpha(1f).setDuration(Motion.MEDIUM).start() }
                first = false
                shown = look
                removeCallbacks(tick)
                if (state == Calls.State.TALKING) postDelayed(tick, 1000L)
                if (ringing) startPulse() else stopPulse()
            }
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
            card.alpha = 0f
            card.translationY = (-22).dp.toFloat()
            card.scaleX = 0.97f
            card.scaleY = 0.97f
            card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.EMPHASIZED)
                .withEndAction(null).start()
        }

        /** Shown again while it was leaving: it stays (a cancelled exit does not run its end, the removal). */
        fun stay() {
            card.animate().cancel()
            card.alpha = 1f
            card.translationY = 0f
            card.scaleX = 1f
            card.scaleY = 1f
        }

        fun leave(done: () -> Unit) {
            stopPulse()
            card.animate().alpha(0f).translationY((-14).dp.toFloat()).setDuration(Motion.SHORT).setInterpolator(Motion.EXIT)
                .withEndAction(done).start()
        }

        fun stop() {
            stopPulse()
            removeCallbacks(tick)
            card.animate().cancel()
        }

        private companion object {
            const val BUTTON = 68
        }
    }
}
