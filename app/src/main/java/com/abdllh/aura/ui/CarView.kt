package com.abdllh.aura.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.abdllh.aura.util.dp

/**
 * Stylised top-down car drawn with vector paths (no bitmaps): body, glass, wheels, mirrors, lights.
 * Design space is 200 x 460 units, scaled to fit the view. Tap toggles the light beams; a scan line
 * sweeps over the car on first show.
 */
class CarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val W = 200f
    private val H = 460f
    private val carTop = 48f // car body starts below the light-beam area

    private val body = Path()
    private val roof = Path()
    private val glassF = Path()
    private val glassR = Path()
    private val headL = Path()
    private val headR = Path()
    private val beamL = Path()
    private val beamR = Path()
    private val tail = Path()
    private val wheels = arrayOf(RectF(166f, 72f, 184f, 124f), RectF(166f, 292f, 184f, 344f), RectF(16f, 72f, 34f, 124f), RectF(16f, 292f, 34f, 344f))
    private val mirrors = arrayOf(RectF(171f, 132f, 191f, 148f), RectF(9f, 132f, 29f, 148f))

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private var lights = 0f          // 0..1 headlight intensity
    private var lightsOn = true
    private var scan = -1f           // -1 = idle, 0..1 sweep progress
    private var pulse = 0f           // ambient glow
    private var accentSeen = 0

    private var bodyShader: Shader? = null
    private var glassShader: Shader? = null
    private var roofShader: Shader? = null
    private var groundShader: Shader? = null
    private var beamShader: Shader? = null
    private var tailShader: Shader? = null
    private var scanShader: Shader? = null

    private var ambient: ValueAnimator? = null
    private var lightAnim: ValueAnimator? = null

    init {
        isClickable = true
        buildPaths()
        setOnClickListener { setLights(!lightsOn) }
    }

    private fun mx(x: Float) = W - x

    private fun buildPaths() {
        val ends = arrayOf(floatArrayOf(100f, 6f), floatArrayOf(162f, 40f), floatArrayOf(173f, 120f), floatArrayOf(172f, 310f), floatArrayOf(146f, 402f), floatArrayOf(100f, 416f))
        val segs = arrayOf(
            floatArrayOf(128f, 6f, 150f, 14f), floatArrayOf(168f, 54f, 172f, 80f),
            floatArrayOf(174f, 160f, 174f, 270f), floatArrayOf(170f, 345f, 160f, 385f), floatArrayOf(134f, 414f, 116f, 416f)
        )
        body.reset()
        body.moveTo(ends[0][0], ends[0][1])
        for (i in segs.indices) body.cubicTo(segs[i][0], segs[i][1], segs[i][2], segs[i][3], ends[i + 1][0], ends[i + 1][1])
        for (i in segs.indices.reversed()) body.cubicTo(mx(segs[i][2]), segs[i][3], mx(segs[i][0]), segs[i][1], mx(ends[i][0]), ends[i][1])
        body.close()

        roof.reset()
        roof.addRoundRect(RectF(64f, 174f, 136f, 282f), 16f, 16f, Path.Direction.CW)

        glassF.reset()
        glassF.moveTo(56f, 134f); glassF.cubicTo(72f, 124f, 128f, 124f, 144f, 134f)
        glassF.lineTo(136f, 172f); glassF.cubicTo(120f, 166f, 80f, 166f, 64f, 172f); glassF.close()

        glassR.reset()
        glassR.moveTo(64f, 286f); glassR.cubicTo(80f, 292f, 120f, 292f, 136f, 286f)
        glassR.lineTo(144f, 326f); glassR.cubicTo(126f, 334f, 74f, 334f, 56f, 326f); glassR.close()

        headR.reset()
        headR.moveTo(112f, 10f); headR.cubicTo(132f, 10f, 150f, 19f, 160f, 39f)
        headR.lineTo(151f, 41f); headR.cubicTo(142f, 29f, 128f, 22f, 112f, 20f); headR.close()
        headL.reset()
        headL.moveTo(mx(112f), 10f); headL.cubicTo(mx(132f), 10f, mx(150f), 19f, mx(160f), 39f)
        headL.lineTo(mx(151f), 41f); headL.cubicTo(mx(142f), 29f, mx(128f), 22f, mx(112f), 20f); headL.close()

        // beams reach upward from the lamps into the empty space above the car (local y < 0)
        beamR.reset()
        beamR.moveTo(112f, 12f); beamR.lineTo(158f, 36f); beamR.lineTo(196f, -56f); beamR.lineTo(104f, -56f); beamR.close()
        beamL.reset()
        beamL.moveTo(mx(112f), 12f); beamL.lineTo(mx(158f), 36f); beamL.lineTo(mx(196f), -56f); beamL.lineTo(mx(104f), -56f); beamL.close()

        tail.reset()
        tail.addRoundRect(RectF(58f, 405f, 142f, 413f), 4f, 4f, Path.Direction.CW)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        scale = minOf(w / W, h / H)
        offX = (w - W * scale) / 2f
        offY = (h - H * scale) / 2f
        buildShaders()
    }

    private fun buildShaders() {
        accentSeen = Palette.accent
        bodyShader = LinearGradient(26f, 0f, 174f, 0f, intArrayOf(0xFF161B21.toInt(), 0xFF2F3743.toInt(), 0xFF161B21.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        roofShader = LinearGradient(0f, 174f, 0f, 282f, 0xFF2A323D.toInt(), 0xFF1D232B.toInt(), Shader.TileMode.CLAMP)
        glassShader = LinearGradient(0f, 124f, 0f, 336f, 0xFF05070A.toInt(), 0xFF141A22.toInt(), Shader.TileMode.CLAMP)
        groundShader = RadialGradient(100f, 210f, 215f, intArrayOf(Palette.withAlpha(Palette.accent, 0.34f), Palette.withAlpha(Palette.accent, 0.10f), 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        beamShader = LinearGradient(0f, 12f, 0f, -56f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        tailShader = LinearGradient(58f, 0f, 142f, 0f, intArrayOf(0xFFB3261E.toInt(), 0xFFFF4A40.toInt(), 0xFFB3261E.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        scanShader = LinearGradient(0f, -24f, 0f, 24f, intArrayOf(0x00FFFFFF, Palette.withAlpha(Palette.accent, 0.55f), 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    fun setLights(on: Boolean) {
        lightsOn = on
        lightAnim?.cancel()
        lightAnim = ValueAnimator.ofFloat(lights, if (on) 1f else 0f).apply {
            duration = 420
            interpolator = DecelerateInterpolator()
            addUpdateListener { lights = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Plays the intro: lights fade in and a scan line sweeps the body. */
    fun playIntro() {
        lights = 0f
        setLights(true)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            startDelay = 250
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { scan = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { scan = -1f; invalidate() }
            })
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ambient = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        ambient?.cancel()
        lightAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) ambient?.pause() else ambient?.resume()
    }

    override fun onDraw(c: Canvas) {
        if (accentSeen != Palette.accent) buildShaders()
        c.save()
        c.translate(offX, offY)
        c.scale(scale, scale)

        // ground glow (breathes slowly)
        glow.shader = groundShader
        glow.alpha = (190 + 65 * pulse).toInt()
        c.drawCircle(100f, 210f + carTop, 215f, glow)

        c.save()
        c.translate(0f, carTop)

        // light beams
        if (lights > 0.01f) {
            fill.shader = beamShader
            fill.alpha = (255 * lights).toInt()
            c.drawPath(beamL, fill)
            c.drawPath(beamR, fill)
        }

        // wheels
        fill.shader = null
        fill.color = 0xFF06080A.toInt()
        line.color = 0xFF2B333D.toInt()
        for (r in wheels) { c.drawRoundRect(r, 7f, 7f, fill); c.drawRoundRect(r, 7f, 7f, line) }

        // body
        fill.shader = bodyShader; fill.alpha = 255
        c.drawPath(body, fill)
        fill.shader = null
        line.color = 0xE64B586A.toInt()
        c.drawPath(body, line)

        // creases
        line.color = 0x1AFFFFFF
        c.drawLine(100f, 22f, 100f, 118f, line)
        c.drawLine(72f, 34f, 72f, 116f, line)
        c.drawLine(128f, 34f, 128f, 116f, line)
        c.drawLine(100f, 342f, 100f, 400f, line)

        // roof + glass
        fill.shader = roofShader
        c.drawPath(roof, fill)
        line.color = 0x664B586A
        c.drawPath(roof, line)
        fill.shader = glassShader
        c.drawPath(glassF, fill); c.drawPath(glassR, fill)
        line.color = 0x8C5A6778.toInt()
        c.drawPath(glassF, line); c.drawPath(glassR, line)
        fill.shader = null
        fill.color = 0x0FFFFFFF
        c.drawPath(glassF, fill) // subtle sheen

        // mirrors
        fill.shader = bodyShader
        for (m in mirrors) { c.drawRoundRect(m, 6f, 6f, fill); }
        fill.shader = null
        line.color = 0xCC4B586A.toInt()
        for (m in mirrors) c.drawRoundRect(m, 6f, 6f, line)

        // lamps
        fill.shader = null
        fill.color = lerpColor(0xFF59636F.toInt(), 0xFFFFFFFF.toInt(), lights)
        c.drawPath(headL, fill); c.drawPath(headR, fill)
        fill.shader = tailShader
        fill.alpha = (130 + 125 * lights).toInt()
        c.drawPath(tail, fill)

        // scan sweep clipped to the body
        if (scan >= 0f) {
            c.save()
            c.clipPath(body)
            val y = -24f + (416f + 48f) * scan
            c.translate(0f, y)
            fill.shader = scanShader; fill.alpha = 255
            c.drawRect(0f, -24f, W, 24f, fill)
            c.restore()
        }
        fill.shader = null
        c.restore()
        c.restore()
    }

    private fun lerpColor(a: Int, b: Int, t: Float) = Palette.mix(a, b, t)

    override fun onTouchEvent(e: MotionEvent): Boolean = super.onTouchEvent(e)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) 260.dp else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }
}
