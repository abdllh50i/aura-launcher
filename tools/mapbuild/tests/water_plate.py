#!/usr/bin/env python
"""The sea of a base.mbtiles as a picture, with no renderer: rows that exist in grey, water blue, no row white. To see where the rectangle ends, zoom by zoom.

    python -I water_plate.py <base.mbtiles> <out.png> <z> [west south east north]

Reads the layer water through mvt.py and fills it with PIL. Each tile is drawn at 256 px or less, so a whole zoom fits on one picture.
"""
import math
import os
import sqlite3
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import mvt  # noqa: E402


def tile_x(lon, z):
    return (lon + 180) / 360 * (1 << z)


def tile_y(lat, z):
    r = math.radians(lat)
    return (1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * (1 << z)


def rings(geom):
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
    if cur:
        out.append(cur)
    return out


def main():
    path, out, z = sys.argv[1], sys.argv[2], int(sys.argv[3])
    w, s, e, n = (float(v) for v in sys.argv[4:8]) if len(sys.argv) > 7 else (26.0, 5.0, 67.5, 40.0)
    x0, x1 = int(tile_x(w, z)), int(tile_x(e, z) - 1e-9)
    y0, y1 = int(tile_y(n, z)), int(tile_y(s, z) - 1e-9)
    size = max(32, min(256, 1600 // max(1, x1 - x0 + 1)))
    k = size / 4096.0
    im = Image.new("RGB", ((x1 - x0 + 1) * size, (y1 - y0 + 1) * size), (255, 255, 255))
    d = ImageDraw.Draw(im)
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    rows = 0
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            row = db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", (z, x, (1 << z) - 1 - y)).fetchone()
            ox, oy = (x - x0) * size, (y - y0) * size
            if not row:
                continue
            rows += 1
            d.rectangle((ox, oy, ox + size - 1, oy + size - 1), fill=(225, 222, 214))
            for name, extent, keys, vals, feats, _ in mvt.layers(mvt.ungzip(row[0])):
                if name != "water":
                    continue
                c = size / extent
                for fb in feats:
                    tags, typ, geom = mvt.feature(fb, keys, vals)
                    if typ != 3:
                        continue
                    for r in rings(geom):
                        if len(r) < 3:
                            continue
                        pts = [(ox + p[0] * c, oy + p[1] * c) for p in r]
                        signed = sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(r, r[1:] + r[:1]))
                        d.polygon(pts, fill=(120, 170, 225) if signed > 0 else (225, 222, 214))
            d.rectangle((ox, oy, ox + size - 1, oy + size - 1), outline=(190, 190, 190))
    im.save(out)
    print(f"z{z}: {x1 - x0 + 1} x {y1 - y0 + 1} tiles, {rows} rows, {im.size[0]} x {im.size[1]} px")


if __name__ == "__main__":
    main()
