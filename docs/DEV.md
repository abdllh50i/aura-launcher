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
`BuildConfig.VERSION_NAME`; the downloaded APK must carry the same signing certificate and a higher versionCode. **From branch `v2-app` on, build with `-PappVersionName=2.0.0-beta.1`** (versionCode 2000001): it is the map pack's `minApp`, a lower code refuses the pack, and the guard acts from it on (see "The guard" for the emulator).

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
nothing of the stock image is overwritten or freed. The config edits (`build.prop`, the four NWD XML files) do give their
few old blocks back; ext4 allocates a file's blocks only at writeback, so `apply.sh` writes every new file back (`sync`)
before those edits (without it, 1.15.0's second boot animation landed on exactly those blocks). The pack also carries per-range hashes and a hash of
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
`home/HomePanel.kt` lays out `CarPanel` (clock, the car, quick buttons), `MapPanel` (live map, "Where to?", Home/Work,
the `MediaCard`, the Maps screen's guidance sign in its compact form while navigating), the `Dock` and two bottom sheets (`ControlsSheet`, `AppDrawer`,
both on `ui/Sheet.kt`). Colours are theme tokens in `ui/Palette.kt`; `ui/Theme.kt` resolves dark / light / auto (light between
an estimated sunrise and sunset) and a theme or accent change rebuilds the views in place behind a `PixelCopy` snapshot that
fades out. HomePanel forwards its lifecycle to the embedded map; the map is capped at 30 fps and only draws when it changes.

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

### Home screen in 1.12
It follows "Design system (1.12)" throughout: type roles, `Radius` / `Space` / `Elev` / `Touch`, the `Motion` helpers,
`accentFg` for accent text and icons, `Snack` through `HomeHost.toast`.
* **Layout.** The map panel has one row along its top: "Where to?" (a 56 dp pill), then Home and Work as 56 dp round
  glass buttons (icon only, the name as the content description; `accentFg` when the place is set, `text3` when
  not). The player spans the panel's width along its bottom, 88 dp high (capped at 460 dp until 1.11, the song's name
  got ~150 dp; now ~290). The street's limit (`RoadHud`) and "a camera here" (`CameraMark`) share one column at the
  top end, so the camera button moves up when there is no sign. About 300 dp of map shows between the row and the
  player, 30 % more than 1.11. While navigating, the row's place is taken by the compact guidance sign
  (`GuidanceBanner`, see Aura Maps), which reads as the Maps screen's own.
* **The dock** is a row: controls button, the tiles centred in what is left between it and the volume, then volume and
  settings. Tiles have a fixed width (78 dp, 76 with names), so the most there can be (`DockPins.MAX` pins plus Apps,
  594 dp) always fits; centred on the whole bar, as until 1.11, five or more ran under the volume capsule. The clock
  (Roboto Light, `DISPLAY_L`) and the controls button share a 20 dp start edge; the settings button lines up with the
  map panel's 12 dp end margin. The Apps tile is a deliberate dark neutral (ink on the light theme), not the slate
  grey that read as disabled.
* **Shadows.** None on the dark theme (`lift()`), where they could not be seen and were redrawn with every map frame.
  On the light theme: the map panel `Elev.REST` plus a `stroke` hairline, the row's pills `REST`, the player `FLOAT`.
  The dock has a 1 dp `divider` on its top edge instead of a full-width shadow; its app tiles are flat. There is one
  full-screen fill: the top panel's own room, since 2.0's step 2, when the window lost its background (`ui/BackdropView`,
  a second fill of the same colour, is gone from home and Settings, and deleted).
* **Entrance.** Everything but the car settles in about half a second (it was ~1 s): header at 0 ms, the map row's
  three parts at 80/110/140, the player at 140, the quick buttons at 160, each `View.enter`. The map panel only moves
  (16 dp, `LONG`), with its picture: the live map comes later, under the window, where nothing moves it. The dock
  rises with its tiles 25 ms apart, translation only. The car's spin is 1400 ms (was 1900) from 100 ms.
* **Start-up.** Nothing heavy competes with the entrance any more:
  * The live map is built by `MapController.startForHome()`, 700 ms after the entrance starts. Until then, and until the map
    has drawn, its last picture shows in the card, and the wall has no hole yet (see "One engine"; Aura Maps, "The home map's start").
  * The CPU path's decoding threads run at default priority (they were above the UI thread). `CarFrames.warmUp`
    only runs when the GPU cannot draw this start's spin (no frame file known yet, or the GPU failed): see "Memory and
    start-up cuts" below. Until 1.19 it decoded the whole spin at every start.
  * `CarStage.playIntro` never starts the spin without a frame: with the frame file there it waits for the GPU's first
    frame, the car faded out; on the CPU path for the spin's first frame or the GPU (`introReady`, from the frame
    listener, the GL `onReady` and attach), at most 400 ms, its delay still counted from the call. A touch during the
    wait shows the car at once.
  * The app list is read, the drawer's cells built and their icons loaded 2.5 s after the entrance (`AppDrawer.prewarm`).
  * `MapGuard`'s start mark is a file, not a preferences `commit()` (see Aura Maps).
* **The car's background.** `CarEtc` has no copies of the theme backgrounds: `CarStage` passes `Palette.bg` to
  `CarGlView`, and the frame file's hash includes it, so a new background colour rebuilds the file on its own. The car
  must stand directly on `Palette.bg`, never on a card. The GL path looks its uniform locations up once, sets the
  constant uniforms once per context and refills the quad only when the view's size changes.
* **Returning home.** The dock is rebuilt only when what it shows changed (`Dock.refresh()` compares the pins and "names
  under icons"; otherwise it only syncs the volume and the update badge). Until 1.11 every return asked the package
  manager about every pin and laid the dock out in the frames of the window animation. A pinned app installed or
  removed anywhere reaches `Dock.rebuild()` through a package receiver registered for the activity's whole life
  (updates, `EXTRA_REPLACING`, are ignored).
* **Sheets.** An app opened from the drawer, or from a Controls tile, opens over the sheet; the sheet closes in
  `HomeActivity.onStop` (closing it at the same time made the sheet, the window animation and the app's start compete); a
  panel, which would open under it, closes it at once. A drawer under a translucent window closes after 1 s once the window lost focus; the Controls sheet stays
  open under a translucent panel (the Wi-Fi panel), which brings the driver back to it. The drawer is INVISIBLE when
  closed, not GONE (it overrides `setVisibility`), so its laid-out grid is kept; icons load at the size shown (68 dp);
  on open the grid rises once as one layer (12 dp, 60 ms after the sheet), and an unchanged list is not bound again.
  It leaves out AMRI itself and names Aura's Maps and Music in the UI language (`AppRepo.clear()` on a language
  change). Controls tiles have a `stroke` hairline on the light theme's white sheet.
* **Brightness and volume.** The Controls slider writes the brightness at most every 50 ms and its last value on
  release; `Controls.setBrightnessPercent` writes `SCREEN_BRIGHTNESS_MODE` only while it is not manual yet (it wrote it
  with every value: two provider calls per step of a drag). Volume is the car's own step number everywhere (dock,
  Controls, Settings, the volume display), as the steering-wheel keys move it.
* **Player.** The play button is an accent disc with `onColor(accent)` (the white disc was the brightest thing on the
  night screen). Previous and next are dimmed (0.35) and disabled while nothing plays; their buttons declare no
  overlapping rendering (the ghost background is clear when disabled), so the dim costs no offscreen buffer on every
  frame of the live map. A new song's names come in from the side of the button pressed (next from the right, in every
  language, like the transport row) within 3 s of the press, else rising; the movement follows the title only
  (Bluetooth sends title, artist and cover separately). A new song without a cover keeps the old one dimmed for up to
  1.5 s. App names for the source line are cached.
* **Small things.** A tap on the map shows the press tint (a drawable state). The gear's
  letters take their lit colour by how much of the sliding circle covers them, frame by frame, with one weight, so a
  shift only redraws. Both sheets' close buttons are 56 dp.
* Checking it: force-stop and start `.home.HomeActivity` for the entrance; pin six apps (long-press in the drawer) and
  switch "names under icons" for the dock's width; the Bluetooth debug broadcast twice with different titles, after
  tapping next, for the song change.

### One way to the screens (2.0 step 0: `home/Screens.kt`, `system/Front.kt`)
`Screens` is the only code that starts AMRI's own screens: `home`, `maps` / `mapsSearch` / `mapsGo` / `mapsPick` / `mapsPlace`, `music`
/ `musicOn`, `settings`, and `pending` for the two notifications. A caller says which screen and with what, not how it
is reached. Today `open` starts the activity with the flags each caller used to set (Settings from an activity stays
that activity's next step in its task); when a screen becomes a panel of the one window of 2.0, only `open` changes.
Nothing looks different. A shared map controller and `Trips` are not part of this step.

**Why Home never goes by name.** Only a start that names no component makes a home-type activity
(`ActivityRecord.setActivityType`: `(!componentSpecified || canLaunchHomeActivity) && isHomeIntent`; AMRI's uid may not
name it, and `isHomeIntent` asks for MAIN, one category, no data and no type, so a package, flags and extras are all
allowed). By name, with no home activity alive, it becomes an ordinary activity in a standard stack, not the home one.
Any other app over it, then the Home key: nothing of the home type to bring back (`hasCompatibleActivityType`), so the
system builds a second one, with a second map and a second car, until the next power-on. Measured on 1.19.0 with AMRI
the real home: force-stop, by name, another app, Home key gives 2 instances, 2 window roots, 404 views against 223, PSS
123 MB against 82 in that run (PSS moves with what the maps have drawn by then). Four things walked into it: a start by name
from outside, `HomeGuard` in the very case it exists for (the stock launcher got the first home start),
the music key's second press when Music was the first screen, and `dev-install.ps1`, so a session tested after
`dev-install` was not running a home-type activity at all. `BootHome`'s fallback made the same kind of start.

So `Screens.home(c, from)` is a home intent with `setPackage` and two extras: `panel` ("home" until there are panels to
ask for) and `from` (who asked, for the log; nothing depends on it). Voice and the updater already used this shape on
the unit, without the extras; the firmware's `isHomeIntent` allows extras, tried on the emulator only. Around it:
* **AMRI not the home app** (`Device.isDefaultHome` false: the kill switch, before it is chosen): the one by-name start
  left. No home start finds AMRI then, so there is no trap, and a home intent would itself build a second activity beside
  the ordinary one a launcher's app list may have started. `HomeGuard` only gets here as the home app.
* **A start by name from outside** (adb, an app list; no home category) while AMRI is the home app: with an instance
  alive `singleTask` hands it over and nothing is built, as before. With none alive `HomeActivity.onCreate` asks
  `Screens.homeByName`, which finishes it and sends the home intent, in this order: while it is on top a home intent is
  only delivered to it. So `am start -n .home.HomeActivity` in scripts and in these notes still works, and is now harmless.
* **`BootHome`** still sends the plain home intent. Unlocked with that intent finding itself for 6 s (it used to start
  the class by name here): the same intent limited to its package, where `HomeActivity` comes first by priority. Nothing
  there either by 40 s: the stock launcher by name, as when the user stays locked. By AOSP's code a by-name start would
  find nothing more anyway: the package manager filters a name by the same user state.
* **A second instance can still be made from outside** (`su 2000 am start -a android.intent.action.MAIN -c
  android.intent.category.HOME -n .../.home.HomeActivity`: by name but with the category), or by making AMRI the home app
  while an ordinary instance runs. `HomeActivity` counts its instances; `Screens.homeBuilt` throws in a debug build, and in
  a release build writes a line in Settings → About ("Home screen, last unusual start") and goes on as before. The same
  line tells of the by-name case and of `BootHome`'s last resort, because nothing here could be tried on the unit first:
  a healthy unit has no such line.

Walked before and after on the emulator with AMRI the real home, 73 steps (Home key, Back, Maps, Music and Settings from
the dock, the map, the drawer, the sheet and the voice button, another app and back, the pick from Settings, voice, the
music key's first and second press, the stock music screen, `HomeGuard`, a trip and music with their notifications, an
update, the language): the same screen and the same one home-type instance in every step, so step 0 changes nothing
there. What changes is the trap above: its sequences, `dev-install`, `HomeGuard` with no Home alive, the music key with
no Home alive and `BootHome`'s fallback ended in 2 instances and now in 1. Right after a by-name start the finished
record can still be listed for a moment beside the new one (no window, gone by the next look).

`Front.panel`, `setPanel` and `ownIs(name, withinMs)` are the same idea for "which screen is in front". `panel` is set when
an activity resumes (`Screens.panelOf`: for the shell, its top panel) and by the shell at every switch. There is no
`ownIs(Class)` any more: a screen that is a panel never resumes as its class, so it would have compiled and never been
true. `MusicKeys` asks by name.

Checking it on the emulator (Android's own rules, not the firmware's: `persist.nwd.launcher.default` does not exist there):
* `cmd package set-home-activity com.abdllh.aura/.home.HomeActivity` once, or the Home key goes to Launcher3.
  `dev-install.ps1` starts Home through a home intent whichever is chosen.
* Count with `dumpsys activity activities | grep -E 'Stack #|Task id|Hist #'`. `Activities:` in `dumpsys meminfo` counts
  objects not yet collected: it stays at 2 for a while after Back.
* `BootHome`: with it on, a chosen home is dropped at once ("Result set changed, dropping preferred activity"),
  `set-home-activity .home.BootHome` does not take (the record it leaves names `HomeActivity`), and a reboot ends in the
  chooser (`home after 15426 ms: android/...ResolverActivity`). For a boot like the unit's, `pm disable
  com.android.launcher3` first: the answer then goes by priority, `BootHome` (−900) over FallbackHome (−1000) while locked,
  `HomeActivity` (0) once unlocked. `pm enable` and `set-home-activity` again afterwards.
* `BootHome`'s last resort cannot come up by itself there: `setprop debug.aura.boothome 1`, start the app, then `am start -n
  com.abdllh.aura/.home.BootHome --ez behind true` (debug builds; the plain answer is taken as nothing found).
* The music key's first and second press: `am broadcast -a com.nwd.action.ACTION_KEY_VALUE --ei extra_key_value 83`.
* After the debug assertion the Home key shows the chooser: stock Android clears the chosen home when a non-system home
  process crashes. `set-home-activity` again. The note written just before the throw is lost with the process in a debug
  build (`apply()`); a release build does not throw.

### One window (2.0 step 2: `home/HomeActivity.kt`, `Panel.kt`, `PanelStack.kt`, `PanelMotion.kt`, `ShellRoot.kt`, `Entry.kt`)
`HomeActivity` is the shell: one window, and nothing of any screen in it. `ShellRoot` is its content, in layers: `mapHost`
(the map's slot, see "One engine" below: always attached, always visible, never follows the keyboard), `panelLayer`, `chrome`
(empty until the new design), `sheets` (Controls and the drawer, the same classes). `setContentView` runs once: a new theme
rebuilds what is in the layers. The home screen moved unchanged to `HomePanel`, the bottom of the stack; Settings to
`SettingsPanel`, the first panel above it, built on open, dropped on close. Maps followed at step 3 and Music at step 4 (both below). Nothing
looks different; a screen that is a panel opens in the window, with no activity start.

**A panel** (`Panel`) is a `ContextWrapper` of the activity, so code moved from an activity keeps working on `this`, and its
callbacks are the activity's under the same meaning: `onShow(args)` / `onHide` were onStart / onStop, `onResume` / `onPause`
the same, `onArgs` was onNewIntent, `onBack`, `onPermission`, `onTick` (the shell's one `TIME_TICK` receiver), `onLook`,
`onTrimMemory`, `onDestroy`. `PanelStack` keeps them in pairs. Under another panel a panel has had `onHide`, and is INVISIBLE
once the move over it has ended (laid out still, so coming back runs no layout; a covered tree is drawn on every frame
otherwise). `isGone`: dropped or window gone, the guard for a late answer. `isTop`: still in front. `finish()`,
`requestPermissions(perms, code)` (the answer goes to whoever asked, and not once it is gone), `runOnUiThread`, `window` have
the activity's names. What needs the real activity takes `shell`: `EnrollDialog`, and the home screen's views, built on
`shell` itself (`CarGlView` asks its context for the window).
To add one: a line in `PanelStack.make`, its name in `Screens.PANELS`, its old class becomes `class X : Entry(name)` with
`Theme.NoDisplay`, `noHistory`, `excludeFromRecents`, `taskAffinity=""` in the manifest. `ProbePanel` (src/debug) is the
smallest there is.

**How it opens.** `Screens.open`: with the shell resumed and on the main thread, `panels.show(name, args)` in place.
Otherwise a home intent limited to the package with the panel's name and the args as extras, which brings the shell forward
and clears what is above it, as the Home key does. `resumedNow`, not `startedNow` as `home-shell.md` 6.7 proposed: a shell
started and only paused has something over it (an Entry, always; a translucent panel of Android's), and a panel shown in
place would open under that. `onNewIntent` with no panel named (the Home key) is Home, and pops everything. `Entry` is what is
left of an old class name: no window, forwards through `Screens` and finishes in `onCreate` whatever happens. With no shell
alive that is the home intent, so a by-name start makes a home-type activity and never the second instance of "One way to the
screens"; walked with AMRI the real home: by name with none alive, then another app, then the Home key, one activity, in the
home stack. `Screens.homeByName` carries the panel's name and args along. AMRI not the home app: the same, in an ordinary stack.

**The stack never takes the home screen down.** A panel that cannot be built, or throws from a callback the stack calls, is
dropped: the one under it comes back, a Snack says so and Settings → About keeps the last one ("Screen that last failed to
open", prefs file `aura_shell`). The home panel's own failures go through, since that is what the crash guard counts and
answers. A move ends by itself at `MOVE_LIMIT_MS`, and the state at rest is reached from one function (`settle`), wherever a
move stopped; no move runs while the shell is not resumed (`paused()` settles, a panel chosen while stopped is just there),
nor with the owner's "Animated screen changes" off. A touch on a panel that stops being on top hears `ACTION_CANCEL`
(`PanelFrame`, as `Sheet`).

**The moves** are one small object, `PanelMotion`, asked by the pair: `between(from, to)` answers a `Move` with `opening` and
`closing`. The default rises 8 dp and fades, 200 ms in, 180 ms out, as one layer, started after the frame that holds the
cost (a panel's first frame, with its layer already drawn at alpha 0; the frame of the one that comes back). A later call on
the same view turns it back from where it is (`Motion.enter/exit`'s tag). The design's laws go there and nowhere else.

**Dialogs and menus** are windows of their own over every panel: an activity's went with the activity, in the shell they
would stay over what comes next, so `Modal.show` and `Menu.show` register (`Panel.own`) and the stack closes them when the top
panel changes. That is also what ends the enrolment card on the Home key, whose `onActivityStopped` watch does not fire any
more (the Home key no longer stops anything under a panel).

**The window has no background** (`Theme.window(fill = false)`): each opaque panel draws its own room, the one full-screen
fill at rest, as the design wants and as the map under the window needs. The car still stands on `Palette.bg`: it is the
room's colour now. `CarStage.redraw` no longer pushes frames to the GPU while it is not shown: nothing takes them under another
panel, and its thread would hang in the swap two frames later (an animation does not stop for that).

**Soft input.** The shell is `adjustNothing`, set per panel at each switch (`Panel.softInput`). With ADJUST_RESIZE only that
panel's frame follows the keyboard: `mapHost` and the panels under it keep the size they had without it, so nothing heavy is
laid out again. Measured with `ProbePanel`: root 1024x230, its frame the same, the map's slot and Home 1024x528. **But that
happens only with the status bar's policy off.** `policy_control immersive.status=*` (`SystemBars`, on by default, so on the
unit) adds FLAG_FULLSCREEN to every window as far as the window manager is concerned (`PolicyControl.getWindowFlags`, in the
firmware's bytecode too), and `DisplayPolicy.layoutWindowLw` gives a fullscreen window `mRestricted`, never the shrunk
`mContent`: ADJUST_RESIZE does not resize. Only the visible frame shrinks, and the window **pans** to keep a focused field in
sight. So a test on an emulator without `WRITE_SECURE_SETTINGS` (no `-g`, no policy) is not the unit's case: "the keyboard
shrinks the screen to a strip" in the Maps notes is that case. And the pan is the bad one: measured with a real `SurfaceView` in
the slot, a field lower than the keyboard's top moves the surface along with the window (298 px), and the window's views over
it vanish (the transparent region does not follow). A field that stays above the keyboard pans nothing; with ADJUST_NOTHING
there is no pan at all.

**What moved with the activity.** Home's checks at `onResume` run at every return to the home panel, as they did (`refreshAll`,
`autoCheckIfDue`, `ZLinkGuard`, the entrance if it has not played), and `notifyHomeForeground` at its `onShow`. `onLook` is the
look check, asked after `onResume` and at each tick; coming out from under a leaving panel Home is rebuilt at once with no
picture (it would be of that panel), before the panel goes. The update check is also asked at the tick now. A language change
is `shell.languageChanged(args)`: the whole window again, the same panel on top (`reopen`, a companion field, and a system
configuration change uses it too), Settings' picture as before. `GuardMarks.homeDrawn` belongs to the shell's first frame,
whichever panel it holds. Home's start cover is inside its panel, so it waits under Settings when the window starts there.

Debug hooks: `SettingsActivity --ei page N --es theme ... --es lang ... --es api_base ... --es repo ...` still work, in `Entry`'s
`beforeOpen`; a language by the hook now shows at once, the window being rebuilt, where the old activity showed it from its next
start. `am start -a android.intent.action.MAIN -c android.intent.category.HOME --es panel probe com.abdllh.aura`: the probe panel.
`setprop debug.aura.probefail build|show|resume`, `debug.aura.probekeep 1`, `debug.aura.probesurface 1` (a real surface in the slot,
its life in the log, `AuraProbe`). `am broadcast -a com.abdllh.aura.debug.PANELS --ei rounds 120 --es panels settings,probe`:
opens, backs and Homes at random 0-300 ms apart on the main thread, then logs how the layer came to rest.

Walked before and after on the emulator (scripted, AMRI the real home, the same steps in the same order from cleared data; 67
screenshots compared pixel by pixel; the release build walked too, with no comparison): 7 Settings pages and their scroll, Back and the chevron, the Controls tile, the Home key from a deep page and from a
dialog, another app and back, a call card and the volume display over Settings (touches through to it), by the old name with one alive
and with none, debug hooks, language both ways, theme and accent (and Back right after a choice), a permission dialog denied and
allowed, the enrolment card, the keyboard in the drawer and in Settings' dialogs, the pick from Settings, Screen check, Maps and Music
from Home. Same pictures but the clock and other times, what a blinking cursor or a ring shows, a fling's distance, a card caught on its
way out, Settings' new About lines, the map's tiles, and two meant differences: by name with no Home alive Back now goes to Home and not to the launcher that was behind, and a
hook's language shows at once. Instances: always one, home stack, no crash, nothing from `AuraShell` at error level but what the probe
was told to do. 120 rounds of the stress: one frame at rest, in place, no layer, views back to 228.

Numbers, emulator, same session, interleaved (times do not carry over to the unit; GPU memory is not in them; PSS differences under
about 5 MB mean nothing): from the tap's handler to Settings' first frame complete 104-148 ms median before (65-1023), 39 ms after
(20-111). To rest: the old window animation ran 280 ms after that first frame, the move ends about 285 ms after the tap. PSS after a
GC, two passes each, before and after: Home at rest 94.6, 107.9 / 100.5, 104.3 MB; Settings open 98.7, 109.7 / 105.1, 106.8; back
102.6, 106.2 / 102.5, 104.0; after 15 more rounds 107.5, 116.8 / 104.7, 108.7 (native 69-73 / 61-66). Views 222 / 228 on Home, 317 / 320
with Settings; window roots 2 / 1 with Settings open. On the unit that is one window's buffers instead of two while Settings is
up, plus one screen-sized layer during a move; not measured. Not tried on the unit: all of it.

### One engine (2.0 step 3: `nav/MapController.kt`, `nav/AuraMap.kt`, `nav/MapsPanel.kt`, `home/Wall.kt`, `home/ShellRoot.kt`)
One `AuraMap` for the window's whole life, on a SurfaceView, in `ShellRoot.mapHost`, under everything. It is never moved, resized, hidden or
faded, from the first time it is wanted until the window ends: only covered, and only its camera changes. Maps is `MapsPanel` now, nothing but cards
over that map, and the home screen is a wall with a hole in it. So going from one to the other is the wall fading off and the camera easing: no engine to
build, no surface to make, no tile to load, no activity to start. Why a surface and not a TextureView is measured in `understand/home-shell.md` 4 (a moving
map makes the window redraw 23.6 times a second with a texture, and not at all with a surface). The new look lands on this; this step only moved what was there.

**`MapController` is the only one that talks to the map.** It owns the `AuraMap` (`shell.map.map`), brings it through the window's lifecycle, and is the only listener to
`NavSession` and `CarLocation` for it and the only caller of `setRoute`, `setRouteAhead`, `setDestination`, `setTurn`, `setCamera`, `setCar`. The home screen and Maps each had their
own copy of that logic and the copies had drifted (the arrow's "shown" check was Maps' alone; it is everybody's now). A panel keeps its chrome, listens for that if it must (a banner, a
speed) and calls the map's names on the controller: `setRoute` and `setDestination` there mean what the panel shows *when no trip is on the map*, and wait for the trip's end. It holds
`CarLocation` as "shell" from the window's start to its stop, so under Settings the fixes (and `RoadWatch`'s tones) go on where they used to stop with Home's `onStop`. `toHome()` and
`toMaps(side)` say who has the map: a panel calls one in its `onShow`, and a panel that says neither (Settings) leaves it as it is.

**The room, not the layout.** `AuraMap.setRoom(l, t, r, b, navTop)` says what part of the view can be seen, and the camera keeps the car in the middle of that part; this replaces `startPadding`,
and the home screen's "top padding of a smaller view" comes out the same inside its card. A new room is a blend (`springK`, the design's section 9: 95 % at 365 ms), and with the zoom the same that is
a pure pan. `setInteractive` is the run-time switch for gestures. A map that follows nothing yet (no fix ever) still takes the room, around where it looks. Going home from Maps: gestures off,
Maps' zoom given back (`zoomBack`), and a camera the driver dragged follows again; Maps does not remember it yet (the design wants that, `MapController` is where it goes).

**The states** (`home-shell.md` 5.3). LIVE: as ever, the frame loop runs only while something moves and draws at most 30 a second, 60 only on Maps on a trip (and a map that fell back to 30 stays there
for the trip: `fellBehind`). FROZEN: an opaque panel at rest on top (`mapHost.covered`, set by `PanelStack`, cleared *before* anything is uncovered); nothing sets the camera, so nothing is drawn (measured: 0
map frames in 4 s under Settings on a trip, 10 a second just before); fixes go on being taken in and uncovered the camera is simply where the car is. STOPPED: the window's `onStop`; its surface goes with the window.
ABSENT: `MapGuard` refused: no MapView at all, and the wall stays whole, an opaque screen with today's grid picture in the card, until the driver asks for Maps, which builds one (a crash then is
a crash he asked for, and the next start still has none at Home).
**The cover** (`mapHost.cover`, the land's colour, a flat fill) is over the map from a start until `onDrawn`, wherever it could be seen, so a surface that is not back yet never shows as black.
Not for a new style: tried, and on a trip it hid the car and the route for seconds; the surface is there and shows the new land at once, with the car and the route on it, while the tiles come.

**The window.** No background and `PixelFormat.TRANSLUCENT` from `onCreate` (a SurfaceView makes ViewRootImpl change an opaque format by itself with its first traversal; better not under a running window). `ShellRoot.gatherTransparentRegion` empties
the region: the hint a SurfaceView asks for is worked out at layout passes, and nothing here moves or shows by layout, so what is drawn outside the last one can be cut (the design's section 14, question 2; DEV's views that vanished over a panned surface). The window is one rectangle, its alpha alone reveals the map.
`PanelMotion.OverMap` is the move to and from Maps: what covered the map (Home with all that is on it, Settings' room) fades as one layer, 240 ms on `Motion.STANDARD`, a hole in it where the card is, and the cards come in. Nothing pops at the end: what stays under a panel at rest is not drawn, and by then it is transparent.

**Home's wall** (`Wall`, the room's drawable): one flat fill until the map has drawn, so nothing of the entrance changes and a map that never comes costs nothing; then the card's rectangle is left out, four rectangles and four small corner bitmaps (the surface cannot be rounded: the corners are the wall's, no path, no layer). The picture of the
card (`MapPanel`'s backdrop, the land's fill) stands over the hole and fades as ever, so nothing shows the moment it opens. The camera's room is the card's laid-out rectangle (`HomePanel.cardPlaced`, from a layout pass). The picture kept for the next start is cut to the card (`pictureArea`), so it is the same file for the same view, under the same name. On the light theme the card has
lost its low shadow while it is a hole: a shadow reaches inside its caster (which normally covers it itself) and lay as a grey ring over the map; the design bakes it into the wall. `solid` is new on `Panel`: Home is not `opaque` any more (the map stays awake) but no touch reaches the map through it.

**A panel that is kept** (`MapsPanel`: `keep`, built ahead 3 s after the entrance, `PanelStack.prepare`, so nothing is built in a tap): what an activity forgot at its destroy goes through `Panel.onOff`, told once it is off the stack and at rest, and `isOff` tells in `onHide` that this hide is the leaving kind. A search, a chosen place, a place being picked go; a trip does not; another app or a panel over it forgets nothing, as before. Listeners on the map must not touch hidden views: a change to a view under an INVISIBLE frame still asks the window for a frame (`turnCompass`).
`softInput` is `adjustResize|stateHidden` only while the field has the focus (a mode of the whole panel made every move to and from Maps a relayout of the window; `PanelStack.softInputChanged`): on the unit (`policy_control`) the window does not resize, the keyboard covers the lower part of the results as it always did, and the only field is above it, so nothing pans; `mapHost` does not follow either way. The results' own measure is unchanged.

**A new look.** `MapController.lookMayChange` is one rule for every panel, Maps' old one: not while the car moves on a trip with a manoeuvre near (a highway at dusk: on the first long stretch), and at once if the map has that look already. `MapsPanel.onLook` builds its cards again in place and `lookChanged` reloads the style on the same engine (`AuraMap.reloadStyle`): route, turn, destination and additions go back on as the style comes. `HomePanel.rebuild` the same; its picture of the window has a hole where the card is (the copy keeps the window's alpha, on the emulator; `openCard` cuts it open if the unit's comes out solid). A language is still the whole window, the one time the engine is built again.

**MapGuard** also counts a map that goes down after its first frame: `no_backup/map-live` ("<boot id> <elapsedRealtime>") from the first frame to `onStop`. Found at the next start: counted only if the boot id is this one and the map came up less than 3 minutes ago (a car losing its power with the map up is another boot; a process killed later is not the map's fault as far as can be told; `liveMarkCounts`, unit tests). Two in a boot and it is ABSENT until the next boot: fewer than the guard's three dead starts in a row, so a map that cannot run costs the map and not the home screen. In prefs file `aura_shell`, as 1.19 reads `aura_mapguard`. Settings → About: "The map at this start" says when it drew, or why it is not there. Nothing about telling the map may throw into the home screen (`MapController.safely`); a native crash cannot be caught and is what the mark is for.

**Hooks.** `setprop debug.aura.frames 1` and a restart: which view asks for each frame of the window (`AuraShell`). A force-stop with the map up counts as a death: `rm no_backup/map-live` first when testing, or the third force-stop within 3 minutes has no map. `MapsActivity --es mock_loc`, `--ez simulate true`, `--es q`, `--es pick`, `no3d` work as before, through `Entry`. `AuraMap` logs `engine #n`, `style load #n`: one engine, and one style per look, is the proof that nothing reloads.

Walked on the emulator with AMRI the real home (`set-home-activity`, the Home key), the map up throughout and no second activity, ever: `['home']`, one instance, in every step. The last step's whole walk again (Settings' pages, the sheets, the Home key from a deep page and from a dialog, another app and back, a call card and the volume display, by the old names with one alive and with none and the trap after it, the hooks, language and theme both ways, a permission dialog, the keyboard, the pick, Screen check). And Maps: from the map, the dock, "Where to?" with the keyboard and a search and a result, the place card, Back through results and place, the Home key with a place up and a menu up (nothing of it the next time), the pick by Back and by Save, a trip by the Work button and by the debug hook, Home and Maps on a trip, a dragged map and the Home key, Settings over a trip, End, a third-party app over Home and over Maps, the old name from another app, the music key and Music as an activity, sunset on a trip (it waits near a turn, comes on a long stretch, reloads on the same engine with the car and the route showing throughout), Arabic and the light theme, 120 rounds of the stress with Settings, Maps and the probe. `scripts/phone-server-test.py` (taught that Maps is a panel), the phone's place and the voice's "go to" through `Trips`: 98 checks, 0 failed. A native crash (`kill -11`) with the map up, twice: the third start has the grid and no surface layer, Maps on request builds the map, a mark of another boot and one 200 s old count nothing. A map that cannot be built (`debug.aura.mapfail 1`): the kept picture at Home, the land under Maps' cards, no crash. No crash line in any of it. Black frames: 358 frames recorded across 24 moves (Home and Maps both ways, back from another app, from Settings, from Music), the card's rectangle never under a brightness of 36 where black would be 16 (that 36 is Music's own room; on Home and Maps never under 58).

Numbers, emulator, a shared PC, interleaved with the build before the panels (`2fc4680`). Times do not carry over to the unit, GPU memory is in none of them, and PSS differences under about 5 MB mean nothing:

| | before the panels | now |
|---|---:|---:|
| Home to Maps, to the first frame | `am start -W`, median per run of 10: 454, 654, 850, 1132 ms (315-5478), and then the map still has to draw | from the tap's handler, median per run of 10: 21-63 ms over six runs (14-118), the map is there |
| Maps to Home, to the first frame | not comparable (a window animation) | median 20-33 ms (10-88, once 394) |
| Window frames in a move, medians, and the longest frame's median | open 4-5, 314-938 ms; back 2, 80-224 ms | open and back 15-23, 24-139 ms |
| Engines in 10 rounds | a new one at every open, by the code | 1 engine, 1 style (the log: `engine #1`, `style load #1`) |
| Window frames with the car moving at Home (1 fix a second, no other change) | 23.6 a second (1.19.0, `home-shell.md` 4) | **0 in 30 s**, and 0 on Maps |
| The same, one sitting on a loaded PC (the emulator's GL thread is encoder and pipe work): window frames, CPU of one core | 8.7 a second, 108 % (map thread 55, RenderThread 51) | 1 in 30 s (the clock), 57 % (map thread 54, RenderThread 0.3) |
| Map frames at Home with the car stopped; under Settings on a trip | | 0 in 12 s; 0 in 4 s |
| PSS after a GC / native, MB: Home at rest | 77.5 / 36.6, 77.6 / 37.1 | 82.3 / 41.5, 90.1 / 46.4, 89.7 / 48.2 |
| Maps open | 89.7 / 48.1, 94.8 / 53.7 | 82.2 / 41.3, 90.5 / 46.9, 91.3 / 49.8 |
| A trip at Home | 96.5 / 53.1, 96.4 / 54.4 | 108.2 / 65.2, 98.8 / 54.2, 112.4 / 68.7 |
| A trip on Maps | 141.2 / 97.9, 144.3 / 102.0 | 114.6 / 72.5, 121.6 / 76.5, 119.7 / 75.9 |
| After the trip and 10 more rounds of Maps | 128.1 / 83.6, 135.3 / 92.0 | 112.6 / 70.4, 121.4 / 77.2, 113.9 / 70.0 |
| Views, window roots | 222 / 1 at Home, 317 / 2 on Maps | 316 / 1, always (Maps' panel is kept) |

So Home itself weighs 5 to 12 MB more (a map of the whole screen, Maps' cards kept), and what used to be two engines costs 20 to 25 MB less on a trip and does not grow with rounds. On the unit, GPU memory: one surface of 1024 x 600 under the window where there were a 607 x 484 TextureView and a Maps surface; not measured.

What the unit has said so far (its Screen check of 1.19.1, `tmp/v2/unit-facts.md`; `MapRig`, not this code): the map on a full-bleed SurfaceView and the window are two hardware planes, 55 fps at the 60 cap and 29 at the 30 cap, the window redrawing 0.4 times a second; motion over it HWUI p95 8 ms; 4 GB of RAM; GPS one fix in 2 s (hence `glideMs` up to 2600, off the route the car stood still for 400 ms in every fix); a baked 9-patch panel animated had p95 20 ms and 13 % jank, a warning for the new look's moves. Only the unit can tell about this code: the first frame of the move on that GPU (two window-sized layers), whether the surface comes back from a stop with a flash (the cover is there for it), a native crash or GPU hang of the real driver, and the firmware's behaviour around a translucent home window. Settings → About shows the map's line and the last panel's open time, for the owner's photo.

### Music in the one window (2.0 step 4: `music/MusicPanel.kt`, `music/MusicActivity.kt`, `home/DrawWatch.kt`)
Music moved unchanged to `MusicPanel` (`git diff -B -M` shows the rename), opaque and kept. `MusicActivity` is an `Entry` that keeps the
name `StockMusic`'s `replace_source_list.xml` names, its MAIN, `action.MUSIC` and VIEW filters, `EXTRA_SOURCE` and `delaySeconds`. What
an activity had: the backdrop is `root.background` and not the window's (the window has none); `recreate` is `buildAgain`; `onStart` and
`onStop` are `onShow` and `onHide`; `isDestroyed` is `isGone`; `onCreate` is the `init` at the end of the class (Kotlin runs initialisers
in order, and the class has fields all the way down). No `stateAlwaysHidden`: `onHide` clears the search field's focus instead, which is what
that mode was for, and a soft input mode that differs from Home's would make every switch a `relayoutWindow`.

**Built ahead of time.** The starting window went with the activity, and a first build on the unit's main thread would have nothing to hide
behind. `HomePanel.onResume` asks `PanelStack.prepareWhenQuiet(MUSIC, 6 s)`, not in the start's first seconds: at that moment and later,
only with Home at rest on top of a resumed window, no sheet open, no finger on the screen for 1.5 s; otherwise it looks again in 2 s, and at any
other screen it stops until Home's next resume. A panel that could not be built is not tried again this way. `prepare` then gives it one
real frame at alpha 1/255 before making it INVISIBLE: letters in the GPU's store, icons rasterised, shaders compiled, which a first open
otherwise pays in its first frame (the frame's recording does not survive, HWUI deletes a display list nothing refers to, so later opens
record anew, and so does the first). `init` also says all it can, list rows included, and lets `ArtLoader` decode the cover and the rows'
pictures for its cache and `Backdrop` work out the look, holding none of them. So the first open has the cover and the colours at once, as a later one.

**Kept, and what it holds.** Across the Home key and any panel: source, tab, open group, where each list was, the lyrics (large ones too),
YouTube's search and results. Until 2.0 the Home key destroyed all of it. The source is looked at again when it comes back from off the stack
(`Panel.newOnStack`), as a new activity did (Bluetooth when only the phone plays, written nowhere), not when the window comes back with it on top:
from YouTube's app it stays on YouTube. `onHide` stops everything that runs, as `onStop` did. 2.5 s later, when nothing of it can be on the screen,
`letGoOfPictures` empties the cover's three views and the rows' pictures, and the fields that remember them. They are in caches with limits
(`ArtLoader` 12 MB, `CoverSearch`, the session's) and those limits hold only if nobody else keeps what they evict. The look stays (48 texels and
two colours), by a weak reference to its cover: back on the same picture nothing is worked out, and a new one comes off the main thread, simply there
if it lands in the first 400 ms and faded in after that. List rows keep their views: building them is what an open must not pay, and they are about 100
small views. Short of memory (`onTrimMemory`: RUNNING_LOW, RUNNING_CRITICAL, from MODERATE; not UI_HIDDEN, which only says another app came)
`ArtLoader` empties, and out of sight the lyric lines and the rows go too; their place is kept. `onShow` hides `root` from itself while it paints
(`isShown` false, as in an activity's `onStart`), so what changed while it was away is just there: nothing inside it moves in the move that brings it in.

**New look.** `onLook` and `onShow` build the views again in place when the theme or accent is not the one they were built in. State stays,
which `recreate` lost (F7 in `understand/music.md`). A language is the whole window's. Recolouring in place is the new design's.

**Lyrics listening ends with the screen** (the switch's text promises it): `LyricsSync.end()` in `onHide`. Measured with `LYRICS_FEED` and
the stand-in: pieces every 10 s while it shows, a last short one at the Home key, none for 20 s at Home, a new `sync start` on the way back.

**Keys.** `MusicKeys` asks `Front.panel` and `Front.ownIs(name, ms)`; there is no class to ask any more. "The same source" is `MusicPanel.onBluetooth`, set
as long as the panel exists. First and second press of 83 and 99, the other source's key, a held key, over another app, the stock screens' signs: walked.

**The way back from YouTube** is `Screens.music` as before. It had never run: `YtMusic.player` knew only the two real packages, so a stand-in could
show its session but never take a song. Now `players` (debug: plus `debug.aura.ytpkg`), and `src/debug/.../YtStandIn` in the `-PstandIn` copy takes the
link, plays its own library a moment later, and AMRI comes back on the YouTube source with that session. Disable the copy's two home components or Android asks
which home. `setprop debug.aura.ytfake 1`: a search with one made-up song. Still never run with YouTube's real app.

**FROZEN** needs nothing from Music: `opaque` makes the stack set `shell.root.mapHost.covered` at rest (`PanelStress --ez report true` logs it) and clear it before uncovering.

**A window that stops drawing (`DrawWatch`).** Found here. When a window gets no buffer for 4 s HWUI writes its surface off for good (`dequeueBuffer failed,
error = -110; switching to fallback`, then `reserveNext failed`): the renderer disables itself and releases the Surface, and `ViewRootImpl.draw` returns at once from then on,
with no error and no log, until the window next relayouts and is given a Surface. With an activity per screen that mended itself at the next screen. In the one window
the screen stays on its last picture with every touch working under it, until another app comes over. Seen on this PC's loaded emulators, whose composer stalls for seconds;
reproduced at will with `kill -STOP` on surfaceflinger for 6 s while frames are coming. Not known on the unit, where a stalled composer would be the trigger. So at every panel change,
minute's tick, resume and finger down a frame is expected: if none was drawn 3 s later (window resumed, display on) the window's attributes are set again, which makes it relayout and costs nothing if nothing was
wrong; if still none, `recreate` once per process (not exercised: the relayout has always been enough). Settings → About keeps the last time ("Screen stopped drawing"). Tried: back within 3 s of a finger, and
at the minute's tick with no finger. This applies to anything in the window, not to Music; the overlays are windows of their own and are not watched.

**Fail-safe.** `setprop debug.aura.musicfail build|show`: the stack drops it, Home goes on, a Snack says so, About keeps the line, and with the property gone it opens. 120 rounds of `PanelStress` over
Settings, Music and the probe: one frame at rest, the other INVISIBLE, alpha 1, no layer, no warning.

**Stand-ins to try it with** (`scripts/`): `gen_test_songs.py` (silent songs with tags and covers, `small` and `big`), `lyrics_standin.py` (never port 8790; prefs `lyricsServer` `10.0.2.2:<port>`, `lyricsServerKey` any),
`naghm_standin.py`. When a prefs file is edited with the app stopped: the notification listener's rebinding brings the process back at once, so force-stop again after copying. `am broadcast` to `PanelStress` needs `-f 0x01000000`.

Walked before and after, AMRI the real home, the same steps, on the emulator: in and out by the dock, the card, the drawer, voice, Back, the chevron, the Home key; the unit's own songs (play, next, previous, seek, shuffle, repeat, all tabs, a group,
Play all, scroll); the phone by the debug broadcasts (no phone, first song with no position, a progress report, next song, pause); Naghm's stand-in (bar seek, next, toggle, its lyrics, line tap seeks, hold measures); YouTube's source, its field, the keyboard
and the Home key over it; lyrics with word times, tapped to seek, large, Home and back; the manual timing (tap sets the clock, tap again saves `offsetMs`, kept across Home, hold forgets); listening; keys; a call over Music and over Home, the key refused during it,
the song back after it; another app and back, the Home key from it; the old name with Home alive, on top, over another app, with none alive (one activity in the home stack each time, and the trap sequence ends in one); `MAIN` as the firmware starts it, `action.MUSIC`,
VIEW `audio/*` (opens, plays nothing, as before); the notification's PendingIntent; theme with Music hidden and in front, at the tick too; language both ways; the library changing while hidden and in front; permission denied and allowed; trim; no motion; Music first in a new process. Radio has no place in Music.
What differs, as meant: the group and scroll survive the Home key; the theme by the hook comes back to Music where it used to find Home; by the old name with no Home alive Back goes to Home. The backdrop differs by a few levels on the emulator only: the root is 552 high there and the window was 600 (no navigation bar on the unit).

Numbers, emulator, interleaved with the build before, on a PC the other agents were loading: they do not carry over to the unit, and the emulator composes at its own pace. From the tap's handler to the first frame complete, ms, two blocks on a rebooted emulator: before, first open medians 2114, 2483 (1717-4222), later opens 1805, 2033 (1627-3215); after, first open 394, 547 (313-1505), later opens 304, 336 (15-655). In a quieter spell, after only: first 83-160, later 16-140. With the frame ahead of time against without it, in turns: the first frame's GPU commands 21-31 ms against 41-97, uploads 0 against 1-19, first opens about 94 against 128. On the main thread (`AuraShell`, the frame recorded) a later open takes 6-63 ms, of which 2-14 before the frame; the build ahead 50-300 ms, once 1115 under load, plus a layout and the real frame. Memory, PSS after a GC, before / after, MB: Home at rest
104.4 / 99.6, Music open 108.1 / 100.7, back 109.8 / 99.5, after 20 opens and closes 158.7 / 106.3 (native 113.0 / 62.7, Java heap allocated 3.8 / 3.8); with 210 songs and 43 covers 102.1 / 95.9, 107.0 / 98.0, 104.7 / 98.3, 163.2 / 104.6 (native 117.2 / 60.9). Over 40 rounds through the tabs
the old screen grew about 2 MB a round without end (105, 122, 133, 155, 172); the panel 111, 138, 138, 138, 138, Java heap flat at 4.0. About 20 MB of that step is Home's map coming back and appears with Settings alone (103, 124, 124, 124). Views at Home 228 / 409 (Music's, not drawn), window roots with Music open 2 / 1.
With a song playing at Home, 1.1 / 1.0 window frames a second. GPU memory is not in any of this. Not tried on the unit: all of it. About's "Last screen opened" now tells the first open and the build ahead too, for the owner's photo.
The figures above are from the build just before the pictures were let go later. The last build, another hour: Home 100.7, Music 102.3, back 100.8, after 20 opens and closes 108.1 (native 66.1, Java heap 3.8);
40 rounds through the tabs with the 210 songs 96, 106, 103, 106, 105 (native 63, Java heap 3.9); Settings alone 110, 118, 114, 118. So the level moves by tens of MB from one sitting to the next on this emulator (the 138 above is not there this time, and the reason was not chased), and what holds in every sitting is that it stays flat.

### Memory and start-up cuts (the foundations of 2.0: nothing looks different, while the GPU keeps its time)
Home was the heavy screen, and almost none of its weight was code: the car's CPU path, its leftovers, the map's sprites and
other apps' files. Each cut below keeps the old way as the fallback and says in Settings → About what it did, because none
of it could be tried on the unit first.
* **The car comes from the GPU at start (`CarStage.playIntro`, `CarGlView`, `CarEtc.readyNow`).** `CarFrames.warmUp`
  decoded the whole spin at every start, up to 40 frames × 921,600 bytes, and drawing them on the canvas put each into
  HWUI's texture cache, which then stayed full (27–29 MB) for as long as Home's window lived. It was there because a 1.12
  test without it showed the floor light alone, then the car popping in already turned: the GPU wanted all 180 frames
  before its first frame (1.2–1.5 s then, 300–900 ms now), could only start when the window's surface came with the first
  draw, and the spin ran on its own clock meanwhile. Now, with the frame file known:
  * `CarEtc.readyNow` tells from a few stats and a hash kept per APK (`carEtcHash`, "<version:size:time:frames>=<hash>"),
    no asset opened. False once after each install or update: nothing has read that APK's frames yet, so that one start
    takes the CPU path as before.
  * The GL thread loads the spin's 47 frames (44 + 3) first, and only once the window's first frame is on the screen
    (`CarGlView.FrameOver`: the window's frame report, 1 s at most). Loading them beside that frame, on a 1×1 pbuffer from the
    view's attach or with the window's surface, was tried: the car then starts with the screen, but the first frame came
    about 270 ms later (of 1200 on a loaded emulator, 12 starts each, round robin from one build). The other 133 follow
    one per message after the spin, at a touch, or 3 s later at most, nearest the resting view first; a draw that needs
    one not in yet uses the nearest that is (`texOf`).
  * The car stays faded out until the GPU's first frame, and that frame starts the spin: a slow GPU can only show a wait,
    never a pop. Only the resting frame is decoded meanwhile (`CarFrames.prepareQuietly`, 0.9 MB, at low priority, from the screen's first draw).
  * Fallbacks: the GPU's first frame more than 700 ms after the screen's (`onGpuLate`): no spin, the car fades in at rest from that frame on the CPU and
    the GPU takes over in place. So this path can look different from 1.19.0, on a GPU that is slow: an empty panel for 700 ms, and no spin at that start.
    The first version of this waited 2 s, against "a loaded emulator came near 1.5 s"; a review measured 3.6 s under load, an empty panel for 3 s
    and no spin, with 1.19.0 showing the car from the screen's first frame, and every drive is a cold boot.
    `files/car-start` keeps how it went (`late`, `ok`): after a late start the next one begins on the CPU's spin, as until 1.19 (`CarStage.startsOnCpu`, `warmUp`; path 5
    in About), and goes back to waiting for the GPU only when the GPU's first frame came within 1.4 s beside that spin. On a unit whose GPU is that slow, 2.0
    keeps 1.19's start and its bitmaps of the first seconds, and what About shows says so.
    Emulator, loaded (six running, the GPU's first frame 2.4-3.1 s by itself): `debug.aura.car.slow 3000`, frame sheets: the car in the panel about 1 s after
    the full screen, at rest, the GPU 4.5 s after the screen's first frame (path 3); the next start the car spins from its first frame on the CPU (path 5).
    The GPU failing: `warmUp` and the CPU's spin, as on a first start. A rebuilt screen (a theme change): the resting frame and
    its neighbours first, one CPU frame until they are in.
  * Debug builds: `setprop debug.aura.car.slow <ms>`, `debug.aura.car.fail 1`, `debug.aura.car.noreport 1` (no frame
    report: the 1 s wait).
* **Nothing of the CPU path stays once the GPU draws (`CarFrames.gpuDraws`).** The decoded and encoded frames and the
  queue are dropped, and a frame still being decoded is thrown away: the two 640×360 frames found alive in the LRU came in
  after the old trim. The layer under the car (0.9 MB) is made each time the car comes to rest, no longer kept.
* **The map's car sprites are 96 px, not 176 (`tools/car3d/pack_map.py`, `--size`).** The view is 92 dp and 1 dp is 1 px on
  the panel, and all 73 stay decoded for the whole process: 9.04 → 2.69 MB, and 196 KB less in the APK. Rebuild larger if a
  new design draws the car larger.
* **Other apps' files are let go of (`system/IdleGc.kt`).** A name or an icon of another app goes through a `Resources` of
  that app, which maps its APK until a garbage collection, and this process can go for hours without one: 15.7 MB of PSS on
  the emulator (clean pages, so less real pressure than that). The list is read when the drawer's cells are built (2.5 s
  after the entrance, not during `onCreate`), and one collection runs off the main thread 3 s after that, once lists and
  icons are all in; again after the drawer closes, at most once a minute. The drawer's first open is as it was: moving the
  icons to the first open would have cost the frames the sheet moves in. Anything new that reads other apps' names or
  icons should ask `IdleGc.request` too.
* **MapLibre's library loads off the main thread (`nav/MapLib.kt`).** Right after the home screen's first frame, 700 ms before
  the live map wants it. At process start, where it was first put, it held that frame back by about 85 ms (of 1200 on a
  loaded emulator: the linker's and the class loader's locks). Not with the kill switch on, not while `MapGuard` has anything
  on record, and not after a load that never came back (a mark on disk, `map-lib-loading`: a native crash leaves no chance
  to write anything); those starts leave it to the map, as before, and so does a Map screen opened first in a process. The
  runtime's lock means the main thread can only wait for what is left of the load, never for more than it used to.
* **The Music screen's equalizer (`EqualizerView`).** Eight frames a second instead of eleven, and never more than one
  redraw waiting: each draw used to post its own, so a draw for any other reason could start a second round that never
  ended. Every frame of it redraws the window for as long as a song plays.
* **AMRI's code is compiled once per build (`system/DexCompile.kt`).** `cmd package compile -m speed-profile` through
  `LocalAdb`, on the unit only, off the main thread: 37 of the 38 stock privileged apps ship compiled, AMRI does not, and
  whether the system's idle job ever runs on a unit that goes off with the car is not known. First look 5 minutes (of the
  unit being awake) after the process started, as the profile needs a run behind it, then every minute until the gear
  says P, the screen is on and no call is up; three attempts per build. dex2oat runs under installd at nice 10 (seen on the
  emulator: 0.9 s, 4 threads, 2.2 s of CPU, 70 MB) and swaps its files in when it ends, so the running process is left alone and
  the new code is used from the next start. A debuggable build always ends at `quicken` and says so. Debug builds with the fake
  adbd: `setprop debug.aura.compile <minutes>`, `debug.aura.adb 10.0.2.2:<port>`.
* **What it reports.** Settings → About: how the car started, with the GPU's times; the map library's load; the last
  collection (PSS before and after); the compile. The same in the log: `AuraCar`, `AuraMap`, `AuraGc`, `AuraCompile`.

What it came to, 1.19.0 against now, on the emulator (restarted first, release builds, the method of
`understand/perf-weight.md`). Its GL drifts and the PC is shared, so only interleaved runs compare, and none of this is the
unit's:

| | 1.19.0 | now |
|---|---:|---:|
| Home's first frame, `am start -W` `TotalTime`, interleaved, 12 starts each (a trivial app: 272) | 483 ms (439–569) | **399 ms** (318–460) |
| Home, highest PSS in the first seconds, 8 runs | 127–213 MB, at 1.9 s | **104–114 MB**, at 3.6–5 s (the live map) |
| Home, PSS at 18 s, before any collection, 9 runs | 116 MB (102–143) | **84–87 MB** (72–93) |
| The same after a collection | 94 MB (84–99) | 84–87 MB (72–91) |
| HWUI's texture cache (GPU memory, not in PSS), for as long as Home's window lives | 26 MB (23–30, once 7.8) | **5.9 MB** |
| Other apps' files mapped at 18 s | 13.8 MB | **0** |
| Bitmaps at 18 s (heap dump) | 16.5 MB (117) | **5.0 MB** (91) |
| App's Java heap at 18 s (heap dump) | 6.2 MB | **1.4 MB** |
| `MapPanel.startLive` on the main thread (sampled, 4 starts) | 71 ms | **19 ms** |
| Music screen with a song playing: window redraws | 10.8 a second | **8.1 a second** |

* The look: frame sheets of a cold start (dark, light, Arabic, as a real home), a touch in the middle of it, Music and back,
  the screen off and on, the first start of an APK, the GPU late or failed: no pop. The car's fade-in shows when it did (from
  the window settled, one recording each: 436 ms, 435 ms). The log puts the GPU's first frame 210–260 ms after the screen's on
  a quiet PC, 600–920 ms on a loaded one.
* Not better here: the CPU of the first 12 s (2.7–3.1 s against 2.8–3.6 s). The decoding threads' 220–300 ms are gone, but `car-gl`
  draws the whole spin now, and that thread is encoder and pipe work on the emulator, nothing like a driver. The Music screen's
  CPU is lost in the noise (3–4 % of a core either way). The 26 redraws a second of the report did not come back: one round
  was 10.8 in every run, with song changes and tab changes too; the second round is closed by reading the code alone.
* Compile: `speed-profile` took the JIT thread's 135 ms away and added about 3 MB of PSS (one reading); the starts after it
  fell in a slower stretch of the emulator (the trivial app 508 → 899 ms), so they say nothing.
* What should carry over to the unit: what is gone is gone (the decoded frames, the texture cache's 20 MB, 6.35 MB of sprites,
  the other apps' mappings, the library's load on the main thread), and the order of things. Not the times: part of the
  first frame's gain is the decoders and the GL thread no longer running beside the emulator's slow GL. Settings → About
  shows the real times there; `dumpsys gfxinfo` and `dumpsys meminfo` settle the rest (`understand/perf-weight.md` §11).
* Measured, not applied: **the car with 90 frames** (every second one; the shader blends two anyway). 1.8 MB less in the
  APK, 20.7 MB less in `/data` (10.4 MB per frame file), 10.4 MB less GPU memory [computed], PSS and load time the same
  (25 frames in 311–373 ms against 47 in 315–336: EGL and the program are most of it). The spin's middle frames show a little
  ghosting at 4 degrees a frame; it would need a look on the panel.

## Design system (2.0: `ui/`, `design/tokens`, `design/icons`, debug `GalleryPanel`)
What 2.0's screens are made of. The design is `SPEC.md` of the design folder (`tmp/v2/design/final2`); this says what is in the code and why it
differs where it does. The old screens still build on all of it: they look imperfect in the new tokens until they are rebuilt, and that is expected.

**The one font.** IBM Plex Sans Arabic in three weights, `assets/fonts` (727 KB, `OFL.txt` beside them, `NOTICE`). `Fonts.get(400|500|600)`, `Fonts.of(role)`;
`Fonts.LIGHT..BOLD` are the old names and fall on the three. Roboto and Readex are out of the UI, and Readex out of the repository: nothing else needed it, because the map pack's glyphs are Plex too (`AMRI Map Sans`, the map branch's
`tools/mapbuild`; its `run_build.ps1 -Fonts` can take `assets/fonts` once the two meet, the same files by the hashes of its `lock.json`). All text goes
through `Fonts.tune(paint)`: subpixel, no hinting. Hinted at 1 dp = 1 px, stems snap and advances round to the pixel, and a long line is off by 2 px from the design;
unhinted the letters are the design's. A long Arabic line still comes out about 1 % narrower than in Chromium (330 px against 327), not traced. There is no `tnum` any more: Plex's digits are
equal, and no feature was needed; the seven sites left in `MapsActivity` and `MusicActivity` do nothing and go with those screens. Whether digits shrink in one run with Arabic (section 14, question 6): `600` at 56 px has 41 px of ink alone, with its unit in spans and in one run with
Arabic (0.70 em is 39, the threshold of the measure adds 2; page `digits`, `gallery_compare.py --digits`), so on Android they do not. `withUnit` keeps its `LocaleSpan`, which does no harm.

**A label.** `ctx.label(Type.road, { Palette.ink })` is one line, 1.5 em tall, its direction the language's; a name inside it keeps its own by `BidiFormatter.unicodeWrap` where it
comes from. Its colour follows the theme when it is given as a token (a lambda); an Int stays what it was. Place it by its line box with `Type.road.box(x, lineTop, w)`, as the layout tables give
them: the view is centred on the box and may overlap its neighbour. `lines = 2` or `0` wraps, with the role's line box and no multiplier; its first baseline stands where a one-line label's does, so it takes
`box()` as well. `Role.px` is the size in the UI's language, `Role.night` the heavier weight of moving screens at night (`ctx.label(role, token, moving = true)`, or `label.oneLine(role, moving = true)`: it follows a theme change by itself, and a `typeface =` from `Fonts.of(role, true)` would stay the old theme's weight). Digits' letter-spacing: `digitSpacing(px)`.

**Tokens.** `design/tokens/tokens.json` is the one source; `node design/tokens/gen_tokens_kt.mjs` writes `ui/DesignTokens.kt`. `Palette.ink` (`ColorTokens`), `Type.dist` (`TypeRoles`), `Tokens2.R_card`,
`Tokens2.MS_theme`, `Tokens2.SPRING_segment`. Added to the design's generator: `scrim` (`scrimRgb` at .6), each role's night weight, `TOUCH_*`, `FREE_*`, and the two base classes, so a new token needs one command. Under
"the old names" in `Palette` and `Type` is what 1.x called things, worked out from the same tokens; the blocks go with the last old screen. `Palette.accent` is one colour now: `Prefs.accent` still stores
what old Settings' swatches pick, and nothing reads it. `Asked.kt` holds the owner's three open answers: the dock's order (the second of `q-dock-order.png`), the lyric's word and a message's text while moving. Each is one table or one constant.

**Recolouring.** `ThemeSwap` is section 8's one path: ready first on a worker (`Surfaces.prepare`, `readySteps` for the car and the wash), the window recorded into a `Picture` and held in a view with a hardware layer, then in one frame
`Palette.applyTheme`, `Look.apply` on the shown panel, the chrome and the sheets, and `map?.invoke(night)`; the ghost fades over 700 ms `move`; the old theme's bitmaps go. Hidden panels are marked stale and catch up in `PanelStack.arrive`, by whichever way they come back (the gallery under a panel of its own through a change, then Back, equals the page built anew: `--es over probe`, `--es swap day`).
`Theme.isDarkNow` follows the real sun at -0.833 degrees, at the last fix, or Dammam before any (`Daylight`). The minute's tick asks (`PanelStack.tick`), and with a turn within 300 m or a call ringing (`ThemeSwap.holdOff`) it waits up to 2 minutes.
What a view does in the walk is its own: a component is `Recolours`; any other view says `view.look { ... }`, or `.ink { }`, `.glyph { }`; a plate is a `Tint`; a view on a surface has a `SurfaceBackground`, whose 9-patch changes in place, because `view.background = ` puts the 9-patch's padding back over the view's own (a Snack came out with no padding after a change, and a Modal's card or a Menu would have lost theirs the same way). In the gallery, the same page built anew is pixel for pixel what recoloured in place, 25 pages both ways, with a sheet, a dialog and a menu left open through the change (`--es theme day --ez cut true`, no page, then `--ez rebuild true`). A bitmap of a theme (`Backdrop.noArt`, the song's wash) is the screen's to set again, in a `look { }` or a ready step. A `Panel` with `recolours = false`, which is every old screen, keeps restyling itself in `onLook` as before, and that is the one thing still
restarting a map (`HomePanel.rebuild`, `MapsActivity`'s recreate): those lines go when the screens are rebuilt. The map's own colours come through `ThemeSwap.map`, set by whoever owns the map: `MapStyle.paints` and `TransitionOptions`, never `setStyle`.

**Surfaces.** `scripts/install_surfaces.py <design folder>` copies what ships: 26 9-patches and 32 small bitmaps into `res/drawable-nodpi`, masks, frames, room, dock, capsules, cover shadow and car glow into `assets/surfaces`. `SurfaceCard` and `SurfaceColumn` are the base of what has an
outset: `Surface.CARD.at(x, y, w, h)` from the visible rect, touches in the outset fall through, a tap inside never reaches the map. Always exact sizes: a 9-patch's minimum is its bitmap. `Tint(ctx, Plates.res(18)) { Palette.fill }` is a plate with its own mutated state, whose bitmap stays shared (the spec's cache of mutated
drawables by token would save a hundred bytes a view, and each view needs its own bounds anyway). `Surfaces.asset/themed` decode the big ones `Config.HARDWARE`, `software` for what a Canvas reads; `Surfaces.res` keeps the small bitmaps of res ordinary, against the spec's table: on Android 10 a hardware bitmap is a buffer of its own and a trip through the uploader's GL context on the calling thread, to save about 10 KB (on the emulator a second each with several running: a page of 16 held its first frame 17 s, and under that load the window once lost its surface and drew nothing until a relayout). `turn-glow` was in no row of the spec's table; it goes in `res/drawable-nodpi`. The car's walls and frames stay with the car (section 10).
`context.scrollFade(surface, top)`: lay it over a list inset 8 from the surface's edge, its last 28 px ending 8 above the card's bottom. The mockup's fade has rounded corners; a stretched bitmap has none and would poke out of the corner if it reached the edge.

**Components, what changed under the old names.** `IconButton` (one view, no children, plate and glyph drawn; `Kind.BARE|TONAL|PILL|ACTIVE`, `pressPlate` for a dock cell) replaced `RoundBtn`, `Dock.button`, `MediaCard.transport` and the two close buttons; `MapPanel.place`, `MusicActivity.ctlButton`, `MapsActivity.roundIcon`, `mapButton`, `zoomHalf`
go with their screens. `PressGesture`: a component calls `onTouch` from `dispatchTouchEvent` and keeps its listener; `View.pressScale` stays for the rest, on the listener. `tolerant` keeps a click alive 20 % outside the view, past Android's 8 px. `ABtn`: M 60, L 84, `icon(res)`, `loading`, the plates of section 5; `Kind.ANSWER`.
A `Segmented` label is two views, regular and medium, and the thumb's coverage is their alpha: no text drawn and no layout while it slides. `AuraSwitch` is its track grown by 10 and `AuraSlider` its track grown by 27 and 60 high, because the baked shadows must not be cut at the view's edge and no parent may stop clipping: place them by `AuraSwitch.at`, `AuraSlider.at`, or in rows by
`switchRow`, `sliderControl`, which take that off in margins. `SeekView` draws its times itself, one view and no layout at a tick; `MusicActivity` keeps its own old `SeekView` and `ThinProgress` classes until Music is rebuilt (they should go then: `ui/` has the new ones, imported by name). `EqualizerView` is gone, `EqGlyph` is the still glyph. `Spinner` turns by the view's rotation, its ring drawn once;
`ABtn`'s spinner is a delayed post at 30 fps, because an animator on a label asks for a frame right after each frame, and on a slow frame that kept the main thread's other messages behind the frames (a page of five stopped answering broadcasts on the emulator). `Sheet(ctx, side = true)`: `sideHead(title, tools...)`, `placeOnTrip`. `Modal.card`, `Modal.title`, `Modal.body`,
`Modal.buttons(ctx, action, others...)` with the action on the left in both languages, `followKeyboard()`, `Modal.dismissAll()` for the car's starting to move. `Snack` shows through one `SnackHost` over the window's content; `Snack.waitFor` is the voice card. `TextField`, `LimitSign` (`SPEED`, `HOME`, `STRIP`, `dim`; `RoadHud` uses it). `Rows`: `controlRow`, `valueChevron`, `sliderControl`, `group(..., fill = 414)` with the mockup's rounding of row heights; hairlines lie over the row under them and take no height.
Icons: `scripts/gen_icons.py` from `design/icons`; 138 and 36 old names. New names mirror by `autoMirrored`; the aliases do not, because their callers still flip by hand.

**Song colour.** `Backdrop.washAsync(ctx, cover, night) { wash -> }`: `panel`, 48 x 28, `widget` and `mini` (show them at the card's rect grown by `CARD_MARGIN`), `fill`, `wordPill`. `Backdrop.round(ctx, cover, size, radius)` on the worker, `noArt`. The word pill is the song's hue at the saturation and value the sea cover gives (night .55, .96, day .89, .525), then `reach`: from the fill itself, as section 4 words it, the
sea cover's pill came out `#56A1BF` against the design's `#6FD6F6`, duller, because a fill's value is held down so that it works as a fill.

**Tones.** `design/sound/make_tones.py` writes `res/raw/tone_tick`, `tone_saher`, `tone_over`; `Tones.tick(ctx)` etc., on the guidance stream, `Prefs.pressTick`. The spec gives the tick's length and the Saher tone's character and no pitch or level; these are this file's choice, one table, not heard on the car's speakers.

**The gallery.** `am broadcast -n com.abdllh.aura/.debug.GalleryReceiver --es page controls --es theme night --ez cut true` (debug builds): every component in every state at real size, a page per group; `theme` without `cut` goes through the real `ThemeSwap`. `r-*` pages put them where the mockups have them with the mockups' words, on the mockups' map plates (`scripts/gallery-shots.ps1 -Design <folder>` pushes them).
`scripts/gallery_compare.py <design folder>` compares those pages rect by rect with `screens/_layout.json`. Text can never be equal: Chromium's gamma and Skia's differ.
On an emulator under load a window can stop drawing altogether while its main thread is well and every view is new: `OpenGLRenderer: dequeueBuffer failed, error = -110; switching to fallback` in the log (the composer lagged more than the buffer's wait, while something animated without a pause), then the renderer is disabled and the surface released until the next relayout. It is the framework's, on the emulator's composer, and not a state of a page; `--ez probe true` prints it (`settings put global hidden_api_policy 1` first). That is why the gallery's page with spinners is its own and last, and a theme change, which relayouts, brings the window back.
With five emulators on the one GPU, one can also die outright (qemu gone, adb says `not found`). Its next start then waits behind a window of its own, "Android Emulator closed unexpectedly", with no device in adb, for as long as nobody closes it: closing the window sends nothing, and the boot goes on.

**What it weighs.** Against the shell before it, entry by entry in the debug APK: +1,334,117 B (fonts 183,153 compressed, 443,855 raw; `assets/surfaces` 724,091; png 82,118; xml 37,413; tones 35,852; arsc 15,976; dex 110,756, the gallery's classes in it; and 144,732 of covers that only a debug build carries). The file itself says less, because an incremental build leaves gaps in the zip. PSS at Home on the emulator, 40 s after a start: 88.7, 90.5, 92.1 and 93.6 MB, against 96.9 MB before it (the one run of three whose map had come up by then); the views went from 311 to 214, since an `IconButton` has no children. One theme's bitmaps of `Surfaces.prepare` are 3.33 MB of gralloc on the unit, which the emulator's meminfo does not show (`Graphics: 0`); the three surfaces' 9-patches 504 KB for both themes. The fonts are compressed in the APK as Readex was, so the platform inflates each into a direct buffer: 727 KB; `noCompress` would map them instead, for 544 KB more APK. Not done: this is the build file's, and worth a look on the unit's numbers.

## The chrome (2.0: `chrome/`, `system/DriveState.kt`, `system/Prayer.kt`, debug `ChromeProbe`)
What stays over every panel: the status strip, Home's row and the dock, and over both the volume and the voice button. `Chrome` holds them, in `ShellRoot.chrome`, made once before the window's first frame and never rebuilt. It has no switch: it is always there, and old panels show through where it has nothing. A debug build can take it off until the window's next start (`ChromeProbe --ez on false`, `Chrome.debugOn`); the gallery's pages do, since they draw what they want of it, and `chrome-shots.ps1` puts it back over them.

**The panel on top** comes from `Front`, which `PanelStack.applyTo` tells at every switch and which now has `addPanelListener`: no line of the stack knows about the chrome. `Chrome.show(name, animate)` is the whole of it. It reads `shell.topPanel` again when it is attached, because a recreate puts the panel back after the chrome is built and `Front` hears no change of name.

**One row.** Volume and the mic never move or fade (`VolumeCluster`, `VoiceButton`, above both rows). `RowHome` and `RowDock` cross-fade in place by section 9, no slide: to the dock, Home's row 120 `exit`, the dock 160 `move`, the voice's plate into its disc in the same 160, the strip's time 120 `exit`; back, the dock 120 `exit`, Home's row 160 `move`, the time 120 `exit`. All on one frame callback (`Tweens`), which turns back from where it is: a second tap retargets, nothing is queued. It is not an animator, so the firmware's scale cannot make it jump; its first frame already counts one frame, and a frame that took long moves it on by two frames at most, so a panel's heavy first draw costs a little time and never a jump.
**The owner's order, the simpler row (2.0.0-beta.2, CHANGES.md section 2).** `Asked.ORDER_NEAREST`: home, map, music, phone, apps, settings from the left, `Asked.centreX` 80 apart from `X_home` 328. Home's row has no home cell and two capsules, and its five tiles stand at their own centres, `Asked.tileX`, 121 apart from the map's 408, the one fixed point: they spread over the capsule, so `ChromeRules.shifts` and the slide went, and with them the second order and its probe.
**No layer on the rows**, where section 9 names one on `RowHome`. With `hasOverlappingRendering` false a group's alpha goes into each thing it draws, children included (hwui's `AlphaFilterCanvas` unrolls them), and what is in a row hardly overlaps. It is one off-screen buffer less in the frame that must answer, and 3 bitmaps and a few glyphs per frame are no more than the layer's quad.
**The plate** is one view at x 0 that only translates, on spring 520/0.86; from Home or to a panel with no cell it does not slide but stands or fades in place. A glyph goes white by how much of it the plate covers (`ChromeRules.coverage`, `IconButton.coverage`): a tint and a redraw of that one 72 x 84 cell, as section 5 words it. A row that is going takes no new touch from the first frame, and a touch that began on it hears it end (`Row.live`); under y 516 a touch on a row's blank parts stops there and does not reach the map.

**Volume.** `VolumeCluster` through `CarAudio`, as `Dock` did: a step at once on the press, repeats after 400 ms at 120 ms, stops at the end of the range. The number takes no tap; held 400 ms it mutes, and because the firmware tells of a mute about 300 ms late the word shows at once and what the unit says counts again a second later. It hears `CarAudio`'s listeners and `VOLUME_CHANGED_ACTION`, `STREAM_MUTE_CHANGED_ACTION`. The 4 px bar is a track with a clip to its rounded outline and a fill of the same width that slides under it: only a translation, on spring 520/1. A change of level shows it; a mute going on or off does not. With the row on screen `VolumeHud` shows nothing for the media stream (`Chrome.rowOnScreen`), so nothing covers pause and next at the moment he handles audio; with another app in front it does as before.
**Voice.** `Amri.wake` as the old quick button, its settings until the microphone is allowed. `Amri` has a state listener and `onLevel` now; the ring is one view scaled by the level, at most 15 times a second, only while listening. It is concentric with the disc: the mockup's stands 2 px lower (`top: 6px` for a 72 px ring in an 84 px cell whose centre the disc is not on), which reads as a mistake.
**Strip.** Status icons at x 16..106 with the hit x 0..118, the speed from x 118, the time ending at x 1008, on every panel but Home. The chevron, the gear row and the logo are gone. His report says `gearSource: GUESS`, `canReportsGear: false`, so `GearBadge` (30 x 30 at x 118, the speed moving to x 158) shows only with `Gear.canReports`. `StripSpeed`: the number is drawn, the sign and the Saher part are placed by translation, the distance is a label in a fixed box, so a new speed redraws one small view and nothing is laid out. Which panel has it is `ChromeRules.stripSpeed`; `chrome.speedCardHidden = true` is for Maps with a search or a place card over its card. The time is set once a minute, and while fixes come 200 ms after the next one, so a speed at the left and a minute at the right are never one damage rect.
**The mini player** (`Glance`, x 772..1008): `ChromeRules.mini`, every screen with a song; no song, nothing, and the cells do not move; on the Music panel only while it lies collapsed (moving, or on a trip), because parked the song is the whole screen. One condition, if he wants it back. A plate, the cover 48 at alpha .8 at night, the title at most 110, pause 34 in a hit of 56 x 84 at the driver's side; anywhere else on the plate `Screens.music`. A change is a 140 crossfade inside a dock that is showing, and comes right with the row otherwise. Next stays on the wheel keys, Home's card and the Music panel. The prayer capsule is gone: Home's clock has its one quiet line.

**`DriveState`** is section 6.0's table: `DriveRule`, pure, with the tests, and an object that feeds it the last fix younger than 5 s and `Gear.known`. `Gear`'s D from motion and its assumed P and D are not known here: they are guesses from the same speed, and a jump of the GPS at a standstill must not skip the 2 s. One listener list, told when the state changes. Note what the table does in a tunnel on a car with no gear data: the fix dies, 5 s later there is no speed and no gear, and that is Parked, at 100 km/h. It is what the table says and what `Gear` does today, and worth a word from the owner. The chrome is its first user: the car starting to move dismisses a Modal as Cancel.
**`system/Prayer.kt`** holds the one `PrayerTimes`, which the guidance's stops import too; `PrayerTimesTest` is its test, with the glance's `Prayer`, which keeps the day's times until the day or the place changes, or the clock is set back. So a tick does no solar work.

**The slots of section 6.0** are in `ShellRoot`: `snack` (`Snack` uses it in the shell, and keeps its own for an old activity), `night`, `flyer`, `ghost`. `ThemeSwap` holds its picture in `ghost` now and has no view of its own to add. They are INVISIBLE where the spec says GONE: drawn no more than a GONE view, and coming back they ask for no layout in the frame that counts. `NightPanel` builds itself at its first show: a long press on the strip's left end; speed, limit, and arrow and distance with a trip; a touch ends it; for a call, a Saher under 400 m and a turn under 300 m it opens in 140 ms and comes back 5 s after. Its arrow is `ManeuverView`, which the guidance's rebuild redraws. `Flyer.fly(bitmap, from, to, ms)` has been flown once by the probe and has no user yet.

**At the start.** The first hardware bitmap of a process brings up a GL context of the uploader's on the calling thread: about 600 ms on a loaded emulator, against 12 ms for each one after it, which put 800 ms into the window's `onCreate`. `Chrome.warm`, from `AuraApp`, does those bitmaps and the first prayer times on a thread at the process's start. Built in 56 to 96 ms then. On the unit the first bitmap's cost is not known.

**Measured**, `ChromeProbe --ei rows 15`, `--ei plates 15`, on a still gallery page, emulator-5578 sharing its GPU with four others, frame metrics; *ui* is the main thread (input, animation, layout, draw), *render* sync and commands, *own* both. Host under 40 % busy, 5 runs of the rows and 3 of the plate, about 290 and 240 frames a run: rows own p50 1.5 to 2.7 ms, p90 4.2 to 5.6, p99 7.8 to 13.2, max 8.2 to 15.1, none over 16; plate own p50 1.4 to 2.7, p99 6.0 to 9.7, max 8.9 to 14.2, none over 16; ui p50 0.6, p99 1.3 to 8.4. Host at 48 to 80 %: 1 to 3 frames of 240 over 16 (26 ms at most in render, 34 once on the main thread). With a build running beside it, up to 29 frames of 390 over 16, and as many in the spec's order, which moves nothing: that is the host's GL and CPU, not the row. `total` is 20 to 30 ms at p50 there, which is the emulator's composition. At rest, 70 s: one frame, the minute's tick; 60 s with no tick in it: none; 30 s at a steady 60 km/h with the strip's speed up: one, the tick. Recoloured in place by the real `ThemeSwap` against the chrome built anew, Home's row, dock on Maps, dock on Settings, both ways: 0 pixels differ. Against the mockups (`chrome-shots.ps1`, `gallery_compare.py`'s `c-` pages, the real chrome on the mockups' plates in the spec's order, 14 states, both themes, English too): 382 non-text rects, mean difference 0.95 per channel; those over 8 are the voice ring's 2 px (above) and Music with no wash under it, on purpose. Text differs by Chromium's LCD anti-aliasing and is at offset 0 everywhere but `stripTime` (up to 1 px: it is a little narrower than Chromium's). Layout passes through all of that: 0, in the rows' change, the plate's moves and at rest. PSS at Home, 40 s after a start, on and off in turns: 90.2, 89.5, 92.5 against 91.9, 90.1, 92.5 MB, no difference to be seen; 285 views against 221, Java heap 9.0 against 8.7 MB. On the unit add one theme's capsules and dock in gralloc, about 1 MB, computed. The debug APK, both built clean: +91,268 B, dex 87,780 (the probe's classes in it) and resources.arsc 3,456; no asset or res file. Merged with `v2-shell` in a scratch worktree (it merges clean, and builds), with the switch on, over the real Maps panel on the one map: Home to Maps and back own p50 2.65 ms, p99 12.1, 2 of 311 frames over 16; at rest on Maps 20 s: 0 window frames. None of this was run on the unit.

Debug hooks: `ChromeProbe` (its head lists them: `--es show`, `--es hold maps --ef at 60` for a still of the row's change, `--ef kmh 60` for fixes at a speed through the real path, `--ez listen`, `--ei vol`, `--ei rows`, `--ei plates`, `--ei rest`, `--ei panels`), `am broadcast -a com.abdllh.aura.debug.GEAR --es gear D`, `-a com.abdllh.aura.debug.VOLUME_KEY --es key up`. `scripts/chrome-shots.ps1 -Design <folder>`, gallery pages `chrome-orders` (the two rows), `r-chrome-wall`, `r-chrome-drive`, `r-chrome-room`.

Volume keys on the emulator: with nothing playing Android moves another stream, so `input keyevent KEYCODE_VOLUME_UP` changes no number there; `media volume --stream 3 --set 11` is a change from elsewhere, and the wheel's hook is what the unit does.

**Left for the screens:** Home's old cards; Music moving with a trip moves the plate with no panel: `chrome.show("music", true)`. Phone stays the firmware's dial pad in this version (`Chrome.open`, the design's fallback). Controls and the voice card: "The overlays" below.

## The base of the screens (2.0: `home/PanelStack.kt`, `home/AppsPanel.kt`, `nav/MapsPanel.kt` and its parts)
This branch is `v2-ds` and `v2-shell` merged over the map system, the chrome always on, and the old Dock and the quick buttons gone. It looks half done on purpose: the old cards in the new frame, each to be replaced by its builder. Home's old cards lie in y 44..506 (`CONTENT_TOP`, `CONTENT_BOTTOM`); so do Maps' (`TOP_EDGE`, `BOTTOM_EDGE`). Music and Settings still lie under the strip and the row. The dock's Apps cell opens `AppsPanel`, a stand-in with the old drawer over it, so the plate lands there and apps can still be started. `Route.better` and `NavSession.takeBetter` are gone: no late route comes any more.

**Maps' parts.** `MapsPanel` is the panel and what its parts share; each part is one file and one whole-screen layer, so two people can rebuild two of them at once and hardly meet:
* `nav/DriveColumn.kt`, in `columnLayer`: banner, trip bar, speed, arrival. For `drive-*`, scrims, column, mini and lyric chip.
* `nav/BrowseCards.kt`, in `browseLayer`: search, results, the place card, and the visit's logic with them: what is chosen, the route (`requestRoute` with `routeAsk`, `NO_ANSWER_YET`, `afterOthers`), Go, Home and Work, a place from the phone, `take(args)`. For `map-browse`, `search-*`, `place-*`, `from-phone-*`. The search uses the system's keyboard in this version.
* `nav/MapTools.kt`, in `toolsLayer`: compass, zoom, the way back to the car, attribution, what the camera does after a touch. It goes with the driving column, whose `drive-map-touched` is the most of it.
`MapsPart` is a `ContextWrapper`, as `Panel` is, so `label()` and `View(this)` work on `this`. The panel tells every part `build`, `sync` (NavSession changed; on every fix, so touch only what changed), `tripChanged`, `listen(on)` (what it hears goes on and off here: the panel is on screen, on top or under a panel beside the column), `onFace`. The hook points the parts have between them are few and by name:
* `MapsPanel.face`: section 6.0's row, `BROWSE`, `DRIVE_FREE` (Moving, no trip, no card, 10 s after the last touch anywhere on the panel; a touch brings browse back), `TRIP`. Worked out in one place, `syncFace`.
* `MapsPanel.cardOverColumn(on)`: browse says a search or place card lies over the column. The column hears `coveredByCard`, and `Chrome.speedCardHidden` follows, so the strip takes the speed when moving.
* `MapsPanel.overview(points)`, `panel.tools.forSearch(open)`, `recenter()`, `hideRecenter()`, `panel.syncNav()`.
* `SIDE` is what the camera leaves free at the start side, 492 for the old cards; `MAPS_NAV_TOP` in `MapController`. Both are the column's to set by `map-*.json`'s `camera`.
A rebuilt part drops what it has of the old look (`panelFill`, `bottomCard`, `roundIcon`, `MapsPanel.bottomCard`, `tools.controls` for the old speed cluster). When all three are done, `override val recolours get() = true` on the panel ends `rebuildLook`, and the new theme comes by `ThemeSwap`. `ThemeSwap.map` is set by `MapController` to a new style until `MapStyle` has paints that change in place: not section 8's way yet, but the map follows.
The voice button and its store hint left the trip bar: no spoken navigation in 2.0 (`unit-facts.md`).

**Beside the column.** On a trip Apps, Settings and Phone are a card at x 392..1008 and the driver's column stays (section 6.0). `override val besideColumn get() = true`, `opaque` and `solid` by the trip (`NavSession.state == IDLE`), and `onTrip(on)` to go from room to card and back. The stack keeps Maps' frame drawn at the bottom of the layer under such a panel, taking the touches the panel leaves, with its parts listening (`Panel.onBeside`) and the map in Maps' camera; it does not give it `onShow`, since it is not on top. A trip that begins or ends under the panel is heard in the stack (`tripChanged`). From Maps the card rises over the column; from Home its wall fades off them (`PanelMotion.between(.., beside)`). Walked with `setprop debug.aura.probebeside 1` and the probe panel on a simulated trip: the column goes on counting, End answers through the panel and the column goes, Back to Maps. No real panel uses it yet.

**Debug hooks on the old paths** still work: `MapsActivity --es mock_loc`, a long press on the map for a pin, a long press on Go for a simulated trip, `--es q`, the gallery and the chrome's probe. `dev-install.ps1` now also sets `debug.aura.mapguard off` (needs `adb root`; gone at the emulator's next boot): two force-stops with the map up within 3 minutes of its first frame were enough for `MapGuard` to refuse Home its live map until the next boot, and Home then shows its picture with no word why.

## The screens together: 2.0.0-beta.1
The six screen branches and the shell's last three commits over `v2-app`, in that order: shell, Home, Settings, overlays, Maps, Drive, Music. Where two of them met, and what came of it:
* **The car's size from above is one number,** `CarMarker.topPx`. Home's `setCarPx` scaled the view and Drive's `carTopPx` drew inside it, so together a car on Home would have been scaled twice. `setCarPx` now moves `topPx`, by the blend's own k from 48 to 64 going to Maps; the tilted views fill the 108 px view whatever it says. The car drawn before its frames are read keeps its one size: it is on the screen for a moment at a process's start.
* **`AuraMap.steer`:** `flatNav` first (Home's card on a trip: flat, zoom 14.6, the room as it is), then `driveShape` (Maps' table), else the old share of a room. Settings' `mapView` holds in all of them: a tilt only when the view is the tilted one, north up by `b`.
* **`syncLayers`** leaves the layers alone while `setLabels` has them off, also `MapStyle.DRIVE_ONLY`, which are real layers since Drive and would show on Home's card on a trip. `applyLabels` brings them back through it.
* **`PlaceInbox.decide`:** another app in front, an unpaired phone or a guess: the banner asks; then the voice's Go (`voiceGo`), then the phone's count (`countdownMs`). The two count alike, 5 s, and are two constants because the first ends on the voice card and the second on Maps' own.
* **`AuraApp.getResources`** is `LocaleHelper.appResources`, Settings' of the two; Maps' did the same with `wrap`. `Entry`'s wrap and `HomeActivity`'s `Locale.setDefault` stay: harmless beside it.
* **One lyric chip,** Music's. Drive's and its `--es chip` hook are gone. The mini's cover opens the source sheet from any screen with a mini.
* **Kept panels** are built ahead one at a time when Home is quiet (`prepareAhead`, the shell's), counted from the entrance's first frame. The update check is the shell's tick.
* **What Maps' parts took from the shell by hand:** the soft input above, and a route he asked for survives a panel over Maps (`!isGone && !isOff`, not `isTop`). `MapsPart`'s old helpers are gone; `MapsPanel.rebuildLook` is dead code while `recolours` is true.

Found in the one walk, fixed: End's undo on Home under 1 km to the turn handed over to Maps at once, because ending the trip forgot that he had chosen Home for this turn (`handedBefore`). A choice of a row of equal ones lost the ends of its word on the 628 wide card beside the column (`الشمال فوق`): `Segmented`'s `equal` items have 2 px of padding, not 10. Music lying collapsed waited 10 s for `drive-free` with browse's tools and credit over the chip (`MapsPanel.onBeside`: nobody's hand is on it). An English message on an Arabic screen had its question mark at the wrong end (`MessageBanner`, `unicodeWrap`). The first-time hint for Bluetooth's delay and `drive_arrived` in English were cut in their places.

Walked once on `emulator-5576`, AMRI as the real home: Home, Maps, search, a place with two routes, Go, NavSim to the arrival in both languages, Home on a trip, Music with a stand-in Naghm and lyrics, full lyrics, the sheets, Apps, the seven pages, the pack page, licences, a call, a message, the voice card, Controls, the volume, a phone's place on and off a trip, the voice's Go, day and night by the switch, another app and back, reverse by `GEAR`, the Screen check to its report. No entry in the crash buffer. `guard-test.py --only throw --new-version 2.0.0-beta.1:2000001`: back on 1.19.1 in 2 s, six checks passed. 432 unit tests, 2 skipped.

The release, `aura-2.0.0-beta.1.apk`: 33,127,559 B, armeabi-v7a only, code 2000001, the certificate of 1.19.1. R8 with `-PwithEmulatorAbi` walked once, on the commit before the last three small fixes: Home, Maps, a search by the system's keyboard, two routes of 84 and 91 km on the pack, Go, Home on a trip, Music, Apps, Settings, the pack page, the Workshop, night by Controls, the language by its page, End; nothing in the crash buffer and no class or method missing. For that run `pm uninstall` deletes the pack with the data: `cp -a files/maps2 /data/local/tmp/` first, back with the new uid's `chown -R` and `restorecon`. Grants the ROM gives: location, audio, `SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS` by `appops`, `WRITE_SECURE_SETTINGS`; without `WRITE_SETTINGS` Android's own page opens over the panel at the first brightness or theme change.

Known and left: `ThemeSwap.map` is still a new style for the map, not `MapStyle.paints` in place (section 8), so the map redraws at a theme change; on a trip and on Home that is under the ghost's fade, and only the unit can say how it feels. Apps and Settings beside the column step away under 300 m to a turn (`ui/TurnAway`, Settings' branch; seen at 70 m); the phone panel it was also meant for is not built. A touch on the map under Music lying collapsed brings browse's zoom, locate and credit for 10 s, and locate lies over the chip's first 56 px then. The route-guard collision layer of section 11 is not there.

## Home (2.0: `home/HomePanel.kt`, `HomeCard.kt`, `HomeWidget.kt`, `PanelMotion.kt`, `ui/CarGl.kt`, `ui/CarStage.kt`, debug `HomeProbe`)
Direction 4 of the design, section 6.1. `CarPanel`, `MapPanel`, `MediaCard`, `Wall`, `GearView`, `CarFrames` and the car's old frames are gone; their logic is here. Coordinates are the window's, the layout tables' numbers as they are: each part is a group laid out as the whole window (`MATCH_WINDOW`), and its children go in by `at` and `box`.

**The wall.** `wall-hole-<theme>` is one `ImageView`, 1024 x 600, with two holes: the map card, with its corners and shadow baked round it, and the GL rect. Under the card's hole stands `MapBackdropView` (the kept picture, or the grid when `MapGuard` refused the map) until the map has drawn; `map-edge` lies over it. No path, no clip, no layer. A panel that recolours in place: `recolours` true, and the old rebuild, the picture by `PixelCopy` and the start cover are gone. The car's picture in the theme's ghost is `TextureView.getBitmap`, through `ThemeSwap.ghostExtra`; the song's wash for the other theme is made on the swap's worker (a ready step).

**Faces, the simpler Home (2.0.0-beta.2, CHANGES.md section 3).** No logo, no Hijri, no chips, no progress bar, no previous, no source word. Parked, moving and on a trip are the same views: `HomeFaces` holds the faces of a part, all built and laid out, of which one shows, a crossfade in alpha on one frame callback, 180 `enter` in, 120 `exit` out. `HomeCard`: one search pill; the speed in its rect; turn pill and ETA pill. `HomeWidget`: one layout parked and moving, a cover of 152, title, artist, pause and next, so nothing moves under the finger, and nothing playing; `movingNow` only says a new song comes as a cut and the cover does not fly. `HomeClock`: the digits ending at x 1008, one date line, and the next prayer in `prayerHome`, `ink2`. A new speed, distance or ETA is a new text in a fixed box with spans (`withUnit`, `small`), one damage rect, and no layout after the build. `DriveState` says Moving; a trip takes the moving look at a red light too, as `home-trip` has no parked form. At night the car dims in 300 ms, `u_shown` .55, and then nothing draws. 20 s at rest on Home: 0 window frames.

**The trip on Home.** `MapController.placeHome`: `map-*.json`'s `home` (padding 108, 644, 288, 16: the car at 198, 210) and `homeTrip` (0, 644, 156, 16: 198, 222, heading up, flat, `AuraMap.flatNav`), both at zoom 14.6; no turn arrow there. `HomePanel.handOver`: moving, under 1 km to the next turn, Maps, once for that turn; Home pressed with a turn near stays for that turn. End is `NavSession.stop` with a hit of 122 x 80, and it leaves `انتهت الرحلة` and `رجّعها` in the ETA pill's own rect for 5 s, as section 1 has it for every screen with the ETA card, the place and the route in memory until then, gone with the panel. The undo is not a trip starting: Home stays. `AuraMap.setLabels(false)` takes the symbol layers and the extras' circles off while Home shows (back at the start of a move to Maps, off again when Home is at rest, so they do not blink in the open map); `setCarPx` is the sprite's size, 48 and 40 here, 64 on browse, by the blend's own k.

**The car's new room (2.0.0-beta.2, CHANGES.md section 3).** 552 px wide at frame 108, against 590, by a scale at draw time, with no new frames: `car-stage.json` and the frames go into the bake's name, so they stay as they were baked and nothing is baked again. `car-place.json` is the design's new table: `scale` .335 and `origin` (291, 88), against the stage's .358 and (261, 67), which give `CarTable.scale`, `x`, `y`, and the per-frame `dx` (-12.7..0.3; the zoom of the old table goes). Against the design's own boxes this lands within 1 px on every frame looked at. The shader is the design's, its `u_dolly` taking those numbers. A frame sampled between its pixels takes in K at its edges: nothing to see at night, and a dotted light line round the sill and the tyres by day. So the crisp copy has its own texture and rect (`restBox`, `u_bb`), 1:1 on the screen: frame 108 is the design's new `rest-*.webp`, any other angle its frame drawn at that scale on a Canvas in `restRgb`, premultiplied. While the car turns the line is there by day, as it was in beta.1 at every angle but 108. `wall-gl` and `wall-hole` are the design's new ones, the GL rect x 392..1024.

**The car** (section 10). `CarGlView`: rest frame and `wall-gl` go up first (RGB888), so it draws from its first frame, before anything is baked; the 180 ETC1 frames follow, one per message, the entrance's 78 to 108 first. `CarEtc` bakes at the first run, on every core up to 4 at background priority: decode, K where the alpha is 0, `ETC1.encodeImage`, into `no_backup/car-etc-<hash>.bin`, the theme that shows first, then the other's. 2.4 s a theme on the emulator; about 17 s on the unit (its Screen check: ETC1 of 512 x 512 in 444 ms, so about 375 ms a frame on one of 4 real cores, whatever the 8 its firmware says). It belongs to no view and starts with the first start; the file holds each finished frame with its CRC from the start, so a process that dies, a unit that loses power, a panel or the Screen check cost it the frames on their way at most. Frames go coarse to fine (`CarBakeOrder`: 32 apart, then 16, 8...), to the GPU as they come. `debug.aura.car.bakems` makes a frame as long as asked on the emulator (335 is the unit's). The shader is `tools/car_stage.frag` with the uniforms of `shader_proof.mjs`, and `u_vr` in the vertex shader so that no uniform is shared across the two stages with a possibly different precision. On the emulator the GL rect at rest against the design's own proof (`gl-night-108.png`): mean 0.14 levels, max 9. Its own frame time picks the program: over 24 ms for 30 frames `CHEAP_MIRROR`, under 18 ms for 120 back. `CarStage` is the old turntable's feel with no CPU path, no zoom (each frame's dolly is in `car-stage.json`) and no tap. From 6 frames in the GPU a finger turns it, each frame standing in for the nearest that is not in, with its own numbers, and at rest the crisp copy is the exact angle anyway. A drag that cannot turn it hears why in a Snack, once per drag (`strings_car.xml`): how far the bake has come (with the coarse turn too: the steps need saying), that it stopped on an error (and the drag asks again), or that the car moves. Nothing shows at rest. Locked is `DriveState.moving` alone: a guessed gear is no gear there, and a trip at a red light has the moving look and a car that stands. `DriveRule` wants a second fix for its 2 s: at the unit's 0.5 Hz one fix's jump, its speed standing for the 2 s to the next one, was the whole of it. The Screen check tells all this by itself: `d_memory.car` and `carAtEnd` (`CarStage.report`: the bake of each theme, frames in the GPU, locked, the last drag and what it came to, and how many fingers came down on the car at the window beside how many reached the stage). `carOnGpu` and `carFps` say neither: the rest frame is drawing before anything is baked, and the entrance's fade is 25 frames at 60. With no GL at all: the rest frame in an `ImageView` over `wall-gl`, its alpha being K, and no floor. Frames are not dropped when Home is covered, as section 10 has it: the unit has 4 GB. One difference from section 10's plan: the wall's fade fades the `TextureView` itself, through its own layer of 640 x 330, and no rest frame in an `ImageView` over it: by the time it matters `u_shown` is on its way to 0, the layer lasts 240 ms, and on the unit a layer of that size is no measurable cost; worth a look with the unit's numbers.

**"I cannot turn the car" (2.0.0-beta.1, the report of 2026-11-05 08:37Z).** What the report could say: `carOnGpu` true and `carFps` 60 are the rest frame and the entrance's fade; `d_logs` looked for `GPU car` and the line had become `GL car`, so its silence was nothing; the check would not have started on a trip, the gear was a guess and the GPS said 0 in 5 fixes of 5, so it was not locked then. What it could not say is what a finger had met before. The first start of a build with a new frame file bakes it, and until the last frame was in the GPU a drag did nothing and said nothing: at least 34 s on two threads, with the process starting twice in the first 5 s (in both reports, 1.19.1 too) and the first start's work lost. The likely reading is that he tried then. Mali's own count points the same way: in the context with the frames, 46.3 MB less 16.6 of hwui less 18.3 of frames leaves 11.4 MB, and 1.19.1 with its frames known to be in left 11.0 (37.2 - 5.5 - 20.7), so by the check, 2 min 45 s after that start, they were probably in. The unit does not cold boot with the car (the same report: 49.5 h awake in 18.6 days of uptime, and one `system_server` in both reports): it sleeps, and the file's marks serve a process restart as well as they would a boot. Found on the way, all of them ways to stay stuck in silence: a theme and back before the GL thread ran left `framesIn` false for good, as did one GL error at any frame and any failure of the bake, and the bake's end deleted the other theme's half-done file.

**The first start** (section 6.7). `BootHome` shows `wall-<theme>` with the logo 200 wide at (412, 266); the theme and the language come from the one file it reads, `boot_look` in device-protected storage (`BootFlag`, kept at every theme change; with none, night and Arabic). 0.5 levels from the mockup on the logo, 0 on the wall. `am start -n com.abdllh.aura/.home.BootHome --ez hold true` with `setprop debug.aura.boothome 1` keeps it up for a look. Home's first frame is the same picture: the logo on top fading in 200 `move`, and a part of `wall-<theme>` over the card's side, so that the hole and its shadow come with the card and not at t 0. Then section 6.7's table on one frame callback: the car when its GL is up and at 120 at the earliest (with the spin if its 16 frames are in), card 160, widget 200, clock 240, the chrome 280. Any tap skips it. Only at the process's first show, and not with the owner's "Animated screen changes" off. `bootanimation.zip` stays for now: `BootAnim.ensure` also serves updates, and leaving it is more than one line.

**Between panels** (section 9). `PanelMotion.between` sends every pair with Home to `WithHome`: Home's own parts by `HomePanel.leaving` and `returning`, on one frame callback that turns back from where it is; the other panel as one layer, each starting after the frame that holds its cost. To Maps (or a panel beside the column on a trip): texts and cards 0/120 `exit`, `u_shown` 40/100 `exit`, wall and GL view 40/240 `move`, Maps' frame 60/200 `move` rising 8; back, wall and car 0/240 `enter`, parts 60/180 `enter`, Maps' frame 120 `exit`. To Music: texts 0/120, Music's frame 0/240 `move`, and Home under it as it is until the stack puts it away; back the frame 180 `exit`, parts 60/180. To a room: the same at 200 and rising 8; back 180. Camera, on the map's own frames: `setRoom`'s spring, `k = (1 + wt)e^-wt`, `w` 13, from the moment the panel says `toMaps`. The rows: the chrome, by `Front`. `Panel.onSettle(top)` puts what a panel moved itself at rest, wherever a move stopped: the stack calls it from `settle`, so a move cut by the window pausing leaves nothing halfway. Pairs without Home keep `OverMap` and `Rise`. One layer for the whole of the other panel, not the spec's part by part, so scrims rise 8 with the cards, at an alpha where it cannot be seen: the per-part laws of Maps' column, Music's lyrics and text, and the rooms are a branch in `between` when their panels are built, if they are worth it. **The Home key:** Android 10 hands a resumed activity its new intent inside a pause of its own (`am_on_paused_called`, `am_new_intent`, `am_on_resume_called`, 10 ms apart), and the stack makes no move for a window that is not resumed: every press of the key was a cut, with the dock's Home cell fading. `onNewIntent` with the window started and not resumed posts its panel change, which runs right after that resume; with something else on top it runs as it did. Moves looked at in `screenrecord` frames (`adb shell screenrecord`, then `ffmpeg` with `select` and `tile`), against `motion-home-maps.png`: Home to Maps and back by the map card, the dock and the key, to Music and back with the flight, a panel beside the column on a trip, a change of mind in the middle.

**Debug.** `am broadcast -n com.abdllh.aura/.debug.HomeProbe` (its head lists the extras) and `scripts/home-shots.ps1 -Serial emulator-5578 [-States ...] [-Themes ...] [-Lang en]`, which leaves the language as it set it (`--es lang` by hand) and the strip's speed from `trip` on the states after it (the probe's hold, not the screen's). Compared with `simple/screens/home-*.png` by eye and in crops, night, day and English: the same rects, the car at rest the same to the pixel but for the map under it. A song over Bluetooth with no phone: `am broadcast -a com.bt.ACTION_AVRCP_MUSIC_ID3 --ez sim true --es extra_avrcp_id3_title Hello --es extra_avrcp_id3_artist Adele`, and `-a com.bt.ACTION_A2DP_RELEASE` for the link going. An emulator under load can leave a window stale (`dequeueBuffer failed`, "The gallery" above): the probe asks for a layout after each change, and a picture that does not change is that.

**The cover's flight.** `Panel.flightCover`, the panel's own cover view, and `flightRadius`: `HomePanel.flyOut` and `flyBack` fly a square copy (`HomeWidget.flightBitmap`, made once per song on `Backdrop`'s worker) through `root.flyer`, 320 `move` to Music and 240 otherwise, with that view hidden until the last 60 ms, in which it comes up under the flyer. Only parked, with a cover. A panel that says null simply fades: Maps says null until the mini wants it (`mini`'s cover and radius 12 are one line there). The copy is already rounded at 20, the outline does the rest.

**Bluetooth lost in a song** (section 12). `MediaMonitor` says nothing plays, or a paused song of another source, the moment the link goes; `HomeWidget.take` keeps the Bluetooth song on the card, paused, with `انقطع البلوتوث` for its sub, until the link is back or something else plays. A tap on its transport opens Music, where the sources are.

**The no-cover wash** follows section 4's and 12's words, `Backdrop` with the logo's hue, deep blue at night and near white by day, as Music's `none-*` has it. The mockup `home-no-cover` drew the sea's wash there; on the same song the two panels now agree.

**Not done:** the Phone panel of CHANGES.md section 1 and its keyboard: Phone is the firmware's dial pad and the search the system's keyboard in this version. `آخر أغنية` under the song at a start: nothing keeps a Bluetooth song across a boot, and the unit's own last song is already there, paused, with its own words. The Maps tile disabled with its reason when the map was refused: the shell lets the driver ask for Maps whatever `MapGuard` said at Home (`MapController.startForMaps`), a crash then being one he asked for, and the guard is over at the next boot, which on his car is the next drive; a disabled tile would take that away. Under the load of five emulators and builds no frame time here means anything: nothing about smoothness has been measured.

## The overlays (2.0: `ui/OverlayWindow.kt`, `phone/CallBanner.kt`, `phone/MessageBanner.kt`, `phone/OverlayOrder.kt`, `system/VolumeHud.kt`, `voice/AmriOverlay.kt`, `link/TripCard.kt`, `nav/GuidePill.kt`, `home/ControlsSheet.kt`)
The windows over everything, section 6.5 of the design, and Controls. Their logic is what it was (what `Calls`, `Amri`, `PlaceInbox` and the notification listener decide, the guards, the timers); the look and the place are new. "Settings, the call card and the overlays (1.12)" below is the history of that logic.

**One window class.** `OverlayWindow(ctx, name, x, y, w, h, touchable, keepScreenOn)`: a fixed rect in screen pixels, content placed in `root` in the window's own coordinates, on a `SurfaceCard(OVERLAY)` placed by `Surface.OVERLAY.at`. Never `FLAG_LAYOUT_NO_LIMITS`: a surface wider than the window is simply cut by it, as the design says; but the window itself must lie inside the screen, or the window manager moves it (the first banner came out 32 px to the left: 716 wide from x 340). A window is larger than what shows, for the shadow and the entrance, and a touch on its transparent part must reach what is under it: `visible(l, t, r, b)` is the part that takes touches, through `addOnComputeInternalInsetsListener` and `TOUCHABLE_INSETS_REGION` by reflection (`dumpsys window` says `mTouchableInsets=3`). If those are ever missing, the window ends at the card and its content is scrolled inside it, which cuts the shadow and changes nothing else. A theme reaches an open window through `Look.opened`; `Chrome`'s `Theme.changed` now also tells them, for a theme changed by an old panel's own path, and `syncLook()` catches up when a window is shown again. A banner's buttons are 72 drawn and 98 to the touch: `TallHit`. `ABtn.sidePad` is their side room; a word that does not fit breaks into a cut second line, it is not shortened: `Hang up` has `sidePad` 0 and a 26 glyph for that. `overlayMs` is the moving factor, 0.7 of the time. A whole banner fades on a layer (`anim` adds it), never a 9-patch per frame: the unit's jank was the big 9-patch (`unit-facts.md`).

**The call.** Answer at x 405, Decline at x 551; once answered Mute and `قفل` in the same places: Answer fades into Mute, Decline is the same button with another word, so red stays where it was. Both are not taken for 600 ms after the answer or after the phone said it was answered: a double press on Answer can only reach Mute, and it does nothing. Mute is `BTFeature.setMute` 58, an int, `Calls.setMicMuted`; a service that could not be told leaves the plate as it was, and the end of a call puts the microphone back. It has not been tried on the unit. 3 s after answering, `MS_bannerShrink`, the name and the avatar go in 120, and the wide plate fades in 180 off a small one that has lain under it all the time, so the part they share never lets the map through; only then is the window's width set, once. No timer, no pulse. In the small form the end of a call simply leaves; in the wide one `انتهت المكالمة` shows for `Calls`' 1.5 s with the buttons gone.

**The message.** App · sender, then `رسالة جديدة` while moving, the text only when parked with Notices' switch on (`Asked.MESSAGE_TEXT_MOVING`); the car starting to move takes the text off an open card at once. `اسمعها` is `Amri.readOut`: Amri's voice, the music waiting as for an answer, the wake word off the microphone, and nothing on the voice card, since a moving car shows no message's words. A tap on the card opens the conversation only when parked. Flick up as before.

**Who gives way: `OverlayOrder`, one small object.** `messageWaits`: a call, a turn under 300 m, reverse. A message held back is looked at once a second and shows when that is over, for up to 2 minutes; a call or the reverse gear coming over an open message sends it back to wait. A volume change shows whatever else is up: it lies in the column, and nothing overlaps it. `VolumeHud.reposition` and `CallBanner.bottom` went with the old place at the top.

**The volume over another app.** `Chrome.rowOnScreen` decides as before. 364 x 80 at (16, 424), not touchable, always left to right. The design has no picture of it, so the positions inside are this file's: speaker 30 at 18, bar 226 x 6 at 62, number at 296.

**The voice card.** At (392, 424), 616 wide, beside the column on every screen, Home too: at x 12 it would cover End. It covers the map's car for the seconds he is talking. One line that changes in place, 80 high; a long answer grows it upward to 132: the tall plate fades in over the small one, which goes when it is covered, and nothing is laid out but the text's. The window is y 330..516 for both, with its touches on what shows, and ends at 516 so the row stays live. `AmriOverlay.show(.., actions)` for a fault's Settings chip, `ask(c, text, actions, ms, onTimeout)` for a question, which what Amri says meanwhile does not take off. The ring and the level stay the row's (`VoiceButton`, `Amri.onLevel`); the old pill's breathing mic is gone. A gear change into reverse takes the card away, not only the next `show`.
**Go, a change of logic worth knowing.** `ودني ...` with one clear winner used to start at once, with a cancel card for 5 s. The design draws the place with Go on the voice card, and a Go that must be pressed would end a hands-free request. So `PlaceInbox.voiceGo` shows the place with Go and starts by itself after 5 s (`VOICE_GO_MS`): Go spares the wait, Cancel is the old cancel. On a trip the same request asks yes or no on the voice card, `بدّلها` and `خلّ رحلتي`, 30 s; after a hold (a call, reverse) it is the banner as before.

**The place from outside** (`TripCard`) is `from-phone-on-trip`'s banner in the call's place, for every path: the main answer at x 405, "no" as the close at x 551, a lone cancel as a plain button at x 405, and with nothing to press what happened gets two lines. A trip that started says `بدأت الاتجاهات` on the small line and the place whole on the big one: the old sentence in one line ran out before its place. The running-out bar went: no countdown on a moving screen. It waits 30 s on a trip, as the design has it (it was 20).

**Guidance over another app** (`GuidePill`): at the turn card's place, 364 x 96, with a trip and `!Front.ownResumed`, `Front.addOwnListener` telling at once. A grace of 800 ms, since AMRI's own starts pass through a pause. A touch goes to Maps. The design words it in one line and draws nothing, so what is inside is this file's.

**Controls.** `Sheet(side = true)` at x 16, or x 392 on a trip (`placeOnTrip` at each open). Brightness as before, the theme by the shell's path (`ThemeSwap.run` when what is on top `recolours`, else what is on top restyles itself as it always did, until the last old panel goes, and the sheet takes the look itself, since that restyle does not reach it), the four tiles. A tile opens Android's page as the old ones did: Wi-Fi's on-state is `Actions.wifiConnected`, Bluetooth's the firmware's link, the hotspot's the hidden `isWifiApEnabled`. Screen off: moving, or with no stock launcher to ask, the Night Panel (`root.night.show()`); parked, the backlight off, as today. `syncVolume` is an empty stay for `HomePanel` until its rebuild. A touch outside a side sheet closes it and goes on to what it landed on (`Sheet.dispatchTouchEvent`), so volume, End, next and the voice stay one touch away with a sheet open, which section 1 asks; a tap on the strip must not reopen what its own touch closed (`closedByTouchAt`).

Debug hooks: `am broadcast -a com.abdllh.aura.debug.CALL --es state ring|dial|talk|end --es name ..`; `.. CALL --es message .. --es from .. --es app ..`, through `MessageBanner.show`'s own door; `am broadcast -a com.abdllh.aura.debug.AMRI --es card listen|hear|look|answer|go|yesno|error|long|off`; the volume as in "The chrome". `dumpsys window windows | grep -A3 Aura` shows the windows and their touch insets. On an emulator `lang` and `theme` by `SettingsActivity --es`, then a force-stop and Home. With `debug.aura.unit 1` set nobody answers back on an emulator: after Answer, `--es state talk`.

## The driving column (2.0: `nav/DriveColumn.kt`, `nav/drive/`, `nav/MapTools.kt`, the camera in `nav/AuraMap.kt`)
What he watches at speed, SPEC.md section 6.2, in `columnLayer` and `toolsLayer`, at the mockups' pixels with `at` and `Surface.at`: from the left in both languages, so nothing here is LTR by a flag, and a label's own start is still the language's. `DriveColumn` holds the cards and decides which are up (`syncCards`), `drive/TurnCard.kt`, `drive/Cards.kt` (speed, Saher, ETA, the arrival's actions, lyric chip) and `drive/MiniCard.kt` are the cards, `drive/TurnWords.kt` says the turn card's two lines, `drive/Bits.kt` the rest.

**No layout.** Every card is built and laid out once and goes INVISIBLE, never GONE, and each label has an exact box, so a new number is `setText` on a fixed view: an `invalidate` and nothing else, and a card that comes asks for no layout. A card comes and goes through `showCard`, which is section 9's table with the moving factor (`moveMs`); `View.anim` puts a card on a layer for the run, so a big surface fades as one. A label is a `Line`: `hasOverlappingRendering` false, or a label with spans would take an off-screen buffer for each frame of its fade. Spans keep the colour of their time, so the column reads `sync()` again from a `look` on its layer. Over the limit is a `Tweens` part: the plate's alpha and the two colours, 140 ms, a redraw of three small views.

**The arrow.** `ManeuverView` draws the one family of icons now (`turn-right`, `ramp-left`, `fork-right`, `merge-left`; never mirrored), and only the roundabout is drawn: the old sweep and angle, the design's look. The mockup's SVG arc goes the wrong way round with a wrong centre (`large-arc` with a fixed sweep flag), so the glyph is the old maths and not its path. The stubs for the exits passed are spread evenly over the way round: the step does not say where each arm is. `exit` is what the sign may say (`Told.ringNumber`), so a ring whose number the guidance will not say has no stubs.

**The words.** `TurnWords.lines`: the road line is what the manoeuvre leads onto, Latin as the guidance shows it, or a roundabout's way out (`Guidance.ringWay`); with no name, half the turns here, it takes the landmark or the action. The hint is one fact: `Guidance.landmark` (the signal counted from the car, the roundabout, a flyover or tunnel, a place), else the close "then" (`ثم لف يسار`, the old banner's rule), else the action in words (`Guidance.actionWords`). Past `QUIET_M` (2 km) it is the road he is on and `استمر، ثم ...` on the quiet tile with `go-straight`. With no guidance in this language: `Instructions`, as before. Durations through `Units.spelled`, with the Arabic agreement; `Units.duration` stays for what keeps the short form.

**End and its undo.** `endNav` keeps the place and the route, stops the trip, and the ETA card's rect says `انتهت الرحلة` with `رجّعها` for `MS_undoEnd`; moving, `panel.freeNow()` makes it `drive-free` at once. `undo` is `NavSession.start(.., remember = false)`, which finds the car on the old route from where it is. A new trip clears it. **Arrival:** `Done`, the 30 s of `NavSession`, or driving off having stood (`stoodAtArrival`) end it; `Save` goes to `Places.favourites`, the phone's list, the only list of places there is with no pick flow. The line under the name is the real time since `NavSession.startedAt` and the route's distance.

**Saher.** `RoadWatch` knows a camera from 1000 m now (the card and the strip's speed), and its tone still comes at 600 m (`CAMERA_TONE_M`). `Guidance.nextAlert` takes the reach; its tests keep the 600 of the voice's word.

**Map tools** at x 384: `syncTools` is the whole of who shows when. Browse: zoom and locate, the compass when the map is turned, the attribution. Trip: from a touch on the map (what nothing over it took: `MapsPanel.root`), gone 5 s after the last touch on the panel, unless the camera is free: then all stay and locate is `accentInk`. Whole route: locate alone. `drive-free`: none. A free camera comes back by itself moving, 10 s after the last touch (`MS_cameraBack`), a whole route after 20 s (6.2's table; `camera.freedCamera` says "the same rule", and 10 s of a whole route is hardly time to read it), both at once under 300 m to a turn; parked they stay. "A camera here" is the logic of `CameraMark`, copied, since Home's old `MapPanel` still builds that class; it goes with it. No guidance mute: no spoken navigation.

**The camera.** `AuraMap.driveShape` (true on Maps) takes the driving camera from `map-*.json`: top padding 270 at 40 km/h to 340 at 90 (`DRIVE_TOP_*`), car at x 701, x 800 with the next step left within 1.5 km (`setAhead`, 1.5 km by `MapController`), arrival zoom 17, tilt 44, top 240, each eased with its tau (1.2 zoom, 0.8 the rest). `navT`, `navL`, `navR`, `navB` are px of the surface; the home screen keeps its share of the card through the same fields. `ringFit`: near a roundabout (the near rule, and all the way round) the ring, 100 m of the exit arm and the car fit in x 388..1020, y 44..506 by bounds along the map's bearing, tilt 32, zoom at most 18, foreshortened by cos 32 and 90 % of the area for the rest of the perspective; the car stands where that leaves it, as in the mockup. `setRoom(378, 40, 0, 80)` is browse: the car at (701, 280). `overview` is left in both languages now, north up, flat, at most zoom 16. `BLEND_MS` is 750, `northUp` 480. The turn's arrow may now shrink to 0.55 at high zoom, or at a ring its head in metres filled the frame.

**The style while the camera drives** (`Camera.NAVIGATE`, on a trip or in `drive-free`): `MapStyle.NAV_HIDDEN` go, `DRIVE_ONLY` come, by visibility, in `syncLayers`. `drive-name-major` and `drive-name-next` lie under the route, one size (21 px, 20 in English), halo 2.2, spacing 900. Their filters come from `AuraMap.setRoads`, at most once per turn: the big roads but not the one the car is on, the road of the next turn, and with no trip primary and secondary too. `poi-drive` is fuel and mosques by the pack's kind, in Arabic only a name that begins at or after `ء`: expressions compare strings by code point. Its rank floor is 45, not band a's, because those are the only places on the map then. A filter that matches nothing has to be an expression, `["==",["get","class"],""]`: `["==",1,0]` reads as an old-style filter, the log says `filter property must be a string` and the style loads without the layer, with nothing else to show for it. The destination is a pin of its own, a signed distance field made once (`destPin`: icons in colour once drew black on the unit's tilted map), with its name at 22 px; browse can give the name too (`MapController.setDestination(p, name)`). **Whoever hides the map's labels on Home's card: `DRIVE_ONLY` must go as well**, since the camera on a trip there is `NAVIGATE`.

**The car** is 108 px (`pack_map.py D:\k2501-work\car3d\map_tilt D:\k2501-work\car3d\map_top app/src/main/assets/carmap --size 108`, 212 KB), and on a flat map the view from above is drawn at `AuraMap.carTopPx`, 64 on Maps and 48 on Home. Still the `View` at the camera's point: the decided fallback. The old halo under it is gone on every camera, since no mockup has one. The arrival is no left turn (`TurnWords.isLeft`), whatever side of the road the place is on: its camera has the car at x 701.

**The process's language.** A change of the system's configuration puts the system's language back as `Locale.getDefault()`, with Prefs saying another. The map's style, the route's words and `Units.arrival` ask the default, and with the UI in Arabic on an English emulator they came out in English: `AM` in an Arabic ETA, no guidance words (`guide.arabic` false). `HomeActivity` sets it again from its own resources at `onCreate`, `onResume` and on a configuration change. Nothing on a unit whose system is in the UI's language.

**For Music:** the lyric chip is Music's own (`music/LyricChip`, one line in `MapsPanel`); the column has none. The mini's title goes through `Chrome.open(Screens.MUSIC)`, so what a trip makes of that is one line there; the cover opens `MusicPanel.openSources(shell)`.

Debug hooks, `am broadcast -a com.abdllh.aura.debug.NAV`: `--es road 80,87,camera,380` and `off` (what `RoadWatch` says), `--es lanes l,s,s,r*,r*` and `off` (no route on the emulator has real lanes), `--ez reroute true|false` (a real reroute is over in a moment), `--es chip words_with_underscores` and `off`, with the old `--es fix`, `--es go`, `--ei sim`, `--ez end`, `--ez dump`. A name in `--es go` with a space goes through `adb shell` as two words, and `am` then sends nothing. After a `NavSim` arrival the emulator's own GPS takes over and the car jumps: to see the pin, drive the last metres with `--es fix`, which keeps it simulated. `--es swap day` to `GalleryReceiver` runs the real `ThemeSwap` over Maps; the gallery's other extras open it and take the chrome off until the next start.

Walked on the emulator, both themes, Arabic and English, with the pictures against the mockups: far, turn, near, lanes, roundabout, over the limit, Saher plain and hot, bump, rerouting, no GPS, touched, whole route, free camera and its return, `drive-free`, End, undo, arrival, Save, Done, Home and back on a trip, the chip. Window frames on a straight road at a steady 100 km/h, emulator at a fix a second: 69 in 20 s, the numbers and the car's small turns. Not on the unit. Left out: the `route-guard` collision layer of section 11, so a name can still lie close to the line; a card beside the column stepping away under 300 m to a turn (section 9, `PanelStack`): it does not cover the column, but it does cover the map at the turn; `ThemeSwap.map` is still the stopgap of a new style, and `recolours` stays false on `MapsPanel` until browse is rebuilt: the column itself recolours in place. A rebuild of the old path (`rebuildLook`, at a sunrise) shows no cards for a moment; it goes with `recolours`.

## Design system (1.12)
The tokens' numbers below are history since 2.0; their names, and how they are used, are still what old screens do.
Everything a screen is made of comes from `ui/`: colours (`Palette.kt`), sizes (`Tokens.kt`), shapes and shadows
(`Shapes.kt`), motion (`Motion.kt`) and the shared components. Until 1.11 only the colours and the curves were tokens;
the app had grown 27 text sizes, 18 radii, 11 elevations, 10 press depths and ~30 animations with their own numbers,
and the light theme (daytime, under "auto") had text at 2.4:1. A new view takes its numbers from here; an old literal
is migrated when its file is touched. Older helpers still work and are marked below.

**Colours** (`Palette`, set by `applyTheme`; a theme change rebuilds the views):
* Surfaces: `bg` screen, `card` panels and rows, `card2` inputs/chips/round buttons/tiles, `card3` pressed tonal and
  the slider track, `sheet` sheets and modal cards (white on the light theme, so card2 tiles on it read as tiles),
  `glass` + `glassStroke` floating cards over the map (`Shapes.glass`), `dock`, `overlay` + `overlayStroke` the
  system-level overlays over any screen (volume, call, voice, messages: `Shapes.overlay()`), `scrim` behind sheets
  and modals.
* Lines: `stroke` hairlines around dark cards, `divider` between parts of one surface (the dock's top edge),
  `track` the empty part of thin progress bars and meters (translucent, works on any surface), `control` the off
  track of a switch.
* Text: `text` primary, `text2` secondary (both ≥ 4.5:1 on every surface of their theme), `text3` tertiary (≥ 4:1;
  only at 14 sp or more, never the only sign that something can be tapped).
* Accent: `accent` is a **fill** (primary buttons, the slider's fill, indicators, selection, the route); as a
  **foreground** (a label, an icon, a highlighted word) use `accentFg`, which `Palette.accent = …` works out for the
  loaded theme: the accent itself when it reads at 4.5:1 on its tint, else lighter (dark) or deeper (light) just
  enough. `accentSoft()` / `Shapes.accentSoft(r)` is the selected chip/tile/row tint, with `accentFg` on it.
* Status: `success`, `warn`, `danger`, themed (the light theme's are deeper: ≥ 4.5:1 on white and white on them), so
  they serve as text and as fills; no hand-picked "light theme green" literals.
* Text on any fill: `onColor(fill)` (WCAG: white while it has 3:1 there, else near-black: cyan, green and amber
  accents get dark labels). `contrast(a, b)` and `luminance(c)` measure; use them to check a new pair.

**Sizes** (`Tokens.kt`):
* `Type` roles, built with `ctx.label(Type.X, colour, lines, gravity)` (wrapping text gets 1.15 line spacing for
  Arabic's marks) or applied with `AText.textRole(Type.X)`: `CAPTION` 14 (units, captions), `FOOTNOTE` 15
  (subtitles, hints), `BODY` 17 (running text, values), `BUTTON` 17 medium, `LABEL` 18 medium (row, list, media
  titles), `TITLE` 21 medium (card and dialog titles), `HEADLINE` 24 medium (sheet and page headings), `DISPLAY_S` 30
  light, `DISPLAY_M` 42 medium, `DISPLAY_L` 58 light (the clock; displays use tabular figures). Nothing under 14 sp
  but the map attribution (`Type.ATTRIBUTION_SP` 11). `label(sizeSp, …)` stays for code not migrated yet.
* `Radius` (dp): `XS` 8 bars and badges, `S` 12 thumbnails, `M` 16 buttons/inputs/tiles/segmented, `L` 20 cards, rows,
  menus, `XL` 24 sheets, dialogs, the big panels and covers; pills are `Shapes.pill(fill, stroke)` (`Radius.PILL`),
  never a radius computed from a height. A radius inside another is the outer one minus the padding between them.
* `Elev` with `View.lift(level, overMap = false)`: `REST` 2 resting controls, `FLOAT` 6 things floating over content,
  `OVERLAY` 12 menus, HUDs, banners, messages. On the dark theme `lift` leaves the shadow out unless `overMap`
  (it cannot be seen on a near-black screen and is still tessellated on every outline change); over the map it is
  the edge that keeps a panel apart from the roads. Elevation also orders siblings, and on the dark theme `lift`
  gives none: a view that must draw above its siblings is placed there by child order (or passes `overMap` when it
  floats over the map), never by `lift`. `lift(Elev.*)` is the only shadow API: the old `elevate(dp)` was removed in 1.12.
* `Space` (dp, 4 dp grid): `XXS` 2, `XS` 4, `S` 8, `M` 12, `L` 16, `XL` 24, `XXL` 32; `GUTTER` 16 is the inset of
  screens, panels and sheets. `Touch.MIN` 56 is the smallest tap target, `Touch.LARGE` 64 a screen's main action; a
  smaller visual sits inside a 56 dp view (`Shapes.touchInset(drawable, visualDp)`).

**Motion** (`Motion`):
* Entrances: `Motion.MEDIUM` (280) on `EMPHASIZED`. Exits: `Motion.SHORT` (160, `XSHORT` 100 for a menu or dialog
  card going after a choice) on `EXIT`, never slower than the entrance. Moves and fades in place: `STANDARD`.
  `LONG` (420) only for a whole layout moving (`LayoutMove`), `AMBIENT` (600) for backdrops and cover crossfades
  nobody waits for. No literal durations, no default (accelerate-decelerate) curves: they start slowly, which reads
  as lag.
* Helpers that set duration, curve and delay every time (a `ViewPropertyAnimator` keeps the last ones, so a fade-out
  after an entrance inherited its curve and delay): `View.enter(dx, dy, scaleFrom, delay, dur, end)` (from
  transparent and offset, in dp; called on a view that is still leaving it turns back from where it is),
  `View.exit(dx, dy, scaleTo, dur, end)` (remove the view or make it GONE in `end`), `View.fadeTo(alpha, dur, interp,
  delay, end)`, `riseIn` (= `enter(dy = …)`). For anything else, `View.anim(dur, interp, delay)` returns the
  animator with all three set. All of them put a view that draws overlapping content (a view group, a view with a
  background) in a hardware layer while it runs: **fade a view group only through these** (alone, every child is
  blended separately on every frame). A view that fades a lot and is one flat fill says `hasOverlappingRendering()
  = false` instead (the sheet and modal scrims).
* Presses: `pressScale(depth)` with `Motion.PRESS_ICON` 0.92 (round icon buttons up to 64 dp), `PRESS_CONTROL` 0.95
  (buttons, chips, pills, tiles, segmented choices, dock app tiles; the default) or `PRESS_SURFACE` 0.98 (cards,
  rows, list cells, the media card). It sinks at once (a stiff spring without overshoot), or after the tap timeout
  inside anything that scrolls, so a drag that starts on a row scrolls without the row sinking and bouncing back;
  a view with text sinks through a hardware layer it takes off again at rest. `Shapes.pressable` tints at once and
  lets go over `SHORT`, with the scale.
* Springs (`Spring`) for what follows the hand or may be retargeted half way: presses, sheets, the switch thumb, the
  slider, indicators. Snap them to their target in `onDetachedFromWindow`. A spring also steps at most 64 ms per
  frame, so it is the fade for what plays across heavy frames (the home map's picture handing over to the live map,
  the light theme's start cover): a timed fade jumps to its end there.
* A screen's first entrance starts from `View.afterNextDraw { … }` (1.12): set the views where they start from at
  once, start the animations in the block. On a cold start the first frame held the main thread for up to 650 ms, and
  an entrance started in `onResume` had run its course before anything showed. `CarStage`'s intro waits the same way.
* Only transforms and alpha are animated, never layout parameters; no blur.

**Components**: `ABtn` (the only button: `kind` PRIMARY / TONAL / GHOST / DANGER / DANGER_FILLED, `size` M 56 dp or
L 64 dp; replaces hand-built buttons), `Segmented` (sliding indicator; each label takes the indicator's text colour
by how much the indicator covers it; `item(i)` gives a label its own typeface), `AuraSwitch` (spring thumb that
stretches while pressed; off track `control`), `AuraSlider` (relative drag: a touch alone changes nothing, a clean
tap glides to where it landed, a swipe that starts vertically is left to the page or sheet; the fill is the track's
shape cut by a rectangle, no clip path), `Sheet` (closes with a push and hands touches back at once; drag down
anywhere on the panel where nothing under the finger can scroll up; `onOpened()` when the open has come to rest),
`Modal` (`Modal.card(ctx)` + `Modal(ctx, card).show()` / `close(then)`: own scrim, card in with `enter`, out with
`exit`, Back and a scrim tap close it animated; `Confirm` is built on it), `Menu` (grows from the pressed item),
`Snack.show(ctx, msg, long, icon, aboveDp, outlives)` (the in-app message in place of `Toast`; falls back to a Toast
where there is no activity to show it. A Snack belongs to its activity: a caller that leaves the screen right after
the message, finishing it or opening another screen over it, passes `outlives = true` and gets a Toast that stays on
top; so does `HomeHost.toast(msg, outlives)`).
`label()` without a gravity aligns its text to the layout's start edge whatever the script (`AText.alignStart`): a
Latin name in the Arabic UI stands on the right with the Arabic around it.

**Windows**: `Theme.Aura` has no starting window (`windowDisablePreview`): one drawn from the XML colours is the dark
theme's, so a light-theme screen opened on near-black; the opening animation now starts on the real first frame.
The home screen keeps its starting window (cold start only), and so do Maps and Music (`Theme.Aura.Heavy`): their
first frame comes late enough on the unit that a tap with nothing after it looked dead, which is worse than a moment
of near-black on the light theme. Not yet timed on the unit: if Settings proves as slow there, it moves to
`Theme.Aura.Heavy` too. `colors.xml` holds only what the window theme reads.

## Aura Maps (`nav/`)
MapLibre Native **10.3.7** on purpose: it renders with OpenGL ES 2.0, which is what the unit's firmware declares
(`ro.opengles.version=131072`); 11.x and later need ES 3.0. `MapStyle.kt` builds the style JSON (OpenFreeMap vector tiles and
fonts, dark/light colours, 3D buildings, labels in the UI language); `AuraMap.kt` wraps `MapView` (camera modes follow /
navigate / free / overview; the car is a plain View moved to its projected screen point each camera frame; the
destination is drawn by the map itself since 1.12, see "Style, camera and car"). `MapGuard` marks each map start on
disk: after two starts that never drew a frame (a native crash cannot be caught), the home screen falls back to the
static `MapBackdropView` and its street grid. Since 1.12 the mark is an empty file (`no_backup/map-starting`) written
before the engine starts and deleted once it drew or stopped normally; it was a preferences flag written with
`commit()`, which waited for the disk on the main thread in the middle of the home screen's entrance. The count of
failed starts stays in `aura_mapguard`, and a 1.11 flag left by a crash counts once.
The style since 1.8 (`MapStyle.kt`): a road map — motorways and trunk roads in a warm colour, main roads wider than
streets, every road on a casing (butt-ended: round ones bead where the tiles cut a road), built-up land, sand, parks and
water each in their tone — and the places people drive to on it: the tiles' `poi` layer as a dot in the colour of its
kind with its name (`Poi.kt`: food, shops, fuel, health, mosques, hotels, banks...), in four bands by the tiles' `rank`
from zoom 13.6 / 15 / 16 / 17. Since 1.12 a dot and its name are one symbol (see "Style, camera and car"); until then
the dots were circle layers beside symbol layers without icons, because a coloured icon image drew black on a tilted
map on the emulator's GPU. The least important band comes first in the list, since a layer further down places its
names first. A tap on a place (`AuraMap.onPoiTap`: `queryRenderedFeatures` in a finger-sized box, the
nearest dot) selects it like a search result.
The route while driving is two sources: `aura-route-near`, from the car to at most 1.2 km ahead, cut again at the car
every 70 ms — at the car as it is drawn, gliding between the fixes (`AuraMap.trimRoute`, called from the glide), not
at the fixes — and `aura-route`, the long rest, rebuilt only when less than 350 m of the short part is left. Up to
1.7 the whole remaining route was rebuilt every 150 m or 10 s, so the line trailed behind the arrow. `aura-turn` is
the arrow of the next manoeuvre on the route itself, from 900 m before it, with a triangle in metres on the ground as
its head; since 1.12 `AuraMap.setTurn` builds it and its length follows the zoom (see "Style, camera and car"). The
guidance banner is a panel in the guidance colour, like a road sign (`GuidanceBanner`, see "Guidance, panels and
words"); the trip bar leads with the time left.
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

### Style, camera and car (1.12: `AuraMap`, `MapStyle`, `MapExtras`, `ui/MapBackdropView`)
**The frame loop.** Everything that moves on its own goes through one `Choreographer` callback (`AuraMap.onFrame`): the
car between fixes, the camera following it, and the change from one camera to another. It runs only while something
moves and stops by itself (car at its target, no change of camera under way, zoom/tilt/heading settled), so a car
standing at a light does not redraw the map. It sets the camera at most once per frame the map draws (`fpsCap`), with
one `moveCamera` and one `placeOverlays()`; the camera listeners skip their own `placeOverlays()` while the loop sets
the camera (`inDrive`). Until 1.11 a camera change was `animateCamera` and every fix's glide called `moveCamera`, which
cancels running flights: in a moving car Go, Recenter, the end of a trip and +/− jumped in one frame (they worked
standing still, which is why they looked fine on the emulator). Now `setCamera(FOLLOW / NAVIGATE)` and `zoomBy` blend
from the camera as it is to the one that follows the car, over 650 ms on `Motion.STANDARD`, while the car keeps
moving (target, zoom, tilt, the four paddings and the heading, the last by `Geo.angleDiff`). A target further than 2.5
screens away (a recenter after panning across town) is MapLibre's own flight, and the loop eases the last metres
(`LANDING_MS`). `overview()` and `northUp()` stay MapLibre animations (750 ms for the overview). The loop is not a
`ValueAnimator`, so the firmware's animator duration scale of 0 does not touch it.

**The car on the route.** On the driven route the car moves along the line, not across it (`movePuck`): from where it
is drawn towards where it will be at the next fix (the fix plus one fix interval at its speed, at most 45 m ahead), so
the arrow is where the car is (in 1.11 one fix behind, about 28 m at 100 km/h) and follows every curve instead of
cutting corners. A late fix lets it carry on at its speed (8 s above 20 km/h, for tunnels); a lead under 25 m over a car
that braked is waited out (it never moves backwards), a larger one is taken back over 400 ms. Below 6 km/h it does not
move ahead and the heading is kept (GPS course is noise at walking pace). Off the route, or with none, the straight
glide stays. `setCar` finds the fix on the line itself, so its callers did not change.

**The navigating camera** (`steer`): the zoom is continuous by speed (17.0 up to 20 km/h, 16.4 at 50, 15.8 at 80, 15.2
from 110, eased over 1.2 s; the 1.11 bands jumped 0.4–0.6 levels in one frame); within max(120 m, 7 s) of the next
turn (the one given to `setTurn`) it is at least 16.9 with a 44° tilt instead of 50°. The room above the car
(`navTopPadding`) grows a little with speed (×0.95 below 40 km/h to ×1.14 above 90). The heading looks down the road (a
point 25–70 m ahead on the route, by speed), turns towards it with a 0.45 s time constant, at most 75°/s, frozen below
6 km/h: at a corner the arrow turns first and the map follows softly. +/− change an offset kept for the trip, blended.

**Frame rate.** `setFps(60)` (navigation) measures: when fewer than 70 % of 60 frames were drawn over 3 s of driving,
the map drops to an even 30 for the rest of the trip ("the map drew N of 60 fps" in the log): frames alternating
between one and two vsyncs look worse than a steady 30. The emulator drops; the unit is not measured yet. 3D buildings
start at zoom 17, closer than guidance drives (15.2–17), so a tilted map while driving never pays for fill-extrusions.

**The owner's car on the map** (1.14, `CarMarker` + `CarSprites`): the home screen's 3D car, pre-rendered for the map by
`CarBakerActivity` (`tools/car3d/bake.ps1`, `pack_map.py`; the commands are in pack_map.py): 72 views 5° apart from 38°
above the road (the navigating map's 52° tilt) and one from straight above, 176 px WebP with alpha in `assets/carmap`
(372 KB, decoded once on a low-priority thread, ~9 MB shared by every map). Bake `target=0` puts the point under the
car's middle at each frame's centre, so the frame's centre goes where the car is. Frame k is yaw 5k° (0 points right
on the screen, counter-clockwise): the frame for a heading r on the screen is (90 − r) / 5, blended with the next by
the remainder so the car turns smoothly. Flat (tilt under 12°) the view from above is rotated to the heading; up to
30° the two cross-fade. The view is 92 dp, not rotated or tilted by itself (the frames carry the perspective);
browsing, a soft halo in the route's colour lies under it. Stale (no fix for 5 s): a desaturating colour filter and
60 % opacity in its paint. Until the frames are loaded the 1.13 marker below is drawn.
**The 1.13 marker** (the fallback): browsing, a disc with a heading arrow; while a route is driven (`setRouteAhead`) a
chevron (34 × 28 dp) in the route's colour with a white edge and a contact shadow, laid on the road by `rotationX` =
0.85 × the camera's tilt (a transform only), crossfading disc to chevron on a `Spring`. With no fix for 5 s
(`STALE_MS`) it turns grey and 60 % opaque; the fade is in its paints, not the view's `alpha` (its shapes overlap, so a
view alpha would draw it through an offscreen layer on every frame). `placeOverlays()` writes translation, rotation and
tilt only when they changed (0.5 px / 0.5°). Callers still pass bearing 0 for a fix without one; below 6 km/h the
last heading is kept anyway.

**The style** (`MapStyle`; in 2.0 the colours below are the design's and much of the land changed: "The map on the pack"):
* The route's colour (`routeBase()`, also the turn arrow's, the car's and the destination's) is the guidance colour,
  `Guide.color()`, the same as the sign's over the map: the owner's accent when it is blue, cyan or violet, otherwise
  the default blue (amber is the motorways' colour, red the cameras', green the parks' and graphite the roads': a
  route in those sank into the map). At night it is that colour mixed with 32 % white (`routeColor()`), the brightest
  line on the map; its casing is it with 38 % black by day, 62 % at night. The rest of the UI keeps the accent.
* Widths: the route is wider than the road it is on at driving zoom (15 px at z16, 22 px at z18, casing +4.5), the
  turn shaft 8 px at z16. While `NavSession.rerouting` the route's four line layers are at 45 % opacity
  (`AuraMap.syncRerouting`, on every fix, also on the home map).
* Night roads step down calmly below the route: service `#30363F` up to primary `#566173`, motorway `#6B5A38`, darker
  casings. Light roads are unchanged.
* Places: a dot and its name are one symbol (`icon-image` = `poi-dot`, an SDF disc made in code by `poiDot()` and
  tinted per kind), so they collide together; as circle layers the dots never gave way and every name that did left
  an unnamed dot. The SDF image renders correctly on the emulator, tilted, in both themes (the black icons of earlier
  builds were a coloured image). **Check it on the unit**; the fallback is circle layers for the dots. Tap targets
  (`POI_LAYERS`) are `poi-a`…`poi-d`. Navigating hides `poi-b`…`poi-d` and the house numbers (`MapStyle.NAV_HIDDEN`,
  `AuraMap.syncLayers`); band a (fuel among it) stays.
* Buildings: the 3D ones (from zoom 17) are drawn after the roads, so a street in front of a building passes behind
  its walls; the flat footprints stay under the roads, from zoom 13 with no upper limit. A style `light` (0.3 at
  night, 0.4 by day) keeps lit roofs from glaring.
* Bridges, ramps, tunnels: roads on the ground exclude bridges and tunnels; `bridge-casing` and `bridge` draw every
  flyover (motorway down to service and track) over all ground roads in its class's colour and width (`classWidth`,
  from the class layers' `W` stops, from zoom 5). Motorway slip roads (`ramp=1`) are 60 % wide, on a bridge too. Main
  tunnels are a quiet band of their own width (`tunnel-major`); small ones keep the dashed line.
* Labels: street names stand up to the viewer on the tilted map (`text-pitch-alignment: viewport`; flat they were
  squashed to about 60 % while driving), a little larger, and every text size is ×1.12 in Arabic (`ts()`).
* The turn arrow is built by `AuraMap.setTurn(line, at, after)`: 45 m before and 32 m (`TURN_AFTER_M`) after the turn
  at zoom 16.8, scaled by 2^(16.8 − zoom) up to ×3, rebuilt when the zoom has changed that scale by more than 15 %, so at
  motorway zoom it no longer shrinks to a blob. `after` makes it run on to a roundabout's exit (`AuraMap.turnAfter(step)`:
  the measured `Step.span` + 15 m, at most 125 m; the arrow ends at `max(after, 32 m × scale)`); Maps and the home map
  both ask it. Its layers are over the labels (a place's name hid its head). The home map shows it too (within 900 m,
  not depart or arrive, not while rerouting).
* The destination is drawn by the map (`SRC_DEST`: halo, ring and core circle layers on the ground in the route's
  colour, on top of everything). In 1.11 it was a pin view, which trailed the GL map by a frame during every flight
  and pan.

**The home map's start** (`MapController`, `MapPanel`, `AuraMap.onDrawn` / `pictureFile`, `MapBackdropView`; since 2.0's step 3 the one map, under
the window, see "One engine"):
* `MapController.startForHome()` builds the live map 700 ms after the home screen's entrance starts.
* `AuraMap.onDrawn` fires when the map has drawn with its style loaded (`onDidFinishRenderingFrame(fully)`), or 2.5 s
  after its first frame (offline, tiles that never come); `MapGuard.ok` still comes on the first frame. It replaced
  `onFirstFrame`, which fired on the first frame of land colour before any tile.
* The home map keeps a picture of the card (`pictureFile`, `pictureArea`, `cacheDir/home-map-{dark|light}-{lang}.webp`, WebP 80),
  taken with `MapboxMap.snapshot` 3 s after the map came to rest, at most once a minute, and only of the view the next
  start opens on (`pictureWorthy()`: FOLLOW, no route, no destination; a picture taken at a red light while navigating
  cross-faded into the north-up start as a double exposure).
* `MapBackdropView(ctx, picture, grid)` lies over the live map with that picture until `onDrawn`, then fades out over
  `Motion.AMBIENT`; the car is usually where it was parked, so the screen opens on the real streets. With no picture
  yet (the first start for a theme and language, or `cacheDir` cleared) it is the plain land colour: the invented
  street grid ghosted over the real streets during the fade. The grid (`grid = true`) is only for the `MapGuard`
  fallback, in the style's own colours (`MapStyle.tones()`).
* The backdrop is not shown again after a stop: the land-coloured cover over the slot stands in until the surface has
  drawn again. Once the map has drawn, the panel's own `mapLand` fill is dropped and the wall opens.
  `Palette.mapLand` is the only map colour left in `Palette`; the others are the style's.

**MapExtras**: `spots`, `published`, `mine`, `speeds`, `grid` and `updatedAt` are written on the main thread only (the io
thread parses and posts; a fetch that finished while a camera was being marked could put the old set back). `version`
counts every change; `geoJson(arabic)` is cached per (version, language) and `AuraMap.pushExtras` sends a set only when
that map does not show it yet. Until 1.11 the whole FeatureCollection was rebuilt and parsed again at every start of
the home screen and of Maps.

### Guidance, panels and words (1.12: `GuidanceBanner`, `MapsPanel`, `Instructions`, `Router`, `NavSession`)
**The guidance sign** (`nav/GuidanceBanner.kt`) is one view for every map that guides: Maps uses it full size, the home
map `compact = true`. It reads `NavSession` itself; a caller adds it, calls `update()` on every session change and
`reveal()`s it in and out.
* `GuidanceBanner(ctx, compact)`: a vertical `LinearLayout`, GONE at first, in `Guide.sign()`, radius `Radius.XL`,
  `lift(Elev.FLOAT, overMap = true)`. `update()` does nothing without a route and a progress; every label goes
  through `setIfChanged` and is MATCH wide, so a fix with the same words costs nothing and a new number only redraws
  its line. `reset()` forgets the step shown, so the next `update()` shows it without the change animation (both
  maps call it when a trip starts).
* Full size: 80 dp arrow; the distance (`DISPLAY_M`, its unit at 0.55 of its size: `GuidanceBanner.styled`), the action
  (`TITLE`), the road (`BODY`, one line, bidi-wrapped so a Latin name or one with brackets keeps its order in an Arabic
  line); then the lane strip and the "then" strip in a darker band. Every word is full white and the lines differ by
  size only: the sign is deepened just to 4.5:1 for white, so a faded line (84 % white was) fell under it.
* Compact (the home map): 52 dp arrow, `HEADLINE` distance, `BODY` action, `FOOTNOTE` road, and at its end the arrival
  clock and "time · distance left" (the home screen has no trip bar); no lane or "then" strip. It is a fixed 440 dp
  (`COMPACT_WIDTH_DP`), capped by `maxWidthPx` (MapPanel sets it to what the speed sign and camera chip column leave,
  on that column's layout changes) and by the parent, so changing numbers never resize it. MapPanel keeps its tap
  (open Maps) with `pressScale(Motion.PRESS_SURFACE)` and shows it with `reveal(navigating, -8f)` in the places row's
  spot; that row switches at once, since both share one column.
* A new step comes in (the arrow grows from 0.82, the words rise 8 dp); the same step only changes its numbers. While
  rerouting it cross-fades (`TransitionDrawable`, `Motion.SHORT`) to a muted sign with a circling arrow
  (`Turn.REROUTE`) and "updating the route"; the step after it comes in like a new one.
* The "then" strip shows when the next manoeuvre starts within `max(250 m, 12 s at the current speed)` of where this one
  ends (a roundabout ends at its exit). The lane strip shows when OSRM gave two or more lanes, one of them wrong for
  the route, under 800 m before the junction; lanes are drawn left to right in every language, as OSRM lists them and
  the road's signs show them.

**The guidance colour** (`Guide`, same file): `Guide.color()` is the owner's accent when it is blue, cyan or violet
(`FOR_GUIDING`, taken from `Palette.accents` by position: a reordered swatch list must update it), else the default
blue. Green is the parks' colour on the map, amber the motorways' and a warning, red the End button's and the cameras',
graphite the roads' and reads as switched off. `Guide.sign()` is that colour deepened until white reads on it at 4.5:1
(on cyan white was about 2:1). The sign, the arrival card's bar and, through `MapStyle.routeBase()`, the route line,
the turn arrow, the car and the destination all use it, so what the sign says and what the map shows are one thing.

**Showing panels**: `View.reveal(show, dy, delay, animate, scaleFrom)` (in `GuidanceBanner.kt`). Maps syncs its panels on
every fix, so every visibility change goes through it; it does nothing when the view is already where it should be
(also while it is on its way out: the foundation's `aura_exiting` tag), enters with `enter(dy = …)` and leaves with
`exit` half the way back, then GONE. A view whose parent is not shown, or not attached yet (the first sync after
`onCreate`), is switched at once. Code that asks "is it showing?" uses `isOn` (VISIBLE and not exiting), not
`visibility`. Offsets: top panels −12…−16 dp, the results −8 dp, the bottom cards +16 dp after 60 ms, the recenter pill
and the zoom capsule by scale. `TextView.setIfChanged(s)` (same file) sets a label's text only when it differs.

**The Maps screen**:
* The map's own controls (compass, zoom capsule, speed, limit sign, camera button, attribution) are one layer
  (`controls`) that fades out while the search is open (the keyboard shrank the screen to a strip where they piled
  up); a trip started from outside meanwhile brings it back.
* + and − are one capsule under the compass; while a route is driven and the map follows it they go (pinching still
  zooms) and come back in free or overview camera. The compass turns with the map (`rotation = -bearing`, set when it
  moved a degree), dimmed to 0.55 while browsing north up; a tap is north up, or back to the drive while navigating.
* The voice button is in the trip bar beside the overview button. End is a red word on a red tint
  (`ABtn.Kind.DANGER`): the time left is what draws the eye.
* The camera button and the limit sign are laid out where they stand while driving and moved by translation into the
  speed's place while browsing, so they glide instead of jumping by their margins.
* The recenter pill sits at the bottom start (the driver's side) above whichever bottom card shows: "متابعة الملاحة" /
  "Resume" while navigating, "موقعي" / "My location" otherwise. While navigating the map goes back to the car by itself
  12 s after the last touch, 20 s after the overview button (cleared on End and in `onStop`).
* The arrival card says "وصلت إلى" / "You have arrived at" and the place's name (not a dropped pin's placeholder), over
  a line that runs out in the 30 s before the trip ends by itself; it steps once a second with a `Motion.SHORT` glide
  (`ARRIVED_STEP_MS`, cleared when the card goes and in `onStop`): one 30 s animation drew about 60 frames a second
  for a parked car.
* Messages (`toast`, a `Snack`) stand above the bottom card that shows; one revealed in the same pass is measured at the
  panel's width (the "no voice" hint at a trip's start covered End). The ETA's green unit is full `Palette.success`,
  smaller only (at 80 % it was 3.5:1 on the light glass). The speed disc rounds the speed (under 3 km/h is 0: GPS
  reports 1–3 parked) and turns red with one beat over the limit.

**Place card and search**:
* The route's time is the headline (green, `HEADLINE`, unit small), then distance and arrival. Go stays dimmed until
  there is a route; a failure is in `warn` with "tap to try again" (the line is the button).
* Go takes the row; beside it one ghost "حفظ" / "Save" opens a `Menu` with "Set as Home" / "Set as Work" (labelled
  "المنزل" beside Go the old buttons read as "drive home" and overwrote Home; two "Set as" buttons left Go barely wider
  than either). While Home or Work is being chosen (`pickFor`), Save keeps the place as that at once.
* A dropped pin's route is no longer thrown away when the reverse lookup renames the pin first (the route callbacks
  compare the selection by position). The first camera flight waits 700 ms for the route, which brings its overview.
* Search keeps the old rows, dimmed, with a spinner in the clear button's place, until the answer replaces them.
  Without a fix it biases to the map's centre. Photon's bias is stronger (`zoom=12`, `location_bias_scale=0.1`) and
  `Places.rank` orders equally good names by distance band (< 5, 30, 150 km, further); a name holding the whole query
  counts as good as the query ("Al Dawa Pharmacy" 230 m away before "Pharmacy" in Dammam). With a position,
  `Places.search` also asks Photon once without the bias (`limit=3`): the biased answers alone were ten local
  namesakes for "Jeddah" or "مكة" in the Eastern Province (streets are named after the cities they lead to); the first
  far answer always keeps a place in the twelve.
* Address parts are joined with the UI language's comma ("، " in Arabic) and the detail lines read in the UI's
  direction (`TEXT_DIRECTION_LOCALE`). The results card measures its room in its own measure pass, again when the
  keyboard comes or goes, and is cut at half a row. The clear button follows the field's text, not only typing.

**Steps, words and voice**:
* `Router.prepare(line, steps)` runs for OSRM and BRouter routes once the steps are on the line. `Step.silent`: "new
  name", "continue", "notification" and "use lane" steps that go straight (with no lanes to keep to), and the "exit
  roundabout" after a roundabout; they stay in the route (their time counts) but are never shown or spoken
  (`NavSession` skips them for `next`; `Route.following(i)` gives the next told one).
* Roundabouts are measured on the line: `Step.span` (entry to the exit taken: OSRM's exit step, else the end of the
  longest left-turning stretch) and `Step.angle` (where the exit really leads). The icon draws the exit at that angle
  with its number in the ring, and the map's turn arrow runs to the exit. Saudi roundabouts often have five or six arms
  or arms at 45°, where the old 4-way table pointed the wrong way. A turn back (over +160, which road noise gives as
  often as −179) is −178 in `Instructions.exitAngle` (the ring is driven counter-clockwise, so it is the whole way
  round); the icon draws any exit beyond −150 at −150, beside the way in. A roundabout step without a road takes its
  exit step's.
* OSRM steps carry `lanes` (from `intersections[0]`), `destinations` and `exits` (`Lane` is a class of its own). Ramps
  say the exit number and "باتجاه …" (two destinations at most); the voice leaves the destination list out.
* `Instructions.action` is the sign's main line, `roadLine` its second, `text` the whole sentence (the notification),
  `spoken` the voice. The arrival ahead says where the destination is ("وجهتك على اليمين"), not "you have arrived";
  `arrived(name)` is said once, on arrival. A "continue" with a U-turn modifier says U-turn, like its arrow. Wording:
  "انعطف يمينًا قليلًا", "الزم اليسار", "ثم", "جارٍ تحديث المسار…"; English "Bear left", "Make a sharp left".
* Units: Arabic minutes "5 دقائق" / "12 دقيقة", hours "1 س 20 د"; no trailing ".0" in km; the Arabic 12-hour arrival
  ends in "ص" / "م". Spoken kilometres follow Arabic grammar ("كيلومتر ونصف", "كيلومترين", "5 كيلومترات", "12 كيلومترًا").
  The voice leaves out a road name it cannot read (Latin for the Arabic voice).
* `Router` puts the offline index's `name:ar` on an online route's Latin street names when the UI is Arabic
  (`PlaceIndex.arabicNames`, one query per route on the io thread, the nearest street of that name within 15 km). Not
  yet seen working on the emulator.
* Prompts (`NavSession.prompt`): early (1–2 km) for ramps, forks, merges and at highway speed; far (400–1500 m, not for
  an arrival under 600 m after the last manoeuvre); near (80–300 m, 8 s) with ", ثم …" when the next manoeuvre follows
  within 150 m; a route's first words at the start and after a reroute; "استمر على … لمسافة …" just past a manoeuvre with
  over 3 km to the next.

**Theme, accent and language while Maps is open**: `MapsPanel` remembers what it was built in and checks it at `onShow`,
in `onLook` and so on every minute's tick (and time, time zone and locale changes) while resumed: a sunset under "auto" or
a change in Settings builds its cards again and reloads the style, on the same engine ("One engine", "A new look"). A new
language at once (the whole window); a theme or accent not while a place is chosen, the search is open or a route is being
found, nor when `MapController.lookMayChange` says no (the next tick tries again). A trip being driven goes on (`NavSession` is
the app's). There is no picture cross-fade on the cards, and the map's colours cut: the design's section 8 replaces all of this.

**Road additions**: "Camera here" (`CameraMark.mark`) takes only a fresh (under 5 s), precise (30 m or better) fix;
NavSim's and the debug mock fixes count (NavSim stamps `elapsedRealtimeNanos`). `CarLocation.last` can be yesterday's
fix or a network one, and a camera stored there beeped at the wrong place on every drive. `RoadWatch` keeps what it
announced when the driver marks or takes back a camera (it forgets only spots that are gone). `RoadHud`'s limit sign
enters and leaves like the chip; the chip's label is as wide as its longest distance, so the countdown only redraws it.

Testing: `MapsActivity --ez simulate true`, long-press the map, Go (or long-press Go in a debug build) drives the route
at 70 km/h. Lanes, ramp destinations and Arabic street names only come with online OSRM routes (the offline router has
no lanes); rerouting, the arrival bar and the over-limit beat need a drive (NavSim, or `emu geo fix` off the route).
Still unchecked on a real drive: lanes, ramps, rerouting, spoken prompts, a real roundabout's arrow, the sunset
recreate, and the guidance colour with the owner's accent.

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
  a missing glyph range answers 204 so labels never hold a tile up). Since 1.12, small pools of background-priority
  threads: three answer from the disk, six fetch tiles online and two fetch glyph ranges online, so a stored tile
  never waits behind a fetch and a missing glyph range (which holds up the labels of every tile that needs it) never
  waits behind tiles. Until 1.11 every connection had a thread of its own at the UI's priority, and the burst a zoom
  asks for competed with the gesture that caused it; if map loading feels slower on the unit, raise the online pool
  first. Upstream is not asked again for 30 s after a failure, the TileJSON refreshed in the background,
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

### The map pack on the unit (2.0: `nav/pack/`, `scripts/pack_server.py`, `pack_e2e.py`, `make-usb-pack.ps1`)
Replaces `OfflineMaps` for 2.0. `tools/mapbuild/make_pack.py` makes the pack (19 assets, 387 MB to fetch, 713 MB on the disk, `needBytes` 990 MB); this gets it onto the unit
and never breaks a working map.
* **Everyone else asks `PackPaths`** (`routeTar`, `placesDb`, `baseMbtiles`, `placesMbtiles`, `glyphsDb`, `current`), each time a file is opened: a File or null, no Context kept, works in
  `:route` too (there it sees a new pack by the pointer file's time). What it gives is read only: open with `OPEN_READONLY`. Who has served real data from it says `noteWorked` once.
* **Files:** `files/maps2/current` (one line, the id; atomic rename), `packs/<id>/` (verified), `staging/<id>/` (on its way, with `progress.json`, `manifest.json`, one `<chunk>.part`), `trash/`.
  `tools/mapbuild/device-config.json`'s `tile_extract` says `files/maps2/route.tar`: that path does not exist here, `:route` must write `PackPaths.routeTar` into its config.
* **One chunk at a time** (`PackJob`): GET with Range into `.part`, SHA-256 of the gzip file, gunzip at the piece's offset with a sync every 8 MB, mark in `progress.json`, delete the part. A kill anywhere loses at most
  the chunk in hand. Pieces are `posix_fallocate`d first, so a full disk shows before the first byte and the file is not in fragments.
* **Why fsync before every rename:** the unit's `/data` is ext4 with `noauto_da_alloc` (vendor `fstab.sun50iw9p1`): without it, a rename after a crash can leave an empty file.
* **No validators, only hashes.** No `If-Range`, `ETag` or `Last-Modified`: GitHub's CDN answers 206 to a wrong validator (`maps.md` 15.1). A resume's `Content-Range` must start where asked and end at the chunk's end;
  anything else drops the part. A resume starts 64 KB before the part's end, as cheap insurance for the bytes just before a drop. `Accept-Encoding: identity`: the platform would ask for gzip and unpack it. Redirects followed by hand, https on every hop in release.
* **Whole-file check last:** every piece's SHA-256, with a CRC per chunk from the moment it was written, so a block damaged after its sync (power cut, bad eMMC) costs that chunk rather than the piece. Then `PackVerify`: read-only
  open, `quick_check` on `places.db` with its schema and norm version against the manifest, `metadata` and a tile from both mbtiles, the Arabic ranges in `glyphs.db`, `index.bin` first in the tar. Only then the rename into `packs/` and the pointer. A kill between those two is finished by the next start (`restore`).
* **The old stays:** the pack in use is never touched by a job. After a switch the previous pack and `files/offline/map.db` stay until `noteWorked`; then the old pack goes by itself and `Status.oldMapBytes` offers `freeOldMap`, which leaves
  BRouter's `segments4` (the fallback router, `maps.md` 7.6).
* **Nothing starts by itself.** Not at a process start, not after a reboot (`PAUSED`, with its percent, from the disk), not for a newer manifest (`checkUpdate` only makes an offer). `releases/latest/download/manifest.json` says 302 to the tag, and the chunks are then fetched under that tag.
* **Trips:** all work stops during a trip (`TRIP_HOLD`, through `NavSession`'s listener): a low priority is not enough on four A53 cores and one eMMC next to the map and the voice. It goes on when the trip ends. No wake lock: with the ignition off it could drain the car's battery; a sleep cuts the connection and the retry takes it from there.
* **USB and SD:** `<mount>/amri-maps/manifest.json`, the same chunks and hashes. Mounts from the firmware's files, not seen on a unit: vold labels `udisk`, `udiskh`, `udisk3`, `udisk4`, `card` (vendor fstab) land as `/mnt/media_rw/udisk1`, `udiskh1`, `udisk31`, `udisk4`, `card1` (stock launcher's strings), and `/mnt/udisk...`, `/mnt/card`, props `ro.nwd.usb1.path`... (`com.nwd.app.NwdDeviceConfig`). What the process cannot read
  itself the root shell does (`RootSource`: `dd` 8 MB at a time into a part the app already owns; `LocalAdb` has no binary channel). `scripts/make-usb-pack.ps1 -Stick E:` writes a stick: FAT32 advised, free space checked, every file read back.
* **A debug broadcast drives it:** `am broadcast -a com.abdllh.aura.debug.PACK --ez start true`, see `PackDebug`; props `debug.aura.pack.base`, `.usb`, `.free`, `.root`. `scripts/pack_e2e.py` runs the tests against `scripts/pack_server.py` (Range, 302, drops, silence, a wrong byte, a spliced version, a portal page, 503).
* **`minApp`:** compared with `BuildConfig.VERSION_CODE`. The manifest of 2026-11-04 says `minApp` 200000, under every version since 0.20: it refuses nothing. 2.0.0-beta.1 is 2000001: pass `--min-app 2000001` to `make_pack.py` for the release.

* **Tests:** `src/test` `PackRulesTest`, `PackVerifyTest` (18). On the emulator `scripts/pack_e2e.py` with the real pack; a worktree with no `signing` folder builds `-PstandIn` with a throwaway key, beside the emulator's own app: a second home screen makes Android drop the chosen one, so after the tests `adb uninstall com.abdllh.aura.standin` and `cmd package set-home-activity com.abdllh.aura/.home.HomeActivity`.
* **Checked on the emulator** (x86_64, /data ext4; the pack built 16:41Z: 379,019,495 bytes to fetch, 705,169,408 on the disk; through `adb reverse`, so a computer's file and a USB-like link, not a hotspot):
  * Whole install 13-24 s, the emulator's load decides (15-27 MB/s all told; 386,613,417 bytes of the 15:24Z build also 24 s), of which about 3 s the whole hash: 19 requests through 19 redirects, not one byte fetched twice, no `If-Range` sent, foreground while it runs. The `aura-pack` thread 55-87 % of one core, PSS at most 5 MB up. At 1.5 MB/s: 2.7 % of one core, PSS 1-3 MB up.
  * `kill -9` at 15, 41, 70 %: the state comes back PAUSED with its percent, no byte asked for until told, 32 MB fetched again over the three, against a largest chunk of 55.6 MB (most of it was still in the tunnel's buffers). The process killed at each of after-fetch, after-unpack, after-mark, before-ok, before-rename, before-pointer, after-pointer: every file right afterwards; killed before the pointer, the next start finishes the switch by itself.
  * Cut every 5 MB: 66 drops, 163 s, 4.3 MB over (the 64 KB overlap each time), no chunk refused. A reset every 7 MB: 48, 119 s, 14 MB over, no chunk refused. None of the 114 cuts damaged a part. Nor did 80 resets with no overlap at all (48 through adb's tunnel, 32 through the emulator's own network), so the overlap is not shown to be needed: it is kept because it costs 64 KB a drop against a whole chunk, and `fetchFile` did lose bytes on hard cuts once (below, `ResumeCheck`).
  * One flipped byte, and another version of a file spliced in by a resume (a CDN that ignores If-Range): exactly those two chunks refused and fetched again. The same chunk wrong three times: FAILED, plain words, nothing broken; a resume finishes. A 503, a sign-in page answering 200 (for a chunk and for the manifest), a link that goes silent (found by the 25 s read timeout), a Range ignored, a Content-Range that starts one byte off: waited out or started over, all files right.
  * Too little space (fake 500 MB): NO_SPACE with the numbers, only the manifest fetched, no folder made. A disk really full behind a fake answer (299 MB left): NO_SPACE from the allocation, the room given back, the process alive.
  * A newer manifest under `releases/latest/download`: an offer, no byte for 5 s. A live reader through it: on the old pack at 40 %, after a `kill -9`, moved to the new one within half a second of the switch, 46 reads with no error, and the old file still open and readable afterwards. `noteWorked`: the old pack goes, `oldMapBytes` shows; `freeOldMap`: `map.db` gone, `segments4` there. `cancel` and `deleteCurrent` leave what they say.
  * USB: from a readable folder 17 s, pulled out in the middle (USB_GONE, percent kept), put back, resumed, no network byte. Through `scripts/fake_adbd.py` as root from a folder the app cannot read (`/mnt/media_rw/udisk1`): 21 s with a `kill -9` in it; every file the app's.
  * A real trip in `NavSession`: stopped about a second after the broadcast, no byte in 6 s; during the whole-file hash the thread used nothing in 3 s; goes on after. Pause, resume, pause, resume without waiting: nothing stuck or ran twice.
  * `adb reboot` at 48 %: PAUSED, nothing by itself, 3 chunks marked before and after. `echo b > /proc/sysrq-trigger` at 46 %, no sync, no unmount: the same, and the part came back at exactly 16 MiB, two sync steps: what was not synced was gone and nothing else. Every file right by `sha256sum` on the device in every case above.
* **Not known until the unit:** free space on `/data`; speed of the eMMC and of SHA-256 and gunzip on the A53s in 32-bit (`last-install.txt`, `PackManager.lastInstall`, says fetch, hash, unpack, whole hash and open in seconds: the first real numbers); `posix_fallocate` on its ext4 and 4.9 kernel (falls back to a plain length); whether the app can read a mount itself or goes through root, and the mount names (from strings, never seen); a real hotspot; GitHub's `latest/download` 302 (measured for `download/<tag>` only).

### The map on the pack (2.0: `MapStyle`, `MapPalette`, `Poi`, `offline/PackTiles`, `offline/KsaOutline`, `TileServer`, `tools/mapbuild`)
The style above reads the pack: colours from the design, labels in the OS's own font, places from Overture and OSM, on a unit that
has no internet. A unit with no pack sees the same colours and today's chain (online OpenFreeMap, `files/offline/map.db`), with Noto
Sans and OpenMapTiles' places.

**Palette.** `scripts/gen_map_palette.py` turns the design's `map-day.json` and `map-night.json` into `nav/MapPalette.kt` (and
`polygon/ksa.poly` into `offline/KsaOutlineData.kt`): one command after a polish, and it stops on a missing key or a colour that is
not `#RRGGBB`. Kotlin rather than an asset: nothing to read at run time, nothing that can fail while the home screen starts. The
default accent draws in the palette's own route and casing (`#1F6BFF` by day, the sign is still the accent's `#3E6AE1` until the design's
tokens come with the shell); an accent the owner picked (blue, cyan, violet) is worked out as before, line and sign in one colour.
Cameras, signals and bumps keep their colours in code, as they were.
`Palette.mapLand` is the same value as `land`, as `tokens.json` says.

**What changed in the layers, and why** (each found on real tiles):
* The tiles' `park` layer is no park. In the Kingdom it holds reserves the size of countries and the Haram's boundary, whose class is
  whatever a mapper wrote in `protection_title` (`منطقة_مقدسة_إسلامية`): at 0.9 opacity all of Makkah and the reserves were green. Now
  only classes named in the style: `forest` is green; a reserve (`national_park`, `nature_reserve`, `protected_area`,
  `protected_site`) is a 9 % tint up to z9 that is gone by z11, with its name (`park-name`, z7 to z11).
* `landcover` by subclass: class `grass` also holds `scrub`, `heath` and `grassland`, and `wetland` holds the salt flats: half the
  Eastern Province came out as a lawn. Only gardens, parks, golf and meadows at 0.9, fields (`farmland`) at 0.45.
* `sand` is the land's own colour in the palettes: its layer is left out while that is so.
* `waterway` from z10.5 with a fade to z12, no `drain` or `ditch`: a river here is a dry wadi, and thousands of them laid a blue web
  over the desert at the zooms of a long drive. `intermittent` water on a layer of its own at 0.45 (a data-driven opacity would have
  moved all water out of the opaque pass).
* Borders: `maritime != 1` (they crossed the Gulf and the causeway); a region's border thinner and fainter than the country's, gone by z11.
* Seas: no tile holds the Arabian Gulf or the Red Sea (`water_name` has the Gulf of Bahrain and the Straits of Tiran, from z8), so
  `MapStyle.SEAS` are fixed points in a small GeoJSON source, far ones only from z7, to z9. Names in the code, as data of the map like a
  tile's names; both languages there, no strings file. `water-name` from z8 with a halo of the water's own colour.
* `place-region` (`state`, `province`) regular, small, grey, z5 to z8, listed before the towns: the last layer in the list places first,
  so a region gives way to a town. Bold and larger, and placed first, they had pushed Makkah and Madinah off the country view. The
  country's name only under z5. In English some regions read as their city (Riyadh, Medina twice at z5): left.
* A casing is an edge at any zoom (`edge()`): 30 % of its width at z5, whole from z12. At street-level width a dual carriageway at z5
  was two grey bands.
* `road-name-major` from z11, far apart while far out; `road-name` a little more room between names.

**The fonts.** `MapStyle.PACK_FONT` (`AMRI Map Sans`: IBM Plex Sans Arabic, under a name without the font's reserved "Plex", as the OFL asks of a modified form) is the one name in the app, with `PACK_HEAVY` (`SemiBold`); `make_glyphs.py --family` and `make_pack.py --family`
must say the same (the two font files are `make_glyphs.py`'s first arguments). The style asks for it when the unit has `glyphs.db` and the
tile server is up, else for `Noto Sans`, which is all OpenFreeMap has. glyphs.db unreadable: the server answers the request from
the Noto stack, so a damaged pack costs the font, not the words. A range the pack has no row for is 204 at once.

**Arabic road names, at pack build, nothing at run time** (SPEC section 11; `tools/mapbuild/arabic_names.py`, README there). `src\ksa-ar.osm.pbf` is `ksa.osm.pbf` with `name:ar` on the highway ways that have a Latin
`name` and none, when every word is known: the hand table (`arabic_names_rules.json`, from the design's `arRoad`), then word pairs learnt from the Kingdom's own two-script names and read by a person (`arabic_names_words.tsv`). A word nobody has read is not used; nothing is spelled out letter by letter. Base tiles, routing and places all read that file.
Planetiler carries the tag as `name:ar` of `transportation_name`. Valhalla with the Kingdom's admin database lists both (`Prince Naif Road; طريق الأمير نايف`) and `Names.pick` takes the Arabic: no `alt_name` needed. `MapStyle`'s `road-name` on a unit with a pack reads `name:ar`, then the tile's `name:nonlatin`, then nothing: a minor road whose Latin name has no rule has no label. `road-name-major` (motorway, trunk) keeps a Latin name. On online tiles, which had no such build, Latin stays; outside the Kingdom a unit with a pack draws the same rule, so a minor road in Latin there has no label on the Arabic map either.
Measured on 2026-11-05, in pack `ksa-2026.11` built 2026-11-04T21:57Z: named highway ways with Arabic, Kingdom 62,723 to 67,913 of 72,051, his box 1,125 to 2,121 of 2,723 (by km 67 % to 91 %). Named segments in z14's `transportation_name`, his box: 980 of 2,244 to 1,732 of 2,241. What stays Latin there is mostly Aramco's camps (Palm Drive, Crane Court, 308 names, 548 ways).

**Places of 3 letters or less** have no word on the map (`build_poi_tiles.py`: no `name` in the tile, the dot and the row stay; 2,307 dots at z14). A tap on such a dot goes to the nearest named place (`AuraMap.tapPlace`).

**Places.** `places.mbtiles` (layer `places`, fields `name`, `name:en`, `kind`, `cat`, `rank`, `id`) is a source of its own on
`/p/{z}/{x}/{y}.pbf`, z12 to z14. `kind` is new: `cat.icon`, the colour group, because `cat.id` changes between builds and the style would
have had to read table `cat` for it. Its `rank` is 1 to 100, higher more important, the opposite of OpenMapTiles': `poi-a` is `rank >= 60`
from z14 (at z13 only `rank >= 80`: hospitals, malls, big hotels, from z12.6), `poi-b` 45 to 60 from z15, `poi-c` 30 to 45 from z16, `poi-d`
below from z17; bands, not `symbol-sort-key`, which splits a bucket into one draw per key. The names `poi-a..d` stay (the design's
`hiddenWhileNavigating`, `NAV_HIDDEN`); on a unit with a pack OpenMapTiles' places are `omt-poi-a..d`, which only tiles from OpenFreeMap
fill, outside the Kingdom. `AuraMap.tapPlace` reads either; `onPlaceRow(id)` gives the row of `places.db`.
OpenMapTiles' `poi` layer has left `base.mbtiles` (`strip_tiles.py`, a step of `run_build.ps1`, `check_tiles.py` refuses it). What else
read it: only the style and the tap. `PlaceIndex.build` and `Region.roadTiles` read `TileStore`, `files/offline/map.db`, OpenFreeMap's
tiles, never the pack: a note in the tile report said the strip would cost PlaceIndex its places, and it does not.

**The server.** `PackTiles` opens the three files read only, on a tile thread at the first request, with `NO_LOCALIZED_COLLATORS`.
Not `TileStore`, which creates tables and turns on WAL. A failed open is tried again a minute later; the style only asks whether the
files are there, which reads nothing. Android's default error handler deletes a database it finds corrupt: `PackTiles` passes its own, a
download of minutes stays where it is for the pack manager to judge. `PackTiles.paths` is the one lookup (`MapPackPaths`; `CurrentPackFolder`,
files/maps2/current/, stands in until the pack's own paths are merged; debug: `setprop debug.aura.pack name`); `PackTiles.changed()` after a switch.
The choice (`TileServer.tile`): a tile that touches the Kingdom's outline (`KsaOutline.touchesTile`, Liang-Barsky against 308 edges, a few
microseconds on the PC, nothing kept) comes from the pack, and a missing row is 204, an empty tile (more than a third of z14 is), never
the internet. Any other tile: store, OpenFreeMap, and the pack when those fail (it holds the sea and far shores over its rectangle).

**The second copy.** `Cache-Control: no-store` does not stop MapLibre 10.3.7: the string is not in `libmapbox-gl.so`, it reads `max-age` and
`must-revalidate` only, and every tile is packed again and written to `mbgl-offline.db`. `Source.setVolatile(true)` does stop it
(`MapStyle.afterLoad`; the JSON key `volatile` is not in that library either, so it is the Java call, after the style loads). The price: online tiles
outside the Kingdom are not kept on a unit with a pack. Measured on the emulator, 10 cameras: `mbgl-offline.db` 0.27 MB after 155 tiles read,
2.62 MB after 166 with the sources not volatile. Reading the database stays: a tile cached under the same address before a pack came is shown until
it expires (a day, then the pack's replaces it), so the pack manager should call `OfflineManager.clearAmbientCache` once after a switch.

**The credit.** `MapStyle.attribution(at)`: `© OpenStreetMap contributors · © OpenMapTiles · Overture Maps` while the pack draws (a pack, and
the middle of the map inside the outline or no internet), `© OpenStreetMap · OpenFreeMap` otherwise, updated when the map comes to rest
(`AuraMap.onCameraIdle`). The Home map has no credit yet: the shell's step must add it there.

**Looking at it** (`debug/MapLookActivity`, `scripts/map-look.ps1`): the real map alone on the screen at any camera, 37 cameras, offline, the pack's read
times per shot and anything MapLibre complains of. `tools/mapbuild/adversary/dump_style.ps1` still writes the style as JSON for the GL JS plates.
Seen on the x86_64 emulator with MapLibre Native 10.3.7, day and night, Arabic and English, against `final2/assets/plates`; not an ARM GPU, nothing on the unit.
Reads there, the emulator's disk image on a spinning disk shared with other emulators: mean 1 to 23 ms a tile per camera, worst 91 ms; one run while the disk was busy, mean 42 ms, worst 420 ms (the PC itself: 0.04 ms). The unit's eMMC is unmeasured.
What differs from the plates, on purpose or not: the words are IBM Plex Sans Arabic, the plates' Noto Sans; the places are the pack's, not OpenFreeMap's, so other names and more
Latin on an Arabic screen; edges of fills have no multisampling; the labels' fade exists on the unit and not on a plate.
The straight lines in the sea at 16.2 N and 55.7 E are gone: Planetiler cuts the polygons it reads exactly at `--bounds`, so z9-14 are built over 32,13.9,58,36.6 (past every tile that touches the outline) and z0-8 over 22.5,0,67.5,41 (`tools/mapbuild/merge_low_zoom.py`), 4.9 MB more on disk. Which tile comes from where has not changed: `KsaOutline.touchesTile` the pack, else online, else what the pack has.

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
  `aura-extras` (circles and text, like the places: no icon symbols), since 1.14: a camera as a red badge (a soft red
  glow, a white ring on either theme, a white lens with a dark pupil, its word under it; growing with the zoom), a
  signal as a traffic light (a dark capsule of five overlapping circles inside five white ones, three lamps on it, all
  moved by `circle-translate` in screen pixels so they stay upright), a bump as an amber dot. The limit is no longer
  written along its street (the owner: the numbers cluttered the map); it shows only as the sign above the speed.
* **While driving** `RoadWatch` reads every fix: the limit of the marked street the car is on (within 24 m of the line and
  heading along it, held 4 s over gaps), and the nearest camera (600 m) or bump (180 m) ahead of it (±55°), with a tone
  the first time each comes within reach. `RoadHud` shows them on the Maps screen (the limit as its sign above the
  speed, red when the car is over it; the warning at the top) and on the home screen's map. Only while fixes come: the
  home screen or the map in front, or a route being driven.
* **A camera marked on the road** (1.10.1): the round button "ساهر هنا" (`nav/CameraMark.kt`) on the Maps screen
  (beside the speed; in its place when no route is driven) and on the home screen's map marks a camera where the car
  is (`CarLocation.last`, with the limit of the marked street if there is one, else no speed). `MapExtras.addMine`
  keeps it in `files/my-cameras.json` (`{"version":1,"cameras":[{"lat","lon","kmh"}]}`), on this unit only: it is not
  published and not in the editor. One within 40 m of a camera already there is not added again ("already marked").
  `MapExtras.spots` = the published ones + these, so they are drawn and announced the same way; the one just marked
  is not announced (`RoadWatch.quiet`). The strip beside the button takes the last one back for 5 s. Settings →
  Navigation: the button's switch (`markCameraButton`, on by default) and how many are kept, with delete-all.
* Debug: `setprop debug.aura.extras http://10.0.2.2:8765/api/data` reads the editor on the PC directly (run it with
  `--dry-run`); `adb emu geo fix` + `MapsActivity --ez simulate true` drives a route past them.

### Valhalla in its own process (2.0: `nav/route/`)
The router of 2.0, over the pack's `route.tar`; still behind a preference. Everything below was measured on the x86_64 emulator with
the real tar; nothing here has run on ARM on the real kernel. That is what the probe at the end of this section is for.

* **The library:** only `libvalhalla-wrapper.so` of valhalla-mobile 0.6.4 (`jniLibs`, 6,017,176 B on armeabi-v7a, stored), and
  `com.valhalla.valhalla.ValhallaKotlin`, whose name and methods its symbols fix (R8 rule). No AAR, no Moshi. Errors come as JSON with a
  numeric code ({"code":171}), no exception. A missing tar still gives an actor, and 171 for every route.
* **`:route`:** `RouteService`, bound by `RouteClient` when a route is asked for, let go 3 minutes after the last request but not while
  a trip runs; then the process ends by itself (`RouteProcess`), the memory back at once. `AuraApp` does nothing there, as for `:boot`.
  A cold process answers Jubail-Dammam in 367 ms here. Nothing on the main thread waits for it: the request goes one-way over Binder, the
  answer is read on a thread of its own.
* **The answer (2.35 MB at the worst, Najran-Tabuk with the old options: 592 ms in the library, 596 ms parsing, 1.6 s in all)** never
  goes over Binder. `:route` parses it with `Router.parseRoute` and writes the same `Route` and `Step` objects, whatever `Router.prepare`
  measured included, to a pipe that came with the request (`RouteCodec`). A process that dies closes the pipe: a frame cut anywhere
  reads as nothing, and the caller hears `DIED` at once. That is how a death is noticed; the connection only tidies up.
* **One at a time:** a newer request ends the wait for the older one (`CANCELLED`, every caller hears exactly once) but not its work:
  the native call cannot be stopped, so the newer one waits for it, inside its own 30 s. After 30 s the process is killed.
* **The request** (`RouteRequest`) is `tools/mapbuild/route-request.json`, see there and `trips.py` why: heading with 45 degrees when known,
  `search_cutoff` 1000 on the destination (or Jana Island gets code Ok and a route ending 22 km away), `search_filter` for the service-road
  switch, `exclude_polygons` (Jeddah-Taif avoiding the Haram 207.7 km, as on the PC; a destination inside: `NO_ROUTE`), via points
  (`through`, or `break` for a stop). Khafji's post, 1,108 m from a road, is refused by the cutoff: as the tests asked, and worth a real
  drive's opinion.
* **Its reading** (`StepTune.kt`): one name per step, by the screen's script (`pick_name` of `route_fields.py`), before `prepare`. Each
  step's time times the factor for its own speed, `Route.etaEstimate` true: an estimate, fitted against OSRM and no real drive; raw -13.6 %
  against OSRM's time over the 30 trips, scaled +2.1 %. `RouteAnswer.rawSeconds` keeps Valhalla's own, for the owner's logged drives.
  The fastest route goes first, as the tests asked, but not if over 3 % longer than Valhalla's first (my limit): Riyadh-Salwa offered 523 km
  against 474 for 1 % of a time that is itself an estimate. Three of the 30 trips start on an alternate this way, each shorter and closer to OSRM's.
* **Speed limits:** BRouter's `messages` fed the sign, and Valhalla's route answer has none. `trace_attributes` with `edge_walk` on the
  chosen route, 190 km at a time (the request's limit), comes as a second frame on the same pipe, so a route never waits for it, and
  is put on `Route.limits` on the main thread. 30 ms for 948 km. A newer request stops it. A point it cannot walk (a via) only costs the
  few points around it: each failing piece is halved. The alternates have none.
* **`RouteGuard`:** while a request is worked on `files/diag/route-working.marker` lies there. A death with work in hand counts, by the
  pipe, the connection or, after a restart, the marker of a pid no longer alive; a timeout counts too; an idle process the system reclaims
  does not. Two in a row trip it; any answer of the engine ends the row. State in `route-guard.state`, gone with a new versionCode or after
  7 days; a clock before the trip says nothing. All it decides, and each time the chain moves on, in `route-guard.log` (at most 16 KB).
* **A minute's rest after every death.** A second crash of one process within a minute is what makes Android put "AMRI OS keeps
  stopping" over the home screen: seen here with two real SIGSEGVs 4 s apart (AppErrors, `MIN_CRASH_INTERVAL`, the first crash shows nothing). So
  `allowed()` says no for 65 s after a death and the chain goes to the next router. The same two crashes 69 s apart: tripped, and nothing on the
  screen. The probe waits the same way.
* **`RouteChain`:** Valhalla; BRouter when it is guarded, the pack is missing or it failed; OSRM as the last try. Abroad (`assets/route/ksa.poly`, either
  end) with a connection OSRM comes first. "No road" or "no route" inside the Kingdom is believed: OSRM would give a route that ends at the nearest
  road, code Ok. `Router.route` always comes here (2.0: no race, no head start, no preference); with no pack, BRouter or OSRM answer and
  no process starts. New screens: `RouteChain.ask(RouteRequest(...)) { answer -> }`: all the routes, `answer.engine`; only Valhalla honours via points, polygons, the switch.
* **`TarCheck`:** the library trusts the tar's index. Without this check a tar cut at 200 MB is a SIGSEGV in `libvalhalla-wrapper.so` (seen). With it: `BAD_PACK`
  in 2 ms, by reading the index, the file's first entry, once for each file. A new file under the same path gets a new actor (by length and date).
* **Debug:** `am broadcast -a com.abdllh.aura.debug.ROUTE`: `--ez valhalla true`, `--es tar <path>`, `--es ask a,b,c,d[,heading]` with `--es ring`, `--es via`,
  `--es road`, `--es raw`; `--es chain ...`; `--es batch <file>`; `--el delay_ms` (the service sits on a request, marker down, so it can be killed in the middle),
  `--el idle_ms`, `--ez guard true`, `--ez guard_reset true` (`RouteDebug.kt`; `setprop debug.aura.osrm` for OSRM). A tar on the AVD: push to
  `files/maps2/current/route.tar`, chown to the app's user, `restorecon`. `tools/mapbuild/tests/app_route_check.py` runs the 30 trips and compares.
* **Measured on the emulator, not true on the unit:** 30 trips in 8 s: 26 the same first route as `out/ref-routes` to the metre, minute and step, 3 an
  alternate first as above, Khafji refused; the worst 231 ms in the library, 133 ms parsing. 150 routes in a row, one process: its PSS flat at 151-158 MB (native heap
  about 33 MB, Java 4 MB, the rest the tar's clean pages, which the kernel can drop), threads and file descriptors no more than before. Probe in a release
  build: PSS 60-66 MB, HWM 148 MB. R8: the same 30 answers. Long JSON on a slow CPU is the open question: `Router.parseRoute`'s placing of steps
  (`line.snap` over the rest of the line, for each step) is quadratic, and the probe times the JSON on the unit.

**The route probe (`RouteProbe.kt`, the scout release):** does Valhalla run on the real ARM32 userspace at all? Started from the main process (`RouteProbe.start`, the Screen
check page is to call it; debug: `am broadcast -a com.abdllh.aura.debug.ROUTE_PROBE`), it works in `:route`, stage by stage: RAM, `/data`, kernel; the static AArch64 program
(`tools/route-probe`, 664 B, two system calls), straight from app storage, then through `LocalAdb`; the 1.5 MB tar unpacked and its SHA-256; the library's load time, the actor;
four trips with ms twice, km against the reference (Jubail-Khobar 104.85), JSON ms, VmPeak, VmHWM, PSS; a sparse 400 MB mapping, since `route.tar` is mapped whole. A marker before
each stage and the report written after each (`files/diag/route-probe.json`): a crash or a hang (90 s) is that stage's, `died` or `hung`, the next process goes on after a minute's rest,
three deaths at most. It is its own commit: cherry-picked onto `v2-fixes` it builds and runs. Here, direct exec gives error 13 under enforcing SELinux and the root shell runs the
x86_64 twin. The firmware is said to be permissive; the report will tell (`getenforce`, `uname -a` in `root.output`).

## Aura Music (`music/`)
### The panel of 2.0 (SPEC.md 6.4: `MusicPanel.kt`, `LyricsView.kt`, `LyricChip.kt`, `MusicSheets.kt`, `Backdrop.kt`)
The logic of the old screen stayed line by line (`Player`, `PhoneLink`, `BtMusic`, sources, the owner's timing tap and hold, the delay's measure, word times, the YouTube hand-off, keys, the build ahead); what it looks like is new, at the spec's pixels from the left in both languages. `gallery_compare`'s way of looking, rect by rect against `music-night`: cover, title, transport and side tools at +0,+0, lyric lines within 2 px (a line is drawn at the larger role and scaled, section "Lyrics" below).

**Collapsed.** `opensFull()` is parked with no trip; otherwise the panel lies collapsed: `root` hidden, `opaque` and `solid` false, `overMaps` true, so `PanelStack.besideFor` keeps Maps' frame drawn and live under it, the map LIVE, the dock's music cell still selected, and Back or a second tap on the cell go to the panel he came from. So a trip has no special path for Music: the Chrome and the key open it as ever and it simply shows nothing but the chip. `DriveState` going to Moving or a trip beginning calls `collapse()` (full lyrics closed, not remembered; `panelChanged` first, so the map is awake before the wash fades, 180, one layer). Nothing comes back by itself when the car stops. `expand()`, the chip's tap or `onArgs` (the mini's title, the widget, the key) if `fullAllowed()`: parked, and on a trip only with gear P known; the map freezes only at the end of the fade. Moving, the chip's tap is off for this song. FROZEN under the full panel is the stack's `opaque` as before.

**Lyrics.** A line is laid out once at the larger role and shown at the smaller by a transform, so nothing is measured when it lights and it wraps the same either way. Lines outside the five have alpha 0: they come and go by their own colour, and a hand brings them all back. With no line lit (Bluetooth with its place unknown, untimed lyrics) it is the first five until a hand moves them, and then they stay where he left them, all of them, to be read and tapped; otherwise a sixth line stood half under the dock. The pill: the word's own box, 13 sideways, 3 above, 5 below, real times only, in the song's colour; the words around it part by translation, as the mockup's inline pill pushes them, one laid-out text with rect clips, so no layout while the song plays. Only the line's view redraws, and the seek's tick waits for a pill that is moving.

**The chip** is `music/LyricChip`, in Maps' panel by one line (`root.addView(LyricChip(this), LyricChip.place())`, between the column and the browse cards) and driven by Music's own tick, which knows the song. A long line shows the part with the current word, cut at word boundaries. The pill on it is `Asked.LYRIC_WORD_MOVING || DriveState.parked`, real times only. It holds no state but what it shows; a rebuild of Maps makes a new one, and the next tick wants it again.

**Sheets** are `SourceSheet` and `QueueSheet`: `Sheet`'s side form, in `shell.root.sheets`, made at their first open, x 12 or x 384 by `placeOnTrip`. A trip beginning closes one at x 12, which would lie over the column. `MusicPanel.openSources(shell)` is the mini's cover from any screen. The queue from a phone or YouTube is the one song and a line that says where the list is; shuffle and repeat are the unit's own player's.

**Theme.** `recolours` true; the song's wash for the other theme is made in `ThemeSwap`'s ready step, so the flip decodes nothing. A theme that comes by the old way (`Theme.refresh` in old Settings and old Controls, `SettingsActivity --es theme`) marks no hidden panel stale, and half of it would go with `Palette` and half not: `catchUpLook` looks for itself when it comes back. `GalleryReceiver --es swap day` is the real path, live.

**The language of the window.** Starting any activity updates the process's configuration, and with that Java's default locale goes back to the system's. With an activity per screen the next screen set it again; in the one window an `Entry` (`MusicActivity` by name, as the firmware starts it) left it there for good: the strip's `AM`, and every `TEXT_DIRECTION_LOCALE` label laid out after it reads backwards (`USB والملفات`, `20 أغنية`, the artist after the source). `Entry` wraps its context as Home does. `AuraApp` in the Maps branch answers it at the root too.

**To try it** on the emulator. Lyrics: `lyrics_standin.py`, prefs `lyricsServer` `10.0.2.2:<port>`; with Arabic lines copied into a scratch copy of its `WORDS`, none in the repo. A phone's song: `setprop debug.aura.phonelink 10.0.2.2:<port>`, `naghm_standin.py`, then `am broadcast -a com.bt.ACTION_AVRCP_MUSIC_ID3 --ez sim true --ez sim_first true --es extra_avrcp_id3_title … --es extra_avrcp_id3_artist …` from a script on the device (`adb shell` mangles Arabic from PowerShell). A cover for it: `cache/covers2/<sha1 of norm(artist)|norm(title)>.jpg`, from `src/debug/assets/gallery`. Moving: `ChromeProbe --ef kmh 60`, `--ez nofix true`. A trip: `-a com.abdllh.aura.debug.NAV --es fix lat,lon,0,60`, `--es go lat,lon,test` (OSRM with no pack). Gear: `-a com.abdllh.aura.debug.GEAR --es gear P`.

Not done here: section 9's other per-pair laws, a way to the queue from the mini on a trip (the spec names none; it is on the full panel). Tried on the emulator only, nothing on the unit, nothing measured.

### What it does underneath (1.x)
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
comes up over Aura Music (`Front.ownIs(Screens.MUSIC)`: left the front within 1.5 s) on the source Aura Music plays is the
second press: the home screen instead. The other source's screen is its own button, and changes the source as before
(MODE, key 23, loops 2 → 3 → 8 → 9 → 11 → 15). Emulator: `settings put system activity_starting
com.nwd.bt.music,com.nwd.bt.music.home_horizontalActivity` with another app started over Aura Music a moment before.
All of that answers a stock screen that is already in front: the owner saw it for a moment. Nothing tells of the press
earlier — `ACTION_KEY_VALUE` is not sent for a key that has a rule, and `ACTION_CHANGE_SOURCE` / `key_appid` change in
the very call that starts the screen (`changeSource` → `pushApp` → `startApp`). So since 1.9 the two music keys are
taken out of the firmware's key rules (`MusicKeys.applyRules`, root): `/data/nwdappconfig/app/KeyRuleConfig.xml` (the
only copy KernelService reads; `SourceMgr.<init>`, once, no reload) loses its `<KeyItem Key="83">` and `Key="99"`
blocks. A key with no rule is only announced (`InputChangeSourceRule.isCanPanelChegeSource`: "no keyItem can
response" → `sendKey`), the way the volume keys are, and `MusicKeys.key` opens Aura Music at the press itself: nothing
stock is ever started. It refuses what the firmware refused (a phone call, reversing), keeps the firmware's 1.5 s
between presses (a key held down is announced again and again), and the second press inside Aura Music still goes home. The edit is made on a copy and checked (as many rules
opened as closed, the list's end, key 23 still there: a file the parser cannot read loses every key's rule silently)
before it is written through the file; the original is `KeyRuleConfig.xml.pre-aura`, which the switch going off, the
kill switch and the ROM restore's `*.pre-aura` loop put back. It works from the unit's next start; until then, and
for every other way a stock music screen comes up (MODE, key 97, a CAN box that starts `com.nwd.bt.music` itself for its
key 26, the ignition, the app list), the cover above stays. Settings → About shows how the button last arrived
("key 83" or "stock music screen"). Not done: pointing app 2 / 15 at Aura in ApplicationList.xml or the replace list
(the force-stop above, and key 83 on any Aura screen would then *leave* instead of opening), and disabling the stock
screens (a CAN key 26 would then do nothing at all, with no sign of it for Aura). Emulator: `setprop debug.aura.unit 1`,
the rules file pushed to `/data/nwdappconfig/app`, the root shell through `scripts/fake_adbd.py`, then `am broadcast -a
com.nwd.action.ACTION_KEY_VALUE --ei extra_key_value 83`.
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
The lyrics alone on the whole screen (1.7): the button in the panel's corner (`MusicPanel.showFull`; Back, or the
button where the screen's own way back is, returns; kept in `musicLyricsFull`). A second `LyricsView(big = true)` in a
layer over the screen (38 sp lines), with a small bar: cover, names, previous / play / next and a thin progress line.
Both views are fed alike and the layer is INVISIBLE, never GONE, so it stays laid out: going there and back measures
nothing, it only fades and moves; the one out of sight follows the song without animating (`at()` when `!isShown`), so
it is in place when shown. The cover travels between its place and the bar's as a copy above both (`fly`), its corner
radius following its size. Lighting a line: `LyricsView.Line` says `hasOverlappingRendering() = false` (a dimmed
TextView with a background was drawn through an off-screen buffer on every frame, a dozen of them while the lines
scroll; since 1.12 lines have no background at all, the list draws the pressed tint), and the alpha + scale change of
the two lines involved runs `withLayer()`.
`relayout` (lyrics on/off, another source) is a `LayoutMove`: the cover, the names and the top buttons travel, the
panel or what was in it fades where it stood.
A song without lyrics gives the room back (1.9): the button is the owner's wish (`lyricsWanted`, the pref), their
place being open is another thing (`lyricsOn`). When the lookup of a new song ends with nothing — none, an
instrumental, the server not reached — `lyricsHave(false)` closes the panel, and leaves the whole-screen lyrics first
(without touching `musicLyricsFull`); the next song that has some opens them again, the size they were. While a song is
looked up the place stays as it is (open with "looking", or closed), so it does not flap between two songs that have
lyrics. The song the button was pressed on is the exception (`lyricsPinned`): there the line saying that there are
none is the answer to the press. A change that happens inside another (`relaying`) joins its movement.

YouTube Music (1.10, `music/YtMusic.kt`). The owner asked for an iPhone player fed by the ytmusicapi library and tied
to the unit; that cannot be had — ytmusicapi finds songs, it never carries their sound (its FAQ sends downloads to
another tool), pulling YouTube's audio outside its own app is not something this project does, and iOS lets no app
read what another one plays. What ytmusicapi's search can do went in instead, twice:
* `YtMusic.find`: the request YouTube Music's own web page makes (POST `music.youtube.com/youtubei/v1/search`, client
  `WEB_REMIX` with today's date as its version, the "songs" filter `EgWKAQIIAWoMEA4QChADEAQQCRAF`, `hl` Arabic for an
  Arabic query so the names read like the phone's, cookie `SOCS=CAI`, no account). It answers with rows of
  `musicResponsiveListItemRenderer`: the title, "artist • album • 3:54", `playlistItemData.videoId` and a picture
  whose size is in its address (`=w120-h120` → any). Not a published interface: when Google changes it the search
  finds nothing until this is updated; nothing else depends on it.
* A Bluetooth song's own length and cover: `CoverSearch` asks this catalogue first (then iTunes, then Deezer). The
  phone plays from it, so the first song there with that name and artist is the song itself; its length is believed
  over the lyrics' recording and over another catalogue's (`BtMusic.offerDuration`, `FROM_SERVICE`). With a length the
  bar and the times show for every song whose start was seen (the clock of 1.6); the position itself is still not
  the phone's word. Two recordings under one name (an album cut of 5:15 and a single of 5:54) cannot be told apart:
  the first is taken. The covers kept before 1.10 are dropped once (`cache/covers2`).
  Matching (1.10.1): up to three searches — "artist title"; for a title like "Artist - Song" (a video's name, the
  artist field often the album) both halves each way; then the title alone. A row counts when its title is the same
  song (equal, starts with, contains, or the same consonants across Arabic/Latin; ≥ 0.75) and its artist agrees
  (≥ 0.5), or, with no artist to compare, when the title is the whole of it. The version day: today (UTC), then
  yesterday on HTTP 400, then a known good one (`KNOWN_VERSION_DAY`). When YouTube Music could not be asked (no
  internet, refused) what another catalogue found is shown but not kept, and `BtMusic` asks again (3 times, 15 s
  apart, more each time). Settings → About shows the last lookup: what was asked and where the answer came from, or
  why YouTube Music gave none (`CoverSearch.note`).
* A third source in Aura Music, "YouTube": a search box and its results in the panel; a song chosen is handed to
  YouTube's own app on the unit (`com.google.android.apps.youtube.music`, else `com.google.android.youtube`) as the
  link its share button gives (`ACTION_VIEW music.youtube.com/watch?v=…`), which brings that app to the front; when
  its session plays something that started after the hand-over, Aura Music comes back (`YtMusic.onSession`, 12 s at
  most, else the owner is left in the app: it wants something on its own screen). From then on the song is read and
  steered through the app's media session (`MediaMonitor.app` / `appPosition` / `appControl`, for any of
  `YtMusic.players`): title, artist, cover, length, the real position, pause / next / previous, the seek bar, a
  tapped lyric line; the lyrics are looked up with the song's real length. `MediaMonitor` now listens to every other
  app's session, not only the one the home card shows. Leaving the source pauses the app; the music button comes
  back to this source when it was the last one that is not Bluetooth (`musicSourceOwn`). The firmware injects the
  wheel's media keys to the playing session itself when Aura's own player does not hold them. Playing with the app
  out of sight needs what YouTube asks for it (a Premium account): a session that stops within 3.5 s of Aura Music
  coming back is said so once. Settings → About shows how the last hand-over went.
* Not tried before the unit: the hand-over itself and what YouTube's app does with it (neither emulator has a
  working YouTube app: the Google Play image's is from 2020 and wants a sign-in to update). Tried on the emulator:
  the search, the Bluetooth lookup, and the whole session side against a stand-in — `gradlew assembleDebug -PstandIn`
  builds a second copy of the app as `com.abdllh.aura.standin`, whose player is "another app with a media session";
  `setprop debug.aura.ytpkg com.abdllh.aura.standin` (and notification access for the real copy, which is not
  privileged there: `cmd notification allow_listener com.abdllh.aura/com.abdllh.aura.media.AuraNotificationListener`)
  points the YouTube source at it.

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

### Naghm, the owner's iPhone player (1.17, `music/PhoneLink.kt`)
The owner's own iPhone app, Naghm (`D:\chatgpt\Naghm`, protocol in its `CAR_LINK.md`), plays YouTube Music to the unit
over Bluetooth and tells, over the iPhone's hotspot the unit is on, what this module never does: where the song is.
Naghm listens on TCP 47100 (and announces `_naghm._tcp`); `PhoneLink` connects to the Wi-Fi's gateway — the iPhone
itself on its hotspot — every 5 s while not connected (a refused connection to a home router costs nothing), says
hello, pings every 5 s (16 s of silence drops it) and reads one JSON object per line: `state` (id, title, artist, art
URL, duration and position in seconds, playing) on every change, a seek, and every ~3 s while playing. Connecting and
reading run on one thread, writing on another; what the screen reads is set on the main thread.
`BtMusic.adoptPhone`: while the A2DP link is up and Naghm plays (or holds the song the module names, `sameSong`: the
module may cut a long title), Naghm's names, length (`durationFromPhone`), position (the clock re-based when it is 300 ms
off) and its own cover (`PhoneLink.art`, no catalogue lookup) replace the module's guesses; another app on the phone
keeps the module's (a title from the module that is not Naghm's drops `phoneOwned`). With `viaNaghm` / `canSeek` the
seek bar takes drags for a Bluetooth song (`enabledSeek`), a tapped lyric line moves the song as the unit's own songs
do, and the transport goes to Naghm (`control`), not to the module (no "did previous restart it?" guess). The names say
"· through Naghm". Settings → About shows the link's last news (`phoneLinkNote`).
Emulator: `setprop debug.aura.phonelink 10.0.2.2:47100`, a stand-in server on the PC (hello, a state every 3 s, answers
seek / toggle / next), and a simulated Bluetooth song (`--ez sim true`): the bar showed the phone's 6:02 of 9:35, a tap
on the bar sent `seek 143.46` and the bar showed 2:25, next brought the next song with its cover and length, the home
card followed. Not tried before the unit: the connection over the real hotspot, and Naghm itself on the iPhone.

### The Bluetooth sound's delay (1.18)
What the phone plays comes out of the car's speakers later: buffers on the phone, in the module, on to the amplifier.
Naghm's clock (and the module's guesses) say what the phone plays, so the lyrics ran ahead of what is heard.
`BtMusic.delayMs` (Prefs `btDelayMs`, 0–2 s) is that delay; `MusicPanel.lyricsShift` is, for a Bluetooth song, the
owner's own correction of that song when he made one (1.15's tap: measured against what he heard, the delay is already in
it), else minus the delay. A tapped line on Naghm's song seeks to the line itself (`l.ms - lyricsOffset`: the delay only
makes it heard later) and stays lit until its sound comes out (`tapLitUntil`, the delay + 400 ms) instead of the line
before lighting again meanwhile. The delay is set in Settings → Display & sound → Music (a slider in 50 ms steps, shown
anew at each visit) or measured: on Naghm's song, with no saved correction, a lyric line held down the moment it is heard
gives `PhoneLink.positionMs() − long-press timeout − 200 ms (reaction) − line start`; below −300 ms or above the maximum
it asks again. A one-time snack the first time timed lyrics show on Naghm's song with no delay set says how
(`btDelayHinted`). The bar and the times keep the phone's clock.
Emulator (stand-in Naghm on Despacito, LRCLIB's timing): a line held 0.9 s after it started (adb's swipe lands
~0.13 s after it is sent) measured 0.83 s, the lit line moved back by it at once, a tapped line sent `seek 102.09` (its
own start) and stayed lit through the delay, and the slider showed 0.83 s, set 1.50 s with a tap, and read right in
Arabic. Not tried on the unit: the real delay of its module.

### Naghm's lyrics on the screen (1.19)
Naghm (1.6) sends, with each song, when the screen connects, and on `{"type":"lyrics"}`, the lyrics the phone has:
`{"type":"lyrics","id","title","synced","source","lines":[{"time": seconds or null,"text"}]}` (no lines: it has none).
`PhoneLink.lyricsOf` makes them a `Lyrics.Result` (`SOURCE_NAGHM`, ms times), kept with `lyricsId` / `lyricsTitle`.
For a Bluetooth song that is Naghm's (`MusicPanel.phoneLyrics`: the link's song, `sameSong` titles) they are shown
in place of the screen's own lookup — at once in `refreshLyrics` when already there, by `phoneListener` when they
come later, and a lookup that answers after them is dropped. They carry no correction of their own, so the Bluetooth
delay applies (`lyricsShift`). `BtMusic.canSeek` is now the link playing the module's song (not only while Naghm owns
it: the module's title word drops ownership until Naghm's next state), so a tap on a line moves the phone there and a
hold measures the delay (`measureDelay`) as before.
Emulator: the stand-in's third song ("أغنية لا يعرفها إلا نغم", no lyrics site has it) showed the phone's 30 lines,
the line at 0:43 lit (its 8th, at 39 s); a tap on its 13th sent `seek 64` (that line's start) and the screen showed 1:05
on it.

### Aura Music in 1.12
The screen uses the design system's tokens throughout (`Type` roles, `Radius`, `Space`, `Touch`, `Elev`, the `Motion`
helpers, `Snack`). Strings added in 1.12 are in `res/values*/strings_music.xml`.
* **The backdrop is the window's background** (`music/Backdrop.kt`). It was four full-screen layers (the window's colour,
  a gradient, the cover scaled up from 18 px, a veil) filled on every frame; on the unit's GPU that was several
  milliseconds a frame before any content, and the 18-texel cover stretched to 600 px showed creases, not a blur.
  `Backdrop.look` works those layers out once per cover into one opaque picture 48 texels across (the screen's shape),
  the cover halved step by step and blurred for real (three box passes), and `applyLook` hands it to
  `window.setBackgroundDrawable` with filtering on: one opaque fill a frame, two while a new one crossfades in
  (`TransitionDrawable`, `Motion.AMBIENT`). It runs on its own single-thread executor (`lookAsync`); only the very first
  look is made on the main thread, in `buildUi`. Nothing else may set this window's background. (Since 2.0 the panel's root
  has it, not the window, which has none: "Music in the one window".)
* **The cover's colour** comes from `Backdrop.colours` as `fill` (progress, play buttons, indicators, equaliser bars,
  the shadow's glow) brought to 3:1 on the background, and `fillText` (the playing row's title, Play all, the crumb's
  chevron, shuffle / repeat when on, the lyrics hint) brought to 4.5:1 on the playing row's tint (a dark blue cover was
  1.8:1 on the dark theme, a yellow one 1.3:1 on the light). Grey covers keep the owner's accent. `accentTo` moves every
  coloured part to the new colours in one animator (`Motion.MEDIUM`), recolouring kept drawables in place (`Tint`);
  the list is told only at the end (`repaintRows`).
* **A new cover** comes in by direction (`swapCover`: `art`, and `artOld` under it, inside `artBox`). A skip press
  records its direction for `SKIP_MEMORY_MS`; the new cover comes 28 dp from that side on `EMPHASIZED` while the old
  one leaves half that way on `EXIT`. Previous / next sit left to right in every language, so the direction is not
  mirrored. The unit's player going on by itself counts as forward; the phone or YouTube changing the song gives 0 (it
  settles in place from 104 %); a better copy of the same cover is a plain crossfade. `artBox`'s own transform is never
  touched (`LayoutMove` and `fly` own it). Bluetooth: a new song arrives without its cover; the old one stays dimmed to
  0.45 for up to `ART_WAIT_MS` (`artWait`, removed in `onStop` and on a source change). YouTube: `MediaMonitor` keeps
  each session's metadata from its own callbacks, so the Bitmap stays the same instance until the app sends new
  metadata; a new instance is compared with `sameAs` once. The home card benefits from the same stable Bitmap.
* **Paused**, the cover breathes out to 94 % (`breath`, a spring on `artSlot`, not `artBox`). `relayout` snaps it to 1
  and moves its scale onto `artBox` for the move (LayoutMove measures a view by its own scale); the slot springs back
  to 94 % while `artBox` travels to 1 (started from a pre-draw listener after the move has measured), so the paused
  cover lands at its size in one motion.
* **After a relayout** (`redrawAfterMove`, once, `Motion.LONG` + 32 ms after the move) every label in the song's column is
  invalidated so each records its text anew. Without it specks of other letters showed beside the title, the artist
  and the length after Device & USB → Bluetooth or the lyrics panel opening: text that travels is drawn at a new
  fraction of a pixel each frame, the GPU's glyph atlas fills and reuses its places, and a label still drawing what it
  recorded before the move picks up pieces of what took them. Invalidating the column alone did not clear them;
  re-recording the labels did (until 1.12 the labels were set on every tick, which hid it). Anything new that travels
  with `LayoutMove` and keeps its text belongs under `left` or needs the same. Check it on the unit, whose GPU has a
  different glyph cache.
* **The song's side** (`buildUi`): title 28 sp bold, one line beside the library (34 sp, two lines, when Bluetooth has the
  screen to itself); the album joins the artist line (`paintNames`); the third line is only for the Bluetooth layout
  (the phone's name). 88 dp play, 72 dp skips, 56 dp shuffle / repeat. `setTransport` dims previous / play / next to
  0.4 and makes them refuse taps when there is nothing to steer (Bluetooth without a phone, YouTube without a session,
  the unit's music with no queue and an empty library). Bluetooth without a phone: the artist line says how
  (`music_bt_hint`) and "Bluetooth settings" is the screen's main button. The source control in the header is
  `Type.FOOTNOTE`. `applyLayout` sets the start column's params again (a changed `marginEnd` becomes a right margin only
  when params are set), only when the layout changes (`laidWide`). Repeat one shows a "1" badge in the cover's colour
  on the repeat icon's corner (`repeatOne`); 1.11 told ONE from ALL only by a 10 % alpha difference. Shuffle and repeat
  hide as whole buttons for Bluetooth and YouTube (hidden icons left a spot that still changed the unit's player).
* **Progress**: the seek strip is 52 dp tall (the bar thin in its middle); held, the bar thickens and the knob grows on a
  spring (`grab`). A position that is not known shows no bar and no times, and a Bluetooth song's length goes beside
  its artist meanwhile. After a seek the position shown is the one asked for (`holdSeek` / `held`) until the source
  agrees (within 1.2 s) or `SEEK_HOLD_MS` passes (YouTube and a USB stick answer a seek late, and the knob jumped back
  and forth). The times sit 8 dp into the strip's lower edge, with fixed equal halves, set only when they change
  (`setTextIfChanged`, private to `MusicPanel.kt`): a WRAP label asked the whole screen for a layout every tick.
* **The progress tick** comes when the next lyric line is due (`LyricsView.msToNext`, at most 250 ms with lyrics, 500
  without, at least 16), and the YouTube branch reads only `appPosition`, never the bitmap. `showNowPlaying` (every
  Bluetooth broadcast, about once a second) changes only what changed.
* **The library** keeps one `ListView` and one album `GridView` for the screen's life (`showList`). A reload (a USB stick
  being indexed reloads every few seconds) only tells the adapter; another tab or group swaps the adapter and comes back
  where the owner left it (`listSpots`); the open group is found again by its key after a reload. The playing song is
  brought into sight only the first time a list shows, and followed (`followPlaying`) only when the owner was looking
  at the song that just ended. Motion (`moveIn`): another tab fades in place; a group comes from the end side, Back
  from the start side, mirrored in Arabic. Rows make their drawables and click listener once; `Track.subtitle` and
  `durationText` are worked out once per song (`Library.kt`); covers are asked for at the size shown.
* **States**: a spinner and "Loading your music…" while the library loads; a YouTube search on its way swaps the search
  icon for a spinner and dims the results to 0.5. Storage permission is asked only from the library's Allow button or
  when the owner chooses the library source.
* **Lyrics** (`LyricsView`): each line's text is measured on a worker thread (`PrecomputedText` with the template line's
  `textMetricsParams`; a mismatch falls back to plain text), lines are made a dozen a frame (`buildStep`), the copy out
  of sight makes its own after 1.5 s of calm or the moment it shows (`settle`), and an answer that comes while a new
  song's cover and names move waits for them (`songMovesUntil`): both copies made 60–90 lines each in one main-thread
  message before. The lit line is centred by the layout that adds the lines (`centerOnLayout`). Following is a spring
  (`follow`, stiffness 110, critically damped) that keeps its speed when retargeted; a line is lit 150 ms early
  (`LEAD_MS`); 4 s after a hand scroll the lit line comes back by itself (`followAgain`). Another song being looked up
  dims what shows (`dim`); "Looking for the lyrics…" only after 600 ms (`lyricsLoading`); "failed" has Try again. The
  whole-screen lyrics: 34 sp lines, a 40 dp fading edge, and lines to be read rather than followed start near the
  top, below the sync hint's measured height.
* **Theme and language while open**: Music lived in its own task, so it came back from Settings in the old language and
  stayed on the day colours all night under "auto". `checkLook` (in `onResume`, and from a `TIME_TICK` receiver while
  resumed) called `recreate()` when the theme, accent or language it was built in changed; no snapshot crossfade yet. (Since
  2.0: `onLook` builds the panel again in place, with its state, and the language is the whole window's.)
  `MusicService` wraps its context with `LocaleHelper`, and YouTube's "stopped out of sight" toast a wrapped context.

### Lyrics from the owner's server, timed by listening (1.13: `Lyrics`, `LyricsServer`, `LyricsSync`, `tools/lyrics-server`)
The owner asked for more places to fetch lyrics from ("find lyrics": read as more lyric search sources), Genius as the
last resort, and Genius's plain lyrics to be timed against the song by a script on his own PC, published through his
own domain. Two halves were built. `tools/lyrics-server` is a Python program for his PC (port 8790 on 127.0.0.1, put on
his domain with a Cloudflare Tunnel; README.md / README.ar.md there say how). The app asks it after LRCLIB, and, when
he switches it on, lets it time untimed lyrics by listening to the song in the car. The sound never comes from YouTube
or any streaming service: for a Bluetooth song it is what the car's microphone hears, for a song on the unit it is the
file itself.

#### The order of the lookups (`Lyrics.kt`)
What is kept on the unit, then LRCLIB as before, then the owner's server (`LyricsServer`, only when an address and a key
are set), then nothing. LRCLIB's plain lyrics are shown at once and the server is asked afterwards on a thread of its
own (`serverIo`, so a slow or absent server never holds the next song's lookup): when it has timed lines (one of its
sources had them, or it made them by listening) they are kept in the song's place and handed to the screen through
`Lyrics.onTimed`. When LRCLIB has nothing the server is asked in the same lookup. A kept "none" now records whether the
server was asked (`"server"`, a time): LRCLIB's "none" with a server that was not reached makes the next play ask the
server only, and a server that was reached keeps the "none" for the usual three days. A server that does not answer
never turns LRCLIB's answer into "failed": the screen does not retry every 20 s because the owner's PC is off. The
server's answers are kept in LRCLIB's record shape with two more fields, `source` and `covered` (a timing by listening
not complete yet is asked about again at the next play: `Result.improvable`).

#### The server's sources (`tools/lyrics-server/sources.py`)
First of all, since 2026-10-18, YouTube Music's own lyrics (`YouTubeMusic`, through the `ytmusicapi` package, no
account): the catalogue the owner plays from, licensed from LyricFind or Musixmatch (the "find lyrics" he asked for was
LyricFind, named under YouTube Music's lyrics) and timed for most songs. On his first evening with 1.13 four of his six
songs came back plain from Genius or not at all; YouTube Music had all six timed. Found by name and artist, the lyrics'
browse id from the watch page, the times from the Android client ytmusicapi asks (`get_lyrics(…, timestamps=True)`);
"♪" lines become empty timed lines. `YTMusic()` as it is: with language "ar" and location "SA" every search came back
empty. Besides the general match, a result with the very same title is taken when its artist is the same name in the
other script (`same_song`: "حودة" is "Houda", 0.63 alike, under the general rule's bar), never another singer's song of
that name. Then the timed sources of the `syncedlyrics` package are asked at once (Musixmatch, LRCLIB, Megalobiz, NetEase) and the first
of them in that order with a matching timed text wins; Genius is asked only when none has any. `syncedlyrics.search()`
itself was not usable as it is: it returns the text of whatever it took without saying which song that was, and two of
its sources had changed by October 2026. Musixmatch hands an anonymous client a placeholder token (all zeros) and
answers every question with invented lyrics ("Wob gopini den…") for some other song: the server sees the token and
leaves Musixmatch out as "unavailable" (not an error, so a "nobody has it" is still kept). Megalobiz removed the search
page `syncedlyrics` 1.0.1 reads (410 Gone) and offers a public API instead, which the server uses; given an Arabic title
it does not have, it answers with another song by the same singer. So every candidate is compared with the song asked
for, by the app's own rules (`textnorm.py` is `TextMatch.kt` and CoverSearch's matching in Python): the title (without
"(Official Video)", "feat.", "حصرياً"…), the artist, across Arabic and Latin spellings by consonants, and the length
within 4 s when both are known. Genius refuses the requests library's User-Agent (403), so its requests say they are a
browser; its pages' furniture (contributors, translations, the blurb: `data-exclude-from-selection`) and "[Chorus]"
headings are taken out. Answers are kept on the PC: timed two months, plain three days (a timed source may add the song),
"nobody" two days, half an hour when a source did not answer.

#### Timing by listening, on the unit (`LyricsSync.kt`)
Only with the owner's switch on (`lyricsListen`, off by default), a server set, untimed lines on the music screen (or a
timing by listening under 90 %), and the song playing with its position known: a Bluetooth song whose start was seen or
whose line was tapped (the server must know where in the song each piece was heard), or one of the unit's own. It is
the music screen's: it stops in `onStop` and on every new song, and what was sent goes on being worked on and is kept.
YouTube's songs are not listened to.
The microphone rules are AmriService's: never while the assistant listens or answers (`Amri.state`), while Settings
records the wake word (`Amri.enrolling`), in a call (`Amri.inCall` for the unit's Bluetooth calls, the audio mode for
the rest) or while reversing. The microphone is never opened twice. While the assistant is switched on
(`Amri.shouldRun`) its own recording is lent through `MicTap.listener` (the buffers arrive on the microphone's thread;
`feed` copies and returns); when the assistant pauses its recording, nothing arrives and nothing is recorded. Only when
the assistant is off does `LyricsSync.Recorder` open its own `AudioRecord` (VOICE_RECOGNITION, 16 kHz, the owner's chosen
microphone), and it checks at every read (ten times a second) that the assistant is idle, nobody enrols, no call, no
reverse gear, `MicTap.active` false and the assistant's switch unchanged since it started — the switch turned on starts
AmriService's recording a moment later, so this one lets go first. A recording that ends without a sample is not
restarted for 10 s.
The song's clock is read on the main thread every 500 ms (`Clock`: the position and the elapsedRealtime it was read at)
and extrapolated on the microphone's thread: a buffer's first sample is where the song was at the read time minus the
buffer's length. Pieces are 20 s, each starting 2 s before the previous one ended (a word cut in two at an edge is heard
whole in the neighbour). When the expected position and the clock differ by more than 800 ms (a seek, a tap that set a
Bluetooth song's clock, a pause) the piece so far is sent if it has 6 s and a new one begins. One upload at a time, at
most two waiting (the oldest is dropped when the network is slower than the song). The server answers each piece with
how much is timed; from 70 % on the screen switches to the timing and `Lyrics.keepAligned` keeps it on the unit (the
lines already heard are timed by what was heard; the line being sung is the server's guess at the song's pace until
the next piece is worked on, about half a minute later). Listening stops when the server says the song is done: 97 %
timed, or 90 % with no line still to come only guessed. Stopping at 90 % alone left the last lines guessed 7–14 s off
in the emulator run below; listening to the song's end times them. After the last piece the server is asked every
5 s until it has worked on everything (6 min at most). Every request to the server goes on a connection of its own
(`Connection: close`, and a failed question asked once more): a kept connection went stale when the server restarted
and the next lookup failed with "unexpected end of stream". A song from the unit's own storage is not listened to: its file (up to 40 MB) is sent once while it plays, and
the server's progress polled. The screen says what happens in a quiet note where the sync hint would be (the two never
show together: listening needs a known position): the microphone icon with "Listening to time the lyrics · 40 %
timed", or "Your server is timing the lyrics" while only the server works.
Debug builds: `am broadcast -a com.abdllh.aura.debug.LYRICS_FEED --es wav /sdcard/Download/song.wav` plays a 16 kHz mono
WAV of the whole song into the same path in real time, from where the song is when listening starts, and follows the
clock when it jumps (the emulator's microphone hears nothing); `--es wav ""` goes back to the microphone. The receiver
is registered at the first `begin`, so the broadcast is sent after the music screen has shown untimed lyrics once.

#### Timing by listening, on the server (`align.py`)
faster-whisper `large-v3-turbo` (int8, CPU, 6 threads, beam 5) writes down the sung words with their times (word
timestamps); the language is the lyrics' own (Arabic or English by their letters, else the model decides). Chosen by
measurements on the owner's PC (i5-12400F, with other work running): turbo 0.33–0.40 × real time, medium about the
same, small three times faster but it wrote a whole Arabic piece as one stretched word. Each piece waits in one queue
for one worker thread; requests answer at once with the state so far (Cloudflare gives up after 100 s).
The recogniser sometimes answers a piece of singing with a phrase it learnt from video subtitles ("اشتركوا في القناة")
and nothing else: such a piece, or one with far fewer words than the speech it found, is asked once more with the
lyrics' own words as hot words (the whole lyrics as a prompt made it write lines it had not heard, at made-up times:
tried and dropped). The stock phrases and words crammed into no time are removed in any case.
Words, not sound, are kept per song (`songs/<id>.json`, the id from the normalised title and artist). A new piece is
believed in its middle; its first and last second only where no other piece has words (`merge`). Then the alignment:
a dynamic programme over the heard words against the lyric words (similarity by rapidfuzz on the normalised words and
on the words without the و ف ب ل ك ال prefixes, short words only when equal), leaving lyric words out freely before the
first and after the last heard word and cheaply across pauses of more than 3 s (instrumental parts, or parts not
listened to), with a mild preference for lines near where the song's length says they should be when the length is
known (a Bluetooth song's length is sent only when the phone told it: a catalogue's may be another recording's). A line
is timed by its words heard (two of them, or one long word near its start): the middle of their times, each moved back
by the song's pace. Not the first word's own start even when heard: the recogniser stretches a line's first word back
over the pause before it (tried: the mean error grew from 0.15–0.5 s to 0.3–0.85 s). Lines in time order are kept by the
heaviest increasing run. A line sung twice in a row is often written down once; the one heard is given to the first of
the two (it went to the second, and the first was guessed seconds early). Lines between two timed ones are spread by
their words (a stanza break takes the slack), the rest guessed at the pace from one timed line to the next, pauses
included (a sung word's own length alone made the guesses run twice too fast: 7.4 s off on average, now 2.5 s), empty
lines put after the line before them. `covered` counts the sung lines timed by their words or between two such lines.

Measured with spoken test songs (`tests/test_align.py`: edge-tts voices — ar-KW-FahedNeural for the Arabic songs —
over made-up chords, noise and rumble with a cabin echo, sent in 20 s pieces with their positions as the car does, and
as whole files), 18 lines each, mean / max error per line:
* voice 6 dB over the backing: "Hello" (Adele) 0.15 / 0.33 s, "الأماكن" (Mohammed Abdu, Gulf) 0.21 / 0.65 s, "بشرة
  خير" (Hussain Al Jassmi) 0.49 / 1.05 s, all 100 % covered; the whole files the same.
* voice and backing equally loud (0 dB): 0.16 / 0.33 s, 0.30 / 0.85 s, 0.49 / 1.17 s, all 100 % covered.
* the app itself on the emulator (its own pieces, song clock and uploads; the debug feed in place of the microphone):
  "الأماكن" as a simulated Bluetooth song, LRCLIB's 82 plain lines (the 13-minute live recording's), spoken as a 418 s
  test song. 22 pieces; the screen switched to the timing at 72 % (about five minutes in); at 93 %: the 73 lines timed
  by their words 0.28 s mean, 0.83 s at most, the 3 between them 0.34 s. The switch in place (plain lines on the
  screen, the timing arriving with the first piece worked on) was checked in a second run, frame by frame: the lines
  stay where they are, step back around the lit one, and the lit one glides to the middle.
Real singing through a car's microphone is harder than speech: not measured (see below).

#### Word times (1.15, `tools/lyrics-server/words.py`)
(Since 1.16 nothing is estimated and the heard words are placed by forced alignment: see "Real word times only (1.16)".)
Asked with `words=1` (GET /api/lyrics and GET /api/sync), the server adds `"enhanced"`: the timed lyrics word by word, an
LRC with `<mm:ss.xx>` before each of a line's whitespace-separated words and one after the last (where the line's
singing ends), and `"words"`: `heard` or `estimated`. A timing made by listening knows when each clearly matched word
was sung (`align(…, words_out)`: pairs at similarity 0.8+ of two letters or more; kept per song as `word_at` only for
lines timed by their own words and inside their own time); the others are read off a line through the known points
(the line's start, each heard word, the line's end) over the words' cumulative weight. Lyrics a source timed line by
line are estimated: a word weighs (its letters and digits)^0.8 + 0.6, a line takes 0.16 s per unit (at least 0.5 s),
never past 0.15 s before the next line (a line sung quickly before a pause does not stretch over it); the last line at
most 6 s. The app estimates with the same rule when it has no word times of the server's (LRCLIB's timed lyrics
fetched by the unit itself, an older server).

#### The screen (`LyricsView.timeLines`, `MusicPanel`)
Timed lines that come for the very lines on the screen do not rebuild anything: the lines stay where they are, take
taps from then on (a tap looks the line's time up when it happens, `tapLine`, since a timing by listening can be refined
while it shows), the room above them grows to the timed lyrics' 38 % and the scroll moves by the same amount in the same
layout pass (`shiftOnLayout`), and the line being sung lights and glides to the middle as with any timed lyrics. They
keep the plain lines' size (measuring them again at the timed size would move every line); the next play shows the timed
size. Other lines (another source's) are shown anew. `MusicPanel.lyricsSong` remembers the song as its lyrics were
asked for, so a late answer is matched to the song on the screen.
Fixed on the way: the phone skipping A → B → A before B's lyrics came left A's lyrics stepped back (dimmed, not
following, not tappable) until the next song, since A's lines counted as already shown; `lyricsShown` is now cleared
when what shows steps back.

#### Settings (`settings/LyricsCard.kt`)
Display & sound → Music → Lyrics server: the address (a name without a scheme is taken as https; an IP or a port as
http, for tests), the key (shown as its first three characters), Test (`/health`, then `/api/ping` with the key: "The
server answers (1.0 · large-v3-turbo)", wrong key, not this server, no answer), and the switch "Sync lyrics by
listening" (needs the server and the microphone permission, asked as the voice page asks it). The inputs are a card of
their own with a URL / password keyboard and no suggestions, and the keyboard's Done (or Enter) saves: on the unit's
short screen the keyboard covers the card's buttons. Release builds refuse plain http to other devices
(`network_security.xml`): an http address other than the unit itself is answered with "use your domain's https
address", before any request.

#### Not verified
* Real singing heard through the K2501's microphone over road noise: the tests used speech. The recogniser is much less
  sure of sung Arabic; the alignment needs two clear words of a line, so a weak piece leaves lines guessed rather than
  wrong, but the coverage reached on real songs is unknown.
* The Bluetooth clock's own offset (the phone's audio reaches the speakers a little after the title broadcast): a timing
  made by listening is in the car's clock, so it is right for this car; the same LRC from a file is in the file's time.
* The owner's domain and tunnel: set up by the owner on 2026-10-18 (https://lyric.abod50i.online, a Cloudflare
  Tunnel to 127.0.0.1:8790); /health, the key check and a refusal without the key answered through it from the PC.
* The unit's microphone shared with the assistant (`MicTap`) on the unit; on the emulator only the own recording and
  the debug feed ran.

### Word by word lyrics and the saved timing (1.15: `Words`, `LyricsView`, `Lyrics`, `MusicPanel`)
The owner asked for three things: the word being sung lit on the lyrics' lit line ("an effect on the word the song has
reached", with a switch to turn it off), his own timing kept when he sets it by tapping the line being sung ("so that
when I play the same song later the same timing comes"), and songs whose lyrics come without timing timed by his
server. The server's half (word times by listening alignment, its own estimate when it heard none) is in
`tools/lyrics-server`; these notes are the app's half.

#### Where a word's time comes from (`Words.kt`, `Lyrics.parse`, `LyricsServer`)
(Since 1.16 the app estimates nothing and the effect is drawn differently: see "Real word times only (1.16)".)
The app adds `words=1` to `GET /api/lyrics` and `GET /api/sync`. A server that knows it answers with two more fields:
`enhanced`, the timed lyrics as enhanced LRC (`[mm:ss.xx]` per line, `<mm:ss.xx>` before each of the line's
whitespace-separated words, a last tag where the last word's singing ends, which may be missing), and `words`,
`"heard"` (from the listening alignment) or `"estimated"`. Both are kept in the song's record on the unit
(`cacheDir/lyrics/<key>.json`, beside `syncedLyrics`), from `/api/lyrics` (`Lyrics.serverJson`) and from a timing by
listening (`LyricsSync.state` → `Lyrics.keepAligned`). When the record is read, `Words.attach` gives each timed line
the word times of the enhanced line with the same words starting within a second of it; the line's own time is
believed and the words move with it. A count that does not match the line's words is ignored. `Lyrics.parseLrc` also
reads word tags inside LRCLIB's own text (now and then an entry has them): they leave the text and become the words'
times, so they never show on the screen as `<00:12.34>`.

Everything else (LRCLIB's timed lyrics, which the unit fetches itself, and servers older than this) is estimated on
the unit with the very rule the server uses for its own estimates, so a song looks the same whoever worked it out: a
word weighs (its letters and digits, at least 1)^0.8 + 0.6; the line is sung for clamp(0.16 × Σweight, 0.5 s, gap to
the next line − 0.15 s), at least 0.3 s (the last line counts a 6 s gap); each word starts at its share of that time;
the last one ends with it. A heard line without its end tag gets the last word's own share of the same rule. The
estimate is made for all lines on the worker thread that already measures their text (`LyricsView.show`), and on the
main thread for a timing that replaces the one shown in place (`timeLines`: a few lines' arithmetic).

#### The effect (`LyricsView.Line.onDraw`)
On the lit line only, with the switch on: the words already sung are lit (the text colour at full strength), the
word being sung fills with that light in its own reading direction over its time, and the words still to come stay at
60 % (`UPCOMING`), a step under lit and well above the other lines' 30–34 %. A word's fill runs from its start to the
next word's start (at most 3 s, `MAX_WORD_MS`: a long rest before the next word is not sung), 60 ms early
(`WORD_LEAD_MS`, as the line is lit 150 ms early). Its edge is soft: 0.6 em wide, drawn in four steps between lit and
upcoming (`EDGE_EM`, `EDGE_STEPS`), travelling from just before the word to just after it, so a word starts wholly
unlit and ends wholly lit. Arabic words fill from the right, Latin words and numbers from the left, each by its own
direction inside a mixed line ("حبيبي come back يا قلبي": the Arabic word from the right, then "come" and "back" from
the left). A line wrapped over two rows lights row by row. Lyrics without timing, a line without words ("♪") and the
lines not being sung are drawn exactly as before. No lift or glow was added: a glow is a blurred shadow layer on every
frame, and the effect read clearly without one.

How it is drawn: the line's text is drawn a few times, each time clipped to part of it, with the paint's alpha set
for that part: once for everything not lit (`clipOutRect` around the lit parts), once per lit span, once for the lit
part of the word being sung and four times for its edge steps. Sung words on one row are one span unless a word still
to come lies between them (a mixed line), then one box per word. Nothing is laid out again and nothing is allocated
per frame; the boxes are worked out once per layout. The next frame is asked for (`postInvalidateOnAnimation`, this
line's view only) only while a word fills and the song plays; between words a delayed invalidate at the next word's
start; paused, nothing. Line-to-line motion (alpha and scale through a layer, the follow spring) is unchanged.

The words' boxes are not taken from the line's own layout. Its text is a `PrecomputedText` (measured off the main
thread since 1.12), and on Android 10 that layout's `getPrimaryHorizontal` / `getSelectionPath` gave the row's end for
every offset: every word's box was the whole row and the first try lit whole rows at once. The boxes come from a copy
of the layout made from the plain string with the same paint, width, alignment, direction, spacing and break settings
(`measureWords`, once when the line is first drawn lit); it must break the rows exactly where the shown one does, or
the line is drawn whole (`boxesBad`). Lyric lines no longer hyphenate (`style`): a word split over two rows cannot be
lit as one word.

The clock: `LyricsView.at(ms, playing)` gets the song's position from the music screen's tick (the same position that
lights the line; for Bluetooth the app's own clock in `BtMusic`, plus the saved correction below) and carries it on
with `uptimeMillis` between ticks. A report a few ms behind the carried clock does not move the fill back (that made
the edge shimmer); a jump of more than 250 ms (a seek, a tap) or a pause/play redraws the line at once.

Cost, measured on the emulator with a word filling: 0.3 ms of the UI thread per frame on average (1.2 ms at most),
4 ms to issue the GPU commands. The emulator itself composes at about 6–8 frames a second whatever is drawn (its
composer service used 64 % of a core; scrolling the lyrics with nothing filling ran at 150 ms a frame), so how smooth
the fill looks has to be judged on the unit.

#### The switch (Settings → Display & sound → Music)
"Word-by-word lyrics" / «الكلمة اللي تنغنى تضيء» (pref `lyricsWords`, `Words.on`), on by default. Off, the lit line is
drawn exactly as before 1.15 (`super.onDraw`). The music screen reads it in `onStart`, so it applies on coming back
from Settings; the word times are worked out either way, so turning it on needs no new lyrics.

#### The saved timing (`MusicPanel.onLineTap`, `onLineHold`, `Lyrics.keepOffset`)
A tapped line on a Bluetooth song used to set the song's clock (the phone never says where a song is). Now:
* The clock unknown (the first song after connecting, before any tap): the tap sets the clock, as before. This is not
  a correction. With a correction already kept for the song, the clock is set so that the lyrics land on the tapped
  line all the same (clock = line + 450 ms − correction).
* The clock known (the song's start was seen, or a tap already set it): the tap corrects the lyrics' timing instead.
  The correction is the lyrics' time minus the clock (the tapped line's time + 450 ms, minus where the clock is), it
  applies at once to the rest of the play, it is written into the song's record (`offsetMs`), and "Timing saved for
  this song. Hold a line to undo it." shows. The clock and the seek bar stay as they were: the correction belongs to
  the lyrics, and the timing by listening keeps hearing the song in the clock's time.
* A tap more than 15 s away from the line lit (`MAX_CORRECTION_MS`) is the clock being wrong, not the lyrics: the
  owner moved the song on the phone, or it restarted. It sets the clock, keeping the correction.
* Every play of the song reads the correction with its lyrics (`Result.offsetMs`) and lights the lines at clock +
  correction. It is a Bluetooth song's only: the unit's own songs and YouTube's report their real position, and a
  tapped line moves them there.
* Holding a line down (Bluetooth, a correction kept) forgets it: "Saved timing removed for this song". Held without
  one, nothing happens (it does not fall through to a tap that would save one).
* A tap after a tap that set an unknown clock counts as a correction, as the owner asked. It is harmless: that clock
  was set from the lyrics with the song's kept correction, so the new correction is the old one plus the difference
  between the two taps' timing, not the clock's arbitrary origin.

Lyrics replaced by other ones drop the correction, because the record is written anew without it: another source's
lyrics, the server's timed lyrics for untimed ones, and a timing by listening that the server refines (more lines
heard). That last one was a choice: a timing by listening is made in this car's own clock, so a correction made on an
earlier, partly guessed version was about lines the refinement re-times by what was heard; keeping it would move the
refined lines by the same amount again. `keepOffset` writes only when the record still holds exactly the lines the
correction was made on.

#### Timing by listening on by default (`LyricsServer.listen`, `LyricsCard`)
The owner asked that songs whose lyrics come untimed be timed by his server, so the pref `lyricsListen` now defaults
to on; the switch still turns it off, and its subtitle says so: only while a song with untimed lyrics plays on the
music screen does the microphone listen, and the sound goes only to his server. Every microphone rule of 1.13 stands
(`LyricsSync.mayListen`: never in a call, while the assistant listens or answers, while the wake word is recorded,
while reversing; only with a server and key set). The microphone permission is never asked for by this default: the
switch shows on only when the pref is on and the permission is given, and turning it on asks for it as before.

#### Checked on the emulator (emulator-5570, debug build)
* "Hello" (Adele) as a simulated Bluetooth song, LRCLIB's timing, estimated words: the line tapped, the fill frame by
  frame on a wrapped line (row 1 lit word by word, then row 2), paused states compared with the estimate, in the panel
  and on the whole screen.
* "3 Daqat" (Abu), LRCLIB's Arabic timing: right-to-left fill; "لما شوفتها قلبي دق 3 دقات" with the "3" in its own
  left-to-right box.
* A made-up record with `enhanced` and `words: "heard"` (uneven times: the first word 4 s long) and a mixed line: the
  fill followed the given times, not the estimate, and each word of the mixed line its own direction; the tags never
  showed.
* The saved timing: a first tap set the unknown clock (no message), a second tap saved `offsetMs` 3904 in the song's
  record with the message; another song and back (a seen start): the first line lit at clock ≈ 29.7 s for lyrics
  time 33.6 s, i.e. with the correction; holding a line removed it from the record with its message.
* The switch off: the lit line lit whole mid-line, as before; on again.

#### Not verified
* Smoothness on the K2501 itself (Mali-G31, 32-bit): the emulator draws 6–8 frames a second whatever the content.
* The owner's real server's `enhanced` field (the server half was being written at the same time): only a made-up
  record took that path. `/api/sync`'s state with `enhanced` while listening is untested end to end.
* Arabic marks that stand far outside a word's advance (a shadda over a final letter) lit with the neighbouring word's
  strength at the very edge: not seen in the songs tried.

### Real word times only (1.16: `Words`, `LyricsView`, `Lyrics`, `LyricsSync`, `tools/lyrics-server`)
The owner, after 1.15: the word light was "made up" (a singer sang half a line, paused, went on, and the light ran on
as if there had been no pause), and the word being sung was not clear enough. The cause: 1.15 spread a line's words over
its time by their length whenever no real times were known, and that was every song in the car — the two listening
sessions the unit sent after 1.15 heard 0 words ("بتقولي أنسى اللي فات", "12 to 12"), and the owner's latest songs
(Sinatra, Bobby Darin) were timed line by line only. Since 1.16 **only real word times light words**; a line without
them is lit whole, as before 1.15. There is no estimate any more, in the app or on the server (`words.estimate` stays
only for 1.15 servers' callers; nothing calls it).

#### Where real word times come from
* **NetEase's word-timed lyrics** ("yrc", `sources.NetEase`, `sources.word_lines`): timed word by word by hand for the
  songs its listeners sing along to — Western standards and pop among them, Arabic songs not so far. Searched through
  its `cloudsearch` endpoint (the older search answered some queries with code −462 and nothing). The pieces a word is
  sung in are joined into the line's whitespace tokens; credit lines are left out.
* They are put on the lines the app shows (`Search._words` → `words.map_words`): those are usually YouTube Music's text
  and timing of the recording the phone plays. The two timings may differ by a constant (another copy of the
  recording): the offset most line pairs of the same words agree on (similarity 0.85+, within 0.35 s, at least 3 lines
  and 30 % of them) is taken, else the source is another recording and gives nothing. Each shown line then takes its
  twin's words (starting within 1.2 s after the offset, the same words in order, spelling aside); a line with fewer than
  75 % of its words found gets none; a word found in only one of the two texts gets a time between its found neighbours.
  The answer keeps `wt` (one entry per line), `words_from`, `words_covered`, `words_offset`. Answers kept before 1.16
  are looked up again once (`CACHE_V` 2).
  Measured: "Call Me Irresponsible" (Bobby Darin) 95 % of its lines, "Fly Me To The Moon" (Sinatra) 74 %; "12 to 12",
  "Believer" (its album recording; NetEase's word-timed copy is another, 16 s longer, and was refused), "Shape of You",
  "Tamally Maak": none. Two hand-timed sources compared on the same lines differed by 0.27 s a word on average (0.58 s
  at the 90th percentile): what the server hears is believed first.
* **The server hearing the song** (listening, below): the car's microphone, or the unit's own song file.

`/api/lyrics?words=1` (`server.add_words`): the words heard of this very song before (its record, the same words
starting within a second; heard times are the song's own and are not moved with the line), then a source's for the
other lines; when the record has (nearly) all its lines' words and the answer has not, the record's own lines are
answered (source "aligned": they are what the car showed when it listened). `enhanced` carries word tags only on lines
with real word times; `words` says "source", "heard" or "source+heard". `words.tagged` writes it: a pause (a word's end
known, the next word more than 0.25 s later) is an empty tag where the singing stopped,
`[00:23.44]<00:23.44>In <00:23.80>other <00:24.10>words, <00:24.90> <00:27.20>hold …` (1.15 apps skip it).

#### The server's listening (`listen.py`, `vocals.py`, `wordtimes.py`, `gpu.py`)
* On the PC's RTX 3090 (`gpu.py`): whisper with int8 weights, htdemucs (the voice alone), torchaudio's MMS_FA forced
  aligner in float16. About 2.4 GB of the card's memory and 5 GB of the PC's with all three loaded (other programs use
  most of the card); loaded at the first piece (about 26 s), kept. A job the card has no memory for is done on the
  processor (never a crash); without a usable card everything runs there (about 16 s a 12 s piece; with pieces waiting
  the voice separation is skipped to keep up). Model caches and temporary files live beside the repository, not on C:.
* A piece: its levels for the diagnostics (the sound is never kept on disk), the voice separated, whisper on the voice
  (its words time an untimed song's lines, as before), the voice kept in memory (the last 45 s of the session, dropped
  three minutes after its last piece), then every line whose window has been heard whole gets its words placed: its
  known text, written in the aligner's letters (uroman for Arabic, as written), forced onto the voice inside the line's
  window, with a wildcard before and after it. A word ends where its voice dies away (a held note lasts, a pause shows);
  a line placed unsurely gets nothing (another text, no singing, the wrong place); a line placed again replaces its words
  only when surer.
* Timed sessions (`times` in `/api/sync/start`, at least 3): the given line times are kept and only words are looked for.
  A line's window runs from its time −1.2 s to the next line's +1.2 s; once three lines are placed surely the session's
  clock offset (the median of first word − given time) moves the windows and narrows the slack to 0.8 s, and the words
  are kept on the song's clock (an offset of 0.35 s or more taken off wholly, under 0.15 s not at all — it cannot be told
  from the source's own error — evenly between), so the next play, with another clock error, still shows them in step.
* Untimed sessions: as before, and a line whose words were placed starts with its first word (a line with a pause in it
  was timed seconds late by the middle of its heard words).
* Repeats: a line with the same words as one already placed, and a span within about 10 % of it, gets that timing at
  its own start (`"from": "repeat"`) until it is heard itself: a chorus has its words on the first play.
* Diagnostics: each piece's `rms`, `peak`, `vocal` (dBFS), `asr` (words written down), `aligned` (lines given words),
  `offset`, `ms`, `src`, in the song's record, in `diag` of every answer, and one log line per piece.
* Measured (no real music exists on the PC — the song files in `dl/songs` are silent placeholders — so on test songs made
  by `tests/songmaker.py`: real lyrics of an English and an Arabic song spoken by edge-tts and made more like singing,
  held notes, pauses inside lines, reverb, a band; every word's true time known): word starts median 0.02–0.04 s, 90th
  percentile 0.06–0.09 s, 91–98 % within 0.1 s; pauses inside lines found 8–9 of 9; through simulated car microphone
  (motorway noise, highs cut, −30 dBFS) at 10 dB and 0 dB signal to noise 23–24 of 24 lines; the wrong lyrics placed no
  line; whisper's own word times on the same sound were 0.12 s off (median). A 12 s piece takes 0.6–1.0 s on the card.
  `tests/test_units.py` and `tests/test_wordsrc.py` need no server, sound or card.

#### The app
* `Words`: no estimate. A line's pauses come from the empty tags (`Lyrics.Line.ends`). `attach` gives a line the
  server's words for the same text starting within 1.5 s and does not move them with the line (both are the same
  clock's); a line whose first word is sung before its own time is lit from that word (at most 2 s earlier, never before
  the line ahead of it).
* `Lyrics`: `Result.wordsCovered`, `wantsWords` (under `WORDS_DONE`, 0.9). Timed lyrics without word times ask the
  owner's server for them (`asks`, with the word effect on); an answer with more words replaces the lyrics shown, and
  keeps the owner's saved correction when its lines are the same.
* The effect (`LyricsView.Line.onDraw`): the words sung at full strength, those to come at 50 % (`UPCOMING`; 1.15's 60 %
  read as nearly the same in the car); the word being sung stands on a pill of the cover's colour (`accent`: 26 % on a
  light screen, 34 % on a dark one) and fills with that colour in its reading direction. The pill glides to the next
  word in 150 ms when the two follow each other on a row, else comes in over 120 ms; when a pause begins (the word's end)
  it and the word's colour let go over 180 ms, and the light waits for the next word. Nothing is drawn at another size
  (a scaled word costs this GPU a new set of letters every frame). Lyrics that come while a line is being sung light it
  word by word at once.
* Listening for words (`LyricsSync`, timed mode): timed lyrics that want words, with the word effect on, are listened to
  through the car's microphone under the 1.13 rules (only with the switch on, a server set, those lyrics on the music
  screen and the song's position known; never in a call, while the assistant listens or answers, while the wake word is
  recorded, or while reversing). The line times go to the server on the song's clock (shown time minus the owner's
  correction); its words come back shifted by the correction and are attached to the shown lines, which keep their
  times, source and correction. Pieces are 12 s with 2 s overlap (`&src=mic`); a piece of nothing but silence is not
  sent; the server is told of the song first and its first answer is waited for up to 3 s, so a song it already knows
  gets its words at once and the microphone is never opened. Listening stops when the server says done (0.9 of the
  lines) or the lyrics no longer want words, and starts again with the new times after a correction.
* Diagnostics on the unit: each piece's level and the server's `diag` in the log (`AuraLyricsSync`), and in Settings →
  Display & sound → Music under the listening switch the last listening's level and words heard.
* The unit's Bluetooth music cannot be heard digitally: the daemon that plays it (`gocsdk_8800`) runs as root, and
  Android 10 does not let an app's playback capture hear a uid without a package (measured on the emulator: silence).
  Going around that protection was not done; the microphone is the way in.

#### Checked on the emulator
* "Call Me Irresponsible" as a simulated Bluetooth song: the server's NetEase words on YouTube Music's lines; the pill
  on the word being sung in the panel and on the whole screen, light and dark; a line without word times lit whole.
* Listening, end to end, with the merged server on the card and the English test song through the debug feed (16 kHz
  WAV in place of the microphone), its lines shown as timed lyrics without word times: words for 22 of 24 lines while it
  played (25 % after 44 s), the clock offset measured −0.3 to −0.4 s and taken off, listening stopped at 91 %. Played
  again, the words came with the server's first answer and the microphone was not opened; through a 2.2 s pause after
  "things" the pill let go and the rest of the line waited, then went on with "been" (a burst of screenshots).

#### Not verified
* Real singing: every measurement above is on test songs. Whether the car's microphone hears the music at all: the two
  1.15 sessions heard nothing; the levels now logged per piece (in the server's log and record, and in Settings) will
  tell after the next songs in the car.
* Smoothness of the pill and the fill on the unit's GPU (the emulator draws 6–8 frames a second).

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
file). The frames of the intro go into the GPU first and the rest one by one after it ("Memory and start-up cuts"). The
feel is set in degrees, so another frame step needs no retuning. Settings → About shows how the car is drawn, how it came
on screen at this start and how fast it turned last time. The model itself is not in the repo.

### The voice assistant "عمري" (`voice/`)
* Wake word, offline and without a model to download: a personal template matcher. The owner says "عمري" 6 times
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

### The voice assistant in 1.13 (`WakeWord`, `WakeNegatives`, `Commands`, `Amri`)
Notes for DEV.md, section "The voice assistant "عمري" (`voice/`)". The owner's request: hear the name better, and above
all stop waking on sounds nobody meant. Everything below was measured, before and after, on a test corpus built on the
PC; the app's own Kotlin code was run over it (`voice-test/kt/Harness2.kt`), so the numbers are the code's, not a copy's.

#### The test corpus (`D:\k2501-work\voice-test`, not in the repo)
* Speech: Windows' Naayf and 32 Arabic and 8 English neural voices (edge-tts 7.3.1; 7.2.8 got 403 from the service).
  Nine of them are "owners": Naayf, ar-SA-Hamed, ar-KW-Fahed, ar-SA-Zariyah (tuning) and ar-AE-Hamdan, ar-OM-Abdullah,
  ar-QA-Moaz, ar-BH-Ali, ar-KW-Noura (held out). Each enrolled six times like the owner would (Settings' checks
  rebuilt: one word, a voice, the odd one out asked again), in an idling car, from "عمري" at rate -8..+8% and pitch
  -10..+10 Hz.
* Positives: each owner's other 154 ways of saying it (rate -25..+35%, pitch -20..+20 Hz, plain, exclaimed, asked,
  ±4 dB) in ten conditions: quiet, idle 20 dB, city 15 dB, motorway 10 / 5 / 0 dB(A), music 15 / 10 / 5 dB (with road
  noise under it), road and music together. A cabin's short reverberation and the head unit's 90 Hz - 7 kHz microphone.
* Negatives: per owner, 10 minutes per background (quiet, idle, city, motorway, music), an event every 0.6-3 s: the
  owner's own near words (عمر، عمرو، حمري، سمري، عمرين، أمري، قمري، عمرك، عمره، الشمري، العمري، بحري، صبري ... 45),
  his Gulf phrases and sentences, other voices' near words and speech, English, and car sounds (thumps, double bumps,
  ringing panels, rattles, bangs, doors, the indicator, horns, engine revs, wipers, chimes including AMRI's own, beeps,
  coughs). 7.5 hours in all, far denser than a real drive: false accepts per hour compare versions, they are not what
  the car will see. Apart: phrases with the word in them ("يا عمري") and other people saying "عمري".
* The background models are synthetic (road: brown and pink noise, tyre roar, wind, engine orders; music: two real
  songs, one Gulf, and synthetic songs with a sung line). Nothing was recorded on the unit.

#### What changed
* **Two listeners.** `Segmenter(band = true)` also follows the voice's band (500 Hz-3 kHz, `VoiceBand`): a word starts
  when either level rises 10 dB over its floor and lasts while every level that rose for it is still up. The plain
  listener runs beside it (music keeps the band up, so words there came out too long); `WakeDetector` judges both
  listeners' words (one cut of the same word is judged once). Most of the misses in road noise were words never found:
  at 5 dB(A) over the motorway the first difference rose 10 dB over its floor in 44-61% of the tries, the band in nearly
  all. Enrolment and the offline requests keep the plain listener, so the owner's recordings were cut as before.
* **A verifier on candidates** (`WakeModel.judge`). Stage 1 is the old score (six alignments, every word). Words under
  the limit then need: a voice (`VoiceCheck`, unchanged); the score again with the recordings seen through the word's
  own background (`spreadThrough`: in loud noise everything looks like the wake word, because the noise hides what tells
  words apart, and the recordings come closer together through it as well); the worst fifth of the alignment
  (`Dtw.path`, `worstPart`); and not "mid-speech" (under 6 dB over its background while that background stood over 4 dB
  above the floor: the background was the previous word). Limits per sensitivity in `Limits`.
* **Learned sounds** (`WakeNegatives`, `amri-wake-neg.bin`). A wake the owner did not mean (the recogniser heard
  nothing, the 8 s / 15 s ran out, he tapped the pill within 6 s, or said "خلاص") is kept if it matched weakly; later
  words much closer to a kept sound than to the recordings are refused. A wake followed by a request forgets the kept
  sounds close to it; 24 at most; Settings → Voice assistant shows the count and forgets them; recording the wake word
  again starts empty.
* Tried and not kept: the loudness contour (c0) in the alignment (no change), fewer cepstra (no change), the end's
  spectral tilt and per-third alignment costs (no separation), the pitch track (TTS near words move like the name),
  a background estimate from before and after the word, a noise margin (2.5x), a logistic mix of the cues (no better
  than plain limits on the held-out voices), a stricter limit under heavy masking (costs road recall), and a "weak"
  test through the background for the learned sounds (real calls in noise were then refused: true accepts 67% → 28%).

#### Numbers (Kotlin code over the corpus; all nine owners; normal sensitivity unless said)
| | 1.12 | 1.13 |
|---|---|---|
| true accepts: quiet / idle / city | 97.3 / 97.8 / 81.3% | 96.8 / 97.9 / 81.2% |
| motorway 10 / 5 / 0 dB(A) | 80.1 / 26.2 / 0.5% | 90.8 / 52.7 / 10.2% |
| music 15 / 10 / 5 dB, road + music | 84.1 / 72.1 / 63.8 / 51.9% | 72.4 / 62.3 / 58.7 / 53.4% |
| all conditions | 65.5% | 67.6% |
| false accepts / h (dense streams) | 128.4 | 44.9 |
| by background: quiet / idle / city / motorway / music | 54.7 / 38.7 / 114.0 / 217.3 / 217.3 | 8.7 / 5.3 / 51.3 / 83.3 / 76.0 |
| car sounds / music itself / speech pieces / English | 104 / 55 / 332 / 38 | 23 / 1 / 37 / 5 |
| own near words / others' near words | 315 / 119 | 222 / 49 |
| strict: true accepts, false / h | 61.0%, 106.5 | 61.5%, 30.1 |
| relaxed: true accepts, false / h | 65.9%, 170.1 | 70.8%, 86.7 |
| phrases with the word (road 10) | 42% | 17% |

Held-out owners alone tell the same story. The cost is music: with music 10-15 dB under the voice 10-12% fewer calls
are taken (the noise-aware score is what keeps the songs out). What still gets through is mostly the owner's own near
words said on their own ("عمرين", "عمرك", "عمر", "حمري", "أمري").

Learned sounds, simulated (each stream's first half teaches every false wake, the second half is heard with them):
false accepts 35.5 → 31.5 / h, true accepts 66.9 → 66.2%. The same false wakes heard again later over other sounds:
50% of them woke again without the learned sounds, 30% with them. Ten real "عمري" answered with silence (only the
weak ones, about four, get learned) cost 4 points of true accepts until confirmed wakes forgot them (back to 66.8%).

A pretrained speech embedding as a second stage (openWakeWord's melspectrogram and embedding models, ONNX, few-shot
against the enrolment words, measured on what the 1.13 verifier still lets through): keeping 98% of the true wakes it
removes 74% of the remaining car sounds and 83-86% of the remaining speech pieces, but only 29% of the near words, which
are most of what remains (39% of all). Not shipped: it needs the TFLite runtime plus 2.4 MB of models (over the ~3 MB
budget with the runtime), 42 M multiply-adds per 80 ms window (about 8 windows per candidate) on the weak SoC, and the
owner's recordings as sound (amri-wake.bin keeps spectra), so a new enrolment or recordings gathered from confirmed
wakes. A candidate for a later version if sounds still wake it.

#### Requests (`Commands.kt`)
New: what's playing (from what played when it was called, before the pause), how long to go (AMRI Maps' route; another
navigation app does not say), the date (with the Umm al-Qura date in Arabic), brightness (needs "modify system
settings"; asks for it), screen off, the nearest petrol station / pharmacy / restaurant / café / hospital / mosque /
ATM / supermarket / hotel / car wash / garage (AMRI Maps: searched near the car and the nearest said, the map's search
opened; another navigation app: its own search), "ودني <place>", an app by name (Arabic finds Latin labels:
"يوتيوب" → YouTube), a song by name (YouTube Music's search, first song to the YouTube Music app), thanks, who are you,
"الصوت عالي / واطي". Answers that take a moment show their first words, then the answer (12 s at most). 102
phrasings, old and new, parse as meant (`voice-test/kt/cmd/CmdTest.kt`). A word one letter off a key was tried and
dropped ("الماجد" read as "المسجد"); the recogniser's spellings are keys instead.

MapsActivity takes a search query ("q") in every build since 1.13 (it was debug-only, for tests): the assistant's
nearby searches open the map's search on the place asked for, with its results; the query is used once (removed from
the intent, so a recreate does not search again).

#### Checked on the emulator (workshop, 1.13.0 debug)
Enrolment from files (spread as on the PC), nine positives woken (quiet, road 5 dB, music 10 dB), 40 s of quiet and
music negatives without a wake, one false wake in 40 s of motorway negatives; every new request through the debug
"say" hook; Settings' new rows. Not checked: the live microphone (the workshop emulator runs without host audio), the
recogniser's real outcomes that teach the learned sounds, the unit's CPU with two listeners, anything on the unit.
Debug hooks: `--es detect a.wav` (both listeners, each word's verdict), `--es learn a.wav`, `--ez forget true`.

## Apps and Settings (2.0: `home/AppsPanel.kt`, `settings/`, `ui/SnapScroll.kt`, `ui/TurnAway.kt`)
Section 6.6 of the design. Both panels are kept once built, take a theme in place (`recolours`), and lie beside the column on a trip (`besideColumn`, `opaque` and `solid` by `NavSession.state`); they build their frame anew at `onTrip`, because what lies in the card has other widths. A trip's turn under 300 m takes the card away and brings it back after it (`TurnAway`: alpha, x, `INVISIBLE` so touches reach the map; the page stays).

**Apps.** `AppRepo.loadAsync` when it opens, and once 6 s after the process's start (`AuraApp`, as the old drawer's prewarm did), so the first open has its grid in its first frame; with no list yet the room and the title answer and the grid comes up as one when it comes. Icons only when opened. The table `KNOWN` at the file's foot gives the firmware's known apps their tile, glyph and word in the mockup's order, wheel and tool box being screens of the stock launcher by name; everything else follows by name on `apptile_ink` with its own icon, 72 px. Android's Settings left the grid (Car and system has it), and so did the stock launcher; AMRI's own Maps and Music stay in `AppRepo`'s list only for the voice's "open ...". A cell is one `TileView` and a label, 40 apps are 120 views and no recycling. Rows snap (`SnapScroll.pitch`), dots in the margin say the page when there is more than one. `AppDrawer`, `DockPins` and `DockTiles` are gone; the stored `dockPins` is read by no one.

**Settings.** `SettingsPanel` is the frame: room, side list (one plate on spring 520/0.86, each item's plate, glyph and word tinted by how much the plate covers), the one card, and a stack of sub-pages. A page (`Page`) is what lies in the card: the seven are `rows(..., fill = act.fillH)`, 414 in the room and 398 on a trip; a sub-page scrolls (`SnapScroll`, rows snapping; baked fades at its ends only where there is more). The card never fades or moves: only its content crossfades, 140 ms, x 12 in reading direction, on one layer (`unit-facts.md`: a big 9-patch by alpha is what janked). On a trip one page at a time, back goes to the list of the seven (`TripListPage`), back from there leaves. `Page.onShow`/`onHide` come in pairs from the panel (`goLive`, `goQuiet`). A kept page reads what can change from outside in `onShow`; `Page.keep = false` is built at each visit, for what is only read at build. A kept page stays in the card, `INVISIBLE`, between visits: added to a parent again a view forgets its direction until its next measure, which a kept page does not get (a switch came back with its thumb at the wrong end), and out of the tree a theme missed it. A sub-page's top end has its own fade from the card's colour under the head (`fadeUnderHead`): the baked one is for a card's own end, which is lighter, and showed as a band.
* **Where every old switch went.** The seven pages hold what the design shows. Everything else lies in `WorkshopPage` with its pref and `apply` as they were: nothing was migrated, renamed or forced on, so `voice-settings.md` 5.A's "always on" was not done; a stored false stays honoured and can be changed. Workshop's order: Home app and the way back (`WaybackCard`) first, then Amri's sensitivity, microphone, learned sounds and offline requests, music, calls and notices, screen, map additions and camera button, internet with the log, power, gear, the unit's notes. Only `accent` and `dockLabels` have no row: nothing reads them. The update page is as it was, its three options included.
* **`Prefs.mapView`** (`tilted`, `above`, `north`) is new: the design's `طريقة العرض` had nothing behind it. `AuraMap.steer` reads it: tilt 0 from above, bearing 0 with north up. **`Prefs.saherTone`** is new, read in `Tones.saher`. `Prefs.builtInMaps` reads true when no app is named: a stored `تلقائي` is AMRI. `صوت التعليمات` is not there: no spoken navigation. `Prefs.clock24` takes the design's fourth row on the display page.
* **The pack's page** (`PackPage`) is `PackManager`'s words and what it can do, changed in place at each report: state, bar, sizes, `advice`, download, stick, stop, go on, cancel, check, delete, the old map's space. With nothing on the unit it asks for the manifest once a visit: that is 5 KB, and an offer, never a start. `OfflineMaps.autoResume` no longer runs with a pack in use: nothing on screen could stop it any more.
* **Notices.** `assets/NOTICE.txt` is a copy of the repository's `NOTICE`: copy it again when that changes. `LicencesPage` adds the pack's `NOTICE-PACK.md` and the licences it names, through `PackPaths.current(ctx).notice(name)`.
* **A dialog never while moving** (section 5): `Context.parked { }` in `Rows.kt` says so in a Snack instead. The chrome's `Modal.dismissAll` still takes what was open when the car starts.
* `Fmt.bytes` is by thousands and in the language now (`713 ميجا`): a Latin unit came out `MB 713` in an Arabic line, and 677.6 where the design says 713.
* `AuraApp.getResources` answers in AMRI's language (`LocaleHelper.appResources`): at every configuration change, also one that only moves a window's bounds and never reaches `onConfigurationChanged`, Android sets the process's default locale from the application context's resources (`ActivityThread.updateLocaleListFromAppContext`). On an emulator in English with AMRI in Arabic that turned every label built afterwards left to right (`TEXT_DIRECTION_LOCALE`), the strip's `ص` into `AM`, the guidance into English and the map's names with it, a minute or a trip into a session. Whatever the unit's system language is, it cannot happen there now.

* **Dots under the line** (`AText.layout`): a TextView clips its text to its frame, and with no font padding the frame ends at the font's descent. The two dots of a final ي reach below it (Plex Arabic -447 against -415, its lowest marks -601), so `عمري` read `عمرى` on every label of the app. A plain `AText` with no background now lays its frame 0.19 em lower than its parent gave; the text's place comes from the measured height, and so does the parent's layout: nothing moves. Not a subclass: `LyricsView.Line` ends its word pill at its height. `SettingRow` does not clip what is in it, for the last line of a row's words.
* About's maps row says whose data draws, `MapStyle.attribution(null)`: the pack's credit is too long for a row's far end, so it lies under the title, left to right in both languages, whole, as the data's licences ask.

Hooks: `setprop debug.aura.appsmany 3` shows each app that many times, since an emulator has ten and a unit more than a page; `SettingsActivity --ei page 0..6` (100 the update page, 101 the pack's), `--es theme`, `--es lang`; a running shell also takes `am start -a android.intent.action.MAIN -c android.intent.category.HOME --es panel settings --ei page 3 com.abdllh.aura`. `setprop debug.aura.unit 1`, `debug.aura.ball 1` for the rows only a unit has. Under load the emulator's window can stop drawing for good (`dequeueBuffer failed, error = -110`, "One window"), and a shot then shows the last picture while the state goes on: a new intent to the shell brings it back.

## Settings, the call card and the overlays (1.12)
Every option and its firmware integration is as in 1.11; what changed is how the screens look and move. New strings of
this area go in `strings_settings.xml` (both languages).

**Settings is no longer recreated.** Until 1.11 `rebuild()` was `recreate()`: picking a navigation app, a microphone,
teaching an offline command or switching Beta flashed the bare window and threw the page back to its top.
* `SettingsPanel.rebuild()` (`SettingsActivity` until 2.0) builds the current page again in place, where it was scrolled to (posted, after the tap
  that asked for it). Use it when a preference changes what the page shows.
* `SettingsPanel.restyle()` (no arguments) is only for the theme, the accent and the language. It takes a picture of
  the screen (`PixelCopy`, 600 ms at most), stores the waiting choice, builds the whole screen again with the new
  palette (the language: the whole window again, the picture handed over in a companion field) and fades the picture out on top
  over `Motion.LONG`; page and scroll stay. It does nothing when nothing waits and the theme shows as it is. The accent
  needs it, since every drawable reads `Palette` when it is built.
* A choice is kept at the tap: the theme in Prefs, the accent and the language in `pendingAccent` / `pendingLang` (not
  Prefs: the accent there would change `Palette` before the picture). The capture's answer stores them before it checks
  that the screen is still there, `onHide` stores them when the screen goes for good (before the home panel looks), `onShow`
  restyles when they still wait, and a `restyle()` during a capture leaves them to it: a pick shown as chosen is never lost. A choice waits
  `Motion.MEDIUM` (`Later` in `Pages.kt`) so the indicator or tick is seen landing first; a newer one replaces it. Taps
  on what is already chosen do nothing (`ui.Segmented`, `Swatches`, a ticked navigation row, the microphone menu).
* `onShow` again (back from Maps, Android's settings, the Play Store): a theme or accent changed elsewhere builds the
  screen again; otherwise the other pages are dropped and the current one rebuilt.

**Pages are built once.** `built[]` keeps each page's `ScrollView` while the screen is up, so a page comes back at once,
scrolled where it was. `Page.onShow()` / `onHide()` attach and detach listeners (UpdateManager, the Wi-Fi log, the
offline map, MapExtras): a hidden page holds none, and `onHide()` must stay safe to call twice (`onDestroy` calls it
again). A kept page that shows a level or state from outside sets it again in `onShow()`: `DisplaySoundPage` sets its
brightness and volume sliders there (not through `onChange`: nothing is sent), since the volume changes from the wheel
meanwhile and a drag moves from the value shown (a stale 15 over a real 5 was a loud jump). `Page.keep = false` drops a
page when it is left: `VehiclePage` (the gear's source, what the CAN box sent) and `AboutPage` (the unit's notes, the
last crash) are built at every visit.

**Motion.** The sidebar's selection is one `accentSoft` view behind the items, moved by a `Spring` (520 / 0.86); the items
take the indicator's colours by how much of each it covers, and only items whose share changed are re-tinted. A page
switch: the old page leaves on `exit` (`XSHORT`, 8 dp towards the list side it was on), the new one and the title
`enter` 40 ms later from 16 / 8 dp on the other side (a page coming back while leaving turns round). The content
scrolls under 24 dp gradients in `Palette.bg` at its top and bottom, shown in proportion to what is beyond them
(`edges()`), in place of a ScrollView fading edge, which drew through an offscreen layer on every scroll frame. No
`BackdropView`: the panel's own room is the screen's colour already.

**Rows** (`Rows.kt`): a section's rows sit in one card, `group(vararg rows: View?)`, with `Palette.stroke` hairlines from
where the row's title starts; the card clips its corners so a pressed first or last row tints the corner, and a null
row is left out. The rhythm lives in the builders: `addSection(title)` (32 dp above, none at the top of a page),
`addRow(card)` (8 dp under a title, 12 under another card), `addHint(text)` (8 dp under its card). `settingRow` is 72 dp
minimum, 26 dp icon, `LABEL` title, `FOOTNOTE` subtitle; a tappable row shows the press tint over its whole width and
does not sink (a row shrinking inside its card tore the hairlines). A trailing value is held to 300 dp, shortened in
its middle, and reads first-strong-LTR (`valueText`): "1.12.0 (1120099)" read "(1120099) 1.12.0" in Arabic.
`switchRow`: the whole row is the target and passes its pressed state to the switch. `block(icon, title, subtitle,
body, live)`: a row with a control under its head. `sliderRow(title, slider, value)`: the slider carries the icon,
the value has tabular figures, and the volume is the car's step number. About: a value longer than 28 characters
goes under the title as a three-line subtitle.

**Components.** `settings.Segmented` is gone (theme, language and sensitivity use `ui.Segmented`). `Swatches` are 56 dp
targets with the ring drawn round the colour and a tick in `onColor`. The Internet, offline-map and map-additions
buttons are `ABtn`s; `InputDialog` and the enrolment card are `Modal`s. `ProgressBar` (`Widgets.kt`) glides on a spring
that keeps its speed when the next report comes first; the indeterminate sweep and the `Spinner` run only while
visible (`onVisibilityAggregated`: on Android 10 an animator does not stop for a stopped activity). The update page
changes only its bar and line at each report (until 1.11 the card was rebuilt every 400 ms: the bar grew from empty and
Cancel was a new button under the finger); release notes show 8 lines with "Show all" (`upd_notes_more`). The
microphone row opens a `Menu` of the inputs. Map additions counts only what was published; this car's cameras have
their own row.

**The call card** (`phone/CallBanner.kt`). Hang up never slides under the finger that has just pressed Answer: once
answered (here or on the phone) Answer fades (`XSHORT`) and its place stays empty; a second or more into the call it
closes, Hang up moving into it by `LayoutMove` (`MEDIUM`); a tap on Hang up within `GUARD_MS` (1 s) of the Answer tap or
of that move is ignored; if the phone does not take the answer, Answer comes back. A dialled call has no Answer place.
The window is the card plus 12 dp at the sides, 18 dp below (the shadow) and 16 dp above at the screen's edge (1.11's
margins left a dead band over the map for the whole call). `CallBanner.bottom()` (px from the screen's top, 0 with no
card) is for the volume display. The ring round Answer has no overlapping rendering.

**The overlays' motion** (call card, volume display, assistant's pill): `enter` on `EMPHASIZED` (`MEDIUM`, from 14–16 dp
and 0.96), `exit` on `EXIT` (`SHORT`, 10 dp). Each keeps a `leaving` flag: an update while it is up lets the entrance
play on (every report and every repeat of a held wheel button cut it to its end until 1.12), and an update while it is
leaving turns it round from where it is.
* The volume display sits under the call card while there is one (`VolumeHud.reposition()`, called by the card, moves an
  open display with one `updateViewLayout`); the level glides on a spring through a held button's repeats; the step
  number in `HEADLINE` with tabular figures.
* The assistant's pill has a fixed width (560 dp, or the screen less 32 dp): one that grew with every partial transcript
  resized its window several times a second. The microphone's breath is a spring (380 / 0.6) fed by the level and
  settles back for the answer; a new mode's icon comes in from 0.7; the pill is rebuilt when the accent changes.
* The enrolment card (`voice/EnrollDialog.kt`) is a `Modal`; the level meter fills from the start side (the right in
  Arabic) on a spring, and a taken recording's dot pops in.

**Strings.** Arabic lines that end a bracket or clause with a Latin name isolate it (`⁨…⁩`): `set_bt_cover_sub`,
`yt_no_app`, `voice_get_google_sub`, `voice_get_tts_sub`, `maps_voice_missing`. Messages made without an activity speak
the app's language, not the unit's (`LocaleHelper.wrap`): the update page's errors (`UpdateManager`), and the ZLink,
beta-leftover and removed-apps toasts.

Test hooks: `SettingsActivity --ei page N --es theme dark|light --es lang ar|en` (the old name: `home/Entry.kt`); `am broadcast -a
com.abdllh.aura.debug.CALL --es state ring|talk|end …`; `com.abdllh.aura.debug.VOLUME_KEY --es key up|down|mute`;
`com.abdllh.aura.debug.AMRI --es say "…"`. The overlays need the overlay permission (`appops set com.abdllh.aura
SYSTEM_ALERT_WINDOW allow` on the emulator).

### Notifications (1.13: `system/Notices.kt`, `media/AuraNotificationListener.kt`, `phone/MessageBanner.kt`)
The owner asked for two things: none of Android's notifications on the screen, and the phone's messages on it
(WhatsApp's in his example). Settings → Display & sound → Notifications holds both switches (on by default) and
"show the message text".
* Hidden: Android's heads-up cards are switched off (`Settings.Global heads_up_notifications_enabled` 0, through the
  API with WRITE_SECURE_SETTINGS, else root `settings put`); the value from before is kept in `files/heads-up-before`,
  put back when the switch goes off, by the kill switch (`CrashGuard`, `AuraApp`) and by the ROM's restore. AMRI's
  notification listener (the one that already existed for other apps' media sessions, `AuraNotificationListener`)
  cancels every clearable notification another app posts, and those already there when it connects. Ongoing ones
  (a foreground service, Android's "displaying over other apps") cannot be cancelled; they stay in the shade, which
  nothing opens here. With the kill switch on, the listener does nothing.
* Allowing the listener: since Android 8 the allowed listeners are the notification service's own list; the secure
  setting `enabled_notification_listeners` is only migrated once and writing it does nothing (tried: the setting
  changed, the service never bound). `NotificationManager.setNotificationListenerAccessGranted` is a system API AMRI
  cannot reach (NoSuchMethod through reflection on the emulator), so it is root `cmd notification allow_listener
  com.abdllh.aura/com.abdllh.aura.media.AuraNotificationListener`; `isNotificationListenerAccessGranted` tells whether it
  is needed. Android binds the listener by itself from then on.
* The phone's messages: an iPhone gives WhatsApp's notifications only to CarPlay and to a Bluetooth LE accessory with
  an ANCS client. The unit's Bluetooth daemon (`gocsdk_8800`, BTstack-based) has a GATT server for ZLink's pairing
  (`CQ_CMD_GATT_REGISTER_APP`, `…_ADD_SERVICE`, `…_START_ADV`, `…_SEND_INDICATION`) but no GATT client and no ANCS
  (no `7905F431…` service UUID, no "ancs" anywhere); it does log "Incoming SMS" (MAP), and com.bt.bc03's callback has
  `onGetSMS` — SMS / iMessage over MAP would be the only Bluetooth way, not WhatsApp. So WhatsApp runs on the unit
  itself as a linked device of the owner's account (Play Store → WhatsApp → "Link as companion device", the iPhone
  scans the code: WhatsApp → Settings → Linked devices); its notifications then reach the listener like any app's.
* A message is a notification from a known messaging app (WhatsApp, WhatsApp Business, Telegram, Messages, Signal,
  Viber, LINE, imo, Discord, Messenger, Instagram, Teams, Skype…), Android's `CATEGORY_MESSAGE`, or `MessagingStyle`;
  not ongoing ones nor group summaries (WhatsApp's "checking for new messages", the "3 messages from 2 chats" line).
  Sender and text come from the newest `EXTRA_MESSAGES` bundle (in a group the conversation title is the group),
  else `EXTRA_TITLE` / `EXTRA_TEXT`; the same app + sender + text within 90 s is not shown again (apps post updates
  of the same notification). It is shown before it is cancelled, so hiding Android's never hides a message.
* `MessageBanner`: an overlay card at the top like the call card (`Shapes.overlay()`, `lift(Elev.OVERLAY)`, enter from
  above): the app's icon, its name (and the group), the sender, two lines of the message (or "New message" with the
  text switch off), a close button; gone after 6 s plus 45 ms a letter (at most 12 s). A newer message replaces the
  words in place; a tap opens the conversation (the notification's content intent, kept after the cancel), a flick
  up or the close button dismisses it; a finger on it holds the timer. During a call the call card has the top: a
  message waits and comes 0.8 s after the call ends if the call took under 2 minutes.
* Emulator: the listener needs the root shell (`fake_adbd.py`, `setprop debug.aura.adb 10.0.2.2:15555`). The workshop
  emulator's adb runs as root, and Android does not post `cmd notification post` from root as the shell: use
  `su 2000 cmd notification post -t "Ahmed" tag "text"` (in a script through `scripts/emu-run.ps1`), with `setprop
  debug.aura.msgpkg com.android.shell` so a debug build takes the shell's notifications as messages. Android 10's
  `-S messaging` style fails to post from the shell.

## Screen check and the trip recorder (`diag/`, 1.19.1)
Until now no number had come from the unit: no RAM, no GPU memory, no frame time; the plans for 2.0 rest on guesses.
Settings › About › Screen check (`settings/ScreenCheckActivity.kt`, a page of its own: seven fill the sidebar) has one
button; about two minutes later there is a report he can photograph, and his server has it. Nothing runs or is sent
unless it is pressed; with its switch off the trip recorder is one preference read at the start.
* **Two processes.** Everything that loads GL or native code runs in `ProbeActivity`, `android:process=":diag"`
  (`AuraApp.onCreate` returns at once there, as for `:boot`); the rest (`Collect.kt`: reading files, the root shell, the
  network) runs on one thread of the main process. They only talk through files in `files/diag/`: `plan.json` (what :diag
  is to run), `marker.json` (on the disk with fsync before each test, deleted after it), `r-<test>.json` (one result),
  `plan-done.json`, `run.json` (a run is under way), `report.json`, `report-sent.json`, `skip.json`. No binder, so
  nothing that dies or hangs in :diag can leave Home waiting. Each group of `Checks.GROUPS` gets a fresh process, so the
  memory read after a map test is that test's. The next one starts only once the last is gone from
  `runningAppProcesses`: the system lists a dying process for a moment, and its going looked like the new one dying (on
  the emulator one run in five, the last test "not run"). `dumpsys meminfo` and `gfxinfo` go by pid: by name Android
  sometimes answers with the whole system's list and no app summary, with one process of that name running.
* **A crash is a result.** A marker found with the process gone: that test is "crashed", its evidence is the logcat
  lines of the death, the rest goes on in a new process, and later runs skip it (`skip.json`, until the app's version
  changes or he presses "try again"; the motion tests go with the map, they need it). Past its `budgetMs` a test is
  killed from outside and is "hung", the same way. A marker from another boot (`boot_id`): "the unit restarted during
  it". Android may start a dead process's top activity again by itself, and only `ScreenCheck` starts tests: `ProbeActivity`
  gives up at once when there is no plan or a marker is there. Tried on the emulator with `setprop
  debug.aura.diagtrouble <test>` (SIGSEGV, debug builds) and `<test>:hang`: Home's pid stays the same.
* **Stops at once, keeps what it has:** the car moving (`Gear`), reverse, a call, Cancel, Back, Home, anything over the
  test (`onStop`). Then nothing is sent; the page offers to. The main process dying is found by the next opening of the page
  (`run.json`), which makes a report of what was left and says so. The step that asks for the wheel's keys is the one
  exception: a key may open its firmware screen, the music keys are held quiet (`MusicKeys.quietUntil`) and the page is
  brought back when the step ends (needs background starts: the unit has them, the emulator needs `appops set
  com.abdllh.aura SYSTEM_ALERT_WINDOW allow`).
* **Everything has an end.** `Sh`: `LocalAdb`'s timeout is per read, so a command that keeps talking would never end by
  it; once the shell failed, later calls answer at once, so a unit without root does not spend seconds on each. Each part
  in the main process has 45 s (`ScreenCheck.part`), each test its `budgetMs`. What needs root says so in the report
  instead of being silently empty.
* **Measured as the car would show it.** `MapRig` is the app's own `AuraMap`, style and tiles, full-bleed, with two static
  translucent panels above it, fed one fix a second along a stored Dammam road at 60 km/h in the navigating camera; first
  capped at 60 (it falls to 30 by itself when it cannot keep up: `perSecond` shows it), then at 30; then the same as
  TextureView in a fresh process. The main process serves the tiles (`TileServer.attach`), offline ones when it has them;
  the report says which, and says so when the map never got its tiles (the numbers are then for bare land colour).
  ES 3 is asked of EGL itself (a config, then a context on an ES 2 config if none admits to it), and ETC2 and ASTC are
  drawn and read back: the driver naming them, and the firmware's `ro.opengles.version` of 2.0, say nothing either way.
* **Immersive.** Sticky only when `policy_control` has `immersive.preconfirms`: Android's "Viewing full screen" card stood
  over the first test and dimmed everything being measured. Without it the first touch brings the bars back, so Cancel
  can take two touches (the unit has no navigation bar, and AMRI's status-bar switch preconfirms).
* **What goes out:** an allow list (`Collect.SETTINGS`), never all preferences: no lyrics key (also taken out of every line
  before the file is written, `ScreenCheck.clean`), no Home or Work place (only that they are set), no contacts, no song
  names, no message text, no coordinates. `POST /api/report` (`tools/lyrics-server/server.py`, same key check as its other
  routes): up to 2 MB of JSON, kept as `<cache>/reports/<utc-time>.json`, the newest 200. A 404 there means the PC's server
  is older than this: the report stays and "Send again" is offered.
* **Trip recorder** (`diag/TripRecorder.kt`, off by default): a parked check cannot show the load of a moving car. One line
  a minute in `files/diag/trip.jsonl` (front screen, FrameMetrics histogram, map fps, CPU %, `Debug.getPss`, GPS rate,
  speed, temperature, memory free, route and reroute times), on one background-priority thread; each line says what the
  thread cost (`selfPct`). The count of lines is kept in the preferences because the unit starts cold at every drive: the
  switch outlives a power-off, and 120 lines (two hours) turn it off. It goes with the next report and is deleted once
  that arrived.
* **Numbers from the emulator mean nothing about the unit**; the page says so on any build that is not on the unit
  (`Device.isNwd`). Parsing of `dumpsys SurfaceFlinger` and the Mali paths have only met the emulator: the raw text goes
  in the JSON for that reason (`surfaceFlinger.raw`, `d_memory.mali`).

## The way back and the guard (1.19.1: `update/Rollback.kt`, `assets/rollback.sh`, `guard/`)
Until 1.19 the only way back from a version that went wrong on the unit was the PC restore, which wipes AMRI's data. A
redesign needs better, and nothing can be tried on the unit before a release, so everything here also says what it did.

### The way back
* What the firmware allows (from its bytecode): `ro.debuggable=1` lets `pm install -r -d` downgrade any package, and the
  data stays. `pm uninstall` never: with an older ROM copy under the /data copy it deletes the data, `-k` or not. A ROM copy
  newer than the /data copy wins at the next boot and removes the /data code, so a way back is only real while the version
  it goes to is not older than the ROM's (`Rollback.usable`, `ro.aura.rom.version`), and 2.0 must stay a /data update.
* `/data/local/tmp/amri-rollback/rollback.sh` (`assets/rollback.sh`, copied there as root by `Rollback.ensureScript` at
  every start, `LF` whatever the checkout did). Any root caller may run it: Settings, the guard, AMRI Rescue, a PC
  (`rollback.bat`, `./install-linux.sh rollback`, or `adb connect <ip>:5555`, `adb shell sh
  /data/local/tmp/amri-rollback/rollback.sh`). It starts itself detached, because replacing the package kills whoever
  asked; checks the saved copy's hash; clears the kill switch and its markers (a new process must not read the home screen
  as not its own); `pm install -r -d` under a 5-minute `timeout`; writes `EXIT=n` to `result`, then `HOME=`/`NOW=`, `DONE`.
  Success: home property back to AMRI, a home intent. Failure: `to_stock` (kill switch, stock launcher as home, what a
  dead AMRI cannot give back: volume bar, call window, key rules, `policy_control`). `--test` leaves everything as it was
  on failure. `--stock` does only `to_stock`.
* The race that cost a test: adbd sends SIGHUP to the whole process group when the shell it started ends. `nohup … &`
  followed by `exit` lost that race on a loaded emulator: "started", and then nothing at all. So the launcher ignores HUP
  before it forks (children are born ignoring it) and only ends once the child has written `running`; it says
  `failed-to-start` if that never comes. `WifiKeeper`'s restart (`nohup sh -c … & echo started`) has the same shape and can
  lose the same race, with no word in its log; it is not changed here.
* The saved copy, `aura-prev.apk` + `aura-prev.json` (version, code, SHA-256, size, source), takes no room in the APK (the
  betas' bundled copy cost 12.6 MB). Two ways in: `Rollback.saveRunning`, called by the updater after it verified the
  download and before it installs, copies what `pm path` names; `Rollback.prepare`, 45 s after every start, fetches the
  published 1.19.0 when there is no copy, a real unit (`Device.isNwd`) and a network, with the updater's own
  `GitHub.download` (resumes, 15 min between tries, 8 tries a start) against the pinned size and hash. Debug:
  `setprop debug.aura.rollback.url http://…`. A version still on trial does not replace a saved copy that works: a bad beta
  updated to the next beta must not take the way back with it.
* The caller clears `availableTag` and `releaseCache` and sets `updateBeta` off first, with `commit()`, or the version left
  behind is offered as an update a moment later. A test is meant to be followed by that update, so it clears the check
  times instead: the version it goes to looks as soon as home opens, and the Update page offers it for one tap.
* Settings → About shows it from a small file (`files/rollback-state`), so the page needs no root shell to open: the
  version it would go to or why there is none, "Try the way back" while the copy is 1.19.0 on a 1.x (both ends known-good
  code, so it proves the way on the real unit before 2.0 needs it), and how the last use ended, whoever pulled it. "The data
  stayed" is seen, not assumed: every start leaves a small mark in the app's data (`files/rollback-data-mark`), and the first
  start after a way back looks for it before it writes it again.

### The guard (`guard/`, process `:guard`)
CrashGuard dies with what it guards: a native crash, a throw in `AuraApp`, a frozen main thread or a home screen that
never draws leave it nothing to count. So a process of its own: `GuardReceiver` (BOOT_COMPLETED, MY_PACKAGE_REPLACED) and
`GuardService` (foreground, the quietest channel, or Android stops it once the app leaves the screen). No native code, no
design system, no singleton of the main process; `AuraApp.onCreate` returns at once there; a throw in it ends the process
quietly, with no "keeps stopping" over the car's screen.
* **The firmware force-stops what is not home and not on its list, and nothing above survives that by itself.**
  `android.os.NwdBackgroundManagerService` (framework.jar, created in `AMS.setSystemProcess` because `persist.nwd.bms.enable=true` is in
  the build.prop): `AMS.updateActivityUsageStats` calls its `managerPackage` at every activity event of any app, which posts a look 500 ms
  later (message 9529), and that force-stops each package with a running service that is neither the top task nor listed
  (`executeKillBackgroundCount`, `kill_background_services` is 1 and no firmware APK touches it). `AMS.startService` returns null for such a
  package (`skipService`): no exception, no service. `isWhitelistBackgroundTask` lists a package through `persist.nwd.launcher.default`, so
  none of this shows while AMRI is home, and from the moment the property names the stock launcher, the guard is a running service in a
  package that is not listed. Then: main and `:guard` gone within a second of the stock launcher's resume, the package in the stopped state
  (no BOOT_COMPLETED after that: no guard, no Wi-Fi step, airplane mode stays on), `Rollback.toStock`'s callers and their give-backs
  cut off. The emulator has no such service, which is how everything below passed there. Not run on the unit; the screen check
  now reports `dumpsys nwdbackground`, the property and the list (`a_identity.nwdBackground`).
  What it changes, since 1.19.1:
  - `guard/BackgroundList.kt`, from the guard's first look (again a minute later if there is no root shell yet at a cold boot): AMRI goes
    on the list in the two places the service reads it. The setting `whitelist_packagename` ("[a,b]", read at once through its observer; the stock
    launcher keeps a short list of its own there and may push ours out, hence at every start of the guard) and
    `/data/nwdappconfig/app/BackgroundManager.ini` (`ro.nwd.config.path`, from `/system/platform.prop`; read at `systemReady`, so from the next boot;
    `.pre-aura` first, so old restores undo it; checked on a copy; the firmware's file has no newline at its end). It stays with the kill switch on:
    that is when it is needed. A restore leaves the setting, naming a package that is gone: harmless.
  - `CrashGuard.bailOutAtStart` no longer needs a living guard: on trial with a usable copy it starts the detached `rollback.sh` itself (the crash
    list first, or the version it goes to finds a loop that is not its own) and home never flips. Otherwise `bailOut`: the marker and the app's own
    releases, then `rollback.sh --stock` flips the property last, gives back as root and starts the stock launcher, and goes on if its caller is
    force-stopped halfway (HUP and PIPE ignored). In a call it gives the stock call window back at the call's end, in the background.
  - A unit that has never run 1.19.1 and goes straight to a 2.0 that dies at once has no list at that moment; the two paths above are for it.
* What the main process leaves: `files/guard-marks`, one line per event in one append (`S` start with the versionCode,
  first thing in `AuraApp`, before anything native; `H` home wanted; `O` home drawn and 20 s later still here; `L` 5 minutes after
  the first frame and still here, the main thread still answering; `B` alive 60 s without home to show; `W` its Wi-Fi step ran), and
  `files/guard-beat`: a counter the main thread moves every 5 s, written by
  a thread of its own so a slow disk never holds the screen. Each line carries the boot id and `elapsedRealtime`: never the
  wall clock, which head units set late. The beat is compared in `uptimeMillis`, which stands still while the unit sleeps.
* What counts: starts of **this version in this boot** with no `L`, dead by now or frozen, newest first. An update is not
  a failure (the old version's starts are another versionCode), a power cut is not (another boot id), the start still
  running is not (with `O` it gets its chance: no verdict over it), a process that lived on with no home to show is not, and a
  process Android last ranked 600 or lower is not: that is the home app behind another screen, what its memory killer goes for first
  (with the notification listener bound it ranks 250 there, out of that killer's reach). Limit 3 in a row, or 3 freezes since the last
  verdict. A verdict is once, until a healthy start: a new failure after that is a new story. `rollback.sh` leaves a reset
  mark (`R`) in the file: a version that comes back in the same boot starts its count anew, since the older one between
  leaves no marks.
* `O` alone says nothing about the trial: a native crash a minute after the first frame (the map engine at the first GPS fix) reaches it every time.
  Only `L` adds a healthy start, and seconds of use are kept apart until `L` and go with a start that dies young. So `GuardTrial`: 3 starts that lived
  and 30 minutes of use in them. A drive shorter than 5 minutes adds nothing, and fails nothing.
* The slow first start: while a version has never reached `O` (`GuardTrial.neverOk`) the limit is 4, and a stale beat or
  an undrawn home takes 5 minutes instead of 90 s and 2 minutes: the first start after an install runs on code still being
  compiled (3 minutes and more on the unit).
* Staged by one constant, `GuardService.ACTS_FROM_CODE` = 2000000: below it the guard writes what it would have done to
  `files/guard-log.txt` (the end of it is in Settings → About) and touches nothing. From 2.0, during the trial
  (`GuardTrial`: 3 healthy starts and 30 min of use, `files/guard-trial`, per versionCode), and never in a call or in reverse:
  ends a frozen process, and after the limit goes back to the saved copy, or with none takes the kill switch and the stock
  launcher. After the trial it only opens AMRI Rescue, and that waits for the first look after a call or reverse (and goes, if it was
  for a frozen main thread that has moved on by then). Debug: `debug.aura.guard.throw`, `.hangstart`, `.hang`, `.slow`,
  `.trialmin`, `.livedsec` (`guard/GuardTest.kt`). **On an emulator at 2.x:** every `am force-stop`, and every `adb install -r`, ends a start that has not lived, so the fourth quick restart in a boot gets the verdict ("kill switch on, stock launcher" in the guard's log; seen on emulator-5576). With no root shell it only writes `files/disable_home` and a preference: the app cannot set the properties, and AMRI stays on screen. With `fake_adbd` it is the real thing: `rollback.sh` runs. `scripts/dev-install.ps1` therefore sets `debug.aura.guard.livedsec 20` (gone at the emulator's next boot; 4 installs 26 s apart stayed healthy). Undo a verdict: `setprop persist.aura.disabled 0; setprop persist.nwd.launcher.default com.abdllh.aura`, remove `files/disable_home`, install again.
* The Wi-Fi step: after a cold boot airplane mode stays on until `WifiOnStart` clears it, and a home screen that dies first
  would leave the unit unreachable for a phone or a PC. `system/WifiStep.kt` is that one step, without anything of the main
  process; the guard runs it 45 s after it came up when the boot has no `W` mark, also in 1.x, unless the owner's
  `wifiOnStart` is off, the screen is off or ZLink holds the Wi-Fi.
* AMRI Rescue (`guard/RescueActivity.kt`): a second launcher icon in the guard's process with plain Android views, for the
  stock launcher's app list: back to the saved version, open the stock launcher, try again. AMRI's own drawer leaves its
  own package out (`AppRepo`). HomeActivity is first in the manifest, so `getLaunchIntentForPackage` still gives home.
* `CrashGuard.isCrashLoop` now also runs right after `install` in `AuraApp`: before, a throw in `AuraApp.onCreate` was
  counted but the check lived in `HomeActivity`, which never ran. `bailOutAtStart`: nothing of the start-up runs (one of its
  steps is what crashes), it goes back or hands over (above), the process leaves after 8 s, and the next start finds the new
  version or the kill switch on.
* That move alone was not enough, as the emulator showed: from the second crash in a row Android 10 puts "AMRI keeps
  stopping" over the screen and the crashed process waits for the answer, 5 minutes if nobody gives one, so the start
  that would find the loop never comes. From the second crash on (and always with the kill switch on, behind the stock
  launcher) CrashGuard's handler now leaves by itself, with the stack in the log and in `last_crash.txt`: a throw in
  `AuraApp` went from 5 minutes under a dialog to kill switch on in under a second. A native crash cannot be helped that
  way: the dialog still comes over the home screen, but the process is dead by then and home restarts under it.

### The data rule of a way back
1.19.0 must start on whatever a newer version left in `/data/data/com.abdllh.aura`. `Prefs.init` reads `accent` with
`getInt` in `AuraApp.onCreate`: the same key stored as anything else throws there on every start, and until 1.19.1 nothing
catches that, so the way back would have bricked the home screen.
* A key in prefs files `aura`, `aura_crash`, `aura_music` or `aura_mapguard` never changes type. New settings go in new
  files. `system/PrefTypes.kt` holds what 1.19.0 reads (`scripts/prefkeys.py` lists it from any tree); a debug build stops
  at its start and at OK when a stored value has another type. R8 drops the check from a release.
* `scripts/test-data-compat.ps1 -Old <1.19.0 with x86_64>` is the written test: let the newer build run, go down the way the
  script does, and ask whether 1.19.0 comes up, home stays in front, and nothing crashed. Build the old APK from the tag:
  the published asset is ARM only.
* **The undo journal must stay live, same names and types, in prefs `aura`**: 1.19 and the restore use it to give back what is
  taken. `barsAdded`, `barsReplacedStatus`; `replaceStockMusic`, `stockMusicApplied`, `stockMusicWasDisabled`; `volumeHud`,
  `volumeHudStockOff`; `callCard`, `callWindowStockOff`; `auraRing`, `auraRingInstalled`; `musicKeysAura`, `musicKeyRulesOut`,
  `musicKeyNote`; `fullOffOnAccOff`, `accOffModeSent`, `powerOffAskedAt`, `powerOffAskedBoot`; `zlinkGuard`, `zlinkOrigStyle`,
  `zlinkSession`, `zlinkWifiWasOn`; `bootAnimCheckedFor`, `bootAnimInstalledMd5`, `bootAnimTries`; `circlesOff1`,
  `circlesOffTries`, `removedRequested1`, `removedRequestedTries`, `betaLeftoversGone`, `wifiOnStart`, `wifiOnStartBoot`,
  `wifiKeeper`, `nwdBootId`, `hideAndroidNotices`, `showPhoneMessages`, `showMessageText`, `handbrakeSeen`, `handbrakeFake`,
  `musicSource`. A snapshot of old settings must never overwrite these blindly.
* Files 1.19 reads stay readable or untouched: `files/offline/`, `my-cameras.json`, `amri-wake.bin`, `amri-wake-neg.bin`,
  `amri-cmd-*.bin`, `wifi-log.txt`, `anim-scales`, `heads-up-before`. Component names stay: `BootHome`, `HomeActivity`,
  `MusicActivity`, `AuraNotificationListener`, `InstallResultReceiver`, `PackageReplacedReceiver`, `BootReceiver`.

### What was checked, on the emulator, and what only the unit can tell
How (2026-11-04, CarUnit3, `-writable-system`, fresh userdata): 1.19.0 built from the tag with x86_64 went into
`/system/priv-app/Aura` with the privapp XML through `scripts/emu-system-app.ps1`. `adb remount` cannot work on this image: its
ext4 has `shared_blocks` (`mount -o rw,remount /` says "unsupported optional features (4000)"), so it would need overlayfs through
`disable-verity`, whose vbmeta change makes the next boot loop. The script mounts the overlay by hand, framework stopped, and the
package scan that follows is the code a boot runs. The root shell was `scripts/fake_adbd.py`, the releases `mock/server.py`, both
through `adb reverse` (cleartext is only allowed to 127.0.0.1). SELinux permissive, as on the unit.

| Step | What it showed |
|---|---|
| 1.19.0 as a system app | `SYSTEM`, `PRIVILEGED`, all 5 privileged permissions `granted=true` |
| 1.19.0 → 1.19.1, tapped through the updater | `UPDATED_SYSTEM_APP`, hidden system package 1.19.0, 5 grants true, same data dir (inode, uid), test file intact; guard up, `O` 20 s after the draw |
| The fetch (`debug.aura.rollback.url`, the real `out/release/aura-1.19.0.apk`) | saved with the pinned hash, `source: published`. Without the property: `not-unit`, no request |
| "Try the way back" with that ARM-only copy | `Failure [INSTALL_FAILED_NO_MATCHING_ABIS…]`, `EXIT=1`, nothing changed, the reason in About. A real failure, so also the next row |
| `rollback.sh` without `--test`, same copy | `EXIT=1`, then `HOME=com.android.launcher DISABLED=1` |
| "Try the way back", a copy with x86_64 | 2 s. versionCode 1190099, hidden system package still there, 5 grants true, same inode, same MD5 of `aura.xml` and the test file; 1.19.0 home in front; no crash |
| Framework restart (new system_server, new package scan) | still the /data copy, log `ignored: updated version 1190099 better than this 1190099`, grants true |
| Back through the updater | "Version 1.19.1 is available" without a tap, one tap installs it. About: "Went from 1.19.1 to 1.19.0. The data stayed." |
| 1.19.1 → 2.0.0, trial not passed / passed | stays 1.19.0 (`has not finished its trial`) / `aura-prev` is 1.19.1, `source: self`, hash equal to the installed APK |
| 1.19.1, `kill -SEGV` loop | `3 starts in a row with no OK, 0 frozen: would go back to 1.19.0 (only watching in 1.x)`, nothing touched |
| 1.19.1, main-thread hang | 95 s after the start: `has not answered for 90 s: would end that process` |
| 1.19.1, throw in `AuraApp` | three crashes in under a second, the fourth start takes the kill switch; before the handler's change: 5 minutes behind "keeps stopping" |
| 2.0.0 on trial, `kill -SEGV` loop | third death: `go back to 1.19.0`, done 30 s after the first kill; same grants and data |
| 2.0.0 on trial, throw in `AuraApp` | back on 1.19.0 in 9 s, no kill switch left. **Emulator only:** it has no `NwdBackgroundManagerService`. With a stand-in for it this path failed, see below |
| 2.0.0 on trial, main-thread hang | ended 3 times, about 100 s each, then back: 305 s in all |
| 2.0.0 on trial, no saved copy | `kill switch and stock launcher`: `DISABLED=1`, home property stock, marker `crash`, `crashDisabled=true` |
| 2.0.0, trial over, `kill -SEGV` loop | `trial over, offering Rescue`: Rescue in front, version unchanged |
| A slow first start (fresh record, `AuraApp` takes 150 s) | nothing fired, `O` 170 s after the start |
| AMRI Rescue | the three buttons did what they say; a second entry in the launcher query; AMRI's drawer leaves it out by package |
| The guard's Wi-Fi step (airplane on, main dying at its start, `debug.aura.unit`) | `airplane was on, wifi on now`, through the root shell |
| `scripts/test-data-compat.ps1` | all passed: 1.19.0 up on 2.0.0's data, home in front, no crash |
| `clock24` stored as a string, debug build | `Pref 'clock24' in aura is stored as 's' but 1.19 reads it as 'b'…` at the start |
| `aura-install.py rollback` over its own ADB client (fake_adbd) | from 2.0.0: `DONE: AMRI went back`; on 1.19.0: `not older`; `rollback.bat` reaches Python, which refuses the emulator as not a K2501 |
| `pm uninstall` (way D) | `Success`, ROM copy active, new inode, `files/` and `shared_prefs/` empty |
| `test-flash.ps1 -Quick -Serial emulator-5574` on 2.0.0's state, current installers over the 1.19.0 patch | `ALL TESTS PASSED (quick)`; then `DISABLED=1`, home stock, `amri-rollback/` gone, ROM copy active |
| A real reboot | new boot id, the old boot's marks count for nothing, /data copy stays, data stays. The overlay goes with a reboot, and the system flag with it: that part says nothing about the unit |
| Release APK | 18,346,775 B, 41 KB more than 1.19.0. R8 build with x86_64: guard up, `O`, counts; `:guard` 10.7 MB PSS (debug 32 MB) |

The same with a stand-in for the firmware's force-stop (`scripts/fake_bms.sh`: 500 ms after the app in front changes, what the bytecode does;
home follows the property; the guard's service disabled while the firmware would refuse to start it; SELinux permissive):
`python scripts/guard-test.py --old <1.19.1 debug> --new <2.0.0 debug>`. On the build before these changes, a throw at the fourth start: home flipped,
main and guard force-stopped within 1 s, no rollback, still 2.0.0 with the kill switch on, nothing left to change that. Now:

| Step | What it showed |
|---|---|
| 2.0.0 on trial, a crash loop's fourth start, a copy | back on 1.19.1 in 2 s, home never flips, nothing force-stopped, no kill switch left, the old version finds no crash loop |
| No copy, not on the list | kill switch, stock launcher in front, AMRI force-stopped, and the give-backs done all the same; marker `crash`, `crashDisabled=true` |
| The same, on the list | nothing force-stopped, the guard still there, the package not left stopped |
| `rollback.sh --stock`, its caller killed 0.25 s in | goes on to the end; in a call the call window comes back at the call's end |
| The list | setting `[com.a,com.b,com.abdllh.aura]`, or `[com.abdllh.aura]`; the ini's last line whole, ours on its own, `.pre-aura` the old bytes; a second start changes nothing; the restore's loop puts the file back |
| `kill -SEGV` a few seconds after `O`, three times, 1.19.1 | `3 starts in a row failed … would go back`; no healthy start, no seconds |
| The same on trial, 2.0.0 | back on 1.19.1 |
| A start that goes on | `O` adds nothing, `L` adds one healthy start and its seconds from its start |
| Killed behind another screen, rank 600, three times | `not counted`, no verdict |
| Trial over, frozen, reverse on | `Rescue waits`, nothing over the camera; in front within 5 s of reverse ending. Frozen and moved on meanwhile: not offered |

Only the unit can tell: that its own `pm` takes `-r -d` as its bytecode says (the emulator's is AOSP's, and it behaved as read); the
real ARM 1.19.0 installing; real adbd's hangup (fake_adbd runs through `adb exec-out`); how long dexopt takes the first start; whether
the firmware shows "keeps stopping" at all; what `dumpsys nwdbackground` really says there, and that the stand-in behaves as its bytecode reads; the stock launcher listing AMRI Rescue (its own list is not on the emulator, which has no
`com.android.launcher` either, so "the stock launcher opens" was only seen as the home property and the kill switch); airplane mode
after a real cold boot. "Try the way back" in 1.19.1 is there to answer the first three, at no risk. Once 1.19.1 runs: the
About page's "Last time" line and the guard's lines are the report.

**What this does not have:** no snapshot of the settings for the way down (not needed while 2.0 keeps to the data rule above), no
phone trigger (Naghm), no acting in 1.x except the Wi-Fi step.

## A place from the phone (`link/`, `nav/Trips.kt`, 1.19.1)
Set a place on the iPhone, directions start on the screen, with no iPhone app yet: a page the unit serves, opened from a
QR code with the iPhone's camera. The app that comes later speaks the same protocol, and so can a Shortcut. Off by
default: Settings → Vehicle & system → "ربط الجوال" (2.0's design decides whether it stays a switch). Nothing here has
run on the real hotspot; what to believe is at the end of this section. The design behind it is in the planning report
`tmp/v2/understand/companion.md` (AMRI Link v2).

**`nav/Trips.kt`: starting a trip, whoever asks.** What `MapsActivity.driveTo`, its Go and the 1.0 address lookup did,
without a screen: `goTo(ctx, place, origin, source, waitForFix, remember, wanted, done)` finds the route and starts
`NavSession`. The Maps screen's Home and Work, Go, the voice and the phone all go through it, one attempt at a time (a
newer one replaces it); a screen listens (`addListener`) and reads `attempt` to show what is going on. `done` says what
really happened: STARTED only after `NavSession.start` returned, FAILED, NO_FIX, DROPPED. The asker's own position
(`origin`) is used only while the car has no fresh fix (2 minutes), and with no position at all it waits up to 10 minutes
for the first fix, holding the GPS itself because no screen may be. A route gets 60 s (a router that never answers must
not hold a place for ever). `NavSession.start` throwing is a FAILED, not a crash. `MapsPanel` keeps today's behaviour
for its own paths: Back during a Home/Work route still means no trip (`wanted`), no fix still means `select`.

**`link/PhoneServer.kt`: HTTP/1.1 on every interface, port 47101.** Why the unit serves: Safari, a Shortcut, a share
extension and an app can all connect out while iOS has the app asleep, and only those. A wildcard bind survives
`WifiKeeper`'s restarts. TileServer's pattern: at most 4 connections on background-priority threads (the fifth is told
503 on the accept thread and closed), 10 s for a whole request (a watchdog closes the socket, so a byte a second gets no
further than silence does), 64 KB body, 16 KB head, 20 s for an answer nobody reads, connection closes after each answer.
One blocked thread at idle: pool and watchdog threads die after 5 s. At most 2 event streams, the oldest goes for a new
one, so a request always finds a way in; a stream whose phone left without a word goes at the next heartbeat's failed
write (15 to 30 s). `persist.aura.disabled` stops it at the next connection.

Who may do what (`classify`, `Pairing`): a paired token acts at once; no token from the Wi-Fi's gateway (on the iPhone's
hotspot that is the phone itself, any app on it) gets a card on the screen that waits for a tap, and nothing else that
acts; no token from another private address gets the page, the ping and the pairing while a code shows; anything else
403 on connect. Pairing: six digits, single use, 5 minutes, 5 tries, and valid only while really on the screen (leaving
the page or Settings ends it within a second; `windowVisibility`, because Home does not call a page's `onHide`). Only
SHA-256 of a token is kept, at most 4 phones. For web pages: POST and PUT must say `application/json`, no CORS allowance
is ever sent so a preflight fails, a mismatched `Origin` (and `null`) is refused, `Host` must be an address or this unit's
name (DNS rebinding). The page itself: one inline file, CSP `default-src 'none'`, no frames. What a token is worth,
honestly: adbd on 5555 gives root to anything on the hotspot; it stops accidents and other phones, nothing more.

Endpoints: `GET /`, `/p/<code>`, `/v1/ping`, `/v1/state`, `/v1/events` (SSE: `nav`, `music`, `car`, a snapshot on
connect, a heartbeat every 15 s, one `progress` per 10 s at most), `/v1/places` (and PUT), `/v1/diag`; `POST /v1/pair`,
`/v1/nav/go`, `/v1/share`, `/v1/nav/saved`, `/v1/nav/stop`, `/v1/search`, `/v1/remote`. The last 64 `id`s are remembered,
so a retry starts nothing: it hears `duplicate` and `last`, what became of it. Beyond the report: `/v1/search` with
`"on":"phone"` answers with the unit's own results and leaves the screen alone (the page's list); `no_answer` when the
service did not answer in 9 s, which is not "nothing found". `/v1/nav/saved` is a place from a paired phone like any
other, not Maps' `go` extra: that one starts at once, and a route must not change silently during a trip.

`link/NavBridge.kt` is the server's only door into the app: everything runs posted to the main thread, caught, and a
server thread that needs the answer waits a limited time (a map's first frame can hold the main thread for seconds on the
emulator). What was asked still happens when the main thread comes free, so the phone is told 202 `pending`, not an error;
only a plain read (`/v1/state`, 3 s) says 503 `busy`. The main thread never waits for anybody here. The live feed listens only while a
phone does. The redesign may rename the Maps internals: this file changes then, the server does not.

A place never costs the main thread more than one waiting Runnable: `NavBridge.offer` keeps the newest and one posted drain, and
the one it replaces hears `replaced`, as it would on the screen. And a peer may send a place at most every 2 s with no token, 3 a
second (5 at once) with one, or it hears 429 `too_fast` with `Retry-After: 2` and its id is not taken, so the same id may come again;
a retry of an id already taken costs nothing and hears `duplicate`. Measured (`phone-server-test.py --only flood`): three loops of
places with no token, 92 a second for a minute: 31 reached the app, `/v1/state` answered every time in under a second, the guard
saw nothing. Before: 324 a second held the main thread at 100 % past the flood's end, and the guard read that as a frozen home screen,
which from 2.0 on means an app with no token could send AMRI back a version.

**`link/PlaceInbox.kt`: what the unit does with a place** (`TripCard` is its one overlay, in the look of the call and
message cards, narrower and on the other side during a trip, because the top of the driver's side belongs to the next
turn; 2.0 reskins it):
* Idle, paired: Maps comes forward (`Screens.maps`), the route starts, "بدأت الاتجاهات إلى X" is said
  and shown, 5 s with a Cancel. The phone hears `routing`, then `started` with the route's numbers. Started means started:
  it is told from `Trips`' STARTED, never from having accepted the request. A retry after a lost answer says the same.
* No fix: the phone's `from` if under 60 s old and under 100 m, else `waiting_fix` with the place on the map and a Cancel.
* On a trip: the card asks, 20 s, nobody answers: the trip goes on. A replacement that finds no route keeps the trip and
  says so (`failed`, `kept`): `select` over a running trip's panels is what the first pass did.
* In a call or in reverse: `held`, and when that ends the card asks (it is old news after 30 minutes). No card ever covers
  the camera or a call's card: one up when they start goes away (a question goes back to `held`; a route being found goes
  on underneath; the started card just ends, the trip runs, Maps has its End).
* No token, or a place that is only a guess (a Google viewport, a name near a point): always the card, 30 s, `expired`.
* No route: `failed`, the place stays on the map with its retry and Go; if he then starts it there, `started`.
* No overlay permission: the place on the map with Go, which is the same tap. Another navigation app chosen: handed to
  it and told `handed`, not `started`, because it says nothing back.
* Apple's place data stays off the recents, also when it starts from the map's Go (`dest_keep`; its terms, the report's 2.6).
* Settings → About: "آخر وجهة من الجوال: 18:22، الراشد مول، عبر الشبكة (بدأت)" and the server's line with its last request,
  because the owner's photo of that page is the only report from the road.

**Voice.** "ودني <مكان>" searches here (`Places.lookup`: the same search, which cancels nothing of the Maps field's, and
which nothing cancels) and `Places.clearWinner` decides. Clear: the name answers what was said (≥ 0.85), no other name
answers as well at about the same distance, branches of one name are one answer, the nearest. Then it goes through the
inbox: it says "أبشر، أوديك إلى X، تبعد Y", the card has the 5 s Cancel, a tap on the pill cancels while it still looks, and
during a trip the card asks. Not clear, or no position: the map's search on it, as before. A word for a kind of place alone
is a list, whatever answers it ("ودني المطار" found the district Al Matar, "شارع" the nearest Street 9), and so is a far
namesake with a partial answer nearby. Photon's Arabic data decides a lot of this: "مطار الملك فهد" and "جامعة الملك فهد"
stayed lists on the live search, "الراشد مول" and "ستاربكس" started. In `Commands` a place with a name is no longer the
nearest of its kind ("ودني مستشفى الموسى"), "جامعة" is no longer a mosque, and "وقف الملاحة" ends the trip (`StopTrip`).

**`link/ShareParser.kt`.** Coordinates the link or text states are `exact`; `/@lat,lon` is where the map was looked at:
`guess`, which always asks; a name alone is a search; a link that gives nothing says why (`dead_link`, `no_redirect`,
`network`, `not_a_map`, `no_place`) and never guesses. The only network step is a short link's redirect, at most 2 hops of 3 s
+ 3 s for a link and 3 requests for a whole text, https, only the hosts in `SHORT_HOSTS`: the Location header is read and the page never
(its `center=` holds the requester's region, measured). An app's User-Agent, because a browser's gets a preview page instead of the
redirect. A link whose authority holds `@` or `\` is never asked for (`https://other\@maps.app.goo.gl/x` and
`https://x@maps.app.goo.gl:y@other/x` name another host to `java.net.URL`, seen connecting on API 29), the request goes to the checked host
rebuilt with the path as written, never to the link's text, and `network` checks the address once more. Text comes from any app on the
hotspot, and a thread in a regular expression cannot be stopped (the watchdog only closes the socket): `coordinates` looks at the first 512
characters with whitespace squeezed, and each separator has one way to be read, or 120 characters of runs of spaces keep a thread busy until
the process dies (four of them: every slot and every core).
`tools/link-fixtures/share.json` has 71 cases in a form the phone's Swift parser can use (`withinMs`: how long an answer may take); 6 are
marked as measured live, the others are the listed formats written by hand, to be replaced by real captures.

**`link/Qr.kt`.** Written here: byte mode, level M, versions 1 to 10 (213 bytes; the address is about 35). Always dark on a white
square with its quiet zone, whatever the theme: a phone's camera reads that. `PhoneLinkCard` shows it at 232 dp.

**`assets/remote.html`** (24 KB, Arabic RTL, system fonts, dark and light by the phone's setting). The status line says what
the unit reports and nothing else: "أرسلت" until it answers, "بدأت" only from its `started`. A token per address in
localStorage; fetch's stream for the events, because EventSource cannot carry a token. The same `id` on three tries over
20 s (the hotspot freezes for 5 to 8 s when it re-associates). Without a token it can still send a link, the screen asks.

**`PhoneLink.playSong`.** "شغّل <أغنية>" with Naghm linked: YouTube Music's search here, its first song to Naghm (`playSong`,
already in CAR_LINK.md, nothing changed there). It waits 4 s for Naghm's `state` with that id: only that says the phone
took it, a sent line says nothing about a phone that is asleep. No answer: it says so. No link: the YouTube Music app, as before.

**`NavService`** starts with a plain `startService` while the app is in front, `startForegroundService` otherwise. After the
latter a service has 10 s to call `startForeground()`: on a loaded emulator a 24 s first map frame killed the whole app
with `RemoteServiceException`, and the home screen with it. Trips now start from outside while Maps is coming up.

**Test:** `python scripts/phone-server-test.py [--serial emulator-5572] [--only peers,safety,...]`, real HTTP through
`adb forward`: every endpoint, peer rules, retries, a bad body, a slow client, 5 clients, the flows with screenshots
(`shots/v2-link-*`), a whole drive, the voice, a stand-in Naghm. Debug builds only: `setprop debug.aura.phoneserver 1`
(loopback counts as a private peer; `gateway` as the gateway; refused otherwise, and always in a release build) and with it
`/debug/parse` (fixtures), `/debug/qr` (decoded with cv2 on the PC for versions 1 to 10), `/debug/code`, `/debug/peer`,
`/debug/command`, `/debug/winner`, `/debug/answer` (fixes `Places.lookup`'s answer: Photon stops answering this PC when many
agents search); `debug.aura.navsim <km/h>` drives any trip; `com.abdllh.aura.debug.LINK --ez on true`. Overlays need
`appops set com.abdllh.aura SYSTEM_ALERT_WINDOW allow`. A place while the location is off: `settings put secure location_mode 0`.

**What only the unit and the iPhone can prove.** Whether the iPhone reaches a hotspot client on 47101 (Apple has never
promised it: the report's top risk), whether the firmware's iptables lets it, whether the Camera opens the QR in Safari and
the events stream there, `dhcpInfo.gateway` being the phone, the card and the bringing forward from the background on the real
ROM, the sentences in the unit's TTS (the emulator has none), Naghm itself answering `playSong`, the real share text of Apple
and Google Maps on his phone. The first look: About's two lines after a try, and `GET /v1/diag`.

## Logic that was wrong, fixed for 2.0's sake (1.19.1)
Each is its own commit; the screens are untouched. Limits were chosen from reading and made-up drives, since nothing has run on
the unit: what the owner's photo of Settings → About tells (`NavNote`, three lines: a late route, the route left and by which
sign, a trip resumed or not and why) is the first real measurement.

* **Tests:** `src/test`, junit 4.13.2 from Gradle's offline cache, no Android classes in the code they test:
  `gradlew --offline testDebugUnitTest`. On this PC the test JVM cannot start from the Arabic user home path: run it with
  `GRADLE_USER_HOME` on an ASCII path (a junction to the real one). 68 tests at this writing.
* **Debug hooks** (`nav/NavDebug.kt`, `am broadcast -a com.abdllh.aura.debug.NAV`): `--es fix lat,lon,bearing,kmh[,accuracy]`
  (from then on the emulator's own GPS keeps out), `--es go lat,lon,name`, `--ei sim 90`, `--ez end true`,
  `--es turn nav|amri|free`, `--es fetch url,name`, `--ez dump true` (log tag `AuraNavDebug`: trip, limit, media card, sun).
  `adb emu geo fix` first, or a process start reads Mountain View. `scripts/osrm_standin.py` (`setprop debug.aura.osrm`) answers
  with a real BRouter answer as OSRM would, after a delay; `scripts/naghm_standin.py` (`setprop debug.aura.phonelink`) sends what
  is written to a file. For BRouter on the AVD: `E45_N25.rd5` into `files/offline/segments4`, owned by the app's user.
* **Speed limits** (`nav/RouteLimits.kt`): BRouter's answer has OSM's `maxspeed` in its `messages` (49-97 % of the driven length
  of real routes here). Only for the route being driven, by place and course rather than distance along, so an OSRM route can use
  the limits of the BRouter answer that raced it. His limits come first and `LimitHold` keeps his number through a hole in his
  line. `RoadWatch.State.limitFromMap` tells them apart; today's sign looks the same. Nothing guessed from a road's class; BRouter's
  bucket "10" (5 to 15) is too rough for a sign.
* **Off route** (`nav/OffRouteWatch.kt`): `too far` as before, and two signs by the course, each over three fixes: `against the
  route` (from 20 km/h, no stretch of the line near the car within 60 degrees of its course: a U-turn, the other carriageway,
  a side road; all of the line near it counts, which keeps a roundabout quiet) and `drifting off` (from 30 km/h, the course
  pointing away from the line on the car's side, 6 m gained over three fixes, 20 m or more off). A course is the GPS's Doppler, so a
  wandering position does not fool it. Emulator: 100 km/h with 12 m of noise and 5 degrees: nothing; 10 km/h turned round: nothing; a
  U-turn at 60: `against the route`; a slip road at 8 degrees at 120: `drifting off` 23 m out, in 5 s (over 40 m took 9-16 s). Not
  seen, and it cannot be without the road network: a parallel road within the limit going the same way.
* **A late OSRM answer** (`Route.better`): kept instead of thrown away after the 2.5 s head start. NavSession takes it on a later
  fix, without a word, when the car is on it going its way and nothing is to be done soon on either route (30 s, 300 m to 1.5 km); a car
  300 m from it drops it. A failed answer stays a failure for that trip: no second question is asked.
* **A trip survives the process** (`nav/TripMemory.kt`): `navTrip` in the prefs, at the start and once a minute. A process start
  looks 4 s later: same boot and under 30 minutes by the monotonic clock; another boot, under 30 minutes by GPS's clock and within 3 km of
  where the car stopped (a head unit's wall clock is set late); within 300 m of the place he is there, and after a power-off within 1 km:
  he has parked, and a pin is rarely where the car ends up. Each try is counted on disk
  first, and after two that did not reach a minute the trip is dropped, so it cannot walk the home screen into the crash guard. It
  starts through `Trips` (`by` is `resumed` in `/v1/state`) and keeps `remember`: Apple's place stays off the recents after a resume
  too, and is on the disk only as long as its trip runs. A place being asked for at that moment (`Trips.attempt`) comes first.
  In the same boot (an update, a crash) it just goes on. After a power-off it never starts by itself: the car has been parked, and a route and a
  voice leading him back to where he just left would be the surprise. `TripCard` asks ("Your last trip: go on to X?"), 15 s, no answer is no, no voice
  until he says yes; not over the camera or a call (it goes and asks again after them). `TripMemory.dropResume`: the voice's or the phone's stop
  reaches a trip still waiting for its fix, its card or its route (`PlaceInbox.stop`); until now "وقف الملاحة" said there was no trip and it started
  anyway. `scripts/trip-resume-test.py` writes the kept trip as another boot would have left it and goes through all of this, on the emulator.
* **Arrival:** within 30 m of the place in a straight line counts only with under 300 m of the route left, or off the route: a place
  seen across a divided road used to end the trip with the way round still ahead.
* **Day and night** (`ui/Daylight.kt`): the sun's real height at the last fix (NOAA's formulas), night under -6 degrees: civil
  dusk, about 23 minutes after sunset here. `Theme.isDarkNow` uses it, so every screen does. Maps also changes theme while moving when
  the next manoeuvre is 3 km off or more: a highway at dusk used to stay on the bright map until the first red light.
* **Search** (`nav/ArabicQuery.kt`): Arabic names the place and then its kind, so word by word gave "laundry syarat". Whole phrases
  for every kind the assistant sends; "مغسلة" alone is a car wash in a car, "مغسلة ملابس" the laundry. Keys go through the query's
  normalisation (a key with ئ could never match).
* **Offline map:** 4 workers (OpenFreeMap's terms). `ResumeCheck`: a 206 is compared by its own ETag or Last-Modified and its
  Content-Range, since GitHub's CDN answers 206 to a wrong `If-Range`. Emulator, a server answering 206 with another version's ETag:
  "not the file that was begun", and the whole new file. What it cannot see is damage in the part kept: a connection cut hard on the
  emulator lost single bytes before the break, 2 times in 4, and nothing in `fetchFile` checks a whole file. Packs with a hash would.
* **Home card** (`NowPlayingRule`): what plays wins as always; when nothing plays, what played last, then the source chosen last,
  then the old order. The phone paused used to give the card, and its play button, to the local queue restored paused at every start.
* **Assistant and music** (`MediaMonitor.pauseNow` / `playAgain`, `voice/WakeMusic.kt`): absolute commands to the source that was
  playing, and exactly that comes back; a pause or mute means it; another source playing by then is not interrupted; a call
  that came meanwhile: when it ends. A false wake still pauses for as long as it listens (up to 15 s).
* **A broadcast that says nothing of its state does not play** (`NowPlayingRule.broadcastPlaying`). The stock Bluetooth app tells every title on
  `com.nwd.btmusic.playinfo.changed` with no state while it is alive, and it is at every ignition (fact 34; its bytecode; not seen on the unit): that
  was "playing" for ever, so the card said so with the phone paused, and the assistant found another source playing and left the phone's music off. It is ignored where
  `BtMusic` has that song; no state means what it said last, else not playing; no media key goes to an app that never said it plays (`command`); the
  assistant's play opens AMRI Music there (`playable`). `Player.release` releases its media session: set inactive it stayed the one that gets the
  media keys (Android keeps the last session that played, active or not, tried on the emulator), and a key meant for something else resumed the old local song.
* **One voice at a time** (`voice/VoiceTurn.kt`): the assistant from the wake until it is done, a prompt while it plays. A prompt
  being said ends before the chime. A prompt due during the assistant's turn is not looked at: the next fix decides, at the distance of
  that moment, so nothing comes late or for a turn already passed. A turn nobody gives back ends by itself (15 s, 40 s). A false wake must not
  cost the exit, though: 15 s is half a kilometre at 120 km/h. So a near prompt that is due takes the voice from a wake by voice nobody has spoken into
  (`Amri.giveWayToPrompt`, `WakeTurn`: from 2 s after the wake, until `onBeginningOfSpeech`; learned as not meant). Far and early prompts still wait, and a
  request being spoken, or a tap on the microphone, keeps it. Emulator, a drive simulated at 90 km/h: `AMRI takes the turn`, 2.2 s later
  `a turn prompt comes first`, `In 150 metres, turn left`; with speech begun: nothing for 25 s until it ended (`--ez wakevoice true`, `--ez speech true`).
* **Lyrics** (`MusicPanel.phoneTakesOver`): Naghm's coming after the screen's own go up, unless they are the very same lines
  (the owner's correction goes on applying) or have no times where the shown ones do. The correction stays on disk with its lines.
* **Music keys:** "the same source" is `MusicPanel.onBluetooth`, what the screen is really on; the pref only with no screen.
* **Prefs:** gone: `setupDone`, `setupBannerDismissed`, `adbBridge`, `hiddenApps` (no version ever wrote them). Kept, read-only:
  `homeAddress`, `workAddress`: their writer went in 1.3, but a unit where one was typed still drives there from it.

## Maps with no trip (2.0: `nav/BrowseCards.kt`, `nav/browse/`, `link/PlaceInbox.kt`, `link/TripCard.kt`)

SPEC.md sections 6.3, 11 and the browse rows of 12. `BrowseCards` is what the visit does (the logic above `build()` is the old one: what was chosen, the route, Go, Home and Work, what the phone sent); the views are `browse/BrowseGroup` (pill, chips, `NoGpsCard`, `Disc`), `SearchPanel`, `PlaceCard` with `FromPhoneCard`, all in `panel.browseLayer` at the mockups' pixels, each built once and shown by visibility: between `نحسب الطريق…` and an answer nothing moves and nothing is laid out, Go stands at y 362 before the route and after. Cards come by `enter`, one layer, y -8, 180 ms, .7 of that moving.

**The pill and the chips stay in `drive-free`** (section 6.2): the field is the mic there and the chips lose their discs. Zoom, locate, compass and the credit on browse are `MapTools`' and it alone holds `map.onCameraIdle`; `BrowseCards.credit` shows only while the search is up (section 11: on both), and reads `MapStyle.attribution` when the search opens and when it flies, so the two labels never lie on each other.

**The keyboard.** The panel's own keyboard is cut from this beta, the system's comes up. `adjustResize` does nothing here (see "Soft input"): the window keeps its size and only its visible frame shrinks, so the old `addOnLayoutChangeListener` on the root never heard it and the rows ran under the keyboard, out of reach. A global layout listener reads `getWindowVisibleDisplayFrame`, only while the search is up because that is a call to the window manager, and `SearchPanel.setRoom` gives the list what lies above the keyboard, in px: part of a row under the baked fade says there is more. The search's camera takes the same number for its bottom padding. Moving: `showSoftInputOnFocus` false and the field opens voice, because a touch on an `EditText` brings the keyboard by itself whatever the focus listener decides.

**Two routes.** `RouteChain.ask` with the old request's guards: `CANCELLED` is no answer yet and asks again in `Router.afterOthers`. `RouteChoices.offer` is pure (`RouteChoicesTest`): a second route only when under 75 % of it by length runs on the first, sampled every 12 m against 14 m cells with their neighbours, so a frontage road beside the road counts as the road; each named by the longest road the other does not use. The chosen one is `route`, the other `routeAlt` under it, a tap on it chooses it (`AuraMap.onRouteAltTap`). `GuideLink` now gives the alternates their own words and warnings too (`landed` tells the card), with no limits: those walk the first route alone. A warning goes in the arrival's line with two routes, what comes before its comma and its glyph, and whole under the arrival with one. The time bubbles are cut, and `Choices.rank` is not used, so `parsed.routes` is not kept.

**On the map.** Result pins are symbols of the source `aura-results`, the first row on top, so labels dodge them; a tap on one is its row (`onResultTap`), and a tap on nothing leaves the search (`onMapTap`) with the camera it had, if the search flew it (`cameraNow`, `cameraBack`). A place tapped keeps its row (`onPlaceRow`), and `PlaceSearch.row` brings its kind, district and town. A dropped pin is `PlaceSearch.reverse`'s: the name replaces the selection and the route stays the pin's. `overview` takes left as left in both languages; the camera's room is at the left too.

**From the phone.** `Trips.goTo(holdMs = 5000)`: with its route found the attempt waits in `Phase.READY` until `startsAt`, and `startNow` or `cancel` end that early. `BrowseCards.syncAttempt` shows the card and ticks one label once a second, nothing on the fix. Paired, with AMRI in front: Maps comes forward with the card. Unpaired, a guess, another app in front, after a call or reverse: the banner asks first. On a trip: the banner, no camera change, 30 s, `بدّل الطريق`. No route: the place's own card with its retry, not the mockup's Cancel alone: a place in hand with a button that can still reach it is worth more than a dead end, and a failure on the road is often the hotspot. `setprop debug.aura.phonehold 25000` holds the count for a look.

**No GPS, browsing** (`empty-no-gps`): `NoGpsCard` under the chips after 30 s with no fix (the GPS gives one every 2 s; a gap of a fix or two is the strip's icon alone), `آخر موقع قبل 3 دقائق`, checked twice a minute, cleared by the next fix. `Units.duration` says `دقيقة`, `دقيقتين`, `5 دقائق` now.

**A theme.** `MapsPanel.recolours` is true: everything here reads its tokens in `Look.apply`, and `lookChanged` puts the route, the second route, the pins and the destination back on the new style. Tried over search, place, phone card and browse with `am broadcast -n com.abdllh.aura/.debug.GalleryReceiver --es swap day|night`.

**The window's language.** `AuraApp.getResources` answers in the app's language: at every update of the configuration, also one that no callback hears, the framework sets the process's default `Locale` from the Application's resources, and on a window whose language is not the system's the lines turned around after a while. (`attachBaseContext` could not do it: Prefs are not up there, and the choice can change.)

The debug road for Arabic, which `adb` cannot type: a UTF-8 file on the device, `am start -n com.abdllh.aura/.nav.MapsActivity --es q 'بنده'`, run with `sh`; `am broadcast -a com.abdllh.aura.debug.NAV --es phone 'lat,lon,الاسم'`, `--es fix lat,lon,bearing,kmh`. Walked on the emulator against the mockups, night, day and English once: browse, search, keyboard, no results, result to place, two routes, choosing, Go, computing, a warning (from Jeddah into the Haram), no road and no route with retry, pin and its name, a place tapped, save and the pick flow from a chip, Home chip straight to a trip, moving (mic, no discs, the strip's speed over a card), `drive-free`, phone card with its count, Cancel, Start now, the banner on a trip, no GPS.

## The map's parts together (2.0: `nav/route`, `nav/pack`, `nav/offline`, `nav/places`, `nav/guide`)
The sections below and above describe each part as it was built; this is how they meet, and what changed when testers attacked them. All measured on an x86_64 emulator; nothing here has run on the unit.

* **Who knows where the files are:** `PackPaths`, asked every time. `ManagedPack` gives the map its three files (`OfflineMaps.wirePack`; `setprop debug.aura.pack <folder>` reads a folder of `files/maps2` instead, for `scripts/map-look.ps1`), `RoutePack.tar` the tar, `PlaceSearch.locate` places.db. With a new pack (the listener compares id and date: a pack rebuilt under one id is another pack) `PackTiles` reopens, MapLibre's ambient cache is cleared and each `AuraMap` builds its style anew. `noteWorked` comes from the first real tile (`PackTiles.onServed`), route or search.
* **On since 2.0.0-beta.1, branch `v2-app`:** `Router.route` is the chain, with no race, no head start and no preference (`routeValhalla` is gone, and with it `--ez valhalla`; `Route.better` is never set and stays until the shell's move has merged). `placesEngine` and `routeGuideWords` default to on, no screen, a debug build can say off (`--ez swap false` on `debug.PLACES`, `--ez guide false` on `debug.ROUTE`). A third is the driver's and stays off: `routeAvoidHaram` (`--ez avoid_haram true`). With no pack everything falls to what 1.x had: BRouter or OSRM, `find` over Photon and the old index, so `ArabicQuery` and `PlaceIndex.search` stay for as long as `OfflineMaps` does.
* **The words on a route:** `RouteClient` asks :route for the engine's own JSON as well, when `routeGuideWords` is on. The route is delivered first, as always; `GuideLink` then reads the JSON in the main process (it needs places.db, which is open there), makes `RoadFacts` from the rows of the limits' walk (`RouteTrace.request(roads = true)`: class, use, bridge, tunnel, toll, in the tags `RouteLimits` passes over), and asks `PlaceSearch.guidance` on the search's thread for a `Guidance` over `PlacesWorld` (the search part's implementation of `RouteWorld`). It lands on `Route.guide`. Until then, and when anything fails, the old `Instructions` speak. `NavSession.prompt` and `GuidanceBanner` ask it by the same step index: both parsers read the answer's own list. `guide.warnings`, `junction`, `nextFuel`, `prayerStop`, `arrival` have no screen yet.
* **`RoadWatch` and the sign, on a route with its words:** the limit is `Limits.at(guide.route, along, MapExtras.limitAt)`, and `route.limits` by place where the facts say nothing; the camera or bump is `guide.nextAlert(along)`, by distance along the road. **`MapExtras` on top:** his limit first; his live spots keep their straight line and cone; a pack row of his (`AlongSpot.owner`, the build's snapshot) does not count once his live set is known, as he may have taken it away; a spot of the route within 40 m of the nearest of his gives way to it, and the same spot within 40 m of one of his, or of one already announced on the route before a reroute, is that one (one tone). An estimate by the road's class is not a limit: it is `RoadWatch.State.estimate`, the sign does not show it and it makes nobody `over`, until a screen marks it as one (`Words` `limit.estimated`). Off the route: as 1.x, his, then `route.limits` by place.
* **One rule of what a route goes by:** `RoadAhead`, the search part's. `Guidance` asks it (`AlongRoad.match`, `TripCheck`) instead of its own: the two disagreed on 4 of 53 trips and on signal counts. A camera 30 m (either way when it has no `dir`), a signal 15 m and only its junction once, a bump 10 m; rows of one camera within 25 m are one, with the limit of whichever knows it. A signal on a stretch the facts call a bridge or a tunnel is not counted: it stops somebody else.
* **Zones:** a route warns when it comes in from outside and ends well inside (150 m from the ring's edge, not the Haram's), or goes 100 m or more on a tertiary road or less inside. Secondary is public: King Abdul Aziz street and Madeena Street in Jubail town are secondary inside the naval base's coarse ring, and the first rule told trips in the owner's own town they enter a military area. `ZonesOnRealRoadsTest` holds it on real lines with real `trace_attributes` (`tools/mapbuild/tests/guide_zone_fixture.py`), because the golden file's fixtures have facts for only 6 of 30 and printed silence. Ithra still warns: it is well inside the air base's ring. That is the rings' fault and the owner's decision, not the road rule's. The Haram: any route that comes in; not one only driving out, not an errand inside. The causeway's box starts at 50.25 E: south Khobar's corniche is on 50.22.
* **Only Valhalla keeps a ring or a via:** `RouteRequest.needsValhalla`. With the engine down the chain checks another router's line against the rings (`RingCheck`) and answers `NOT_HONOURED` when it goes through; via points are not asked of anyone else. Before, the answer was status OK with a route through the Haram, and only `engine` differed. A ring under 3 or over 100 points is refused at once. The road switch only helps with the first metres: another router may answer, `roadIgnored` says so. `RoadClasses` takes OSM's words too and sends no filter for one it does not know. `NavNote` gets "route by brouter".
* **The Haram:** `avoidHaram` asks `PlaceSearch.haramRingBlocking(1)` on the chain's thread and `Haram.plan`: a start or a destination inside is not left out, `haramInside` says so. With no file, a box around it, wider than the ring. Jeddah-Taif 169.9 km plain, 207.7 km round it, on the real pack through the chain.
* **The guard:** a state file from the version before now belongs to this one, and a death is written with this version's code: left with the old one the next look took it for the old version's and forgot it and its rest, and two crashes 5 s apart put "keeps stopping" over the home screen. A marker from another version counts for nothing (an update ends the process in the middle of its work).
* **One worker per process:** `RouteWork`. A client binding in the second before the process ends is a second `RouteService` in it; with a worker of its own that held a second actor and a second mapping of the 378 MB tar (8 in a row: 8 GB of address space, in a process that has 3).
* **Older and newer chains:** a chain that took 8 s over OSRM abroad reached the client after a newer request and cancelled it. `RouteChain.newest` stops that. A second `Router.route` caller still cancels the first, which the old race did not, so `done` hears `Router.NO_ANSWER_YET`: no failure and no try spent. `Router.afterOthers` asks again once no chain is under way: asked again at once, two askers would end each other's wait for good on a unit where a route takes longer than the pause. A screen using `RouteChain.ask` checks `a.status == RouteAnswer.CANCELLED` for the same.
* **Lanes:** `Step.lanes` stays empty on Valhalla's routes and that is the data: on the 30 reference answers 70 intersections have lanes, none is a step's first, all say straight only, the nearest is 275 km before its next manoeuvre. They are lanes of junctions driven through, and no manoeuvre's. Valhalla's OSRM form leaves the lanes at a manoeuvre's own node out. So no strip can be made from them, and `turn_lanes` gives nothing to show on this data.
* **A bad tile:** `TileCheck` walks every tile row before MapLibre sees it: every wire type and length, each packed list, a layer's three fields, tags against the layer's keys and values, geometry commands, UTF-8. 10.3.7 reads a tile on a worker thread of the app's process, and a damaged protobuf inside good gzip throws out of it, SIGABRT, and the home screen is gone (one flipped bit was enough; a hash of the pack sees nothing, since the damage is from before the gzip). A refused tile is an empty one, logged. Over all 705,624 rows of the pack: 0 refused, 6.5 s on the PC's JVM. Online tiles are streamed and not looked at. A tile whose wires are sound and whose numbers are nonsense draws nonsense.
* **The Two Holy Mosques** have a layer of their own (`poi-holy`), placed before the words around them: by rank they lost to the district's name and to band a.
* **The pack:** going on after a pause or a trip needs no margin (`PackRules.mayStart`: 264 MB free was demanded of a job that lacked nothing). When a piece's chunks all agree and its whole hash does not, the manifest and its chunks disagree: no refetching (it cost 810 MB and then 647 MB more per try). A pack rebuilt under one id is offered and comes in place at the switch (`worthOffering`). A chunked sign-in page cannot wipe the part (`bodyFits`); a portal's JSON is no manifest (`looksLikeManifest`); SQLite's refusal at the last look is `BAD_DATA`, and its handler no longer deletes the file. **Left:** a redirect loop or a manifest lying about a chunk's bytes waits for ever under "no internet"; a staged manifest that a later app refuses leaves the staging invisible. **On an emulator:** set `debug.aura.pack.base` before the first `--ez check`: a check made without it keeps the source it found (GitHub), and `--ez start` then fetches 381 MB from there, whatever the property says by then. A new `--ez check` with the property set takes the local server.
* **The search's words:** `places_tables.py` holds known things' full names as aliases (جسر الملك فهد, مكة المكرمة, المسجد الحرام, the airports by their short names), and an alias may now be the words themselves. `دولي` is a role-2 word. `tools/mapbuild/patch_syn.py` writes table syn alone into a built file; then `make_pack.py`, and `scripts/search_compare.py fixture`. In `search_ref.py` and `Score` alike: an airport's distance counts for 30 % whenever an airport is asked for; a row only its consonants found, over 30 km off and with no word of the query in it, is dropped, as is a negative score, so "nothing found" can be said; a query is cut at 100 characters. **Left:** `البلد جدة` and `طريق الملك فهد الرياض` from far away: the candidates' cut, 150 nearest of what matches, loses them.
* **The route probe:** see its head comment. It now runs what 2.0 calls on every route (`more`), the eMMC by itself, and notes every death. For a branch without the map work, in this order: `d8ce7ad 40ea677 eba50da 944e02e a8ab77f f8d2722` (branch `v2-routeprobe`, from `main`), which cherry-pick clean on `main` and on `scout-1.19.1`, and were built and run from the latter on the emulator. The first is `v2-route`'s `61a081a` cut again: its `AuraApp` hunks stood beside lines `main` lacks and `scout-1.19.1` has rewritten, so the check now stands before `super.onCreate()` and the debug hook after `CarAudio.init`. Still 1 % of route.tar: it says whether the library loads and routes and how fast the eMMC is, not how long the first route over the whole tar takes.
* **Whole-chain test of 2026-11-04 (emulator-5572, x86_64, offline, real pack by the local server):** a search in Arabic through the search box's road, a route over Valhalla, a drive of 16.5 km with the simulator to arrival, by day and by night (`D:\k2501-work\shots\v2-mapapp-*.png`). `:route` killed in the middle of a reroute: the guard counted it, BRouter's 16,092 m came 5.8 s later with the old words, "route by brouter" in `NavNote`, the home process and the screen untouched. The pack deleted: the same chain answered from BRouter. Row by row against `search_ref.py` on the new file: 0 mismatches of ids, scores, km, names, MATCH strings in all sets, 193 of 193 of the golden. 383 unit tests. R8 release build: 25,960,185 B.
* **In the Screen check's report:** `n_route` (`RouteProbe`), `o_maps`: `routeGuard`, `routeGuardLog`, `packLastInstall`, `refusedTiles`, `tileReads` (the read counts start again with each report).

## Place search over places.db (2.0, `nav/places/`)
The app side of `tools/mapbuild/SEARCH_SPEC.md`: `search_ref.py` ported to the letter, with no screen depending on it. `MapsActivity` searches through `Places`, as in 1.19, unless the
preference below is on. Nothing here has run on the unit: every number is from the emulator (x86_64, Android 10, SQLite 3.22.0) unless it says otherwise.

* **Files:** `Norm.kt` (norm, base, skeleton, query words), `QueryPlan.kt` (table syn, the plan, the MATCH strings, other readings), `Score.kt` (the score, one place once), `PlacesDb.kt`
  (the file: checks, reads), `PlaceEngine.kt` (the stages, reverse, what guidance asks), `RoadAhead.kt` (a route's line against spots, gates and zones: plain geometry), `PlaceSearch.kt`
  (the way in: one background thread, answers on the main thread), `PlacesDebug.kt` (debug builds). Only the last four touch Android.
* **Why "to the letter":** the index in places.db was made with these functions, and the scores decide which of 12 rows show. What had to be copied from the code and not from the
  text: lower case runs before NFKD, so `´` has become a space and a mark and `℃` stays a capital C; ties in the sort go by the score after `round(s, 3)`, which needs Python's rounding
  (the exact binary value, half to even: `Score.pyRound`); `{k:.6f}` in the SQL rounds the exact value too, where `String.format` rounds the shortest decimal; `Math.toRadians` was not always
  `x * (pi / 180)`. Stage 9 reads the best-ranked rows, which the spec's text says it never does. A length counts UTF-16 units, not code points, which only a word with a letter outside
  the BMP can tell (one row in the file). Python's Unicode 14 and Android 10's differ on 178 characters of the BMP, all assigned since; no name in the file holds one.
* **The file:** `SQLiteDatabase.openDatabase`, read only, `NO_LOCALIZED_COLLATORS`, no helper, nothing written, `PRAGMA cache_size=128` (512 KB), 4 kept statements (the SQL holds the car's
  position, so they are hardly ever seen twice). Never the default `DatabaseErrorHandler`: on any report of corruption it deletes the file, and a pack is the pack manager's. Before SQLite sees the
  file its first 100 bytes are checked against its length, so a download cut short is told at once and not by the first search that reaches the missing pages. `meta.norm_version` must be
  `Norm.VERSION` (3) and `meta.schema` `PlacesDb.SCHEMA` (2), or it is refused, with the two numbers in `PlacesStatus.why`. A file that cannot be used is a status and an empty answer; `PlacesStatus.message` is the
  driver's line (`strings_search.xml`). It is looked at again only when its path, length or time changes. Where: `PlaceSearch.locate`, asked at every use, which the pack manager's `PackPaths.placesDb` is to fill (a new pack is a new path, so it is opened again by itself; `worked` is for `PackPaths.noteWorked`). Until it is set, `files/maps2/places.db`.
* **Threads:** one single-thread executor, as `Places`. A newer query increments `generation` and cancels the last `CancellationSignal`, which stops SQLite itself at its next steps, not only the
  stages between. `typing()` adds the spec's 250 ms; `search()` does not (`MapsActivity` has its own 350 ms). After 2 minutes unused the file is closed and table syn goes with it.
* **Guidance, spec first:** `spotsAlong` gives each camera and bump once, and a signal once **per junction per pass** (`spot.j`: "الإشارة الثانية" counts these, `RouteSpots.signalNumberAt`).
  On a signal or a bump `roadKmh` is the road's limit; only a camera has `cameraKmh`. How near counts: camera 30 m (it stands beside its road, found within 25 m at build), signal 15 m, bump 10 m;
  a camera or bump must go the way of its `dir` within 60 degrees, null is either way; a signal's node counts unless it is for the traffic coming against (over 135 degrees), since a node on the
  crossing road is the same junction. A camera on a main road also fires for a frontage road beside it going the same way: 85 % of such pairs are under 40 m apart, and nothing in the file tells
  them apart. Gates within 15 m; `warn` for sub 2 or 3 on a road of class 2 or more (gate-camp's checkpoint is on a secondary road, Jubail-Dammam's on a motorway).
* **Where it differs from the spec, on purpose:** the spec warns about a military or private zone when a route ends inside "or leaves the motorway and trunk roads inside", and names Jubail-Dammam as the
  public highway that must not. That road is OSM's primary for 9 km in the naval base's ring, so those words warn on it; over the 54 kept test trips with road classes (`scripts/search_compare.py zones`) they warn 11 times, once for a guarded place
  (gate-air). `ZoneHit.warn` goes by a tertiary road or less (100 m or more) or an end well inside. `offMainMetres` keeps the spec's measure. Since the pack of 2026-11-05 the rings are OSM's own line, not 24 points grown outward: the air base holds 9 POIs, not 1,289, the naval base 19, not 269.
  Road classes come from `trace_attributes` (`roadClassNumber`); without them only "ends inside" is known. The Haram warns any route that comes in (`haramRing` gives the ring for `exclude_polygons`).
* **The swap:** preference `placesEngine`, default on, no setting for it: `Places.search` and `Places.lookup` (through `PlaceSearch.lookup`, which stops nothing) ask this engine, give `Place`s at once, then Photon's rows after them when online (none that is the same place by either of a row's names within 200 m, 14 in all), and go the old way when the file cannot be used; `Places.cancelSearch` stops it. `Places.reverse` asks `PlaceSearch.reverse` and keeps the picked point, and Photon only hears what the pack cannot name. `Places.geocode` takes text, so the engine's search: its best row unless its position is coarse, else Photon, the old index, and last that coarse row.
  `am broadcast -a com.abdllh.aura.debug.PLACES --ez swap false` for the old road; `--es db <file>` for another file. The other extras are at the top of `PlacesDebug.kt`.
* **Tests:** `src/test/.../places` (run as above): the 76 vectors of `tools/mapbuild/tests/norm_vectors.json`, and `src/test/resources/places`, written by Python itself (`scripts/search_compare.py fixture`): every character of the
  Arabic blocks, 200 names, the tester's 200 query plans, 480 scores. Write it again when `search_ref.py` or table syn changes. 63 tests, no database. `scripts/search-compare.ps1 -Serial emulator-5578` runs the rest on the emulator's real SQLite through the
  broadcast: each query's ids in order, scores, distances, shown names, details, approx, every MATCH string and the candidates' count against the reference's. `-Failures` tries the files it must refuse.
  Emulator only, on the file built 2026-11-04T15:22Z: the 200-query set, the 193 of `search-golden.json`, 715 prefixes of a hundred of them, 786 finished with a space or with no position, 554 random rows by their
  own names: **0 mismatches** of any of those kinds; 627 reverses 0; 10,018 names through every text function 0. Search, warm: median 7.7 ms, p90 13.6, worst 41 (over all sets the worst was 82, a long name that went through the loose stages).
  By stage, median / worst ms: plan 0.09 / 0.7, first stages 3.4 / 35, other readings 0.01 / 12, place 0.01 / 2, single edit 0 / 9, loose 0 / 6, areas 1.0 / 8, score 1.4 / 14, one place once 0.05 / 0.8. With the kernel's file cache dropped and the file opened again, 30 queries: median 59 ms, worst
  210, the open itself (checks, cat, syn) 12.5 and 23. The emulator's disk sits on the PC's own cache and SSD, so that says nothing of the unit's eMMC. Reverse median 0.8 ms, worst 9. A route: spots up to 18 ms, warnings up to 28 ms (414 km), the nearest fuel or mosque up to 10 ms.
  PSS of the home process, 200 searches, twice: file open +0.1 MB of Java (syn); before a collection +3.5 MB of Java; after one, native moved by -0.9 and +2.3 MB in two runs, the size of the process's own noise; 128 pages of cache are at most 0.5 MB of that. On x86_64, so ARM32's pointers would make it less, not measured.
  Typing, 30 queries, 279 letters through the real entry: at 40 ms a letter 5 searches were stopped in the middle (67 on a busier run); at no pause 248 never began; the last answer was the whole query's own 30 of 30 at every speed, median about 9 ms after the last letter, worst 185 on the busy run.
  Bad files (`-Failures`), 9 kinds, the same process throughout: search empty with the reason, the search box on its old road, every file still there, the one with holes (it opens, a read meets the damage) too. On this AVD that old road has no offline store and the network was off, so it was seen answering "no internet", not rows.

## The words of the guidance (`nav/guide/`, for 2.0)
Pure logic, no Android classes, no screen: the voice and the sign ask it behind a preference ("The map's parts together"), the rest has no screen yet
(the hook is `Route.guide`). It works from the engine's answer (`GuideAnswer.parse`, `format: osrm`) and from `RouteWorld`, the small
interface the search part implements over `places.db`. Everything fails towards plainer words: a world that throws is no world
(`Guidance.build`), an answer of an unknown shape is null, and `Guidance.safely` is null on anything else, so the old
`Instructions` remain. All of it was measured on reference answers on the PC; nobody has heard or driven a word of it.

* **Tests:** `app/src/test/.../nav/guide`, 155 tests on JVM. Fixtures: the 30 reference trips cut from real answers and the pack's
  own `places.db` by `tools/mapbuild/tests/guide_fixtures.py`, 1.4 MB, 3 answers byte for byte beside them. `GoldenTest` writes all
  their words to `tools/mapbuild/tests/guide-golden.md` for a person to read, and fails when the words change and that file did not:
  run it with `GUIDE_GOLDEN_UPDATE=1`, read the diff. A wording change is a diff in that file. When a new `places.db` comes, run
  the cutter too.
* **The words are one table** (`Words.TABLE`): sign, voice, English, all from it, and the ids are the clips' names (maps.md section 12).
  A prompt is a list of `Part`s: `Clip` a word of the table, `Name` free text with the word that leads to it. `Speech.withoutNames`
  is what a clip voice with no engine says, and every sentence still stands. They are in Kotlin and not in `res/values`: the JVM tests
  must read them, and `Instructions` before it was code too. The register follows the owner's own phrases: "لف يمين", "الزم اليسار",
  "كمّل على طول", "خذ المخرج", "وجهتك قدامك". His to change; nobody has heard Google's voice read them (a shadda on "كمّل" for it).
* **Names** (`Names.pick`): Valhalla's joined list ("طريق الملك فهد; King Fahd Road") by script, then the other script, then the number.
  A `ref` is often no number ("King Abdul Aziz street, Jubail, saudi arabia", 3 ways). A Latin name's Arabic comes from the index when it
  has one (`RouteWorld.arabicRoadName`). The voice calls "الخرمة" "شارع الخرمة" and leaves out over 38 letters; the sign shows the name.
  **How often, in an Arabic UI, on the 382 told manoeuvres of the 30 trips:** Arabic name 174 (46 %), Latin only 3 (1 %: the banner shows it, the voice does not read it), a number only 30 (8 %),
  **no name at all 175 (46 %)**, of which 24 have a landmark. Before the pack's Arabic road names (`tools/mapbuild/arabic_names.py`): 152 (40 %, 5 of them by the index), 24 (6 %), 31, 175. So half the turns are still "لف يمين" and the distance: those roads have no name in OSM in any script, and no rule makes one.
* **Landmarks**, in the plan's order: a signal (`spot.j` counted, nodes of one junction once, from where the car is at that moment, so the
  second becomes "the signal" once the first is passed; past the third no ordinal), a roundabout, after a flyover or tunnel at most 150 m
  back, onto one at most 120 m ahead, and only when there is no name to read, a place within 35 m (50 for what has a yard) on the side
  of the turn. Fired on the 382: 24 at a signal, 44 roundabouts, 3 places. Turns with anything at all within 35 m on their side: 30 of 242.
  Places: a short list of kinds and chains, rank 45 or more, never a coarse position; tenants of a mall sit on the mall's point. OSM misses
  signals, so an ordinal can be off by one: the distance is always said with it when far. Flyovers need `trace_attributes`
  (`RoadFacts`): the answer has no bridge class. 15 of the 30 fixtures have facts for their very line (6 before the route tests were asked again with the same request), and on those 5 fired.
* **Roundabouts** (`Ring`): the router's number counts driveways (8 of 60 rings differ from OSM's road exits). The answer does not say which
  arm is what, but where they are: two arms under 17 m apart are never two roads. Number said only with its arms apart, at most the fourth, and
  not for a first exit that runs straight on. On all 60: said on 46, of which 2 differ from OSM (a first exit OSM calls a service road:
  still the first arm met); 6 of the 8 bad ones gone, 1 good one with them. Else the direction measured on the line, 60 to 15 m before and 15 to
  60 m after, past the splitter's flare. A check that the number fits the direction (1 right, 2 straight, 3 left) was tried and does not
  separate them: d3's third does lead left. Recounting by class needs `node.intersecting_edge` from `trace_attributes`: not built, no answer
  with it here to test on.
* **U-turn honesty:** "الدوران بعد 600 متر" as a place ahead from 300 m, then "لف وارجع من الدوران". **Ferry:** a change of `mode` is told ("اركب
  العبّارة", "انزل من العبّارة وكمّل"), its time is the timetable's. **Ramps and forks** with a straight modifier say no side, also in
  `Instructions` (19 steps said "على اليمين" with a straight arrow).
* **ETA** (`EtaTable`): route-request.json's table by each step's speed, an estimate fitted against OSRM and no real drive. One place,
  `EtaTable.parse` takes his own logged drives in the same form. Jubail-Dammam 50.0 min raw, 60.8 estimate (OSRM 59). `Choices.rank`: fastest
  by that time first, with the differences in words; the router's own first was not the fastest on Khobar corniche to Dammam corniche.
  Warning 401 (`relaxed`) is a second search: log it, nothing to say.
* **On the road** (`AlongRoad`, `Guidance.nextAlert`): a spot counts within 25 m of the line going its way, 15 m when it has no `dir`; distance
  along the road, twice on a road driven out and back. A bump's `kmh` is its road's and is never said. **The limit** (`Limits.pick`): his, then the
  road's `speed_limit`, then by class, `LimitNow.source` says which ("من الخريطة", "تقريبي"). The class table is what OSM says most by length over
  the 56 test trips; his to confirm.
* **Before a trip** (`TripCheck`): SEARCH_SPEC section 1 with one change: primary counts as a public road beside motorway and trunk, or the spec's
  own example warned on the 22-point ring (Jubail-Dammam, King Abdul Aziz street, primary). An end inside must be 150 m from the ring's edge. Since the pack of 2026-11-05 kinds 3 and 4 keep OSM's own line within about 16 m (`osm_roadside.py`): the naval base's ring 22 to 144 points and 269 POIs inside it to 19, the air base's 23 to 81 and 1,289 to 9. Over the 54 kept trips only `base-air`, which ends inside the fence, warns; Ithra and the air base's hospital went quiet with the ring. OSM's "Border Exclusion Zone" at Salwa (access=permissive), Prince Sultan's reserve, Taif's civil airport and 16 hazards with a military tag are no zone at all. A `barrier=toll_booth` outside the causeway is a checkpoint: `arabic_names.py` rewrites the 53 nodes in the PBF (or Valhalla's answer says `toll_collection` there), `osm_roadside.py` makes them gate sub 3. The Kingdom's roads have no other toll (from what is known of the Kingdom, not from a tag), and Al Shumaisi said "this road has a toll" on Jeddah-Taif. The causeway goes by place
  (`CAUSEWAY_SEA`): "طريق جسر الملك فهد" runs on land for kilometres. `Haram.plan`: the zone he goes to or comes from is not excluded
  (NoRoute otherwise) and he is told.
* **Prayer times** (`PrayerTimes`): new. The "Umm al-Qura code" the plan points to is `Amri.kt`'s Hijri date; there were no times in the app. From
  `Daylight.altitude`: Fajr 18.5, Asr Shafi'i, Maghrib -0.833, Isha 90 or 120 minutes. Within 2 min of praytimes.org's formulas worked out in
  Python on 8 days and places, which is another computation and not the official calendar: compare before trusting it to the minute.
  `Guidance.prayerStop`: during the trip, the first mosque past where the car will be by then. A stop before the time is no stop.
* **Stops** (`nextFuel`): on a motorway only the driver's own side within 300 m, else either side within 200 m, trips of 30 km and more; 13 stations
  in 359 km to Al Batha and a gap of 224 km are what the data has, not a measure of the road. **Arrival:** the answer's side (48 of 66 routes have one),
  else from the line; parking within 400 m.
* **Cost:** the largest reference answer (890 KB, Dammam-Jeddah) parses in 15 ms and `Guidance.build` over a list of rows takes 7 ms, warm on this PC. `build` belongs on a worker all the same: on an A53, with SQLite answering about 680 boxes for that trip, nobody has measured it.
* **Fast loop:** kotlin-compiler-embeddable from Gradle's cache over `nav/guide`, `Geo.kt`, `Instructions.kt`, `RouteLimits.kt`, `Daylight.kt` and a stub of
  `Router.kt`'s classes, then `JUnitCore`: 20 s against a Gradle build's minutes while reading the words. `tests/wording/run_wording.py` shows how.

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
  **Except after a power-off** (1.11.0): the airplane mode is NwdManagerService's (`services.jar`): `handleAutoSleep`
  (ignition off) `setAirplaneModeOn(true)`, `handleAutoWakeup` `setAirplaneModeOn(false)`, also `lowVoltageProtect`
  (on, then sleep); `initSettings` at boot does not touch it. A unit that powered off instead of waking ("power off
  when the car is switched off", or the hours after which a sleeping unit powers off) boots with airplane mode still
  on, so the Wi-Fi stayed off until the owner switched it on. `system/WifiOnStart.kt` (switch "Wi-Fi on with the car",
  `wifiOnStart`, on by default): once per boot (its boot id kept, so a restart of AMRI's process later in the same
  boot does nothing) 6 s after `sys.boot_completed`, and 12 s after every `SCREEN_ON` or the firmware's
  `com.nwd.ACTION_AUTO_SLEEP_WAKE_STATE`, with the screen on and ZLink not holding the Wi-Fi: airplane mode off
  (`ConnectivityManager.setAirplaneMode`, which needs NETWORK_SETTINGS, else root `cmd connectivity airplane-mode
  disable`: the setting and the broadcast, as the firmware does), then the Wi-Fi on if it did not come back
  (`setWifiEnabled`, else root `svc wifi enable`). Logged in the Wi-Fi log (`wifion`: airplane / wifi / fail).
  Emulator test: airplane mode also cuts the emulator's network, so 10.0.2.2 is unreachable: `adb reverse tcp:15555
  tcp:15555` and `setprop debug.aura.adb 127.0.0.1:15555` (the unit's own adbd is on loopback, which airplane mode
  leaves alone); `cmd connectivity airplane-mode enable`, then power key twice (wake), or delete `wifiOnStartBoot`
  from `shared_prefs/aura.xml` and start the app (boot).
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
  API, Deezer; YouTube Music's catalogue first since 1.10), matching Arabic-script names against the catalogues' Latin
  spellings by their consonants.
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
  **1.13, after the owner said the gear still showed only R and D:** read from v.26 itself (`out/gear-research/`, the
  copy extracted from `carconfig.img`; the dumps under `re/music/` are v.24), the only writers of `CarInfo.mGear` for a
  Changan are `RaiseCommonCanProtocalUtil.responseBasicInfo` — the generic Raise "basic info" frame, command 0x7D
  subtype 0x06, gear at payload +2 — and other makes' managers. The Changan managers' own parsers (oudi, Changan
  hiworld, binary, xinbas, daojun, xfeiyang, zhiqixin) never set it; the Raise Changan managers' own frames (0x20-0x54,
  0xFF) carry no gear either. So a gear comes only from a Raise box whose firmware sends 0x7D/06. AMRI's frame offsets
  (byte 74, parcel value 104) were confirmed. Raw box frames never leave the CAN app (`addCallBack4Outerface`'s list is
  never broadcast), there is no gear in any Settings key and the MCU has no gear data type. What the MCU does have:
  the handbrake wire (frame type 4 → `Settings.System mcu_hand_break`, 1 = pulled, and broadcast
  `com.nwd.action.ACTION_HAND_BREAK_STATE_CHANGE`, `extra_hand_break_state`). `Gear` uses it for P when the car stands
  — believed only after it was seen both pulled and released, and never again after it said "pulled" for 15 s while
  driving over 20 km/h (an unwired input or the factory switch that lets video play reports "pulled" for ever; kept in
  Prefs `handbrakeSeen` / `handbrakeFake`). Otherwise P is a guess drawn as a ring (`Source.GUESS`): when the unit
  comes on (screen on, process start) until the car moves, when the held D runs out, and a minute after reversing.
  Vehicle & system now also shows the CAN app's parser (`canManagerClassName`, short: "changan.raise"), whether that
  parser can ever pass a gear on, the box's own version (`can_box_version` / `can_version_info`) and the handbrake
  wire's state and use. Emulator: `am broadcast -a com.abdllh.aura.debug.GEAR --ei handbrake 0|1 --ef kmh 30`.
* What is in front: the framework writes every activity that resumes to `Settings.System activity_starting` as
  `package,class` (`NwdPackageChecker.checkResumedPackageName`); KernelService observes it (`SourceMgr$6`: `key_top_app`
  / `key_top_cls`, `ACTION_START_NWD_ACTIVITY` / `ACTION_START_ACTIVITY` with `extra_package_name` /
  `extra_class_name`). `system/Front.kt` reads it too (a state: a process that starts late still sees what came up
  before it) and keeps AMRI's own screen in front or last in front (`own`, `ownLeftAt`).
* What the firmware puts in front when the unit comes on (the owner saw "the old system" until he pressed something):
  - **After a power-on, the stock launcher — found in 1.9, and the cause of the owner's report** (he had turned on
    "power off completely when the car is switched off": every start of the car is a cold boot). Android starts the
    home screen at `systemReady`, while user 0 is still "locked" (the user is unlocked only after the first activity
    has gone idle: `finishBooting` → `maybeUnlockUser`; the unit is not encrypted, the state machine runs all the
    same). Until then the package manager finds direct-boot aware components only (`updateFlags` adds
    `MATCH_DIRECT_BOOT_AWARE` alone), and NWD's `PackageParser.parseBaseApplication` force-marks every package whose
    name contains `launcher` or `nwd` (plus `com.bt.bc03`, `com.android.utils.sendkey` and the packages listed in
    `/data/nwdappconfig/app/DirectBootAwareAppList.ini`) as direct-boot aware ("force set … directBootAware=true",
    `ro.kernelservice.directbootaware`, true unless set). So the only home candidate was
    `com.android.launcher2.Launcher`, and `chooseBestActivity` returns a single candidate before it ever reads
    `persist.nwd.launcher.default` (the property is looked at for two or more: the first entry of that package).
    Nothing asks for the home screen again after the unlock, AMRI's process was never started (no boot receiver, the
    home intent did not reach it), so `HomeGuard` did not exist to cover anything; the first home intent after that
    — a Home key, leaving any app — found AMRI. A wake-up from sleep never passes through this, which is why it
    only showed with the power-off option.
    The fix is `home/BootHome.kt`: a second home activity, `directBootAware`, in a process of its own (`:boot`, where
    `AuraApp.onCreate` returns at once: `getSharedPreferences` throws while locked), filter priority −900. Locked,
    the candidates are the stock launcher and it; the property picks AMRI's package, that is it. It is a black
    screen that waits for `ACTION_USER_UNLOCKED` (and looks every 400 ms), then sends the home intent, which now
    finds `HomeActivity` (priority 0, first of AMRI's entries), and finishes — what Android's own `FallbackHome` does.
    After 6 s unlocked with the intent still finding itself it sends the home intent limited to its package (never the
    class by name: "One way to the screens"); nothing else found by 40 s, or never unlocked for 40 s, the stock launcher
    by name (it exists in that state). The component is off in the manifest and switched on by
    the app on the unit (`BootHome.ensure`, at every start): on a stock Android a second home activity in the package
    makes the package manager drop the chosen home app ("Result set changed, dropping preferred activity"). Component
    states are written 10 s late: the first power-on after the update's first run uses it. Behind it,
    `system/BootReceiver` (BOOT_COMPLETED) starts AMRI's process whatever is in front, so `HomeGuard` covers a stock
    home screen within seconds should `BootHome` not be chosen (a unit whose component state was reset, the first boot
    after the ROM is installed). Do not disable the stock launcher instead: with no direct-boot aware home nothing
    goes idle and the boot never finishes. Emulator: `setprop debug.aura.boothome 1`, start the app, wait 15 s,
    `adb reboot`; `logcat -s AuraBootHome` ("home after 1865 ms"); there the candidates are Settings' FallbackHome
    (−1000) and it, and it wins by priority.
  - Not the stock launcher by KernelService: `SourceMgr.startApp` sends app 4 (ApplicationList.xml:
    `com.android.launcher` / `com.android.launcher2.Launcher`) as a plain home intent when `isHomePage` (package
    starts with `com.android.launcher`, class ends with `Launcher`), and `PackageManagerService.chooseBestActivity`
    answers a MAIN + HOME intent with the package in `persist.nwd.launcher.default`. `ro.nwd.start.launcher.by.fw=true`:
    for the first-boot source (−48, app 4) `resumeSource` starts nothing ("first boot source,by fw start launcher").
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
  The last three were closed in 1.7 without knowing which one the owner saw; it was none of them (the first one).
* Phone calls belong to the Bluetooth service (`com.bt.bc03`, `HFPManager`), not to the phone app
  (`com.nwd.android.phone`). It broadcasts, to anybody: `com.bt.ACTION_BT_INCOMING_CALL`, `_INCOMING_NUMBER`
  (`extra_phone_number`, `extra_phone_name` when its synced phone book has the number), `_INCOMING_NAME`; the same
  three with `OUTGOING`; `_BEGIN_CALL_ONLINE`; `_END_CALL`; and keeps `Settings.System key_bt_state` (3 ringing, 4
  dialling, 5 talking) and `key_call_num`. `BTFeature` (binder): `answerCall` 14, `cancelCall` 15 (declines a ringing
  call, ends any other), `changeSoundChannel` 28, `getCallState` 52, `setMute` 58 (microphone); or broadcast
  `com.nwd.bluetooth.hfp.operation`, int `extra_hfp_operation` 2 answer / 3 hang up. KernelService turns the broadcasts
  into the MCU's phone status (`MCUDeviceManager$5` → `BtProtocalUtil.requestSendBtStatus`, `bt_phone_status`), the
  setting service into the backlight and `AudioManager.setMode`, and volume changes go to parameter 16 while
  `key_bt_state` ≥ 3: none of it depends on a window.
  - The stock call window: the phone app's `BtBroadcastReceiver` service answers `_INCOMING_NUMBER` with broadcast
    `com.nwd.android.phone.createFloat` (`pagename` "phonedialog_horizontal", `bt_state`, `bt_call_number`,
    `bt_call_name`), and its `com.nwd.android.phone.FloatBar` service draws the dialog in one of the firmware's top
    window layers (`ro.nwd.first.window` 2401 + 1), above anything an app can add; the same window serves a dialled
    and an answered call. `phone/Calls.kt` disables that one component (root `pm disable`, like the stock volume bar;
    not during a call: it restarts the phone app's process) and `phone/CallBanner.kt` shows AMRI's card instead: who,
    decline and answer, then the time and hang up. The card shows only while the stock component is really off —
    never the two together. The phone app's own state keeps running (`mIsShow` is set after the broadcast whether
    anybody drew or not), so the panel's answer / hang-up keys it handles still work. The switch going off, the kill
    switch (also when it is set while AMRI runs: that is the ROM's restore at work, and an older pack's restore does
    not know this component) and the restore itself enable it again.
  - The ring is the service's too (`PlayerService`, on `_INCOMING_CALL`): a `MediaPlayer` looping a file on
    `STREAM_VOICE_CALL` at full stream volume (the loudness is the MCU's phone-call volume), stopped by
    `_BEGIN_CALL_ONLINE` / `_END_CALL`; no setting turns it off. The file: the last that exists of
    `/data/ringtones/ring.ogg`, `/system/ring.mp3` (there: 19 s, peaks at −9.6 dBFS), `/mtc/ring.mp3` — unless
    `ro.nwd.internal.path` + `/ring.mp3` exists (`/mnt/media_rw/sdcard`, which init links to the primary storage:
    a `ring.mp3` in the root of the internal storage), looked for at every ring. `phone/Ringtone.kt` puts AMRI's ring
    there as root (`res/raw/aura_ring.mp3`, made by `design/sound/make_ring.py`: since 1.13 a bright marimba figure
    in A major at 116 bpm, the owner asked for a ring like the iPhone's — composed here, no other ringtone's melody;
    modal marimba bars (×1, ×3.98, ×9.86 partials), a mallet tick, roots below; 4.66 s ending on a faded rest,
    −6.5 dBFS peak, −19.7 dBFS RMS), with its MD5 in `.aura-ring` beside it; an owner's own `ring.mp3` is kept as
    `ring.mp3.pre-aura` and comes back. A second player from AMRI would only sound over the firmware's. A changed
    file reaches the unit by itself: `Ringtone.apply` compares the MD5 at every start.
  - Emulator: `am broadcast -a com.abdllh.aura.debug.CALL --es state ring|talk|end --es number … --es name …`.
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

## Arabic font
Until 2.0 Arabic UI text was Readex Pro (OFL), a variable font; the UI is IBM Plex Sans Arabic now ("Design system (2.0)"), whose metrics need no patch. Upstream Readex declares ascent 1000 / descent 250 while the Arabic reaches about −550…1150, and the
app's labels use `includeFontPadding = false`, so Android cut the bottom off (ي lost its dots): `design/fonts/make_arabic_font.py` wrote the app's copy with ascent 1150 / descent 560, renamed "Readex Pro AMRI" as the OFL asks. The file and the script left the repository with 2.0 (git's history has them): the map pack's glyphs, the last thing that could have read them, are Plex as well.

## Logo and icon (`design/logo/`)
`amri-os-en-source.png` / `amri-ar-source.png` are the owner's artwork (white and blue on black). `make_assets.py` takes
them off the black (alpha = brightest channel), writes the transparent logos, the app's `drawable-nodpi/logo_amri_*`
(white for the dark theme, `_ink` for the light one) and traces the "A" with its blue dot for the vector launcher icon
(`ic_launcher_foreground.xml`, from `a_glyph.txt`).

## The route probe in the Screen check (1.19.1)

The last step of the Screen check runs `nav/route/RouteProbe` in `:route`: does Valhalla run on the unit's ARM32 userspace at all, how long a route takes, how much memory it holds, and whether the kernel runs a static AArch64 program. It runs last because it may die a few times on its way; its deaths are reported, never felt by Home. The whole JSON goes in the report as `n_route`, one line of it on the page's summary. A cancel leaves it to end by itself. It adds 7.5 MB to the APK, of which 6 MB is the library 2.0 ships anyway.
