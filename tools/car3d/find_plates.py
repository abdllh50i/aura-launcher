"""Finds the licence-plate surfaces of the car (flat, facing straight forward/backward, at the very front/back, centred)
and paints them plain in the base-colour texture (the model ships with a dummy 'HUM3D' plate).

    python find_plates.py OUTDIR      (reads OUTDIR/mesh.bin and OUTDIR/basecolor.jpg, writes OUTDIR/basecolor.jpg)
"""
import os
import struct
import sys

import numpy as np
from PIL import Image, ImageDraw

Image.MAX_IMAGE_PIXELS = None


def load(path):
    b = open(path, "rb").read()
    nv, ni = struct.unpack_from("<II", b, 8)
    o = 16
    pos = np.frombuffer(b, "<f4", nv * 3, o).reshape(-1, 3); o += nv * 12
    nrm = np.frombuffer(b, "<f4", nv * 3, o).reshape(-1, 3); o += nv * 12
    uv = np.frombuffer(b, "<f4", nv * 2, o).reshape(-1, 2); o += nv * 8
    idx = np.frombuffer(b, "<u4", ni, o).reshape(-1, 3)
    return pos, nrm, uv, idx


def main():
    out = sys.argv[1]
    pos, nrm, uv, idx = load(os.path.join(out, "mesh.bin"))
    tp = pos[idx]                                   # (m, 3, 3)
    c = tp.mean(1)
    fn = np.cross(tp[:, 1] - tp[:, 0], tp[:, 2] - tp[:, 0])
    area = np.linalg.norm(fn, axis=1)
    fn = fn / (area[:, None] + 1e-12)
    xmax, xmin = pos[:, 0].max(), pos[:, 0].min()
    found = []
    # Plate boxes in car space (units of the converted mesh), measured with a brightness map of the front/back centre:
    # front plate below the grille, rear plate on the boot lid.  (y range, half width)
    boxes = (("front", 1, xmax, (14.6, 19.0), 11.0), ("rear", -1, xmin, (28.6, 35.6), 11.5))
    for name, sign, edge, (y0, y1), half in boxes:
        sel = (np.abs(c[:, 2]) <= half) & (sign * fn[:, 0] > 0.9) & (sign * c[:, 0] > abs(edge) - 7.0)
        sel &= (c[:, 1] >= y0) & (c[:, 1] <= y1)
        if not sel.any():
            print(name, ": nothing found")
            continue
        print("%s plate: %d triangles, y %.1f..%.1f, z %.1f..%.1f, x %.1f..%.1f" % (
            name, sel.sum(), c[sel, 1].min(), c[sel, 1].max(), c[sel, 2].min(), c[sel, 2].max(), c[sel, 0].min(), c[sel, 0].max()))
        found.append(sel)
    if not found:
        return
    tex_path = os.path.join(out, "basecolor.jpg")
    src_path = os.path.join(out, "basecolor_orig.jpg")
    tex = Image.open(src_path if os.path.exists(src_path) else tex_path).convert("RGB")
    W, H = tex.size
    mask = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(mask)
    sel = np.any(np.stack(found), axis=0)
    for t in idx[sel]:
        pts = [(float(uv[i, 0] * W), float(uv[i, 1] * H)) for i in t]
        d.polygon(pts, fill=255)
    m = np.asarray(mask) > 0
    a = np.asarray(tex).astype(np.float32)
    lum = a.mean(2)
    inside = lum[m]
    print("plate texels: %d, brightness median %.0f" % (m.sum(), np.median(inside)))
    # plain plate: every plate texel becomes the plate's own light background colour (median of its bright texels)
    bright = a[m & (lum > np.percentile(inside, 60))]
    colour = np.median(bright, axis=0) if len(bright) else np.array([235, 235, 235], np.float32)
    a[m] = colour
    Image.fromarray(a.astype(np.uint8)).save(tex_path, quality=95, subsampling=0)
    mask.resize((512, 512)).save(os.path.join(out, "plate_mask.png"))
    print("plates painted with", colour.astype(int))


if __name__ == "__main__":
    main()
