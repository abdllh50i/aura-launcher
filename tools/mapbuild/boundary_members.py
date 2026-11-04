#!/usr/bin/env python3
"""Reads one boundary relation out of an .osm.pbf and writes its member ways as coordinates.

    python -I boundary_members.py <in.osm.pbf> <relation id> <out.json>

Needs pyosmium. The rings are put together afterwards by boundary_poly.py (shapely, inside the Valhalla image).
Three plain passes over the file (relations, then ways, then nodes), so no node cache is kept in memory.
"""
import json
import sys
import time

import osmium


def main():
    pbf, rel_id, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    t0 = time.time()

    relation = None
    for r in osmium.FileProcessor(pbf, osmium.osm.RELATION):
        if r.id == rel_id:
            relation = {
                "id": r.id,
                "tags": {t.k: t.v for t in r.tags},
                "members": [(m.type, m.ref, m.role) for m in r.members],
            }
            break
    if relation is None:
        sys.exit(f"relation {rel_id} is not in {pbf}")

    roles = {}
    for typ, ref, role in relation["members"]:
        if typ == "w":
            roles[ref] = role
    print(f"relation {rel_id}: {len(relation['members'])} members, {len(roles)} ways; {time.time() - t0:.0f} s", flush=True)

    ways = {}
    for w in osmium.FileProcessor(pbf, osmium.osm.WAY):
        if w.id in roles:
            ways[w.id] = [n.ref for n in w.nodes]
    missing_ways = sorted(set(roles) - set(ways))
    print(f"ways found {len(ways)}, missing {len(missing_ways)}; {time.time() - t0:.0f} s", flush=True)

    wanted = {n for refs in ways.values() for n in refs}
    coords = {}
    for n in osmium.FileProcessor(pbf, osmium.osm.NODE):
        if n.id in wanted:
            coords[n.id] = (n.location.lon, n.location.lat)
    missing_nodes = len(wanted - set(coords))
    print(f"nodes wanted {len(wanted)}, missing {missing_nodes}; {time.time() - t0:.0f} s", flush=True)

    doc = {
        "relation": rel_id,
        "tags": relation["tags"],
        "missing_ways": missing_ways,
        "missing_nodes": missing_nodes,
        "ways": [
            {"id": wid, "role": roles[wid], "coords": [coords[n] for n in refs if n in coords]}
            for wid, refs in ways.items()
        ],
    }
    with open(out, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False)
    keep = ("name", "name:en", "name:ar", "admin_level", "ISO3166-1", "ISO3166-1:alpha2", "boundary", "type", "default_language")
    print({k: relation["tags"].get(k) for k in keep})
    print(f"wrote {out}; {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
