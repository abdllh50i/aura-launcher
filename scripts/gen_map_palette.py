#!/usr/bin/env python
"""The map's colours from the design's two palettes, as Kotlin: one command after a polish of map-day.json or map-night.json.

  python -I scripts/gen_map_palette.py [--design <folder with map-day.json, map-night.json>]

Writes app/src/main/java/com/abdllh/aura/nav/MapPalette.kt (what the style draws with, and the POI kinds' colours in the order of
table cat's icon) and nav/offline/KsaOutlineData.kt (tools/mapbuild/polygon/ksa.poly, the Kingdom's outline the tile server chooses by).
Kotlin rather than an asset: nothing to read at run time, so nothing that can fail while the home screen starts.
It stops, writing nothing, when a key is missing from a palette or a colour is not #RRGGBB: a typo must not reach a build.
Standard library only.
"""
import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
NAV = os.path.join(ROOT, "app", "src", "main", "java", "com", "abdllh", "aura", "nav")
DEFAULT_DESIGN = r"D:\k2501-work\tmp\v2\design\final2"

# nav/MapStyle.kt's class C as it was, then route and POI: the palette files' first two blocks
BLOCK = ["land", "built", "commercial", "industrial", "care", "sand", "park", "wood", "water", "waterway", "building", "buildingLine", "building3d",
         "casing", "service", "minor", "tertiary", "secondary", "primary", "primaryCasing", "motorway", "motorwayCasing", "tunnel", "rail", "boundary",
         "roadLabel", "roadHalo", "motorLabel", "place", "placeHalo", "minorPlace", "waterLabel", "poiStroke",
         "route", "routeCasing", "routeAlt", "routeAltCasing", "turn", "turnCasing", "dest", "destRing", "poi", "poiHalo"]
# table cat's icon: 0 other, then the order of nav/Poi.kt's kinds (tools/mapbuild/places_tables.py)
KINDS = ["other", "food", "cafe", "shop", "fuel", "transport", "health", "worship", "lodging", "bank", "school", "park", "sight", "public"]
COLOUR = re.compile(r"^#[0-9A-Fa-f]{6}$")


def stop(msg):
    raise SystemExit("gen_map_palette: " + msg)


def colour(p, key, name):
    v = p.get(key)
    if not isinstance(v, str) or not COLOUR.match(v):
        stop(f"{name}: {key} is {v!r}, not #RRGGBB")
    return v.upper()


def read(path):
    with open(path, encoding="utf-8") as f:
        p = json.load(f)
    name = os.path.basename(path)
    out = {k: colour(p, k, name) for k in BLOCK}
    kinds = p.get("poiKinds") or {}
    for k in KINDS:
        if k not in kinds:
            stop(f"{name}: poiKinds has no {k}")
    out["dots"] = [colour(kinds[k], "dot", name + " " + k) for k in KINDS]
    out["texts"] = [colour(kinds[k], "text", name + " " + k) for k in KINDS]
    light = (p.get("light") or {}).get("intensity")
    if not isinstance(light, (int, float)):
        stop(f"{name}: light.intensity is {light!r}")
    out["light"] = float(light)
    return out


def palette_kt(day, night):
    fields = ", ".join(f"val {k}: String" for k in BLOCK)

    def one(p):
        lines = []
        for i in range(0, len(BLOCK), 6):
            lines.append("        " + ", ".join(f'{k} = "{p[k]}"' for k in BLOCK[i:i + 6]) + ",")
        lines.append("        dots = arrayOf(" + ", ".join(f'"{c}"' for c in p["dots"]) + "),")
        lines.append("        texts = arrayOf(" + ", ".join(f'"{c}"' for c in p["texts"]) + "),")
        lines.append(f"        light = {p['light']}")
        return "\n".join(lines)

    return f"""// Made by scripts/gen_map_palette.py from the design's map-day.json and map-night.json. Do not edit: change those and run it again.
package com.abdllh.aura.nav

/** The colours of the map in one theme. [dots] and [texts]: a place's dot and name, by kind ([KINDS]: table cat's icon). */
internal class MapColors(
    {fields},
    val dots: Array<String>, val texts: Array<String>, val light: Double
)

internal object MapPalette {{
    /** The places' kinds in the order of [MapColors.dots]: the `kind` field of the POI tiles is an index into it. */
    val KINDS = arrayOf({", ".join(f'"{k}"' for k in KINDS)})

    val DAY = MapColors(
{one(day)}
    )

    val NIGHT = MapColors(
{one(night)}
    )
}}
"""


def outline_kt(path):
    pts = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) == 2:
                try:
                    pts.append((float(parts[0]), float(parts[1])))
                except ValueError:
                    pass
    if len(pts) < 100:
        stop(f"{path}: only {len(pts)} points")
    if pts[0] == pts[-1]:
        pts.pop()
    rows = []
    for i in range(0, len(pts), 6):
        rows.append("    " + ", ".join(f"{a:.6f},{b:.6f}" for a, b in pts[i:i + 6]) + ",")
    body = "\n".join(rows)
    return f"""// Made by scripts/gen_map_palette.py from tools/mapbuild/polygon/ksa.poly. Do not edit.
package com.abdllh.aura.nav.offline

/** The Kingdom's outline, {len(pts)} points, one ring: lon, lat, lon, lat... (OSM relation 307584, 0.03 degrees wider, simplified.) */
internal val KSA_RING = doubleArrayOf(
{body}
)
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--design", default=DEFAULT_DESIGN)
    a = ap.parse_args()
    day = read(os.path.join(a.design, "map-day.json"))
    night = read(os.path.join(a.design, "map-night.json"))
    outline = outline_kt(os.path.join(ROOT, "tools", "mapbuild", "polygon", "ksa.poly"))
    for path, text in ((os.path.join(NAV, "MapPalette.kt"), palette_kt(day, night)), (os.path.join(NAV, "offline", "KsaOutlineData.kt"), outline)):
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print(os.path.relpath(path, ROOT), len(text), "bytes")


if __name__ == "__main__":
    sys.exit(main())
