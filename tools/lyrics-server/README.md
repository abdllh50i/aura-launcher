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

## Setting it up (once)

1. Python 3.9 or newer on `PATH` (python.org's installer, tick "Add python.exe to PATH").
2. Double-click **`setup.bat`**. It makes the Python environment and fetches the speech model (about 1.6 GB). Nothing
   big goes on `C:`: by default everything is put beside the repository folder —
   `..\lyrics-venv` (the environment) and `..\lyrics-cache` (pip's downloads, the model, the server's home).
   Other places: set `AMRI_LYRICS_VENV` / `AMRI_LYRICS_CACHE` before running the `.bat` files.

## Starting it

Double-click **`lyrics-server.bat`**. The window shows:

```
AMRI lyrics server 1.0
  address : http://127.0.0.1:8790
  key     : abcd2345ef   (in ...\lyrics-cache\server\config.json)
  model   : large-v3-turbo on 6 CPU threads
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
| `POST /api/sync/start` | `{"title", "artist", "duration", "lines": [...]}`: the lines the car shows |
| `POST /api/sync/chunk?id=&pos=&rate=16000` | body: 16-bit mono PCM heard from song position `pos` (ms) |
| `POST /api/sync/file?id=&name=` | body: the song's file (deleted once decoded) |
| `GET /api/sync?id=` | `{"lrc", "times", "covered", "show", "done", "pending", ...}` |

`covered` is the share of the sung lines that are timed by what was heard (or spread between two such lines). The car
shows the timing from 70 % and stops listening at 90 %.

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

## The speech model

`large-v3-turbo` (int8, on the CPU; no graphics card is used). Measured on this PC (i5-12400F, 6 threads, with other
work running): 0.33–0.40 × real time with beam 5 — a 20 s piece in 7–8 s, a 4-minute song file in about 1.5 minutes.
`medium` was as fast and `small` three times faster, but `small` wrote a whole Arabic piece as one stretched word, and
turbo is the strongest of the three on Arabic. Another model: `--model medium` (or `small`).

## Tests

```
tests\test_server.py --key KEY [--fresh]      the key, real songs (which source answered), the kept answers, a miss
tests\test_align.py --key KEY [--file]        the listening, end to end, with spoken test songs (see below)
```

`test_align.py` makes test songs this project may use: the lines of a real song spoken by text-to-speech (`edge-tts`),
laid at known times over a made-up backing (chords, noise, a road's rumble, a little cabin echo), sent as the car does
(20 s pieces with their positions, or the whole file), and compares the timing the server makes with where each line
was put. Run them with the environment's Python (`..\lyrics-venv\Scripts\python.exe`) while the server runs.
