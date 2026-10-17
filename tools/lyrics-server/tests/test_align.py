#!/usr/bin/env python3
"""The listening sync, end to end, with sound this project may use: the song's lines spoken by text-to-speech
(edge-tts), laid one after another at known times over a made-up backing (chords, noise, a road's rumble) and a little
room echo, then sent to the server as the car would — chunks of 20 s with their song position, or the whole file — and
the timing it makes compared with where each line was put.

    python tests/test_align.py --server http://127.0.0.1:8787 --key KEY [--song "Hello|Adele" ...] [--file]
    python tests/test_align.py --direct --model small            (no server: the recogniser and the alignment here)

The spoken lines are kept in --work (default ../../../lyrics-cache/test, outside the repository) so a second run does
not ask for them again. Lyrics come from the server's own search (nothing of a song's text is stored in the repo).
"""
import argparse
import asyncio
import io
import json
import os
import random
import statistics
import sys
import time
import urllib.parse
import urllib.request
import zlib

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

RATE = 16000
VOICES = {"ar": "ar-KW-FahedNeural", "en": "en-US-GuyNeural"}


def http(method, url, key, body=None, ctype=None, timeout=60):
    req = urllib.request.Request(url, data=body, method=method)
    if key:
        req.add_header("X-Lyrics-Key", key)
    if ctype:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8") or "{}")


def lyrics_lines(server, key, title, artist, limit):
    q = urllib.parse.urlencode({"title": title, "artist": artist})
    code, d = http("GET", "%s/api/lyrics?%s" % (server, q), key)
    if code != 200:
        raise SystemExit("lyrics: HTTP %d %s" % (code, d))
    text = d.get("plain")
    if not text and d.get("synced"):
        import sources
        text = sources.lrc_to_plain(d["synced"])
    if not text:
        raise SystemExit("no lyrics for %s / %s" % (title, artist))
    lines = []
    for line in text.split("\n"):
        if len([x for x in lines if x]) >= limit:
            break
        lines.append(line.strip())
    while lines and not lines[-1]:
        lines.pop()
    return lines, d.get("source")


async def _tts(text, voice, path):
    import edge_tts
    await edge_tts.Communicate(text, voice, rate="-8%").save(path)


def spoken(line, voice, work):
    import hashlib
    from faster_whisper import decode_audio
    name = hashlib.sha1((voice + "|" + line).encode("utf-8")).hexdigest()[:16] + ".mp3"
    path = os.path.join(work, name)
    if not os.path.isfile(path):
        for attempt in range(3):
            try:
                asyncio.run(_tts(line, voice, path))
                break
            except Exception as e:
                if attempt == 2:
                    raise
                time.sleep(2)
    return decode_audio(path, sampling_rate=RATE)


def onset(x):
    """Where the voice starts in a spoken clip (s): the first 10 ms frame above a tenth of the loudest."""
    frames = len(x) // 160
    if frames == 0:
        return 0.0
    e = np.sqrt(np.mean(x[: frames * 160].reshape(frames, 160) ** 2, axis=1))
    k = int(np.argmax(e > 0.1 * e.max()))
    return k * 0.01


def backing(seconds, seed):
    """Chords (a few sung-range tones, changing every 2 s), pink-ish noise and a low rumble: something like a song's
    band and a car's cabin, never a recording of either."""
    rng = np.random.default_rng(seed)
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    out = np.zeros(n, dtype=np.float32)
    roots = [110.0, 130.81, 146.83, 98.0, 123.47]
    step = 2 * RATE
    for s in range(0, n, step):
        f0 = roots[rng.integers(len(roots))]
        seg = slice(s, min(n, s + step))
        for mult, amp in ((1, 0.30), (1.25, 0.18), (1.5, 0.18), (2, 0.12), (3, 0.06)):
            out[seg] += amp * np.sin(2 * np.pi * f0 * mult * t[seg] + rng.random() * 6.28)
    # a beat: a short thump every half second
    for s in range(0, n, RATE // 2):
        k = np.arange(min(1600, n - s))
        out[s:s + len(k)] += 0.5 * np.sin(2 * np.pi * 60 * k / RATE) * np.exp(-k / 300.0)
    white = rng.standard_normal(n).astype(np.float32)
    pink = np.convolve(white, np.ones(8) / 8, mode="same") * 0.35
    rumble = np.convolve(rng.standard_normal(n).astype(np.float32), np.ones(200) / 200, mode="same") * 3.0
    out += pink + rumble
    return out / (np.sqrt(np.mean(out ** 2)) + 1e-9)


def build(lines, lang, work, seed, snr_db):
    """The test song: the lines spoken in order with pauses (longer at a stanza break), over the backing."""
    rng = random.Random(seed)
    voice = VOICES.get(lang, VOICES["en"])
    clips = []
    t = 6.0  # an intro
    truth = []
    for line in lines:
        if not line:
            t += 4.0  # a stanza break: the band alone
            truth.append(None)
            continue
        x = spoken(line, voice, work)
        clips.append((t, x))
        truth.append(round(t + onset(x), 2))
        t += len(x) / RATE + rng.uniform(0.5, 1.6)
    total = t + 5.0
    voice_track = np.zeros(int(total * RATE), dtype=np.float32)
    for at, x in clips:
        s = int(at * RATE)
        voice_track[s:s + len(x)] += x
    v_rms = np.sqrt(np.mean(voice_track[voice_track != 0] ** 2)) + 1e-9
    band = backing(total, seed) * v_rms / (10 ** (snr_db / 20.0))
    mix = voice_track + band
    # a small car cabin: a short echo tail
    ir = np.zeros(int(0.12 * RATE), dtype=np.float32)
    ir[0] = 1.0
    ir[int(0.011 * RATE)] = 0.35
    ir[int(0.029 * RATE)] = 0.22
    ir[int(0.061 * RATE)] = 0.12
    mix = np.convolve(mix, ir)[: len(mix)]
    mix = mix / (np.abs(mix).max() + 1e-9) * 0.7
    return mix.astype(np.float32), truth


def chunks(audio, size_s, overlap_s):
    step = size_s - overlap_s
    out = []
    s = 0.0
    total = len(audio) / RATE
    while s < total - 3:
        a = int(s * RATE)
        b = int(min(total, s + size_s) * RATE)
        out.append((s, audio[a:b]))
        s += step
    return out


def pcm16(x):
    return (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()


def wav(x):
    import wave
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm16(x))
    return buf.getvalue()


def report(name, lines, truth, times, covered, extra=""):
    errs = []
    print("\n%s%s" % (name, extra))
    print("  line  truth     timed     error   text")
    for k, (line, tr) in enumerate(zip(lines, truth)):
        if tr is None:
            continue
        tm = None if times is None else times[k]
        if tm is None:
            print("  %3d  %7.2f      -          -    %s" % (k, tr, line[:48]))
            continue
        tm = tm / 1000.0 if tm > 1000 else tm
        e = tm - tr
        errs.append(e)
        print("  %3d  %7.2f   %7.2f   %+6.2f   %s" % (k, tr, tm, e, line[:48]))
    if errs:
        a = [abs(e) for e in errs]
        within = sum(1 for e in a if e <= 0.5) / len(a)
        print("  covered %.0f%%  |error| mean %.2f s, median %.2f s, max %.2f s; within 0.5 s: %.0f%%, within 1 s: %.0f%%" % (
            100 * covered, statistics.mean(a), statistics.median(a), max(a), 100 * within, 100 * sum(1 for e in a if e <= 1.0) / len(a)))
    return errs


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://127.0.0.1:8787")
    ap.add_argument("--key")
    ap.add_argument("--song", action="append", help='"title|artist" (repeatable)')
    ap.add_argument("--lines", type=int, default=18, help="lines of each song to speak")
    ap.add_argument("--snr", type=float, default=6.0, help="voice over the backing, dB")
    ap.add_argument("--chunk", type=float, default=20.0)
    ap.add_argument("--overlap", type=float, default=2.0)
    ap.add_argument("--file", action="store_true", help="also send the whole song as a file")
    ap.add_argument("--with-length", action="store_true", help="tell the server the song's length (a Bluetooth song often has none)")
    ap.add_argument("--direct", action="store_true", help="no server: transcribe and align here")
    ap.add_argument("--model", default="large-v3-turbo")
    ap.add_argument("--threads", type=int, default=6)
    ap.add_argument("--lyrics-from", help="--direct: a server to take the lyrics from (else the --server)")
    ap.add_argument("--work", default=os.path.join(os.environ.get("AMRI_LYRICS_CACHE") or os.path.join(HERE, "..", "..", "..", "..", "lyrics-cache"), "test"))
    args = ap.parse_args()
    work = os.path.abspath(args.work)
    os.makedirs(work, exist_ok=True)
    songs = [s.split("|", 1) for s in (args.song or ["Hello|Adele", "الأماكن|محمد عبده"])]
    import align
    summary = []
    speech = align.Transcriber(args.model, args.threads) if args.direct else None
    for title, artist in songs:
        lines, src = lyrics_lines(args.lyrics_from or args.server, args.key, title, artist, args.lines)
        lang = align.language_of(lines)
        audio, truth = build(lines, lang, work, seed=zlib.crc32(title.encode("utf-8")) & 0xffff, snr_db=args.snr)
        print("\n=== %s / %s: %d lines (lyrics from %s), %.0f s of test song, voice %s, %s dB over the backing" % (
            title, artist, len(lines), src, len(audio) / RATE, VOICES.get(lang), args.snr))
        if args.direct:
            words = []
            t0 = time.time()
            for start, piece in chunks(audio, args.chunk, args.overlap):
                heard = speech.heard(piece, lang, start, lines)
                words = align.merge(words, heard, start, start + len(piece) / RATE, edges=True)
            spent = time.time() - t0
            times, kinds, covered = align.align(lines, words, len(audio) / RATE)
            errs = report("chunks, %s (%.1f s of work for %.0f s of song, %.2fx real time)" % (args.model, spent, len(audio) / RATE, spent / (len(audio) / RATE)),
                          lines, truth, times, covered)
            summary.append((title, "chunks", covered, errs, spent))
            continue
        # through the server, as the car does
        # (a title of its own for each run: the server keeps what it heard of a song, and merges a new run into it)
        tag = time.strftime("%H%M%S")
        code, st = http("POST", args.server + "/api/sync/start", args.key,
                        json.dumps({"title": "%s test chunks %s" % (title, tag), "artist": artist, "lines": lines,
                                    "duration": round(len(audio) / RATE) if args.with_length else 0}).encode("utf-8"), "application/json")
        assert code == 200, (code, st)
        sid = st["id"]
        t0 = time.time()
        for start, piece in chunks(audio, args.chunk, args.overlap):
            code, st = http("POST", "%s/api/sync/chunk?id=%s&pos=%d&rate=%d" % (args.server, sid, int(start * 1000), RATE),
                            args.key, pcm16(piece), "application/octet-stream")
            assert code == 202, (code, st)
        while True:
            code, st = http("GET", "%s/api/sync?id=%s" % (args.server, sid), args.key)
            if st.get("pending", 0) == 0:
                break
            time.sleep(1.0)
        spent = time.time() - t0
        errs = report("chunks through the server (%.1f s until all were worked on)" % spent, lines, truth, st.get("times"), st.get("covered", 0),
                      extra="; show=%s done=%s" % (st.get("show"), st.get("done")))
        summary.append((title, "chunks", st.get("covered", 0), errs, spent))
        if args.file:
            code, st = http("POST", "%s/api/sync/start" % args.server, args.key,
                            json.dumps({"title": "%s test file %s" % (title, tag), "artist": artist, "lines": lines}).encode("utf-8"), "application/json")
            sid = st["id"]
            t0 = time.time()
            code, st = http("POST", "%s/api/sync/file?id=%s&name=test.wav" % (args.server, sid), args.key, wav(audio), "application/octet-stream")
            assert code == 202, (code, st)
            while True:
                code, st = http("GET", "%s/api/sync?id=%s" % (args.server, sid), args.key)
                if st.get("pending", 0) == 0:
                    break
                time.sleep(1.0)
            spent = time.time() - t0
            errs = report("whole file through the server (%.1f s of work)" % spent, lines, truth, st.get("times"), st.get("covered", 0))
            summary.append((title, "file", st.get("covered", 0), errs, spent))
    print("\nSummary")
    for title, how, cov, errs, spent in summary:
        a = [abs(e) for e in errs] or [float("nan")]
        print("  %-28s %-6s covered %3.0f%%  mean |err| %.2f s  median %.2f s  max %.2f s  (%.0f s of work)" % (
            title[:28], how, 100 * cov, statistics.mean(a), statistics.median(a), max(a), spent))


if __name__ == "__main__":
    main()
