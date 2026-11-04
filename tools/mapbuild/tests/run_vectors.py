#!/usr/bin/env python
"""Checks search_ref.py's text functions against tests/norm_vectors.json. The Kotlin test must read the same file.

  python -I run_vectors.py [places.db]     with a db, the MATCH strings too
"""
import json
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import search_ref as S  # noqa: E402


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    with open(os.path.join(HERE, "norm_vectors.json"), encoding="utf-8") as f:
        v = json.load(f)
    bad = total = 0

    def same(kind, inp, got, want):
        nonlocal bad, total
        total += 1
        if got != want:
            bad += 1
            print(f"FAIL {kind} {inp!r}: got {got!r}, want {want!r}")

    for a, b in v["norm"]:
        same("norm", a, S.norm(a), b)
    for a, b in v["base"]:
        same("base", a, S.base(a), b)
    for a, b in v["latin_base"]:
        same("latin_base", a, S.latin_base(a), b)
    for a, b in v["query_words"]:
        same("query_words", a, S.query_words(a), b)
    for a, b in v["skeletons"]:
        same("skeletons", a, S.skeletons(S.norm(a)), b)
    for a, b, c in v["lev1"]:
        same("lev1", (a, b), S.lev1(a, b), c)
    if len(sys.argv) > 1:
        db = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
        syn = S.Syn(db)
        cat = {k: i for i, k in db.execute("SELECT id,key FROM cat")}
        for q, want in v["fts"]:
            for k, i in cat.items():
                want = want.replace("{" + k + "}", S.CT + str(i))
            same("fts", q, S.fts_string(S.plan_query(q, syn)), want)
    print(f"{total - bad} of {total} vectors pass")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
