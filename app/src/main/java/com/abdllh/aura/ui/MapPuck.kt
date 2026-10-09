package com.abdllh.aura.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import com.abdllh.aura.util.dp

/**
 * The car's position on the map: an accent dot with a heading arrow and a soft halo. Each time the home screen comes
 * to the front the halo pulses a few times and then rests, so an idle home screen does not redraw every frame.
 */
class MapPuck(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Path()
    private var pulse = 1f        // 1 = resting (the expanding ring has faded out)
    private var anim: ValueAnimator? = null

    private fun pulseFew() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            repeatCount = 2
            startDelay = 600
            interpolator = LinearInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) pulseFew() else { anim?.cancel(); pulse = 1f }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val x = width / 2f
        val y = height / 2f
        val d = 1f.dp
        p.color = Palette.withAlpha(Palette.accent, 0.26f * (1f - pulse))
        c.drawCircle(x, y, (16f + 30f * pulse) * d, p)
        p.color = Palette.withAlpha(Palette.accent, if (Palette.dark) 0.20f else 0.16f)
        c.drawCircle(x, y, 24f * d, p)
        p.color = Palette.withAlpha(0xFF000000.toInt(), if (Palette.dark) 0.45f else 0.18f)
        c.drawCircle(x, y + 1.5f * d, 14.5f * d, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(x, y, 14f * d, p)
        p.color = Palette.accent
        c.drawCircle(x, y, 11f * d, p)
        arrow.reset()
        arrow.moveTo(x, y - 6.5f * d)
        arrow.lineTo(x + 5f * d, y + 5.5f * d)
        arrow.lineTo(x, y + 3f * d)
        arrow.lineTo(x - 5f * d, y + 5.5f * d)
        arrow.close()
        p.color = 0xFFFFFFFF.toInt()
        c.drawPath(arrow, p)
    }

    companion object {
        const val SIZE_DP = 96
    }
}
