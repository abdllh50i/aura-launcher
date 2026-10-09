"""Minimal reader for binary FBX files (versions 7100-7700), enough to pull out meshes, UVs, normals and embedded textures.

    from fbx_binary import read_fbx
    root = read_fbx("model.fbx")          # FbxNode tree; root.children are the top-level sections
"""
import struct
import zlib

import numpy as np

_ARRAY_TYPES = {b"f": ("<f4", 4), b"d": ("<f8", 8), b"l": ("<i8", 8), b"i": ("<i4", 4), b"b": ("<u1", 1)}


class FbxNode:
    __slots__ = ("name", "props", "children")

    def __init__(self, name, props, children):
        self.name, self.props, self.children = name, props, children

    def find(self, name):
        for c in self.children:
            if c.name == name:
                return c
        return None

    def find_all(self, name):
        return [c for c in self.children if c.name == name]

    def __repr__(self):
        return "FbxNode(%s, %d props, %d children)" % (self.name, len(self.props), len(self.children))


def _read_prop(data, pos):
    t = data[pos:pos + 1]
    pos += 1
    if t == b"Y":
        return struct.unpack_from("<h", data, pos)[0], pos + 2
    if t == b"C":
        return data[pos] != 0, pos + 1
    if t == b"I":
        return struct.unpack_from("<i", data, pos)[0], pos + 4
    if t == b"F":
        return struct.unpack_from("<f", data, pos)[0], pos + 4
    if t == b"D":
        return struct.unpack_from("<d", data, pos)[0], pos + 8
    if t == b"L":
        return struct.unpack_from("<q", data, pos)[0], pos + 8
    if t in (b"S", b"R"):
        n = struct.unpack_from("<I", data, pos)[0]
        raw = data[pos + 4:pos + 4 + n]
        return (raw if t == b"R" else raw), pos + 4 + n
    if t in _ARRAY_TYPES:
        count, encoding, clen = struct.unpack_from("<III", data, pos)
        pos += 12
        raw = data[pos:pos + clen]
        if encoding == 1:
            raw = zlib.decompress(raw)
        dtype, size = _ARRAY_TYPES[t]
        arr = np.frombuffer(raw, dtype=dtype, count=count)
        return arr, pos + clen
    raise ValueError("unknown FBX property type %r at %d" % (t, pos - 1))


def _read_node(data, pos, wide):
    if wide:
        end, nprops, plen = struct.unpack_from("<QQQ", data, pos)
        pos += 24
    else:
        end, nprops, plen = struct.unpack_from("<III", data, pos)
        pos += 12
    nlen = data[pos]
    pos += 1
    if end == 0:
        return None, pos
    name = data[pos:pos + nlen].decode("ascii", "replace")
    pos += nlen
    props = []
    for _ in range(nprops):
        v, pos = _read_prop(data, pos)
        props.append(v)
    children = []
    null_len = 25 if wide else 13
    while pos < end:
        if end - pos == null_len and data[pos:end] == b"\0" * null_len:
            pos = end
            break
        child, pos = _read_node(data, pos, wide)
        if child is None:
            break
        children.append(child)
    return FbxNode(name, props, children), end


def read_fbx(path):
    data = open(path, "rb").read()
    if not data.startswith(b"Kaydara FBX Binary"):
        raise ValueError("not a binary FBX file")
    version = struct.unpack_from("<I", data, 23)[0]
    wide = version >= 7500
    pos = 27
    top = []
    while pos < len(data):
        node, pos = _read_node(data, pos, wide)
        if node is None:
            break
        top.append(node)
    return FbxNode("root", [version], top)


def props70(node):
    """The Properties70 block of an object as {name: [values...]}."""
    out = {}
    p = node.find("Properties70")
    if p:
        for c in p.find_all("P"):
            key = c.props[0].decode("utf-8", "replace") if isinstance(c.props[0], (bytes, bytearray)) else str(c.props[0])
            out[key] = c.props[4:]
    return out
