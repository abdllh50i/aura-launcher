package com.abdllh.aura.nav.browse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Spinner
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.glyph
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.ink
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.plateView
import com.abdllh.aura.ui.pressScale

/**
 * The place's card (SPEC.md section 6.3 and 12: `place-routes`, `place-loading`, `place-no-route`, `place-moving`): a card at (16, 48), 364 wide, name and address, close and save at the far
 * end, one or two route choices, the arrival line, Go. Everything has its rect from the build on and shows by visibility, so between computing and an answer nothing moves and nothing is laid
 * out: Go is at y 362 before the route comes and after. Only no route is a shorter card.
 */
internal class PlaceCard(ctx: Context) : SurfaceCard(ctx, Surface.CARD) {
    private val arabic = Fonts.arabicUi
    private val name: AText = ctx.label(Type.placeName, { Palette.ink })
    private val sub: AText = ctx.label(Type.placeSub, { Palette.ink2 })
    val close = IconButton(ctx, R.drawable.ic_close, 28, 56, 56, token = { Palette.ink2 }).apply { contentDescription = ctx.getString(R.string.maps_close) }
    val save = IconButton(ctx, R.drawable.ic_star, 28, 56, 56, token = { Palette.ink2 }).apply { contentDescription = ctx.getString(R.string.btn_save) }
    private val choices = arrayOf(ChoiceRow(ctx), ChoiceRow(ctx))
    private val waiting = FrameLayout(ctx)
    private val waitingPlate = ctx.plateView(18) { Palette.fill }
    private val spinner = Spinner(ctx, 24)
    private val waitingText: AText = ctx.label(Type.routeSub, { Palette.ink2 })
    private val arrive: AText = ctx.label(Type.routeSub, { Palette.ink2 })
    private val warnGlyph: ImageView = ctx.iconView(R.drawable.ic_warn, 24) { Palette.red }
    private val warnText: AText = ctx.label(Type.routeSub, { Palette.ink }, lines = 2)
    private val noRoute: AText = ctx.label(Type.stateBody, { Palette.ink }, lines = 2)
    val retry = ABtn(ctx).apply {
        kind = ABtn.Kind.TONAL
        size = ABtn.Size.L
        role = Type.go
        icon(R.drawable.ic_refresh, 30)
        setText(R.string.browse_retry)
    }
    val go = ABtn(ctx).apply {
        kind = ABtn.Kind.PRIMARY
        size = ABtn.Size.L
        role = Type.go
        icon(R.drawable.ic_nav, 32)
        setText(R.string.maps_go)
    }

    /** Which choice he tapped. */
    var onChoice: ((Int) -> Unit)? = null
    private var two = false
    private var heightNow = 0

    init {
        val textX = if (arabic) 124 else 18
        addView(name, Type.placeName.box(textX, 12, TEXT_W))
        addView(sub, Type.placeSub.box(textX, 54, TEXT_W))
        addView(close, at(if (arabic) 8 else W - 8 - 112, 8, 56, 56))
        addView(save, at(if (arabic) 64 else W - 8 - 56, 8, 56, 56))
        addView(choices[0], at(8, 94, ROW_W, ROW_H))
        addView(choices[1], at(8, 186, ROW_W, ROW_H))
        for (i in choices.indices) choices[i].setOnClickListener { onChoice?.invoke(i) }
        waiting.addView(waitingPlate, FrameLayout.LayoutParams(ROW_W, ROW_H))
        waiting.addView(spinner, at(if (arabic) ROW_W - 14 - 24 else 14, 30, 24, 24))
        waiting.addView(waitingText, Type.routeSub.box(if (arabic) 14 else 50, 27, ROW_W - 64))
        addView(waiting, at(8, 94, ROW_W, ROW_H))
        addView(arrive, Type.routeSub.box(18, ARRIVE_TOP_TWO, 328))
        addView(warnGlyph, at(if (arabic) 322 else 18, ARRIVE_TOP_ONE + 39, 24, 24))
        addView(warnText, Type.routeSub.box(if (arabic) 18 else 50, ARRIVE_TOP_ONE + 36, 296).apply { height = 60 })
        addView(noRoute, Type.stateBody.box(18, 104, 328).apply { height = 64 })
        addView(retry, at(8, 208, ROW_W, 84))
        addView(go, at(8, 318, ROW_W, 84))
        showComputing("")
    }

    fun setPlace(title: String, detail: String) {
        name.text = android.text.BidiFormatter.getInstance(arabic).unicodeWrap(title)
        sub.text = detail
    }

    fun setSaved(on: Boolean) = save.setIcon(if (on) R.drawable.ic_star_on else R.drawable.ic_star)

    private fun height(h: Int) {
        if (h == heightNow) return
        heightNow = h
        layoutParams = Surface.CARD.at(16, 48, W, h)
    }

    /** `place-loading`: the route's being found, or the first fix awaited. [spin]: not while moving, nothing spins on a driving screen. */
    fun showComputing(text: String, spin: Boolean = true) {
        height(H)
        waitingText.text = text
        waiting.visibility = VISIBLE
        spinner.visibility = if (spin) VISIBLE else GONE
        for (c in choices) c.visibility = INVISIBLE
        arrive.visibility = INVISIBLE
        warn(null)
        noRoute.visibility = GONE
        retry.visibility = GONE
        go.visibility = VISIBLE
        goOn(false)
    }

    /** One or two routes, [chosen] of them selected: `time`, `distance · road`. */
    fun showRoutes(lines: List<Pair<String, String>>, chosen: Int) {
        height(H)
        two = lines.size > 1
        waiting.visibility = INVISIBLE
        spinner.visibility = GONE
        noRoute.visibility = GONE
        retry.visibility = GONE
        for (i in choices.indices) {
            val c = choices[i]
            val l = lines.getOrNull(i)
            if (l == null) { c.visibility = INVISIBLE; continue }
            c.set(l.first, l.second, i == chosen)
            if (c.visibility != VISIBLE) {
                c.visibility = VISIBLE
                c.alpha = 0f
                c.anim(Tokens2.MS_swap, Motion.MOVE).alpha(1f).start()
            }
        }
        (arrive.layoutParams as LayoutParams).topMargin = (if (two) ARRIVE_TOP_TWO else ARRIVE_TOP_ONE) + Type.routeSub.viewTop
        arrive.requestLayout()
        arrive.visibility = VISIBLE
        go.visibility = VISIBLE
        goOn(true)
    }

    fun select(chosen: Int) {
        for (i in choices.indices) choices[i].setOn(i == chosen)
    }

    fun setArrival(text: String) { arrive.text = text }

    /**
     * A warning of the chosen route (section 5: in a short line). With one route there is room under the arrival for all of it; with two it takes the arrival's line, because what it
     * says matters more than the time, which the choice already has, and only what comes before its comma fits there: "وجهتك داخل حدود الحرم".
     */
    fun warn(text: String?) {
        val on = !text.isNullOrEmpty()
        val top = if (two) ARRIVE_TOP_TWO else ARRIVE_TOP_ONE + 36
        (warnGlyph.layoutParams as LayoutParams).topMargin = top + 3
        (warnText.layoutParams as LayoutParams).apply {
            topMargin = top + Type.routeSub.viewTop
            height = if (two) Type.routeSub.viewH else 60
        }
        warnText.maxLines = if (two) 1 else 2
        warnText.text = if (two) text.orEmpty().substringBefore('،').substringBefore(':') else text.orEmpty()
        warnGlyph.visibility = if (on) VISIBLE else INVISIBLE
        warnText.visibility = if (on) VISIBLE else INVISIBLE
        arrive.visibility = if (choices[0].visibility != VISIBLE || (on && two)) INVISIBLE else VISIBLE
        warnText.requestLayout()
    }

    /** `place-no-route`: why, and try again in Go's place on a shorter card. */
    fun showNoRoute(text: String) {
        height(H_NO_ROUTE)
        waiting.visibility = INVISIBLE
        spinner.visibility = GONE
        for (c in choices) c.visibility = INVISIBLE
        arrive.visibility = INVISIBLE
        warn(null)
        noRoute.text = text
        noRoute.visibility = VISIBLE
        retry.visibility = VISIBLE
        go.visibility = GONE
    }

    /** Go answers, or stands at .4 until the route is there. Coming on, a fade: the first thing the eye should go to. */
    private fun goOn(on: Boolean) {
        if (on == go.isEnabled && go.alpha == (if (on) 1f else 0.4f)) return
        go.animate().cancel()
        go.isEnabled = on
        if (on && go.isAttachedToWindow) {
            go.alpha = 0.4f
            go.anim(Tokens2.MS_swap, Motion.MOVE).alpha(1f).start()
        }
    }

    private companion object {
        const val W = 364
        const val H = 410
        const val H_NO_ROUTE = 300
        const val TEXT_W = 222
        const val ROW_W = 348
        const val ROW_H = 84
        const val ARRIVE_TOP_TWO = 276
        const val ARRIVE_TOP_ONE = 186
    }
}

/** A route choice, 348 x 84 (section 5): the dot at the reading edge, the time over `distance · road`; selected, the quiet blue plate with its edge. */
private class ChoiceRow(ctx: Context) : FrameLayout(ctx), Recolours {
    private val arabic = Fonts.arabicUi
    private var on = false
    private val plate = Tint(ctx, R.drawable.plate_18) { if (on) Palette.selectedSoft else Palette.fill }
    private val edge = Tint(ctx, R.drawable.ring_18) { if (on) Palette.selectedSoftEdge else 0 }
    private val dot = ChoiceDot(ctx)
    private val time: AText = ctx.label(Type.routeTime, { Palette.ink })
    private val sub: AText = ctx.label(Type.routeSub, { Palette.ink2 })

    init {
        background = LayerDrawable(arrayOf<Drawable>(plate.drawable, edge.drawable)).apply { setPaddingMode(LayerDrawable.PADDING_MODE_STACK) }
        setPadding(0, 0, 0, 0)
        addView(dot, at(if (arabic) 348 - 14 - 26 else 14, 29, 26, 26))
        val x = if (arabic) 14 else 52
        addView(time, Type.routeTime.box(x, 9, 282))
        addView(sub, Type.routeSub.box(x, 45, 282))
        pressScale(PressGesture.SURFACE)
    }

    fun set(t: String, s: String, selected: Boolean) {
        time.text = t
        sub.text = s
        setOn(selected)
    }

    fun setOn(selected: Boolean) {
        if (selected == on) return
        on = selected
        onLook()
        dot.setOn(selected)
    }

    override fun onLook() {
        plate.recolour()
        edge.recolour()
        invalidate()
    }
}

/** The choice's dot, 26: a 2 px ring in `ink3`, or a blue disc with its check. */
private class ChoiceDot(ctx: Context) : View(ctx), Recolours {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val check = ctx.getDrawable(R.drawable.ic_check)!!.mutate()
    private var on = false

    fun setOn(v: Boolean) {
        if (v == on) return
        on = v
        invalidate()
    }

    override fun onLook() = invalidate()

    override fun hasOverlappingRendering() = false

    override fun onDraw(c: Canvas) {
        val mid = width / 2f
        if (on) {
            paint.style = Paint.Style.FILL
            paint.color = Palette.accent
            c.drawCircle(mid, mid, mid, paint)
            check.setTint(Palette.onAccent)
            val l = (width - 18) / 2
            check.setBounds(l, l, l + 18, l + 18)
            check.draw(c)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Palette.ink3
            c.drawCircle(mid, mid, mid - 1f, paint)
        }
    }
}

/**
 * The card of a place sent from the phone with no trip (section 5, `from-phone`): 364 x 284 at (16, 48): who sent it, the place, `18 دقيقة · 21.8 كم`, the count in whole seconds, no ring.
 * Start now, and Cancel on the driver's side because it has the deadline. [Trips] does the counting: the card only shows it.
 */
internal class FromPhoneCard(ctx: Context) : SurfaceCard(ctx, Surface.CARD) {
    private val arabic = Fonts.arabicUi
    private val headGlyph = ctx.iconView(R.drawable.ic_iphone, 24) { Palette.ink2 }
    private val head: AText = ctx.label(Type.resultSub, { Palette.ink2 })
    private val name: AText = ctx.label(Type.placeName, { Palette.ink })
    private val info: AText = ctx.label(Type.placeSub, { Palette.ink2 })
    private val count: AText = ctx.label(Type.placeSub, { Palette.ink }).apply { typeface = Fonts.get(500) }
    val startNow = ABtn(ctx).apply {
        kind = ABtn.Kind.PRIMARY
        size = ABtn.Size.L
        setText(R.string.browse_start_now)
    }
    val cancel = ABtn(ctx).apply {
        kind = ABtn.Kind.TONAL
        size = ABtn.Size.L
        setText(R.string.btn_cancel)
    }

    init {
        addView(headGlyph, at(if (arabic) 364 - 18 - 24 else 18, 17, 24, 24))
        addView(head, Type.resultSub.box(if (arabic) 18 else 50, 14, 296))
        addView(name, Type.placeName.box(18, 52, 328))
        addView(info, Type.placeSub.box(18, 96, 328))
        addView(count, Type.placeSub.box(18, 134, 328))
        addView(cancel, at(8, 192, 170, 84))
        addView(startNow, at(186, 192, 170, 84))
        head.setText(R.string.link_from_phone)
    }

    fun setPlace(title: String) { name.text = android.text.BidiFormatter.getInstance(arabic).unicodeWrap(title) }

    /** The route is being found, or the first fix awaited: what it is, no count yet. */
    fun waiting(text: String) {
        info.text = text
        count.text = ""
        startNow.isEnabled = false
    }

    /** The route's `18 دقيقة · 21.8 كم`, and how long until it starts. */
    fun ready(trip: String, countdown: String) {
        info.text = trip
        count.text = countdown
        startNow.isEnabled = true
    }
}
