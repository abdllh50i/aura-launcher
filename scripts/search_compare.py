#!/usr/bin/env python
"""The Kotlin search (nav/places) against the reference (tools/mapbuild/search_ref.py), row by row. scripts/search-compare.ps1 runs the whole of it on an emulator.

  python -I search_compare.py fixture --db places.db --out app/src/test/resources/places     what the JVM tests read (syn, cat, Python's own answers)
  python -I search_compare.py prepare --db places.db --routes <route-tests> --out <folder>   the jobs for the app, and what the reference answers to them
  python -I search_compare.py compare --out <folder>                                         the app's answers against those: every mismatch, then the times

Standard library only.
"""
import argparse
import json
import math
import os
import random
import re
import sqlite3
import statistics
import sys
import time
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MAPBUILD = os.path.join(ROOT, "tools", "mapbuild")
sys.path.insert(0, MAPBUILD)
import search_ref as S  # noqa: E402

STAGES = ["plan", "first", "other", "place", "edit", "loose", "area", "score", "dedupe"]


def dump(path, doc):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def tests_dir():
    return os.path.join(MAPBUILD, "tests")


def answer(db, syn, q, near):
    """What the reference answers, in the form the app writes."""
    dbg = {}
    res = S.search(db, q, tuple(near) if near else None, 12, syn=syn, debug=dbg)
    out = {"ids": [r["id"] for r in res], "scores": [r["score"] for r in res], "km": [r["km"] for r in res], "shown": [r["shown"] for r in res],
           "detail": [r["detail"] for r in res], "approx": [r["approx"] for r in res], "match": dbg.get("match", []), "cand": dbg.get("candidates", 0)}
    en = []
    for r in res:  # display() with arabic=False, as far as shown goes
        main = r["name"]
        if r["alt"]:
            name_ar = bool(main) and any("؀" <= c <= "ۿ" for c in main)
            if name_ar:
                main = r["alt"]
        en.append(main)
    out["shown_en"] = en
    return out


def fixture(a):
    """The syn and cat tables and Python's answers, for src/test: text functions, query plans and scores without a database."""
    os.makedirs(a.out, exist_ok=True)
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    syn = S.Syn(db)
    dump(os.path.join(a.out, "tables.json"), {
        "syn": db.execute("SELECT w,cats,role,canon FROM syn ORDER BY w").fetchall(),
        "cat": db.execute("SELECT id,key,ar,en,icon FROM cat ORDER BY id").fetchall(),
    })
    rnd = random.Random(2501)
    # every character of the Arabic blocks and the first ones of Unicode, between two letters: what folds to what
    chars = list(range(0x0, 0x250)) + list(range(0x0600, 0x0700)) + list(range(0x0750, 0x0780)) + list(range(0x2000, 0x2070)) + list(range(0xFB50, 0xFE00)) + list(range(0xFE70, 0xFF00))
    fold = [[c, S.norm("x" + chr(c) + "x")] for c in chars]
    names = [r[0] for r in db.execute("SELECT name FROM p WHERE id % 97 = 0")] + [r[0] for r in db.execute("SELECT alt FROM p WHERE alt IS NOT NULL AND id % 197 = 0")]
    rnd.shuffle(names)
    texts = names[:200]
    words = []
    for t in texts:
        ws = S.query_words(t)
        words.append({"t": t, "norm": S.norm(t), "words": ws, "sks": [S.skeletons(w) for w in ws], "latin": [S.latin_base(w) for w in ws], "edits": [S.one_edit_forms(w) for w in ws[:2]]})
    # query plans and scores of the tester's set
    cases = load(os.path.join(tests_dir(), "search-set.json"))["cases"]
    plans = []
    scores = []
    area_rows = {}
    for c in cases:
        q = c["q"]
        p = S.plan_query(q, syn)
        if not p.required:
            plans.append({"q": q, "none": True})
            continue
        others = S.other_plans(p, syn)
        plans.append({"q": q, "first": S.first_stages(p), "others": [{"other": o.other, "first": S.first_stages(o)} for o in others], "edit": S.fts_edit_string(p), "place": S.place_string(p),
                      "best": S.wants_best(p, syn), "loose3": S.fts_string(p, loose_from=3), "loose2": S.fts_string(p, loose_from=2)})
    for c in cases[::4]:
        q, near = c["q"], tuple(c["near"])
        p = S.plan_query(q, syn)
        if not p.required:
            continue
        rows = db.execute(f"SELECT {S.COLUMNS} FROM p WHERE id % 40009 = {rnd.randrange(40009)}").fetchall()
        res = S.search(db, q, near, 3, syn=syn)
        if res:
            rows += db.execute(f"SELECT {S.COLUMNS} FROM p WHERE id IN ({','.join(str(r['id']) for r in res)})").fetchall()
        areas = S.areas_of(db, {r[0]: r for r in rows})
        for r in rows:
            s, km, t = S.score_row(r, p, near, areas.get(r[7]), syn)
            scores.append({"q": q, "near": near, "row": r, "area": areas.get(r[7]), "score": s, "km": km})
    dump(os.path.join(a.out, "answers.json"), {"fold": fold, "words": words, "plans": plans, "scores": scores})
    print("fold", len(fold), "words", len(words), "plans", len(plans), "scores", len(scores))


def route_coords(routes, tid):
    doc = load(os.path.join(routes, "valhalla", tid + ".json"))
    return doc["routes"][0]["geometry"]["coordinates"]


def route_classes(routes, tid, n):
    """Road class of each segment of the line, from trace_attributes over the same route: the number of gate.road."""
    names = {"motorway": 0, "trunk": 1, "primary": 2, "secondary": 3, "tertiary": 4, "unclassified": 5, "residential": 6, "living_street": 7}
    e = load(os.path.join(routes, "edges", tid + ".json"))
    out = [8] * (n - 1)
    for x in e["edges"]:
        for i in range(x["begin_shape_index"], min(x["end_shape_index"], n - 1)):
            out[i] = names.get(x.get("road_class"), 8)
    return out


def prepare(a):
    os.makedirs(a.out, exist_ok=True)
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    syn = S.Syn(db)
    rnd = random.Random(2501)
    t0 = time.time()

    def job(name, cases):
        out = []
        for q, near in cases:
            try:
                out.append(answer(db, syn, q, near))
            except sqlite3.Error as e:
                out.append({"error": str(e)})
        dump(os.path.join(a.out, name + ".job.json"), {"searches": [{"q": q, "near": near} for q, near in cases]})
        dump(os.path.join(a.out, name + ".exp.json"), {"searches": out})
        print(name, len(cases), f"{time.time() - t0:.0f} s", flush=True)

    set200 = [(c["q"], c["near"]) for c in load(os.path.join(tests_dir(), "search-set.json"))["cases"]]
    tests = load(os.path.join(tests_dir(), "search_tests.json"))
    golden = [(c["q"], c.get("near")) for c in tests["cases"]]
    job("set200", set200)
    job("golden", golden)
    # what a driver goes through on the way to those: every prefix of a hundred of them, with the last space, and with no position at all
    all_q = list(dict.fromkeys(set200 + [(q, tuple(n) if n else None) for q, n in golden]))
    all_q = [(q, list(n) if n else None) for q, n in dict.fromkeys((q, tuple(n) if n else None) for q, n in set200 + golden)]
    pick = rnd.sample(all_q, 100)
    prefixes = [(q[:i], n) for q, n in pick for i in range(1, len(q))]
    job("prefixes", prefixes)
    job("finished", [(q + " ", n) for q, n in all_q] + [(q, None) for q, n in all_q])
    # random rows searched by their own names, from about 3 km away: nobody chose these words
    rows = db.execute("SELECT name,alt,lat,lon FROM p WHERE id % 211 = 0 AND kind>=5").fetchall()
    rnd.shuffle(rows)
    own = []
    for name, alt, la, lo in rows[:400]:
        near = [la / 1e6 + 0.02, lo / 1e6 - 0.015]
        own.append((name, near))
        if alt:
            own.append((alt, near))
    job("own", own)
    # reverse: the four of the set, and points beside rows of every kind
    points = [c["at"] for c in tests["reverse"]]
    rows = db.execute("SELECT lat,lon FROM p WHERE id % 503 = 0").fetchall()
    for la, lo in rows:
        points.append([round(la / 1e6 + rnd.uniform(-0.002, 0.002), 6), round(lo / 1e6 + rnd.uniform(-0.002, 0.002), 6)])
    points += [[round(rnd.uniform(17, 31), 5), round(rnd.uniform(36, 55), 5)] for _ in range(100)]
    rev = [S.reverse(db, la, lo) for la, lo in points]
    dump(os.path.join(a.out, "reverse.job.json"), {"reverse": points})
    dump(os.path.join(a.out, "reverse.exp.json"), {"reverse": rev})
    print("reverse", len(points), flush=True)
    # text functions: every character of the BMP, and names as they are in the file
    names = [r[0] for r in db.execute("SELECT name FROM p WHERE id % 37 = 0")] + [r[0] for r in db.execute("SELECT alt FROM p WHERE alt IS NOT NULL AND id % 41 = 0")]
    words = []
    for t in names:
        ws = S.query_words(t)
        words.append({"norm": S.norm(t), "words": ws, "sks": [S.skeletons(w) for w in ws], "latin": [S.latin_base(w) for w in ws], "edits": [S.one_edit_forms(w) for w in ws]})
    dump(os.path.join(a.out, "text.job.json"), {"norm_bmp": True, "words": names})
    dump(os.path.join(a.out, "text.exp.json"), {"words": words, "norm_bmp": [("" if 0xD800 <= c <= 0xDFFF else S.norm("x" + chr(c) + "x")) for c in range(0x10000)]})
    print("text", len(names), flush=True)
    # routes: the kept test trips, with the road classes of the same route
    wanted = ["gate-camp", "gate-air", "gate-rt", "jubail-dammam", "dammam-riyadh", "jed-taif", "kfupm", "d4", "dam-khb", "abha-riyadh"]
    routes = []
    if a.routes and os.path.isdir(a.routes):
        for tid in wanted:
            if os.path.exists(os.path.join(a.routes, "valhalla", tid + ".json")):
                c = route_coords(a.routes, tid)
                routes.append({"id": tid, "coords": c, "classes": route_classes(a.routes, tid, len(c))})
    park = [[27.0046, 49.6586], [26.2794, 50.2083], [24.6877, 46.7219], [21.4225, 39.8262], [20.0, 45.0]]
    dump(os.path.join(a.out, "routes.job.json"), {"routes": routes, "points": park, "haram": True})
    dump(os.path.join(a.out, "routes.exp.json"), {"routes": [twin_route(db, r) for r in routes], "points": [twin_near(db, p, "parking") for p in park],
                                                  "haram": [dict(zip(("id", "name", "json"), db.execute("SELECT id,name,ring FROM zone WHERE kind=? ORDER BY id", (k,)).fetchone())) for k in (1, 2)]})
    print("routes", len(routes), flush=True)
    # typing: letters through the real entry, at four speeds
    typed = [{"q": q, "near": n} for q, n in rnd.sample([x for x in set200 if len(x[0]) >= 5], 30)]
    dump(os.path.join(a.out, "typing.job.json"), {"queries": typed, "cadence_ms": [0, 40, 120, 300]})
    print("done", f"{time.time() - t0:.0f} s")


# ---------------------------------------------------------------------------------------- a second hand for along-a-route: all points against all stretches, no windows

R = 6371008.8


def rad(x):
    return x * math.pi / 180.0


def project(p, a, b):
    k = math.cos(rad(a[0]))
    bx, by = rad(b[1] - a[1]) * k * R, rad(b[0] - a[0]) * R
    px, py = rad(p[1] - a[1]) * k * R, rad(p[0] - a[0]) * R
    n = bx * bx + by * by
    t = 0.0 if n < 1e-9 else (px * bx + py * by) / n
    t = max(0.0, min(1.0, t))
    return t, math.hypot(px - bx * t, py - by * t)


def bearing(a, b):
    y = math.sin(rad(b[1] - a[1])) * math.cos(rad(b[0]))
    x = math.cos(rad(a[0])) * math.sin(rad(b[0])) - math.sin(rad(a[0])) * math.cos(rad(b[0])) * math.cos(rad(b[1] - a[1]))
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def adiff(a, b):
    return abs((b - a + 180) % 360 - 180)


def dist(a, b):
    h = math.sin(rad(b[0] - a[0]) / 2) ** 2 + math.cos(rad(a[0])) * math.cos(rad(b[0])) * math.sin(rad(b[1] - a[1]) / 2) ** 2
    return 2 * R * math.asin(math.sqrt(min(1.0, h)))


def along_passes(pts, cum, rows, reach, accept):
    """rows: (lat, lon, ...) in degrees. The passes (row index, along, offset) by the rule of RoadAhead.passes."""
    out = []
    las = [p[0] for p in pts]
    los = [p[1] for p in pts]
    s, n, w, e = min(las) - 0.002, max(las) + 0.002, min(los) - 0.002, max(los) + 0.002
    for k, r in enumerate(rows):
        if not (s <= r[0] <= n and w <= r[1] <= e):
            continue
        hits = []
        for i in range(len(pts) - 1):
            a, b = pts[i], pts[i + 1]
            if abs(a[0] - r[0]) > 0.15 and abs(b[0] - r[0]) > 0.15:
                continue
            t, d = project(r, a, b)
            if d > reach:
                continue
            br = bearing(a, b)
            if accept(k, d, br):
                hits.append((cum[i] + (cum[i + 1] - cum[i]) * t, d))
        if hits:
            best = hits[0]
            last = hits[0][0]
            for m in hits[1:]:
                if m[0] - last > 150.0:
                    out.append((k, best[0], best[1]))
                    best = m
                elif m[1] < best[1]:
                    best = m
                last = m[0]
            out.append((k, best[0], best[1]))
    out.sort(key=lambda x: (x[1], x[0]))
    return out


def inside(la, lo, ring):
    hit = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]):
        if (y1 > la) != (y2 > la) and lo < (x2 - x1) * (la - y1) / (y2 - y1) + x1:
            hit = not hit
    return hit


def twin_route(db, r):
    pts = [(c[1], c[0]) for c in r["coords"]]
    cum = [0.0]
    for a, b in zip(pts, pts[1:]):
        cum.append(cum[-1] + dist(a, b))
    spots = [(la / 1e6, lo / 1e6, kind, d, kmh, j) for kind, la, lo, d, kmh, j in db.execute("SELECT kind,lat,lon,dir,kmh,j FROM spot ORDER BY lat")]
    reach = {1: 30.0, 2: 15.0, 3: 10.0}

    def fits(k, d, br):
        s = spots[k]
        if d > reach[s[2]]:
            return False
        if s[3] is None:
            return True
        off = adiff(s[3], br)
        return off <= 135.0 if s[2] == 2 else off <= 60.0

    hits = []
    last_j = {}
    last = {1: -1, 3: -1}
    for k, al, off in along_passes(pts, cum, spots, 30.0, fits):
        s = spots[k]
        if s[2] == 2:
            at = last_j.get(s[5])
            if at is not None and al - hits[at]["along"] <= 300.0:
                continue
            last_j[s[5]] = len(hits)
        else:
            at = last[s[2]]
            if at >= 0 and al - hits[at]["along"] <= 25.0:
                continue
            last[s[2]] = len(hits)
        hits.append({"kind": s[2], "lat": s[0], "lon": s[1], "along": al, "j": s[5]})
    gates_all = [(la / 1e6, lo / 1e6, sub, road) for la, lo, sub, road in db.execute("SELECT lat,lon,sub,road FROM gate ORDER BY lat")]
    gates = [{"lat": gates_all[k][0], "lon": gates_all[k][1], "sub": gates_all[k][2], "road": gates_all[k][3], "along": al, "warn": gates_all[k][2] in (2, 3) and gates_all[k][3] >= 2}
             for k, al, off in along_passes(pts, cum, gates_all, 15.0, lambda k, d, b: True)]
    zones = []
    las = [p[0] for p in pts]
    los = [p[1] for p in pts]
    for zid, kind, name, en, s, n, w, e, ring in db.execute("SELECT id,kind,name,en,s,n,w,e,ring FROM zone WHERE s<=? AND n>=? AND w<=? AND e>=?", (max(las), min(las), max(los), min(los))):
        ring = json.loads(ring)
        # fine sampling every 5 m along the stretches near the box: slow, no crossings arithmetic
        ins = None
        metres = off_main = 0.0
        first = None
        last_in = 0.0
        entered = False
        start = inside(pts[0][0], pts[0][1], ring)
        now = start
        for i in range(len(pts) - 1):
            a, b = pts[i], pts[i + 1]
            if max(a[0], b[0]) < s or min(a[0], b[0]) > n or max(a[1], b[1]) < w or min(a[1], b[1]) > e:
                now = False
                continue
            ln = cum[i + 1] - cum[i]
            steps = max(1, int(ln / 5.0))
            for k in range(steps):
                t = (k + 0.5) / steps
                la, lo = a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
                here = inside(la, lo, ring)
                if here and not now:
                    entered = True
                if here:
                    if first is None:
                        first = cum[i] + ln * t
                    metres += ln / steps
                    last_in = cum[i] + ln * t
                    if entered and r["classes"][i] >= 2:
                        off_main += ln / steps
                now = here
        if first is None and not start:
            continue
        ends = inside(pts[-1][0], pts[-1][1], ring)
        warn = entered and (kind <= 2 or ends or off_main >= 50.0)
        zones.append({"id": zid, "kind": kind, "name": name or en, "inside_m": metres, "starts": start, "ends": ends, "entered": entered, "off_main_m": off_main, "warn": warn})
    return {"id": r["id"], "spots": hits, "gates": gates, "zones": zones}


def twin_near(db, p, key):
    cat = db.execute("SELECT id FROM cat WHERE key=?", (key,)).fetchone()[0]
    k = math.cos(rad(p[0]))
    d, dl = int(3000 / 110540 * 1e6), int(3000 / (111320 * k) * 1e6)
    la, lo = int(p[0] * 1e6), int(p[1] * 1e6)
    rows = db.execute(f"SELECT id,lat,lon FROM p WHERE lat BETWEEN {la - d} AND {la + d} AND lon BETWEEN {lo - dl} AND {lo + dl} AND cat={cat} AND kind=6 AND src&4=0").fetchall()
    got = sorted((S.km_between(p[0], p[1], r[1] / 1e6, r[2] / 1e6) * 1000.0, r[0]) for r in rows)
    return [x[1] for x in got if x[0] <= 3000.0][:5]


# ---------------------------------------------------------------------------------------- the comparison


def ms(ns):
    return ns / 1e6


def times(got):
    rows = [g for g in got if "total" in g]
    if not rows:
        return {}
    out = {"n": len(rows), "total_median_ms": round(statistics.median(ms(g["total"]) for g in rows), 2), "total_worst_ms": round(max(ms(g["total"]) for g in rows), 2),
           "total_p90_ms": round(sorted(ms(g["total"]) for g in rows)[int(len(rows) * 0.9)], 2)}
    for i, st in enumerate(STAGES):
        v = [ms(g["ns"][i]) for g in rows]
        out[st] = {"median": round(statistics.median(v), 3), "worst": round(max(v), 2)}
    return out


def compare_searches(name, exp, got, report):
    exp = exp["searches"]
    got = got.get("searches", [])
    c = {"queries": len(exp), "answered": len(got), "errors": 0, "top1": 0, "top3": 0, "ids": 0, "scores": 0, "km": 0, "shown": 0, "detail": 0, "shown_en": 0, "approx": 0, "match": 0, "cand": 0}
    examples = []
    job = load(os.path.join(report["out"], name + ".job.json"))["searches"]
    for i, (e, g) in enumerate(zip(exp, got)):
        if "error" in g or "error" in e:
            if ("error" in g) != ("error" in e):
                c["errors"] += 1
                examples.append({"q": job[i]["q"], "near": job[i]["near"], "py": e.get("error"), "kt": g.get("error")})
            continue
        bad = []
        if e["ids"][:1] != g["ids"][:1]:
            c["top1"] += 1
            bad.append("top1")
        if e["ids"][:3] != g["ids"][:3]:
            c["top3"] += 1
            bad.append("top3")
        if e["ids"] != g["ids"]:
            c["ids"] += 1
            bad.append("ids")
        for k in ("scores", "km", "shown", "detail", "shown_en", "approx", "match", "cand"):
            if e[k] != g[k]:
                c[k] += 1
                bad.append(k)
        if bad:
            examples.append({"q": job[i]["q"], "near": job[i]["near"], "bad": bad, "py": {k: e[k] for k in bad if k in e}, "kt": {k: g[k] for k in bad if k in g}, "py_ids": e["ids"], "kt_ids": g["ids"], "py_scores": e["scores"], "kt_scores": g["scores"]})
    report["searches"][name] = {"counts": c, "times": times(got)}
    report["examples"][name] = examples[:30]
    print(name, json.dumps(c), flush=True)
    if report["searches"][name]["times"]:
        t = report["searches"][name]["times"]
        print("   ms: median", t["total_median_ms"], "p90", t["total_p90_ms"], "worst", t["total_worst_ms"], " ".join(f'{s}={t[s]["median"]}/{t[s]["worst"]}' for s in STAGES), flush=True)


def compare(a):
    report = {"out": a.out, "searches": {}, "examples": {}}
    for name in ("set200", "golden", "prefixes", "finished", "own"):
        f = os.path.join(a.out, name + ".got.json")
        if os.path.exists(f):
            compare_searches(name, load(os.path.join(a.out, name + ".exp.json")), load(f), report)
    f = os.path.join(a.out, "set200.warm.got.json")
    if os.path.exists(f):
        compare_searches("set200", load(os.path.join(a.out, "set200.exp.json")), load(f), report)
    # the golden file of the build, the same ids and scores
    gold = os.path.join(a.out, "golden.got.json")
    if a.golden and os.path.exists(a.golden) and os.path.exists(gold):
        g = load(a.golden)["search"]
        got = load(gold)["searches"]
        bad = sum(1 for x, y in zip(g, got) if x["ids"] != y.get("ids") or x["scores"] != y.get("scores"))
        print("search-golden.json against the app: ", len(g) - bad, "of", len(g), "the same ids and scores")
        report["golden_file_mismatch"] = bad
    f = os.path.join(a.out, "reverse.got.json")
    if os.path.exists(f):
        exp = load(os.path.join(a.out, "reverse.exp.json"))["reverse"]
        got = load(f)["reverse"]
        bad = []
        for e, g in zip(exp, got):
            g2 = None if g is None else {k: v for k, v in g.items() if k != "ns"}
            if e != g2:
                bad.append({"py": e, "kt": g2})
        ns = [g["ns"] for g in got if g]
        print("reverse", len(exp), "mismatch", len(bad), "ms median", round(statistics.median(ns) / 1e6, 2), "worst", round(max(ns) / 1e6, 2), "kinds", {k: sum(1 for g in got if g and g["what"] == k) for k in ("poi", "road", "area", "district")}, "none", sum(1 for g in got if g is None), flush=True)
        report["reverse_mismatch"] = bad[:20]
    f = os.path.join(a.out, "text.got.json")
    if os.path.exists(f):
        exp = load(os.path.join(a.out, "text.exp.json"))
        got = load(f)
        diff = [(i, exp["norm_bmp"][i], got["norm_bmp"][i]) for i in range(0x10000) if exp["norm_bmp"][i] != got["norm_bmp"][i]]
        print("norm of the BMP: ", 0x10000 - len(diff), "of 65536 the same;", "first differences:", [(hex(i), x, y, unicodedata.name(chr(i), "?")) for i, x, y in diff[:12]], flush=True)
        report["norm_bmp_diff"] = [(hex(i), x, y, unicodedata.category(chr(i))) for i, x, y in diff[:400]]
        report["norm_bmp_diff_n"] = len(diff)
        bad = [(e, g) for e, g in zip(exp["words"], got["words"]) if e != g]
        print("text functions of names:", len(exp["words"]), "mismatch", len(bad), flush=True)
        report["words_mismatch"] = bad[:20]
    f = os.path.join(a.out, "routes.got.json")
    if os.path.exists(f):
        exp = load(os.path.join(a.out, "routes.exp.json"))
        got = load(f)
        rows = []
        for e, g in zip(exp["routes"], got["routes"]):
            sa = [(x["kind"], x["lat"], x["lon"]) for x in e["spots"]]
            sb = [(x["kind"], x["lat"], x["lon"]) for x in g["spots"]]
            ga = [(x["lat"], x["lon"], x["sub"], x["warn"]) for x in e["gates"]]
            gb = [(x["lat"], x["lon"], x["sub"], x["warn"]) for x in g["gates"]]
            za = {x["id"]: x for x in e["zones"]}
            zb = {x["id"]: x for x in g["zones"]}
            zbad = [k for k in set(za) | set(zb) if k not in za or k not in zb or za[k]["warn"] != zb[k]["warn"] or za[k]["ends"] != zb[k]["ends"] or za[k]["entered"] != zb[k]["entered"]]
            metres = [(k, round(za[k]["inside_m"]), round(zb[k]["inside_m"]), round(za[k]["off_main_m"]), round(zb[k]["off_main_m"] or 0)) for k in set(za) & set(zb)]
            counts = {k: sum(1 for x in g["spots"] if x["kind"] == k) for k in (1, 2, 3)}
            row = {"id": g["id"], "km": round(g["metres"] / 1000, 1), "cameras": counts[1], "junctions": counts[2], "bumps": counts[3], "spots_same_as_second_hand": sa == sb, "gates_same": ga == gb, "zones_bad": zbad,
                   "gates_warn": [x for x in g["gates"] if x["warn"]], "zones": [{k: x[k] for k in ("id", "kind", "name", "inside_m", "starts", "ends", "off_main_m", "warn")} for x in g["zones"]], "metres_py_kt": metres,
                   "fuel": [(x["name"], round(x["along"] / 1000, 1), round(x["off"]), x["right"]) for x in g["fuel"]], "mosque": [(x["name"], round(x["along"] / 1000, 1), round(x["off"]), x["right"]) for x in g["mosque"]],
                   "ms": {k: round(g[k + "_ns"] / 1e6, 1) for k in ("spots", "warn", "fuel", "mosque")}}
            rows.append(row)
            print(json.dumps({k: row[k] for k in ("id", "km", "cameras", "junctions", "bumps", "spots_same_as_second_hand", "gates_same", "zones_bad", "ms")}, ensure_ascii=False), flush=True)
            for z in row["zones"]:
                print("    zone", json.dumps(z, ensure_ascii=False))
            for g2 in row["gates_warn"]:
                print("    gate warns", json.dumps(g2))
            print("    fuel", row["fuel"][:2], "mosque", row["mosque"][:2], flush=True)
        report["routes"] = rows
        pts = [[h["id"] for h in p["hits"]] for p in got["points"]]
        print("parking near a point the same:", pts == exp["points"], pts, flush=True)
        for e, g in zip(exp["haram"], got["haram"]):
            print("haram", g["id"], g["name"], g["points"], "points; the same ring:", e["json"] == g["json"], "grand mosque inside:", g["grand_mosque_inside"], "riyadh inside:", g["riyadh_inside"], flush=True)
    f = os.path.join(a.out, "typing.got.json")
    if os.path.exists(f):
        runs = load(f)["runs"]
        for c in sorted({r["cadence"] for r in runs}):
            x = [r for r in runs if r["cadence"] == c]
            fin = [r["final_ms"] for r in x]
            print(f"typing every {c} ms: {len(x)} queries, last answer is the whole query's own: {sum(r['last_is_whole'] for r in x)}; ms after the last letter: median {round(statistics.median(fin), 1)} worst {round(max(fin), 1)}; letters {sum(r['letters'] for r in x)} begun {sum(r['begun'] for r in x)} finished {sum(r['finished'] for r in x)} stopped {sum(r['stopped'] for r in x)} dropped {sum(r['dropped'] for r in x)}; answers shown {sum(len(r['answered']) for r in x)}", flush=True)
        report["typing"] = runs
    f = os.path.join(a.out, "pss.got.json")
    if os.path.exists(f):
        report["pss"] = load(f)
        print("pss", json.dumps(report["pss"]), flush=True)
    dump(os.path.join(a.out, "report.json"), report)
    print("report:", os.path.join(a.out, "report.json"))


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("what", choices=["fixture", "prepare", "compare"])
    ap.add_argument("--db")
    ap.add_argument("--routes")
    ap.add_argument("--out", required=True)
    ap.add_argument("--golden")
    a = ap.parse_args()
    {"fixture": fixture, "prepare": prepare, "compare": compare}[a.what](a)


if __name__ == "__main__":
    main()
