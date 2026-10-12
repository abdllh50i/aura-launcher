#!/usr/bin/env python3
"""The map editor's little server: serves the page to this PC only, keeps what is drawn in data/additions.json, and
publishes it for the car units.

    python server.py [--port 8765] [--repo owner/name] [--dry-run]

Publishing writes additions.json to the branch "mapdata" of the GitHub repository (through the GitHub CLI, `gh`, with
the account it is logged in to): the units read it from there (Settings > Navigation > Map additions > Update).
--dry-run keeps "published" on this PC (data/published.json) instead, for trying things out.

Python 3.8+, standard library only.
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(HERE, "data")
DATA = os.path.join(DATA_DIR, "additions.json")
DRY = os.path.join(DATA_DIR, "published.json")
BRANCH = "mapdata"
NAME = "additions.json"
MAX_BODY = 4 * 1024 * 1024
LOCK = threading.Lock()
ARGS = None


class Bad(Exception):
    pass


def clean(d):
    """The data in the one shape the units accept; anything else is refused, with the reason."""
    if not isinstance(d, dict):
        raise Bad("not an object")

    def num(v, lo, hi, what):
        if isinstance(v, bool) or not isinstance(v, (int, float)) or not (lo <= v <= hi):
            raise Bad("bad " + what)
        return v

    def ident(it):
        i = it.get("id")
        return i if isinstance(i, str) and 0 < len(i) <= 24 and i.isalnum() else None

    def spot(it, speed):
        if not isinstance(it, dict):
            raise Bad("bad item")
        out = {"lat": round(num(it.get("lat"), -90, 90, "latitude"), 6), "lon": round(num(it.get("lon"), -180, 180, "longitude"), 6)}
        if speed:
            out["kmh"] = int(num(it.get("kmh", 0), 0, 200, "speed"))
        if ident(it):
            out["id"] = ident(it)
        return out

    out = {"version": 1, "updated": int(num(d.get("updated", 0), 0, 4e12, "date")),
           "speeds": [], "cameras": [], "signals": [], "bumps": []}
    for key, speed in (("cameras", True), ("signals", False), ("bumps", False)):
        items = d.get(key, [])
        if not isinstance(items, list) or len(items) > 20000:
            raise Bad("bad " + key)
        out[key] = [spot(it, speed) for it in items]
    speeds = d.get("speeds", [])
    if not isinstance(speeds, list) or len(speeds) > 20000:
        raise Bad("bad speeds")
    for s in speeds:
        if not isinstance(s, dict) or not isinstance(s.get("lines"), list):
            raise Bad("bad street")
        lines = []
        for line in s["lines"]:
            if not isinstance(line, list) or len(line) < 2 or len(line) > 5000:
                raise Bad("bad line")
            pts = []
            for p in line:
                if not isinstance(p, list) or len(p) != 2:
                    raise Bad("bad point")
                pts.append([round(num(p[0], -180, 180, "longitude"), 6), round(num(p[1], -90, 90, "latitude"), 6)])
            lines.append(pts)
        if not lines:
            continue
        name = s.get("name", "")
        item = {"name": name[:120] if isinstance(name, str) else "", "kmh": int(num(s.get("kmh"), 5, 200, "speed")), "lines": lines}
        if ident(s):
            item["id"] = ident(s)
        out["speeds"].append(item)
    return out


def read(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def write(path, obj):
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, path)


def gh(*args, body=None):
    """`gh api ...`; returns (ok, parsed JSON or text)."""
    cmd = ["gh", "api", *args]
    if body is not None:
        cmd += ["--input", "-"]
    try:
        r = subprocess.run(cmd, input=None if body is None else json.dumps(body).encode("utf-8"),
                           capture_output=True, timeout=90)
    except FileNotFoundError:
        raise Bad("GitHub CLI (gh) is not installed on this PC")
    except subprocess.TimeoutExpired:
        raise Bad("GitHub did not answer in time")
    text = r.stdout.decode("utf-8", "replace")
    try:
        parsed = json.loads(text) if text.strip() else {}
    except ValueError:
        parsed = text
    if r.returncode != 0 and not text.strip():
        parsed = r.stderr.decode("utf-8", "replace").strip()
    return r.returncode == 0, parsed


def published():
    """What is on GitHub now (None when nothing is)."""
    if ARGS.dry_run:
        return read(DRY)
    ok, got = gh("repos/%s/contents/%s?ref=%s" % (ARGS.repo, NAME, BRANCH))
    if not ok or not isinstance(got, dict) or "content" not in got:
        return None
    try:
        return json.loads(base64.b64decode(got["content"]).decode("utf-8"))
    except ValueError:
        return None


def publish(data):
    """Puts the data on the repository's data branch (made, with nothing else on it, the first time)."""
    text = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if ARGS.dry_run:
        write(DRY, data)
        return
    repo = ARGS.repo
    n = "%d cameras, %d bumps, %d signals, %d streets" % (len(data["cameras"]), len(data["bumps"]), len(data["signals"]), len(data["speeds"]))
    message = "Map additions: " + n
    content = base64.b64encode(text).decode("ascii")
    ok, got = gh("repos/%s/contents/%s?ref=%s" % (repo, NAME, BRANCH))
    if ok and isinstance(got, dict) and got.get("sha"):
        ok, res = gh("-X", "PUT", "repos/%s/contents/%s" % (repo, NAME),
                     body={"message": message, "content": content, "branch": BRANCH, "sha": got["sha"]})
        if not ok:
            raise Bad(explain(res))
        return
    ok, ref = gh("repos/%s/git/ref/heads/%s" % (repo, BRANCH))
    if ok:  # the branch is there, the file is not
        ok, res = gh("-X", "PUT", "repos/%s/contents/%s" % (repo, NAME), body={"message": message, "content": content, "branch": BRANCH})
        if not ok:
            raise Bad(explain(res))
        return
    # the first time: a branch of its own with this one file (not a copy of the code)
    ok, blob = gh("-X", "POST", "repos/%s/git/blobs" % repo, body={"content": content, "encoding": "base64"})
    if not ok:
        raise Bad(explain(blob))
    ok, tree = gh("-X", "POST", "repos/%s/git/trees" % repo,
                  body={"tree": [{"path": NAME, "mode": "100644", "type": "blob", "sha": blob["sha"]}]})
    if not ok:
        raise Bad(explain(tree))
    ok, commit = gh("-X", "POST", "repos/%s/git/commits" % repo, body={"message": message, "tree": tree["sha"], "parents": []})
    if not ok:
        raise Bad(explain(commit))
    ok, res = gh("-X", "POST", "repos/%s/git/refs" % repo, body={"ref": "refs/heads/" + BRANCH, "sha": commit["sha"]})
    if not ok:
        raise Bad(explain(res))


def explain(res):
    if isinstance(res, dict):
        return str(res.get("message") or res)[:300]
    return str(res)[:300] or "GitHub refused"


class Handler(BaseHTTPRequestHandler):
    server_version = "AmriMapEditor/1"

    def log_message(self, fmt, *args):
        pass

    def send(self, code, body, kind="application/json; charset=utf-8"):
        if not isinstance(body, bytes):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def local(self):
        """Only this PC's own browser talks to this server: not another site's page, not another name for 127.0.0.1."""
        host = (self.headers.get("Host") or "").split(":")[0]
        return host in ("127.0.0.1", "localhost", "10.0.2.2")

    def do_GET(self):
        if not self.local():
            return self.send(403, {"ok": False, "message": "forbidden"})
        path = self.path.split("?")[0]
        if path in ("/", "/index.html"):
            with open(os.path.join(HERE, "index.html"), "rb") as f:
                return self.send(200, f.read(), "text/html; charset=utf-8")
        if path == "/api/data":
            with LOCK:
                data = read(DATA)
                pub = None
                if data is None:  # nothing on this PC yet: what was published before (another PC, a new copy)
                    try:
                        pub = published()
                    except Bad:
                        pub = None
                    data = pub or {"version": 1, "updated": 0, "speeds": [], "cameras": [], "signals": [], "bumps": []}
            # (the units fetch this same address in tests: the data itself is the whole answer for them)
            if self.headers.get("Accept", "").startswith("application/vnd.github.raw"):
                return self.send(200, data)
            return self.send(200, {"ok": True, "data": data, "published": data.get("updated") or 0})
        return self.send(404, {"ok": False, "message": "not found"})

    def do_POST(self):
        origin = self.headers.get("Origin")
        if not self.local() or self.headers.get("X-Map-Editor") != "1" or (origin and origin.split("//")[-1].split(":")[0] not in ("127.0.0.1", "localhost")):
            return self.send(403, {"ok": False, "message": "forbidden"})
        try:
            n = int(self.headers.get("Content-Length") or 0)
            if n <= 0 or n > MAX_BODY:
                raise Bad("too large")
            data = clean(json.loads(self.rfile.read(n).decode("utf-8")))
        except (Bad, ValueError) as e:
            return self.send(400, {"ok": False, "message": str(e)})
        path = self.path.split("?")[0]
        with LOCK:
            if path == "/api/save":
                write(DATA, data)
                return self.send(200, {"ok": True})
            if path == "/api/publish":
                data["updated"] = int(time.time() * 1000)
                try:
                    publish(data)
                except Bad as e:
                    write(DATA, data)
                    return self.send(200, {"ok": False, "message": str(e)})
                write(DATA, data)
                return self.send(200, {"ok": True, "updated": data["updated"]})
        return self.send(404, {"ok": False, "message": "not found"})


def main():
    global ARGS
    ap = argparse.ArgumentParser(description="AMRI map editor")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--repo", default="abdllh50i/aura-launcher")
    ap.add_argument("--dry-run", action="store_true", help="publish to data/published.json on this PC, not to GitHub")
    ap.add_argument("--no-browser", action="store_true")
    ARGS = ap.parse_args()
    srv = ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler)
    url = "http://127.0.0.1:%d/" % ARGS.port
    print("AMRI map editor: " + url + ("   (dry run: nothing goes to GitHub)" if ARGS.dry_run else "   -> " + ARGS.repo + " @ " + BRANCH))
    print("Close this window to stop it.")
    if not ARGS.no_browser:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
