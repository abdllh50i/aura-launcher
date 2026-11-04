#!/usr/bin/env python
"""Cuts what the guidance tests need out of the reference answers and places.db, into app/src/test/resources/guide.   python -I guide_fixtures.py <mapbuild out> <resources dir>

Each trip file is one real answer of Valhalla (out/ref-routes, the request in ../trips.py) with what guidance never reads left out, so all 30 stay under 2 MB:
the alternates keep only their steps' times, a long step only its first crossing, its last, and those where the road's classes change, and the line stays whole.
Beside it, the rows a RouteWorld (nav/guide/RouteWorld.kt) would answer for that route: spots and gates by the line, POIs by its turns, fuel, mosques and rest areas along it,
parking by its end, zones it touches. edges: trace_attributes over this very line, only where out/route-tests/edges was made from the same shape (6 of the 30).
raw/ holds a few answers byte for byte, so the parser also meets them untouched. rings.json: OSM's own count of roundabout exits (tests/check_rings.py), for the exit-number rule.
"""
import json
import math
import os
import shutil
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
import route_trips  # noqa: E402
import trips  # noqa: E402

LANDMARK_RADIUS_M = 70  # by a told step: more than Landmarks' 35 m, so the rule can be tuned without cutting again
LANDMARK_MIN_RANK = 30
ALONG_M = {"fuel": 400, "rest_area": 400, "mosque": 250}
PARKING_M = 500
LINE_M = 40  # spots and gates
EXTRAS = ["ferry", "gate-camp", "gate-air", "jed-taif"]  # beyond the 30: the ferry, guarded ends, the Haram


def hav(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[1], a[0], b[1], b[0]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371008 * math.asin(min(1.0, math.sqrt(h)))


def seg_dist(p, a, b):
    k = math.cos(math.radians(p[1]))
    ax, ay = (a[0] - p[0]) * k * 111320, (a[1] - p[1]) * 110540
    bx, by = (b[0] - p[0]) * k * 111320, (b[1] - p[1]) * 110540
    dx, dy = bx - ax, by - ay
    t = 0.0 if dx * dx + dy * dy < 1e-9 else max(0.0, min(1.0, -(ax * dx + ay * dy) / (dx * dx + dy * dy)))
    return math.hypot(ax + dx * t, ay + dy * t)


class Corridor:
    """The line in stretches of about 2 km, each with its box, so a row is measured only against the stretches near it."""

    def __init__(self, coords):
        self.coords = coords
        self.chunks = []
        i = 0
        while i < len(coords) - 1:
            j, run = i, 0.0
            while j < len(coords) - 1 and run < 2000:
                run += hav(coords[j], coords[j + 1])
                j += 1
            lons = [c[0] for c in coords[i:j + 1]]
            lats = [c[1] for c in coords[i:j + 1]]
            self.chunks.append((i, j, min(lats), max(lats), min(lons), max(lons)))
            i = j

    def boxes(self, m):
        out = []
        for i, j, s, n, w, e in self.chunks:
            dlat = m / 110540
            dlon = m / (111320 * math.cos(math.radians(s)))
            out.append((s - dlat, n + dlat, w - dlon, e + dlon, i, j))
        return out

    def near(self, p, m):
        for s, n, w, e, i, j in self.boxes(m):
            if s <= p[1] <= n and w <= p[0] <= e:
                for k in range(i, j):
                    if seg_dist(p, self.coords[k], self.coords[k + 1]) <= m:
                        return True
        return False


def trim_intersections(ints, whole):
    if whole or len(ints) <= 2:
        keep = list(range(len(ints)))
    else:
        keep = [0, len(ints) - 1]
        for k in range(1, len(ints)):
            if sorted(ints[k].get("classes") or []) != sorted(ints[k - 1].get("classes") or []):
                keep.append(k)
        keep = sorted(set(keep))
    out = []
    for k in keep:
        i = ints[k]
        o = {x: i[x] for x in ("bearings", "entry", "in", "out", "location", "geometry_index", "classes", "lanes", "toll_collection") if x in i}
        out.append(o)
    return out


def trim_step(s, first_route):
    m = s["maneuver"]
    o = {"distance": s["distance"], "duration": s["duration"], "mode": s.get("mode", "driving")}
    if not first_route:
        return o
    o["maneuver"] = {k: m[k] for k in ("type", "modifier", "exit", "location", "bearing_before", "bearing_after") if k in m}
    for k in ("name", "ref", "destinations", "exits", "rotary_name"):
        if s.get(k):
            o[k] = s[k]
    ring = m["type"] in ("roundabout", "rotary", "roundabout turn")
    o["intersections"] = trim_intersections(s.get("intersections") or [], ring)
    return o


def trim_answer(doc):
    out = {k: doc[k] for k in ("code", "warnings", "waypoints") if k in doc}
    out["waypoints"] = [{"distance": w.get("distance", 0), "location": w["location"]} for w in doc.get("waypoints", [])]
    routes = []
    for ri, r in enumerate(doc["routes"]):
        rr = {"distance": r["distance"], "duration": r["duration"]}
        if ri == 0:
            rr["geometry"] = r["geometry"]
        rr["legs"] = [{"distance": leg["distance"], "duration": leg["duration"], "admins": leg.get("admins", []), "steps": [trim_step(s, ri == 0) for s in leg["steps"]]} for leg in r["legs"]]
        routes.append(rr)
    out["routes"] = routes
    return out


def trim_edges(path, npoints):
    if not os.path.exists(path):
        return None
    e = json.load(open(path, encoding="utf-8"))
    edges = e.get("edges") or []
    if not edges or edges[-1].get("end_shape_index") != npoints - 1:
        return None  # made from another line: a shape index would point at the wrong place
    keys = ("begin_shape_index", "end_shape_index", "speed_limit", "road_class", "use", "bridge", "tunnel", "toll", "roundabout")
    return {"edges": [{k: x[k] for k in keys if k in x} for x in edges]}


def world(db, doc, label):
    r0 = doc["routes"][0]
    coords = r0["geometry"]["coordinates"]
    cor = Corridor(coords)
    steps = [s for leg in r0["legs"] for s in leg["steps"]]
    cats = dict(db.execute("select id,key from cat"))
    cat_id = {v: k for k, v in cats.items()}
    spots = {}
    gates = {}
    for s, n, w, e, _, _ in cor.boxes(LINE_M):
        for row in db.execute("select kind,lat,lon,dir,kmh,src,j from spot where lat between ? and ? and lon between ? and ?", (int(s * 1e6), int(n * 1e6), int(w * 1e6), int(e * 1e6))):
            if cor.near((row[2] / 1e6, row[1] / 1e6), LINE_M):
                spots[row] = 1
        for row in db.execute("select lat,lon,sub,road from gate where lat between ? and ? and lon between ? and ?", (int(s * 1e6), int(n * 1e6), int(w * 1e6), int(e * 1e6))):
            if cor.near((row[1] / 1e6, row[0] / 1e6), LINE_M):
                gates[row] = 1
    # a signal near a turn: the junction's other nodes are off the line but say the turn is at it
    for st in steps:
        lon, lat = st["maneuver"]["location"]
        dlat, dlon = 80 / 110540, 80 / (111320 * math.cos(math.radians(lat)))
        for row in db.execute("select kind,lat,lon,dir,kmh,src,j from spot where kind=2 and lat between ? and ? and lon between ? and ?", (int((lat - dlat) * 1e6), int((lat + dlat) * 1e6), int((lon - dlon) * 1e6), int((lon + dlon) * 1e6))):
            spots[row] = 1
    pois = {}
    columns = "id,name,alt,cat,lat,lon,rank,src"

    def add(row):
        pois[row[0]] = [row[0], row[1], row[2], cats.get(row[3], "other"), row[4], row[5], row[6], row[7]]

    for st in steps:
        lon, lat = st["maneuver"]["location"]
        dlat, dlon = LANDMARK_RADIUS_M / 110540, LANDMARK_RADIUS_M / (111320 * math.cos(math.radians(lat)))
        for row in db.execute(f"select {columns} from p where kind=6 and rank>=? and lat between ? and ? and lon between ? and ?", (LANDMARK_MIN_RANK, int((lat - dlat) * 1e6), int((lat + dlat) * 1e6), int((lon - dlon) * 1e6), int((lon + dlon) * 1e6))):
            if hav((row[5] / 1e6, row[4] / 1e6), (lon, lat)) <= LANDMARK_RADIUS_M:
                add(row)
    for key, m in ALONG_M.items():
        for s, n, w, e, _, _ in cor.boxes(m):
            for row in db.execute(f"select {columns} from p where kind=6 and cat=? and lat between ? and ? and lon between ? and ?", (cat_id[key], int(s * 1e6), int(n * 1e6), int(w * 1e6), int(e * 1e6))):
                if row[0] not in pois and cor.near((row[5] / 1e6, row[4] / 1e6), m):
                    add(row)
    lon, lat = coords[-1]
    dlat, dlon = PARKING_M / 110540, PARKING_M / (111320 * math.cos(math.radians(lat)))
    for row in db.execute(f"select {columns} from p where kind=6 and cat=? and lat between ? and ? and lon between ? and ?", (cat_id["parking"], int((lat - dlat) * 1e6), int((lat + dlat) * 1e6), int((lon - dlon) * 1e6), int((lon + dlon) * 1e6))):
        add(row)
    zones = []
    boxes = cor.boxes(50)
    for z in db.execute("select id,kind,name,en,s,n,w,e,ring from zone"):
        if any(z[4] <= n and z[5] >= s and z[6] <= e and z[7] >= w for s, n, w, e, _, _ in boxes):
            zones.append(list(z[:8]) + [json.loads(z[8])])
    # Latin-only names and the Arabic one the index holds for the same road nearby (kind 7: name is Arabic when there is one, alt the other script)
    names = {}
    for st in steps:
        raw = st.get("name") or ""
        parts = [p.strip() for p in raw.split(";") if p.strip()]
        if not parts or any(any("؀" <= c <= "ۿ" for c in p) for p in parts):
            continue
        lon, lat = st["maneuver"]["location"]
        for p in parts:
            if p in names:
                continue
            best = None
            for name, plat, plon in db.execute("select name,lat,lon from p where kind=7 and alt=? collate nocase", (p,)):
                if not any("؀" <= c <= "ۿ" for c in name):
                    continue
                d = hav((plon / 1e6, plat / 1e6), (lon, lat))
                if d < 15000 and (best is None or d < best[0]):
                    best = (d, name)
            if best:
                names[p] = best[1]
    return {"spots": [list(k) for k in spots], "gates": [list(k) for k in gates], "pois": list(pois.values()), "zones": zones, "names": names}


def main():
    out, res = sys.argv[1], sys.argv[2]
    os.makedirs(os.path.join(res, "raw"), exist_ok=True)
    db = sqlite3.connect("file:" + os.path.join(out, "places.db").replace("\\", "/") + "?mode=ro", uri=True)
    labels = {t[0]: (t[1], t[2], t[3]) for t in trips.LONG + trips.SHORT + trips.EDGES}
    for t in route_trips.TRIPS:
        labels.setdefault(t[0], (t[2], t[3], t[4]))
    names = [(k, os.path.join(out, "ref-routes", k + ".json"), True) for k, *_ in trips.LONG + trips.SHORT + trips.EDGES]
    names += [(k, os.path.join(out, "route-tests", "var-default", k + ".json"), False) for k in EXTRAS]
    total = 0
    index = []
    for tid, path, reference in names:
        doc = json.load(open(path, encoding="utf-8"))
        ans = trim_answer(doc)
        label, a, b = labels[tid]
        fx = {"id": tid, "label": label, "reference": reference, "from": a, "to": b, "answer": ans,
              "edges": trim_edges(os.path.join(out, "route-tests", "edges", tid + ".json"), len(doc["routes"][0]["geometry"]["coordinates"])),
              "world": world(db, doc, tid)}
        p = os.path.join(res, tid + ".json")
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            json.dump(fx, f, ensure_ascii=False, separators=(",", ":"))
        total += os.path.getsize(p)
        index.append(tid)
    with open(os.path.join(res, "index.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(index) + "\n")
    for k in ("j1", "j4", "k1"):
        shutil.copy(os.path.join(out, "ref-routes", k + ".json"), os.path.join(res, "raw", k + ".json"))
        total += os.path.getsize(os.path.join(res, "raw", k + ".json"))
    rg = json.load(open(os.path.join(out, "route-tests", "rings.json"), encoding="utf-8"))
    rows = [r for r in rg["rows"] if r["engine"] == "var-req"]
    with open(os.path.join(res, "rings.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(rows, f, separators=(",", ":"))
    total += os.path.getsize(os.path.join(res, "rings.json"))
    print("files", len(index), "bytes", total)


if __name__ == "__main__":
    main()
