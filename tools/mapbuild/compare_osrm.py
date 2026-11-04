#!/usr/bin/env python
"""Our reference answers against the public OSRM demo server, at most one request a second, as its policy asks.

    python -I compare_osrm.py <ref-routes dir> <out.tsv>

Reads the distance and time of the first route of each saved answer, asks OSRM for the same trip, and marks a difference
of more than 15 % in distance. OSRM's times come from its own speed profile: a difference there says little by itself.
"""
import json
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trips  # noqa: E402


def main():
    ref, out = sys.argv[1], sys.argv[2]
    rows = ["id\tlabel\tvalhalla_km\tosrm_km\tkm_diff_pct\tvalhalla_min\tosrm_min\tmin_diff_pct\tflag"]
    for group in (trips.LONG, trips.SHORT, trips.EDGES):
        for tid, label, a, b in group:
            doc = json.load(open(os.path.join(ref, tid + ".json"), encoding="utf-8"))
            r = doc["routes"][0]
            url = f"https://router.project-osrm.org/route/v1/driving/{a[1]},{a[0]};{b[1]},{b[0]}?overview=false"
            t0 = time.time()
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "AMRI map build comparison (one car, about 30 requests)"})
                with urllib.request.urlopen(req, timeout=30) as resp:
                    o = json.loads(resp.read())["routes"][0]
                vk, ok = r["distance"] / 1000, o["distance"] / 1000
                vm, om = r["duration"] / 60, o["duration"] / 60
                dk, dm = 100 * (vk - ok) / ok, 100 * (vm - om) / om
                flag = "DIST>15%" if abs(dk) > 15 else ""
                line = f"{tid}\t{label}\t{vk:.1f}\t{ok:.1f}\t{dk:+.1f}\t{vm:.1f}\t{om:.1f}\t{dm:+.1f}\t{flag}"
            except Exception as e:
                line = f"{tid}\t{label}\t{r['distance'] / 1000:.1f}\t\t\t{r['duration'] / 60:.1f}\t\t\tOSRM failed: {e}"
            rows.append(line)
            print(line.encode("ascii", "replace").decode(), flush=True)
            time.sleep(max(0.0, 1.1 - (time.time() - t0)))
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(rows) + "\n")


if __name__ == "__main__":
    main()
