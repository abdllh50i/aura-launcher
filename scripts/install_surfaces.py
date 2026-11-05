"""Puts the design's baked surfaces where the app reads them (SPEC.md section 4, "Where the files go").

    python -I scripts/install_surfaces.py <final2 folder>

Nothing is drawn here: gen_surfaces.py (in the design folder) bakes them. res/drawable-nodpi: every 9-patch as generated, and the
small plain bitmaps with "-" as "_"; aapt2 keeps the guides. assets/surfaces: what a software Canvas reads (the wash's masks and
frames) and what must not go through aapt2's recompression (the room, the dock, the capsules, the cover's shadow, the car's glow).
The finished samples (wash-widget-*, wash-mini-*) are for the mockups and never ship: Backdrop makes the real ones, per song.
The car's walls, frames and stage (assets/car of the design) belong to the car, section 10, and are not touched here.
"""
import fnmatch
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable-nodpi")
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets", "surfaces")

# plain bitmaps that go to res (those with a 9-patch come from drawable-nodpi, the same pixels with the guides)
TO_RES = ["knob-33", "thumb-32", "tile-music", "tile-phone", "tile-apps", "tile-settings", "tile-map", "apptile-*", "voice-disc",
          "fade-*", "scrim-*", "turn-glow-*"]
TO_ASSETS = ["wash-mask-*", "wash-frame-*", "cap-*", "room-*", "dock-*", "cover-shadow-*", "car-glow-*"]


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    root = sys.argv[1]
    surf = os.path.join(root, "assets", "surfaces")
    nine = os.path.join(root, "assets", "android", "drawable-nodpi")
    os.makedirs(RES, exist_ok=True)
    os.makedirs(ASSETS, exist_ok=True)
    count = [0, 0, 0]
    for n in sorted(os.listdir(nine)):
        if n.endswith(".9.png"):
            shutil.copyfile(os.path.join(nine, n), os.path.join(RES, n))
            count[0] += 1
    for n in sorted(os.listdir(surf)):
        if not n.endswith(".png"):
            continue
        stem = n[:-4]
        if any(fnmatch.fnmatch(stem, p) for p in TO_RES):
            shutil.copyfile(os.path.join(surf, n), os.path.join(RES, stem.replace("-", "_") + ".png"))
            count[1] += 1
        elif any(fnmatch.fnmatch(stem, p) for p in TO_ASSETS):
            shutil.copyfile(os.path.join(surf, n), os.path.join(ASSETS, n))
            count[2] += 1
    print("%d 9-patches and %d bitmaps in res/drawable-nodpi, %d in assets/surfaces" % tuple(count))


if __name__ == "__main__":
    main()
