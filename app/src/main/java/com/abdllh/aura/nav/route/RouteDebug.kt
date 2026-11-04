package com.abdllh.aura.nav.route

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Route
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug builds only (`am broadcast -a com.abdllh.aura.debug.ROUTE`), tools/mapbuild/tests/app_route_check.py drives it:
 *   --ez valhalla true|false              the preference: Router.route over RouteChain
 *   --ez guide true|false                 the preference: the guidance part's words for routes from the pack
 *   --ez avoid_haram true|false           the preference: go round the Haram of Makkah, on every route (the ring from places.db)
 *   --es tar <path>                       another route.tar
 *   --es ask lat,lon,lat,lon[,heading]    one request through RouteClient: files/diag/route-ask.json, steps and all
 *     with --es ring <file of lat,lon lines>, --ez haram true (the ring from places.db), --es via lat,lon[;lat,lon...] (--ez stop true), --es road main:<class> or side:<main class>[:<side class>]
 *     or --es raw <file>: that JSON is asked, whatever the rest says (the old 2.35 MB answer needs the old options)
 *   --es chain lat,lon,lat,lon[,heading]  the whole chain; the log says which engine answered
 *   --es batch <path> [--ei rounds 1] [--ez limits false]   lines id,lat,lon,lat,lon in a row: files/diag/route-batch.json
 *   --el delay_ms <n>                     the service sits on the next requests this long, marker down
 *   --el idle_ms <n>                      RouteClient lets go after this long
 *   --ez guard true                       the guard's state and log, in the log
 *   --ez guard_reset true
 * Log tag AuraRouteDebug.
 */
internal object RouteDebug {
    private const val TAG = "AuraRouteDebug"
    private const val ACTION = "com.abdllh.aura.debug.ROUTE"
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        try {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    try { handle(app, i) } catch (t: Throwable) { Log.w(TAG, "$t") }
                }
            }, IntentFilter(ACTION))
        } catch (t: Throwable) {
            Log.w(TAG, "$t")
        }
    }

    private fun handle(c: Context, i: Intent) {
        if (i.hasExtra("valhalla")) { RouteChain.on = i.getBooleanExtra("valhalla", false); Log.i(TAG, "valhalla ${RouteChain.on}") }
        if (i.hasExtra("guide")) { RouteChain.guideOn = i.getBooleanExtra("guide", false); Log.i(TAG, "guide ${RouteChain.guideOn}") }
        if (i.hasExtra("avoid_haram")) { RouteChain.avoidHaramOn = i.getBooleanExtra("avoid_haram", false); Log.i(TAG, "avoid haram ${RouteChain.avoidHaramOn}") }
        i.getStringExtra("tar")?.let { RoutePack.tar = File(it); Log.i(TAG, "tar $it") }
        if (i.hasExtra("delay_ms")) RouteClient.debugDelayMs = i.getLongExtra("delay_ms", 0L)
        if (i.hasExtra("idle_ms")) RouteClient.idleMs = i.getLongExtra("idle_ms", RouteClient.idleMs)
        if (i.getBooleanExtra("guard_reset", false)) RouteGuard.reset(c)
        if (i.getBooleanExtra("guard", false)) {
            Log.i(TAG, "guard: ${RouteGuard.status(c)}; client: ${RouteClient.describe()}; pref ${RouteChain.on}")
            for (l in RouteGuard.logText(c).lines().takeLast(30)) Log.i(TAG, "guard log: $l")
        }
        i.getStringExtra("ask")?.let { s ->
            val t0 = SystemClock.elapsedRealtime()
            RouteClient.ask(c, request(s, i), RoutePack.tar(c)) { a ->
                Log.i(TAG, "ask: ${line(a, SystemClock.elapsedRealtime() - t0)}")
                // (a moment later: the limits come after the route)
                main.postDelayed({ write(File(diag(c), "route-ask.json"), full(a, SystemClock.elapsedRealtime() - t0)) }, 3000L)
            }
        }
        i.getStringExtra("chain")?.let { s ->
            val t0 = SystemClock.elapsedRealtime()
            RouteChain.ask(request(s, i)) { a -> Log.i(TAG, "chain: ${a.engine}${if (a.roadIgnored) " (road switch ignored)" else ""}${if (a.haramInside) " (trip touches the Haram: not left out)" else ""} ${line(a, SystemClock.elapsedRealtime() - t0)}") }
        }
        i.getStringExtra("batch")?.let { p ->
            val lines = File(p).readLines().filter { it.isNotBlank() }
            batch(c, lines, i.getIntExtra("rounds", 1), i.getBooleanExtra("limits", true))
        }
    }

    private fun request(s: String, i: Intent): RouteRequest {
        val v = s.split(',').map { it.trim().toDouble() }
        fun point(p: String) = p.split(',').let { LatLon(it[0].trim().toDouble(), it[1].trim().toDouble()) }
        val ring = i.getStringExtra("ring")?.let { f -> listOf(File(f).readLines().filter { it.isNotBlank() }.map(::point)) } ?: emptyList()
        val via = i.getStringExtra("via")?.split(';')?.map { RouteRequest.Via(point(it), i.getBooleanExtra("stop", false)) } ?: emptyList()
        val road = i.getStringExtra("road")?.split(':')?.let { if (it[0] == "main") RouteRequest.RoadClasses.onMain(it[1]) else RouteRequest.RoadClasses.onSide(it[1], it.getOrNull(2)) }
        val raw = i.getStringExtra("raw")?.let { File(it).readText() }
        return object : RouteRequest(LatLon(v[0], v[1]), LatLon(v[2], v[3]), v.getOrNull(4), via, ring, road, avoidHaram = i.getBooleanExtra("haram", false)) {
            override fun json() = raw ?: super.json()
        }
    }

    private fun diag(c: Context) = File(c.filesDir, "diag").apply { mkdirs() }

    private fun write(f: File, o: Any) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    private fun line(a: RouteAnswer, ms: Long): String {
        val r = a.route ?: return "status ${a.status} '${a.message}' in $ms ms"
        return "${r.distance.toInt()} m, ${(r.duration / 60).toInt()} min, ${a.routes.size} routes, ${r.steps.size} steps, named ${r.steps.count { it.name.isNotBlank() }} in $ms ms; " +
            "native ${a.stats.routeMs} ms, parse ${a.stats.parseMs} ms, ${a.stats.answerBytes} B; HWM ${a.stats.vmHwmKb / 1024} MB, PSS ${a.stats.pssKb / 1024} MB"
    }

    private fun summary(a: RouteAnswer, ms: Long): JSONObject {
        val s = a.stats
        val o = JSONObject().put("status", a.status).put("message", a.message).put("engine", a.engine).put("clientMs", ms)
            .put("routeMs", s.routeMs).put("parseMs", s.parseMs).put("bytes", s.answerBytes).put("loadMs", s.loadMs).put("actorMs", s.actorMs)
            .put("vmPeakKb", s.vmPeakKb).put("vmHwmKb", s.vmHwmKb).put("pssKb", s.pssKb).put("pid", s.pid).put("endOffRoad", a.endOffRoad)
            .put("mainPssKb", Debug.getPss())
        val rs = JSONArray()
        for ((k, r) in a.routes.withIndex()) {
            rs.put(JSONObject().put("km", r.distance / 1000).put("minutes", r.duration / 60).put("rawMinutes", a.rawSeconds.getOrElse(k) { Double.NaN }.let { if (it.isNaN()) 0.0 else it / 60 })
                .put("steps", r.steps.size).put("points", r.line.points.size).put("lineKm", r.line.length / 1000).put("estimate", r.etaEstimate)
                .put("limitsKm", (r.limits?.metres ?: 0.0) / 1000))
        }
        return o.put("routes", rs)
    }

    /** Everything the main process got of the first route, to hold against the answer. */
    private fun full(a: RouteAnswer, ms: Long): JSONObject {
        val o = summary(a, ms)
        val r = a.route ?: return o
        o.put("steps", stepsJson(r))
        return o
    }

    private fun stepsJson(r: Route): JSONArray {
        val a = JSONArray()
        for (s in r.steps) {
            a.put(JSONObject().put("type", s.type).put("modifier", s.modifier).put("exit", s.exit).put("name", s.name).put("ref", s.ref)
                .put("lat", s.location.lat).put("lon", s.location.lon).put("distance", s.distance).put("duration", s.duration).put("at", s.at)
                .put("lanes", s.lanes.size).put("destinations", s.destinations).put("exits", s.exits)
                .put("angle", if (s.angle.isNaN()) JSONObject.NULL else s.angle).put("span", s.span).put("silent", s.silent))
        }
        return a
    }

    /** The trips one after the other through the real service. [waitLimits]: each waits until its limits came (or 5 s). */
    private fun batch(c: Context, lines: List<String>, rounds: Int, waitLimits: Boolean) {
        val out = JSONArray()
        var round = 0
        var n = 0
        fun next() {
            if (n >= lines.size) { n = 0; round++ }
            if (round >= rounds) {
                write(File(diag(c), "route-batch.json"), out)
                Log.i(TAG, "batch done: ${out.length()}")
                return
            }
            val p = lines[n++].split(',')
            val req = RouteRequest(LatLon(p[1].toDouble(), p[2].toDouble()), LatLon(p[3].toDouble(), p[4].toDouble()))
            val t0 = SystemClock.elapsedRealtime()
            RouteClient.ask(c, req, RoutePack.tar(c)) { a ->
                val ms = SystemClock.elapsedRealtime() - t0
                fun record() {
                    out.put(summary(a, ms).put("id", p[0]).put("round", round))
                    // (the running file too: a crash leaves what came before it)
                    next()
                }
                val r = a.route
                if (r == null || !waitLimits) record()
                else {
                    val end = SystemClock.elapsedRealtime() + 5000
                    fun wait() {
                        if (r.limits != null || SystemClock.elapsedRealtime() > end) record() else main.postDelayed({ wait() }, 20)
                    }
                    wait()
                }
            }
        }
        next()
    }
}
