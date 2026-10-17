package com.abdllh.aura.nav

import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lift
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
    private val hide = Runnable { strip.exit(dx = toward(6f)) { strip.visibility = View.GONE } }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        val side = if (small) 52 else 66
        strip = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(Space.L.dp, 0, Space.L.dp, 0)
            background = Shapes.glassPressable(Radius.PILL)
            lift(Elev.FLOAT, overMap = true)
            isClickable = true
            pressScale(Motion.PRESS_CONTROL)
            visibility = View.GONE
            setOnClickListener { undo() }
        }
        visibility = if (enabled) View.VISIBLE else View.GONE
        stripText = ctx.label(if (small) Type.FOOTNOTE else Type.BODY, Palette.text)
        strip.addView(stripText, lp(WRAP, WRAP))
        // (the strip first: the button sits at the map's edge, the strip toward its middle)
        addView(strip, lp(WRAP, (side - Space.S).dp).apply { marginEnd = Space.M.dp })
        val button = FrameLayout(ctx).apply {
            background = Shapes.pressable(Shapes.oval(Palette.glass, Palette.glassStroke), Shapes.oval(Palette.mix(Palette.glass, Palette.text, 0.08f), Palette.glassStroke))
            lift(Elev.FLOAT, overMap = true)
            isClickable = true
            pressScale(Motion.PRESS_ICON)
            contentDescription = ctx.getString(R.string.mark_camera)
            setOnClickListener { mark() }
            // the camera in the colour it has on the map
            addView(ctx.iconView(R.drawable.ic_speed_camera, if (small) 24 else 28, Palette.danger)
                .apply { layoutParams = flp((if (small) 24 else 28).dp, (if (small) 24 else 28).dp, Gravity.CENTER) })
        }
        addView(button, lp(side.dp, side.dp))
    }

    /** [dp] toward the button (the strip comes out of it): the end side, which is the left in Arabic. */
    private fun toward(dp: Float) = if (layoutDirection == LAYOUT_DIRECTION_RTL) -dp else dp

    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        // (the switch may have changed in the settings while this screen was away)
        if (v == View.VISIBLE) visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun mark() {
        // Only a fix of now, from the GPS, precise: CarLocation keeps the last fix of the day before until the GPS
        // has found the sky again (the maps open there), and a coarse network fix may stand in for a while; a camera
        // marked with either would be stored at the wrong place and beeped there on every drive. The simulator's and
        // the debug "mock_loc" fixes count as GPS ones.
        val car = CarLocation.last
        val fresh = car != null &&
            (car.provider == LocationManager.GPS_PROVIDER || car.provider == "sim" || car.provider == "mock") &&
            SystemClock.elapsedRealtimeNanos() - car.elapsedRealtimeNanos < FRESH_NS &&
            (!car.hasAccuracy() || car.accuracy <= 30f)
        if (car == null || !fresh) { say(context.getString(R.string.mark_camera_no_gps), undo = false); return }
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
        // in from the button's side; while it shows, only the words change
        if (strip.visibility != View.VISIBLE || strip.getTag(R.id.aura_exiting) == true) strip.enter(dx = toward(10f))
        postDelayed(hide, if (undo) 5000L else 2500L)
    }

    companion object {
        private const val KEY = "markCameraButton"
        /** A fix older than this is not "here" any more (GPS gives one a second; one may come late). */
        private const val FRESH_NS = 5_000_000_000L

        /** The switch (on by default): the button on the maps. */
        var enabled: Boolean
            get() = Prefs.raw.getBoolean(KEY, true)
            set(v) { Prefs.raw.edit().putBoolean(KEY, v).apply() }
    }
}
