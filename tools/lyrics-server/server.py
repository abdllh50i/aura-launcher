#!/usr/bin/env python3
"""AMRI's lyrics server, on the owner's PC: more places to find a song's lyrics than the unit asks itself, and plain
lyrics made into timed ones by listening to the song in the car.

    lyrics-server.bat                       (the usual way: the Python environment made by setup.bat)
    python server.py [--host 127.0.0.1] [--port 8787] [--model NAME] [--threads N] [--home DIR] [--new-key]

It listens on 127.0.0.1:8787 by default: the owner publishes it through his own domain (a Cloudflare Tunnel, see
README.md), which gives the car an https address. Every request but /health carries the shared key (header
X-Lyrics-Key, or ?key=): it is made on the first start and kept in config.json in the home folder, printed at every
start.

  GET  /health                                 no key: {"ok": true, "service": "amri-lyrics", ...}
  GET  /api/ping                               the key is right
  GET  /api/lyrics?title=&artist=&album=&duration=
       {"synced": LRC or null, "plain": text or null, "source", "id", "covered", "tried": [...], "cached"}
  POST /api/sync/start      {title, artist, duration, lines: [...]}        the song's timing so far
  POST /api/sync/chunk?id=&pos=&rate=16000     body: 16-bit mono PCM the car heard from song position pos (ms)
  POST /api/sync/file?id=&name=                body: the song's file from the unit (deleted once decoded)
  GET  /api/sync?id=                           {"lrc", "times", "covered", "show", "done", "pending", ...}

Audio is worked on one piece at a time by a background thread; the answers say how much is still waiting.
"""
import argparse
import hmac
import io
import json
import os
import queue
import secrets
import socket
import sys
import tempfile
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import align  # noqa: E402
import sources  # noqa: E402

VERSION = "1.0"
KEY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"  # no 0/o, 1/l/i: read off a screen and typed on the car's
MAX_JSON = 512 * 1024
MAX_CHUNK = 6 * 1024 * 1024    # PCM: about three minutes at 16 kHz
MAX_FILE = 60 * 1024 * 1024    # a song file
CFG = {}
SEARCH = None
SONGS = None
SPEECH = None
JOBS = queue.Queue()
PENDING = {}                   # song id -> pieces of audio waiting (guarded by PENDING_LOCK)
PENDING_LOCK = threading.Lock()


def log(msg):
    print(time.strftime("%H:%M:%S ") + msg, flush=True)


# ---------------------------------------------------------------------------------------------- config and key
def load_config(home, args):
    os.makedirs(home, exist_ok=True)
    path = os.path.join(home, "config.json")
    try:
        with open(path, encoding="utf-8") as f:
            cfg = json.load(f)
    except (OSError, ValueError):
        cfg = {}
    changed = False
    if args.new_key or not cfg.get("key"):
        cfg["key"] = "".join(secrets.choice(KEY_ALPHABET) for _ in range(10))
        changed = True
    for k, default in (("host", "127.0.0.1"), ("port", 8787), ("model", "large-v3-turbo"), ("threads", min(8, max(2, (os.cpu_count() or 4) // 2)))):
        if k not in cfg:
            cfg[k] = default
            changed = True
    if changed:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    run = dict(cfg)
    for k in ("host", "port", "model", "threads"):
        v = getattr(args, k)
        if v is not None:
            run[k] = v
    run["path"] = path
    return run


# ---------------------------------------------------------------------------------------------- the audio worker
def pending(i, delta=0):
    with PENDING_LOCK:
        n = PENDING.get(i, 0) + delta
        if n <= 0:
            PENDING.pop(i, None)
            return 0
        PENDING[i] = n
        return n


def worker():
    import numpy as np
    while True:
        job = JOBS.get()
        kind, i = job[0], job[1]
        t0 = time.time()
        try:
            s = SONGS.load(i)
            if s is None:
                continue
            lang = align.language_of(s.get("lines") or [])
            if kind == "chunk":
                _, _, pcm, pos_ms, rate = job
                audio = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
                del pcm
                if rate != align.RATE and len(audio):
                    n = int(len(audio) * align.RATE / rate)
                    audio = np.interp(np.linspace(0, len(audio) - 1, n), np.arange(len(audio)), audio).astype(np.float32)
                start = pos_ms / 1000.0
                words = SPEECH.heard(audio, lang, start, s.get("lines") or [])
                end = start + len(audio) / align.RATE
                s = SONGS.add_words(i, words, start, end, edges=True, kind="chunk")
                log("  heard %4.1f-%4.1f s of %s: %d words (%.1f s of work), %.0f%% timed" % (
                    start, end, label(s), len(words), time.time() - t0, 100 * s["covered"]))
            elif kind == "file":
                _, _, path = job
                try:
                    from faster_whisper import decode_audio
                    audio = decode_audio(path, sampling_rate=align.RATE)
                finally:
                    try:
                        os.remove(path)  # the song's file: gone as soon as it is decoded
                    except OSError:
                        pass
                words = SPEECH.heard(audio, lang, 0.0, s.get("lines") or [])
                s = SONGS.add_words(i, words, 0.0, len(audio) / align.RATE, edges=False, kind="file")
                log("  file of %s: %d words (%.1f s of work), %.0f%% timed" % (label(s), len(words), time.time() - t0, 100 * s["covered"]))
        except Exception:
            log("  audio job failed:\n" + traceback.format_exc())
        finally:
            pending(i, -1)
            JOBS.task_done()


def label(s):
    return "'%s' / %s" % ((s or {}).get("title", "?"), (s or {}).get("artist", "?"))


# ---------------------------------------------------------------------------------------------- HTTP
class Bad(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code = code


class Handler(BaseHTTPRequestHandler):
    server_version = "AmriLyrics/" + VERSION
    timeout = 120  # a client that stops sending in the middle of a body does not hold a thread for ever

    def log_message(self, fmt, *args):
        pass

    def send(self, code, body):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        # (one request per connection: a client must never keep a socket the server let go, e.g. across a restart)
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()
        self.wfile.write(data)

    def keyed(self, q):
        given = self.headers.get("X-Lyrics-Key") or (q.get("key") or [""])[0]
        return bool(given) and hmac.compare_digest(given.strip().lower().encode(), CFG["key"].encode())

    def body(self, limit):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            raise Bad(411, "length")
        if n <= 0:
            raise Bad(400, "empty")
        if n > limit:
            raise Bad(413, "too large")
        buf = io.BytesIO()
        left = n
        while left > 0:
            part = self.rfile.read(min(left, 256 * 1024))
            if not part:
                raise Bad(400, "short body")
            buf.write(part)
            left -= len(part)
        return buf.getvalue()

    def handle_any(self, method):
        t0 = time.time()
        u = urlparse(self.path)
        q = parse_qs(u.query)
        path = u.path.rstrip("/") or "/"
        note = ""
        try:
            if path in ("/health", "/api/health"):
                return self.send(200, {"ok": True, "service": "amri-lyrics", "version": VERSION})
            if not self.keyed(q):
                time.sleep(0.4)  # (a guessed key is slow to try)
                note = "wrong key"
                return self.send(401, {"ok": False, "error": "key"})
            if method == "GET" and path == "/api/ping":
                return self.send(200, {"ok": True, "service": "amri-lyrics", "version": VERSION, "model": CFG["model"],
                                       "sources": [c.name for c in sources.SYNCED_ORDER] + ["genius"]})
            if method == "GET" and path == "/api/lyrics":
                body, note = lyrics(q)
                return self.send(200, body)
            if method == "POST" and path == "/api/sync/start":
                try:
                    d = json.loads(self.body(MAX_JSON).decode("utf-8"))
                except ValueError:
                    raise Bad(400, "not JSON")
                body, note = sync_start(d)
                return self.send(200, body)
            if method == "POST" and path == "/api/sync/chunk":
                body, note = sync_chunk(q, self)
                return self.send(202, body)
            if method == "POST" and path == "/api/sync/file":
                body, note = sync_file(q, self)
                return self.send(202, body)
            if method == "GET" and path == "/api/sync":
                i = (q.get("id") or [""])[0]
                s = SONGS.load(i)
                if s is None:
                    raise Bad(404, "unknown song")
                note = "%.0f%% timed" % (100 * s.get("covered", 0))
                return self.send(200, align.Songs.state(s, pending(i)))
            raise Bad(404, "not found")
        except Bad as e:
            note = note or str(e)
            return self.send(e.code, {"ok": False, "error": str(e)})
        except (ConnectionError, socket.timeout):
            note = "connection lost"
        except Exception:
            note = "error"
            log(traceback.format_exc())
            try:
                return self.send(500, {"ok": False, "error": "server"})
            except Exception:
                pass
        finally:
            if path not in ("/health", "/api/health"):
                log("%s %s %d ms %s" % (method, path, int((time.time() - t0) * 1000), note))

    def do_GET(self):
        self.handle_any("GET")

    def do_POST(self):
        self.handle_any("POST")


def arg(q, name, default=""):
    v = (q.get(name) or [default])[0]
    return v.strip() if isinstance(v, str) else v


def lyrics(q):
    title = arg(q, "title")
    if not title:
        raise Bad(400, "title")
    try:
        duration = int(float(arg(q, "duration", "0") or 0))
    except ValueError:
        duration = 0
    song = sources.Song(title, arg(q, "artist"), arg(q, "album"), duration)
    found = SEARCH.find(song, fresh=arg(q, "fresh") == "1")
    r = found["result"] or {}
    i = align.song_id(song.title, song.artist)
    out = {"ok": True, "id": i, "synced": r.get("synced"), "plain": r.get("plain"), "source": r.get("source") or "none",
           "match": {"title": r.get("title"), "artist": r.get("artist"), "duration": r.get("duration")} if r else None,
           "url": r.get("url"), "covered": 1.0 if r.get("synced") else 0.0, "cached": found["cached"], "tried": found["tried"]}
    if not out["synced"]:
        # timed here by listening earlier: the timing made of what the car heard
        s = SONGS.load(i)
        # (another recording of the song, a live one, is not timed by this one's timing)
        if s and duration and s.get("duration") and abs(s["duration"] - duration) > sources.DURATION_SLACK:
            s = None
        if s and s.get("times") and s.get("covered", 0) >= align.SHOW_AT:
            st = align.Songs.state(s, pending(i))
            out.update({"synced": st["lrc"], "source": "aligned", "covered": st["covered"],
                        "plain": out["plain"] or "\n".join(s.get("lines") or [])})
        elif s:
            out["covered"] = s.get("covered", 0.0)
    note = "'%s' / %s -> %s%s%s" % (song.title, song.artist, out["source"], " (timed)" if out["synced"] else (" (plain)" if out["plain"] else ""),
                                     " [kept]" if found["cached"] else "")
    return out, note


def sync_start(d):
    title = str(d.get("title") or "").strip()
    if not title:
        raise Bad(400, "title")
    lines = d.get("lines")
    if isinstance(lines, str):
        lines = lines.split("\n")
    if not isinstance(lines, list) or not lines or len(lines) > 400:
        raise Bad(400, "lines")
    lines = [str(x)[:300] for x in lines]
    try:
        duration = float(d.get("duration") or 0)
    except (TypeError, ValueError):
        duration = 0.0
    s = SONGS.start(title, str(d.get("artist") or "").strip(), duration if 20 <= duration <= 3600 else 0, lines)
    return align.Songs.state(s, pending(s["id"])), "%s, %.0f%% timed" % (label(s), 100 * s.get("covered", 0))


def sync_chunk(q, h):
    i = arg(q, "id")
    s = SONGS.load(i)
    if s is None:
        raise Bad(409, "start first")
    try:
        pos = int(arg(q, "pos", "-1"))
        rate = int(arg(q, "rate", "16000"))
    except ValueError:
        raise Bad(400, "pos")
    if pos < 0 or pos > 4 * 3600 * 1000 or rate not in (8000, 11025, 16000, 22050, 24000, 32000, 44100, 48000):
        raise Bad(400, "pos/rate")
    pcm = h.body(MAX_CHUNK)
    if len(pcm) % 2:
        pcm = pcm[:-1]
    secs = len(pcm) / 2 / rate
    if secs < 3:
        raise Bad(400, "too short")
    n = pending(i, +1)
    JOBS.put(("chunk", i, pcm, pos, rate))
    return align.Songs.state(s, n), "%s %.1f s at %.1f s (%d waiting)" % (label(s), secs, pos / 1000.0, n)


def sync_file(q, h):
    i = arg(q, "id")
    s = SONGS.load(i)
    if s is None:
        raise Bad(409, "start first")
    name = os.path.basename(arg(q, "name", "song")) or "song"
    ext = os.path.splitext(name)[1][:8] if "." in name else ".bin"
    data = h.body(MAX_FILE)
    tmp_dir = os.path.join(CFG["home"], "tmp")
    os.makedirs(tmp_dir, exist_ok=True)
    fd, path = tempfile.mkstemp(suffix=ext, dir=tmp_dir)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    del data
    n = pending(i, +1)
    JOBS.put(("file", i, path))
    return align.Songs.state(s, n), "%s file %s (%d waiting)" % (label(s), name, n)


def main():
    global CFG, SEARCH, SONGS, SPEECH
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    ap = argparse.ArgumentParser(description="AMRI lyrics server")
    ap.add_argument("--host", help="address to listen on (default 127.0.0.1: published through a tunnel)")
    ap.add_argument("--port", type=int, help="port (default 8787)")
    ap.add_argument("--model", help="faster-whisper model (default large-v3-turbo)")
    ap.add_argument("--threads", type=int, help="CPU threads for the speech model")
    ap.add_argument("--home", default=os.environ.get("AMRI_LYRICS_HOME") or os.path.join(HERE, "data"),
                    help="where the key, the kept lyrics and the songs' timing live")
    ap.add_argument("--new-key", action="store_true", help="make a new key (the car then needs the new one)")
    ap.add_argument("--download-model", action="store_true", help="fetch the speech model now and stop")
    args = ap.parse_args()
    home = os.path.abspath(args.home)
    CFG = load_config(home, args)
    CFG["home"] = home
    SPEECH = align.Transcriber(CFG["model"], int(CFG["threads"]))
    if args.download_model:
        SPEECH.load()
        return 0
    # what a previous run left half done: a song file not decoded before the server stopped
    tmp_dir = os.path.join(home, "tmp")
    if os.path.isdir(tmp_dir):
        for f in os.listdir(tmp_dir):
            try:
                os.remove(os.path.join(tmp_dir, f))
            except OSError:
                pass
    SEARCH = sources.Search(home)
    SONGS = align.Songs(home)
    threading.Thread(target=worker, name="audio", daemon=True).start()
    try:
        srv = ThreadingHTTPServer((CFG["host"], int(CFG["port"])), Handler)
    except OSError as e:
        print("Cannot listen on %s:%s (%s). Is it already running?" % (CFG["host"], CFG["port"], e))
        return 1
    srv.daemon_threads = True
    print("AMRI lyrics server %s" % VERSION)
    print("  address : http://%s:%d   (publish it through your domain: see README.md)" % (CFG["host"], int(CFG["port"])))
    print("  key     : %s   (in %s)" % (CFG["key"], CFG["path"]))
    print("  model   : %s on %d CPU threads (loaded at the first song to time)" % (CFG["model"], int(CFG["threads"])))
    print("  home    : %s" % home)
    print("Close this window to stop it.", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
