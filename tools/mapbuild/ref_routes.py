#!/usr/bin/env python3
"""Asks a running valhalla_service for the reference answers and keeps them.

    python3 -I ref_routes.py <base url> <out dir> [long,short,edges]

Each trip is asked twice: the first time cold (nothing of it in memory), the second warm. The raw JSON of the warm answer
is kept as <id>.json; times, lengths and counts go to summary.json and summary.tsv.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trips  # noqa: E402


def ask(url, body):
    req = urllib.request.Request(url + "/route", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            raw = r.read()
            code = r.status
    except urllib.error.HTTPError as e:
        raw = e.read()
        code = e.code
    return code, raw, (time.perf_counter() - t0) * 1000


def main():
    url, out = sys.argv[1], sys.argv[2]
    groups = (sys.argv[3] if len(sys.argv) > 3 else "long,short,edges").split(",")
    os.makedirs(out, exist_ok=True)
    rows = []
    for group, items in (("long", trips.LONG), ("short", trips.SHORT), ("edges", trips.EDGES)):
        if group not in groups:
            continue
        for tid, label, a, b in items:
            body = trips.request(a, b)
            code1, raw1, ms1 = ask(url, body)
            code2, raw2, ms2 = ask(url, body)
            row = {"id": tid, "group": group, "label": label, "from": a, "to": b, "http": code2, "cold_ms": round(ms1, 1), "warm_ms": round(ms2, 1),
                   "bytes": len(raw2)}
            with open(os.path.join(out, tid + ".json"), "wb") as f:
                f.write(raw2)
            doc = json.loads(raw2)
            if code2 == 200 and doc.get("code") == "Ok":
                routes = doc["routes"]
                r = routes[0]
                row.update(km=round(r["distance"] / 1000, 2), minutes=round(r["duration"] / 60, 1), routes=len(routes),
                           steps=sum(len(l["steps"]) for l in r["legs"]), points=len(r["geometry"]["coordinates"]),
                           alternates=[(round(x["distance"] / 1000, 1), round(x["duration"] / 60, 1)) for x in routes[1:]])
            else:
                row["error"] = doc
            rows.append(row)
            print(f"{tid:34} http {code2} cold {ms1:8.0f} ms warm {ms2:8.0f} ms {row.get('km', '-'):>8} km {row.get('minutes', '-'):>7} min "
                  f"steps {row.get('steps', '-'):>4} routes {row.get('routes', '-')} {len(raw2) / 1024:7.1f} KB", flush=True)
    with open(os.path.join(out, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=1)
    keys = ("id", "group", "http", "cold_ms", "warm_ms", "km", "minutes", "steps", "routes", "points", "bytes", "alternates", "label")
    with open(os.path.join(out, "summary.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\t".join(keys) + "\n")
        for r in rows:
            f.write("\t".join(str(r.get(k, "")) for k in keys) + "\n")


if __name__ == "__main__":
    main()
