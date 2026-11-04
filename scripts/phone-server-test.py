#!/usr/bin/env python3
"""
Real HTTP calls against link/PhoneServer.kt on an emulator, through adb forward (docs/DEV.md, "A place from the phone").

    python scripts/phone-server-test.py [--serial emulator-5572] [--port 47101] [--only name,name] [--list]

Needs the debug build installed and on the screen, with the overlay permission:
    adb shell appops set com.abdllh.aura SYSTEM_ALERT_WINDOW allow
Sets its own debug properties and switches the server on. Exit code 1 when something failed.

It never touches anything but the given serial. A route needs OSRM (or the offline map): a run without internet shows
the route's failure path instead, and says so.
"""
import argparse
import base64
import http.client
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time

ADB = r"E:\Android\Sdk\platform-tools\adb.exe"
HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURES = os.path.join(HERE, "..", "tools", "link-fixtures", "share.json")

results = []
RUN = str(int(time.time()))


def I(name):
    """An id for this run: the server remembers the last 64 for as long as it lives."""
    return name + "-" + RUN


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (("   " + str(detail)) if detail and not ok else ""))
    return ok


def adb(*args):
    return subprocess.run([ADB, "-s", SERIAL] + list(args), capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def shell(cmd):
    return adb("shell", cmd)


def req(method, path, body=None, token=None, headers=None, timeout=10, raw_body=None):
    c = http.client.HTTPConnection("127.0.0.1", PORT, timeout=timeout)
    h = dict(headers or {})
    data = None
    if raw_body is not None:
        data = raw_body
    elif body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        h.setdefault("Content-Type", "application/json")
    if token:
        h["Authorization"] = "Bearer " + token
    c.request(method, path, body=data, headers=h)
    r = c.getresponse()
    raw = r.read()
    c.close()
    try:
        j = json.loads(raw.decode("utf-8"))
    except Exception:
        j = None
    return r.status, j, raw, dict(r.getheaders())


class Events:
    """GET /v1/events on a thread: every event seen, with when."""

    def __init__(self, token):
        self.events = []
        self.heartbeats = 0
        self.alive = True
        self.sock = socket.create_connection(("127.0.0.1", PORT), timeout=40)
        self.sock.sendall(("GET /v1/events HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nAuthorization: Bearer %s\r\n\r\n" % (PORT, token)).encode())
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def run(self):
        buf = b""
        try:
            while True:
                d = self.sock.recv(4096)
                if not d:
                    break
                buf += d
                while b"\n\n" in buf:
                    chunk, buf = buf.split(b"\n\n", 1)
                    text = chunk.decode("utf-8", "replace")
                    if text.startswith(":") or "\n:" in text:
                        self.heartbeats += 1
                    ev, data = None, None
                    for line in text.split("\n"):
                        if line.startswith("event: "):
                            ev = line[7:]
                        elif line.startswith("data: "):
                            data = line[6:]
                    if ev and data:
                        self.events.append((time.time(), ev, json.loads(data)))
        except Exception:
            pass
        self.alive = False

    def nav(self):
        return [e[2] for e in self.events if e[1] == "nav"]

    def wait(self, pred, timeout=20):
        end = time.time() + timeout
        while time.time() < end:
            for e in self.nav():
                if pred(e):
                    return e
            time.sleep(0.1)
        return None

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


def set_debug(value):
    shell("setprop debug.aura.phoneserver %s" % (value if value else "''"))


def fix(lat=26.40, lon=50.09):
    # the emulator's own GPS (it reports again by itself), and the debug hook for a fix right now
    adb("emu", "geo", "fix", str(lon), str(lat))
    shell("am start -n com.abdllh.aura/.nav.MapsActivity --es mock_loc %s,%s" % (lat, lon))
    time.sleep(1.5)


def home():
    shell("input keyevent HOME")
    time.sleep(1)


def stop_trip(token):
    req("POST", "/v1/nav/stop", {"id": I("t") + str(time.time())}, token)
    time.sleep(0.5)


def shot(name):
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", os.path.join(HERE, "shot.ps1"),
                    "-Name", name, "-Serial", SERIAL], capture_output=True)


def tap_card(which):
    """The card's buttons, by position on the 1024 x 600 screen (the card is at the top, its buttons at the left in Arabic, the right in English)."""
    x, y = which
    shell("input tap %d %d" % (x, y))


# --------------------------------------------------------------------------------------------------- the tests
def t_peers():
    set_debug("")
    time.sleep(0.2)
    try:
        s, j, _, _ = req("GET", "/v1/ping")
        check("loopback refused without the debug property", s == 403 and j == {"reason": "refused"}, (s, j))
    except Exception as e:
        check("loopback refused without the debug property", False, e)
    set_debug("1")
    s, j, _, _ = req("GET", "/v1/ping")
    check("ping for a private peer, no secrets", s == 200 and j["app"] == "AMRI" and j["proto"] == 2 and "unit" in j and "token" not in j, j)
    for method, path, body in [("GET", "/v1/state", None), ("GET", "/v1/events", None), ("GET", "/v1/places", None), ("GET", "/v1/diag", None),
                               ("POST", "/v1/nav/go", {"id": I("p1"), "place": {"lat": 26.3, "lon": 50.2}}), ("POST", "/v1/share", {"text": "26.3,50.2"}),
                               ("POST", "/v1/nav/stop", {}), ("POST", "/v1/nav/saved", {"which": "home"}), ("POST", "/v1/search", {"q": "abc"}),
                               ("POST", "/v1/remote", {"cmd": "music.toggle"}), ("PUT", "/v1/places", {})]:
        s, j, _, _ = req(method, path, body)
        check("other peer refused: %s %s" % (method, path), s == 403, (s, j))
    s, _, raw, h = req("GET", "/")
    check("the page for any private peer", s == 200 and b"<html" in raw and "Content-Security-Policy" in h, s)
    check("the page is under 50 KB", len(raw) < 50 * 1024, len(raw))
    s, j, _, _ = req("POST", "/v1/pair", {"code": "000000", "device": "x"})
    check("no pairing while no code shows", s == 403 and j["reason"] == "no_code", (s, j))
    # the rule itself, with the addresses a real network has
    for addr, gw, expect in [("172.20.10.1", "172.20.10.1", "GATEWAY"), ("172.20.10.3", "172.20.10.1", "PRIVATE"), ("192.168.1.7", None, "PRIVATE"),
                             ("10.1.2.3", None, "PRIVATE"), ("169.254.1.1", None, "PRIVATE"), ("172.32.0.1", None, "REFUSED"), ("8.8.8.8", "172.20.10.1", "REFUSED"),
                             ("fe80::1", None, "PRIVATE"), ("fd00::1", None, "PRIVATE"), ("2001:4860::1", None, "REFUSED"), ("::ffff:172.20.10.1", "172.20.10.1", "GATEWAY"),
                             ("127.0.0.1", None, "REFUSED")]:
        s, j, _, _ = req("POST", "/debug/peer", {"address": addr, "gateway": gw or ""})
        check("peer %s is %s" % (addr, expect), s == 200 and j["peer"] == expect, j)
    set_debug("gateway")
    s, j, _, _ = req("GET", "/v1/state")
    check("gateway without a token: pair first (401)", s == 401, (s, j))


def t_web_safety():
    set_debug("gateway")
    s, j, _, _ = req("POST", "/v1/nav/go", raw_body=json.dumps({"place": {"lat": 26.3, "lon": 50.2}}).encode(), headers={"Content-Type": "text/plain"})
    check("POST as text/plain refused (415)", s == 415, (s, j))
    s, j, _, _ = req("POST", "/v1/nav/go", raw_body=b"{}", headers={"Content-Type": "application/x-www-form-urlencoded"})
    check("POST as a form refused (415)", s == 415, (s, j))
    s, j, _, h = req("OPTIONS", "/v1/nav/go", headers={"Origin": "http://evil.example", "Access-Control-Request-Method": "POST"})
    check("preflight fails, no CORS allowance", s in (403, 405) and not any(k.lower().startswith("access-control") for k in h), (s, h))
    s, j, _, _ = req("POST", "/v1/share", {"text": "26.3,50.2"}, headers={"Origin": "http://evil.example"})
    check("a mismatched Origin refused", s == 403 and j["reason"] == "origin", (s, j))
    s, j, _, _ = req("POST", "/v1/share", {"text": "26.3,50.2"}, headers={"Origin": "null"})
    check("Origin null refused", s == 403 and j["reason"] == "origin", (s, j))
    s, j, _, _ = req("GET", "/v1/ping", headers={"Host": "evil.example"})
    check("another Host refused (DNS rebinding)", s == 403 and j["reason"] == "host", (s, j))
    s, j, _, _ = req("GET", "/v1/ping", headers={"Host": "127.0.0.1:47101", "Origin": "http://127.0.0.1:47101"})
    check("the page's own Origin passes", s == 200, (s, j))
    s, j, _, _ = req("GET", "/v1/ping", headers={"Host": "[fe80::1]:47101"})
    check("an IPv6 Host passes", s == 200, (s, j))


def t_bad_bodies(token):
    s, j, _, _ = req("POST", "/v1/nav/go", raw_body=b"{not json", headers={"Content-Type": "application/json"}, token=token)
    check("bad JSON: 400", s == 400 and j["reason"] == "bad_json", (s, j))
    s, j, _, _ = req("POST", "/v1/nav/go", raw_body=b"[" * 60000, headers={"Content-Type": "application/json"}, token=token)
    check("deep JSON does not hurt: 400", s == 400, (s, j))
    for place in [None, {"lat": 95, "lon": 50}, {"lat": 26, "lon": 190}, {"lat": "x", "lon": 50}, {"lat": 0, "lon": 0}, {"name": "no coordinates"}]:
        s, j, _, _ = req("POST", "/v1/nav/go", {"id": I("bad"), "place": place} if place else {"id": I("bad")}, token)
        check("bad place %s: 400" % place, s == 400 and j["reason"] == "bad_place", (s, j))
    s, j, _, _ = req("POST", "/v1/nav/go", {"id": I("x") * 65, "place": {"lat": 26.3, "lon": 50.2}}, token)
    check("too long an id: 400", s == 400, (s, j))
    s, j, _, _ = req("POST", "/v1/nav/go", {"id": I("m"), "place": {"lat": 26.3, "lon": 50.2}, "mode": "launch"}, token)
    check("unknown mode: 400", s == 400, (s, j))
    s, j, _, _ = req("POST", "/v1/remote", {"cmd": "self.destruct"}, token)
    check("unknown remote command: 400", s == 400, (s, j))
    s, j, _, _ = req("POST", "/v1/nav/saved", {"which": "gym"}, token)
    check("unknown saved place: 400", s == 400, (s, j))
    s, j, _, _ = req("POST", "/v1/search", {"q": ""}, token)
    check("empty search: 400", s == 400, (s, j))
    s, j, _, _ = req("POST", "/v1/share", {"text": ""}, token)
    check("empty share: 400", s == 400, (s, j))
    s, j, _, _ = req("POST", "/v1/nav/go", raw_body=b"x" * 70000, headers={"Content-Type": "application/json"}, token=token)
    check("a body over 64 KB: 413", s == 413, (s, j))
    c = socket.create_connection(("127.0.0.1", PORT), timeout=10)
    c.sendall(b"POST /v1/nav/go HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n\r\n")
    a = c.recv(200)
    c.close()
    check("no length: 411", b" 411 " in a, a)
    c = socket.create_connection(("127.0.0.1", PORT), timeout=10)
    c.sendall(b"NOT HTTP\r\n\r\n")
    a = c.recv(200)
    c.close()
    check("not HTTP: 400", b" 400 " in a, a)
    c = socket.create_connection(("127.0.0.1", PORT), timeout=10)
    c.sendall(b"GET /v1/ping HTTP/1.1\r\nHost: 127.0.0.1\r\n" + b"X-Long: " + b"a" * 20000 + b"\r\n\r\n")
    a = c.recv(200)
    c.close()
    check("a head over the limit: 431", b" 431 " in a, a)


def t_pair():
    set_debug("1")
    req("POST", "/debug/forget", {})
    s, j, _, _ = req("POST", "/debug/code", {})
    code = j["code"]
    check("a six digit code", len(code) == 6 and code.isdigit(), code)
    s, j, _, _ = req("POST", "/v1/pair", {"code": "000000" if code != "000000" else "111111", "device": "test"})
    check("a wrong code refused, tries counted", s == 403 and j["reason"] == "bad_code" and j["triesLeft"] == 4, (s, j))
    s, j, _, _ = req("POST", "/v1/pair", {"code": code, "device": "test phone"})
    check("the code gives a token", s == 200 and len(j["token"]) == 64 and "unit" in j and "name" in j, (s, j))
    token = j["token"]
    s, j, _, _ = req("POST", "/v1/pair", {"code": code, "device": "again"})
    check("the code works once", s == 403, (s, j))
    s, j, _, _ = req("GET", "/v1/state", token=token)
    check("the token works, from any private peer", s == 200 and "nav" in j and "music" in j and "car" in j and "link" in j, (s, j))
    s, j, _, _ = req("GET", "/v1/state", token="f" * 64)
    check("another token does not", s == 403, (s, j))
    # five wrong tries end a code
    s, j, _, _ = req("POST", "/debug/code", {})
    code = j["code"]
    wrong = "000000" if code != "000000" else "111111"
    for i in range(5):
        req("POST", "/v1/pair", {"code": wrong, "device": "x"})
    s, j, _, _ = req("POST", "/v1/pair", {"code": code, "device": "x"})
    check("five wrong tries end the code", s == 403, (s, j))
    req("POST", "/debug/hidecode", {})
    pf = shell("run-as com.abdllh.aura cat shared_prefs/aura.xml")
    check("the token itself is not on the disk", token not in pf and "phoneTokens" in pf, "see prefs")
    log = adb("logcat", "-d", "-t", "3000")
    check("the token is not in the log", token not in log, "in the log!")
    return token


def t_endpoints(token):
    s, j, raw, h = req("GET", "/v1/diag", token=token)
    check("diag: a text report", s == 200 and b"AMRI" in raw and "text/plain" in h.get("Content-Type", "") and token.encode() not in raw, raw[:100])
    s, j, _, _ = req("GET", "/v1/places", token=token)
    check("places: all four lists", s == 200 and all(k in j for k in ("home", "work", "favourites", "recents")), j)
    old_home = j["home"]
    s, j, _, _ = req("PUT", "/v1/places", {"favourites": [{"name": "مكان", "detail": "تفصيل", "lat": 26.3, "lon": 50.1}, {"name": "bad", "lat": 99, "lon": 1}, {"name": "apple", "lat": 26.1, "lon": 50.1, "src": "share.apple"}]}, token)
    check("PUT places: favourites kept, the bad and Apple's left out", s == 200 and [p["name"] for p in j["favourites"]] == ["مكان"], j)
    s, j, _, _ = req("PUT", "/v1/places", {"home": {"name": "البيت", "lat": 26.31, "lon": 50.21}}, token)
    check("PUT places: home set", s == 200 and j["home"]["name"] == "البيت", j)
    s, j, _, _ = req("PUT", "/v1/places", {"home": {"name": "x", "lat": 26.31, "lon": 50.21, "src": "share.apple"}}, token)
    check("PUT places: Apple's place not kept", s == 409 and j["reason"] == "apple_not_kept", (s, j))
    s, j, _, _ = req("PUT", "/v1/places", {"work": None}, token)
    check("PUT places: work cleared with null", s == 200 and j["work"] is None, j)
    s, j, _, _ = req("POST", "/v1/nav/saved", {"which": "work"}, token)
    check("saved: a place not set says so (409)", s == 409 and j["reason"] == "not_set", (s, j))
    for cmd in ["volume.up", "volume.down", "voice.mute", "voice.mute", "music.toggle", "music.next", "music.prev"]:
        s, j, _, _ = req("POST", "/v1/remote", {"cmd": cmd}, token)
        check("remote %s" % cmd, s == 200 and j["state"] == "ok" and "volume" in j, (s, j))
    s, j, _, _ = req("POST", "/v1/search", {"q": "pharmacy", "on": "phone"}, token, timeout=15)
    check("search for the phone answers with results, or that it cannot", s == 200 and j["state"] in ("ok", "no_answer"), (s, j))
    print("     search: %s" % (json.dumps(j, ensure_ascii=False)[:200]))
    return old_home


def t_share_fixtures():
    f = json.load(open(FIXTURES, encoding="utf-8"))
    print("     %d fixtures" % len(f["cases"]))
    for case in f["cases"]:
        red = {}
        for k, v in case.get("redirects", {}).items():
            if v != "error":
                red[k] = v
        s, j, _, _ = req("POST", "/debug/parse", {"text": case["input"]["text"], "redirects": red})
        e = case["expect"]
        ok = s == 200 and j["kind"] == e["kind"]
        if ok:
            for k, v in e.items():
                if k in ("kind", "tolerance"):
                    continue
                if k in ("lat", "lon"):
                    ok = ok and abs(j[k] - v) <= e.get("tolerance", 0.000001)
                else:
                    ok = ok and j.get(k, "") == v
        check("share: " + case["name"], ok, json.dumps(j, ensure_ascii=False))


def t_share_live():
    """The real redirects of the report's links, when this emulator has the internet."""
    s, j, _, _ = req("POST", "/debug/parse", {"text": "https://maps.app.goo.gl/Q6ZugPBVWvuiVb8e8", "network": True}, timeout=20)
    print("     live google short link: %s" % json.dumps(j, ensure_ascii=False))
    if j["kind"] == "unresolved" and j["reason"] == "network":
        print("     (no internet from this emulator: the live links were not tried)")
        return
    check("live: Google's short link gives the report's place", j["kind"] == "place" and abs(j["lat"] - 42.5784957) < 1e-6 and abs(j["lon"] - 1.8955661) < 1e-6, j)
    s, j, _, _ = req("POST", "/debug/parse", {"text": "https://maps.app.goo.gl/jVuuNEZ_-FQ3UGhX7", "network": True}, timeout=20)
    check("live: the dead link says so", j["kind"] == "unresolved", j)
    print("     live dead link: %s" % json.dumps(j, ensure_ascii=False))
    s, j, _, _ = req("POST", "/debug/parse", {"text": "https://maps.apple/p/7E-Brjrk_THN14", "network": True}, timeout=20)
    print("     live apple short link: %s" % json.dumps(j, ensure_ascii=False))
    check("live: Apple's short link gives the report's place", j["kind"] == "place" and abs(j["lat"] - 52.4737758) < 1e-6, j)


def qr_decode(png):
    import cv2
    import numpy as np
    img = cv2.imdecode(np.frombuffer(png, np.uint8), cv2.IMREAD_GRAYSCALE)
    data, points, _ = cv2.QRCodeDetector().detectAndDecode(img)
    return data


def t_qr():
    import random
    import string
    # every version (M, byte mode) by its capacity limits, the real pairing address, Arabic, and random fill for the masks
    caps = [14, 26, 42, 62, 84, 106, 122, 152, 180, 213]
    texts = ["http://172.20.10.3:47101/p/123456", "http://192.168.1.100:47101/p/000000", "مرحبا عمري"]
    for i, cap in enumerate(caps):
        rng = random.Random(i)
        texts.append("".join(rng.choice(string.ascii_letters + string.digits) for _ in range(cap)))
        texts.append("x" * (cap if i == 0 else caps[i - 1] + 1))
    for i in range(16):
        rng = random.Random(100 + i)
        texts.append("http://10.%d.%d.%d:47101/p/%06d" % (rng.randint(0, 255), rng.randint(0, 255), rng.randint(1, 254), rng.randint(0, 999999)))
    sizes = set()
    for t in texts:
        s, j, raw, h = req("POST", "/debug/qr", {"text": t, "scale": 6})
        if s != 200:
            check("qr: encodes %r" % t[:30], False, (s, j))
            continue
        sizes.add(int(h["X-Qr-Size"]))
        got = qr_decode(raw)
        check("qr: reads back (%d bytes, %s modules)" % (len(t.encode()), h["X-Qr-Size"]), got == t, repr(got)[:60])
    check("qr: versions 1 to 10 all made", sizes >= {21 + 4 * v for v in range(10)}, sorted(sizes))
    s, j, _, _ = req("POST", "/debug/qr", {"text": "y" * 214})
    check("qr: over version 10 says so", s == 422, (s, j))


def t_retries(token, routes):
    home()
    fix()
    ev = Events(token)
    time.sleep(0.5)
    body = {"id": I("retry-1"), "place": {"name": "اختبار الإعادة", "detail": "الدمام", "lat": 26.3, "lon": 50.2, "src": "test"}, "mode": "start"}
    s1, j1, _, _ = req("POST", "/v1/nav/go", body, token)
    check("a place: 202 accepted", s1 == 202 and j1 == {"id": I("retry-1"), "state": "accepted"}, (s1, j1))
    answers = []
    def again():
        answers.append(req("POST", "/v1/nav/go", body, token))
    th = [threading.Thread(target=again) for _ in range(3)]
    for t in th:
        t.start()
    for t in th:
        t.join()
    check("the same id again: duplicate, at once and in parallel", all(a[0] == 202 and a[1]["state"] == "duplicate" for a in answers), [a[1] for a in answers])
    e = ev.wait(lambda x: x.get("id") == I("retry-1") and x["state"] in ("started", "failed"), 45)
    check("then started or failed, for that id", e is not None, ev.nav())
    print("     %s" % e)
    routing = [x for x in ev.nav() if x.get("id") == I("retry-1") and x["state"] == "routing"]
    check("routing was said once", len(routing) == 1, routing)
    final = [x for x in ev.nav() if x.get("id") == I("retry-1") and x["state"] in ("started", "failed")]
    check("one trip: started or failed once", len(final) == 1, final)
    s, j, _, _ = req("POST", "/v1/nav/go", body, token)
    check("a retry afterwards says what became of it", s == 202 and j["state"] == "duplicate" and j["last"] == (e or {}).get("state"), (s, j))
    ev.close()
    stop_trip(token)
    return e is not None and e["state"] == "started"


def t_flow(token, routes):
    """The whole thing, with screenshots: POST a place, the route starts on the screen, cancel, stop."""
    home()
    fix()
    home()
    ev = Events(token)
    time.sleep(0.5)
    place = {"name": "الراشد مول", "detail": "الخبر", "lat": 26.290457, "lon": 50.184555, "src": "test"}
    s, j, _, _ = req("POST", "/v1/nav/go", {"id": I("flow-1"), "place": place, "mode": "start"}, token)
    check("flow: accepted", s == 202, (s, j))
    time.sleep(0.4)
    shot("v2-link-flow-routing")
    e = ev.wait(lambda x: x.get("id") == I("flow-1") and x["state"] in ("started", "failed"), 45)
    check("flow: an answer", e is not None, ev.nav())
    print("     %s" % e)
    time.sleep(1.0)
    shot("v2-link-flow-started" if e and e["state"] == "started" else "v2-link-flow-failed")
    front = shell("dumpsys activity activities | grep mResumedActivity")
    check("flow: Maps came forward", "MapsActivity" in front, front)
    s, j, _, _ = req("GET", "/v1/state", token=token)
    print("     state nav: %s" % json.dumps(j["nav"], ensure_ascii=False))
    if e and e["state"] == "started":
        check("flow: started has the route's numbers", all(k in e for k in ("distanceM", "durationS", "etaLocal")), e)
        check("flow: the state says navigating", j["nav"]["state"] in ("navigating", "arrived"), j["nav"])
        time.sleep(6)  # the cancel window goes
        shot("v2-link-flow-after-window")
        s, j, _, _ = req("POST", "/v1/nav/stop", {"id": I("flow-stop")}, token)
        check("flow: stop ends the trip", s == 200 and j["state"] == "stopped", (s, j))
        time.sleep(0.5)
        s, j, _, _ = req("GET", "/v1/state", token=token)
        check("flow: idle after the stop", j["nav"]["state"] == "idle", j["nav"])
        s, j, _, _ = req("POST", "/v1/nav/stop", {"id": I("flow-stop-2")}, token)
        check("flow: a second stop says idle", s == 200 and j["state"] == "idle", (s, j))
    ev.close()


def t_cancel_card(token, routes):
    """The five seconds' visible cancel: a tap on the card's cancel button."""
    if not routes:
        print("     (no route on this emulator: the cancel window was not tried)")
        return
    home()
    fix()
    ev = Events(token)
    time.sleep(0.5)
    s, j, _, _ = req("POST", "/v1/nav/go", {"id": I("cancel-1"), "place": {"name": "اختبار الإلغاء", "lat": 26.29, "lon": 50.18}, "mode": "start"}, token)
    e = ev.wait(lambda x: x.get("id") == I("cancel-1") and x["state"] == "started", 45)
    check("cancel: started", e is not None, ev.nav())
    time.sleep(0.5)
    shot("v2-link-cancel-window")
    # the button (English: the card's end, right; the emulator runs in the app's language: see the shot)
    tap_card(CARD_NARROW_CANCEL)
    e = ev.wait(lambda v: v.get("id") == I("cancel-1") and v["state"] == "cancelled", 5)
    check("cancel: the tap cancelled it", e is not None and e.get("by") == "card", ev.nav())
    time.sleep(0.5)
    s, j, _, _ = req("GET", "/v1/state", token=token)
    check("cancel: nothing is running after it", j["nav"]["state"] == "idle", j["nav"])
    shot("v2-link-cancel-done")
    ev.close()


def t_navigating(token, routes):
    """A place while on a trip: the card; nobody answers: the trip goes on. Never a silent change."""
    if not routes:
        print("     (no route on this emulator: the trip was not tried)")
        return
    home()
    fix()
    ev = Events(token)
    time.sleep(0.5)
    req("POST", "/v1/nav/go", {"id": I("nav-1"), "place": {"name": "الرحلة الأولى", "lat": 26.29, "lon": 50.18}, "mode": "start"}, token)
    check("on a trip: first started", ev.wait(lambda x: x.get("id") == I("nav-1") and x["state"] == "started", 45) is not None, ev.nav())
    time.sleep(6)
    req("POST", "/v1/nav/go", {"id": I("nav-2"), "place": {"name": "المكان الثاني", "lat": 26.35, "lon": 50.15}, "mode": "start"}, token)
    e = ev.wait(lambda x: x.get("id") == I("nav-2") and x["state"] == "card", 5)
    check("on a trip: a card, kind replace", e is not None and e.get("kind") == "replace", ev.nav())
    time.sleep(0.5)
    shot("v2-link-replace-card")
    s, j, _, _ = req("GET", "/v1/state", token=token)
    check("on a trip: the trip has not changed", j["nav"]["place"]["name"] == "الرحلة الأولى", j["nav"])
    t0 = time.time()
    e = ev.wait(lambda x: x.get("id") == I("nav-2") and x["state"] == "ignored", 24)
    check("on a trip: after 20 s it keeps the trip", e is not None and e.get("kept") and 17 < time.time() - t0 < 24, (e, time.time() - t0))
    s, j, _, _ = req("GET", "/v1/state", token=token)
    check("on a trip: still the first trip", j["nav"]["place"]["name"] == "الرحلة الأولى", j["nav"])
    # and answered: replace
    req("POST", "/v1/nav/go", {"id": I("nav-3"), "place": {"name": "المكان الثالث", "lat": 26.35, "lon": 50.15}, "mode": "start"}, token)
    ev.wait(lambda x: x.get("id") == I("nav-3") and x["state"] == "card", 5)
    time.sleep(0.5)
    tap_card(CARD_REPLACE)
    e = ev.wait(lambda x: x.get("id") == I("nav-3") and x["state"] in ("started", "failed"), 45)
    check("on a trip: Replace starts the new one", e is not None and e["state"] == "started", ev.nav())
    s, j, _, _ = req("GET", "/v1/state", token=token)
    check("on a trip: now the new trip", j["nav"]["place"]["name"] == "المكان الثالث", j["nav"])
    ev.close()
    stop_trip(token)


def t_held(token, routes):
    """In a call and in reverse the place waits, then the card asks."""
    home()
    fix()
    home()
    ev = Events(token)
    time.sleep(0.5)
    shell("am broadcast -a com.bt.ACTION_BT_INCOMING_CALL --es extra_phone_number 0500000000")
    time.sleep(0.5)
    s, j, _, _ = req("POST", "/v1/nav/go", {"id": I("held-1"), "place": {"name": "أثناء المكالمة", "lat": 26.29, "lon": 50.18}, "mode": "start"}, token)
    e = ev.wait(lambda x: x.get("id") == I("held-1") and x["state"] == "held", 4)
    check("in a call: held", e is not None and e.get("reason") == "call", ev.nav())
    time.sleep(1.5)
    front = shell("dumpsys activity activities | grep mResumedActivity")
    check("in a call: nothing came forward", "MapsActivity" not in front, front)
    shell("am broadcast -a com.bt.ACTION_BT_END_CALL")
    e = ev.wait(lambda x: x.get("id") == I("held-1") and x["state"] == "card", 4)
    check("after the call: the card asks", e is not None and e.get("kind") == "ask", ev.nav())
    time.sleep(0.5)
    shot("v2-link-after-call-card")
    tap_card(CARD_SECOND)
    e = ev.wait(lambda x: x.get("id") == I("held-1") and x["state"] == "ignored", 4)
    check("after the call: No is no", e is not None, ev.nav())
    # reverse
    shell("am broadcast -a com.abdllh.aura.debug.GEAR --ez reverse true")
    time.sleep(0.5)
    s, j, _, _ = req("POST", "/v1/nav/go", {"id": I("held-2"), "place": {"name": "أثناء الريوس", "lat": 26.29, "lon": 50.18}, "mode": "start"}, token)
    e = ev.wait(lambda x: x.get("id") == I("held-2") and x["state"] == "held", 4)
    check("in reverse: held", e is not None and e.get("reason") == "reverse", ev.nav())
    s, j, _, _ = req("POST", "/v1/remote", {"cmd": "open.maps"}, token)
    check("in reverse: nothing opens over the camera", s == 409 and j.get("reason") == "reversing", (s, j))
    shell("am broadcast -a com.abdllh.aura.debug.GEAR --ez reverse false")
    e = ev.wait(lambda x: x.get("id") == I("held-2") and x["state"] == "card", 4)
    check("after reverse: the card asks", e is not None, ev.nav())
    time.sleep(0.5)
    if routes:
        tap_card(CARD_PRIMARY)
        e = ev.wait(lambda x: x.get("id") == I("held-2") and x["state"] in ("started", "failed"), 45)
        check("after reverse: Start starts it", e is not None and e["state"] == "started", ev.nav())
        stop_trip(token)
    else:
        tap_card(CARD_SECOND)
    ev.close()


def t_unpaired(token, routes):
    """No token, from the gateway: always the card. From another peer: refused."""
    home()
    fix()
    home()
    set_debug("gateway")
    ev = Events(token)
    time.sleep(0.5)
    s, j, _, _ = req("POST", "/v1/nav/go", {"id": I("gw-1"), "place": {"name": "من غير ربط", "lat": 26.29, "lon": 50.18}, "mode": "start"})
    check("gateway, no token: accepted, needs a tap", s == 202 and j.get("needs") == "tap", (s, j))
    e = ev.wait(lambda x: x.get("id") == I("gw-1") and x["state"] == "card", 4)
    check("gateway, no token: a card", e is not None and e.get("kind") == "ask", ev.nav())
    time.sleep(0.5)
    shot("v2-link-ask-card")
    front = shell("dumpsys activity activities | grep mResumedActivity")
    check("gateway, no token: the screen under it stays", "MapsActivity" not in front, front)
    s, j, _, _ = req("GET", "/v1/state", token=token)
    check("gateway, no token: nothing started", j["nav"]["state"] == "idle", j["nav"])
    tap_card(CARD_SECOND)
    e = ev.wait(lambda x: x.get("id") == I("gw-1") and x["state"] == "ignored", 4)
    check("gateway, no token: No", e is not None, ev.nav())
    s, j, _, _ = req("POST", "/v1/share", {"id": I("gw-2"), "text": "الراشد مول\nhttps://maps.google.com/?q=26.290457,50.184555"})
    check("gateway share: read here, the place back", s == 202 and j["place"]["name"] == "الراشد مول" and j.get("needs") == "tap", (s, j))
    e = ev.wait(lambda x: x.get("id") == I("gw-2") and x["state"] == "card", 4)
    check("gateway share: a card", e is not None, ev.nav())
    # a newer place replaces one still waiting
    s, j, _, _ = req("POST", "/v1/share", {"id": I("gw-3"), "text": "26.31, 50.21"})
    e = ev.wait(lambda x: x.get("id") == I("gw-2") and x["state"] == "replaced", 4)
    check("a newer place replaces one waiting", e is not None, ev.nav())
    time.sleep(0.5)
    tap_card(CARD_SECOND)
    s, j, _, _ = req("POST", "/v1/share", {"id": I("gw-4"), "text": "https://example.com/nothing"})
    check("share of no place: says so", s == 422 and j["reason"] == "not_a_map", (s, j))
    s, j, _, _ = req("POST", "/v1/share", {"id": I("gw-5"), "text": "مكان بالاسم"})
    check("share of a name, no token: no search on the screen", s == 200 and j["state"] == "search" and j["onScreen"] is False, (s, j))
    s, j, _, _ = req("POST", "/v1/share", {"id": I("gw-6"), "text": "https://www.google.com/maps/@26.29,50.18,15z"}, token)
    check("a guess asks even a paired phone", s == 202 and j.get("needs") == "tap" and j["confidence"] == "guess", (s, j))
    e = ev.wait(lambda x: x.get("id") == I("gw-6") and x["state"] == "card", 4)
    check("a guess: a card", e is not None, ev.nav())
    time.sleep(0.5)
    tap_card(CARD_SECOND)
    # nobody answers: it goes after 30 s, and the phone hears that
    req("POST", "/v1/nav/go", {"id": I("gw-7"), "place": {"name": "ما أحد رد", "lat": 26.29, "lon": 50.18}, "mode": "start"})
    ev.wait(lambda x: x.get("id") == I("gw-7") and x["state"] == "card", 4)
    t0 = time.time()
    e = ev.wait(lambda x: x.get("id") == I("gw-7") and x["state"] == "expired", 34)
    check("nobody answers the card: expired after 30 s, nothing started", e is not None and 27 < time.time() - t0 < 34 and state(token)["nav"]["state"] == "idle", (e, time.time() - t0))
    ev.close()
    set_debug("1")


def t_events(token):
    ev = Events(token)
    time.sleep(1.5)
    kinds = [e[1] for e in ev.events]
    check("events: a snapshot on connect (nav, music, car)", kinds[:3] == ["nav", "music", "car"], kinds)
    t0 = time.time()
    while ev.heartbeats == 0 and time.time() - t0 < 18:
        time.sleep(0.2)
    check("events: a heartbeat within 15 s", ev.heartbeats >= 1 and time.time() - t0 < 17, ev.heartbeats)
    req("POST", "/v1/remote", {"cmd": "volume.up"}, token)
    time.sleep(0.8)
    music = [e for e in ev.events if e[1] == "music"]
    check("events: a volume change reaches them", len(music) >= 2 and "volume" in music[-1][2], music[-1:])
    req("POST", "/v1/remote", {"cmd": "volume.down"}, token)
    ev2 = Events(token)
    time.sleep(0.7)
    ev3 = Events(token)
    time.sleep(1.5)
    check("events: the third stream drops the oldest", (not ev.alive) and ev2.alive and ev3.alive, (ev.alive, ev2.alive, ev3.alive))
    s, j, _, _ = req("GET", "/v1/ping")
    check("with two streams open a request still gets in", s == 200, s)
    ev2.close()
    ev3.close()
    time.sleep(1)


def t_slow(token):
    # sends a byte a second: closed at 10 s, not a second later
    c = socket.create_connection(("127.0.0.1", PORT), timeout=20)
    t0 = time.time()
    closed = None
    try:
        for b in b"GET /v1/ping HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Slow: " + b"a" * 30:
            c.sendall(bytes([b]))
            c.settimeout(0.9)
            try:
                if c.recv(100) == b"":
                    closed = time.time() - t0
                    break
            except socket.timeout:
                pass
    except OSError:
        closed = time.time() - t0
    c.close()
    check("a slow client is cut at 10 s", closed is not None and 9 <= closed <= 12, closed)
    # says nothing at all
    c = socket.create_connection(("127.0.0.1", PORT), timeout=14)
    t0 = time.time()
    try:
        d = c.recv(100)
    except OSError:
        d = b""
    took = time.time() - t0
    c.close()
    check("a silent client is cut at 10 s", d == b"" and 9 <= took <= 12, (d, took))
    # sends a head and half a body
    c = socket.create_connection(("127.0.0.1", PORT), timeout=14)
    c.sendall(b"POST /v1/share HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"text\":")
    t0 = time.time()
    try:
        d = c.recv(100)
    except OSError:
        d = b""
    took = time.time() - t0
    c.close()
    check("half a body is cut at 10 s", d == b"" and 8 <= took <= 12, (d, took))
    # meanwhile the screen answered
    s, j, _, _ = req("GET", "/v1/ping")
    check("after them it still answers", s == 200, s)


def t_many(token):
    # 5 at once with nobody sending anything: 4 are in, the fifth is told 503 at once
    socks = [socket.create_connection(("127.0.0.1", PORT), timeout=12) for _ in range(4)]
    time.sleep(0.3)
    c5 = socket.create_connection(("127.0.0.1", PORT), timeout=5)
    t0 = time.time()
    d = c5.recv(200)
    took = time.time() - t0
    c5.close()
    check("the fifth connection: 503 at once", b" 503 " in d and took < 1.5, (d, took))
    for s in socks:
        s.close()
    time.sleep(0.5)
    s, j, _, _ = req("GET", "/v1/ping")
    check("after they go it answers again", s == 200, s)
    # and a crowd of real requests: nobody hangs, each gets 200 or 503
    out = []
    def one():
        try:
            out.append(req("GET", "/v1/state", token=token, timeout=10)[0])
        except Exception as e:
            out.append(str(e))
    th = [threading.Thread(target=one) for _ in range(20)]
    for t in th:
        t.start()
    for t in th:
        t.join()
    check("a crowd of 20: each gets an answer", all(x in (200, 503) for x in out) and 200 in out, out)
    print("     crowd: %s" % out)


def t_idle():
    # one thread at rest (a stream whose phone left without a word goes at the next heartbeat's failed write: up to 30 s)
    pid = shell("pidof com.abdllh.aura").strip()
    t0 = time.time()
    while True:
        threads = shell("ls /proc/%s/task | while read t; do cat /proc/%s/task/$t/comm; done" % (pid, pid))
        # (aura-phonelink is Naghm's link, not this server)
        names = [l.strip() for l in threads.split("\n") if "aura-phone" in l and "aura-phonelink" not in l]
        if len(names) == 1 or time.time() - t0 > 40:
            break
        time.sleep(2)
    print("     at rest after %.0f s: %s" % (time.time() - t0, names))
    check("at rest: one thread of the server's", len(names) == 1 and "acce" in names[0], names)


def t_off():
    shell("am broadcast -a com.abdllh.aura.debug.LINK --ez on false")
    time.sleep(1)
    listening = shell("netstat -lnt | grep 47101 | grep LISTEN")
    check("switched off: the port is closed", "47101" not in listening, listening)
    try:
        req("GET", "/v1/ping", timeout=3)
        check("switched off: nothing answers", False)
    except Exception:
        check("switched off: nothing answers", True)
    pid = shell("pidof com.abdllh.aura").strip()
    threads = shell("ls /proc/%s/task | while read t; do cat /proc/%s/task/$t/comm; done" % (pid, pid))
    check("switched off: no thread of the server's", "aura-phone-acce" not in threads, "still there")
    shell("am broadcast -a com.abdllh.aura.debug.LINK --ez on true")
    time.sleep(1.5)
    s, j, _, _ = req("GET", "/v1/ping")
    check("switched on again: it answers", s == 200, s)


def front():
    return shell("dumpsys activity activities | grep mResumedActivity")


def state(token):
    # (the main thread busy for over 1.5 s, a map opening on the emulator: told 503 "busy", asked again)
    j = None
    for _ in range(10):
        try:
            j = req("GET", "/v1/state", token=token)[1]
        except OSError as e:  # (a whole emulator can stall for many seconds when the PC is loaded)
            print("     (no answer to state: %s)" % e)
            continue
        if j and "nav" in j:
            return j
        time.sleep(0.5)
    return j


def wait_state(token, pred, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        j = state(token)
        if j and "nav" in j and pred(j):
            return j
        time.sleep(0.3)
    return None


def on_device(line):
    """Arabic through a file on the device: the Windows command line would re-encode it (docs/DEV.md)."""
    path = os.path.join(tempfile.gettempdir(), "aura-link-say.sh")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(line + "\n")
    adb("push", path, "/data/local/tmp/aura-link-say.sh")
    shell("sh /data/local/tmp/aura-link-say.sh")


def amri_say(text):
    adb("logcat", "-c")
    on_device('am broadcast -a com.abdllh.aura.debug.AMRI --es say "%s"' % text)


def amri_log():
    return adb("logcat", "-d", "-s", "AuraAmri:I")


def t_voice_words():
    """What the voice makes of a sentence, and which place of an answer it starts for: no search, no internet."""
    for said, kind in [("ودني صيدلية", "Nearby"), ("ودني اقرب صيدلية", "Nearby"), ("ودني محطة بنزين", "Nearby"), ("ودني مغسلة سيارات", "Nearby"),
                       ("ودني سوبر ماركت", "Nearby"), ("ودني مسجد", "Nearby"), ("ودني الجامع", "Nearby"), ("take me to a hospital", "Nearby"), ("وين اقرب محطة", "Nearby"),
                       ("ودني مستشفى الموسى", "SearchPlace"), ("ودني جامعة الملك فهد", "SearchPlace"), ("ودني الجامعة", "SearchPlace"), ("خذني فندق الشيراتون", "SearchPlace"),
                       ("ودني مطعم البيك", "SearchPlace"), ("ودني الراشد مول", "SearchPlace"), ("ودني المطار", "SearchPlace"), ("go to starbucks", "SearchPlace"),
                       ("ودني البيت", "Navigate"), ("خذني الدوام", "Navigate"), ("وقف الملاحة", "StopTrip"), ("الغ الرحلة", "StopTrip"), ("افتح الخريطة", "OpenMaps")]:
        s, j, _, _ = req("POST", "/debug/command", {"text": said})
        check("voice words: %s is %s" % (said, kind), s == 200 and (j.get("command") or "").startswith(kind), j)
    near = {"lat": 26.40, "lon": 50.09}
    def p(name, lat, lon):
        return {"name": name, "lat": lat, "lon": lon}
    streets = [p("Street 9", 26.44, 50.10), p("Street 9", 26.44, 50.11), p("Street 8", 26.44, 50.12), p("Street 28", 26.43, 50.11)]
    jarir = [p("Jarir Bookstore", 26.42, 50.09), p("Jarir Bookstore", 26.44, 50.10), p("Jarir Bookstore", 26.33, 50.20), p("مكتبة جرير", 25.3, 51.5)]
    matar = [p("Al Matar", 26.31, 50.15), p("Airport Road", 26.37, 49.85), p("Airport Mosque", 26.43, 49.80), p("Airport", 26.47, 49.80)]
    pharmacies = [p("Daan Pharmacy", 26.43, 50.09), p("AlNahdi pharmacy", 26.41, 50.10), p("Whites Pharmacy", 26.39, 50.08)]
    for q, lst, want in [("شارع", streets, None), ("المطار", matar, None), ("مطار", matar, None), ("صيدلية", pharmacies, None), ("qwxyzz", streets, None), ("مول", [p("Mall of Dhahran", 26.30, 50.17)], None),
                         ("مكتبة جرير", jarir, None), ("مكتبة جرير", [p("مكتبة جرير", 25.3, 51.5)], "مكتبة جرير"), ("الراشد مول", [p("al rashid mall", 26.29, 50.18), p("Rashid Road", 26.5, 50.0)], "al rashid mall"),
                         ("ستاربكس", [p("Starbucks", 26.42, 50.10), p("Starbucks", 26.30, 50.20)], "Starbucks"),
                         ("الراشد مول", [p("الراشد مول", 26.29, 50.18), p("الراشد للسيارات", 26.29, 50.17)], "الراشد مول"),
                         ("مركز الراشد", [p("مركز الراشد", 26.29, 50.18), p("مركز الراشدي", 26.40, 50.09)], "مركز الراشدي"), ("الوردة", [p("الوردة", 24.7, 46.7), p("شركة الوردة الحمراء", 26.4, 50.1)], None)]:
        s, j, _, _ = req("POST", "/debug/winner", {"q": q, "list": lst, "near": near})
        print("     winner of %s: %s" % (q, j))
        if q not in ("مركز الراشد", "الوردة"):
            check("voice answer: %s is %s" % (q, want or "a list"), s == 200 and j["winner"] == want, j)
    s, j, _, _ = req("POST", "/debug/winner", {"q": "مكتبة جرير", "list": jarir})
    check("voice answer: no position, no choice", s == 200 and j["winner"] is None, j)


def t_voice_live(token):
    """What the unit's real search answers for some spoken names, and which way each goes. Reports only: the data is the internet's."""
    home()
    fix()
    home()
    for q in ["مطار الملك فهد", "المطار", "جامعة الملك فهد", "ستاربكس", "الراشد مول", "شارع", "مكتبة جرير", "qwxyzz"]:
        amri_say("ودني " + q)
        j = wait_state(token, lambda x: x["nav"]["state"] in ("routing", "navigating") and x["nav"].get("by") == "voice", 11)
        log = [l for l in amri_log().split("\n") if "place '" in l or "request " in l]
        print("     %s -> %s | %s" % (q, j["nav"]["place"]["name"] if j else "(no trip)", " | ".join(l.split(": ", 1)[-1] for l in log)[:220]))
        stop_trip(token)
        home()


def t_voice(token):
    """"ودني ...": one clear place starts a route and says so, with a way to cancel; a weak or ambiguous answer stays a list.
    The search's answer is fixed here (/debug/answer, for Places.lookup only): what is tested is what is done with it."""
    home()
    fix()
    home()
    def answer(*places):
        req("POST", "/debug/answer", {"list": [{"name": n, "detail": "", "lat": la, "lon": lo} for n, la, lo in places]})
    clear = [("Al Rashid Mall", 26.29, 50.18), ("Rashid Road", 26.5, 50.0)]
    try:
        answer(*clear)
        amri_say("ودني الراشد مول")
        j = wait_state(token, lambda x: x["nav"]["state"] == "navigating", 15)
        check("voice: a clear place starts a trip", j is not None and j["nav"].get("by") == "voice" and j["nav"]["place"]["name"] == "Al Rashid Mall", amri_log()[-400:])
        log = amri_log()
        print("     %s" % " | ".join(l.split(": ", 1)[-1] for l in log.split("\n") if "place '" in l or "request " in l))
        said = [l for l in log.split("\n") if "request SearchPlace" in l]
        check("voice: it says what it chose", len(said) == 2 and "Al Rashid Mall" in said[-1], said)
        time.sleep(0.5)
        shot("v2-link-voice-started")
        tap_card(CARD_NARROW_CANCEL)
        j = wait_state(token, lambda x: x["nav"]["state"] == "idle", 4)
        check("voice: the card's cancel ends it", j is not None, state(token)["nav"])
        home()
        # ambiguous: two names answer alike, at about the same distance
        answer(("Al Nahdi Pharmacy", 26.41, 50.10), ("Al Nahdi Clinic", 26.42, 50.09))
        amri_say("ودني النهدي")
        time.sleep(4)
        j = state(token)
        check("voice: ambiguous: no trip", j["nav"]["state"] == "idle", j["nav"])
        check("voice: ambiguous: the map's list", "MapsActivity" in front(), front())
        said = [l for l in amri_log().split("\n") if "request SearchPlace" in l]
        print("     %s" % (said[-1].split(": ", 1)[-1] if said else None))
        shot("v2-link-voice-list")
        home()
        # weak: a word that says what kind of place; nothing found
        answer(("Street 9", 26.44, 50.10))
        amri_say("ودني شارع")
        time.sleep(3)
        check("voice: a word for a kind of place: no trip", state(token)["nav"]["state"] == "idle", state(token)["nav"])
        home()
        answer()
        amri_say("ودني qwxyzz")
        time.sleep(3)
        check("voice: nothing found: no trip", state(token)["nav"]["state"] == "idle", state(token)["nav"])
        home()
        # during a trip it asks, like the phone
        answer(*clear)
        req("POST", "/v1/nav/go", {"id": I("voice-trip"), "place": {"name": "الرحلة", "lat": 26.35, "lon": 50.15}, "mode": "start"}, token)
        j = wait_state(token, lambda x: x["nav"]["state"] == "navigating", 45)
        check("voice during a trip: first the trip", j is not None, state(token)["nav"])
        time.sleep(6)
        amri_say("ودني الراشد مول")
        time.sleep(1.5)
        j = state(token)
        check("voice during a trip: the trip stays, the card asks", j["nav"]["place"]["name"] == "الرحلة" and j["nav"].get("pending", {}).get("stage") == "replacing", j["nav"])
        said = [l for l in amri_log().split("\n") if "request SearchPlace" in l]
        print("     %s" % (said[-1].split(": ", 1)[-1] if said else None))
        time.sleep(0.4)
        shot("v2-link-voice-replace")
        amri_say("وقف الملاحة")
        j = wait_state(token, lambda x: x["nav"]["state"] == "idle" and "pending" not in x["nav"], 5)
        check("voice: stop ends the trip, and what waited", j is not None, state(token)["nav"])
        amri_say("وقف الملاحة")
        time.sleep(1.5)
        check("voice: stop with no trip says so", "request StopTrip" in amri_log(), amri_log()[-300:])
    finally:
        req("POST", "/debug/answer", {})
    stop_trip(token)
    home()


class FakeNaghm(threading.Thread):
    """Naghm's side of its link (CAR_LINK.md), on this PC; the emulator reaches it at 10.0.2.2."""

    def __init__(self, port, answer):
        super().__init__(daemon=True)
        self.answer = answer
        self.asked = []
        self.server = socket.socket()
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind(("0.0.0.0", port))
        self.server.listen(2)

    def run(self):
        try:
            c, _ = self.server.accept()
            self.conn = c
            c.sendall(b'{"type":"hello","app":"Naghm","version":"test"}\n')
            buf = b""
            while True:
                d = c.recv(4096)
                if not d:
                    return
                buf += d
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    m = json.loads(line.decode("utf-8"))
                    if m.get("type") == "ping":
                        c.sendall(b'{"type":"pong"}\n')
                    elif m.get("type") == "playSong":
                        self.asked.append(m)
                        if self.answer:
                            c.sendall((json.dumps({"type": "state", "seq": 1, "id": m["id"], "title": m["title"], "artist": m["artist"], "art": m["art"],
                                                   "duration": m["duration"], "position": 0, "playing": True, "loading": False}) + "\n").encode())
        except OSError:
            pass

    def close(self):
        for s in (self.server, getattr(self, "conn", None)):
            try:
                if s:
                    s.close()
            except OSError:
                pass


def t_naghm(token):
    """A song asked of Amri with Naghm linked goes to Naghm (its existing playSong); with no link, as it always was."""
    home()
    port = 47190
    for answer in (True, False):
        fake = FakeNaghm(port, answer)
        fake.start()
        shell("setprop debug.aura.phonelink 10.0.2.2:%d" % port)
        j = wait_state(token, lambda x: x["link"]["naghm"], 15)
        check("naghm: linked", j is not None, state(token)["link"])
        amri_say("شغل عبادي الجوهر")
        end = time.time() + 15
        while not fake.asked and time.time() < end:
            time.sleep(0.2)
        asked = fake.asked[0] if fake.asked else None
        check("naghm: playSong sent, with the id of the search", asked is not None and asked["id"] and asked["title"], amri_log()[-400:])
        print("     %s" % json.dumps(asked, ensure_ascii=False)[:200])
        time.sleep(5 if not answer else 1.5)
        _, _, raw, _ = req("GET", "/v1/diag", token=token)
        word = "took playSong" if answer else "no answer to playSong"
        check("naghm: the About note says: " + word, word.encode() in raw, raw[:400])
        log = [l.split(": ", 1)[-1] for l in amri_log().split("\n") if "request PlaySong" in l]
        print("     %s" % " | ".join(log))
        shell("setprop debug.aura.phonelink ''")
        fake.close()
        wait_state(token, lambda x: not x["link"]["naghm"], 20)
        home()
    amri_say("شغل عبادي الجوهر")
    time.sleep(6)
    log = [l.split(": ", 1)[-1] for l in amri_log().split("\n") if "request PlaySong" in l]
    print("     no link: %s" % " | ".join(log))
    check("naghm: no link: today's way, nothing sent", len(log) >= 1, amri_log()[-300:])
    home()


def t_failed(token):
    """No road goes there: told "failed", the place stays on the map; to replace a trip: that trip goes on."""
    home()
    fix()
    home()
    ev = Events(token)
    time.sleep(0.5)
    far = {"name": "لا طريق إليه", "detail": "هاواي", "lat": 19.60, "lon": -155.50}
    req("POST", "/v1/nav/go", {"id": I("fail-1"), "place": far, "mode": "start"}, token)
    e = ev.wait(lambda x: x.get("id") == I("fail-1") and x["state"] in ("started", "failed"), 70)
    print("     %s" % e)
    check("no route: told failed, never started", e is not None and e["state"] == "failed", ev.nav())
    time.sleep(1.5)
    shot("v2-link-failed")
    check("no route: the place is on the map", "MapsActivity" in front(), front())
    j = state(token)
    check("no route: nothing runs", j["nav"]["state"] == "idle", j["nav"])
    home()
    fix()
    home()
    req("POST", "/v1/nav/go", {"id": I("fail-2"), "place": {"name": "الرحلة", "lat": 26.29, "lon": 50.18}, "mode": "start"}, token)
    ok = ev.wait(lambda x: x.get("id") == I("fail-2") and x["state"] == "started", 45)
    check("no route to replace with: first the trip", ok is not None, ev.nav())
    time.sleep(6)
    req("POST", "/v1/nav/go", {"id": I("fail-3"), "place": far, "mode": "start"}, token)
    ev.wait(lambda x: x.get("id") == I("fail-3") and x["state"] == "card", 5)
    time.sleep(0.5)
    tap_card(CARD_REPLACE)
    e = ev.wait(lambda x: x.get("id") == I("fail-3") and x["state"] == "failed", 70)
    check("no route to replace with: failed, the trip kept", e is not None and e.get("kept") is True, ev.nav())
    j = state(token)
    check("no route to replace with: still the first trip", j["nav"]["state"] == "navigating" and j["nav"]["place"]["name"] == "الرحلة", j["nav"])
    time.sleep(0.8)
    shot("v2-link-failed-on-trip")
    ev.close()
    stop_trip(token)
    home()


def t_saved(token):
    """Home by the phone and by the screen's button: the same way there."""
    s, j, _, _ = req("PUT", "/v1/places", {"home": {"name": "البيت", "lat": 26.31, "lon": 50.21}}, token)
    check("saved: home set", s == 200 and j["home"]["name"] == "البيت", j)
    home()
    fix()
    home()
    ev = Events(token)
    time.sleep(0.5)
    s, j, _, _ = req("POST", "/v1/nav/saved", {"id": I("saved-1"), "which": "home"}, token)
    check("saved: accepted", s == 202, (s, j))
    e = ev.wait(lambda x: x.get("id") == I("saved-1") and x["state"] in ("started", "failed"), 45)
    check("saved: home from the phone starts", e is not None and e["state"] == "started", ev.nav())
    j = state(token)
    check("saved: started by the phone", j["nav"].get("by") == "phone" and j["nav"]["place"]["name"] == "البيت", j["nav"])
    stop_trip(token)
    home()
    # the buttons' own way: a place the screen itself starts
    shell("am start -n com.abdllh.aura/.nav.MapsActivity --es go home")
    j = wait_state(token, lambda x: x["nav"]["state"] == "navigating", 20)
    check("saved: the screen's Home button starts, as before", j is not None and j["nav"].get("by") == "maps", state(token)["nav"])
    time.sleep(0.5)
    shot("v2-link-home-button")
    stop_trip(token)
    home()


def t_apple(token):
    """Apple's place data is not kept on the unit (companion.md 2.6); any other place goes to the recents."""
    home()
    fix()
    home()
    ev = Events(token)
    time.sleep(0.5)
    for key, src, kept in [("apple-1", "share.apple", False), ("apple-2", "share.google", True)]:
        name = "مكان " + key
        req("POST", "/v1/nav/go", {"id": I(key), "place": {"name": name, "lat": 26.29, "lon": 50.18, "src": src}, "mode": "start"}, token)
        e = ev.wait(lambda x: x.get("id") == I(key) and x["state"] == "started", 45)
        check("%s: started" % src, e is not None, ev.nav())
        recents = [p["name"] for p in req("GET", "/v1/places", token=token)[1]["recents"]]
        check("%s: %s the recent places" % (src, "in" if kept else "not in"), (name in recents) == kept, recents)
        time.sleep(0.3)
        stop_trip(token)
        home()
    ev.close()


def t_drive(token):
    """A whole trip with the fake drive (debug.aura.navsim, km/h): the reports every 10 s, then the arrival."""
    shell("setprop debug.aura.navsim 200")
    home()
    fix()
    home()
    ev = Events(token)
    time.sleep(0.5)
    req("POST", "/v1/nav/go", {"id": I("drive-1"), "place": {"name": "قريب", "lat": 26.425, "lon": 50.10}, "mode": "start"}, token)
    e = ev.wait(lambda x: x.get("id") == I("drive-1") and x["state"] in ("started", "failed"), 45)
    check("drive: started", e is not None and e["state"] == "started", ev.nav())
    print("     %s" % e)
    time.sleep(12)
    shot("v2-link-drive")
    a = ev.wait(lambda x: x["state"] == "arrived", 150)
    check("drive: arrived is told", a is not None, [x["state"] for x in ev.nav()])
    prog = [(t, d) for t, k, d in ev.events if k == "nav" and d["state"] == "progress"]
    print("     %d reports, e.g. %s" % (len(prog), json.dumps(prog[0][1], ensure_ascii=False)[:230] if prog else None))
    check("drive: progress reports, each with what is left", len(prog) >= 1 and all(all(k in d for k in ("remainingM", "timeLeftS", "etaLocal")) for _, d in prog), prog[:1])
    gaps = [prog[i + 1][0] - prog[i][0] for i in range(len(prog) - 1)]
    check("drive: at most one report in 10 s", all(g >= 9 for g in gaps), gaps)
    left = [d["remainingM"] for _, d in prog]
    check("drive: what is left goes down", left == sorted(left, reverse=True), left)
    time.sleep(0.5)
    shot("v2-link-arrived")
    ev.close()
    shell("setprop debug.aura.navsim ''")
    stop_trip(token)
    home()


def t_remote_more(token):
    home()
    s, j, _, _ = req("POST", "/v1/remote", {"cmd": "open.music"}, token)
    time.sleep(1.5)
    check("remote open.music", s == 200 and "Music" in front(), (s, j, front()))
    s, j, _, _ = req("POST", "/v1/remote", {"cmd": "open.maps"}, token)
    time.sleep(1.5)
    check("remote open.maps", s == 200 and "MapsActivity" in front(), (s, j, front()))
    home()


def t_no_fix(token):
    """Before the unit's first fix: the phone's own position starts the route; without one the place waits for the fix."""
    shell("settings put secure location_mode 0")
    shell("am force-stop com.abdllh.aura")
    shell("am start -n com.abdllh.aura/.home.HomeActivity")
    time.sleep(4)
    try:
        s, j, _, _ = req("GET", "/v1/ping")
        check("after a restart of the app the server is back", s == 200, s)
    except Exception as e:
        check("after a restart of the app the server is back", False, e)
    j = state(token)
    if "fix" in j["car"]:
        print("     (this emulator has a fix anyway: %s; the no-fix path was not tried)" % j["car"]["fix"])
        shell("settings put secure location_mode 3")
        return
    check("no fix: the state says so", "fix" not in j["car"], j["car"])
    ev = Events(token)
    time.sleep(0.5)
    place = {"name": "بدون موقع", "lat": 26.29, "lon": 50.18}
    req("POST", "/v1/nav/go", {"id": I("nofix-1"), "place": place, "from": {"lat": 26.40, "lon": 50.09, "acc": 12, "ageSec": 8}, "mode": "start"}, token)
    e = ev.wait(lambda x: x.get("id") == I("nofix-1") and x["state"] in ("started", "failed", "waiting_fix"), 45)
    check("no fix, the phone's position: the route starts from it", e is not None and e["state"] == "started", ev.nav())
    print("     %s" % e)
    j = state(token)
    check("no fix, the phone's position: navigating, the whole way left", j["nav"]["state"] == "navigating" and j["nav"].get("remainingM", 0) > 1000, j["nav"])
    time.sleep(0.5)
    shot("v2-link-from-phone-fix")
    stop_trip(token)
    home()
    req("POST", "/v1/nav/go", {"id": I("nofix-2"), "place": place, "from": {"lat": 26.40, "lon": 50.09, "acc": 12, "ageSec": 300}, "mode": "start"}, token)
    e = ev.wait(lambda x: x.get("id") == I("nofix-2") and x["state"] in ("started", "failed", "waiting_fix"), 8)
    check("no fix, a stale position from the phone: it waits for the fix", e is not None and e["state"] == "waiting_fix", ev.nav())
    time.sleep(1.5)
    shot("v2-link-waiting-fix")
    check("no fix: Maps came forward", "MapsActivity" in front(), front())
    check("no fix: nothing said started", not [x for x in ev.nav() if x.get("id") == I("nofix-2") and x["state"] == "started"], ev.nav())
    shell("am start -n com.abdllh.aura/.nav.MapsActivity --es mock_loc 26.40,50.09")
    e = ev.wait(lambda x: x.get("id") == I("nofix-2") and x["state"] in ("started", "failed"), 45)
    check("no fix: the first fix starts it", e is not None and e["state"] == "started", ev.nav())
    stop_trip(token)
    # told to wait for a fix, and cancelled on the card
    shell("settings put secure location_mode 0")
    shell("am force-stop com.abdllh.aura")
    shell("am start -n com.abdllh.aura/.home.HomeActivity")
    time.sleep(4)
    ev.close()
    ev = Events(token)
    time.sleep(0.5)
    req("POST", "/v1/nav/go", {"id": I("nofix-3"), "place": place, "mode": "start"}, token)
    e = ev.wait(lambda x: x.get("id") == I("nofix-3") and x["state"] == "waiting_fix", 8)
    check("no fix again: it waits", e is not None, ev.nav())
    time.sleep(2)
    tap_card(CARD_WAIT_CANCEL)
    e = ev.wait(lambda x: x.get("id") == I("nofix-3") and x["state"] == "cancelled", 4)
    check("no fix: the card's cancel drops it", e is not None and e.get("by") == "card", ev.nav())
    j = state(token)
    check("no fix: after the cancel nothing waits", j["nav"]["state"] == "idle" and "pending" not in j["nav"], j["nav"])
    time.sleep(0.5)
    shot("v2-link-waiting-cancelled")
    ev.close()
    shell("settings put secure location_mode 3")
    home()


def t_kill():
    """The ROM's restore in progress (persist.aura.disabled): nothing new starts here."""
    shell("setprop persist.aura.disabled 1")
    try:
        c = socket.create_connection(("127.0.0.1", PORT), timeout=3)
        c.sendall(b"GET /v1/ping HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        c.settimeout(3)
        d = c.recv(100)
        c.close()
    except OSError:
        d = b""
    check("kill switch: no answer", d == b"", d)
    time.sleep(0.5)
    listening = shell("netstat -lnt | grep 47101 | grep LISTEN")
    check("kill switch: the port closes", "47101" not in listening, listening)
    shell("setprop persist.aura.disabled 0")
    shell("am broadcast -a com.abdllh.aura.debug.LINK --ez on true")
    time.sleep(1.5)
    s, _, _, _ = req("GET", "/v1/ping")
    check("kill switch off: it answers again", s == 200, s)


def main():
    global SERIAL, PORT, CARD_NARROW_CANCEL, CARD_REPLACE, CARD_PRIMARY, CARD_SECOND, CARD_WAIT_CANCEL
    p = argparse.ArgumentParser()
    p.add_argument("--serial", default="emulator-5572")
    p.add_argument("--port", type=int, default=47101)
    p.add_argument("--only", default="")
    a = p.parse_args()
    SERIAL = a.serial
    PORT = a.port
    only = set(filter(None, a.only.split(",")))
    want = lambda n: not only or n in only
    # where the card's buttons are: read from the screen, both languages
    CARD_NARROW_CANCEL, CARD_REPLACE, CARD_PRIMARY, CARD_SECOND, CARD_WAIT_CANCEL = card_buttons()

    adb("forward", "tcp:%d" % PORT, "tcp:47101")
    shell("appops set com.abdllh.aura SYSTEM_ALERT_WINDOW allow")
    set_debug("1")
    shell("am broadcast -a com.abdllh.aura.debug.LINK --ez on true")
    time.sleep(1.5)

    if want("peers"):
        t_peers()
    if want("safety"):
        t_web_safety()
    set_debug("1")
    token = t_pair()
    if want("bad"):
        t_bad_bodies(token)
    if want("endpoints"):
        t_endpoints(token)
    if want("fixtures"):
        t_share_fixtures()
    if want("live"):
        t_share_live()
    if want("qr"):
        t_qr()
    if want("events"):
        t_events(token)
    if want("slow"):
        t_slow(token)
    if want("many"):
        t_many(token)
    routes = True
    if want("retries"):
        routes = t_retries(token, routes)
        print("     routes on this emulator: %s" % routes)
    if want("flow"):
        t_flow(token, routes)
    if want("cancel"):
        t_cancel_card(token, routes)
    if want("navigating"):
        t_navigating(token, routes)
    if want("held"):
        t_held(token, routes)
    if want("unpaired"):
        t_unpaired(token, routes)
    if want("drive"):
        t_drive(token)
    if want("apple"):
        t_apple(token)
    if want("remote"):
        t_remote_more(token)
    if want("saved"):
        t_saved(token)
    if want("failed"):
        t_failed(token)
    if want("nofix"):
        t_no_fix(token)
    if want("voicewords"):
        t_voice_words()
    if want("voice"):
        t_voice(token)
    if "voicelive" in only:
        t_voice_live(token)
    if want("naghm"):
        t_naghm(token)
    if want("kill"):
        t_kill()
    if want("idle"):
        t_idle()
    if want("off"):
        t_off()

    failed = [n for n, ok in results if not ok]
    print("\n%d checks, %d failed" % (len(results), len(failed)))
    for n in failed:
        print("  FAILED: " + n)
    sys.exit(1 if failed else 0)


def card_buttons():
    """Where the buttons of the cards are on the 1024 x 600 screen, by the app's language (Arabic is the mirror image): Cancel
    and Replace on the narrow card beside a trip's banner, Start and No on the wide one."""
    lang = shell("run-as com.abdllh.aura cat shared_prefs/aura.xml")
    arabic = 'name="lang">ar' in lang or shell("getprop persist.sys.locale").startswith("ar")
    at = [(945, 77), (790, 137), (735, 62), (820, 62), (806, 62)]
    return [(1024 - x, y) for x, y in at] if arabic else at


if __name__ == "__main__":
    main()
