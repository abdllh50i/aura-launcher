#!/usr/bin/env python
"""Asks valhalla_service and the public OSRM for each trip of route_trips.py and keeps the raw answers.

    python -I run_route_trips.py <valhalla url> <out dir> [--no-osrm] [id ...]

<out>/valhalla/<id>.json   Valhalla's answer to the app's request (trips.request), all routes
<out>/osrm/<id>.json       OSRM's answer to the app's own request (Router.kt): overview=full, geojson, steps. Never more than one request per 1.1 s.
                           An answer already on disk is not asked again.
<out>/edges/<id>.json      trace_attributes edge_walk over Valhalla's first route: road class, speed, limit, way id per edge
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import route_trips  # noqa: E402
import trips  # noqa: E402

EDGE_ATTRS = ["edge.names", "edge.speed_limit", "edge.road_class", "edge.lane_count", "edge.tunnel", "edge.bridge", "edge.roundabout", "edge.toll", "edge.way_id", "edge.length",
              "edge.speed", "edge.use", "edge.begin_shape_index", "edge.end_shape_index", "edge.begin_heading", "edge.end_heading", "node.type", "node.elapsed_time", "node.transition_time"]
_last_osrm = [0.0]


def post(url, path, body, timeout=300):
    req = urllib.request.Request(url + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(), (time.perf_counter() - t0) * 1000
    except urllib.error.HTTPError as e:
        return e.code, e.read(), (time.perf_counter() - t0) * 1000


def osrm(a, b, extra=""):
    wait = 1.1 - (time.time() - _last_osrm[0])
    if wait > 0:
        time.sleep(wait)
    url = f"https://router.project-osrm.org/route/v1/driving/{a[1]},{a[0]};{b[1]},{b[0]}?overview=full&geometries=geojson&steps=true{extra}"
    req = urllib.request.Request(url, headers={"User-Agent": "AMRI map build comparison (one car, about 150 requests, at most one a second)"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    finally:
        _last_osrm[0] = time.time()


def main():
    url, out = sys.argv[1], sys.argv[2]
    args = sys.argv[3:]
    use_osrm = "--no-osrm" not in args
    only = {x for x in args if not x.startswith("--")}
    for sub in ("valhalla", "osrm", "edges"):
        os.makedirs(os.path.join(out, sub), exist_ok=True)
    rows = []
    for tid, group, label, a, b in route_trips.TRIPS:
        if only and tid not in only:
            continue
        code, raw, ms = post(url, "/route", trips.request(a, b))
        with open(os.path.join(out, "valhalla", tid + ".json"), "wb") as f:
            f.write(raw)
        doc = json.loads(raw)
        line = f"{tid:16} valhalla {code} {ms:7.0f} ms"
        row = {"id": tid, "http": code, "ms": round(ms, 1), "bytes": len(raw)}
        if doc.get("code") == "Ok":
            r0 = doc["routes"][0]
            line += f" {r0['distance'] / 1000:8.1f} km {r0['duration'] / 60:7.1f} min"
            shape = [{"lat": c[1], "lon": c[0]} for c in r0["geometry"]["coordinates"]]
            body = {"shape": shape, "costing": "auto", "shape_match": "edge_walk", "filters": {"attributes": EDGE_ATTRS, "action": "include"}}
            c2, raw2, ms2 = post(url, "/trace_attributes", body)
            row["edges_http"] = c2
            with open(os.path.join(out, "edges", tid + ".json"), "wb") as f:
                f.write(raw2)
            line += f" edges {c2}"
        else:
            line += " " + json.dumps(doc)[:160]
        path = os.path.join(out, "osrm", tid + ".json")
        if use_osrm and not os.path.exists(path):
            try:
                c3, raw3 = osrm(a, b)
                with open(path, "wb") as f:
                    f.write(raw3)
                line += f" | osrm {c3}"
            except Exception as e:
                line += f" | osrm failed {e}"
        rows.append(row)
        print(line.encode("ascii", "replace").decode(), flush=True)
    with open(os.path.join(out, "run.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(rows, f, indent=0)


if __name__ == "__main__":
    main()
