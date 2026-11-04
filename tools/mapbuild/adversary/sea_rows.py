#!/usr/bin/env python
"""Questions the plates raised, answered from the tiles: where the sea has no row (the map then shows its land colour), what water_name holds per zoom, what the green
areas are, whether the style's far POI bands can ever show, and whether boundaries say they are at sea.

  python -I sea_rows.py base.mbtiles out.json
"""
import collections
import json
import math
import os
import sqlite3
import sys
import zlib

sys.stdout.reconfigure(encoding="utf8")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import mvt  # noqa: E402  the build's own independent MVT reader

db = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)


def lonlat_to_tile(lon, lat, z):
    n = 1 << z
    return int((lon + 180) / 360 * n), int((1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n)


def row(z, x, y):
    r = db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", (z, x, (1 << z) - 1 - y)).fetchone()
    return bytes(r[0]) if r else None


def feats(data, want):
    raw = zlib.decompress(data, 31)
    for name, extent, keys, vals, fs, size in mvt.layers(raw):
        if name == want:
            for f in fs:
                yield mvt.feature(f, keys, vals)


SEA = {"Gulf off Jubail": (50.3, 27.2), "Gulf N of Bahrain": (50.65, 26.6), "Gulf N of Qatar": (51.3, 26.5), "Gulf mid": (51.5, 27.5), "Gulf off Kuwait": (49.0, 28.9),
       "Red Sea off Jeddah": (38.9, 21.5), "Red Sea mid": (38.0, 21.5), "Red Sea far side": (37.4, 21.5), "Red Sea off Yanbu": (37.6, 24.0), "Aqaba": (34.8, 28.8), "Off Jazan": (41.5, 16.8)}
out = {"sea": {}}
for name, (lon, lat) in SEA.items():
    res = {z: row(z, *lonlat_to_tile(lon, lat, z)) is not None for z in range(4, 15)}
    out["sea"][name] = res
    print(f"{name:20}", " ".join(f"{z}:{'y' if v else '-'}" for z, v in res.items()))

box = (49.5, 25.0, 52.0, 28.0)
out["gulf_box"] = {}
for z in range(6, 15):
    x0, y0 = lonlat_to_tile(box[0], box[3], z)
    x1, y1 = lonlat_to_tile(box[2], box[1], z)
    have = db.execute("SELECT count(*) FROM tiles_shallow WHERE zoom_level=? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?", (z, x0, x1, (1 << z) - 1 - y1, (1 << z) - 1 - y0)).fetchone()[0]
    out["gulf_box"][z] = [have, (x1 - x0 + 1) * (y1 - y0 + 1)]
print("Gulf box 49.5-52E 25-28N, rows of cells:", out["gulf_box"])

# all rows that are only sea? how many rows the bounds rectangle lacks, per zoom
b = (34.42717, 16.26132, 55.6951, 32.18388)
out["rect"] = {}
for z in (8, 10, 12, 14):
    x0, y0 = lonlat_to_tile(b[0], b[3], z)
    x1, y1 = lonlat_to_tile(b[2], b[1], z)
    have = db.execute("SELECT count(*) FROM tiles_shallow WHERE zoom_level=?", (z,)).fetchone()[0]
    out["rect"][z] = [have, (x1 - x0 + 1) * (y1 - y0 + 1)]
print("rows and cells in the bounds rectangle:", out["rect"])

wn = collections.defaultdict(collections.Counter)
for z in range(0, 15):
    ids = db.execute("SELECT DISTINCT tile_data_id FROM tiles_shallow WHERE zoom_level=?", (z,)).fetchall()
    if len(ids) > 4000:
        ids = ids[::max(1, len(ids) // 4000)]
    for (tid,) in ids:
        data = bytes(db.execute("SELECT tile_data FROM tiles_data WHERE tile_data_id=?", (tid,)).fetchone()[0])
        for tags, typ, geom in feats(data, "water_name"):
            wn[z][(tags.get("name:en") or tags.get("name:latin") or tags.get("name"), tags.get("class"))] += 1
out["water_name"] = {z: [[str(k), v] for k, v in c.most_common(8)] for z, c in wn.items()}
for z, c in sorted(wn.items()):
    print("water_name z", z, sum(c.values()), c.most_common(6))

# POI ranks, the style's bands: a <=3 from 13.6, b <=12 from 15, c <=40 from 16, d >40 from 17
ranks = collections.Counter()
for z, x, y in [(14, 10316, 7031), (14, 10004, 7193), (14, 10474, 6951), (14, 10470, 6950)]:
    d = row(z, x, y)
    if d:
        for tags, typ, geom in feats(d, "poi"):
            r = tags.get("rank")
            ranks["<=3" if r is not None and r <= 3 else "4-12" if r is not None and r <= 12 else "13-40" if r is not None and r <= 40 else ">40"] += 1
out["poi_bands"] = dict(ranks)
print("POI rank bands in 4 dense z14 tiles:", dict(ranks))

# Green: what covers Mecca and the Dahna, biggest names in the park layer at z8
green = collections.Counter()
for lon, lat, z in [(39.826, 21.4225, 14), (48.1, 25.6, 12), (46.5, 24.8, 10), (45, 26, 7), (48, 26, 7), (40, 28, 7)]:
    d = row(z, *lonlat_to_tile(lon, lat, z))
    if d:
        for tags, typ, geom in feats(d, "park"):
            green[(tags.get("name:en") or tags.get("name") or "?", tags.get("class"), f"z{z}")] += 1
out["green"] = [[str(k), v] for k, v in green.most_common(14)]
for k, v in green.most_common(14):
    print("park", k, v)

# Boundaries at sea
mar = collections.Counter()
for z, x, y in [lonlat_to_tile(50.3, 26.2, 11) and (11, *lonlat_to_tile(50.3, 26.2, 11)), (14, *lonlat_to_tile(50.33, 26.185, 14)), (8, *lonlat_to_tile(50.5, 26.5, 8))]:
    d = row(z, x, y)
    if d:
        for tags, typ, geom in feats(d, "boundary"):
            mar[(z, tags.get("admin_level"), tags.get("maritime"), tags.get("disputed"))] += 1
out["boundary"] = [[str(k), v] for k, v in mar.items()]
print("boundary (z, admin_level, maritime, disputed):", dict(mar))

# Strings the owner might object to
bad = collections.Counter()
ids = db.execute("SELECT tile_data_id FROM tiles_data").fetchall()
for (tid,) in ids[::4]:
    raw = zlib.decompress(bytes(db.execute("SELECT tile_data FROM tiles_data WHERE tile_data_id=?", (tid,)).fetchone()[0]), 31)
    for s in (b"Persian", "الفارسي".encode()):
        if s in raw:
            i = raw.find(s)
            bad[raw[max(0, i - 24):i + 24].decode("utf8", "replace")] += 1
out["persian"] = bad.most_common(10)
print("'Persian' strings (1 blob in 4):", bad.most_common(10))
with open(sys.argv[2], "w", encoding="utf8") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
