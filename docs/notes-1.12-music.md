# Aura Music in 1.12 (for DEV.md, "Aura Music")

What changed on the music screen in 1.12, and what a maintainer must know before touching it. The screen now uses the
design system's tokens throughout (`Type` roles, `Radius`, `Space`, `Touch`, `Elev`, the `Motion` helpers, `Snack`).

**The backdrop is the window's background** (`music/Backdrop.kt`). It used to be four full-screen layers (the window's
colour, a gradient, the cover scaled up from 18 px, a translucent veil) filled on every frame the screen drew; on the
unit's GPU that was several milliseconds a frame before any content, and the 18-texel cover stretched to 600 px showed
creases, not a blur. `Backdrop.look` now works the same layers out once per cover, per texel, into one opaque picture
48 texels across (the screen's shape), the cover halved step by step and blurred for real (three box passes of 2
texels), and `applyLook` hands it to `window.setBackgroundDrawable` with filtering on: one opaque fill a frame, two
while a new one crossfades in (`TransitionDrawable`, `Motion.AMBIENT`, then the plain picture). It runs on its own
single-thread executor (`lookAsync`); only the very first look (no cover yet, nothing to fade from) is made on the
main thread, in `buildUi`. There are no backdrop views any more: nothing else may set this window's background.

**The cover's colour** comes from `Backdrop.colours` as two values: `fill` (progress, play buttons, indicators, the
equaliser bars, the shadow's glow) brought to 3:1 on the background, and `fillText` (the playing row's title, Play all,
the crumb's chevron, shuffle / repeat when on, the lyrics hint) brought to 4.5:1 on the playing row's tint. A dark blue
cover was 1.8:1 on the dark theme and a yellow one 1.3:1 on the light theme. Grey covers keep the owner's accent
(`Palette.accent` / `accentFg`). `accentTo` moves every coloured part to the new colours together in one animator
(`Motion.MEDIUM`), recolouring kept drawables in place (`Tint`): no new drawables a frame, and the list is told only at
the end (`repaintRows`).

**A new cover** comes in by direction (`swapCover`): two ImageViews inside `artBox` (`art`, and `artOld` under it). A
skip press (`transport`) records its direction for `SKIP_MEMORY_MS`; the new cover comes 28 dp from that side on
`EMPHASIZED` while the old one leaves half that way the other side on `EXIT`. Previous / next sit left to right in every
language, so the direction is not mirrored. The unit's own player going on by itself counts as forward; the phone or
YouTube changing the song without a press here gives 0 (the new cover settles in place from 104 %); a better copy of
the same song's cover is a plain crossfade. `artBox`'s own transform is never touched (LayoutMove and `fly` own it),
so the old 0.95 snap is gone. Bluetooth: a new song arrives without its cover (looked up by name); the cover before
stays dimmed to 0.45 for up to `ART_WAIT_MS` (`artWait`, removed in `onStop` and on a source change) instead of
flashing the placeholder on every song. YouTube: `MediaMonitor` now keeps each session's metadata from its own
callbacks, so the Bitmap stays the same instance until the app sends new metadata; a new instance is compared with
`sameAs` once before it counts as a new cover.

**Paused** the cover breathes out to 94 % (`breath`, a spring on `artSlot`, not `artBox`). `relayout` snaps it to 1
and moves its scale onto `artBox` for the move (LayoutMove measures a view by its own scale only), and puts it back
after the move.

**The song's side** (`buildUi`): title 28 sp bold, one line beside the library (34 sp, two lines, when Bluetooth has
the screen to itself); the album joins the artist on one line (`paintNames`), and the third line is only for the
Bluetooth layout (the phone's name). Controls are 88 dp play, 72 dp skips, 56 dp shuffle / repeat. `setTransport`
dims previous / play / next to 0.4 and makes them refuse taps when there is nothing to steer: Bluetooth without a
connected phone, YouTube without a session, the unit's music with no queue and an empty library (play still resumes a
restored queue). Bluetooth without a phone: the artist line says how (`music_bt_hint`, two lines) and "Bluetooth
settings" is the screen's main button (`ABtn` PRIMARY, size L, with the Bluetooth icon). The old `btBox` panel was
only ever visible inside a GONE panel, and is gone. The source control in the header is a step smaller than the
panel's tabs (`Type.FOOTNOTE`): beside the back and lyrics buttons it ran 15 dp past its column in English.
`applyLayout` sets the start column's params again rather than only changing them: a changed `marginEnd` is turned
into a right margin only when params are set, so after the Bluetooth layout the gutter beside the panel stayed 0.

**Progress**: the seek strip is 52 dp tall (the bar thin in its middle); held, the bar thickens and the knob grows on a
spring (`grab`). A position that is not known (a Bluetooth song before its line is tapped, nothing loaded) shows no
bar and no times at all, and a Bluetooth song's length goes beside its artist meanwhile. After a seek (the bar let go,
a lyric line tapped) the position shown is the one asked for (`holdSeek` / `held`) until the source agrees (within
1.2 s) or `SEEK_HOLD_MS` passes: YouTube's app and a file on a USB stick answer a seek some time later, and the knob
and the lit line used to jump back and forth. The times sit 8 dp into the strip's lower edge (below the largest knob), so
the taller strip costs the cover little. They have fixed, equal halves (`lp(0, WRAP, 1f)`) and are set only
when they change (`setTextIfChanged`, private to `MusicActivity.kt`): a WRAP label asked the whole screen for a layout
on every tick.

**The progress tick** comes when the next lyric line is due (`LyricsView.msToNext`, at most 250 ms with lyrics, 500 ms
without, at least 16 ms) instead of up to a tick late, and the YouTube branch reads only `appPosition` (the playback
state), never the bitmap. `showNowPlaying` (every Bluetooth broadcast, about once a second) changes only what changed:
text, the placeholder icon (`sourceIcon`), the toggles' tints (`paintToggles`).

**The library** keeps one `ListView` and one album `GridView` for the screen's life (`showList`). A reload (a USB stick
being indexed reloads every few seconds) only tells the adapter, so the list stays where it is; another tab or group
swaps the adapter and comes back where the owner left it (`listSpots`, by tab and group key); the open group is found
again by its key after a reload, or left when it is gone. The playing song is brought into sight only the first time a
list is shown, and followed (`followPlaying`) only when the owner was looking at the song that just ended. Motion
(`moveIn`): another tab fades in place; a group comes from the end side (`Nav.INTO`), Back brings the level before from
the start side (`Nav.BACK`), mirrored in Arabic. Row and cell pictures (`picture`) appear at once from the cache and
fade in when they come later; a recycled row resets its picture first. Rows make their drawables and click listener
once (the playing row's tint is recoloured in place), `Track.subtitle` and `Track.durationText` are worked out once per
song (`Library.kt`, with `clockText`), and covers are asked for at the size shown (58 dp rows, the grid's cell width).

**States**: while the library loads, a spinner and "Loading your music…" (`music_loading`) instead of an empty panel. A
YouTube search on its way swaps the bar's search icon for a spinner and steps the results there back to 0.5. Storage
permission is asked only from the library's own Allow button or when the owner chooses the library source: opening the
screen for the phone's music no longer starts with a system dialog.

**Lyrics** (`LyricsView`):
- New lyrics no longer stop the screen. Each line's text is measured on a worker thread (`PrecomputedText` on API 28+,
  with the template line's `textMetricsParams`; a mismatch falls back to plain text), the lines are made a dozen a frame
  (`buildStep`), and the copy out of sight makes its own only after 1.5 s of calm, or the moment it is shown
  (`onVisibilityAggregated`, `settle`). An answer that comes while a new song's cover and names are moving waits for
  them (`songMovesUntil`). Before, both copies made 60–90 lines each, three drawables apiece, in one main-thread message
  in the middle of the cover's animation.
- No background per line: `LineList` draws the pressed tint behind the line under the finger (`Line.setPressed`).
- The lit line is centred by the layout that adds the lines (`centerOnLayout` in `onLayout`), so lyrics arriving
  mid-song no longer leave it below the screen and then whip there at the next line.
- Following is a spring (`follow`, stiffness 110, critically damped) that keeps its speed when retargeted (quick lines
  no longer stop and start the scroll); a line is lit 150 ms early (`LEAD_MS`); 4 s after a hand scroll the lit line
  comes back by itself (`followAgain`), even without a new line.
- Another song being looked up dims what shows (`dim`) instead of flashing "Looking for the lyrics…"; that line shows
  only when the answer takes longer than 600 ms (`lyricsLoading`). Status texts have the lyrics icon above them, and
  "failed" has a Try again button.
- The whole-screen lyrics: lines 34 sp, a 40 dp fading edge, and lines to be read rather than followed (untimed, or
  waiting for the owner's sync tap) start near the top instead of under 40 % of empty space (below the sync hint,
  measured: it takes two lines in Arabic and covered the first line); unlit lines are at 0.85
  while none is lit. The hint's icon and the "synced" note take the cover's colour (`accent`).

**Theme and language while open**: Music lives in its own task, so it came back from Settings in the old language and
stayed on the day colours all night under "auto". `checkLook` (in `onResume`, and from a `TIME_TICK` receiver while
resumed) rebuilds the screen with `recreate()` when the theme, accent or language it was built in changed. There is no
snapshot crossfade (the shared helper the audit suggested belongs in `ui/`); the window shows the new theme's
background for the moment the screen takes to build.

**Language of messages without an activity**: `MusicService` wraps its context with `LocaleHelper` (its notification),
and YouTube's "stopped out of sight" toast uses a wrapped context.

**Strings** added in 1.12 are in `res/values*/strings_music.xml`.

**Emulator**: Bluetooth song `am broadcast -a com.bt.ACTION_AVRCP_MUSIC_ID3 --ez sim true --es extra_avrcp_id3_title
"…" --es extra_avrcp_id3_artist "…"`; frame stats `dumpsys gfxinfo com.abdllh.aura reset`, then `dumpsys gfxinfo
com.abdllh.aura`.
