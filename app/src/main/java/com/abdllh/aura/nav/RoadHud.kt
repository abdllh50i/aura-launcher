package com.abdllh.aura.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.elevate
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp
import java.util.Locale

/**
 * On a map screen, what [RoadWatch] knows about the road: the street's limit as its sign (red when the car is over
 * it), and beside it the camera or the bump coming up with how far it is. Nothing shows when there is nothing to say.
 * [small]: the home screen's map, where everything is a size down. [parts]: the Maps screen shows the two apart (the
 * sign with the speed, the warning at the top), so that neither covers the car.
 */
class RoadHud(ctx: Context, private val small: Boolean = false, parts: Parts = Parts.BOTH) : LinearLayout(ctx) {
    enum class Parts { BOTH, SIGN, ALERT }

    private val sign = LimitSign(ctx)
    private val icon: ImageView = ctx.iconView(R.drawable.ic_speed_camera, if (small) 22 else 26, 0xFFFFFFFF.toInt())
    private val text: AText = ctx.label(if (small) 15.5f else 18f, 0xFFFFFFFF.toInt(), Fonts.MEDIUM)
    private val chip = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(14.dp, 0, 18.dp, 0)
        elevate(8f)
        visibility = View.GONE
        addView(icon)
        addView(text, lp(WRAP, WRAP).apply { marginStart = 9.dp })
    }
    private var shownSpot: MapExtras.Spot? = null
    private val listener: () -> Unit = { sync() }
    private val arabic get() = Locale.getDefault().language == "ar"

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // (the chip first: this sits at the end edge of the screen, and the sign is the one at the edge)
        if (parts != Parts.SIGN) addView(chip, lp(WRAP, (if (small) 44 else 52).dp).apply { if (parts == Parts.BOTH) marginEnd = 10.dp })
        val s = (if (small) 54 else 62).dp
        if (parts != Parts.ALERT) addView(sign, lp(s, s))
        sign.visibility = View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RoadWatch.addListener(listener)
        sync()
    }

    override fun onDetachedFromWindow() {
        RoadWatch.removeListener(listener)
        super.onDetachedFromWindow()
    }

    private fun sync() {
        val s = RoadWatch.state
        sign.visibility = if (s.limit > 0) View.VISIBLE else View.GONE
        sign.kmh = s.limit
        sign.over = s.over
        val spot = s.spot
        if (spot == null) {
            if (shownSpot != null) {
                shownSpot = null
                chip.animate().alpha(0f).setDuration(Motion.SHORT).withEndAction { if (shownSpot == null) chip.visibility = View.GONE }.start()
            }
            return
        }
        val camera = spot.kind == MapExtras.Kind.CAMERA
        // a camera in the colour of a warning that costs money, a bump in the colour of one that costs a jolt
        val color = if (camera) Palette.danger else 0xFFC77E00.toInt()
        if (spot !== shownSpot) {
            shownSpot = spot
            chip.background = Shapes.rect(color, 26f)
            icon.setImageResource(if (camera) R.drawable.ic_speed_camera else R.drawable.ic_bump)
            chip.animate().cancel()
            chip.visibility = View.VISIBLE
            chip.alpha = 0f
            chip.scaleX = 0.92f; chip.scaleY = 0.92f
            chip.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.EMPHASIZED).withEndAction(null).start()
        }
        val what = context.getString(if (camera) R.string.road_camera else R.string.road_bump) + (if (camera && spot.kmh > 0) " ${spot.kmh}" else "")
        text.text = "$what · ${Units.distance(s.metres.toDouble(), arabic)}"
    }

    /** The limit as the road sign everybody knows: a white disc in a red ring; the ring fills in when the car is over it. */
    private class LimitSign(ctx: Context) : View(ctx) {
        var kmh = 0
            set(v) { if (v != field) { field = v; invalidate() } }
        var over = false
            set(v) { if (v != field) { field = v; invalidate() } }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Fonts.get(Fonts.MEDIUM) }

        init { elevation = 6f.dp }

        override fun onDraw(c: Canvas) {
            if (kmh <= 0) return
            val r = minOf(width, height) / 2f
            val cx = width / 2f
            val cy = height / 2f
            p.style = Paint.Style.FILL
            p.color = 0x33000000
            c.drawCircle(cx, cy + 1.5f.dp, r - 1f.dp, p)
            p.color = if (over) 0xFFE5484D.toInt() else 0xFFFFFFFF.toInt()
            c.drawCircle(cx, cy, r - 2f.dp, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = r * 0.2f
            p.color = 0xFFE5484D.toInt()
            c.drawCircle(cx, cy, r - 2f.dp - p.strokeWidth / 2, p)
            t.color = if (over) 0xFFFFFFFF.toInt() else 0xFF16181C.toInt()
            t.textSize = r * (if (kmh >= 100) 0.72f else 0.86f)
            c.drawText(kmh.toString(), cx, cy - (t.descent() + t.ascent()) / 2, t)
        }
    }
}
