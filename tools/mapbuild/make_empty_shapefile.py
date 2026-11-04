#!/usr/bin/env python3
"""Writes an empty polyline shapefile as a zip.

    python -I make_empty_shapefile.py <out.shp.zip>

Planetiler's OpenMapTiles profile wants a third source besides water polygons and Natural Earth: lake centre lines
(used only to bend the name of a big lake along it). Saudi Arabia has no such lakes, and that file is not on the list of
approved downloads, so it gets an empty stand-in: Planetiler downloads a source only when its path does not exist.
"""
import io
import struct
import sys
import zipfile


def shp_header():
    h = struct.pack(">i", 9994) + b"\0" * 20 + struct.pack(">i", 50)  # file code, unused, length in 16-bit words
    h += struct.pack("<ii", 1000, 3)  # version, polyline
    h += struct.pack("<8d", 0, 0, 0, 0, 0, 0, 0, 0)  # bounding box, z, m
    return h


def dbf():
    # one numeric field, as the real file has (OSM_ID), and no records
    field = b"OSM_ID".ljust(11, b"\0") + b"N" + b"\0" * 4 + bytes([18, 0]) + b"\0" * 14
    header_size = 32 + len(field) + 1
    record_size = 1 + 18
    head = bytes([3, 125, 1, 1]) + struct.pack("<IHH", 0, header_size, record_size) + b"\0" * 20
    return head + field + b"\r" + b"\x1a"


def main():
    out = sys.argv[1]
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("lake_centerline.shp", shp_header())
        z.writestr("lake_centerline.shx", shp_header())
        z.writestr("lake_centerline.dbf", dbf())
    with open(out, "wb") as f:
        f.write(buf.getvalue())
    print("wrote", out, len(buf.getvalue()), "bytes")


if __name__ == "__main__":
    main()
