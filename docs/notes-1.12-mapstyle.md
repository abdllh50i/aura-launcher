# 1.12: the map's style, camera and car (`nav/AuraMap.kt`, `nav/MapStyle.kt`, `nav/MapExtras.kt`, `ui/MapBackdropView.kt`, `nav/offline/TileServer.kt`)

Notes for DEV.md's "Aura Maps" section. Several of its sentences describe 1.11 and are no longer true: the
destination is no longer a view, places are no longer circle layers, the tile server no longer has a thread per
connection, and the turn arrow is no longer a fixed 45 + 32 m.

## The frame loop (`AuraMap`)
Everything that moves on its own goes through one `Choreographer` callback (`onFrame`): the car between fixes, the
camera following it, and the change from one camera to another. It runs only while something moves and stops by
itself (car at its target, no change of camera under way, zoom/tilt/heading settled), so a car standing at a light
does not redraw the map. It sets the camera at most once per frame the map draws (`fpsCap`), with one `moveCamera`
and one `placeOverlays()`; the camera move/idle listeners skip their own `placeOverlays()` while the loop is setting
the camera (`inDrive`).
Until 1.11 a camera change was `animateCamera` (a MapLibre flight) and every fix's glide called `moveCamera`, which
cancels running flights: in a moving car Go, Recenter, the end of a trip and the +/− buttons jumped in one frame.
They worked only standing still, which is why they looked fine on the emulator. Now `setCamera(FOLLOW / NAVIGATE)`
and `zoomBy` start a blend: from the camera as it is to the one that follows the car, over 650 ms on
`Motion.STANDARD`, while the car keeps moving. Target, zoom, tilt, the four paddings and the heading are each
interpolated, the heading by `Geo.angleDiff`. When the target is further away than 2.5 screens (a recenter after
panning across town), MapLibre's own flight does the way and the loop eases the last metres after it (`LANDING_MS`).
`overview()` and `northUp()` stay MapLibre animations (the loop leaves OVERVIEW and FREE alone); the overview is
750 ms (900 before).
The loop is not a `ValueAnimator`, so it is not affected by the firmware's animator duration scale of 0.

## The car on the route
On the driven route the car moves along the line, not across it (`movePuck`): from where it is drawn towards where
the car will be at the next fix (the fix plus what its speed covers in one fix interval, at most 45 m ahead). The
arrow is where the car is, not one fix behind it (about 28 m at 100 km/h in 1.11), and it follows every curve of the
line instead of cutting corners on chords. A late fix lets it carry on at its speed (8 s at more than 20 km/h, for
tunnels); a lead of less than 25 m over a car that braked is waited out (it never moves backwards), a larger one is
taken back over 400 ms. Below 6 km/h it does not move ahead and the heading is kept (GPS course is noise at walking
pace). The route's near part is cut at `drawnAlong` directly, without a snap. Off the route, or with no route, the
1.11 straight glide stays. `setCar` finds the fix on the line itself (navigation fixes are snapped, offset 0), so the
callers did not change.

## The navigating camera (`steer`)
* Zoom: continuous by speed instead of the 1.11 bands (which jumped 0.4–0.6 zoom levels in one frame at 35, 65 and
  95 km/h): 17.0 up to 20 km/h, 16.4 at 50, 15.8 at 80, 15.2 from 110, eased with a 1.2 s time constant.
* Near the next turn (within max(120 m, 7 s of driving)) the zoom is at least 16.9 and the tilt 44° instead of 50°:
  the camera closes in on the junction where the driver needs the detail. It uses the turn given to `setTurn`, so
  there is no separate API.
* The room above the car (`navTopPadding`) grows a little with speed (×0.95 below 40 km/h to ×1.14 above 90).
* The heading looks down the road (the bearing to a point 25–70 m ahead on the route, by speed) and turns towards it
  with a 0.45 s time constant, at most 75°/s, frozen below 6 km/h. The car keeps the route's own bearing, so at a
  corner the arrow turns first and the map follows softly. In 1.11 the whole map turned in a linear step per fix.
* The +/− buttons change an offset that is kept for the trip, and the change is blended.

## Frame rate
`setFps(60)` (navigation) now measures: when fewer than 70 % of 60 frames were drawn over 3 s of driving, the map
drops to an even 30 for the rest of the trip (logged as "the map drew N of 60 fps"). Frames alternating between one
and two vsyncs look worse than a steady 30. On the emulator it drops to 30. The unit has not been measured yet.
MapsActivity still asks for 60, unchanged.
3D buildings start at zoom 17, which is closer in than guidance drives (15.2–17). A tilted map while driving never
pays for fill-extrusions, the most expensive layer this GPU draws; browsing close in keeps them.

## The car marker (`CarMarker`)
Browsing, it is a disc with a heading arrow. While a route is driven (`setRouteAhead`) it becomes a chevron (34 × 28 dp) in
the route's colour, with a white edge and a contact shadow. It is laid on the road with `rotationX` = 0.85 × the
camera's tilt, which is a transform only, and it crossfades disc to chevron on a `Spring`. With no fix for 5 s
(`STALE_MS`) it turns grey and 60 % opaque, and the next fix brings it back. `placeOverlays()` writes translation,
rotation and tilt only when they changed (0.5 px / 0.5°), because a view that moves makes the window draw again.
Not done: callers still pass bearing 0 for a fix without one. That only matters above 6 km/h, where a fix nearly
always has a bearing. Below that the last heading is kept anyway.

## The style (`MapStyle`)
* **The route's colour** (`routeBase()`, also the car's): the owner's accent when it is blue, cyan or violet,
  otherwise the default blue. Amber is the motorways' colour, red the cameras', green the parks' and graphite the
  roads': a route in those sank into the map. At night the route is the accent mixed with 32 % white, so it is the
  brightest line on the map. Its casing is the accent with 38 % black by day and 62 % at night. The rest of the UI
  keeps the accent.
* **Widths**: the route is wider than the road it is on at driving zoom (15 px at z16, 22 px at z18, casing +4.5),
  and the turn shaft is 8 px at z16.
* **Rerouting**: while `NavSession.rerouting`, the route's four line layers are at 45 % opacity (the style's own
  transition fades them; `AuraMap.syncRerouting`, checked on every fix).
* **Night roads** step down calmly below the route: service `#30363F` up to primary `#566173`, motorway `#6B5A38`,
  darker casings. Light roads are unchanged.
* **Places**: a dot and its name are one symbol (`icon-image` = `poi-dot`, an SDF disc made in code by `poiDot()`
  and tinted per kind), so they collide together. In 1.11 the dots were circle layers, which never give way, and
  every name that did left an unnamed dot behind. DEV.md says icon symbols drew black on a tilted map on the
  emulator's GPU. That was a coloured image; an SDF image renders correctly on the emulator, tilted, in both
  themes. **Check it on the unit.** The fallback is circle layers for the dots, as in 1.11. Tap targets
  (`POI_LAYERS`) are `poi-a`…`poi-d`.
* **Navigating** hides `poi-b`, `poi-c`, `poi-d` and the house numbers (`MapStyle.NAV_HIDDEN`, applied in
  `AuraMap.syncLayers` on every camera mode change and after a style load). Band a (the most important places,
  fuel among them) stays.
* **Buildings** are drawn after the roads: a street in front of a building passes behind its walls instead of being
  painted across them. Flat footprints show from zoom 13 with no upper limit; 3D starts at 17 (see Frame rate). The
  style has a `light` (intensity 0.3 at night, 0.4 by day) so lit roofs do not glare.
* **Bridges, ramps, tunnels**: roads on the ground exclude both bridges and tunnels. `bridge-casing` and `bridge`
  draw every flyover over all ground roads, in its class's colour and width (`classWidth`, made from the same
  `W` stops as the class layers). Motorway slip roads (`ramp=1`) are 60 % wide. Main tunnels are a quiet band of
  their own width (`tunnel-major`); small ones keep the dashed line.
* **Labels**: street names stand up to the viewer on the tilted map (`text-pitch-alignment: viewport`); flat, they
  were squashed to about 60 % while driving. They are a little larger, and every text size is ×1.12 in Arabic
  (`ts()`), whose letters read about a size smaller than Latin at the same pixel size.
* **The turn arrow** is built by `AuraMap.setTurn(line, at)` (it was a slice made by MapsActivity). It is 45 m
  before and 32 m after the turn at zoom 16.8, scaled by 2^(16.8 − zoom) up to ×3, and rebuilt when the zoom has
  changed that scale by more than 15 %. At motorway zoom it no longer shrinks to a blob. Its layers are **over the
  labels**: a place's name sitting on the turn hid its head. The home map shows it too (MapPanel, same rule: within
  900 m, not depart/arrive, not while rerouting).
* **The destination** is drawn by the map (`SRC_DEST`, three circle layers lying on the ground: halo, ring, core in
  the route's colour), on top of everything. In 1.11 it was a pin view, which trailed the GL map by a frame during
  every flight and pan. `DestPin` is gone.

## The home map's start (`MapBackdropView`, `AuraMap.onDrawn`, `pictureFile`)
* `AuraMap.onDrawn` fires when the map has drawn with the style loaded (`onDidFinishRenderingFrame(fully)`), or 2.5 s
  after its first frame (offline, tiles that never come). `MapGuard.ok` still fires on the first frame. It replaces
  `onFirstFrame`, which fired on the first frame of land colour before any tile.
* The home map keeps a picture of itself (`pictureFile`, `cacheDir/home-map-{dark|light}-{lang}.webp`, WebP 80). It
  is taken with `MapboxMap.snapshot` 3 s after the map came to rest, at most once a minute. At the next start
  `MapBackdropView` shows that picture over the live map until `onDrawn`, then fades it out over `Motion.AMBIENT`.
  The car is usually still where it was parked, so the screen opens on the real streets. The synthetic street grid is
  only for the very first start and for the `MapGuard` fallback, and it now uses the style's own colours
  (`MapStyle.tones()`).
* The backdrop is no longer shown again at `onStop`. The TextureView keeps its last frame across stop, which I
  checked on the emulator going home from Maps: the map was there at once, with no flash.
* Once the map has drawn, the panel's own `mapLand` fill is removed. It sat under an opaque map on every map frame.

## MapExtras
* All of `spots`, `published`, `mine`, `speeds`, `grid` and `updatedAt` are now written on the main thread only. The
  io thread parses and posts the result. A fetch that finished while a camera was being marked could otherwise put
  the old set back.
* `version` counts every change. `geoJson(arabic)` is cached per (version, language), and `AuraMap.pushExtras` sends
  a set only when that map does not show it yet. Before, the whole FeatureCollection (thousands of points) was
  rebuilt and reparsed at every start of the home screen and of Maps.

## TileServer
There are two small pools of background-priority threads instead of a cached pool at the UI's priority. Three answer
from the disk, and six fetch online, so a stored tile never waits behind a fetch. A zoom asks for dozens of tiles at
once; in 1.11 each got a thread of its own, and that burst competed with the gesture that caused it. If map loading
feels slower on the unit, raise the online pool first.

## For other files (not changed here)
* `Palette.mapBlock`, `mapPark`, `mapWater`, `mapRoad`, `mapRoadMajor` and `mapCasing` are no longer used (the
  backdrop reads the style's colours) and can be removed. `mapLand` is still used.
* `HomeActivity` adds a full-screen `BackdropView` that fills `Palette.bg` over a window background of the same
  colour (the audit's map-backdrop-pop-overdraw (c)). `MapsActivity.buildUi` sets `root.setBackgroundColor(mapLand)`
  (the same finding's (e)). Both are owned by other areas.
* Floating panels over the map: opaque glass and `lift(…, overMap = true)` (the audit's nav-ui-redraws-every-frame
  (3)/(4)) belong to the maps UI and the foundation.
