package com.abdllh.aura.nav

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.util.dp
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.MapboxMapOptions
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import java.util.Locale

/**
 * A MapLibre map in the Aura style with the car, the route and the destination, plus the camera behaviour of a car
 * screen: follow the car north-up while browsing, heading-up and tilted while navigating, or stay where the user
 * dragged it. Used full screen by [MapsActivity] and small (TextureView, no gestures) on the home screen.
 *
 * The car and the destination pin are ordinary views laid over the map at their projected screen positions (updated
 * on every camera frame) rather than map icons: crisp at any tilt, and independent of the GPU's icon rendering.
 */
class AuraMap(ctx: Context, interactive: Boolean, texture: Boolean, private val guarded: Boolean = false) {
    enum class Camera { FOLLOW, NAVIGATE, FREE, OVERVIEW }

    /** The map with its overlays; add this to the layout. */
    val view: FrameLayout = FrameLayout(ctx)
    private val mapView: MapView
    private val carView = CarMarker(ctx)
    private val pinView = DestPin(ctx)
    var map: MapboxMap? = null
        private set
    private var style: Style? = null
    var camera = Camera.FOLLOW
        private set
    /** The user moved the map with a finger (the screen shows a "recenter" button then). */
    var onUserMoved: (() -> Unit)? = null
    var onReady: (() -> Unit)? = null
    /** The first frame has been drawn (whatever was shown in the map's place can go). */
    var onFirstFrame: (() -> Unit)? = null
    /** A long press on the map (interactive maps): drop a pin there. */
    var onLongPress: ((LatLon) -> Unit)? = null
    /** Fraction of the view height kept free above the car while navigating (puts the car low on the screen). */
    var navTopPadding = 0.42
    /** Extra start padding (e.g. a side panel covering the map). */
    var startPadding = 0

    private var carPos: LatLon? = null
    private var carBearing = 0.0
    private var anim: ValueAnimator? = null
    private var speedKmh = 0.0
    private var routePts: List<LatLon>? = null
    private var dest: LatLon? = null
    private val rtl: Boolean
    private val app = ctx.applicationContext

    // zoom kept by the map itself (fixes move the camera many times a second: an animated zoom would be cut short)
    private var followZoom = 15.0
    private var navZoomOffset = 0.0
    private var navBand = 0

    // fixes arrive about once a second: the car glides over the measured interval, so it never stops in between
    private var lastFixAt = 0L
    private var fixInterval = 1000.0
    private var cameraOnCar = false // follow() has positioned the camera on the car at least once

    /** Re-armed at every start: whatever covers the map until it has drawn (the home screen's backdrop) can go then. */
    private inner class FirstFrame : MapView.OnDidFinishRenderingFrameListener {
        private var done = false
        override fun onDidFinishRenderingFrame(fully: Boolean) {
            if (done) return
            done = true
            if (guarded) MapGuard.ok(app)
            onFirstFrame?.invoke()
            view.post { mapView.removeOnDidFinishRenderingFrameListener(this) }
        }
    }
    private var firstFrame = FirstFrame()

    init {
        Mapbox.getInstance(ctx.applicationContext)
        // Every request goes to the unit's own tile server (offline map, else the internet): without a network Android
        // reports "disconnected" and MapLibre would stop asking at all, so its connectivity check is overridden
        if (com.abdllh.aura.nav.offline.TileServer.running) Mapbox.setConnected(true)
        val opts = MapboxMapOptions.createFromAttributes(ctx)
            .textureMode(texture)
            .logoEnabled(false)
            .attributionEnabled(false)
            .compassEnabled(false)
            .foregroundLoadColor(Palette.mapLand)
        if (guarded) MapGuard.starting(app)
        mapView = MapView(ctx, opts)
        mapView.setMaximumFps(30) // plenty for a map on a weak GPU, and half the work of 60
        view.addView(mapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Overlays sit at the top-left corner and are moved by translation to their screen points; LEFT (not the
        // default START) so they do not start at the right edge in Arabic.
        view.addView(pinView, FrameLayout.LayoutParams(DestPin.W.dp, DestPin.H.dp, Gravity.TOP or Gravity.LEFT))
        view.addView(carView, FrameLayout.LayoutParams(CarMarker.SIZE.dp, CarMarker.SIZE.dp, Gravity.TOP or Gravity.LEFT))
        carView.visibility = View.GONE
        pinView.visibility = View.GONE
        rtl = ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                setAllGesturesEnabled(interactive)
                setRotateGesturesEnabled(interactive)
                setTiltGesturesEnabled(false)
                setCompassEnabled(false)
                setLogoEnabled(false)
                setAttributionEnabled(false)
            }
            m.setMaxPitchPreference(60.0)
            m.setMinZoomPreference(3.0)
            m.setMaxZoomPreference(19.5)
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapboxMap.OnCameraMoveStartedListener.REASON_API_GESTURE && interactive) {
                    camera = Camera.FREE
                    onUserMoved?.invoke()
                }
            }
            m.addOnCameraMoveListener { placeOverlays() }
            m.addOnCameraIdleListener { placeOverlays() }
            if (interactive) m.addOnMapLongClickListener { ll ->
                val l = onLongPress ?: return@addOnMapLongClickListener false
                l(LatLon(ll.latitude, ll.longitude))
                true
            }
            val start = CarLocation.lastKnown() ?: LatLon(24.7136, 46.6753) // Riyadh until the first fix
            m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(start.lat, start.lon)).zoom(followZoom).build()))
            loadStyle()
        }
    }

    private fun loadStyle() {
        val m = map ?: return
        m.setStyle(Style.Builder().fromJson(MapStyle.json(Locale.getDefault().language == "ar"))) { s ->
            style = s
            pushRoute()
            placeOverlays()
            onReady?.invoke()
        }
    }

    // ------------------------------------------------------------------------------------------ lifecycle
    fun onCreate(b: Bundle?) = mapView.onCreate(b)

    fun onStart() {
        mapView.onStart()
        firstFrame = FirstFrame().also { mapView.addOnDidFinishRenderingFrameListener(it) }
    }

    fun onResume() {
        mapView.onResume()
        // back from another app: draw once even if nothing moves (a texture view can come back empty otherwise)
        try { map?.triggerRepaint() } catch (_: Throwable) { }
    }

    fun onPause() = mapView.onPause()

    fun onStop() {
        if (guarded) MapGuard.ok(app) // still alive: the engine did not crash
        mapView.removeOnDidFinishRenderingFrameListener(firstFrame)
        mapView.onStop()
    }

    /** Frame-rate cap: 30 is plenty while browsing; navigation asks for 60 so the turning map stays fluid. */
    fun setFps(fps: Int) = mapView.setMaximumFps(fps)
    fun onLowMemory() = mapView.onLowMemory()
    fun onDestroy() {
        anim?.cancel()
        mapView.onDestroy()
    }

    // ------------------------------------------------------------------------------------------ data
    /**
     * Moves the car marker and the camera with it. The glide starts where the car is drawn now and lasts as long as
     * the fixes are apart (measured), so the car moves continuously instead of stopping before each fix.
     */
    fun setCar(p: LatLon, bearing: Double, speed: Double, ms: Long = -1) {
        speedKmh = speed
        val from = carPos
        val fromB = carBearing
        val now = android.os.SystemClock.uptimeMillis()
        if (lastFixAt > 0) {
            val dt = now - lastFixAt
            if (dt in 300..3000) fixInterval = fixInterval * 0.7 + dt * 0.3
        }
        lastFixAt = now
        // Standing still: GPS jitter (a few metres, a wandering heading) must not keep the map redrawing all the time
        // (once the camera has been put on the car: a fix that came before the map was ready still has to do that).
        if (from != null && cameraOnCar && ms != 0L && speed < 4 && Geo.distance(from, p) < 6) return
        anim?.cancel()
        if (from == null || Geo.distance(from, p) > 400 || ms == 0L) {
            carPos = p
            carBearing = bearing
            follow(false)
            placeOverlays()
            return
        }
        val dB = Geo.angleDiff(fromB, bearing)
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (ms > 0) ms else fixInterval.toLong().coerceIn(500L, 1600L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = (it.animatedValue as Float).toDouble()
                carPos = Geo.lerp(from, p, t)
                carBearing = (fromB + dB * t + 360) % 360
                follow(false)
                placeOverlays()
            }
            start()
        }
    }

    fun setRoute(points: List<LatLon>?) {
        routePts = points
        pushRoute()
    }

    fun setDestination(p: LatLon?) {
        dest = p
        placeOverlays()
    }

    private fun pushRoute() {
        val s = style ?: return
        val pts = routePts
        val json = if (pts == null || pts.size < 2) EMPTY else buildString {
            append("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
            pts.forEachIndexed { i, p -> if (i > 0) append(','); append('[').append(p.lon).append(',').append(p.lat).append(']') }
            append("]}}")
        }
        try { s.getSourceAs<GeoJsonSource>(MapStyle.SRC_ROUTE)?.setGeoJson(json) } catch (_: Throwable) { }
    }

    /**
     * Puts the car and the pin at the screen positions of their coordinates. While the camera follows the car, the
     * car sits exactly at the camera's focal point, so it is placed there directly: the arrow then stays perfectly
     * still while the map moves under it (a projected position would wobble against the map's own frames).
     */
    private fun placeOverlays() {
        val m = map ?: return
        val proj = m.projection
        val cam = m.cameraPosition
        val camBearing = cam.bearing
        val c = carPos
        if (c == null) carView.visibility = View.GONE else {
            val t = cam.target
            val pad = cam.padding
            val w = mapView.width.toFloat()
            val h = mapView.height.toFloat()
            val following = (camera == Camera.FOLLOW || camera == Camera.NAVIGATE) && t != null && w > 0 && h > 0 &&
                Geo.distance(LatLon(t.latitude, t.longitude), c) < 0.5
            val x: Float
            val y: Float
            if (following && pad != null && pad.size >= 4) {
                x = (pad[0] + (w - pad[0] - pad[2]) / 2).toFloat()
                y = (pad[1] + (h - pad[1] - pad[3]) / 2).toFloat()
            } else {
                val sp = proj.toScreenLocation(LatLng(c.lat, c.lon))
                x = sp.x
                y = sp.y
            }
            carView.visibility = View.VISIBLE
            // fixed sizes, not width/height: those are still 0 the first time a hidden overlay is shown
            carView.translationX = x - CarMarker.SIZE.dp / 2f
            carView.translationY = y - CarMarker.SIZE.dp / 2f
            carView.rotation = ((carBearing - camBearing) % 360).toFloat()
        }
        val d = dest
        if (d == null) pinView.visibility = View.GONE else {
            val sp = proj.toScreenLocation(LatLng(d.lat, d.lon))
            pinView.visibility = View.VISIBLE
            pinView.translationX = sp.x - DestPin.W.dp / 2f
            pinView.translationY = sp.y - DestPin.H.dp.toFloat()
        }
    }

    // ------------------------------------------------------------------------------------------ camera
    fun setCamera(c: Camera) {
        camera = c
        follow(true)
    }

    /** Keeps the camera on the car in FOLLOW / NAVIGATE mode. */
    private fun follow(animate: Boolean) {
        val m = map ?: return
        val p = carPos ?: return
        val h = mapView.height.toDouble().coerceAtLeast(1.0)
        val side = startPadding.toDouble()
        val pos = when (camera) {
            Camera.FOLLOW -> CameraPosition.Builder().target(LatLng(p.lat, p.lon)).bearing(0.0).tilt(0.0)
                .zoom(followZoom)
                .padding(if (rtl) 0.0 else side, 0.0, if (rtl) side else 0.0, 0.0).build()
            Camera.NAVIGATE -> {
                // faster = further out; 5 km/h of hysteresis, so driving at a band edge does not pump the zoom
                while (navBand < NAV_EDGES.size && speedKmh > NAV_EDGES[navBand] + 5) navBand++
                while (navBand > 0 && speedKmh < NAV_EDGES[navBand - 1] - 5) navBand--
                val zoom = (NAV_ZOOMS[navBand] + navZoomOffset).coerceIn(12.0, 19.0)
                CameraPosition.Builder().target(LatLng(p.lat, p.lon)).bearing(carBearing).tilt(52.0).zoom(zoom)
                    .padding(if (rtl) 0.0 else side, h * navTopPadding, if (rtl) side else 0.0, 0.0).build()
            }
            else -> return
        }
        if (animate) m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 700)
        else m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
        cameraOnCar = true
    }

    /** Shows the whole route (or the car and a place) with room for the panels. */
    fun overview(points: List<LatLon>, padStart: Int, padTop: Int, padEnd: Int, padBottom: Int) {
        val m = map ?: return
        if (points.isEmpty()) return
        camera = Camera.OVERVIEW
        val l = if (rtl) padEnd else padStart
        val r = if (rtl) padStart else padEnd
        if (points.size == 1) {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                .target(LatLng(points[0].lat, points[0].lon)).zoom(15.5).bearing(0.0).tilt(0.0)
                .padding(l.toDouble(), padTop.toDouble(), r.toDouble(), padBottom.toDouble()).build()), 900)
            return
        }
        val b = LatLngBounds.Builder()
        points.forEach { b.include(LatLng(it.lat, it.lon)) }
        try {
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 0.0, 0.0, l, padTop, r, padBottom), 900)
        } catch (_: Throwable) {
        }
    }

    /** + / − buttons: following the car, the zoom is remembered (the next fix would otherwise undo it). */
    fun zoomBy(d: Double) {
        val m = map ?: return
        when (camera) {
            Camera.FOLLOW -> { followZoom = (followZoom + d).coerceIn(12.0, 18.5); follow(true) }
            Camera.NAVIGATE -> { navZoomOffset = (navZoomOffset + d).coerceIn(-3.0, 2.0); follow(true) }
            else -> m.animateCamera(CameraUpdateFactory.zoomBy(d), 300)
        }
    }

    fun northUp() {
        map?.animateCamera(CameraUpdateFactory.bearingTo(0.0), 400)
    }

    companion object {
        private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
        private val NAV_EDGES = doubleArrayOf(30.0, 60.0, 90.0)       // km/h
        private val NAV_ZOOMS = doubleArrayOf(16.8, 16.4, 15.9, 15.3) // per speed band
    }
}

/** The car: accent disc with a heading arrow, white rim and a soft halo. Rotated to the car's heading. */
private class CarMarker(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Path()

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 17f.dp
        p.color = Palette.withAlpha(Palette.accent, 0.20f)
        c.drawCircle(cx, cy, width / 2f, p)
        p.color = 0x40000000
        c.drawCircle(cx, cy + 1.5f.dp, r + 0.5f.dp, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(cx, cy, r, p)
        p.color = Palette.accent
        c.drawCircle(cx, cy, r - 3f.dp, p)
        arrow.reset()
        arrow.moveTo(cx, cy - 9f.dp)
        arrow.lineTo(cx + 7f.dp, cy + 7.5f.dp)
        arrow.lineTo(cx, cy + 4f.dp)
        arrow.lineTo(cx - 7f.dp, cy + 7.5f.dp)
        arrow.close()
        p.color = 0xFFFFFFFF.toInt()
        c.drawPath(arrow, p)
    }

    companion object { const val SIZE = 64 }
}

/** The destination: accent pin with a white dot; its bottom tip marks the place. */
private class DestPin(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w / 2f - 2f.dp
        path.reset()
        path.addArc(RectF(w / 2f - r, 2f.dp, w / 2f + r, 2f.dp + 2 * r), 140f, 260f)
        path.lineTo(w / 2f, h - 2f.dp)
        path.close()
        p.color = 0x40000000
        c.save(); c.translate(0f, 1.5f.dp); c.drawPath(path, p); c.restore()
        p.color = Palette.accent
        c.drawPath(path, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(w / 2f, 2f.dp + r, r * 0.42f, p)
    }

    companion object {
        const val W = 40
        const val H = 54
    }
}

/**
 * The map engine is native code: if it ever crashes on this GPU the process dies without passing through CrashGuard,
 * and a home screen that embeds the map would crash again on every start. Each map start is marked on disk first and
 * cleared once a frame was drawn (or the map was shut down normally); after two starts that never got there, the home
 * screen stops embedding the live map (Maps itself still opens on request).
 */
object MapGuard {
    // a tiny file of its own: the marker is written synchronously before the map starts
    private fun sp(c: Context) = c.getSharedPreferences("aura_mapguard", Context.MODE_PRIVATE)

    fun homeMapAllowed(c: Context): Boolean {
        val sp = sp(c)
        if (sp.getBoolean("starting", false)) {
            sp.edit().putInt("fails", sp.getInt("fails", 0) + 1).putBoolean("starting", false).commit()
        }
        return sp.getInt("fails", 0) < 2
    }

    fun starting(c: Context) { sp(c).edit().putBoolean("starting", true).commit() }

    fun ok(c: Context) {
        val sp = sp(c)
        if (sp.getBoolean("starting", false) || sp.getInt("fails", 0) != 0) sp.edit().putBoolean("starting", false).putInt("fails", 0).apply()
    }
}
