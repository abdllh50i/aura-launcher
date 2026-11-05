package com.abdllh.aura.phone

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.BidiFormatter
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import com.abdllh.aura.media.AuraNotificationListener
import com.abdllh.aura.system.DriveState
import com.abdllh.aura.system.Gear
import com.abdllh.aura.system.Notices
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Asked
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.Motion
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
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.glyph
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.overlayMs
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.voice.Amri

/**
 * A message from the phone on screen, over any app (SPEC.md sections 5 and 6.5): a banner in the call's place, 628 x 98, gone by itself. The app and who wrote; under it `رسالة جديدة` while the car
 * moves, the message itself only when it is parked and the switch allows, so there is nothing to read while driving ([Asked.MESSAGE_TEXT_MOVING] is the owner's answer). `اسمعها` at x 397: Amri reads
 * it. A flick upwards or the close button puts it away; parked, a tap on the card opens the conversation in its app. A newer message replaces the one showing, in place. [OverlayOrder] holds a message
 * back: it waits, and comes when that is over if it is still recent.
 */
object MessageBanner {
    private const val TAG = "AuraMessageBanner"
    private val main = Handler(Looper.getMainLooper())
    private var view: Card? = null
    private var app: Context? = null
    /** A message held back, and since when (elapsed realtime). */
    private var waiting: AuraNotificationListener.Message? = null
    private var waitingAt = 0L
    private var listening = false

    fun show(ctx: Context, m: AuraNotificationListener.Message) {
        val c = ctx.applicationContext
        app = c
        if (!listening) {
            listening = true
            // (a call came while one showed: the call has its place, and the message comes back after it; reverse the same, nothing lies over the camera)
            Calls.addListener { main.post { if (Calls.state != Calls.State.IDLE) putBack() } }
            Gear.addListener { main.post { if (Amri.reversing) putBack() } }
        }
        if (OverlayOrder.messageWaits()) { hold(m); return }
        if (!Settings.canDrawOverlays(c)) { Log.w(TAG, "no overlay permission"); return }
        var v = view
        if (v == null || v.lang != Prefs.lang) {
            remove()
            v = Card(LocaleHelper.wrap(c)).also { view = it }
        }
        val replacing = v.window.attached
        v.bind(m, replacing)
        if (replacing) { v.stay(); return }
        if (v.window.add()) v.enter() else view = null
    }

    /** Settings' "show me how it looks": a made-up message from AMRI itself. */
    fun preview(ctx: Context) {
        val c = LocaleHelper.wrap(ctx.applicationContext)
        show(c, AuraNotificationListener.Message(c.packageName, c.getString(R.string.app_name), null,
            c.getString(R.string.notices_preview_sender), null, c.getString(R.string.notices_preview_text), null))
    }

    /** Debug builds: a message as if it had come, through the same door. */
    internal fun debugShow(ctx: Context, appName: String, from: String, text: String) {
        show(ctx, AuraNotificationListener.Message(ctx.packageName, appName, null, from, null, text, null))
    }

    private fun putBack() {
        val v = view ?: return
        if (v.window.attached) { hold(v.message); hide() }
    }

    private fun hold(m: AuraNotificationListener.Message?) {
        waiting = m ?: return
        waitingAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(again)
        main.postDelayed(again, AGAIN_MS)
    }

    /** Once a second while one waits: over or not. Old news is dropped. */
    private val again: Runnable = Runnable {
        val m = waiting
        val c = app
        if (m != null && c != null && SystemClock.elapsedRealtime() - waitingAt < WAIT_MS) {
            if (OverlayOrder.messageWaits()) main.postDelayed(again, AGAIN_MS)
            else { waiting = null; show(c, m) }
        } else waiting = null
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

    /** The card in its window, with room round it for its shadow and its entrance. */
    @SuppressLint("ClickableViewAccessibility")
    private class Card(c: Context) {
        val lang = Prefs.lang
        val window = OverlayWindow(c, "AuraMessage", WINDOW_X, 0, 1024 - WINDOW_X, WINDOW_H)
        private val stage = FrameLayout(c)
        private val card = SurfaceCard(c, Surface.OVERLAY)
        private val from: AText = c.label(Type.msgFrom, { Palette.ink2Move }, gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        private val what: AText = c.label(Type.msgTitle, { Palette.ink }, gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        private val listen = TallHit(c, BUTTON_Y, BUTTON_H)
        var message: AuraNotificationListener.Message? = null
            private set
        private var leaving = false
        private val timeout = Runnable { hide() }
        private val onDrive: () -> Unit = { message?.let { setWords(it) } }

        init {
            stage.addView(card, Surface.OVERLAY.at(ROOM, ROOM, CARD_W, CARD_H))
            val plate = ImageView(c).apply {
                val t = Tint(c, R.drawable.plate_18) { Palette.fill2 }
                background = t.drawable
                setPadding(13, 13, 13, 13)
                setImageResource(R.drawable.ic_message)
                look { t.recolour() }
                glyph { Palette.ink }
            }
            stage.addView(plate, at(ROOM + 558, ROOM + 21, 56, 56))
            stage.addView(from, Type.msgFrom.box(ROOM + TEXT_END - TEXT_W, ROOM + 14, TEXT_W))
            stage.addView(what, Type.msgTitle.box(ROOM + TEXT_END - TEXT_W, ROOM + 50, TEXT_W))
            val button = ABtn(c).apply {
                layoutDirection = if (Fonts.arabicUi) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                sidePad = BUTTON_SIDE
                kind = ABtn.Kind.TONAL
                size = ABtn.Size.CALL
                setText(R.string.msg_listen)
                icon(R.drawable.ic_vol, 28)
                setOnClickListener { listenTapped() }
            }
            listen.addView(button, at(0, BUTTON_Y, BUTTON_W, BUTTON_H))
            stage.addView(listen, at(ROOM + 13, ROOM, BUTTON_W, CARD_H))
            val close = IconButton(c, R.drawable.ic_close, 28, 76, 84, token = { Palette.ink2 }).apply {
                contentDescription = c.getString(R.string.notices_close)
                setOnClickListener { hide() }
            }
            stage.addView(close, at(ROOM + 159, ROOM + 7, 76, 84))
            card.setOnTouchListener(Drag())
            window.root.addView(stage, FrameLayout.LayoutParams(CARD_W + 2 * ROOM, WINDOW_H))
            window.visible(ROOM, ROOM, ROOM + CARD_W, ROOM + CARD_H)
            DriveState.addListener(onDrive)
        }

        fun bind(m: AuraNotificationListener.Message, replacing: Boolean) {
            window.syncLook()
            message = m
            if (replacing) {
                // (the newer one replaces it in place: the words change, the card stays)
                for (t in arrayOf(from, what)) { t.alpha = 0.2f; t.fadeTo(1f, Tokens2.MS_cardIn) }
            }
            val bidi = BidiFormatter.getInstance(Fonts.arabicUi)
            from.text = listOf(m.app, m.group, m.sender).filter { !it.isNullOrBlank() }.distinct().joinToString(" · ") { bidi.unicodeWrap(it) }
            setWords(m)
            val moving = DriveState.moving
            removeTimer()
            stage.postDelayed(timeout, if (moving) MOVING_MS else (SHOW_MS + 45L * m.text.length).coerceAtMost(MAX_SHOW_MS))
        }

        /** The message itself only with the car parked; the car starting to move takes it off the card at once. */
        private fun setWords(m: AuraNotificationListener.Message) {
            val body = Notices.showText && m.text.isNotBlank() && (!DriveState.moving || Asked.MESSAGE_TEXT_MOVING)
            // (its own direction inside the line: an English message on an Arabic screen kept its question mark at the wrong end)
            what.text = if (body) BidiFormatter.getInstance(Fonts.arabicUi).unicodeWrap(m.text) else what.context.getString(R.string.notices_new_message)
        }

        private fun removeTimer() = stage.removeCallbacks(timeout)

        private fun listenTapped() {
            val m = message ?: return
            val c = what.context
            val who = m.sender.ifBlank { m.app }
            Amri.readOut(c, if (m.text.isBlank()) c.getString(R.string.notices_new_message) else c.getString(R.string.amri_read_message, who, m.text))
            hide()
        }

        private fun open() {
            // (only parked: a conversation over the map is no thing to open while driving)
            if (DriveState.moving) return
            try { message?.open?.send() } catch (t: Throwable) { Log.w(TAG, "open: $t") }
            hide()
        }

        fun enter() {
            leaving = false
            stage.enter(dy = -ENTER_DY, dur = overlayMs(Tokens2.MS_banner))
        }

        /** Shown again while it was leaving: it turns round from where it is. */
        fun stay() {
            if (!leaving) return
            leaving = false
            stage.enter(dy = -ENTER_DY, dur = overlayMs(Tokens2.MS_banner))
        }

        fun leave(done: () -> Unit) {
            if (leaving) return
            leaving = true
            removeTimer()
            stage.exit(dur = overlayMs(Tokens2.MS_bannerOut), end = done)
        }

        fun stop() {
            removeTimer()
            DriveState.removeListener(onDrive)
            stage.animate().cancel()
        }

        /** The card under the finger: it follows a drag upwards and goes when flicked or pulled up far enough, else comes back; a touch that did not move is a tap. The timer waits while the finger is on it. */
        private inner class Drag : View.OnTouchListener {
            private val slop = ViewConfiguration.get(card.context).scaledTouchSlop
            private var downY = 0f
            private var downAt = 0L
            private var dragging = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = e.rawY
                        downAt = SystemClock.uptimeMillis()
                        dragging = false
                        removeTimer()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - downY
                        if (!dragging && dy < -slop) dragging = true
                        if (dragging) stage.translationY = dy.coerceAtMost(0f)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dy = e.rawY - downY
                        val ms = (SystemClock.uptimeMillis() - downAt).coerceAtLeast(1L)
                        when {
                            dragging && (dy < -FLICK_PX || -dy / ms > 0.6f) -> hide()
                            dragging -> {
                                stage.animate().translationY(0f).setDuration(Tokens2.MS_banner).setInterpolator(Motion.ENTER).start()
                                stage.postDelayed(timeout, SHOW_MS)
                            }
                            e.actionMasked == MotionEvent.ACTION_UP -> {
                                open()
                                if (window.attached && !leaving) stage.postDelayed(timeout, MOVING_MS)
                            }
                            else -> stage.postDelayed(timeout, SHOW_MS)
                        }
                    }
                }
                return true
            }
        }
    }

    /** The visible rect from the window's left and top: the overlay's outset. */
    private const val ROOM = 44
    private const val WINDOW_X = 340
    private const val WINDOW_H = 186
    private const val CARD_W = 628
    private const val CARD_H = 98
    private const val BUTTON_W = 138
    private const val BUTTON_H = 72
    private const val BUTTON_Y = 13
    private const val BUTTON_SIDE = 6
    private const val TEXT_END = 544
    private const val TEXT_W = 300
    private const val ENTER_DY = 24f
    private const val FLICK_PX = 36
    /** Parked, on screen this long plus a little per letter to read it, and no longer than the most; moving, a fixed 6 s: there is nothing to read (ms). */
    private const val SHOW_MS = 6000L
    private const val MAX_SHOW_MS = 12_000L
    private const val MOVING_MS = 6000L
    /** A message that waited is still shown after this long, not later (ms). */
    private const val WAIT_MS = 120_000L
    private const val AGAIN_MS = 1000L
}
