#!/usr/bin/env python
"""Is Valhalla's time believable, and what scale would bring it to the yardstick?   python -I route_eta.py <out dir> <places.db> [answers folder, default var-top160]

No ground truth exists here: nobody has logged a real drive. OSRM's time is the only other number, and nav.md's Jubail to Dammam "about 59 min" is OSRM's and an online Valhalla's.
So this only says: on trips where both engines drive the same road (each line at least 85 % on the other), what multiplier on Valhalla's step durations, by the step's own speed, matches OSRM.
The scale uses only what the app has in hand: step distance and step duration. Signals are counted from table spot (kind 2) within 25 m of the line; neither engine charges much for them.
Writes <out>/eta.json.
"""
import json
import os
import sqlite3
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import route_report as rr  # noqa: E402
import route_trips  # noqa: E402

BANDS = [(110, 999, "110+"), (85, 110, "85-110"), (60, 85, "60-85"), (35, 60, "35-60"), (0, 35, "under 35")]


def band_times(route):
    t = [0.0] * len(BANDS)
    km = [0.0] * len(BANDS)
    for leg in route["legs"]:
        for s in leg["steps"]:
            if not s.get("duration") or s.get("mode") == "ferry":
                continue
            v = s["distance"] / s["duration"] * 3.6
            for i, (lo, hi, _) in enumerate(BANDS):
                if lo <= v < hi:
                    t[i] += s["duration"] / 60
                    km[i] += s["distance"] / 1000
                    break
    return t, km


def fit(xs, ys, iters=2000):
    """Non-negative least squares by coordinate descent, weights 1/y so short trips count like long ones."""
    n = len(xs[0])
    k = [1.0] * n
    for _ in range(iters):
        for j in range(n):
            num = den = 0.0
            for x, y in zip(xs, ys):
                w = 1.0 / max(y, 1.0) ** 2
                r = y - sum(k[i] * x[i] for i in range(n) if i != j)
                num += w * x[j] * r
                den += w * x[j] * x[j]
            if den > 0:
                k[j] = max(0.0, num / den)
    return k


def main():
    out, db = sys.argv[1], sys.argv[2]
    folder = sys.argv[3] if len(sys.argv) > 3 else "var-top160"
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    signals = [(lat / 1e6, lon / 1e6) for lat, lon in con.execute("select lat, lon from spot where kind=2")]
    sig_near = rr.Near([(lon, lat, 0.0) for lat, lon in signals], 25.0)
    rows = []
    for tid, group, label, a, b in route_trips.TRIPS:
        if group == "gated" and tid == "gate-jana":
            continue
        if tid == "ferry":
            continue
        v = json.load(open(os.path.join(out, folder, tid + ".json"), encoding="utf-8"))
        o = json.load(open(os.path.join(out, "osrm", tid + ".json"), encoding="utf-8"))
        if v.get("code") != "Ok" or o.get("code") != "Ok":
            continue
        rv, ro = v["routes"][0], o["routes"][0]
        vp, op = rr.resample(rr.line_of(rv)), rr.resample(rr.line_of(ro))
        s1 = rr.overlap(vp, op)[0]
        s2 = rr.overlap(op, vp)[0]
        t, km = band_times(rv)
        seen = set()
        for p in vp[::2]:
            for q in sig_near.near(p):
                seen.add((q[0], q[1]))
        rows.append({"id": tid, "group": group, "v_min": rv["duration"] / 60, "o_min": ro["duration"] / 60, "v_km": rv["distance"] / 1000, "o_km": ro["distance"] / 1000, "same": s1 >= .85 and s2 >= .85,
                     "t": t, "bandkm": km, "signals": len(seen), "same1": round(s1, 3), "same2": round(s2, 3)})
    same = [r for r in rows if r["same"]]
    k = fit([r["t"] for r in same], [r["o_min"] for r in same])
    for r in rows:
        r["scaled_min"] = sum(ki * ti for ki, ti in zip(k, r["t"]))
        r["raw_err"] = 100 * (r["v_min"] - r["o_min"]) / r["o_min"]
        r["scaled_err"] = 100 * (r["scaled_min"] - r["o_min"]) / r["o_min"]
    def stats(rs, key):
        v = sorted(abs(r[key]) for r in rs)
        return {"median abs %": round(statistics.median(v), 1), "p90 abs %": round(v[int(len(v) * .9)], 1), "worst abs %": round(v[-1], 1), "median signed %": round(statistics.median(r[key] for r in rs), 1)}
    groups = {}
    for g in sorted({r["group"] for r in rows}):
        rs = [r for r in rows if r["group"] == g]
        groups[g] = {"n": len(rs), "raw": stats(rs, "raw_err"), "scaled": stats(rs, "scaled_err")}
    v_speed = {}
    for i, (_, _, name) in enumerate(BANDS):
        tt = sum(r["t"][i] for r in rows)
        kk = sum(r["bandkm"][i] for r in rows)
        v_speed[name] = {"km": round(kk, 1), "minutes": round(tt, 1), "mean km/h": round(kk / tt * 60, 1) if tt else None, "scale": round(k[i], 3)}
    report = {"answers": folder, "trips": len(rows), "same road": len(same), "scale by step speed": v_speed, "same-road trips, raw": stats(same, "raw_err"), "same-road trips, scaled": stats(same, "scaled_err"),
              "all trips, raw": stats(rows, "raw_err"), "all trips, scaled": stats(rows, "scaled_err"), "by group": groups,
              "signals on the line per km, city groups": {g: round(sum(r["signals"] for r in rows if r["group"] == g) / sum(r["v_km"] for r in rows if r["group"] == g), 2) for g in sorted({r["group"] for r in rows})},
              "rows": [{k2: (round(v2, 1) if isinstance(v2, float) else v2) for k2, v2 in r.items() if k2 not in ("t", "bandkm")} for r in rows]}
    with open(os.path.join(out, "eta.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(report, f, ensure_ascii=False, indent=0)
    print(json.dumps({k2: report[k2] for k2 in ("answers", "trips", "same road", "scale by step speed", "same-road trips, raw", "same-road trips, scaled", "all trips, raw", "all trips, scaled", "signals on the line per km, city groups")}, ensure_ascii=True))
    for r in rows:
        if r["id"] in ("jubail-dammam", "dammam-riyadh", "riyadh-jeddah", "dam-khb", "ruh-air", "j6", "hofuf-dammam", "abha-riyadh", "qatif", "d4"):
            print(r["id"], round(r["v_km"], 1), "raw", round(r["v_min"], 1), "osrm", round(r["o_min"], 1), "scaled", round(r["scaled_min"], 1), "signals", r["signals"], "same", r["same"])


if __name__ == "__main__":
    main()
