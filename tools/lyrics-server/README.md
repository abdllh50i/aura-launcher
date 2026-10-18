# AMRI lyrics server

A small program for the owner's PC that gives the car more lyrics than the unit finds by itself, and makes untimed
lyrics timed by listening to the song in the car. The car reaches it through the owner's own domain.

**Address on this PC: `http://127.0.0.1:8790`** (it listens only there; the domain is what the car uses).

* **More sources.** The unit asks LRCLIB itself. When LRCLIB has no timed lyrics, it asks this server, which tries the
  timed sources of the open-source [`syncedlyrics`](https://github.com/moehmeni/syncedlyrics) package — Musixmatch,
  LRCLIB, Megalobiz (through its public API) and NetEase — and takes a result only when it really is the song (title
  and artist, Arabic or Latin spelling, and the length within 4 s when known). Genius comes last, for plain text.
* **Timing by listening.** For a song whose lyrics have no times, the car's microphone hears the song while it plays
  and sends what it hears here, 20 s at a time with the song's position. [faster-whisper](https://github.com/SYSTRAN/faster-whisper)
  writes down the sung words with their times, they are matched to the lines, and the timing is kept: the next play
  is timed at once. A song from the unit's own storage is sent as its file instead. The sound is deleted as soon as it
  is decoded; only the words heard and their times are kept. Nothing is ever taken from YouTube or any streaming
  service.
* **Word times by listening (1.16).** The car sends a digital copy of the music it plays (or, failing that, what its
  microphone hears), 12 s at a time. The singer's voice is separated from the band ([demucs](https://github.com/facebookresearch/demucs)
  htdemucs), and each line's known words are placed onto it around the line's own time (CTC forced alignment with
  torchaudio's MMS model, Arabic written in Latin letters by [uroman](https://github.com/isi-nlp/uroman)): every word
  gets the time it is really sung, and where the singer pauses inside a line the pause shows. A line placed unsurely
  (other words, no singing there) gets no word times: the app lights it whole. When the app already has the lines'
  times (from its lyrics source) it sends them and only the words are looked for ("timed"). A chorus heard once gives
  its later repeats their words at once. Only the times are kept, never the sound (the last 45 s of a song being
  listened to stay in memory, so a line sung across two pieces is heard whole).

## Setting it up (once)

1. Python 3.9 to 3.12 on `PATH` (python.org's installer, tick "Add python.exe to PATH"; 3.11 is the tested one).
2. Double-click **`setup.bat`**. It makes the Python environment (about 5 GB with torch) and fetches the models (about
   3.5 GB: speech, voice separator, word aligner). With an NVIDIA graphics card (`nvidia-smi` answers) it installs
   torch's CUDA 12.4 build and the server works on the card; without one, the processor's build (everything works,
   slower). Nothing big goes on `C:`: by default everything is put beside the repository folder —
   `..\lyrics-venv` (the environment) and `..\lyrics-cache` (pip's downloads, the models, the server's home).
   Other places: set `AMRI_LYRICS_VENV` / `AMRI_LYRICS_CACHE` before running the `.bat` files.

## Starting it

Double-click **`lyrics-server.bat`**. The window shows:

```
AMRI lyrics server 1.1
  address : http://127.0.0.1:8790
  key     : abcd2345ef   (in ...\lyrics-cache\server\config.json)
  models  : large-v3-turbo, htdemucs, MMS_FA on NVIDIA GeForce RTX 3090 (... GB free; loaded at the first song to time)
```

Close the window to stop it. Options (after `lyrics-server.bat`): `--port 8790`, `--host 127.0.0.1`,
`--model large-v3-turbo`, `--threads 6`, `--home DIR`, `--new-key` (a new key: the car then needs the new one).

**The key** is made on the first start (10 letters and digits, no look-alikes such as 0/o or 1/l) and kept in
`lyrics-cache\server\config.json`, which is outside the repository. Every request except `/health` must carry it.
Type it in the car exactly as shown (capitals do not matter).

## Publishing it through your domain (Cloudflare Tunnel)

The server listens only on `127.0.0.1`, so nothing on your network or the internet reaches it directly. A Cloudflare
Tunnel connects out from this PC to Cloudflare, and Cloudflare serves it as `https://lyrics.<your domain>` — with a
real certificate, which the unit needs (it refuses plain `http` to other devices). Your domain must use Cloudflare's
name servers.

**From the dashboard (simplest):** Cloudflare dashboard → Zero Trust → Networks → Tunnels → *Create a tunnel* →
Cloudflared → name it (e.g. `amri-lyrics`) → install the connector on this PC with the command it shows (Windows) →
*Public hostname*: subdomain `lyrics`, your domain, service type `HTTP`, URL `127.0.0.1:8790` → Save.

**From the command line:**

```
winget install --id Cloudflare.cloudflared
cloudflared tunnel login                       (opens the browser: choose your domain)
cloudflared tunnel create amri-lyrics
cloudflared tunnel route dns amri-lyrics lyrics.example.com
```

Then `%USERPROFILE%\.cloudflared\config.yml`:

```yaml
tunnel: amri-lyrics
credentials-file: C:\Users\<you>\.cloudflared\<tunnel id>.json
ingress:
  - hostname: lyrics.example.com
    service: http://127.0.0.1:8790
  - service: http_status:404
```

and `cloudflared tunnel run amri-lyrics` (or `cloudflared service install` to have it start with Windows).

Check it from any browser: `https://lyrics.example.com/health` must answer `{"ok": true, "service": "amri-lyrics", ...}`.

Notes:
* If Cloudflare's *Bot Fight Mode* or a challenge page is on for the domain, the car's requests (not a browser) can be
  stopped: switch it off, or add a WAF rule that skips it for `lyrics.example.com`.
* Cloudflare waits at most 100 s for an answer and takes uploads up to 100 MB: the server answers at once and works on
  the sound in the background, and the car sends song files of at most 40 MB.
* Only the key protects the server: keep it to yourself; make a new one with `--new-key` if it got out.

## In the car

Settings → Display & sound → Music → **Lyrics server**: the address (`https://lyrics.example.com`) and the key, then
*Test* ("The server answers (1.0 · large-v3-turbo)"). **Sync lyrics by listening** is off until you turn it on.

## What the server does (API)

All answers are JSON (UTF-8). The key goes in the header `X-Lyrics-Key` (or `?key=`).

| Request | |
|---|---|
| `GET /health` | no key: `{"ok": true, "service": "amri-lyrics", "version": "1.0"}` |
| `GET /api/ping` | the key is right (the car's *Test* button) |
| `GET /api/lyrics?title=&artist=&album=&duration=` | `{"synced": LRC or null, "plain": text or null, "source", "id", "covered", "match", "tried": [...], "cached"}` |
| `POST /api/sync/start` | `{"title", "artist", "duration", "lines": [...], "times": [ms or null, ...]}`: the lines the car shows (and their times: "timed") |
| `POST /api/sync/chunk?id=&pos=&rate=16000&src=capture` | body: 16-bit mono PCM heard from song position `pos` (ms); `src` capture or mic |
| `POST /api/sync/file?id=&name=` | body: the song's file (deleted once decoded) |
| `GET /api/sync?id=&words=1` | `{"mode", "lrc", "times", "covered", "show", "done", "pending", "enhanced", "words", "diag", ...}` |

Untimed (no `times`): `covered` is the share of the sung lines that are timed by what was heard (or spread between two
such lines); the car shows the timing from 70 % and stops listening at 90 %. Timed: `times` are the given ones and
`covered` is the share of the lines with words that have their word times (done at 90 %). With `words=1` (start,
chunk, file and `GET /api/sync`): `enhanced`, the LRC with `<mm:ss.xx>` before each word of the lines whose real word
times are known (an empty tag where the singing paused), and `words`: `heard`. `diag` is the last piece's numbers:
`rms`, `peak` and `vocal` (the separated voice) in dBFS, `asr` (words written down), `aligned` (lines given word times),
`src`, and `offset` (ms: how far the app's song clock was from the given times) and `ms` (work) when known. The contract
with the app: `docs/notes-1.16.md`.

Kept on disk (in the home folder): `lyrics\` the sources' answers (timed ones two months, plain ones three days, "nobody
has it" two days; anything but a timed answer only half an hour when a source did not answer — LRCLIB's "busy" once
turned a song it has timed into Genius's plain text), `songs\` the words heard per song and the timing made of them.
`config.json` holds the key and the settings.

## When something is wrong

* *Test* in the car says "not the lyrics server": something else answers at that address — the tunnel points at
  another port, or the server is not running and another program has the port. It runs on 8790 because 8790 on the
  workshop PC belongs to another program; another free port works too (`lyrics-server.bat --port 8791`, and the
  tunnel's service with it).
* "No answer": the server window is closed, the PC asleep, or the tunnel not running (`https://…/health` in a browser
  tells which).
* "The key is wrong": the key in the car is not the one the server printed (a `--new-key` since?).
* The timing by listening needs the song's position: a Bluetooth song that was already playing when the phone
  connected is not listened to until its start is seen (the next song) or a lyric line is tapped.

## Sources, as found on 2026-10-17

* **LRCLIB**: answers, often "busy" (503: asked again 1.5 s later).
* **Megalobiz**: the search page `syncedlyrics` 1.0.1 reads was removed ("410 Gone"); the server uses Megalobiz's new
  public API (no key, 30 questions a minute). Strong on Arabic.
* **NetEase**: answers (mostly Chinese, English, some Arabic).
* **Musixmatch**: gives anonymous clients a placeholder token (`000…`) and invented lyrics for every song; the server
  sees the token and leaves Musixmatch out (shown as "unavailable") instead of showing made-up text.
* **Genius**: answers only clients that look like a browser; its pages' own furniture (contributors, translations,
  "[Chorus]" headings) is taken out of the text.

## The models

* Speech: faster-whisper `large-v3-turbo` (another: `--model medium`). On the graphics card its weights are int8 and it
  computes in float16 (as accurate as float16 weights on the test songs, 1 GB less); on the processor int8.
* Voice: demucs `htdemucs` (80 MB). Words: torchaudio's `MMS_FA` (a wav2vec2 model of 1,100+ languages; the 1.2 GB
  download is kept as a 0.6 GB float16 copy beside it, `TORCH_HOME\hub\checkpoints\mms_fa_uroman_fp16.pt`).

Measured on the workshop PC (RTX 3090 shared with other programs, driver 610) for a 12 s piece: the voice 0.3 s,
whisper 0.3 s, a line's words 0.02-0.05 s; 0.6-0.9 s of work per piece in all (2.9 s at worst, a piece whose every
line is tried twice and refused), and a 2.5-minute song file in 6-9 s. The first piece after a start loads the models
(20-30 s). On the card about 2.2 GB stay taken while the models are loaded. On Windows the card's memory is also
charged to the server's process (about 5 GB of the PC's memory in all): when the PC is short of memory, a job the card
cannot take is done on the processor instead. Without a usable card, everything runs on the processor (i5-12400F: a
12 s piece in about 16 s with the voice separated; when pieces are waiting they are worked on without separating it,
8-10 s each, so the work keeps up with the car; the first piece loads the models, about 45 s).

## Tests

```
tests\test_server.py --key KEY [--fresh]      the key, real songs (which source answered), the kept answers, a miss
tests\test_align.py --key KEY [--file]        the listening, end to end, with spoken test songs (see below)
tests\test_words.py --key KEY [--mic 10] [--clock 0.8] [--untimed] [--file] [--half] [--wrong]
                                              the word times by listening, end to end, against every word's true time
tests\test_units.py                           the word-time parts that need no server, sound or card
```

`test_words.py` makes its test songs with `tests/songmaker.py` (spoken lines made more like singing: held last
words, pauses inside lines, an echo, a made-up band with a wordless "ooh" lead; every word's true time known), sends
them in 12 s pieces as the app does (as the digital copy, or through a simulated car microphone: road noise at
`--mic` dB under the song, the highs cut, -30 dBFS), and reports how many lines got words, how far the word starts
are, the pauses found and made up, and how long each piece took.

`test_align.py` makes test songs this project may use: the lines of a real song spoken by text-to-speech (`edge-tts`),
laid at known times over a made-up backing (chords, noise, a road's rumble, a little cabin echo), sent as the car does
(20 s pieces with their positions, or the whole file), and compares the timing the server makes with where each line
was put. Run them with the environment's Python (`..\lyrics-venv\Scripts\python.exe`) while the server runs.
