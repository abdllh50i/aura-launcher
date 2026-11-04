#!/usr/bin/env python
"""Field by field: the ARM32 library's answers against the PC service's reference answers.

  python -I arm32_compare.py <ARM32 answers folder> <reference folder> <arm32 run log>
"""
import glob
import json
import math
import os
import sys

sys.stdout.reconfigure(encoding="utf8")
arm_dir, ref_dir, log = sys.argv[1:4]


def walk(a, b, path, out):
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a or k not in b:
                out.append((path + "." + k, "missing in " + ("arm32" if k not in a else "ref"), a.get(k), b.get(k)))
            else:
                walk(a[k], b[k], path + "." + k, out)
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            out.append((path, "list length", len(a), len(b)))
        for i, (x, y) in enumerate(zip(a, b)):
            walk(x, y, f"{path}[{i}]", out)
    elif isinstance(a, float) or isinstance(b, float):
        try:
            if not math.isclose(float(a), float(b), rel_tol=0, abs_tol=0):
                out.append((path, "number", a, b))
        except (TypeError, ValueError):
            out.append((path, "type", a, b))
    elif a != b:
        out.append((path, "value", a, b))


total, same_bytes, same_json, diffs_all = 0, 0, 0, {}
for f in sorted(glob.glob(os.path.join(arm_dir, "*.json"))):
    n = os.path.basename(f)
    rf = os.path.join(ref_dir, n)
    if not os.path.exists(rf):
        print(n, "no reference")
        continue
    total += 1
    ab, rb = open(f, "rb").read(), open(rf, "rb").read()
    if ab == rb:
        same_bytes += 1
    a, b = json.loads(ab), json.loads(rb)
    out = []
    walk(a, b, "", out)
    if not out:
        same_json += 1
    else:
        diffs_all[n] = out
    r = a.get("routes", [{}])
    km = r[0].get("distance", 0) / 1000 if r and "distance" in r[0] else None
    print(f"{n:42} bytes {len(ab):8} / {len(rb):8} {'identical bytes' if ab == rb else ''} {'same json' if not out else f'{len(out)} differences'} routes {len(a.get('routes', []))} km {km}")
print(f"\n{total} answers compared: {same_bytes} byte-identical, {same_json} same JSON")
for n, out in diffs_all.items():
    big = [d for d in out if d[1] != "number" or abs(float(d[2]) - float(d[3])) > 0.5]
    print(n, len(out), "differences, over 0.5:", len(big), "examples:", out[:4])
print("\nTimes under qemu (run 1 / run 2 ms), from the log:")
lines = open(log, encoding="utf8", errors="replace").read().splitlines()
keep = [l for l in lines if l.startswith(("sizeof", "dlopen", "createActor", "after createActor", "ThrowNew", "JNI function", "bionic", "exit code", "done")) or " run " in l]
print("\n".join(keep[:12]))
for l in lines:
    if l.startswith(("abha-riyadh.json", "dammam-jeddah.json", "riyadh-jeddah.json", "dammam-riyadh.json", "jubail-dammam.json", "outside.json")) and ("run" in l or "VmHWM" in l):
        print(l)
print(lines[-3:])
