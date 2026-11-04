#!/usr/bin/env python
"""Do tables gate and zone of places.db do what they are for? Against a running valhalla_service, with the request in ..\\trips.py.

  python -I zone_gate_check.py <places.db> <service url> <folder of route-tests answers> <out.json>

Zones: Jeddah to Taif asked plainly and with Makkah's ring from table zone in exclude_polygons: how much of each route lies inside the ring, and a destination inside it.
Gates: which gate rows lie within 15 m of the kept answers of the test trips gate-camp, gate-air and gate-rt (tests/route_trips.py), and of a plain long trip for comparison.
"""
import json
import math
import os
import sqlite3
import sys
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import trips  # noqa: E402


def post(url, body):
    req = urllib.request.Request(url + "/route", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def inside(p, ring):
    x, y = p
    hit = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            hit = not hit
    return hit


def km_inside(route, ring):
    c = route["geometry"]["coordinates"]
    total = 0.0
    for a, b in zip(c, c[1:]):
        if inside(a, ring) and inside(b, ring):
            total += math.hypot((b[0] - a[0]) * math.cos(math.radians(a[1])) * 111.32, (b[1] - a[1]) * 110.54)
    return round(total, 1)


def seg_m(la, lo, a, b):
    k = math.cos(math.radians(la)) * 111320.0
    ax, ay = (a[0] - lo) * k, (a[1] - la) * 110540.0
    bx, by = (b[0] - lo) * k, (b[1] - la) * 110540.0
    dx, dy = bx - ax, by - ay
    t = 0.0 if dx == 0 and dy == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / (dx * dx + dy * dy)))
    return math.hypot(ax + t * dx, ay + t * dy)


def gates_on(db, coords):
    las = [c[1] for c in coords]
    rows = db.execute("SELECT lat,lon,sub,road FROM gate WHERE lat BETWEEN ? AND ?", (int((min(las) - 0.001) * 1e6), int((max(las) + 0.001) * 1e6))).fetchall()
    out = []
    for la, lo, sub, road in rows:
        la, lo = la / 1e6, lo / 1e6
        for a, b in zip(coords, coords[1:]):
            if abs(a[1] - la) < 0.003 or abs(b[1] - la) < 0.003:
                if seg_m(la, lo, a, b) <= 15.0:
                    out.append({"lat": la, "lon": lo, "sub": sub, "road": road})
                    break
    return out


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    db = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
    url, folder, out = sys.argv[2], sys.argv[3], sys.argv[4]
    rep = {"zones": {k: n for k, n in db.execute("SELECT kind,count(*) FROM zone GROUP BY kind")}, "gates": {k: n for k, n in db.execute("SELECT sub,count(*) FROM gate GROUP BY sub")}}
    name, ring_text = db.execute("SELECT name,ring FROM zone WHERE kind=1 ORDER BY id LIMIT 1").fetchone()
    ring = json.loads(ring_text)
    rep["makkah"] = {"name": name, "points": len(ring)}
    a, b = (21.5433, 39.1728), (21.2703, 40.4158)  # Jeddah, Taif
    code, plain = post(url, trips.request(a, b, 0))
    r0 = plain["routes"][0]
    rep["jeddah_taif_plain"] = {"http": code, "km": round(r0["distance"] / 1000, 1), "min": round(r0["duration"] / 60, 1), "km_inside": km_inside(r0, ring)}
    body = trips.request(a, b, 0)
    body["exclude_polygons"] = [ring]
    code, av = post(url, body)
    if code == 200:
        r1 = av["routes"][0]
        rep["jeddah_taif_avoiding"] = {"http": code, "km": round(r1["distance"] / 1000, 1), "min": round(r1["duration"] / 60, 1), "km_inside": km_inside(r1, ring)}
    else:
        rep["jeddah_taif_avoiding"] = {"http": code, "answer": av}
    body = trips.request(a, (21.4225, 39.8262), 0)  # the Grand Mosque
    body["exclude_polygons"] = [ring]
    code, inner = post(url, body)
    rep["destination_inside_avoiding"] = {"http": code, "code": inner.get("code"), "message": inner.get("message")}
    rep["gates_on_kept_routes"] = {}
    for tid in ("gate-camp", "gate-air", "gate-rt", "jubail-dammam", "dammam-riyadh"):
        p = os.path.join(folder, tid + ".json")
        if not os.path.exists(p):
            rep["gates_on_kept_routes"][tid] = "no answer kept"
            continue
        with open(p, encoding="utf-8") as f:
            doc = json.load(f)
        coords = doc["routes"][0]["geometry"]["coordinates"]
        rep["gates_on_kept_routes"][tid] = gates_on(db, coords)
        la, lo = coords[-1][1], coords[-1][0]
        rep.setdefault("zones_at_the_route_end", {})[tid] = [(k, n or e) for k, n, e, rg in db.execute("SELECT kind,name,en,ring FROM zone WHERE s<=? AND n>=? AND w<=? AND e>=?", (la, la, lo, lo)) if inside((lo, la), json.loads(rg))]
    with open(out, "w", encoding="utf-8") as f:
        json.dump(rep, f, ensure_ascii=False, indent=1)
    print(json.dumps(rep, ensure_ascii=False))


if __name__ == "__main__":
    main()
