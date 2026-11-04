#!/usr/bin/env python
"""Reads every tile of places.mbtiles with mvt.py, a reader that shares no code with the writer in build_poi_tiles.py, and compares it with places.db.

  python -I check_poi_tiles.py --tiles places.mbtiles --db places.db [--json report.json]

Checked for every feature: it is one point inside the tile; it has name, cat, rank, id; its id is a row of p with the same name, category and rank;
its position in the tile gives back the row's position to within one tile unit. Checked for every tile: the MVT version, the extent, one layer "places".
Also the MBTiles metadata, the tile_row flip (compared through the position test), and how much was kept per zoom.
"""
import argparse
import collections
import json
import math
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mvt  # noqa: E402


def merc_y(lat):
    s = math.sin(math.radians(lat))
    return 0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tiles", required=True)
    ap.add_argument("--db", required=True)
    ap.add_argument("--json")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    t = sqlite3.connect(f"file:{a.tiles}?mode=ro", uri=True)
    d = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    rows = {r[0]: r for r in d.execute("SELECT id,name,alt,cat,lat,lon,rank FROM p WHERE kind=6")}
    meta = dict(t.execute("SELECT name,value FROM metadata"))
    problems = collections.Counter()
    examples = {}
    per_zoom = collections.defaultdict(lambda: {"tiles": 0, "features": 0, "bytes": 0, "biggest": 0, "max_features": 0, "max_err_units": 0.0})
    cats = collections.Counter()
    seen_ids = {12: set(), 13: set(), 14: set()}
    keys_seen = collections.Counter()
    for z, x, row, data in t.execute("SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles"):
        y = (1 << z) - 1 - row
        pz = per_zoom[z]
        pz["tiles"] += 1
        pz["bytes"] += len(data)
        pz["biggest"] = max(pz["biggest"], len(data))
        raw = mvt.ungzip(bytes(data))
        layers = list(mvt.layers(raw))
        if len(layers) != 1 or layers[0][0] != "places":
            problems["layer"] += 1
            continue
        name, extent, keys, vals, feats, _ = layers[0]
        if extent != 4096:
            problems["extent"] += 1
        pz["features"] += len(feats)
        pz["max_features"] = max(pz["max_features"], len(feats))
        scale = (1 << z) * extent
        for f in feats:
            tags, typ, geom = mvt.feature(f, keys, vals)
            if typ != 1 or len(geom) != 3 or geom[0] != 9:
                problems["geometry"] += 1
                continue
            for k in tags:
                keys_seen[k] += 1
            px, py = mvt.first_point(geom)
            if not (0 <= px < extent and 0 <= py < extent):
                problems["outside tile"] += 1
            rid = tags.get("id")
            if rid not in rows:
                problems["id not in p"] += 1
                continue
            r = rows[rid]
            seen_ids[z].add(rid)
            if tags.get("name") != (r[1] if sum(c.isalpha() for c in r[1]) > 3 else None):  # (3 letters or less: a dot with no word, build_poi_tiles.py)
                problems["name differs"] += 1
                examples.setdefault("name differs", (tags.get("name"), r[1]))
            if tags.get("cat") != r[3]:
                problems["cat differs"] += 1
            if tags.get("rank") != r[6]:
                problems["rank differs"] += 1
            cats[r[3]] += 1
            ex = ((r[5] / 1e6 + 180.0) / 360.0 * scale) - (x * extent + px)
            ey = (merc_y(r[4] / 1e6) * scale) - (y * extent + py)
            err = max(abs(ex), abs(ey))
            pz["max_err_units"] = max(pz["max_err_units"], err)
            if err > 1.01:
                problems["position differs"] += 1
                examples.setdefault("position differs", (rid, ex, ey, z, x, y))
    report = {
        "metadata": {k: v for k, v in meta.items() if k != "attribution"},
        "zooms": {str(z): v for z, v in sorted(per_zoom.items())},
        "problems": dict(problems), "examples": {k: str(v) for k, v in examples.items()},
        "fields": dict(keys_seen),
        "distinct_places_shown_per_zoom": {str(z): len(s) for z, s in seen_ids.items()},
        "file_bytes": os.path.getsize(a.tiles),
    }
    print(json.dumps(report, ensure_ascii=False, indent=1))
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=1)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
