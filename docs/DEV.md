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

## Aura Maps (`nav/`)
MapLibre Native **10.3.7** on purpose: it renders with OpenGL ES 2.0, which is what the unit's firmware declares
(`ro.opengles.version=131072`); 11.x and later need ES 3.0. `MapStyle.kt` builds the style JSON (OpenFreeMap vector tiles and
fonts, dark/light colours, 3D buildings, labels in the UI language); `AuraMap.kt` wraps `MapView` (camera modes follow /
navigate / free / overview; the car and the destination pin are plain Views moved to their projected screen points each
camera frame — a tilted symbol layer rendered black on the emulator's GPU). `MapGuard` marks each map start on disk: after two
starts that never drew a frame (a native crash cannot be caught), the home screen falls back to the static `MapBackdropView`.
Search: Photon (`Places.kt`, type-ahead with a generation counter, Home/Work/recents in prefs). Routing: OSRM (`Router.kt`,
steps placed on the polyline). `NavSession.kt` snaps fixes to the route, reroutes after three off-route fixes (at most every
8 s), speaks Arabic/English prompts (TTS) and runs `NavService` (foreground, type location) with the next manoeuvre.
Debug builds: `MapsActivity --es mock_loc "lat,lon,bearing"` and `--ez simulate true` (drives the route); the CarUnit AVD
needs `hw.gps = yes` for `adb emu geo fix`. A release build can be tried on the emulator with `-PwithEmulatorAbi`.

### Offline map (`nav/offline/`)
Settings → Navigation → Offline map downloads the Eastern Province once (`OfflineMaps.kt`, resumable, waits out internet
drops, resumes after a restart): BRouter's routing data `segments4/E45_N25, E50_N25, E45_N20, E50_N20.rd5` (brouter.de,
~26 MB, lon 45–55 / lat 20–30), the Noto Sans glyph ranges of the labels, and every OpenFreeMap tile of `Region.kt` (street
level, zoom 14, over the populated band Jubail–Dammam–Khobar–Qatif–Abqaiq–Al-Ahsa and Hafr Al-Batin, Khafji, Qaryat
Al-Ulya; zoom 11–12 over the roads to Kuwait, Riyadh and Salwa; zoom ≤10 over the whole province: ~20 800 tiles), then
zoom 13–14 tiles along the motorways/trunk/primary/secondary roads of those zoom-12 areas (`Region.roadTiles`, found in the
tiles themselves), then builds the search index. All in `files/offline/map.db` (SQLite, WAL: tiles as sent (gzip), glyphs,
`places`). About 150 MB.
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
  assistive touch services; Aura repeats both (`system/NwdBridge.kt`).
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
  probe passed — no restart, the owner did it by hand. Probe pages without an IPv6 address count as fine; a page with
  one that does not answer over it is cause V6, restarted like the others (emulator test: `setprop debug.aura.v6test 1`
  plus `ip6tables -I OUTPUT -p tcp --dport 80 -j DROP`). Each outage first logs a root snapshot `diag`:
  `ip=… gw=<gateway ping> net=<1.1.1.1 ping> v6=<global IPv6 addresses> v6net=<IPv6 ping, - without IPv6>`.
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
