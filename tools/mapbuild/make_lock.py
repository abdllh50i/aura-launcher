#!/usr/bin/env python
"""lock.json: what a build was made from, so that the next one can be made from the same or say what changed. Run after a build; commit the result.

  python -I make_lock.py --work <mapbuild folder> --docker <docker.exe> --font <ReadexPro-VF.ttf> --overture-places <parquet> --out lock.json [--check]

--check compares what is on disk with the file and lists every difference. Images are pinned by digest in run_build.ps1 as well.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys

VALHALLA = "ghcr.io/valhalla/valhalla:3.9.1"
PLANETILER = "openmaptiles/planetiler-openmaptiles:3.16"
FILES = {
    "src/gcc-states-latest.osm.pbf": "https://download.geofabrik.de/asia/gcc-states-latest.osm.pbf",
    "src/ksa.osm.pbf": "cut_pbf.py, from the PBF above and polygon/ksa.poly",
    "sources/natural_earth_vector.sqlite.zip": "https://dev.maptiler.download/geodata/omt/natural_earth_vector.sqlite.zip (Planetiler's --download)",
    "sources/water-polygons-ksa.shp.zip": "make_water_polygons.py",
    "sources/lake_centerline.empty.shp.zip": "make_empty_shapefile.py",
    "src/overture/divisions-sa.parquet": "fetch_overture.py",
    "src/overture/division_areas-sa.parquet": "fetch_overture.py",
    "places/additions.json": "a read-only copy of branch mapdata's additions.json",
    "dl/aar/valhalla-mobile-0.6.4.aar": "https://repo1.maven.org/maven2/io/github/rallista/valhalla-mobile/0.6.4/valhalla-mobile-0.6.4.aar",
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def digest(docker, image):
    out = subprocess.run([docker, "image", "inspect", image, "--format", "{{json .RepoDigests}}"], capture_output=True, text=True, check=True).stdout
    return json.loads(out)


def versions():
    out = {"python": sys.version.split()[0]}
    for m in ("numpy", "scipy", "pyarrow", "fontTools", "PIL"):
        try:
            mod = __import__(m)
            out[m] = getattr(mod, "__version__", "?")
        except Exception as e:  # noqa: BLE001
            out[m] = f"not importable: {type(e).__name__}"
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--docker", required=True)
    ap.add_argument("--font", required=True)
    ap.add_argument("--overture-places")
    ap.add_argument("--osm-stamp", default="2026-11-02T21:21:26Z")
    ap.add_argument("--out", required=True)
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    data = {"osm_stamp": a.osm_stamp, "files": {}}
    for rel, where in FILES.items():
        p = os.path.join(a.work, *rel.split("/"))
        if os.path.exists(p):
            data["files"][rel] = {"bytes": os.path.getsize(p), "sha256": sha256(p), "from": where}
        else:
            data["files"][rel] = {"missing": True, "from": where}
    md5 = os.path.join(a.work, "src", "gcc-states-latest.osm.pbf.md5")
    if os.path.exists(md5):
        with open(md5, encoding="ascii") as f:
            data["gcc_pbf_md5"] = f.read().split()[0]
    release = os.path.join(a.work, "src", "overture", "release.txt")
    if os.path.exists(release):
        with open(release, encoding="ascii") as f:
            data["overture_release"] = f.read().strip()
    if a.overture_places and os.path.exists(a.overture_places):
        data["overture_places_rows"] = {"bytes": os.path.getsize(a.overture_places), "sha256": sha256(a.overture_places), "from": "fetch_overture.py --places: row groups over the Gulf"}
    data["font"] = {"file": os.path.basename(a.font), "sha256": sha256(a.font)}
    lock = {
        "about": "What the pack was built from. Regenerate with make_lock.py after a build.",
        "images": {"valhalla": {"tag": VALHALLA, "digests": digest(a.docker, VALHALLA)}, "planetiler": {"tag": PLANETILER, "digests": digest(a.docker, PLANETILER), "planetiler": "0.9.3 e6e13811"}},
        "library": {"valhalla_mobile": "0.6.4", "valhalla_commit": "bafb69902220615a48307d5c790fb6c943802ba4"},
        "data": data,
        "tools": versions(),
    }
    text = json.dumps(lock, ensure_ascii=False, indent=1) + "\n"
    if a.check:
        with open(a.out, encoding="utf-8") as f:
            old = json.load(f)
        same = old == json.loads(text)
        print("same as the lock file" if same else "differs from the lock file")
        if not same:
            for k in ("images", "library", "tools"):
                if old.get(k) != lock[k]:
                    print(" ", k, old.get(k), "->", lock[k])
            for rel in set(old["data"]["files"]) | set(data["files"]):
                if old["data"]["files"].get(rel) != data["files"].get(rel):
                    print(" ", rel)
        sys.exit(0 if same else 1)
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("wrote", a.out)


if __name__ == "__main__":
    main()
