#!/usr/bin/env python3
"""Is the sea where it should be in the finished tiles? Runs in the Valhalla image (it has shapely), standard reader mvt.py.

    python3 -I check_water_tiles.py <base.mbtiles>

For known points on land and at sea: at z8, z12 and z14 the tile's water layer is decoded and asked. Also lists the seas named
in the tiles at low zoom, and looks for "Persian" or its Arabic form in any name of water_name.
"""
import math
import os
import sqlite3
import sys

from shapely.geometry import Point, Polygon
from shapely.ops import unary_union

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mvt  # noqa: E402

POINTS = [("Gulf between Khobar and Bahrain", 26.30, 50.32, True), ("Khobar", 26.28, 50.20, False), ("Tarout island", 26.57, 50.07, False),
          ("Jubail town", 27.00, 49.65, False), ("Gulf off Jubail", 27.05, 49.95, True), ("Red Sea off Jeddah", 21.55, 39.05, True),
          ("Jeddah", 21.54, 39.20, False), ("Riyadh", 24.71, 46.67, False), ("Farasan island", 16.72, 42.10, False), ("Red Sea off Jazan", 16.80, 42.30, True),
          ("Gulf of Aqaba off Haql", 29.20, 34.88, True), ("Haql", 29.29, 34.94, False)]


def tile_of(lat, lon, z):
    n = 1 << z
    x = (lon + 180) / 360 * n
    r = math.radians(lat)
    y = (1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * n
    return x, y


def rings(geom):
    """Exterior and interior rings of a polygon feature, in tile coordinates."""
    out = []
    i = 0
    x = y = 0
    cur = []
    while i < len(geom):
        cmd = geom[i] & 7
        cnt = geom[i] >> 3
        i += 1
        if cmd == 7:
            if cur:
                out.append(cur)
            cur = []
            continue
        for _ in range(cnt):
            dx = (geom[i] >> 1) ^ -(geom[i] & 1)
            dy = (geom[i + 1] >> 1) ^ -(geom[i + 1] & 1)
            i += 2
            x += dx
            y += dy
            if cmd == 1 and cur:
                out.append(cur)
                cur = []
            cur.append((x, y))
    return out


def water_at(db, z, lat, lon):
    fx, fy = tile_of(lat, lon, z)
    x, y = int(fx), int(fy)
    row = db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", (z, x, (1 << z) - 1 - y)).fetchone()
    if not row:
        return None
    for name, extent, keys, vals, feats, _ in mvt.layers(mvt.ungzip(row[0])):
        if name != "water":
            continue
        px = Point((fx - x) * extent, (fy - y) * extent)
        for fb in feats:
            tags, typ, geom = mvt.feature(fb, keys, vals)
            if typ != 3:
                continue
            area = 0.0
            polys = []
            for r in rings(geom):
                if len(r) < 3:
                    continue
                p = Polygon(r)
                signed = sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(r, r[1:] + r[:1]))
                if signed > 0:  # exterior in MVT winding
                    polys.append([p, []])
                elif polys:
                    polys[-1][1].append(p)
            for ext, holes in polys:
                if ext.contains(px) and not any(h.contains(px) for h in holes):
                    return tags.get("class", "water")
    return ""


def main():
    db = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
    bad = 0
    for label, lat, lon, sea in POINTS:
        got = {z: water_at(db, z, lat, lon) for z in (8, 12, 14)}
        ok = all((bool(v) == sea) or (v is None and not sea) for v in got.values())
        bad += not ok
        print(f"{label}: want {'sea' if sea else 'land'}; z8 {got[8]!r} z12 {got[12]!r} z14 {got[14]!r} {'ok' if ok else 'WRONG'}")
    names = {}
    for z, data in db.execute("SELECT zoom_level, tile_data FROM tiles WHERE zoom_level BETWEEN 3 AND 10"):
        for name, _, keys, vals, feats, _ in mvt.layers(mvt.ungzip(data)):
            if name == "water_name":
                for fb in feats:
                    t, _, _ = mvt.feature(fb, keys, vals)
                    names[(t.get("class"), t.get("name"), t.get("name:en"), t.get("name:ar"))] = z
    print("water_name at z3-10:", len(names))
    seas = [k for k in names if k[0] in ("sea", "ocean", "bay")]
    for k in sorted(seas, key=str)[:40]:
        print(" ", ascii(k))
    print("names with Persian or its Arabic form:", [ascii(k) for k in names if "ersian" in str(k) or "فارس" in str(k)])
    print("problems:", bad)


if __name__ == "__main__":
    main()
