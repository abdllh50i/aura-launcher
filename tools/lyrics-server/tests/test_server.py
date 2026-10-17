#!/usr/bin/env python3
"""The server's checks against a running server: the key, the lyrics of real songs (English, Arabic, Gulf; which
source answered), what is kept (a second question answered from disk), a miss and its keeping, and the timing
requests' refusals.

    python tests/test_server.py --server http://127.0.0.1:8787 --key KEY [--fresh]

--fresh asks every source again the first time (the second question of each song is then the kept answer).
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SONGS = [
    # title, artist, length (s, 0: unknown, as a phone over Bluetooth tells none)
    ("Hello", "Adele", 295),
    ("Thunder", "Imagine Dragons", 0),
    ("Shape of You", "Ed Sheeran", 0),
    ("Bohemian Rhapsody", "Queen", 0),
    ("الأماكن", "محمد عبده", 0),
    ("Al Amaken", "Mohammed Abdu", 0),
    ("المسافر", "راشد الماجد", 0),
    ("بشرة خير", "حسين الجسمي", 0),
    ("ابعتذر", "عبدالمجيد عبدالله", 0),
    ("تملي معاك", "عمرو دياب", 0),
    ("3 Daqat", "Abu", 0),
]
NOBODY = ("Zxqv Plorbt Wemmick", "Nobody Known Atall", 0)


def call(method, url, key=None, body=None, ctype=None, header=True):
    req = urllib.request.Request(url, data=body, method=method)
    if key and header:
        req.add_header("X-Lyrics-Key", key)
    if ctype:
        req.add_header("Content-Type", ctype)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return r.status, json.loads(r.read().decode("utf-8")), time.time() - t0
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8") or "{}"), time.time() - t0
        except ValueError:
            return e.code, {}, time.time() - t0


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://127.0.0.1:8787")
    ap.add_argument("--key", required=True)
    ap.add_argument("--fresh", action="store_true")
    a = ap.parse_args()
    s = a.server.rstrip("/")
    failures = []

    def check(name, ok, detail=""):
        print("  %-52s %s %s" % (name, "ok " if ok else "FAIL", detail))
        if not ok:
            failures.append(name)

    print("The key")
    code, d, _ = call("GET", s + "/health")
    check("/health without a key answers", code == 200 and d.get("service") == "amri-lyrics", str(code))
    code, d, _ = call("GET", s + "/api/ping")
    check("/api/ping without a key is refused", code == 401, str(code))
    code, d, _ = call("GET", s + "/api/ping", key="wrongkey22")
    check("/api/ping with a wrong key is refused", code == 401, str(code))
    code, d, _ = call("GET", s + "/api/lyrics?title=Hello&artist=Adele", key="wrongkey22")
    check("/api/lyrics with a wrong key is refused", code == 401, str(code))
    code, d, _ = call("POST", s + "/api/sync/start", key=None, body=b"{}", ctype="application/json")
    check("/api/sync/start without a key is refused", code == 401, str(code))
    code, d, _ = call("GET", s + "/api/ping", key=a.key)
    check("/api/ping with the key (header)", code == 200 and d.get("ok"), str(code))
    code, d, _ = call("GET", s + "/api/ping?key=" + urllib.parse.quote(a.key), key=a.key, header=False)
    check("/api/ping with the key (?key=)", code == 200 and d.get("ok"), str(code))
    code, d, _ = call("GET", s + "/api/ping", key=a.key.upper())
    check("the key typed in capitals", code == 200, str(code))

    print("\nLyrics of real songs (first question%s, then the same again)" % (": every source asked" if a.fresh else ""))
    print("  %-22s %-20s %-10s %-7s %6s %6s  %s" % ("title", "artist", "source", "kind", "first", "again", "what each source said"))
    for title, artist, dur in SONGS + [NOBODY]:
        q = {"title": title, "artist": artist}
        if dur:
            q["duration"] = dur
        url = s + "/api/lyrics?" + urllib.parse.urlencode(q)
        code1, d1, t1 = call("GET", url + ("&fresh=1" if a.fresh else ""), key=a.key)
        code2, d2, t2 = call("GET", url, key=a.key)
        kind = "timed" if d1.get("synced") else ("plain" if d1.get("plain") else "none")
        tried = ", ".join("%s:%s" % (x["source"], x["result"]) for x in d1.get("tried", []))
        print("  %-22s %-20s %-10s %-7s %5.1fs %5.2fs  %s" % (title[:22], artist[:20], d1.get("source"), kind, t1, t2, tried))
        m = d1.get("match")
        if m:
            print("  %-43s matched: %s / %s (%s s)" % ("", m.get("title"), m.get("artist"), m.get("duration")))
        if code1 != 200 or code2 != 200:
            check("HTTP 200 for %s" % title, False, "%s %s" % (code1, code2))
        elif not d2.get("cached"):
            check("second question from disk: %s" % title, False)
        elif (d1.get("synced") or "") != (d2.get("synced") or "") or (d1.get("plain") or "") != (d2.get("plain") or ""):
            check("kept answer is the same: %s" % title, False)
    code, d, _ = call("GET", s + "/api/lyrics?" + urllib.parse.urlencode({"title": NOBODY[0], "artist": NOBODY[1]}), key=a.key)
    check("a song nobody has: none, kept", code == 200 and d.get("source") == "none" and d.get("cached"), "%s %s" % (d.get("source"), d.get("cached")))

    print("\nTiming requests that must be refused")
    code, d, _ = call("POST", s + "/api/sync/start", key=a.key, body=b'{"title": ""}', ctype="application/json")
    check("start without a title", code == 400, str(code))
    code, d, _ = call("POST", s + "/api/sync/start", key=a.key, body=b"not json", ctype="application/json")
    check("start with a body that is not JSON", code == 400, str(code))
    code, d, _ = call("POST", s + "/api/sync/chunk?id=0123456789abcdef&pos=0&rate=16000", key=a.key, body=b"\0\0" * 16000 * 4, ctype="application/octet-stream")
    check("a chunk for a song never started", code == 409, str(code))
    code, d, _ = call("GET", s + "/api/sync?id=../../etc", key=a.key)
    check("a song id that is not one", code == 404, str(code))
    code, st, _ = call("POST", s + "/api/sync/start", key=a.key, body=json.dumps({"title": "Test song", "artist": "Nobody", "lines": ["one two three", "four five six"]}).encode(), ctype="application/json")
    check("start a song", code == 200 and st.get("id"), str(code))
    code, d, _ = call("POST", s + "/api/sync/chunk?id=%s&pos=0&rate=16000" % st.get("id"), key=a.key, body=b"\0\0" * 8000, ctype="application/octet-stream")
    check("a chunk shorter than 3 s", code == 400, str(code))
    code, d, _ = call("POST", s + "/api/sync/chunk?id=%s&pos=-5&rate=16000" % st.get("id"), key=a.key, body=b"\0\0" * 16000 * 4, ctype="application/octet-stream")
    check("a chunk at a negative position", code == 400, str(code))
    print("\n%s" % ("all checks passed" if not failures else "FAILED: " + ", ".join(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
