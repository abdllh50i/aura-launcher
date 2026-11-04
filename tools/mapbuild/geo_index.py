"""Point-in-polygon for many points at once, with numpy alone: WKB parsing and an even-odd test that only looks at the
points inside each edge's latitude range."""
import math
import struct

import numpy as np


def parse_wkb(b):
    """Rings of a WKB Polygon or MultiPolygon as a list of (n, 2) float arrays of lon, lat, or a (lon, lat) tuple for a Point."""
    pos = 0

    def geom():
        nonlocal pos
        order = "<" if b[pos] == 1 else ">"
        pos += 1
        typ = struct.unpack_from(order + "I", b, pos)[0] & 0xFF
        pos += 4
        if typ == 1:
            x, y = struct.unpack_from(order + "dd", b, pos)
            pos += 16
            return (x, y)
        if typ == 3:
            n = struct.unpack_from(order + "I", b, pos)[0]
            pos += 4
            rings = []
            for _ in range(n):
                k = struct.unpack_from(order + "I", b, pos)[0]
                pos += 4
                dt = np.dtype(np.float64).newbyteorder(order)
                rings.append(np.frombuffer(b, dtype=dt, count=2 * k, offset=pos).reshape(-1, 2).astype(np.float64))
                pos += 16 * k
            return rings
        if typ == 6:
            n = struct.unpack_from(order + "I", b, pos)[0]
            pos += 4
            rings = []
            for _ in range(n):
                rings.extend(geom())
            return rings
        raise ValueError(f"geometry type {typ}")

    return geom()


def rings_bbox(rings):
    return (min(float(r[:, 0].min()) for r in rings), min(float(r[:, 1].min()) for r in rings),
            max(float(r[:, 0].max()) for r in rings), max(float(r[:, 1].max()) for r in rings))


def rings_area_km2(rings):
    """Rough area, enough to tell a small district from a big one (holes subtract only by the winding of the data: not used)."""
    a = 0.0
    for r in rings:
        x, y = r[:, 0], r[:, 1]
        k = math.cos(math.radians(float(y.mean())))
        a += abs(float(np.sum(x[:-1] * y[1:] - x[1:] * y[:-1]))) * 0.5 * k * 111.32 * 111.32
    return a


class Points:
    """Points sorted by latitude, so that every polygon test touches only the ones in its range."""

    def __init__(self, lat, lon):
        self.order = np.argsort(lat, kind="stable")
        self.lat = np.asarray(lat)[self.order]
        self.lon = np.asarray(lon)[self.order]

    def in_rings(self, rings, bbox=None):
        """Indexes (into the original arrays) of the points inside rings, even-odd over all rings."""
        if bbox is None:
            bbox = rings_bbox(rings)
        lo = int(np.searchsorted(self.lat, bbox[1], side="left"))
        hi = int(np.searchsorted(self.lat, bbox[3], side="right"))
        if hi <= lo:
            return np.zeros(0, dtype=np.int64)
        y = self.lat[lo:hi]
        x = self.lon[lo:hi]
        odd = np.zeros(y.size, dtype=bool)
        for r in rings:
            x1, y1 = r[:-1, 0], r[:-1, 1]
            x2, y2 = r[1:, 0], r[1:, 1]
            ya = np.minimum(y1, y2)
            yb = np.maximum(y1, y2)
            first = np.searchsorted(y, ya, side="left")
            last = np.searchsorted(y, yb, side="left")
            busy = np.nonzero(last > first)[0]
            for e in busy.tolist():
                f, l = first[e], last[e]
                # the half-open rule [ya, yb) keeps a shared vertex from counting twice
                xc = x1[e] + (y[f:l] - y1[e]) * (x2[e] - x1[e]) / (y2[e] - y1[e])
                odd[f:l] ^= x[f:l] < xc
        return self.order[lo + np.nonzero(odd)[0]]


def distance_m(lat1, lon1, lat2, lon2):
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 6371000.0 * 2 * math.asin(min(1.0, math.sqrt(a)))
