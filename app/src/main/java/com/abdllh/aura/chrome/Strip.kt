package com.abdllh.aura.chrome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import com.abdllh.aura.system.Gear
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.LimitSign
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.ThemeSwap
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.ink
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look

/**
 * The status strip, 1024 x 40 (SPEC.md section 5): what is linked at the left, then the gear, then the speed when no speed card shows; the time at the right. Two
 * faces in alpha: gear row and logo on Home, gear badge and time elsewhere. All in their places from the start and never laid out again; a label's text changes
 * inside a fixed box. [homeFace] and [otherFace] are the two faces' alpha, which [Chrome] moves.
 */
internal class Strip(ctx: Context, onControls: () -> Unit, onNight: () -> Unit) : FrameLayout(ctx) {
    private val status = StatusIcons(ctx, onControls, onNight)
    private val gearRow = GearRow(ctx)
    private val logo = Logo(ctx)
    private val badge = GearBadge(ctx)
    private val time: AText = ctx.label(Type.stripTime, { Palette.ink }).apply {
        gravity = Gravity.RIGHT or Gravity.TOP
        textAlignment = TEXT_ALIGNMENT_GRAVITY
    }
    val speed = StripSpeed(ctx)

    init {
        addView(status, at(0, 0, 126, 48))
        addView(gearRow, at(128, 0, 102, 40))
        addView(badge, at(128, 5, 30, 30))
        addView(speed, at(240, 2, 320, 36))
        addView(logo, FrameLayout.LayoutParams(logo.w, 26, Gravity.RIGHT or Gravity.TOP).apply { rightMargin = 24; topMargin = 7 })
        addView(time, Type.stripTime.box(TIME_RIGHT - TIME_W, 0, TIME_W))
    }

    var homeFace = 1f
        set(v) {
            field = v
            gearRow.fadeTo(v)
            logo.fadeTo(v)
        }

    var otherFace = 0f
        set(v) {
            field = v
            badge.fadeTo(v)
            time.fadeTo(v)
        }

    private fun View.fadeTo(a: Float) {
        alpha = a
        val want = if (a > 0f) VISIBLE else INVISIBLE
        if (visibility != want) visibility = want
    }

    fun setStatus(gps: Boolean, wifi: Boolean, phone: Boolean) = status.set(gps, wifi, phone)

    fun setGear(g: Gear.Pos?, sure: Boolean, moving: Boolean) {
        gearRow.set(g, sure, moving)
        badge.set(g, sure)
    }

    fun setTime(s: CharSequence) {
        if (s != time.text) time.text = s
    }

    override fun hasOverlappingRendering() = false

    private companion object {
        const val TIME_RIGHT = 1010
        const val TIME_W = 220
    }
}

/**
 * The strip's left end, x 0..126, y 0..48: GPS, Wi-Fi, phone at x 16, 50, 84 (22 px, off forms in `ink3`), then the small chevron that says they open Controls. A tap
 * or a drag down opens Controls, a long press is the Night Panel.
 */
internal class StatusIcons(ctx: Context, private val onControls: () -> Unit, private val onNight: () -> Unit) : FrameLayout(ctx), Recolours {
    private inner class One(private val on: Int, private val off: Int, x: Int) {
        val view = ImageView(context)
        private var state: Boolean? = null

        init { addView(view, at(x, 9, 22, 22)) }

        fun set(v: Boolean) {
            if (v == state) return
            state = v
            view.setImageResource(if (v) on else off)
            tint()
        }

        fun tint() = view.setColorFilter(if (state == true) Palette.ink2 else Palette.ink3)
    }

    private val gps = One(R.drawable.ic_gps, R.drawable.ic_gps_off, 16)
    private val wifi = One(R.drawable.ic_wifi, R.drawable.ic_wifi_off, 50)
    private val phone = One(R.drawable.ic_iphone, R.drawable.ic_iphone_off, 84)
    private val chevron = ImageView(ctx).apply { setImageResource(R.drawable.ic_chev_down) }
    private val gesture = PressGesture(this, PressGesture.ICON)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downY = 0f
    private var used = false

    private val hold = Runnable {
        used = true
        onNight()
    }

    init {
        addView(chevron, at(106, 11, 18, 18))
        set(gps = false, wifi = false, phone = false)
        onLook()
    }

    fun set(gps: Boolean, wifi: Boolean, phone: Boolean) {
        this.gps.set(gps)
        this.wifi.set(wifi)
        this.phone.set(phone)
    }

    override fun onLook() {
        gps.tint()
        wifi.tint()
        phone.tint()
        chevron.setColorFilter(Palette.ink3)
    }

    override fun hasOverlappingRendering() = false

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        gesture.onTouch(e)
        return super.dispatchTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y
                used = false
                postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (!used && e.y - downY > slop * 2) {
                used = true
                removeCallbacks(hold)
                onControls()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                if (!used && e.x >= 0 && e.x < width && e.y >= 0 && e.y < height) onControls()
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(hold)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hold)
        gesture.reset()
        super.onDetachedFromWindow()
    }
}

/** P R N D at (128, 1): cells 30, 24, 24, 24, the current one `ink`, the others `ink3`; moving, at the badge's size in the same cells. One view that draws them; the lever's letters never mirror. */
internal class GearRow(ctx: Context) : View(ctx), Recolours {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; Fonts.tune(this) }
    private var gear: Gear.Pos? = null
    private var sure = true
    private var moving = false

    fun set(g: Gear.Pos?, sure: Boolean, moving: Boolean) {
        if (g == gear && sure == this.sure && moving == this.moving) return
        gear = g
        this.sure = sure
        this.moving = moving
        invalidate()
    }

    override fun onLook() = invalidate()

    override fun hasOverlappingRendering() = false

    override fun onDraw(c: Canvas) {
        val g = gear ?: return
        val role = if (moving) Type.gearBadge else Type.gearRow
        p.typeface = Fonts.of(role)
        p.textSize = role.sp
        val y = (if (moving) 5 else 1) + role.baseline
        for (i in LETTERS.indices) {
            // (an assumed gear between the two: it has no word in the design for the row; the badge's is `ink3`, and here all four would then be the same)
            p.color = if (i != g.ordinal) Palette.ink3 else if (sure) Palette.ink else Palette.ink2
            c.drawText(LETTERS[i], CENTRES[i], y, p)
        }
    }

    private companion object {
        val LETTERS = arrayOf("P", "R", "N", "D")
        val CENTRES = floatArrayOf(15f, 42f, 66f, 90f)
    }
}

/** The gear on every screen but Home: 30 x 30, radius 9, `fill2`. Unknown: nothing. Assumed: `ink3`. */
internal class GearBadge(ctx: Context) : View(ctx), Recolours {
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; Fonts.tune(this) }
    private val r = RectF(0f, 0f, 30f, 30f)
    private var gear: Gear.Pos? = null
    private var sure = true

    fun set(g: Gear.Pos?, sure: Boolean) {
        if (g == gear && sure == this.sure) return
        gear = g
        this.sure = sure
        invalidate()
    }

    override fun onLook() = invalidate()

    override fun hasOverlappingRendering() = false

    override fun onDraw(c: Canvas) {
        val g = gear ?: return
        plate.color = Palette.fill2
        c.drawRoundRect(r, Tokens2.R_gear.toFloat(), Tokens2.R_gear.toFloat(), plate)
        val role = Type.gearBadge
        p.typeface = Fonts.of(role)
        p.textSize = role.sp
        p.color = if (sure) Palette.ink else Palette.ink3
        c.drawText(g.name, 15f, role.baseline, p)
    }
}

/**
 * The logo, 26 high, its right edge at x 1000, in this language and theme. The drawables are 4 to 6 times that size for Settings' About: drawn straight, the GPU's
 * bilinear filter would skip most of their pixels, so each is cut down once to its real size, by halves, and kept.
 */
internal class Logo(ctx: Context) : ImageView(ctx) {
    private val arabic = Fonts.arabicUi
    val w = if (arabic) 87 else 155

    init {
        scaleType = ScaleType.FIT_XY
        look { setImageBitmap(bitmap(context, Palette.dark)) }
        // (the other theme's is cut on the swap's worker, not in its frame)
        if (!stepAdded) {
            stepAdded = true
            val app = ctx.applicationContext
            ThemeSwap.readySteps.add { night, done -> bitmap(app, night); done() }
        }
    }

    private fun bitmap(c: Context, night: Boolean): Bitmap? = synchronized(kept) {
        val res = if (arabic) { if (night) R.drawable.logo_amri_ar else R.drawable.logo_amri_ar_ink } else { if (night) R.drawable.logo_amri_en else R.drawable.logo_amri_en_ink }
        kept[res] ?: try {
            var b = BitmapFactory.decodeResource(c.resources, res, BitmapFactory.Options().apply { inScaled = false; inSampleSize = 2 })
            while (b.width > w * 2) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            Bitmap.createScaledBitmap(b, w, 26, true).also { kept[res] = it }
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        val kept = HashMap<Int, Bitmap>()
        var stepAdded = false
    }
}

/**
 * The speed in the strip, from x 240 (SPEC.md section 5): the limit sign 34, then the number; over the limit a plate behind the number in `overFill`, the number `overInk`;
 * a Saher camera within reach, `cam` and its distance beside it. No GPS: a dash, no sign. Everything stays where it was laid out: what has no sign moves the number by
 * translation, the number is drawn, the distance is a label in a fixed box, so a new speed redraws this one small view.
 */
internal class StripSpeed(ctx: Context) : FrameLayout(ctx), Recolours {
    private val sign = LimitSign(ctx, LimitSign.STRIP).apply { visibility = INVISIBLE }
    private val number = Number(ctx)
    private val saher = FrameLayout(ctx).apply { visibility = INVISIBLE }
    private val cam = ImageView(ctx).apply { setImageResource(R.drawable.ic_cam) }
    private val metres: AText = ctx.label(Type.saherLabel, { Palette.red }).apply {
        gravity = Gravity.LEFT or Gravity.TOP
        textAlignment = TEXT_ALIGNMENT_GRAVITY
        textDirection = TEXT_DIRECTION_LTR
    }

    init {
        addView(sign, at(0, 1, 34, 34))
        addView(number, at(0, 0, Number.W, 36))
        saher.addView(cam, at(0, 6, 24, 24))
        saher.addView(metres, Type.saherLabel.box(30, 2, 130))
        addView(saher, at(0, 0, 160, 36))
        look { cam.setColorFilter(Palette.red) }
    }

    /** [kmh] null: no GPS. [limit] 0: none known. [saherM] null: no camera within reach. */
    fun set(kmh: Int?, limit: Int, over: Boolean, saherM: String?) {
        val hasSign = kmh != null && limit > 0
        val wantSign = if (hasSign) VISIBLE else INVISIBLE
        if (sign.visibility != wantSign) sign.visibility = wantSign
        if (hasSign) sign.kmh = limit
        number.translationX = if (hasSign) AFTER_SIGN else 0f
        number.set(kmh?.toString() ?: "—", over && kmh != null)
        val wantSaher = if (saherM != null) VISIBLE else INVISIBLE
        if (saher.visibility != wantSaher) saher.visibility = wantSaher
        if (saherM != null) {
            if (saherM != metres.text.toString()) metres.text = saherM
            saher.translationX = number.translationX + number.boxW + SAHER_GAP
        }
    }

    override fun onLook() = Unit

    override fun hasOverlappingRendering() = false

    private class Number(ctx: Context) : View(ctx), Recolours {
        private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { Fonts.tune(this); typeface = Fonts.of(Type.stripSpeed); textSize = Type.stripSpeed.sp }
        private val r = RectF()
        private var s = ""
        private var over = false
        var boxW = 0f
            private set

        fun set(text: String, over: Boolean) {
            if (text == s && over == this.over) return
            s = text
            this.over = over
            boxW = p.measureText(text) + 2 * PAD
            invalidate()
        }

        override fun onLook() = invalidate()

        override fun hasOverlappingRendering() = false

        override fun onDraw(c: Canvas) {
            if (over) {
                plate.color = Palette.overFill
                r.set(0f, 0f, boxW, 36f)
                c.drawRoundRect(r, Tokens2.R_gear.toFloat(), Tokens2.R_gear.toFloat(), plate)
            }
            p.color = if (over) Palette.overInk else Palette.ink
            c.drawText(s, PAD, 18f + 0.335f * p.textSize, p)
        }

        companion object {
            const val W = 80
            const val PAD = 8f
        }
    }

    private companion object {
        const val AFTER_SIGN = 42f
        const val SAHER_GAP = 14f
    }
}
