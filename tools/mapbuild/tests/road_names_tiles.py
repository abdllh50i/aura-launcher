#!/usr/bin/env python
"""Road names in finished tiles: of the named segments in transportation_name at z14, how many has the Arabic map a word for, how many stay Latin. Before and after a build, in the owner's box and over the Kingdom.

    python -I road_names_tiles.py <base.mbtiles> [<another base.mbtiles> ...] [--every N]

Named segments are counted per tile and per name, as a person sees them: one street cut in two tiles counts twice. --every N: the Kingdom on every Nth z14 tile (the box always whole).
"""
import argparse
import collections
import json
import os
import sqlite3
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import mvt  # noqa: E402

BOX = (26.10, 27.25, 49.40, 50.30)
ROADS = {"primary", "secondary", "tertiary", "minor", "service"}
MAJOR = {"motorway", "trunk"}


def tile_in_box(x, y, z=14):
    import math
    n = 1 << z
    lon0 = x / n * 360 - 180
    lon1 = (x + 1) / n * 360 - 180
    lat1 = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n))))
    lat0 = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 1) / n))))
    return lon1 >= BOX[2] and lon0 <= BOX[3] and lat1 >= BOX[0] and lat0 <= BOX[1]


def count(path, every):
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    rows = db.execute("SELECT tile_column,tile_row,tile_data FROM tiles WHERE zoom_level=14")
    out = {"box": collections.Counter(), "kingdom": collections.Counter()}
    fields = collections.Counter()
    for x, tr, data in rows:
        y = (1 << 14) - 1 - tr
        in_box = tile_in_box(x, y)
        take = (x * 7 + y) % every == 0
        if not in_box and not take:
            continue
        for name, _, keys, vals, feats, _ in mvt.layers(mvt.ungzip(data)):
            if name != "transportation_name":
                continue
            seen = set()
            for fb in feats:
                t, _, _ = mvt.feature(fb, keys, vals)
                n = t.get("name")
                if not n or n in seen:
                    continue
                seen.add(n)
                for k in t:
                    fields[k] += 1
                c = t.get("class")
                arabic = bool(t.get("name:ar")) or mvt.has_arabic(n)
                what = "arabic" if arabic else "latin"
                group = "minor" if c in ROADS else "major" if c in MAJOR else "other"
                for area, on in (("box", in_box), ("kingdom", take)):
                    if on:
                        out[area]["named"] += 1
                        out[area][what] += 1
                        out[area][f"{group} {what}"] += 1
    return {k: dict(v) for k, v in out.items()}, fields


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--every", type=int, default=10)
    a = ap.parse_args()
    for f in a.files:
        got, fields = count(f, a.every)
        print(f, json.dumps(got), "fields:", [k for k, _ in fields.most_common(12)], flush=True)


if __name__ == "__main__":
    main()
