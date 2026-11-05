package com.abdllh.aura.nav.route

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.abdllh.aura.nav.Polyline
import com.abdllh.aura.nav.Route
import com.abdllh.aura.nav.RouteLimits
import com.abdllh.aura.nav.guide.GuideAnswer
import com.abdllh.aura.nav.guide.GuideRoute
import com.abdllh.aura.nav.guide.RoadFacts
import com.abdllh.aura.nav.guide.RoadStretch
import com.abdllh.aura.nav.places.PlaceSearch
import kotlin.math.abs

/**
 * Between the route part and the guidance part, behind the preference [RouteChain.guideOn]. The route is delivered first, as always; its [Route.guide] comes a moment
 * later on the same object, so until then, and whenever anything here fails, the old wording speaks. Steps of the two are one to one: both read the answer's own list in
 * its order. On the client's reader thread: the engine's JSON is read here, in the main process, because the words need places.db, which this process holds open.
 */
internal object GuideLink {
    private const val TAG = "AuraGuideLink"
    private val main = Handler(Looper.getMainLooper())

    /** A route has its [Route.guide] now, on the main thread: what a place's card shows of its warnings. */
    var landed: ((Route) -> Unit)? = null

    fun attach(route: Route, answer: RouteAnswer, raw: String, rows: List<RouteLimits.Row>?, req: RouteRequest) {
        val parsed = GuideAnswer.parse(raw)
        if (parsed == null) { Log.i(TAG, "the answer could not be read for guidance"); return }
        if (parsed.relaxed) Log.i(TAG, "warning 401: a second search")
        val chosen = pick(parsed, route, answer.rawSeconds.firstOrNull()) ?: return
        chosen.facts = rows?.let { facts(chosen.line, it) }
        PlaceSearch.guidance(chosen, req.arabic, parsed.endSnapMetres, req.to) { g ->
            main.post {
                route.guide = g
                landed?.invoke(route)
                Log.i(TAG, "guidance on the route: ${g?.told?.size ?: 0} told, ${g?.spots?.size ?: 0} spots, warnings ${g?.warnings?.map { it.kind }}, facts ${chosen.facts?.stretches?.size ?: 0}")
            }
        }
        // (the others a card may offer: their words and warnings too, by the same answer; the limits' walk was of the first alone, so no facts)
        for (i in 1 until answer.routes.size) {
            val alt = answer.routes[i]
            val own = pick(parsed, alt, answer.rawSeconds.getOrNull(i)) ?: continue
            if (own === chosen) continue
            PlaceSearch.guidance(own, req.arabic, parsed.endSnapMetres, req.to) { g -> main.post { alt.guide = g; landed?.invoke(alt) } }
        }
    }

    /** The route of the answer that is [route]: the chain sorts them by scaled time, so by what the engine itself said of it. */
    internal fun pick(a: GuideAnswer, route: Route, rawSeconds: Double?): GuideRoute? =
        a.routes.minByOrNull { abs(it.distance - route.distance) + (if (rawSeconds != null) abs(it.duration - rawSeconds) else 0.0) }

    /**
     * The roads along [line] from the rows of the limits' walk (where each ends, and in [RouteLimits.Row.tags] what it is), by distance as [RoadFacts] has them. A row
     * whose end is not found on the line is left to the next one. Null when they do not reach the line's end: then guidance knows no classes, as it would have without them.
     */
    internal fun facts(line: Polyline, rows: List<RouteLimits.Row>): RoadFacts? {
        val pts = line.points
        if (rows.isEmpty() || pts.size < 2) return null
        val out = ArrayList<RoadStretch>(rows.size)
        var i = 0
        var from = 0.0
        for (r in rows) {
            var j = i
            var at = -1
            while (j < pts.size) {
                if (abs(pts[j].lat - r.end.lat) < NEAR_DEG && abs(pts[j].lon - r.end.lon) < NEAR_DEG) { at = j; break }
                j++
            }
            if (at < 0) continue
            val to = line.cumulative[at]
            if (to > from) out.add(stretch(from, to, at, r.tags))
            from = to
            i = at
        }
        if (out.isEmpty() || out.last().endIndex != pts.size - 1) return null
        return RoadFacts(out)
    }

    private fun stretch(from: Double, to: Double, end: Int, tags: String): RoadStretch {
        var kmh = 0
        var cls = ""
        var use = ""
        var bridge = false
        var tunnel = false
        var toll = false
        for (w in tags.split(' ')) {
            when {
                w.startsWith("maxspeed=") -> kmh = w.substring(9).toIntOrNull() ?: 0
                w.startsWith("class=") -> cls = w.substring(6)
                w.startsWith("use=") -> use = w.substring(4)
                w == "bridge=1" -> bridge = true
                w == "tunnel=1" -> tunnel = true
                w == "toll=1" -> toll = true
            }
        }
        return RoadStretch(from, to, kmh, cls, use, bridge, tunnel, toll, end)
    }

    /** Two prints of one node: the trace's shape and the route's line, each to 6 digits. */
    private const val NEAR_DEG = 2e-5
}
