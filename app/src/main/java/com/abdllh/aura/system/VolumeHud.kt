package com.abdllh.aura.system

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.OverlayWindow
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.overlayMs
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import java.util.concurrent.Executors

/** The boost's tint as text on the pill's card: the warm token, as far toward white or black as it takes to read (4.5:1). */
private fun boostInk(): Int = Palette.reach(Palette.warn, Palette.cardFlat, 4.5, Palette.dark)

/**
 * Aura's own volume display, in place of the stock floating bar (com.android.launcher/com.launcher.FloatBar).
 *
 * It shows for every change of the volume, on every screen and over any app, whoever made it: the steering-wheel, panel and
 * CAN buttons (the firmware announces each of those first with com.nwd.action.ACTION_KEY_VALUE 14 up / 15 down / 2 mute),
 * a knob, voice, another app, the row's own + and -. All of it arrives through [CarAudio]'s listeners (the firmware's
 * notifyAudioParam and its mcu_system_volume setting). A mute shows when somebody asked for it (a key, Aura itself): the
 * firmware's mcu_mute_state may also move by itself, and the row's number says it anyway. Navigation (15) and phone-call
 * (16) levels show while a button was just pressed.
 *
 * The stock bar is turned off by disabling its service component through the unit's root shell (once; it stays off
 * across restarts) — only when Aura may draw over other apps, which the same shell allows; otherwise the stock bar
 * stays. The ROM's restore and Aura's kill switch ([release]) enable it again.
 */
object VolumeHud {
    private const val TAG = "AuraVolumeHud"
    private const val APPLIED = "volumeHudStockOff"        // "on" while the stock bar is off because of Aura
    private const val STOCK_PKG = "com.android.launcher"
    private const val STOCK_CLS = "com.launcher.FloatBar"
    private const val STOCK = "$STOCK_PKG/$STOCK_CLS"
    private const val KEY_WINDOW_MS = 1500L
    private const val QUIET_AFTER_START_MS = 10_000L     // the first reports after a start are not changes
    const val DEBUG_KEY = "com.abdllh.aura.debug.VOLUME_KEY" // emulator: a wheel button (up / down / mute)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private var keyAt = -1_000_000L
    private var startedAt = 0L
    private var lastVolume = -1
    private var lastMute = false
    private var hud: HudView? = null

    enum class Kind { MEDIA, NAV, PHONE }

    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        startedAt = SystemClock.elapsedRealtime()
        lastVolume = CarAudio.level()
        lastMute = CarAudio.muted()
        CarAudio.addListener(onAudio)
        CarAudio.addParamListener(onParam)
        try {
            c.registerReceiver(receiver, IntentFilter().apply {
                addAction("com.nwd.action.ACTION_KEY_VALUE")
                addAction("com.nwd.ACTION_REQUEST_VLOUME_DISPLAY") // the stock apps ask for the bar like this
                if (BuildConfig.DEBUG) addAction(DEBUG_KEY)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "receiver: $t")
        }
        // not in the middle of the boot: turning the stock bar off restarts the stock launcher's process once
        main.postDelayed({ apply(c) }, 8000L)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try { // anything can send these: a broadcast with a bad extra must not take the home screen down
                when (i.action) {
                    "com.nwd.action.ACTION_KEY_VALUE" -> {
                        val k = (i.extras?.get("extra_key_value") as? Number)?.toInt() ?: return
                        if (k == 14 || k == 15 || k == 2) keyAt = SystemClock.elapsedRealtime()
                    }
                    "com.nwd.ACTION_REQUEST_VLOUME_DISPLAY" -> show(Kind.MEDIA, CarAudio.level(), CarAudio.muted())
                    DEBUG_KEY -> { // a wheel button on the emulator's own stream (the firmware's stand-in is CarAudio.DEBUG_WHEEL)
                        keyAt = SystemClock.elapsedRealtime()
                        when (i.getStringExtra("key")) {
                            "up" -> CarAudio.stepLevel(1)
                            "down" -> CarAudio.stepLevel(-1)
                            "mute" -> CarAudio.toggleMute()
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "receive: $t")
            }
        }
    }

    /** Aura is not the home app (the kill switch): the stock bar is the one to show. */
    private fun auraOff() = SystemProps.get(Device.DISABLED_PROP) == "1"

    private val onAudio: () -> Unit = {
        val v = CarAudio.level()
        val m = CarAudio.muted()
        if (v != lastVolume || m != lastMute) {
            val now = SystemClock.elapsedRealtime()
            val asked = now - keyAt < KEY_WINDOW_MS || CarAudio.changedByAura()
            if (asked || (v != lastVolume && now - startedAt > QUIET_AFTER_START_MS)) show(Kind.MEDIA, v, m)
            lastVolume = v
            lastMute = m
        }
    }

    private val onParam: (Int, Int) -> Unit = { type, value ->
        if ((type == 15 || type == 16) && SystemClock.elapsedRealtime() - keyAt < KEY_WINDOW_MS) {
            show(if (type == 16) Kind.PHONE else Kind.NAV, value, false)
        }
    }

    // ------------------------------------------------------------------------------------------ the stock bar
    /** Brings the unit in line, on a worker thread. */
    fun apply(ctx: Context) {
        if (!Device.isNwd) return
        val c = ctx.applicationContext
        worker.execute {
            try {
                // decided here, in order with release(): the kill switch may have been set meanwhile
                if (auraOff()) giveBack(c) else takeOver(c)
            } catch (t: Throwable) {
                Log.w(TAG, "apply: $t")
            }
        }
    }

    /** Aura stops being the home app (kill switch): the stock bar comes back. */
    fun release(ctx: Context) {
        if (!Device.isNwd) return
        val c = ctx.applicationContext
        worker.execute { try { giveBack(c) } catch (t: Throwable) { Log.w(TAG, "release: $t") } }
    }

    private fun takeOver(c: Context) {
        if (!Settings.canDrawOverlays(c)) LocalAdb.run("appops set ${c.packageName} SYSTEM_ALERT_WINDOW allow")
        if (!Settings.canDrawOverlays(c)) return // no window of its own: the stock bar stays
        if (stockOn(c) != true) return
        Prefs.raw.edit().putString(APPLIED, "on").apply()
        val r = LocalAdb.run("pm disable $STOCK")
        Log.i(TAG, "stock bar off: ${r.ok} ${r.output}")
        if (stockOn(c) == true) return
        restartStockHelpers(c)
    }

    private fun giveBack(c: Context) {
        if (Prefs.raw.getString(APPLIED, "") != "on") return // never touched
        if (stockOn(c) == false) {
            val r = LocalAdb.run("pm enable $STOCK && am startservice -n $STOCK")
            Log.i(TAG, "stock bar back: ${r.ok} ${r.output}")
            if (stockOn(c) == false) return
            restartStockHelpers(c)
        }
        Prefs.raw.edit().putString(APPLIED, "off").apply()
    }

    /**
     * Enabling or disabling a component restarts the stock launcher's process: its assistive-touch service comes back
     * (the boot tip has nothing to do any more, and the volume bar is started with the enable itself).
     */
    private fun restartStockHelpers(c: Context) {
        main.post {
            try { c.startService(Intent("com.nwd.action.SuspensionService").setPackage(STOCK_PKG)) } catch (_: Throwable) { }
        }
    }

    /** Is the stock bar's service enabled (null: not on this unit)? */
    private fun stockOn(c: Context): Boolean? = try {
        c.packageManager.getComponentEnabledSetting(ComponentName(STOCK_PKG, STOCK_CLS)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------------------------------ the display
    private fun show(kind: Kind, value: Int, muted: Boolean) {
        val c = app ?: return
        if (auraOff() || !Settings.canDrawOverlays(c)) return
        var v = hud
        if (v == null || v.lang != Prefs.lang) {
            v?.window?.remove() // (the language changed: built again)
            v = HudView(LocaleHelper.wrap(c)).also { hud = it }
        }
        // the media level on the whole scale (0..50 on his unit: the boost above the firmware's top); navigation and phone on theirs
        if (kind == Kind.MEDIA) v.bind(kind, value, CarAudio.maxLevel(), muted, CarAudio.max())
        else v.bind(kind, value, CarAudio.max(), muted, CarAudio.max())
        if (v.window.attached) v.stay() // pressed again while it was fading out
        else if (v.window.add()) v.enter() else return
        main.removeCallbacks(hide)
        main.postDelayed(hide, Tokens2.MS_volumeHold)
    }

    // only the view that faded out is removed (a language change may have replaced it meanwhile)
    private val hide = Runnable { val v = hud; v?.leave { if (hud === v) v?.window?.remove() } }

    /**
     * A floating pill 264 x 64, centred on the screen above the row, x 380..644, y 440..504, so it hides neither the driver's column nor the row: the speaker, a level bar and the car's own step number (the one
     * its buttons and the row show), left to right in every language, not touchable. While the voice card lies there it stands above it, y 352..416. A window of its own with room for the shadow.
     */
    private class HudView(c: android.content.Context) {
        val lang = Prefs.lang
        val window = OverlayWindow(c, "AuraVolume", WINDOW_X, WINDOW_Y, CARD_W + 2 * Surface.OVERLAY.outset, WINDOW_H, touchable = false)
        private val card = FrameLayout(c)
        private val icon = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        private val bar = Bar(c)
        private var boosted = false
        private val value: AText = c.label(Type.hudNum, { if (boosted) boostInk() else Palette.ink }, gravity = Gravity.CENTER)
        private val word: AText = c.label(Type.button, { Palette.redInk }, gravity = Gravity.CENTER).apply { setText(R.string.ctl_mute); visibility = View.INVISIBLE }
        /** Above the firmware's top: the word over the bar's end, in the boost's tint. */
        private val boostWord: AText = c.label(Type.rowHint, { boostInk() }, gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { setText(R.string.vol_boost); visibility = View.INVISIBLE }
        private var iconMuted = false
        private var leaving = false

        init {
            card.addView(SurfaceCard(c, Surface.OVERLAY), Surface.OVERLAY.at(CARD_X, CARD_Y, CARD_W, CARD_H))
            card.addView(icon, at(CARD_X + 16, CARD_Y + 17, 30, 30))
            card.addView(bar, at(CARD_X + 58, CARD_Y + 28, Bar.W, Bar.H))
            value.textDirection = View.TEXT_DIRECTION_LTR
            card.addView(value, Type.hudNum.box(CARD_X + 206, CARD_Y + 10, 50))
            card.addView(word, Type.button.box(CARD_X + 200, CARD_Y + 16, 60))
            card.addView(boostWord, at(CARD_X + 58, CARD_Y + 38, Bar.W, 24)) // under the bar's end (above it, the dots of its ي met the bar)
            window.root.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            icon.look { setColorFilter(if (iconMuted) Palette.ink3 else Palette.ink) }
        }

        /** [top]: where the boost begins on the scale (the firmware's top); the bar's part above it is the boost's. */
        fun bind(kind: Kind, level: Int, max: Int, muted: Boolean, top: Int) {
            window.syncLook()
            window.root.translationY = if (com.abdllh.aura.voice.AmriOverlay.showing) ABOVE_VOICE else 0f
            val m = max.coerceAtLeast(1)
            val f = level.coerceIn(0, m).toFloat() / m
            val boost = kind == Kind.MEDIA && !muted && level > top
            icon.setImageResource(when {
                kind == Kind.NAV -> R.drawable.ic_nav
                kind == Kind.PHONE -> R.drawable.ic_phone
                muted || level <= 0 -> R.drawable.ic_vol_mute
                f < 0.4f -> R.drawable.ic_vol_low
                else -> R.drawable.ic_vol
            })
            iconMuted = muted
            icon.setColorFilter(if (muted) Palette.ink3 else Palette.ink)
            bar.dim = muted
            bar.top = if (kind == Kind.MEDIA && m > top) top.toFloat() / m else 1f
            bar.to(f)
            boosted = boost
            boostWord.visibility = if (boost) View.VISIBLE else View.INVISIBLE
            boostWord.setTextColor(boostInk())
            if (muted) {
                value.visibility = View.INVISIBLE
                word.visibility = View.VISIBLE
            } else {
                word.visibility = View.INVISIBLE
                value.visibility = View.VISIBLE
                value.setTextColor(if (boost) boostInk() else Palette.ink)
                value.text = level.coerceIn(0, m).toString()
            }
        }

        fun enter() {
            leaving = false
            card.enter(dy = ENTER_DY, dur = overlayMs(Tokens2.MS_cardIn))
        }

        /**
         * Shown again while it is up: a held wheel button repeats every few tenths of a second, and the entrance plays on through the repeats. Shown again while it was fading out, it turns round from
         * where it is (a cancelled fade-out does not run its end action, the removal).
         */
        fun stay() {
            if (!leaving) return
            leaving = false
            card.enter(dy = ENTER_DY, dur = overlayMs(Tokens2.MS_cardIn))
        }

        fun leave(done: () -> Unit) {
            leaving = true
            card.exit(dur = overlayMs(Tokens2.MS_volumeOut), end = done)
        }

        companion object {
            const val CARD_W = 264
            const val CARD_H = 64
            private const val SHADOW = 44 // (Surface.OVERLAY.outset)
            /** Where the visible card begins in this window: x 380, y 440 on the screen, with room above it for its place over the voice card. */
            const val WINDOW_X = 380 - SHADOW
            const val CARD_X = SHADOW
            const val WINDOW_Y = 352 - SHADOW
            const val CARD_Y = 440 - WINDOW_Y
            const val WINDOW_H = CARD_Y + CARD_H + SHADOW
            const val ABOVE_VOICE = -88f
            const val ENTER_DY = 12f
        }
    }

    /**
     * The level, 140 x 8, always filled from the left, gliding between steps on a spring that keeps its speed through the repeats of a held button.
     */
    private class Bar(c: android.content.Context) : View(c), Recolours {
        var dim = false
            set(v) { field = v; invalidate() }
        /** Where the boost begins (the firmware's top), 0..1: the fill above it is in the boost's tint. */
        var top = 1f
            set(v) { field = v; invalidate() }
        private var known = false
        private val glide = Spring(0f, 520f, 1f, 0.001f) { invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        fun to(f: Float) {
            if (!known || !isAttachedToWindow) { known = true; glide.snapTo(f); invalidate(); return }
            glide.animateTo(f)
        }

        override fun onLook() = invalidate()

        override fun hasOverlappingRendering() = false

        override fun onDetachedFromWindow() {
            glide.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            paint.color = Palette.track
            r.set(0f, 0f, w, h)
            c.drawRoundRect(r, h / 2, h / 2, paint)
            val f = glide.value.coerceIn(0f, 1f)
            if (f <= 0f) return
            if (!dim && f > top) { // the boost's part first, under the firmware's: one bar, two tints
                r.set(0f, 0f, (w * f).coerceAtLeast(h), h)
                paint.color = Palette.reach(Palette.warn, Palette.cardFlat, 3.0, Palette.dark)
                c.drawRoundRect(r, h / 2, h / 2, paint)
                r.set(0f, 0f, (w * top).coerceAtLeast(h), h)
                paint.color = Palette.accent
                c.save()
                c.clipRect(0f, 0f, w * top, h)
                c.drawRoundRect(0f, 0f, w * top + h, h, h / 2, h / 2, paint) // square where the two meet
                c.restore()
                return
            }
            r.set(0f, 0f, (w * f).coerceAtLeast(h), h)
            paint.color = if (dim) Palette.ink3 else Palette.accent
            c.drawRoundRect(r, h / 2, h / 2, paint)
        }

        companion object {
            const val W = 140
            const val H = 8
        }
    }
}
