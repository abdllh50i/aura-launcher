#!/usr/bin/env python
"""Everything places.db wants from an OpenStreetMap extract, read with the small reader in osmpbf.py.

  python osm_extract.py --pbf gcc-states-latest.osm.pbf --out D:\\k2501-work\\mapbuild\\places

Writes, one JSON object per line, in the whole extract (the Saudi boundary is applied later, in build_places.py):
  osm-poi.jsonl    named places of interest and the useful unnamed ones (mosques, fuel, parking...): nodes, ways, relations
  osm-place.jsonl  place=* (cities, towns, villages, neighbourhoods)
  osm-road.jsonl   named or numbered roads, with points along them about every kilometre
  osm-spot.jsonl   speed cameras, traffic signals, traffic calming, with direction and limit when known
and osm-stats.json with the counts, to compare with Geofabrik's taginfo.
"""
import argparse
import array
import collections
import json
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np

from osmpbf import PbfReader

POI_KEYS = ("amenity", "shop", "tourism", "leisure", "office", "healthcare", "craft", "historic", "aeroway", "railway",
            "public_transport", "man_made", "natural", "landuse", "military", "emergency")
NODE_KEYS = POI_KEYS + ("highway", "traffic_calming", "place", "building", "barrier")
NAME_KEYS = (b"name", b"name:ar", b"name:en", b"brand", b"brand:ar", b"brand:en")
# what a road row may be: not paths, tracks, or ways under construction
ROADS = {b"motorway", b"trunk", b"primary", b"secondary", b"tertiary", b"unclassified", b"residential", b"living_street",
         b"motorway_link", b"trunk_link", b"primary_link", b"secondary_link", b"tertiary_link", b"service", b"pedestrian", b"road"}
UNNAMED_OK = {b"place_of_worship", b"fuel", b"parking", b"atm", b"pharmacy", b"hospital", b"clinic", b"car_wash", b"car_repair",
              b"charging_station", b"bank", b"police", b"fire_station"}
KEEP = set("""name name:ar name:en int_name alt_name alt_name:ar alt_name:en old_name old_name:ar old_name:en official_name
official_name:ar official_name:en short_name short_name:ar short_name:en loc_name loc_name:ar brand brand:ar brand:en
brand:wikidata operator operator:ar operator:en phone contact:phone phone:mobile contact:mobile mobile ref amenity shop
tourism leisure office healthcare healthcare:speciality craft historic aeroway railway public_transport highway place religion
denomination cuisine sport access parking building junction bridge tunnel oneway maxspeed man_made natural landuse waterway
barrier iata icao capital population direction traffic_signals:direction traffic_calming enforcement military emergency
wikidata""".split())
SPOT_LATER = {"speed_camera", "traffic_signals"}


def keep(t):
    return {k: v for k, v in t.items() if k in KEEP}


def wanted_poi_bytes(t):
    named = any(k in t for k in NAME_KEYS)
    if named and (b"building" in t or b"place" in t):
        return True
    for k in (b"amenity", b"shop", b"tourism", b"leisure", b"office", b"healthcare", b"craft", b"historic", b"aeroway",
              b"railway", b"public_transport", b"man_made", b"natural", b"landuse", b"military", b"emergency"):
        if k in t:
            if named:
                return True
            if t.get(b"amenity") in UNNAMED_OK:
                return True
            if t.get(b"shop") == b"tyres":
                return True
    if t.get(b"highway") in (b"services", b"rest_area"):
        return True
    return False


def wanted_poi(t):
    return wanted_poi_bytes({k.encode(): v.encode() for k, v in t.items()})


def bearing(lat1, lon1, lat2, lon2):
    y = math.sin(math.radians(lon2 - lon1)) * math.cos(math.radians(lat2))
    x = math.cos(math.radians(lat1)) * math.sin(math.radians(lat2)) - \
        math.sin(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.cos(math.radians(lon2 - lon1))
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def centroid(pts):
    """Area centroid of a closed ring, or the mean of the points."""
    if len(pts) >= 4 and pts[0] == pts[-1]:
        a = cx = cy = 0.0
        y0, x0 = pts[0]
        for (y1, x1), (y2, x2) in zip(pts, pts[1:]):
            c = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            a += c
            cx += (x1 - x0 + x2 - x0) * c
            cy += (y1 - y0 + y2 - y0) * c
        if abs(a) > 1e-12:
            return y0 + cy / (3 * a), x0 + cx / (3 * a)
    return sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts)


def along(pts, step_m=1000.0):
    """Points along a line: its middle when short, else one per step, none at the very ends."""
    seg = []
    tot = 0.0
    for (a1, o1), (a2, o2) in zip(pts, pts[1:]):
        dy = (a2 - a1) * 111320.0
        dx = (o2 - o1) * 111320.0 * math.cos(math.radians((a1 + a2) / 2))
        d = math.hypot(dx, dy)
        seg.append(d)
        tot += d
    if tot == 0 or not seg:
        return [pts[0]]
    n = max(1, int(tot // step_m))
    want = [tot * (i + 0.5) / n for i in range(n)]
    out = []
    i = 0
    acc = 0.0
    for w in want:
        while i < len(seg) - 1 and acc + seg[i] < w:
            acc += seg[i]
            i += 1
        f = (w - acc) / seg[i] if seg[i] else 0.0
        out.append((pts[i][0] + (pts[i + 1][0] - pts[i][0]) * f, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * f))
    return out


def parse_direction(v):
    """Degrees from an OSM direction value, 'forward'/'backward' as they are, None when unusable."""
    if not v:
        return None
    v = v.strip().lower()
    if v in ("forward", "backward"):
        return v
    table = {"n": 0, "nne": 22, "ne": 45, "ene": 67, "e": 90, "ese": 112, "se": 135, "sse": 157, "s": 180, "ssw": 202, "sw": 225,
             "wsw": 247, "w": 270, "wnw": 292, "nw": 315, "nnw": 337, "north": 0, "east": 90, "south": 180, "west": 270}
    if v in table:
        return table[v]
    try:
        return int(float(v)) % 360
    except ValueError:
        return None


def parse_kmh(v):
    if not v:
        return None
    v = v.strip().lower().split(";")[0]
    mph = "mph" in v
    digits = "".join(c for c in v if c.isdigit())
    if not digits:
        return None
    k = int(digits)
    if mph:
        k = int(round(k * 1.609))
    return k if 5 <= k <= 160 else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pbf", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    t00 = time.time()
    r = PbfReader(a.pbf)
    print(f"index: {time.time() - t00:.1f} s, node blobs {len(r.index[2])}, way blobs {len(r.index[3])}, relation blobs {len(r.index[4])}", flush=True)
    stats = collections.Counter()

    # relations first: the ways they need must be known before the ways are read
    t0 = time.time()
    rel_rows = []
    enforcement = []
    rel_ways = set()

    def rel_wanted(t):
        ty = t.get(b"type")
        if ty == b"enforcement":
            return True
        return ty in (b"multipolygon", b"site", b"building") and wanted_poi_bytes(t)

    for rid, mem, tags in r.relations(rel_wanted):
        if tags.get("type") == "enforcement":
            enforcement.append((rid, mem, keep(tags)))
            stats["enforcement relations"] += 1
            continue
        outer = [m[1] for m in mem if m[0] == 1 and m[2] in ("outer", "", "perimeter", "outline")]
        nodes = [m[1] for m in mem if m[0] == 0]
        if not outer and not nodes:
            continue
        rel_rows.append((rid, outer, nodes, keep(tags)))
        rel_ways.update(outer)
    print(f"relations: {time.time() - t0:.1f} s, {len(rel_rows)} POI relations, {len(enforcement)} enforcement, {len(rel_ways)} ways they name", flush=True)

    # tagged nodes: POIs, places and spots
    t0 = time.time()
    poi_f = open(os.path.join(a.out, "osm-poi.jsonl"), "w", encoding="utf-8")
    place_f = open(os.path.join(a.out, "osm-place.jsonl"), "w", encoding="utf-8")
    spots = {}
    extra_nodes = {}
    for m in enforcement:
        for ty, mid, role in m[1]:
            if ty == 0:
                extra_nodes[mid] = None
    for nid, lat, lon, tags in r.tagged_nodes(NODE_KEYS):
        hw = tags.get("highway")
        if hw in SPOT_LATER or "traffic_calming" in tags:
            kind = "camera" if hw == "speed_camera" else "signal" if hw == "traffic_signals" else "bump"
            spots[nid] = {"k": kind, "o": f"n{nid}", "lat": round(lat, 7), "lon": round(lon, 7), "t": keep(tags)}
            stats["spot " + kind] += 1
        if "place" in tags:
            json.dump({"o": f"n{nid}", "lat": round(lat, 7), "lon": round(lon, 7), "t": keep(tags)}, place_f, ensure_ascii=False)
            place_f.write("\n")
            stats["place nodes"] += 1
        elif hw == "motorway_junction" and ("ref" in tags or "name" in tags):
            json.dump({"o": f"n{nid}", "lat": round(lat, 7), "lon": round(lon, 7), "t": keep(tags)}, poi_f, ensure_ascii=False)
            poi_f.write("\n")
            stats["junction nodes"] += 1
        elif wanted_poi(tags):
            json.dump({"o": f"n{nid}", "lat": round(lat, 7), "lon": round(lon, 7), "t": keep(tags)}, poi_f, ensure_ascii=False)
            poi_f.write("\n")
            stats["poi nodes"] += 1
    print(f"tagged nodes: {time.time() - t0:.1f} s, {dict(stats)}", flush=True)

    # ways: roads, POI areas, rings of relations, and for each spot the way through it
    t0 = time.time()
    spot_ids = set(spots)
    keep_ways = []  # (kind, id, refs array, tags)
    rel_way_refs = {}
    through = collections.defaultdict(list)
    need = set(extra_nodes)

    def way_wanted(wid, t):
        return b"highway" in t or wid in rel_ways or wanted_poi_bytes(t) or (b"place" in t and any(k in t for k in NAME_KEYS))

    for wid, refs, tags in r.ways(way_wanted):
        hw = tags.get("highway", "")
        if wid in rel_ways:
            rel_way_refs[wid] = array.array("q", refs)
            need.update(refs)
        road = hw.encode() in ROADS
        if road:
            stats["highway ways"] += 1
            for i, n in enumerate(refs):
                if n in spot_ids:
                    prev = refs[i - 1] if i > 0 else None
                    nxt = refs[i + 1] if i + 1 < len(refs) else None
                    through[n].append((hw, tags.get("oneway", ""), tags.get("junction", ""), tags.get("maxspeed", ""), prev, nxt))
                    if prev:
                        need.add(prev)
                    if nxt:
                        need.add(nxt)
        tb = {k.encode(): v.encode() for k, v in tags.items() if k in ("name", "name:ar", "name:en", "brand", "brand:ar", "brand:en", "building", "place", "amenity", "shop", "tourism", "leisure",
                                                                       "office", "healthcare", "craft", "historic", "aeroway", "railway", "public_transport", "man_made",
                                                                       "natural", "landuse", "military", "emergency", "highway")}
        if "place" in tags and any(k in tags for k in ("name", "name:ar", "name:en")):
            keep_ways.append(("place", wid, array.array("q", refs), keep(tags)))
            need.update(refs)
        elif road and (any(k in tags for k in ("name", "name:ar", "name:en", "ref"))):
            keep_ways.append(("road", wid, array.array("q", refs), keep(tags)))
            need.update(refs)
        elif not road and wanted_poi_bytes(tb):
            keep_ways.append(("poi", wid, array.array("q", refs), keep(tags)))
            need.update(refs)
    print(f"ways: {time.time() - t0:.1f} s, kept {len(keep_ways)}, spots on ways {len(through)}, node ids needed {len(need)}", flush=True)

    # where those nodes are
    t0 = time.time()
    want = np.array(sorted(need), dtype=np.int64)
    del need
    ids, lat7, lon7 = r.locations(want)
    del want
    order = np.argsort(ids)
    ids = ids[order]
    lat = lat7[order] / 1e7
    lon = lon7[order] / 1e7
    del lat7, lon7, order
    print(f"locations: {time.time() - t0:.1f} s, found {ids.size}", flush=True)

    def locate(refs):
        arr = np.frombuffer(refs, dtype=np.int64) if isinstance(refs, array.array) else np.array(refs, dtype=np.int64)
        if arr.size == 0:
            return []
        i = np.searchsorted(ids, arr)
        i[i >= ids.size] = ids.size - 1
        ok = ids[i] == arr
        return list(zip(lat[i[ok]].tolist(), lon[i[ok]].tolist()))

    def one(n):
        i = int(np.searchsorted(ids, n))
        if i < ids.size and ids[i] == n:
            return float(lat[i]), float(lon[i])
        return None

    # write the ways
    t0 = time.time()
    road_f = open(os.path.join(a.out, "osm-road.jsonl"), "w", encoding="utf-8")
    for kind, wid, refs, tags in keep_ways:
        pts = locate(refs)
        if not pts:
            stats["ways without points"] += 1
            continue
        if kind == "road":
            sampled = along(pts)
            json.dump({"o": f"w{wid}", "pts": [[round(p[0], 6), round(p[1], 6)] for p in sampled], "t": tags}, road_f, ensure_ascii=False)
            road_f.write("\n")
            stats["road ways"] += 1
        else:
            c = centroid(pts)
            f = poi_f if kind == "poi" else place_f
            json.dump({"o": f"w{wid}", "lat": round(c[0], 7), "lon": round(c[1], 7), "t": tags}, f, ensure_ascii=False)
            f.write("\n")
            stats[kind + " ways"] += 1
    road_f.close()
    del keep_ways
    for rid, outer, nodes, tags in rel_rows:
        pts = []
        for w in outer:
            refs = rel_way_refs.get(w)
            if refs is not None:
                pts.extend(locate(refs))
        if not pts:
            stats["relations without points"] += 1
            continue
        la = sum(p[0] for p in pts) / len(pts)
        lo = sum(p[1] for p in pts) / len(pts)
        json.dump({"o": f"r{rid}", "lat": round(la, 7), "lon": round(lo, 7), "t": tags}, poi_f, ensure_ascii=False)
        poi_f.write("\n")
        stats["poi relations"] += 1
    poi_f.close()
    place_f.close()

    # enforcement relations give a camera its direction and limit
    enf = {}
    for rid, mem, tags in enforcement:
        if tags.get("enforcement") not in ("maxspeed", "average_speed", "mindistance", None):
            stats["enforcement other: " + str(tags.get("enforcement"))] += 1
            continue
        dev = [m for m in mem if m[0] == 0 and m[2] == "device"]
        fr = [m for m in mem if m[0] == 0 and m[2] == "from"]
        to = [m for m in mem if m[0] == 0 and m[2] == "to"]
        d = None
        if dev and (fr or to):
            pd = one(dev[0][1])
            a0 = one(fr[0][1]) if fr else pd
            b0 = one(to[0][1]) if to else pd
            if a0 and b0 and a0 != b0:
                d = int(round(bearing(a0[0], a0[1], b0[0], b0[1]))) % 360
        for m in dev:
            enf[m[1]] = {"dir": d, "kmh": parse_kmh(tags.get("maxspeed")), "rel": rid}
            stats["enforcement devices"] += 1

    # the spots: direction from the tag, from a one-way road, or from the relation
    spot_f = open(os.path.join(a.out, "osm-spot.jsonl"), "w", encoding="utf-8")
    for nid, s in spots.items():
        t = s["t"]
        ws = through.get(nid, [])
        tagged = parse_direction(t.get("direction")) if s["k"] != "signal" else parse_direction(t.get("traffic_signals:direction") or t.get("direction"))
        d = None
        how = None

        def way_bearing(w):
            here = (s["lat"], s["lon"])
            p = one(w[4]) if w[4] else None
            n = one(w[5]) if w[5] else None
            if p and n:
                return bearing(p[0], p[1], n[0], n[1])
            if n:
                return bearing(here[0], here[1], n[0], n[1])
            if p:
                return bearing(p[0], p[1], here[0], here[1])
            return None

        if isinstance(tagged, int):
            d = tagged
            how = "tag"
        elif tagged in ("forward", "backward") and len(ws) >= 1:
            b = way_bearing(ws[0])
            if b is not None:
                d = int(round(b if tagged == "forward" else b + 180)) % 360
                how = "tag+way"
        elif nid in enf and enf[nid]["dir"] is not None:
            d = enf[nid]["dir"]
            how = "enforcement"
        elif len(ws) == 1 and (ws[0][1] in ("yes", "1", "true") or ws[0][0] in ("motorway", "motorway_link", "trunk_link") or ws[0][2] == "roundabout"):
            # on a single one-way road: the traffic it faces is that road's
            b = way_bearing(ws[0])
            if b is not None and ws[0][1] != "-1":
                d = int(round(b)) % 360
                how = "oneway"
        elif len(ws) == 2 and ws[0][1] in ("yes", "1", "true") and ws[1][1] in ("yes", "1", "true") and ws[0][4] is None and ws[1][5] is None:
            pass  # the joint of two one-way pieces: handled below
        if d is None and len(ws) == 2 and all(w[1] in ("yes", "1", "true") for w in ws):
            # the joint of two pieces of one one-way road: same direction if their bearings agree
            b0, b1 = way_bearing(ws[0]), way_bearing(ws[1])
            if b0 is not None and b1 is not None and abs((b0 - b1 + 180) % 360 - 180) < 35:
                d = int(round(b0)) % 360
                how = "oneway"
        kmh = parse_kmh(t.get("maxspeed"))
        if nid in enf and enf[nid]["kmh"]:
            kmh = kmh or enf[nid]["kmh"]
        if kmh is None:
            for w in ws:
                kmh = parse_kmh(w[3])
                if kmh:
                    break
        out = {"k": s["k"], "o": s["o"], "lat": s["lat"], "lon": s["lon"], "dir": d, "how": how, "kmh": kmh, "on": len(ws)}
        if s["k"] == "bump":
            out["sub"] = t.get("traffic_calming")
        json.dump(out, spot_f, ensure_ascii=False)
        spot_f.write("\n")
        stats[f"spot {s['k']} dir={'yes' if d is not None else 'no'}"] += 1
        if how:
            stats[f"spot {s['k']} how={how}"] += 1
        if kmh:
            stats[f"spot {s['k']} kmh"] += 1
    spot_f.close()
    stats["seconds"] = round(time.time() - t00, 1)
    with open(os.path.join(a.out, "osm-stats.json"), "w", encoding="utf-8") as f:
        json.dump(stats, f, ensure_ascii=False, indent=1, sort_keys=True)
    print("done", json.dumps(stats, ensure_ascii=False, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
