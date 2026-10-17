package com.abdllh.aura.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.media.AuraNotificationListener
import com.abdllh.aura.system.Notices
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Touch
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
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp
import java.util.Locale

/**
 * A message from the phone on screen, over any app: a card at the top with the app, who wrote and what (one or two
 * lines), gone again by itself after a few seconds. A tap opens the conversation in its app; a flick upwards or the
 * close button puts it away. A newer message replaces the one showing. During a call the call card has the top of the
 * screen: a message that comes then waits and is shown when the call is over (if it is still recent).
 */
object MessageBanner {
    private const val TAG = "AuraMessageBanner"
    private val main = Handler(Looper.getMainLooper())
    private var view: Card? = null
    private var attached = false
    /** A message that came during a call, and when (elapsed realtime). */
    private var waiting: AuraNotificationListener.Message? = null
    private var waitingAt = 0L
    private var listening = false

    fun show(ctx: Context, m: AuraNotificationListener.Message) {
        val c = ctx.applicationContext
        if (!listening) { listening = true; Calls.addListener { main.post { afterCall(c) } } }
        if (Calls.state != Calls.State.IDLE) {
            waiting = m
            waitingAt = SystemClock.elapsedRealtime()
            return
        }
        if (!Settings.canDrawOverlays(c)) { Log.w(TAG, "no overlay permission"); return }
        var v = view
        if (v == null || v.dark != Palette.dark || v.accent != Palette.accent || v.lang != Prefs.lang) {
            remove()
            v = Card(LocaleHelper.wrap(c)).also { view = it }
        }
        v.bind(m, replacing = attached)
        if (attached) { v.stay(); return }
        val lp = WindowManager.LayoutParams(
            WRAP, WRAP, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            title = "AuraMessage"
        }
        try {
            (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
            attached = true
            v.enter()
        } catch (t: Throwable) {
            Log.w(TAG, "show: $t")
        }
    }

    /** Settings' "show me how it looks": a made-up message from AMRI itself. */
    fun preview(ctx: Context) {
        val c = LocaleHelper.wrap(ctx.applicationContext)
        show(c, AuraNotificationListener.Message(c.packageName, c.getString(R.string.app_name), null,
            c.getString(R.string.notices_preview_sender), null, c.getString(R.string.notices_preview_text), null))
    }

    /** The call is over: the message that waited for it comes now, unless it is old news. */
    private fun afterCall(c: Context) {
        if (Calls.state != Calls.State.IDLE) {
            // (a call came while a message showed: the call card has the top)
            if (attached) hide()
            return
        }
        val m = waiting ?: return
        waiting = null
        if (SystemClock.elapsedRealtime() - waitingAt < WAIT_MS) main.postDelayed({ show(c, m) }, 800L)
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
        private val card = LinearLayout(c)
        private val icon = ImageView(c)
        private val from: AText = c.label(Type.CAPTION, Palette.text3)
        private val who: AText = c.label(Type.TITLE, Palette.text)
        private val what: AText = c.label(Type.BODY, Palette.text2, lines = 2).apply { ellipsize = TextUtils.TruncateAt.END }
        private var message: AuraNotificationListener.Message? = null
        private var leaving = false
        private val timeout = Runnable { hide() }

        init {
            layoutDirection = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault())
            clipChildren = false
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            card.setPaddingRelative(Space.L.dp, Space.M.dp, Space.XS.dp, Space.M.dp)
            card.background = Shapes.overlay()
            card.lift(Elev.OVERLAY, overMap = true)
            card.isClickable = true

            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            icon.roundedClip(Radius.S)
            card.addView(icon, lp(48.dp, 48.dp))

            val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(from, lp(MATCH, WRAP))
            texts.addView(who, lp(MATCH, WRAP))
            texts.addView(what, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
            card.addView(texts, lp(0, WRAP, 1f).apply { marginStart = Space.L.dp; marginEnd = Space.S.dp })

            val close = FrameLayout(c).apply {
                background = Shapes.pressable(Shapes.oval(0), Shapes.oval(Palette.press))
                isClickable = true
                contentDescription = c.getString(R.string.notices_close)
                addView(c.iconView(R.drawable.ic_close, 22, Palette.text2).apply { layoutParams = flp(22.dp, 22.dp, Gravity.CENTER) })
                setOnClickListener { hide() }
            }
            card.addView(close, lp(Touch.MIN.dp, Touch.MIN.dp))

            val w = minOf(600.dp, resources.displayMetrics.widthPixels - 2 * SIDE_DP.dp)
            addView(card, flp(w, WRAP).apply { setMargins(SIDE_DP.dp, TOP_DP.dp, SIDE_DP.dp, BOTTOM_DP.dp) })
            card.setOnTouchListener(Drag())
        }

        fun bind(m: AuraNotificationListener.Message, replacing: Boolean) {
            message = m
            val names = if (m.group.isNullOrBlank()) m.app else "${m.app}  ·  ${m.group}"
            val text = if (Notices.showText) m.text.ifBlank { context.getString(R.string.notices_new_message) }
                else context.getString(R.string.notices_new_message)
            if (replacing) {
                // (the newer one replaces it in place: the words change, the card stays)
                for (t in listOf(from, who, what)) { t.alpha = 0.2f; t.fadeTo(1f, Motion.MEDIUM) }
            }
            from.text = names
            who.text = m.sender.ifBlank { m.app }
            what.text = text
            what.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            if (m.icon != null) icon.setImageDrawable(m.icon) else icon.setImageResource(R.mipmap.ic_launcher)
            removeCallbacks(timeout)
            postDelayed(timeout, (SHOW_MS + 45L * text.length).coerceAtMost(MAX_SHOW_MS))
        }

        private fun open() {
            val m = message
            try { m?.open?.send() } catch (t: Throwable) { Log.w(TAG, "open: $t") }
            hide()
        }

        fun enter() {
            leaving = false
            card.enter(dy = -16f, scaleFrom = 0.96f)
        }

        /** Shown again while it was leaving: it turns round from where it is. */
        fun stay() {
            if (!leaving) return
            leaving = false
            card.enter(dy = -16f, scaleFrom = 0.96f, dur = Motion.SHORT)
        }

        fun leave(done: () -> Unit) {
            removeCallbacks(timeout)
            leaving = true
            card.exit(dy = -10f, end = done)
        }

        fun stop() {
            removeCallbacks(timeout)
            card.animate().cancel()
        }

        /**
         * The card under the finger: it sinks a little when pressed, follows a drag upwards (not downwards: there is
         * nothing below to pull it to) and goes when flicked or pulled up far enough, else springs back; a touch that did
         * not move is a tap. The timer waits while the finger is on it.
         */
        private inner class Drag : OnTouchListener {
            private val slop = ViewConfiguration.get(context).scaledTouchSlop
            private var downY = 0f
            private var downAt = 0L
            private var dragging = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = e.rawY; downAt = SystemClock.uptimeMillis(); dragging = false
                        removeCallbacks(timeout)
                        card.animate().scaleX(0.985f).scaleY(0.985f).setDuration(Motion.XSHORT).setInterpolator(Motion.STANDARD).start()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - downY
                        if (!dragging && dy < -slop) dragging = true
                        if (dragging) card.translationY = dy.coerceAtMost(0f)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dy = e.rawY - downY
                        val ms = (SystemClock.uptimeMillis() - downAt).coerceAtLeast(1L)
                        card.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SHORT).setInterpolator(Motion.STANDARD).start()
                        when {
                            dragging && (dy < -36.dp || -dy / ms > 0.6f) -> hide()
                            dragging -> {
                                card.animate().translationY(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.EMPHASIZED).start()
                                postDelayed(timeout, SHOW_MS)
                            }
                            e.actionMasked == MotionEvent.ACTION_UP -> open()
                            else -> postDelayed(timeout, SHOW_MS)
                        }
                    }
                }
                return true
            }
        }

        companion object {
            const val SIDE_DP = 12
            const val TOP_DP = 16
            const val BOTTOM_DP = 18
            /** On screen this long, plus a little per letter to read it (ms). */
            const val SHOW_MS = 6000L
            const val MAX_SHOW_MS = 12_000L
        }
    }

    /** A message that came during a call is still shown after it when the call was shorter than this (ms). */
    private const val WAIT_MS = 120_000L
}
