#!/usr/bin/env python
"""A small fixture for the zones' rule on real roads: real lines with their real trace_attributes, cut to the stretch around the zone, and the zones they go through. From out/route-tests and places.db, read only.

  python -I guide_zone_fixture.py <mapbuild out folder> <app/src/test/resources/guide/zones-real.json>

guide_fixtures.py's own trips keep edges only when they match the fixture's line from ref-routes, which hid what real facts do to the rule on 24 of the 30: these match their own lines (53 of 54).
"""
import json
import os
import sqlite3
import sys

# id, and which of the line to keep in km from its start (None for the end)
TRIPS = {"j6": (0, 6), "j1": (0, None), "khafji": (-6, None), "gate-jana": (0, 6), "airport": (0, 8), "gate-air": (-6, None), "ithra": (0, None)}


def main():
    out, dest = sys.argv[1], sys.argv[2]
    db = sqlite3.connect(f"file:{os.path.join(out, 'places.db')}?mode=ro", uri=True)
    trips = []
    boxes = []
    for tid, (a, b) in TRIPS.items():
        route = json.load(open(os.path.join(out, "route-tests", "valhalla", tid + ".json"), encoding="utf-8"))["routes"][0]
        edges = json.load(open(os.path.join(out, "route-tests", "edges", tid + ".json"), encoding="utf-8"))["edges"]
        pts = route["geometry"]["coordinates"]
        # by the edges' own lengths, so a cut falls on an edge's end
        km = 0.0
        ends = []
        for e in edges:
            km += e.get("length", 0.0)
            ends.append(km)
        total = km
        lo = (total + a) if a < 0 else a
        hi = total if b is None else b
        keep = [e for e, d in zip(edges, ends) if d > lo and d - e.get("length", 0.0) < hi]
        first = keep[0]["begin_shape_index"]
        last = keep[-1]["end_shape_index"]
        line = pts[first:last + 1]
        rows = [[e["begin_shape_index"] - first, e["end_shape_index"] - first, e.get("road_class", ""), e.get("use", "")] for e in keep]
        trips.append({"id": tid, "line": [[round(p[1], 6), round(p[0], 6)] for p in line], "edges": rows})
        boxes.append((min(p[1] for p in line), max(p[1] for p in line), min(p[0] for p in line), max(p[0] for p in line)))
    zones = {}
    for s, n, w, e in boxes:
        for r in db.execute("SELECT id,kind,name,en,s,n,w,e,ring FROM zone WHERE s<=? AND n>=? AND w<=? AND e>=?", (n, s, e, w)):
            zones[r[0]] = list(r[:8]) + [json.loads(r[8])]
    with open(dest, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"zones": list(zones.values()), "trips": trips}, f, ensure_ascii=False, separators=(",", ":"))
    print(len(trips), "trips", sum(len(t["line"]) for t in trips), "points", len(zones), "zones", os.path.getsize(dest), "bytes")


if __name__ == "__main__":
    main()
