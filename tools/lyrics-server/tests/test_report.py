#!/usr/bin/env python3
"""POST /api/report, the unit's screen check: the key is asked as on the other routes, what is not JSON or is too large
is refused, a good body is kept byte for byte as <cache>/reports/<utc-time>.json, two in one second keep both, and only
the newest 200 stay. The server's handler on a port of its own and a temporary folder: no model, no sound, and never
the running server or its folder.

    python tests/test_report.py            (or: python -m unittest tests.test_report)
"""
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import server  # noqa: E402

KEY = "testkey234"


class Report(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="amri-report-")
        server.CFG = {"key": KEY, "model": "none", "home": cls.tmp}
        cls.was = server.CACHE
        server.CACHE = cls.tmp
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.srv.daemon_threads = True
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.url = "http://127.0.0.1:%d/api/report" % cls.srv.server_address[1]
        cls.folder = os.path.join(cls.tmp, "reports")

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()
        server.CACHE = cls.was
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def setUp(self):
        shutil.rmtree(self.folder, ignore_errors=True)

    def post(self, body, key=KEY, url=None):
        req = urllib.request.Request(url or self.url, data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        if key:
            req.add_header("X-Lyrics-Key", key)
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return r.status, json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")

    def files(self):
        return sorted(os.listdir(self.folder)) if os.path.isdir(self.folder) else []

    def test_no_key(self):
        code, d = self.post(b"{}", key=None)
        self.assertEqual(code, 401)
        self.assertEqual(self.files(), [])

    def test_wrong_key(self):
        code, d = self.post(b"{}", key="wrongkey22")
        self.assertEqual(code, 401)
        self.assertEqual(self.files(), [])

    def test_key_in_the_address(self):
        code, d = self.post(b'{"a": 1}', key=None, url=self.url + "?key=" + KEY)
        self.assertEqual(code, 200)

    def test_kept_as_it_came(self):
        body = json.dumps({"kind": "amri-screen-check", "name": "فحص الشاشة", "n": 1.5}, ensure_ascii=False, indent=1).encode("utf-8")
        code, d = self.post(body)
        self.assertEqual((code, d), (200, {"ok": True}))
        names = self.files()
        self.assertEqual(len(names), 1)
        self.assertRegex(names[0], r"^\d{4}-\d\d-\d\dT\d\d-\d\d-\d\dZ\.json$")
        with open(os.path.join(self.folder, names[0]), "rb") as f:
            self.assertEqual(f.read(), body)

    def test_two_in_one_second_keep_both(self):
        for i in range(5):
            self.assertEqual(self.post(json.dumps({"i": i}).encode())[0], 200)
        self.assertEqual(len(self.files()), 5)

    def test_refused(self):
        self.assertEqual(self.post(b"not json")[0], 400)
        self.assertEqual(self.post(b"[1, 2]")[0], 400)      # (JSON, but not a report)
        self.assertEqual(self.post(b"\xff\xfe\x00")[0], 400)  # (not UTF-8)
        self.assertEqual(self.post(b"")[0], 400)
        self.assertEqual(self.files(), [])

    def test_size_limit(self):
        most = b'{"x": "' + b"a" * (server.MAX_REPORT - 9) + b'"}'
        self.assertEqual(len(most), server.MAX_REPORT)
        self.assertEqual(self.post(most)[0], 200)
        self.assertEqual(len(self.files()), 1)
        over = b'{"x": "' + b"a" * (server.MAX_REPORT - 8) + b'"}'
        try:
            code = self.post(over)[0]
        except (ConnectionError, urllib.error.URLError):
            code = 413  # (the server answered without reading it all: the client may see the connection go first)
        self.assertEqual(code, 413)
        self.assertEqual(len(self.files()), 1)

    def test_newest_kept(self):
        os.makedirs(self.folder)
        for i in range(server.REPORTS_KEPT + 3):
            with open(os.path.join(self.folder, "2020-01-01T00-%02d-%02dZ.json" % (i // 60, i % 60)), "w") as f:
                f.write("{}")
        oldest = sorted(self.files())[:4]
        self.assertEqual(self.post(b"{}")[0], 200)
        names = self.files()
        self.assertEqual(len(names), server.REPORTS_KEPT)
        for n in oldest:
            self.assertNotIn(n, names)
        self.assertTrue(any(not n.startswith("2020") for n in names), "the new one stays")

    def test_other_routes_still_ask_the_key(self):
        code, d = self.post(b"{}", key=None, url=self.url.replace("/api/report", "/api/sync/start"))
        self.assertEqual(code, 401)


if __name__ == "__main__":
    unittest.main()
