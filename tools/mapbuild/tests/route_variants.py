#!/usr/bin/env python
"""Asks Valhalla for every trip under several costing options and compares with the default request.

    python -I route_variants.py <valhalla url> <out dir> <name=json options> ...      for example top252={"top_speed":252}

Keeps <out>/var-<name>/<id>.json (OSRM format, like the app's request) and writes <out>/variants.tsv: km, minutes, ms, steps told, U-turns, the longest loop, for each option.
The options go inside costing_options.auto on top of trips.request.
"""
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import route_report as rr  # noqa: E402
import route_trips  # noqa: E402
import run_route_trips as run  # noqa: E402
import trips  # noqa: E402

QUIET = {"new name", "continue", "notification", "use lane"}


def facts(doc):
    if doc.get("code") != "Ok":
        return {"error": str(doc.get("code"))}
    r = doc["routes"][0]
    steps = [s for leg in r["legs"] for s in leg["steps"]]
    told = 0
    for i, s in enumerate(steps):
        m = s["maneuver"]
        t = m.get("type", "")
        mod = m.get("modifier", "")
        if t in ("depart", "arrive"):
            continue
        if t in QUIET and mod in ("", "straight"):
            continue
        if t.startswith("exit ") and i and steps[i - 1]["maneuver"].get("type") in ("roundabout", "rotary"):
            continue
        told += 1
    loop = rr.biggest_loop(rr.resample(rr.line_of(r)))
    return {"km": round(r["distance"] / 1000, 2), "min": round(r["duration"] / 60, 1), "told": told, "uturns": sum(1 for s in steps if s["maneuver"].get("modifier") == "uturn"),
            "off_ramps": sum(1 for s in steps if s["maneuver"].get("type") == "off ramp"), "loop": loop[0], "routes": len(doc["routes"])}


def main():
    url, out = sys.argv[1], sys.argv[2]
    variants = [("default", {})]
    for spec in sys.argv[3:]:
        name, _, js = spec.partition("=")
        variants.append((name, json.loads(js)))
    rows = []
    totals = {name: {"km": 0.0, "min": 0.0, "ms": 0.0, "told": 0, "uturns": 0, "loops": 0} for name, _ in variants}
    for tid, group, label, a, b in route_trips.TRIPS:
        row = {"id": tid}
        for name, opt in variants:
            body = trips.request(a, b)
            body["costing_options"]["auto"].update(opt)
            code, raw, ms = run.post(url, "/route", body)
            code, raw, ms2 = run.post(url, "/route", body)
            d = os.path.join(out, "var-" + name)
            os.makedirs(d, exist_ok=True)
            with open(os.path.join(d, tid + ".json"), "wb") as f:
                f.write(raw)
            fa = facts(json.loads(raw))
            fa["ms"] = round(min(ms, ms2))
            row[name] = fa
            t = totals[name]
            if "km" in fa:
                t["km"] += fa["km"]
                t["min"] += fa["min"]
                t["ms"] += fa["ms"]
                t["told"] += fa["told"]
                t["uturns"] += fa["uturns"]
                t["loops"] += 1 if fa["loop"] else 0
        rows.append(row)
        print(tid.ljust(16), " | ".join(f"{n}: {row[n].get('km')} km {row[n].get('min')} min {row[n].get('ms')} ms told {row[n].get('told')} U {row[n].get('uturns')} loop {row[n].get('loop')}" for n, _ in variants).encode("ascii", "replace").decode(), flush=True)
    with open(os.path.join(out, "variants.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"totals": totals, "rows": rows}, f, indent=0)
    print(json.dumps(totals))


if __name__ == "__main__":
    main()
