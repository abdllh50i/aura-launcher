#!/usr/bin/env python
"""Prints the steps of kept answers, one line each, so a route can be read.   python -I route_steps.py <out dir> <out.txt> id ...  (id:osrm for OSRM's answer)"""
import json
import os
import sys


def main():
    out, txt = sys.argv[1], sys.argv[2]
    with open(txt, "w", encoding="utf-8", newline="\n") as f:
        for spec in sys.argv[3:]:
            tid, _, eng = spec.partition(":")
            doc = json.load(open(os.path.join(out, "osrm" if eng == "osrm" else "valhalla", tid + ".json"), encoding="utf-8"))
            f.write(f"\n=== {tid} {eng or 'valhalla'} code={doc.get('code')} warnings={doc.get('warnings')}\n")
            if doc.get("code") != "Ok":
                continue
            for ri, r in enumerate(doc["routes"]):
                f.write(f"route {ri}: {r['distance'] / 1000:.2f} km {r['duration'] / 60:.1f} min\n")
                if ri > 0:
                    continue
                for leg in r["legs"]:
                    at = 0.0
                    for s in leg["steps"]:
                        m = s["maneuver"]
                        loc = m["location"]
                        f.write(f"  {at / 1000:7.2f} km  {s['distance']:8.0f} m {s['duration']:7.0f} s  {m.get('type', ''):16} {m.get('modifier', ''):13} exit={m.get('exit', '')} "
                                f"({loc[1]:.5f},{loc[0]:.5f}) {s.get('mode', '')} name={s.get('name', '')!r} ref={s.get('ref', '')!r} dest={s.get('destinations', '')!r}\n")
                        at += s["distance"]


if __name__ == "__main__":
    main()
