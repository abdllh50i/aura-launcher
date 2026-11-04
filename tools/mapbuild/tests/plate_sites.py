#!/usr/bin/env python
"""Plates of single sites: asks Valhalla and OSRM (one request a second) for a trip given on the command line and writes jobs for plate/plate_routes.mjs.

    python -I plate_sites.py <url> <jobs.json> name:lat,lon:lat,lon[:heading[:half_km[:json of costing]]] ...
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run_route_trips as run  # noqa: E402


def main():
    url, jobs_path = sys.argv[1], sys.argv[2]
    jobs = []
    for spec in sys.argv[3:]:
        parts = spec.split(":", 5)
        name = parts[0]
        a = tuple(map(float, parts[1].split(",")))
        b = tuple(map(float, parts[2].split(",")))
        heading = int(parts[3]) if len(parts) > 3 and parts[3] else None
        half = float(parts[4]) if len(parts) > 4 and parts[4] else None
        costing = json.loads(parts[5]) if len(parts) > 5 and parts[5] else {}
        la = {"lat": a[0], "lon": a[1], "type": "break"}
        if heading is not None:
            la.update({"heading": heading, "heading_tolerance": 45})
        body = {"locations": [la, {"lat": b[0], "lon": b[1], "type": "break"}], "costing": "auto", "costing_options": {"auto": dict({"top_speed": 160, "use_tolls": 0.5, "use_tracks": 0.0}, **costing)},
                "format": "osrm", "shape_format": "geojson", "units": "kilometers", "language": "en-US", "alternates": 0}
        code, raw, ms = run.post(url, "/route", body)
        v = json.loads(raw)
        lines = []
        title = name
        if v.get("code") == "Ok":
            r = v["routes"][0]
            lines.append({"coords": r["geometry"]["coordinates"], "color": "#1a56db", "width": 6})
            title += f"\nValhalla (blue) {r['distance'] / 1000:.2f} km {r['duration'] / 60:.1f} min"
            steps = [s for leg in r["legs"] for s in leg["steps"]]
            print(name, "V", [(s["maneuver"].get("type"), s["maneuver"].get("modifier"), round(s["distance"])) for s in steps])
        c3, raw3 = run.osrm(a, b, f"&bearings={heading},45;" if heading is not None else "")
        o = json.loads(raw3)
        if o.get("code") == "Ok":
            r = o["routes"][0]
            lines.append({"coords": r["geometry"]["coordinates"], "color": "#e02424", "width": 3.5, "dash": [2, 1.5], "opacity": 1})
            title += f"   OSRM (red dashes) {r['distance'] / 1000:.2f} km {r['duration'] / 60:.1f} min"
        points = [{"lon": a[1], "lat": a[0], "label": "A", "color": "#0e9f6e"}, {"lon": b[1], "lat": b[0], "label": "B", "color": "#111111"}]
        lons = [c[0] for ln in lines for c in ln["coords"]]
        lats = [c[1] for ln in lines for c in ln["coords"]]
        box = [min(lons), min(lats), max(lons), max(lats)]
        if half:
            d = half / 100.0
            mid = ((a[1] + b[1]) / 2, (a[0] + b[0]) / 2)
            box = [mid[0] - d, mid[1] - d * 0.9, mid[0] + d, mid[1] + d * 0.9]
        jobs.append({"name": name, "title": title, "lines": lines, "points": points, "box": box})
    with open(jobs_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(jobs, f)


if __name__ == "__main__":
    main()
