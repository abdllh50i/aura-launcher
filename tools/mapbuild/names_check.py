#!/usr/bin/env python
"""Do a road's names survive into Valhalla's tiles? Asks a running valhalla_service with /locate (verbose) for sample ways.

    python -I names_check.py <osm-road.jsonl> <base url> <box w,s,e,n> <out.json> [samples per case]

Cases come from the OSM tags of the way (read from osm_extract.py's output): Latin name with name:ar, Arabic name with
name:en, and so on. For each sampled way the point on it is located and the edge with the same way id is read:
edge_info.names is everything the tile keeps for that road. Network: only the local service.
"""
import collections
import json
import random
import sys
import urllib.request


def has_ar(s):
    return any("؀" <= c <= "ۿ" or "ﭐ" <= c <= "﷿" or "ﹰ" <= c <= "﻿" for c in s or "")


def has_lat(s):
    return any("a" <= c.lower() <= "z" for c in s or "")


def case_of(t):
    name, ar, en = t.get("name", ""), t.get("name:ar", ""), t.get("name:en", "")
    alt = t.get("alt_name", "") or t.get("official_name", "")
    if name and not has_ar(name) and ar:
        return "latin name + name:ar"
    if name and not has_ar(name) and not ar:
        return "latin name, no name:ar"
    if has_ar(name) and en:
        return "arabic name + name:en"
    if has_ar(name) and has_lat(name):
        return "mixed name"
    if has_ar(name):
        return "arabic name only"
    if not name and ar:
        return "no name, name:ar"
    if not name and en:
        return "no name, name:en"
    if not name and alt:
        return "no name, alt or official"
    if t.get("ref") and not name:
        return "ref only"
    return None


def locate(url, lat, lon):
    body = {"locations": [{"lat": lat, "lon": lon, "radius": 5}], "costing": "auto", "verbose": True}
    req = urllib.request.Request(url + "/locate", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())[0]


def main():
    path, url, box, out = sys.argv[1:5]
    per_case = int(sys.argv[5]) if len(sys.argv) > 5 else 40
    w, s, e, n = [float(v) for v in box.split(",")]
    random.seed(5)
    cases = collections.defaultdict(list)
    with open(path, encoding="utf-8") as f:
        for ln in f:
            d = json.loads(ln)
            t = d["t"]
            hw = t.get("highway", "")
            if hw not in ("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "motorway_link", "trunk_link",
                          "primary_link", "secondary_link", "tertiary_link", "living_street"):
                continue
            pts = [p for p in d["pts"] if s <= p[0] <= n and w <= p[1] <= e]
            if not pts or not d["o"].startswith("w"):
                continue
            c = case_of(t)
            if c:
                cases[c].append((int(d["o"][1:]), pts, t))
    report = {}
    for c, items in sorted(cases.items()):
        random.shuffle(items)
        tried = found = 0
        kept = collections.Counter()
        samples = []
        for wid, pts, t in items[:per_case * 3]:
            if found >= per_case:
                break
            hit = None
            for lat, lon in pts[:3]:
                tried += 1
                try:
                    res = locate(url, lat, lon)
                except Exception:
                    continue
                for edge in res.get("edges") or []:
                    info = edge.get("edge_info") or {}
                    if info.get("way_id") == wid:
                        hit = info.get("names") or []
                        break
                if hit is not None:
                    break
            if hit is None:
                continue
            found += 1
            joined = " | ".join(hit)
            kept["some Arabic"] += has_ar(joined)
            kept["some Latin"] += has_lat(joined)
            kept["empty"] += not hit
            for tag in ("name", "name:ar", "name:en", "ref", "alt_name", "official_name", "int_ref"):
                if t.get(tag):
                    kept[f"{tag} on the way"] += 1
                    kept[f"{tag} in names"] += any(t[tag] == v or v in t[tag].split(";") for v in hit)
            if len(samples) < 6:
                samples.append({"way": wid, "tags": {k: v for k, v in t.items() if k.startswith(("name", "ref", "alt_name", "official"))}, "names": hit})
        report[c] = {"ways in the box": len(items), "read": found, "counts": dict(sorted(kept.items())), "samples": samples}
        print(c.encode("ascii", "replace").decode(), "ways", len(items), "read", found, {k: v for k, v in sorted(kept.items())}, flush=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
