#!/usr/bin/env python
"""A check nobody tuned the search for: a random sample of the rows, each searched by its own name from a point near it. Is it in the first 3, the first 12?

  python -I self_retrieval.py places.db [--n 1000] [--box s,n,w,e] [--seed 7]

What is in the same spot under the same name counts as found. Generic rows ("مسجد" with no name of its own) are left out: their name asks for a kind.
Also searches the name as people type it: with its first word only, and for an Arabic name without its tashkeel and with one common misspelling (ة as ه).
"""
import argparse
import os
import random
import sqlite3
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import search_ref as S  # noqa: E402


def found(res, row, top, near):
    """The row itself, or the same name within 300 m (one place twice), or the same name nearer to the driver than this row (a nearer branch of a chain
    or a nearer piece of a road is the right answer, and search shows one place once)."""
    keys = {" ".join(sorted(S.word_set(x))) for x in (row[2], row[3]) if x}
    own_km = S.km_between(near[0], near[1], row[4] / 1e6, row[5] / 1e6)
    for r in res[:top]:
        if r["id"] == row[0]:
            return True
        if {" ".join(sorted(S.word_set(x))) for x in (r["name"], r["alt"]) if x} & keys:
            if S.km_between(r["lat"], r["lon"], row[4] / 1e6, row[5] / 1e6) < (6.0 if row[1] == 7 else 0.3) or (r["km"] is not None and r["km"] <= own_km):
                return True
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("--n", type=int, default=1000)
    ap.add_argument("--box", default="26.10,27.25,49.40,50.30")
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    syn = S.Syn(db)
    s, n, w, e = [int(float(x) * 1e6) for x in a.box.split(",")]
    generic = {r[0] for r in db.execute("SELECT ar FROM cat")} | {r[0] for r in db.execute("SELECT en FROM cat")}
    rows = [r for r in db.execute("SELECT id,kind,name,alt,lat,lon FROM p WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? AND kind IN (5,6,7)", (s, n, w, e)) if r[2] not in generic and len(S.word_set(r[2])) >= 1]
    random.Random(a.seed).shuffle(rows)
    rows = rows[:a.n]
    rnd = random.Random(a.seed + 1)
    tally = {}
    misses = []

    def run(label, query, near, row):
        t = tally.setdefault(label, [0, 0, 0])
        res = S.search(db, query, near, syn=syn)
        t[0] += 1
        hit3 = found(res, row, 3, near)
        t[1] += hit3
        hit12 = found(res, row, 12, near)
        t[2] += hit12
        if not hit12 and len(misses) < 40 and label in ("whole name", "the other script"):
            misses.append((label, query, row[1], [x["name"] for x in res[:3]]))

    t0 = time.time()
    for r in rows:
        la, lo = r[4] / 1e6 + rnd.uniform(-0.03, 0.03), r[5] / 1e6 + rnd.uniform(-0.03, 0.03)  # about 3 km away
        run("whole name", r[2], (la, lo), r)
        if r[3]:
            run("the other script", r[3], (la, lo), r)
        ws = r[2].split()
        if len(ws) >= 2:
            run("first two words", " ".join(ws[:2]), (la, lo), r)
        if "ة" in r[2]:
            run("ة typed as ه", r[2].replace("ة", "ه"), (la, lo), r)
        if len(r[2]) >= 6:
            run("typing, name without its last 2 letters", r[2][:-2], (la, lo), r)
    print(f"{len(rows)} rows of {a.box}; {time.time() - t0:.0f} s")
    for k, (t, h3, h12) in tally.items():
        print(f"  {k}: {t} searches; in the first 3: {100 * h3 / t:.1f}%; in the first 12: {100 * h12 / t:.1f}%")
    print("misses of the whole name and of the other script (which, query, kind, what came first):")
    for m in misses:
        print("  ", m)


if __name__ == "__main__":
    main()
