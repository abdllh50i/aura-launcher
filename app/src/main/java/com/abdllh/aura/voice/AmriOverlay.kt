package com.abdllh.aura.voice

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp

/**
 * The assistant on screen, over any app: a pill above the dock with a microphone that breathes with the voice
 * ("تفضّل…", then what it heard), then the answer. Tapping it cancels.
 */
internal object AmriOverlay {
    enum class Mode { LISTEN, DONE, ERROR }

    private val main = Handler(Looper.getMainLooper())
    private var pill: Pill? = null
    private var attached = false
    private val hideLater = Runnable { hide() }

    fun show(c: Context, mode: Mode, text: String, hideAfterMs: Int = 0, sub: String? = null) {
        val app = c.applicationContext
        if (!Settings.canDrawOverlays(app)) return
        if (Amri.reversing) { hide(); return } // nothing over the reversing camera
        var p = pill
        if (p == null || p.dark != Palette.dark) {
            remove()
            p = Pill(app)
            pill = p
        } else if (attached) {
            // shown again while it was fading out: it stays (its fade-out, and the removal at its end, are cancelled)
            p.animate().cancel()
            p.alpha = 1f
            p.translationY = 0f
        }
        p.set(mode, text, sub)
        if (!attached) {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 104.dp // above the dock
            }
            try {
                (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(p, lp)
                attached = true
                p.alpha = 0f
                p.translationY = 24.dp.toFloat()
                p.animate().alpha(1f).translationY(0f).setDuration(220).start()
            } catch (_: Throwable) {
                return
            }
        }
        main.removeCallbacks(hideLater)
        if (hideAfterMs > 0) main.postDelayed(hideLater, hideAfterMs.toLong())
    }

    /** What the recogniser has heard so far. */
    fun text(t: String) { pill?.title?.text = t }

    /** The voice's level, 0..1, while listening. */
    fun level(l: Float) { pill?.level(l) }

    fun hide() {
        main.removeCallbacks(hideLater)
        val p = pill ?: return
        if (!attached) return
        p.animate().alpha(0f).translationY(16.dp.toFloat()).setDuration(200).withEndAction { if (pill === p) remove() }.start()
    }

    private fun remove() {
        val p = pill ?: return
        if (attached) {
            try { (p.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(p) } catch (_: Throwable) { }
        }
        attached = false
        pill = null
    }

    private class Pill(c: Context) : LinearLayout(c) {
        val dark = Palette.dark
        private val iconBg = FrameLayout(c)
        private val icon = ImageView(c)
        val title: AText = c.label(20f, Palette.text, Fonts.MEDIUM, lines = 2)
        private val sub: AText = c.label(15f, Palette.text2, Fonts.REGULAR, lines = 1)
        private var shown = 0f

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // the app's language (its own setting, not the system's)
            layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault())
            setPadding(12.dp, 12.dp, 28.dp, 12.dp)
            background = Shapes.rect(Palette.sheet, 40f, if (Palette.dark) Palette.stroke else 0)
            elevation = 14.dp.toFloat()
            iconBg.addView(icon, flp(28.dp, 28.dp, Gravity.CENTER))
            addView(iconBg, lp(56.dp, 56.dp))
            val texts = LinearLayout(c).apply { orientation = VERTICAL }
            texts.addView(title, lp(WRAP, WRAP))
            texts.addView(sub, lp(WRAP, WRAP))
            addView(texts, lp(WRAP, WRAP).apply { marginStart = 16.dp })
            minimumWidth = 320.dp
            isClickable = true
            setOnClickListener { Amri.cancel(context) }
        }

        fun set(mode: Mode, text: String, subText: String?) {
            val tint = when (mode) {
                Mode.LISTEN -> Palette.accent
                Mode.DONE -> Palette.accent
                Mode.ERROR -> Palette.warn
            }
            icon.setImageResource(when (mode) { Mode.LISTEN -> R.drawable.ic_mic; Mode.DONE -> R.drawable.ic_check; Mode.ERROR -> R.drawable.ic_alert })
            icon.setColorFilter(if (mode == Mode.LISTEN) Palette.onColor(tint) else tint)
            iconBg.background = if (mode == Mode.LISTEN) Shapes.oval(tint) else Shapes.oval(Palette.withAlpha(tint, if (dark) 0.2f else 0.14f))
            title.text = text
            sub.text = subText.orEmpty()
            sub.visibility = if (subText.isNullOrBlank()) GONE else VISIBLE
            if (mode != Mode.LISTEN) level(0f)
        }

        fun level(l: Float) {
            shown += (l - shown) * 0.5f
            val s = 1f + 0.22f * shown
            iconBg.scaleX = s
            iconBg.scaleY = s
        }
    }
}
