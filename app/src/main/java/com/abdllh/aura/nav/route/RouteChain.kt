package com.abdllh.aura.nav.route

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Route
import com.abdllh.aura.nav.NavNote
import com.abdllh.aura.nav.Router
import com.abdllh.aura.nav.guide.GeoBox
import com.abdllh.aura.nav.guide.Haram
import com.abdllh.aura.nav.guide.ZoneRow
import com.abdllh.aura.nav.offline.OfflineMaps
import com.abdllh.aura.nav.offline.OfflineRouter
import com.abdllh.aura.nav.pack.PackPaths
import com.abdllh.aura.nav.places.PlaceSearch
import com.abdllh.aura.util.Prefs
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 2.0's order of routers (maps.md 7.6): Valhalla over the pack; BRouter when the guard tripped, the pack is missing, or Valhalla
 * failed; public OSRM abroad, or as the last try. No race and no head start: the local route is the route.
 *
 * "No road reaches it" from Valhalla is an answer for a place inside the Kingdom, not a failure: OSRM would snap to whatever road is
 * nearest (Jana Island: a route that ends 22 km away, code Ok, on both engines). Abroad the pack knows nothing, so with a connection
 * OSRM is asked first.
 *
 * `Router.route` comes here. [ask] is the way in for the new screens: alternates, via points, polygons, the road switch. A request that
 * only Valhalla can keep ([RouteRequest.needsValhalla]) gets that or the truth: another router's way is checked against the rings and left
 * out if it goes through them, and via points are not asked of any other router ([RouteAnswer.NOT_HONOURED]). The engine that answered
 * goes to the guard's log and to [NavNote].
 */
internal object RouteChain {
    private const val TAG = "AuraRouteChain"
    private const val PREF_GUIDE = "routeGuideWords"
    private const val PREF_HARAM = "routeAvoidHaram"
    /** Makkah's Haram lies inside this (its ring of 89 points in places.db: south 21.2, north 21.6, west 39.6, east 40.0, rounded out). */
    private val HARAM_BOX = GeoBox(21.15, 39.55, 21.65, 40.05)
    private val main = Handler(Looper.getMainLooper())
    /** BRouter's engine one at a time, as [Router] has it. */
    private val brouter = Any()
    /** The newest [ask]: a chain that took its time with another router must not take the client from a newer one. Bumped on the main thread. */
    private val newest = AtomicInteger()
    private var running = 0

    /** Somebody is waiting for a chain's answer. Main thread. */
    val asking: Boolean get() = running > 0

    /** The guidance part's words for routes from the pack ([com.abdllh.aura.nav.guide.Guidance]): [GuideLink]. On; a debug build can say off, to hear the old wording. */
    var guideOn: Boolean
        get() = try { Prefs.raw.getBoolean(PREF_GUIDE, true) } catch (_: Throwable) { true }
        set(v) { Prefs.raw.edit().putBoolean(PREF_GUIDE, v).apply() }

    /** The driver's switch: go round the Haram of Makkah. Off unless he asks; asked for, it holds for every route. */
    var avoidHaramOn: Boolean
        get() = try { Prefs.raw.getBoolean(PREF_HARAM, false) } catch (_: Throwable) { false }
        set(v) { Prefs.raw.edit().putBoolean(PREF_HARAM, v).apply() }

    /** As [Router.route]: the first route, or the reason as a message; [Router.NO_ANSWER_YET] when another request took the engine. */
    fun route(from: LatLon, to: LatLon, heading: Double?, done: (Route?, String?) -> Unit) {
        ask(RouteRequest(from, to, heading, arabic = Locale.getDefault().language == "ar", avoidHaram = avoidHaramOn)) { a ->
            val c = OfflineMaps.context
            done(a.route, when {
                a.route != null -> null
                a.status == RouteAnswer.CANCELLED -> Router.NO_ANSWER_YET
                a.status == RouteAnswer.NOT_HONOURED && c != null -> a.userMessage(c)
                else -> a.message
            })
        }
    }

    /** Main thread, and [done] on it, always once. */
    fun ask(req: RouteRequest, done: (RouteAnswer) -> Unit) {
        val c = OfflineMaps.context
        if (c == null) { main.post { done(RouteAnswer(RouteAnswer.FAILED, "too early")) }; return }
        val seq = newest.incrementAndGet()
        running++
        Thread({
            val a = try {
                chain(c, req, seq)
            } catch (t: Throwable) {
                Log.w(TAG, "$t")
                RouteAnswer(RouteAnswer.FAILED, t.toString().take(200))
            }
            main.post { running--; done(a) }
        }, "aura-route-chain").apply { isDaemon = true }.start()
    }

    /** Blocks its own thread. A newer request's [ask] does not wait for this one: [RouteClient] ends the older's wait at once. */
    private fun chain(c: Context, asked: RouteRequest, seq: Int): RouteAnswer {
        var haramInside = false
        val q = if (!asked.avoidHaram || asked.avoid.isNotEmpty()) asked else {
            val plan = haramPlan(asked)
            haramInside = plan.destinationInside != null || plan.startInside != null
            asked.with(plan.exclude)
        }
        q.problem()?.let {
            RouteGuard.note(c, "refused: $it")
            return RouteAnswer(RouteAnswer.NOT_HONOURED, it)
        }
        val outline = Outline.ksa(c)
        val inside = outline == null || (outline.contains(q.from) && outline.contains(q.to))
        val online = online(c)
        // (via points are not a thing another router could be checked for: it is not asked)
        val others = q.via.isEmpty()
        var broke = false
        /** Another router's route as an answer, when it is what was asked for. */
        fun theirs(r: Route?, engine: String): RouteAnswer? {
            if (r == null) return null
            if (q.avoid.any { RingCheck.crosses(r.line.points, it) }) {
                RouteGuard.note(c, "$engine: its way goes through what was to be avoided: not taken")
                broke = true
                return null
            }
            return RouteAnswer(RouteAnswer.OK, routes = listOf(r)).also {
                it.engine = engine
                it.roadIgnored = q.startRoad != null
                it.haramInside = haramInside
                NavNote.add("route by $engine")
            }
        }
        if (!inside && online && others) theirs(osrm(c, q, "abroad"), RouteAnswer.OSRM)?.let { return it }
        val a = valhalla(c, q, seq)
        when (a.status) {
            RouteAnswer.OK, RouteAnswer.CANCELLED -> return a.also { it.haramInside = haramInside }
            RouteAnswer.NO_ROAD, RouteAnswer.NO_ROUTE -> if (inside) return a.also { it.haramInside = haramInside }
            else -> RouteGuard.note(c, "valhalla: status ${a.status}: ${a.message}: the next router")
        }
        if (others && OfflineRouter.canRoute(c, q.from, q.to)) {
            val r = synchronized(brouter) { OfflineRouter.route(c, q.from, q.to, q.heading) }
            RouteGuard.note(c, "brouter: ${if (r != null) "${r.distance.toInt()} m" else "no route"}")
            theirs(r, RouteAnswer.BROUTER)?.let { return it }
        }
        if (others && inside && online) theirs(osrm(c, q, "the last try"), RouteAnswer.OSRM)?.let { return it }
        if (q.needsValhalla) {
            RouteGuard.note(c, "not honoured: ${if (broke) "the other routers go through it" else "valhalla: ${a.status} ${a.message}"}")
            return RouteAnswer(RouteAnswer.NOT_HONOURED, "valhalla: ${a.status} ${a.message}")
        }
        return a
    }

    private fun valhalla(c: Context, q: RouteRequest, seq: Int): RouteAnswer {
        var answer: RouteAnswer? = null
        val done = CountDownLatch(1)
        val tar = RoutePack.tar(c)
        main.post {
            try {
                // (it took its time with another router, and a newer request came meanwhile: that one has the engine)
                if (seq != newest.get()) { answer = RouteAnswer(RouteAnswer.CANCELLED, "a newer request"); done.countDown() }
                else RouteClient.ask(c, q, tar) { answer = it; done.countDown() }
            } catch (t: Throwable) {
                // (nothing of this may reach the home screen's main thread)
                Log.w(TAG, "$t")
                done.countDown()
            }
        }
        // (the client always answers, within its own 30 s: this is only so that a mistake there cannot leave a trip rerouting for ever)
        done.await(40, TimeUnit.SECONDS)
        val got = answer ?: return RouteAnswer(RouteAnswer.FAILED, "no answer")
        if (got.status == RouteAnswer.OK && RoutePack.tar == null) PackPaths.noteWorked(c)
        return got
    }

    /**
     * The ring of the Haram from places.db, and the rule of what may be left out ([Haram]). With no file to read it from: a box around it, wider than the
     * ring, so that a way through it is still seen; the answer then errs by going further round.
     */
    private fun haramPlan(q: RouteRequest): Haram.Plan {
        val ring = PlaceSearch.haramRingBlocking(1)
        val zone = if (ring != null) {
            val pts = ring.ring
            ZoneRow(ring.id, ring.kind, ring.name, ring.en, GeoBox(pts.minOf { it.lat }, pts.minOf { it.lon }, pts.maxOf { it.lat }, pts.maxOf { it.lon }), pts)
        } else {
            val b = HARAM_BOX
            ZoneRow(0, ZoneRow.HARAM_MAKKAH, null, null, b, listOf(LatLon(b.south, b.west), LatLon(b.south, b.east), LatLon(b.north, b.east), LatLon(b.north, b.west)))
        }
        return Haram.plan(q.from, q.to, true, listOf(zone))
    }

    private fun osrm(c: Context, q: RouteRequest, why: String): Route? {
        val (r, err) = Router.online(q.from, q.to, q.heading)
        RouteGuard.note(c, "osrm ($why): ${if (r != null) "${r.distance.toInt()} m" else "no route: $err"}")
        return r
    }

    internal fun online(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Throwable) {
        false
    }
}

/** Where the pack's routing tar is: [PackPaths], asked every time, so a new pack is seen by the next request. A test can name another. */
internal object RoutePack {
    @Volatile var tar: File? = null
    /** With no pack, a file that is not there: the client answers BAD_PACK at once and the chain goes on. */
    fun tar(c: Context): File = tar ?: PackPaths.routeTar(c) ?: File(PackPaths.root(c), "no-pack/${PackPaths.ROUTE}")
}

/** The Kingdom's outline (tools/mapbuild/polygon/ksa.poly, an asset of 8 KB): what the pack covers, for the online-or-pack choice. */
internal class Outline private constructor(private val lat: DoubleArray, private val lon: DoubleArray) {
    /** Even-odd rule on one ring: 320 points, each asked twice per request. */
    fun contains(p: LatLon): Boolean {
        var inside = false
        var j = lat.size - 1
        for (i in lat.indices) {
            if ((lat[i] > p.lat) != (lat[j] > p.lat) && p.lon < (lon[j] - lon[i]) * (p.lat - lat[i]) / (lat[j] - lat[i]) + lon[i]) inside = !inside
            j = i
        }
        return inside
    }

    companion object {
        @Volatile private var ksa: Outline? = null

        /** Null when the asset cannot be read (the chain then takes every place for inside). */
        fun ksa(c: Context): Outline? = ksa ?: try {
            parse(c.assets.open("route/ksa.poly").use { it.readBytes().toString(Charsets.UTF_8) }).also { ksa = it }
        } catch (t: Throwable) {
            Log.w("AuraRouteChain", "$t")
            null
        }

        /** An osmosis .poly: a name, a ring's name, "lon lat" lines, END, END. The first ring only: this file has one. */
        fun parse(text: String): Outline {
            val pts = ArrayList<DoubleArray>()
            for (l in text.lineSequence().drop(2)) {
                val w = l.trim().split(Regex("\\s+"))
                if (w.size == 1 && w[0] == "END") break
                if (w.size == 2) pts.add(doubleArrayOf(w[1].toDouble(), w[0].toDouble()))
            }
            require(pts.size >= 3) { "no ring" }
            return Outline(DoubleArray(pts.size) { pts[it][0] }, DoubleArray(pts.size) { pts[it][1] })
        }
    }
}
