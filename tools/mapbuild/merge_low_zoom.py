#!/usr/bin/env python
"""Base tiles from two builds of Planetiler: the low zooms of a wide one, the rest of a narrower one.   python -I merge_low_zoom.py <base.mbtiles> <low.mbtiles> <top low zoom> <out.mbtiles> [west south east north]

Why: on the screen of the unit a tile of z8 is 1.4 degrees wide and the Kingdom fills the picture at z5 to z7, so the sea must go on well past the Kingdom at those zooms, or it ends in a straight line (Planetiler cuts the polygons it reads
at --bounds, exactly). Those tiles are few and cheap. At street zooms the same rectangle would be a million rows of open sea, so the two are built apart.
Build <low> one zoom past <top low zoom>: Planetiler simplifies its last zoom less. Every row up to <top low zoom> comes from <low>, every row above it from <base>, and with a box also only the rows of <base> that touch it: a row costs a few bytes, even when its tile is one more copy of the same sea.
"""
import math
import os
import sqlite3
import sys
import time


def tile_x(lon, z):
    return int((lon + 180) / 360 * (1 << z))


def tile_y(lat, z):
    r = math.radians(lat)
    return int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * (1 << z))


def main():
    base, low, top, out = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
    box = [float(v) for v in sys.argv[5:9]] if len(sys.argv) >= 9 else None
    t0 = time.time()
    if os.path.exists(out):
        os.remove(out)
    src = sqlite3.connect(f"file:{base}?mode=ro", uri=True)
    db = sqlite3.connect(f"file:{out}", uri=True)
    db.execute("PRAGMA journal_mode=off")
    db.execute("PRAGMA synchronous=off")
    for (sql,) in src.execute("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type='view', type='index'"):
        db.execute(sql)
    db.executemany("INSERT INTO metadata VALUES (?, ?)", src.execute("SELECT name, value FROM metadata"))
    db.execute("ATTACH ? AS b", (f"file:{base}?mode=ro",))
    db.execute("ATTACH ? AS l", (f"file:{low}?mode=ro",))
    kept = 0
    for z in range(top + 1, 15):
        if box:
            w, s, e, n = box
            x0, x1 = tile_x(w, z), tile_x(e - 1e-9, z)
            y0, y1 = tile_y(n, z), tile_y(s + 1e-9, z)
            r0, r1 = (1 << z) - 1 - y1, (1 << z) - 1 - y0
            kept += db.execute("INSERT INTO tiles_shallow SELECT * FROM b.tiles_shallow WHERE zoom_level=? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?", (z, x0, x1, r0, r1)).rowcount
        else:
            kept += db.execute("INSERT INTO tiles_shallow SELECT * FROM b.tiles_shallow WHERE zoom_level=?", (z,)).rowcount
    db.execute("INSERT INTO tiles_data SELECT * FROM b.tiles_data WHERE tile_data_id IN (SELECT tile_data_id FROM tiles_shallow)")
    shift = db.execute("SELECT coalesce(max(tile_data_id),0) FROM tiles_data").fetchone()[0] + 1
    low_rows = db.execute("INSERT INTO tiles_shallow SELECT zoom_level,tile_column,tile_row,tile_data_id+? FROM l.tiles_shallow WHERE zoom_level<=?", (shift, top)).rowcount
    db.execute("INSERT INTO tiles_data SELECT tile_data_id+?,tile_data FROM l.tiles_data WHERE tile_data_id IN (SELECT tile_data_id FROM l.tiles_shallow WHERE zoom_level<=?)", (shift, top))
    db.commit()
    db.execute("DETACH b")
    db.execute("DETACH l")
    db.execute("VACUUM")
    db.close()
    print(f"rows: {kept:,} of z{top + 1}-14 from {os.path.basename(base)}, {low_rows:,} of z0-{top} from {os.path.basename(low)}; {os.path.getsize(out) / 1e6:.1f} MB; {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
