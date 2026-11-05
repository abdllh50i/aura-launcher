package com.abdllh.aura.chrome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Surfaces
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.glyph
import com.abdllh.aura.ui.plateView

/**
 * The voice button, centred on (240, 556), hit 76 x 84 at x 204..280, the same place on every screen (SPEC.md sections 1 and 5). Two faces in alpha, [face]: a quiet plate with an ink mic on Home,
 * the blue disc with a white one elsewhere. The disc's glow is wider than the hit: the view's bounds are the bitmap's, and a touch outside the hit goes to the volume's plus beside it.
 * Listening: one 3 px ring round it, scaled by the voice's level at up to 15 fps and only while listening.
 */
internal class VoiceButton(ctx: Context, onTap: () -> Unit) : FrameLayout(ctx) {
    private val ring = Ring(ctx)
    private val plate = ctx.plateView(Tokens2.R_inner) { Palette.fill }
    private val mic = ImageView(ctx).apply { setImageResource(R.drawable.ic_mic) }.glyph { Palette.ink }
    private val disc = ImageView(ctx).apply { setImageBitmap(Surfaces.res(ctx, R.drawable.voice_disc)) }
    private val micOn = ImageView(ctx).apply { setImageResource(R.drawable.ic_mic) }.glyph { Palette.onAccent }
    private val gesture = PressGesture(this, PressGesture.ICON)
    private var levelAt = 0L

    /** 0: the plate. 1: the disc. */
    var face = 0f
        set(v) {
            field = v
            show(plate, 1f - v)
            show(mic, 1f - v)
            show(disc, v)
            show(micOn, v)
        }

    init {
        isClickable = true
        contentDescription = ctx.getString(R.string.set_voice)
        setOnClickListener { onTap() }
        addView(disc, at(0, 0, SIZE, SIZE))
        addView(plate, at(22, 24, 64, 60))
        addView(mic, at(39, 39, 30, 30))
        addView(micOn, at(38, 38, 32, 32))
        ring.visibility = INVISIBLE
        ring.alpha = 0f
        addView(ring, at(15, 15, Ring.SIZE, Ring.SIZE))
        face = 0f
    }

    private fun show(v: View, a: Float) {
        v.alpha = a
        val want = if (a > 0f) VISIBLE else INVISIBLE
        if (v.visibility != want) v.visibility = want
    }

    override fun hasOverlappingRendering() = false

    /** The assistant listens, or stops. */
    fun listening(on: Boolean) {
        if (on) {
            ring.visibility = VISIBLE
            ring.scaleX = 1f
            ring.scaleY = 1f
            ring.fadeTo(RING_ALPHA, Tokens2.MS_swap, Motion.MOVE)
        } else ring.fadeTo(0f, Tokens2.MS_swap, Motion.MOVE) { if (ring.alpha == 0f) ring.visibility = INVISIBLE }
    }

    /** The voice's level, 0..1, from whoever hears it; more often than 15 times a second is not drawn. */
    fun level(l: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - levelAt < LEVEL_MS || ring.visibility != VISIBLE) return
        levelAt = now
        val s = 1f + LEVEL_GROW * l.coerceIn(0f, 1f)
        ring.scaleX = s
        ring.scaleY = s
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && (e.x < HIT_L || e.x >= HIT_L + HIT_W || e.y < HIT_T)) return false
        gesture.onTouch(e)
        return super.dispatchTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = gesture.tolerant(e) { super.onTouchEvent(it) }

    override fun onDetachedFromWindow() {
        gesture.reset()
        super.onDetachedFromWindow()
    }

    private class Ring(ctx: Context) : View(ctx), Recolours {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = STROKE }

        override fun onLook() = invalidate()

        override fun hasOverlappingRendering() = false

        override fun onDraw(c: Canvas) {
            p.color = Palette.accentInk
            c.drawCircle(SIZE / 2f, SIZE / 2f, (SIZE - STROKE) / 2f, p)
        }

        companion object {
            const val SIZE = 78
            const val STROKE = 3f
        }
    }

    companion object {
        /** The disc's bitmap: x 186..294, y 502..610, and the screen ends at 600. */
        const val SIZE = 108
        private const val HIT_L = 18
        private const val HIT_W = 76
        private const val HIT_T = 14
        private const val RING_ALPHA = 0.7f
        private const val LEVEL_MS = 66L
        private const val LEVEL_GROW = 0.1f
    }
}
