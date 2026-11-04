#!/usr/bin/env python
"""The tester's look at places.mbtiles: where are its densest tiles, what is in them, what was thinned, do points pile up on one spot.

  python -I poi_density.py --tiles out\\places.mbtiles --db out\\places.db --out places\\test\\poi-density.json
"""
import argparse
import collections
import json
import math
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import mvt  # noqa: E402

PLACES = {"Jubail": (27.0046, 49.6586), "Jubail Industrial": (27.09, 49.56), "Dammam": (26.4207, 50.0888), "Khobar": (26.2885, 50.2185), "Dhahran": (26.305, 50.17), "Riyadh": (24.7136, 46.6753), "Jeddah": (21.54, 39.17)}
CAPS = {12: 90, 13: 180, 14: 420}


def tile_center(x, y, z):
    n = 1 << z
    lon = (x + 0.5) / n * 360 - 180
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 0.5) / n))))
    return lat, lon


def tile_bounds(x, y, z):
    n = 1 << z
    lon0, lon1 = x / n * 360 - 180, (x + 1) / n * 360 - 180
    lat1 = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n))))
    lat0 = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 1) / n))))
    return lat0, lat1, lon0, lon1


def km(a, b, c, d):
    p1, p2 = math.radians(a), math.radians(c)
    x = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(d - b) / 2) ** 2
    return 12742 * math.asin(min(1, math.sqrt(x)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tiles", required=True)
    ap.add_argument("--db", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    t = sqlite3.connect(f"file:{a.tiles}?mode=ro", uri=True)
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    cats = {i: k for i, k in db.execute("SELECT id,key FROM cat")}
    areas = {r[0]: r for r in db.execute("SELECT id,ar,en,city_ar,city_en FROM area")}
    rep = {}
    for z in (12, 13, 14):
        tiles = []
        counts = []
        for x, row, data in t.execute("SELECT tile_column,tile_row,tile_data FROM tiles WHERE zoom_level=?", (z,)):
            y = (1 << z) - 1 - row
            raw = mvt.ungzip(data)
            n = 0
            for name, extent, keys, vals, feats, _ in mvt.layers(raw):
                n = len(feats)
            counts.append(n)
            tiles.append((n, len(data), x, y))
        tiles.sort(reverse=True)
        counts.sort()
        z_rep = {"tiles": len(tiles), "at_cap": sum(1 for c in counts if c >= CAPS[z]), "median_features": counts[len(counts) // 2], "p90": counts[int(len(counts) * 0.9)], "p99": counts[int(len(counts) * 0.99)], "max": counts[-1], "top": []}
        for n, size, x, y in tiles[:6]:
            z_rep["top"].append(describe(t, db, cats, areas, z, x, y, n, size))
        # the owner's cities: the densest tile within 15 km
        z_rep["cities"] = {}
        for name, (la, lo) in PLACES.items():
            best = None
            for n, size, x, y in tiles:
                c = tile_center(x, y, z)
                if km(la, lo, c[0], c[1]) <= 15:
                    best = (n, size, x, y)
                    break
            if best:
                z_rep["cities"][name] = describe(t, db, cats, areas, z, best[2], best[3], best[0], best[1], brief=True)
        rep[str(z)] = z_rep
        print(z, {k: v for k, v in z_rep.items() if k not in ("top", "cities")}, flush=True)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(rep, f, ensure_ascii=False, indent=1)


def describe(t, db, cats, areas, z, x, y, n, size, brief=False):
    row = (1 << z) - 1 - y
    data = t.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", (z, x, row)).fetchone()[0]
    feats_out = []
    pts = collections.Counter()
    for name, extent, keys, vals, feats, _ in mvt.layers(mvt.ungzip(data)):
        for fb in feats:
            tags, typ, geom = mvt.feature(fb, keys, vals)
            p = mvt.first_point(geom)
            pts[p] += 1
            feats_out.append(tags)
    la0, la1, lo0, lo1 = tile_bounds(x, y, z)
    rows = db.execute("SELECT id,rank,cat,area,name FROM p WHERE kind=6 AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?", (int(la0 * 1e6), int(la1 * 1e6), int(lo0 * 1e6), int(lo1 * 1e6))).fetchall()
    kept = {f["id"] for f in feats_out}
    area_names = collections.Counter()
    for r in rows:
        ar = areas.get(r[3])
        if ar and r[0] in kept:
            area_names[(ar[1] or ar[2] or "") + "، " + (ar[3] or ar[4] or "")] += 1
    best_dropped = sorted(((r[1], r[4], cats.get(r[2])) for r in rows if r[0] not in kept), reverse=True)[:8]
    lowest_kept = sorted(f["rank"] for f in feats_out)[:1]
    cen = tile_center(x, y, z)
    d = {"z": z, "x": x, "y": y, "features": n, "gz_bytes": size, "center": [round(cen[0], 4), round(cen[1], 4)], "where": area_names.most_common(3), "rows_in_p": len(rows), "lowest_rank_kept": lowest_kept[0] if lowest_kept else None,
         "points_with_2_or_more": sum(1 for v in pts.values() if v >= 2), "largest_pile": max(pts.values()) if pts else 0, "top_cats": collections.Counter(cats.get(f["cat"]) for f in feats_out).most_common(6), "best_dropped": best_dropped}
    if not brief:
        d["best_kept"] = [(f["rank"], f["name"], cats.get(f["cat"])) for f in feats_out[:12]]
    return d


if __name__ == "__main__":
    main()