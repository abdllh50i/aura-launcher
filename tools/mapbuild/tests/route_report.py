#!/usr/bin/env python
"""Reads what run_route_trips.py kept and says what differs.   python -I route_report.py <out dir>

Writes <out>/report.json and <out>/report.tsv. For each trip: both engines' km and minutes, FLAG at more than 15 % in either; how much of each line runs on
the other (within 30 m); the longest stretch that comes back to where it was (a detour around a median shows here); the snap distances; U-turns.
Over all trips: every manoeuvre type and modifier; roundabout exit numbers compared where both engines leave by the same exit; time by road class.
OSRM is a yardstick, not the road.
"""
import collections
import json
import math
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import route_trips  # noqa: E402


def hav(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[1], a[0], b[1], b[0]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371008 * math.asin(min(1.0, math.sqrt(h)))


def resample(line, step=20.0):
    """Points every `step` metres, with their distance along the line."""
    out = [(line[0][0], line[0][1], 0.0)]
    along = 0.0
    left = step
    for i in range(len(line) - 1):
        a, b = line[i], line[i + 1]
        d = hav(a, b)
        if d <= 0:
            continue
        pos = 0.0
        while d - pos >= left:
            pos += left
            f = pos / d
            out.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, along + pos))
            left = step
        left -= d - pos
        along += d
    out.append((line[-1][0], line[-1][1], along))
    return out


class Near:
    """Is a point within r metres of a line? A hash of the resampled line."""

    def __init__(self, pts, r):
        self.r = r
        self.cell = max(r, 30.0) / 90000.0 * 1.2
        self.g = collections.defaultdict(list)
        for p in pts:
            self.g[(int(p[0] / self.cell), int(p[1] / self.cell))].append(p)

    def near(self, p):
        cx, cy = int(p[0] / self.cell), int(p[1] / self.cell)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for q in self.g.get((cx + dx, cy + dy), ()):
                    if hav(p, q) <= self.r:
                        yield q


def overlap(a, b, r=30.0):
    """Share of line a's length within r of line b, and the box of what is not."""
    n = Near(b, r)
    hit = 0
    box = None
    far = []
    for p in a:
        if next(n.near(p), None) is not None:
            hit += 1
        else:
            far.append(p)
    if far:
        box = [min(p[0] for p in far), min(p[1] for p in far), max(p[0] for p in far), max(p[1] for p in far)]
    return hit / max(1, len(a)), box, far


def biggest_loop(pts, r=60.0, ignore=400.0):
    """The longest stretch of the drive that ends within r metres of where it began, ignoring ring roads' small loops: what a U-turn detour costs."""
    n = Near(pts, r)
    best = (0.0, None)
    for p in pts:
        for q in n.near(p):
            d = q[2] - p[2]
            if d > best[0]:
                best = (d, (round(p[1], 5), round(p[0], 5)))
    return (round(best[0]), best[1]) if best[0] >= ignore else (0, None)


def line_of(route):
    return [tuple(c) for c in route["geometry"]["coordinates"]]


def roundabouts(route):
    """(where the ring is entered, where it is left, exit number) for each ring driven."""
    steps = [s for leg in route["legs"] for s in leg["steps"]]
    out = []
    for i, s in enumerate(steps):
        m = s["maneuver"]
        if m.get("type") in ("roundabout", "rotary"):
            nxt = steps[i + 1]["maneuver"] if i + 1 < len(steps) else None
            if nxt and nxt.get("type", "").startswith("exit "):
                out.append((m["location"], nxt["location"], m.get("exit", 0)))
    return out


def load(path):
    try:
        return json.load(open(path, encoding="utf-8"))
    except FileNotFoundError:
        return None


def arrival_side(route, b):
    """Which side of the last stretch the place asked for lies on, measured: 'left', 'right' or '' when it is on the line (under 5 m)."""
    c = route["geometry"]["coordinates"]
    if len(c) < 2:
        return ""
    end = c[-1]
    if hav(end, (b[1], b[0])) < 5:
        return ""
    i = len(c) - 2
    while i > 0 and hav(c[i], end) < 8:
        i -= 1
    k = math.cos(math.radians(end[1]))
    dx, dy = (end[0] - c[i][0]) * k, end[1] - c[i][1]
    px, py = (b[1] - end[0]) * k, b[0] - end[1]
    return "left" if dx * py - dy * px > 0 else "right"


def main():
    out = sys.argv[1]
    vfolder = sys.argv[2] if len(sys.argv) > 2 else "valhalla"
    tag = "" if vfolder == "valhalla" else "-" + vfolder
    rows = []
    alt_better = []
    side_ok = side_bad = side_none = 0
    side_cases = []
    types = collections.Counter()
    pairs = collections.Counter()
    osrm_pairs = collections.Counter()
    ring_same = ring_diff = 0
    ring_cases = []
    cls_km = collections.Counter()
    cls_s = collections.Counter()
    cls_limit = collections.Counter()
    cls_at_limit = collections.Counter()
    speed_hist = collections.defaultdict(collections.Counter)
    for tid, group, label, a, b in route_trips.TRIPS:
        v = load(os.path.join(out, vfolder, tid + ".json"))
        o = load(os.path.join(out, "osrm", tid + ".json"))
        e = load(os.path.join(out, "edges", tid + ".json")) if vfolder == "valhalla" else None
        if v and v.get("code") == "Ok":
            r0 = v["routes"][0]
            for ai, alt in enumerate(v["routes"][1:], 1):
                if alt["duration"] < r0["duration"] * 0.97:
                    alt_better.append({"trip": tid, "first": [round(r0["distance"] / 1000, 1), round(r0["duration"] / 60, 1)], "alternate": [round(alt["distance"] / 1000, 1), round(alt["duration"] / 60, 1)]})
            said = next((s["maneuver"].get("modifier", "") for leg in r0["legs"] for s in leg["steps"] if s["maneuver"].get("type") == "arrive"), "")
            meas = arrival_side(r0, b)
            if not meas or not said or said == "straight":
                side_none += 1
            elif said == meas:
                side_ok += 1
            else:
                side_bad += 1
                side_cases.append({"trip": tid, "said": said, "measured": meas, "snap_m": round(v["waypoints"][-1].get("distance", -1))})
        row = {"id": tid, "group": group, "label": label, "from": a, "to": b, "flags": []}
        rows.append(row)
        if not v or v.get("code") != "Ok":
            row["valhalla_error"] = v
        if not o or o.get("code") != "Ok":
            row["osrm_error"] = {k: o.get(k) for k in ("code", "message")} if o else "no answer"
        if v and v.get("code") == "Ok":
            r = v["routes"][0]
            row.update(v_km=round(r["distance"] / 1000, 2), v_min=round(r["duration"] / 60, 1), v_routes=len(v["routes"]),
                       v_snap=[round(w.get("distance", -1), 1) for w in v["waypoints"]], v_warn=[w.get("code") for w in v.get("warnings", [])])
            row["alts"] = [[round(x["distance"] / 1000, 1), round(x["duration"] / 60, 1)] for x in v["routes"][1:]]
            for rr in v["routes"]:
                for leg in rr["legs"]:
                    for s in leg["steps"]:
                        m = s["maneuver"]
                        types[m.get("type", "")] += 1
                        pairs[(m.get("type", ""), m.get("modifier", ""))] += 1
            steps = [s for leg in r["legs"] for s in leg["steps"]]
            row["v_uturns"] = sum(1 for s in steps if s["maneuver"].get("modifier") == "uturn")
            row["v_rings"] = sum(1 for s in steps if s["maneuver"].get("type") in ("roundabout", "rotary"))
            row["v_modes"] = sorted({s.get("mode", "") for s in steps})
            vp = resample(line_of(r))
            row["v_loop"] = biggest_loop(vp)
        if o and o.get("code") == "Ok":
            r2 = o["routes"][0]
            row.update(o_km=round(r2["distance"] / 1000, 2), o_min=round(r2["duration"] / 60, 1), o_snap=[round(w.get("distance", -1), 1) for w in o["waypoints"]])
            steps2 = [s for leg in r2["legs"] for s in leg["steps"]]
            for s in steps2:
                m = s["maneuver"]
                osrm_pairs[(m.get("type", ""), m.get("modifier", ""))] += 1
            row["o_uturns"] = sum(1 for s in steps2 if s["maneuver"].get("modifier") == "uturn")
            row["o_rings"] = sum(1 for s in steps2 if s["maneuver"].get("type") in ("roundabout", "rotary"))
            op = resample(line_of(r2))
            row["o_loop"] = biggest_loop(op)
            if v and v.get("code") == "Ok":
                row["dkm"] = round(100 * (row["v_km"] - row["o_km"]) / row["o_km"], 1) if row["o_km"] else None
                row["dmin"] = round(100 * (row["v_min"] - row["o_min"]) / row["o_min"], 1) if row["o_min"] else None
                if row["dkm"] is not None and abs(row["dkm"]) > 15:
                    row["flags"].append("KM")
                if row["dmin"] is not None and abs(row["dmin"]) > 15:
                    row["flags"].append("TIME")
                s1, box1, far1 = overlap(vp, op)
                s2, box2, far2 = overlap(op, vp)
                row["v_on_o"] = round(s1, 3)
                row["o_on_v"] = round(s2, 3)
                boxes = [x for x in (box1, box2) if x]
                if boxes:
                    row["diff_box"] = [round(min(x[0] for x in boxes), 5), round(min(x[1] for x in boxes), 5), round(max(x[2] for x in boxes), 5), round(max(x[3] for x in boxes), 5)]
                ro = roundabouts(r2)
                for en, ex, n in roundabouts(r):
                    for en2, ex2, n2 in ro:
                        if hav(en, en2) < 40 and hav(ex, ex2) < 40:
                            if n == n2:
                                ring_same += 1
                            else:
                                ring_diff += 1
                                ring_cases.append({"trip": tid, "at": [en[1], en[0]], "exit": [en[1], en[0]], "valhalla": n, "osrm": n2})
                            break
        if e and e.get("edges"):
            total = sec = 0.0
            for ed in e["edges"]:
                km = ed.get("length", 0.0)
                sp = ed.get("speed", 0) or 0
                c = ed.get("road_class", "?")
                cls_km[c] += km
                if sp > 0:
                    cls_s[c] += km / sp * 3600
                    sec += km / sp * 3600
                    speed_hist[c][int(sp // 10 * 10)] += km
                lim = ed.get("speed_limit")
                if isinstance(lim, (int, float)) and lim:
                    cls_limit[c] += km
                    if sp >= lim:
                        cls_at_limit[c] += km
                total += km
            row["edges_km"] = round(total, 2)
            row["edge_only_min"] = round(sec / 60, 1)
            row["transition_min"] = round(row.get("v_min", 0) - sec / 60, 1)
            row["limit_share"] = round(sum(ed.get("length", 0) for ed in e["edges"] if isinstance(ed.get("speed_limit"), (int, float)) and ed.get("speed_limit")) / max(total, 1e-6), 3)
    dk = [r["dkm"] for r in rows if r.get("dkm") is not None]
    dm = [r["dmin"] for r in rows if r.get("dmin") is not None]
    groups = collections.defaultdict(list)
    for r in rows:
        if r.get("dmin") is not None:
            groups[r["group"]].append(r)
    summary = {
        "trips": len(rows), "with both answers": len(dk), "flagged": sum(1 for r in rows if r["flags"]),
        "flagged KM": [r["id"] for r in rows if "KM" in r["flags"]], "flagged TIME": [r["id"] for r in rows if "TIME" in r["flags"]],
        "median dkm %": statistics.median(dk) if dk else None, "median dmin %": statistics.median(dm) if dm else None,
        "by group: n, median dkm, median dmin, sum v_min / sum o_min": {g: [len(x), statistics.median(y["dkm"] for y in x), statistics.median(y["dmin"] for y in x),
                                                                         round(sum(y["v_min"] for y in x) / max(sum(y["o_min"] for y in x), 0.1), 3)] for g, x in groups.items()},
        "valhalla maneuver types": dict(types.most_common()), "valhalla type+modifier": {f"{k[0]} | {k[1]}": n for k, n in sorted(pairs.items())},
        "osrm type+modifier": {f"{k[0]} | {k[1]}": n for k, n in sorted(osrm_pairs.items())},
        "roundabouts both engines leave by the same exit": {"same number": ring_same, "different number": ring_diff, "cases": ring_cases},
        "road class: km, Valhalla mean km/h, share with a known limit, share at the limit or above": {
            c: [round(cls_km[c], 1), round(cls_km[c] / cls_s[c] * 3600, 1) if cls_s[c] else None, round(cls_limit[c] / cls_km[c], 3) if cls_km[c] else None,
                round(cls_at_limit[c] / cls_limit[c], 3) if cls_limit[c] else None] for c in sorted(cls_km, key=lambda k: -cls_km[k])},
        "km by class and speed band": {c: {str(k): round(v, 1) for k, v in sorted(h.items())} for c, h in speed_hist.items()},
        "alternates over 3 % faster than the first route": alt_better,
        "arrival side said against measured": {"same": side_ok, "different": side_bad, "not said or on the line": side_none, "cases": side_cases},
    }
    with open(os.path.join(out, f"report{tag}.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"summary": summary, "trips": rows}, f, ensure_ascii=False, indent=1)
    keys = ("id", "group", "v_km", "o_km", "dkm", "v_min", "o_min", "dmin", "flags", "v_on_o", "o_on_v", "v_loop", "o_loop", "v_uturns", "o_uturns", "v_rings", "o_rings", "v_snap", "o_snap", "v_warn", "edge_only_min", "transition_min", "limit_share", "v_modes")
    with open(os.path.join(out, f"report{tag}.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\t".join(keys) + "\n")
        for r in rows:
            f.write("\t".join(json.dumps(r.get(k, ""), ensure_ascii=False) if isinstance(r.get(k), (list, dict)) else str(r.get(k, "")) for k in keys) + "\n")
    print(json.dumps(summary, ensure_ascii=True)[:4000])


if __name__ == "__main__":
    main()
