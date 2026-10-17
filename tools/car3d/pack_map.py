"""The car on the map (1.14): the owner's car, the same model as the home screen's, seen the way the map sees it.

    python pack_map.py TILTDIR TOPDIR ASSETDIR [--size 176] [--quality 88]

TILTDIR: a turntable baked by CarBakerActivity for the navigating map (72 frames, 5 degrees apart, camera 38 degrees
above the road: the map's 52 degree tilt), e.g. bake.ps1 -Params "frames=72`nwidth=176`nheight=176`nss=4`nelevation=38`n
target=0.0`nmargin=0.9`nshadow=0.7`nexposure=0.8`nclearcoat=1.4`ndirs=48`ntile=512". target=0 puts the point under the
car's middle at the frame's centre, so the frame's centre is where the car is on the map.
TOPDIR: one frame from straight above (elevation=89, yaw0=90: the car pointing up), for the flat map.
Writes ASSETDIR/t_00.webp ... t_71.webp and top.webp (premultiplied-correct downscale, lossy WebP with alpha).
Frame k shows the car at yaw 5k degrees: yaw 0 points right on the screen, 90 up, 180 left (counter-clockwise).
"""
import os
import sys

from PIL import Image


def shrink(src, size):
    im = Image.open(src).convert("RGBA")
    # (through premultiplied alpha: a plain resize would draw dark fringes from the transparent pixels' black)
    return im.convert("RGBa").resize((size, size), Image.LANCZOS).convert("RGBA")


def main():
    tilt, top, dst = sys.argv[1], sys.argv[2], sys.argv[3]
    size = int(sys.argv[sys.argv.index("--size") + 1]) if "--size" in sys.argv else 176
    quality = int(sys.argv[sys.argv.index("--quality") + 1]) if "--quality" in sys.argv else 88
    os.makedirs(dst, exist_ok=True)
    names = sorted(n for n in os.listdir(tilt) if n.startswith("frame_") and n.endswith(".png"))
    total = 0
    for i, n in enumerate(names):
        out = os.path.join(dst, "t_%02d.webp" % i)
        shrink(os.path.join(tilt, n), size).save(out, "WEBP", quality=quality, method=6)
        total += os.path.getsize(out)
    tops = sorted(n for n in os.listdir(top) if n.startswith("frame_") and n.endswith(".png"))
    out = os.path.join(dst, "top.webp")
    shrink(os.path.join(top, tops[0]), size).save(out, "WEBP", quality=quality, method=6)
    total += os.path.getsize(out)
    print("%d tilted frames + top, %d KB" % (len(names), total // 1024))


if __name__ == "__main__":
    sys.exit(main())
