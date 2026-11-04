#!/usr/bin/env python3
"""Prints what one route answer says about road names, step by step.

    python3 -I look_names.py <answer.json>      (a saved answer)
    python3 -I look_names.py <url> lat,lon lat,lon [native]
"""
import json
import os
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trips  # noqa: E402


def has_ar(s):
    return any("؀" <= c <= "ۿ" for c in s)


def main():
    if os.path.exists(sys.argv[1]):
        doc = json.load(open(sys.argv[1], encoding="utf-8"))
    else:
        a = tuple(float(v) for v in sys.argv[2].split(","))
        b = tuple(float(v) for v in sys.argv[3].split(","))
        body = trips.request(a, b, alternates=0)
        if len(sys.argv) > 4:
            body.pop("format")
            body.pop("shape_format")
            body.pop("turn_lanes")
        req = urllib.request.Request(sys.argv[1] + "/route", data=json.dumps(body).encode())
        doc = json.loads(urllib.request.urlopen(req, timeout=120).read())
    sys.stdout.reconfigure(encoding="utf-8")
    if "trip" in doc:
        for leg in doc["trip"]["legs"]:
            for m in leg["maneuvers"]:
                print(m.get("type"), "|", m.get("street_names"), "|", m.get("begin_street_names"), "|", m.get("sign"), "|", m.get("instruction"))
        return
    r = doc["routes"][0]
    print("route keys", list(r.keys()), "distance", r["distance"], "duration", r["duration"], "legs", len(r["legs"]))
    for leg in r["legs"]:
        print("leg keys", list(leg.keys()))
        for s in leg["steps"]:
            m = s["maneuver"]
            print(f'{m.get("type"):16} {str(m.get("modifier")):13} exit={m.get("exit")} d={s["distance"]:.0f} name={s.get("name")!r} ref={s.get("ref")!r} '
                  f'dest={s.get("destinations")!r} exits={s.get("exits")!r} lanes={len((s.get("intersections") or [{}])[0].get("lanes") or [])}')
        print("step keys", sorted({k for s in leg["steps"] for k in s}))
        print("maneuver keys", sorted({k for s in leg["steps"] for k in s["maneuver"]}))


if __name__ == "__main__":
    main()
