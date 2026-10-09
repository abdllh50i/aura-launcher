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
`rom/flash/aura-flash.sh` applies with full hash verification on the unit. The pack also carries per-range hashes and a hash of
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

### The 3D car (`tools/car3d`, debug-only `CarBakerActivity`)
The car is not rendered live (the source model has ~726k triangles and 8K textures; the unit has a Mali-G31). Instead a
turntable is pre-rendered once on the emulator's GPU and shipped as 90 WebP frames (4° apart, 640×360 with alpha, ~1.7 MB):
1. `fbx2mesh.py model.fbx OUT --front -x` → `mesh.bin` (positions, normals, GL UVs, indices; binary FBX parsed by `fbx_binary.py`),
2. `prep_textures.py` → `basecolor.jpg` + `rm.png` (roughness/metalness), `find_plates.py OUT` blanks the licence plates,
3. `bake.ps1 -Out DIR -Params "frames=90`nss=4..." -Install -PushMesh` runs `app/src/debug/.../CarBakerActivity` (GLES2:
   studio environment, GGX specular, clear coat, ambient occlusion from 64 depth maps, soft floor shadow, 4× supersampling),
4. `pack_frames.py DIR app/src/main/assets/car --default 80` → `f_NNN.webp` + `car.json` (frame size, resting frame, boxes).
At runtime `ui/CarFrames.kt` decodes frames on two worker threads into a small LRU (bitmaps are reused), and `ui/CarStage.kt`
turns them with drag, fling, snap and a spring back to the resting view (mirrored in RTL). The model itself is not in the repo.

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
* Wi-Fi: AIC8800 (`aic8800_fdrv.ko`, power save on by default: `ps_on=1`, `dpsm=1`); no `iw`/`wpa_cli` on the image.
  `system/WifiKeeper.kt` holds a high-performance Wi-Fi lock (no power save) and reconnects a Wi-Fi that stays
  connected without internet.
* Bluetooth music has no cover art in the BT module; `music/CoverSearch.kt` finds it by title + artist (iTunes Search
  API, Deezer), matching Arabic-script names against the catalogues' Latin spellings by their consonants.
* The status bar is hidden with the framework's own `Settings.Global policy_control`
  (`immersive.status=*`), which Android 10 still honours (`system/SystemBars.kt`); swiping down from the top shows it.
