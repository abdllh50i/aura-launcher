package com.abdllh.aura.chrome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.abdllh.aura.R
import com.abdllh.aura.system.CarAudio
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Tones
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.look

/**
 * The volume, hit 204 x 84, y 516..600, the same three views on every screen and above both rows, so it never fades (SPEC.md sections 1 and 5). Real volume through [CarAudio], a
 * step number and never a percentage, left to right in every language. A press on minus or plus takes its step at once, and held it repeats at 120 ms. The number takes no tap: a tap on
 * a 48 px cell between minus and plus would mute by slip on a rough road; held 400 ms it mutes. The number changes in place, and a 4 px bar shows for 2.2 s after a change.
 */
internal class VolumeCluster(ctx: Context) : FrameLayout(ctx), Recolours {
    private val minus = IconButton(ctx, R.drawable.ic_minus, 34, 86, 84, centerDy = -2, centerDx = 7, token = { Palette.ink })
    private val plus = IconButton(ctx, R.drawable.ic_plus, 34, 70, 84, centerDy = -2, centerDx = 1, token = { Palette.ink })
    private val number = VolumeNumber(ctx) { toggleMute() }
    private val bar = VolumeBar(ctx)
    /** The last level shown unmuted; a mute going on or off is no change of level, and the bar stays down. */
    private var level = -1
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
        addView(bar, at(24, 64, VolumeBar.W, 4))
        // (the row's own surfaces lie under this: blank parts of the cluster are not targets of their own, its three hits fill it)
    }

    override fun onLook() = Unit

    override fun hasOverlappingRendering() = false

    private fun repeats(b: IconButton, dir: Int) {
        val again = object : Runnable {
            override fun run() {
                val before = CarAudio.volume()
                CarAudio.step(dir)
                if (CarAudio.volume() != before) b.postDelayed(this, REPEAT_MS)
            }
        }
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    Tones.tick(context)
                    CarAudio.step(dir)
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
        sync(false)
    }

    private val resync = Runnable { sync() }

    /** What the unit says now: the number in place; a new level shows the bar, unless [bar] says this is only a look at it (the window coming back). */
    fun sync(showBar: Boolean = true) {
        val muted = if (SystemClock.uptimeMillis() < wantUntil) wantMuted else CarAudio.muted()
        val v = CarAudio.volume()
        if (muted) {
            number.set(if (level >= 0) level else v, true)
            bar.hideNow()
            return
        }
        number.set(v, false)
        if (v != level) {
            val first = level < 0
            level = v
            if (showBar && !first) bar.show(v.toFloat() / CarAudio.max().coerceAtLeast(1))
        }
    }

    /** Debug builds: the mockups' numbers. */
    fun hold(v: Int, muted: Boolean, fraction: Float) {
        level = v
        number.set(v, muted)
        if (fraction >= 0f) bar.pin(fraction) else bar.hideNow()
    }

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

    fun set(v: Int, muted: Boolean) {
        if (v == value && muted == this.muted) return
        value = v
        text = v.toString()
        this.muted = muted
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
            p.color = Palette.ink
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

/**
 * The 4 px bar inside the pill, x 24..188, y 580..584: `track` with an `ink` fill. Only transforms: the fill is the whole bar's width and slides under the rounded clip, its way on spring
 * 520/1, its alpha in 120 and out 140 after 2200 ms. Left to right always.
 */
internal class VolumeBar(ctx: Context) : FrameLayout(ctx) {
    private val fill = object : View(ctx) { override fun hasOverlappingRendering() = false }.look { setBackgroundColor(Palette.ink) }
    private val glide = Spring(0f, Tokens2.SPRING_volumeBar[0], Tokens2.SPRING_volumeBar[1], restDelta = 0.002f) { f ->
        fill.translationX = -(1f - f) * W
    }
    private var up = false
    private val down = Runnable {
        up = false
        fadeTo(0f, Tokens2.MS_volumeOut, Motion.EXIT) { if (!up) visibility = INVISIBLE }
    }

    init {
        look { setBackgroundColor(Palette.track) }
        addView(fill, FrameLayout.LayoutParams(W, H))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, W, H, H / 2f)
        }
        clipToOutline = true
        alpha = 0f
        visibility = INVISIBLE
    }

    override fun hasOverlappingRendering() = false

    fun show(f: Float) {
        if (up || visibility == VISIBLE) glide.animateTo(f) else glide.snapTo(f)
        visibility = VISIBLE
        up = true
        fadeTo(1f, Tokens2.MS_volumeIn, Motion.MOVE)
        removeCallbacks(down)
        postDelayed(down, Tokens2.MS_volumeHold)
    }

    fun hideNow() {
        removeCallbacks(down)
        if (!up && visibility != VISIBLE) return
        up = false
        animate().cancel()
        alpha = 0f
        visibility = INVISIBLE
    }

    /** Debug builds: there and stays. */
    fun pin(f: Float) {
        removeCallbacks(down)
        animate().cancel()
        glide.snapTo(f)
        visibility = VISIBLE
        alpha = 1f
        up = true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(down)
        glide.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val W = 164
        private const val H = 4
    }
}
