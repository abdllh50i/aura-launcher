"""A small OpenStreetMap PBF reader: numpy and the standard library, nothing else.

Written for tools that only want a part of a country file and must stay light on memory: the file is indexed once by
section (nodes, ways, relations), and each pass reads only the blobs of one section.

  r = PbfReader(path)
  for id, lat, lon, tags in r.tagged_nodes(wanted_keys): ...        # lat, lon in degrees; tags a dict of str
  ids, lat, lon = r.locations(sorted_ids)                           # numpy arrays, for the ids that exist
  for id, refs, tags in r.ways(wanted): ...                         # wanted(tags_bytes_dict) -> bool
  for id, members, tags in r.relations(wanted): ...                 # members: (type 0 node|1 way|2 relation, id, role)

Format: https://wiki.openstreetmap.org/wiki/PBF_Format
"""
import struct
import zlib

import numpy as np


def _varint(buf, pos):
    b = buf[pos]
    pos += 1
    if b < 0x80:
        return b, pos
    v = b & 0x7F
    shift = 7
    while True:
        b = buf[pos]
        pos += 1
        v |= (b & 0x7F) << shift
        if b < 0x80:
            return v, pos
        shift += 7


def _fields(buf, pos, end):
    """(field number, wire type, value or start, end). Length-delimited fields give their byte range."""
    while pos < end:
        key, pos = _varint(buf, pos)
        fn = key >> 3
        wt = key & 7
        if wt == 0:
            v, pos = _varint(buf, pos)
            yield fn, 0, v, 0
        elif wt == 2:
            n, pos = _varint(buf, pos)
            yield fn, 2, pos, pos + n
            pos += n
        elif wt == 1:
            pos += 8
        elif wt == 5:
            pos += 4
        else:
            raise ValueError(f"wire type {wt}")


def varints(buf, start=0, end=None):
    """All the varints of a packed field as uint64, at once."""
    a = np.frombuffer(buf, dtype=np.uint8, offset=start, count=(len(buf) if end is None else end) - start)
    if a.size == 0:
        return np.zeros(0, dtype=np.uint64)
    ends = np.nonzero(a < 0x80)[0]
    starts = np.empty(ends.size, dtype=np.int64)
    starts[0] = 0
    starts[1:] = ends[:-1] + 1
    lens = ends - starts + 1
    if lens.max() == 1:
        return a.astype(np.uint64)
    within = np.arange(a.size, dtype=np.int64) - np.repeat(starts, lens)
    vals = (a & 0x7F).astype(np.uint64) << (within * 7).astype(np.uint64)
    return np.add.reduceat(vals, starts)


def zigzag(u):
    """Protocol buffer sint64 from its unsigned form."""
    s = (u >> np.uint64(1)).astype(np.int64)
    return s ^ -((u & np.uint64(1)).astype(np.int64))


def _blob_data(blob):
    raw = None
    z = None
    for fn, wt, a, b in _fields(blob, 0, len(blob)):
        if wt != 2:
            continue
        if fn == 1:
            raw = blob[a:b]
        elif fn == 3:
            z = blob[a:b]
        elif fn in (4, 5, 6, 7):
            raise ValueError("only raw and zlib blobs are supported")
    return raw if raw is not None else zlib.decompress(z)


class _Block:
    __slots__ = ("buf", "strings", "gran", "lat_off", "lon_off", "groups")

    def __init__(self, buf):
        self.buf = buf
        self.gran = 100
        self.lat_off = 0
        self.lon_off = 0
        self.groups = []
        self.strings = []
        for fn, wt, a, b in _fields(buf, 0, len(buf)):
            if fn == 1:
                self.strings = [buf[x:y] for f2, w2, x, y in _fields(buf, a, b) if f2 == 1 and w2 == 2]
            elif fn == 2:
                self.groups.append((a, b))
            elif fn == 17:
                self.gran = a
            elif fn == 19:
                self.lat_off = a
            elif fn == 20:
                self.lon_off = a

    def group_kinds(self):
        out = set()
        for a, b in self.groups:
            for fn, wt, x, y in _fields(self.buf, a, b):
                out.add(fn)
                break
        return out

    def members(self, wanted_fn):
        for a, b in self.groups:
            for fn, wt, x, y in _fields(self.buf, a, b):
                if fn == wanted_fn and wt == 2:
                    yield x, y

    def dense(self):
        """For each dense group: ids (int64), lat and lon in 1e-7 degrees (int64), the keys_vals array or None."""
        for x, y in self.members(2):
            ids = lat = lon = None
            kv = None
            for fn, wt, p, q in _fields(self.buf, x, y):
                if fn == 1:
                    ids = np.cumsum(zigzag(varints(self.buf, p, q)))
                elif fn == 8:
                    lat = np.cumsum(zigzag(varints(self.buf, p, q)))
                elif fn == 9:
                    lon = np.cumsum(zigzag(varints(self.buf, p, q)))
                elif fn == 10:
                    kv = varints(self.buf, p, q).astype(np.int64)
            lat = (self.lat_off + self.gran * lat) // 100
            lon = (self.lon_off + self.gran * lon) // 100
            yield ids, lat, lon, kv


class PbfReader:
    NODES = 2
    WAYS = 3
    RELATIONS = 4

    def __init__(self, path):
        self.path = path
        self.index = {self.NODES: [], self.WAYS: [], self.RELATIONS: []}
        self._scan()

    def _scan(self):
        with open(self.path, "rb") as f:
            while True:
                h = f.read(4)
                if len(h) < 4:
                    break
                hlen = struct.unpack(">I", h)[0]
                hdr = f.read(hlen)
                typ = b""
                size = 0
                for fn, wt, a, b in _fields(hdr, 0, hlen):
                    if fn == 1:
                        typ = hdr[a:b]
                    elif fn == 3:
                        size = a
                pos = f.tell()
                blob = f.read(size)
                if typ != b"OSMData":
                    continue
                kinds = _Block(_blob_data(blob)).group_kinds()
                for k in kinds:
                    if k in self.index:
                        self.index[k].append((pos, size))
                    elif k == 1:
                        raise ValueError("plain (non-dense) nodes are not supported")

    def _blocks(self, kind):
        with open(self.path, "rb") as f:
            for pos, size in self.index[kind]:
                f.seek(pos)
                yield _Block(_blob_data(f.read(size)))

    def tagged_nodes(self, keys):
        """Nodes that carry one of keys (str): yields (id, lat, lon, tags), degrees, every tag decoded."""
        bkeys = {k.encode() for k in keys}
        for bl in self._blocks(self.NODES):
            wanted = np.array([i for i, s in enumerate(bl.strings) if s in bkeys], dtype=np.int64)
            for ids, lat, lon, kv in bl.dense():
                if kv is None or wanted.size == 0:
                    continue
                hits = np.nonzero(np.isin(kv, wanted))[0]
                if hits.size == 0:
                    continue
                zeros = np.nonzero(kv == 0)[0]
                # the node of a position in keys_vals is the number of terminators before it
                nodes = np.unique(np.searchsorted(zeros, hits))
                starts = np.empty(zeros.size, dtype=np.int64)
                starts[0] = 0
                starts[1:] = zeros[:-1] + 1
                s = bl.strings
                for n in nodes.tolist():
                    part = kv[starts[n]:zeros[n]].tolist()
                    tags = {}
                    for i in range(0, len(part) - 1, 2):
                        tags[s[part[i]].decode("utf-8", "replace")] = s[part[i + 1]].decode("utf-8", "replace")
                    if not bkeys.intersection(k.encode() for k in tags):
                        continue  # a value that happens to equal a wanted key's string
                    yield int(ids[n]), float(lat[n]) / 1e7, float(lon[n]) / 1e7, tags

    def locations(self, sorted_ids):
        """Where the given node ids are: (ids, lat, lon) in 1e-7 degrees, in file order."""
        out_i, out_a, out_o = [], [], []
        for bl in self._blocks(self.NODES):
            for ids, lat, lon, kv in bl.dense():
                if ids.size == 0:
                    continue
                lo = np.searchsorted(sorted_ids, ids[0])
                hi = np.searchsorted(sorted_ids, ids[-1], side="right")
                if hi <= lo:
                    continue
                pick = np.nonzero(np.isin(ids, sorted_ids[lo:hi], assume_unique=True))[0]
                if pick.size:
                    out_i.append(ids[pick])
                    out_a.append(lat[pick].astype(np.int32))
                    out_o.append(lon[pick].astype(np.int32))
        if not out_i:
            e = np.zeros(0, dtype=np.int64)
            return e, e.astype(np.int32), e.astype(np.int32)
        return np.concatenate(out_i), np.concatenate(out_a), np.concatenate(out_o)

    def ways(self, wanted):
        """Yields (id, refs list, tags dict of str) of the ways for which wanted(id, bytes key -> bytes value dict) is true.
        Ways without tags are offered too (the outer rings of a multipolygon often have none)."""
        for bl in self._blocks(self.WAYS):
            buf = bl.buf
            s = bl.strings
            for x, y in bl.members(3):
                wid = 0
                keys = vals = None
                refs_at = None
                pos = x
                while pos < y:
                    key, pos = _varint(buf, pos)
                    fn = key >> 3
                    if key & 7 == 0:
                        v, pos = _varint(buf, pos)
                        if fn == 1:
                            wid = v
                    else:
                        n, pos = _varint(buf, pos)
                        if fn == 2:
                            keys = (pos, pos + n)
                        elif fn == 3:
                            vals = (pos, pos + n)
                        elif fn == 8:
                            refs_at = (pos, pos + n)
                        pos += n
                t = {}
                if keys is not None:
                    pk, pv = keys[0], vals[0]
                    while pk < keys[1]:
                        ki, pk = _varint(buf, pk)
                        vi, pv = _varint(buf, pv)
                        t[s[ki]] = s[vi]
                if not wanted(wid, t):
                    continue
                refs = []
                cur = 0
                if refs_at:
                    pos, end = refs_at
                    while pos < end:
                        v, pos = _varint(buf, pos)
                        cur += (v >> 1) ^ -(v & 1)
                        refs.append(cur)
                yield wid, refs, {k.decode("utf-8", "replace"): v.decode("utf-8", "replace") for k, v in t.items()}

    def relations(self, wanted):
        """Yields (id, [(type, member id, role)], tags dict of str) of the relations wanted(bytes dict) accepts."""
        for bl in self._blocks(self.RELATIONS):
            buf = bl.buf
            s = bl.strings
            for x, y in bl.members(4):
                rid = 0
                keys = vals = roles = mem = typ = None
                for fn, wt, a, b in _fields(buf, x, y):
                    if fn == 1:
                        rid = a
                    elif fn == 2:
                        keys = (a, b)
                    elif fn == 3:
                        vals = (a, b)
                    elif fn == 8:
                        roles = (a, b)
                    elif fn == 9:
                        mem = (a, b)
                    elif fn == 10:
                        typ = (a, b)
                if keys is None or keys[0] == keys[1]:
                    continue
                t = {}
                pk, pv = keys[0], vals[0]
                while pk < keys[1]:
                    ki, pk = _varint(buf, pk)
                    vi, pv = _varint(buf, pv)
                    t[s[ki]] = s[vi]
                if not wanted(t):
                    continue
                out = []
                if mem:
                    pm, pr, pt = mem[0], roles[0], typ[0]
                    cur = 0
                    while pm < mem[1]:
                        v, pm = _varint(buf, pm)
                        cur += (v >> 1) ^ -(v & 1)
                        ri, pr = _varint(buf, pr)
                        ty, pt = _varint(buf, pt)
                        out.append((ty, cur, s[ri].decode("utf-8", "replace")))
                yield rid, out, {k.decode("utf-8", "replace"): v.decode("utf-8", "replace") for k, v in t.items()}
