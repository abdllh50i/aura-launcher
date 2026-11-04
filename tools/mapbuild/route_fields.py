#!/usr/bin/env python
"""Goes through Valhalla's OSRM answers the way the app's parser does (nav/Router.kt, parse and lanes) and counts what it finds.

    python -I route_fields.py <dir of answers or a file> [out.json]

For each field Router.parse reads: does it exist, of what type, and which values. Then the names: how many steps carry
any name, a name in Arabic letters, only Latin, only a number; what the picker in pick_name() below does with them.
"""
import collections
import glob
import json
import math
import os
import re
import sys


def has_ar(s):
    return any("؀" <= c <= "ۿ" or "ﭐ" <= c <= "﷿" or "ﹰ" <= c <= "﻿" for c in s or "")


def has_lat(s):
    return any("a" <= c.lower() <= "z" for c in s or "")


def split_names(s):
    return [p.strip() for p in (s or "").split(";") if p.strip()]


def pick_name(name, ref, arabic=True):
    """The picker the Kotlin side should use (see the report): split on ';', first by script, then the other, then the number."""
    parts = split_names(name)
    words = [p for p in parts if not re.fullmatch(r"[\d\s\-/]+", p)]
    ar = [p for p in words if has_ar(p)]
    la = [p for p in words if not has_ar(p)]
    first = (ar + la) if arabic else (la + ar)
    if first:
        return first[0]
    num = split_names(ref) + [p for p in parts if p not in words]
    return num[0] if num else ""


def kind(v):
    if v is None:
        return "null"
    return type(v).__name__


class Tally:
    def __init__(self):
        self.seen = collections.Counter()
        self.kinds = collections.defaultdict(collections.Counter)
        self.values = collections.defaultdict(collections.Counter)

    def add(self, path, obj, key, values=False):
        if key in obj:
            self.seen[path] += 1
            self.kinds[path][kind(obj[key])] += 1
            if values:
                self.values[path][str(obj[key])] += 1
        else:
            self.seen[path + " (missing)"] += 1


QUIET = {"new name", "continue", "notification", "use lane"}  # Router.QUIET_TYPES


def more(files):
    """What the parser's later steps rely on, and what the driver would be told. First route of each answer, except where said."""
    by_type = collections.defaultdict(collections.Counter)
    exit_types = collections.Counter()
    dest, exits, odd_refs, refs = collections.Counter(), collections.Counter(), collections.Counter(), collections.Counter()
    multi = []
    t = collections.Counter()
    worst_sum = 0.0
    worst_len = 0.0
    biggest = 0
    for path in files:
        biggest = max(biggest, os.path.getsize(path))
        doc = json.load(open(path, encoding="utf-8"))
        if doc.get("code") != "Ok":
            continue
        for ri, r in enumerate(doc["routes"]):
            steps = [s for leg in r["legs"] for s in leg["steps"]]
            total = sum(s["distance"] for s in steps)
            worst_sum = max(worst_sum, abs(total - r["distance"]) / max(r["distance"], 1))
            c = r["geometry"]["coordinates"]
            length = sum(hav(c[i], c[i + 1]) for i in range(len(c) - 1))
            worst_len = max(worst_len, abs(length - r["distance"]) / max(r["distance"], 1))
            t["routes"] += 1
            prev = None
            for s in steps:
                m = s["maneuver"]
                typ, mod = m.get("type", ""), m.get("modifier", "(none)")
                by_type[typ][mod] += 1
                if "exit" in m:
                    exit_types[typ] += 1
                if s.get("destinations"):
                    dest[s["destinations"]] += 1
                if s.get("exits"):
                    exits[s["exits"]] += 1
                ref = s.get("ref") or ""
                for part in split_names(ref):
                    refs[part] += 1
                    if has_ar(part) or len(part) > 12 or re.search(r"[a-z]{3}", part):
                        odd_refs[part] += 1
                ints = s.get("intersections") or []
                t["steps"] += 1
                if ints and ints[0].get("location") == m.get("location"):
                    t["first intersection is at the manoeuvre"] += 1
                if ri == 0:
                    t["first routes: steps"] += 1
                    name = s.get("name") or ""
                    roundabout_in = prev is not None and prev["maneuver"]["type"] in ("roundabout", "rotary")
                    straight = mod in ("(none)", "straight")
                    silent = (typ in QUIET and straight) or (typ.startswith("exit ") and roundabout_in)
                    told = not silent and typ not in ("arrive", "depart")
                    has_num = bool(s.get("ref")) or any(re.fullmatch(r"[\d\s\-/]+", p) for p in split_names(name))
                    for prefix in ("first routes", "told to the driver") if told else ("first routes",):
                        t[f"{prefix}: all"] += 1
                        t[f"{prefix}: with an Arabic name"] += has_ar(name)
                        t[f"{prefix}: Latin name only"] += bool(name) and not has_ar(name)
                        t[f"{prefix}: no name, a road number"] += (not name) and has_num
                        t[f"{prefix}: nothing"] += (not name) and not has_num
                    if len(split_names(name)) > 2 and len(multi) < 5:
                        multi.append(name)
                prev = s
    return {
        "answer bytes, largest": biggest,
        "worst |sum of steps - route distance| / distance": round(worst_sum, 5),
        "worst |line length - route distance| / distance": round(worst_len, 5),
        "maneuver type x modifier": {k: dict(v) for k, v in sorted(by_type.items())},
        "maneuver.exit is on": dict(exit_types),
        "counts": dict(sorted(t.items())),
        "destinations samples": [k for k, _ in dest.most_common(25)],
        "exits samples": [k for k, _ in exits.most_common(15)],
        "ref samples": [k for k, _ in refs.most_common(25)],
        "ref values that do not look like a number": [k for k, _ in odd_refs.most_common(25)],
        "names with more than two parts": multi,
    }


def hav(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[1], a[0], b[1], b[0]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371008 * math.asin(math.sqrt(h))


def main():
    src = sys.argv[1]
    files = [src] if os.path.isfile(src) else sorted(f for f in glob.glob(os.path.join(src, "*.json")) if not os.path.basename(f).startswith(("summary", "names", "fields", "mem")))
    t = Tally()
    extra_step_keys = collections.Counter()
    extra_man_keys = collections.Counter()
    extra_top = collections.Counter()
    extra_route = collections.Counter()
    extra_leg = collections.Counter()
    per_route = []
    stats = collections.Counter()
    lane_samples = []
    for path in files:
        doc = json.load(open(path, encoding="utf-8"))
        tid = os.path.basename(path)[:-5]
        t.add("code", doc, "code", values=True)
        extra_top.update(doc.keys())
        if doc.get("code") != "Ok":
            continue
        for ri, r in enumerate(doc["routes"]):
            extra_route.update(r.keys())
            t.add("routes[].distance", r, "distance")
            t.add("routes[].duration", r, "duration")
            t.add("routes[].geometry", r, "geometry")
            g = r["geometry"]
            t.add("geometry.coordinates", g, "coordinates")
            stats["coordinate pairs"] += len(g["coordinates"])
            stats["coordinate pairs not 2 numbers"] += sum(1 for c in g["coordinates"] if not (len(c) == 2 and all(isinstance(v, (int, float)) for v in c)))
            n = named = ar = latin_only = num_only = 0
            legs = r["legs"]
            for leg in legs:
                extra_leg.update(leg.keys())
                for s in leg["steps"]:
                    extra_step_keys.update(s.keys())
                    n += 1
                    m = s["maneuver"]
                    extra_man_keys.update(m.keys())
                    t.add("maneuver.type", m, "type", values=True)
                    t.add("maneuver.modifier", m, "modifier", values=True)
                    t.add("maneuver.exit", m, "exit", values=True)
                    t.add("maneuver.location", m, "location")
                    t.add("step.name", s, "name")
                    t.add("step.ref", s, "ref")
                    t.add("step.distance", s, "distance")
                    t.add("step.duration", s, "duration")
                    t.add("step.destinations", s, "destinations")
                    t.add("step.exits", s, "exits")
                    t.add("step.intersections", s, "intersections")
                    ints = s.get("intersections") or []
                    if ints:
                        i0 = ints[0]
                        if "lanes" in i0:
                            stats["steps with lanes on the first intersection"] += 1
                            for ln in i0["lanes"]:
                                t.add("lanes[].indications", ln, "indications")
                                t.add("lanes[].valid", ln, "valid", values=True)
                                t.add("lanes[].valid_indication", ln, "valid_indication", values=True)
                                for ind in ln.get("indications") or []:
                                    t.values["lanes[].indications values"][ind] += 1
                            if len(lane_samples) < 3:
                                lane_samples.append({"route": tid, "lanes": i0["lanes"], "name": s.get("name")})
                        if any("lanes" in x for x in ints[1:]):
                            stats["steps with lanes only on a later intersection"] += "lanes" not in i0
                    stats["steps"] += 1
                    name = s.get("name") or ""
                    parts = split_names(name)
                    stats["names per step: " + str(min(len(parts), 4))] += 1
                    if name:
                        named += 1
                        if has_ar(name):
                            ar += 1
                        elif re.fullmatch(r"[\d\s\-/;]+", name):
                            num_only += 1
                        else:
                            latin_only += 1
                    if s.get("ref"):
                        stats["steps with ref"] += 1
                    if s.get("destinations"):
                        stats["steps with destinations"] += 1
                    if s.get("exits"):
                        stats["steps with exits"] += 1
            if ri == 0:
                per_route.append({"route": tid, "steps": n, "named": named, "arabic": ar, "latin_only": latin_only, "number_only": num_only,
                                  "share_arabic": round(ar / n, 3) if n else 0})
    report_more = more(files)
    report = {
        "more": report_more,
        "files": len(files),
        "top keys": dict(extra_top), "route keys": dict(extra_route), "leg keys": dict(extra_leg),
        "step keys": dict(extra_step_keys), "maneuver keys": dict(extra_man_keys),
        "fields seen": dict(sorted(t.seen.items())),
        "kinds": {k: dict(v) for k, v in sorted(t.kinds.items())},
        "values": {k: dict(v.most_common(40)) for k, v in sorted(t.values.items())},
        "stats": dict(sorted(stats.items())),
        "first route of each answer": per_route,
        "lane samples": lane_samples,
    }
    txt = json.dumps(report, ensure_ascii=False, indent=1)
    if len(sys.argv) > 2:
        with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as f:
            f.write(txt)
    print(txt.encode("ascii", "backslashreplace").decode())


if __name__ == "__main__":
    main()
