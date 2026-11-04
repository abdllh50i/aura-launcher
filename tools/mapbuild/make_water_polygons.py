#!/usr/bin/env python3
"""Builds the sea as a polygon shapefile from OSM's own coastline, for Planetiler's --water_polygons_path.

    python3 -I make_water_polygons.py <coast.json> <outline.geojson> <natural_earth_vector.sqlite.zip> <out.shp.zip>

Runs inside the Valhalla image (it has shapely). The stand-in for water-polygons-split-3857.zip when that file cannot
be fetched: those polygons are made from the same coastline by OSMCoastline, world-wide; this makes them for one area.

  * Inside the working area (the outline plus 0.15 degrees) the sea comes from the OSM coastline: the coastline lines
    and the area's edge cut the area into faces, and each face is told apart by which side of a coastline way it lies
    on (OSM: land on the left, water on the right).
  * Outside it, where a low-zoom tile still reaches, the sea is Natural Earth's 1:10 m ocean. No road map is read there.
  * A guard: nothing is sea unless it is within 0.15 degrees of Natural Earth's ocean. A coastline cut by the extract's
    edge (the Sinai side of the Gulf of Aqaba) would otherwise turn land into sea.
"""
import json
import os
import random
import sqlite3
import struct
import sys
import tempfile
import time
import zipfile

import numpy as np
import shapely
import shapely.ops
from shapely.geometry import LineString, Point, box, shape
from shapely.geometry.polygon import orient
from shapely.ops import polygonize, unary_union

WGS84_PRJ = ('GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137,298.257223563]],'
             'PRIMEM["Greenwich",0],UNIT["Degree",0.0174532925199433]]')
WORK_BUFFER = 0.15
GUARD_BUFFER = 0.15
REACH = box(26.0, 5.0, 64.0, 40.0)  # all that z6-z13 tiles over the Kingdom can show
GRID = 1.0  # degrees


def say(*a):
    print(f"{time.strftime('%H:%M:%S')}", *a, flush=True)


def natural_earth_ocean(zip_path):
    tmp = tempfile.mkdtemp(prefix="ne")  # (TMPDIR: tmpfs in the container, the data disk is a slow HDD)
    with zipfile.ZipFile(zip_path) as z:
        name = next(n for n in z.namelist() if n.endswith(".sqlite"))
        path = z.extract(name, tmp)
    db = sqlite3.connect(path)
    cols = [r[1] for r in db.execute("PRAGMA table_info(ne_10m_ocean)")]
    geom_col = next(c for c in cols if c.lower() in ("geometry", "wkb_geometry", "geom"))
    geoms = [shapely.from_wkb(bytes(r[0])) for r in db.execute(f"SELECT {geom_col} FROM ne_10m_ocean")]
    db.close()
    os.remove(path)
    for root, dirs, files in os.walk(tmp, topdown=False):
        for d in dirs:
            os.rmdir(os.path.join(root, d))
    os.rmdir(tmp)
    return shapely.make_valid(unary_union(geoms))


def classify_faces(faces, ways):
    """Votes per face from points just right (sea) and left (land) of the coastline ways."""
    eps = 2e-5  # about 2 m
    pts_x, pts_y, side = [], [], []
    for coords in ways:
        a = np.array(coords)
        if len(a) < 2:
            continue
        step = max(1, (len(a) - 1) // 25)
        for i in range(0, len(a) - 1, step):
            (x1, y1), (x2, y2) = a[i], a[i + 1]
            dx, dy = x2 - x1, y2 - y1
            n = (dx * dx + dy * dy) ** 0.5
            if n == 0:
                continue
            mx, my = (x1 + x2) / 2, (y1 + y2) / 2
            # right of the direction = sea
            pts_x += [mx + dy / n * eps, mx - dy / n * eps]
            pts_y += [my - dx / n * eps, my + dx / n * eps]
            side += [1, -1]
    points = shapely.points(np.array(pts_x), np.array(pts_y))
    # (asked face by face: the query prepares each face, so one point costs a lookup and not a walk round a coast of
    # half a million points. Asked point by point the same answer took over ten minutes.)
    fi, pi = shapely.STRtree(points).query(np.array(faces, dtype=object), predicate="contains")
    votes = np.zeros(len(faces), dtype=np.int64)
    np.add.at(votes, fi, np.array(side)[pi])
    say(f"sample points {len(points):,}, inside a face {len(pi):,}")
    return votes


def write_shapefile(polys, out_zip):
    """Polygon shapefile: outer rings clockwise, holes counter-clockwise, as the format says."""
    records = []
    offset = 50  # in 16-bit words, after the header
    index = []
    xmin = ymin = 1e9
    xmax = ymax = -1e9
    for i, p in enumerate(polys):
        p = orient(p, sign=-1.0)
        rings = [p.exterior] + list(p.interiors)
        coords = [np.array(r.coords) for r in rings]
        nparts = len(rings)
        npoints = sum(len(c) for c in coords)
        all_c = np.concatenate(coords)
        bb = (all_c[:, 0].min(), all_c[:, 1].min(), all_c[:, 0].max(), all_c[:, 1].max())
        xmin, ymin = min(xmin, bb[0]), min(ymin, bb[1])
        xmax, ymax = max(xmax, bb[2]), max(ymax, bb[3])
        body = struct.pack("<i4dii", 5, *bb, nparts, npoints)
        start = 0
        for c in coords:
            body += struct.pack("<i", start)
            start += len(c)
        body += all_c.astype("<f8").tobytes()
        length = len(body) // 2
        records.append(struct.pack(">ii", i + 1, length) + body)
        index.append(struct.pack(">ii", offset, length))
        offset += 4 + length

    def header(file_words):
        return (struct.pack(">i", 9994) + b"\0" * 20 + struct.pack(">i", file_words) +
                struct.pack("<ii", 1000, 5) + struct.pack("<8d", xmin, ymin, xmax, ymax, 0, 0, 0, 0))

    shp = header(offset) + b"".join(records)
    shx = header(50 + 4 * len(index)) + b"".join(index)
    # dbf: one numeric field
    field = b"FID".ljust(11, b"\0") + b"N" + b"\0" * 4 + bytes([10, 0]) + b"\0" * 14
    head = bytes([3, 125, 1, 1]) + struct.pack("<IHH", len(polys), 32 + len(field) + 1, 11) + b"\0" * 20
    dbf = head + field + b"\r" + b"".join(b" " + str(i).rjust(10).encode() for i in range(len(polys))) + b"\x1a"
    base = os.path.basename(out_zip).replace(".shp.zip", "")
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as z:
        z.writestr(base + ".shp", shp)
        z.writestr(base + ".shx", shx)
        z.writestr(base + ".dbf", dbf)
        z.writestr(base + ".prj", WGS84_PRJ)
    return len(shp)


def main():
    coast_path, outline_path, ne_zip, out_zip = sys.argv[1:5]
    t0 = time.time()
    outline = shape(json.load(open(outline_path, encoding="utf-8"))["geometry"])
    work = outline.buffer(WORK_BUFFER, quad_segs=2).simplify(0.01)
    say("working area vertices", shapely.get_num_coordinates(work))

    say("Natural Earth ocean")
    ocean = natural_earth_ocean(ne_zip).intersection(REACH)
    say("  area", round(ocean.area, 2), "deg2")
    guard = ocean.buffer(GUARD_BUFFER, quad_segs=2)

    coast = json.load(open(coast_path, encoding="ascii"))["ways"]
    ways = [w["coords"] for w in coast if len(w["coords"]) >= 2]
    say("coastline ways", len(ways), "points", sum(len(w) for w in ways))
    # where a way ends with no continuation inside the working area, the extract has cut it
    starts = {tuple(w[0]) for w in ways}
    ends = {tuple(w[-1]) for w in ways}
    loose = [Point(p) for p in (starts ^ ends)]
    inside_loose = [p for p in loose if work.contains(p)]
    say("loose ends", len(loose), "inside the working area", len(inside_loose),
        [(round(p.x, 3), round(p.y, 3)) for p in inside_loose[:20]])

    lines = [LineString(w) for w in ways]
    tree = shapely.STRtree(lines)
    hit = tree.query(work, predicate="intersects")
    say("ways touching the working area", len(hit))
    near = [lines[i] for i in hit]
    near_coords = [ways[i] for i in hit]
    # The whole lines, not lines clipped to the area: the end of a clipped line is a computed point next to the area's edge,
    # not on it, so it would never be noded with it and the line would divide nothing.
    # A coast the extract's edge has cut (the Red Sea coast at both of its ends) stops inside the working area, and land
    # and sea would stay one face round its end. So each loose end is closed to the nearest point of the area's edge, a
    # little past it: never more than the buffer away, far from any town, and the guard below still limits the sea there.
    closers = []
    for p in inside_loose:
        edge = shapely.ops.nearest_points(p, work.boundary)[1]
        dx, dy = edge.x - p.x, edge.y - p.y
        n = (dx * dx + dy * dy) ** 0.5
        closers.append(LineString([(p.x, p.y), (edge.x + dx / n * 0.01, edge.y + dy / n * 0.01)]))
        say(f"closing the loose end {p.x:.3f},{p.y:.3f} to the edge at {edge.x:.3f},{edge.y:.3f}")
    noded = unary_union(near + closers + [work.boundary])
    say("noded")
    faces = list(polygonize(noded))
    faces = [f for f in faces if work.contains(f.representative_point())]
    say("faces", len(faces))
    votes = classify_faces(faces, near_coords)
    sea, land, unknown = [], [], []
    for f, v in zip(faces, votes):
        (sea if v > 0 else land if v < 0 else unknown).append(f)
    say(f"sea faces {len(sea)}, land faces {len(land)}, no vote {len(unknown)}")
    for f, v in sorted(zip(faces, votes), key=lambda fv: -fv[0].area)[:6]:
        rp = f.representative_point()
        say(f"  largest faces: {f.area:.3f} deg2 votes {v} at {rp.x:.2f},{rp.y:.2f}")
    for f in unknown:
        rp = f.representative_point()
        (sea if ocean.contains(rp) else land).append(f)
    inside_sea = unary_union(sea)
    if not inside_sea.is_valid:
        inside_sea = shapely.make_valid(inside_sea)
    say("sea inside the working area, deg2", round(inside_sea.area, 3), "working area", round(work.area, 3))
    inside_sea = inside_sea.intersection(guard)
    say("after the guard, deg2", round(inside_sea.area, 3))
    outside_sea = ocean.difference(work)
    total = unary_union([inside_sea, outside_sea])
    say("total sea, deg2", round(total.area, 3))

    checks = {
        "gulf between Khobar and Bahrain": (26.30, 50.32, True),
        "Khobar": (26.28, 50.20, False),
        "Tarout island": (26.57, 50.07, False),
        "Jubail town": (27.00, 49.65, False),
        "gulf off Jubail": (27.05, 49.95, True),
        "Red Sea off Jeddah": (21.55, 39.05, True),
        "Jeddah": (21.54, 39.20, False),
        "Farasan island": (16.72, 42.10, False),
        "Riyadh": (24.71, 46.67, False),
        "Bahrain": (26.08, 50.55, False),
        "Doha": (25.28, 51.50, False),
        "Red Sea far side": (20.0, 38.5, True),
        "Iran side of the Gulf": (28.0, 50.5, True),
    }
    bad = 0
    for label, (lat, lon, want) in checks.items():
        got = total.contains(Point(lon, lat))
        ok = got == want
        bad += not ok
        say(f"  {label}: sea={got} {'ok' if ok else 'WRONG'}")
    if bad:
        sys.exit(f"{bad} checks wrong")

    # cut into grid cells: small shapes, and Planetiler has less to do per tile
    west, south, east, north = total.bounds
    polys = []
    tree_parts = list(total.geoms) if hasattr(total, "geoms") else [total]
    tree = shapely.STRtree(tree_parts)
    x = np.floor(west)
    while x < east:
        y = np.floor(south)
        while y < north:
            cell = box(x, y, x + GRID, y + GRID)
            idx = tree.query(cell, predicate="intersects")
            if len(idx):
                part = unary_union([tree_parts[i].intersection(cell) for i in idx])
                for g in (part.geoms if hasattr(part, "geoms") else [part]):
                    if g.geom_type == "Polygon" and g.area > 1e-10:
                        polys.append(g)
            y += GRID
        x += GRID
    say("polygons written", len(polys), "points", sum(shapely.get_num_coordinates(p) for p in polys))
    n = write_shapefile(polys, out_zip)
    say(f"wrote {out_zip}: .shp {n / 1e6:.1f} MB, zip {os.path.getsize(out_zip) / 1e6:.1f} MB; {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
