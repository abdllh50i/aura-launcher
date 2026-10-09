package com.abdllh.aura.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.abdllh.aura.util.Prefs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The car's position: GPS (plus the network provider, if the unit has one, for a quick first fix), shared by the home
 * map, the Maps screen and navigation. Updates run only while somebody holds it ([acquire]/[release]).
 * The last fix is remembered across restarts so the maps open where the car was.
 */
object CarLocation {
    private val main = Handler(Looper.getMainLooper())
    private val holders = HashSet<String>()
    private val listeners = CopyOnWriteArrayList<(Location) -> Unit>()
    private var lm: LocationManager? = null
    private var running = false
    private var lastGpsAt = 0L

    /** Latest fix (main thread). */
    var last: Location? = null
        private set

    /** Debug/demo: positions are fed by [NavSim] instead of the receivers. */
    @Volatile var simulated = false

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun lastKnown(): LatLon? = last?.let { LatLon(it.latitude, it.longitude) } ?: run {
        val lat = Prefs.raw.getFloat("lastLat", Float.NaN)
        val lon = Prefs.raw.getFloat("lastLon", Float.NaN)
        if (lat.isNaN() || lon.isNaN()) null else LatLon(lat.toDouble(), lon.toDouble())
    }

    fun addListener(l: (Location) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Location) -> Unit) { listeners.remove(l) }

    fun acquire(ctx: Context, who: String) {
        holders.add(who)
        start(ctx.applicationContext)
    }

    fun release(who: String) {
        holders.remove(who)
        if (holders.isEmpty()) stop()
    }

    private val receiver = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (simulated) return
            if (l.provider == LocationManager.GPS_PROVIDER) lastGpsAt = SystemClock.elapsedRealtime()
            else if (SystemClock.elapsedRealtime() - lastGpsAt < 10_000) return // GPS is live: ignore coarse fixes
            publish(l)
        }
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun start(ctx: Context) {
        if (running || !hasPermission(ctx)) return
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        lm = m
        try {
            val providers = m.allProviders
            if (LocationManager.GPS_PROVIDER in providers) m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, receiver, Looper.getMainLooper())
            if (LocationManager.NETWORK_PROVIDER in providers) m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, receiver, Looper.getMainLooper())
            running = true
            if (last == null) {
                val known = listOfNotNull(
                    m.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                    if (LocationManager.NETWORK_PROVIDER in providers) m.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null
                ).maxByOrNull { it.time }
                if (known != null) publish(known)
            }
        } catch (_: Throwable) {
        }
    }

    private fun stop() {
        if (!running) return
        running = false
        try { lm?.removeUpdates(receiver) } catch (_: Throwable) { }
    }

    /** Delivers a fix to everybody (also used by the simulator). */
    fun publish(l: Location) {
        val prev = last
        if (!l.hasBearing() && prev != null) {
            val moved = l.distanceTo(prev)
            if (moved > 3) l.bearing = prev.bearingTo(l)
            else if (prev.hasBearing()) l.bearing = prev.bearing
        }
        last = l
        val now = SystemClock.elapsedRealtime()
        if (savedAt == 0L || now - savedAt > 60_000) { // remembered for the next start, without writing flash every second
            savedAt = now
            Prefs.raw.edit().putFloat("lastLat", l.latitude.toFloat()).putFloat("lastLon", l.longitude.toFloat()).apply()
        }
        for (x in listeners) x(l)
    }

    private var savedAt = 0L
}
