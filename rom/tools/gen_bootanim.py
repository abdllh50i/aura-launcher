"""Generates the Aura boot animation (bootanimation.zip, 1024x600).
Default (--car DIR, the full-size turntable bake of the owner's car, frame_NNN.png with alpha):
   part0: a floor light comes up, the car turns into its showroom angle, the wordmark fades in (plays once)
   part1: a soft light sweeps over the car now and then (loops until the system has booted)
Without --car (the 1.0 design):
   part0: the ring draws itself, the core and the wordmark fade in (plays once)
   part1: calm glow pulse (loops until the system has booted)
Usage: python gen_bootanim.py OUT.zip [--car DIR [--frames FROM,TO]] [--preview DIR]
   --frames: the bake frames of the turn (default 58,80 for the 90-frame bake; the release uses a 180-frame bake of
   frames 116..160 = the same 232..320 degrees in 2-degree steps: tools/car3d/bake.ps1 ... frames=180, only=116..160)
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


# ------------------------------------------------------------------------------------------------ car animation
CAR_FROM, CAR_TO = 58, 80          # bake frames (4 degrees apart): front-left view -> the home screen's showroom angle
CAR_W = 600                        # width of the car on screen at rest, px
CAR_C = (512, 262)                 # where the car's box centre sits
TEXT_Y = 468


def load_car(d, i, scale):
    """Bake frame i, scaled, as a premultiplied float array (h, w, 4) in 0..1."""
    im = Image.open(os.path.join(d, "frame_%03d.png" % i)).convert("RGBA").convert("RGBa")
    im = im.resize((int(im.width * scale + 0.5), int(im.height * scale + 0.5)), Image.LANCZOS)
    return np.asarray(im).astype(np.float32) / 255.0


def car_box(a):
    ys, xs = np.nonzero(a[..., 3] > 0.94)
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1


def floor_light(k):
    """Soft cool light on the floor under the car, k in 0..1."""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    dx = (xx - CAR_C[0]) / 430.0
    dy = (yy - (CAR_C[1] + 92)) / 64.0
    g = np.exp(-(dx * dx + dy * dy) * 1.6)
    col = np.array([0.20, 0.23, 0.30], np.float32)
    return g[..., None] * col[None, None, :] * k


def sweep_mask(shape, pos):
    """Diagonal light band; pos in 0..1 moves it from the left to the right of the car."""
    h, w = shape
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    u = (xx + 0.45 * yy) / (w + 0.45 * h)
    c = -0.25 + 1.5 * pos
    return np.exp(-((u - c) / 0.07) ** 2)


def text_rgba(t):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    if t > 0:
        d = ImageDraw.Draw(im)
        f = font(30)
        text = "A U R A"
        wd = d.textlength(text, font=f)
        d.text((CX - wd / 2, TEXT_Y), text, font=f, fill=(WHITE[0], WHITE[1], WHITE[2], int(230 * ease(t))))
    return np.asarray(im).astype(np.float32) / 255.0


def car_frame(car, light_k, car_k, text_t, sweep=None):
    """Composite: black, floor light, the car (premultiplied, with its own floor shadow), optional sweep, wordmark."""
    out = floor_light(light_k)
    h, w = car.shape[:2]
    x0, y0 = car_pos
    sx, sy = max(0, -x0), max(0, -y0)            # crop the (larger) render to the screen
    dx, dy = max(0, x0), max(0, y0)
    cw, ch = min(w - sx, W - dx), min(h - sy, H - dy)
    c = car[sy:sy + ch, sx:sx + cw]
    a = c[..., 3:4]                              # the car comes out of the dark: brightness fades in, never see-through
    rgb = c[..., :3] * car_k
    if sweep is not None:
        body = np.clip((c[..., 3:4] - 0.9) * 10.0, 0.0, 1.0)
        rgb = rgb + sweep[sy:sy + ch, sx:sx + cw, None] * body * 0.22 * car_k
    region = out[dy:dy + ch, dx:dx + cw]
    out[dy:dy + ch, dx:dx + cw] = rgb + region * (1.0 - a)
    tx = text_rgba(text_t)
    out = tx[..., :3] * tx[..., 3:4] + out * (1.0 - tx[..., 3:4])
    return Image.fromarray(np.clip(out * 255.0 + 0.5, 0, 255).astype(np.uint8))


car_pos = (0, 0)


def car_animation(d):
    global car_pos
    ref = Image.open(os.path.join(d, "frame_%03d.png" % CAR_TO)).convert("RGBA")
    full = np.asarray(ref)[..., 3]
    ys, xs = np.nonzero(full > 240)
    scale = CAR_W / float(xs.max() + 1 - xs.min())
    frames = {i: load_car(d, i, scale) for i in range(CAR_FROM, CAR_TO + 1)}
    bx0, by0, bx1, by1 = car_box(frames[CAR_TO])
    car_pos = (int(CAR_C[0] - (bx0 + bx1) / 2), int(CAR_C[1] - (by0 + by1) / 2))

    def car_at(p):
        """Nearest bake frame: at full screen size a cross-fade of two views shows as a double image, so the turn
        relies on finely spaced renders (--frames on a 2-degree bake) instead."""
        return frames[min(CAR_TO, max(CAR_FROM, int(round(p))))]

    def turn_at(t):
        return CAR_FROM + (CAR_TO - CAR_FROM) * (1.0 - (1.0 - min(1.0, t / 0.8)) ** 3)  # ease-out over 80 %

    part0, part1 = [], []
    n0 = 40
    for k in range(n0):
        t = k / (n0 - 1)
        part0.append(car_frame(car_at(turn_at(t)), ease(t / 0.35), ease(t / 0.4), (t - 0.6) / 0.34))
    rest = frames[CAR_TO]
    n1 = 17                                                     # the sweep crosses in ~1 s; desc.txt adds a pause
    for k in range(n1):
        part1.append(car_frame(rest, 1.0, 1.0, 1.0, sweep_mask(rest.shape[:2], k / (n1 - 1.0))))
    return part0, part1


def main():
    out = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    car_dir = sys.argv[sys.argv.index("--car") + 1] if "--car" in sys.argv else None
    if "--frames" in sys.argv:
        global CAR_FROM, CAR_TO
        CAR_FROM, CAR_TO = (int(v) for v in sys.argv[sys.argv.index("--frames") + 1].split(","))
    if car_dir:
        part0, part1 = car_animation(car_dir)
        # part1 holds its last (clean) frame for 2 s between sweeps
        write(out, part0, part1, prev, (0, 8, 14, 22, 39), pause1=30, palette=True)
        return
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
    write(out, part0, part1, prev, (0, 10, 20, 30, 37))


def to_palette(im):
    """256-colour PNG with error diffusion: about a third of the size, no visible banding on dark gradients."""
    return im.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.FLOYDSTEINBERG)


def write(out, part0, part1, prev, samples, pause1=0, palette=False):
    if palette:
        part0 = [to_palette(im) for im in part0]
        part1 = [to_palette(im) for im in part1]
    if prev:
        os.makedirs(prev, exist_ok=True)
        for idx in samples:
            part0[idx].save(os.path.join(prev, "p0_%02d.png" % idx))
        for idx in (0, len(part1) // 2):
            part1[idx].save(os.path.join(prev, "p1_%02d.png" % idx))
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
        z.writestr("desc.txt", "%d %d %d\np 1 0 part0\np 0 %d part1\n" % (W, H, FPS, pause1))
        for i, im in enumerate(part0):
            z.writestr("part0/%05d.png" % i, png_bytes(im))
        for i, im in enumerate(part1):
            z.writestr("part1/%05d.png" % i, png_bytes(im))
    print("wrote %s  (%d + %d frames, %.2f MB)" % (out, len(part0), len(part1), os.path.getsize(out) / 1048576))


if __name__ == "__main__":
    main()
