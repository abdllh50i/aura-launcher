#!/usr/bin/env python3
"""Bakes the screen check's "glass" panel: app/src/main/res/drawable-nodpi/diag_glass.9.png, a 9-patch with rounded
corners, a dark translucent body, a soft top light and a little grain, as a designer's frosted panel would be baked
(no blur is computed at run time on this GPU). It is only what ProbeActivity's motion test draws, to weigh a baked
panel against a plain translucent fill; the look is not a design.

    python scripts/gen_diag_glass.py

Only the Python standard library: the PNG is written by hand.
"""
import os
import random
import struct
import zlib

SIZE = 96          # the panel's picture, without the 9-patch's one-pixel guides
RADIUS = 24
STRETCH = (44, 52) # the part that stretches, both ways

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(HERE), "app", "src", "main", "res", "drawable-nodpi", "diag_glass.9.png")


def inside(x, y):
    """How much of the pixel is inside the rounded square (0..1), by 4 x 4 samples."""
    hits = 0
    for sy in range(4):
        for sx in range(4):
            px, py = x + (sx + 0.5) / 4, y + (sy + 0.5) / 4
            dx = max(RADIUS - px, px - (SIZE - RADIUS), 0)
            dy = max(RADIUS - py, py - (SIZE - RADIUS), 0)
            if dx * dx + dy * dy <= RADIUS * RADIUS:
                hits += 1
    return hits / 16


def pixel(x, y, rng):
    cover = inside(x, y)
    if cover == 0:
        return (0, 0, 0, 0)
    t = y / (SIZE - 1)
    light = max(0.0, 1.0 - t * 6)                   # a soft light along the top
    edge = 0.0
    if min(x, y, SIZE - 1 - x, SIZE - 1 - y) < 1.5:
        edge = 0.14                                 # a thin brighter rim
    base = 24 + 26 * light + 255 * edge + rng.uniform(-2.5, 2.5)
    v = max(0, min(255, int(base)))
    alpha = (0.70 + 0.10 * light + edge) * cover
    # (a straight-alpha PNG; aapt keeps it, Android premultiplies when it loads it)
    return (v, min(255, v + 2), min(255, v + 5), max(0, min(255, int(alpha * 255))))


def chunk(tag, data):
    c = zlib.crc32(tag + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", c)


def main():
    rng = random.Random(9)
    w = h = SIZE + 2
    rows = []
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            if y == 0:   # stretch across
                p = (0, 0, 0, 255) if STRETCH[0] <= x - 1 < STRETCH[1] else (0, 0, 0, 0)
            elif x == 0: # stretch down
                p = (0, 0, 0, 255) if STRETCH[0] <= y - 1 < STRETCH[1] else (0, 0, 0, 0)
            elif y == h - 1 or x == w - 1:
                p = (0, 0, 0, 0)
            else:
                p = pixel(x - 1, y - 1, rng)
            row.extend(p)
        rows.append(bytes(row))
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")
    with open(OUT, "wb") as f:
        f.write(png)
    print(OUT, len(png), "bytes")


if __name__ == "__main__":
    main()
