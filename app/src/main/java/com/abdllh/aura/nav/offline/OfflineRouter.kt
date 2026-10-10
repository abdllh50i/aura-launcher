package com.abdllh.aura.nav.offline

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import btools.router.FormatJson
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Polyline
import com.abdllh.aura.nav.Route
import com.abdllh.aura.nav.Step
import org.json.JSONObject
import java.io.File

/**
 * Driving directions without internet: BRouter's engine (the `brouter` module) over the routing data of the offline
 * map, with BRouter's car profile (car-vario, target speed 120 km/h: Saudi highways). Its turn hints become the same
 * steps OSRM gives (without road names: the routing data has none), so guidance, voice and rerouting work unchanged.
 */
object OfflineRouter {
    private const val TAG = "AuraRoute"
    private const val PROFILE = "car-vario.brf"

    /** Both ends lie in squares of routing data that are on the unit. */
    fun canRoute(ctx: Context, from: LatLon, to: LatLon): Boolean {
        val dir = File(OfflineMaps.dir(ctx), "segments4")
        return File(dir, Region.segmentOf(from.lat, from.lon) + ".rd5").isFile && File(dir, Region.segmentOf(to.lat, to.lon) + ".rd5").isFile
    }

    /** A route, or null (no road nearby, out of the data, or a failure). Blocking: off the main thread. */
    fun route(ctx: Context, from: LatLon, to: LatLon, heading: Double?): Route? {
        val t0 = System.currentTimeMillis()
        return try {
            val base = OfflineMaps.dir(ctx)
            val profile = profile(ctx, base)
            val rc = RoutingContext()
            rc.localFunction = profile.path
            rc.turnInstructionMode = 3 // turn hints on (the style only matters for BRouter's own GPX output)
            // BRouter's node cache may grow to this many MB (then it starts over): a third of the app's heap class, the
            // rest is the map's and the car pictures' (a low-RAM unit)
            rc.memoryclass = ((ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass / 3).coerceIn(24, 64)
            if (heading != null) {
                // the way the car is heading (BRouter ignores it unless told to use it): a reroute on a divided road
                // must not start behind the car
                rc.startDirection = heading.toInt()
                rc.forceUseStartDirection = true
            }
            RoutingParamCollector().setProfileParams(rc, mapOf("vmax" to "120"))
            val engine = RoutingEngine(null, null, File(base, "segments4"), listOf(node("from", from), node("to", to)), rc,
                RoutingEngine.BROUTER_ENGINEMODE_ROUTING)
            engine.quite = true
            engine.doRun(30_000L)
            val track = engine.foundTrack
            if (track == null) {
                Log.w(TAG, "no route: ${engine.errorMessage}")
                return null
            }
            parse(FormatJson(rc).format(track)).also {
                Log.i(TAG, "offline route ${it?.distance?.toInt()} m in ${System.currentTimeMillis() - t0} ms")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "offline route: $t")
            null
        }
    }

    private fun node(name: String, p: LatLon) = OsmNodeNamed().apply {
        this.name = name
        ilon = ((p.lon + 180.0) * 1_000_000.0 + 0.5).toInt()
        ilat = ((p.lat + 90.0) * 1_000_000.0 + 0.5).toInt()
    }

    /** The profile and its lookup table, from the app's assets (once per app version). */
    private fun profile(ctx: Context, base: File): File {
        val dir = File(base, "profiles2").apply { mkdirs() }
        val f = File(dir, PROFILE)
        val stamp = File(dir, ".version")
        val version = com.abdllh.aura.BuildConfig.VERSION_CODE.toString()
        if (!f.isFile || !stamp.isFile || stamp.readText() != version) {
            for (name in listOf(PROFILE, "lookups.dat")) {
                ctx.assets.open("offline/profiles2/$name").use { inp -> File(dir, name).outputStream().use { inp.copyTo(it) } }
            }
            stamp.writeText(version)
        }
        return f
    }

    /**
     * BRouter's GeoJSON: the line, the time at every point, and the turn hints [index in the line, command, roundabout
     * exit, metres to the next hint, angle]. Commands: 1 straight, 2/3/4 left/slight/sharp, 5/6/7 the same right,
     * 8/9 keep left/right, 10/11/15 U-turns, 12 off route, 13/14 roundabout, 16 beeline, 17/18 exit left/right.
     */
    private fun parse(json: String): Route? {
        val f = JSONObject(json).getJSONArray("features").getJSONObject(0)
        val props = f.getJSONObject("properties")
        val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
        val raw = ArrayList<LatLon>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            raw.add(LatLon(c.getDouble(1), c.getDouble(0)))
        }
        if (raw.size < 2) return null
        val times = props.optJSONArray("times")
        fun timeAt(i: Int) = times?.optDouble(i.coerceIn(0, (times.length() - 1).coerceAtLeast(0)), 0.0) ?: 0.0
        val total = props.optString("total-time").toDoubleOrNull() ?: timeAt(raw.size - 1)

        // the line without repeated points (the hints keep their positions by coordinates, placed again below)
        val pts = ArrayList<LatLon>(raw.size)
        for (p in raw) if (pts.isEmpty() || Geo.distance(pts.last(), p) > 0.3) pts.add(p)
        if (pts.size < 2) return null
        val line = Polyline(pts)

        class Hint(val index: Int, val cmd: Int, val exit: Int)
        val hints = ArrayList<Hint>()
        props.optJSONArray("voicehints")?.let { vh ->
            for (i in 0 until vh.length()) {
                val h = vh.getJSONArray(i)
                hints.add(Hint(h.getInt(0), h.getInt(1), h.optInt(2, 0)))
            }
        }
        val steps = ArrayList<Step>()
        val rawIndex = ArrayList<Int>() // where each step is in BRouter's line (for its time)
        steps.add(Step("depart", "", 0, "", "", raw[0], 0.0, 0.0)); rawIndex.add(0)
        for (h in hints.sortedBy { it.index }) {
            val (type, modifier) = when (h.cmd) {
                2 -> "turn" to "left"
                3 -> "turn" to "slight left"
                4 -> "turn" to "sharp left"
                5 -> "turn" to "right"
                6 -> "turn" to "slight right"
                7 -> "turn" to "sharp right"
                8 -> "fork" to "slight left"
                9 -> "fork" to "slight right"
                10, 11, 15 -> "turn" to "uturn"
                13, 14 -> "roundabout" to ""
                17 -> "off ramp" to "slight left"
                18 -> "off ramp" to "slight right"
                else -> continue // straight on, off route, beeline: nothing to say
            }
            val idx = h.index.coerceIn(0, raw.size - 1)
            steps.add(Step(type, modifier, if (type == "roundabout") h.exit else 0, "", "", raw[idx], 0.0, 0.0)); rawIndex.add(idx)
        }
        steps.add(Step("arrive", "", 0, "", "", raw.last(), 0.0, 0.0)); rawIndex.add(raw.size - 1)

        // place every step on the line (walking forwards), then its length and time up to the next step
        var seg = 0
        for (s in steps) {
            val snap = line.snap(s.location, seg, 0, line.points.size) ?: continue
            s.at = snap.along
            seg = snap.segment
        }
        val timed = times != null && times.length() == raw.size
        val result = ArrayList<Step>(steps.size)
        for (i in steps.indices) {
            val s = steps[i]
            val end = if (i + 1 < steps.size) steps[i + 1].at else line.length
            val len = (end - s.at).coerceAtLeast(0.0)
            val dur = when {
                i + 1 >= steps.size -> 0.0
                timed -> (timeAt(rawIndex[i + 1]) - timeAt(rawIndex[i])).coerceAtLeast(0.0)
                line.length > 0 -> total * len / line.length
                else -> 0.0
            }
            result.add(Step(s.type, s.modifier, s.exit, s.name, s.ref, s.location, len, dur, s.at))
        }
        return Route(line, result, line.length, total)
    }
}
