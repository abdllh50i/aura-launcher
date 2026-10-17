# 1.13 lyrics: more sources, and lyrics timed by listening (notes for DEV.md)

These notes belong under "Aura Music" in DEV.md, after the Lyrics paragraphs of 1.5.4–1.12. Strings of this area are in
`res/values*/strings_lyrics.xml`.

## What the owner asked for, and what was built
The owner asked for more places to fetch lyrics from ("find lyrics": read as more lyric search sources), Genius as the
last resort, and Genius's plain lyrics to be timed against the song by a script on his own PC, published through his
own domain. Two halves were built. `tools/lyrics-server` is a Python program for his PC (port 8790 on 127.0.0.1, put on
his domain with a Cloudflare Tunnel; README.md / README.ar.md there say how). The app asks it after LRCLIB, and, when
he switches it on, lets it time untimed lyrics by listening to the song in the car. The sound never comes from YouTube
or any streaming service: for a Bluetooth song it is what the car's microphone hears, for a song on the unit it is the
file itself.

## The order of the lookups (`Lyrics.kt`)
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

## The server's sources (`tools/lyrics-server/sources.py`)
The timed sources of the `syncedlyrics` package are asked at once (Musixmatch, LRCLIB, Megalobiz, NetEase) and the first
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

## Timing by listening, on the unit (`LyricsSync.kt`)
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

## Timing by listening, on the server (`align.py`)
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

## The screen (`LyricsView.timeLines`, `MusicActivity`)
Timed lines that come for the very lines on the screen do not rebuild anything: the lines stay where they are, take
taps from then on (a tap looks the line's time up when it happens, `tapLine`, since a timing by listening can be refined
while it shows), the room above them grows to the timed lyrics' 38 % and the scroll moves by the same amount in the same
layout pass (`shiftOnLayout`), and the line being sung lights and glides to the middle as with any timed lyrics. They
keep the plain lines' size (measuring them again at the timed size would move every line); the next play shows the timed
size. Other lines (another source's) are shown anew. `MusicActivity.lyricsSong` remembers the song as its lyrics were
asked for, so a late answer is matched to the song on the screen.
Fixed on the way: the phone skipping A → B → A before B's lyrics came left A's lyrics stepped back (dimmed, not
following, not tappable) until the next song, since A's lines counted as already shown; `lyricsShown` is now cleared
when what shows steps back.

## Settings (`settings/LyricsCard.kt`)
Display & sound → Music → Lyrics server: the address (a name without a scheme is taken as https; an IP or a port as
http, for tests), the key (shown as its first three characters), Test (`/health`, then `/api/ping` with the key: "The
server answers (1.0 · large-v3-turbo)", wrong key, not this server, no answer), and the switch "Sync lyrics by
listening" (needs the server and the microphone permission, asked as the voice page asks it). The inputs are a card of
their own with a URL / password keyboard and no suggestions, and the keyboard's Done (or Enter) saves: on the unit's
short screen the keyboard covers the card's buttons. Release builds refuse plain http to other devices
(`network_security.xml`): an http address other than the unit itself is answered with "use your domain's https
address", before any request.

## Not verified
* Real singing heard through the K2501's microphone over road noise: the tests used speech. The recogniser is much less
  sure of sung Arabic; the alignment needs two clear words of a line, so a weak piece leaves lines guessed rather than
  wrong, but the coverage reached on real songs is unknown.
* The Bluetooth clock's own offset (the phone's audio reaches the speakers a little after the title broadcast): a timing
  made by listening is in the car's clock, so it is right for this car; the same LRC from a file is in the file's time.
* The owner's domain and tunnel (no real domain was used); Cloudflare's bot protection may need a rule (README).
* The unit's microphone shared with the assistant (`MicTap`) on the unit; on the emulator only the own recording and
  the debug feed ran.
