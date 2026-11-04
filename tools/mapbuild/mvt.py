"""A small vector-tile reader: enough to count layers, fields and names. Standard library only."""
import gzip
import struct


def varint(b, p):
    r = s = 0
    while True:
        c = b[p]
        p += 1
        r |= (c & 0x7F) << s
        if c < 0x80:
            return r, p
        s += 7


def fields(b):
    p = 0
    n = len(b)
    while p < n:
        k, p = varint(b, p)
        f, w = k >> 3, k & 7
        if w == 0:
            v, p = varint(b, p)
        elif w == 2:
            ln, p = varint(b, p)
            v = b[p:p + ln]
            p += ln
        elif w == 5:
            v = b[p:p + 4]
            p += 4
        elif w == 1:
            v = b[p:p + 8]
            p += 8
        else:
            raise ValueError(f"wire type {w}")
        yield f, w, v


def value(b):
    out = None
    for f, _, v in fields(b):
        if f == 1:
            out = v.decode("utf-8", "replace")
        elif f == 2:
            out = struct.unpack("<f", v)[0]
        elif f == 3:
            out = struct.unpack("<d", v)[0]
        elif f in (4, 5):
            out = v
        elif f == 6:
            out = (v >> 1) ^ -(v & 1)
        elif f == 7:
            out = bool(v)
    return out


def packed(b):
    out = []
    p = 0
    n = len(b)
    while p < n:
        v, p = varint(b, p)
        out.append(v)
    return out


def ungzip(data):
    return gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data


def layer_sizes(data):
    """(layer name, bytes) of an uncompressed tile, without reading its features."""
    for f, _, v in fields(data):
        if f != 3:
            continue
        name = ""
        for f2, _, v2 in fields(v):
            if f2 == 1:
                name = v2.decode("utf-8", "replace")
                break
        yield name, len(v)


def layers(data):
    """(name, extent, keys, values, raw features, bytes) of each layer of an uncompressed tile."""
    for f, _, v in fields(data):
        if f != 3:
            continue
        name = ""
        keys, vals, feats = [], [], []
        extent = 4096
        for f2, _, v2 in fields(v):
            if f2 == 1:
                name = v2.decode("utf-8", "replace")
            elif f2 == 2:
                feats.append(v2)
            elif f2 == 3:
                keys.append(v2.decode("utf-8", "replace"))
            elif f2 == 4:
                vals.append(value(v2))
            elif f2 == 5:
                extent = v2
        yield name, extent, keys, vals, feats, len(v)


def feature(raw, keys, vals):
    """(tags, geometry type, geometry commands) of one raw feature."""
    tags = {}
    typ = 0
    geom = []
    for f, _, v in fields(raw):
        if f == 2:
            t = packed(v)
            for i in range(0, len(t) - 1, 2):
                tags[keys[t[i]]] = vals[t[i + 1]]
        elif f == 3:
            typ = v
        elif f == 4:
            geom = packed(v)
    return tags, typ, geom


def first_point(geom):
    if len(geom) < 3:
        return None
    return (geom[1] >> 1) ^ -(geom[1] & 1), (geom[2] >> 1) ^ -(geom[2] & 1)


def has_arabic(s):
    return isinstance(s, str) and any("؀" <= c <= "ۿ" or "ﭐ" <= c <= "﷿" or "ﹰ" <= c <= "﻿" for c in s)
