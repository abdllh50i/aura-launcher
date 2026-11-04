#!/usr/bin/env python
"""Lists nodes that carry a barrier (or a checkpoint) on a drivable road, and what they are tagged.   <venv python> -I barrier_nodes.py <ksa.osm.pbf> <out.json>

Valhalla charges a gate a large penalty (gate_penalty, 300 s by default), so one such node on a main road can send a route around it. This says where they are.
"""
import collections
import json
import sys

import osmium

MAIN = {"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link"}
ROADS = MAIN | {"unclassified", "residential", "service", "living_street"}


class Nodes(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.n = {}

    def node(self, n):
        t = n.tags
        b = t.get("barrier")
        hw = t.get("highway")
        if b or hw in ("checkpoint",) or t.get("military") or t.get("amenity") == "police":
            self.n[n.id] = {"id": n.id, "lat": n.location.lat, "lon": n.location.lon, "barrier": b or "", "highway": hw or "", "access": t.get("access") or "", "motor_vehicle": t.get("motor_vehicle") or "",
                            "name": t.get("name") or t.get("name:en") or "", "other": t.get("military") or t.get("amenity") or "", "tags": {k: v for k, v in t}}


class Ways(osmium.SimpleHandler):
    def __init__(self, nodes):
        super().__init__()
        self.nodes = nodes
        self.on = collections.defaultdict(list)

    def way(self, w):
        hw = w.tags.get("highway")
        if hw not in ROADS:
            return
        for nd in w.nodes:
            if nd.ref in self.nodes:
                self.on[nd.ref].append({"way": w.id, "highway": hw, "name": w.tags.get("name") or w.tags.get("ref") or ""})


def main():
    a = Nodes()
    a.apply_file(sys.argv[1])
    b = Ways(a.n)
    b.apply_file(sys.argv[1])
    rows = []
    for nid, ways in b.on.items():
        n = dict(a.n[nid])
        n["ways"] = ways
        n["best"] = min(ways, key=lambda x: (x["highway"] not in MAIN, sorted(MAIN).index(x["highway"]) if x["highway"] in MAIN else 99))["highway"]
        rows.append(n)
    rows.sort(key=lambda r: (r["best"], r["lat"]))
    count = collections.Counter((r["best"], r["barrier"] or r["highway"] or r["other"]) for r in rows)
    with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as f:
        json.dump({"counts": {f"{k[0]} | {k[1]}": v for k, v in count.most_common()}, "nodes": rows}, f, ensure_ascii=False, indent=0)
    print(len(rows), count.most_common(30))


if __name__ == "__main__":
    main()
