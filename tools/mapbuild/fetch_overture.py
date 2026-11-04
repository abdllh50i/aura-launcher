#!/usr/bin/env python
"""Overture Maps rows for Saudi Arabia, read from the public bucket by row group (anonymous, nothing else is fetched).

  python fetch_overture.py --out D:\\k2501-work\\mapbuild\\src\\overture [--release 2026-10-21.0] [--places]

Writes divisions-sa.parquet and division_areas-sa.parquet, with --places also places-box.parquet (every row of the row
groups that touch the Kingdom's box, because a place's country is only known from its address). Public releases live
about 60 days: without --release the current one is read from the STAC catalog.

Row-group statistics decide what is read. For the two division types the cheap columns come first, so that a row group
with no Saudi row never costs its geometry column. The script prints the bytes it asked for.
"""
import argparse
import json
import os
import sys
import urllib.request



class _NoPandas:
    """pyarrow only peeks at pandas, which is never used here: a pandas built for another numpy must not break it."""

    def find_spec(self, name, path=None, target=None):
        if name == "pandas" or name.startswith("pandas."):
            raise ImportError("pandas is not used here")


sys.meta_path.insert(0, _NoPandas())
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.fs as pafs
import pyarrow.parquet as pq

BUCKET = "overturemaps-us-west-2"
KSA_BOX = (34.4, 16.3, 55.8, 32.3)
STAC = "https://stac.overturemaps.org/catalog.json"

DIVISION_COLS = ["id", "subtype", "class", "local_type", "country", "region", "admin_level", "parent_division_id",
                 "population", "names", "wikidata", "capital_of_divisions", "bbox", "geometry"]
AREA_COLS = ["id", "division_id", "subtype", "class", "country", "region", "admin_level", "is_land", "is_territorial",
             "names", "bbox", "geometry"]
PLACE_COLS = ["id", "bbox", "confidence", "basic_category", "names", "brand", "phones", "websites", "addresses",
              "sources", "taxonomy"]


def current_release():
    with urllib.request.urlopen(STAC, timeout=30) as r:
        cat = json.load(r)
    rel = cat.get("latest") or ""
    if rel:
        return rel
    ids = sorted(l["href"].split("/")[1] for l in cat.get("links", []) if l.get("rel") == "child" and "/" in l.get("href", ""))
    if not ids:
        raise SystemExit("STAC catalog names no release")
    return ids[-1]


def groups_touching(fs, base, box):
    """(path, group, rows, compressed bytes per top-level column) of the row groups whose bbox statistics touch box."""
    out = []
    files = [f for f in fs.get_file_info(pafs.FileSelector(base)) if f.is_file]
    for fi in files:
        with fs.open_input_file(fi.path) as fh:
            md = pq.read_metadata(fh)
        names = [md.schema.column(i).path for i in range(md.num_columns)]
        ix = {n: names.index(n) for n in names if n.startswith("bbox.")}
        for g in range(md.num_row_groups):
            rg = md.row_group(g)
            s = {k: rg.column(i).statistics for k, i in ix.items()}
            if any(v is None or not v.has_min_max for v in s.values()):
                continue
            b = (s["bbox.xmin"].min, s["bbox.ymin"].min, s["bbox.xmax"].max, s["bbox.ymax"].max)
            if b[2] >= box[0] and b[0] <= box[2] and b[3] >= box[1] and b[1] <= box[3]:
                sizes = {}
                for c in range(rg.num_columns):
                    cc = rg.column(c)
                    k = cc.path_in_schema.split(".")[0]
                    sizes[k] = sizes.get(k, 0) + cc.total_compressed_size
                out.append((fi.path, g, rg.num_rows, sizes))
    return out


def read_saudi(fs, base, cols, out_path):
    """Writes the rows with country SA. Returns (rows, bytes asked for)."""
    groups = groups_touching(fs, base, KSA_BOX)
    asked = 0
    writer = None
    rows = 0
    try:
        for path, g, n, sizes in groups:
            with fs.open_input_file(path) as fh:
                pf = pq.ParquetFile(fh)
                c = pf.read_row_group(g, columns=["country"])
                asked += sizes.get("country", 0)
                sa = pa.scalar("SA", type=c.column(0).type)
                if pc.sum(pc.cast(pc.equal(c.column(0), sa), pa.int32())).as_py() in (None, 0):
                    print(f"  {os.path.basename(path)[:12]} group {g}: no Saudi row, {n} rows skipped", flush=True)
                    continue
                keep = [k for k in cols if k in pf.schema_arrow.names]
                t = pf.read_row_group(g, columns=keep)
                asked += sum(sizes.get(k, 0) for k in keep if k != "country")
            t = t.filter(pc.equal(t.column("country"), sa))
            rows += t.num_rows
            print(f"  {os.path.basename(path)[:12]} group {g}: {t.num_rows} of {n} rows, asked {asked / 1e6:.1f} MB so far", flush=True)
            if writer is None:
                writer = pq.ParquetWriter(out_path, t.schema, compression="zstd", compression_level=9)
            writer.write_table(t)
    finally:
        if writer:
            writer.close()
    return rows, asked


def read_places(fs, base, out_path):
    groups = groups_touching(fs, base, KSA_BOX)
    asked = 0
    writer = None
    rows = 0
    try:
        for path, g, n, sizes in groups:
            with fs.open_input_file(path) as fh:
                pf = pq.ParquetFile(fh)
                keep = [k for k in PLACE_COLS if k in pf.schema_arrow.names]
                t = pf.read_row_group(g, columns=keep)
            asked += sum(sizes.get(k, 0) for k in keep)
            rows += t.num_rows
            print(f"  {os.path.basename(path)[:12]} group {g}: {t.num_rows} rows, asked {asked / 1e6:.1f} MB so far", flush=True)
            if writer is None:
                writer = pq.ParquetWriter(out_path, t.schema, compression="zstd", compression_level=9)
            writer.write_table(t)
    finally:
        if writer:
            writer.close()
    return rows, asked


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--release")
    ap.add_argument("--places", action="store_true", help="also fetch the places rows (about 83 MB)")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    os.makedirs(a.out, exist_ok=True)
    rel = a.release or current_release()
    print("release", rel, flush=True)
    fs = pafs.S3FileSystem(anonymous=True, region="us-west-2")
    root = f"{BUCKET}/release/{rel}"
    total = 0
    for typ, cols, name in (("division", DIVISION_COLS, "divisions-sa.parquet"), ("division_area", AREA_COLS, "division_areas-sa.parquet")):
        print(typ, flush=True)
        rows, asked = read_saudi(fs, f"{root}/theme=divisions/type={typ}", cols, os.path.join(a.out, name))
        total += asked
        print(f"{typ}: {rows} Saudi rows, {asked / 1e6:.1f} MB asked, file {os.path.getsize(os.path.join(a.out, name)) / 1e6:.1f} MB", flush=True)
    if a.places:
        print("place", flush=True)
        path = os.path.join(a.out, "places-box.parquet")
        rows, asked = read_places(fs, f"{root}/theme=places/type=place", path)
        total += asked
        print(f"place: {rows} rows in the box, {asked / 1e6:.1f} MB asked", flush=True)
    with open(os.path.join(a.out, "release.txt"), "w", encoding="ascii") as f:
        f.write(rel + "\n")
    print(f"total asked {total / 1e6:.1f} MB", flush=True)


if __name__ == "__main__":
    main()
