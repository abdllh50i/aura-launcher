#!/usr/bin/env python3
"""Checks base.mbtiles against what the app reads, and counts what it holds.

    python -I check_tiles.py <base.mbtiles> --style <MapStyle.kt> --poi <Poi.kt> [--ofm <dir of z_x_y.pbf>] --out <report.txt>

1. The container: tables, metadata, tile rows, blobs, gzip, largest tile, what TileServer would send.
2. Sizes per zoom and per layer over ALL tiles.
3. The style: every source-layer, field and class MapStyle.kt and Poi.kt use, against the tiles.
4. Arabic names on the z14 tiles of today's offline area, deduped exactly as the nav report did it, against the
   OpenFreeMap tiles kept from that report (no tile is fetched here).
"""
import argparse
import collections
import gzip
import math
import os
import random
import re
import sqlite3
import struct
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mvt  # noqa: E402


# What MapStyle.kt reads. layer -> (fields read, classes it filters or colours by)
# Checked below against the file itself, so a new source-layer in the style fails this script.
STYLE = {
    "landcover": ({"class", "subclass"}, {"sand", "wood", "forest", "grass", "farmland"}),
    "landuse": ({"class"}, {"residential", "suburb", "neighbourhood", "commercial", "retail", "industrial", "railway",
                            "garages", "quarry", "hospital", "school", "university", "college", "kindergarten",
                            "cemetery", "stadium", "pitch", "playground", "track"}),
    "park": ({"class", "name", "name:ar", "name:en", "name:latin"}, {"national_park", "nature_reserve", "protected_area", "protected_site", "forest"}),
    "waterway": ({"class"}, set()),
    "water": ({"intermittent"}, set()),
    "aeroway": ({"class"}, {"runway", "taxiway"}),
    "building": ({"render_height", "render_min_height"}, set()),
    "boundary": ({"admin_level", "maritime"}, set()),
    "transportation": ({"class", "brunnel", "ramp"}, {"motorway", "trunk", "primary", "secondary", "tertiary", "minor",
                                                      "service", "track", "rail", "transit"}),

    "water_name": ({"name", "name:ar", "name:en", "name:latin"}, set()),
    "housenumber": ({"housenumber"}, set()),
    "transportation_name": ({"class", "name", "name:ar", "name:en", "name:latin", "ref"},
                            {"primary", "secondary", "tertiary", "minor", "service", "motorway", "trunk"}),
    "aerodrome_label": ({"name", "name:ar", "name:en", "name:latin"}, set()),
    "place": ({"class", "name", "name:ar", "name:en", "name:latin"},
              {"suburb", "quarter", "neighbourhood", "hamlet", "isolated_dwelling", "village", "town", "city", "country", "state"}),
}

# The offline area of today (nav report): z14 of these boxes. lon, lat, lon, lat
BOXES = [(49.40, 26.85, 49.82, 27.25), (49.88, 26.10, 50.30, 26.52), (49.72, 26.40, 49.90, 26.53)]
# "His box" for counts over the whole of it
OWNER_BOX = (49.40, 26.10, 50.30, 27.25)


def tx(lon, z):
    return math.floor((lon + 180.0) / 360.0 * (1 << z))


def ty(lat, z):
    r = math.radians(lat)
    return math.floor((1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * (1 << z))


def tile_lonlat(z, x, y, px, py, extent):
    fx = x + px / extent
    fy = y + py / extent
    lon = fx / (1 << z) * 360 - 180
    n = math.pi - 2 * math.pi * fy / (1 << z)
    return math.degrees(math.atan(math.sinh(n))), lon


def box_tiles(boxes, z):
    out = set()
    for w, s, e, n in boxes:
        for x in range(tx(w, z), tx(e, z) + 1):
            for y in range(ty(n, z), ty(s, z) + 1):
                out.add((z, x, y))
    return sorted(out)


class Mb:
    def __init__(self, path):
        self.db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)

    def tile(self, z, x, y):
        row = self.db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                              (z, x, (1 << z) - 1 - y)).fetchone()
        return row[0] if row else None


class Report:
    def __init__(self, path):
        self.f = open(path, "w", encoding="utf-8", newline="\n")

    def __call__(self, *a):
        s = " ".join(str(v) for v in a)
        self.f.write(s + "\n")
        self.f.flush()
        print(s.encode("ascii", "replace").decode(), flush=True)


# Layers the style reads that are not in this file: places.mbtiles, and the poi layer of OpenFreeMap's tiles (strip_tiles.py took it out of the base).
ELSEWHERE = {"places": {"name", "name:en", "kind", "cat", "rank", "id"}, "poi": {"name", "name:ar", "name:en", "name:latin", "rank", "class"}}


def check_style_file(style_path, poi_path, say):
    src = open(style_path, encoding="utf-8").read()
    used = set(re.findall(r'"source-layer":"([a-z_]+)"', src))
    used |= set(re.findall(r'fill\("[a-z-]+", "([a-z_]+)"', src))
    used.discard("$layer")
    fixed = {u for u in used if not u.startswith("$")}
    say("source-layers named in MapStyle.kt:", sorted(fixed))
    unknown = fixed - set(STYLE) - set(ELSEWHERE)
    if unknown:
        sys.exit(f"MapStyle.kt reads layers this script does not know: {unknown}")
    gets = set(re.findall(r'\["get","([^"]+)"\]', src))
    say("fields read with [\"get\",...] in MapStyle.kt:", sorted(gets))
    known = set().union(*(v[0] for v in STYLE.values()), *ELSEWHERE.values())
    # label is a field of the app's own extras source, minz of its seas source
    unknown = gets - known - {"label", "minz"}
    if unknown:
        sys.exit(f"MapStyle.kt reads fields this script does not know: {unknown}")
    poi = open(poi_path, encoding="utf-8").read()
    classes = set()
    for m in re.finditer(r"Kind\(listOf\(([^)]*)\)", poi):
        classes |= set(re.findall(r'"([a-z_]+)"', m.group(1)))
    say("poi classes coloured by Poi.kt:", len(classes))
    return classes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mbtiles")
    ap.add_argument("--style", required=True)
    ap.add_argument("--poi", required=True)
    ap.add_argument("--ofm")
    ap.add_argument("--out", required=True)
    ap.add_argument("--sample", type=int, default=6000, help="random z13-14 tiles read feature by feature")
    a = ap.parse_args()
    say = Report(a.out)
    t0 = time.time()

    poi_classes = check_style_file(a.style, a.poi, say)
    mb = Mb(a.mbtiles)
    db = mb.db

    say("\n== 1. container")
    say("file bytes:", os.path.getsize(a.mbtiles))
    for typ, name, sql in db.execute("SELECT type, name, sql FROM sqlite_master ORDER BY type, name"):
        say(f"  {typ} {name}: {' '.join((sql or '').split())}")
    say("  PRAGMA page_size", db.execute("PRAGMA page_size").fetchone()[0], "page_count", db.execute("PRAGMA page_count").fetchone()[0],
        "journal_mode", db.execute("PRAGMA journal_mode").fetchone()[0], "schema_version", db.execute("PRAGMA schema_version").fetchone()[0])
    header = open(a.mbtiles, "rb").read(100)
    say("  file header: write version", header[18], "read version", header[19], "schema format", struct.unpack(">I", header[44:48])[0],
        "sqlite that wrote it", struct.unpack(">I", header[96:100])[0])
    meta = dict(db.execute("SELECT name, value FROM metadata"))
    for k, v in sorted(meta.items()):
        say(f"  metadata {k}: {v[:300] if k == 'json' else v}")
    has_compact = db.execute("SELECT 1 FROM sqlite_master WHERE name='tiles_data'").fetchone() is not None
    say("  compact (tiles_shallow + tiles_data):", has_compact)

    say("\n== 2. tiles and sizes")
    per_zoom = collections.OrderedDict()
    for z, n, b in db.execute("SELECT zoom_level, COUNT(*), SUM(LENGTH(tile_data)) FROM tiles GROUP BY zoom_level ORDER BY zoom_level"):
        per_zoom[z] = (n, b)
    total_n = sum(v[0] for v in per_zoom.values())
    total_b = sum(v[1] for v in per_zoom.values())
    if has_compact:
        blobs, blob_bytes = db.execute("SELECT COUNT(*), SUM(LENGTH(tile_data)) FROM tiles_data").fetchone()
    else:
        blobs, blob_bytes = total_n, total_b
    say(f"rows {total_n:,}; bytes of all rows {total_b:,}; distinct blobs {blobs:,}, {blob_bytes:,} bytes")
    say("zoom: tiles, MB (gzip, by row)")
    for z, (n, b) in per_zoom.items():
        say(f"  z{z}: {n:,}  {b / 1e6:.2f} MB")

    layer_raw = collections.Counter()
    layer_z = collections.defaultdict(collections.Counter)
    biggest = (0, None)
    biggest_gz = (0, None)
    not_gzip = 0
    empty = 0
    raw_total = 0
    t1 = time.time()
    cur = db.execute("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles")
    for z, x, y, data in cur:
        if len(data) == 0:
            empty += 1
            continue
        if data[:2] != b"\x1f\x8b":
            not_gzip += 1
        raw = mvt.ungzip(data)
        raw_total += len(raw)
        if len(data) > biggest_gz[0]:
            biggest_gz = (len(data), (z, x, (1 << z) - 1 - y))
        if len(raw) > biggest[0]:
            biggest = (len(raw), (z, x, (1 << z) - 1 - y))
        for name, size in mvt.layer_sizes(raw):
            layer_raw[name] += size
            layer_z[name][z] += size
    say(f"read all tiles in {time.time() - t1:.0f} s. uncompressed total {raw_total / 1e6:.0f} MB; empty blobs {empty}; not gzip {not_gzip}")
    say(f"largest tile: {biggest_gz[0] / 1024:.0f} KB gzip at {biggest_gz[1]}, {biggest[0] / 1024:.0f} KB uncompressed at {biggest[1]}")
    say("layer, uncompressed MB over all rows, share")
    for name, b in layer_raw.most_common():
        say(f"  {name:22} {b / 1e6:9.1f} MB  {100 * b / raw_total:5.1f} %   z14 only {layer_z[name][14] / 1e6:8.1f} MB")

    say("\n== 3. the style against the tiles (keys and classes from: all tiles z0-12, the owner's box z14, a random sample z13-14)")
    wanted = {}
    for z, x, y, d in db.execute("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles WHERE zoom_level <= 12"):
        wanted[(z, x, (1 << z) - 1 - y)] = d
    keys_seen = collections.defaultdict(collections.Counter)
    classes_seen = collections.defaultdict(collections.Counter)
    feats_seen = collections.Counter()

    def read_tile(data):
        raw = mvt.ungzip(data)
        for name, _, keys, vals, feats, _ in mvt.layers(raw):
            feats_seen[name] += len(feats)
            for fb in feats:
                tags, _, _ = mvt.feature(fb, keys, vals)
                for k in tags:
                    keys_seen[name][k] += 1
                if "class" in tags:
                    classes_seen[name][tags["class"]] += 1

    for d in wanted.values():
        read_tile(d)
    say(f"  z0-12: {len(wanted):,} tiles; {time.time() - t0:.0f} s since start")
    del wanted
    for k in box_tiles([OWNER_BOX], 14):
        d = mb.tile(*k)
        if d:
            read_tile(d)
    say(f"  owner's box z14 read; {time.time() - t0:.0f} s since start")
    random.seed(7)
    rows = db.execute("SELECT zoom_level, tile_column, tile_row FROM tiles WHERE zoom_level >= 13").fetchall()
    for z, x, y in random.sample(rows, min(a.sample, len(rows))):
        d = db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", (z, x, y)).fetchone()[0]
        read_tile(d)
    say(f"  sample z13-14 read; {time.time() - t0:.0f} s since start")

    problems = []
    for layer, (flds, classes) in sorted(STYLE.items()):
        present = feats_seen[layer]
        miss_f = sorted(f for f in flds if keys_seen[layer][f] == 0)
        miss_c = sorted(c for c in (classes or ()) if classes_seen[layer][c] == 0)
        say(f"  {layer}: {present:,} features read; fields missing: {miss_f or 'none'}; classes never seen: {miss_c or 'none'}")
        say(f"     keys: {[(k, v) for k, v in keys_seen[layer].most_common(14)]}")
        if classes:
            say(f"     classes in tiles: {classes_seen[layer].most_common(30)}")
        if present == 0:
            problems.append(f"source-layer {layer} is absent")
        for f in miss_f:
            problems.append(f"{layer}.{f} absent")
        for c in miss_c:
            problems.append(f"{layer} class {c} never seen")
    say("  layers in the tiles that the style does not read:", sorted(set(feats_seen) - set(STYLE)))
    if feats_seen["poi"]:
        problems.append("the poi layer is still in the base: places would show twice (strip_tiles.py --drop-layers poi)")
    say("  PROBLEMS:", problems if problems else "none")

    say("\n== 4. Arabic names, today's offline area, z14, deduped like the nav report")
    area = box_tiles(BOXES, 14)
    say("tiles in the area:", len(area))

    def count(get_tile, label):
        poi, streets, places = {}, {}, {}
        raw_counts = collections.Counter()
        for k in area:
            data = get_tile(*k)
            if not data:
                continue
            z, x, y = k
            for name, extent, keys, vals, feats, _ in mvt.layers(mvt.ungzip(data)):
                if name not in ("poi", "transportation_name", "place"):
                    continue
                for fb in feats:
                    tags, _, geom = mvt.feature(fb, keys, vals)
                    raw_counts[name] += 1
                    if tags.get("name"):
                        raw_counts[name + " named"] += 1
                        if tags.get("name:ar") or mvt.has_arabic(tags.get("name")):
                            raw_counts[name + " with Arabic"] += 1
                    if name == "poi":
                        pt = mvt.first_point(geom)
                        la, lo = tile_lonlat(z, x, y, pt[0], pt[1], extent)
                        poi[(tags.get("name", ""), round(la, 3), round(lo, 3))] = tags
                    elif name == "transportation_name":
                        streets[(tags.get("name", ""), tags.get("class", ""))] = tags
                    else:
                        places[(tags.get("name", ""), tags.get("class", ""))] = tags
        named = [t for t in poi.values() if t.get("name")]
        out = {
            "POI named": len(named),
            "POI name:ar": sum(1 for t in named if t.get("name:ar")),
            "POI name itself Arabic": sum(1 for t in named if mvt.has_arabic(t.get("name"))),
            "POI either": sum(1 for t in named if t.get("name:ar") or mvt.has_arabic(t.get("name"))),
            "street names": len([1 for k in streets if k[0]]),
            "street name:ar": sum(1 for k, t in streets.items() if k[0] and t.get("name:ar")),
            "street either": sum(1 for k, t in streets.items() if k[0] and (t.get("name:ar") or mvt.has_arabic(t.get("name")))),
            "places": len(places),
            "places either": sum(1 for t in places.values() if t.get("name:ar") or mvt.has_arabic(t.get("name"))),
        }
        out.update({k: raw_counts[k] for k in sorted(raw_counts)})
        say(f"  {label}: {out}")
        return out, streets

    ours, our_streets = count(mb.tile, "ours")
    if a.ofm:
        def ofm(z, x, y):
            p = os.path.join(a.ofm, f"{z}_{x}_{y}.pbf")
            if not os.path.exists(p) or os.path.getsize(p) == 0:
                return None
            return open(p, "rb").read()
        count(ofm, "OpenFreeMap planet 20261101 (kept from the nav report)")
    say("samples of our street names (name | name:ar | name:en | class):")
    random.seed(3)
    items = [t for k, t in our_streets.items() if k[0]]
    for t in random.sample(items, min(25, len(items))):
        say("   ", t.get("name"), "|", t.get("name:ar", ""), "|", t.get("name:en", ""), "|", t.get("class"))

    say("\n== 5. the same counts over the owner's whole box, ours only (raw features, z14)")
    area = box_tiles([OWNER_BOX], 14)
    say("tiles:", len(area))
    count(mb.tile, "ours, box 26.10-27.25 N, 49.40-50.30 E")

    # what the app's server would do with one tile: it passes the blob on, and marks gzip by its first two bytes
    say("\n== 6. the app's TileServer path, simulated")
    z, x, y = biggest_gz[1]
    d = mb.tile(z, x, y)
    say(f"tile {z}/{x}/{y}: {len(d)} bytes, starts {d[:2].hex()}, Content-Encoding gzip by TileServer.gzip(): {d[:2] == bytes.fromhex('1f8b')}; first layer "
        f"{next(mvt.layer_sizes(mvt.ungzip(d)))[0]}")
    t2 = time.perf_counter()
    n = 0
    random.seed(11)
    for z, x, y in random.sample(rows, min(2000, len(rows))):
        if mb.tile(z, x, (1 << z) - 1 - y):
            n += 1
    say(f"{n} random lookups through the tiles view, read-only, on this PC: {(time.perf_counter() - t2) * 1000 / max(n, 1):.3f} ms each")
    say(f"\nfinished in {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
