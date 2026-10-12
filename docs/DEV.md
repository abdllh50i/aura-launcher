# Developer notes

## Build the app
```
set JAVA_HOME=...\jdk-17   &  set ANDROID_HOME=...\Sdk
gradlew :app:assembleRelease        (needs signing\keystore.properties — see below)
```
`app/build.gradle.kts` reads `signing/keystore.properties` (storeFile, storePassword, keyAlias, keyPassword). The keystore is
never committed. Version: `-PappVersionName=1.2.3` (or `1.3.0-beta.2`); the versionCode is derived from it, in the same order
as the name: `(MAJOR*10000 + MINOR*100 + PATCH) * 100 + (99 if stable, else the pre-release number N of "-beta.N")`, so
`1.3.0-beta.1` = 130001 < `1.3.0-beta.2` = 130002 < `1.3.0` = 130099 < `1.3.1-beta.1` = 130101. MINOR and PATCH stay below 100,
and a version's pre-releases form one numbered series. The updater compares the GitHub tag (`v1.2.3`) with
`BuildConfig.VERSION_NAME`; the downloaded APK must carry the same signing certificate and a higher versionCode.

## Publish an app update (what the on-screen updater installs)
1. bump the version, build release, 2. `gh release create vX.Y.Z aura-X.Y.Z.apk aura-X.Y.Z.apk.sha256 --notes "..."`
(`--prerelease` for betas; they are only offered to units that switched on "Include beta versions").
Release notes are shown in the update screen. `rom/make-release.ps1 -Version X.Y.Z` builds the APK, the ROM image and the pack.
Integrity: the app verifies the APK against GitHub's own asset digest, else the `.sha256` asset (keep it next to the APK), and
always checks package name, signing certificate and a higher versionCode before handing the file to the installer.
A release that has no APK (for example one that only carries the ROM pack) is simply skipped by the updater.

## ROM workshop (how the system image is built)
The stock `system.img` (ext4, from the dump) is edited inside an Android 10 emulator because it ships the real
`e2fsck`/`resize2fs` and a Linux ext4 driver: `rom/workshop/apply.sh` grows the filesystem into the free space of its logical
partition (never beyond), mounts it, copies `rom/system/**`, patches the NWD config with `rom/system/bin/aura-prepare.sh --image`
and the `build.prop` default-launcher line, runs `e2fsck -fn`. `rom/tools/imgdiff.py` then proves, file by file, that only the
intended paths changed; `rom/tools/make_patch.py` turns the difference into a block patch (forward + reverse), which
`rom/flash/aura-flash.sh` applies with full hash verification on the unit. Room: the system partition has 8417 free 4K
blocks after the grow; a writable ext4 mount keeps up to 4096 of them back from every writer, root included (the
kernel's runtime `reserved_clusters`), so `apply.sh` sets that to 0 for the workshop mount (the unit mounts system
read-only). 1.4.0 leaves about 2600 blocks (~10 MB) free; 1.5.2's new data takes 6755 of the 8417. The file system
has no journal, so a stock file rewritten in place gives its blocks back at once and the allocator may hand them to
the new data, which the block patch cannot write crash-safely (make_patch stops: "live file data in the old image";
1.5.2's bigger APK hit it); deleting the stock file instead fails the write-order proof (a directory entry briefly
points at a freed inode). So the two stock boot animations are kept, renamed `bootanimation.zip.aura-stock`, and
nothing of the stock image is overwritten or freed. The pack also carries per-range hashes and a hash of
every block *outside* the ranges (`rest`): if a run is ever interrupted, a partition that matches neither image but whose
`rest` still matches is reported as an interrupted patch and `install`/`restore` simply finish it (rewriting all ranges leads
to a known image again); anything else is refused. `rom/tools/make_zip.py` writes the release zip (forward-slash entries).

Crash consistency: the ranges are written in a fixed order (new data, superblock/group descriptors, bitmaps, inode tables,
existing directory blocks; restore = the exact reverse), because the partition is live and a power cut during the write must
not leave a system that cannot boot. `rom/tools/make_patch.py` classifies every changed block with `rom/tools/fsmap.py`
(and refuses images that overwrite live file data), and `rom/tools/check_order.py` proves the order: it simulates the state
after *every single block* of the write (both directions) and requires that all changed files, new files and their parent
directories are always exactly the old or exactly the new version; the same check on an ascending order fails in >1000 of
1022 states. `make-release.ps1` runs it. `scripts/test-flash.ps1` exercises the PC installer and the on-device script
against a loop device on the emulator (round trip, interrupted runs, refusals, damaged packs, a run that outlives its adb
session, finishing after a lost follow-up); `-Quick` runs only the round trip, e.g. on a freshly unzipped release.

### The Linux installer (`rom/flash/aura-install.py`, `install-linux.sh`)
Python 3.8+, standard library only, so a laptop at the car needs neither `adb` nor internet. It carries a small ADB client
(CNXN handshake, `exec:`/`shell:` streams, `sync:` push; no AUTH, because the unit has `ro.adb.secure=0`; if a unit ever asks for
authorisation it tells the user to use `--system-adb`, which drives the system `adb` binary instead). Discovery: it scans the
laptop's own private /22-or-smaller networks (virtual/VPN interfaces skipped) for TCP 5555, reads the connect banner and only
opens a shell on hosts whose `ro.product.model` is K2501 (or that do not say); then the same identity check as the Windows
installer (`ro.product.system.model` / `ro.nwd.platform.name` == K2501) gates everything. All write-safety logic stays in
`aura-flash.sh` on the unit; the Python side pushes the pack (size **and** SHA-256 checked on the unit), runs the script,
parses `STATE_CODE=` / `RESULT:`, runs the follow-up step, reboots and waits for the unit to come back.
Testing: `AURA_TEST=1` unlocks the hidden `--device`/`--expect-model` flags. `scripts/fake_adbd.py` is an adbd test double
(strict about the protocol rules adbd enforces) that runs the commands on the emulator through the real adb, so
`scripts/test-flash.ps1 -Installer py -Pack <pack dir>` runs the whole suite through the Linux installer.
Never scan the LAN while developing if other adb devices (a TV...) live on it: use `--scan 127.0.0.1/32 --port N`.

Emulator: AVD "CarUnit" (Android 10 x86_64, 1024×600, 160 dpi) created with `ANDROID_AVD_HOME` on an ASCII path; the stock image is pushed to
`/data/local/tmp/ws/system.img` once.

## Home screen design
`home/HomeActivity.kt` lays out `CarPanel` (clock, the car, quick buttons), `MapPanel` (live map, "Where to?", Home/Work,
the floating `MediaCard`, a guidance banner while navigating), the `Dock` and two bottom sheets (`ControlsSheet`, `AppDrawer`,
both on `ui/Sheet.kt`). Colours are theme tokens in `ui/Palette.kt`; `ui/Theme.kt` resolves dark / light / auto (light between
an estimated sunrise and sunset) and a theme or accent change rebuilds the views in place behind a `PixelCopy` snapshot that
fades out. HomeActivity forwards its lifecycle to the embedded map; the map is capped at 30 fps and only draws when it changes.

Motion (1.6): `ui/Motion.kt` has the curves (emphasized in / exit / standard), a `Spring` (Choreographer frames, keeps its
speed when the target changes: presses, sheets, sliding indicators) and `pressScale` on it. `ui/Segmented.kt` (sliding
indicator, its two edges on springs), `ui/PlayPauseView.kt` (the triangle's halves morph into the bars), `GearView`'s lit
circle slides between P R N D. **The firmware ships with Android's window and transition animation scales at 0**
(SettingsProvider `def_window_animation_scale` / `def_window_transition_scale` = 0 %), so every screen change was a cut:
`system/Animations.kt` sets them to 1 where it finds 0 or nothing (switch in Settings → Display & sound, on by default),
keeps what it found in `files/anim-scales` and puts it back when switched off, when AMRI stops being the home screen and
in the installers' restore. The screens open and close with `res/anim/aura_*` (zoom 0.94 ↔ 1 with a fade; the screen
below steps back to 0.97 and dims) through the theme's `windowAnimationStyle`. In-process, `ensureAnimators()` sets
ValueAnimator's scale back to 1 when the system says 0 (reflection on `setDurationScale`, greylisted = allowed on 10),
as the stock launcher's `AnimationDriver.resetAnimatorDurationScale` does.

1.7 made the motion quieter and quicker. Rules it follows: nothing on a screen takes longer than about 300 ms
(`Motion.MEDIUM` 280, `LONG` 420 for a whole layout); what leaves is quicker than what arrives (a screen opens in
280 ms and closes in 200); nothing starts slowly — `Motion.EXIT` and `aura_exit` are ease-out curves now (an ease-in
exit read as the screen answering late); a press comes back with one small overshoot, not a wobble (`pressScale`
releases with stiffness 560, damping 0.7; it was 420 / 0.48). `ui/Motion.kt`'s `LayoutMove` is for a layout that
changes under the owner's eyes: made before the change it remembers where views are and takes a picture of the ones
that leave; `play()` after it shows each view coming from its old place (translation and scale only, so no layout
runs while it plays), the pictures fading where they stood. The moving views' parents must have `clipChildren`
off, and `roundedClip` keeps a scaled view's corners as round as they look at rest (radius ÷ scale, asked again on
every frame of the move). Only transforms and alpha are animated anywhere; a view whose text is scaled for a moment
goes through a layer (`withLayer()`), since text redrawn at a new size on every frame makes this GPU rasterise a
new set of letters each time.

## Aura Maps (`nav/`)
MapLibre Native **10.3.7** on purpose: it renders with OpenGL ES 2.0, which is what the unit's firmware declares
(`ro.opengles.version=131072`); 11.x and later need ES 3.0. `MapStyle.kt` builds the style JSON (OpenFreeMap vector tiles and
fonts, dark/light colours, 3D buildings, labels in the UI language); `AuraMap.kt` wraps `MapView` (camera modes follow /
navigate / free / overview; the car and the destination pin are plain Views moved to their projected screen points each
camera frame — a tilted symbol layer rendered black on the emulator's GPU). `MapGuard` marks each map start on disk: after two
starts that never drew a frame (a native crash cannot be caught), the home screen falls back to the static `MapBackdropView`.
The style since 1.8 (`MapStyle.kt`): a road map — motorways and trunk roads in a warm colour, main roads wider than
streets, every road on a casing (butt-ended: round ones bead where the tiles cut a road), built-up land, sand, parks and
water each in their tone — and the places people drive to on it: the tiles' `poi` layer as a dot in the colour of its
kind with its name (`Poi.kt`: food, shops, fuel, health, mosques, hotels, banks...), in four bands by the tiles' `rank`
from zoom 13.6 / 15 / 16 / 17. Dots are circle layers and names symbol layers without icons (icon symbols drew black
on a tilted map on the emulator's GPU); the least important band comes first in the list, since a layer further down
places its names first. A tap on a place (`AuraMap.onPoiTap`: `queryRenderedFeatures` in a finger-sized box, the
nearest dot) selects it like a search result.
The route while driving is two sources: `aura-route-near`, from the car to at most 1.2 km ahead, cut again at the car
every 70 ms — at the car as it is drawn, gliding between the fixes (`AuraMap.trimRoute`, called from the glide), not
at the fixes — and `aura-route`, the long rest, rebuilt only when less than 350 m of the short part is left. Up to
1.7 the whole remaining route was rebuilt every 150 m or 10 s, so the line trailed behind the arrow. `aura-turn` is
the arrow of the next manoeuvre on the route itself (from 900 m before it: 45 m of the route before the turn, 32 m
after, and a triangle in metres on the ground as its head). The guidance banner is a panel in the route's colour, like
a road sign; the trip bar leads with the time left.
Google's map cannot be the map here: the Maps SDK has no offline regions and needs a billed key, the Maps Platform
terms forbid downloading or keeping its tiles and places, and only the Google Maps app can keep an area for use
without internet. So that app stays a choice (Settings → Navigation, with a line saying how to download an area in
it): Where to? opens it, Home / Work and "take me home" are handed to it as coordinates (`Actions.driveWithApp`), and
the home screen keeps AMRI's own live map either way.
Search: Photon (`Places.kt`, type-ahead with a generation counter, Home/Work/recents in prefs). Routing: OSRM (`Router.kt`,
steps placed on the polyline). `NavSession.kt` snaps fixes to the route, reroutes after three off-route fixes (at most every
8 s), speaks Arabic/English prompts (TTS) and runs `NavService` (foreground, type location) with the next manoeuvre.
Debug builds: `MapsActivity --es mock_loc "lat,lon,bearing"` and `--ez simulate true` (drives the route); the CarUnit AVD
needs `hw.gps = yes` for `adb emu geo fix`. A release build can be tried on the emulator with `-PwithEmulatorAbi`.

### Offline map (`nav/offline/`)
Settings → Navigation → Offline map downloads Jubail, Dammam–Dhahran–Khobar and the roads between them once
(`OfflineMaps.kt`, resumable, waits out internet drops, resumes after a restart): BRouter's routing data
`segments4/E45_N25, E50_N25.rd5` (brouter.de, ~17 MB, lon 45–55 / lat 25–30), the Noto Sans glyph ranges of the labels,
and every OpenFreeMap tile of `Region.kt` (street level, zoom 14 — every street and every place the tiles have — over the
two cities and King Fahd airport; zoom 11–12 over the stretch between them; zoom ≤10 around them: ~1 300 tiles), then
zoom 13–14 tiles along the motorways/trunk/primary/secondary roads of that stretch (`Region.roadTiles`, found in the
tiles themselves, ~1 000), then builds the search index. All in `files/offline/map.db` (SQLite, WAL: tiles as sent
(gzip), glyphs, `places`). 34 MB, a minute on a good line. Up to 1.7 the area was the whole Eastern Province (~150 MB):
the owner asked for the three cities only. A unit that holds the old area is trimmed to the new one at the next start
(`OfflineMaps.trimKept`: the store's `region` fact is not `Region.ID` → tiles outside it deleted, the two southern
routing files removed, the search index rebuilt, `VACUUM`), without downloading anything.
* `TileServer.kt`: http://127.0.0.1:47821 (fixed port: MapLibre's cache keys stay valid across restarts) serves
  `/t/z/x/y.pbf` and `/f/{fontstack}/{range}.pbf` from the store, else from OpenFreeMap (503 when neither: not cached;
  a missing glyph range answers 204 so labels never hold a tile up). A thread per connection (a stored tile never waits
  behind an online one), upstream not asked again for 30 s after a failure, the TileJSON refreshed in the background,
  upstream tiles streamed. `AuraMap` calls `Mapbox.setConnected(true)`: without any network Android reports
  "disconnected" and MapLibre would otherwise stop requesting, even from 127.0.0.1.
  `MapStyle` points the `omt` source and the glyphs there, always: OpenFreeMap's TileJSON names a new planet version
  every week (`.../planet/<version>/{z}/{x}/{y}.pbf`), so MapLibre offline regions (keyed by URL) would stop matching a
  week after the download. Release builds allow cleartext only to 127.0.0.1/localhost (`res/xml/network_security.xml`).
* `PlaceIndex.kt` + `Mvt.kt` (a small MVT reader): names of the `poi`, `place`, `transportation_name`, `aerodrome_label`
  and `park` layers at zoom 14 (+ towns at 12), with `TextMatch.norm` keys and consonant skeletons (Arabic query ↔ Latin
  name) and the nearest town. `Places.search` shows the offline results at once and merges Photon's when they come;
  `Places.reverse` names a pin offline first.
* `OfflineRouter.kt`: BRouter (`brouter/` module, `car-vario.brf`, vmax 120) → GeoJSON → OSRM-like `Step`s (no road
  names; U-turns, keep left/right, roundabout exits, ramps). `Router.route` runs OSRM and BRouter side by side and takes
  OSRM when it answers within 2.5 s. R8 keeps `btools.**` (the profile loads `btools.router.KinematicModel` by name).
  Emulator: 84 km Dammam → Jubail in 2.4 s.
* Debug: `setprop debug.aura.offline small` downloads only a test area (Jubail + a stretch of the Dammam highway, two
  routing squares, ~27 MB). Offline tests on the emulator: `svc data disable` + an iptables REJECT on every interface
  except DNS and 10.0.2.2, and delete `files/mbgl-offline.db` (MapLibre's own cache) with the app stopped.

### Map additions: limits, cameras, signals, bumps (`tools/map-editor`, `nav/MapExtras.kt`)
What the map data does not have, the owner draws himself and every unit picks up.
* **The editor** (`tools/map-editor/map-editor.bat` → `server.py`, Python standard library, http://127.0.0.1:8765, this
  PC only: it answers no other host name and no other site's page). `index.html` is a MapLibre GL JS map (OpenFreeMap
  "liberty") with six tools: move, street speed (a click takes the nearest named road of the tiles and the pieces of
  the same name joined to it, simplified, and asks for the limit), camera (with the speed it checks), signal, bump,
  delete. Every change is saved at once to `data/additions.json` (ignored by git); **تحديث الخريطة (نشر)** publishes.
* **Publishing** writes `additions.json` to the branch `mapdata` of the GitHub repository through `gh api` (the account
  `gh` is logged in to; the branch is made the first time, an orphan with that one file — never a copy of the code).
  `--dry-run` publishes to `data/published.json` instead; `--repo owner/name` for another repository.
* **Format** (version 1; a unit ignores any other version):
  `{"version":1,"updated":ms,"speeds":[{"id","name","kmh","lines":[[[lon,lat],…],…]}],"cameras":[{"id","lat","lon","kmh"}],"signals":[{"id","lat","lon"}],"bumps":[{"id","lat","lon"}]}`
* **On the unit** `MapExtras` keeps the last copy in `files/map-extras.json` (so it works without internet), fetches
  `api.github.com/repos/{repo}/contents/additions.json?ref=mapdata` (raw; `raw.githubusercontent.com` if that fails)
  once a day when there is internet, and at once from Settings → Navigation → Map additions → Update (`ExtrasCard`).
  The repository is the updater's (`Prefs.updateRepo`). `MapStyle.extras` draws them from the GeoJSON source
  `aura-extras`: the limit as numbers along the street, a camera as a red disc, a signal as three lamps, a bump as an
  amber dot (circles and text, like the places: no icon symbols).
* **While driving** `RoadWatch` reads every fix: the limit of the marked street the car is on (within 24 m of the line and
  heading along it, held 4 s over gaps), and the nearest camera (600 m) or bump (180 m) ahead of it (±55°), with a tone
  the first time each comes within reach. `RoadHud` shows them on the Maps screen (the limit as its sign above the
  speed, red when the car is over it; the warning at the top) and on the home screen's map. Only while fixes come: the
  home screen or the map in front, or a route being driven.
* Debug: `setprop debug.aura.extras http://10.0.2.2:8765/api/data` reads the editor on the PC directly (run it with
  `--dry-run`); `adb emu geo fix` + `MapsActivity --ez simulate true` drives a route past them.

## Aura Music (`music/`)
`Library.kt` reads MediaStore (internal storage, `/mnt/media_rw/udisk*`, SD card) and groups by album / artist / folder;
`ArtLoader` decodes covers off the main thread into an LRU. `Player.kt` (MediaPlayer, queue, shuffle/repeat, MediaSession,
audio focus) also does what the stock NWD music app does so the firmware treats it as the music source (app id 2): takes MCU
source 0, announces the app (`ACTION_APP_IN_OUT`), stops the other media apps, sets bit 1 of `Settings.System
nwd_arm_volume_type` while it plays (the firmware then sends the panel/steering-wheel media keys as `ACTION_KEY_VALUE`
broadcasts) and reports the track with `send_media_play_info` / `send_media_play_time`. `BtMusic.kt` talks to the firmware's
Bluetooth module (`com.bt.bc03`, binder `com.bt.BTFeature`, AVRCP ID3 broadcasts): title/artist, play state, progress and
transport (no cover art, album or seeking over this module). `media/MediaMonitor.kt` merges Bluetooth, Aura's player, other
apps' MediaSessions and the NWD broadcasts for the home card.
`system/StockMusic.kt` (user switch, off by default): `pm disable-user com.nwd.android.music.ui` and
`/data/nwdappconfig/app/replace_source_list.xml` (app id 2 → `com.abdllh.aura.music.MusicActivity`, re-read on
`com.nwd.ACTION_REPLACE_SOURCE_LIST_CHANGE`); both need root, so they run through the unit's own adbd (`system/LocalAdb.kt`).
`system/MusicKeys.kt` (on by default) opens Aura Music in place of the stock Bluetooth-music screen / music player when
they come to the front (wheel button, CAN box, app list). Not through the replace list: an app named there becomes a
"source" app, and KernelService force-stops the package of a source it leaves (MCU "pop source" → `back2LastSource` →
`com.nwd.action.ACTION_STOP_APP` → `NwdManager.forceStopPackage`), which would kill the home screen; and the old CAN app
starts `com.nwd.bt.music` directly. The stock screens' `onResume` sends `com.music.action.STOP_QQ_MUSIC` (BT music) /
`com.bt.ACTION_A2DP_MUTE` (music) right before `com.nwd.ACTION_MEDIA_PLAY` (15 / 2); the firmware also sends MEDIA_PLAY 15
alone for phone-projection apps, and Aura marks its own with `MediaMonitor.EXTRA_SELF`.
The same button again, inside Aura Music, goes out to the home screen (1.7), as it leaves the stock screen on the
stock system. Why it did nothing before: a key that starts one app (KeyRuleConfig.xml: 99 → app 15 Bluetooth music,
83 → app 2 music; `StartMode` 0) is handled by `InputChangeSourceRule.singleChangeSource`, which leaves the app
(`exitSource`, `ExitMode` 1 = back to the source before) only when the key's source is the current one **and** its
package is on top (`ProcessManager.getTopPackgeName`). With Aura Music on top it is not ("app is at top = false"), so
the firmware starts the stock screen once more, and 1.6 opened Aura Music over it again. Now a stock music screen that
comes up over Aura Music (`Front.own` is `MusicActivity`, paused within 1.5 s) on the source Aura Music plays is the
second press: the home screen instead. The other source's screen is its own button, and changes the source as before
(MODE, key 23, loops 2 → 3 → 8 → 9 → 11 → 15). Emulator: `settings put system activity_starting
com.nwd.bt.music,com.nwd.bt.music.home_horizontalActivity` with another app started over Aura Music a moment before.
Lyrics (1.5.4): `Lyrics.kt` asks LRCLIB (lrclib.net, free and open, no key; many Gulf / Arabic songs) by title, artist
and length (`/api/get`, then `/api/search?q=` and by field), takes the result whose name, artist and length (±8 s)
match, prefers timed (LRC) lyrics, keeps them in `cacheDir/lyrics` (a "none" for 3 days, only when every question
got its answer). The server often answers 503: each kind of question gets one more try 1.5 s later, then the next
kind; a question left unanswered makes it "failed" (not kept; the screen asks again 20 s later, one retry at a time,
none after the screen stops), and a song skipped while its lookup waited in line is not looked up. A Bluetooth song is
looked up once, with the length the phone told (none on this unit) — never a catalogue's, which may be a live recording
and would filter the right lines out. Without a length (1.6), the recording most timed entries agree on (within 3 s)
wins: a song is on LRCLIB many times, mostly as its album recording, sometimes as a video with a long intro (Thunder:
261 s vs 187 s); the cache key carries `|2` so 1.5.4's picks are looked up again. Spaces must go as `%20` (URLEncoder's `+` reached LRCLIB as a plus:
Arabic titles were never found). No album: a file's album is often its folder ("Music"), which misses the exact
match. `LyricsView` lights the line being sung (others dimmed and at 95 %) and glides it to the middle (4 s after a hand
scroll it follows again); a tapped line is where the song goes: the unit's songs seek there, a Bluetooth song sets its
clock (the line's time + 450 ms, the owner taps what is being sung); while a Bluetooth song's position is unknown no line
is lit and a hint asks for that tap. The lyrics button puts it in the library's panel (with Bluetooth the song gives the
panel back for it); the progress tick runs every 200 ms while it shows.
The lyrics alone on the whole screen (1.7): the button in the panel's corner (`MusicActivity.showFull`; Back, or the
button where the screen's own way back is, returns; kept in `musicLyricsFull`). A second `LyricsView(big = true)` in a
layer over the screen (38 sp lines), with a small bar: cover, names, previous / play / next and a thin progress line.
Both views are fed alike and the layer is INVISIBLE, never GONE, so it stays laid out: going there and back measures
nothing, it only fades and moves; the one out of sight follows the song without animating (`at()` when `!isShown`), so
it is in place when shown. The cover travels between its place and the bar's as a copy above both (`fly`), its corner
radius following its size. Lighting a line: `LyricsView.Line` says `hasOverlappingRendering() = false` — with a
background (the pressed tint) a dimmed TextView is otherwise drawn through an off-screen buffer on every frame, a
dozen of them while the lines scroll — and the alpha + scale change of the two lines involved runs `withLayer()`.
`relayout` (lyrics on/off, another source) is a `LayoutMove`: the cover, the names and the top buttons travel, the
panel or what was in it fades where it stood.

Where a Bluetooth song is (1.6): this unit's stack never says. UartConfig.ini `bt_module=0` → the AT# ("atpound") driver
over `/dev/goc_serial` (the GOC stack for the AIC8800, `persist.sys.bt.aic8800=true`); its progress report is the
unsolicited `MQ` indication (`responseA2dpProgress` → `onGetA2dpProgress(total, pos)`, which needs three fields and is
dropped otherwise) and there is no command to ask for it; the stock BT music app shows its progress bar only with
`persist.sys.bt.rtl8761=true` (false here). So `BtMusic` keeps a clock (elapsedRealtime; pause/play stop and restart it):
a title that follows another one in the same connection is a seen start (position 250 ms); the first one after
connecting (or after the process started) is unknown (`positionKnown` false) until the owner taps a lyric line
(`syncTo`) or the next song starts; "previous" with the same title still there 2.5 s later = the phone restarted the
song. A module report (if a phone ever sends one) re-bases the clock when it is more than 1.5 s off, and its length
allows a repeat-one wrap. Length: the phone's, else the lyrics' recording, else the cover catalogue's (`CoverSearch`
keeps iTunes `trackTimeMillis` / Deezer `duration` beside the cover as `<hash>.len`). Debug: `am broadcast -a
com.bt.ACTION_AVRCP_MUSIC_ID3 --es extra_avrcp_id3_title T --es extra_avrcp_id3_artist A --ez sim true [--ez sim_first
true]`, `com.bt.ACTION_BT_MUSIC_PAUSE/PLAY`, `com.abdllh.aura.debug.BT_PROGRESS --ei total S --ei pos S`.
The 1.6.1 betas (tags `v1.6.1-beta.1..3`, code only in the history) tried to make the phone say it: a changed copy of
`/system/bin/gocsdk_8800` bind-mounted over the daemon (AT#VI → AVRCP GetPlayStatus, the missing `MQ` template), the
daemon and `com.bt.bc03` restarted to pick it up. It did not work on the unit and 1.7 is 1.6.0's code again;
`system/BetaLeftovers.kt` lets that mount go once and removes `/data/local/tmp/amri-bt-timing` (the daemon that runs
keeps running: the owner is told to restart once). What was learnt about the daemon is in the betas' `tools/bt-timing`.

### The 3D car (`tools/car3d`, debug-only `CarBakerActivity`)
The car is not rendered live (the source model has ~726k triangles and 8K textures; the unit has a Mali-G31). Instead a
turntable is pre-rendered once on the emulator's GPU and shipped as 180 WebP frames (2° apart, 640×360 with alpha, ~3.4 MB;
the 2560×1440 bake masters stay in `D:\k2501-work\car3d\final180`, outside the repo):
1. `fbx2mesh.py model.fbx OUT --front -x` → `mesh.bin` (positions, normals, GL UVs, indices; binary FBX parsed by `fbx_binary.py`),
2. `prep_textures.py` → `basecolor.jpg` + `rm.png` (roughness/metalness), `find_plates.py OUT` blanks the licence plates,
3. `bake.ps1 -Out DIR -Params "frames=180`nwidth=640`nheight=360`nss=4..." -Install -PushMesh` runs
   `app/src/debug/.../CarBakerActivity` (GLES2: studio environment, GGX specular, clear coat, ambient occlusion from 64
   depth maps, soft floor shadow, 4× supersampling); `only=a-b` bakes part of the frames (long bakes in two halves),
4. `pack_frames.py DIR app/src/main/assets/car --size 640x360 --default 160 --yaw-step 2` → `f_NNN.webp` + `car.json`
   (frame size, step, resting frame, boxes).
At runtime the GPU draws it (`ui/CarGl.kt`). `CarEtc` lays every frame over the stage's background and its floor light
(the home backdrop is one flat colour, so the frames need no alpha then) and compresses it to ETC1 (decoded in hardware by
every GLES 2 device): one file per theme, `no_backup/car-etc-<hash>.bin`, 180 × 115 KB ≈ 21 MB, built once in the
background on two threads (4 s on the emulator; the other theme's file follows). `CarGlView` (a TextureView with its own
EGL context and thread) keeps all 180 textures in the GPU, so turning the car costs no copies and no uploads: per frame
the shader blends two frames and fades the frame border, the view's edges and the intro into the background. A car at
rest gets its frame uncompressed (decoded and composed again, uploaded as RGB) and blends over to it, so compression
only ever shows while it turns. `ui/CarStage.kt` (a FrameLayout over that view) owns the motion: drag, fling, snap and a
spring back to the resting view (mirrored in RTL); the car eases towards the finger on every display frame (touch panels
report unevenly); with the GPU drawing, motion goes straight to it without redrawing the view tree. Until the frames are
in the GPU (the first start builds them), and for good if the GPU path fails, the stage draws on its own canvas
(`ui/CarFrames.kt`: frames decoded on two threads into a small LRU; after a GPU failure also a memory-mapped raw frame
file). The feel is set in degrees, so another frame step needs no retuning. Settings → About shows how the car is drawn
and how fast it turned last time. The model itself is not in the repo.

### The voice assistant "عمري" (`voice/`)
* Wake word, offline and without a model to download: a personal template matcher. The owner says "عمري" 4 times
  (Settings → Voice assistant; best in the car with the engine running); each later word is compared with those.
  `WakeWord.kt`: `Segmenter` splits the microphone into words (background = the 4th quietest 10 ms of the last 1.5 s,
  measured after a first difference that takes away road rumble; up to 2 s of near silence while a recording starts
  are skipped, and the 3 quietest hops ignored: a recording's start and a stream's dropouts otherwise held the
  background too low and the next noise looked like a word; a word starts 10 dB over it and ends 0.2 s near it or
  24 dB under its own peak; 0.2–1.5 s only). `Features.spectrum` keeps a word's mel spectrum with the noise of the
  0.3 s before it subtracted (over-subtraction 1.5, floor 0.1) and that noise; `Features.cepstra` gives MFCC c1..c12 +
  deltas (cepstral mean normalisation) of a spectrum seen through a given background (no band under it). `WakeModel`
  keeps the recordings as spectra (`amri-wake.bin`, "AMR2") and compares a word with each through the louder of the two
  backgrounds (`Dtw`, Sakoe-Chiba band): what noise hides in one is hidden in both, so a "عمري" said on the motorway
  matches one recorded in a quiet garage. Score = (mean of the two nearest) / (the recordings' own mean distance
  through their background); wakes under 1.15 (strict 1.05, relaxed 1.30). Tried with Windows' Arabic voice (Naayf)
  played into the emulator's microphone (VB-Cable, below): recorded in quiet, "عمري" scored 0.56–0.92 quiet and in
  road noise (6/6 each); "عمر", "عمرو", "مرحبا", "شغل", "الصوت", "طيب", "عمرين", "سمري", "أحمد" 1.4+; the
  near-homophone "حمري" 1.19 in road noise. An offline copy of the matcher (Python, not kept) agreed, and showed
  every pairing of quiet / road / loud-road recordings and words keeping "عمري" ≤ 0.93 and the others ≥ 1.40 (the
  earlier per-word noise subtraction alone failed noisy words recorded in quiet: 1.31–1.69).
* `VoiceCheck` (1.5.4): a word close to the recordings must also be a voice: at least 15% of its voiced frames' own
  energy in 250 Hz-4 kHz (own: the background of the 0.3 s before the word taken off three times over, since a
  rumble's spectrum swings around its mean) and at least 25% of its loud 25 ms frames voiced (normalised
  autocorrelation > 0.45 at a lag of 70-400 Hz). The owner's car woke on bumps: against the owner's own model,
  simulated bumps in road noise scored 0.67-0.72 (a wake). Measured: thumps, double bumps and ringing panels 0-2% in
  the band whatever the background; "عمري" (TTS) 67-78% quiet or in road noise and still 24-54% under a low rumble
  louder than the voice (where it is only 45-58% voiced, hence the low voicing bar: bangs and clicks are 0%, rattles
  up to 30% in that rumble, and rattles score 1.65-2.49 against the recordings anyway). Also applied to the enrolment
  samples, now six, and in requests without the internet (a bump before the request is skipped, not taken as it).
  A review found the first version (no background taken off, 50%/30%) would have refused the owner's voice in a
  loud rumble (checked with synthetic rumble: 30-200 Hz noise at -18 dBFS under the recordings).
* Requests without the internet (1.5.4, `OfflineCommands`): fourteen fixed requests (next, previous, pause, play,
  volume up / down / all the way, mute, unmute, music, map, home, work, time), each taught in Settings in the owner's
  voice (three times, any phrase: `EnrollDialog.teach`, files `amri-cmd-<id>.bin`, segmenter up to 2.6 s with 0.4 s
  pauses inside), matched like the wake word: the best request under 1.6 and 1.15 times better than the next. With
  Windows' Arabic voice (six requests taught with three quiet recordings): the request 0.59-1.31 quiet and in road
  noise (35 of 36), the closest other request 1.81+, other phrases 2.34+ (none taken). After the chime: Google when
  there is a recogniser and a validated network that WifiKeeper does not know to be down, else the taught requests;
  a recogniser network error falls back to them ("say it once more"). Debug: `--es teach id:a.wav,b.wav,c.wav`,
  `--es offline x.wav,...`.
* `AmriService`: a foreground service (the microphone from the background needs one) that runs the matcher; off the
  microphone while the assistant listens or answers, while Settings records, while reversing (`Gear` R: nothing may
  cover the camera) and in calls: the unit's Bluetooth calls are `com.bt.ACTION_BT_*` broadcasts (the audio mode does
  not change for them; capped at 2 min of ringing / 1 h of talking if the end is never announced), plus the audio
  mode, looked at every 2 s. It checks `Amri.shouldRun` (on, microphone, recordings, `persist.aura.disabled` not 1) at
  every start, also when Android brings it back (START_STICKY) in a process where it is no longer wanted; a failing
  microphone is retried less and less often (2 s .. 1 min). `MicLoop.quit` stops the AudioRecord (a blocked read
  returns), so two recordings never overlap. The enrolment dialog ends with Settings (lifecycle callbacks: a dialog
  whose activity is destroyed never reports its dismissal), asks again for a recording that is unlike the others
  (`WakeModel.outlier`) and says so when saving fails.
* `Amri`: after the wake word (or the home screen's microphone button) music that plays is paused, a generated chime,
  then Android's `SpeechRecognizer` in ar-SA (the Google app's, asked for by component: a head unit's default
  recogniser setting may be unset; `com.google.android.tts` is asked for by name for the answers). **The K2501
  firmware has no recogniser and no TTS engine**: Play Store, GmsCore, GSF, Gboard, Chrome and Maps, but not the
  Google app or Speech Services by Google — the owner installs "Google" from Play (and Speech Services by Google for
  spoken answers); until then every request says "no speech recognition on this device", as a plain emulator does. `Commands` parses Gulf Arabic (normalised: one alef, ه for ة,
  western digits; numbers in words incl. "خمسطعش"): next / previous / pause / play, volume up / down / to N / all the
  way / mute / unmute (`CarAudio`, the MCU volume), music / Bluetooth music / map / take me home or to work / home
  screen / time / cancel. The answer is shown (`AmriOverlay`, a pill above the dock; tap = cancel) and said in the
  app's language (`TextToSpeech`, USAGE_ASSISTANT); the music plays on unless it was paused or muted on purpose.
* Debug hooks: `am broadcast -a com.abdllh.aura.debug.AMRI --es say "ارفع الصوت الى 20"` (as if heard; push a script
  with adb and run it on the device: emu-run.ps1 re-encodes Arabic), `--ez wake true`, `--ez miclevel true` (1.5 s of
  the microphone's level: all zeros = no sound reaches the emulator), `--es rec name --ei ms 10000` (what the
  microphone gives the app, saved as files/name.wav, the listener paused meanwhile).
* The emulator with the PC's sound: the AVD needs `hw.audioInput = yes` (it was `no`: the app got pure zeros whatever
  Windows did) and `-allow-host-audio`; `scripts/emu-audio.ps1` sets both and restarts it (it then records Windows'
  default input and plays on the default output). `scripts/emu-mic.bat` (`emu-mic.ps1` + `emu-mic.cs`) opens a small
  window to choose the PC microphone the emulator hears and the speaker it is heard on, with a level meter: when
  Windows' default input is VB-Audio's "CABLE Output" it passes the chosen microphone into "CABLE Input"; when the
  default is a microphone it shows that one (the emulator hears it directly); a chosen speaker gets what plays on the
  default output (WASAPI loopback). `-Mic name -Seconds n` runs it without the window. Tests that play recordings into
  the cable must stay under full scale: clipped input reached the app 25 dB quieter and broken up.
* Requests need Google's speech recognition, which the workshop image lacks: `scripts/emu-google.ps1` starts a second
  AVD, `CarUnitGoogle` (Google Play image `system-images;android-29;google_apis_playstore;x86_64` r9, installed from
  dl.google.com's `x86_64-29_r09.zip`; Google app 9.91 = `GoogleRecognitionService`, Google TTS; no `adb root`), on
  port 5572 (`emulator-5572`) with the PC's microphone, stops the workshop emulator so only one listens, installs the
  debug build and opens Settings → Voice assistant. Checked there: wake → chime → "تفضّل…" → Google listening in ar-SA.

## Facts about the firmware (from the dump)
* No `avb` flag in the vendor fstab → no dm-verity on system/vendor/product; vbmeta uses the public AOSP test keys.
* `persist.nwd.launcher.default=<pkg>` makes the patched PackageManager return that package as HOME.
* The firmware keeps trusted apps in `AppConfig.xml` (`NwdApp`), a background-kill whitelist in `TaskWhitelist.xml`, and a
  fast-dexopt list; `aura-prepare.sh` registers Aura in all of them at every boot (idempotent).
* The stock launcher also reports "home in front" (ACTION_APP_IN_OUT, app id 4) and starts the floating volume bar, boot tip and
  assistive touch services; Aura repeats both (`system/NwdBridge.kt`), without the boot tip since 1.7 (see below).
* Volume is the MCU's, not Android's: `com.nwd.setting.service` (binder `com.nwd.setting.service.SettingFeature`,
  setAudioParam = 6, getAudioParam = 7, setMute = 10, registAudioCallback = 24), parameter 14 = system volume in
  0..`Settings.System mcu_max_volume`; current values in `mcu_system_volume` / `mcu_mute_state` (`system/CarAudio.kt`).
* ZLink (`com.zjinnova.zlink`: wireless CarPlay / Android Auto / HiCar) is woken by the firmware, not by itself: the native
  daemon `/system/bin/z-link` (started by init while `sys.nwd.support.carplay=true`, with `gocsdk_8800` on the Bluetooth
  side) runs `am start-foreground-service -a zjinnova.android.intent.action.ZLINK_SERVICE` when a phone connects; ZLink
  then takes the screen and turns `wlan0` into an access point / P2P group for the phone. The NWD setting service
  derives that property from `Settings.System phone_connect_style` (0 none, 1 HiCar, 2 EasyConnect, 3 CarPlay; with 0 it
  disables the ZLink app too) — `system/ZLinkGuard.kt` keeps it at 0 and turns it back on when the user opens ZLink.
* Wi-Fi: AIC8800 (`aic8800_fdrv.ko` 6.4.3.0, loaded by the Wi-Fi HAL without parameters: power save `ps_on=1`,
  read-only once loaded); no `iw`/`wpa_cli` on the image. The driver's cfg80211 `set_power_mgmt` does nothing (returns
  0), so Android's "power save off" (a high-performance Wi-Fi lock, `setPowerSave`) is ignored: the chip always dozes,
  which the owner's PC and TV on the same iPhone hotspot do not. Checked in the firmware and found harmless: NWD's
  auto-sleep turns airplane mode on and wake-up off (every ACC cycle reloads the Wi-Fi), the BT module's "close Wi-Fi
  in calls" is for K20/K22 platforms only (this is K25), SystemUI's screen-off Wi-Fi switch is disabled, nothing in
  /system watches connectivity and toggles the Wi-Fi.
  `system/WifiKeeper.kt` holds a high-performance Wi-Fi lock (useless on this chip, see above), probes the web every 20 s through the
  Wi-Fi network and, when it fails, tells apart a dead link (the phone/gateway does not answer), a phone that passes
  nothing (its DNS or gateway answers, the internet does not), DNS trouble and blocked web; it restarts the Wi-Fi for
  the first three (root `svc wifi`, detached with `nohup`; the WifiManager calls are refused to an app targeting
  Android 10) with backoff (1, 2, 4, 8, 16 min), asks Android to re-validate when the web is back, and keeps a log
  (`files/wifi-log.txt`) shown in Settings → Vehicle & system. The owner's phone is an iPhone: its Personal Hotspot keeps
  a client associated, DNS answering, while it stops passing that client's traffic until the client reconnects (the
  owner fixed it by hand by turning the Wi-Fi off and on), so "passes nothing" is restarted too. Since 1.5.3 the check
  also covers IPv6 when the network has a global IPv6 address and default route: the iPhone hands out its cellular
  prefix, apps try IPv6 first, and an IPv6 that stops (a new cell, a new prefix) left them waiting while the IPv4-only
  probe passed — no restart, the owner did it by hand. Both probe pages are asked over IPv6 and judged together (one
  of them, msftconnecttest, has no IPv6 address: taken alone as "fine", it hid every failure in the first version):
  fine when one answers over IPv6 or none has an IPv6 address; else cause V6, restarted like the others (emulator test: `setprop debug.aura.v6test 1`
  plus `ip6tables -I OUTPUT -p tcp --dport 80 -j DROP`). Each outage first logs a root snapshot `diag`:
  `ip=… gw=<gateway ping> net=<1.1.1.1 ping> v6=<global IPv6 addresses> v6net=<IPv6 ping, - without IPv6>`.
  The owner's log (1.5.3) then showed the real pattern: about once a minute `LINK` with `gw=0 net=0 v6=0` (the iPhone
  itself silent, a strong signal), cured each time by the re-association. Since 1.5.4 a heartbeat thread connects
  every 1.5 s to port 53 of the gateway or the DNS server on the same subnet (whichever answered first on that
  network: it only "arms" then, so a router that ignores it is never touched, and an outside DNS server such as
  8.8.8.8 never makes it an internet check); three silences in a row (~5-8 s) reconnect at once: a re-association,
  or Wi-Fi off/on when the one before did not hold two minutes; at most every 15 s and six times in ten minutes
  (then the 20 s probes and their pauses decide), instead of waiting for two failed 20 s probes; the steady traffic may also keep the chip's power
  saving from letting the link freeze. `nextFixAt` is reset when the web is back (one outage's pause held back the
  next one's cure by 31 s in that log). Emulator test: `iptables -I OUTPUT -o wlan0 -j DROP` until the reconnect
  (caught after 8 s, against 23 s by the probes).
* Two floating circles, both drawn in the stock launcher's process (`system/AssistiveBall.kt` turns both off once for the
  owner, marked done only after it went through as root, and offers the switch in Settings → Display & sound; on = the
  CAN menu back):
  - The CAN float menu, a circle with the car's controls: `com.nwd.can.setting.service.CanService` →
    `CanUiOperationManager.initCanFloatMenu` → `CanFloatMenu`. Shown while `Settings.System
    car_config_can_float_menu_display` is 1; choosing or resetting the car in the CAN settings sets it to 1
    (`CanConfigUtil.resetCarConfig`), which is how it appeared for the owner. Broadcast
    `com.nwd.action.CAN_FLOAT_MENU_SHOW_ACTION` (dynamic receiver, no extras) makes the menu read the setting again and
    show or close itself.
  - The assistive touch, `com.nwd.fushion.assistivetouch.SuspensionService` (started at boot; AMRI starts it too,
    `NwdBridge`): reads `key_white_window_state` once in `onCreate` and adds its dot only when it is 1. The car settings'
    "Assistive touch" option sends `com.nwd.action.suspension.HIDE_THE_LISTVIEW` (removes the dot, writes 0) or
    `DISPLAY_LISTVIEW` (opens the side panel). Stopping the service removes the dot and both panels (`onDestroy`).
    1.4.1 switched only this one, so the owner's circle (the CAN menu) stayed.
* Apps a dealer installed on `/data` may make themselves device administrators, which blocks their uninstall
  (`DELETE_FAILED_DEVICE_POLICY_MANAGER`). `system/AppRemover.kt` disables the admin receiver (`pm disable '<component>'`,
  quoted: an inner class's `$` would be expanded by the shell; disabling it drops the admin) and uninstalls again; then
  `--user 0`, then `disable-user`. The app list's long press offers it for apps
  that are not part of the firmware; a launcher called "Vivid" that the owner asked to be removed goes once, by itself.
* The stock floating volume bar is `com.android.launcher/com.launcher.FloatBar` (started by KernelService at boot, from
  `UartConfig.ini`); it pops up for every `notifyAudioParam` of the setting service (14 media, 15 navigation, 16 phone),
  Aura's own changes included. `system/VolumeHud.kt` disables that component (root `pm disable`, once; allowed to draw
  its own overlay through `appops SYSTEM_ALERT_WINDOW`) and shows Aura's display for changes Aura did not make: wheel /
  panel / CAN buttons are announced first by `com.nwd.action.ACTION_KEY_VALUE` (byte `extra_key_value` 14 up, 15 down,
  2 mute). The installer's restore enables the component again; `Settings.System isVolumeTouch` = 1 would also silence it
  (observer only, must change after the bar connected).
* Bluetooth music has no cover art in the BT module; `music/CoverSearch.kt` finds it by title + artist (iTunes Search
  API, Deezer), matching Arabic-script names against the catalogues' Latin spellings by their consonants.
* Power belongs to the MCU. Apps reach it with broadcast `com.nwd.action.EMU_SEND_DATA_TO_MCU` (byte[] `protocal`,
  written verbatim to the MCU UART by KernelService, no permission); frame `F0 LEN TYPE SUB 00 data… SUM` (`LEN` = data
  + 3, `SUM` = low byte of the sum of everything after `F0`). The factory option `sleep_power_off` is `7B 1F [mode, h]`:
  0 sleep (stock), 1 power off at ACC off, 2…6 sleep then power off after 0/2/24/48/72 h (K25 → T5 sub-platform → the
  new 7-mode scheme). The setting service sends the mode from `/data/nwdappconfig/app/FactoryConfig.ini`
  (`sleep_power_off=`) again at every boot, so `system/Power.kt` writes it there too (root, original kept as
  `FactoryConfig.ini.pre-aura`, which the ROM restore's `*.pre-aura` loop puts back) and re-sends it once the boot has
  settled. The MCU's ACC-off test command is `C0 02 [0]`. Android's own shutdown is no use while ACC is on:
  `ro.recovery.mode=mcu`, and the firmware's reboot watchdog powers it back.
* Gear: R from the reverse wire (`Settings.System mcu_backcar_state`, broadcast
  `com.android.action.ACTION_BACKCAR_STATE_CHANGE`). P/R/N/D only from the CAN app (com.nwd.can.setting): its exported
  CanService binder `com.nwd.can.sdk.outer.adil.ICanRemote4OuterFeature` — `initSdkCfg` (2: "nwdapp" + the CAN app's
  key for NWD apps) then `addCarInfoCallBack` (17); car info then comes as `onDistributeCanData` (1), the frame
  `6E 02 71 <113 bytes> FF` with the gear at byte 74. Never register `addCanCarInfoCallBack` (27): it switches the CAN
  app to CarInfo objects (`onDistributeCarInfo`, 2) for every client, and the stock ones only read frames. The CAN app's
  version names are build dates: "v.24…" in /system has no gear; "v.26.04.09A" ships in the `carconfig` partition
  (`app/.app/CanAllInOne.apk`, installed over it by the firmware). Its parsers fill the gear only for some boxes:
  `RaiseCommonCanProtocalUtil` (Raise: 1 P, 2 R, 3 N, 4 D; reached through the generic Raise protocols 2E / FD that
  `AbsCanFactory` builds), Hiworld for Haval / Dongfeng / Hyundai / WUE, bba_general (BMW, Benz, Audi boxes), VinFast.
  The Changan managers (binary, daojun, hiworld, oudi, raise, xinbas, …) do not call them directly; which path a car
  takes is only certain on the unit. Raw box frames are not handed out (`addCallBack4Outerface`'s list is never broadcast; `addCanDataCallBack` gets
  only the CAN app's own non-6E messages). The CAN app's setup is `Settings.System can_config_app_cartype_json` (its
  CanConfig as JSON: `canProviderName`, `carBandName`, `carTypeName`, `carYearName`, `canManagerClassName`); Settings →
  Vehicle & system shows the box, the car and whether a gear came. Without the CAN gear: D while driving (GPS) and held
  at a stop after it for up to 3 minutes (drawn lighter) unless R. `system/CanGear.kt`, `system/Gear.kt`.
* What is in front: the framework writes every activity that resumes to `Settings.System activity_starting` as
  `package,class` (`NwdPackageChecker.checkResumedPackageName`); KernelService observes it (`SourceMgr$6`: `key_top_app`
  / `key_top_cls`, `ACTION_START_NWD_ACTIVITY` / `ACTION_START_ACTIVITY` with `extra_package_name` /
  `extra_class_name`). `system/Front.kt` reads it too (a state: a process that starts late still sees what came up
  before it) and keeps AMRI's own screen in front or last in front (`own`, `ownLeftAt`).
* What the firmware puts in front when the unit comes on (the owner saw "the old system" until he pressed something):
  - Not the stock launcher: `SourceMgr.startApp` sends app 4 (ApplicationList.xml: `com.android.launcher` /
    `com.android.launcher2.Launcher`) as a plain home intent when `isHomePage` (package starts with
    `com.android.launcher`, class ends with `Launcher`), and `PackageManagerService.chooseBestActivity` answers a MAIN +
    HOME intent with the package in `persist.nwd.launcher.default`. `ro.nwd.start.launcher.by.fw=true`: the first
    start is left to the framework.
  - The source that played when the ignition went off: `SourceMgr.accOn` → `resumeSource` →
    `SourceKeeper.getKeepSource` (`key_appid`) → `startApp` with the **component** from ApplicationList.xml: the stock
    radio (8), video (3), AUX (9), TV (11), Bluetooth music (15, `com.nwd.bt.music/.home_horizontalActivity`), music (2).
    `keepCurrentSource` keeps app 4 only when no audio source is active. `UartConfig.ini`
    `key_acc_on_not_need_start_app=1` would stop it, and the home intent at ignition-off with it.
  - `system/HomeGuard.kt`: for 12 s after `com.nwd.action.ACTION_MCU_STATE_CHANGE` says on (byte `extra_mcu_state` 1;
    0 / 2 / 3 are off) and until the unit has been up 90 s after a boot, the first stock source screen or stock
    launcher screen that takes the front is covered by AMRI's home screen (the source plays on: the same as Home
    pressed on it); the stock music screens go to `MusicKeys` at any time, now also when their broadcasts were
    missed (AMRI's process started after them: `Front` 1.5 s after the process starts); the stock home screen by name
    is always covered; a screen AMRI opened itself in the last 8 s is left alone. Settings → About shows the last
    screen covered. Emulator: make AMRI the home app (`cmd package set-home-activity`), `am broadcast -a
    com.nwd.action.ACTION_MCU_STATE_CHANGE --ei extra_mcu_state 0`, then `1`, start another app and `settings put
    system activity_starting com.nwd.radio,x`.
  - The stock boot tip (`com.nwd.fushion.boot.tips2.BootTipService`, started by the stock launcher's `onCreate` and,
    up to 1.6, by `NwdBridge`): by `FactoryConfig.ini key_boot_menu` it puts `/cache/boot_logo.bmp` over everything
    from ignition-off until 7 s after ignition-on or a tap (1), or a notice over the stock wallpaper after a boot
    (2), plus a language page at the first start and handbrake / MCU-version warnings. Stock is 0; the unit's copy in
    `/data/nwdappconfig/app` is not known. AMRI no longer starts it, and stops one an earlier AMRI started.
  Which of these the owner saw is not known (nothing was read off the unit); all are closed.
* The backlight: `com.nwd.action.ACTION_SET_BACKLIGHT_SWITCH`, boolean `extra_backlight_switch` (the setting service's
  `AbsBackLightManager`; off until the panel is touched). The stock launcher's "screen off" icon sends it and never
  opens its own `com.nwd.screenoff.ScreenOffActivity`, which is an empty activity; AMRI's screen-off buttons opened
  that activity up to 1.6 (an empty stock screen stayed in front) and send the broadcast since 1.7.
* The status bar is hidden with the framework's own `Settings.Global policy_control`
  (`immersive.status=*`), which Android 10 still honours (`system/SystemBars.kt`); swiping down from the top shows it.
* Boot animation: NWD's player (`libbootanimation.so`, `findBootAnimationFile`) checks `/cache/bootanimation.zip` first,
  then `/system/config/app/bootanimation.zip`, then the stock AOSP places (`/apex`, `/product`, `/oem`,
  `/data/local`, `/system/media`). The `/cache` file is the firmware's "dynamic logo": the factory setting and the
  broadcast `com.nwd.ACTION_THIRD_APP_SET_DYNAMIC_LOGO` (`extra_path`) copy a zip there, its factory reset deletes it,
  and `bootanim.rc` runs the player with the `cache` group. The cache partition is 1.37 GB and held no animation in the
  owner's dump (only the static `boot_logo.bmp`, which is left alone). So the ROM puts AMRI's animation in `/system/media`
  and `/system/config/app`, and the app (`system/BootAnim.kt`) copies the one in its assets to `/cache` as root once
  per animation (MD5), keeping a file that was there as `bootanimation.zip.pre-aura` and its own MD5 in
  `/cache/.aura-bootanim`; the installers' restore deletes Aura's file (only while it still has that MD5) and puts the
  old one back. Zips must be STORED; `rom/tools/gen_bootanim.py` makes it (the car turning in, then the AMRI OS logo
  from `design/logo/`, palette PNGs with 16 entries kept for the logo blue).

## Arabic font (`design/fonts/`)
Arabic UI text is Readex Pro (OFL), a variable font: `ui/Fonts.kt` takes real weights from the one file
(`Typeface.Builder` + `'wght'` 300 / 400 / 500 / 650); Latin text stays on the system Roboto. Upstream declares ascent
1000 / descent 250 while the Arabic reaches about −550…1150, and the app's labels use `includeFontPadding = false`, so
Android cut the bottom off (ي lost its dots). `design/fonts/make_arabic_font.py` writes the asset from the Google Fonts
file with ascent 1150 / descent 560 (nothing else changed; renamed "Readex Pro AMRI" as the OFL asks).

## Logo and icon (`design/logo/`)
`amri-os-en-source.png` / `amri-ar-source.png` are the owner's artwork (white and blue on black). `make_assets.py` takes
them off the black (alpha = brightest channel), writes the transparent logos, the app's `drawable-nodpi/logo_amri_*`
(white for the dark theme, `_ink` for the light one) and traces the "A" with its blue dot for the vector launcher icon
(`ic_launcher_foreground.xml`, from `a_glyph.txt`).
