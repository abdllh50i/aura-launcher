package com.abdllh.aura.nav.guide

import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Turn
import com.abdllh.aura.nav.Units
import com.abdllh.aura.system.PrayerTimes

/** A manoeuvre the driver is told about. [road]: where it leads. [sameRoad]: it stays on the road the car is on, so the voice need not name it. */
class Told(
    val index: Int,
    val step: GuideStep,
    val road: RoadName,
    val sameRoad: Boolean,
    val sign: ExitSign?,
    /** The junction number of the signal this manoeuvre is at, -1 when it is at none. */
    val signalJunction: Int,
    val mark: Mark?,
    /** A roundabout: the number that may be said, 0 when only the direction. */
    val ringNumber: Int
)

/** What names a manoeuvre, beside its action. Signals are not here: their count depends on where the car is ([Guidance.spoken]). */
sealed class Mark {
    class AfterStructure(val tunnel: Boolean) : Mark()
    class OntoStructure(val tunnel: Boolean) : Mark()
    class Place(val mark: PoiMark) : Mark()
}

/**
 * The sign for a manoeuvre. [action]: its main line, landmark and all; [road]: the road or the ferry as its own line; [shield]: its number, when it
 * has a name too; [toward]: the sign's places; [exit]: the exit's number on the sign, also one the voice cannot read ("3B"). [ringNumber], [ringAngle]: for
 * [com.abdllh.aura.nav.ManeuverView]'s ring, 0 and NaN when not.
 */
class Banner(val action: String, val road: String, val shield: String, val toward: String, val exit: String, val turn: Turn, val ringNumber: Int, val ringAngle: Float)

/**
 * The guidance of one route for one language: every told manoeuvre's words, its landmarks, and what is on the road, from the
 * route's answer and the place data. [build] is slow (it asks the world): on the worker, once per route. What it holds after that is pure
 * and cheap: the banner and the voice ask with the car's distance along the line.
 *
 * The rule's order for a turn (maps.md 13 item 3): the leg's signals counted from where the car is, a roundabout, a flyover, a tunnel,
 * and a place on the corner only when there is no road name to read. Parts instead of text ([Part]): a clip pack changes nothing here.
 */
class Guidance internal constructor(
    val route: GuideRoute,
    val arabic: Boolean,
    val told: List<Told>,
    /** Cameras, signals and bumps on the route, going its way, by distance. */
    val spots: List<AlongSpot>,
    /** Checks to show before the trip starts. */
    val warnings: List<TripWarning>,
    private val stops: StopsAlong,
    private val parkingRows: List<PoiRow>,
    /** What was asked for, not where the road ends: the voice and the arrival card must say one side. */
    private val destination: LatLon? = null
) {
    private val signals = spots.filter { it.kind == SpotRow.SIGNAL }
    private val endSide by lazy { ArrivalCard.of(route, destination, emptyList()).side }

    /**
     * How many signal junctions come from [along] up to the one at this manoeuvre, that one counted: 2 is "the second". Nodes
     * within 60 m are one junction (`spot.j`), and the tester's point stands: 427 nodes are 137 junctions in his box.
     */
    fun signalCount(t: Told, along: Double): Int {
        if (t.signalJunction < 0) return 0
        val seen = HashSet<Int>()
        for (s in signals) {
            if (s.along <= along) continue
            if (s.along >= t.step.at) break
            if (s.junction != t.signalJunction) seen.add(s.junction)
        }
        // (a junction the car is in the middle of has nodes behind and ahead: it is not still to come)
        for (s in signals) if (s.along <= along && s.along >= along - JUNCTION_M) seen.remove(s.junction)
        return seen.size + 1
    }

    /** Which told step is [GuideRoute.steps]' [stepIndex], -1 for a silent one. */
    fun toldOfStep(stepIndex: Int): Int = told.indexOfFirst { it.index == stepIndex }

    /** The told step a distance along the route is heading for, -1 past the last. */
    fun nextTold(along: Double): Int = told.indexOfFirst { it.step.at + it.step.span > along + 3 }

    /** The arrow's distance: a roundabout's to its exit once the car is on it. */
    fun metresTo(t: Told, along: Double): Double = ((if (along > t.step.at) t.step.at + t.step.span else t.step.at) - along).coerceAtLeast(0.0)

    /** The action with its landmark, as a sign or a voice has it. */
    private fun doing(t: Told, along: Double, saysDistance: Boolean): List<Part> {
        val out = ArrayList<Part>(6)
        val act = action(t)
        val count = signalCount(t, along)
        when {
            t.step.entersRing -> out.addAll(act)
            // (four or more to go: no ordinal to say, and "at the signal" would read as the next one)
            count in 1..3 -> {
                out.add(Part.Clip(when (count) { 1 -> "signal.next"; 2 -> "signal.2"; else -> "signal.3" }))
                out.addAll(act)
            }
            else -> when (val m = t.mark) {
                is Mark.AfterStructure -> {
                    out.add(Part.Clip(if (m.tunnel) "tunnel.after" else "bridge.after"))
                    out.addAll(act)
                }
                is Mark.OntoStructure -> {
                    out.addAll(act)
                    out.add(Part.Clip(if (m.tunnel) "tunnel.into" else "bridge.onto"))
                }
                is Mark.Place -> {
                    val rel = when (m.mark.relation) { Relation.BEFORE_IT -> "poi.before"; Relation.AT_IT -> "poi.at"; Relation.AFTER_IT -> "poi.after" }
                    // ("بعد 800 متر، بعد محطة الدريس" twice over: then "بعد 800 متر، لف يمين بعد محطة الدريس")
                    if (saysDistance && m.mark.relation == Relation.AFTER_IT) {
                        out.addAll(act)
                        out.add(Part.Clip(rel))
                        out.addAll(m.mark.phrase)
                    } else {
                        out.add(Part.Clip(rel))
                        out.addAll(m.mark.phrase)
                        out.add(Part.Clip("pause.en"))
                        out.addAll(act)
                    }
                }
                null -> out.addAll(act)
            }
        }
        return out
    }

    /** What to do, without a landmark: one or a few words of the table. */
    private fun action(t: Told): List<Part> {
        val s = t.step
        val m = s.modifier
        val left = m.contains("left")
        val right = m.contains("right")
        fun clips(vararg ids: String) = ids.map { Part.Clip(it) }
        fun turn(): List<Part> = clips(when (m) {
            "left" -> "turn.left"
            "right" -> "turn.right"
            "slight left" -> "turn.slight_left"
            "slight right" -> "turn.slight_right"
            "sharp left" -> "turn.sharp_left"
            "sharp right" -> "turn.sharp_right"
            "uturn" -> "uturn.gap"
            else -> "straight"
        })
        if (s.type == "arrive") return clips(endSide.word)
        if (s.onFerry) return clips("ferry.board")
        val afterFerry = route.steps.getOrNull(t.index - 1)?.onFerry == true
        if (afterFerry) return clips("ferry.leave")
        return when (s.type) {
            "depart" -> clips("depart")
            "fork" -> clips(if (left) "keep.left" else if (right) "keep.right" else "straight")
            "merge" -> clips(if (left) "merge.left" else if (right) "merge.right" else "merge")
            "on ramp" -> clips(if (left) "ramp.in.left" else if (right) "ramp.in.right" else "ramp.in")
            "off ramp" -> {
                val n = t.sign?.number.orEmpty()
                val side = if (left) "side.left" else if (right) "side.right" else null
                if (n.isNotEmpty() && n.all { it.isDigit() }) clips("ramp.number", "num.$n") + listOfNotNull(side).map { Part.Clip(it) }
                else clips(if (left) "ramp.out.left" else if (right) "ramp.out.right" else "ramp.out")
            }
            "end of road" -> clips("end.road") + turn()
            "roundabout", "rotary", "roundabout turn" -> ringParts(t)
            "exit roundabout", "exit rotary" -> clips("ring.leave")
            else -> turn()
        }
    }

    private fun ringParts(t: Told): List<Part> {
        val out = ArrayList<Part>(4)
        val name = t.step.rotaryName.takeIf { it.isNotEmpty() && Names.hasArabic(it) == arabic && it.length <= 24 }
        if (name != null) {
            out.add(Part.Name(name, "ring.at.named"))
            out.add(Part.Clip("pause.en"))
        } else out.add(Part.Clip("ring.at"))
        if (t.ringNumber > 0) out.add(Part.Clip("ring.exit.${t.ringNumber}"))
        else out.add(Part.Clip(Ring.wordFor(Ring.way(t.step.angle)) ?: return listOf(Part.Clip("ring.enter"))))
        return out
    }

    /**
     * What the voice says of told step [i] with the car at [along]: "بعد 500 متر، عند الإشارة الثانية لف يمين على طريق الملك فهد",
     * and [withThen] the manoeuvre right after it when that one follows closely. Under [SAY_NOTHING_M] no distance, and none
     * with a landmark in sight: the signal or the corner says where.
     */
    fun spoken(i: Int, along: Double, withThen: Boolean = true): List<Part> {
        val t = told.getOrNull(i) ?: return emptyList()
        val metres = metresTo(t, along)
        val out = ArrayList<Part>(10)
        // a median U-turn said as a place ahead: he has to drive on to it, and the sign's arrow already bends
        if (t.step.modifier == "uturn" && metres >= UTURN_AHEAD_M) {
            out.add(Part.Clip("uturn.ahead"))
            out.add(Part.Clip("in"))
            out.add(Part.Clip(Words.distanceId(metres)))
            return out
        }
        val inSight = metres < IN_SIGHT_M && (signalCount(t, along) in 1..3 || t.step.entersRing || t.mark != null)
        val saysDistance = metres >= SAY_NOTHING_M && !inSight
        if (saysDistance) {
            out.add(Part.Clip("in"))
            out.add(Part.Clip(Words.distanceId(metres)))
            out.add(Part.Clip("pause"))
        }
        out.addAll(doing(t, along, saysDistance))
        val next = told.getOrNull(i + 1)?.takeIf { withThen && it.step.type != "arrive" && it.step.at - (t.step.at + t.step.span) < THEN_M }
        // (with what follows, the road's name goes: there is no time for both, and the sign shows it)
        if (next == null) roadPart(t)?.let { out.add(it) }
        else {
            out.add(Part.Clip("pause"))
            out.add(Part.Clip("then"))
            out.addAll(action(next))
        }
        return out
    }

    /** The road the voice names, when it may, with the word that leads to it. */
    private fun roadPart(t: Told): Part.Name? {
        val s = t.step
        if (s.type == "arrive" || s.type == "depart" || s.onFerry || s.modifier == "uturn") return null
        if (t.mark is Mark.Place) return null // (a place was chosen because there is no name to read)
        // a town on the sign is what the driver looks for
        t.sign?.place?.let { return Part.Name(it, "toward") }
        if (t.sameRoad) return null
        val say = t.road.spoken ?: return null
        // ("على اليمين على طريق": ramps, and a straight way on, lead toward a road)
        val toward = s.type == "off ramp" || s.type == "on ramp" || s.entersRing || s.type == "fork"
        return Part.Name(say, if (toward) "toward" else "onto")
    }

    /** "كمّل على طريق الجبيل لمسافة 12 كيلومترًا": just past a manoeuvre, with a long way to the next one. [roadOf]: the told step the car has just done. */
    fun keepOn(roadOf: Int, metres: Double): List<Part> {
        if (told.getOrNull(roadOf)?.step?.onFerry == true) return emptyList() // (nobody drives a ferry on)
        val road = told.getOrNull(roadOf)?.road?.spoken
        val out = ArrayList<Part>(4)
        out.add(Part.Clip("keep.on"))
        if (road != null) out.add(Part.Name(road, "on"))
        out.add(Part.Clip("for"))
        out.add(Part.Clip(Words.distanceId(metres)))
        return out
    }

    /** "وصلت إلى مستشفى الجبيل", with a short name the voice can read. */
    fun arrived(name: String?): List<Part> {
        val n = name?.trim().orEmpty()
        return if (n.isNotEmpty() && n.length <= 28 && Names.hasArabic(n) == arabic) listOf(Part.Clip("arrived.at"), Part.Name(n)) else listOf(Part.Clip("arrived"))
    }

    /** The sign. Latin names show here: only the voice leaves them out. */
    fun banner(i: Int, along: Double, destination: String? = null): Banner {
        val t = told.getOrNull(i) ?: return Banner("", "", "", "", "", Turn.STRAIGHT, 0, Float.NaN)
        val s = t.step
        val road = when {
            s.type == "arrive" -> destination.orEmpty()
            s.onFerry -> Names.ferryName(s.name, arabic)
            else -> t.road.shown
        }
        val sign = t.sign
        val toward = sign?.toward?.joinToString(if (arabic) "، " else ", ").orEmpty()
        val shield = if (t.road.kind == RoadName.Kind.NUMBER) "" else t.road.ref
        return Banner(Speech.text(doing(t, along, saysDistance = false), arabic, said = false), road, shield, toward, sign?.number.orEmpty(), arrow(s),
            t.ringNumber, if (s.entersRing && !s.angle.isNaN()) (if (s.angle > 160) -178f else s.angle.toFloat()) else Float.NaN)
    }

    /** The arrow for the sign: what a modifier says, a roundabout measured; no side for a straight ramp, as in the words. */
    private fun arrow(s: GuideStep): Turn = when {
        s.type == "arrive" -> Turn.ARRIVE
        s.entersRing -> Turn.ROUNDABOUT
        else -> when (s.modifier) {
            "slight left" -> Turn.SLIGHT_LEFT
            "left" -> Turn.LEFT
            "sharp left" -> Turn.SHARP_LEFT
            "slight right" -> Turn.SLIGHT_RIGHT
            "right" -> Turn.RIGHT
            "sharp right" -> Turn.SHARP_RIGHT
            "uturn" -> Turn.UTURN
            else -> Turn.STRAIGHT
        }
    }

    /** The junction for the sign's view, null when there is no choice to show. */
    fun junction(i: Int): JunctionView? {
        val t = told.getOrNull(i) ?: return null
        return Junctions.of(t.step, route.steps.getOrNull(t.index + 1))
    }

    /**
     * The next camera or bump within reach of [along] ("ساهر بعد 500 متر، السرعة 80"), its limit from the spot or the road. [skip]: spots not to count, as the
     * owner's own of the pack's build when his live set is known ([com.abdllh.aura.nav.RoadWatch]).
     */
    fun nextAlert(along: Double, skip: ((AlongSpot) -> Boolean)? = null): SpotAlert? {
        for (s in spots) {
            if (s.along <= along) continue
            if (s.along - along > CAMERA_M) break
            val reach = when (s.kind) { SpotRow.CAMERA -> CAMERA_M; SpotRow.BUMP -> BUMP_M; else -> continue }
            if (s.along - along > reach) continue
            if (skip != null && skip(s)) continue
            val kmh = if (s.kind == SpotRow.CAMERA) s.kmh.takeIf { it > 0 } ?: route.facts?.at(s.along)?.limit ?: 0 else 0
            return SpotAlert(s, s.along - along, kmh)
        }
        return null
    }

    /** The next signal on the way, for a chip ("إشارة بعد 300 متر"), by the same direction rule. Not said by default: a signal is seen. */
    fun nextSignal(along: Double): SpotAlert? = signals.firstOrNull { it.along > along }?.let { SpotAlert(it, it.along - along, 0) }

    /** "أقرب محطة بعد 38 كم", on a long road: null while the trip is short or none is known ahead. */
    fun nextFuel(along: Double): StopAhead? = if (route.line.length - along < FUEL_TRIP_MIN_M) null else stops.next("fuel", along)

    fun fuelText(stop: StopAhead, along: Double): String =
        "${Words.text("fuel.next", arabic, false)} ${Words.text("in", arabic, false)} ${Units.distance(stop.along - along, arabic)}"

    fun fuelSpoken(stop: StopAhead, along: Double): List<Part> =
        listOf(Part.Clip("fuel.next"), Part.Clip("in"), Part.Clip(Words.distanceId(stop.along - along)))

    /**
     * The next prayer and a mosque for it. During the trip: the first mosque the car reaches once the time has come, as a stop before
     * it is no stop. [secondsLeft]: to the destination, from the ETA.
     */
    fun prayerStop(nowUtc: Long, here: LatLon, along: Double, secondsLeft: Double, ramadan: Boolean = PrayerTimes.isRamadan(nowUtc)): PrayerStop {
        val (p, at) = PrayerTimes.next(here.lat, here.lon, nowUtc, ramadan)
        val wait = (at - nowUtc) / 1000.0
        val during = wait < secondsLeft
        val left = route.line.length - along
        val thereBy = if (during && secondsLeft > 0) along + left * (wait / secondsLeft) else along
        val m = stops.next("mosque", thereBy)
        return PrayerStop(p, at, during, m, if (m != null) m.along - along else 0.0)
    }

    /** For the arrival card; [destination]: what was asked for, not where the road ends. */
    fun arrival(destination: LatLon?): ArrivalCard = ArrivalCard.of(route, destination, parkingRows)

    internal fun stopsOf(cat: String) = stops.all(cat)

    companion object {
        /** A signal this near the manoeuvre is its junction's. */
        private const val TURN_AT_SIGNAL_M = 40.0
        private const val SIGNAL_BEYOND_M = 15.0
        /** `spot.j` joins nodes within 60 m. */
        private const val JUNCTION_M = 60.0
        private const val SAY_NOTHING_M = 60.0
        private const val IN_SIGHT_M = 200.0
        private const val THEN_M = 150.0
        private const val UTURN_AHEAD_M = 300.0
        private const val CAMERA_M = 600.0
        private const val BUMP_M = 180.0
        private const val FUEL_TRIP_MIN_M = 30_000.0
        /** A road's end at a flyover or a tunnel this near names the turn. */
        private const val AFTER_STRUCTURE_M = 150.0
        /** A way into a flyover or a tunnel this near after a fork. */
        private const val ONTO_STRUCTURE_M = 120.0
        private const val ONTO_MIN_M = 100.0
        private val FORK_LIKE = setOf("fork", "off ramp", "on ramp")
        private val TURN_LIKE = setOf("turn", "end of road", "continue", "new name")

        /** [build], or null when something not seen before goes wrong: the app then has the old wording, and the trip goes on. */
        fun safely(route: GuideRoute, arabic: Boolean, world: RouteWorld = NoWorld, endSnap: Double = 0.0, destination: LatLon? = null): Guidance? =
            try { build(route, arabic, world, endSnap, destination) } catch (_: Throwable) { null }

        /** All of it for one route. A world that fails gives plainer words, no failure. [endSnap]: [GuideAnswer.endSnapMetres]. */
        fun build(route: GuideRoute, arabic: Boolean, world: RouteWorld = NoWorld, endSnap: Double = 0.0, destination: LatLon? = null): Guidance {
            val corridor = Corridor(route.line)
            val spotRows = ask { world.spots(corridor.boxes(30.0)) }
            // (a signal over an underpass, or under a flyover the route is on, stops somebody else: "the second signal" must not count it)
            val spots = try {
                AlongRoad.match(corridor, spotRows).filter { s -> s.kind != SpotRow.SIGNAL || route.facts?.at(s.along)?.let { it.tunnel || it.bridge } != true }
            } catch (_: Throwable) { emptyList() }
            val gates = ask { world.gates(corridor.boxes(20.0)) }
            val zones = ask { world.zones(corridor.boxes(50.0)) }
            val stopRows = ask { world.pois(corridor.boxes(StopsAlong.REACH_MAIN_M), StopsAlong.CATS, 0) }
            val parking = route.line.points.lastOrNull()?.let { end -> ask { world.pois(listOf(GeoBox.around(end, 450.0)), setOf("parking"), 0) } }.orEmpty()
            val told = toldSteps(route, arabic, world, spotRows)
            val warnings = try { TripCheck.check(route, corridor, gates, zones, endSnap) } catch (_: Throwable) { emptyList() }
            val stops = StopsAlong(route, corridor, stopRows)
            return Guidance(route, arabic, told, spots, warnings, stops, parking, destination)
        }

        /** This road's own signal stands further on than the turn: the car turns before it, not at it (a side street just short of the junction). */
        private fun beyondTheTurn(signal: SpotRow, s: GuideStep): Boolean {
            val dir = signal.dir ?: return false
            if (kotlin.math.abs(Geo.angleDiff(dir.toDouble(), s.bearingBefore.toDouble())) > 50) return false
            val ahead = Geo.distance(s.pos, signal.pos) * kotlin.math.cos(Math.toRadians(Geo.angleDiff(s.bearingBefore.toDouble(), Geo.bearing(s.pos, signal.pos))))
            return ahead > SIGNAL_BEYOND_M
        }

        /** What the world says, or nothing: its failing must not stop a trip. */
        private inline fun <T> ask(what: () -> List<T>): List<T> = try { what() } catch (_: Throwable) { emptyList() }

        private fun toldSteps(route: GuideRoute, arabic: Boolean, world: RouteWorld, signals: List<SpotRow>): List<Told> {
            val structures = try { Structures.of(route) } catch (_: Throwable) { emptyList() }
            val out = ArrayList<Told>(route.steps.size)
            var lastRoad = ""
            var lastAt = 0.0
            for ((i, s) in route.steps.withIndex()) {
                val next = route.steps.getOrNull(i + 1)
                val road = Names.pick(s.name, s.ref, arabic, s.distance) { latin ->
                    try { world.arabicRoadName(latin, s.pos) } catch (_: Throwable) { null }
                }
                if (s.silent) {
                    if (road.shown.isNotEmpty()) lastRoad = road.shown
                    continue
                }
                val same = road.shown.isNotEmpty() && road.shown == lastRoad
                val sign = if (s.type == "off ramp" || s.type == "on ramp" || s.type == "fork" || s.type == "merge") Signs.read(s.exits, s.destinations, arabic)?.without(road) else null
                val turnLike = s.type in TURN_LIKE && (s.modifier.contains("left") || s.modifier.contains("right") || s.modifier == "uturn")
                val junction = if (!turnLike || i == 0) -1 else signals
                    .filter { it.kind == SpotRow.SIGNAL && Geo.distance(it.pos, s.pos) <= TURN_AT_SIGNAL_M && !beyondTheTurn(it, s) }
                    .minByOrNull { Geo.distance(it.pos, s.pos) }?.junction ?: -1
                var mark: Mark? = null
                if (junction < 0 && !s.entersRing && i > 0 && s.type != "arrive") {
                    val before = structures.firstOrNull { it.to in (s.at - AFTER_STRUCTURE_M)..s.at && it.from >= lastAt - 1 }
                    val onto = structures.firstOrNull { it.from in s.at..(s.at + ONTO_STRUCTURE_M) && it.length >= ONTO_MIN_M }
                    when {
                        before != null && (turnLike || s.type in FORK_LIKE) -> mark = Mark.AfterStructure(before.tunnel)
                        onto != null && (s.type in FORK_LIKE || s.modifier.startsWith("slight")) -> mark = Mark.OntoStructure(onto.tunnel)
                        turnLike && s.modifier != "uturn" && (road.spoken == null || same) -> {
                            val side = if (s.modifier.contains("right")) 1 else -1
                            val rows = ask { world.pois(listOf(GeoBox.around(s.pos, Landmarks.ASK_M)), Landmarks.CATS, Landmarks.MIN_RANK) }
                            Landmarks.pick(s.pos, s.bearingBefore.toDouble(), side, rows, arabic)?.let { mark = Mark.Place(it) }
                        }
                    }
                }
                out.add(Told(i, s, road, same, sign, junction, mark, if (s.entersRing) Ring.number(s, next) else 0))
                if (road.shown.isNotEmpty()) lastRoad = road.shown
                lastAt = s.at
            }
            return out
        }
    }
}
