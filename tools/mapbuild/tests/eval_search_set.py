#!/usr/bin/env python
"""The tester's check of the search: runs tests/search-set.json through search_ref.py and scores the answers against a plain scan of table p.

The truth does not use the search's code: no FTS, no norm(), no skeletons, only a regex over a simple fold of name and alt, category keys, kinds
and distance. Only search() itself comes from search_ref.py.

  python -I eval_search_set.py --db D:\\k2501-work\\mapbuild\\out\\places.db --set tests\\search-set.json --out results.json
"""
import argparse
import json
import math
import os
import re
import sqlite3
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import search_ref  # noqa: E402

MARKS = "ًٌٍَُِّْـ"


def fold(s):
    s = (s or "").lower()
    for a, b in (("أ", "ا"), ("إ", "ا"), ("آ", "ا"), ("ٱ", "ا"), ("ى", "ي"), ("ة", "ه"), ("ؤ", "و"), ("ئ", "ي"), ("ء", "")):
        s = s.replace(a, b)
    return "".join(c for c in s if c not in MARKS)


def km(a, b, c, d):
    p1, p2 = math.radians(a), math.radians(c)
    x = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(d - b) / 2) ** 2
    return 12742 * math.asin(min(1, math.sqrt(x)))


def truth_ids(case, rows, texts, cat_ids):
    """Ids of the rows that satisfy a case's expect, and the same without the distance from the car."""
    e = case["expect"]
    rx = re.compile(fold(e["name_re"])) if e.get("name_re") else None
    cats = {cat_ids[c] for c in e.get("cat", [])}
    kinds = e.get("kind")
    either = e.get("name_or_cat")
    place = e.get("place")
    within = e.get("within_km")
    near = case["near"]
    ok_any, ok_bar = set(), set()
    for r, t in zip(rows, texts):
        if kinds and r[1] not in kinds:
            continue
        nm = bool(rx.search(t)) if rx else None
        ct = (r[4] in cats) if cats else None
        if either:
            if not (nm or ct):
                continue
        else:
            if nm is False or ct is False:
                continue
        la, lo = r[5] / 1e6, r[6] / 1e6
        if place and km(place[0], place[1], la, lo) > place[2]:
            continue
        ok_any.add(r[0])
        if within is None or km(near[0], near[1], la, lo) <= within:
            ok_bar.add(r[0])
    return ok_any, ok_bar


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--set", required=True)
    ap.add_argument("--out")
    ap.add_argument("--limit", type=int, default=12)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    syn = search_ref.Syn(db)
    cat_ids = {k: i for i, k in db.execute("SELECT id,key FROM cat")}
    cat_key = {i: k for k, i in cat_ids.items()}
    rows = db.execute("SELECT id,kind,name,alt,cat,lat,lon FROM p").fetchall()
    texts = [fold(r[2]) + " | " + fold(r[3]) for r in rows]
    with open(a.set, encoding="utf-8") as f:
        doc = json.load(f)
    out = []
    for c in doc["cases"]:
        any_ids, bar_ids = truth_ids(c, rows, texts, cat_ids)
        t0 = time.perf_counter()
        res = search_ref.search(db, c["q"], tuple(c["near"]), a.limit, syn=syn)
        ms = (time.perf_counter() - t0) * 1000.0
        rank = next((i + 1 for i, r in enumerate(res) if r["id"] in bar_ids), None)
        rank_any = next((i + 1 for i, r in enumerate(res) if r["id"] in any_ids), None)
        top = [{"id": r["id"], "kind": r["kind"], "name": r["name"], "alt": r["alt"], "cat": cat_key.get(r["cat"]), "km": r["km"], "rank": r["rank"], "score": r["score"], "ok": r["id"] in bar_ids} for r in res[:5]]
        right5 = sum(1 for r in res[:5] if r["id"] in any_ids)
        out.append({"id": c["id"], "group": c["group"], "q": c["q"], "at": c["at"], "expect": c["expect"], "truth_rows": len(any_ids), "truth_rows_in_bar": len(bar_ids),
                    "truth_nearest_km": c.get("truth_nearest_km"), "rank": rank, "rank_ignoring_distance": rank_any, "right_of_top5": right5, "n": len(res), "ms": round(ms, 1), "top": top, "note": c.get("note")})

    def rates(items):
        n = len(items)
        return {"n": n, "top1": sum(1 for x in items if x["rank"] == 1), "top3": sum(1 for x in items if x["rank"] and x["rank"] <= 3), "top5": sum(1 for x in items if x["rank"] and x["rank"] <= 5), "top12": sum(1 for x in items if x["rank"])}

    summary = {"all": rates(out), "data_has_it": rates([x for x in out if x["truth_rows_in_bar"]]), "groups": {}}
    for g in sorted({x["group"] for x in out}):
        summary["groups"][g] = rates([x for x in out if x["group"] == g])
    ms = sorted(x["ms"] for x in out)
    summary["pc_ms"] = {"median": ms[len(ms) // 2], "worst": ms[-1]}
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            json.dump({"summary": summary, "cases": out}, f, ensure_ascii=False, indent=0)
    print(json.dumps(summary, ensure_ascii=False))
    for x in out:
        if not x["rank"] or x["rank"] > 5:
            print(f"MISS #{x['id']} [{x['group']}] {x['q']} @ {x['at']} truth {x['truth_rows_in_bar']}/{x['truth_rows']} nearest {x['truth_nearest_km']} rank {x['rank']} any {x['rank_ignoring_distance']}")
            for r in x["top"][:3]:
                print(f"     {r['km']} km  k{r['kind']} {r['cat']} r{r['rank']} {r['name']} | {r['alt']}")


if __name__ == "__main__":
    main()