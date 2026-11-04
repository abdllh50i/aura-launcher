#!/usr/bin/env python
"""Requests for what the app will ask beyond a plain route: the road edge by edge, map matching of a drive, a heading on the start, a trip across Saudi Arabia's
south, and the answers of the PC service to the same, for arm32_compare.py.

  python -I arm32_requests.py <ref-routes folder> <requests out> <reference out> <service url>
"""
import json
import os
import sys
import urllib.request

ref, out_req, out_ref, url = sys.argv[1:5]
os.makedirs(out_req, exist_ok=True)
os.makedirs(out_ref, exist_ok=True)
ATTR = ["edge.names", "edge.speed_limit", "edge.road_class", "edge.lane_count", "edge.tunnel", "edge.bridge", "edge.roundabout", "edge.toll", "edge.way_id", "edge.length",
        "edge.speed", "edge.use", "edge.begin_shape_index", "edge.end_shape_index"]


def shape(name, step=1, jitter=0.0):
    doc = json.load(open(os.path.join(ref, name + ".json"), encoding="utf8"))
    c = doc["routes"][0]["geometry"]["coordinates"][::step]
    return [{"lat": p[1] + (jitter if i % 2 else -jitter), "lon": p[0]} for i, p in enumerate(c)]


reqs = {
    "trace-edge-walk": ("/trace_attributes", {"shape": shape("jubail-dammam"), "costing": "auto", "shape_match": "edge_walk", "filters": {"attributes": ATTR, "action": "include"}}),
    "trace-map-snap": ("/trace_attributes", {"shape": shape("k3", 2, 0.00005), "costing": "auto", "shape_match": "map_snap", "filters": {"attributes": ATTR, "action": "include"}}),
    "heading": ("/route", {"locations": [{"lat": 26.4207, "lon": 50.0888, "heading": 123, "heading_tolerance": 45}, {"lat": 24.7136, "lon": 46.6753, "type": "break"}], "costing": "auto",
                           "costing_options": {"auto": {"top_speed": 140, "use_tolls": 0.5, "use_tracks": 0.0}}, "format": "osrm", "shape_format": "geojson", "alternates": 2, "units": "kilometers", "language": "en-US"}),
    "najran-tabuk": ("/route", {"locations": [{"lat": 17.4933, "lon": 44.1277, "type": "break"}, {"lat": 28.3838, "lon": 36.555, "type": "break"}], "costing": "auto",
                                "costing_options": {"auto": {"top_speed": 140, "use_tolls": 0.5, "use_tracks": 0.0}}, "format": "osrm", "shape_format": "geojson", "alternates": 2, "units": "kilometers", "language": "en-US"}),
}
for name, (path, body) in reqs.items():
    raw = json.dumps(body).encode()
    with open(os.path.join(out_req, name + ".json"), "wb") as f:
        f.write(raw)
    r = urllib.request.urlopen(urllib.request.Request(url + path, data=raw, headers={"Content-Type": "application/json"}), timeout=120).read()
    with open(os.path.join(out_ref, name + ".json"), "wb") as f:
        f.write(r)
    print(name, len(raw), "request bytes,", len(r), "answer bytes")
