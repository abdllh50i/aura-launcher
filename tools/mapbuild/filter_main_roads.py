#!/usr/bin/env python3
"""Keeps only the main roads of an .osm.pbf, for the smallest routing set that still routes between towns.

    python -I filter_main_roads.py <in.osm.pbf> <out.osm.pbf>

Needs pyosmium. Kept: ways with highway motorway to tertiary and their links, the nodes on them (signals and barriers with
their tags), and turn restrictions. The routing tiles of what is left are built exactly as the full set (build_set.sh).
"""
import sys
import time

import numpy as np
import osmium
import osmium.osm

MAIN = {"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link"}


def main():
    src, dst = sys.argv[1], sys.argv[2]
    t0 = time.time()
    refs = []
    ways = 0
    for w in osmium.FileProcessor(src, osmium.osm.WAY):
        if w.tags.get("highway") in MAIN:
            refs.extend(n.ref for n in w.nodes)
            ways += 1
    nodes = np.unique(np.array(refs, dtype=np.int64))
    del refs
    print(f"{ways:,} main-road ways, {len(nodes):,} nodes; {time.time() - t0:.0f} s", flush=True)
    keep = set(nodes.tolist())
    counts = {"n": 0, "w": 0, "r": 0}
    with osmium.SimpleWriter(dst, overwrite=True) as out:
        for o in osmium.FileProcessor(src):
            if o.is_node():
                if o.id in keep:
                    out.add_node(o)
                    counts["n"] += 1
            elif o.is_way():
                if o.tags.get("highway") in MAIN:
                    out.add_way(o)
                    counts["w"] += 1
            elif o.is_relation():
                if o.tags.get("type", "").startswith("restriction"):
                    out.add_relation(o)
                    counts["r"] += 1
    print(f"written {counts}; {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
