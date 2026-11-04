#!/usr/bin/env python
"""What became of the rows the tester judged junk (tests/junk-judged-200.json, on the file of 2026-11-04 14:02), in a newer places.db. A machine's look, by name and position: it says
what changed, not whether the new file is clean. A fresh sample needs a person's judgement again (junk_sample.py, junk_tally.py).

  python -I junk_recheck.py --sample places\\test\\junk-sample-200.tsv --judged tests\\junk-judged-200.json --db out\\places.db --out places\\test\\run3\\junk-recheck.json
"""
import argparse
import collections
import csv
import json
import math
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import search_ref  # noqa: E402


def km(a, b, c, d):
    p1, p2 = math.radians(a), math.radians(c)
    x = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(d - b) / 2) ** 2
    return 12742 * math.asin(min(1, math.sqrt(x)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sample", required=True)
    ap.add_argument("--judged", required=True)
    ap.add_argument("--db", required=True)
    ap.add_argument("--out")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    with open(a.sample, encoding="utf-8", newline="") as f:
        sample = {r["id"]: r for r in csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE)}
    with open(a.judged, encoding="utf-8") as f:
        judged = json.load(f)
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    cats = {i: k for i, k in db.execute("SELECT id,key FROM cat")}
    out = []
    tally = collections.Counter()
    for j in judged["junk"]:
        r = sample[str(j["id"])]
        la, lo = float(r["lat"]), float(r["lon"])
        d = 1600  # about 170 m
        rows = db.execute("SELECT id,name,alt,cat,lat,lon,rank,src FROM p WHERE kind=6 AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?", (int(la * 1e6) - d, int(la * 1e6) + d, int(lo * 1e6) - d, int(lo * 1e6) + d)).fetchall()
        want = set(search_ref.query_words(r["name"])) | set(search_ref.query_words(r["alt"] or ""))
        same = []
        for x in rows:
            ws = set(search_ref.query_words(x[1])) | set(search_ref.query_words(x[2] or ""))
            if want and (want <= ws or ws <= want) and ws:
                same.append({"id": x[0], "name": x[1], "alt": x[2], "cat": cats[x[3]], "m": round(km(la, lo, x[4] / 1e6, x[5] / 1e6) * 1000), "rank": x[6], "coarse": bool(x[7] & 4)})
        if not same:
            state = "gone"
        else:
            best = min(same, key=lambda x: x["m"])
            cls = j["class"]
            if cls == "duplicate":
                # the row it duplicated: nothing else of its words or merged into one
                state = "still there, maybe merged"
            elif cls == "wrong_category":
                state = "category changed" if best["cat"] != r["cat"] else "category unchanged"
            elif cls == "wrong_location":
                state = "marked coarse" if best["coarse"] else "unchanged"
            else:
                state = "marked coarse" if best["coarse"] else "still there"
        tally[(j["class"], state)] += 1
        out.append({"class": j["class"], "why": j["why"], "was": {"name": r["name"], "alt": r["alt"], "cat": r["cat"], "rank": r["rank"]}, "state": state, "now": same[:3]})
    for k, v in sorted(tally.items()):
        print(k, v)
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            json.dump({"tally": {f"{k[0]} / {k[1]}": v for k, v in sorted(tally.items())}, "rows": out}, f, ensure_ascii=False, indent=0)
    for x in out:
        print(x["class"], "|", x["was"]["name"], "|", x["was"]["cat"], "->", x["state"], "|", [(n["name"], n["cat"], n["m"], n["rank"]) for n in x["now"][:2]])


if __name__ == "__main__":
    main()
