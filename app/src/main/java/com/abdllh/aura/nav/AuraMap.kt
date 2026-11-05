package com.abdllh.aura.nav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * dragged it. One of these in the one window ([MapController], the only one that talks to it), under every panel, on a
 * SurfaceView: it is never moved, resized or hidden. What differs between the screens is [setRoom], what part of it shows.
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
    /** The same tap, just before it, on a place of the pack: its row in places.db (table p), for a card that wants more. */
    var onPlaceRow: ((id: Long) -> Unit)? = null
    /** The map has come to rest after a finger or a flight, not while the frame loop drives it: the place at its middle. */
    var onCameraIdle: ((LatLon) -> Unit)? = null
    /** A tap on a search row's pin: which row, from 0 ([setResults]). */
    var onResultTap: ((Int) -> Unit)? = null
    /** A tap on the second route ([setRouteAlt]). */
    var onRouteAltTap: (() -> Unit)? = null
    /** A tap on nothing of the above and no place: the map itself. */
    var onMapTap: (() -> Unit)? = null
    /**
     * Fraction of the view height kept free above the car while navigating (puts the car low on the screen); a little
     * more at motorway speed, where what is far ahead matters more.
     */
    var navTopPadding = 0.42
    // [setRoom]
    private var roomL = 0.0
    private var roomT = 0.0
    private var roomR = 0.0
    private var roomB = 0.0
    /**
     * Where a picture of this map is kept (the home map): taken when the map has come to rest, shown by
     * [com.abdllh.aura.ui.MapBackdropView] at the next start until the map has drawn, so the screen opens on the real
     * streets instead of invented ones.
     */
    var pictureFile: File? = null
    /** The part of the view that picture is of (it is the home screen's card, not the whole map); null: all of it. */
    var pictureArea: Rect? = null

    private val rtl: Boolean
    private val app = ctx.applicationContext
    private val density = ctx.resources.displayMetrics.density

    // ---- the car as drawn
    private var carPos: LatLon? = null
    private var carBearing = 0.0
    private var speedKmh = 0.0
    private var lastFix: LatLon? = null
    private var lastFixAt = 0L
    // fixes arrive about once a second on the emulator, one in 2 s on the owner's unit (its Screen check, 1.19.1): the car glides
    // over that interval, so it never stops in between
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
    private var altPts: List<LatLon>? = null
    private var resultPts: List<LatLon> = emptyList()
    private var destName: String? = null
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
    private var turnFrom = 0.0  // where along its line the arrow as drawn starts, and ends
    private var turnEnd = 0.0
    private var dest: LatLon? = null
    private var routeFaded = false
    private var extrasVersion = -1

    // ---- the camera
    // zoom kept by the map itself while following (the camera is set on every frame the car moves)
    private var followZoom = FOLLOW_ZOOM
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
    private var blendSpring = false  // on [springK], not [Motion.STANDARD]
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
    /** Covered by an opaque panel ([setFrozen]). */
    private var frozen = false
    private var gestures = interactive
    private var fpsWanted = 30
    private var fpsCap = 30
    private var renders = 0
    private var measureFrom = 0L
    private val counter = MapView.OnDidFinishRenderingFrameListener { renders++ }

    private var drawn = false
    private var pictureAt = 0L
    private val stale = Runnable { carView.setStale(true) }
    private val keepPicture = Runnable { takePicture() }

    /**
     * Re-armed at every start: whatever covers the map until it has drawn (the home screen's backdrop) can go then.
     * [skip]: frames that do not count (a new style: the frame already on its way is of the one before, and fully drawn).
     */
    private inner class Drawn(private var skip: Int = 0) : MapView.OnDidFinishRenderingFrameListener {
        private var first = false
        private var done = false
        private val late = Runnable { finish() }
        override fun onDidFinishRenderingFrame(fully: Boolean) {
            if (done) return
            if (!first) {
                first = true
                if (guarded) { MapGuard.ok(app); MapGuard.live(app) }
                view.postDelayed(late, DRAWN_LATE_MS)
            }
            if (skip > 0) { skip--; return }
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
        Log.i(TAG, "engine #${++engines} in this process (${if (texture) "texture" else "surface"})")
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
        // A new size (a panel or the keyboard coming and going) moves every point on the screen without any camera
        // event: the car is placed again once the map has drawn at the new size, or it stays where the old size had it.
        mapView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) { mapView.post(placeAgain); mapView.postDelayed(placeAgain, 120) }
        }
        rtl = ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                setAllGesturesEnabled(gestures)
                setRotateGesturesEnabled(gestures)
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
            m.addOnCameraIdleListener { if (!inDrive) { placeOverlays(); rescaleTurn(); m.cameraPosition.target?.let { t -> onCameraIdle?.invoke(LatLon(t.latitude, t.longitude)) } } }
            if (interactive) m.addOnMapLongClickListener { ll ->
                val l = onLongPress ?: return@addOnMapLongClickListener false
                l(LatLon(ll.latitude, ll.longitude))
                true
            }
            if (interactive) m.addOnMapClickListener { ll -> tapPlace(m, ll) }
            val start = CarLocation.lastKnown() ?: LatLon(24.7136, 46.6753) // Riyadh until the first fix
            m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(start.lat, start.lon)).zoom(followZoom).build()))
            live.add(this)
            loadStyle()
            placeRoom(m)
        }
    }

    private fun loadStyle() {
        val m = map ?: return
        val arabic = Locale.getDefault().language == "ar"
        Log.i(TAG, "style load #${++styleLoads}")
        m.setStyle(Style.Builder().fromJson(MapStyle.json(arabic)).withImage(MapStyle.POI_DOT, MapStyle.poiDot(), true).withImage(MapStyle.DEST_PIN, MapStyle.destPin(), true)) { s ->
            style = s
            MapStyle.afterLoad(s)
            extrasVersion = -1
            routeFaded = false
            pushRoute()
            pushLine(MapStyle.SRC_ROUTE_ALT, altPts)
            pushResults()
            pushTurn()
            pushDest()
            pushExtras()
            syncLayers()
            placeOverlays()
            onReady?.invoke()
        }
    }

    /**
     * The look changed (sunset, a new accent): the map stays, the same surface and engine, with the style of now; everything
     * on it goes back on it as the style comes (the route, the turn, the destination, the additions). [onDrawn] says when it
     * has drawn again.
     */
    fun reloadStyle() {
        carView.refreshLook()
        style = null
        if (started) {
            firstFrame.cancel()
            mapView.removeOnDidFinishRenderingFrameListener(firstFrame)
            firstFrame = Drawn(1).also { mapView.addOnDidFinishRenderingFrameListener(it) }
        }
        drawn = false
        loadStyle()
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
        val at = m.projection.toScreenLocation(ll)
        val r = 26f.dp // (a finger on a car screen, not a pixel)
        val box = RectF(at.x - r, at.y - r, at.x + r, at.y + r)
        fun under(layers: Array<String>) = try { m.queryRenderedFeatures(box, *layers) } catch (_: Throwable) { emptyList<Feature>() }
        // (what a card put on the map first: the row's pin, the second route. A pin's own number says which)
        if (resultPts.isNotEmpty()) onResultTap?.let { tap ->
            under(MapStyle.RESULT_LAYERS).firstOrNull()?.getStringProperty("n")?.toIntOrNull()?.let { n -> tap(n - 1); return true }
        }
        if (altPts != null) onRouteAltTap?.let { tap -> if (under(MapStyle.ROUTE_ALT_LAYERS).isNotEmpty()) { tap(); return true } }
        val l = onPoiTap
        if (l == null) { onMapTap?.invoke(); return false }
        val hits = under(MapStyle.POI_LAYERS)
        val arabic = Locale.getDefault().language == "ar"
        fun text(f: Feature, key: String): String? =
            if (f.hasNonNullValueForProperty(key)) f.getStringProperty(key)?.takeIf { it.isNotBlank() } else null
        var best: Feature? = null
        var bestPos: LatLon? = null
        var bestD = Double.MAX_VALUE
        for (f in hits) {
            val g = f.geometry() as? Point ?: continue
            // (a dot with no word: a name of 3 letters or less, tools/mapbuild/build_poi_tiles.py. Nothing to say of it: the nearest place that has a name)
            if (text(f, "name") == null && text(f, "name:en") == null) continue
            val pos = LatLon(g.latitude(), g.longitude())
            val sp = m.projection.toScreenLocation(LatLng(pos.lat, pos.lon))
            val d = Math.hypot((sp.x - at.x).toDouble(), (sp.y - at.y).toDouble())
            if (d < bestD) { bestD = d; best = f; bestPos = pos }
        }
        val f = best
        if (f == null) { onMapTap?.invoke(); return false }
        val name = (if (arabic) text(f, "name:ar") else text(f, "name:en") ?: text(f, "name:latin")) ?: text(f, "name") ?: text(f, "name:en") ?: return false
        // (a place of the pack says what it is by number, and which row it is)
        fun number(key: String) = if (f.hasNonNullValueForProperty(key)) f.getNumberProperty(key) else null
        val at2 = bestPos ?: return false
        number("id")?.let { onPlaceRow?.invoke(it.toLong()) }
        l(name, number("kind")?.let { Poi.packKind(it.toInt()) } ?: text(f, "class").orEmpty(), at2)
        return true
    }

    // ------------------------------------------------------------------------------------------ lifecycle
    fun onCreate(b: Bundle?) = mapView.onCreate(b)

    fun onStart() {
        mapView.onStart()
        started = true
        firstFrame = Drawn().also { mapView.addOnDidFinishRenderingFrameListener(it) }
        com.abdllh.aura.diag.TripRecorder.mapOn(mapView) // (nothing unless the trip recorder is on)
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
        if (guarded) { MapGuard.ok(app); MapGuard.notLive(app) } // still alive: the engine did not crash
        started = false
        sleep()
        view.removeCallbacks(stale)
        view.removeCallbacks(keepPicture)
        firstFrame.cancel()
        MapExtras.removeListener(extrasChanged)
        mapView.removeOnDidFinishRenderingFrameListener(firstFrame)
        com.abdllh.aura.diag.TripRecorder.mapOff(mapView)
        mapView.onStop()
    }

    /**
     * Frame-rate cap: 30 is plenty while browsing; navigation asks for 60 so the turning map stays fluid. A map that
     * cannot keep up with 60 (fewer than 70 % of the frames drawn over a few seconds of driving) drops to an even 30
     * for the rest of the trip ([fellBehind]): frames alternating between one and two vsyncs look worse than a steady 30.
     */
    fun setFps(fps: Int) {
        if (fps == fpsWanted) return
        fpsWanted = fps
        fpsCap = fps
        mapView.setMaximumFps(fps)
        measureFrom = 0L
        if (fps > 30) mapView.addOnDidFinishRenderingFrameListener(counter) else mapView.removeOnDidFinishRenderingFrameListener(counter)
    }

    /**
     * Touches move the map, or nothing of it does (it is the one map: the Maps panel wants the finger, the home screen wants
     * a tap on its card to open Maps). What a press and a tap do needs no switch: [onLongPress] and [onPoiTap] are null then.
     */
    fun setInteractive(on: Boolean) {
        if (on == gestures) return
        gestures = on
        map?.uiSettings?.apply {
            setAllGesturesEnabled(on)
            setRotateGesturesEnabled(on)
            setTiltGesturesEnabled(false)
        }
    }

    /**
     * What part of the view shows, in pixels from each side (the rest lies under the panels): the camera keeps the car in
     * the middle of that part, and this is the only thing that differs between the screens. The view itself never moves or
     * resizes; a new room eases, as a pan, with no tile touched when the zoom stays. [navTop]: the fraction of that part kept
     * free above the car while navigating ([navTopPadding]).
     */
    fun setRoom(l: Int, t: Int, r: Int, b: Int, navTop: Double) {
        navTopPadding = navTop
        if (l.toDouble() == roomL && t.toDouble() == roomT && r.toDouble() == roomR && b.toDouble() == roomB) return
        roomL = l.toDouble()
        roomT = t.toDouble()
        roomR = r.toDouble()
        roomB = b.toDouble()
        // (not yet ready: the first camera takes it. One the finger or an overview holds stays where it is, until it follows again)
        if (map != null && (camera == Camera.FOLLOW || camera == Camera.NAVIGATE)) startBlend(ROOM_MS, true)
    }

    /** The first camera, with the room of now. */
    private fun placeRoom(m: MapboxMap) {
        val c = m.cameraPosition ?: return
        try {
            m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(c).padding(roomL, roomT, roomR, roomB).build()))
        } catch (t: Throwable) {
            Log.w(TAG, "room: $t")
        }
    }

    /**
     * An opaque panel lies over the whole map, or no longer: LIVE and FROZEN (home-shell.md 5.3). Frozen, nothing sets the
     * camera, so the map draws nothing (it draws when dirty); its surface and GL context stay, and fixes go on being taken in.
     * Uncovered, the camera is where the car is, at once: it comes out from under a fade, and a flight would only show the
     * time the panel was up.
     */
    fun setFrozen(on: Boolean) {
        if (on == frozen) return
        frozen = on
        if (on) {
            sleep()
            view.removeCallbacks(keepPicture)
            return
        }
        blendFrom = null
        flightUntil = 0L
        applied = null
        wake()
    }

    /** The zoom a map that follows the car starts with, and no offset on the navigating one: what the home screen shows. */
    fun zoomBack() {
        followZoom = FOLLOW_ZOOM
        navZoomOffset = 0.0
    }

    /** It could not keep up with 60 on this trip ([setFps]): nobody asks it again until the trip has ended ([MapController]). */
    var fellBehind = false

    fun onLowMemory() = mapView.onLowMemory()
    fun onDestroy() {
        live.remove(this)
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
        val jump = !onRoute || carPos == null || frozen || abs(along - drawnAlong) > JUMP_M
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
        if (from == null || frozen || Geo.distance(from, p) > JUMP_M) {
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
        glideMs = fixInterval.toLong().coerceIn(500L, 2600L) // (it was 1600, and at the unit's 2 s the car stood still 400 ms in every fix)
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
        trimAlong = 0.0 // (the old line's: the turn's arrow is cut there until this one is trimmed)
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
        // the turn's arrow, cut at the car while the car is on it ([pushTurn])
        if (turnLine === line && along > turnFrom + TURN_CUT_STEP_M && along < turnEnd) pushTurn()
    }

    /** The place's pin, and its [name] above it. */
    fun setDestination(p: LatLon?, name: String? = null) {
        if (p == dest && name == destName) return
        dest = p
        destName = name
        pushDest()
    }

    private fun pushDest() {
        val s = style ?: return
        val d = dest
        val json = if (d == null) EMPTY else org.json.JSONObject().put("type", "Feature")
            .put("properties", org.json.JSONObject().put("name", destName.orEmpty()))
            .put("geometry", org.json.JSONObject().put("type", "Point").put("coordinates", org.json.JSONArray().put(d.lon).put(d.lat))).toString()
        try { s.getSourceAs<GeoJsonSource>(MapStyle.SRC_DEST)?.setGeoJson(json) } catch (_: Throwable) { }
    }

    /** The second route a card offers, under the chosen one; null: none. */
    fun setRouteAlt(points: List<LatLon>?) {
        if (points == null && altPts == null) return
        altPts = points
        pushLine(MapStyle.SRC_ROUTE_ALT, points)
    }

    /** The search's rows on the map, each with its number. */
    fun setResults(points: List<LatLon>) {
        if (points == resultPts) return
        resultPts = points
        pushResults()
    }

    private fun pushResults() {
        val s = style ?: return
        val json = buildString {
            append("""{"type":"FeatureCollection","features":[""")
            // (the first row last: it lies on top where two pins meet)
            for (i in resultPts.indices.reversed()) {
                val p = resultPts[i]
                if (i != resultPts.lastIndex) append(',')
                append("""{"type":"Feature","properties":{"n":"${i + 1}"},"geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]}}""")
            }
            append("]}")
        }
        try { s.getSourceAs<GeoJsonSource>(MapStyle.SRC_RESULTS)?.setGeoJson(json) } catch (_: Throwable) { }
    }

    /** Where the camera is now, for [cameraBack]: a card that flew the map remembers what it had. */
    fun cameraNow(): CameraPosition? = map?.cameraPosition

    /** Back to what [cameraNow] said (the map's own flight), when the camera was the driver's; following the car it just follows it again. */
    fun cameraBack(was: CameraPosition?, wasMode: Camera) {
        if (wasMode == Camera.FOLLOW || wasMode == Camera.NAVIGATE || was == null) { setCamera(wasMode); return }
        blendFrom = null
        flightUntil = 0L
        setMode(wasMode)
        map?.animateCamera(CameraUpdateFactory.newCameraPosition(was), OVERVIEW_MS, landed)
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
            // Never from behind the car: on the driven route it starts where the car is once the car is on it (it
            // ran back through the car to a tail behind it, the way into a roundabout drawn as part of the way out),
            // and [trimRoute] moves its start along with the car.
            var from = turnAt - TURN_BEFORE_M * k
            if (line === routeLine) from = maxOf(from, trimAlong).coerceAtMost(end - 1.0)
            turnFrom = from
            turnEnd = end
            val pts = line.slice(from, end)
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
        carView.pose((((carBearing - bearing) % 360 + 360) % 360).toFloat(), tilt.toFloat())
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
    private fun startBlend(ms: Long = BLEND_MS, spring: Boolean = false) {
        val m = map ?: return
        applied = null
        blendFrom = null
        flightUntil = 0L
        if (frozen) return // ([setFrozen]: where the car is, when it is seen again)
        val from = Cam.of(m.cameraPosition) ?: return
        if (camera == Camera.NAVIGATE) steer(0.0)
        val to = desired()
        val now = SystemClock.uptimeMillis()
        if (to != null && far(from, to)) {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(to.position()), FLIGHT_MS.toInt())
            flightUntil = now + FLIGHT_MS
        } else {
            blendFrom = from
            blendStart = now
            blendMs = ms
            blendSpring = spring
        }
        wake()
    }

    private fun far(a: Cam, b: Cam): Boolean {
        val metres = Geo.distance(LatLon(a.lat, a.lon), LatLon(b.lat, b.lon))
        // MapLibre's world is 512 units wide at zoom 0, a unit being a dp
        val perPx = 40075016.686 * cos(Math.toRadians(a.lat)) / (512 * 2.0.pow(min(a.zoom, b.zoom))) / density
        return metres / perPx > 2.5 * max(mapView.width, mapView.height)
    }

    /**
     * The camera that follows the car now, or null when the camera does not follow it (free, overview). With no fix ever,
     * the same around where the map looks: the room counts there too.
     */
    private fun desired(): Cam? {
        val p = carPos ?: map?.cameraPosition?.target?.let { LatLon(it.latitude, it.longitude) } ?: return null
        return when (camera) {
            Camera.FOLLOW -> Cam(p.lat, p.lon, followZoom, 0.0, 0.0, roomL, roomT, roomR, roomB)
            Camera.NAVIGATE -> Cam(p.lat, p.lon, navZoom, navTilt, camBearing, roomL, roomT + (mapView.height - roomT - roomB).coerceAtLeast(0.0) * navPad, roomR, roomB)
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
            blendSpring = false
        }
        val from = blendFrom
        val out = if (from == null) d else {
            val t = ((now - blendStart).toDouble() / blendMs).coerceIn(0.0, 1.0)
            if (t >= 1.0) { blendFrom = null; d }
            else from.lerp(d, if (blendSpring) springK((now - blendStart) / 1000.0) else Motion.STANDARD.getInterpolation(t.toFloat()).toDouble())
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

    /** A move that leaves at once and comes to rest softly, with no end of its own (the design's camera between screens). */
    private fun springK(s: Double): Double {
        val x = ROOM_SPRING * s
        return 1 - (1 + x) * exp(-x)
    }

    /**
     * Shows the whole route (or the car and a place) with room for the panels, which do not swap sides with the language: [padLeft] is the
     * left. North up, tilt 0, no closer than [maxZoom] (map-*.json's `overview` and `search`).
     */
    fun overview(points: List<LatLon>, padLeft: Int, padTop: Int, padRight: Int, padBottom: Int, maxZoom: Double = 16.0) {
        val m = map ?: return
        if (points.isEmpty()) return
        blendFrom = null
        flightUntil = 0L
        setMode(Camera.OVERVIEW)
        val l = padLeft
        val r = padRight
        if (points.size == 1) {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                .target(LatLng(points[0].lat, points[0].lon)).zoom(minOf(15.5, maxZoom)).bearing(0.0).tilt(0.0)
                .padding(l.toDouble(), padTop.toDouble(), r.toDouble(), padBottom.toDouble()).build()), OVERVIEW_MS, landed)
            return
        }
        val b = LatLngBounds.Builder()
        points.forEach { b.include(LatLng(it.lat, it.lon)) }
        try {
            val bounds = b.build()
            val fits = m.getCameraForLatLngBounds(bounds, intArrayOf(l, padTop, r, padBottom), 0.0, 0.0)
            // (a place round the corner: not so close that the map loses where it is)
            if (fits != null && fits.zoom > maxZoom) {
                m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(bounds.center).zoom(maxZoom).bearing(0.0).tilt(0.0)
                    .padding(l.toDouble(), padTop.toDouble(), r.toDouble(), padBottom.toDouble()).build()), OVERVIEW_MS, landed)
            } else {
                m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0.0, 0.0, l, padTop, r, padBottom), OVERVIEW_MS, landed)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * The car placed again where an overview flight ended. A flight that replaces another one (the car and the place
     * first, then the whole route once it has come) did not always end with a camera-idle event, and the car stayed
     * where the first flight had left it, beside its road, until the next zoom.
     */
    private val landed = object : MapboxMap.CancelableCallback {
        override fun onCancel() {}
        override fun onFinish() { mapView.post(placeAgain) }
    }

    private val placeAgain = Runnable { if (!inDrive) placeOverlays() }

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
        if (looping || !started || frozen) return
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
            fellBehind = true
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
        val area = pictureArea?.let { Rect(it) }
        try {
            m.snapshot { bmp ->
                PICTURES.execute {
                    try {
                        val part = if (area != null && area.left >= 0 && area.top >= 0 && area.right <= bmp.width && area.bottom <= bmp.height)
                            Bitmap.createBitmap(bmp, area.left, area.top, area.width(), area.height()) else bmp
                        val tmp = File(f.path + ".tmp")
                        @Suppress("DEPRECATION")
                        tmp.outputStream().use { part.compress(Bitmap.CompressFormat.WEBP, 80, it) }
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
        /** The maps that have a style (the home screen's, the Maps screen's). Main thread. */
        private val live = java.util.Collections.newSetFromMap(java.util.WeakHashMap<AuraMap, Boolean>())

        /** The pack in use changed: each map builds its style anew. Main thread. */
        fun reloadStyles() { for (m in live.toList()) m.loadStyle() }

        // (the log's proof that nothing is built or loaded twice in a process's life)
        private var engines = 0
        private var styleLoads = 0
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
        /** A new room: 95 % at 365 ms, settled by here (the design's section 9). */
        private const val ROOM_MS = 520L
        private const val ROOM_SPRING = 13.0
        private const val FOLLOW_ZOOM = 14.6 // (Home's and browse's, so Home to Maps is a pure pan: map-*.json's camera)
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
        private const val TURN_CUT_STEP_M = 3.0 // the arrow's start follows the car in steps this long (under the car)
        /** How far past the turn its arrow runs at street zoom, unless [setTurn] asks for more (a roundabout). */
        const val TURN_AFTER_M = 32.0

        /**
         * The `after` to give [setTurn] for [step]: through a roundabout the arrow runs on to the exit taken (its
         * measured [Step.span], plus the head's length), so its head shows the way out rather than the ring. The
         * Maps screen and the home map ask the same, so both draw the same arrow.
         */
        fun turnAfter(step: Step): Double =
            if (step.span > 0) (step.span + 15).coerceIn(TURN_AFTER_M, 125.0) else TURN_AFTER_M
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
 * The owner's own car on the map (1.14), the model the home screen shows, pre-rendered the way the map sees it
 * (`tools/car3d/pack_map.py`, assets/carmap): 72 views 5° apart from 38° above the road (the navigating map's 52° tilt)
 * and one from straight above. Tilted, the view that matches the car's heading against the map's bearing is drawn,
 * blended with its neighbour so it turns smoothly; flat, the view from above is turned to the heading; in between the
 * two cross-fade. The frames' centre is the point under the car's middle, so the car stands exactly where it is.
 * Browsing, a soft halo in the route's colour lies under it so it is found at a glance. With no fix for a few seconds
 * it greys and fades a little: a position that is no longer current must not look like a live one.
 * Until the frames are loaded (a moment after the first map starts), and should they ever fail, the 1.13 marker is
 * drawn instead: a disc with an arrow browsing, a chevron while a route is driven.
 */
private object CarSprites {
    /** The tilted views (index k: the car at yaw 5k°: 0 points right on the screen, 90 up, counter-clockwise). */
    @Volatile var tilt: Array<Bitmap>? = null
    @Volatile var top: Bitmap? = null
    private var loading = false
    private val waiting = ArrayList<View>()

    /** Starts loading once; [v] is redrawn when the frames are there. Main thread. */
    fun want(c: Context, v: View) {
        if (tilt != null) return
        waiting.add(v)
        if (loading) return
        loading = true
        val app = c.applicationContext
        Thread({
            try {
                val am = app.assets
                fun read(n: String) = am.open("carmap/$n.webp").use { BitmapFactory.decodeStream(it) }
                val frames = Array(72) { k -> read("t_%02d".format(java.util.Locale.ROOT, k)) }
                top = read("top")
                tilt = frames
            } catch (t: Throwable) {
                Log.w("AuraMap", "car frames: $t") // (the drawn marker stays)
            }
            Handler(Looper.getMainLooper()).post { for (w in waiting) w.invalidate(); waiting.clear() }
        }, "aura-car-frames").apply { priority = Thread.MIN_PRIORITY }.start()
    }
}

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
    private var color = MapStyle.routeColor()
    private var navK = 0f
    private var staleK = 0f
    private val navSpring = Spring(0f, 520f, 1f) { navK = it; invalidate() }
    // (the fade is in the paints, not the view's alpha: the marker's shapes overlap, and a view alpha below 1 would
    // send every frame of it through an offscreen layer for as long as the fixes stay away)
    private val staleSpring = Spring(0f, 300f, 1f) { staleK = it; invalidate() }

    /** The car's heading on the screen (degrees clockwise from up) and the map's tilt, for the 3D car. */
    private var yaw = 0f
    private var tiltDeg = 0f
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val grey = android.graphics.ColorMatrix()
    private var greyAt = -1f

    init {
        // (a far camera: the drawn marker's view is tilted by up to 50°, and a near one would stretch its nearer edge)
        cameraDistance = 8000 * resources.displayMetrics.density
        CarSprites.want(ctx, this)
    }

    /**
     * Where the car points on the screen and how far the map is tilted. The 3D car draws both itself; the drawn marker
     * (no frames yet) is turned and laid on the road by the view, as in 1.13. Only a visible change redraws.
     */
    fun pose(bearing: Float, tilt: Float) {
        if (CarSprites.tilt != null) {
            if (rotation != 0f) rotation = 0f
            if (rotationX != 0f) rotationX = 0f
            if (abs(Geo.angleDiff(yaw.toDouble(), bearing.toDouble())) > 0.4 || abs(tiltDeg - tilt) > 0.5f) {
                yaw = bearing; tiltDeg = tilt
                invalidate()
            }
            return
        }
        if (abs(Geo.angleDiff(rotation.toDouble(), bearing.toDouble())) > 0.5) rotation = bearing
        // laid on the ground like the map around it (a little less than the map's tilt: flat on the road it would
        // shrink to a sliver)
        val rx = tilt * 0.85f
        if (abs(rotationX - rx) > 0.5f) rotationX = rx
    }

    fun setNav(on: Boolean) = navSpring.animateTo(if (on) 1f else 0f)

    /** The route's colour may be another now ([AuraMap.reloadStyle]). */
    fun refreshLook() {
        color = MapStyle.routeColor()
        invalidate()
    }

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
        val frames = CarSprites.tilt
        val top = CarSprites.top
        if (frames != null && top != null) { drawCar(c, frames, top); return }
        drawMarker(c)
    }

    /** The 3D car: from above when the map is flat, the matching tilted view when it is tilted, cross-faded between. */
    private fun drawCar(c: Canvas, frames: Array<Bitmap>, top: Bitmap) {
        val cx = width / 2f
        val cy = height / 2f
        val fade = 1f - 0.4f * staleK
        // browsing: a soft halo in the route's colour under the car (navigating, the route itself leads to it)
        val halo = 1f - navK
        if (halo > 0.01f) {
            p.colorFilter = null
            c.drawCircle(cx, cy, 30f.dp, paint(Palette.withAlpha(Palette.mix(color, Palette.text3, staleK), 0.18f), halo))
        }
        if (greyAt != staleK) {
            greyAt = staleK
            grey.setSaturation(1f - staleK)
            bmp.colorFilter = if (staleK > 0.01f) android.graphics.ColorMatrixColorFilter(grey) else null
        }
        // 0 = from above, 1 = the tilted views (the map is flat while browsing, 52° navigating)
        val k = ((tiltDeg - 12f) / 18f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        val half = width / 2f
        dst.set(cx - half, cy - half, cx + half, cy + half)
        if (k < 0.99f) {
            c.save()
            c.rotate(yaw, cx, cy)
            bmp.alpha = (255 * fade * (1f - k)).toInt()
            c.drawBitmap(top, null, dst, bmp)
            c.restore()
        }
        if (k > 0.01f) {
            // the frame of the heading and the next one, blended by how far between them it is
            val y = (((90f - yaw) % 360f) + 360f) % 360f / STEP
            val i = y.toInt() % frames.size
            val f = y - y.toInt()
            bmp.alpha = (255 * fade * k).toInt()
            c.drawBitmap(frames[i], null, dst, bmp)
            if (f > 0.02f) {
                bmp.alpha = (255 * fade * k * f).toInt()
                c.drawBitmap(frames[(i + 1) % frames.size], null, dst, bmp)
            }
        }
    }

    private fun drawMarker(c: Canvas) {
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

    companion object {
        /** The view (dp): the car's frames fill it; the drawn marker sits in its middle. */
        const val SIZE = 92
        /** Degrees between two tilted views. */
        const val STEP = 5f
    }
}

/**
 * The map engine is native code: if it ever crashes on this GPU the process dies without passing through CrashGuard,
 * and a home screen that embeds the map would crash again on every start. Each map start is marked on disk first and
 * cleared once a frame was drawn (or the map was shut down normally); after two starts that never got there, the home
 * screen stops embedding the live map (Maps itself still opens on request).
 *
 * Since the map lies under everything (2.0), the same for a map that goes down later: a second mark from its first frame to its
 * stop ([live], [notLive]). A process found gone with that mark means it went down with the map up, which counts only in the
 * boot it happened and within [LIVE_WINDOW_MS] of the map coming up: a car losing its power with the map on the screen is normal,
 * and that is another boot. [LIVE_LIMIT] of those and no map at the home screen until the next boot: fewer than the guard's three
 * dead starts in a row ([com.abdllh.aura.guard.GuardWatch]), so a map that cannot run costs the map and not the home screen.
 */
object MapGuard {
    private const val LIVE_WINDOW_MS = 3 * 60_000L
    private const val LIVE_LIMIT = 2
    // (a new file for what is new: 1.19 reads aura_mapguard, and 1.19 must start on whatever this leaves)
    private const val PREFS_LIVE = "aura_shell"
    private const val KEY_LIVE_BOOT = "mapLiveBoot"
    private const val KEY_LIVE_FAILS = "mapLiveFails"

    private fun liveMark(c: Context) = File(c.noBackupFilesDir, "map-live")
    private fun bootId() = com.abdllh.aura.guard.GuardMarks.bootId()
    private fun spLive(c: Context) = c.getSharedPreferences(PREFS_LIVE, Context.MODE_PRIVATE)

    // the count of failed starts, in a tiny preferences file of its own (written only when it changes)
    private fun sp(c: Context) = c.getSharedPreferences("aura_mapguard", Context.MODE_PRIVATE)

    // The start marker is an empty file. It must be on disk before the engine starts, because a native crash ends the
    // process without a chance to write anything. Until 1.11 it was a preferences flag written with commit(), which
    // waits for the disk on the main thread just as the home screen's entrance plays; a file written and closed
    // outlives the process just the same (only a power cut in that moment loses it, which the next start shrugs off).
    private fun marker(c: Context) = File(c.noBackupFilesDir, "map-starting")

    fun homeMapAllowed(c: Context): Boolean {
        val sp = sp(c)
        val m = marker(c)
        // (debug builds: a dev loop's force-stops are not a map that cannot run. scripts/dev-install.ps1 sets this)
        if (com.abdllh.aura.BuildConfig.DEBUG && com.abdllh.aura.system.SystemProps.get("debug.aura.mapguard") == "off") {
            m.delete()
            liveMark(c).delete()
            return true
        }
        // (the 1.11 flag too, left by a crash before the update)
        if (m.exists() || sp.getBoolean("starting", false)) {
            sp.edit().putInt("fails", sp.getInt("fails", 0) + 1).remove("starting").commit()
            m.delete()
        }
        countLive(c)
        return sp.getInt("fails", 0) < 2 && liveFails(c) < LIVE_LIMIT
    }

    /** What the mark left by the process before this one means, once. */
    private fun countLive(c: Context) {
        try {
            val f = liveMark(c)
            if (!f.exists()) return
            val mark = f.readText()
            f.delete()
            val boot = bootId()
            if (!liveMarkCounts(mark, boot, SystemClock.elapsedRealtime())) return
            val n = liveFails(c) + 1
            spLive(c).edit().putString(KEY_LIVE_BOOT, boot).putInt(KEY_LIVE_FAILS, n).commit()
            Log.w("AuraMap", "the process went down with the map up: $n in this boot")
        } catch (t: Throwable) {
            Log.w("AuraMap", "guard: $t")
        }
    }

    /** A mark left by a process that is gone ("<boot id> <elapsedRealtime at the map's first frame>"), found at [now] in [boot]: it was the map's fault, as far as can be told. */
    fun liveMarkCounts(mark: String, boot: String, now: Long): Boolean {
        val p = mark.trim().split(' ')
        val at = p.getOrNull(1)?.toLongOrNull() ?: return false
        return p[0] == boot && now - at in 0..LIVE_WINDOW_MS
    }

    /** How many times the process went down with the map up, in this boot. */
    private fun liveFails(c: Context): Int = spLive(c).let { if (it.getString(KEY_LIVE_BOOT, "") == bootId()) it.getInt(KEY_LIVE_FAILS, 0) else 0 }

    /** Why the home screen has no live map (for Settings → About); empty when it may have one. */
    fun whyNot(c: Context): String = try {
        val live = liveFails(c)
        when {
            live >= LIVE_LIMIT -> "not at the home screen until the next start of the unit: the process went down $live times with the map up"
            sp(c).getInt("fails", 0) >= 2 -> "not at the home screen until it has drawn once in Maps: two starts never drew"
            else -> ""
        }
    } catch (_: Throwable) {
        ""
    }

    /** No failed start on record, and none just now. Counts nothing, unlike [homeMapAllowed], so any thread may ask. */
    fun looksFine(c: Context): Boolean = !marker(c).exists() && liveFails(c) == 0 && sp(c).let { !it.getBoolean("starting", false) && it.getInt("fails", 0) == 0 }

    fun starting(c: Context) {
        try { marker(c).writeText("") } catch (_: Throwable) { }
    }

    fun ok(c: Context) {
        val m = marker(c)
        if (m.exists()) m.delete()
        val sp = sp(c)
        if (sp.getInt("fails", 0) != 0) sp.edit().putInt("fails", 0).apply()
    }

    /** The map has drawn its first frame after a start. */
    fun live(c: Context) {
        try { liveMark(c).writeText("${bootId()} ${SystemClock.elapsedRealtime()}") } catch (_: Throwable) { }
    }

    /** It stopped in good order. */
    fun notLive(c: Context) {
        try { liveMark(c).let { if (it.exists()) it.delete() } } catch (_: Throwable) { }
    }
}
