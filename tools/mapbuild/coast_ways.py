#!/usr/bin/env python3
"""Writes every natural=coastline way of an .osm.pbf as coordinates, in the way's own direction (OSM: land on its left).

    python -I coast_ways.py <in.osm.pbf> <out.json>

Input for make_water_polygons.py. Used when the ready water polygons of osmdata.openstreetmap.de cannot be fetched.
"""
import json
import sys
import time

import osmium


def main():
    pbf, out = sys.argv[1], sys.argv[2]
    t0 = time.time()
    ways = []
    for w in osmium.FileProcessor(pbf, osmium.osm.WAY).with_filter(osmium.filter.TagFilter(("natural", "coastline"))):
        ways.append((w.id, [n.ref for n in w.nodes]))
    ids = {n for _, refs in ways for n in refs}
    print(f"coastline ways {len(ways):,}, nodes {len(ids):,}; {time.time() - t0:.0f} s", flush=True)
    coords = {}
    for n in osmium.FileProcessor(pbf, osmium.osm.NODE).with_filter(osmium.filter.IdFilter(ids)):
        coords[n.id] = (n.location.lon, n.location.lat)
    print(f"nodes found {len(coords):,}; {time.time() - t0:.0f} s", flush=True)
    doc = {"ways": [{"id": wid, "coords": [coords[r] for r in refs if r in coords]} for wid, refs in ways]}
    with open(out, "w", encoding="ascii") as f:
        json.dump(doc, f)
    print(f"wrote {out}; {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
