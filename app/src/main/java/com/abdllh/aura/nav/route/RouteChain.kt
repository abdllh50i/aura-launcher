package com.abdllh.aura.nav.route

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.Route
import com.abdllh.aura.nav.Router
import com.abdllh.aura.nav.offline.OfflineMaps
import com.abdllh.aura.nav.offline.OfflineRouter
import com.abdllh.aura.util.Prefs
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 2.0's order of routers (maps.md 7.6): Valhalla over the pack; BRouter when the guard tripped, the pack is missing, or Valhalla
 * failed; public OSRM abroad, or as the last try. No race and no head start: the local route is the route.
 *
 * "No road reaches it" from Valhalla is an answer for a place inside the Kingdom, not a failure: OSRM would snap to whatever road is
 * nearest (Jana Island: a route that ends 22 km away, code Ok, on both engines). Abroad the pack knows nothing, so with a connection
 * OSRM is asked first.
 *
 * Behind the preference [on], default off, until 2.0 makes it the way: `Router.route` comes here then. [ask] is the way in for the
 * new screens: alternates, via points, polygons, the road switch.
 */
internal object RouteChain {
    private const val TAG = "AuraRouteChain"
    private const val PREF = "routeValhalla"
    private val main = Handler(Looper.getMainLooper())
    /** BRouter's engine one at a time, as [Router] has it. */
    private val brouter = Any()

    var on: Boolean
        get() = try { Prefs.raw.getBoolean(PREF, false) } catch (_: Throwable) { false }
        set(v) { Prefs.raw.edit().putBoolean(PREF, v).apply() }

    /** As [Router.route]: the first route, or the reason as a message. */
    fun route(from: LatLon, to: LatLon, heading: Double?, done: (Route?, String?) -> Unit) {
        ask(RouteRequest(from, to, heading, arabic = Locale.getDefault().language == "ar")) { a -> done(a.route, if (a.route == null) a.message else null) }
    }

    /** Main thread, and [done] on it, always once. */
    fun ask(req: RouteRequest, done: (RouteAnswer) -> Unit) {
        val c = OfflineMaps.context
        if (c == null) { main.post { done(RouteAnswer(RouteAnswer.FAILED, "too early")) }; return }
        Thread({
            val a = try {
                chain(c, req)
            } catch (t: Throwable) {
                Log.w(TAG, "$t")
                RouteAnswer(RouteAnswer.FAILED, t.toString().take(200))
            }
            main.post { done(a) }
        }, "aura-route-chain").apply { isDaemon = true }.start()
    }

    /** Blocks its own thread. A newer request's [ask] does not wait for this one: [RouteClient] ends the older's wait at once. */
    private fun chain(c: Context, q: RouteRequest): RouteAnswer {
        val outline = Outline.ksa(c)
        val inside = outline == null || (outline.contains(q.from) && outline.contains(q.to))
        val online = online(c)
        if (!inside && online) osrm(c, q, "abroad")?.let { return it }
        val a = valhalla(c, q)
        when (a.status) {
            RouteAnswer.OK, RouteAnswer.CANCELLED -> return a
            RouteAnswer.NO_ROAD, RouteAnswer.NO_ROUTE -> if (inside) return a
            else -> RouteGuard.note(c, "valhalla: status ${a.status}: ${a.message}: the next router")
        }
        if (OfflineRouter.canRoute(c, q.from, q.to)) {
            val r = synchronized(brouter) { OfflineRouter.route(c, q.from, q.to, q.heading) }
            RouteGuard.note(c, "brouter: ${if (r != null) "${r.distance.toInt()} m" else "no route"}")
            if (r != null) return RouteAnswer(RouteAnswer.OK, routes = listOf(r)).also { it.engine = RouteAnswer.BROUTER }
        }
        if (inside && online) osrm(c, q, "the last try")?.let { return it }
        return a
    }

    private fun valhalla(c: Context, q: RouteRequest): RouteAnswer {
        var answer: RouteAnswer? = null
        val done = CountDownLatch(1)
        main.post {
            try {
                RouteClient.ask(c, q, RoutePack.tar(c)) { answer = it; done.countDown() }
            } catch (t: Throwable) {
                // (nothing of this may reach the home screen's main thread)
                Log.w(TAG, "$t")
                done.countDown()
            }
        }
        // (the client always answers, within its own 30 s: this is only so that a mistake there cannot leave a trip rerouting for ever)
        done.await(40, TimeUnit.SECONDS)
        return answer ?: RouteAnswer(RouteAnswer.FAILED, "no answer")
    }

    private fun osrm(c: Context, q: RouteRequest, why: String): RouteAnswer? {
        val (r, err) = Router.online(q.from, q.to, q.heading)
        RouteGuard.note(c, "osrm ($why): ${if (r != null) "${r.distance.toInt()} m" else "no route: $err"}")
        return r?.let { RouteAnswer(RouteAnswer.OK, routes = listOf(it)).also { a -> a.engine = RouteAnswer.OSRM } }
    }

    private fun online(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Throwable) {
        false
    }
}

/** Where the pack's routing tar is. The pack manager's layout; a test or the manager can name another. */
internal object RoutePack {
    @Volatile var tar: File? = null
    fun tar(c: Context): File = tar ?: File(c.filesDir, "maps2/current/route.tar")
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
