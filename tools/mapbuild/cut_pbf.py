#!/usr/bin/env python3
"""Cuts an area out of an .osm.pbf with pyosmium, the way `osmium extract --strategy=smart` does.

    python -I cut_pbf.py <in.osm.pbf> <out.osm.pbf> --poly ksa.poly
    python -I cut_pbf.py <in.osm.pbf> <out.osm.pbf> --bbox 49.4,26.0,50.4,27.3

Kept:
  * every node inside the area;
  * every way with at least one node inside, whole (a road crossing the edge is not cut);
  * every relation with a kept member, and parent relations of those (one level), with the members that are in the
    file; for type=multipolygon all member ways, whole (a lake or a park crossing the edge stays a closed shape).

The area test is a lookup in a raster of the polygon (cells of 0.002 degrees, about 200 m). The polygon has a buffer of
kilometres, so that precision does not matter, and it makes 30 million tests a matter of seconds.
"""
import argparse
import sys
import time

import numpy as np
import osmium
import osmium.io
import osmium.osm


def read_poly(path):
    """Osmosis .poly: returns (outer rings, holes), each a list of [(lon, lat), ...]."""
    outer, holes = [], []
    with open(path, encoding="ascii") as f:
        lines = [ln.strip() for ln in f if ln.strip()]
    i = 1  # first line: the name
    while i < len(lines) and lines[i] != "END":
        hole = lines[i].startswith("!")
        i += 1
        ring = []
        while lines[i] != "END":
            lon, lat = lines[i].split()
            ring.append((float(lon), float(lat)))
            i += 1
        i += 1
        (holes if hole else outer).append(ring)
    return outer, holes


class PolyMask:
    """The polygon as a boolean raster, for fast tests of many points."""

    def __init__(self, outer, holes, cell=0.002):
        pts = np.array([p for r in outer for p in r])
        self.cell = cell
        self.lon0, self.lat0 = pts[:, 0].min() - cell, pts[:, 1].min() - cell
        self.cols = int((pts[:, 0].max() - self.lon0) / cell) + 2
        self.rows = int((pts[:, 1].max() - self.lat0) / cell) + 2
        self.mask = np.zeros((self.rows, self.cols), dtype=bool)
        for ring in outer:
            self._fill(ring, True)
        for ring in holes:
            self._fill(ring, False)

    def _fill(self, ring, value):
        r = np.array(ring)
        x1, y1 = r[:-1, 0], r[:-1, 1]
        x2, y2 = r[1:, 0], r[1:, 1]
        for row in range(self.rows):
            yc = self.lat0 + (row + 0.5) * self.cell
            hit = ((y1 <= yc) & (y2 > yc)) | ((y2 <= yc) & (y1 > yc))
            if not hit.any():
                continue
            xs = np.sort(x1[hit] + (yc - y1[hit]) * (x2[hit] - x1[hit]) / (y2[hit] - y1[hit]))
            for a, b in zip(xs[0::2], xs[1::2]):
                c0 = int(np.ceil((a - self.lon0) / self.cell - 0.5))
                c1 = int(np.floor((b - self.lon0) / self.cell - 0.5))
                if c1 >= c0:
                    self.mask[row, max(c0, 0):min(c1 + 1, self.cols)] = value

    def contains(self, lons, lats):
        c = ((lons - self.lon0) / self.cell).astype(np.int64)
        r = ((lats - self.lat0) / self.cell).astype(np.int64)
        ok = (c >= 0) & (c < self.cols) & (r >= 0) & (r < self.rows)
        out = np.zeros(len(lons), dtype=bool)
        out[ok] = self.mask[r[ok], c[ok]]
        return out

    def bounds(self):
        rows, cols = np.nonzero(self.mask.any(axis=1))[0], np.nonzero(self.mask.any(axis=0))[0]
        return (self.lon0 + cols[0] * self.cell, self.lat0 + rows[0] * self.cell,
                self.lon0 + (cols[-1] + 1) * self.cell, self.lat0 + (rows[-1] + 1) * self.cell)


class BoxMask:
    def __init__(self, w, s, e, n):
        self.w, self.s, self.e, self.n = w, s, e, n

    def contains(self, lons, lats):
        return (lons >= self.w) & (lons <= self.e) & (lats >= self.s) & (lats <= self.n)

    def bounds(self):
        return self.w, self.s, self.e, self.n


def member_of(ids_sorted, values):
    """For each of values: is it in the sorted id array?"""
    if len(ids_sorted) == 0 or len(values) == 0:
        return np.zeros(len(values), dtype=bool)
    idx = np.searchsorted(ids_sorted, values)
    idx[idx == len(ids_sorted)] = len(ids_sorted) - 1
    return ids_sorted[idx] == values


def nodes_inside(pbf, area):
    t0 = time.time()
    total = 0
    parts = []
    ids, lons, lats = [], [], []

    def flush():
        a = np.array(ids, dtype=np.int64)
        keep = area.contains(np.array(lons, dtype=np.float64), np.array(lats, dtype=np.float64))
        parts.append(a[keep])
        ids.clear(), lons.clear(), lats.clear()

    for n in osmium.FileProcessor(pbf, osmium.osm.NODE):
        ids.append(n.id)
        loc = n.location
        if loc.valid():
            lons.append(loc.lon)
            lats.append(loc.lat)
        else:
            lons.append(999.0)
            lats.append(999.0)
        total += 1
        if len(ids) >= 2_000_000:
            flush()
    flush()
    inside = np.concatenate(parts)
    inside.sort()
    print(f"nodes: {total:,} read, {len(inside):,} inside; {time.time() - t0:.0f} s", flush=True)
    return inside


def ways_touching(pbf, inside):
    t0 = time.time()
    total = 0
    kept_ways, way_nodes = [], []
    ids, refs, starts = [], [], []

    def flush():
        if not ids:
            return
        r = np.array(refs, dtype=np.int64)
        s = np.array(starts, dtype=np.int64)
        hit = member_of(inside, r).astype(np.int32)
        touched = np.add.reduceat(hit, s) > 0
        a = np.array(ids, dtype=np.int64)
        kept_ways.append(a[touched])
        ends = np.append(s[1:], len(r))
        for i in np.nonzero(touched)[0]:
            way_nodes.append(r[s[i]:ends[i]])
        ids.clear(), refs.clear(), starts.clear()

    for w in osmium.FileProcessor(pbf, osmium.osm.WAY):
        total += 1
        nodes = [n.ref for n in w.nodes]
        if not nodes:
            continue
        ids.append(w.id)
        starts.append(len(refs))
        refs.extend(nodes)
        if len(refs) >= 4_000_000:
            flush()
    flush()
    ways = np.sort(np.concatenate(kept_ways))
    nodes = np.unique(np.concatenate(way_nodes)) if way_nodes else np.zeros(0, dtype=np.int64)
    print(f"ways: {total:,} read, {len(ways):,} kept with {len(nodes):,} distinct nodes; {time.time() - t0:.0f} s", flush=True)
    return ways, nodes


def relations_touching(pbf, ways, nodes):
    """Returns the kept relations and the member ways of kept multipolygons that are not kept yet."""
    t0 = time.time()
    rels = {}
    for r in osmium.FileProcessor(pbf, osmium.osm.RELATION):
        members = [(m.type, m.ref) for m in r.members]
        rels[r.id] = (r.tags.get("type", ""), members)
    keep = set()
    extra_ways = set()
    for rid, (typ, members) in rels.items():
        w = np.array([ref for t, ref in members if t == "w"], dtype=np.int64)
        n = np.array([ref for t, ref in members if t == "n"], dtype=np.int64)
        if member_of(ways, w).any() or member_of(nodes, n).any():
            keep.add(rid)
            if typ == "multipolygon":
                extra_ways.update(w.tolist())
    # parents, one level
    parents = {rid for rid, (_, members) in rels.items() if rid not in keep and any(t == "r" and ref in keep for t, ref in members)}
    keep |= parents
    print(f"relations: {len(rels):,} read, {len(keep):,} kept ({len(parents)} as parents); {time.time() - t0:.0f} s", flush=True)
    return keep, extra_ways


def nodes_of_ways(pbf, wanted):
    out = []
    for w in osmium.FileProcessor(pbf, osmium.osm.WAY):
        if w.id in wanted:
            out.append(np.array([n.ref for n in w.nodes], dtype=np.int64))
    return np.unique(np.concatenate(out)) if out else np.zeros(0, dtype=np.int64)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--poly")
    ap.add_argument("--bbox", help="minlon,minlat,maxlon,maxlat")
    a = ap.parse_args()
    if bool(a.poly) == bool(a.bbox):
        sys.exit("give --poly or --bbox")
    t0 = time.time()
    if a.poly:
        area = PolyMask(*read_poly(a.poly))
        print(f"raster {area.cols} x {area.rows}; {time.time() - t0:.0f} s", flush=True)
    else:
        area = BoxMask(*[float(v) for v in a.bbox.split(",")])

    inside = nodes_inside(a.input, area)
    ways, way_nodes = ways_touching(a.input, inside)
    keep_rel, extra = relations_touching(a.input, ways, np.union1d(inside, way_nodes))
    extra_new = np.array(sorted(extra), dtype=np.int64)
    extra_new = extra_new[~member_of(ways, extra_new)]
    if len(extra_new):
        extra_nodes = nodes_of_ways(a.input, set(extra_new.tolist()))
        ways = np.union1d(ways, extra_new)
        way_nodes = np.union1d(way_nodes, extra_nodes)
        print(f"multipolygons add {len(extra_new):,} ways, {len(extra_nodes):,} nodes (with repeats among others)", flush=True)
    nodes = np.union1d(inside, way_nodes)
    del inside, way_nodes

    fp = osmium.FileProcessor(a.input)
    src_header = fp.header
    header = osmium.io.Header()
    w, s, e, n = area.bounds()
    header.add_box(osmium.osm.Box(osmium.osm.Location(w, s), osmium.osm.Location(e, n)))
    header.set("generator", "AMRI tools/mapbuild/cut_pbf.py with pyosmium")
    stamp = src_header.get("osmosis_replication_timestamp", "")
    if stamp:
        header.set("osmosis_replication_timestamp", stamp)
    t1 = time.time()
    counts = {"n": 0, "w": 0, "r": 0}
    ni = wi = 0
    n_len, w_len = len(nodes), len(ways)
    nodes_l, ways_l = nodes, ways
    with osmium.SimpleWriter(a.output, overwrite=True, header=header) as out:
        # all three lists are in ascending id order in the file and in the arrays: one walk through each
        for o in fp:
            if o.is_node():
                i = o.id
                while ni < n_len and nodes_l[ni] < i:
                    ni += 1
                if ni < n_len and nodes_l[ni] == i:
                    out.add_node(o)
                    counts["n"] += 1
            elif o.is_way():
                i = o.id
                while wi < w_len and ways_l[wi] < i:
                    wi += 1
                if wi < w_len and ways_l[wi] == i:
                    out.add_way(o)
                    counts["w"] += 1
            elif o.is_relation():
                if o.id in keep_rel:
                    out.add_relation(o)
                    counts["r"] += 1
    print(f"written: {counts['n']:,} nodes, {counts['w']:,} ways, {counts['r']:,} relations; {time.time() - t1:.0f} s", flush=True)
    print(f"total {time.time() - t0:.0f} s; box {w:.4f},{s:.4f},{e:.4f},{n:.4f}; data stamp {stamp or 'none'}")


if __name__ == "__main__":
    main()
