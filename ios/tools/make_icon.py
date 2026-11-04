"""Draws عمري's app icon: a navigation arrow in the signal colour, on ink. The look may change with AMRI 2.0's design; this
only needs PIL, and writes the one 1024 px file the asset catalog asks for.

    python tools/make_icon.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 1024
SS = 4
INK = (14, 14, 12)
RING = (38, 37, 35)
SIGNAL = (255, 77, 20)
PAPER = (246, 242, 233)

OUT = Path(__file__).resolve().parent.parent / "App" / "Assets.xcassets" / "AppIcon.appiconset" / "icon-1024.png"

big = SIZE * SS
image = Image.new("RGB", (big, big), INK)
draw = ImageDraw.Draw(image)


def scale(points):
    return [(x * SS, y * SS) for x, y in points]


# The road: two soft rings, the way a map's range rings look.
for radius, width in ((380, 18), (270, 18)):
    box = [(512 - radius) * SS, (540 - radius) * SS, (512 + radius) * SS, (540 + radius) * SS]
    draw.ellipse(box, outline=RING, width=width * SS)

# The arrow, pointing up and to the right of the screen: a dart with a notch.
arrow = [(512, 170), (770, 800), (512, 668), (254, 800)]
draw.polygon(scale(arrow), fill=SIGNAL)
# Its other side, a shade down, gives it an edge.
draw.polygon(scale([(512, 170), (770, 800), (512, 668)]), fill=(224, 60, 0))

image = image.resize((SIZE, SIZE), Image.LANCZOS)
OUT.parent.mkdir(parents=True, exist_ok=True)
image.save(OUT, "PNG", optimize=True)
print("wrote", OUT)
