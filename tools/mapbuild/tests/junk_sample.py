#!/usr/bin/env python
"""The tester's look at the places data in the Eastern Province: 200 random POIs with what a machine can say about each (for a person to judge),
and province-wide counts of the same signs.

  python -I junk_sample.py --db out\\places.db --base out\\base.mbtiles --areas src\\overture\\division_areas-sa.parquet --out places\\test

Writes junk-sample-200.tsv and junk-province.json. Sample: fixed seed, plain random over kind 6 rows inside the region's polygon.
"""
import argparse
import collections
import json
import math
import os
import random
import re
import sqlite3
import sys

import numpy as np
import pyarrow.parquet as pq

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)
import geo_index  # noqa: E402
import mvt  # noqa: E402
import search_ref  # noqa: E402

CLOSED = re.compile(r"مغلق|مقفل|closed|permanently|سابقا|previously|formerly|\(old\)|\bold\b.*\bbranch\b|out of business|متوقف|ملغي|ملغى|مهجور|abandoned|demolish|under construction|تحت الانشاء|تحت الإنشاء|قيد الانشاء", re.I)
FOREIGN_LATIN = re.compile(r"rumah sakit|apotek|\btoko\b|warung|restoran\b|klinik|masjid raya|hastane|eczane|camii|mesjid|kedai|pasar\b|krankenhaus|apotheke|farmacia|hôpital|hopital|pharmacie|boulangerie|ресторан", re.I)
OTHER_SCRIPT = re.compile(r"[\u0400-\u04ff\u0590-\u05ff\u0900-\u0dff\u0e00-\u0eff\u1100-\u11ff\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]")
URDU = re.compile(r"[ٹڈڑںھےہ]")
ADVERT = re.compile(r"(0|\+?966|٠)\d{8,}|https?:|www\.|\.com\b|واتس|whats ?app|شراء .*(مستعمل|اثاث)|نقل عفش|مكافحة حشرات|كشف تسربات|سطحه|سطحة|تخفيض|ارخص|افضل شركة|افضل شركه|رقم |اتصل", re.I)


def tile_of(lat, lon, z):
    n = 1 << z
    x = (lon + 180) / 360 * n
    r = math.radians(lat)
    y = (1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * n
    return x, y


def rings(geom):
    out = []
    i = 0
    x = y = 0
    cur = []
    while i < len(geom):
        cmd = geom[i] & 7
        cnt = geom[i] >> 3
        i += 1
        if cmd == 7:
            if cur:
                out.append(cur)
            cur = []
            continue
        for _ in range(cnt):
            dx = (geom[i] >> 1) ^ -(geom[i] & 1)
            dy = (geom[i + 1] >> 1) ^ -(geom[i + 1] & 1)
            i += 2
            x += dx
            y += dy
            if cmd == 1 and cur:
                out.append(cur)
                cur = []
            cur.append((x, y))
    if cur:
        out.append(cur)
    return out


def inside(rs, px, py):
    """Even-odd over all the rings of one feature."""
    c = False
    for r in rs:
        n = len(r)
        j = n - 1
        for i in range(n):
            xi, yi = r[i]
            xj, yj = r[j]
            if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi:
                c = not c
            j = i
    return c


class Tiles:
    def __init__(self, path, z):
        self.db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        self.z = z
        self.cache = {}

    def tile(self, x, y):
        k = (x, y)
        got = self.cache.get(k)
        if got is not None:
            return got
        row = self.db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", (self.z, x, (1 << self.z) - 1 - y)).fetchone()
        info = {"exists": bool(row), "water": [], "road": 0, "building": 0, "extent": 4096}
        if row:
            for name, extent, keys, vals, feats, _ in mvt.layers(mvt.ungzip(row[0])):
                if name == "water":
                    info["extent"] = extent
                    for fb in feats:
                        tags, typ, geom = mvt.feature(fb, keys, vals)
                        if typ == 3:
                            info["water"].append((tags.get("class", ""), rings(geom)))
                elif name == "transportation":
                    info["road"] = len(feats)
                elif name == "building":
                    info["building"] = len(feats)
        if len(self.cache) > 4000:
            self.cache.clear()
        self.cache[k] = info
        return info

    def at(self, lat, lon):
        fx, fy = tile_of(lat, lon, self.z)
        x, y = int(fx), int(fy)
        t = self.tile(x, y)
        cls = None
        if t["water"]:
            px, py = (fx - x) * t["extent"], (fy - y) * t["extent"]
            for c, rs in t["water"]:
                if inside(rs, px, py):
                    cls = c or "water"
                    break
        return t, cls


def km(a, b, c, d):
    p1, p2 = math.radians(a), math.radians(c)
    x = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(d - b) / 2) ** 2
    return 12742 * math.asin(min(1, math.sqrt(x)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--base", required=True)
    ap.add_argument("--areas", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=2501)
    ap.add_argument("--n", type=int, default=200)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    cats = {i: k for i, k in db.execute("SELECT id,key FROM cat")}
    areas = {r[0]: r for r in db.execute("SELECT id,ar,en,city_ar,city_en FROM area")}
    t = pq.ParquetFile(a.areas).read(columns=["subtype", "names", "geometry"])
    ring_list = None
    for s, n, g in zip(t.column("subtype").to_pylist(), t.column("names").to_pylist(), t.column("geometry").to_pylist()):
        if s == "region" and n and n.get("primary") == "المنطقة الشرقية":
            ring_list = geo_index.parse_wkb(g)
            break
    all_rows = db.execute("SELECT id,kind,name,alt,cat,lat,lon,area,rank,src,phone FROM p").fetchall()
    # the region's polygon, even-odd over its rings, numpy over all rows
    lats = np.array([r[5] / 1e6 for r in all_rows])
    lons = np.array([r[6] / 1e6 for r in all_rows])
    hit = np.zeros(len(all_rows), dtype=bool)
    for ring in ring_list:
        xs, ys = ring[:, 0], ring[:, 1]
        for i in range(len(xs) - 1):
            lo_, hi_ = min(ys[i], ys[i + 1]), max(ys[i], ys[i + 1])
            if lo_ == hi_:
                continue
            m = (lats > lo_) & (lats <= hi_)
            if not m.any():
                continue
            cross = xs[i] + (lats[m] - ys[i]) * (xs[i + 1] - xs[i]) / (ys[i + 1] - ys[i])
            idx = np.nonzero(m)[0]
            hit[idx[lons[m] < cross]] ^= True
    ep_all = [r for r, h in zip(all_rows, hit) if h]
    ep = [r for r in ep_all if r[1] == 6]
    print("Eastern Province rows", len(ep_all), "POIs", len(ep), flush=True)
    by_kind = collections.Counter(r[1] for r in ep_all)
    print("by kind", dict(by_kind))
    # neighbours
    grid = collections.defaultdict(list)
    for r in ep:
        grid[(r[5] // 2000, r[6] // 2000)].append(r)
    same_point = collections.Counter((r[5], r[6]) for r in ep)

    def words(r):
        return set(search_ref.query_words(r[2])) | set(search_ref.query_words(r[3] or ""))

    def sks(ws):
        out = set()
        for w in ws:
            for s in search_ref.skeletons(w):
                if len(s) >= 3:
                    out.add(s)
        return out

    GENERIC = {"مطعم", "مسجد", "شركه", "مؤسسه", "موسسه", "محل", "مركز", "فرع", "صيدليه", "بقاله", "restaurant", "mosque", "company", "center", "centre", "shop", "store", "pharmacy", "cafe", "coffee", "مقهي", "بنك", "bank", "hotel", "فندق", "سوق", "مدرسه", "school"}

    def similar(r):
        """Rows within 150 m that may be the same place: a shared long word or the same consonants."""
        ws = {w for w in words(r) if len(w) >= 3 and w not in GENERIC}
        sk = sks(ws)
        out = []
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for o in grid.get((r[5] // 2000 + dx, r[6] // 2000 + dy), ()):
                    if o[0] == r[0]:
                        continue
                    d = km(r[5] / 1e6, r[6] / 1e6, o[5] / 1e6, o[6] / 1e6)
                    if d > 0.15:
                        continue
                    ow = {w for w in words(o) if len(w) >= 3 and w not in GENERIC}
                    if ws & ow or sk & sks(ow):
                        out.append((round(d * 1000), o[0], o[2], o[3], cats.get(o[4])))
        return sorted(out, key=lambda x: x[0])

    tz14 = Tiles(a.base, 14)
    rng = random.Random(a.seed)
    sample = rng.sample(ep, a.n)
    sample.sort(key=lambda r: r[0])
    with open(os.path.join(a.out, "junk-sample-200.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("id\tname\talt\tcat\tdistrict\ttown\tlat\tlon\trank\tsrc\tphone\tsame_point\twater\troads_in_z14\tbuildings_in_z14\tnearest_poi_m\tclosed_word\tforeign\tadvert\tsimilar_within_150m\n")
        for r in sample:
            tile, water = tz14.at(r[5] / 1e6, r[6] / 1e6)
            ar = areas.get(r[7]) or (None, "", "", "", "")
            nearest = None
            for dx in (-2, -1, 0, 1, 2):
                for dy in (-2, -1, 0, 1, 2):
                    for o in grid.get((r[5] // 2000 + dx, r[6] // 2000 + dy), ()):
                        if o[0] != r[0]:
                            d = km(r[5] / 1e6, r[6] / 1e6, o[5] / 1e6, o[6] / 1e6)
                            if nearest is None or d < nearest:
                                nearest = d
            text = f"{r[2]} | {r[3] or ''}"
            foreign = "script" if OTHER_SCRIPT.search(text) else "urdu" if URDU.search(text) else "latin" if FOREIGN_LATIN.search(text) else ""
            sim = similar(r)
            f.write("\t".join(str(x) for x in (r[0], r[2], r[3] or "", cats.get(r[4]), ar[1] or ar[2] or "", ar[3] or ar[4] or "", round(r[5] / 1e6, 5), round(r[6] / 1e6, 5), r[8], {1: "ov", 2: "osm", 3: "both"}.get(r[9], r[9]), r[10] or "", same_point[(r[5], r[6])],
                                                 water or "", tile["road"], tile["building"], "" if nearest is None else round(nearest * 1000), "1" if CLOSED.search(text) else "", foreign, "1" if ADVERT.search(text) else "",
                                                 " ; ".join(f"{s[0]}m {s[2]}{' / ' + s[3] if s[3] else ''} [{s[4]}]" for s in sim[:3]))) + "\n")
    # the whole province
    prov = {"pois": len(ep), "rows": len(ep_all), "by_kind": dict(by_kind), "by_src": dict(collections.Counter({1: "overture only", 2: "osm only", 3: "both"}.get(r[9], str(r[9])) for r in ep))}
    tz12 = Tiles(a.base, 12)
    sea, sea_ex = 0, []
    empty, no_road, no_road_ex = 0, 0, []
    natural = {"nature", "historic", "farm", "beach", "park", "resort", "military", "industrial", "fun", "other", "exit", "building"}
    tz14.cache.clear()
    for i, r in enumerate(ep):
        la, lo = r[5] / 1e6, r[6] / 1e6
        _, w = tz12.at(la, lo)
        if w in ("ocean", "sea", "bay", "lake", "river", "water", "swimming_pool"):
            # really in it: also a point 100 m to each side, at z14
            ok = True
            for dla, dlo in ((0, 0), (0.0009, 0), (-0.0009, 0), (0, 0.001), (0, -0.001)):
                _, w2 = tz14.at(la + dla, lo + dlo)
                if not w2:
                    ok = False
                    break
            if ok:
                sea += 1
                sea_ex.append((r[0], r[2], cats.get(r[4]), r[8], round(la, 4), round(lo, 4), w))
        if i % 10000 == 0:
            print("water", i, flush=True)
    prov["in_water_all_5_points"] = sea
    prov["in_water_examples"] = sea_ex[:60]
    prov["in_water_by_cat"] = collections.Counter(x[2] for x in sea_ex).most_common(20)
    no_tile = collections.Counter()
    ex = []
    tz14.cache.clear()
    for r in ep:
        t14, _ = tz14.at(r[5] / 1e6, r[6] / 1e6)
        if not t14["exists"] or t14["road"] == 0:
            c = cats.get(r[4])
            no_road += 1
            if not t14["exists"]:
                empty += 1
            no_tile[c] += 1
            if c not in natural and len(ex) < 300:
                ex.append((r[0], r[2], c, r[8], round(r[5] / 1e6, 4), round(r[6] / 1e6, 4)))
    prov["no_road_in_z14_tile"] = no_road
    prov["no_z14_tile_at_all"] = empty
    prov["no_road_by_cat"] = no_tile.most_common(30)
    prov["no_road_town_kinds_examples"] = ex[:60]
    prov["no_road_town_kinds"] = sum(n for c, n in no_tile.items() if c not in natural)
    # signs in the names
    signs = collections.Counter()
    examples = collections.defaultdict(list)
    for r in ep:
        text = f"{r[2]} | {r[3] or ''}"
        for key, rx in (("closed word", CLOSED), ("other script", OTHER_SCRIPT), ("urdu letters", URDU), ("foreign word", FOREIGN_LATIN), ("advert", ADVERT)):
            if rx.search(text):
                signs[key] += 1
                if len(examples[key]) < 25:
                    examples[key].append(text[:70])
    prov["signs"] = dict(signs)
    prov["sign_examples"] = examples
    piles = same_point.most_common(25)
    prov["piles"] = [{"lat": k[0] / 1e6, "lon": k[1] / 1e6, "n": v} for k, v in piles]
    prov["rows_on_points_with_n_or_more"] = {str(n): sum(v for v in same_point.values() if v >= n) for n in (2, 3, 5, 10, 20)}
    # duplicates by a simple rule, whole province: same words within 50 m
    dup = 0
    ex2 = []
    seen = set()
    for r in ep:
        w = tuple(sorted(search_ref.query_words(r[2])))
        if not w:
            continue
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for o in grid.get((r[5] // 2000 + dx, r[6] // 2000 + dy), ()):
                    if o[0] > r[0] and tuple(sorted(search_ref.query_words(o[2]))) == w and km(r[5] / 1e6, r[6] / 1e6, o[5] / 1e6, o[6] / 1e6) <= 0.15:
                        dup += 1
                        if len(ex2) < 30:
                            ex2.append((r[0], o[0], r[2], cats.get(r[4]), cats.get(o[4])))
    prov["same_name_within_150m_pairs"] = dup
    prov["same_name_examples"] = ex2
    with open(os.path.join(a.out, "junk-province.json"), "w", encoding="utf-8") as f:
        json.dump(prov, f, ensure_ascii=False, indent=0)
    print({k: v for k, v in prov.items() if k not in ("sign_examples", "in_water_examples", "no_road_town_kinds_examples", "same_name_examples", "piles")})


if __name__ == "__main__":
    main()