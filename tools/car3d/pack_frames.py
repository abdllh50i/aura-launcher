"""Turns the baked turntable frames into the assets the app ships.

    python pack_frames.py FRAMEDIR ASSETDIR [--size 640x360] [--quality 86] [--default 80] [--yaw-step 4]

Writes ASSETDIR/f_000.webp ... (premultiplied-correct downscale, lossy WebP with alpha) and ASSETDIR/car.json:
  {"frames": N, "width": W, "height": H, "yawStep": 4, "default": 80, "boxes": [[x0, y0, x1, y1], ...]}
where boxes are the car's opaque pixels in each frame (for framing in the UI).
"""
import json
import os
import sys

import numpy as np
from PIL import Image


def main():
    src, dst = sys.argv[1], sys.argv[2]
    size = tuple(int(v) for v in (sys.argv[sys.argv.index("--size") + 1] if "--size" in sys.argv else "640x360").split("x"))
    quality = int(sys.argv[sys.argv.index("--quality") + 1]) if "--quality" in sys.argv else 86
    default = int(sys.argv[sys.argv.index("--default") + 1]) if "--default" in sys.argv else 80
    step = float(sys.argv[sys.argv.index("--yaw-step") + 1]) if "--yaw-step" in sys.argv else 4.0
    os.makedirs(dst, exist_ok=True)
    names = sorted(n for n in os.listdir(src) if n.startswith("frame_") and n.endswith(".png"))
    boxes = []
    total = 0
    # fade the very edge of the frame so a floor shadow can never end in a hard line
    w, h = size
    yy, xx = np.mgrid[0:h, 0:w]
    edge = np.minimum.reduce([xx, w - 1 - xx, yy, h - 1 - yy]).astype(np.float32)
    fade = np.clip(edge / 10.0, 0.0, 1.0)
    for i, n in enumerate(names):
        im = Image.open(os.path.join(src, n)).convert("RGBA").convert("RGBa")       # premultiplied for resampling
        im = im.resize(size, Image.LANCZOS).convert("RGBA")
        a = np.asarray(im).astype(np.float32)
        a[..., 3] *= fade
        im = Image.fromarray(np.clip(a + 0.5, 0, 255).astype(np.uint8), "RGBA")
        alpha = a[..., 3]
        ys, xs = np.nonzero(alpha > 240)
        boxes.append([int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1] if len(xs) else [0, 0, w, h])
        out = os.path.join(dst, "f_%03d.webp" % i)
        im.save(out, "WEBP", quality=quality, alpha_quality=92, method=6)
        total += os.path.getsize(out)
    meta = {"frames": len(names), "width": w, "height": h, "yawStep": step, "default": default, "boxes": boxes}
    with open(os.path.join(dst, "car.json"), "w") as f:
        json.dump(meta, f, separators=(",", ":"))
    print("%d frames, %.2f MB total (%.1f KB each)" % (len(names), total / 1048576.0, total / 1024.0 / max(1, len(names))))
    print("default frame box:", boxes[default] if default < len(boxes) else None)


if __name__ == "__main__":
    main()
