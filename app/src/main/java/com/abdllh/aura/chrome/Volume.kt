package com.abdllh.aura.chrome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.abdllh.aura.R
import com.abdllh.aura.system.CarAudio
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Tones
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at

/**
 * The volume, hit 204 x 84, y 516..600, the same three views on every screen. Real volume through [CarAudio], a step number and never a percentage, left to right in every language. A press on minus or plus
 * takes its step at once, and held it repeats at 120 ms. The number takes no tap: a tap on a 48 px cell between minus and plus would mute by slip on a rough road; held 400 ms it mutes. The number changes in
 * place; the bar that shows a change is [com.abdllh.aura.system.VolumeHud]'s, the same for every way of changing it.
 */
internal class VolumeCluster(ctx: Context) : FrameLayout(ctx), Recolours {
    private val minus = IconButton(ctx, R.drawable.ic_minus, 34, 86, 84, centerDy = -2, centerDx = 7, token = { Palette.ink })
    private val plus = IconButton(ctx, R.drawable.ic_plus, 34, 70, 84, centerDy = -2, centerDx = 1, token = { Palette.ink })
    private val number = VolumeNumber(ctx) { toggleMute() }
    /** The firmware tells of a mute about 300 ms late: the word is there at once, and what the unit says counts again after this. */
    private var wantMuted = false
    private var wantUntil = 0L

    init {
        minus.contentDescription = ctx.getString(R.string.ctl_vol_down)
        plus.contentDescription = ctx.getString(R.string.ctl_vol_up)
        repeats(minus, -1)
        repeats(plus, +1)
        addView(minus, at(0, 0, 86, 84))
        addView(plus, at(134, 0, 70, 84))
        addView(number, at(78, 0, VolumeNumber.W, 84))
        // (the row's own surfaces lie under this: blank parts of the cluster are not targets of their own, its three hits fill it)
    }

    override fun onLook() = Unit

    override fun hasOverlappingRendering() = false

    private fun repeats(b: IconButton, dir: Int) {
        val again = object : Runnable {
            override fun run() {
                val before = CarAudio.level()
                CarAudio.stepLevel(dir)
                if (CarAudio.level() != before) b.postDelayed(this, REPEAT_MS)
            }
        }
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    Tones.tick(context)
                    CarAudio.stepLevel(dir)
                    b.postDelayed(again, Tokens2.MS_muteHold)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> b.removeCallbacks(again)
                MotionEvent.ACTION_MOVE -> {
                    val w = b.width * 0.2f
                    val h = b.height * 0.2f
                    if (e.x < -w || e.x > b.width + w || e.y < -h || e.y > b.height + h) b.removeCallbacks(again)
                }
            }
            false
        }
    }

    private fun toggleMute() {
        Tones.tick(context)
        val want = !CarAudio.muted()
        CarAudio.setMuted(want)
        wantMuted = want
        wantUntil = SystemClock.uptimeMillis() + MUTE_GRACE_MS
        removeCallbacks(resync)
        postDelayed(resync, MUTE_GRACE_MS + 50)
        sync()
    }

    private val resync = Runnable { sync() }

    /** What the unit says now, in place: the level (0..50 on his unit, the boost's part above the firmware's top in the warm tint). */
    fun sync() = number.set(CarAudio.level(), if (SystemClock.uptimeMillis() < wantUntil) wantMuted else CarAudio.muted(), CarAudio.boost() > 0)

    /** Debug builds: the mockups' numbers. */
    fun hold(v: Int, muted: Boolean) = number.set(v, muted, v > CarAudio.max())

    override fun onDetachedFromWindow() {
        removeCallbacks(resync)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val REPEAT_MS = 120L
        const val MUTE_GRACE_MS = 1000L
    }
}

/**
 * Speaker 24 over the number `volNum`, drawn in one view so a new number redraws these 48 x 84 and lays out nothing. Muted: `vol-mute` and the word in `redInk` on `redFill`. Its bounds are
 * wider than its hit (English's word is wider than the cell); a touch outside the hit goes to its neighbours.
 */
internal class VolumeNumber(ctx: Context, private val onHold: () -> Unit) : View(ctx), Recolours {
    private val speaker: Drawable = ctx.getDrawable(R.drawable.ic_vol)!!.mutate()
    private val off: Drawable = ctx.getDrawable(R.drawable.ic_vol_mute)!!.mutate()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        Fonts.tune(this)
        typeface = Fonts.of(Type.volNum)
        textSize = Type.volNum.sp
    }
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val word = ctx.getString(R.string.ctl_mute)
    private val gesture = PressGesture(this, PressGesture.ICON)
    private var value = 0
    private var text = "0"
    private var muted = false
    private var boosted = false
    private val hold = Runnable { onHold() }

    init {
        isClickable = true
        contentDescription = ctx.getString(R.string.ctl_mute)
        pivotX = W / 2f
        pivotY = 40f
        val left = (W - 24) / 2
        speaker.setBounds(left, 14, left + 24, 38)
        off.setBounds(left, 14, left + 24, 38)
        onLook()
    }

    fun set(v: Int, muted: Boolean, boosted: Boolean = false) {
        if (v == value && muted == this.muted && boosted == this.boosted) return
        value = v
        text = v.toString()
        this.muted = muted
        this.boosted = boosted
        invalidate()
    }

    override fun onLook() {
        speaker.setTint(Palette.ink)
        off.setTint(Palette.ink)
        invalidate()
    }

    override fun hasOverlappingRendering() = false

    override fun onDraw(c: Canvas) {
        val cx = W / 2f
        val y = 38 + Type.volNum.baseline
        if (muted) {
            off.draw(c)
            val half = maxOf(MUTE_MIN_W, p.measureText(word) + 4f) / 2f
            r.set(cx - half, 38f, cx + half, 66f)
            plate.color = Palette.redFill
            c.drawRoundRect(r, MUTE_R, MUTE_R, plate)
            p.color = Palette.redInk
            c.drawText(word, cx, y, p)
        } else {
            speaker.draw(c)
            // above the firmware's top the number is the boost's: the warm token, as dark as it must be to read on the row
            p.color = if (boosted) Palette.reach(Palette.warn, Palette.dock, 4.5, Palette.dark) else Palette.ink
            c.drawText(text, cx, y, p)
        }
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && (e.x < HIT_L || e.x >= HIT_L + HIT_W)) return false
        gesture.onTouch(e)
        return super.dispatchTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> postDelayed(hold, Tokens2.MS_muteHold)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(hold)
            MotionEvent.ACTION_MOVE -> {
                val w = HIT_W * 0.2f
                if (e.x < HIT_L - w || e.x > HIT_L + HIT_W + w || e.y < -height * 0.2f || e.y > height * 1.2f) removeCallbacks(hold)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hold)
        gesture.reset()
        super.onDetachedFromWindow()
    }

    companion object {
        const val W = 64
        private const val HIT_L = 8
        private const val HIT_W = 48
        private const val MUTE_MIN_W = 44f
        private const val MUTE_R = 6f
    }
}
