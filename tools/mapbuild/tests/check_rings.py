#!/usr/bin/env python
"""Counts the exits of each roundabout a route drives on OSM's own ways, and compares with the exit number each engine says.   <venv python> -I check_rings.py <ksa.osm.pbf> <out dir> <folder> ...

The count: from the node where the route enters the ring to the node where it leaves, driving the ring's way, each other road a car can leave by counts as one, the last one included.
all_exits counts every such road, main_exits leaves out service roads and tracks. "The route's exit" is the one both say it takes.
Writes <out>/rings.json. OSM is the judge here, not the street: an unmapped arm or a driveway mapped as a road changes the count.
"""
import collections
import glob
import json
import math
import os
import sys

import osmium

DRIVE = {"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service", "road", "track"}


def hav(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[1], a[0], b[1], b[0]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371008 * math.asin(min(1.0, math.sqrt(h)))


class Roads(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.ring_next = {}  # node -> next node on a ring
        self.ring_nodes = {}  # node -> (lon, lat)
        self.ways_at = collections.defaultdict(list)  # node -> (highway, can leave here, way id, access)
        self.grid = collections.defaultdict(list)

    def way(self, w):
        hw = w.tags.get("highway")
        if hw not in DRIVE:
            return
        access = w.tags.get("motor_vehicle") or w.tags.get("access") or ""
        ring = w.tags.get("junction") in ("roundabout", "circular")
        nodes = list(w.nodes)
        if ring:
            for i in range(len(nodes)):
                n = nodes[i]
                if not n.location.valid():
                    continue
                self.ring_nodes[n.ref] = (n.location.lon, n.location.lat)
                self.grid[(int(n.location.lon * 500), int(n.location.lat * 500))].append(n.ref)
                if i + 1 < len(nodes):
                    self.ring_next[n.ref] = nodes[i + 1].ref
            return
        one = w.tags.get("oneway")
        if hw == "motorway" and one is None:
            one = "yes"
        last = len(nodes) - 1
        for i, n in enumerate(nodes):
            if one in ("yes", "1"):
                leave = i < last
            elif one == "-1":
                leave = i > 0
            else:
                leave = True
            self.ways_at[n.ref].append((hw, leave, w.id, access))

    def nearest_ring_node(self, lon, lat, limit=45.0):
        best = (limit, None)
        cx, cy = int(lon * 500), int(lat * 500)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for n in self.grid.get((cx + dx, cy + dy), ()):
                    d = hav((lon, lat), self.ring_nodes[n])
                    if d < best[0]:
                        best = (d, n)
        return best[1]

    def count(self, en, ex):
        """All exits and main exits from entry node en to exit node ex, walking the ring."""
        all_x = main_x = 0
        n = en
        for _ in range(500):
            n = self.ring_next.get(n)
            if n is None:
                return None
            here = [x for x in self.ways_at.get(n, ()) if x[1] and x[3] not in ("no", "private")]
            if here:
                all_x += len(here)
                main_x += sum(1 for x in here if x[0] not in ("service", "track"))
            if n == ex:
                return all_x, main_x
            if n == en:
                return None
        return None


def rings_of(route):
    steps = [s for leg in route["legs"] for s in leg["steps"]]
    out = []
    for i, s in enumerate(steps):
        m = s["maneuver"]
        if m.get("type") in ("roundabout", "rotary") and i + 1 < len(steps):
            nxt = steps[i + 1]["maneuver"]
            if nxt.get("type", "").startswith("exit "):
                out.append((m["location"], nxt["location"], m.get("exit", 0)))
    return out


def main():
    pbf, out = sys.argv[1], sys.argv[2]
    folders = sys.argv[3:] or ["valhalla", "osrm"]
    r = Roads()
    r.apply_file(pbf, locations=True, idx="flex_mem")
    result = {}
    rows = []
    for folder in folders:
        seen = set()
        c = collections.Counter()
        for path in sorted(glob.glob(os.path.join(out, folder, "*.json"))):
            doc = json.load(open(path, encoding="utf-8"))
            if doc.get("code") != "Ok":
                continue
            for route in doc["routes"][:1]:
                for en, ex, n in rings_of(route):
                    a, b = r.nearest_ring_node(*en), r.nearest_ring_node(*ex)
                    key = (a, b)
                    if a is None or b is None or a == b:
                        c["could not place on a ring"] += 1
                        continue
                    if key in seen:
                        continue
                    seen.add(key)
                    cnt = r.count(a, b)
                    if cnt is None:
                        c["ring walk failed"] += 1
                        continue
                    all_x, main_x = cnt
                    c["rings"] += 1
                    c["equal to all exits"] += n == all_x
                    c["equal to main exits"] += n == main_x
                    c["equal to neither"] += n not in (all_x, main_x)
                    c["said more than all exits"] += n > all_x
                    c["said fewer than main exits"] += n < main_x
                    rows.append({"engine": folder, "trip": os.path.basename(path)[:-5], "at": [en[1], en[0]], "out": [ex[1], ex[0]], "said": n, "all_exits": all_x, "main_exits": main_x})
        result[folder] = dict(c)
    with open(os.path.join(out, "rings.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"summary": result, "rows": rows}, f, ensure_ascii=False, indent=0)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
