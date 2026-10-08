"""Generates the Aura boot animation (bootanimation.zip, 1024x600).
   part0: the ring draws itself, the core and the wordmark fade in (plays once)
   part1: calm glow pulse (loops until the system has booted)
Usage: python gen_bootanim.py OUT.zip [--preview DIR]
Needs Pillow + numpy. The zip is written STORED (no compression), as the platform requires.
"""
import io, math, os, sys, zipfile
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H, FPS = 1024, 600, 15   # modest frame rate: the head unit's CPU decodes every PNG on the fly (the stock animation runs at 10)
CX, CY = 512, 262
R = 66                     # ring radius
ACCENT = (62, 106, 225)    # same blue as the UI default accent
WHITE = (244, 246, 248)
SS = 2                     # supersampling for the sharp layers

FONT_PATHS = [r"C:\Windows\Fonts\segoeuil.ttf", r"C:\Windows\Fonts\segoeui.ttf", r"C:\Windows\Fonts\arial.ttf"]


def font(size):
    for p in FONT_PATHS:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def ease(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def ring_layer(sweep, size=(W, H), scale=SS, width=3.2):
    """Antialiased ring arc, `sweep` in 0..1 (clockwise from 12 o'clock)."""
    w, h = size[0] * scale, size[1] * scale
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if sweep <= 0.001:
        return im.resize(size, Image.LANCZOS)
    box = [(CX - R) * scale, (CY - R) * scale, (CX + R) * scale, (CY + R) * scale]
    steps = max(2, int(sweep * 240))
    for i in range(steps):
        a0 = -90 + 360 * sweep * i / steps
        a1 = -90 + 360 * sweep * (i + 1) / steps + 0.6
        k = (i + 1) / steps                      # head of the arc is whiter, tail is accent blue
        col = tuple(int(ACCENT[j] + (WHITE[j] - ACCENT[j]) * ease(k) ) for j in range(3))
        d.arc(box, a0, a1, fill=col + (255,), width=int(width * scale))
    return im.resize(size, Image.LANCZOS)


def glow_of(layer, radius, strength):
    g = layer.filter(ImageFilter.GaussianBlur(radius))
    a = np.asarray(g).astype(np.float32)
    a[..., 3] *= strength
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")


def core_layer(t):
    """Centre dot with a soft halo, t in 0..1."""
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    if t <= 0:
        return im
    d = ImageDraw.Draw(im)
    r = 7 * (0.6 + 0.4 * ease(t))
    d.ellipse([CX - r, CY - r, CX + r, CY + r], fill=WHITE + (int(255 * ease(t)),))
    return im


def text_layer(t):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    if t <= 0:
        return im
    d = ImageDraw.Draw(im)
    f = font(34)
    text = "A U R A"
    wd = d.textlength(text, font=f)
    d.text((CX - wd / 2, CY + R + 58), text, font=f, fill=(WHITE[0], WHITE[1], WHITE[2], int(235 * ease(t))))
    return im


def compose(sweep, glow_k, core_t, text_t):
    base = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    ring = ring_layer(sweep)
    # soft background light behind the ring
    halo = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    hd = ImageDraw.Draw(halo)
    hd.ellipse([CX - 190, CY - 190, CX + 190, CY + 190], fill=ACCENT + (int(42 * glow_k * min(1.0, sweep * 1.4)),))
    halo = halo.filter(ImageFilter.GaussianBlur(70))
    out = Image.alpha_composite(base, halo)
    out = Image.alpha_composite(out, glow_of(ring, 11, 0.95 * glow_k))
    out = Image.alpha_composite(out, glow_of(ring, 3, 0.7))
    out = Image.alpha_composite(out, ring)
    out = Image.alpha_composite(out, core_layer(core_t))
    out = Image.alpha_composite(out, text_layer(text_t))
    return out.convert("RGB")


def png_bytes(im):
    b = io.BytesIO()
    im.save(b, "PNG", optimize=True)
    return b.getvalue()


def main():
    out = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    part0, part1 = [], []
    n0 = 38
    for i in range(n0):
        t = i / (n0 - 1)
        sweep = ease(t / 0.62)
        core_t = (t - 0.55) / 0.2
        text_t = (t - 0.68) / 0.3
        part0.append(compose(sweep, 0.55 + 0.45 * ease(t), core_t, text_t))
    n1 = 24
    for i in range(n1):
        k = 0.5 + 0.5 * math.sin(2 * math.pi * i / n1 - math.pi / 2)  # 0..1..0
        part1.append(compose(1.0, 0.72 + 0.28 * k, 1.0, 1.0))
    if prev:
        os.makedirs(prev, exist_ok=True)
        for idx in (0, 10, 20, 30, 37):
            part0[idx].save(os.path.join(prev, "p0_%02d.png" % idx))
        part1[0].save(os.path.join(prev, "p1_00.png"))
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
        z.writestr("desc.txt", "%d %d %d\np 1 0 part0\np 0 0 part1\n" % (W, H, FPS))
        for i, im in enumerate(part0):
            z.writestr("part0/%05d.png" % i, png_bytes(im))
        for i, im in enumerate(part1):
            z.writestr("part1/%05d.png" % i, png_bytes(im))
    print("wrote %s  (%d + %d frames, %.2f MB)" % (out, len(part0), len(part1), os.path.getsize(out) / 1048576))


if __name__ == "__main__":
    main()
