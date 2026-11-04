#!/usr/bin/env python
"""Finds real U-turn gaps and frontage (service) roads in an OSM extract, for route_probes.py.   Runs with pyosmium:

    <venv>\\Scripts\\python.exe -I find_sites.py <probe.osm.pbf> <out sites.json>

U-turn gap: a short way (<= 90 m) whose two ends lie on two one-way roads running opposite ways. The trip built on it starts about
250 m before the gap on one carriageway and ends about 250 m after it on the other, so the shortest legal drive is known exactly.
Frontage: a road running the same way 10-50 m beside a one-way main road for at least 300 m (maps.md section 13, item 12).
Every site is taken from the map's own ways, nothing is written by hand. The route itself is judged by route_probes.py.
"""
import collections
import json
import math
import sys

import osmium

MAIN = {"motorway", "trunk", "primary", "secondary"}
CARRIAGE = MAIN | {"tertiary"}
SIDE = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "service", "living_street"}
GAP = {"primary_link", "secondary_link", "tertiary_link", "trunk_link", "tertiary", "unclassified", "residential", "service", "secondary", "primary"}
ALL = SIDE | GAP | {"motorway_link"}
RANK = {"motorway": 0, "trunk": 1, "primary": 2, "secondary": 3, "tertiary": 4, "unclassified": 5, "residential": 6, "service": 7, "living_street": 8}
M_LAT = 110574.0
M_LON = 111320.0 * math.cos(math.radians(26.6))


def xy(lat, lon):
    return lon * M_LON, lat * M_LAT


def dist(a, b):
    return math.hypot((a[1] - b[1]) * M_LON, (a[0] - b[0]) * M_LAT)


def bearing(a, b):
    return math.degrees(math.atan2((b[1] - a[1]) * M_LON, (b[0] - a[0]) * M_LAT)) % 360


def adiff(a, b):
    d = abs(a - b) % 360
    return 360 - d if d > 180 else d


class H(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.ways = {}
        self.restrictions = []

    def way(self, w):
        hw = w.tags.get("highway")
        if hw not in ALL:
            return
        access = w.tags.get("access") or w.tags.get("motor_vehicle") or ""
        if access in ("no", "private"):
            return
        nodes = []
        for n in w.nodes:
            if not n.location.valid():
                return
            nodes.append((n.ref, round(n.location.lat, 7), round(n.location.lon, 7)))
        if len(nodes) < 2:
            return
        one = w.tags.get("oneway")
        if one == "-1":
            nodes.reverse()
        oneway = one in ("yes", "-1", "1") or w.tags.get("junction") in ("roundabout", "circular") or hw == "motorway"
        self.ways[w.id] = {"id": w.id, "hw": hw, "name": w.tags.get("name") or w.tags.get("name:ar") or w.tags.get("name:en") or "", "ref": w.tags.get("ref") or "",
                           "oneway": oneway, "nodes": nodes, "roundabout": w.tags.get("junction") in ("roundabout", "circular"), "service": w.tags.get("service") or ""}

    def relation(self, r):
        if r.tags.get("type") != "restriction":
            return
        kind = r.tags.get("restriction") or ""
        via = [m.ref for m in r.members if m.role == "via" and m.type == "n"]
        if via:
            self.restrictions.append((kind, via[0]))


def walk(h, ends, way, node_index, metres, backwards):
    """A point that many metres up (or down) a one-way road, carrying on into the next piece when this way ends. None if the road stops first."""
    w = way
    i = node_index
    left = metres
    seen = {w["id"]}
    while True:
        n = w["nodes"]
        j = i - 1 if backwards else i + 1
        if j < 0 or j >= len(n):
            key = n[0][0] if backwards else n[-1][0]
            nxt = [x for x in ends.get((key, "last" if backwards else "first"), ()) if x["id"] not in seen and x["oneway"] and x["hw"] == w["hw"] and not x["roundabout"]]
            if len(nxt) != 1:
                return None
            w = nxt[0]
            seen.add(w["id"])
            i = len(w["nodes"]) - 1 if backwards else 0
            continue
        a, b = n[i][1:], n[j][1:]
        d = dist(a, b)
        if d >= left and d > 0:
            f = left / d
            p = (a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)
            head = bearing(b, a) if backwards else bearing(a, b)
            return p, head, w["id"]
        left -= d
        i = j


def find_gaps(h):
    by_node = collections.defaultdict(list)
    ends = collections.defaultdict(list)
    for w in h.ways.values():
        ends[(w["nodes"][0][0], "first")].append(w)
        ends[(w["nodes"][-1][0], "last")].append(w)
        if w["oneway"] and w["hw"] in CARRIAGE and not w["roundabout"]:
            for i, nd in enumerate(w["nodes"]):
                by_node[nd[0]].append((w, i))
    banned = {via for kind, via in h.restrictions if kind in ("no_u_turn", "no_left_turn")}
    out = []
    for g in h.ways.values():
        if g["hw"] not in GAP or g["roundabout"]:
            continue
        n = g["nodes"]
        length = sum(dist(n[i][1:], n[i + 1][1:]) for i in range(len(n) - 1))
        if length > 90 or length < 5:
            continue
        orders = [(n[0], n[-1])] if g["oneway"] else [(n[0], n[-1]), (n[-1], n[0])]
        for s, e in orders:
            for a, ia in by_node.get(s[0], ()):
                for b, ib in by_node.get(e[0], ()):
                    if a["id"] == b["id"] or a["id"] == g["id"] or b["id"] == g["id"]:
                        continue
                    if ia == 0 or ib >= len(b["nodes"]) - 1:
                        continue
                    ha = bearing(a["nodes"][ia - 1][1:], a["nodes"][ia][1:])
                    hb = bearing(b["nodes"][ib][1:], b["nodes"][ib + 1][1:])
                    if adiff(ha, hb) < 160:
                        continue
                    start = walk(h, ends, a, ia, 250, True)
                    end = walk(h, ends, b, ib, 250, False)
                    if not start or not end:
                        continue
                    out.append({"gap_way": g["id"], "gap_class": g["hw"], "gap_m": round(length), "at": [round(s[1], 6), round(s[2], 6)], "banned": s[0] in banned or e[0] in banned,
                                "road": a["name"] or a["ref"], "road_class": a["hw"], "from": [round(start[0][0], 6), round(start[0][1], 6)], "heading": round(start[1]),
                                "to": [round(end[0][0], 6), round(end[0][1], 6)], "ideal_m": round(500 + length)})
    return out


def find_frontage(h):
    cell = 120.0
    grid = collections.defaultdict(list)
    for w in h.ways.values():
        if w["hw"] not in SIDE or w["roundabout"]:
            continue
        n = w["nodes"]
        for i in range(len(n) - 1):
            ax, ay = xy(*n[i][1:])
            bx, by = xy(*n[i + 1][1:])
            for cx in {int(ax // cell), int(bx // cell)}:
                for cy in {int(ay // cell), int(by // cell)}:
                    grid[(cx, cy)].append((w["id"], ax, ay, bx, by))
    runs = collections.defaultdict(list)
    per_class = collections.Counter()
    total = collections.Counter()
    for m in h.ways.values():
        if m["hw"] not in MAIN or not m["oneway"] or m["roundabout"]:
            continue
        n = m["nodes"]
        for i in range(len(n) - 1):
            ax, ay = xy(*n[i][1:])
            bx, by = xy(*n[i + 1][1:])
            seg = math.hypot(bx - ax, by - ay)
            if seg < 1:
                continue
            head = math.degrees(math.atan2(bx - ax, by - ay)) % 360
            steps = max(1, int(seg // 25))
            for k in range(steps):
                f = (k + 0.5) / steps
                px, py = ax + (bx - ax) * f, ay + (by - ay) * f
                total[m["hw"]] += seg / steps
                best = {}
                cx, cy = int(px // cell), int(py // cell)
                for ox in (-1, 0, 1):
                    for oy in (-1, 0, 1):
                        for wid, sx, sy, ex, ey in grid.get((cx + ox, cy + oy), ()):
                            if wid == m["id"]:
                                continue
                            dx, dy = ex - sx, ey - sy
                            ll = dx * dx + dy * dy
                            if ll < 1:
                                continue
                            t = max(0.0, min(1.0, ((px - sx) * dx + (py - sy) * dy) / ll))
                            qx, qy = sx + dx * t, sy + dy * t
                            d = math.hypot(px - qx, py - qy)
                            if d < 10 or d > 50:
                                continue
                            hf = math.degrees(math.atan2(dx, dy)) % 360
                            w = h.ways[wid]
                            same = adiff(head, hf) <= 15
                            if not same and not (not w["oneway"] and adiff(head, hf) >= 165):
                                continue
                            if wid not in best or d < best[wid][0]:
                                best[wid] = (d, qx, qy, head)
                if best:
                    per_class[m["hw"]] += seg / steps
                for wid, (d, qx, qy, hd) in best.items():
                    runs[(m["id"], wid)].append((d, px, py, qx, qy, hd, seg / steps))
    out = []
    for (mid, fid), r in runs.items():
        length = sum(x[6] for x in r)
        if length < 300:
            continue
        d, px, py, qx, qy, hd, _ = r[len(r) // 2]
        m, f = h.ways[mid], h.ways[fid]
        out.append({"main_way": mid, "main_class": m["hw"], "main_name": m["name"] or m["ref"], "side_way": fid, "side_class": f["hw"], "side_name": f["name"], "side_service": f["service"],
                    "side_oneway": f["oneway"], "run_m": round(length), "gap_m": round(sum(x[0] for x in r) / len(r)), "on_main": [round(py / M_LAT, 6), round(px / M_LON, 6)],
                    "on_side": [round(qy / M_LAT, 6), round(qx / M_LON, 6)], "heading": round(hd)})
    stats = {k: {"km": round(total[k] / 1000, 1), "km_with_side_road": round(per_class[k] / 1000, 1)} for k in total}
    return out, stats


def main():
    h = H()
    h.apply_file(sys.argv[1], locations=True, idx="flex_mem")
    gaps = find_gaps(h)
    side, stats = find_frontage(h)
    gaps.sort(key=lambda g: (g["banned"], g["from"]))
    side.sort(key=lambda s: -s["run_m"])
    rep = {"ways": len(h.ways), "restrictions": len(h.restrictions), "one_way_main_roads_with_a_side_road": stats, "gaps_found": len(gaps), "frontage_found": len(side), "gaps": gaps, "frontage": side}
    with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as f:
        json.dump(rep, f, ensure_ascii=False, indent=0)
    print("ways", len(h.ways), "gaps", len(gaps), "frontage pairs", len(side), json.dumps(stats))


if __name__ == "__main__":
    main()
