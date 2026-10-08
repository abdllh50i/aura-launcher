package com.abdllh.aura.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.abdllh.aura.util.dp
import java.util.Random

/**
 * Decorative night-map backdrop for the navigation card: procedural streets, a park, water and a route
 * with a pulsing position marker. The static part is cached in a bitmap; only the marker ring animates.
 * (It is a stand-in until a real map can be embedded — tapping the card opens the navigation app.)
 */
class MapBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var cache: Bitmap? = null
    private var cacheAccent = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val route = Path()
    private var mx = 0f
    private var my = 0f
    private var pulse = 0f
    private var anim: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cache?.recycle()
        cache = null
        route.reset()
        // route: from the lower-left, bending towards the marker (right of centre)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        fun x(f: Float) = if (rtl) w * (1f - f) else w * f
        route.moveTo(x(-0.02f), h * 1.04f)
        route.cubicTo(x(0.18f), h * 0.90f, x(0.26f), h * 0.66f, x(0.44f), h * 0.60f)
        route.cubicTo(x(0.58f), h * 0.55f, x(0.64f), h * 0.58f, x(0.74f), h * 0.46f)
        mx = x(0.74f)
        my = h * 0.46f
    }

    private fun render(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        cacheAccent = Palette.accent
        c.drawColor(0xFF0E1217.toInt())
        val rnd = Random(11)
        val wf = w.toFloat()
        val hf = h.toFloat()

        // water (lower-left bay) and park (upper right)
        p.style = Paint.Style.FILL
        p.color = 0xFF0B1621.toInt()
        val water = Path().apply {
            moveTo(0f, hf * 0.62f); cubicTo(wf * 0.10f, hf * 0.55f, wf * 0.16f, hf * 0.80f, wf * 0.30f, hf)
            lineTo(0f, hf); close()
        }
        c.drawPath(water, p)
        p.color = 0xFF0F1B17.toInt()
        c.drawRoundRect(RectF(wf * 0.60f, -10f, wf * 0.92f, hf * 0.34f), 26f.dp, 26f.dp, p)

        // city blocks: slightly lighter rounded rectangles on a loose grid
        val cols = 9
        val rows = 5
        val gx = wf / cols
        val gy = hf / rows
        for (r in 0 until rows) for (col in 0 until cols) {
            if (rnd.nextFloat() < 0.18f) continue
            val l = col * gx + 6.dp + rnd.nextFloat() * 4.dp
            val t = r * gy + 6.dp + rnd.nextFloat() * 4.dp
            val rr = RectF(l, t, l + gx - 12.dp - rnd.nextFloat() * 6.dp, t + gy - 12.dp - rnd.nextFloat() * 6.dp)
            p.color = if (rnd.nextFloat() < 0.5f) 0xFF121821.toInt() else 0xFF10151D.toInt()
            c.drawRoundRect(rr, 6f.dp, 6f.dp, p)
        }

        // minor roads
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0xFF1B222C.toInt()
        p.strokeWidth = 3f.dp
        for (r in 0..rows) c.drawLine(0f, r * gy, wf, r * gy + (rnd.nextFloat() - 0.5f) * 10.dp, p)
        for (col in 0..cols) c.drawLine(col * gx, 0f, col * gx + (rnd.nextFloat() - 0.5f) * 10.dp, hf, p)

        // arterial roads with dashed centre lines
        val roads = arrayOf(
            Path().apply { moveTo(-10f, hf * 0.30f); cubicTo(wf * 0.35f, hf * 0.22f, wf * 0.55f, hf * 0.50f, wf + 10f, hf * 0.40f) },
            Path().apply { moveTo(wf * 0.22f, -10f); cubicTo(wf * 0.30f, hf * 0.40f, wf * 0.52f, hf * 0.60f, wf * 0.58f, hf + 10f) },
            Path().apply { moveTo(wf * 0.80f, -10f); cubicTo(wf * 0.84f, hf * 0.50f, wf * 0.70f, hf * 0.70f, wf * 0.74f, hf + 10f) }
        )
        for (rd in roads) {
            p.pathEffect = null
            p.color = 0xFF252E3B.toInt(); p.strokeWidth = 8f.dp
            c.drawPath(rd, p)
            p.color = 0xFF323E4F.toInt(); p.strokeWidth = 1f.dp
            p.pathEffect = DashPathEffect(floatArrayOf(10f.dp, 8f.dp), 0f)
            c.drawPath(rd, p)
        }
        p.pathEffect = null

        // route: casing + accent line
        p.color = 0x99000000.toInt(); p.strokeWidth = 11f.dp
        c.drawPath(route, p)
        p.color = Palette.accent; p.strokeWidth = 6f.dp
        c.drawPath(route, p)
        p.style = Paint.Style.FILL
        return bmp
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) anim?.pause() else anim?.resume()
    }

    override fun onDraw(c: Canvas) {
        if (width == 0 || height == 0) return
        if (cache == null || cacheAccent != Palette.accent) {
            cache?.recycle()
            cache = render(width, height)
        }
        c.drawBitmap(cache!!, 0f, 0f, null)

        // marker: expanding ring, halo, dot
        p.style = Paint.Style.FILL
        val ringR = (14f + 30f * pulse).dp
        p.color = Palette.withAlpha(Palette.accent, 0.32f * (1f - pulse))
        c.drawCircle(mx, my, ringR, p)
        p.color = Palette.withAlpha(Palette.accent, 0.35f)
        c.drawCircle(mx, my, 17f.dp, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(mx, my, 10f.dp, p)
        p.color = Palette.accent
        c.drawCircle(mx, my, 6f.dp, p)
    }
}
