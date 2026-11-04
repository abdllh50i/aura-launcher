#!/usr/bin/env python
"""A stand-in for the map pack's release, with the faults a hotspot and GitHub's CDN can bring. Standard library only, for tests on the emulator through adb reverse:

  python -I pack_server.py --root D:\\k2501-work\\mapbuild\\out\\pack --port 8791
  adb -s emulator-5578 reverse tcp:8791 tcp:8791
  adb -s emulator-5578 shell setprop debug.aura.pack.base http://127.0.0.1:8791/releases/download/data-2026.11/

It answers like GitHub (maps.md 15.1, measured on a real asset): /releases/download/<tag>/<file> says 302 to /cdn/<tag>/<file>?sig=..., which answers 200 or 206 with Content-Range,
ETag, Last-Modified and Accept-Ranges and pays no attention to If-Range. /releases/latest/download/<file> says 302 to the newest tag. A tag's manifest is the real one, with the changes
set for that tag (a newer id and date, for update tests); chunks are the same files under every tag.

GET /__ctl?name=value sets faults, GET /__stats answers what was served. All faults off with /__ctl?reset=1:
  drop_mb=N          each answer's connection is cut after N MB
  drop_rst=1         cut with a reset instead of a plain close
  slow_kbps=N        at most N kilobytes a second, per answer
  stall=N            the next N answers go silent after 1 MB, without closing (a frozen hotspot)
  corrupt=NAME       one flipped byte in the middle of that file, in corrupt_times answers (default 1)
  splice=NAME        the first answer for that file comes from another version of it (other bytes, another ETag), cut after 3 MB; later ones are the real file: a resume
                     splices the two, as a CDN that ignores If-Range would. Test: only a hash can catch it
  no_range=1         Range is ignored: a 200 and the whole file
  wrong_range=1      a 206 whose Content-Range starts somewhere else than asked
  portal=N           the next N answers are a hotspot's sign-in page, 200 text/html
  status=CODE,N      the next N answers are that status
  missing=NAME       that file answers 404
  latest=TAG         which tag /releases/latest/ points at
  patch=TAG,KEY,VAL  the manifest of TAG gets top-level KEY = VAL (VAL as JSON, e.g. patch=data-2026.12,id,"ksa-2026.12")
"""
import argparse
import hashlib
import json
import os
import re
import socket
import struct
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class State:
    def __init__(self, root):
        self.root = root
        self.lock = threading.Lock()
        self.patches = {}
        self.reset()
        self.clear_stats()

    def reset(self):
        self.drop_mb = 0.0
        self.drop_rst = False
        self.slow_kbps = 0
        self.stall = 0
        self.corrupt = None
        self.corrupt_times = 1
        self.splice = None
        self.no_range = False
        self.wrong_range = False
        self.portal = 0
        self.status = None
        self.missing = None
        self.latest = "data-2026.11"
        self.patches = {}

    def clear_stats(self):
        self.requests = 0
        self.redirects = 0
        self.ranges = 0
        self.drops = 0
        self.served = {}
        self.if_range_seen = 0
        self.faults = []

    def manifest(self, tag):
        with open(os.path.join(self.root, "manifest.json"), encoding="utf-8") as f:
            m = json.load(f)
        for k, v in self.patches.get(tag, {}).items():
            m[k] = v
        return json.dumps(m, ensure_ascii=False, indent=1).encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    st = None

    def log_message(self, fmt, *args):
        pass

    def small(self, code, body=b"", ctype="text/plain", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        st = self.st
        u = urllib.parse.urlsplit(self.path)
        path = u.path
        if path == "/__ctl":
            return self.ctl(u.query)
        if path == "/__stats":
            with st.lock:
                body = json.dumps({"requests": st.requests, "redirects": st.redirects, "ranges": st.ranges, "drops": st.drops, "if_range_seen": st.if_range_seen,
                                   "served": st.served, "served_total": sum(st.served.values()), "faults": st.faults}, indent=1).encode()
            return self.small(200, body, "application/json")
        m = re.fullmatch(r"/releases/latest/download/([^/]+)", path)
        if m:
            with st.lock:
                st.redirects += 1
            return self.small(302, extra={"Location": f"/releases/download/{st.latest}/{m.group(1)}"})
        m = re.fullmatch(r"/releases/download/([^/]+)/([^/]+)", path)
        if m:
            with st.lock:
                st.redirects += 1
            # the signature changes with every answer, like the CDN's
            sig = hashlib.sha1(f"{time.time()}".encode()).hexdigest()[:12]
            return self.small(302, extra={"Location": f"http://{self.headers.get('Host')}/cdn/{m.group(1)}/{m.group(2)}?sig={sig}"})
        m = re.fullmatch(r"/cdn/([^/]+)/([^/]+)", path)
        if m:
            return self.serve(m.group(1), m.group(2))
        return self.small(404, b"not found")

    def ctl(self, query):
        st = self.st
        q = urllib.parse.parse_qs(query, keep_blank_values=True)
        with st.lock:
            if "reset" in q:
                st.reset()
            if "clear" in q:
                st.clear_stats()
            for k, vs in q.items():
                v = vs[-1]
                if k == "drop_mb":
                    st.drop_mb = float(v)
                elif k == "drop_rst":
                    st.drop_rst = v == "1"
                elif k == "slow_kbps":
                    st.slow_kbps = int(v)
                elif k == "stall":
                    st.stall = int(v)
                elif k == "corrupt":
                    st.corrupt = v or None
                elif k == "corrupt_times":
                    st.corrupt_times = int(v)
                elif k == "splice":
                    st.splice = v or None
                elif k == "no_range":
                    st.no_range = v == "1"
                elif k == "wrong_range":
                    st.wrong_range = v == "1"
                elif k == "portal":
                    st.portal = int(v)
                elif k == "status":
                    code, n = v.split(",")
                    st.status = [int(code), int(n)]
                elif k == "missing":
                    st.missing = v or None
                elif k == "latest":
                    st.latest = v
                elif k == "patch":
                    for one in vs:
                        tag, key, val = one.split(",", 2)
                        st.patches.setdefault(tag, {})[key] = json.loads(val)
        self.small(200, b"ok")

    def serve(self, tag, name):
        st = self.st
        path = os.path.join(st.root, name)
        rng = self.headers.get("Range")
        with st.lock:
            st.requests += 1
            if rng:
                st.ranges += 1
            if self.headers.get("If-Range"):
                st.if_range_seen += 1
            if st.status and st.status[1] > 0:
                st.status[1] -= 1
                code = st.status[0]
                st.faults.append(f"status {code} for {name}")
                return self.small(code, b"busy")
            if st.portal > 0:
                st.portal -= 1
                st.faults.append(f"portal page for {name}")
                return self.small(200, b"<html><body>Sign in to this hotspot</body></html>", "text/html")
            if st.missing == name:
                st.faults.append(f"404 for {name}")
                return self.small(404, b"not found")
        if name == "manifest.json":
            body = st.manifest(tag)
            size = len(body)
        else:
            if not os.path.isfile(path):
                return self.small(404, b"not found")
            body = None
            size = os.path.getsize(path)
        etag = '"v1-%x"' % size
        lastmod = time.strftime("%a, %d %b %Y %H:%M:%S GMT", time.gmtime(os.path.getmtime(path)))
        start = 0
        end = size - 1
        code = 200
        if rng and not st.no_range:
            m = re.fullmatch(r"bytes=(\d+)-(\d*)", rng.strip())
            if m:
                start = int(m.group(1))
                if m.group(2):
                    end = min(end, int(m.group(2)))
                if start >= size:
                    return self.small(416, extra={"Content-Range": f"bytes */{size}"})
                code = 206
        head_start = start
        other_version = False
        cut_after = int(st.drop_mb * 1024 * 1024) if st.drop_mb > 0 else 0
        stall_now = False
        with st.lock:
            if st.stall > 0 and name != "manifest.json":
                st.stall -= 1
                stall_now = True
                st.faults.append(f"silence in {name} after 1 MB")
            if st.splice == name:
                st.splice = None
                other_version = True
                cut_after = 3 * 1024 * 1024
                etag = '"v0-other"'
                st.faults.append(f"another version of {name}, cut after 3 MB")
            if st.wrong_range and code == 206:
                head_start = start + 1
                st.faults.append(f"a wrong Content-Range for {name}")
            flip_at = -1
            if st.corrupt == name and st.corrupt_times > 0:
                flip_at = size // 2
                if start <= flip_at <= end:
                    st.corrupt_times -= 1
                    st.faults.append(f"flipped a byte of {name} at {flip_at}")
                else:
                    flip_at = -1
        self.send_response(code)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", etag)
        self.send_header("Last-Modified", lastmod)
        if code == 206:
            self.send_header("Content-Range", f"bytes {head_start}-{end}/{size}")
        self.end_headers()
        sent = 0
        pos = start
        t0 = time.time()
        step = 64 * 1024
        f = None if body is not None else open(path, "rb")
        try:
            if f:
                f.seek(start)
            while pos <= end:
                n = min(step, end - pos + 1)
                data = body[pos:pos + n] if body is not None else f.read(n)
                if not data:
                    break
                if other_version:
                    data = bytes(b ^ 0x5A for b in data)
                if flip_at >= 0 and pos <= flip_at < pos + len(data):
                    ba = bytearray(data)
                    ba[flip_at - pos] ^= 0xFF
                    data = bytes(ba)
                if cut_after and sent + len(data) > cut_after:
                    # the rest of the allowance, then the line goes dead
                    keep = cut_after - sent
                    if keep > 0:
                        self.wfile.write(data[:keep])
                        sent += keep
                        with st.lock:
                            st.served[name] = st.served.get(name, 0) + keep
                    with st.lock:
                        st.drops += 1
                    self.wfile.flush()
                    if st.drop_rst:
                        self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                    self.close_connection = True
                    self.connection.close()
                    return
                self.wfile.write(data)
                sent += len(data)
                pos += len(data)
                with st.lock:
                    st.served[name] = st.served.get(name, 0) + len(data)
                if stall_now and sent >= 1024 * 1024:
                    self.wfile.flush()
                    time.sleep(60)
                    self.close_connection = True
                    self.connection.close()
                    return
                if st.slow_kbps:
                    want = sent / (st.slow_kbps * 1024.0)
                    wait = want - (time.time() - t0)
                    if wait > 0:
                        time.sleep(wait)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            self.close_connection = True
        finally:
            if f:
                f.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--port", type=int, default=8791)
    a = ap.parse_args()
    Handler.st = State(a.root)
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    srv.daemon_threads = True
    print(f"serving {a.root} on 127.0.0.1:{a.port}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
