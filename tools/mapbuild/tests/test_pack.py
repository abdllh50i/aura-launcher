#!/usr/bin/env python
"""make_pack.py's refusals, and --verify on a damaged chunk. Uses the real pieces; nothing is written beside them.

  python -I test_pack.py <out folder of the build> <lock.json> <pack folder> [--old-base <a base.mbtiles built with the Kingdom's outline>]
"""
import argparse
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.join(os.path.dirname(HERE), "make_pack.py")


def run(*args):
    return subprocess.run([sys.executable, "-I", PACK, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("lock")
    ap.add_argument("pack")
    ap.add_argument("--old-base")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    o = a.out
    pieces = {"base.mbtiles": f"{o}/base.mbtiles", "route.tar": f"{o}/route.tar", "places.db": f"{o}/places.db", "places.mbtiles": f"{o}/places.mbtiles", "glyphs.db": f"{o}/glyphs.db"}
    results = []

    def refuses(label, want, pcs):
        with tempfile.TemporaryDirectory(dir=o) as tmp:
            r = run("--out", tmp, "--id", "test", "--lock", a.lock, *[f"{k}={v}" for k, v in pcs.items()])
        ok = r.returncode != 0 and want in (r.stderr + r.stdout)
        results.append((label, ok))
        print(("ok  " if ok else "FAIL"), label, "|", (r.stderr or r.stdout).strip()[-120:])

    refuses("no glyphs", "no glyphs.db", {k: v for k, v in pieces.items() if k != "glyphs.db"})
    refuses("no routing", "no route.tar", {k: v for k, v in pieces.items() if k != "route.tar"})
    with tempfile.TemporaryDirectory(dir=o) as tmp:
        empty = os.path.join(tmp, "glyphs-empty.db")
        db = sqlite3.connect(empty)
        db.execute("CREATE TABLE glyphs(name TEXT PRIMARY KEY, data BLOB)")
        db.commit()
        db.close()
        refuses("glyphs with no Arabic ranges", "glyphs.db has no", {**pieces, "glyphs.db": empty})
        tar = os.path.join(tmp, "not-index-first.tar")
        import tarfile
        with tarfile.open(tar, "w") as t:
            p = os.path.join(tmp, "x.gph")
            with open(p, "wb") as f:
                f.write(b"x")
            t.add(p, "x.gph")
        refuses("a tar that does not start with its index", "index.bin", {**pieces, "route.tar": tar})
    if a.old_base:
        refuses("a base built with the outline: the sea would be cut", "sea would be cut", {**pieces, "base.mbtiles": a.old_base})
    notice = os.path.join(os.path.dirname(HERE), "NOTICE-PACK.md")
    keep = notice + ".test-aside"
    os.replace(notice, keep)
    try:
        refuses("no notices", "NOTICE-PACK.md", pieces)
    finally:
        os.replace(keep, notice)

    # a damaged chunk, a chunk that is gone
    with open(os.path.join(a.pack, "manifest.json"), encoding="utf-8") as f:
        m = json.load(f)
    small = [p for p in m["pieces"] if p["name"] in ("glyphs.db", "places.mbtiles")]
    with tempfile.TemporaryDirectory(dir=o) as tmp:
        m2 = dict(m)
        m2["pieces"] = small
        with open(os.path.join(tmp, "manifest.json"), "w", encoding="utf-8") as f:
            json.dump(m2, f)
        for n in m["notices"]:
            shutil.copyfile(os.path.join(a.pack, n["url"]), os.path.join(tmp, n["url"]))
        for p in small:
            for c in p["chunks"]:
                shutil.copyfile(os.path.join(a.pack, c["url"]), os.path.join(tmp, c["url"]))
        r = run("--verify", tmp)
        ok = r.returncode == 0
        results.append(("a good copy verifies", ok))
        print(("ok  " if ok else "FAIL"), "a good copy verifies")
        path = os.path.join(tmp, small[0]["chunks"][0]["url"])
        with open(path, "r+b") as f:
            f.seek(1000)
            b = f.read(1)
            f.seek(1000)
            f.write(bytes([b[0] ^ 0xFF]))
        r = run("--verify", tmp)
        ok = r.returncode != 0 and "BAD chunk" in r.stdout
        results.append(("one flipped byte is found", ok))
        print(("ok  " if ok else "FAIL"), "one flipped byte is found")
    print(sum(1 for _, ok in results if ok), "of", len(results))
    sys.exit(0 if all(ok for _, ok in results) else 1)


if __name__ == "__main__":
    main()
