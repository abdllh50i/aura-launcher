#!/usr/bin/env python3
"""Makes the cutting polygon from the member ways written by boundary_members.py.

    python3 -I boundary_poly.py <members.json> <out dir> <name> [buffer_deg] [simplify_deg]

Runs inside the Valhalla image, which already has shapely. Output: <name>.poly (Osmosis format, read by the cutter and
by Planetiler's --polygon) and <name>.geojson (the same outline). The buffer keeps border posts and the Saudi end of
the King Fahd Causeway; the simplification stays well under the buffer, so the outline stays outside the real border.
"""
import json
import sys

from shapely.geometry import LineString, MultiPolygon, Point, mapping
from shapely.ops import polygonize, unary_union


def rings_of(geom):
    polys = geom.geoms if isinstance(geom, MultiPolygon) else [geom]
    for p in polys:
        yield p.exterior.coords, False
        for h in p.interiors:
            yield h.coords, True


def main():
    members, out_dir, name = sys.argv[1], sys.argv[2], sys.argv[3]
    buffer_deg = float(sys.argv[4]) if len(sys.argv) > 4 else 0.03
    simplify_deg = float(sys.argv[5]) if len(sys.argv) > 5 else 0.005

    doc = json.load(open(members, encoding="utf-8"))
    print("relation", doc["relation"], {k: doc["tags"].get(k) for k in ("name", "name:en", "admin_level", "ISO3166-1")})
    print("ways", len(doc["ways"]), "missing ways", len(doc["missing_ways"]), "missing nodes", doc["missing_nodes"])
    roles = {}
    for w in doc["ways"]:
        roles[w["role"]] = roles.get(w["role"], 0) + 1
    print("roles", roles)

    lines = [LineString(w["coords"]) for w in doc["ways"] if len(w["coords"]) >= 2 and w["role"] in ("outer", "inner", "")]
    merged = unary_union(lines)
    faces = list(polygonize(merged))
    print("faces", len(faces), "areas (deg2):", sorted((round(f.area, 4) for f in faces), reverse=True)[:8])
    # keep the faces bounded by the relation's rings: holes (inner rings) are faces too, told apart by being inner rings
    inner_lines = [LineString(w["coords"]) for w in doc["ways"] if len(w["coords"]) >= 2 and w["role"] == "inner"]
    inner_faces = list(polygonize(unary_union(inner_lines))) if inner_lines else []
    keep = [f for f in faces if not any(f.representative_point().within(h) for h in inner_faces)]
    real = unary_union(keep)
    print("real outline: type", real.geom_type, "area deg2", round(real.area, 3), "bounds", [round(v, 4) for v in real.bounds],
          "inner rings", len(inner_faces))

    buffered = real.buffer(buffer_deg, quad_segs=4)
    simple = buffered.simplify(simplify_deg, preserve_topology=True)
    if not simple.is_valid:
        simple = simple.buffer(0)
    # drop holes smaller than the area of a few tiles: nothing is gained by them
    if isinstance(simple, MultiPolygon):
        parts = list(simple.geoms)
    else:
        parts = [simple]
    print("buffered: parts", len(parts), "vertices", sum(len(r[0]) for r in rings_of(simple)))
    # the outline must hold the whole real border
    outside = real.difference(simple)
    print("real area outside the outline (deg2):", round(outside.area, 8))
    if outside.area > 1e-6:
        sys.exit("the simplified outline cuts into the real border: lower simplify_deg")
    print("distance of the real border to the outline, min deg:", round(real.boundary.distance(simple.boundary), 5))

    poly_path = f"{out_dir}/{name}.poly"
    with open(poly_path, "w", encoding="ascii", newline="\n") as f:
        f.write(f"{name}\n")
        i = 0
        for coords, hole in rings_of(simple):
            i += 1
            f.write(("!" if hole else "") + f"{i}\n")
            for x, y in coords:
                f.write(f"   {x:.6f}   {y:.6f}\n")
            f.write("END\n")
        f.write("END\n")
    with open(f"{out_dir}/{name}.geojson", "w", encoding="utf-8", newline="\n") as f:
        json.dump({"type": "Feature", "properties": {"name": name, "source": f"OSM relation {doc['relation']}",
                                                     "buffer_deg": buffer_deg, "simplify_deg": simplify_deg},
                   "geometry": mapping(simple)}, f)
    # outline of the real border for reference checks (not simplified much: 0.0005 deg)
    with open(f"{out_dir}/{name}-real.geojson", "w", encoding="utf-8", newline="\n") as f:
        json.dump({"type": "Feature", "properties": {"name": name + " real border, simplified 0.0005 deg"},
                   "geometry": mapping(real.simplify(0.0005, preserve_topology=True))}, f)
    print("wrote", poly_path)

    # border posts and places that must fall inside (lat, lon), checked against the outline
    checks = {
        "King Fahd Causeway, Saudi passport island": (26.1862, 50.3290),
        "Khobar corniche": (26.2885, 50.2185),
        "Salwa post (Qatar)": (24.7405, 50.7600),
        "Al Batha post (UAE)": (24.2520, 51.5840),
        "Khafji post (Kuwait)": (28.5240, 48.4450),
        "Halat Ammar post (Jordan)": (29.1110, 36.0740),
        "Al Wadiah post (Yemen)": (17.0520, 47.1040),
        "Jazan": (16.8892, 42.5511),
        "Abha": (18.2164, 42.5053),
        "Riyadh": (24.7136, 46.6753),
        "Jeddah": (21.5433, 39.1728),
        "Tabuk": (28.3838, 36.5550),
        "Farasan island": (16.7000, 42.1200),
    }
    for label, (lat, lon) in checks.items():
        p = Point(lon, lat)
        print(f"  {label}: inside outline {simple.contains(p)}, inside real border {real.contains(p)}")


if __name__ == "__main__":
    main()
