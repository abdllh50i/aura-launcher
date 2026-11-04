#!/usr/bin/env python
"""Writes jobs.json for plate/plate_routes.mjs: one plate of the whole trip and one of where the engines differ.

    python -I make_plates.py <out dir of run_route_trips.py> <jobs.json> id[@lat,lon[,km]] ...

id@lat,lon draws the trip around that point (default half width 1 km) instead of the difference box. Valhalla is blue, its alternates pale blue, OSRM red dashes.
"""
import json
import os
import sys


def main():
    out, jobs_path = sys.argv[1], sys.argv[2]
    report = json.load(open(os.path.join(out, "report.json"), encoding="utf-8"))
    trips = {t["id"]: t for t in report["trips"]}
    jobs = []
    for spec in sys.argv[3:]:
        tid, _, at = spec.partition("@")
        t = trips[tid]
        v = json.load(open(os.path.join(out, "valhalla", tid + ".json"), encoding="utf-8"))
        o = json.load(open(os.path.join(out, "osrm", tid + ".json"), encoding="utf-8"))
        lines = []
        if v.get("code") == "Ok":
            for alt in v["routes"][1:]:
                lines.append({"coords": alt["geometry"]["coordinates"], "color": "#7fb2ff", "width": 3})
            lines.append({"coords": v["routes"][0]["geometry"]["coordinates"], "color": "#1a56db", "width": 6})
        if o.get("code") == "Ok":
            lines.append({"coords": o["routes"][0]["geometry"]["coordinates"], "color": "#e02424", "width": 3.5, "dash": [2, 1.5], "opacity": 1})
        a, b = t["from"], t["to"]
        points = [{"lon": a[1], "lat": a[0], "label": "A", "color": "#0e9f6e"}, {"lon": b[1], "lat": b[0], "label": "B", "color": "#111111"}]
        for w, name in ((v.get("waypoints") or [None, None])[0], "snap A"), ((v.get("waypoints") or [None, None])[-1], "snap B"):
            if w:
                points.append({"lon": w["location"][0], "lat": w["location"][1], "label": "", "color": "#1a56db"})
        title = (f"{tid}: {t['label']}\nValhalla (blue) {t.get('v_km')} km {t.get('v_min')} min   OSRM (red dashes) {t.get('o_km')} km {t.get('o_min')} min")
        if at:
            parts = at.split(",")
            lat, lon = float(parts[0]), float(parts[1])
            half = float(parts[2]) if len(parts) > 2 else 1.0
            d = half / 100.0
            jobs.append({"name": f"{tid}-at", "title": title, "lines": lines, "points": points, "box": [lon - d, lat - d * 0.9, lon + d, lat + d * 0.9]})
            continue
        lons = [c[0] for ln in lines for c in ln["coords"]]
        lats = [c[1] for ln in lines for c in ln["coords"]]
        jobs.append({"name": tid, "title": title, "lines": lines, "points": points, "box": [min(lons), min(lats), max(lons), max(lats)]})
        box = t.get("diff_box")
        if box and (box[2] - box[0] < 0.7 * (max(lons) - min(lons)) or box[3] - box[1] < 0.7 * (max(lats) - min(lats))):
            jobs.append({"name": f"{tid}-diff", "title": title, "lines": lines, "points": points, "box": box})
    with open(jobs_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(jobs, f)
    print(len(jobs), "plates")


if __name__ == "__main__":
    main()
