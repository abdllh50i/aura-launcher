#!/usr/bin/env python
"""The pack: every piece cut into chunks of 64 MiB, each chunk gzipped, sizes and hashes in manifest.json (maps.md section 15.2). Nothing is uploaded: this makes a folder
that a release or a USB stick can hold.

  python -I make_pack.py --out <folder> --id ksa-2026.11 --lock lock.json base.mbtiles=<path> route.tar=<path> places.db=<path> places.mbtiles=<path> glyphs.db=<path> [voice.db=<path>]
  python -I make_pack.py --verify <folder>            every chunk against its hash, then every piece whole against its hash

It stops without the notices, without glyphs (a fresh offline unit would draw a map with no words), with a Valhalla version or font stack the manifest does not expect, or with a file that fails
SQLite's quick_check. gzip on every chunk, tiles too: it saves about a fifth of base.mbtiles although its tiles are gzipped (zlib level 6, no time in the header, so the same input gives the same chunks).

On the unit: fetch or copy a chunk, check its sha256, gunzip it and write it at its offset, mark it done; at the end check the piece's sha256, open it, and only then switch to the new pack. Free space needed
before starting is needBytes: all pieces unpacked, one chunk of work space and 200 MB to spare.
"""
import argparse
import gzip
import hashlib
import json
import multiprocessing as mp
import os
import re
import shutil
import sqlite3
import sys
import tarfile
import time
import zlib

CHUNK = 64 * 1024 * 1024
LEVEL = 6
HERE = os.path.dirname(os.path.abspath(__file__))
NEEDED = ("base.mbtiles", "route.tar", "places.db", "places.mbtiles", "glyphs.db")
NOTICE_WORDS = ["OpenStreetMap", "Open Database License", "OpenMapTiles", "Natural Earth", "Overture", "Foursquare", "Community Data License", "Apache", "Valhalla", "valhalla-mobile", "Protocol Buffers", "Boost", "LZ4", "Abseil", "RapidJSON", "Open Font License", "Readex Pro"]
LICENCES = ("CDLA-Permissive-2.0.txt", "Apache-2.0.txt", "OFL-1.1.txt")
FONT_STACKS = ("Readex Pro AMRI Regular", "Readex Pro AMRI Bold")
VALHALLA = "3.9.1"


def stop(msg):
    raise SystemExit("make_pack: " + msg)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def one_chunk(job):
    path, out_path, off = job
    with open(path, "rb") as f:
        f.seek(off)
        raw = f.read(CHUNK)
    c = zlib.compressobj(LEVEL, zlib.DEFLATED, 31)
    gz = c.compress(raw) + c.flush()
    with open(out_path, "wb") as f:
        f.write(gz)
    return {"url": os.path.basename(out_path), "offset": off, "raw": len(raw), "bytes": len(gz), "gz": True, "sha256": hashlib.sha256(gz).hexdigest()}


def quick_check(path):
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        got = db.execute("PRAGMA quick_check").fetchone()[0]
        if got != "ok":
            stop(f"{path}: quick_check says {got}")
        mode = db.execute("PRAGMA journal_mode").fetchone()[0]
        if mode == "wal":
            stop(f"{path}: WAL journal, a read-only open on the unit would write")
    finally:
        db.close()


def check_pieces(pieces, lock):
    """What a pack must hold to work with nothing else on the unit. Returns facts for the manifest."""
    for n in NEEDED:
        if n not in pieces:
            stop(f"no {n}")
        if not os.path.exists(pieces[n]):
            stop(f"{pieces[n]} does not exist")
    for n, p in pieces.items():
        if n.endswith((".db", ".mbtiles")):
            quick_check(p)
    g = sqlite3.connect(f"file:{pieces['glyphs.db']}?mode=ro", uri=True)
    names = {r[0] for r in g.execute("SELECT name FROM glyphs")}
    g.close()
    for stack in FONT_STACKS:
        for rng in ("0-255", "1536-1791", "65024-65279"):  # Latin, Arabic, the joined forms
            if f"{stack}/{rng}" not in names:
                stop(f"glyphs.db has no {stack}/{rng}")
    p = sqlite3.connect(f"file:{pieces['places.db']}?mode=ro", uri=True)
    meta = dict(p.execute("SELECT k,v FROM meta"))
    p.close()
    b = sqlite3.connect(f"file:{pieces['base.mbtiles']}?mode=ro", uri=True)
    bm = dict(b.execute("SELECT name,value FROM metadata"))
    water = b.execute("SELECT count(*) FROM tiles_shallow WHERE zoom_level=14").fetchone()[0]
    b.close()
    if water < 400000:
        stop(f"base.mbtiles holds {water} z14 rows: built with the Kingdom's outline? The sea would be cut (see build_base.sh)")
    with tarfile.open(pieces["route.tar"]) as t:
        first = t.next()
    if first.name.lstrip("./") != "index.bin":
        stop(f"route.tar starts with {first.name}, not index.bin: the unit's scan-free start needs it first")
    versions = (lock or {}).get("images", {})
    if lock and VALHALLA not in json.dumps(versions.get("valhalla", {})):
        stop("lock.json does not pin Valhalla " + VALHALLA)
    return meta, bm


def check_notices():
    path = os.path.join(HERE, "NOTICE-PACK.md")
    if not os.path.exists(path):
        stop("no NOTICE-PACK.md")
    with open(path, encoding="utf-8") as f:
        text = f.read()
    missing = [w for w in NOTICE_WORDS if w not in text]
    if missing:
        stop("NOTICE-PACK.md does not name: " + ", ".join(missing))
    for n in LICENCES:
        if not os.path.exists(os.path.join(HERE, "licences", n)):
            stop(f"no licences/{n}")
    return path


def verify(folder):
    with open(os.path.join(folder, "manifest.json"), encoding="utf-8") as f:
        m = json.load(f)
    t0 = time.time()
    bad = 0
    for extra in m["notices"]:
        if sha256_file(os.path.join(folder, extra["url"])) != extra["sha256"]:
            print("BAD", extra["url"])
            bad += 1
    for p in m["pieces"]:
        whole = hashlib.sha256()
        size = 0
        offset = 0
        for c in p["chunks"]:
            with open(os.path.join(folder, c["url"]), "rb") as f:
                gz = f.read()
            if hashlib.sha256(gz).hexdigest() != c["sha256"] or len(gz) != c["bytes"]:
                print("BAD chunk", c["url"])
                bad += 1
                continue
            raw = zlib.decompress(gz, 31)
            if len(raw) != c["raw"] or c["offset"] != offset:
                print("BAD raw size or offset", c["url"])
                bad += 1
            whole.update(raw)
            size += len(raw)
            offset += len(raw)
        if size != p["size"] or whole.hexdigest() != p["sha256"]:
            print("BAD piece", p["name"], size, p["size"])
            bad += 1
        else:
            print("ok", p["name"], size, "bytes,", len(p["chunks"]), "chunks")
    print(f"verify: {bad} problems, {time.time() - t0:.0f} s")
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pieces", nargs="*")
    ap.add_argument("--out")
    ap.add_argument("--id")
    ap.add_argument("--lock")
    ap.add_argument("--verify")
    ap.add_argument("--min-app", type=int, default=200000)
    ap.add_argument("--procs", type=int, default=4)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    if a.verify:
        sys.exit(verify(a.verify))
    if not a.out or not a.id or not a.lock:
        stop("--out, --id and --lock are needed")
    with open(a.lock, encoding="utf-8") as f:
        lock = json.load(f)
    pieces = dict(x.split("=", 1) for x in a.pieces)
    meta, bm = check_pieces(pieces, lock)
    notice = check_notices()
    os.makedirs(a.out, exist_ok=True)
    for old in os.listdir(a.out):
        if old.endswith(".gz") or old == "manifest.json":
            os.remove(os.path.join(a.out, old))
    t0 = time.time()
    jobs = []
    order = []
    for name, path in pieces.items():
        size = os.path.getsize(path)
        idx = 0
        for off in range(0, size, CHUNK):
            jobs.append((path, os.path.join(a.out, f"{name}.{idx:03d}.gz"), off))
            order.append(name)
            idx += 1
    with mp.Pool(a.procs) as pool:
        done = pool.map(one_chunk, jobs, 1)
    out_pieces = []
    for name, path in pieces.items():
        chunks = [c for c, n in zip(done, order) if n == name]
        out_pieces.append({"name": name, "size": os.path.getsize(path), "sha256": sha256_file(path), "chunks": chunks})
    notices = []
    for src, dst in [(notice, "NOTICE-PACK.md")] + [(os.path.join(HERE, "licences", n), n) for n in LICENCES]:
        shutil.copyfile(src, os.path.join(a.out, dst))
        notices.append({"url": dst, "bytes": os.path.getsize(src), "sha256": sha256_file(src)})
    total_raw = sum(p["size"] for p in out_pieces)
    total_gz = sum(c["bytes"] for p in out_pieces for c in p["chunks"])
    manifest = {
        "format": 1, "id": a.id, "built": time.strftime("%Y-%m-%dT%H:%MZ", time.gmtime()),
        "sources": {"osm": lock.get("data", {}).get("osm_stamp"), "overture": meta.get("overture"), "valhalla": VALHALLA, "omt": "3.16"},
        "pieces": out_pieces, "notices": notices, "minApp": a.min_app, "normVersion": int(meta.get("norm_version", 0)), "placesSchema": int(meta.get("schema", 0)),
        "fonts": list(FONT_STACKS), "needBytes": total_raw + CHUNK + 200 * 1024 * 1024, "downloadBytes": total_gz,
        "attribution": "© OpenStreetMap contributors · © OpenMapTiles · Overture Maps",
    }
    with open(os.path.join(a.out, "manifest.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    largest = max(c["bytes"] for p in out_pieces for c in p["chunks"])
    print(json.dumps({"id": a.id, "assets": sum(len(p["chunks"]) for p in out_pieces) + len(notices) + 1, "raw_bytes": total_raw, "download_bytes": total_gz, "largest_chunk": largest, "need_bytes": manifest["needBytes"],
                      "pieces": {p["name"]: {"raw": p["size"], "gz": sum(c["bytes"] for c in p["chunks"]), "chunks": len(p["chunks"])} for p in out_pieces}, "seconds": round(time.time() - t0)}, indent=1))


if __name__ == "__main__":
    main()
