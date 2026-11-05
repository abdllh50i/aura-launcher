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
 * The speed card (simple/CHANGES.md section 4), 364 x 112: the number over its unit in a box 94 wide at the reading edge, so three digits fit and nothing jiggles at 100; the limit sign 76 at the far end, when one
 * is known; between them, only when real, the Saher or bump chip, so the card has no hole beside it. Over the limit a plate of `overFill` comes inside the whole card, and the number and its unit take their colours
 * by the same 140 ms: pale at night, solid red by day.
 */
internal class SpeedCard(ctx: Context) : SurfaceCard(ctx, Surface.CARD) {
    private val ar = Fonts.arabicUi
    private val plate = ctx.plateView(Tokens2.R_card) { Palette.overFill }
    private val number = ctx.line(Type.speed, { Palette.ink })
    private val unit = ctx.line(Type.speedUnit, { Palette.ink2Move })
    private val sign = LimitSign(ctx)
    private val chip = SaherChip(ctx).apply { visibility = View.INVISIBLE }
    private var over = false
    private var overK = 0f
    private val tweens = Tweens()
    private val overPart = tweens.part(0f) { k ->
        overK = k
        plate.alpha = k
        colour()
    }

    init {
        addView(plate, at(0, 0, 364, 112))
        val x = if (ar) 254 else 16
        addView(number, Type.speed.box(x, 16, 94))
        addView(unit, Type.speedUnit.box(x, 70, 94))
        addView(sign, at(if (ar) 16 else 272, 18, 76, 76))
        addView(chip, at(if (ar) 102 else 118, 18, 142, 76))
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

    /** The chip: a camera or a bump within reach ([distance]), or none ([distance] null). */
    fun saher(distance: String?, isBump: Boolean, isHot: Boolean) {
        if (distance != null) chip.set(isBump, distance, isHot)
        chip.showCard(distance != null, dy = 0f)
    }
}

/**
 * Saher or a bump inside the speed card, 142 x 76, `plate-18` in `fill`: the glyph and its word, the distance under them. A camera 400 m or nearer is hot: a `red` plate with a white glyph comes over the plain glyph.
 * A bump always has its `warn` plate.
 */
private class SaherChip(ctx: Context) : FrameLayout(ctx) {
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
        clipChildren = false // (a final letter's tail goes a little under the chip's own 76)
        addView(ctx.plateView(Tokens2.R_inner) { Palette.fill }, at(0, 0, 142, 76))
        addView(plain, at(if (ar) 100 else 12, 5, 30, 30))
        addView(plate, at(if (ar) 96 else 12, 3, 34, 34))
        addView(glyph, at(if (ar) 100 else 16, 7, 26, 26))
        addView(word, Type.saherLabel.box(if (ar) 12 else 52, 4, 78))
        addView(value, Type.saherValue.box(12, 38, 118))
        plate.alpha = 0f
        glyph.alpha = 0f
        placeWord()
        look { plateTint.recolour(); glyphColour() }
    }

    private fun glyphColour() = glyph.setColorFilter(if (bump) Palette.onWarn else Palette.onAccent)

    /** The word stands 6 from what is before it, and the plain glyph is 4 narrower than the plate. */
    private fun placeWord() {
        word.translationX = if (hot) 0f else if (ar) 4f else -4f
    }

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
        placeWord()
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
 * The ETA card (section 4), 364 x 112 at (16, 392), which never moves. `etaUnit` then `etaValue`, `etaSub` under them, End 104 x 64 at the left in both languages, its hit the card's 132 x 112, one touch and no confirm.
 * After End the same rect says the trip has ended, with `رجّعها` where End was, for 5 s (`drive-ended`).
 */
internal class EtaCard(ctx: Context, onEnd: () -> Unit, onUndo: () -> Unit) : SurfaceCard(ctx, Surface.CARD) {
    private val top = ctx.line(Type.etaValue, { Palette.ink })
    private val sub = ctx.line(Type.etaSub, { Palette.ink2Move })
    private val end = ABtn(ctx).apply {
        kind = ABtn.Kind.DANGER
        setHeightOverride(64)
        setPadding(0, 0, 0, 4)
        setText(R.string.maps_end)
        setOnClickListener { onEnd() }
    }
    private val ended = ctx.line(Type.etaUnit, { Palette.ink }, moving = false)
    private val kept = ctx.line(Type.etaSub, { Palette.ink2Move })
    private val undo = ABtn(ctx).apply {
        kind = ABtn.Kind.PRIMARY
        setHeightOverride(64)
        setPadding(0, 0, 0, 2)
        setText(R.string.drive_undo)
        setOnClickListener { onUndo() }
        alpha = 0f
        visibility = View.INVISIBLE
    }
    private var shown: String? = null
    private var undoing = false

    init {
        addView(top, Type.etaValue.box(TEXT_X, 16, TEXT_W))
        addView(sub, Type.etaSub.box(TEXT_X, 60, TEXT_W))
        addView(ended, Type.etaUnit.box(TEXT_X, 16, TEXT_W))
        addView(kept, Type.etaSub.box(TEXT_X, 60, TEXT_W))
        addView(end, at(16, 24, 104, 64))
        addView(undo, at(16, 24, 104, 64))
        ended.setText(R.string.drive_trip_ended)
        ended.typeface = Fonts.get(500)
        kept.setText(R.string.drive_route_kept)
        ended.alpha = 0f
        kept.alpha = 0f
        hitFor(end)
    }

    /** From the card's edge to the plate's and past it: the hit is 132 x 112 where the plate is 104 x 64. Whichever button is up has it. */
    private fun hitFor(button: View) {
        val o = Surface.CARD.outset
        touchDelegate = TouchDelegate(Rect(o, o, o + 132, o + 112), button)
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
        hitFor(if (on) undo else end)
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

    private companion object {
        /** The words' box in both languages: the card's far end, past End. */
        const val TEXT_X = 136
        const val TEXT_W = 212
    }
}

/** The arrival's actions (`drive-arrived`), 364 x 92 at (16, 268): Save and Done 76 high, Done by the driver. */
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
