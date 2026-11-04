#!/usr/bin/env python
"""What the road side holds, for table spot and table zone of places.db.   <venv python> -I osm_roadside.py <ksa.osm.pbf> <out.json>

Needs pyosmium (it walks every road's geometry; the build's own PBF reader would take many minutes for this).

  cameras  For each highway=speed_camera node: the traffic it concerns. OSM's direction tag is unreliable for this: tests/spot_check.py found most
           numeric tags against the camera's own carriageway, because mappers tag where the lens faces. So the heading comes from the road: the nearest main road
           within CAMERA_REACH metres. A one-way road gives its heading. A two-way road gives none (null: it may watch either way). When two one-way
           carriageways are about equally near, the tag, turned round, chooses; with no tag the camera stays null.
  gates    Nodes on a drivable road that can stop a car: gates, checkpoints, border posts, toll booths, concrete blocks, with what they are (sub).
           A route through one of these is legal to the engine and may still end at a guard: the app warns.
  zones    Areas a route should warn about: the Haram of Makkah and of Madinah (entry is for Muslims only, no road tag says so), and what is really military or private.
           The Haram's rings are simplified and grown outward so that they hold all of the original, at most MAX_RING points each, the limit of exclude_polygons on the unit.
           All others keep OSM's own line, within a few metres (ZONE_TOLERANCE, at most ZONE_MAX_RING points): a ring of 24 points grown outward covered half of Jubail and 1,289 places of Khobar and Dhahran.
           A zone is military when OSM says landuse=military or military=*, unless the same tags say it is open or something else (not_a_military_zone): such an area is no zone at all.
"""
import collections
import json
import math
import sys

import osmium

MAIN = {"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link"}
ROADS = MAIN | {"unclassified", "residential", "service", "living_street"}
CAMERA_REACH = 25.0  # metres
AMBIGUOUS = 4.0  # metres: two carriageways this close in distance are told apart by the tag
MAX_RING = 100
ZONE_MAX_RING = 400
ZONE_TOLERANCE = 0.00015  # degrees, about 16 m
HARAM = {19590020: 1, 19122097: 2}  # OSM relations: 1 Makkah, 2 Madinah
# The one road toll of the Kingdom: the King Fahd Causeway's booths, from its Saudi end on. s, n, w, e
CAUSEWAY = (26.10, 26.25, 50.19, 50.50)

# sub of a gate
SUB_GATE, SUB_PRIVATE, SUB_CHECKPOINT, SUB_BORDER, SUB_TOLL, SUB_BLOCK = 1, 2, 3, 4, 5, 6
GATE_LIKE = {"gate", "lift_gate", "swing_gate", "sliding_gate", "bump_gate", "sally_port", "entrance", "yes", "full-height_turnstile", "hampshire_gate", "kissing_gate"}
BLOCK_LIKE = {"block", "jersey_barrier", "bollard", "spikes", "kerb", "bunker", "debris"}


def sub_of(t, lat=None, lon=None):
    b = t.get("barrier") or ""
    hw = t.get("highway") or ""
    access = t.get("access") or t.get("motor_vehicle") or ""
    if b == "border_control":
        return SUB_BORDER
    if b == "checkpoint" or hw == "checkpoint" or t.get("military") == "checkpoint":
        return SUB_CHECKPOINT
    if b == "toll_booth" or hw == "toll_gantry":
        # Roads in the Kingdom have no tolls but the causeway's. Mappers draw a row of booths with this tag at the checkpoints (Al Shumaisi on the road to Makkah and to Taif said "this road has a toll"), six of them with
        # military=checkpoint as well. What they are is what they do to a car: it stops and goes on.
        if lat is not None and CAUSEWAY[0] <= lat <= CAUSEWAY[1] and CAUSEWAY[2] <= lon <= CAUSEWAY[3]:
            return SUB_TOLL
        return SUB_CHECKPOINT
    if b in BLOCK_LIKE:
        return SUB_BLOCK if access not in ("yes", "permissive", "designated") else None
    if b in GATE_LIKE:
        if access in ("yes", "permissive", "customers", "destination", "delivery"):
            return None
        return SUB_PRIVATE if access in ("private", "no", "permit") else SUB_GATE
    return None


def bearing(la1, lo1, la2, lo2):
    p1, p2 = math.radians(la1), math.radians(la2)
    d = math.radians(lo2 - lo1)
    return (math.degrees(math.atan2(math.sin(d) * math.cos(p2), math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(d))) + 360) % 360


def adiff(a, b):
    return abs((a - b + 180) % 360 - 180)


def seg_dist_m(la, lo, a, b):
    k = math.cos(math.radians(la)) * 111320.0
    ax, ay = (a[1] - lo) * k, (a[0] - la) * 110540.0
    bx, by = (b[1] - lo) * k, (b[0] - la) * 110540.0
    dx, dy = bx - ax, by - ay
    t = 0.0 if dx == 0 and dy == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / (dx * dx + dy * dy)))
    return math.hypot(ax + t * dx, ay + t * dy)


def tag_direction(v):
    try:
        return float(v) % 360
    except (TypeError, ValueError):
        pass
    words = {"n": 0, "nne": 22.5, "ne": 45, "ene": 67.5, "e": 90, "ese": 112.5, "se": 135, "sse": 157.5, "s": 180, "ssw": 202.5, "sw": 225, "wsw": 247.5, "w": 270, "wnw": 292.5, "nw": 315, "nnw": 337.5}
    return words.get((v or "").strip().lower())


class Nodes(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.cameras = {}
        self.gates = {}
        self.grid = collections.defaultdict(list)

    def node(self, n):
        t = n.tags
        if t.get("highway") == "speed_camera":
            la, lo = n.location.lat, n.location.lon
            self.cameras[n.id] = {"lat": la, "lon": lo, "tag": tag_direction(t.get("direction")), "best": []}
            self.grid[(int(la * 200), int(lo * 200))].append(n.id)
        s = sub_of(t, n.location.lat, n.location.lon)
        if s is not None:
            self.gates[n.id] = {"lat": n.location.lat, "lon": n.location.lon, "sub": s}


class Ways(osmium.SimpleHandler):
    def __init__(self, nodes):
        super().__init__()
        self.n = nodes
        self.on = {}
        self.zones = []

    def way(self, w):
        hw = w.tags.get("highway")
        if hw not in ROADS:
            return
        for nd in w.nodes:
            if nd.ref in self.n.gates:
                r = self.on.get(nd.ref)
                self.on[nd.ref] = hw if r is None or (hw in MAIN and r not in MAIN) else r
        if hw not in MAIN:
            return
        one = w.tags.get("oneway", "")
        if not one and (hw in ("motorway", "motorway_link") or w.tags.get("junction") in ("roundabout", "circular")):
            one = "yes"
        pts = [(nd.location.lat, nd.location.lon) for nd in w.nodes if nd.location.valid()]
        grid = self.n.grid
        for a, b in zip(pts, pts[1:]):
            cells = {(int(a[0] * 200), int(a[1] * 200)), (int(b[0] * 200), int(b[1] * 200))}
            near = set()
            for cx, cy in cells:
                for dx in (-1, 0, 1):
                    for dy in (-1, 0, 1):
                        near.update(grid.get((cx + dx, cy + dy), ()))
            for cid in near:
                c = self.n.cameras[cid]
                d = seg_dist_m(c["lat"], c["lon"], a, b)
                if d <= CAMERA_REACH:
                    h = bearing(a[0], a[1], b[0], b[1])
                    if one == "-1":
                        h = (h + 180) % 360
                    c["best"].append((d, h, one in ("yes", "1", "true", "-1"), hw, w.id))


def not_a_military_zone(t):
    """Why an area with a military tag is none, or "". Each of these said "your destination is in a military zone" where nobody stops a car."""
    if t.get("access") in ("permissive", "yes", "public", "customers"):
        return "open"  # "منطقة استبعاد الحدود", the border exclusion area at Salwa, 515 km2: the road to the post runs through it
    if t.get("boundary") == "protected_area" or t.get("leisure") == "nature_reserve":
        return "reserve"  # Prince Sultan's reserve, 3,217 km2
    if t.get("natural") or t.get("landuse") == "quarry":
        return "hazard"  # military=danger_area on a sinkhole or a digging site
    if t.get("aeroway") == "aerodrome" and t.get("iata") and ("الدولي" in (t.get("name") or "") or "International" in (t.get("name:en") or t.get("int_name") or "")):
        return "public airport"  # Taif's: a civil terminal inside a base's fence
    return ""


class Areas(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.found = []
        self.not_zones = collections.Counter()

    def area(self, a):
        t = a.tags
        kind = None
        rid = a.orig_id() if not a.from_way() else None
        name = t.get("name") or ""
        if rid in HARAM:
            kind = HARAM[rid]
        elif t.get("landuse") == "military" or t.get("military"):
            why = not_a_military_zone(t)
            if why:
                self.not_zones[why] += 1
            else:
                kind = 3
        elif t.get("access") in ("private", "no") and t.get("landuse") in ("residential", "industrial", "commercial", "construction", "retail") and name:
            kind = 4
        if kind is None:
            return
        rings = [[(round(n.lon, 6), round(n.lat, 6)) for n in outer] for outer in a.outer_rings()]
        self.found.append({"kind": kind, "osm": ("r" if rid else "w") + str(a.orig_id()), "name": name, "en": t.get("name:en") or "", "rings": rings})


def ring_area_km2(r):
    k = math.cos(math.radians(r[0][1])) * 111.32
    s = 0.0
    for (x1, y1), (x2, y2) in zip(r, r[1:]):
        s += (x1 * k) * (y2 * 110.54) - (x2 * k) * (y1 * 110.54)
    return abs(s) / 2


def perp_deg(p, a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    n = dx * dx + dy * dy
    if n == 0:
        return math.hypot(p[0] - a[0], p[1] - a[1])
    t = max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / n))
    return math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy)


def douglas(pts, tol):
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        i, j = stack.pop()
        best, at = 0.0, None
        for k in range(i + 1, j):
            d = perp_deg(pts[k], pts[i], pts[j])
            if d > best:
                best, at = d, k
        if at is not None and best > tol:
            keep[at] = True
            stack += [(i, at), (at, j)]
    return [p for p, k in zip(pts, keep) if k]


def inside(p, ring):
    x, y = p
    hit = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            hit = not hit
    return hit


def simple_outward(ring, max_points):
    """At most max_points, holding every point of the original: simplified, then pushed away from its middle until nothing sticks out."""
    r = list(ring)
    if r[0] != r[-1]:
        r.append(r[0])
    tol = 0.0005
    s = douglas(r, tol)
    while len(s) > max_points:
        tol *= 1.3
        s = douglas(r, tol)
    cx = sum(p[0] for p in s[:-1]) / (len(s) - 1)
    cy = sum(p[1] for p in s[:-1]) / (len(s) - 1)
    grow = 1.0
    for _ in range(40):
        grown = [(round(cx + (p[0] - cx) * grow, 5), round(cy + (p[1] - cy) * grow, 5)) for p in s]
        if all(inside(p, grown) for p in r[::max(1, len(r) // 400)]):
            return grown
        grow *= 1.01
    return grown


def close_to_real(ring):
    """OSM's own line within ZONE_TOLERANCE: nothing is grown, so what is near the fence stays outside it. Closed, with at most ZONE_MAX_RING points."""
    r = list(ring)
    if r[0] != r[-1]:
        r.append(r[0])
    tol = ZONE_TOLERANCE
    s = douglas(r, tol)
    while len(s) > ZONE_MAX_RING:
        tol *= 1.3
        s = douglas(r, tol)
    return [(round(p[0], 5), round(p[1], 5)) for p in s]


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    pbf, out = sys.argv[1], sys.argv[2]
    n = Nodes()
    n.apply_file(pbf)
    print("cameras", len(n.cameras), "gates and the like", len(n.gates), flush=True)
    w = Ways(n)
    w.apply_file(pbf, locations=True, idx="flex_mem")
    how = collections.Counter()
    cams = {}
    for cid, c in n.cameras.items():
        best = sorted(c["best"])
        d = h = None
        if not best:
            what = "no main road near"
        else:
            nearest = best[0]
            ones = [b for b in best if b[2]]
            if not nearest[2]:
                what = "two-way road"
            else:
                # the other carriageway: a one-way road heading the other way, about as near
                other = [b for b in ones if adiff(b[1], nearest[1]) > 120 and b[0] - nearest[0] <= AMBIGUOUS]
                if not other:
                    d, h, what = nearest[0], nearest[1], "one-way road"
                elif c["tag"] is None:
                    what = "between two carriageways, no tag"
                else:
                    traffic = (c["tag"] + 180) % 360
                    pick = min([nearest] + other, key=lambda b: adiff(b[1], traffic))
                    d, h, what = pick[0], pick[1], "between two carriageways, by the tag"
        how[what] += 1
        if h is not None and c["tag"] is not None:
            how["tag agrees with the road" if adiff(c["tag"], h) <= 45 else "tag turned round agrees with the road" if adiff(c["tag"], h) >= 135 else "tag across the road"] += 1
        cams[f"{c['lat']:.7f},{c['lon']:.7f}"] = {"dir": None if h is None else int(round(h)) % 360, "how": what, "m": None if d is None else round(d, 1), "tag": c["tag"]}
    print("cameras:", how.most_common(), flush=True)
    gates = []
    subs = collections.Counter()
    for nid, road in w.on.items():
        g = n.gates[nid]
        gates.append({"lat": g["lat"], "lon": g["lon"], "sub": g["sub"], "road": road})
        subs[g["sub"]] += 1
    print("gates on a drivable road, by sub:", sorted(subs.items()), flush=True)
    a = Areas()
    a.apply_file(pbf, locations=True, idx="flex_mem")
    zones = []
    kinds = collections.Counter()
    for f in a.found:
        for ring in f["rings"]:
            km2 = ring_area_km2(ring)
            if f["kind"] >= 3 and km2 < 0.05:
                continue
            zones.append({"kind": f["kind"], "osm": f["osm"], "name": f["name"], "en": f["en"], "points": len(ring), "km2": round(km2, 2), "ring": simple_outward(ring, MAX_RING) if f["kind"] <= 2 else close_to_real(ring)})
            kinds[f["kind"]] += 1
    print("zones by kind:", sorted(kinds.items()), [(z["name"], z["points"], len(z["ring"]), z["km2"]) for z in zones if z["kind"] <= 2], "points in all rings:", sum(len(z["ring"]) for z in zones), "areas with a military tag that are no zone:", a.not_zones.most_common(), flush=True)
    with open(out, "w", encoding="utf-8", newline="\n") as fo:
        json.dump({"cameras": cams, "camera_how": dict(how), "gates": gates, "zones": zones}, fo, ensure_ascii=False)


if __name__ == "__main__":
    main()
