#!/usr/bin/env python
"""Which costing should the app ask for? Each option over all trips and all U-turn gaps, on Valhalla only.   python -I route_sweep.py <url> <out dir> name=json ...

For each option: how many trips are over 15 % longer than OSRM's kept answer, total km and minutes, Valhalla's minutes on them, told steps, U-turns, loops; and over the gaps of find_sites.py how far past the ideal it drives.
Writes <out>/sweep.json. OSRM is not asked again: the kept answers in <out>/osrm are used.
"""
import json
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import route_probes as rp  # noqa: E402
import route_trips  # noqa: E402
import route_variants as rv  # noqa: E402
import run_route_trips as run  # noqa: E402
import trips  # noqa: E402


def main():
    url, out = sys.argv[1], sys.argv[2]
    variants = [s.split("=", 1) for s in sys.argv[3:]]
    gaps = [g for g in json.load(open(os.path.join(out, "sites.json"), encoding="utf-8"))["gaps"] if not g["banned"]]
    result = {}
    per_trip = {}
    for name, js in variants:
        opt = json.loads(js)
        rows = []
        for tid, group, label, a, b in route_trips.TRIPS:
            body = trips.request(a, b)
            body["costing_options"]["auto"].update(opt)
            code, raw, ms = run.post(url, "/route", body)
            doc = json.loads(raw)
            f = rv.facts(doc)
            f["ms"] = round(ms)
            o = json.load(open(os.path.join(out, "osrm", tid + ".json"), encoding="utf-8"))
            if o.get("code") == "Ok" and "km" in f:
                f["o_km"] = o["routes"][0]["distance"] / 1000
                f["dkm"] = round(100 * (f["km"] - f["o_km"]) / f["o_km"], 1)
            f["id"] = tid
            rows.append(f)
            per_trip.setdefault(tid, {})[name] = {k: f.get(k) for k in ("km", "min", "dkm", "uturns", "told", "loop")}
        extra = []
        gone = 0
        for g in gaps:
            a, b = g["from"], g["to"]
            loc = [{"lat": a[0], "lon": a[1], "type": "break", "heading": g["heading"], "heading_tolerance": 45}, {"lat": b[0], "lon": b[1], "type": "break"}]
            code, doc, ms = rp.ask(url, rp.req(loc, costing=opt))
            if doc.get("code") == "Ok":
                extra.append(round(doc["routes"][0]["distance"]) - g["ideal_m"])
            else:
                gone += 1
        extra.sort()
        ok = [r for r in rows if "dkm" in r]
        result[name] = {"options": opt, "trips over 15 % longer than OSRM": [r["id"] for r in ok if r["dkm"] > 15], "trips over 15 % shorter": [r["id"] for r in ok if r["dkm"] < -15],
                        "median dkm %": statistics.median(r["dkm"] for r in ok), "total km": round(sum(r["km"] for r in ok), 1), "total min": round(sum(r["min"] for r in ok), 1),
                        "told steps": sum(r["told"] for r in ok), "uturns": sum(r["uturns"] for r in ok), "trips with a loop over 1 km": sum(1 for r in ok if r["loop"] > 1000),
                        "ms total": sum(r["ms"] for r in ok), "gaps": len(gaps), "gap extra over 300 m": sum(1 for x in extra if x > 300), "over 1 km": sum(1 for x in extra if x > 1000), "gap extra median": statistics.median(extra) if extra else None, "no answer": gone}
        print(name, json.dumps(result[name]), flush=True)
    with open(os.path.join(out, "sweep.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"result": result, "trips": per_trip}, f, ensure_ascii=False, indent=0)


if __name__ == "__main__":
    main()
