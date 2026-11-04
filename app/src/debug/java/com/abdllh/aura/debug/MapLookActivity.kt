package com.abdllh.aura.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.abdllh.aura.nav.AuraMap
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.MapStyle
import com.abdllh.aura.nav.Polyline
import com.abdllh.aura.nav.offline.PackTiles
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.util.Prefs
import com.mapbox.geojson.FeatureCollection
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import java.io.File
import java.util.Locale

/**
 * Debug-only: the real map (AuraMap, MapStyle, TileServer, the pack) and nothing else on the screen, for looking at the style on
 * the real renderer at any camera, as scripts/map-look.ps1 does:
 *   am start -S -n com.abdllh.aura/.debug.MapLookActivity --es theme dark|light --es lang ar|en
 *   am broadcast -a com.abdllh.aura.debug.MAPLOOK --es cam lat,lon,zoom,tilt,bearing[,top,right,bottom,left]
 *      --ez route true                  the route line from files/maplook-route.txt (lon,lat a line)   --ef turn metres   the arrow there on it
 *      --es dest lat,lon                the destination
 *      --es extras lat,lon              a camera, a signal and a bump around it, as MapExtras would send them
 *      --ez style true                  writes files/maplook-style.json     --ez stats true   the pack's read times in the log
 *      --ez cached true                 the sources not volatile, to measure what that is worth (the size of files/mbgl-offline.db)
 * The log (tag AuraMapLook) says "drawn" when MapLibre has all it asked for and has placed the labels.
 */
class MapLookActivity : Activity() {
    private lateinit var map: AuraMap
    private var movedAt = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try { handle(i) } catch (t: Throwable) { Log.w(TAG, "$t") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        intent.getStringExtra("theme")?.let { Prefs.theme = it }
        Theme.refresh()
        intent.getStringExtra("lang")?.let { Prefs.lang = it; Locale.setDefault(Locale(it)) }
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        map = AuraMap(this, interactive = true, texture = false)
        map.onCreate(savedInstanceState)
        setContentView(map.view)
        // (a frame "fully" drawn right after a move can be the old tiles' last: a moment must have passed, and only once per move)
        (map.view.getChildAt(0) as MapView).addOnDidFinishRenderingFrameListener { fully ->
            if (fully && movedAt != 0L && SystemClock.elapsedRealtime() - movedAt > 250) { movedAt = 0L; Log.i(TAG, "drawn") }
        }
        map.onReady = { Log.i(TAG, "style ready, pack=${PackTiles.present}"); map.setCamera(AuraMap.Camera.FREE) }
        registerReceiver(receiver, IntentFilter(ACTION))
    }

    private fun handle(i: Intent) {
        val m = map.map ?: return
        if (i.getBooleanExtra("route", false)) {
            val pts = File(filesDir, "maplook-route.txt").readLines().filter { it.isNotBlank() }.map { it.split(',').let { p -> LatLon(p[1].toDouble(), p[0].toDouble()) } }
            map.setRoute(pts)
            if (i.hasExtra("turn")) map.setTurn(Polyline(pts), i.getFloatExtra("turn", 0f).toDouble())
        }
        i.getStringExtra("dest")?.let { d -> d.split(',').let { p -> map.setDestination(LatLon(p[0].toDouble(), p[1].toDouble())) } }
        i.getStringExtra("extras")?.let { e ->
            val (lat, lon) = e.split(',').map { it.toDouble() }
            fun f(kind: String, label: String, dLat: Double, dLon: Double) =
                """{"type":"Feature","properties":{"kind":"$kind","label":"$label"},"geometry":{"type":"Point","coordinates":[${lon + dLon},${lat + dLat}]}}"""
            val json = """{"type":"FeatureCollection","features":[${f("camera", "ساهر", 0.002, 0.0)},${f("signal", "", -0.002, 0.002)},${f("bump", "مطب", 0.0, -0.003)}]}"""
            m.style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_EXTRAS)?.setGeoJson(FeatureCollection.fromJson(json))
        }
        i.getStringExtra("cam")?.let { c ->
            val v = c.split(',').map { it.toDouble() }
            val b = CameraPosition.Builder().target(LatLng(v[0], v[1])).zoom(v[2]).tilt(v.getOrElse(3) { 0.0 }).bearing(v.getOrElse(4) { 0.0 })
            b.padding(v.getOrElse(8) { 0.0 }, v.getOrElse(5) { 0.0 }, v.getOrElse(6) { 0.0 }, v.getOrElse(7) { 0.0 })
            m.moveCamera(CameraUpdateFactory.newCameraPosition(b.build()))
            movedAt = SystemClock.elapsedRealtime()
            m.triggerRepaint()
        }
        if (i.getBooleanExtra("style", false)) {
            File(filesDir, "maplook-style.json").writeText(MapStyle.json(Locale.getDefault().language == "ar"))
            Log.i(TAG, "style written")
        }
        if (i.getBooleanExtra("stats", false)) Log.i(TAG, "pack: ${PackTiles.readStats()}")
        if (i.getBooleanExtra("cached", false)) m.style?.let { s -> s.getSource("omt")?.setVolatile(false); s.getSource("places")?.setVolatile(false) }
    }

    override fun onStart() { super.onStart(); map.onStart() }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }
    override fun onStop() { map.onStop(); super.onStop() }
    override fun onDestroy() {
        unregisterReceiver(receiver)
        map.onDestroy()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AuraMapLook"
        const val ACTION = "com.abdllh.aura.debug.MAPLOOK"
    }
}
