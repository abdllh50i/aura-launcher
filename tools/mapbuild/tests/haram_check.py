#!/usr/bin/env python
"""Makkah's and Madinah's forbidden zones, as OSM draws them, against the kept routes.   <venv python> -I haram_check.py <ksa.osm.pbf> <out dir> <folders...>

Non-Muslims may not enter. No road tag says so, so no engine here knows; the polygons are in OSM (the base tiles' park layer has "حدود الحرم المكي"). This finds them, says how many km of each route lie inside,
and writes <out>/haram.json with the rings, so that the app can warn and a request can try exclude_polygons.
"""
import json
import os
import sys

import osmium

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import route_report as rr  # noqa: E402
import route_trips  # noqa: E402


class Areas(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.found = []

    def area(self, a):
        name = a.tags.get("name") or ""
        en = a.tags.get("name:en") or ""
        text = name + " " + en
        if not ("حدود الحرم" in text or "حرم مك" in text or "حرم المك" in text or "حرم المدني" in text or "Al Haram Al" in text or "Haram boundary" in text):
            return
        rings = []
        for outer in a.outer_rings():
            rings.append([(n.lon, n.lat) for n in outer])
        tags = {t.k: t.v for t in a.tags}
        self.found.append({"id": a.orig_id(), "way": a.from_way(), "name": name, "name_en": en, "tags": tags, "rings": rings})


def inside(p, ring):
    x, y = p[0], p[1]
    hit = False
    for i in range(len(ring) - 1):
        (x1, y1), (x2, y2) = ring[i], ring[i + 1]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            hit = not hit
    return hit


def main():
    pbf, out = sys.argv[1], sys.argv[2]
    folders = sys.argv[3:] or ["var-req", "osrm"]
    h = Areas()
    h.apply_file(pbf, locations=True, idx="flex_mem")
    rows = []
    for tid, group, label, a, b in route_trips.TRIPS:
        for folder in folders:
            doc = json.load(open(os.path.join(out, folder, tid + ".json"), encoding="utf-8"))
            if doc.get("code") != "Ok":
                continue
            pts = rr.resample(rr.line_of(doc["routes"][0]), 100.0)
            for f in h.found:
                for ring in f["rings"]:
                    bx = (min(c[0] for c in ring), min(c[1] for c in ring), max(c[0] for c in ring), max(c[1] for c in ring))
                    near = [p for p in pts if bx[0] <= p[0] <= bx[2] and bx[1] <= p[1] <= bx[3]]
                    n = sum(1 for p in near if inside(p, ring))
                    if n:
                        rows.append({"trip": tid, "engine": folder, "zone": f["name"], "km_inside": round(n * 0.1, 1), "route_km": round(doc["routes"][0]["distance"] / 1000, 1)})
    rep = {"zones": [{"id": f["id"], "way": f["way"], "name": f["name"], "name_en": f["name_en"], "tags": f["tags"], "rings": len(f["rings"]), "points": sum(len(r) for r in f["rings"])} for f in h.found], "routes_inside": rows}
    with open(os.path.join(out, "haram.json"), "w", encoding="utf-8", newline="\n") as fo:
        json.dump({**rep, "geometry": [{"name": f["name"], "rings": f["rings"]} for f in h.found]}, fo, ensure_ascii=False)
    with open(os.path.join(out, "haram-summary.json"), "w", encoding="utf-8", newline="\n") as fo:
        json.dump(rep, fo, ensure_ascii=False, indent=0)
    print(len(h.found), "zones;", len(rows), "route pieces inside")


if __name__ == "__main__":
    main()
