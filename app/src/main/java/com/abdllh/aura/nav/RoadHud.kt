package com.abdllh.aura.nav

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.LimitSign
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lift
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

    private val sign = LimitSign(ctx, if (small) LimitSign.HOME else LimitSign.SPEED)
    private val icon: ImageView = ctx.iconView(R.drawable.ic_speed_camera, if (small) 22 else 26, 0xFFFFFFFF.toInt())
    private val text: AText = ctx.label(if (small) Type.FOOTNOTE else Type.LABEL, 0xFFFFFFFF.toInt())
    private val chip = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(Space.M.dp, 0, Space.L.dp, 0)
        lift(Elev.FLOAT, overMap = true)
        visibility = View.GONE
        addView(icon)
        addView(text, lp(WRAP, WRAP).apply { marginStart = Space.S.dp })
    }
    private var shownSpot: MapExtras.Spot? = null
    private val listener: () -> Unit = { sync() }
    private val arabic get() = Locale.getDefault().language == "ar"

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // (the chip first: this sits at the end edge of the screen, and the sign is the one at the edge)
        if (parts != Parts.SIGN) addView(chip, lp(WRAP, (if (small) 44 else 52).dp).apply { if (parts == Parts.BOTH) marginEnd = Space.M.dp })
        if (parts != Parts.ALERT) addView(sign, lp(WRAP, WRAP))
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
        // the sign comes in and goes like the chip, not in one frame
        val limitShown = sign.visibility == View.VISIBLE && sign.getTag(R.id.aura_exiting) != true
        if (s.limit > 0 && !limitShown) sign.enter(scaleFrom = 0.9f)
        else if (s.limit <= 0 && limitShown) sign.exit(scaleTo = 0.9f) { sign.visibility = View.GONE }
        if (s.limit > 0) sign.kmh = s.limit
        val spot = s.spot
        if (spot == null) {
            if (shownSpot != null) {
                shownSpot = null
                chip.exit(scaleTo = 0.96f) { chip.visibility = View.GONE }
            }
            return
        }
        val camera = spot.kind == MapExtras.Kind.CAMERA
        val what = context.getString(if (camera) R.string.road_camera else R.string.road_bump) + (if (camera && spot.kmh > 0) " ${spot.kmh}" else "")
        if (spot !== shownSpot) {
            shownSpot = spot
            // a camera in the colour of a warning that costs money, a bump in the colour of one that costs a jolt (an
            // amber deep enough for the white words on both themes: the dark theme's warn is too light for them)
            chip.background = Shapes.pill(if (camera) Palette.danger else 0xFFC77E00.toInt())
            icon.setImageResource(if (camera) R.drawable.ic_speed_camera else R.drawable.ic_bump)
            // As wide as its longest distance ("600 م"; tabular figures) from the start, so the distance counting
            // down on every fix only redraws the label: a label that changes its width lays the whole screen out again.
            text.layoutParams = text.layoutParams.apply { width = text.paint.measureText("$what · ${Units.distance(600.0, arabic)}").toInt() + 2 }
            chip.enter(scaleFrom = 0.92f)
        }
        text.setIfChanged("$what · ${Units.distance(s.metres.toDouble(), arabic)}")
    }
}
