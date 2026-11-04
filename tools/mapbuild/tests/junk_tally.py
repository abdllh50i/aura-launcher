#!/usr/bin/env python
"""Adds up tests/junk-judged-200.json against the sample from junk_sample.py.

  python -I junk_tally.py --sample places\\test\\junk-sample-200.tsv --judged tests\\junk-judged-200.json
"""
import argparse
import collections
import csv
import json
import math
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sample", required=True)
    ap.add_argument("--judged", required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    with open(a.sample, encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE))
    with open(a.judged, encoding="utf-8") as f:
        j = json.load(f)
    ids = {r["id"] for r in rows}
    junk = {str(x["id"]): x for x in j["junk"]}
    missing = [i for i in list(junk) + [str(x["id"]) for x in j["minor"]] if i not in ids]
    if missing:
        raise SystemExit(f"judged ids not in the sample: {missing}")
    n = len(rows)
    k = len(junk)
    p = k / n
    z = 1.96
    den = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / den
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / den
    print(f"sample {n}, junk {k} = {100 * p:.1f} % (Wilson 95 %: {100 * (centre - half):.1f}-{100 * (centre + half):.1f} %), minor, not counted: {len(j['minor'])}")
    print("by class:", collections.Counter(x["class"] for x in j["junk"]).most_common())

    def rate(key):
        g = collections.defaultdict(lambda: [0, 0])
        for r in rows:
            b = key(r)
            g[b][1] += 1
            g[b][0] += r["id"] in junk
        return {b: f"{v[0]}/{v[1]}" for b, v in sorted(g.items())}

    print("by source:", rate(lambda r: r["src"]))

    def band(r):
        x = int(r["rank"])
        return "rank <30" if x < 30 else "rank 30-44" if x < 45 else "rank 45-59" if x < 60 else "rank 60+"

    print("by rank:", rate(band))
    print("kinds, top 5 right:", sum(j["kinds_top5"]["per_kind"].values()), "of", j["kinds_top5"]["of_each"] * len(j["kinds_top5"]["per_kind"]), j["kinds_top5"]["per_kind"])


if __name__ == "__main__":
    main()