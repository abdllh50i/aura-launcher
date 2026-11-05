package com.abdllh.aura.nav

import android.location.Location
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.nav.drive.ArrivedActions
import com.abdllh.aura.nav.drive.EtaCard
import com.abdllh.aura.nav.drive.SpeedCard
import com.abdllh.aura.nav.drive.TurnCard
import com.abdllh.aura.nav.drive.TurnWords
import com.abdllh.aura.nav.drive.moveMs
import com.abdllh.aura.nav.drive.showCard
import com.abdllh.aura.nav.drive.up
import com.abdllh.aura.system.DriveState
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.surfaceImage
import com.abdllh.aura.ui.withUnit
import com.abdllh.aura.util.Prefs
import kotlin.math.roundToInt

/**
 * What he watches at speed (SPEC.md section 6.2, simple/CHANGES.md section 4): the scrims, and three cards at x 16..380 with air between them: turn y 48, speed y 268 with Saher inside it when real, ETA y 392. With no trip
 * there is no column to protect: the side scrim goes, and `drive-free` has the speed under the search pill at y 112 and `رجّعها` in the ETA's rect. Everything is built once, and only its content and alpha change, so a fix
 * redraws a number and nothing else.
 *
 * What the panel calls: [build], [sync], [tripChanged], [listen], [onFace], [coveredByCard].
 */
internal class DriveColumn(panel: MapsPanel) : MapsPart(panel) {
    private lateinit var scrimCol: ImageView
    private lateinit var turn: TurnCard
    private lateinit var speed: SpeedCard
    private lateinit var eta: EtaCard
    private lateinit var actions: ArrivedActions
    private var built = false
    private var speedY = SPEED_Y_TRIP

    private var listening = false
    private var covered = false
    private var kmh: Int? = null
    private var gpsLost = false
    private var lastHint = ""
    /** The trip End took off, for 5 s of `رجّعها`: the route stays in memory, and [NavSession] resumes from the car's place. */
    private var kept: Kept? = null
    /** On the arrival's card: the car has stood since it came, so driving off again ends the trip. */
    private var stoodAtArrival = false

    private class Kept(val place: Place, val route: Route, val simKmh: Double)

    /** `رجّعها` is up: on browse it has the rect the map's tools would take. */
    val undoUp: Boolean get() = kept != null

    init {
        // (what is set in a span keeps the colour of its time)
        panel.columnLayer.look { if (built) sync() }
    }

    /** A search or a place card lies over the column: with no trip what is under it steps aside. On a trip no card is over it, whatever is near (section 1: never covered). */
    fun coveredByCard(on: Boolean) {
        covered = on
        if (built) syncCards()
    }

    fun destroy() {
        root.removeCallbacks(undoGone)
    }

    override fun tripChanged(on: Boolean) {
        if (on) {
            turn.forget()
            lastHint = ""
            lastEta = ""
            actions.save.isEnabled = true
            clearUndo()
        }
    }

    override fun onFace(face: MapsPanel.Face) = syncCards()

    override fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        if (on) {
            CarLocation.addListener(onFix)
            RoadWatch.addListener(onRoad)
            DriveState.addListener(onDrive)
            // (what came while it was away; a fix older than the design's 5 s is no fix)
            val l = CarLocation.last
            if (l != null && ageMs(l) < FIX_MAX_AGE_MS) takeFix(l) else gone()
        } else {
            CarLocation.removeListener(onFix)
            RoadWatch.removeListener(onRoad)
            DriveState.removeListener(onDrive)
            root.removeCallbacks(fixGone)
        }
    }

    // ------------------------------------------------------------------------------------------ what it hears
    private val onFix: (Location) -> Unit = { takeFix(it) }
    private val onRoad: () -> Unit = { syncRoad() }
    private val onDrive: () -> Unit = {
        syncCards()
        if (NavSession.state == NavSession.State.ARRIVED) {
            if (DriveState.parked) stoodAtArrival = true
            else if (stoodAtArrival) doneTrip() // ("or moving off again": back to browse)
        }
    }
    private fun ageMs(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L

    private fun takeFix(l: Location) {
        val age = ageMs(l)
        if (age >= FIX_MAX_AGE_MS) return
        root.removeCallbacks(fixGone)
        root.postDelayed(fixGone, FIX_MAX_AGE_MS - age)
        val v = if (l.hasSpeed()) l.speed * 3.6f else null
        // (GPS reports 1-3 km/h for a parked car: that is standing still)
        kmh = v?.let { if (it < 3f) 0 else it.roundToInt() }
        val was = gpsLost
        gpsLost = false
        syncRoad()
        if (was) sync()
    }

    private val fixGone = Runnable { gone() }

    private fun gone() {
        kmh = null
        gpsLost = true
        syncRoad()
        sync()
    }

    // ------------------------------------------------------------------------------------------ the cards
    /** NavSession said something (every fix on a trip: only what changed reaches a view), or the panel is back. */
    override fun sync() {
        if (!built) return
        syncCards()
        when (NavSession.state) {
            NavSession.State.IDLE -> return
            NavSession.State.ARRIVED -> arrived()
            NavSession.State.NAVIGATING -> guiding()
        }
    }

    /** Which cards are up: section 6.2's table. Each at its own place, so a card that goes leaves its gap. */
    private fun syncCards() {
        if (!built) return
        val st = NavSession.state
        val nav = st == NavSession.State.NAVIGATING
        val arrived = st == NavSession.State.ARRIVED
        val freeUp = st == NavSession.State.IDLE && DriveState.moving && !covered
        val tripDelay = if (turn.up) 0L else Tokens2.MS_columnDelay
        scrimCol.showCard(nav || arrived, inMs = Tokens2.MS_cardIn, outMs = Tokens2.MS_cardOut, dy = 0f)
        turn.showCard(nav || arrived, inMs = Tokens2.MS_cardIn, outMs = Tokens2.MS_cardOut, delay = tripDelay)
        eta.showCard(nav || kept != null, inMs = Tokens2.MS_cardIn, outMs = Tokens2.MS_cardOut, delay = tripDelay)
        if (nav) placeSpeed(SPEED_Y_TRIP) else if (freeUp) placeSpeed(SPEED_Y_FREE)
        speed.showCard(nav || freeUp, outMs = Tokens2.MS_cardOut, delay = if (nav) tripDelay else 0L)
        actions.showCard(arrived)
        syncSaher(nav, freeUp)
    }

    /** The speed's place: in the column on a trip, under the search pill with none. Only when a trip begins or ends: a card that is up goes there and does not jump. */
    private fun placeSpeed(y: Int) {
        if (y == speedY) return
        val from = speedY
        speedY = y
        speed.layoutParams = Surface.CARD.at(16, y, 364, 112)
        if (!speed.up || !speed.isShown) return
        speed.translationY = (from - y).toFloat()
        speed.anim(moveMs(Tokens2.MS_cardIn), Motion.MOVE).translationY(0f).start()
    }

    private fun syncSaher(nav: Boolean, freeUp: Boolean) {
        val w = RoadWatch.state
        val s = w.spot
        val near = s != null && w.metres <= SAHER_M && (nav || freeUp)
        speed.saher(if (near) Units.distance(w.metres.toDouble(), Fonts.arabicUi) else null, s?.kind == MapExtras.Kind.BUMP, w.metres <= SAHER_HOT_M)
    }

    private fun syncRoad() {
        if (!built) return
        val w = RoadWatch.state
        speed.set(kmh, w.limit, w.over)
        syncSaher(NavSession.state == NavSession.State.NAVIGATING, NavSession.state == NavSession.State.IDLE && DriveState.moving && !covered)
    }

    private fun guiding() {
        val r = NavSession.route ?: return
        val p = NavSession.progress ?: return
        val ar = Fonts.arabicUi
        val clock = Units.arrival(p.timeLeft, Prefs.clock24, ar)
        when {
            NavSession.rerouting -> {
                turn.message(R.drawable.ic_reroute, R.drawable.ic_reroute, getString(R.string.drive_rerouting), getString(R.string.drive_off_route))
                eta.set("—", getString(R.string.drive_working_time))
            }
            gpsLost -> {
                turn.message(R.drawable.ic_gps_off, R.drawable.ic_gps_off, getString(R.string.drive_no_gps), lastHint.ifBlank { getString(R.string.drive_off_gps) })
                eta.set(lastEta.ifEmpty { clock }, getString(R.string.drive_last_value))
            }
            else -> {
                val step = r.steps[p.next]
                val g = r.guide?.takeIf { it.arabic == ar }
                val told = g?.toldOfStep(p.next) ?: -1
                val destination = destinationName()
                val lines = TurnWords.lines(r, p, ar, destination) { getString(R.string.drive_keep_then, it) }
                lastHint = lines.hint
                val quiet = step.type != "arrive" && p.toNext > TurnWords.QUIET_M
                val parts = Units.distance(p.toNext, ar).split(' ', limit = 2)
                val dist = withUnit(parts[0], parts.getOrElse(1) { "" }, Type.distUnit, Palette.ink2Move, moving = true)
                val exit = if (g != null && told >= 0) g.told[told].ringNumber else step.exit
                // the lanes, while it matters which one the car is in: only real ones, and within 300 m (section 5)
                val real = step.lanes.takeIf { it.size >= 2 && it.any { l -> !l.valid } && it.any { l -> l.valid } && p.toNext < LANES_M }
                turn.manoeuvre(r to p.next, step, exit, !quiet, dist, lines.road, lines.hint, debugLaneList(p) ?: real)
                lastEta = clock
                eta.set(clock, "${Units.spelled(p.timeLeft, ar)} · ${Units.distance(p.remaining, ar)}")
            }
        }
    }

    private var lastEta = ""

    private fun debugLaneList(p: NavSession.Progress): List<Lane>? {
        val d = debugLanes
        if (!BuildConfig.DEBUG || d == null || p.toNext >= LANES_M) return null
        return d.split(',').map { c ->
            val way = when (c.trim().trimEnd('*')) { "l" -> "left"; "r" -> "right"; "sl" -> "slight left"; "sr" -> "slight right"; "u" -> "uturn"; else -> "straight" }
            Lane(listOf(way), c.endsWith("*"), if (c.endsWith("*")) way else "")
        }
    }

    private fun arrived() {
        val r = NavSession.route
        val ar = Fonts.arabicUi
        val name = destinationName()
        val took = (SystemClock.elapsedRealtime() - NavSession.startedAt) / 1000.0
        val line = if (r == null) "" else "${Units.spelled(took, ar)} · ${Units.distance(r.distance, ar)}"
        turn.arrived(NavSession.arrivedAt, getString(R.string.drive_arrived), name.orEmpty(), line)
        if (DriveState.parked) stoodAtArrival = true
    }

    /** (a pin whose address was not found has no name worth announcing: "arrived at Dropped pin") */
    private fun destinationName(): String? = NavSession.destination?.name?.takeIf { it.isNotBlank() && it != getString(R.string.maps_dropped_pin) }

    // ------------------------------------------------------------------------------------------ End and its undo
    /** One touch, no confirm: the trip ends, and the same rect offers it back for 5 s. */
    private fun endNav() {
        val place = NavSession.destination
        val route = NavSession.route
        kept = if (place != null && route != null) Kept(place, route, NavSim.driving) else null
        NavSession.stop()
        leaveMap()
        if (kept != null) {
            eta.ended(true, animate = true)
            root.removeCallbacks(undoGone)
            root.postDelayed(undoGone, Tokens2.MS_undoEnd)
        }
        // (moving: `drive-free` now, not 10 s after this touch)
        panel.freeNow()
        panel.syncNav()
    }

    private fun leaveMap() {
        map.setRoute(null)
        map.setDestination(null)
        map.setCamera(AuraMap.Camera.FOLLOW)
        panel.tools.tripEnded()
    }

    private val undoGone = Runnable {
        kept = null
        syncCards()
        panel.tools.sync()
    }

    private fun clearUndo() {
        root.removeCallbacks(undoGone)
        kept = null
        eta.ended(false, animate = eta.up)
    }

    private fun undo() {
        val k = kept ?: return
        clearUndo()
        NavSession.start(this, k.place, k.route, remember = false)
        if (BuildConfig.DEBUG && k.simKmh > 0) NavSim.start(k.simKmh)
        map.setCamera(AuraMap.Camera.NAVIGATE)
        panel.syncNav()
    }

    /** Done, or moving off from the arrival: back to browse. */
    private fun doneTrip() {
        stoodAtArrival = false
        NavSession.stop()
        leaveMap()
        panel.syncNav()
    }

    /** The place he arrived at goes to his places, kept for the phone's list ([Places.favourites]). */
    private fun savePlace() {
        val p = NavSession.destination ?: return
        if (Places.favourites.none { Geo.distance(it.pos, p.pos) <= SAME_PLACE_M }) Places.favourites = listOf(p) + Places.favourites
        actions.save.isEnabled = false
        panel.toast(getString(R.string.maps_saved))
    }

    // ------------------------------------------------------------------------------------------ UI
    override fun build() {
        val layer = panel.columnLayer
        // scrims, under everything of the panel (section 4): the map under the column is hidden, a label at its edge fades instead of being cut. The side one only on a trip: with none the map runs to the edge
        scrimCol = surfaceImage(R.drawable.scrim_col_night, R.drawable.scrim_col_day)
        layer.addView(scrimCol, at(0, 0, 396, 600))
        layer.addView(surfaceImage(R.drawable.scrim_top_night, R.drawable.scrim_top_day), at(0, 0, 1024, 76))

        turn = TurnCard(this)
        layer.addView(turn, Surface.CARD.at(16, 48, 364, 208))
        speed = SpeedCard(this)
        speedY = SPEED_Y_TRIP
        layer.addView(speed, Surface.CARD.at(16, speedY, 364, 112))
        eta = EtaCard(this, { endNav() }, { undo() })
        layer.addView(eta, Surface.CARD.at(16, 392, 364, 112))
        actions = ArrivedActions(this, { savePlace() }, { doneTrip() })
        layer.addView(actions, Surface.CARD.at(16, 268, 364, 92))
        for (v in arrayOf<View>(scrimCol, turn, speed, eta, actions)) v.visibility = View.INVISIBLE
        built = true
        kept = null
        syncRoad()
    }

    companion object {
        private const val FIX_MAX_AGE_MS = 5000L
        private const val SPEED_Y_TRIP = 268
        private const val SPEED_Y_FREE = 112
        /** The Saher chip shows a spot this near, and it is hot this near. */
        private const val SAHER_M = 1000
        private const val SAHER_HOT_M = 400
        private const val LANES_M = 300.0
        private const val SAME_PLACE_M = 60.0

        /** Debug builds: the next turn's lanes on the sign (`NavDebug --es lanes`): no route here has real ones. */
        @JvmField var debugLanes: String? = null
    }
}
