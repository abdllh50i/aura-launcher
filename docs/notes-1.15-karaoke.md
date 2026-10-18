# Word-by-word lyrics and a song's saved timing (1.15: `Words`, `LyricsView.Line`, `Lyrics`, `MusicActivity`)

The owner asked for three things: the word being sung lit on the lyrics' lit line ("an effect on the word the song has
reached", with a switch to turn it off), his own timing kept when he sets it by tapping the line being sung ("so that
when I play the same song later the same timing comes"), and songs whose lyrics come without timing timed by his
server. The server's half (word times by listening alignment, its own estimate when it heard none) is in
`tools/lyrics-server`; these notes are the app's half.

## Where a word's time comes from (`Words.kt`, `Lyrics.parse`, `LyricsServer`)
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

## The effect (`LyricsView.Line.onDraw`)
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

## The switch (Settings → Display & sound → Music)
"Word-by-word lyrics" / «الكلمة اللي تنغنى تضيء» (pref `lyricsWords`, `Words.on`), on by default. Off, the lit line is
drawn exactly as before 1.15 (`super.onDraw`). The music screen reads it in `onStart`, so it applies on coming back
from Settings; the word times are worked out either way, so turning it on needs no new lyrics.

## The saved timing (`MusicActivity.onLineTap`, `onLineHold`, `Lyrics.keepOffset`)
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

## Timing by listening on by default (`LyricsServer.listen`, `LyricsCard`)
The owner asked that songs whose lyrics come untimed be timed by his server, so the pref `lyricsListen` now defaults
to on; the switch still turns it off, and its subtitle says so: only while a song with untimed lyrics plays on the
music screen does the microphone listen, and the sound goes only to his server. Every microphone rule of 1.13 stands
(`LyricsSync.mayListen`: never in a call, while the assistant listens or answers, while the wake word is recorded,
while reversing; only with a server and key set). The microphone permission is never asked for by this default: the
switch shows on only when the pref is on and the permission is given, and turning it on asks for it as before.

## Checked on the emulator (emulator-5570, debug build)
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

## Not verified
* Smoothness on the K2501 itself (Mali-G31, 32-bit): the emulator draws 6–8 frames a second whatever the content.
* The owner's real server's `enhanced` field (the server half was being written at the same time): only a made-up
  record took that path. `/api/sync`'s state with `enhanced` while listening is untested end to end.
* Arabic marks that stand far outside a word's advance (a shadda over a final letter) lit with the neighbouring word's
  strength at the very edge: not seen in the songs tried.
