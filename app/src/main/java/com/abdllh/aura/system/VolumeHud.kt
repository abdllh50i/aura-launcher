package com.abdllh.aura.system

import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.lp
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
                y = 10.dp
                title = "AuraVolume"
            }
            try {
                (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
                attached = true
                v.enter()
            } catch (t: Throwable) {
                Log.w(TAG, "show: $t")
                return
            }
        }
        main.removeCallbacks(hide)
        main.postDelayed(hide, SHOW_MS)
    }

    // only the view that faded out is removed (a theme change may have replaced it meanwhile)
    private val hide = Runnable { val v = hud; v?.leave { if (hud === v) detach() } }

    private fun detach() {
        val v = hud ?: return
        if (!attached) return
        attached = false
        try { (v.context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) { }
    }

    /** A floating pill at the top: icon, level bar, number. Room around it for the shadow. */
    private class HudView(c: Context) : FrameLayout(c) {
        val dark = Palette.dark
        val accent = Palette.accent
        val lang = Prefs.lang
        private val card = LinearLayout(c)
        private val iconBg = FrameLayout(c)
        private val icon = ImageView(c)
        private val bar = Bar(c)
        private val value: AText = AText(c)

        init {
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            card.setPadding(14.dp, 0, 18.dp, 0)
            card.background = Shapes.rect(if (dark) 0xF2202329.toInt() else 0xF7FFFFFF.toInt(), 37f,
                if (dark) 0x1AFFFFFF else 0x14000000)
            card.elevation = 10.dp.toFloat()
            iconBg.background = Shapes.oval(Palette.withAlpha(accent, if (dark) 0.20f else 0.14f))
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            iconBg.addView(icon, flp(26.dp, 26.dp, Gravity.CENTER))
            card.addView(iconBg, lp(48.dp, 48.dp))
            card.addView(bar, lp(0, 12.dp, 1f).apply { marginStart = 18.dp; marginEnd = 16.dp })
            value.textSize = 24f
            value.weight = Fonts.MEDIUM
            value.setTextColor(if (dark) 0xFFFFFFFF.toInt() else 0xFF111317.toInt())
            value.gravity = Gravity.CENTER
            value.maxLines = 1
            card.addView(value, lp(56.dp, LayoutParams.WRAP_CONTENT))
            addView(card, flp(470.dp, 74.dp).apply { setMargins(16.dp, 8.dp, 16.dp, 18.dp) })
            bar.track = if (dark) 0x26FFFFFF else 0x1A000000
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
            icon.setColorFilter(if (muted) Palette.text3 else accent)
            bar.fill = if (muted) Palette.text3 else accent
            bar.dim = muted
            bar.to(f)
            if (muted) {
                value.textSize = 15f
                value.text = context.getString(R.string.vol_muted)
            } else {
                value.textSize = 24f
                value.text = level.coerceIn(0, m).toString()
            }
        }

        fun enter() {
            card.alpha = 0f
            card.translationY = (-14).dp.toFloat()
            card.scaleX = 0.96f
            card.scaleY = 0.96f
            card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(DecelerateInterpolator()).start()
        }

        fun stay() {
            card.animate().cancel() // a cancelled fade-out does not run its end action (the removal)
            card.alpha = 1f
            card.translationY = 0f
            card.scaleX = 1f
            card.scaleY = 1f
        }

        fun leave(done: () -> Unit) {
            card.animate().alpha(0f).translationY((-10).dp.toFloat()).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).withEndAction(done).start()
        }
    }

    /** The level: a rounded track filled from the start side (the right in Arabic), moving smoothly between steps. */
    private class Bar(c: Context) : View(c) {
        var track = 0x26FFFFFF
        var fill = Palette.accent
            set(v) { field = v; invalidate() }
        var dim = false
            set(v) { field = v; invalidate() }
        private var shown = -1f
        private var anim: ValueAnimator? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        fun to(f: Float) {
            anim?.cancel()
            if (shown < 0f || !isAttachedToWindow) { shown = f; invalidate(); return }
            anim = ValueAnimator.ofFloat(shown, f).apply {
                duration = 140
                interpolator = DecelerateInterpolator()
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            paint.color = track
            r.set(0f, 0f, w, h)
            c.drawRoundRect(r, h / 2, h / 2, paint)
            val f = shown.coerceIn(0f, 1f)
            if (f <= 0f) return
            val len = (w * f).coerceAtLeast(h)
            if (layoutDirection == LAYOUT_DIRECTION_RTL) r.set(w - len, 0f, w, h) else r.set(0f, 0f, len, h)
            paint.color = fill
            paint.alpha = if (dim) 110 else 255
            c.drawRoundRect(r, h / 2, h / 2, paint)
        }
    }
}
