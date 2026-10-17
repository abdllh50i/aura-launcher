package com.abdllh.aura.nav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.util.dp
import com.mapbox.geojson.Feature
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.MapboxMapOptions
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.layers.Property
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A MapLibre map in the Aura style with the car, the route and the destination, plus the camera behaviour of a car
 * screen: follow the car north-up while browsing, heading-up and tilted while navigating, or stay where the user
 * dragged it. Used full screen by [MapsActivity] and small (TextureView, no gestures) on the home screen.
 *
 * The car is an ordinary view laid over the map at its screen point rather than a map icon: crisp at any tilt, and
 * independent of the GPU's icon rendering. The destination is drawn by the map itself ([MapStyle.SRC_DEST]).
 *
 * Everything that moves on its own — the car between fixes, the camera following it, the change from one camera to
 * another — is driven by one frame loop (Choreographer) that runs only while something moves, and sets the camera at
 * most once per drawn frame. Until 1.11 a camera change was MapLibre's own animation, which the next fix's camera
 * update cancelled at once: in a moving car Go, Recenter and the zoom buttons jumped instead of moving.
 */
class AuraMap(ctx: Context, interactive: Boolean, texture: Boolean, private val guarded: Boolean = false) {
    enum class Camera { FOLLOW, NAVIGATE, FREE, OVERVIEW }

    /** The map with its overlays; add this to the layout. */
    val view: FrameLayout = FrameLayout(ctx)
    private val mapView: MapView
    private val carView = CarMarker(ctx)
    var map: MapboxMap? = null
        private set
    private var style: Style? = null
    var camera = Camera.FOLLOW
        private set
    /** The user moved the map with a finger (the screen shows a "recenter" button then). */
    var onUserMoved: (() -> Unit)? = null
    var onReady: (() -> Unit)? = null
    /**
     * The map has drawn itself — its tiles, not only the land colour of its first frames — or 2.5 s have passed since
     * its first frame (tiles that never come, offline). Whatever covered the map until then can go.
     */
    var onDrawn: (() -> Unit)? = null
    /** A long press on the map (interactive maps): drop a pin there. */
    var onLongPress: ((LatLon) -> Unit)? = null
    /** A tap on one of the map's places (interactive maps): its name in the UI language, its kind ([Poi]), where it is. */
    var onPoiTap: ((name: String, kind: String, at: LatLon) -> Unit)? = null
    /**
     * Fraction of the view height kept free above the car while navigating (puts the car low on the screen); a little
     * more at motorway speed, where what is far ahead matters more.
     */
    var navTopPadding = 0.42
    /** Extra start padding (e.g. a side panel covering the map). */
    var startPadding = 0
    /**
     * Where a picture of this map is kept (the home map): taken when the map has come to rest, shown by
     * [com.abdllh.aura.ui.MapBackdropView] at the next start until the map has drawn, so the screen opens on the real
     * streets instead of invented ones.
     */
    var pictureFile: File? = null

    private val rtl: Boolean
    private val app = ctx.applicationContext
    private val density = ctx.resources.displayMetrics.density

    // ---- the car as drawn
    private var carPos: LatLon? = null
    private var carBearing = 0.0
    private var speedKmh = 0.0
    private var lastFix: LatLon? = null
    private var lastFixAt = 0L
    // fixes arrive about once a second (measured): the car glides over that interval, so it never stops in between
    private var fixInterval = 1000.0

    // On the driven route the car moves along the line, not across: from where it is drawn towards where the car will
    // be at the next fix (the fix plus the distance its speed covers), so the arrow is where the car is, not one fix
    // behind it, and it follows every curve of the line. A late fix (a tunnel) lets it carry on at its last speed.
    private var onRoute = false
    private var routeSeg = 0
    private var drawnAlong = 0.0
    private var fixAlong = 0.0
    private var fixKmh = 0.0
    private var fixAt = 0L
    private var aheadErr = 0.0  // where the car was drawn ahead (+) or behind (-) the fix when it came
    private var settleS = 1.0   // the time that difference is taken away over
    private var correcting = false

    // Off the route (or with none): a straight glide from where the car is drawn to the fix.
    private var glideFrom: LatLon? = null
    private var glideTo: LatLon? = null
    private var glideFromB = 0.0
    private var glideTurn = 0.0
    private var glideStart = 0L
    private var glideMs = 1L

    // ---- the route, the turn, the destination
    private var routePts: List<LatLon>? = null
    // driving: the route the car is on; only what is ahead of the car as it is drawn shows ([setRouteAhead])
    private var routeLine: Polyline? = null
    private var farFrom = -1.0   // where along it the long part (SRC_ROUTE) starts; before that, the short part
    private var trimSeg = 0
    private var trimAlong = 0.0
    private var trimAt = 0L
    private var turnLine: Polyline? = null
    private var turnAt = 0.0
    private var turnAfter = TURN_AFTER_M
    private var turnScale = 1.0
    private var dest: LatLon? = null
    private var routeFaded = false
    private var extrasVersion = -1

    // ---- the camera
    // zoom kept by the map itself while following (the camera is set on every frame the car moves)
    private var followZoom = 15.0
    private var navZoomOffset = 0.0
    // navigating: what the camera eases towards — zoom by speed and the turn ahead, tilt, the room above the car,
    // and a heading that looks a little down the road
    private var navZoom = 16.8
    private var navTilt = 50.0
    private var navPad = 0.42
    private var camBearing = 0.0
    private var steerReady = false
    // a change of camera (Go, Recenter, the zoom buttons, the end of a trip): from where the camera was to where it
    // follows the car, eased over BLEND_MS while the car keeps moving
    private var blendFrom: Cam? = null
    private var blendStart = 0L
    private var blendMs = BLEND_MS
    private var flightUntil = 0L // a recenter from far away is MapLibre's own flight; the loop waits for it
    private var applied: Cam? = null // the camera the loop set last
    private var camOnCar = false     // ... and it was on the car (the car is drawn at the focal point then)
    private var cameraOnCar = false  // the camera has been put on the car at least once
    private var inDrive = false      // the loop is setting the camera (its own move callbacks are not needed)

    // ---- the frame loop
    private var started = false
    private var looping = false
    private var lastDriveNs = 0L
    private val frame = Choreographer.FrameCallback { onFrame(it) }
    private var fpsWanted = 30
    private var fpsCap = 30
    private var renders = 0
    private var measureFrom = 0L
    private val counter = MapView.OnDidFinishRenderingFrameListener { renders++ }

    private var drawn = false
    private var pictureAt = 0L
    private val stale = Runnable { carView.setStale(true) }
    private val keepPicture = Runnable { takePicture() }

    /** Re-armed at every start: whatever covers the map until it has drawn (the home screen's backdrop) can go then. */
    private inner class Drawn : MapView.OnDidFinishRenderingFrameListener {
        private var first = false
        private var done = false
        private val late = Runnable { finish() }
        override fun onDidFinishRenderingFrame(fully: Boolean) {
            if (done) return
            if (!first) {
                first = true
                if (guarded) MapGuard.ok(app)
                view.postDelayed(late, DRAWN_LATE_MS)
            }
            // (before the style is there, a frame of land colour alone counts as "fully" drawn)
            if (fully && style != null) finish()
        }
        fun finish() {
            if (done) return
            done = true
            view.removeCallbacks(late)
            drawn = true
            onDrawn?.invoke()
            if (pictureFile != null && pictureWorthy()) view.postDelayed(keepPicture, PICTURE_REST_MS)
            view.post { mapView.removeOnDidFinishRenderingFrameListener(this) }
        }
        fun cancel() = view.removeCallbacks(late)
    }
    private var firstFrame = Drawn()

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
        // The car sits at the top-left corner and is moved by translation to its screen point; LEFT (not the default
        // START) so it does not start at the right edge in Arabic.
        view.addView(carView, FrameLayout.LayoutParams(CarMarker.SIZE.dp, CarMarker.SIZE.dp, Gravity.TOP or Gravity.LEFT))
        carView.visibility = View.GONE
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
                    blendFrom = null
                    flightUntil = 0L
                    setMode(Camera.FREE)
                    onUserMoved?.invoke()
                }
            }
            // (the loop places the car itself after setting the camera; these are for gestures and MapLibre's flights)
            m.addOnCameraMoveListener { if (!inDrive) placeOverlays() }
            m.addOnCameraIdleListener { if (!inDrive) { placeOverlays(); rescaleTurn() } }
            if (interactive) m.addOnMapLongClickListener { ll ->
                val l = onLongPress ?: return@addOnMapLongClickListener false
                l(LatLon(ll.latitude, ll.longitude))
                true
            }
            if (interactive) m.addOnMapClickListener { ll -> tapPlace(m, ll) }
            val start = CarLocation.lastKnown() ?: LatLon(24.7136, 46.6753) // Riyadh until the first fix
            m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(start.lat, start.lon)).zoom(followZoom).build()))
            loadStyle()
        }
    }

    private fun loadStyle() {
        val m = map ?: return
        val arabic = Locale.getDefault().language == "ar"
        m.setStyle(Style.Builder().fromJson(MapStyle.json(arabic)).withImage(MapStyle.POI_DOT, MapStyle.poiDot(), true)) { s ->
            style = s
            extrasVersion = -1
            routeFaded = false
            pushRoute()
            pushTurn()
            pushDest()
            pushExtras()
            syncLayers()
            placeOverlays()
            onReady?.invoke()
        }
    }

    /** The owner's additions ([MapExtras]) on the map; again whenever a newer set was fetched. */
    private val extrasChanged: () -> Unit = { pushExtras() }

    /** (Only a set this map does not show yet: the text is made once per set and language, and parsing it is not free.) */
    private fun pushExtras() {
        val s = style ?: return
        val v = MapExtras.version
        if (v == extrasVersion) return
        try {
            s.getSourceAs<GeoJsonSource>(MapStyle.SRC_EXTRAS)?.setGeoJson(MapExtras.geoJson(Locale.getDefault().language == "ar"))
            extrasVersion = v
        } catch (_: Throwable) {
        }
    }

    /** A tap: the place of the map nearest to the finger, if one of the drawn ones is under it. */
    private fun tapPlace(m: MapboxMap, ll: LatLng): Boolean {
        val l = onPoiTap ?: return false
        val at = m.projection.toScreenLocation(ll)
        val r = 26f.dp // (a finger on a car screen, not a pixel)
        val hits = try {
            m.queryRenderedFeatures(RectF(at.x - r, at.y - r, at.x + r, at.y + r), *MapStyle.POI_LAYERS)
        } catch (_: Throwable) {
            emptyList()
        }
        val arabic = Locale.getDefault().language == "ar"
        fun text(f: Feature, key: String): String? =
            if (f.hasNonNullValueForProperty(key)) f.getStringProperty(key)?.takeIf { it.isNotBlank() } else null
        var best: Feature? = null
        var bestPos: LatLon? = null
        var bestD = Double.MAX_VALUE
        for (f in hits) {
            val g = f.geometry() as? Point ?: continue
            val pos = LatLon(g.latitude(), g.longitude())
            val sp = m.projection.toScreenLocation(LatLng(pos.lat, pos.lon))
            val d = Math.hypot((sp.x - at.x).toDouble(), (sp.y - at.y).toDouble())
            if (d < bestD) { bestD = d; best = f; bestPos = pos }
        }
        val f = best ?: return false
        val name = (if (arabic) text(f, "name:ar") else text(f, "name:en") ?: text(f, "name:latin")) ?: text(f, "name") ?: return false
        l(name, text(f, "class").orEmpty(), bestPos ?: return false)
        return true
    }

    // ------------------------------------------------------------------------------------------ lifecycle
    fun onCreate(b: Bundle?) = mapView.onCreate(b)

    fun onStart() {
        mapView.onStart()
        started = true
        firstFrame = Drawn().also { mapView.addOnDidFinishRenderingFrameListener(it) }
        MapExtras.addListener(extrasChanged)
        pushExtras() // (fetched while this map was not on the screen)
        if (carPos != null) {
            view.postDelayed(stale, STALE_MS) // (unless fixes come again)
            wake()
        }
    }

    fun onResume() {
        mapView.onResume()
        // back from another app: draw once even if nothing moves (a texture view can come back empty otherwise)
        try { map?.triggerRepaint() } catch (_: Throwable) { }
    }

    fun onPause() = mapView.onPause()

    fun onStop() {
        if (guarded) MapGuard.ok(app) // still alive: the engine did not crash
        started = false
        sleep()
        view.removeCallbacks(stale)
        view.removeCallbacks(keepPicture)
        firstFrame.cancel()
        MapExtras.removeListener(extrasChanged)
        mapView.removeOnDidFinishRenderingFrameListener(firstFrame)
        mapView.onStop()
    }

    /**
     * Frame-rate cap: 30 is plenty while browsing; navigation asks for 60 so the turning map stays fluid. A map that
     * cannot keep up with 60 (fewer than 70 % of the frames drawn over a few seconds of driving) drops to an even 30
     * for the rest of the trip: frames alternating between one and two vsyncs look worse than a steady 30.
     */
    fun setFps(fps: Int) {
        if (fps == fpsWanted) return
        fpsWanted = fps
        fpsCap = fps
        mapView.setMaximumFps(fps)
        measureFrom = 0L
        if (fps > 30) mapView.addOnDidFinishRenderingFrameListener(counter) else mapView.removeOnDidFinishRenderingFrameListener(counter)
    }

    fun onLowMemory() = mapView.onLowMemory()
    fun onDestroy() {
        sleep()
        mapView.onDestroy()
    }

    // ------------------------------------------------------------------------------------------ data
    /**
     * A fix: the car glides there (along the route when it is on the one being driven), and the camera with it. The
     * same fix given again (the screen redrawn for another reason) only says that fixes are still coming.
     */
    fun setCar(p: LatLon, bearing: Double, speed: Double) {
        val now = SystemClock.uptimeMillis()
        view.removeCallbacks(stale)
        view.postDelayed(stale, STALE_MS)
        carView.setStale(false)
        syncRerouting() // (the screens give the driven route only when it changes, the fixes all the time)
        if (p == lastFix && speed == speedKmh) return
        if (lastFixAt > 0) {
            val dt = now - lastFixAt
            if (dt in 300..3000) fixInterval = fixInterval * 0.7 + dt * 0.3
        }
        lastFixAt = now
        lastFix = p
        speedKmh = speed
        val line = routeLine
        val snap = if (line != null) onLine(line, p) else null
        if (line != null && snap != null) fixOnRoute(line, snap, speed, now) else if (!fixOffRoute(p, bearing, speed, now)) return
        wake()
    }

    /** Where on the driven route a fix is, when it is on it (navigation gives fixes snapped to it: no offset at all). */
    private fun onLine(line: Polyline, p: LatLon): Polyline.Snap? {
        var s = line.snap(p, routeSeg, 3, 80)
        if (s == null || s.offset > ON_ROUTE_M) s = line.snap(p, 0, 0, line.points.size)
        return s?.takeIf { it.offset <= ON_ROUTE_M }
    }

    private fun fixOnRoute(line: Polyline, snap: Polyline.Snap, speed: Double, now: Long) {
        val along = snap.along
        routeSeg = snap.segment
        val jump = !onRoute || carPos == null || abs(along - drawnAlong) > JUMP_M
        onRoute = true
        fixAlong = along
        fixKmh = speed
        fixAt = now
        if (jump) {
            drawnAlong = along
            aheadErr = 0.0
            correcting = false
            placeOnRoute(line)
        } else {
            aheadErr = drawnAlong - along
            // drawn far ahead of a car that braked hard: back over a short glide; a smaller lead is waited out instead
            correcting = aheadErr > BACK_M
        }
        settleS = if (correcting) 0.4 else intervalS()
    }

    /** Off the route: returns false for GPS jitter of a standing car (the map then does not move at all). */
    private fun fixOffRoute(p: LatLon, bearing: Double, speed: Double, now: Long): Boolean {
        val wasOnRoute = onRoute
        onRoute = false
        val from = carPos
        // Standing still: jitter (a few metres, a wandering heading) must not keep the map redrawing all the time
        // (once the camera has been put on the car: a fix that came before the map was ready still has to do that).
        if (from != null && !wasOnRoute && cameraOnCar && speed < 4 && Geo.distance(from, p) < 6) return false
        // a GPS course at walking pace is noise (a car park flips it about): the arrow keeps the last heading then
        val b = if (from != null && speed < HOLD_KMH) carBearing else bearing
        if (from == null || Geo.distance(from, p) > JUMP_M) {
            carPos = p
            carBearing = b
            glideFrom = null
            return true
        }
        glideFrom = from
        glideTo = p
        glideFromB = carBearing
        glideTurn = Geo.angleDiff(carBearing, b)
        glideStart = now
        glideMs = fixInterval.toLong().coerceIn(500L, 1600L)
        return true
    }

    private fun intervalS() = (fixInterval / 1000.0).coerceIn(0.5, 1.6)

    /** Moves the drawn car for this frame; true while it is still on its way. */
    private fun movePuck(now: Long): Boolean {
        val line = routeLine
        if (onRoute && line != null) {
            val since = (now - fixAt) / 1000.0
            val i = intervalS()
            val v = if (fixKmh < HOLD_KMH - 1) 0.0 else min(fixKmh / 3.6, LEAD_MAX_M / i)
            val coast = if (fixKmh > 20) COAST_S else 1.5 * i
            val e = (since / settleS).coerceAtMost(1.0)
            var d = fixAlong + v * min(since, coast) + aheadErr * (1 - e)
            if (!correcting && d < drawnAlong) d = drawnAlong // never backwards for a small lead: it is waited out
            drawnAlong = d.coerceIn(0.0, line.length)
            placeOnRoute(line)
            return e < 1.0 || (v > 0 && since < coast)
        }
        val from = glideFrom ?: return false
        val to = glideTo ?: return false
        val t = ((now - glideStart).toDouble() / glideMs).coerceIn(0.0, 1.0)
        carPos = Geo.lerp(from, to, t)
        carBearing = (glideFromB + glideTurn * t + 360) % 360
        if (t >= 1.0) glideFrom = null
        return t < 1.0
    }

    /** The car at [drawnAlong]; its arrow along the line across a few metres, so it turns smoothly through a vertex. */
    private fun placeOnRoute(line: Polyline) {
        val d = drawnAlong
        carPos = line.at(d).first
        val a = line.at((d - 6).coerceAtLeast(0.0)).first
        val b = line.at((d + 6).coerceAtMost(line.length)).first
        if (Geo.distance(a, b) > 1) carBearing = Geo.bearing(a, b)
    }

    /** A route shown whole (a preview, before driving it). */
    fun setRoute(points: List<LatLon>?) {
        routePts = points
        routeLine = null
        onRoute = false
        carView.setNav(false)
        syncRerouting()
        pushRoute()
    }

    /**
     * The route being driven: from now on only the part ahead of the car shows, and it starts where the car is
     * drawn, at every moment (the car glides between fixes: the line is cut at the gliding car, not at the fixes).
     * While a new route is being looked for, the old one is faded (it is no longer the way).
     */
    fun setRouteAhead(line: Polyline) {
        if (line === routeLine) return
        routeLine = line
        routePts = null
        onRoute = false
        routeSeg = 0
        carView.setNav(true)
        syncRerouting()
        pushRoute()
    }

    private fun syncRerouting() {
        val faded = routeLine != null && NavSession.rerouting
        if (faded == routeFaded) return
        val s = style ?: return
        routeFaded = faded
        // (the style's own transition fades it)
        for (id in MapStyle.ROUTE_LAYERS) s.getLayer(id)?.setProperties(PropertyFactory.lineOpacity(if (faded) 0.45f else 1f))
    }

    /**
     * Cuts the driven route at the car. The long rest of the route is one source, rebuilt only when the car comes
     * near its start; the stretch from the car to there is another, a few dozen points, set again on every call
     * that comes at least [TRIM_EVERY_MS] after the last (the map takes a short line in well under a frame).
     */
    private fun trimRoute(force: Boolean) {
        val line = routeLine ?: return
        if (style == null) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - trimAt < TRIM_EVERY_MS) return
        trimAt = now
        val c = carPos
        if (onRoute) trimAlong = drawnAlong // (the car is drawn on the line: no need to look for it)
        else if (c != null) {
            var snap = line.snap(c, trimSeg, 2, 60)
            // not near where it was last (the map opened in the middle of a trip, a tunnel): anywhere on the route
            if (snap == null || snap.offset > OFF_ROUTE_M) snap = line.snap(c, 0, 0, line.points.size)
            // (off the route altogether: the line stays as it was until the car is back, or a new route comes)
            if (snap != null && snap.offset <= OFF_ROUTE_M) { trimSeg = snap.segment; trimAlong = snap.along }
        }
        val along = trimAlong
        if (farFrom < 0 || along > farFrom - NEAR_MIN_M || along < farFrom - NEAR_M - 50) {
            farFrom = minOf(along + NEAR_M, line.length)
            pushLine(MapStyle.SRC_ROUTE, if (farFrom < line.length - 1) line.tail(farFrom) else null)
        }
        pushLine(MapStyle.SRC_ROUTE_NEAR, line.slice(along, minOf(farFrom + 2.0, line.length)))
    }

    fun setDestination(p: LatLon?) {
        if (p == dest) return
        dest = p
        pushDest()
    }

    private fun pushDest() {
        val s = style ?: return
        val d = dest
        val json = if (d == null) EMPTY else """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${d.lon},${d.lat}]}}"""
        try { s.getSourceAs<GeoJsonSource>(MapStyle.SRC_DEST)?.setGeoJson(json) } catch (_: Throwable) { }
    }

    /**
     * The arrow of the next turn, drawn on the route: the turn is [at] metres along [line]; null for none. The arrow
     * is made here, in metres on the ground but longer the further out the map is (at motorway zoom a fixed length
     * shrank to a blob just when exits come quickly); navigating, the camera also closes in on it as it nears. It
     * runs on at least [after] metres past the turn (through a roundabout: to its exit, so the head shows the way out).
     */
    fun setTurn(line: Polyline?, at: Double = 0.0, after: Double = TURN_AFTER_M) {
        if (line === turnLine && at == turnAt && after == turnAfter) return
        turnLine = line
        turnAt = at
        turnAfter = after
        pushTurn()
    }

    /** How much longer the arrow is drawn than at street zoom: twice per zoom level out, up to three times. */
    private fun turnK(): Double {
        val z = applied?.zoom ?: map?.cameraPosition?.zoom ?: TURN_ZOOM
        return 2.0.pow(TURN_ZOOM - z).coerceIn(1.0, 3.0)
    }

    /** Again when the zoom has changed the arrow's scale by more than 15 %. */
    private fun rescaleTurn() {
        if (turnLine != null && abs(turnK() / turnScale - 1) > 0.15) pushTurn()
    }

    private fun pushTurn() {
        val s = style ?: return
        val line = turnLine
        val k = turnK()
        turnScale = k
        val json = if (line == null || line.points.size < 2) EMPTY else buildString {
            val end = turnAt + maxOf(turnAfter, TURN_AFTER_M * k)
            val pts = line.slice(turnAt - TURN_BEFORE_M * k, end)
            val bearing = line.at(end).second
            // the head: a triangle in metres on the ground, so it tilts and turns with the map like the line it ends
            val tip = pts.last()
            val head = listOf(Geo.offset(tip, bearing - 90, HEAD_HALF_M * k), Geo.offset(tip, bearing, HEAD_LENGTH_M * k),
                Geo.offset(tip, bearing + 90, HEAD_HALF_M * k))
            fun coords(list: List<LatLon>) = list.joinToString(",") { "[${it.lon},${it.lat}]" }
            append("""{"type":"FeatureCollection","features":[""")
            append("""{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${coords(pts)}]}},""")
            append("""{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[${coords(head + head.first())}]]}}""")
            append("]}")
        }
        try { s.getSourceAs<GeoJsonSource>(MapStyle.SRC_TURN)?.setGeoJson(json) } catch (_: Throwable) { }
    }

    private fun pushRoute() {
        if (style == null) return
        if (routeLine != null) {
            farFrom = -1.0
            trimSeg = 0
            trimAlong = 0.0
            trimRoute(true)
            return
        }
        pushLine(MapStyle.SRC_ROUTE, routePts)
        pushLine(MapStyle.SRC_ROUTE_NEAR, null)
    }

    private fun pushLine(source: String, pts: List<LatLon>?) {
        val s = style ?: return
        val json = if (pts == null || pts.size < 2) EMPTY else buildString {
            append("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
            pts.forEachIndexed { i, p -> if (i > 0) append(','); append('[').append(p.lon).append(',').append(p.lat).append(']') }
            append("]}}")
        }
        try { s.getSourceAs<GeoJsonSource>(source)?.setGeoJson(json) } catch (_: Throwable) { }
    }

    /**
     * Puts the car at the screen position of its coordinates. While the camera follows the car, the car sits exactly
     * at the camera's focal point, so it is placed there directly: the arrow then stays perfectly still while the map
     * moves under it (a projected position would wobble against the map's own frames). Only a real change is written:
     * a view that moves makes the whole window draw again.
     */
    private fun placeOverlays() {
        val m = map ?: return
        val c = carPos
        if (c == null) {
            if (carView.visibility != View.GONE) carView.visibility = View.GONE
            return
        }
        val a = applied
        val following = camOnCar && a != null && (camera == Camera.FOLLOW || camera == Camera.NAVIGATE)
        val w = mapView.width.toFloat()
        val h = mapView.height.toFloat()
        val x: Float
        val y: Float
        val bearing: Double
        val tilt: Double
        if (following && a != null && w > 0 && h > 0) {
            x = (a.l + (w - a.l - a.r) / 2).toFloat()
            y = (a.t + (h - a.t - a.b) / 2).toFloat()
            bearing = a.bearing
            tilt = a.tilt
        } else {
            val sp = m.projection.toScreenLocation(LatLng(c.lat, c.lon))
            val cam = m.cameraPosition
            x = sp.x
            y = sp.y
            bearing = cam.bearing
            tilt = cam.tilt
        }
        if (carView.visibility != View.VISIBLE) carView.visibility = View.VISIBLE
        // fixed sizes, not width/height: those are still 0 the first time a hidden overlay is shown
        val tx = x - CarMarker.SIZE.dp / 2f
        val ty = y - CarMarker.SIZE.dp / 2f
        if (abs(carView.translationX - tx) > 0.5f || abs(carView.translationY - ty) > 0.5f) {
            carView.translationX = tx
            carView.translationY = ty
        }
        val r = (((carBearing - bearing) % 360 + 360) % 360).toFloat()
        if (abs(Geo.angleDiff(carView.rotation.toDouble(), r.toDouble())) > 0.5) carView.rotation = r
        // laid on the ground like the map around it (a little less than the map's tilt: flat on the road it would
        // shrink to a sliver)
        val rx = (tilt * 0.85).toFloat()
        if (abs(carView.rotationX - rx) > 0.5f) carView.rotationX = rx
    }

    // ------------------------------------------------------------------------------------------ camera
    fun setCamera(c: Camera) {
        val was = camera
        setMode(c)
        if (c == Camera.NAVIGATE && was != Camera.NAVIGATE) steerReady = false
        if (c == Camera.FOLLOW || c == Camera.NAVIGATE) startBlend()
    }

    private fun setMode(c: Camera) {
        camera = c
        camOnCar = false
        syncLayers()
    }

    /** Navigating, the minor places and house numbers go ([MapStyle.NAV_HIDDEN]); browsing, they come back. */
    private fun syncLayers() {
        val s = style ?: return
        val v = PropertyFactory.visibility(if (camera == Camera.NAVIGATE) Property.NONE else Property.VISIBLE)
        for (id in MapStyle.NAV_HIDDEN) try { s.getLayer(id)?.setProperties(v) } catch (_: Throwable) { }
    }

    /**
     * From the camera as it is now to the one that follows the car: eased by the frame loop while the car goes on
     * moving. From much further than the screen shows (a recenter after panning across town), MapLibre's own flight
     * (out and back in) does the way, and the loop eases the last metres the car drove meanwhile.
     */
    private fun startBlend() {
        val m = map ?: return
        val from = Cam.of(m.cameraPosition) ?: return
        applied = null
        blendFrom = null
        flightUntil = 0L
        if (camera == Camera.NAVIGATE) steer(0.0)
        val to = desired()
        val now = SystemClock.uptimeMillis()
        if (to != null && far(from, to)) {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(to.position()), FLIGHT_MS.toInt())
            flightUntil = now + FLIGHT_MS
        } else {
            blendFrom = from
            blendStart = now
            blendMs = BLEND_MS
        }
        wake()
    }

    private fun far(a: Cam, b: Cam): Boolean {
        val metres = Geo.distance(LatLon(a.lat, a.lon), LatLon(b.lat, b.lon))
        // MapLibre's world is 512 units wide at zoom 0, a unit being a dp
        val perPx = 40075016.686 * cos(Math.toRadians(a.lat)) / (512 * 2.0.pow(min(a.zoom, b.zoom))) / density
        return metres / perPx > 2.5 * max(mapView.width, mapView.height)
    }

    /** The camera that follows the car now, or null when the camera does not follow it (free, overview). */
    private fun desired(): Cam? {
        val p = carPos ?: return null
        val side = startPadding.toDouble()
        val l = if (rtl) 0.0 else side
        val r = if (rtl) side else 0.0
        return when (camera) {
            Camera.FOLLOW -> Cam(p.lat, p.lon, followZoom, 0.0, 0.0, l, 0.0, r, 0.0)
            Camera.NAVIGATE -> Cam(p.lat, p.lon, navZoom, navTilt, camBearing, l, mapView.height * navPad, r, 0.0)
            else -> null
        }
    }

    /**
     * Navigating: eases the zoom (out with speed, back in near the turn ahead), the tilt, the room above the car and
     * the heading towards their targets. A continuous zoom instead of the speed bands of 1.11, which jumped half a
     * zoom level in one frame at 35, 65 and 95 km/h. The heading looks a little down the road and turns at a limited
     * rate: the map swings round a corner smoothly instead of in a step per fix. True while not settled.
     */
    private fun steer(dt: Double): Boolean {
        if (camera != Camera.NAVIGATE || carPos == null) return false
        val v = speedKmh
        var z = zoomFor(v)
        var tilt = NAV_TILT
        val ahead = turnAt - drawnAlong
        if (turnLine != null && onRoute && ahead > -15 && ahead < max(120.0, v / 3.6 * 7)) { z = max(z, 16.9); tilt = NAV_TILT_TURN }
        z = (z + navZoomOffset).coerceIn(12.0, 19.0)
        val pad = navTopPadding * (0.95 + 0.19 * ((v - 40) / 50).coerceIn(0.0, 1.0))
        val b = lookBearing(v)
        if (!steerReady) {
            navZoom = z; navTilt = tilt; navPad = pad; camBearing = b
            steerReady = true
            return false
        }
        navZoom += (z - navZoom) * (1 - exp(-dt / 1.2))
        navTilt += (tilt - navTilt) * (1 - exp(-dt / 0.8))
        navPad += (pad - navPad) * (1 - exp(-dt / 0.8))
        val turn = Geo.angleDiff(camBearing, b)
        if (v >= HOLD_KMH - 1) {
            val most = 75.0 * dt
            camBearing = (camBearing + (turn * (1 - exp(-dt / 0.45))).coerceIn(-most, most) + 360) % 360
        }
        return abs(z - navZoom) > 0.005 || abs(tilt - navTilt) > 0.1 || abs(pad - navPad) > 0.0005 ||
            (v >= HOLD_KMH - 1 && abs(turn) > 0.1)
    }

    /** Street zoom up to 20 km/h, further out with speed, motorway zoom from 110 km/h. */
    private fun zoomFor(kmh: Double): Double = when {
        kmh <= 20 -> 17.0
        kmh <= 50 -> 17.0 - (kmh - 20) / 30 * 0.6
        kmh <= 80 -> 16.4 - (kmh - 50) / 30 * 0.6
        kmh <= 110 -> 15.8 - (kmh - 80) / 30 * 0.6
        else -> 15.2
    }

    /** The way the road goes a little ahead of the car (on the route), else the car's own heading. */
    private fun lookBearing(kmh: Double): Double {
        val line = routeLine
        val c = carPos
        if (onRoute && line != null && c != null) {
            val ahead = line.at(drawnAlong + (kmh / 3.6 * 1.8).coerceIn(25.0, 70.0)).first
            if (Geo.distance(c, ahead) > 2) return Geo.bearing(c, ahead)
        }
        return carBearing
    }

    /** Sets the camera for this frame (at most once); true while a change of camera is still under way. */
    private fun driveCamera(now: Long): Boolean {
        val m = map ?: return false
        val d = desired() ?: return false
        if (flightUntil != 0L) {
            if (now < flightUntil) return true
            flightUntil = 0L
            blendFrom = Cam.of(m.cameraPosition)
            blendStart = now
            blendMs = LANDING_MS
        }
        val from = blendFrom
        val out = if (from == null) d else {
            val t = ((now - blendStart).toDouble() / blendMs).coerceIn(0.0, 1.0)
            if (t >= 1.0) { blendFrom = null; d } else from.lerp(d, Motion.STANDARD.getInterpolation(t.toFloat()).toDouble())
        }
        if (applied?.near(out) != true) {
            inDrive = true
            try { m.moveCamera(CameraUpdateFactory.newCameraPosition(out.position())) } finally { inDrive = false }
            applied = out
        }
        camOnCar = blendFrom == null
        cameraOnCar = true
        return blendFrom != null
    }

    /** Shows the whole route (or the car and a place) with room for the panels. */
    fun overview(points: List<LatLon>, padStart: Int, padTop: Int, padEnd: Int, padBottom: Int) {
        val m = map ?: return
        if (points.isEmpty()) return
        blendFrom = null
        flightUntil = 0L
        setMode(Camera.OVERVIEW)
        val l = if (rtl) padEnd else padStart
        val r = if (rtl) padStart else padEnd
        if (points.size == 1) {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                .target(LatLng(points[0].lat, points[0].lon)).zoom(15.5).bearing(0.0).tilt(0.0)
                .padding(l.toDouble(), padTop.toDouble(), r.toDouble(), padBottom.toDouble()).build()), OVERVIEW_MS)
            return
        }
        val b = LatLngBounds.Builder()
        points.forEach { b.include(LatLng(it.lat, it.lon)) }
        try {
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 0.0, 0.0, l, padTop, r, padBottom), OVERVIEW_MS)
        } catch (_: Throwable) {
        }
    }

    /** + / − buttons: following the car, the zoom is remembered (the next fix would otherwise undo it) and eased to. */
    fun zoomBy(d: Double) {
        val m = map ?: return
        when (camera) {
            Camera.FOLLOW -> { followZoom = (followZoom + d).coerceIn(12.0, 18.5); startBlend() }
            Camera.NAVIGATE -> {
                navZoomOffset = (navZoomOffset + d).coerceIn(-3.0, 2.0)
                navZoom = (navZoom + d).coerceIn(12.0, 19.0)
                startBlend()
            }
            else -> m.animateCamera(CameraUpdateFactory.zoomBy(d), 300)
        }
    }

    fun northUp() {
        map?.animateCamera(CameraUpdateFactory.bearingTo(0.0), 400)
    }

    // ------------------------------------------------------------------------------------------ the frame loop
    private fun wake() {
        view.removeCallbacks(keepPicture)
        if (looping || !started) return
        looping = true
        lastDriveNs = 0L
        measureFrom = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun sleep() {
        if (!looping) return
        looping = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    private fun onFrame(ns: Long) {
        if (!looping) return
        // (no more often than the map draws: setting the camera between two of its frames is work nobody sees)
        if (lastDriveNs != 0L && ns - lastDriveNs < 1_000_000_000L / fpsCap - 4_000_000L) {
            Choreographer.getInstance().postFrameCallback(frame)
            return
        }
        val dt = if (lastDriveNs == 0L) 1.0 / fpsCap else ((ns - lastDriveNs) / 1e9).coerceIn(0.0, 0.05)
        lastDriveNs = ns
        val now = ns / 1_000_000L // (Choreographer's clock is uptime's)
        val moving = movePuck(now)
        val steering = steer(dt)
        val changing = driveCamera(now)
        trimRoute(false)
        placeOverlays()
        rescaleTurn()
        measureFps(now)
        if (moving || steering || changing) Choreographer.getInstance().postFrameCallback(frame)
        else {
            looping = false
            if (pictureFile != null && drawn && pictureWorthy() && now - pictureAt > PICTURE_EVERY_MS) view.postDelayed(keepPicture, PICTURE_REST_MS)
        }
    }

    private fun measureFps(now: Long) {
        if (fpsCap <= 30 || camera != Camera.NAVIGATE) return
        if (measureFrom == 0L) { measureFrom = now; renders = 0; return }
        val span = now - measureFrom
        if (span < 3000) return
        val rate = renders * 1000.0 / span
        if (rate < fpsCap * 0.7) {
            Log.i(TAG, "the map drew ${"%.0f".format(Locale.ROOT, rate)} of $fpsCap fps: 30 from now on")
            fpsCap = 30
            mapView.setMaximumFps(30)
            mapView.removeOnDidFinishRenderingFrameListener(counter)
        }
        measureFrom = now
        renders = 0
    }

    /** A picture of the map as it is now, kept for the next start ([pictureFile]); taken when the map has come to rest. */
    private fun takePicture() {
        val f = pictureFile ?: return
        val m = map ?: return
        if (!started || !drawn || !pictureWorthy()) return
        pictureAt = SystemClock.uptimeMillis()
        try {
            m.snapshot { bmp ->
                PICTURES.execute {
                    try {
                        val tmp = File(f.path + ".tmp")
                        @Suppress("DEPRECATION")
                        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP, 80, it) }
                        tmp.renameTo(f)
                    } catch (t: Throwable) {
                        Log.w(TAG, "picture not kept: $t")
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Only the view the next start opens on is worth keeping: following the car, north up and flat, with no trip on it.
     * A picture taken at a red light while navigating (tilted, heading up, the route and its arrow) cross-faded into
     * that view as a double exposure, with a route that was no longer there.
     */
    private fun pictureWorthy() = camera == Camera.FOLLOW && routeLine == null && routePts == null && dest == null

    /** A camera: target, zoom, tilt, bearing and the four paddings (left, top, right, bottom, in pixels). */
    private class Cam(val lat: Double, val lon: Double, val zoom: Double, val tilt: Double, val bearing: Double,
                      val l: Double, val t: Double, val r: Double, val b: Double) {
        fun position(): CameraPosition = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).tilt(tilt)
            .bearing(bearing).padding(l, t, r, b).build()

        fun lerp(o: Cam, e: Double) = Cam(lat + (o.lat - lat) * e, lon + (o.lon - lon) * e, zoom + (o.zoom - zoom) * e,
            tilt + (o.tilt - tilt) * e, (bearing + Geo.angleDiff(bearing, o.bearing) * e + 360) % 360,
            l + (o.l - l) * e, t + (o.t - t) * e, r + (o.r - r) * e, b + (o.b - b) * e)

        /** Too close to be worth a camera update (a centimetre, a thousandth of a zoom level). */
        fun near(o: Cam) = abs(lat - o.lat) < 1e-7 && abs(lon - o.lon) < 1e-7 && abs(zoom - o.zoom) < 1e-3 &&
            abs(tilt - o.tilt) < 0.05 && abs(Geo.angleDiff(bearing, o.bearing)) < 0.05 &&
            abs(l - o.l) < 0.5 && abs(t - o.t) < 0.5 && abs(r - o.r) < 0.5 && abs(b - o.b) < 0.5

        companion object {
            fun of(p: CameraPosition): Cam? {
                val target = p.target ?: return null
                val pad = p.padding
                fun side(i: Int) = if (pad != null && pad.size >= 4) pad[i] else 0.0
                return Cam(target.latitude, target.longitude, p.zoom, p.tilt, p.bearing, side(0), side(1), side(2), side(3))
            }
        }
    }

    companion object {
        private const val TAG = "AuraMap"
        private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
        // the car on the route
        private const val ON_ROUTE_M = 3.0   // a fix this close to the driven line is on it (navigation snaps them)
        private const val JUMP_M = 400.0     // further than this from where the car is drawn: put there, no glide
        private const val BACK_M = 25.0      // drawn this far ahead of a fix: glide back
        private const val LEAD_MAX_M = 45.0  // never drawn further ahead of the last fix than this per interval
        private const val COAST_S = 8.0      // at speed, carry on this long without fixes (a tunnel)
        private const val HOLD_KMH = 6.0     // slower: the heading is kept, the car is not moved ahead
        private const val STALE_MS = 5000L   // no fix for this long: the car is shown as not current
        // the camera
        private const val BLEND_MS = 650L
        private const val LANDING_MS = 400L
        private const val FLIGHT_MS = 900L
        private const val OVERVIEW_MS = 750
        private const val NAV_TILT = 50.0
        private const val NAV_TILT_TURN = 44.0
        private const val DRAWN_LATE_MS = 2500L
        private const val PICTURE_REST_MS = 3000L
        private const val PICTURE_EVERY_MS = 60_000L
        private val PICTURES = Executors.newSingleThreadExecutor()
        // the arrow of the next turn, on the ground at street zoom (longer further out: [turnK])
        private const val TURN_ZOOM = 16.8
        private const val TURN_BEFORE_M = 45.0
        /** How far past the turn its arrow runs at street zoom, unless [setTurn] asks for more (a roundabout). */
        const val TURN_AFTER_M = 32.0
        private const val HEAD_LENGTH_M = 15.0
        private const val HEAD_HALF_M = 9.5
        // the driven route: its short part reaches this far ahead of the car, and is made again when less is left
        private const val NEAR_M = 1200.0
        private const val NEAR_MIN_M = 350.0
        private const val TRIM_EVERY_MS = 70L
        private const val OFF_ROUTE_M = 60.0
    }
}

/**
 * The car, in the route's colour. Browsing it is a disc with a heading arrow, a white rim and a soft halo; while a
 * route is driven it becomes a chevron with a white edge and a contact shadow, laid on the tilted road by the map
 * ([AuraMap] tilts the view), the way navigation shows "you". With no fix for a few seconds it greys and fades a
 * little: a position that is no longer current must not look like a live one.
 */
private class CarMarker(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3f.dp
        color = 0xFFFFFFFF.toInt()
    }
    private val path = Path()
    private val oval = RectF()
    private val color = MapStyle.routeColor()
    private var navK = 0f
    private var staleK = 0f
    private val navSpring = Spring(0f, 520f, 1f) { navK = it; invalidate() }
    // (the fade is in the paints, not the view's alpha: the marker's shapes overlap, and a view alpha below 1 would
    // send every frame of it through an offscreen layer for as long as the fixes stay away)
    private val staleSpring = Spring(0f, 300f, 1f) { staleK = it; invalidate() }

    init {
        // (a far camera: the view is tilted by up to 50°, and a near one would stretch its nearer edge)
        cameraDistance = 8000 * resources.displayMetrics.density
    }

    fun setNav(on: Boolean) = navSpring.animateTo(if (on) 1f else 0f)

    fun setStale(on: Boolean) {
        if (staleSpring.target != (if (on) 1f else 0f)) staleSpring.animateTo(if (on) 1f else 0f)
    }

    override fun onDetachedFromWindow() {
        navSpring.snapTo(navSpring.target)
        staleSpring.snapTo(staleSpring.target)
        super.onDetachedFromWindow()
    }

    private fun paint(c: Int, a: Float): Paint {
        p.color = c
        p.alpha = (Color.alpha(c) * a * (1f - 0.4f * staleK)).toInt()
        return p
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val fill = Palette.mix(color, Palette.text3, staleK)
        val disc = 1f - navK
        if (disc > 0.01f) {
            c.save()
            val s = 1f - 0.2f * navK
            c.scale(s, s, cx, cy)
            val r = 16f.dp
            c.drawCircle(cx, cy, 30f.dp, paint(Palette.withAlpha(fill, 0.16f), disc))
            c.drawCircle(cx, cy + 1.5f.dp, r + 0.5f.dp, paint(0x40000000, disc))
            c.drawCircle(cx, cy, r, paint(0xFFFFFFFF.toInt(), disc))
            c.drawCircle(cx, cy, r - 3f.dp, paint(fill, disc))
            path.reset()
            path.moveTo(cx, cy - 8.5f.dp)
            path.lineTo(cx + 6.5f.dp, cy + 7f.dp)
            path.lineTo(cx, cy + 3.5f.dp)
            path.lineTo(cx - 6.5f.dp, cy + 7f.dp)
            path.close()
            c.drawPath(path, paint(0xFFFFFFFF.toInt(), disc))
            c.restore()
        }
        if (navK > 0.01f) {
            c.save()
            val s = 0.8f + 0.2f * navK
            c.scale(s, s, cx, cy)
            oval.set(cx - 13f.dp, cy + 9f.dp, cx + 13f.dp, cy + 19f.dp)
            c.drawOval(oval, paint(0x33000000, navK))
            path.reset()
            path.moveTo(cx, cy - 19f.dp)
            path.lineTo(cx + 14f.dp, cy + 15f.dp)
            path.lineTo(cx, cy + 8f.dp)
            path.lineTo(cx - 14f.dp, cy + 15f.dp)
            path.close()
            c.drawPath(path, paint(fill, navK))
            edge.alpha = (255 * navK * (1f - 0.4f * staleK)).toInt()
            c.drawPath(path, edge)
            c.restore()
        }
    }

    companion object { const val SIZE = 64 }
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
