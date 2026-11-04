#!/usr/bin/env python
"""The map's fonts as a pack piece: glyphs.db, signed distance fields of the OS's own typeface, in the 256-code-point ranges MapLibre asks for.

  python -I make_glyphs.py <font.ttf> <out glyphs.db> [--family "Readex Pro AMRI"] [--report out.json]

A change of the UI's font: run this with the new font and a new --family, make_pack.py with the same --family, and change MapStyle.PACK_FONT in the app: the three must say the same name.

Why not the font as it is: MapLibre 10.3.7 shapes Arabic itself (ICU u_shapeArabic), turning letters into Unicode presentation forms (U+FB50-FDFF, U+FE70-FEFF), and draws one
signed-distance glyph per code point. Readex Pro AMRI has its joined forms behind OpenType features (init, medi, fina, rlig), with no code points. So each presentation form is
drawn from the glyph those features give for it, under that form's code point.

Letters the font lacks (Persian and Urdu letters, in a few names only) borrow the nearest letter it has, listed in NEAR and counted in the report: a label never shows a blank for them.

Standard library, numpy, scipy and fontTools: no font-maker, which would be one more download. The file holds one table, the one TileStore.glyph() reads:
  glyphs(name TEXT PRIMARY KEY, data BLOB)   name is "<stack>/<first>-<last>", data the PBF as MapLibre wants it, not gzipped
Licence: the font is SIL OFL 1.1; the glyphs are a modified form of it. It keeps the name "Readex Pro AMRI" (app/src/main/assets/fonts/ReadexPro-OFL.txt, NOTICE-PACK.md).
"""
import argparse
import hashlib
import json
import os
import sqlite3
import sys
import unicodedata

import numpy as np
from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from PIL import Image, ImageDraw
from scipy import ndimage

WEIGHTS = {"Regular": 400, "Bold": 700}
RANGES = [0, 256, 512, 768, 1536, 1792, 7680, 8192, 8448, 64256, 64512, 64768, 65024, 65280]  # OfflineMaps.GLYPH_RANGES, and 7680-7935: real tags such as "Wādī al Mulayḩ"
SIZE = 24  # what MapLibre expects: glyphs at 24 px
BUFFER = 3
RADIUS = 8.0
CUTOFF = 0.25
OVER = 8  # the outline is drawn this many times larger to measure distances
ASCENT = 26  # so that the baseline sits 9 px under the label's middle, as with the common map fonts: cap height 17 px around the middle
# a letter the font lacks -> the nearest it has (only the shape of a few rare names depends on this)
NEAR = {0x06A9: 0x0643, 0x06AA: 0x0643, 0x06AF: 0x0643, 0x06B3: 0x0643, 0x067E: 0x0628, 0x0680: 0x0628, 0x0686: 0x062C, 0x0683: 0x062C, 0x0698: 0x0632, 0x06CC: 0x064A, 0x06D0: 0x064A, 0x06D2: 0x064A, 0x06D3: 0x064A, 0x0679: 0x062A,
        0x067A: 0x062A, 0x067F: 0x062A, 0x0688: 0x062F, 0x068C: 0x062F, 0x068D: 0x062F, 0x068E: 0x062F, 0x0691: 0x0631, 0x06BE: 0x0647, 0x06C0: 0x0647, 0x06C1: 0x0647, 0x06C2: 0x0647, 0x06C3: 0x0629, 0x06D5: 0x0647, 0x0672: 0x0627,
        0x0673: 0x0627, 0x0675: 0x0627, 0x0676: 0x0648, 0x0677: 0x0648, 0x06C4: 0x0648, 0x06C5: 0x0648, 0x06C6: 0x0648, 0x06C7: 0x0648, 0x06C8: 0x0648, 0x06C9: 0x0648, 0x06CA: 0x0648, 0x06CB: 0x0648, 0x06CF: 0x0648, 0x0678: 0x064A,
        0x067B: 0x0628, 0x0684: 0x062C, 0x0687: 0x062C, 0x06A6: 0x0641, 0x06AD: 0x0643, 0x06B1: 0x0643, 0x06BB: 0x0646, 0x06BC: 0x0646, 0x06A0: 0x063A, 0x06A2: 0x0641, 0x06A3: 0x0641, 0x06A5: 0x0641, 0x06A7: 0x0642, 0x06A8: 0x0642, 0x06A4: 0x06A4, 0x06BA: 0x06BA}
# a few characters often found in names that some fonts lack
SPARE = {0x2019: 0x27, 0x2018: 0x27, 0x201C: 0x22, 0x201D: 0x22, 0x2013: 0x2D, 0x2014: 0x2D, 0x2212: 0x2D, 0x00A0: 0x20, 0x202F: 0x20, 0x2009: 0x20, 0x200A: 0x20, 0x2002: 0x20, 0x2003: 0x20}


def varint(v):
    out = bytearray()
    while v >= 0x80:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)
    return bytes(out)


def f_varint(num, v):
    return varint(num << 3) + varint(v)


def f_sint(num, v):
    return varint(num << 3) + varint((v << 1) ^ (v >> 31))


def f_bytes(num, data):
    return varint((num << 3) | 2) + varint(len(data)) + data


class Forms:
    """What the font's own features say a letter looks like in each position."""

    def __init__(self, font):
        gsub = font["GSUB"].table
        self.single = {"init": {}, "medi": {}, "fina": {}, "isol": {}}
        self.lig = {}
        arab = next(s for s in gsub.ScriptList.ScriptRecord if s.ScriptTag == "arab").Script.DefaultLangSys
        for i in arab.FeatureIndex:
            r = gsub.FeatureList.FeatureRecord[i]
            for li in r.Feature.LookupListIndex:
                look = gsub.LookupList.Lookup[li]
                for st in look.SubTable:
                    if hasattr(st, "ExtSubTable"):
                        st = st.ExtSubTable
                    if r.FeatureTag in self.single and hasattr(st, "mapping"):
                        self.single[r.FeatureTag].update(st.mapping)
                    elif r.FeatureTag in ("rlig", "liga") and hasattr(st, "ligatures"):
                        for first, ligs in st.ligatures.items():
                            for lg in ligs:
                                self.lig[(first, *lg.Component)] = lg.LigGlyph


def form_glyph(cp, font, cmap, forms, near_used):
    """The glyph of a presentation form, or None."""
    d = unicodedata.decomposition(chr(cp)).split()
    if len(d) < 2 or not d[0].startswith("<"):
        return None
    tag = {"<isolated>": "isol", "<initial>": "init", "<medial>": "medi", "<final>": "fina"}.get(d[0])
    if tag is None:
        return None
    letters = [int(x, 16) for x in d[1:]]
    if len(letters) == 1:
        c = letters[0]
        if c not in cmap and c in NEAR and NEAR[c] in cmap:
            near_used.add(c)
            c = NEAR[c]
        g = cmap.get(c)
        if g is None:
            return None
        return forms.single[tag].get(g, g)
    if len(letters) == 2 and letters[0] == 0x644:  # lam-alef, the one ligature ICU makes
        lam, alef = cmap.get(letters[0]), cmap.get(letters[1])
        if lam is None or alef is None:
            return None
        lam_tag = "medi" if tag == "fina" else "init"
        for a in (forms.single["fina"].get(alef, alef), alef):
            for b in (forms.single[lam_tag].get(lam, lam), lam):
                g = forms.lig.get((b, a))
                if g:
                    return g
    return None


def outlines(glyphset, name):
    """Contours as lists of points in font units, curves flattened."""
    pen = DecomposingRecordingPen(glyphset)
    glyphset[name].draw(pen)
    contours = []
    cur = []
    for op, args in pen.value:
        if op == "moveTo":
            cur = [args[0]]
        elif op == "lineTo":
            cur.append(args[0])
        elif op == "qCurveTo":
            pts = list(args)
            p0 = cur[-1]
            if pts[-1] is None:  # all off-curve: not used by this font's outlines, but legal
                pts = pts[:-1]
                pts.append(((pts[0][0] + pts[-1][0]) / 2, (pts[0][1] + pts[-1][1]) / 2))
            for i in range(len(pts) - 1):
                c = pts[i]
                e = pts[i + 1] if i == len(pts) - 2 else ((pts[i][0] + pts[i + 1][0]) / 2, (pts[i][1] + pts[i + 1][1]) / 2)
                for t in (0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875, 1.0):
                    a = (1 - t) ** 2
                    b = 2 * t * (1 - t)
                    d = t * t
                    cur.append((a * p0[0] + b * c[0] + d * e[0], a * p0[1] + b * c[1] + d * e[1]))
                p0 = e
        elif op == "curveTo":
            p0 = cur[-1]
            c1, c2, e = args
            for t in (0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875, 1.0):
                cur.append(((1 - t) ** 3 * p0[0] + 3 * t * (1 - t) ** 2 * c1[0] + 3 * t * t * (1 - t) * c2[0] + t ** 3 * e[0], (1 - t) ** 3 * p0[1] + 3 * t * (1 - t) ** 2 * c1[1] + 3 * t * t * (1 - t) * c2[1] + t ** 3 * e[1]))
        elif op in ("closePath", "endPath"):
            if len(cur) >= 3:
                contours.append(cur)
            cur = []
    return contours


def signed_area(c):
    return sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(c, c[1:] + c[:1])) / 2


def sdf(contours, upm):
    """(bitmap bytes, width, height, left, top) of one glyph at 24 px, or None for an empty glyph."""
    k = SIZE / upm
    xs = [p[0] * k for c in contours for p in c]
    ys = [p[1] * k for c in contours for p in c]
    if not xs:
        return None
    x0, x1 = int(np.floor(min(xs))), int(np.ceil(max(xs)))
    y0, y1 = int(np.floor(min(ys))), int(np.ceil(max(ys)))
    w, h = max(1, x1 - x0), max(1, y1 - y0)
    pad = BUFFER + 6
    W, H = (w + 2 * pad) * OVER, (h + 2 * pad) * OVER
    wind = np.zeros((H, W), dtype=np.int16)
    for c in contours:
        pts = [((p[0] * k - x0 + pad) * OVER, (y1 - p[1] * k + pad) * OVER) for p in c]
        im = Image.new("1", (W, H), 0)
        ImageDraw.Draw(im).polygon(pts, fill=1)
        m = np.asarray(im, dtype=bool)
        # TrueType: nonzero winding. Overlapping contours (this variable font has them) add up, counters take away.
        wind[m] += 1 if signed_area(c) < 0 else -1
    inside = wind != 0
    dist = (ndimage.distance_transform_edt(~inside) - ndimage.distance_transform_edt(inside)) / OVER
    bw, bh = w + 2 * BUFFER, h + 2 * BUFFER
    half = OVER // 2
    rows = (pad - BUFFER) * OVER + half + np.arange(bh) * OVER
    cols = (pad - BUFFER) * OVER + half + np.arange(bw) * OVER
    d = dist[np.ix_(rows, cols)]
    a = 255.0 - 255.0 * (d / RADIUS + CUTOFF)
    return np.clip(np.floor(a + 0.5), 0, 255).astype(np.uint8).tobytes(), w, h, x0, y1 - ASCENT


def glyph_pbf(cp, g):
    body = f_varint(1, cp)
    bitmap, w, h, left, top, adv = g
    if bitmap:
        body += f_bytes(2, bitmap)
    body += f_varint(3, w) + f_varint(4, h) + f_sint(5, left) + f_sint(6, top) + f_varint(7, adv)
    return f_bytes(3, body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("font")
    ap.add_argument("out")
    ap.add_argument("--family", default="Readex Pro AMRI", help="the stacks' names are this + Regular, Bold; the font must be a variable one with a wght axis")
    ap.add_argument("--report")
    a = ap.parse_args()
    stacks = {f"{a.family} {name}": w for name, w in WEIGHTS.items()}
    sys.stdout.reconfigure(encoding="utf-8")
    report = {"font": os.path.basename(a.font), "font_sha256": hashlib.sha256(open(a.font, "rb").read()).hexdigest(), "stacks": {}, "ranges": RANGES}
    if os.path.exists(a.out):
        os.remove(a.out)
    db = sqlite3.connect(a.out)
    db.execute("PRAGMA page_size=4096")
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("CREATE TABLE glyphs(name TEXT PRIMARY KEY, data BLOB NOT NULL)")
    total = 0
    for stack, weight in stacks.items():
        font = instancer.instantiateVariableFont(TTFont(a.font), {"wght": weight, "HEXP": 0})
        glyphset = font.getGlyphSet()
        cmap = font.getBestCmap()
        forms = Forms(font)
        upm = font["head"].unitsPerEm
        hmtx = font["hmtx"]
        made = {}
        near_used = set()
        missing_forms = []

        def add(cp, name):
            adv = int(round(hmtx[name][0] * SIZE / upm))
            r = sdf(outlines(glyphset, name), upm)
            made[cp] = (r[0], r[1], r[2], r[3], r[4], adv) if r else (b"", 0, 0, 0, 0, adv)

        for start in RANGES:
            for cp in range(start, start + 256):
                name = cmap.get(cp)
                if name is None and cp in SPARE:
                    name = cmap.get(SPARE[cp])
                if name is None and cp in NEAR and NEAR[cp] in cmap:
                    near_used.add(cp)
                    name = cmap[NEAR[cp]]
                if name is None and 0x1E00 <= cp <= 0x1EFF:  # ḩ, ṭ: the letter without its mark rather than a gap
                    b = unicodedata.normalize("NFD", chr(cp))[0]
                    name = cmap.get(ord(b))
                if name is None and (0xFB50 <= cp <= 0xFDFF or 0xFE70 <= cp <= 0xFEFC):
                    name = form_glyph(cp, font, cmap, forms, near_used)
                    if name is None and unicodedata.decomposition(chr(cp)).startswith("<") and len(unicodedata.decomposition(chr(cp)).split()) == 2:
                        missing_forms.append(hex(cp))
                if name is not None:
                    add(cp, name)
        pbf_bytes = 0
        ranges_done = 0
        for start in RANGES:
            label = f"{start}-{start + 255}"
            body = f_bytes(1, stack.encode()) + f_bytes(2, label.encode())
            n = 0
            for cp in range(start, start + 256):
                if cp in made:
                    body += glyph_pbf(cp, made[cp])
                    n += 1
            if not n:
                continue
            data = f_bytes(1, body)
            db.execute("INSERT INTO glyphs VALUES(?,?)", (f"{stack}/{label}", data))
            pbf_bytes += len(data)
            ranges_done += 1
        total += pbf_bytes
        report["stacks"][stack] = {"weight": weight, "glyphs": len(made), "ranges": ranges_done, "bytes": pbf_bytes, "letters_borrowed": sorted(hex(c) for c in near_used), "forms_with_no_glyph": missing_forms,
                                   "code_points": sorted(made)}
        print(stack, "glyphs", len(made), "ranges", ranges_done, "bytes", pbf_bytes, "borrowed", len(near_used), "forms with no glyph", len(missing_forms), flush=True)
    db.commit()
    db.execute("VACUUM")
    db.close()
    report["file_bytes"] = os.path.getsize(a.out)
    print("file", report["file_bytes"], flush=True)
    if a.report:
        with open(a.report, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False)


if __name__ == "__main__":
    main()
