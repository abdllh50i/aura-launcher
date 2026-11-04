#!/usr/bin/env python
"""Probes of the things maps.md section 13 items 12 and 13 worry about, on real sites from find_sites.py.   python -I route_probes.py <url> <out dir> path|uturn|side|all [--top N]

path    For each trip flagged on distance: Valhalla's own time and cost on OSRM's line (trace_route) against its own choice. Cheaper on OSRM's line means Valhalla's search missed it;
        dearer means its costing prefers the other way on purpose.
uturn   U-turn gaps: start 250 m before the gap with the car's heading, end 250 m after it on the other carriageway. The shortest legal drive is known, about 500 m + the gap.
        What do Valhalla and OSRM do, and do they use the gap?
side    Frontage roads: (1) the car on the side road, with heading, 3-8 km trip ahead: which road does Valhalla snap to, how long is the route against starting on the main road;
        (2) the car between the two roads (a GPS error): which one wins; (3) can a request force the side road or the main road: search_filter, then exclude_locations (the "I am on the service road" button).
"""
import collections
import json
import math
import os
import random
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import route_report as rr  # noqa: E402
import route_trips  # noqa: E402
import run_route_trips as run  # noqa: E402
import trips  # noqa: E402

COSTING = {"top_speed": 160, "use_tolls": 0.5, "use_tracks": 0.0}
CITIES = [(27.0046, 49.6586), (26.4207, 50.0888), (26.29442, 50.22216), (26.2935, 50.11), (26.5586, 50.00874), (24.7136, 46.6753), (25.3697, 49.5865), (26.7074, 50.0674), (26.4686, 49.7968),
          (26.1748, 50.0665), (26.1862, 50.3290), (27.1358, 49.5667), (27.4742, 48.4789), (26.6484, 49.9467)]


def req(locs, costing=None, **kw):
    b = {"locations": locs, "costing": "auto", "costing_options": {"auto": dict(COSTING, **(costing or {}))}, "format": "osrm", "shape_format": "geojson", "units": "kilometers", "language": "en-US"}
    b.update(kw)
    return b


def ask(url, body, path="/route"):
    code, raw, ms = run.post(url, path, body)
    try:
        return code, json.loads(raw), ms
    except ValueError:
        return code, {"raw": raw[:200].decode("latin1")}, ms


def bearing(a, b):
    return math.degrees(math.atan2((b[1] - a[1]) * math.cos(math.radians(a[0])), b[0] - a[0])) % 360


def adiff(a, b):
    d = abs(a - b) % 360
    return 360 - d if d > 180 else d


def dist_to_line(pt, coords):
    """Metres from (lat, lon) to a [lon, lat] line, near enough."""
    k = math.cos(math.radians(pt[0])) * 111320.0
    m = 110574.0
    px, py = pt[1] * k, pt[0] * m
    best = 1e12
    for i in range(len(coords) - 1):
        ax, ay = coords[i][0] * k, coords[i][1] * m
        bx, by = coords[i + 1][0] * k, coords[i + 1][1] * m
        dx, dy = bx - ax, by - ay
        ll = dx * dx + dy * dy
        t = 0 if ll == 0 else max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / ll))
        best = min(best, math.hypot(px - ax - dx * t, py - ay - dy * t))
    return best


def spread(items, key, cell, limit, seed=7):
    """At most one site per grid cell, in random order, up to limit."""
    rnd = random.Random(seed)
    items = list(items)
    rnd.shuffle(items)
    seen = set()
    out = []
    for it in items:
        p = key(it)
        c = (int(p[0] / cell), int(p[1] / cell))
        if c in seen:
            continue
        seen.add(c)
        out.append(it)
        if len(out) >= limit:
            break
    return out


def probe_path(url, out):
    report = json.load(open(os.path.join(out, "report.json"), encoding="utf-8"))
    rows = []
    for t in report["trips"]:
        if "KM" not in t["flags"]:
            continue
        tid = t["id"]
        o = json.load(open(os.path.join(out, "osrm", tid + ".json"), encoding="utf-8"))["routes"][0]
        a, b = t["from"], t["to"]
        for name, top in (("top140", 140), ("top160", 160)):
            costing = {"top_speed": top, "use_tolls": 0.5, "use_tracks": 0.0}
            base = {"locations": [{"lat": a[0], "lon": a[1], "type": "break"}, {"lat": b[0], "lon": b[1], "type": "break"}], "costing": "auto", "costing_options": {"auto": costing}, "directions_type": "none"}
            code, doc, ms = ask(url, base)
            s = doc["trip"]["summary"]
            shape = [{"lat": c[1], "lon": c[0]} for c in o["geometry"]["coordinates"]]
            code2, doc2, ms2 = ask(url, {"shape": shape, "costing": "auto", "costing_options": {"auto": costing}, "shape_match": "map_snap", "directions_type": "none"}, "/trace_route")
            row = {"id": tid, "top": top, "chosen_km": round(s["length"], 2), "chosen_min": round(s["time"] / 60, 1), "chosen_cost": round(s["cost"], 0), "osrm_km": round(o["distance"] / 1000, 2), "osrm_min": round(o["duration"] / 60, 1)}
            if code2 == 200 and "trip" in doc2:
                s2 = doc2["trip"]["summary"]
                row.update(on_osrm_km=round(s2["length"], 2), on_osrm_min=round(s2["time"] / 60, 1), on_osrm_cost=round(s2["cost"], 0), legs=len(doc2["trip"]["legs"]))
                row["verdict"] = "search missed it" if s2["cost"] < s["cost"] * 0.995 else "costing prefers its own way"
            else:
                row["trace_error"] = doc2
            rows.append(row)
            print(json.dumps(row, ensure_ascii=True)[:300], flush=True)
    with open(os.path.join(out, "probe-path.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(rows, f, ensure_ascii=False, indent=0)


def probe_uturn(url, out, limit=60):
    sites = json.load(open(os.path.join(out, "sites.json"), encoding="utf-8"))["gaps"]
    sites = [g for g in sites if not g["banned"] and g["road_class"] in ("primary", "secondary", "tertiary", "trunk")]
    chosen = spread(sites, lambda g: g["at"], 0.012, limit)
    rows = []
    for g in chosen:
        a, b = g["from"], g["to"]
        loc = [{"lat": a[0], "lon": a[1], "type": "break", "heading": g["heading"], "heading_tolerance": 45}, {"lat": b[0], "lon": b[1], "type": "break"}]
        code, doc, ms = ask(url, req(loc))
        row = {k: g[k] for k in ("gap_way", "gap_class", "gap_m", "at", "road", "road_class", "from", "to", "heading", "ideal_m")}
        if doc.get("code") == "Ok":
            r = doc["routes"][0]
            c = r["geometry"]["coordinates"]
            row.update(v_m=round(r["distance"]), v_s=round(r["duration"]), v_used_gap=dist_to_line(g["at"], c) < 25, v_uturns=sum(1 for leg in r["legs"] for s in leg["steps"] if s["maneuver"].get("modifier") == "uturn"),
                       v_snap=[round(w.get("distance", -1), 1) for w in doc["waypoints"]])
        else:
            row["v_error"] = doc.get("code")
        try:
            c3, raw3 = run.osrm(a, b, f"&bearings={g['heading']},45;")
            o = json.loads(raw3)
            if o.get("code") == "Ok":
                r = o["routes"][0]
                row.update(o_m=round(r["distance"]), o_s=round(r["duration"]), o_used_gap=dist_to_line(g["at"], r["geometry"]["coordinates"]) < 25)
            else:
                row["o_error"] = o.get("code")
        except Exception as e:
            row["o_error"] = str(e)
        rows.append(row)
        print(json.dumps({k: row.get(k) for k in ("at", "road_class", "gap_class", "ideal_m", "v_m", "v_used_gap", "o_m", "o_used_gap")}, ensure_ascii=True), flush=True)
    ok = [r for r in rows if "v_m" in r]
    both = [r for r in ok if "o_m" in r]
    extra = [r["v_m"] - r["ideal_m"] for r in ok]
    summary = {"sites": len(rows), "valhalla answered": len(ok), "valhalla used the gap": sum(1 for r in ok if r["v_used_gap"]), "osrm used the gap": sum(1 for r in both if r["o_used_gap"]),
               "median extra m over the ideal, valhalla": statistics.median(extra) if extra else None, "extra over 1 km": sum(1 for x in extra if x > 1000), "extra over 3 km": sum(1 for x in extra if x > 3000),
               "valhalla longer than osrm by over 300 m": [r["at"] for r in both if r["v_m"] - r["o_m"] > 300], "osrm longer than valhalla by over 300 m": [r["at"] for r in both if r["o_m"] - r["v_m"] > 300],
               "median v_m/o_m": statistics.median(r["v_m"] / max(r["o_m"], 1) for r in both) if both else None}
    with open(os.path.join(out, "probe-uturn.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"summary": summary, "rows": rows}, f, ensure_ascii=False, indent=0)
    print(json.dumps(summary, ensure_ascii=True))


def probe_uturn_all(url, out, worst_for_osrm=12):
    """Every gap found, Valhalla only; then OSRM (at most one request a second) on the worst ones, as a check."""
    sites = json.load(open(os.path.join(out, "sites.json"), encoding="utf-8"))["gaps"]
    rows = []
    for g in sites:
        a, b = g["from"], g["to"]
        loc = [{"lat": a[0], "lon": a[1], "type": "break", "heading": g["heading"], "heading_tolerance": 45}, {"lat": b[0], "lon": b[1], "type": "break"}]
        code, doc, ms = ask(url, req(loc))
        row = {k: g[k] for k in ("gap_way", "gap_class", "gap_m", "at", "road", "road_class", "from", "to", "heading", "ideal_m", "banned")}
        if doc.get("code") == "Ok":
            r = doc["routes"][0]
            row.update(v_m=round(r["distance"]), v_s=round(r["duration"]), v_used_gap=dist_to_line(g["at"], r["geometry"]["coordinates"]) < 25)
        else:
            row["v_error"] = doc.get("code")
        rows.append(row)
    ok = [r for r in rows if "v_m" in r]
    free = [r for r in ok if not r["banned"]]
    extra = sorted(r["v_m"] - r["ideal_m"] for r in free)
    worst = sorted(free, key=lambda r: r["ideal_m"] - r["v_m"])[:worst_for_osrm]
    for r in worst:
        try:
            c3, raw3 = run.osrm(r["from"], r["to"], f"&bearings={r['heading']},45;")
            o = json.loads(raw3)
            if o.get("code") == "Ok":
                rr3 = o["routes"][0]
                r.update(o_m=round(rr3["distance"]), o_s=round(rr3["duration"]), o_used_gap=dist_to_line(r["at"], rr3["geometry"]["coordinates"]) < 25)
        except Exception as e:
            r["o_error"] = str(e)
    summary = {"sites": len(rows), "answered": len(ok), "not banned by a turn restriction": len(free), "used the gap": sum(1 for r in free if r["v_used_gap"]),
               "extra m over the ideal: median, p90, p99, worst": [extra[len(extra) // 2], extra[int(len(extra) * .9)], extra[int(len(extra) * .99)], extra[-1]] if extra else None,
               "extra over 300 m": sum(1 for x in extra if x > 300), "over 1 km": sum(1 for x in extra if x > 1000), "over 3 km": sum(1 for x in extra if x > 3000), "over 5 km": sum(1 for x in extra if x > 5000),
               "worst": [{k: r.get(k) for k in ("at", "from", "to", "heading", "road", "road_class", "gap_class", "gap_m", "ideal_m", "v_m", "v_s", "v_used_gap", "o_m", "o_s", "o_used_gap")} for r in worst]}
    with open(os.path.join(out, "probe-uturn-all.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"summary": summary, "rows": rows}, f, ensure_ascii=False, indent=0)
    print(json.dumps(summary, ensure_ascii=True)[:3000])


def near_side(snap, side, main):
    """Which of the two parallel roads a snapped [lon, lat] is on: nearer the side point's line or the main's. The two points are one road-width apart, opposite each other."""
    return "side" if rr.hav(snap, (side[1], side[0])) <= rr.hav(snap, (main[1], main[0])) else "main"


def probe_side(url, out, limit=40):
    sites = json.load(open(os.path.join(out, "sites.json"), encoding="utf-8"))["frontage"]
    sites = [s for s in sites if s["main_class"] in ("motorway", "trunk", "primary") and s["side_class"] in ("secondary", "tertiary", "unclassified", "service", "residential", "primary") and RANK[s["side_class"]] > RANK[s["main_class"]]]
    chosen = spread(sites, lambda s: s["on_main"], 0.015, limit)
    rows = []
    for s in chosen:
        side, main = s["on_side"], s["on_main"]
        head = s["heading"]
        mid = [(side[0] + main[0]) / 2, (side[1] + main[1]) / 2]
        # the nearest town roughly ahead, at least 3 km off
        cand = [(rr.hav((side[1], side[0]), (c[1], c[0])), c) for c in CITIES if adiff(bearing(side, c), head) < 60 and rr.hav((side[1], side[0]), (c[1], c[0])) > 3000]
        row = {k: s[k] for k in ("main_class", "main_name", "side_class", "side_name", "side_oneway", "run_m", "gap_m", "on_main", "on_side", "heading")}
        if not cand:
            row["skipped"] = "no town ahead"
            rows.append(row)
            continue
        dest = min(cand)[1]
        to = {"lat": dest[0], "lon": dest[1], "type": "break"}
        h = {"type": "break", "heading": head, "heading_tolerance": 45}
        results = {}
        for name, loc, kw in (
            ("on_side", {"lat": side[0], "lon": side[1], **h}, {}),
            ("on_main", {"lat": main[0], "lon": main[1], **h}, {}),
            ("between", {"lat": mid[0], "lon": mid[1], **h}, {}),
            ("between+filter", {"lat": mid[0], "lon": mid[1], **h, "search_filter": {"max_road_class": CLASS[s["side_class"]]}}, {}),
            ("between+filter_main", {"lat": mid[0], "lon": mid[1], **h, "search_filter": {"min_road_class": CLASS[s["main_class"]]}}, {}),
            ("between+exclude_main", {"lat": mid[0], "lon": mid[1], **h}, {"exclude_locations": [{"lat": main[0], "lon": main[1]}]}),
            ("between+exclude_side", {"lat": mid[0], "lon": mid[1], **h}, {"exclude_locations": [{"lat": side[0], "lon": side[1]}]}),
        ):
            code, doc, ms = ask(url, req([loc, to], **kw))
            if doc.get("code") == "Ok":
                r = doc["routes"][0]
                snap = doc["waypoints"][0]["location"]
                steps = [x for leg in r["legs"] for x in leg["steps"]]
                results[name] = {"road": near_side(snap, side, main), "snap_m": round(doc["waypoints"][0].get("distance", -1), 1), "name": doc["waypoints"][0].get("name", ""), "km": round(r["distance"] / 1000, 2), "min": round(r["duration"] / 60, 1), "uturns": sum(1 for x in steps if x["maneuver"].get("modifier") == "uturn")}
            else:
                results[name] = {"error": doc.get("code"), "http": code, "message": str(doc.get("message"))[:80]}
        row["to"] = dest
        row["results"] = results
        rows.append(row)
        print(json.dumps({"at": side, "side": s["side_class"], "main": s["main_class"], "gap": s["gap_m"], **{k: (v.get("road"), v.get("km")) for k, v in results.items()}}, ensure_ascii=True), flush=True)
    ok = [r for r in rows if "results" in r]
    c = collections.defaultdict(collections.Counter)
    for r in ok:
        for k, v in r["results"].items():
            c[k][v.get("road", "error")] += 1
    extra = [r["results"]["on_side"]["km"] - r["results"]["on_main"]["km"] for r in ok if "km" in r["results"]["on_side"] and "km" in r["results"]["on_main"]]
    summary = {"sites": len(rows), "tested": len(ok), "which road each request snapped to": {k: dict(v) for k, v in c.items()},
               "km more when starting on the side road than on the main road: median, worst, over 2 km": [statistics.median(extra) if extra else None, max(extra) if extra else None, sum(1 for x in extra if x > 2)],
               "km less when starting on the side road": min(extra) if extra else None}
    with open(os.path.join(out, "probe-side.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"summary": summary, "rows": rows}, f, ensure_ascii=False, indent=0)
    print(json.dumps(summary, ensure_ascii=True))


RANK = {"motorway": 0, "trunk": 1, "primary": 2, "secondary": 3, "tertiary": 4, "unclassified": 5, "residential": 6, "service": 7, "living_street": 8}
CLASS = {"motorway": "motorway", "trunk": "trunk", "primary": "primary", "secondary": "secondary", "tertiary": "tertiary", "unclassified": "unclassified", "residential": "residential", "service": "service_other", "living_street": "residential"}


def main():
    url, out, what = sys.argv[1], sys.argv[2], sys.argv[3]
    if what in ("path", "all"):
        probe_path(url, out)
    if what in ("uturn", "all"):
        probe_uturn(url, out)
    if what in ("uturn-all", "all"):
        probe_uturn_all(url, out)
    if what in ("side", "all"):
        probe_side(url, out)


if __name__ == "__main__":
    main()
