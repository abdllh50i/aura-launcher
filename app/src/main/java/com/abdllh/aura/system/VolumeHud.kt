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

/**
 * Aura's own volume display, in place of the stock floating bar (com.android.launcher/com.launcher.FloatBar).
 *
 * The stock bar pops up for every volume report of the firmware's setting service, Aura's own screen changes
 * included. Aura's shows only for changes Aura did not make: the steering-wheel, panel and CAN buttons (the firmware
 * announces each of those first with com.nwd.action.ACTION_KEY_VALUE 14 up / 15 down / 2 mute), a knob, another
 * app; a change from Aura's own screen ([CarAudio.changedByAura]) shows nothing. Navigation (15) and phone-call (16)
 * levels show while a button was just pressed.
 *
 * The stock bar is turned off by disabling its service component through the unit's root shell (once; it stays off
 * across restarts) — only when Aura may draw over other apps, which the same shell allows; otherwise the stock bar
 * stays. Turning the option off, the ROM's restore and Aura's kill switch ([release]) enable it again.
 */
object VolumeHud {
    private const val TAG = "AuraVolumeHud"
    private const val KEY = "volumeHud"
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

    /** The user's switch (on by default). */
    var enabled: Boolean
        get() = Prefs.raw.getBoolean(KEY, true)
        set(v) { Prefs.raw.edit().putBoolean(KEY, v).apply() }

    enum class Kind { MEDIA, NAV, PHONE }

    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        startedAt = SystemClock.elapsedRealtime()
        lastVolume = CarAudio.volume()
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
                    "com.nwd.ACTION_REQUEST_VLOUME_DISPLAY" -> if (enabled) show(Kind.MEDIA, CarAudio.volume(), CarAudio.muted())
                    DEBUG_KEY -> { // what the firmware does for a wheel button, on the emulator
                        keyAt = SystemClock.elapsedRealtime()
                        when (i.getStringExtra("key")) {
                            "up" -> CarAudio.step(1)
                            "down" -> CarAudio.step(-1)
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
        val v = CarAudio.volume()
        val m = CarAudio.muted()
        if (v != lastVolume || m != lastMute) {
            val now = SystemClock.elapsedRealtime()
            val byKey = now - keyAt < KEY_WINDOW_MS
            val fromScreen = CarAudio.changedByAura()
            if (enabled && (byKey || (!fromScreen && now - startedAt > QUIET_AFTER_START_MS))) show(Kind.MEDIA, v, m)
            lastVolume = v
            lastMute = m
        }
    }

    private val onParam: (Int, Int) -> Unit = { type, value ->
        if (enabled && (type == 15 || type == 16) && SystemClock.elapsedRealtime() - keyAt < KEY_WINDOW_MS) {
            show(if (type == 16) Kind.PHONE else Kind.NAV, value, false)
        }
    }

    // ------------------------------------------------------------------------------------------ the stock bar
    /** Brings the unit in line with the switch; [done] gets true when it worked (on a worker thread). */
    fun apply(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        if (!Device.isNwd) { done?.invoke(true); return }
        val c = ctx.applicationContext
        worker.execute {
            val ok = try {
                // decided here, in order with release(): the kill switch may have been set meanwhile
                if (enabled && !auraOff()) takeOver(c) else giveBack(c)
            } catch (t: Throwable) {
                Log.w(TAG, "apply: $t")
                false
            }
            done?.invoke(ok)
        }
    }

    /** Aura stops being the home app (kill switch): the stock bar comes back. */
    fun release(ctx: Context) {
        if (!Device.isNwd) return
        val c = ctx.applicationContext
        worker.execute { try { giveBack(c) } catch (t: Throwable) { Log.w(TAG, "release: $t") } }
    }

    private fun takeOver(c: Context): Boolean {
        if (!Settings.canDrawOverlays(c)) LocalAdb.run("appops set ${c.packageName} SYSTEM_ALERT_WINDOW allow")
        if (!Settings.canDrawOverlays(c)) return false // no window of its own: the stock bar stays
        if (stockOn(c) != true) return true
        Prefs.raw.edit().putString(APPLIED, "on").apply()
        val r = LocalAdb.run("pm disable $STOCK")
        Log.i(TAG, "stock bar off: ${r.ok} ${r.output}")
        if (stockOn(c) == true) return false
        restartStockHelpers(c)
        return true
    }

    private fun giveBack(c: Context): Boolean {
        if (Prefs.raw.getString(APPLIED, "") != "on") return true // never touched
        if (stockOn(c) == false) {
            val r = LocalAdb.run("pm enable $STOCK && am startservice -n $STOCK")
            Log.i(TAG, "stock bar back: ${r.ok} ${r.output}")
            if (stockOn(c) == false) return false
            restartStockHelpers(c)
        }
        Prefs.raw.edit().putString(APPLIED, "off").apply()
        return true
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
        // (the row is on screen and shows it itself, with no overlay over what he is handling: SPEC.md, "Volume")
        if (kind == Kind.MEDIA && com.abdllh.aura.chrome.Chrome.rowOnScreen()) return
        if (com.abdllh.aura.phone.OverlayOrder.volumeWaits()) return
        var v = hud
        if (v == null || v.lang != Prefs.lang) {
            v?.window?.remove() // (the language changed: built again)
            v = HudView(LocaleHelper.wrap(c)).also { hud = it }
        }
        v.bind(kind, value, CarAudio.max(), muted)
        if (v.window.attached) v.stay() // pressed again while it was fading out
        else if (v.window.add()) v.enter() else return
        main.removeCallbacks(hide)
        main.postDelayed(hide, Tokens2.MS_volumeHold)
    }

    // only the view that faded out is removed (a language change may have replaced it meanwhile)
    private val hide = Runnable { val v = hud; v?.leave { if (hud === v) v?.window?.remove() } }

    /**
     * Over another app, where there is no row: 364 x 80 at (12, 426), the speaker, a level bar and the car's own step number (the one its buttons and the dock show), left to right in every
     * language, not touchable (SPEC.md section 5). A window of its own with room for the shadow, cut at the screen's left edge.
     */
    private class HudView(c: android.content.Context) {
        val lang = Prefs.lang
        val window = OverlayWindow(c, "AuraVolume", 0, 382, 420, 168, touchable = false)
        private val card = FrameLayout(c)
        private val icon = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        private val bar = Bar(c)
        private val value: AText = c.label(Type.hudNum, { Palette.ink }, gravity = Gravity.CENTER)
        private val word: AText = c.label(Type.button, { Palette.redInk }, gravity = Gravity.CENTER).apply { setText(R.string.ctl_mute); visibility = View.INVISIBLE }
        private var iconMuted = false
        private var leaving = false

        init {
            card.addView(SurfaceCard(c, Surface.OVERLAY), Surface.OVERLAY.at(CARD_X, CARD_Y, 364, 80))
            card.addView(icon, at(CARD_X + 18, CARD_Y + 25, 30, 30))
            card.addView(bar, at(CARD_X + 62, CARD_Y + 37, Bar.W, Bar.H))
            value.textDirection = View.TEXT_DIRECTION_LTR
            card.addView(value, Type.hudNum.box(CARD_X + 296, CARD_Y + 18, 56))
            card.addView(word, Type.button.box(CARD_X + 296, CARD_Y + 24, 56))
            window.root.addView(card, FrameLayout.LayoutParams(420, 168))
            icon.look { setColorFilter(if (iconMuted) Palette.ink3 else Palette.ink) }
        }

        fun bind(kind: Kind, level: Int, max: Int, muted: Boolean) {
            window.syncLook()
            val m = max.coerceAtLeast(1)
            val f = level.coerceIn(0, m).toFloat() / m
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
            bar.to(f)
            if (muted) {
                value.visibility = View.INVISIBLE
                word.visibility = View.VISIBLE
            } else {
                word.visibility = View.INVISIBLE
                value.visibility = View.VISIBLE
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
            /** Where the visible card begins in this window: x 12, y 426 on the screen. */
            const val CARD_X = 12
            const val CARD_Y = 44
            const val ENTER_DY = 12f
        }
    }

    /**
     * The level, 226 x 6, always filled from the left, gliding between steps on a spring that keeps its speed through the repeats of a held button.
     */
    private class Bar(c: android.content.Context) : View(c), Recolours {
        var dim = false
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
            r.set(0f, 0f, (w * f).coerceAtLeast(h), h)
            paint.color = if (dim) Palette.ink3 else Palette.accent
            c.drawRoundRect(r, h / 2, h / 2, paint)
        }

        companion object {
            const val W = 226
            const val H = 6
        }
    }
}
