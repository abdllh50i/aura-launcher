package com.abdllh.aura.nav.drive

import android.content.Context
import android.graphics.Rect
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.TouchDelegate
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import com.abdllh.aura.chrome.Tweens
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.LimitSign
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Plates
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.plateView

/**
 * The speed card (SPEC.md section 5), 232 x 88 at (12, 242): the number over its unit in a box 94 wide at the reading edge, so three digits fit and nothing jiggles at 100; the limit sign at the far end, when one
 * is known. Over the limit a plate of `overFill` comes inside the whole card, and the number and its unit take their colours by the same 140 ms: pale at night, solid red by day.
 */
internal class SpeedCard(ctx: Context) : SurfaceCard(ctx, Surface.CARD) {
    private val ar = Fonts.arabicUi
    private val plate = ctx.plateView(Tokens2.R_card) { Palette.overFill }
    private val number = ctx.line(Type.speed, { Palette.ink })
    private val unit = ctx.line(Type.speedUnit, { Palette.ink2Move })
    private val sign = LimitSign(ctx)
    private var over = false
    private var overK = 0f
    private val tweens = Tweens()
    private val overPart = tweens.part(0f) { k ->
        overK = k
        plate.alpha = k
        colour()
    }

    init {
        addView(plate, at(0, 0, 232, 88))
        val x = if (ar) 124 else 14
        addView(number, Type.speed.box(x, 4, 94))
        addView(unit, Type.speedUnit.box(x, 58, 94))
        addView(sign, at(if (ar) 10 else 154, 10, 68, 68))
        number.digitSpacing(-0.5f)
        unit.setText(R.string.maps_kmh)
        sign.visibility = View.INVISIBLE
        look { colour() }
    }

    private fun colour() {
        number.setTextColor(Palette.mix(Palette.ink, Palette.overInk, overK))
        unit.setTextColor(Palette.mix(Palette.ink2Move, Palette.overUnit, overK))
    }

    /** [kmh] null: no fix, `—` and no sign. [limit] 0: none known, no sign. */
    fun set(kmh: Int?, limit: Int, isOver: Boolean) {
        val text = kmh?.toString() ?: "—"
        if (number.text.toString() != text) number.text = text
        val show = kmh != null && limit > 0
        if (show) sign.kmh = limit
        val on = sign.visibility == View.VISIBLE
        if (show != on) sign.visibility = if (show) View.VISIBLE else View.INVISIBLE
        val o = isOver && kmh != null
        if (o != over) {
            over = o
            if (isShown) overPart.to(if (o) 1f else 0f, 0L, Tokens2.MS_swap, Motion.MOVE) else overPart.snap(if (o) 1f else 0f)
        }
    }
}

/**
 * The Saher card (section 5), 124 x 88 at (252, 242), and the bump's: the glyph and its word, the distance under them. A camera 400 m or nearer is hot: a `red` plate with a white glyph comes over the plain glyph.
 * A bump always has its `warn` plate.
 */
internal class SaherCard(ctx: Context) : SurfaceCard(ctx, Surface.CARD) {
    private val ar = Fonts.arabicUi
    private val plain: ImageView = ctx.iconView(R.drawable.ic_cam, 30) { Palette.red }
    private var bump = false
    private val plateTint = Tint(ctx, Plates.res(Tokens2.R_small)) { if (bump) Palette.warn else Palette.red }
    private val plate = object : View(ctx) { override fun hasOverlappingRendering() = false }.apply { background = plateTint.drawable }
    private val glyph = ImageView(ctx)
    private val word = ctx.line(Type.saherLabel, { Palette.ink2Move })
    private val value = ctx.line(Type.saherValue, { Palette.ink })
    private var hot = false

    init {
        val plateX = if (ar) 80 else 10
        addView(plain, at(if (ar) 84 else 10, 9, 30, 30))
        addView(plate, at(plateX, 7, 34, 34))
        addView(glyph, at(plateX + 4, 11, 26, 26))
        addView(word, Type.saherLabel.box(if (ar) 8 else 50, 8, 66))
        addView(value, Type.saherValue.box(10, 42, 104))
        plate.alpha = 0f
        glyph.alpha = 0f
        look { plateTint.recolour(); glyphColour() }
    }

    private fun glyphColour() = glyph.setColorFilter(if (bump) Palette.onWarn else Palette.onAccent)

    fun set(isBump: Boolean, distance: String, isHot: Boolean) {
        val h = isHot || isBump
        if (isBump != bump || glyph.drawable == null) {
            bump = isBump
            plateTint.recolour()
            glyph.setImageResource(if (isBump) R.drawable.ic_bump else R.drawable.ic_cam)
            glyphColour()
            word.setText(if (isBump) R.string.drive_bump else R.string.drive_saher)
        }
        if (value.text.toString() != distance) value.text = distance
        if (h == hot) return
        hot = h
        val a = if (h) 1f else 0f
        if (isShown) {
            val dur = moveMs(Tokens2.MS_swap)
            plate.fadeTo(a, dur)
            glyph.fadeTo(a, dur)
            plain.fadeTo(1f - a, dur)
        } else {
            plate.alpha = a
            glyph.alpha = a
            plain.alpha = 1f - a
        }
    }
}

/**
 * The ETA card (section 5), 364 x 80 at (12, 338), which never moves. `etaUnit` then `etaValue`, `etaSub` under them, End 100 x 58 at the left in both languages, its hit 122 x 80, one touch and no confirm. After End
 * the same rect says the trip has ended, with `رجّعها` where End was, for 5 s (`drive-ended`).
 */
internal class EtaCard(ctx: Context, onEnd: () -> Unit, onUndo: () -> Unit) : SurfaceCard(ctx, Surface.CARD) {
    private val ar = Fonts.arabicUi
    private val textX = if (ar) 126 else 124
    private val textW = if (ar) 222 else 224
    private val top = ctx.line(Type.etaValue, { Palette.ink })
    private val sub = ctx.line(Type.etaSub, { Palette.ink2Move })
    private val end = ABtn(ctx).apply {
        kind = ABtn.Kind.DANGER
        setHeightOverride(58)
        setPadding(0, 0, 0, 4)
        setText(R.string.maps_end)
        setOnClickListener { onEnd() }
    }
    private val ended = ctx.line(Type.etaUnit, { Palette.ink }, moving = false)
    private val kept = ctx.line(Type.etaSub, { Palette.ink2Move })
    private val undo = ABtn(ctx).apply {
        kind = ABtn.Kind.PRIMARY
        setHeightOverride(58)
        setPadding(0, 0, 0, 2)
        setText(R.string.drive_undo)
        setOnClickListener { onUndo() }
        alpha = 0f
        visibility = View.INVISIBLE
    }
    private var shown: String? = null
    private var undoing = false

    init {
        addView(top, Type.etaValue.box(textX, 6, textW))
        addView(sub, Type.etaSub.box(textX, 44, textW))
        addView(ended, Type.etaUnit.box(textX, 6, textW))
        addView(kept, Type.etaSub.box(textX, 44, textW))
        addView(end, at(11, 11, 100, 58))
        addView(undo, at(11, 11, 100, 58))
        ended.setText(R.string.drive_trip_ended)
        ended.typeface = Fonts.get(500)
        kept.setText(R.string.drive_route_kept)
        ended.alpha = 0f
        kept.alpha = 0f
        // (from the card's edge to the plate's and past it: the hit is 122 x 80 where the plate is 100 x 58, section 1)
        val o = Surface.CARD.outset
        touchDelegate = TouchDelegate(Rect(o, o, o + 122, o + 80), end)
    }

    /** The clock of the arrival in `etaValue` after its word in `etaUnit`, 8 px apart; [left] under it. Only what changed is set. */
    fun set(time: String, left: String) {
        val key = "$time|${Palette.dark}"
        if (key != shown) {
            shown = key
            val s = SpannableStringBuilder(context.getString(R.string.drive_arrive))
            s.setSpan(AbsoluteSizeSpan(Type.etaUnit.px, false), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(TypefaceSpan(Fonts.of(Type.etaUnit, true)), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(Palette.ink2Move), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val gap = s.length
            s.append(" ")
            s.setSpan(GapSpan(8), gap, gap + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.append(time)
            top.text = s
        }
        if (sub.text.toString() != left) sub.text = left
    }

    /** From the trip's words to `drive-ended`'s and back: out 120, in 180, in the same rect. */
    fun ended(on: Boolean, animate: Boolean) {
        if (on == undoing) return
        undoing = on
        val trip = arrayOf<View>(top, sub, end)
        val after = arrayOf<View>(ended, kept, undo)
        val going = if (on) trip else after
        val coming = if (on) after else trip
        for (v in coming) v.visibility = View.VISIBLE
        for (v in going + coming) v.animate().cancel()
        if (animate) {
            for (v in going) v.fadeTo(0f, moveMs(Tokens2.MS_cardOut), Motion.EXIT) { if (undoing == on) v.visibility = View.INVISIBLE }
            for (v in coming) v.fadeTo(1f, moveMs(Tokens2.MS_cardIn), Motion.ENTER)
        } else {
            for (v in going) { v.alpha = 0f; v.visibility = View.INVISIBLE }
            for (v in coming) v.alpha = 1f
        }
    }
}

/** The arrival's actions (`drive-arrived`), 364 x 92 at (12, 242): Save and Done 76 high, Done by the driver. */
internal class ArrivedActions(ctx: Context, onSave: () -> Unit, onDone: () -> Unit) : SurfaceCard(ctx, Surface.CARD) {
    val save = ABtn(ctx).apply {
        kind = ABtn.Kind.TONAL
        setHeightOverride(76)
        setPadding(0, 0, 0, 0)
        setText(R.string.drive_save_place)
        setOnClickListener { onSave() }
    }

    init {
        addView(ABtn(ctx).apply {
            kind = ABtn.Kind.PRIMARY
            size = ABtn.Size.ARRIVAL
            setPadding(0, 0, 0, 0)
            setText(R.string.maps_done)
            setOnClickListener { onDone() }
        }, at(8, 8, 170, 76))
        addView(save, at(186, 8, 170, 76))
    }
}

/** A group whose touch stops within its visible rect, like a surface's; for a card whose surface is an image ([MiniCard]). */
internal open class Hit(ctx: Context, private val outset: Int) : FrameLayout(ctx) {
    private var kept = false

    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            kept = e.x >= outset && e.y >= outset && e.x < width - outset && e.y < height - outset
        }
        if (!kept) return false
        return super.dispatchTouchEvent(e) || e.actionMasked == android.view.MotionEvent.ACTION_DOWN
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean = super.onTouchEvent(e) || kept
}
