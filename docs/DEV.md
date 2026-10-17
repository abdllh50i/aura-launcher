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
the `MediaCard`, the Maps screen's guidance sign in its compact form while navigating), the `Dock` and two bottom sheets (`ControlsSheet`, `AppDrawer`,
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
  The dock has a 1 dp `divider` on its top edge instead of a full-width shadow; its app tiles are flat. The window
  background is the only full-screen fill (`ui/BackdropView`, a second fill of the same colour, is gone from home
  and Settings, and deleted).
* **Entrance.** Everything but the car settles in about half a second (it was ~1 s): header at 0 ms, the map row's
  three parts at 80/110/140, the player at 140, the quick buttons at 160, each `View.enter`. The map panel only moves
  (16 dp, `LONG`): it holds the live map's surface, which a fade would draw through a layer on every frame. The dock
  rises with its tiles 25 ms apart, translation only. The car's spin is 1400 ms (was 1900) from 100 ms.
* **Start-up.** Nothing heavy competes with the entrance any more:
  * The live map is built by `MapPanel.startLive()`, 700 ms after the entrance starts (at once after a restyle).
    `MapPanel` keeps the activity's lifecycle (created/started/resumed) and replays it on the map when it is built.
    Until then, and until the map has drawn, its last picture shows (see Aura Maps, "The home map's start").
  * The CPU path's decoding threads run at default priority (they were above the UI thread). `CarFrames.warmUp`
    still decodes the whole intro spin at every start: even with the GPU's frame file built, loading it takes longer
    than the spin (1.2–1.5 s on the emulator, after the window shows), so the CPU draws most of it; skipping the
    warm-up (a 1.12 test build did) showed the floor light alone, then the car popping in already turned.
  * `CarStage.playIntro` never starts the spin without a frame: on the CPU path it waits for the spin's first frame or
    the GPU (`introReady`, from the frame listener, the GL `onReady` and attach), at most 400 ms, its delay still
    counted from the call. A touch during the wait shows the car at once.
  * The app drawer's cells are built and their icons loaded 2.5 s after the entrance (`AppDrawer.prewarm`).
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
* **Sheets.** An app opened from the drawer, or a screen opened from a Controls tile, opens over the sheet; the sheet
  closes in `HomeActivity.onStop` (closing it at the same time made the sheet, the window animation and the app's start
  compete). A drawer under a translucent window closes after 1 s once the window lost focus; the Controls sheet stays
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
* **Small things.** A tap on the map shows the press tint (a drawable state; nothing moves the TextureView). The gear's
  letters take their lit colour by how much of the sliding circle covers them, frame by frame, with one weight, so a
  shift only redraws. Both sheets' close buttons are 56 dp.
* Checking it: force-stop and start `.home.HomeActivity` for the entrance; pin six apps (long-press in the drawer) and
  switch "names under icons" for the dock's width; the Bluetooth debug broadcast twice with different titles, after
  tapping next, for the song change.

## Design system (1.12)
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

**The car marker** (`CarMarker`): browsing, a disc with a heading arrow; while a route is driven (`setRouteAhead`) a
chevron (34 × 28 dp) in the route's colour with a white edge and a contact shadow, laid on the road by `rotationX` =
0.85 × the camera's tilt (a transform only), crossfading disc to chevron on a `Spring`. With no fix for 5 s
(`STALE_MS`) it turns grey and 60 % opaque; the fade is in its paints, not the view's `alpha` (its shapes overlap, so a
view alpha would draw it through an offscreen layer on every frame). `placeOverlays()` writes translation, rotation and
tilt only when they changed (0.5 px / 0.5°). Callers still pass bearing 0 for a fix without one; below 6 km/h the
last heading is kept anyway.

**The style** (`MapStyle`):
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

**The home map's start** (`MapPanel`, `AuraMap.onDrawn` / `pictureFile`, `MapBackdropView`):
* `MapPanel.startLive()` builds the live map 700 ms after the home screen's entrance starts (at once after a restyle),
  and replays the activity's lifecycle on it (see "Home screen in 1.12").
* `AuraMap.onDrawn` fires when the map has drawn with its style loaded (`onDidFinishRenderingFrame(fully)`), or 2.5 s
  after its first frame (offline, tiles that never come); `MapGuard.ok` still comes on the first frame. It replaced
  `onFirstFrame`, which fired on the first frame of land colour before any tile.
* The home map keeps a picture of itself (`pictureFile`, `cacheDir/home-map-{dark|light}-{lang}.webp`, WebP 80),
  taken with `MapboxMap.snapshot` 3 s after the map came to rest, at most once a minute, and only of the view the next
  start opens on (`pictureWorthy()`: FOLLOW, no route, no destination; a picture taken at a red light while navigating
  cross-faded into the north-up start as a double exposure).
* `MapBackdropView(ctx, picture, grid)` lies over the live map with that picture until `onDrawn`, then fades out over
  `Motion.AMBIENT`; the car is usually where it was parked, so the screen opens on the real streets. With no picture
  yet (the first start for a theme and language, or `cacheDir` cleared) it is the plain land colour: the invented
  street grid ghosted over the real streets during the fade. The grid (`grid = true`) is only for the `MapGuard`
  fallback, in the style's own colours (`MapStyle.tones()`).
* The backdrop is not shown again at `onStop`: the TextureView keeps its last frame across a stop (going home from
  Maps, the map is there at once). Once the map has drawn, the panel's own `mapLand` fill is dropped (it sat under an
  opaque map on every frame). `Palette.mapLand` is the only map colour left in `Palette`; the others are the style's.
* The Maps screen's window background is the map's land (no root background over it): the screen is all map, and
  the two fills were drawn under every frame.

**MapExtras**: `spots`, `published`, `mine`, `speeds`, `grid` and `updatedAt` are written on the main thread only (the io
thread parses and posts; a fetch that finished while a camera was being marked could put the old set back). `version`
counts every change; `geoJson(arabic)` is cached per (version, language) and `AuraMap.pushExtras` sends a set only when
that map does not show it yet. Until 1.11 the whole FeatureCollection was rebuilt and parsed again at every start of
the home screen and of Maps.

### Guidance, panels and words (1.12: `GuidanceBanner`, `MapsActivity`, `Instructions`, `Router`, `NavSession`)
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

**Theme, accent and language while Maps is open**: `MapsActivity` remembers what it was built in and checks it in
`onResume` and on every minute's tick (and time, time zone and locale changes) while resumed: a sunset under "auto" or
a change in Settings recreates it. A new language at once; a theme or accent not while a place is chosen, the search
is open or a route is being found (the next tick tries again). A trip being driven goes on (`NavSession` is the
app's). There is no snapshot cross-fade on this recreate yet (it would need HomeActivity's PixelCopy fade moved into a
shared helper in `ui/`).

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
The lyrics alone on the whole screen (1.7): the button in the panel's corner (`MusicActivity.showFull`; Back, or the
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
  look is made on the main thread, in `buildUi`. Nothing else may set this window's background.
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
  (`setTextIfChanged`, private to `MusicActivity.kt`): a WRAP label asked the whole screen for a layout every tick.
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
* **Theme and language while open**: Music lives in its own task, so it came back from Settings in the old language and
  stayed on the day colours all night under "auto". `checkLook` (in `onResume`, and from a `TIME_TICK` receiver while
  resumed) calls `recreate()` when the theme, accent or language it was built in changed; no snapshot crossfade yet.
  `MusicService` wraps its context with `LocaleHelper`, and YouTube's "stopped out of sight" toast a wrapped context.

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

## Settings, the call card and the overlays (1.12)
Every option and its firmware integration is as in 1.11; what changed is how the screens look and move. New strings of
this area go in `strings_settings.xml` (both languages).

**Settings is no longer recreated.** Until 1.11 `rebuild()` was `recreate()`: picking a navigation app, a microphone,
teaching an offline command or switching Beta flashed the bare window and threw the page back to its top.
* `SettingsActivity.rebuild()` builds the current page again in place, where it was scrolled to (posted, after the tap
  that asked for it). Use it when a preference changes what the page shows.
* `SettingsActivity.restyle()` (no arguments) is only for the theme, the accent and the language. It takes a picture of
  the screen (`PixelCopy`, 600 ms at most), stores the waiting choice, builds the whole screen again with the new
  palette (the language: `recreate()`, the picture handed over in a companion field) and fades the picture out on top
  over `Motion.LONG`; page and scroll stay. It does nothing when nothing waits and the theme shows as it is. The accent
  needs it, since every drawable reads `Palette` when it is built.
* A choice is kept at the tap: the theme in Prefs, the accent and the language in `pendingAccent` / `pendingLang` (not
  Prefs: the accent there would change `Palette` before the picture). The capture's answer stores them before it checks
  that the screen is still there, `onDestroy` stores them when the screen goes first, `onRestart` restyles when they
  still wait, and a `restyle()` during a capture leaves them to it: a pick shown as chosen is never lost. A choice waits
  `Motion.MEDIUM` (`Later` in `Pages.kt`) so the indicator or tick is seen landing first; a newer one replaces it. Taps
  on what is already chosen do nothing (`ui.Segmented`, `Swatches`, a ticked navigation row, the microphone menu).
* `onRestart` (back from Maps, Android's settings, the Play Store): a theme or accent changed elsewhere builds the
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
`BackdropView`: the window background is the screen's colour already.

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

Test hooks: `SettingsActivity --ei page N --es theme dark|light --es lang ar|en`; `am broadcast -a
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
    After 6 s unlocked with the intent still finding itself it starts `HomeActivity` by name; never unlocked for 40 s,
    the stock launcher by name (it exists in that state). The component is off in the manifest and switched on by
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
