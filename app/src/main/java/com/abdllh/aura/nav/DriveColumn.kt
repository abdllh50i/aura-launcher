package com.abdllh.aura.nav

import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lift
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

/**
 * The driver's cards over [MapsPanel]: on a trip, and in `drive-free`. Today's look (banner, trip bar, speed disc, arrival), for the driving column's builder to replace (SPEC.md section 6.2): what
 * the panel calls is [build], [sync], [tripChanged], [listen], [onFace], [coveredByCard], [bottomCard], and browse calls nothing here. What the owner cut stays out: no guidance mute, no spoken word from here.
 */
internal class DriveColumn(panel: MapsPanel) : MapsPart(panel) {
    private lateinit var banner: GuidanceBanner
    private lateinit var tripBar: LinearLayout
    private lateinit var tripEta: AText
    private lateinit var tripLeft: AText
    private lateinit var speedBubble: LinearLayout
    private lateinit var speedText: AText
    private lateinit var speedUnit: AText
    private lateinit var speedSign: RoadHud
    private lateinit var cameraMark: CameraMark
    private lateinit var arrivedCard: FrameLayout
    private lateinit var arrivedCaption: AText
    private lateinit var arrivedName: AText
    private lateinit var arrivedBar: View

    private var overShown = false
    private var arrivalShown = 0L
    private var clusterNav: Boolean? = null

    /** A search or a place card lies over the column ([MapsPanel.cardOverColumn]): what is under it steps aside. Nothing of today's look lies there. */
    fun coveredByCard(@Suppress("UNUSED_PARAMETER") on: Boolean) {
    }

    /** The old trip bar or arrival card, for the old locate button's lift. */
    val bottomCard: View? get() = if (tripBar.isOn) tripBar else if (arrivedCard.isOn) arrivedCard else null

    override fun tripChanged(on: Boolean) {
        if (on) banner.reset()
    }

    override fun listen(on: Boolean) {
        if (on) return
        root.removeCallbacks(arrivedTick)
        arrivalShown = 0L // (back on the screen, the arrival line starts stepping again from where the hold is)
    }

    /** Shows the navigation UI for NavSession's state. */
    override fun sync() {
        val st = NavSession.state
        val navigating = st == NavSession.State.NAVIGATING
        banner.reveal(navigating, -16f)
        tripBar.reveal(navigating, 16f, delay = 60)
        speedBubble.reveal(navigating, 0f, scaleFrom = 0.9f)
        arrivedCard.reveal(st == NavSession.State.ARRIVED, 16f, delay = 60)
        if (st == NavSession.State.ARRIVED) showArrival() else root.removeCallbacks(arrivedTick)
        placeEndCluster(navigating)
        if (st == NavSession.State.IDLE) return
        val p = NavSession.progress ?: return
        banner.update()
        // how long is left first (what the driver asks), then how far and when
        tripEta.setIfChanged(GuidanceBanner.styled(Units.duration(p.timeLeft, arabic), 0.5f, Palette.success))
        tripLeft.setIfChanged("${Units.distance(p.remaining, arabic)} · ${getString(R.string.maps_arrival)} ${Units.arrival(p.timeLeft, Prefs.clock24)}")
        speedText.setIfChanged("${p.speedKmh}")
        showOver(RoadWatch.state.over)
    }

    /** Over the street's limit: the speed disc turns red and beats once, not only its digits. */
    private fun showOver(over: Boolean) {
        if (over == overShown) return
        overShown = over
        speedBubble.background = if (over) Shapes.oval(Palette.danger) else Shapes.oval(Palette.glass, Palette.glassStroke)
        val fg = if (over) Palette.onColor(Palette.danger) else Palette.text
        speedText.setTextColor(fg)
        speedUnit.setTextColor(if (over) Palette.withAlpha(fg, 0.8f) else Palette.text2)
        if (over) speedBubble.anim(Motion.XSHORT, Motion.STANDARD).scaleX(1.08f).scaleY(1.08f).withEndAction {
            speedBubble.anim(Motion.MEDIUM, Motion.EMPHASIZED).scaleX(1f).scaleY(1f).start()
        }.start()
    }

    /** The arrival card: where, and a line that runs out in the time left before the trip ends by itself. */
    private fun showArrival() {
        val at = NavSession.arrivedAt
        if (at == arrivalShown) return
        arrivalShown = at
        // (a pin whose address was not found has no name worth announcing: "arrived at Dropped pin")
        val name = NavSession.destination?.name?.takeIf { it.isNotBlank() && it != getString(R.string.maps_dropped_pin) }
        arrivedCaption.setText(if (name == null) R.string.maps_arrived else R.string.maps_arrived_at)
        arrivedName.text = name.orEmpty()
        arrivedName.visibility = if (name == null) View.GONE else View.VISIBLE
        root.removeCallbacks(arrivedTick)
        arrivedBar.animate().cancel()
        arrivedBar.scaleX = arrivedLeft()
        arrivedBar.post { arrivedBar.pivotX = if (rtl) arrivedBar.width.toFloat() else 0f } // (it runs out toward the start)
        root.postDelayed(arrivedTick, ARRIVED_STEP_MS)
    }

    /** The share of the arrival's hold still left. */
    private fun arrivedLeft(): Float =
        (NavSession.ARRIVED_HOLD_MS - (SystemClock.elapsedRealtime() - NavSession.arrivedAt)).coerceAtLeast(0L) / NavSession.ARRIVED_HOLD_MS.toFloat()

    /**
     * The arrival line moves on a step a second, each step a short glide: one animation over the whole hold drew a
     * frame at every vsync for half a minute, on top of the map, for a line moving a few pixels a second.
     */
    private val arrivedTick = object : Runnable {
        override fun run() {
            val left = arrivedLeft()
            arrivedBar.anim(Motion.SHORT, LinearInterpolator()).scaleX(left).start()
            if (left > 0f && NavSession.state == NavSession.State.ARRIVED) root.postDelayed(this, ARRIVED_STEP_MS)
        }
    }

    /**
     * The camera button and the limit sign: while a route is driven the speed is at the screen's end edge and they
     * stand beside and above it; without one the camera button takes the speed's place. Moved by translation (the
     * layout is the driving one), so they glide instead of jumping by their margins.
     */
    private fun placeEndCluster(navigating: Boolean) {
        if (clusterNav == navigating) return
        val animate = clusterNav != null && root.isLaidOut
        clusterNav = navigating
        fun move(v: View, dx: Int, dy: Int) {
            val x = if (navigating) 0f else (if (rtl) -dx else dx).dp.toFloat()
            val y = if (navigating) 0f else dy.dp.toFloat()
            if (animate) v.anim(Motion.MEDIUM, Motion.STANDARD).translationX(x).translationY(y).start()
            else { v.translationX = x; v.translationY = y }
        }
        move(cameraMark, 106, 13)
        move(speedSign, 15, 20)
    }

    private fun endNav() {
        NavSession.stop()
        map.setRoute(null)
        map.setDestination(null)
        map.setCamera(AuraMap.Camera.FOLLOW)
        panel.tools.tripEnded()
        panel.syncNav()
    }

    // ------------------------------------------------------------------------------------------ UI
    override fun build() {
        overShown = false
        arrivalShown = 0L
        clusterNav = null
        val controls = panel.tools.controls
        speedBubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Shapes.oval(Palette.glass, Palette.glassStroke)
            lift(Elev.FLOAT, overMap = true)
            visibility = View.GONE
        }
        speedText = label(Type.DISPLAY_S, Palette.text, gravity = Gravity.CENTER)
        speedBubble.addView(speedText, lp(MATCH, WRAP))
        speedUnit = label(Type.CAPTION, Palette.text2, gravity = Gravity.CENTER).apply { setText(R.string.maps_kmh) }
        speedBubble.addView(speedUnit, lp(MATCH, WRAP))
        controls.addView(speedBubble, flp(92.dp, 92.dp, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 18.dp, MapsPanel.BOTTOM_EDGE.dp + 40.dp); marginEnd = 18.dp })
        // the owner's additions: the street's limit above the speed, and the camera or bump ahead at the top beside the
        // compass (beside the limit it covered the car while navigating)
        speedSign = RoadHud(this, parts = RoadHud.Parts.SIGN)
        controls.addView(speedSign, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 33.dp, MapsPanel.BOTTOM_EDGE.dp + 138.dp); marginEnd = 33.dp })
        controls.addView(RoadHud(this, parts = RoadHud.Parts.ALERT), flp(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, MapsPanel.TOP_EDGE.dp + 31.dp, 94.dp, 0); marginEnd = 94.dp })
        // "a camera here": beside the speed, in reach of the driver's hand (Settings → Navigation hides it); laid out
        // where it stands while driving, moved into the speed's place by translation otherwise ([placeEndCluster])
        cameraMark = CameraMark(this)
        controls.addView(cameraMark, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 122.dp, MapsPanel.BOTTOM_EDGE.dp + 53.dp); marginEnd = 122.dp })

        // ---- the guidance banner, in the search pill's place (both there while one goes and the other comes)
        banner = GuidanceBanner(this)
        panel.columnLayer.addView(banner, flp(panel.cardW, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(Space.L.dp, MapsPanel.TOP_EDGE.dp, Space.L.dp, 0); marginStart = Space.L.dp })

        // ---- bottom start: trip bar / arrived, in one place with the place card
        tripBar = buildTripBar()
        panel.columnLayer.addView(tripBar, bottomCard())
        arrivedCard = buildArrivedCard()
        panel.columnLayer.addView(arrivedCard, bottomCard())
    }

    private fun buildTripBar(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panelFill()
            lift(Elev.FLOAT, overMap = true)
            visibility = View.GONE
            setPaddingRelative(Space.XL.dp, Space.M.dp, Space.M.dp, Space.M.dp)
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // (the time left in green, as on every navigation screen: all is well, this is how long; its unit small)
        tripEta = label(Type.DISPLAY_S, Palette.success).apply { weight = Fonts.MEDIUM }
        tripLeft = label(Type.FOOTNOTE, Palette.text2).apply { fontFeatureSettings = "tnum" }
        t.addView(tripEta, lp(MATCH, WRAP))
        t.addView(tripLeft, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
        c.addView(t, lp(0, WRAP, 1f))
        c.addView(roundIcon(R.drawable.ic_layers, 60, R.string.maps_overview) { panel.tools.overviewOfTrip() }, lp(60.dp, 60.dp))
        // a red word on a red tint, not a red block: stopping is there, but the time left is what draws the eye
        c.addView(ABtn(this).apply {
            kind = ABtn.Kind.DANGER
            size = ABtn.Size.L
            setPadding(Space.L.dp, 0, Space.L.dp, 0)
            setText(R.string.maps_end)
            setOnClickListener { endNav() }
        }, lp(WRAP, Touch.LARGE.dp).apply { marginStart = Space.S.dp })
        return c
    }

    private fun buildArrivedCard(): FrameLayout {
        val c = FrameLayout(this).apply {
            background = panelFill()
            roundedClip(Radius.XL)
            lift(Elev.FLOAT, overMap = true)
            visibility = View.GONE
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(Space.XL.dp, Space.L.dp, Space.M.dp, Space.L.dp)
        }
        row.addView(iconView(R.drawable.ic_check_circle, 34, Palette.success), lp(34.dp, 34.dp))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        arrivedCaption = label(Type.FOOTNOTE, Palette.text2)
        arrivedName = label(Type.TITLE, Palette.text)
        t.addView(arrivedCaption, lp(MATCH, WRAP))
        t.addView(arrivedName, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
        row.addView(t, lp(0, WRAP, 1f).apply { marginStart = Space.M.dp; marginEnd = Space.S.dp })
        row.addView(ABtn(this).apply {
            kind = ABtn.Kind.PRIMARY
            size = ABtn.Size.L
            setText(R.string.maps_done)
            setOnClickListener { endNav() }
        }, lp(WRAP, Touch.LARGE.dp))
        c.addView(row, flp(MATCH, WRAP))
        // the time before the trip ends by itself, running out along the bottom edge
        arrivedBar = View(this).apply { setBackgroundColor(Guide.color()) }
        c.addView(arrivedBar, flp(MATCH, 3.dp, Gravity.BOTTOM))
        return c
    }

    private companion object {
        /** The arrival card's line moves on this often. */
        private const val ARRIVED_STEP_MS = 1000L
    }
}
