#!/usr/bin/env python
"""What the map must decode and draw: the largest tiles, and every character the tiles can ask the glyph ranges for.

  python -I tile_stats.py base.mbtiles out.json [--procs 6]

Reads all distinct blobs. Writes the largest tiles with their layers and which code points fall outside
OfflineMaps.GLYPH_RANGES (the 13 ranges the app stores for offline use), with examples by layer and field.
"""
import argparse
import json
import multiprocessing as mp
import sqlite3
import sys
import time
import zlib

RANGES = [0, 256, 512, 768, 1536, 1792, 8192, 8448, 64256, 64512, 64768, 65024, 65280]  # OfflineMaps.kt:35
IN_RANGES = set()
for r in RANGES:
    IN_RANGES.update(range(r, r + 256))
LABEL_KEYS = {"name", "name:ar", "name:en", "name:latin", "ref", "housenumber"}  # what MapStyle.kt draws as text


def varint(b, i):
    v = s = 0
    while True:
        c = b[i]
        i += 1
        v |= (c & 0x7F) << s
        if c < 128:
            return v, i
        s += 7


def fields(b):
    i, n = 0, len(b)
    while i < n:
        k, i = varint(b, i)
        t = k & 7
        if t == 0:
            v, i = varint(b, i)
        elif t == 2:
            ln, i = varint(b, i)
            v = b[i:i + ln]
            i += ln
        elif t == 1:
            v = b[i:i + 8]
            i += 8
        elif t == 5:
            v = b[i:i + 4]
            i += 4
        else:
            raise ValueError(t)
        yield k >> 3, v


def packed(b):
    i, n, out = 0, len(b), []
    while i < n:
        v, i = varint(b, i)
        out.append(v)
    return out


def layers(raw):
    """Each layer: name, keys, string values, raw feature messages."""
    for tag, lay in fields(raw):
        if tag != 3:
            continue
        name, keys, vals, feats, size = "", [], [], [], len(lay)
        for t, v in fields(lay):
            if t == 1:
                name = v.decode()
            elif t == 3:
                keys.append(v.decode())
            elif t == 4:
                sv = None
                for t2, v2 in fields(v):
                    if t2 == 1:
                        sv = v2.decode("utf8", "replace")
                vals.append(sv)
            elif t == 2:
                feats.append(v)
        yield name, keys, vals, feats, size


def scan(job):
    path, lo, hi = job
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    chars, examples, raw_total, n = {}, {}, 0, 0
    big = []
    for tid, data in db.execute("SELECT tile_data_id, tile_data FROM tiles_data WHERE tile_data_id >= ? AND tile_data_id < ?", (lo, hi)):
        raw = zlib.decompress(bytes(data), 31)
        raw_total += len(raw)
        n += 1
        big.append((len(raw), len(data), tid))
        if len(big) > 200:
            big = sorted(big, reverse=True)[:40]
        for name, keys, vals, feats, _ in layers(raw):
            # which values are labels: walk the features' tags only for layers that have text
            ks = [i for i, k in enumerate(keys) if k in LABEL_KEYS]
            if not ks:
                continue
            used = {}
            kset = set(ks)
            for f in feats:
                for t, v in fields(f):
                    if t == 2:
                        tags = packed(v)
                        for j in range(0, len(tags), 2):
                            if tags[j] in kset:
                                used[tags[j + 1]] = tags[j]
            for vi, ki in used.items():
                s = vals[vi]
                if not s:
                    continue
                key = keys[ki]
                for ch in s:
                    c = ord(ch)
                    d = chars.setdefault(key, {})
                    d[c] = d.get(c, 0) + 1
                    if c not in IN_RANGES and c not in examples and len(examples) < 4000:
                        examples[c] = (name, key, s[:60])
    return chars, examples, raw_total, n, sorted(big, reverse=True)[:40]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("out")
    ap.add_argument("--procs", type=int, default=6)
    a = ap.parse_args()
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    lo, hi = db.execute("SELECT min(tile_data_id), max(tile_data_id) FROM tiles_data").fetchone()
    step = max(1, (hi - lo + 1) // (a.procs * 8))
    jobs = [(a.db, i, i + step) for i in range(lo, hi + 1, step)]
    t0 = time.time()
    chars, examples, raw_total, n, big = {}, {}, 0, 0, []
    with mp.Pool(a.procs) as pool:
        for c, e, rt, nn, b in pool.imap_unordered(scan, jobs):
            for k, d in c.items():
                dd = chars.setdefault(k, {})
                for ch, cnt in d.items():
                    dd[ch] = dd.get(ch, 0) + cnt
            for ch, ex in e.items():
                examples.setdefault(ch, ex)
            raw_total += rt
            n += nn
            big.extend(b)
    big = sorted(big, reverse=True)[:30]
    print(f"scanned {n} blobs in {time.time() - t0:.0f} s, {raw_total / 1e6:.1f} MB raw", flush=True)
    # The biggest, by compressed bytes as well as raw: where they are and what is in them
    top = []
    byz = sorted(db.execute("SELECT tile_data_id, length(tile_data) FROM tiles_data ORDER BY length(tile_data) DESC LIMIT 30"), key=lambda r: -r[1])
    seen = set()
    for tid, _ in byz + [(b[2], 0) for b in big]:
        if tid in seen:
            continue
        seen.add(tid)
        data = bytes(db.execute("SELECT tile_data FROM tiles_data WHERE tile_data_id=?", (tid,)).fetchone()[0])
        where = db.execute("SELECT zoom_level, tile_column, tile_row FROM tiles_shallow WHERE tile_data_id=? LIMIT 1", (tid,)).fetchone()
        raw = zlib.decompress(data, 31)
        t1 = time.perf_counter()
        zlib.decompress(data, 31)
        t1 = time.perf_counter() - t1
        lay = {}
        for name, keys, vals, feats, size in layers(raw):
            lay[name] = {"bytes": size, "features": len(feats)}
        z, x, r = where
        y = (1 << z) - 1 - r
        top.append({"z": z, "x": x, "y": y, "gz": len(data), "raw": len(raw), "inflate_ms_py": round(t1 * 1000, 2), "layers": lay,
                    "features": sum(v["features"] for v in lay.values())})
    top.sort(key=lambda t: -t["gz"])
    outside = {}
    for key, d in chars.items():
        for c, cnt in d.items():
            if c not in IN_RANGES:
                o = outside.setdefault(c, {"count": 0, "by": {}, "ex": examples.get(c)})
                o["count"] += cnt
                o["by"][key] = o["by"].get(key, 0) + cnt
    # the same, but what share of labels each mode's text would lose
    result = {"blobs": n, "raw_mb": round(raw_total / 1e6, 1), "top": top[:40], "outside_ranges": {hex(k): v for k, v in sorted(outside.items())},
              "code_points_total": {k: len(v) for k, v in chars.items()}, "chars": {k: {hex(c): v for c, v in d.items()} for k, d in chars.items()}}
    with open(a.out, "w", encoding="utf8") as f:
        json.dump(result, f, ensure_ascii=False)
    print("top", json.dumps(top[0]), flush=True)
    print("outside ranges:", len(outside), flush=True)


if __name__ == "__main__":
    main()
