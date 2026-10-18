#!/usr/bin/env python3
"""The word times by listening (1.16), end to end, as the app sends them: a test song (songmaker.py: a real song's
lines spoken, held and paused like singing, over a made-up band, every word's true time known) sent to the server in
12 s pieces with 2 s overlap and the song position of each (as the app's digital copy of the music, or through a
simulated car microphone, with the app's song clock off by --clock s), and the enhanced LRC the server answers compared
with the truth: how many lines got words, how far their starts are, the pauses inside lines found (and made up), and
how long each piece took.

    python tests/test_words.py --server http://127.0.0.1:8791 --key KEY [--song "Believer|Imagine Dragons"]
           [--mic 10] [--clock 0.6] [--untimed] [--file] [--half] [--wrong]

--half sends only the first half of the song (the later choruses then have their words from the first: "repeat");
--wrong starts the session with another song's lines (nothing should get words). Lyrics come from the server's own
search (nothing of a song's text is stored in the repository).
"""
import argparse
import json
import os
import re
import statistics
import sys
import time
import zlib

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

from test_align import http, lyrics_lines, pcm16, wav  # noqa: E402
import songmaker as sm  # noqa: E402

RATE = 16000
NOISE = os.path.join(os.environ.get("AMRI_VOICE_TEST") or r"D:\k2501-work\voice-test", "data", "corpus", "naayf", "neg_motorway.wav")
_TAG = re.compile(r"<(\d+):(\d+(?:\.\d+)?)>")
_LINE = re.compile(r"^\[(\d+):(\d+(?:\.\d+)?)\](.*)$")


def parse_enhanced(text):
    """Per LRC line: (time, [(token, start)], [pause starts after token j: (j, time)], last end or None)."""
    out = []
    for raw in (text or "").splitlines():
        m = _LINE.match(raw)
        if not m:
            continue
        t = int(m.group(1)) * 60 + float(m.group(2))
        rest = m.group(3)
        if "<" not in rest:
            out.append((t, None, [], None, rest.strip()))
            continue
        toks, pauses = [], []
        last_end = None
        # split into (tag, text) pairs
        parts = _TAG.split(rest)
        # parts: [before, mm, ss, text, mm, ss, text, ...]
        cur = None
        for i in range(1, len(parts), 3):
            tt = int(parts[i]) * 60 + float(parts[i + 1])
            txt = parts[i + 2].strip()
            if txt:
                toks.append((txt, tt))
            elif i + 3 < len(parts):
                pauses.append((len(toks) - 1, tt))
            else:
                last_end = tt
        out.append((t, toks, pauses, last_end, " ".join(x for x, _ in toks)))
    return out


def chunks(audio, size=12.0, overlap=2.0):
    step = size - overlap
    out = []
    s = 0.0
    total = len(audio) / RATE
    while s < total - 3:
        out.append((s, audio[int(s * RATE):int(min(total, s + size) * RATE)]))
        s += step
    return out


def score(song, enh, kinds=None):
    """The enhanced LRC against the truth: per line its words' start errors, pauses found / made up."""
    lines = parse_enhanced(enh)
    by_text = {}
    errs, line_med = [], []
    have = heard = rep = 0
    found = missed = made_up = 0
    n_sung = sum(1 for tr in song.truth if tr)
    j = 0
    for k, tr in enumerate(song.truth):
        # the enhanced lines are the song's lines in order (each has a time in these tests)
        if j >= len(lines):
            break
        t, toks, pauses, last_end, text = lines[j]
        j += 1
        if not tr or not toks:
            continue
        have += 1
        if kinds:
            heard += kinds[k] == "heard"
            rep += kinds[k] == "repeat"
        e = [toks[i][1] - tr[i][0] for i in range(min(len(toks), len(tr))) if tr[i]]
        errs += e
        if e:
            line_med.append(statistics.median(e))
        truth_p = {p[1] for p in song.pauses if p[0] == k}
        got_p = {p[0] for p in pauses}
        found += len(truth_p & got_p)
        missed += len(truth_p - got_p)
        for jj in got_p - truth_p:
            if 0 <= jj < len(tr) - 1 and tr[jj] and tr[jj + 1] and tr[jj + 1][0] - tr[jj][1] < 0.15:
                made_up += 1
    a = np.abs(errs) if errs else np.array([np.nan])
    return {"lines": have, "of": n_sung, "heard": heard, "repeat": rep, "med": float(np.median(errs)) if errs else float("nan"),
            "abs_med": float(np.median(a)), "p90": float(np.percentile(a, 90)), "w100": float(np.mean(a <= 0.1)),
            "w250": float(np.mean(a <= 0.25)), "pauses": found, "pauses_of": found + missed, "made_up": made_up, "n": len(errs)}


def whisper_vs_truth(song, home, sid):
    """For comparison: the recogniser's own word times (kept in the record) against the truth — each true word with
    the heard word of the same letters nearest to it (within 1 s)."""
    sys.path.insert(0, os.path.dirname(HERE))
    import textnorm as tn
    try:
        with open(os.path.join(home, "songs", sid + ".json"), encoding="utf-8") as f:
            heard = json.load(f).get("words") or []
    except (OSError, ValueError):
        return None
    by = {}
    for w in heard:
        by.setdefault(tn.word_norm(w[0]), []).append(w[1])
    errs = []
    for k, tr in enumerate(song.truth):
        if not tr:
            continue
        for j, tok in enumerate(song.lines[k].split()):
            if tr[j] is None:
                continue
            c = by.get(tn.word_norm(tok))
            if not c:
                continue
            d = min(c, key=lambda t: abs(t - tr[j][0])) - tr[j][0]
            if abs(d) <= 1.0:
                errs.append(d)
    return errs


def kinds_from_home(home, sid, n):
    try:
        with open(os.path.join(home, "songs", sid + ".json"), encoding="utf-8") as f:
            s = json.load(f)
        return [None if not w else w.get("from") for w in (s.get("wt") or [None] * n)]
    except (OSError, ValueError):
        return None


def wait_idle(server, key, sid, limit=120):
    t0 = time.time()
    while time.time() - t0 < limit:
        code, st = http("GET", "%s/api/sync?id=%s&words=1" % (server, sid), key)
        if st.get("pending", 0) == 0:
            return st, time.time() - t0
        time.sleep(0.1)
    return st, time.time() - t0


def run(args, song, lines, title, artist, how):
    tag = time.strftime("%H%M%S")
    lang = "ar" if any("\u0600" <= c <= "\u06ff" for c in "".join(lines)) else "en"
    body = {"title": "%s test %s %s" % (title, how, tag), "artist": artist, "lines": song.lines if not args.wrong else args.wrong_lines,
            "duration": round(len(song.audio) / RATE)}
    timed = not args.untimed
    if timed:
        body["times"] = [int(round(t * 1000)) for t in song.given] if not args.wrong else \
            [int(round(t * 1000)) for t in song.given[:len(args.wrong_lines)]] + [None] * max(0, len(args.wrong_lines) - len(song.given))
    code, st = http("POST", args.server + "/api/sync/start?words=1", args.key, json.dumps(body).encode("utf-8"), "application/json")
    assert code == 200, (code, st)
    sid = st["id"]
    assert st["mode"] == ("timed" if timed else "untimed"), st["mode"]
    audio = song.audio
    if args.mic is not None:
        audio = sm.car_mic(song.audio, NOISE, args.mic, -30.0, seed=7)
    pieces = chunks(audio)
    if args.half:
        pieces = pieces[: len(pieces) // 2]
    lat = []
    diags = []
    t_all = time.time()
    if args.file:
        code, st = http("POST", "%s/api/sync/file?id=%s&name=test.wav" % (args.server, sid), args.key, wav(audio), "application/octet-stream", timeout=120)
        assert code == 202, (code, st)
        st, spent = wait_idle(args.server, args.key, sid, 600)
        lat.append(spent)
        diags.append(st.get("diag"))
    else:
        last_sent = None
        for start, piece in pieces:
            pos = int(round((start + args.clock) * 1000))
            if args.realtime and last_sent is not None:
                # (as the app: a piece every 10 s, whether the server is done with the last one or not)
                time.sleep(max(0.0, 10.0 - (time.time() - last_sent)))
            t0 = time.time()
            last_sent = t0
            code, st = http("POST", "%s/api/sync/chunk?id=%s&pos=%d&rate=%d&src=%s" % (args.server, sid, pos, RATE, "mic" if args.mic is not None else "capture"),
                            args.key, pcm16(piece), "application/octet-stream")
            assert code == 202, (code, st)
            if args.realtime:
                lat.append(float(st.get("pending", 0)))
                continue
            st, _ = wait_idle(args.server, args.key, sid)
            lat.append(time.time() - t0)
            diags.append(st.get("diag"))
        if args.realtime:
            t0 = time.time()
            st, _ = wait_idle(args.server, args.key, sid, 1200)
            print("  real time: pieces waiting when each was sent %s; all worked on %.1f s after the last was sent" % (
                "/".join("%d" % x for x in lat), time.time() - t0))
            lat = [time.time() - t0]
            diags.append(st.get("diag"))
    spent = time.time() - t_all
    code, st = http("GET", "%s/api/sync?id=%s&words=1" % (args.server, sid), args.key)
    kinds = kinds_from_home(args.home, sid, len(song.lines)) if args.home else None
    r = score(song, st.get("enhanced"), kinds)
    print("\n%s (%s%s%s): %d pieces, %.1f s in all" % (title, how, ", mic %s dB" % args.mic if args.mic is not None else "",
                                                   ", clock %+.1f s" % args.clock if args.clock else "", len(lat), spent))
    print("  state: mode %s covered %.0f%% show %s done %s words %s; last diag %s" % (st.get("mode"), 100 * st.get("covered", 0), st.get("show"), st.get("done"), st.get("words"), st.get("diag")))
    print("  per piece: %.2f s mean, %.2f s max (from sending it until it was worked on); server's own work %s ms" % (
        statistics.mean(lat), max(lat), "/".join(str(d.get("ms")) for d in diags if d)[:120]))
    print("  lines with words %d/%d (heard %s, repeat %s); word starts: median %+.3f s, |err| median %.3f p90 %.3f, within 0.1 s %.0f%%, within 0.25 s %.0f%% (%d words)" % (
        r["lines"], r["of"], r["heard"] if kinds else "?", r["repeat"] if kinds else "?", r["med"], r["abs_med"], r["p90"], 100 * r["w100"], 100 * r["w250"], r["n"]))
    print("  pauses inside lines: %d of %d found, %d made up" % (r["pauses"], r["pauses_of"], r["made_up"]))
    if args.home and not args.wrong:
        we = whisper_vs_truth(song, args.home, sid)
        if we:
            a = np.abs(we)
            print("  (whisper's own word starts, for comparison: median %+.3f s, |err| median %.3f p90 %.3f, within 0.1 s %.0f%% (%d words matched))" % (
                float(np.median(we)), float(np.median(a)), float(np.percentile(a, 90)), 100 * float(np.mean(a <= 0.1)), len(we)))
    if not timed:
        tm = st.get("times") or []
        errs = [abs(tm[k] / 1000.0 - song.starts[k]) for k in range(len(song.lines)) if k < len(tm) and tm[k] is not None and song.starts[k] is not None]
        if errs:
            print("  line times by listening: |err| median %.2f s, p90 %.2f s (%d lines)" % (statistics.median(errs), np.percentile(errs, 90), len(errs)))
        rec_kinds = None
        if args.home:
            try:
                with open(os.path.join(args.home, "songs", sid + ".json"), encoding="utf-8") as f:
                    rec_kinds = json.load(f).get("kinds")
            except (OSError, ValueError):
                pass
        for k in range(len(song.lines)):
            if k < len(tm) and tm[k] is not None and song.starts[k] is not None and abs(tm[k] / 1000.0 - song.starts[k]) > 1.0:
                print("    line %d off by %+.2f s (%s): %s" % (k, tm[k] / 1000.0 - song.starts[k], rec_kinds[k] if rec_kinds else "?", song.lines[k][:50]))
    return st, r


def show_lines(song, st, n=4):
    lines = parse_enhanced(st.get("enhanced"))
    shown = 0
    for k, (t, toks, pauses, last_end, text) in enumerate(lines):
        if shown >= n or k >= len(song.truth) or not toks:
            continue
        if not any(p[0] == k for p in song.pauses):
            continue
        shown += 1
        tr = song.truth[k]
        print("\n  line %d: %s" % (k, song.lines[k]))
        print("    heard: " + "  ".join("%s %.2f" % (x, s) for x, s in toks) + ("   pauses after: %s" % ", ".join("%r at %.2f" % (toks[j][0], p) for j, p in pauses) if pauses else ""))
        print("    truth: " + "  ".join("%s %.2f" % (song.lines[k].split()[i], tr[i][0]) for i in range(len(tr)) if tr[i]) +
              "   pauses: %s" % ", ".join("%.2f-%.2f" % (a, b) for (kk, j, a, b) in song.pauses if kk == k))


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://127.0.0.1:8791")
    ap.add_argument("--key", required=True)
    ap.add_argument("--home", help="the server's home, to tell heard words from repeats in the report")
    ap.add_argument("--song", action="append", help='"title|artist" (repeatable)')
    ap.add_argument("--lines", type=int, default=24)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--mic", type=float, help="through a car's microphone: road noise this many dB under the song, -30 dBFS")
    ap.add_argument("--clock", type=float, default=0.0, help="the app's song clock this far ahead (s)")
    ap.add_argument("--untimed", action="store_true", help="without the lines' times (timed by listening)")
    ap.add_argument("--file", action="store_true", help="the whole song as a file instead of pieces")
    ap.add_argument("--half", action="store_true", help="only the first half of the song")
    ap.add_argument("--wrong", action="store_true", help="start the session with another song's lines")
    ap.add_argument("--realtime", action="store_true", help="a piece every 10 s, as the app sends them (not after each is done)")
    ap.add_argument("--show", type=int, default=3, help="lines with pauses to show")
    args = ap.parse_args()
    songs = [s.split("|", 1) for s in (args.song or ["Believer|Imagine Dragons", "هلا باللي|راشد الماجد"])]
    texts = {}
    for title, artist in songs:
        texts[(title, artist)] = lyrics_lines(args.server, args.key, title, artist, args.lines)[0]
    for title, artist in songs:
        lines = texts[(title, artist)]
        lang = "ar" if any("\u0600" <= c <= "\u06ff" for c in "".join(lines)) else "en"
        song = sm.make_song(lines, lang, seed=args.seed + (zlib.crc32(title.encode("utf-8")) & 0xff))
        cal = song.calibrate()
        print("\n=== %s / %s: %d lines, %.0f s test song, %d pauses inside lines (truth moved %+.3f s onto the sound, from %d words)" % (
            title, artist, len(lines), len(song.audio) / RATE, len(song.pauses), cal[0], cal[1]))
        if args.wrong:
            other = [v for k, v in texts.items() if k != (title, artist)]
            args.wrong_lines = other[0] if other else list(reversed(lines))
        how = "file" if args.file else ("half" if args.half else "pieces")
        how = ("untimed " if args.untimed else "timed ") + how + (" wrong lyrics" if args.wrong else "")
        st, r = run(args, song, lines, title, artist, how)
        if not args.wrong:
            show_lines(song, st, args.show)


if __name__ == "__main__":
    main()
