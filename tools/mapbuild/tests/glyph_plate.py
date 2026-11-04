#!/usr/bin/env python
"""Draws Arabic and Latin labels from glyphs.db the way MapLibre 10.3.7 does: letters to presentation forms, right to left, one signed-distance glyph per code point at its advance,
cut at the same threshold. No renderer or network: a quick look at whether the letters join.   python -I glyph_plate.py <glyphs.db> <out.png> [stack ...]

Not the real renderer. The real one needs the app (MapStyle's font names and the pack), which is the app part of the work.
"""
import sqlite3
import sys
import unicodedata

import numpy as np
from PIL import Image

TEXTS = ["طريق الملك فهد", "شارع الأمير محمد بن عبدالعزيز", "الجبيل الصناعية", "صيدلية النهدي", "مسجد لا إله إلا الله", "الظهران", "خميس مشيط", "حي الفناتير", "مكة المكرمة", "ينبع",
         "شيخ غازي ضياء ظفر", "لؤلؤة ئ ء آ أ إ ة ى", "رقم 94 — طريق 40", "King Fahd Road", "Al Khobar 26.3", "Jeddah (Corniche)"]
RIGHT_ONLY = set("ادذرزوأإآؤةىٱ")  # join to what comes before them only
NONE = set("ء")


def read_pbf(data):
    """{code point: (bitmap, w, h, left, top, advance)} of one range."""
    out = {}

    def fields(b):
        i = 0
        while i < len(b):
            key = 0
            shift = 0
            while True:
                c = b[i]
                i += 1
                key |= (c & 0x7F) << shift
                shift += 7
                if not c & 0x80:
                    break
            num, wt = key >> 3, key & 7
            if wt == 0:
                v = 0
                shift = 0
                while True:
                    c = b[i]
                    i += 1
                    v |= (c & 0x7F) << shift
                    shift += 7
                    if not c & 0x80:
                        break
                yield num, v
            elif wt == 2:
                n = 0
                shift = 0
                while True:
                    c = b[i]
                    i += 1
                    n |= (c & 0x7F) << shift
                    shift += 7
                    if not c & 0x80:
                        break
                yield num, b[i:i + n]
                i += n
            else:
                raise ValueError(wt)

    def unzig(v):
        return (v >> 1) ^ -(v & 1)

    for num, stack in fields(data):
        if num != 1:
            continue
        for n2, g in fields(stack):
            if n2 != 3:
                continue
            d = dict(fields(g))
            out[d[1]] = (d.get(2, b""), d[3], d[4], unzig(d[5]), unzig(d[6]), d[7])
    return out


FORM = {}
for cp in list(range(0xFE70, 0xFEFD)) + list(range(0xFB50, 0xFC00)):
    dec = unicodedata.decomposition(chr(cp)).split()
    if len(dec) == 2 and dec[0].startswith("<"):
        FORM[(int(dec[1], 16), dec[0])] = cp
    elif len(dec) == 3 and dec[0].startswith("<") and int(dec[1], 16) == 0x644:
        FORM[(0x644, int(dec[2], 16), dec[0])] = cp


def is_ar_letter(c):
    return "ؠ" <= c <= "ي" or "ٮ" <= c <= "ۓ"


def shape(text):
    """Logical text to presentation forms, as ICU's u_shapeArabic does, lam-alef ligature included. Returns visual order."""
    out = []
    i = 0
    s = text
    n = len(s)
    while i < n:
        c = s[i]
        if not is_ar_letter(c):
            out.append(ord(c))
            i += 1
            continue
        prev = s[i - 1] if i > 0 else ""
        next_ = s[i + 1] if i + 1 < n else ""
        joins_prev = is_ar_letter(prev) and prev not in RIGHT_ONLY and prev not in NONE and c not in NONE
        if c == "ل" and next_ in "اأإآ" and next_:
            f = FORM.get((0x644, ord(next_), "<final>" if joins_prev else "<isolated>"))
            out.append(f or ord(c))
            i += 2
            continue
        joins_next = c not in RIGHT_ONLY and c not in NONE and is_ar_letter(next_)
        tag = "<medial>" if joins_prev and joins_next else "<final>" if joins_prev else "<initial>" if joins_next else "<isolated>"
        out.append(FORM.get((ord(c), tag)) or FORM.get((ord(c), "<isolated>")) or ord(c))
        i += 1
    if any(is_ar_letter(c) for c in text):
        # simple bidi for a label: a run of digits or Latin stays left to right inside the reversed line
        rev = []
        run = []
        for cp in out:
            ch = chr(cp)
            if ch.isascii() and (ch.isalnum() or ch in ".-"):
                run.append(cp)
            else:
                if run:
                    rev = run + rev
                    run = []
                rev = [cp] + rev
        if run:
            rev = run + rev
        return rev
    return out


def main():
    db = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
    stacks = sys.argv[3:] or ["AMRI Map Sans Regular", "AMRI Map Sans SemiBold"]
    width, row_h, scale = 720, 44, 1.5
    canvas = np.full((row_h * len(TEXTS) * len(stacks) // 1 + 20, width), 245, dtype=np.uint8)
    y = 34
    missing = set()
    for stack in stacks:
        glyphs = {}
        for name, data in db.execute("SELECT name,data FROM glyphs WHERE name LIKE ?", (stack + "/%",)):
            glyphs.update(read_pbf(bytes(data)))
        for text in TEXTS:
            x = 12.0
            for cp in shape(text):
                g = glyphs.get(cp)
                if g is None:
                    missing.add(hex(cp))
                    continue
                bitmap, w, h, left, top, adv = g
                if bitmap:
                    a = np.frombuffer(bitmap, dtype=np.uint8).reshape(h + 6, w + 6).astype(np.float32)
                    H, W = int((h + 6) * scale), int((w + 6) * scale)
                    a2 = np.asarray(Image.fromarray(a, "F").resize((W, H), Image.BICUBIC))
                    ink = np.clip((a2 - 192 + 12) / 24, 0, 1)
                    # as MapLibre places it: 17 px above the anchor line, the top counted down from there
                    y0 = int(y + (-17 - top - 3) * scale)
                    x0 = int(x + (left - 3) * scale)
                    y1, x1 = min(canvas.shape[0], y0 + H), min(width, x0 + W)
                    if y0 >= 0 and x0 >= 0 and y1 > y0 and x1 > x0:
                        region = canvas[y0:y1, x0:x1]
                        canvas[y0:y1, x0:x1] = np.minimum(region, (245 - ink[: y1 - y0, : x1 - x0] * 225).astype(np.uint8))
                x += adv * scale
            y += row_h
    Image.fromarray(canvas).save(sys.argv[2])
    print("no glyph:", sorted(missing))


if __name__ == "__main__":
    main()
