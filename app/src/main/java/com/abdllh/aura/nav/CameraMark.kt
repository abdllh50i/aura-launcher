package com.abdllh.aura.nav

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.elevate
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

/**
 * "A camera here", on the maps: one press while passing a speed camera marks it where the car is ([MapExtras.addMine]);
 * from then on it is on the map and announced like the ones the editor published. A strip beside the button says what
 * happened, and takes it back when tapped. Shown while its switch is on (Settings → Navigation).
 * [small]: the home screen's map.
 */
class CameraMark(ctx: Context, small: Boolean = false) : LinearLayout(ctx) {
    private val strip: LinearLayout
    private val stripText: AText
    private var last: MapExtras.Spot? = null
    private val hide = Runnable { strip.animate().alpha(0f).setDuration(Motion.SHORT).withEndAction { strip.visibility = View.GONE }.start() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        val side = if (small) 52 else 66
        strip = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(18.dp, 0, 18.dp, 0)
            background = Shapes.glassPressable(28f)
            elevate(8f)
            isClickable = true
            pressScale(0.96f)
            visibility = View.GONE
            setOnClickListener { undo() }
        }
        visibility = if (enabled) View.VISIBLE else View.GONE
        stripText = ctx.label(if (small) 15f else 16.5f, Palette.text, Fonts.MEDIUM)
        strip.addView(stripText, lp(WRAP, WRAP))
        // (the strip first: the button sits at the map's edge, the strip toward its middle)
        addView(strip, lp(WRAP, (side - 8).dp).apply { marginEnd = 10.dp })
        val button = FrameLayout(ctx).apply {
            background = Shapes.pressable(Shapes.oval(Palette.glass), Shapes.oval(Palette.mix(Palette.glass, Palette.text, 0.08f)))
            elevate(6f)
            isClickable = true
            pressScale(0.9f)
            contentDescription = ctx.getString(R.string.mark_camera)
            setOnClickListener { mark() }
            // the camera in the colour it has on the map
            addView(ctx.iconView(R.drawable.ic_speed_camera, if (small) 24 else 28, Palette.danger)
                .apply { layoutParams = flp((if (small) 24 else 28).dp, (if (small) 24 else 28).dp, Gravity.CENTER) })
        }
        addView(button, lp(side.dp, side.dp))
    }

    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        // (the switch may have changed in the settings while this screen was away)
        if (v == View.VISIBLE) visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun mark() {
        val car = CarLocation.last
        if (car == null) { say(context.getString(R.string.mark_camera_no_gps), undo = false); return }
        // its speed: the limit of the marked street the car is on, when there is one
        val (spot, added) = MapExtras.addMine(LatLon(car.latitude, car.longitude), RoadWatch.state.limit)
        if (!added) { say(context.getString(R.string.mark_camera_known), undo = false); return }
        RoadWatch.quiet(spot)
        last = spot
        say(context.getString(R.string.mark_camera_saved), undo = true)
    }

    private fun undo() {
        val s = last ?: return
        last = null
        MapExtras.removeMine(s)
        say(context.getString(R.string.mark_camera_undone), undo = false)
    }

    private fun say(text: String, undo: Boolean) {
        removeCallbacks(hide)
        if (!undo) last = null
        stripText.text = text
        strip.animate().cancel()
        if (strip.visibility != View.VISIBLE) {
            strip.visibility = View.VISIBLE
            strip.alpha = 0f
            strip.translationX = (if (layoutDirection == LAYOUT_DIRECTION_RTL) -10 else 10).dp.toFloat()
            strip.animate().alpha(1f).translationX(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.EMPHASIZED).withEndAction(null).start()
        } else {
            strip.alpha = 1f
            strip.translationX = 0f
        }
        postDelayed(hide, if (undo) 5000L else 2500L)
    }

    companion object {
        private const val KEY = "markCameraButton"

        /** The switch (on by default): the button on the maps. */
        var enabled: Boolean
            get() = Prefs.raw.getBoolean(KEY, true)
            set(v) { Prefs.raw.edit().putBoolean(KEY, v).apply() }
    }
}
