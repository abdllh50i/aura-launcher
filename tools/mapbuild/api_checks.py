#!/usr/bin/env python
"""Small checks of the running valhalla_service that the Kotlin side depends on.   python -I api_checks.py <url> <ref-routes dir> <out.json>

  * what an error looks like in OSRM format (outside the pack; no road near; no route between two points);
  * top_speed against the arrival time (the plan's request says 140; OSRM's time is the yardstick);
  * a start with the car's heading;
  * trace_attributes with edge_walk over a real route: which attributes come back, how long it takes.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trips  # noqa: E402


def post(url, path, body):
    req = urllib.request.Request(url + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read()), round((time.perf_counter() - t0) * 1000, 1)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}"), round((time.perf_counter() - t0) * 1000, 1)


def main():
    url, ref, out = sys.argv[1:4]
    report = {}
    # errors
    dam = (26.4207, 50.0888)
    cases = {
        "start in Dubai, outside the pack": ((25.2048, 55.2708), dam),
        "start in the empty quarter, no road near": ((20.0, 51.0), dam),
        "start on the causeway's far side (Bahrain)": ((26.2235, 50.5876), dam),
    }
    errors = {}
    for label, (a, b) in cases.items():
        code, doc, ms = post(url, "/route", trips.request(a, b))
        errors[label] = {"http": code, "ms": ms, "answer": doc if "routes" not in doc else {"code": doc.get("code"), "km": round(doc["routes"][0]["distance"] / 1000, 1)}}
    report["errors"] = errors
    # top speed
    a, b = trips.LONG[0][2], trips.LONG[0][3]
    speeds = {}
    for trip in (trips.LONG[0], trips.LONG[2], trips.LONG[3]):
        tid, _, a, b = trip
        row = {}
        for ts in (140, 120, 110, 100):
            req = trips.request(a, b, alternates=0)
            req["costing_options"]["auto"]["top_speed"] = ts
            code, doc, ms = post(url, "/route", req)
            r = doc["routes"][0]
            row[ts] = {"km": round(r["distance"] / 1000, 1), "min": round(r["duration"] / 60, 1)}
        speeds[tid] = row
    report["top_speed"] = speeds
    # heading
    req = trips.request(trips.LONG[0][2], trips.LONG[0][3], alternates=0)
    req["locations"][0].update({"heading": 123, "heading_tolerance": 45})
    code, doc, ms = post(url, "/route", req)
    report["with heading"] = {"http": code, "ms": ms, "first step": doc["routes"][0]["legs"][0]["steps"][0]["maneuver"]["instruction"], "km": round(doc["routes"][0]["distance"] / 1000, 1)}
    # the road, edge by edge
    doc = json.load(open(os.path.join(ref, "jubail-dammam.json"), encoding="utf-8"))
    shape = [{"lat": c[1], "lon": c[0]} for c in doc["routes"][0]["geometry"]["coordinates"]]
    body = {"shape": shape, "costing": "auto", "shape_match": "edge_walk",
            "filters": {"attributes": ["edge.names", "edge.speed_limit", "edge.road_class", "edge.lane_count", "edge.tunnel", "edge.bridge", "edge.roundabout", "edge.toll",
                                       "edge.way_id", "edge.length", "edge.speed", "edge.use", "edge.begin_shape_index", "edge.end_shape_index"], "action": "include"}}
    code, attrs, ms = post(url, "/trace_attributes", body)
    edges = attrs.get("edges") or []
    limits = sum(e.get("length", 0) for e in edges if isinstance(e.get("speed_limit"), (int, float)) and e.get("speed_limit"))
    total = sum(e.get("length", 0) for e in edges)
    report["trace_attributes edge_walk"] = {"http": code, "ms": ms, "points in": len(shape), "edges": len(edges), "bytes": len(json.dumps(attrs)),
                                            "keys of one edge": sorted(edges[0].keys()) if edges else attrs, "sample": edges[len(edges) // 2] if edges else None,
                                            "share of length with a known speed limit": round(limits / total, 3) if total else None,
                                            "speed_limit values": sorted({str(e.get("speed_limit")) for e in edges})}
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    print(json.dumps(report, ensure_ascii=True)[:6000])


if __name__ == "__main__":
    main()
