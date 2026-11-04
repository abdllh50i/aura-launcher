#!/usr/bin/env python
"""A small tile server for looking at an MBTiles file in a browser: http://127.0.0.1:<port>/<name>/{z}/{x}/{y}.pbf, nothing else.

  python -I serve_tiles.py --port 8799 places=D:\\k2501-work\\mapbuild\\out\\places.mbtiles [base=...planet.mbtiles] [--root folder]

--root also serves the files of one folder, so that a page and its tiles share an origin. Standard library only, loopback only.
"""
import argparse
import http.server
import mimetypes
import os
import re
import sqlite3
import sys
import threading

TILE = re.compile(r"^/(\w+)/(\d+)/(\d+)/(\d+)\.pbf$")


class Handler(http.server.BaseHTTPRequestHandler):
    sources = {}
    root = None
    local = threading.local()

    def log_message(self, *a):
        pass

    def _db(self, name):
        cache = getattr(self.local, "dbs", None)
        if cache is None:
            cache = self.local.dbs = {}
        if name not in cache:
            cache[name] = sqlite3.connect(f"file:{self.sources[name]}?mode=ro", uri=True)
        return cache[name]

    def do_GET(self):
        path = self.path.split("?")[0]
        m = TILE.match(path)
        if m and m.group(1) in self.sources:
            z, x, y = int(m.group(2)), int(m.group(3)), int(m.group(4))
            row = self._db(m.group(1)).execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", (z, x, (1 << z) - 1 - y)).fetchone()
            if not row:
                self.send_response(204)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                return
            data = bytes(row[0])
            self.send_response(200)
            self.send_header("Content-Type", "application/x-protobuf")
            if data[:2] == b"\x1f\x8b":
                self.send_header("Content-Encoding", "gzip")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(data)
            return
        if self.root:
            rel = os.path.normpath(path.lstrip("/")) or "index.html"
            full = os.path.join(self.root, rel)
            if not rel.startswith("..") and os.path.isfile(full):
                with open(full, "rb") as f:
                    body = f.read()
                self.send_response(200)
                self.send_header("Content-Type", mimetypes.guess_type(full)[0] or "application/octet-stream")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
        self.send_response(404)
        self.end_headers()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8799)
    ap.add_argument("--root")
    ap.add_argument("sources", nargs="+", help="name=path.mbtiles")
    a = ap.parse_args()
    Handler.sources = dict(s.split("=", 1) for s in a.sources)
    Handler.root = a.root
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    print(f"serving on http://127.0.0.1:{a.port}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
