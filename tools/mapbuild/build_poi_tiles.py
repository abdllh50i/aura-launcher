#!/usr/bin/env python
"""POI vector tiles from places.db: out\\places.mbtiles, zoom 12-14, one layer "places" with fields name, name:en, kind, cat, rank, id.

  python build_poi_tiles.py --db places.db --out places.mbtiles

A small Mapbox Vector Tile writer for points, standard library only (tippecanoe would be one more download). A tile keeps its
best places by rank: at most so many per cell of a grid over the tile, so a dense quarter is thinned while a quiet one keeps
everything, and a limit for the whole tile. What is dropped stays searchable: the tile is for the eye. Rows on a geocoder's default point (p.src bit 4) never reach a tile.

Tile fields:
  name      p.name, the Arabic name when there is one. Absent when it has 3 letters or less (SPEC section 11: "Me" is not a place): the dot stays, with no word; search still finds the row in places.db
  name:en   p.alt when name is Arabic and alt is Latin (absent otherwise), for the English map; absent under the same rule
  kind      cat.icon: the colour group, an index the style can name (cat.id is not stable across builds)
  cat       cat.id (see table cat)
  rank      p.rank, 0-100, higher is more important
  id        p.id, to read the row when a place is tapped
"""
import argparse
import gzip
import json
import math
import os
import sqlite3
import sys
import time

# zoom: (lowest rank, grid cells per side, places per cell, places per tile)
ZOOMS = {12: (42, 8, 2, 90), 13: (30, 8, 4, 180), 14: (14, 8, 10, 420)}
EXTENT = 4096
GROUP_SKIP = {"parking", "road", "roundabout", "bridge", "tunnel", "city", "neighbourhood", "region", "military"}
SHORT_NAME = 3


def varint(v):
    out = bytearray()
    while v >= 0x80:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)
    return bytes(out)


def field_bytes(num, data):
    return varint((num << 3) | 2) + varint(len(data)) + data


def field_varint(num, v):
    return varint(num << 3) + varint(v)


def zigzag(v):
    return (v << 1) ^ (v >> 31)


class Layer:
    """One MVT layer of point features. Keys and values are shared within the layer, as the format wants."""

    def __init__(self, name):
        self.name = name
        self.keys = {}
        self.values = {}
        self.features = []

    def _key(self, k):
        i = self.keys.get(k)
        if i is None:
            i = len(self.keys)
            self.keys[k] = i
        return i

    def _value(self, v):
        key = (type(v).__name__, v)
        i = self.values.get(key)
        if i is None:
            i = len(self.values)
            self.values[key] = i
        return i

    def add_point(self, x, y, props):
        tags = bytearray()
        for k, v in props.items():
            tags += varint(self._key(k)) + varint(self._value(v))
        geom = varint(9) + varint(zigzag(x)) + varint(zigzag(y))
        f = field_bytes(2, bytes(tags)) + field_varint(3, 1) + field_bytes(4, geom)
        self.features.append(f)

    def encode(self):
        out = bytearray()
        out += field_varint(15, 2)
        out += field_bytes(1, self.name.encode("utf-8"))
        for f in self.features:
            out += field_bytes(2, f)
        for k in self.keys:
            out += field_bytes(3, k.encode("utf-8"))
        for (typ, v) in self.values:
            if typ == "str":
                val = field_bytes(1, v.encode("utf-8"))
            else:
                val = field_varint(5, v)  # uint_value: every number here is a small non-negative integer
            out += field_bytes(4, val)
        out += field_varint(5, EXTENT)
        return bytes(out)


def worth_a_label(name):
    """More than SHORT_NAME letters of any script; digits and marks do not count."""
    return sum(c.isalpha() for c in name) > SHORT_NAME


def tile_of(lat, lon, z):
    """Tile x, y and the point's position inside it in tile units."""
    n = 1 << z
    fx = (lon + 180.0) / 360.0 * n
    s = math.sin(math.radians(lat))
    fy = (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n
    x = int(fx)
    y = int(fy)
    return x, y, int((fx - x) * EXTENT), int((fy - y) * EXTENT)


def build(db_path, out_path):
    src = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    skip = {i for i, k in src.execute("SELECT id,key FROM cat") if k in GROUP_SKIP}
    kinds = dict(src.execute("SELECT id,icon FROM cat"))
    t0 = time.time()
    rows = []
    for r in src.execute("SELECT id,name,alt,cat,lat,lon,rank FROM p WHERE kind=6 AND rank>=? AND src&4=0 ORDER BY rank DESC, id", (min(v[0] for v in ZOOMS.values()),)):
        if r[3] in skip:
            continue
        rows.append(r)
    print(f"{len(rows)} places can reach a tile ({time.time() - t0:.1f} s)", flush=True)
    if os.path.exists(out_path):
        os.remove(out_path)
    db = sqlite3.connect(out_path)
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    db.execute("CREATE TABLE metadata(name TEXT, value TEXT)")
    db.execute("CREATE TABLE tiles(zoom_level INT, tile_column INT, tile_row INT, tile_data BLOB)")
    report = {}
    for z, (min_rank, grid, per_cell, per_tile) in ZOOMS.items():
        t1 = time.time()
        tiles = {}
        cell_size = EXTENT / grid
        total = kept = 0
        for r in rows:  # best rank first, so that what a full cell or a full tile refuses is what matters least
            if r[6] < min_rank:
                break
            total += 1
            x, y, px, py = tile_of(r[4] / 1e6, r[5] / 1e6, z)
            t = tiles.get((x, y))
            if t is None:
                t = tiles[(x, y)] = {"cells": {}, "items": []}
            if len(t["items"]) >= per_tile:
                continue
            c = (int(px / cell_size), int(py / cell_size))
            n = t["cells"].get(c, 0)
            if n >= per_cell:
                continue
            t["cells"][c] = n + 1
            t["items"].append((px, py, r))
            kept += 1
        size = 0
        biggest = 0
        no_label = 0
        for (x, y), t in tiles.items():
            layer = Layer("places")
            # feature order: within a layer, MapLibre places symbols first to last when no sort key says otherwise
            prev_x = 0
            for px, py, r in t["items"]:
                props = {}
                if worth_a_label(r[1]):
                    props["name"] = r[1]
                else:
                    no_label += 1
                if r[2] and any("؀" <= c <= "ۿ" for c in r[1]) and not any("؀" <= c <= "ۿ" for c in r[2]) and worth_a_label(r[2]):
                    props["name:en"] = r[2]
                props["kind"] = kinds.get(r[3], 0)
                props["cat"] = r[3]
                props["rank"] = r[6]
                props["id"] = r[0]
                layer.add_point(px, py, props)
            data = gzip.compress(field_bytes(3, layer.encode()), 9, mtime=0)
            db.execute("INSERT INTO tiles VALUES(?,?,?,?)", (z, x, (1 << z) - 1 - y, data))
            size += len(data)
            biggest = max(biggest, len(data))
        report[str(z)] = {"tiles": len(tiles), "places": total, "kept": kept, "dropped": total - kept, "dots_with_no_word": no_label, "bytes": size, "largest_tile_bytes": biggest, "seconds": round(time.time() - t1, 1)}
        print(f"z{z}: {report[str(z)]}", flush=True)
    db.execute("CREATE UNIQUE INDEX tile_index ON tiles(zoom_level, tile_column, tile_row)")
    built = src.execute("SELECT v FROM meta WHERE k='built'").fetchone()[0]
    attribution = src.execute("SELECT v FROM meta WHERE k='attribution'").fetchone()[0]
    fields = {"name": "String", "name:en": "String", "kind": "Number", "cat": "Number", "rank": "Number", "id": "Number"}
    meta = {
        "name": "AMRI places", "format": "pbf", "type": "overlay", "version": "1", "description": "Points of interest of Saudi Arabia", "minzoom": "12", "maxzoom": "14",
        "bounds": "34.4,16.3,55.8,32.3", "center": "50.2,26.3,14", "attribution": attribution, "built": built,
        "json": json.dumps({"vector_layers": [{"id": "places", "minzoom": 12, "maxzoom": 14, "fields": fields}]}),
    }
    db.executemany("INSERT INTO metadata VALUES(?,?)", list(meta.items()))
    db.commit()
    db.execute("VACUUM")
    db.close()
    src.close()
    report["file_bytes"] = os.path.getsize(out_path)
    report["seconds"] = round(time.time() - t0, 1)
    print(json.dumps(report), flush=True)
    return report


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    build(a.db, a.out)


if __name__ == "__main__":
    main()
