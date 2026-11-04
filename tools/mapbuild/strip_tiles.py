#!/usr/bin/env python3
"""Takes things out of a compact MBTiles after the build, without building it again.   Standard library only.

    python -I strip_tiles.py <in.mbtiles> [--out <out.mbtiles>] [--drop-layers poi,...] [--drop-names] [--sample N]

--drop-layers   removes whole layers from every tile.
--drop-names    also removes what the style never reads: name_de, name_int, name_en and name:nonlatin everywhere, and all names
                of the waterway layer (the style draws its lines without a label).
--sample N      only measures, on every Nth distinct blob, and prints the projected file size.
A tile left with no layer is dropped. Identical tiles stay shared (tiles_shallow is copied as it is). Without --out it only counts.
"""
import argparse
import gzip
import os
import sqlite3
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mvt  # noqa: E402

LEGACY = {"name_de", "name_int", "name_en", "name:nonlatin"}


def varint(v):
    out = bytearray()
    while v >= 0x80:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)
    return bytes(out)


def field(num, data):
    return varint((num << 3) | 2) + varint(len(data)) + data


def rewrite_layer(raw, drop):
    """One layer's bytes, without the keys in drop(key). Keys and values no feature uses any more are left out."""
    name, version, extent = "", 1, 4096
    keys, vals_raw, feats = [], [], []
    for f, _, v in mvt.fields(raw):
        if f == 1:
            name = v.decode("utf-8", "replace")
        elif f == 2:
            feats.append(v)
        elif f == 3:
            keys.append(v.decode("utf-8", "replace"))
        elif f == 4:
            vals_raw.append(v)
        elif f == 5:
            extent = v
        elif f == 15:
            version = v
    new_keys, new_vals = {}, {}
    out_feats = []
    for fb in feats:
        parts = []
        for f, w, v in mvt.fields(fb):
            if f == 2:
                t = mvt.packed(v)
                tags = []
                for i in range(0, len(t) - 1, 2):
                    k = keys[t[i]]
                    if drop(name, k):
                        continue
                    ki = new_keys.setdefault(k, len(new_keys))
                    vi = new_vals.setdefault(vals_raw[t[i + 1]], len(new_vals))
                    tags += [ki, vi]
                parts.append(field(2, b"".join(varint(x) for x in tags)))
            elif w == 0:
                parts.append(varint(f << 3) + varint(v))
            elif w == 2:
                parts.append(field(f, v))
        out_feats.append(b"".join(parts))
    body = field(1, name.encode())
    body += b"".join(field(2, x) for x in out_feats)
    body += b"".join(field(3, k.encode()) for k in new_keys)
    body += b"".join(field(4, v) for v in new_vals)
    body += varint(5 << 3) + varint(extent) + varint(15 << 3) + varint(version)
    return body


def layer_name(raw):
    for f, _, v in mvt.fields(raw):
        if f == 1:
            return v.decode("utf-8", "replace")
    return ""


def strip(data, drop_layers, drop_names):
    raw = mvt.ungzip(data)
    out = []
    for f, _, v in mvt.fields(raw):
        if f != 3:
            continue
        name = layer_name(v)
        if name in drop_layers:
            continue
        if drop_names:
            v = rewrite_layer(v, lambda lay, k: k in LEGACY or (lay == "waterway" and (k == "name" or k.startswith("name:"))))
        out.append(field(3, v))
    if not out:
        return None
    return gzip.compress(b"".join(out), 9, mtime=0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("--out")
    ap.add_argument("--drop-layers", default="")
    ap.add_argument("--drop-names", action="store_true")
    ap.add_argument("--sample", type=int, default=1)
    a = ap.parse_args()
    drop_layers = {x for x in a.drop_layers.split(",") if x}
    src = sqlite3.connect(f"file:{a.input}?mode=ro", uri=True)
    t0 = time.time()
    dst = None
    if a.out:
        if os.path.exists(a.out):
            os.remove(a.out)
        dst = sqlite3.connect(a.out)
        dst.execute("PRAGMA journal_mode=off")
        dst.execute("PRAGMA synchronous=off")
        for (sql,) in src.execute("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type='view', type='index'"):
            dst.execute(sql)
        dst.executemany("INSERT INTO metadata VALUES (?, ?)", src.execute("SELECT name, value FROM metadata"))
    before = after = n = gone = 0
    for i, (tid, data) in enumerate(src.execute("SELECT tile_data_id, tile_data FROM tiles_data ORDER BY tile_data_id")):
        if i % a.sample:
            continue
        new = strip(data, drop_layers, a.drop_names)
        n += 1
        before += len(data)
        if new is None:
            gone += 1
            continue
        after += len(new)
        if dst:
            dst.execute("INSERT INTO tiles_data VALUES (?, ?)", (tid, new))
    print(f"blobs read {n:,}; bytes {before:,} -> {after:,} ({100 * after / before:.1f} %); blobs left empty {gone:,}; {time.time() - t0:.0f} s")
    size = os.path.getsize(a.input)
    print(f"file {size / 1e6:.1f} MB; about {(size - (before - after) * a.sample) / 1e6:.1f} MB after this")
    if dst:
        dst.execute("ATTACH ? AS src", (f"file:{a.input}?mode=ro",))
        dst.execute("INSERT INTO tiles_shallow SELECT * FROM src.tiles_shallow WHERE tile_data_id IN (SELECT tile_data_id FROM tiles_data)")
        dst.commit()
        dst.execute("DETACH src")
        dst.execute("VACUUM")
        dst.close()
        print(f"wrote {a.out}: {os.path.getsize(a.out) / 1e6:.1f} MB; {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
