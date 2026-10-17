# Home screen in 1.12 (notes for DEV.md)

What changed on the home screen (`home/`, `ui/CarStage.kt`, `ui/CarGl.kt`, `ui/CarFrames.kt`) and what a maintainer
must know about it. It follows the "Design system (1.12)" rules: type roles, `Radius`/`Space`/`Elev`/`Touch` tokens,
`Motion` helpers, `accentFg` for accent text and icons, `Snack` through `HomeHost.toast`.

**Layout.** The map panel has one row along its top: "Where to?" (a 56 dp pill) followed by Home and Work as 56 dp
round glass buttons (icon only; the name is the content description; the icon is `accentFg` when the place is set,
`text3` when not). The player spans the panel's width along its bottom (until 1.11 it was capped at 460 dp and the
song's name got ~150 dp; now ~290 dp), 88 dp high. The street's limit (`RoadHud`) and "a camera here" (`CameraMark`)
share one column at the top end, so the camera button moves up when there is no sign. Together that leaves about
300 dp of map visible between the row and the player, 30 % more than 1.11.
The dock is a row: controls button, the tiles centred in what is left between it and the volume, then volume and
settings. Tiles have a fixed width (78 dp, 76 with names), so the most there can be (`DockPins.MAX` pins plus Apps,
594 dp) always fits; centred on the whole bar, as until 1.11, five or more ran under the volume capsule. The clock
(Roboto Light, `DISPLAY_L`) and the controls button share a 20 dp start edge; the settings button lines up with the
map panel's 12 dp end margin.

**Shadows.** None on the dark theme (`lift()`), where they could not be seen and were redrawn with every map frame.
On the light theme: map panel `Elev.REST` plus a `stroke` hairline (its land is close to the background), the row's
pills `REST`, the player `FLOAT`. The dock has a 1 dp `divider` line on its top edge instead of a full-width shadow,
and its app tiles are flat (their coloured shadows are gone). The Apps tile is a deliberate dark neutral (ink on the
light theme), not the slate grey that read as disabled. The window background is the only full-screen fill:
`BackdropView` is no longer added on home (Settings still adds it; once it stops, the class can go).

**Entrance.** Everything but the car is settled in about half a second (it was ~1 s, with the player and the quick
buttons out of reach until then): header at 0 ms, the map row's three parts at 80/110/140, the player at 140, the
quick buttons at 160, each `View.enter` (one layer per card, 280 ms, `EMPHASIZED`). The map panel only moves (16 dp,
`LONG`): it holds the live map's surface, which a fade would draw through a layer on every frame. The dock rises
with its tiles staggered 25 ms apart, translation only. The car's spin is 1400 ms (was 1900) from 100 ms.

**Start-up.** Three things no longer compete with the entrance:
* The live map is built by `MapPanel.startLive()`, 700 ms after the entrance starts (at once after a restyle).
  `MapPanel` keeps the activity's lifecycle (created/started/resumed) and replays it on the map when it is built.
  The placeholder (`MapBackdropView`) shows until then, as it did until the map's first frame.
* `CarFrames.warmUp` (the CPU's frames) runs only when the GPU's frame file for the theme has not been built before:
  `CarEtc.builtBefore()` checks the file name kept in Prefs (`carEtc_d`, `carEtc_l`) when a build succeeded. The CPU
  path's decoding thread runs at default priority (it was above the UI thread).
* The app drawer's cells are built and their icons loaded 2.5 s after the entrance (`AppDrawer.prewarm`).

**The car's background.** `CarEtc` no longer has its own copies of the theme backgrounds: `CarStage` passes
`Palette.bg` to `CarGlView`, the frame file's hash includes it, so a new background colour rebuilds the file on its
own. The car must stand directly on `Palette.bg`, never on a card (said in `CarPanel` and `CarStage`). The GL path
looks its uniform locations up once, sets the constant uniforms once per context and refills the quad only when the
view's size changes.

**Returning home.** The dock is rebuilt only when what it shows changed (pins, "names under icons"):
`Dock.refresh()` compares a key and otherwise only syncs the volume and the update badge. Until 1.11 every return
asked the package manager about every pin and laid the dock out again in the frames of the window animation. A
pinned app installed or removed anywhere reaches `Dock.rebuild()` through a package receiver registered for the
activity's whole life (updates, `EXTRA_REPLACING`, are ignored).

**Sheets.**
* An app opened from the drawer, or a screen opened from a Controls tile, opens over the sheet; the sheet closes in
  `HomeActivity.onStop` (closing it at the same time made the sheet, the window animation and the app's start
  compete). A drawer under a translucent window (home not stopped) closes after 1 s when the window has lost focus.
  The Controls sheet stays open under a translucent panel (the Wi-Fi panel), which brings the driver back to it.
* The drawer is INVISIBLE when closed, not GONE, so its laid-out grid is kept between opens; it overrides
  `setVisibility` for that. Its icons are loaded at the size they are shown (68 dp; they were 136 px). On open the
  grid rises once as one layer (12 dp, 60 ms after the sheet) instead of a fade per cell, and an unchanged list is not
  bound again.
* The drawer leaves out AMRI itself and names Aura's own Maps and Music in the UI language (the list is loaded with
  the locale-wrapped application context; `AppRepo.clear()` on a language change).
* Brightness: the Controls slider writes the setting at most every 50 ms and its last value on release (it wrote on
  every 1 % step). `Controls.setBrightnessPercent` still writes the brightness mode with every value; writing it only
  when it is not manual yet would halve the provider calls (that file is not the home screen's).
* Volume is the car's own step number everywhere on home (dock and Controls), as the steering-wheel keys move it.
* Controls tiles on the light theme have a `stroke` hairline on the white sheet.

**Player.** The play button is an accent disc with `onColor(accent)` (the white disc was the brightest thing on the
night screen). Previous and next are dimmed (0.35) and disabled while nothing plays. A new song's names come in from
the side of the button pressed (next from the right, in every language, like the transport row) within 3 s of the
press, else rising. The movement follows the title only: Bluetooth sends the title, the artist and the cover
separately. A new song without a cover keeps the old cover dimmed for up to 1.5 s instead of dropping to the note
in between. App names for the source line are cached.

**Small things.** A tap on the map shows the press tint (a drawable state, nothing moves the TextureView). The gear's
letters take their lit colour by how much of the sliding circle covers them, frame by frame, and keep one weight, so
a shift only redraws. Both sheets' close buttons are 56 dp.

**Checking it.** The entrance: force-stop and start `.home.HomeActivity`. The dock's width: pin six apps
(long-press in the drawer) and switch "names under icons". The song change: the Bluetooth debug broadcast
(`com.bt.ACTION_AVRCP_MUSIC_ID3 --ez sim true ...`) twice with different titles, after tapping next.
