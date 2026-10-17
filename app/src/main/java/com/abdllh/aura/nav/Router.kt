package com.abdllh.aura.nav

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/** One lane where a manoeuvre is (OSRM): the arrows painted on it, and whether it leads the route's way. */
class Lane(val indications: List<String>, val valid: Boolean, val validIndication: String)

/** One manoeuvre of a route (OSRM step). [at] is its distance along the route geometry. */
class Step(
    val type: String,          // turn, new name, depart, arrive, merge, on ramp, off ramp, fork, end of road, continue, roundabout, rotary, ...
    val modifier: String,      // uturn, sharp right, right, slight right, straight, slight left, left, sharp left
    val exit: Int,             // roundabout exit number (0 = none)
    var name: String,          // road name after the manoeuvre (may be empty; Arabic from the offline index when it has it)
    val ref: String,           // road number, e.g. "40"
    val location: LatLon,
    val distance: Double,      // length of the step in metres
    val duration: Double,      // seconds
    var at: Double = 0.0,
    val lanes: List<Lane> = emptyList(),
    val destinations: String = "", // a ramp's signposted destinations, e.g. "40: Dammam, Jubail"
    val exits: String = ""         // the exit's number on the sign
) {
    /** Measured on the line ([Router.prepare]): how far the car turns here, degrees (+ right); NaN when not measured. */
    var angle = Double.NaN
    /** Metres from [at] to where the manoeuvre is over: a roundabout's way round to the exit taken; else 0. */
    var span = 0.0
    /**
     * Not a manoeuvre for the driver: the street only changes its name, or the car leaves the roundabout it was told
     * about. Kept in the route (its time counts), never shown or spoken.
     */
    var silent = false
}

class Route(val line: Polyline, val steps: List<Step>, val distance: Double, val duration: Double) {
    /** Time left from a distance along the route, scaled by the steps' own speeds. */
    fun timeLeft(along: Double): Double {
        var t = 0.0
        for (i in steps.indices) {
            val s = steps[i]
            val start = s.at
            val end = if (i + 1 < steps.size) steps[i + 1].at else line.length
            if (end <= along) continue
            val len = (end - start).coerceAtLeast(1.0)
            t += if (start >= along) s.duration else s.duration * ((end - along) / len)
        }
        return t
    }

    /** The first manoeuvre the driver is told about after step [i] (or null: [i] is the last). */
    fun following(i: Int): Step? {
        for (k in i + 1 until steps.size) if (!steps[k].silent) return steps[k]
        return null
    }
}

/**
 * Driving directions: the public OSRM server (OpenStreetMap data, with road names) and, where the offline map has
 * routing data, BRouter on the unit ([com.abdllh.aura.nav.offline.OfflineRouter]) at the same time. OSRM's route is
 * taken when it comes within [ONLINE_HEAD_START_MS]; else the offline one, so a route shows at once also without
 * internet or with a hotspot that stopped passing traffic (where OSRM would only time out).
 */
object Router {
    private const val ONLINE_HEAD_START_MS = 2500L
    private val io = Executors.newSingleThreadExecutor()
    private val offlineIo = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** [heading] (degrees) helps the router start in the direction the car is already moving. */
    fun route(from: LatLon, to: LatLon, heading: Double?, done: (Route?, String?) -> Unit) {
        val ctx = com.abdllh.aura.nav.offline.OfflineMaps.context
        val offline = ctx != null && com.abdllh.aura.nav.offline.OfflineRouter.canRoute(ctx, from, to)
        if (!offline) {
            io.execute { val (r, err) = online(from, to, heading); main.post { done(r, err) } }
            return
        }
        // both, on the main thread from here: the first good answer within the rules above is the one
        var delivered = false
        var onlineDone = false
        var onlineRoute: Route? = null
        var onlineErr: String? = null
        var offlineDone = false
        var offlineRoute: Route? = null
        var headStartOver = false
        fun decide() {
            if (delivered) return
            val r: Route? = when {
                onlineDone && onlineRoute != null -> onlineRoute
                offlineDone && offlineRoute != null && (headStartOver || onlineDone) -> offlineRoute
                onlineDone && offlineDone -> null
                else -> return
            }
            delivered = true
            done(r, if (r == null) onlineErr else null)
        }
        io.execute {
            val (r, err) = online(from, to, heading)
            main.post { onlineDone = true; onlineRoute = r; onlineErr = err; decide() }
        }
        offlineIo.execute {
            val r = com.abdllh.aura.nav.offline.OfflineRouter.route(ctx!!, from, to, heading)
            main.post { offlineDone = true; offlineRoute = r; decide() }
        }
        main.postDelayed({ headStartOver = true; decide() }, ONLINE_HEAD_START_MS)
    }

    private fun online(from: LatLon, to: LatLon, heading: Double?): Pair<Route?, String?> = try {
        val coords = String.format(Locale.ROOT, "%.6f,%.6f;%.6f,%.6f", from.lon, from.lat, to.lon, to.lat)
        val bearings = heading?.let { String.format(Locale.ROOT, "&bearings=%d,60;", it.toInt().coerceIn(0, 359)) } ?: ""
        val r = parse(Places.get("https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson&steps=true$bearings"))
        if (r != null && Locale.getDefault().language == "ar") arabicNames(r)
        r to null
    } catch (t: Throwable) {
        null to t.message
    }

    private fun parse(body: String): Route? {
        val o = JSONObject(body)
        if (o.optString("code") != "Ok") return null
        val r = o.getJSONArray("routes").getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = ArrayList<LatLon>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            val p = LatLon(c.getDouble(1), c.getDouble(0))
            if (pts.isEmpty() || Geo.distance(pts.last(), p) > 0.3) pts.add(p)
        }
        if (pts.size < 2) return null
        val line = Polyline(pts)
        val steps = ArrayList<Step>()
        val legs = r.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val ss = legs.getJSONObject(l).getJSONArray("steps")
            for (i in 0 until ss.length()) {
                val s = ss.getJSONObject(i)
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                steps.add(Step(m.optString("type"), m.optString("modifier"), m.optInt("exit", 0), s.optString("name"),
                    s.optString("ref"), LatLon(loc.getDouble(1), loc.getDouble(0)), s.optDouble("distance", 0.0),
                    s.optDouble("duration", 0.0), lanes = lanes(s), destinations = s.optString("destinations"),
                    exits = s.optString("exits")))
            }
        }
        // place every manoeuvre on the line, walking forwards so a route that passes a point twice stays in order
        var seg = 0
        for (s in steps) {
            val snap = line.snap(s.location, seg, 0, line.points.size) ?: continue
            s.at = snap.along
            seg = snap.segment
        }
        prepare(line, steps)
        return Route(line, steps, r.optDouble("distance", line.length), r.optDouble("duration", 0.0))
    }

    /** The lanes where the step's manoeuvre is: its first intersection is the manoeuvre's own. */
    private fun lanes(s: JSONObject): List<Lane> {
        val a = s.optJSONArray("intersections")?.optJSONObject(0)?.optJSONArray("lanes") ?: return emptyList()
        val out = ArrayList<Lane>(a.length())
        for (i in 0 until a.length()) {
            val l = a.optJSONObject(i) ?: continue
            val ind = l.optJSONArray("indications")
            val list = if (ind == null) emptyList() else (0 until ind.length()).map { ind.optString(it) }
            out.add(Lane(list, l.optBoolean("valid"), l.optString("valid_indication")))
        }
        return out
    }

    /**
     * What both routers' steps need once they are on the line: which steps say nothing new ([Step.silent]), and how a
     * roundabout is driven ([Step.angle], [Step.span]), measured on the line itself. The exit's number alone does not
     * say where it leads: Saudi roundabouts often have five or six arms, or arms at 45 degrees.
     */
    fun prepare(line: Polyline, steps: List<Step>) {
        for ((i, s) in steps.withIndex()) {
            val prev = steps.getOrNull(i - 1)
            val next = steps.getOrNull(i + 1)
            val roundabout = Instructions.entersRoundabout(s)
            val straight = s.modifier.isEmpty() || s.modifier == "straight"
            // (a straight step that still has lanes to keep to is the lanes' prompt: it stays)
            val laneHint = s.lanes.size >= 2 && s.lanes.any { !it.valid }
            s.silent = (s.type in QUIET_TYPES && straight && !laneHint) ||
                (s.type.startsWith("exit ") && prev != null && Instructions.entersRoundabout(prev))
            if (s.type.startsWith("exit ") && !s.silent) {
                // Leaving a roundabout no step brought the car into (the trip starts on it): how far the car turns
                // from where it is (or 150 m back) onto the road out, the sign's ring then shows that way out. Added
                // up along the line in small steps: where the trip starts on the ring, the line's first metres
                // already curve, and one bearing there against the road out gave a way out on the wrong side.
                val from = maxOf(prev?.at ?: 0.0, s.at - 150)
                var heading = line.at(from).second
                var turned = 0.0
                var d = from + 5
                while (d <= minOf(s.at + 30, line.length)) {
                    val h = line.at(d).second
                    turned += Geo.angleDiff(heading, h)
                    heading = h
                    d += 5
                }
                s.angle = turned
            }
            if (!roundabout) continue
            // where the car leaves it: OSRM says so with an exit step; else where the way round has turned left most
            val exitAt = if (next != null && next.type.startsWith("exit ")) next.at else ringEnd(line, s.at, next?.at ?: line.length)
            if (exitAt.isNaN()) continue
            s.span = (exitAt - s.at).coerceAtLeast(0.0)
            val inBearing = Geo.bearing(line.at(s.at - 40).first, line.at(s.at - 8).first)
            val outBearing = Geo.bearing(line.at(exitAt + 8).first, line.at(exitAt + 40).first)
            s.angle = Geo.angleDiff(inBearing, outBearing)
            // the road it leads to, when the roundabout step itself has none (OSRM names it on the exit step)
            if (next != null && next.type.startsWith("exit ") && s.name.isBlank() && s.ref.isBlank()) s.name = next.name.ifBlank { next.ref }
        }
    }

    /**
     * The end of a roundabout's way round, from its entry at [from]: right-hand traffic turns right into it, goes
     * round counter-clockwise (the heading turning left the whole way) and turns right again to leave, so the way
     * round is the longest left-turning stretch, and it ends where that stretch does. NaN when there is none (a mini
     * roundabout drawn as a point).
     */
    private fun ringEnd(line: Polyline, from: Double, limit: Double): Double {
        val end = minOf(from + 220.0, limit, line.length)
        var turned = 0.0
        var peak = 0.0
        var drop = 0.0
        var dropAt = Double.NaN
        var heading = line.at(from - 20).second // (the turn into it may begin before the step's point)
        var d = from - 15
        while (d <= end) {
            val h = line.at(d).second
            turned += Geo.angleDiff(heading, h)
            heading = h
            if (turned > peak) peak = turned
            if (turned - peak < drop) { drop = turned - peak; dropAt = d }
            d += 5
        }
        return if (drop < -30) dropAt else Double.NaN
    }

    /**
     * Arabic street names for an Arabic screen and voice: OSM's `name` is often the English one in Jubail, while the
     * offline map's index keeps `name:ar`. Off the main thread (a scan of the index: up to 200 ms on the unit).
     */
    private fun arabicNames(r: Route) {
        val store = com.abdllh.aura.nav.offline.OfflineMaps.searchStore() ?: return
        val wanted = r.steps.map { it.name }.filter { it.isNotBlank() && !Places.isArabic(it) }.toSet()
        if (wanted.isEmpty()) return
        val found = try {
            com.abdllh.aura.nav.offline.PlaceIndex.arabicNames(store, wanted)
        } catch (_: Throwable) {
            return
        }
        for (s in r.steps) {
            // a name used by several streets: the one nearest the manoeuvre
            val best = found.filter { it.key == s.name }.minByOrNull { Geo.distance(it.pos, s.location) } ?: continue
            if (Geo.distance(best.pos, s.location) < 15_000) s.name = best.arabic
        }
    }

    private val QUIET_TYPES = setOf("new name", "continue", "notification", "use lane")
}
