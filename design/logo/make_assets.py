"""Turns the owner's logo artwork (white + blue on black) into the assets AMRI OS uses.

  python make_assets.py            (needs Pillow + numpy; run from anywhere)

Writes, next to this file and into the app:
  amri-os-en.png / amri-ar.png                   the logos on transparency (colour un-premultiplied from the black),
  app/src/main/res/drawable-nodpi/logo_amri_en.png, logo_amri_ar.png   the same, sized for the app,
  a_glyph.txt                                    the "A" of the English logo traced as a polygon + the blue dot
                                                 (for the vector launcher icon, see ic_launcher_foreground.xml).
"""
import os
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
APP_RES = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "drawable-nodpi")


def unblack(path):
    """RGBA from art on black: alpha = brightest channel, colour = pixel / alpha; dark noise dropped."""
    a = np.asarray(Image.open(path).convert("RGB")).astype(np.float32) / 255.0
    alpha = a.max(axis=2)
    alpha = np.where(alpha < 0.10, 0.0, (alpha - 0.10) / 0.90)  # the black is not perfectly black (compression)
    alpha = np.clip(alpha, 0.0, 1.0)
    rgb = np.where(alpha[..., None] > 0, a / np.maximum(a.max(axis=2, keepdims=True), 1e-4), 0.0)
    # near-neutral pixels are the white letters: make them exactly white (no grey fringes)
    sat = rgb.max(axis=2) - rgb.min(axis=2)
    rgb = np.where((sat < 0.18)[..., None], 1.0, rgb)
    out = np.dstack([rgb, alpha])
    return out


def crop(rgba, pad):
    ys, xs = np.nonzero(rgba[..., 3] > 0.02)
    y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
    c = rgba[max(0, y0 - pad):y1 + pad, max(0, x0 - pad):x1 + pad]
    return c


def to_image(rgba):
    return Image.fromarray(np.clip(rgba * 255.0 + 0.5, 0, 255).astype(np.uint8))


def fit_line(xs, ys):
    """x = m*y + b by least squares."""
    m, b = np.polyfit(ys, xs, 1)
    return m, b


def trace_a(rgba):
    """The A of the English logo: outer and inner edges as lines, the bottom flat; the blue dot as a circle."""
    alpha = rgba[..., 3] > 0.5
    white = alpha & (rgba[..., 2] - rgba[..., 0] < 0.25)          # not the blue dot
    blue = alpha & (rgba[..., 2] - rgba[..., 0] >= 0.25)
    cols = np.nonzero(white.any(axis=0))[0]
    # the A is the first run of columns (a gap separates it from the M)
    gaps = np.nonzero(np.diff(cols) > 3)[0]
    a_x1 = cols[gaps[0]] + 1 if len(gaps) else cols[-1] + 1
    a = white[:, :a_x1]
    rows = np.nonzero(a.any(axis=1))[0]
    top, bottom = rows.min(), rows.max()
    ol, orr, il, ir, iy, oy = [], [], [], [], [], []
    for y in range(top + 2, bottom - 1):
        xs = np.nonzero(a[y])[0]
        if len(xs) == 0:
            continue
        runs = np.split(xs, np.nonzero(np.diff(xs) > 1)[0] + 1)
        oy.append(y); ol.append(runs[0][0]); orr.append(runs[-1][-1] + 1)
        if len(runs) >= 2:
            iy.append(y); il.append(runs[0][-1] + 1); ir.append(runs[-1][0])
    mol, bol = fit_line(np.array(ol, float), np.array(oy, float))
    mor, bor = fit_line(np.array(orr, float), np.array(oy, float))
    mil, bil = fit_line(np.array(il, float), np.array(iy, float))
    mir, bir = fit_line(np.array(ir, float), np.array(iy, float))
    yb = bottom + 1.0
    apex_y = (bor - bol) / (mol - mor)
    in_y = (bir - bil) / (mil - mir)
    pts = [
        (mol * yb + bol, yb), (mol * apex_y + bol, apex_y), (mor * yb + bor, yb),
        (mir * yb + bir, yb), (mir * in_y + bir, in_y), (mil * yb + bil, yb),
    ]
    by, bx = np.nonzero(blue)
    dot = (bx.mean(), by.mean(), np.sqrt(len(bx) / np.pi))
    return pts, dot


def main():
    en = crop(unblack(os.path.join(HERE, "amri-os-en-source.png")), 6)
    ar = crop(unblack(os.path.join(HERE, "amri-ar-source.png")), 6)
    to_image(en).save(os.path.join(HERE, "amri-os-en.png"))
    to_image(ar).save(os.path.join(HERE, "amri-ar.png"))
    os.makedirs(APP_RES, exist_ok=True)
    for name, rgba, w in (("logo_amri_en", en, 720), ("logo_amri_ar", ar, 560)):
        # "_ink": the letters in the light theme's text colour, the blue unchanged
        ink = rgba.copy()
        white = (ink[..., :3].min(axis=2) > 0.9)
        ink[white, :3] = np.array([0x11, 0x13, 0x17], np.float32) / 255.0
        for suffix, art in (("", rgba), ("_ink", ink)):
            im = to_image(art)
            im = im.resize((w, int(im.height * w / im.width + 0.5)), Image.LANCZOS)
            im.save(os.path.join(APP_RES, name + suffix + ".png"), optimize=True)
            print(name + suffix, im.size)
    pts, dot = trace_a(en)
    with open(os.path.join(HERE, "a_glyph.txt"), "w") as f:
        f.write("polygon " + " ".join("%.2f,%.2f" % p for p in pts) + "\n")
        f.write("dot %.2f,%.2f r=%.2f\n" % dot)
    print("A:", ["(%.1f,%.1f)" % p for p in pts], "dot", "(%.1f,%.1f) r=%.1f" % dot)


if __name__ == "__main__":
    main()
