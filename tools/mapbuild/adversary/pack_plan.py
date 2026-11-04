#!/usr/bin/env python
"""A dry run of maps.md section 15.2: every piece cut into 64 MiB chunks, each chunk gzipped, sizes and hashes as a manifest would hold them.
Nothing is uploaded. Also measures what the plan's 'no gzip for tiles' costs, per chunk.

  python -I pack_plan.py <out.json> name=path [name=path ...] [--level 6] [--procs 4]
"""
import argparse
import hashlib
import json
import multiprocessing as mp
import os
import time
import zlib

CHUNK = 64 * 1024 * 1024


def one(job):
    path, name, i, off, level = job
    with open(path, "rb") as f:
        f.seek(off)
        raw = f.read(CHUNK)
    t0 = time.perf_counter()
    c = zlib.compressobj(level, zlib.DEFLATED, 31)
    gz = c.compress(raw) + c.flush()
    ms = (time.perf_counter() - t0) * 1000
    t0 = time.perf_counter()
    zlib.decompress(gz, 31)
    un = (time.perf_counter() - t0) * 1000
    return {"name": name, "i": i, "raw": len(raw), "gz": len(gz), "sha256_gz": hashlib.sha256(gz).hexdigest(), "gzip_ms": round(ms), "gunzip_ms": round(un)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("pieces", nargs="+")
    ap.add_argument("--level", type=int, default=6)
    ap.add_argument("--procs", type=int, default=4)
    a = ap.parse_args()
    jobs, sizes = [], {}
    for p in a.pieces:
        name, path = p.split("=", 1)
        size = os.path.getsize(path)
        sizes[name] = size
        for i, off in enumerate(range(0, size, CHUNK)):
            jobs.append((path, name, i, off, a.level))
    with mp.Pool(a.procs) as pool:
        res = pool.map(one, jobs, 1)
    out = {"chunk": CHUNK, "level": a.level, "pieces": {}, "chunks": res}
    for name, size in sizes.items():
        rows = [r for r in res if r["name"] == name]
        out["pieces"][name] = {"raw": size, "gz": sum(r["gz"] for r in rows), "chunks": len(rows), "largest_gz": max(r["gz"] for r in rows)}
    out["total_raw"] = sum(sizes.values())
    out["total_gz"] = sum(r["gz"] for r in res)
    out["assets"] = len(res)
    out["largest_gz"] = max(r["gz"] for r in res)
    out["gunzip_pc_ms_total"] = sum(r["gunzip_ms"] for r in res)
    with open(a.out, "w", encoding="utf8") as f:
        json.dump(out, f, indent=1)
    print(json.dumps({k: v for k, v in out.items() if k != "chunks"}, indent=1))


if __name__ == "__main__":
    main()
