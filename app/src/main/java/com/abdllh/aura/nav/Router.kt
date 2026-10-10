package com.abdllh.aura.nav

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/** One manoeuvre of a route (OSRM step). [at] is its distance along the route geometry. */
class Step(
    val type: String,          // turn, new name, depart, arrive, merge, on ramp, off ramp, fork, end of road, continue, roundabout, rotary, ...
    val modifier: String,      // uturn, sharp right, right, slight right, straight, slight left, left, sharp left
    val exit: Int,             // roundabout exit number (0 = none)
    val name: String,          // road name after the manoeuvre (may be empty)
    val ref: String,           // road number, e.g. "40"
    val location: LatLon,
    val distance: Double,      // length of the step in metres
    val duration: Double,      // seconds
    var at: Double = 0.0
)

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
        parse(Places.get("https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson&steps=true$bearings")) to null
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
                    s.optString("ref"), LatLon(loc.getDouble(1), loc.getDouble(0)), s.optDouble("distance", 0.0), s.optDouble("duration", 0.0)))
            }
        }
        // place every manoeuvre on the line, walking forwards so a route that passes a point twice stays in order
        var seg = 0
        for (s in steps) {
            val snap = line.snap(s.location, seg, 0, line.points.size) ?: continue
            s.at = snap.along
            seg = snap.segment
        }
        return Route(line, steps, r.optDouble("distance", line.length), r.optDouble("duration", 0.0))
    }
}
