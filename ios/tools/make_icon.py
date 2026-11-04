"""Draws عمري's app icon: a white navigation arrow on the accent blue of AMRI 2.0's design (final2/tokens.json, accentTile). Needs
only PIL, and writes the one 1024 px file the asset catalog asks for.

    python tools/make_icon.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 1024
SS = 4
TOP = (79, 128, 255)
BOTTOM = (37, 82, 221)

OUT = Path(__file__).resolve().parent.parent / "App" / "Assets.xcassets" / "AppIcon.appiconset" / "icon-1024.png"

big = SIZE * SS
image = Image.new("RGB", (big, big), BOTTOM)
draw = ImageDraw.Draw(image)
for y in range(big):
    t = y / (big - 1)
    draw.line([(0, y), (big, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)))


def scale(points):
    return [(x * SS, y * SS) for x, y in points]


# The arrow: a dart with a notch, as a car's navigation draws the car.
draw.polygon(scale([(512, 170), (770, 800), (512, 668), (254, 800)]), fill=(255, 255, 255))
# Its other side, a shade down, gives it an edge.
draw.polygon(scale([(512, 170), (770, 800), (512, 668)]), fill=(216, 226, 255))

image = image.resize((SIZE, SIZE), Image.LANCZOS)
OUT.parent.mkdir(parents=True, exist_ok=True)
image.save(OUT, "PNG", optimize=True)
print("wrote", OUT)
