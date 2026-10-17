## Settings, the call card and the overlays (1.12)
Notes for DEV.md from the settings area's part of the 1.12 redesign: the Settings screen, the call card
(`phone/CallBanner.kt`), the volume display (`system/VolumeHud.kt`), the assistant's pill (`voice/AmriOverlay.kt`) and
the enrolment card (`voice/EnrollDialog.kt`). Every option and its firmware integration is unchanged; what changed is
how the screens look and move, and the fixes below.

**Settings: the screen is no longer recreated.** Until 1.11 `rebuild()` was `recreate()`: picking a navigation app,
a microphone, teaching an offline command or switching Beta flashed the bare window and threw the page back to its
top, and a tap on the language or accent already chosen did the same. Now:
* `SettingsActivity.rebuild()` builds the current page again in place, where it was scrolled to (after the tap that
  asked for it has finished: it is posted). Use it when a preference changes what the page shows.
* `SettingsActivity.restyle()` is only for the theme, the accent and the language. It takes a picture of the screen
  (`PixelCopy`, 600 ms at most, null when it fails), stores the waiting choice, builds the whole screen again with
  the new palette (the language: `recreate()`, the picture handed over in a companion field) and fades the picture
  out on top over `Motion.LONG`. Page and scroll stay. It does nothing when nothing waits and the theme shows as it
  is. The accent needs this and not `rebuild()`, since every drawable reads `Palette` when it is built.
* A choice is kept at the tap: the theme in Prefs, the accent and the language in `pendingAccent` / `pendingLang`
  (not Prefs: the accent there would change `Palette` before the picture is taken). The capture's answer stores them
  before it checks whether the screen is still there, `onDestroy` stores them when the screen goes first (Back or
  Home within the wait), `onRestart` restyles when they still wait, and a `restyle()` while a capture is under way
  leaves them to that capture. So a pick shown as chosen is never lost.
* A choice of theme, accent or language waits `Motion.MEDIUM` (`Later` in `Pages.kt`) so the indicator or the tick is
  seen landing first; a newer choice replaces the waiting one. Choosing the theme restyles only when what shows
  changes ("auto" by day on the light theme changes nothing).
* Taps on what is already chosen do nothing: `ui.Segmented` and `Swatches` ignore them, a ticked navigation row
  ignores them, the microphone menu skips its current input.
* `onRestart` (back from Maps, Android's settings, the Play Store): a theme or accent changed elsewhere builds the
  screen again; otherwise the other pages are dropped and the current one rebuilt.

**Settings: pages are built once.** `built[]` keeps each page's `ScrollView` while the screen is up, so a page comes
back instantly and scrolled where it was. `Page.onShow()` / `onHide()` still attach and detach listeners
(UpdateManager, the Wi-Fi log, the offline map, MapExtras): a cached page holds no listener while it is hidden, and
`onHide()` must stay safe to call twice (it is called again from `onDestroy`). `NavigationPage` also keeps the "my
cameras" count current from a MapExtras listener while it shows.
* A kept page that shows a level or a state from outside must set it again in `onShow()`: `DisplaySoundPage` sets its
  brightness and volume sliders there (not through `onChange`, nothing is sent), since the volume changes from the
  wheel buttons meanwhile and a drag moves from the value shown (a stale 15 over a real 5 was a loud jump).
* `Page.keep = false` drops a page when it is left, so it is built again at every visit: `VehiclePage` (the gear's
  source, what the CAN box sent) and `AboutPage` (the notes from the unit, the last crash) are lines read at build.

**Settings: motion.**
* The sidebar's selection is one `accentSoft` view behind the items, moved by a `Spring` (520 / 0.86) to the chosen
  item's top; the items take the indicator's colours by how much of each it covers, and only items whose share
  changed are re-tinted. Items are a ghost press tint plus `PRESS_SURFACE`.
* A page switch: the old page leaves on `exit` (`XSHORT`, 8 dp towards the side of the list it was on), the new one
  and the title `enter` 40 ms later from 16 / 8 dp on the other side. Both run through a layer (the foundation's
  helpers). A page coming back while it was leaving turns round.
* The content scrolls under 24 dp gradients in `Palette.bg` at its top and bottom, shown in proportion to how much is
  beyond them (`edges()`), in place of a ScrollView fading edge, which would draw through an offscreen layer on every
  frame of a scroll.
* There is no `BackdropView` any more: the window background is the screen's colour already.

**Settings: rows (`Rows.kt`).** The rows of a section sit in one card, `group(vararg rows: View?)`, with hairlines in
`Palette.stroke` from where the row's title starts (`SettingRow.textStart`); the card clips to its corners so a
pressed first or last row tints the corner, not past it. A null row is left out (an option this unit lacks). The
rhythm lives in the builders, not in each call: `addSection(title)` (32 dp above, nothing at the top of a page),
`addRow(card)` (8 dp under a title, 12 dp under another card), `addHint(text)` (8 dp under its card). Other parts:
* `settingRow(icon, title, subtitle, trailing, iconTint, onClick)`: 72 dp minimum, 26 dp icon, `LABEL` title,
  `FOOTNOTE` subtitle. A tappable row shows the ghost press tint over its whole width and does not sink: a row
  shrinking inside its card opens gaps at the card's edges and tears the hairlines. A trailing `AText` is held to
  300 dp and shortened in its middle (`valueText`), so it never squeezes the title.
* `switchRow`: the whole row is the switch's target; the switch is not clickable itself, so the row passes its
  pressed state down and the thumb stretches whether the finger is on the switch or on the words.
* `block(icon, title, subtitle, body, live)`: a row with a control under its head (theme, swatches, sensitivity, the
  offline map, map additions); `live` is a subtitle the caller keeps changing.
* `sliderRow(title, slider, value)`: the slider carries the icon (no second icon in the head); the value uses tabular
  figures. The volume shows the car's own step number (the one the dock and the wheel buttons show), not a percentage.
* About: a value longer than 28 characters (the notes from the unit) goes under the title as a three-line subtitle.

**Settings: components.** `settings.Segmented` is gone; theme, language and sensitivity use `ui.Segmented` (equal
widths, `card2` track, `Touch.MIN` tall). `Swatches` are 56 dp targets whose ring is drawn round the colour (inset
5 dp), not inside it, with a tick in `onColor`; a pick repaints the two dots concerned. The Internet, offline-map and
map-additions buttons are `ABtn`s (map additions: disabled plus a `Spinner` while it fetches). `InputDialog` and the
enrolment card are `Modal`s.
* `ProgressBar` (`Widgets.kt`): a value glides on a spring that keeps its speed when the next report comes first; a
  bar made for a value starts at it. The indeterminate sweep and the `Spinner` run only while visible
  (`onVisibilityAggregated`): on Android 10 an animator does not stop for a stopped activity.
* Update page: during a download only the bar and its line change at each report (until 1.11 the card was rebuilt
  every 400 ms: the bar grew from empty 2.5 times a second and Cancel was a new button under the finger). The release
  notes show their first 8 lines, with "Show all" when there is more, instead of a small box that scrolled inside the
  scrolling page. New string: `upd_notes_more` (in `strings_settings.xml`).
* The microphone row opens a `Menu` of the inputs (the one in use ticked) instead of moving to the next one per tap.
* Map additions counts only what was published; the cameras marked from this car are on their own row.

**The call card.**
* Hang up never slides under the finger that has just pressed Answer. Once answered (here or on the phone), Answer
  fades (`XSHORT`) and its place stays empty; a second or more into the call it closes, Hang up moving into it by
  `LayoutMove` (`MEDIUM`). A tap on Hang up within `GUARD_MS` (1 s) of the Answer tap or of that move is ignored. If
  the phone does not take the answer (`Calls.answering` times out), Answer comes back. A call the owner dialled has
  no Answer place from the start.
* The window is the card plus 12 dp at the sides, 18 dp below (the shadow) and 16 dp above (the entrance, at the
  screen's top edge where there is nothing to tap); 1.11's margins left a dead band over the map for the whole call,
  since a touch on a window's transparent edge reaches nothing.
* `CallBanner.bottom()` (px from the screen's top, 0 with no card) is for the volume display.
* Surface `Shapes.overlay()` with `lift(Elev.OVERLAY, overMap = true)`, type roles, `accentFg` on `accentSoft` for the
  avatar. The ring round Answer is a view without overlapping rendering (no offscreen pass per frame).

**The overlays' motion (call card, volume display, assistant's pill).** One pattern for the three: `enter` on
`EMPHASIZED` (`MEDIUM`, from 14–16 dp and 0.96), `exit` on `EXIT` (`SHORT`, 10 dp). Each keeps a `leaving` flag:
an update while it is up lets the entrance play on (until 1.12 every report, and every repeat of a held wheel button,
cut it to its end), and an update while it is leaving turns it round from where it is (`enter` on a view that is
exiting does that; the cancelled exit does not run its removal).

**The volume display.** Under the call card while there is one (`top()`; `VolumeHud.reposition()` is called by the
card when it comes and goes, and moves an open display with one `updateViewLayout`). The level glides on a spring
through the repeats of a held button; `Shapes.overlay()`, `accentFg` icon on `accentSoft`, the step number in
`HEADLINE` with tabular figures.

**The assistant's pill.** A fixed width (560 dp, or the screen less 32 dp): a pill that grew with every partial
transcript resized its window several times a second. Title `TITLE` up to two lines, ellipsized. The microphone's
breath is a spring (380 / 0.6) fed by the level; for the answer it settles back to its size (it could stay swollen
at 1.09). A new mode's icon comes in (`enter` from 0.7). The pill is rebuilt when the accent changes too.

**The enrolment card.** A `Modal` (Cancel and the end after the last take close it with the Modal's exit; the
dismiss listener still ends the recording). The level meter fills from the start side (the right in Arabic) on a
spring, and a taken recording's dot pops in.

**Strings.** Arabic lines that end a bracket or a clause with a Latin name isolate the name (`⁨…⁩`):
`set_bt_cover_sub`, `yt_no_app`, `voice_get_google_sub`, `voice_get_tts_sub`, `maps_voice_missing`; the first two are
reworded so no bracket ends in Latin. New strings of this area go in `strings_settings.xml` (both languages).

**Test hooks** (unchanged): `SettingsActivity --ei page N --es theme dark|light --es lang ar|en`; call card
`am broadcast -a com.abdllh.aura.debug.CALL --es state ring|talk|end …`; the volume display
`am broadcast -a com.abdllh.aura.debug.VOLUME_KEY --es key up|down|mute`; the pill
`am broadcast -a com.abdllh.aura.debug.AMRI --es say "…"`. The overlays need the overlay permission
(`appops set com.abdllh.aura SYSTEM_ALERT_WINDOW allow` on the emulator).
