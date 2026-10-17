package com.abdllh.aura.voice

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lift
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp

/**
 * The assistant on screen, over any app: a pill above the dock with a microphone that breathes with the voice
 * ("تفضّل…", then what it heard), then the answer. Tapping it cancels.
 * The pill is as wide as it will ever be from the start: what the recogniser hears comes several times a second, and a
 * pill that grew with it (until 1.12) moved both its edges and had the window manager resize its window each time.
 */
internal object AmriOverlay {
    enum class Mode { LISTEN, DONE, ERROR }

    /** The pill's width, and the room round it in its window (for its shadow, and below for its entrance). */
    private const val WIDTH_DP = 560
    private const val SIDE_DP = 12
    private const val TOP_DP = 6
    private const val BOTTOM_DP = 16
    /** Where the pill's lower edge is, above the screen's bottom: over the dock. */
    private const val ABOVE_DP = 104

    private val main = Handler(Looper.getMainLooper())
    private var frame: Frame? = null
    private var attached = false
    private val hideLater = Runnable { hide() }

    fun show(c: Context, mode: Mode, text: String, hideAfterMs: Int = 0, sub: String? = null) {
        val app = c.applicationContext
        if (!Settings.canDrawOverlays(app)) return
        if (Amri.reversing) { hide(); return } // nothing over the reversing camera
        var f = frame
        if (f == null || f.dark != Palette.dark || f.accent != Palette.accent) {
            remove()
            f = Frame(app)
            frame = f
        } else if (attached) {
            f.stay()
        }
        f.pill.set(mode, text, sub)
        if (!attached) {
            val w = minOf(WIDTH_DP.dp, app.resources.displayMetrics.widthPixels - 2 * Space.L.dp)
            val lp = WindowManager.LayoutParams(
                w + 2 * SIDE_DP.dp, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (ABOVE_DP - BOTTOM_DP).dp
            }
            try {
                (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(f, lp)
                attached = true
                f.enter()
            } catch (_: Throwable) {
                return
            }
        }
        main.removeCallbacks(hideLater)
        if (hideAfterMs > 0) main.postDelayed(hideLater, hideAfterMs.toLong())
    }

    /** What the recogniser has heard so far. */
    fun text(t: String) { frame?.pill?.title?.text = t }

    /** The voice's level, 0..1, while listening. */
    fun level(l: Float) { frame?.pill?.level(l) }

    fun hide() {
        main.removeCallbacks(hideLater)
        val f = frame ?: return
        if (!attached) return
        f.leave { if (frame === f) remove() }
    }

    private fun remove() {
        val f = frame ?: return
        f.pill.stop()
        if (attached) {
            try { (f.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(f) } catch (_: Throwable) { }
        }
        attached = false
        frame = null
    }

    /** The window's content: the pill, with the little room it needs round it. */
    private class Frame(c: Context) : FrameLayout(c) {
        val dark = Palette.dark
        val accent = Palette.accent
        val pill = Pill(c)
        private var leaving = false

        init {
            clipChildren = false
            addView(pill, flp(MATCH, WRAP).apply { setMargins(SIDE_DP.dp, TOP_DP.dp, SIDE_DP.dp, BOTTOM_DP.dp) })
        }

        fun enter() {
            leaving = false
            pill.enter(dy = 12f, scaleFrom = 0.96f)
        }

        /**
         * Shown again while it is up (it heard more, it answers): the entrance plays on. Shown again while it was
         * fading out, it turns round from where it is (until 1.12 both cut to the end at once).
         */
        fun stay() {
            if (!leaving) return
            leaving = false
            pill.enter(dy = 12f, scaleFrom = 0.96f, dur = Motion.SHORT)
        }

        fun leave(done: () -> Unit) {
            leaving = true
            pill.exit(dy = 10f, end = done)
        }
    }

    private class Pill(c: Context) : LinearLayout(c) {
        private val iconBg = FrameLayout(c)
        private val icon = ImageView(c)
        val title: AText = c.label(Type.TITLE, Palette.text, lines = 2).apply { ellipsize = TextUtils.TruncateAt.END }
        private val sub: AText = c.label(Type.FOOTNOTE, Palette.text2).apply { ellipsize = TextUtils.TruncateAt.END }
        private var mode: Mode? = null
        // The microphone's breath: the level comes 10 to 20 times a second, and set straight it moved in steps; on a
        // soft spring it swells and settles between them.
        private val breath = Spring(0f, 380f, 0.6f, 0.002f) { v ->
            iconBg.scaleX = 1f + 0.22f * v
            iconBg.scaleY = 1f + 0.22f * v
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // the app's language (its own setting, not the system's)
            layoutDirection = TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault())
            setPaddingRelative(Space.M.dp, Space.M.dp, Space.XL.dp + Space.XS.dp, Space.M.dp)
            // the overlays' own surface (the volume display and the call card have it too)
            background = Shapes.overlay()
            lift(Elev.OVERLAY, overMap = true)
            iconBg.addView(icon, flp(28.dp, 28.dp, Gravity.CENTER))
            addView(iconBg, lp(Touch.MIN.dp, Touch.MIN.dp))
            val texts = LinearLayout(c).apply { orientation = VERTICAL }
            texts.addView(title, lp(MATCH, WRAP))
            texts.addView(sub, lp(MATCH, WRAP))
            addView(texts, lp(0, WRAP, 1f).apply { marginStart = Space.L.dp })
            isClickable = true
            setOnClickListener { Amri.cancel(context) }
        }

        fun set(m: Mode, text: String, subText: String?) {
            val tint = if (m == Mode.ERROR) Palette.warn else Palette.accent
            if (m != mode) {
                icon.setImageResource(when (m) { Mode.LISTEN -> R.drawable.ic_mic; Mode.DONE -> R.drawable.ic_check; Mode.ERROR -> R.drawable.ic_alert })
                icon.setColorFilter(when (m) {
                    Mode.LISTEN -> Palette.onColor(tint)
                    Mode.DONE -> Palette.accentFg
                    Mode.ERROR -> tint
                })
                iconBg.background = if (m == Mode.LISTEN) Shapes.oval(tint) else Shapes.oval(Palette.withAlpha(tint, if (Palette.dark) 0.2f else 0.14f))
                // (a new mode's icon comes in, not just swapped; not on the first showing, which comes in whole)
                if (mode != null) icon.enter(scaleFrom = 0.7f, dur = Motion.SHORT)
                mode = m
            }
            title.text = text
            sub.text = subText.orEmpty()
            sub.visibility = if (subText.isNullOrBlank()) GONE else VISIBLE
            // (the tick or the alert at rest at its own size, not left swollen by the last word heard)
            if (m != Mode.LISTEN) breath.animateTo(0f)
        }

        fun level(l: Float) {
            if (mode == Mode.LISTEN) breath.animateTo(l.coerceIn(0f, 1f))
        }

        fun stop() = breath.cancel()
    }
}
