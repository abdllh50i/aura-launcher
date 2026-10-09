"""Contact sheet of baked car frames on the dark and the light UI background (to judge lighting and shadow).
    python contact_sheet.py FRAMEDIR OUT.png [--scale 0.5] [--cols 4]
"""
import os
import sys

from PIL import Image


def main():
    src, out = sys.argv[1], sys.argv[2]
    scale = float(sys.argv[sys.argv.index("--scale") + 1]) if "--scale" in sys.argv else 0.5
    cols = int(sys.argv[sys.argv.index("--cols") + 1]) if "--cols" in sys.argv else 4
    names = sorted(n for n in os.listdir(src) if n.endswith(".png"))
    tiles = []
    for n in names:
        im = Image.open(os.path.join(src, n)).convert("RGBA")
        im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
        row = []
        for bg in ((11, 12, 14), (236, 237, 240)):
            b = Image.new("RGBA", im.size, bg + (255,))
            b.alpha_composite(im)
            row.append(b)
        pair = Image.new("RGBA", (im.width, im.height * 2))
        pair.paste(row[0], (0, 0))
        pair.paste(row[1], (0, im.height))
        tiles.append(pair)
    if not tiles:
        raise SystemExit("no frames")
    w, h = tiles[0].size
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new("RGBA", (w * cols, h * rows), (60, 60, 60, 255))
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % cols) * w, (i // cols) * h))
    sheet.convert("RGB").save(out, quality=90)
    print(out, sheet.size, len(tiles), "frames")


if __name__ == "__main__":
    main()
