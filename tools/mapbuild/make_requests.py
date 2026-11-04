#!/usr/bin/env python
"""Writes the fixed trips as request files, for the harness that reads a tar through the library.   make_requests.py <out dir>"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trips  # noqa: E402

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
for group in (trips.LONG, trips.SHORT, trips.EDGES):
    for tid, _, a, b in group:
        with open(os.path.join(out, tid + ".json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(trips.request(a, b), f)
