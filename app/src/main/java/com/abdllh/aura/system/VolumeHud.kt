package com.abdllh.aura.system

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
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
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lift
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.textRole
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp
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
    private const val SHOW_MS = 2200L
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
    private var attached = false
    private var params: WindowManager.LayoutParams? = null

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
        var v = hud
        if (v == null || v.dark != Palette.dark || v.accent != Palette.accent || v.lang != Prefs.lang) {
            if (v != null && attached) { v.stay(); detach() } // theme or language changed: built again
            v = HudView(LocaleHelper.wrap(c)).also { hud = it }
        }
        v.bind(kind, value, CarAudio.max(), muted)
        if (attached) v.stay() // pressed again while it was fading out
        if (!attached) {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = top()
                title = "AuraVolume"
            }
            try {
                (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
                attached = true
                params = lp
                v.enter()
            } catch (t: Throwable) {
                Log.w(TAG, "show: $t")
                return
            }
        }
        main.removeCallbacks(hide)
        main.postDelayed(hide, SHOW_MS)
    }

    /**
     * Where the display goes: at the top, or under the call card while there is one. The phone's volume is changed
     * exactly while a call is up, and over the card it hid the caller's name and buttons (with taps going through it
     * to a card that could not be seen).
     */
    private fun top(): Int {
        val call = com.abdllh.aura.phone.CallBanner.bottom()
        return if (call > 0) call - HudView.TOP_DP.dp + Space.S.dp else Space.XXS.dp
    }

    /** A call card came or went: the display, if it is up, moves to its place (one move of a small window). */
    fun reposition() {
        main.post {
            val v = hud ?: return@post
            val lp = params ?: return@post
            if (!attached) return@post
            val y = top()
            if (lp.y == y) return@post
            lp.y = y
            try { (v.context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(v, lp) } catch (_: Throwable) { }
        }
    }

    // only the view that faded out is removed (a theme change may have replaced it meanwhile)
    private val hide = Runnable { val v = hud; v?.leave { if (hud === v) detach() } }

    private fun detach() {
        val v = hud ?: return
        if (!attached) return
        attached = false
        params = null
        try { (v.context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) { }
    }

    /**
     * A floating pill at the top: icon, level bar, the car's own step number (the one its buttons and the dock show).
     * Room around it for the shadow and, above, for its entrance.
     */
    private class HudView(c: Context) : FrameLayout(c) {
        val dark = Palette.dark
        val accent = Palette.accent
        val lang = Prefs.lang
        private val card = LinearLayout(c)
        private val iconBg = FrameLayout(c)
        private val icon = ImageView(c)
        private val bar = Bar(c)
        private val value: AText = c.label(Type.HEADLINE, Palette.text, gravity = Gravity.CENTER)
        private var leaving = false

        init {
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            card.setPaddingRelative(Space.M.dp, 0, Space.L.dp, 0)
            // the overlays' own surface (the call card and the assistant's pill have it too)
            card.background = Shapes.overlay()
            card.lift(Elev.OVERLAY, overMap = true)
            iconBg.background = Shapes.oval(Palette.accentSoft())
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            iconBg.addView(icon, flp(26.dp, 26.dp, Gravity.CENTER))
            card.addView(iconBg, lp(48.dp, 48.dp))
            card.addView(bar, lp(0, 12.dp, 1f).apply { marginStart = Space.L.dp + 2.dp; marginEnd = Space.L.dp })
            value.fontFeatureSettings = "tnum" // (a number that changes as the button is held does not jiggle)
            card.addView(value, lp(56.dp, LayoutParams.WRAP_CONTENT))
            addView(card, flp(470.dp, 74.dp).apply { setMargins(Space.L.dp, TOP_DP.dp, Space.L.dp, 18.dp) })
        }

        fun bind(kind: Kind, level: Int, max: Int, muted: Boolean) {
            val m = max.coerceAtLeast(1)
            val f = level.coerceIn(0, m).toFloat() / m
            icon.setImageResource(when {
                kind == Kind.NAV -> R.drawable.ic_nav
                kind == Kind.PHONE -> R.drawable.ic_phone
                muted || level <= 0 -> R.drawable.ic_volume_off
                f < 0.4f -> R.drawable.ic_volume_low
                else -> R.drawable.ic_volume
            })
            icon.setColorFilter(if (muted) Palette.text3 else Palette.accentFg)
            bar.fill = if (muted) Palette.text3 else accent
            bar.dim = muted
            bar.to(f)
            if (muted) {
                value.textRole(Type.FOOTNOTE)
                value.text = context.getString(R.string.vol_muted)
            } else {
                value.textRole(Type.HEADLINE)
                value.text = level.coerceIn(0, m).toString()
            }
        }

        fun enter() {
            leaving = false
            card.enter(dy = -ENTER_DP, scaleFrom = 0.96f)
        }

        /**
         * Shown again while it is up: a held wheel button repeats every few tenths of a second, and the entrance plays
         * on through the repeats (until 1.12 the first repeat cut it to its end). Shown again while it was fading
         * out, it turns round from where it is (a cancelled fade-out does not run its end action, the removal).
         */
        fun stay() {
            if (!leaving) return
            leaving = false
            card.enter(dy = -ENTER_DP, scaleFrom = 0.96f, dur = Motion.SHORT)
        }

        fun leave(done: () -> Unit) {
            leaving = true
            card.exit(dy = -10f, end = done)
        }

        companion object {
            /** The card's distance from the window's top: room for the way it comes in. */
            const val TOP_DP = 16
            const val ENTER_DP = 14f
        }
    }

    /**
     * The level: a rounded track filled from the start side (the right in Arabic), gliding between steps on a spring
     * that keeps its speed through the repeats of a held button (an animation started anew at each step stuttered).
     */
    private class Bar(c: Context) : View(c) {
        var fill = Palette.accent
            set(v) { field = v; invalidate() }
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
            val len = (w * f).coerceAtLeast(h)
            if (layoutDirection == LAYOUT_DIRECTION_RTL) r.set(w - len, 0f, w, h) else r.set(0f, 0f, len, h)
            paint.color = fill
            paint.alpha = if (dim) 110 else 255
            c.drawRoundRect(r, h / 2, h / 2, paint)
        }
    }
}
