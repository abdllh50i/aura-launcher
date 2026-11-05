package com.abdllh.aura.nav

import android.location.Location
import android.os.SystemClock
import android.view.View
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.chrome.Chrome
import com.abdllh.aura.home.Screens
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.media.NowPlaying
import com.abdllh.aura.music.Backdrop
import com.abdllh.aura.nav.drive.ArrivedActions
import com.abdllh.aura.nav.drive.EtaCard
import com.abdllh.aura.nav.drive.MiniCard
import com.abdllh.aura.nav.drive.SaherCard
import com.abdllh.aura.nav.drive.SpeedCard
import com.abdllh.aura.nav.drive.TurnCard
import com.abdllh.aura.nav.drive.TurnWords
import com.abdllh.aura.nav.drive.showCard
import com.abdllh.aura.nav.drive.up
import com.abdllh.aura.system.DriveState
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.surfaceImage
import com.abdllh.aura.ui.withUnit
import com.abdllh.aura.util.Prefs
import kotlin.math.roundToInt

/**
 * What he watches at speed (SPEC.md section 6.2): the scrims, and the driver's cards at x 12..376, each at its own place, which none of them leaves when another comes or goes: turn y 44, speed y 242, Saher beside it,
 * ETA y 338, mini y 426. Trip or `drive-free`; browse keeps the scrims and the mini. Everything is built once, laid out once, and only its content and alpha change, so a fix redraws a number and nothing else.
 *
 * What the panel calls: [build], [sync], [tripChanged], [listen], [onFace], [coveredByCard].
 */
internal class DriveColumn(panel: MapsPanel) : MapsPart(panel) {
    private lateinit var turn: TurnCard
    private lateinit var speed: SpeedCard
    private lateinit var saher: SaherCard
    private lateinit var eta: EtaCard
    private lateinit var actions: ArrivedActions
    private lateinit var mini: MiniCard
    private var built = false

    private var listening = false
    private var covered = false
    private var song: NowPlaying? = null
    private var kmh: Int? = null
    private var gpsLost = false
    private var lastHint = ""
    /** The trip End took off, for 5 s of `رجّعها`: the route stays in memory, and [NavSession] resumes from the car's place. */
    private var kept: Kept? = null
    /** On the arrival's card: the car has stood since it came, so driving off again ends the trip. */
    private var stoodAtArrival = false

    private class Kept(val place: Place, val route: Route, val simKmh: Double)

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
        if (built) mini.destroy()
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
            MediaMonitor.observe(onSong)
            // (what came while it was away; a fix older than the design's 5 s is no fix)
            val l = CarLocation.last
            if (l != null && ageMs(l) < FIX_MAX_AGE_MS) takeFix(l) else gone()
        } else {
            CarLocation.removeListener(onFix)
            RoadWatch.removeListener(onRoad)
            DriveState.removeListener(onDrive)
            MediaMonitor.unobserve(onSong)
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
    private val onSong: (NowPlaying?) -> Unit = { n ->
        song = n
        if (built) {
            n?.let { mini.bind(it) }
            syncCards()
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
        turn.showCard(nav || arrived, inMs = Tokens2.MS_cardIn, outMs = Tokens2.MS_cardOut, delay = tripDelay)
        eta.showCard(nav || kept != null, inMs = Tokens2.MS_cardIn, outMs = Tokens2.MS_cardOut, delay = tripDelay)
        speed.showCard(nav || freeUp, outMs = Tokens2.MS_cardOut, delay = if (nav) tripDelay else 0L)
        actions.showCard(arrived)
        mini.showCard(song != null && !(covered && st == NavSession.State.IDLE))
        syncSaher(nav, freeUp)
    }

    private fun syncSaher(nav: Boolean, freeUp: Boolean) {
        val w = RoadWatch.state
        val s = w.spot
        val show = s != null && w.metres <= SAHER_M && (nav || freeUp)
        if (s != null && show) saher.set(s.kind == MapExtras.Kind.BUMP, Units.distance(w.metres.toDouble(), Fonts.arabicUi), w.metres <= SAHER_HOT_M)
        saher.showCard(show)
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
        if (built) mini.destroy()
        val layer = panel.columnLayer
        // scrims, under everything of the panel (section 4): the map under the column is hidden, a label at its edge fades instead of being cut
        layer.addView(surfaceImage(R.drawable.scrim_col_night, R.drawable.scrim_col_day), at(0, 0, 392, 600))
        layer.addView(surfaceImage(R.drawable.scrim_top_night, R.drawable.scrim_top_day), at(0, 0, 1024, 76))
        layer.addView(surfaceImage(R.drawable.scrim_bot_night, R.drawable.scrim_bot_day), at(0, 482, 1024, 34))

        turn = TurnCard(this)
        layer.addView(turn, Surface.CARD.at(12, 44, 364, 190))
        speed = SpeedCard(this)
        layer.addView(speed, Surface.CARD.at(12, 242, 232, 88))
        saher = SaherCard(this)
        layer.addView(saher, Surface.CARD.at(252, 242, 124, 88))
        eta = EtaCard(this, { endNav() }, { undo() })
        layer.addView(eta, Surface.CARD.at(12, 338, 364, 80))
        actions = ArrivedActions(this, { savePlace() }, { doneTrip() })
        layer.addView(actions, Surface.CARD.at(12, 242, 364, 92))
        val m = Backdrop.CARD_MARGIN
        // (the words: the dock's own way to Music, whatever a trip makes of it. The cover: the source sheet, section 6.4)
        mini = MiniCard(this, { Chrome.of(panel.shell)?.open(Screens.MUSIC) ?: Unit }, { com.abdllh.aura.music.MusicPanel.openSources(panel.shell) })
        layer.addView(mini, at(12 - m, 426 - m, 364 + 2 * m, 80 + 2 * m))
        for (v in arrayOf<View>(turn, speed, saher, eta, actions, mini)) v.visibility = View.INVISIBLE
        built = true
        song?.let { mini.bind(it) }
        kept = null
        syncRoad()
    }

    companion object {
        private const val FIX_MAX_AGE_MS = 5000L
        /** The Saher card shows a spot this near, and it is hot this near. */
        private const val SAHER_M = 1000
        private const val SAHER_HOT_M = 400
        private const val LANES_M = 300.0
        private const val SAME_PLACE_M = 60.0

        /** Debug builds: the next turn's lanes on the sign (`NavDebug --es lanes`): no route here has real ones. */
        @JvmField var debugLanes: String? = null
    }
}
