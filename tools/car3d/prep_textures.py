"""Prepares the Meshy textures for the car baker (runs on the emulator GPU).

    python prep_textures.py TEXDIR OUTDIR [--size 4096]

  basecolor.jpg  base colour, downscaled (the GPU cannot take 8192 comfortably through the emulator)
  rm.png         R = roughness, G = metallic (packed, 2048)
"""
import os
import sys

import numpy as np
from PIL import Image

Image.MAX_IMAGE_PIXELS = None


def main():
    src, out = sys.argv[1], sys.argv[2]
    size = int(sys.argv[sys.argv.index("--size") + 1]) if "--size" in sys.argv else 4096
    os.makedirs(out, exist_ok=True)
    base = Image.open(os.path.join(src, "Image_0.jpg")).convert("RGB")
    base.resize((size, size), Image.LANCZOS).save(os.path.join(out, "basecolor.jpg"), quality=95, subsampling=0)
    rough = np.asarray(Image.open(os.path.join(src, "texture_0_roughness.png")).convert("L").resize((2048, 2048), Image.LANCZOS))
    metal = np.asarray(Image.open(os.path.join(src, "texture_0_metallic.png")).convert("L").resize((2048, 2048), Image.LANCZOS))
    rm = np.stack([rough, metal, np.zeros_like(rough)], axis=2)
    Image.fromarray(rm.astype(np.uint8), "RGB").save(os.path.join(out, "rm.png"), optimize=True)
    print("roughness: mean %.2f  min %d max %d | metallic: mean %.2f, >128: %.1f%%" % (
        rough.mean() / 255, rough.min(), rough.max(), metal.mean() / 255, 100.0 * (metal > 128).mean()))
    for n in ("basecolor.jpg", "rm.png"):
        print("%-14s %.1f MB" % (n, os.path.getsize(os.path.join(out, n)) / 1048576))


if __name__ == "__main__":
    main()
