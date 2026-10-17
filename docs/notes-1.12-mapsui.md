# Aura Maps in 1.12: guidance, panels and words (notes for DEV.md)

What a maintainer must know about the maps UI and the guidance after the 1.12 redesign. Written to be merged into the
"Aura Maps (`nav/`)" section of DEV.md.

## The guidance banner (`nav/GuidanceBanner.kt`)
One view for every map that guides: Maps uses it full size, the home screen's map is meant to use `compact = true`.
It reads `NavSession` itself, so a caller only adds it, calls `update()` when the session changes and `reveal()`s it in
and out:

- `GuidanceBanner(ctx, compact = false)`: a vertical `LinearLayout`, GONE at first, in the guidance colour
  (`Guide.sign()`), radius `Radius.XL`, `lift(Elev.FLOAT, overMap = true)`.
- `update()`: shows `NavSession.route` / `progress` / `rerouting` / `destination` now; does nothing without a route
  and a progress. Every label goes through `setIfChanged`, and the labels are MATCH wide, so a fix with the same words
  costs nothing and a new number only redraws its line (no layout pass per fix).
- `reset()`: forget the step shown, so the next `update()` shows it at once (call it when a trip starts; Maps does).
- `maxWidthPx` (compact only): the most the banner may take. Compact is a fixed 440 dp (`COMPACT_WIDTH_DP`), capped by
  this and by the parent, so the changing numbers never resize it. The home map should set it to what the speed sign
  and the camera chip leave.
- Full size: 80 dp arrow; the distance (`DISPLAY_M`, its unit at 0.55 of its size: `GuidanceBanner.styled`), the
  action (`TITLE`, one line), the road (`BODY`, one line, ellipsized, bidi-wrapped so a Latin name or one with brackets
  keeps its order in an Arabic line); then the lane strip and the "then" strip in a darker band. Every word on the
  sign is full white and the lines differ by size only: the sign is deepened just to 4.5:1 for white, so any faded
  line (84 % white was) falls under it.
- Compact: 52 dp arrow, `HEADLINE` distance, `BODY` action, `FOOTNOTE` road, and at its end a column with the arrival
  clock and "time · distance left" (the home screen has no trip bar). No lane or "then" strip.
- A new step comes in (the arrow grows from 0.82, the words rise 8 dp, `enter`); the same step only changes its
  numbers. While rerouting the banner cross-fades (`TransitionDrawable`, `Motion.SHORT`) to a muted sign with a
  circling arrow (`Turn.REROUTE`) and only "updating the route", instead of the old step's arrow and distance; the
  step shown after it comes in like a new one.
- The "then" strip shows when the manoeuvre after this one starts within `max(250 m, 12 s at the current speed)` of
  where this one ends (a roundabout ends at its exit). The lane strip shows when OSRM gave two or more lanes, at least
  one of them wrong for the route, and the junction is under 800 m away. Lanes are drawn left to right in every
  language, as OSRM lists them and as the road's signs show them.
- The home map (`home/MapPanel.kt`, not changed in this branch): replace its own banner with
  `GuidanceBanner(ctx, compact = true)`; in its `syncNav` call `banner.reveal(navigating, -8f)` and `banner.update()`;
  keep its click (open Maps) and give it `pressScale(Motion.PRESS_SURFACE)`; set `maxWidthPx` from the RoadHud's
  measured width. Its own instant visibility switch of the search pill and chips should become `reveal` too.

`Guide` (same file) is the guidance colour: `Guide.color()` is the accent unless that is red, amber or graphite (which
mean danger, a warning, switched off; taken from `Palette.accents` by position, so a reordered swatch list must
update `Guide.NOT_FOR_GUIDING`), then the default blue; `Guide.sign()` is that colour deepened until white
reads on it at 4.5:1 (green and cyan accents gave white text at about 2:1). The banner and the arrival card's bar use
them. The route line, the turn arrow and the car are drawn by `MapStyle` / `AuraMap` (not changed here): they should
take `Guide.color()` instead of `Palette.accent`, so the route and its sign agree.

## Showing panels: `View.reveal(show, dy, delay, animate, scaleFrom)` (in `GuidanceBanner.kt`)
Maps syncs its panels on every fix, so every visibility change goes through `reveal`, which does nothing when the view
is already where it should be (also while it is on its way out: the foundation's `aura_exiting` tag). It enters with
`enter(dy = …)` and leaves with `exit` half the way back, then GONE. A view whose parent is not shown, or that is not
attached yet (the first sync after `onCreate`), is switched at once. Code that asks "is it showing?" must use
`isOn` (VISIBLE and not exiting) instead of `visibility`. Offsets: top panels −12…−16 dp, the results −8 dp, the
bottom cards +16 dp with a 60 ms delay (after the top one), the recenter pill and the zoom capsule by scale.
`TextView.setIfChanged(s)` (same file) sets a label's text only when it differs.

## Maps screen layout
- The map's own controls (compass, zoom capsule, speed, limit sign, camera button, attribution) are one layer
  (`controls`) that fades out while the search is open: the keyboard shrinks the screen to a strip where they piled
  onto each other. A trip started from outside while it is faded brings it back.
- + and − are one capsule under the compass; while a route is driven and the map follows it they go (pinching still
  zooms, and the navigation zoom follows the speed), and come back in free or overview camera.
- The compass turns with the map (`rotation = -bearing`, set when it moved at least a degree, from MapLibre's camera
  listeners); while browsing with north up it is dimmed to 0.55. Tap: north up, or back to the drive while navigating.
- The voice button moved into the trip bar beside the overview button (it only ever showed while navigating).
- End is a red word on a red tint (`ABtn.Kind.DANGER`), not a red block: the time left is what draws the eye.
- The camera button and the limit sign are laid out where they stand while driving and moved by translation into the
  speed's place while browsing, so they glide instead of jumping by their margins.
- The recenter pill sits at the bottom start (the driver's side), lifted above whichever bottom card shows. It says
  "متابعة الملاحة" / "Resume" while navigating, "موقعي" / "My location" otherwise. While navigating the map goes back to
  the car by itself 12 s after the last touch, 20 s after the overview button; the timer is cleared on End and in
  `onStop`.
- The arrival card says "وصلت إلى" / "You have arrived at" and the place's name (not a dropped pin's placeholder name),
  over a line that runs out in the 30 s before the trip ends by itself. The line steps once a second with a
  `Motion.SHORT` glide (`arrivedTick`, cleared when the card goes and in `onStop`): one 30 s animation drew a frame
  at every vsync, about 60 a second, for a parked car.
- Messages (`toast`, a `Snack`) stand above the bottom card that shows; one revealed in the same pass has no height
  yet and is measured at the panel width (the "no voice" hint at a trip's start covered End).
- The ETA's green unit (trip bar, place card) is full `Palette.success`, smaller only: at 80 % it was 3.5:1 on the
  light glass.
- The speed disc rounds the speed (under 3 km/h is 0: GPS reports 1-3 for a parked car) and turns red with one beat
  over the limit, instead of only its digits.

## Place card and search
- The route's time is the headline (green, `HEADLINE`, unit small), then distance and arrival. Go stays dimmed until
  there is a route; a failure is in `warn` with "tap to try again" (the line is the button).
- Go takes the row; beside it one ghost "حفظ" / "Save" opens a `Menu` with "حفظ كمنزل" / "Set as Home" and "حفظ كعمل"
  / "Set as Work". Labelled "المنزل" beside Go the old buttons read as "drive home" and overwrote Home; two "Set as …"
  buttons beside it left Go barely wider than either. While Home or Work is being chosen (`pickFor`), Save keeps the
  place as that at once, without the menu.
- A dropped pin's route is no longer thrown away when the reverse lookup renames the pin first: the route callbacks
  compare the selection by position, not identity.
- The first camera flight waits 700 ms for the route (which brings its own overview) instead of flying twice.
- Search keeps the old rows, dimmed, with a spinner in the clear button's place, until the answer replaces them (the
  card no longer collapses and grows on every letter). Without a fix it biases to the map's centre. Photon's bias is
  stronger (`zoom=12`, `location_bias_scale=0.1`), and `Places.rank` orders equally good names by distance band
  (< 5, 30, 150 km, further). A name that holds the whole query counts as good as the query itself, so "Al Dawa
  Pharmacy" 230 m away comes before "Pharmacy" in Dammam.
- With a position, `Places.search` also asks Photon once without the bias (the typed text, `limit=3`): the biased
  answers alone are ten local namesakes for "Jeddah" or "مكة" in the Eastern Province (streets are named after the
  cities they lead to). Those far answers sort into the last band, and the first of them keeps a place in the twelve
  even when the local ones would fill them. (1.12's first cut dropped everything over 500 km while anything under
  150 km answered; that lost the far cities.)
- Address parts are joined with the UI language's comma ("، " in Arabic), whatever the name's script, and the detail
  lines read in the UI's direction (`TEXT_DIRECTION_LOCALE`): an address starting with a Latin street name turned the
  whole Arabic line around.
- The results card measures its room in its own measure pass and re-measures when the keyboard comes or goes; it is
  cut at half a row so the cut reads as "more below".
- The clear button follows the field's text (also a place's name put there), not only typing.

## Guidance: steps, words and voice
- `Router.prepare(line, steps)` runs for OSRM and BRouter routes once the steps are on the line:
  - `Step.silent`: "new name", "continue", "notification" and "use lane" steps that go straight (and have no lanes to
    keep to), and the "exit roundabout" after a roundabout. They stay in the route (their time counts) but are never
    shown or spoken; `NavSession` skips them for `next`, and `Route.following(i)` gives the next told one.
  - Roundabouts are measured on the line: `Step.span` (entry to the exit taken; OSRM's exit step, else the end of the
    longest left-turning stretch) and `Step.angle` (where the exit really leads). The icon draws the exit at that
    angle with the exit's number in the ring, and the map's turn arrow runs to the exit. Saudi roundabouts often
    have five or six arms or arms at 45 degrees, where the old 4-way table pointed the wrong way. A turning back
    (measured over +160, which road noise gives as often as -179) is `-178` in `Instructions.exitAngle`: the ring is
    driven counter-clockwise, so it is the whole way round; +179 drew the exit where the car came in. The icon draws
    any exit beyond -150 at -150, beside the way in instead of over it.
  - A roundabout step without a road takes its exit step's.
- OSRM steps now carry `lanes` (from `intersections[0]`), `destinations` and `exits`. Ramps say the exit number and
  "باتجاه …" (two destinations at most); the voice leaves the destination list out.
- `Instructions.action` is the banner's main line, `roadLine` its second, `text` the whole sentence (notification),
  `spoken` the voice. The upcoming arrival says where the destination is ("وجهتك على اليمين"), not "you have arrived";
  `arrived(name)` is said once, on arrival, with a short readable name. A "continue" with a U-turn modifier says
  U-turn, like its arrow. Wording: "انعطف يمينًا قليلًا", "الزم اليسار", "ثم", "جارٍ تحديث المسار…"; English "Bear left",
  "Make a sharp left".
- Units: Arabic minutes "5 دقائق" / "12 دقيقة", hours "1 س 20 د"; no trailing ".0" in km; the 12-hour arrival in
  Arabic ends in "ص" / "م". Spoken kilometres in Arabic grammar ("كيلومتر ونصف", "كيلومترين", "5 كيلومترات",
  "12 كيلومترًا"). The voice leaves out a road name it cannot read (Latin for the Arabic voice).
- `Router` puts the offline index's `name:ar` on an online route's Latin street names when the UI is Arabic
  (`PlaceIndex.arabicNames`, one query per route on the io thread; the nearest street of that name within 15 km).
- Prompts (`NavSession.prompt`): early (1-2 km) for ramps, forks, merges and at highway speed; far (400-1500 m, not
  for an arrival under 600 m after the last manoeuvre); near (80-300 m, 8 s) with ", ثم …" when the next manoeuvre
  follows within 150 m; a route's first words (the first manoeuvre, or "استمر لمسافة …" when it is over 3 km away) at
  the start and after a reroute; "استمر على … لمسافة …" just past a manoeuvre with over 3 km to the next one.

## Theme, accent and language while Maps is open
`MapsActivity` remembers the theme, accent and language it was built in and checks them in `onResume` and on every
minute's tick (also time, time zone and locale changes) while resumed: a sunset under "auto" or a change in Settings
recreates it. A new language at once; a theme or accent not while a place is chosen, the search is open or a route is
being found (the next tick tries again). A trip being driven goes on (`NavSession` is the app's). There is no
snapshot cross-fade on this recreate yet; HomeActivity's PixelCopy fade would need to move into a shared helper in
`ui/` first.

## Road additions
- "Camera here" (`CameraMark.mark`) takes only a fresh (under 5 s), precise (30 m or better) GPS fix; NavSim's "sim"
  and the debug "mock" fixes count. `CarLocation.last` can be yesterday's last-known fix or a network one, and a
  camera stored there beeped at the wrong place on every drive. NavSim now stamps `elapsedRealtimeNanos` for this.
- `RoadWatch` keeps what it announced when the driver marks or takes back a camera (it only forgets spots that are
  gone, by identity), so the bump or camera just announced does not beep again.
- `RoadHud`: the limit sign enters and leaves like the chip; the chip's label is as wide as its longest distance from
  the start, so the distance counting down only redraws it.

## Testing
- `MapsActivity --ez simulate true`, long-press the map, Go (or long-press Go in a debug build) drives the route at
  70 km/h. The lanes, ramp destinations and Arabic street names only come with online OSRM routes; the offline router
  has no lanes.
- Rerouting, the arrival card's 30 s bar and the over-limit beat need a drive (NavSim, or `emu geo fix` off the route).
