package com.abdllh.aura.nav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.nav.offline.OfflineMaps
import com.abdllh.aura.ui.Daylight
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.voice.VoiceTurn
import java.io.File
import java.util.Locale

/**
 * Debug builds only, the emulator's way into navigation, which has no GPS or road of its own (the broadcast's name: ACTION):
 *   --es fix lat,lon,bearing,kmh[,accuracy]   a fix, as the GPS would give it
 *   --es go lat,lon,name                      a trip there from the last fix (without the Maps screen)
 *   --ei sim 90                               the simulator drives the route at that speed
 *   --ez end true                             the trip ends
 *   --es turn nav|amri|free                   the car's voice ([VoiceTurn]) taken for one of them, or given back
 *   --es fetch url,name                       OfflineMaps.fetchFile into files/fetchtest/name
 *   --ez dump true                            what navigation, the road watch and the media card know, in the log (tag AuraNavDebug)
 */
internal object NavDebug {
    private const val TAG = "AuraNavDebug"
    private const val ACTION = "com.abdllh.aura.debug.NAV"

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

    private fun handle(app: Context, i: Intent) {
        i.getStringExtra("fix")?.let { s ->
            val v = s.split(',').map { it.trim().toDouble() }
            CarLocation.simulated = true // (the emulator's own GPS keeps out of it from now on)
            CarLocation.publish(Location(LocationManager.GPS_PROVIDER).apply {
                latitude = v[0]; longitude = v[1]; bearing = v[2].toFloat(); speed = (v[3] / 3.6).toFloat()
                accuracy = v.getOrElse(4) { 5.0 }.toFloat()
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
        }
        i.getStringExtra("go")?.let { s ->
            val v = s.split(',', limit = 3)
            val dest = Place(v.getOrElse(2) { "test" }, "", LatLon(v[0].trim().toDouble(), v[1].trim().toDouble()))
            val from = CarLocation.lastKnown() ?: return Unit.also { Log.i(TAG, "no fix yet") }
            Router.route(from, dest.pos, null) { r, err ->
                Log.i(TAG, "route: ${if (r == null) "none: $err" else "${r.distance.toInt()} m, ${r.steps.size} steps, named ${r.steps.count { it.name.isNotBlank() }}"}")
                if (r != null) NavSession.start(app, dest, r)
            }
        }
        if (i.hasExtra("sim")) NavSim.start(i.getIntExtra("sim", 60).toDouble())
        if (i.getBooleanExtra("end", false)) NavSession.stop()
        when (i.getStringExtra("turn")) { // the car's voice taken and given back (no TTS on this emulator to hold it)
            "nav" -> Log.i(TAG, "turn taken: ${VoiceTurn.take(VoiceTurn.Who.NAV)}")
            "amri" -> Log.i(TAG, "turn taken: ${VoiceTurn.take(VoiceTurn.Who.AMRI)}")
            "free" -> { VoiceTurn.release(VoiceTurn.Who.NAV); VoiceTurn.release(VoiceTurn.Who.AMRI) }
        }
        i.getStringExtra("fetch")?.let { s ->
            val (url, name) = s.split(',', limit = 2)
            Thread({
                try {
                    val f = File(File(app.filesDir, "fetchtest").apply { mkdirs() }, name)
                    f.delete()
                    OfflineMaps.fetchFile(url, f) { }
                    Log.i(TAG, "fetched $name: ${f.length()} bytes")
                } catch (t: Throwable) {
                    Log.w(TAG, "fetch: $t")
                }
            }, "aura-fetchtest").start()
        }
        if (i.getBooleanExtra("dump", false)) dump()
    }

    private fun dump() {
        val r = NavSession.route
        val p = NavSession.progress
        val w = RoadWatch.state
        val named = r?.steps?.count { it.name.isNotBlank() } ?: 0
        Log.i(TAG, "nav state=${NavSession.state} dest=${NavSession.destination?.name} rerouting=${NavSession.rerouting} route=${r?.let { "${it.distance.toInt()}m/${it.steps.size}steps/named$named" }} " +
            "limits=${r?.limits?.metres?.toInt()} better=${r?.better != null}")
        Log.i(TAG, "progress along=${p?.along?.toInt()} next=${p?.next} toNext=${p?.toNext?.toInt()} left=${p?.remaining?.toInt()} kmh=${p?.speedKmh} off=${p?.offRoute}")
        Log.i(TAG, "road limit=${w.limit} fromMap=${w.limitFromMap} over=${w.over} kmh=${w.speedKmh}")
        Log.i(TAG, "media current=${MediaMonitor.current?.let { "'${it.title}' playing=${it.playing} from=${it.sourcePkg}" }}")
        val at = CarLocation.lastKnown()
        Log.i(TAG, String.format(Locale.ROOT, "dark=%b sun=%.1f at=%s", Theme.isDarkNow(), at?.let { Daylight.altitude(it.lat, it.lon, System.currentTimeMillis()) } ?: Double.NaN, at?.let { "${it.lat},${it.lon}" }))
        Log.i(TAG, String.format(Locale.ROOT, "note=%s", NavNote.text.replace('\n', '|')))
    }
}
