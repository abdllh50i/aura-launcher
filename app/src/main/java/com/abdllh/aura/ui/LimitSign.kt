package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The speed limit as the road sign everybody knows (SPEC.md section 5): a `limitFace` disc inside a `limitRing` ring, the number in
 * `limitInk`, centred on the sign's box line. Drawn on Canvas, once for each new number.
 * [SPEED] 68 with `limit`, ring 6, 5 with three digits; [HOME] 40; [STRIP] 34 with `stripLimit`, ring 3. Any other size scales from these.
 * [dim]: the Night Panel's face and ring, which have no white in them.
 */
class LimitSign @JvmOverloads constructor(
    ctx: Context,
    private val spec: Spec = SPEED,
    private val dim: Boolean = false
) : View(ctx), Recolours {
    class Spec(@JvmField val px: Int, @JvmField val role: Type.Role, @JvmField val ring: Float, @JvmField val ring3: Float, @JvmField val spacing3: Float)

    var kmh = 0
        set(v) { if (v != field) { field = v; invalidate() } }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onLook() = invalidate()

    override fun hasOverlappingRendering() = false

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(spec.px, spec.px)

    override fun onDraw(c: Canvas) {
        if (kmh <= 0) return
        val r = spec.px / 2f
        val three = kmh >= 100
        val ring = if (three) spec.ring3 else spec.ring
        p.style = Paint.Style.FILL
        p.color = if (dim) Palette.nightPanelInk else Palette.limitFace
        c.drawCircle(r, r, r - 0.25f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = ring
        p.color = if (dim) Palette.nightPanelRing else Palette.limitRing
        c.drawCircle(r, r, r - ring / 2f, p)
        t.color = Palette.limitInk
        t.typeface = Fonts.of(spec.role)
        t.textSize = spec.role.sp
        // (a digit is 0.6 em, and only a three-digit number is tightened: section 3)
        t.letterSpacing = if (three) spec.spacing3 / t.textSize else 0f
        c.drawText(kmh.toString(), r, r + 0.335f * t.textSize, t)
    }

    companion object {
        @JvmField val SPEED = Spec(68, Type.limit, 6f, 5f, -0.8f)
        @JvmField val HOME = Spec(40, Type.stripLimit, 3f, 3f, -0.4f)
        @JvmField val STRIP = Spec(34, Type.stripLimit, 3f, 3f, -0.4f)
    }
}
