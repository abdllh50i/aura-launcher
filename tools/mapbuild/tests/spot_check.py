#!/usr/bin/env python
"""The tester's sanity check of table spot (cameras, signals, bumps), against the OSM file itself and the routes.

  venv\\Scripts\\python.exe -I spot_check.py --db out\\places.db --pbf src\\probe.osm.pbf --jsonl places\\osm-spot.jsonl --add places\\additions.json --routes out\\ref-routes --poly polygon\\ksa.poly --out places\\test\\spot-check.json

Reads the PBF with pyosmium, not with the build's own reader. The rectangle is the probe's: 49.4-50.4 E, 26.0-27.3 N.
"""
import argparse
import collections
import json
import math
import os
import sqlite3
import sys

import osmium

KINDS = {1: "camera", 2: "signal", 3: "bump"}
ROADS = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link", "road"}
BOX = (26.0, 27.3, 49.4, 50.4)
OWNER_BOX = (26.10, 27.25, 49.40, 50.30)


def bearing(la1, lo1, la2, lo2):
    p1, p2 = math.radians(la1), math.radians(la2)
    d = math.radians(lo2 - lo1)
    return (math.degrees(math.atan2(math.sin(d) * math.cos(p2), math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(d))) + 360) % 360


def adiff(a, b):
    return abs((a - b + 180) % 360 - 180)


class Read(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.segs = []  # lat1,lon1,lat2,lon2,oneway,highway,way id
        self.points = collections.Counter()
        self.pt_rows = {"camera": [], "signal": [], "bump": []}

    def node(self, n):
        h = n.tags.get("highway")
        k = None
        if h == "speed_camera":
            k = "camera"
        elif h == "traffic_signals":
            k = "signal"
        elif n.tags.get("traffic_calming") in ("bump", "hump", "table", "cushion", "rumble_strip", "yes", "choker", "chicane", "island", "dip", "mini_bumps"):
            k = "bump"
        if k and n.location.valid():
            self.points[k] += 1
            self.pt_rows[k].append((n.location.lat, n.location.lon))

    def way(self, w):
        h = w.tags.get("highway")
        if h not in ROADS:
            return
        one = w.tags.get("oneway", "")
        if not one and (h in ("motorway", "motorway_link") or w.tags.get("junction") == "roundabout"):
            one = "yes"
        pts = [(n.location.lat, n.location.lon) for n in w.nodes if n.location.valid()]
        for a, b in zip(pts, pts[1:]):
            self.segs.append((a[0], a[1], b[0], b[1], one, h, w.id))


def seg_dist_m(la, lo, s):
    k = math.cos(math.radians(la)) * 111320.0
    ax, ay = (s[1] - lo) * k, (s[0] - la) * 110540.0
    bx, by = (s[3] - lo) * k, (s[2] - la) * 110540.0
    dx, dy = bx - ax, by - ay
    t = 0.0 if dx == 0 and dy == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / (dx * dx + dy * dy)))
    return math.hypot(ax + t * dx, ay + t * dy)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--pbf", required=True)
    ap.add_argument("--jsonl", required=True)
    ap.add_argument("--add", required=True)
    ap.add_argument("--routes", required=True)
    ap.add_argument("--poly", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    rows = db.execute("SELECT kind,lat,lon,dir,kmh,src FROM spot").fetchall()
    rep = {"rows": len(rows)}
    rep["by_kind_src"] = {f"{KINDS[k]} src{s}": n for (k, s), n in collections.Counter((r[0], r[5]) for r in rows).items()}
    rep["with_dir"] = {KINDS[k]: sum(1 for r in rows if r[0] == k and r[3] is not None) for k in KINDS}
    rep["with_kmh"] = {KINDS[k]: sum(1 for r in rows if r[0] == k and r[4] is not None) for k in KINDS}
    rep["bad_dir"] = sum(1 for r in rows if r[3] is not None and not 0 <= r[3] < 360)
    rep["kmh_values"] = {KINDS[k]: collections.Counter(r[4] for r in rows if r[0] == k and r[4] is not None).most_common(20) for k in KINDS}
    rep["kmh_odd"] = [(KINDS[r[0]], r[1] / 1e6, r[2] / 1e6, r[4]) for r in rows if r[4] is not None and (r[4] < 10 or r[4] > 140 or r[4] % 5)][:40]
    rep["dir_by_45"] = collections.Counter(int((r[3] + 22.5) % 360 // 45) * 45 for r in rows if r[3] is not None).most_common()
    # inside the Kingdom
    ring = []
    with open(a.poly, encoding="utf-8") as f:
        for line in f:
            p = line.split()
            if len(p) == 2:
                try:
                    ring.append((float(p[0]), float(p[1])))
                except ValueError:
                    pass

    def in_poly(la, lo):
        c = False
        j = len(ring) - 1
        for i in range(len(ring)):
            xi, yi = ring[i]
            xj, yj = ring[j]
            if (yi > la) != (yj > la) and lo < (xj - xi) * (la - yi) / (yj - yi) + xi:
                c = not c
            j = i
        return c

    outside = [r for r in rows if not in_poly(r[1] / 1e6, r[2] / 1e6)]
    rep["outside_poly"] = len(outside)
    rep["outside_examples"] = [(KINDS[r[0]], r[1] / 1e6, r[2] / 1e6) for r in outside[:10]]
    # owner's box, for nav.md
    ob = [r for r in rows if OWNER_BOX[0] <= r[1] / 1e6 <= OWNER_BOX[1] and OWNER_BOX[2] <= r[2] / 1e6 <= OWNER_BOX[3]]
    rep["owner_box"] = {KINDS[k]: sum(1 for r in ob if r[0] == k) for k in KINDS}
    # duplicates: the same kind within 15 m
    grid = collections.defaultdict(list)
    for r in rows:
        grid[(r[0], r[1] // 200, r[2] // 200)].append(r)
    pairs = collections.Counter()
    ex = []
    for (k, gx, gy), items in grid.items():
        for dx in (0, 1):
            for dy in (-1, 0, 1):
                if dx == 0 and dy < 0:
                    continue
                other = grid.get((k, gx + dx, gy + dy), ())
                for r in items:
                    for o in other:
                        if (dx, dy) == (0, 0) and o <= r:
                            continue
                        d = math.hypot((r[1] - o[1]) * 0.1105, (r[2] - o[2]) * 0.1)
                        if d <= 15:
                            pairs[KINDS[k]] += 1
                            if len(ex) < 12:
                                ex.append((KINDS[k], r[1] / 1e6, r[2] / 1e6, r[3], o[3], round(d, 1)))
    rep["same_kind_within_15m_pairs"] = dict(pairs)
    rep["same_kind_within_15m_examples"] = ex
    # the raw extract: how each direction was found
    raw = {}
    with open(a.jsonl, encoding="utf-8") as f:
        for line in f:
            s = json.loads(line)
            raw[(int(round(s["lat"] * 1e6)), int(round(s["lon"] * 1e6)), {"camera": 1, "signal": 2, "bump": 3}[s["k"]])] = s
    rep["how"] = {KINDS[k]: collections.Counter(s.get("how") or "none" for key, s in raw.items() if key[2] == k).most_common() for k in KINDS}
    rep["on_ways"] = {KINDS[k]: collections.Counter(s.get("on") for key, s in raw.items() if key[2] == k).most_common(4) for k in KINDS}
    # the PBF itself
    h = Read()
    h.apply_file(a.pbf, locations=True, idx="flex_mem")
    print("segments", len(h.segs), dict(h.points), flush=True)
    rep["pbf_rectangle_counts"] = dict(h.points)
    inbox = [r for r in rows if BOX[0] <= r[1] / 1e6 <= BOX[1] and BOX[2] <= r[2] / 1e6 <= BOX[3] and r[5] == 1]
    rep["table_rectangle_counts"] = {KINDS[k]: sum(1 for r in inbox if r[0] == k) for k in KINDS}
    # nearest road segment of each spot in the rectangle
    sgrid = collections.defaultdict(list)
    for s in h.segs:
        la0, la1 = sorted((s[0], s[2]))
        lo0, lo1 = sorted((s[1], s[3]))
        for gx in range(int(la0 * 500), int(la1 * 500) + 1):
            for gy in range(int(lo0 * 500), int(lo1 * 500) + 1):
                sgrid[(gx, gy)].append(s)
    res = collections.defaultdict(lambda: collections.Counter())
    far_ex = []
    by_how = collections.defaultdict(collections.Counter)
    for r in inbox:
        la, lo = r[1] / 1e6, r[2] / 1e6
        best, bd = None, 1e9
        cx, cy = int(la * 500), int(lo * 500)
        for gx in range(cx - 1, cx + 2):
            for gy in range(cy - 1, cy + 2):
                for s in sgrid.get((gx, gy), ()):
                    d = seg_dist_m(la, lo, s)
                    if d < bd:
                        bd, best = d, s
        kind = KINDS[r[0]]
        band = "under 5 m" if bd < 5 else "5-15 m" if bd < 15 else "15-50 m" if bd < 50 else "50-200 m" if bd < 200 else "over 200 m or none"
        res[kind]["road " + band] += 1
        if bd >= 50 and len(far_ex) < 15:
            far_ex.append((kind, la, lo, round(bd)))
        if r[3] is not None and best is not None and bd < 30:
            b = bearing(best[0], best[1], best[2], best[3])
            if best[4] == "-1":
                b = (b + 180) % 360
            diff = adiff(r[3], b)
            axis = min(diff, 180 - diff)
            one = best[4] in ("yes", "1", "true", "-1")
            s = raw.get((r[1], r[2], r[0]), {})
            how = s.get("how") or "?"
            if axis > 30:
                res[kind]["dir across the road"] += 1
                by_how[f"{kind} {how}"]["across"] += 1
            elif one:
                if diff <= 30:
                    res[kind]["dir with a one-way road's traffic"] += 1
                    by_how[f"{kind} {how}"]["with one-way"] += 1
                else:
                    res[kind]["dir against a one-way road's traffic"] += 1
                    by_how[f"{kind} {how}"]["against one-way"] += 1
            else:
                res[kind]["dir along a two-way road"] += 1
                by_how[f"{kind} {how}"]["along two-way"] += 1
    rep["rectangle"] = {k: dict(v) for k, v in res.items()}
    rep["dir_check_by_how"] = {k: dict(v) for k, v in by_how.items()}
    rep["far_from_road_examples"] = far_ex
    # would it warn on the real routes?
    routes = {}
    for name in ("jubail-dammam", "khobar-corniche-dammam-corniche", "dammam-riyadh", "riyadh-jeddah"):
        p = os.path.join(a.routes, name + ".json")
        if not os.path.exists(p):
            continue
        with open(p, encoding="utf-8") as f:
            c = json.load(f)["routes"][0]["geometry"]["coordinates"]
        pts = [(x[1], x[0]) for x in c]
        la0, la1 = min(p_[0] for p_ in pts) - 0.01, max(p_[0] for p_ in pts) + 0.01
        cam = [r for r in rows if r[0] == 1 and la0 * 1e6 <= r[1] <= la1 * 1e6]
        cnt = collections.Counter()
        ex2 = []
        for r in cam:
            la, lo = r[1] / 1e6, r[2] / 1e6
            bd, bb = 1e9, None
            for u, v in zip(pts, pts[1:]):
                if abs(u[0] - la) > 0.02 and abs(v[0] - la) > 0.02:
                    continue
                d = seg_dist_m(la, lo, (u[0], u[1], v[0], v[1]))
                if d < bd:
                    bd, bb = d, bearing(u[0], u[1], v[0], v[1])
            if bd > 30:
                continue
            if r[3] is None:
                cnt["on route, no direction"] += 1
            else:
                diff = adiff(r[3], bb)
                key = "on route, same way" if diff <= 45 else "on route, opposite" if diff >= 135 else "on route, across"
                cnt[key] += 1
        routes[name] = dict(cnt)
    rep["cameras_on_routes_within_30m"] = routes
    # the same road, camera by camera: its own carriageway is the nearest one-way main road, whichever way the route goes
    main_segs = [s for s in h.segs if s[5] in ("motorway", "trunk", "primary", "secondary", "motorway_link", "trunk_link", "primary_link") and s[4] in ("yes", "1", "true")]
    with open(os.path.join(a.routes, "jubail-dammam.json"), encoding="utf-8") as f:
        c = json.load(f)["routes"][0]["geometry"]["coordinates"]
    pts = [(x[1], x[0]) for x in c]
    own = collections.Counter()
    for r in rows:
        if r[0] != 1 or r[3] is None or not 26.3e6 <= r[1] <= 27.1e6 or not 49.5e6 <= r[2] <= 50.2e6:
            continue
        la, lo = r[1] / 1e6, r[2] / 1e6
        if min((seg_dist_m(la, lo, (u[0], u[1], v[0], v[1])) for u, v in zip(pts, pts[1:]) if abs(u[0] - la) < 0.02 or abs(v[0] - la) < 0.02), default=1e9) > 40:
            continue
        nd, ns = 1e9, None
        for s in main_segs:
            if abs(s[0] - la) < 0.01 or abs(s[2] - la) < 0.01:
                d = seg_dist_m(la, lo, s)
                if d < nd:
                    nd, ns = d, s
        if ns is None or nd > 30:
            own["no one-way main road within 30 m"] += 1
            continue
        diff = adiff(r[3], bearing(ns[0], ns[1], ns[2], ns[3]))
        own["with its own carriageway's traffic" if diff <= 45 else "against its own carriageway's traffic" if diff >= 135 else "across"] += 1
    rep["jubail_dammam_cameras_against_own_carriageway"] = dict(own)
    # how many junctions the signal nodes of the owner's box make
    sig = [(r[1] / 1e6, r[2] / 1e6) for r in ob if r[0] == 2]
    parent = list(range(len(sig)))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for i in range(len(sig)):
        for j in range(i + 1, len(sig)):
            if abs(sig[i][0] - sig[j][0]) < 0.0006 and seg_dist_m(sig[i][0], sig[i][1], (sig[j][0], sig[j][1], sig[j][0], sig[j][1])) <= 60:
                parent[find(i)] = find(j)
    rep["owner_box_signal_nodes"] = len(sig)
    rep["owner_box_junctions_at_60m"] = len({find(i) for i in range(len(sig))})
    # the owner's additions
    with open(a.add, encoding="utf-8") as f:
        add = json.load(f)
    rep["additions_keys"] = {k: (len(v) if isinstance(v, (list, dict)) else v) for k, v in add.items()}
    src2 = [r for r in rows if r[5] == 2]
    rep["src2_rows"] = len(src2)
    near_osm = 0
    for r in src2:
        for o in rows:
            if o[5] == 1 and o[0] == r[0] and abs(o[1] - r[1]) < 300 and abs(o[2] - r[2]) < 300:
                near_osm += 1
                break
    rep["src2_with_osm_same_kind_within_30m"] = near_osm
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(rep, f, ensure_ascii=False, indent=1)
    print(json.dumps(rep, ensure_ascii=False))


if __name__ == "__main__":
    main()