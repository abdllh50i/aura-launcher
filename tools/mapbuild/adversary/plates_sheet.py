#!/usr/bin/env python
"""Contact sheets of the plates, 3 by 2, and a table of what MapLibre itself read at each camera.

  python -I plates_sheet.py <plates folder>
"""
import glob
import json
import os
import sys

from PIL import Image, ImageDraw

sys.stdout.reconfigure(encoding="utf8")
root = sys.argv[1]
variants = ["day-ar", "night-ar", "day-en", "night-en"]
for v in variants:
    files = sorted(glob.glob(os.path.join(root, v, "*.png")))
    for s in range(0, len(files), 6):
        sheet = Image.new("RGB", (3 * 512 + 8, 2 * 300 + 4), "#ff00ff")
        for i, f in enumerate(files[s:s + 6]):
            im = Image.open(f).convert("RGB").resize((512, 300), Image.LANCZOS)
            d = ImageDraw.Draw(im)
            d.rectangle((0, 0, 190, 13), fill="#000000")
            d.text((2, 1), os.path.basename(f)[:-4], fill="#ffff00")
            sheet.paste(im, ((i % 3) * 516, (i // 3) * 304))
        sheet.save(os.path.join(root, f"sheet-{v}-{s // 6 + 1}.png"))

all_ = json.load(open(os.path.join(root, "all.json"), encoding="utf8"))
layers_seen = {}
for k, info in all_.items():
    for l, n in info.get("byLayer", {}).items():
        layers_seen[l] = layers_seen.get(l, 0) + n
style = json.load(open(os.path.join(os.path.dirname(root.rstrip("\\/")), "style", "day-ar.json"), encoding="utf8"))
never = [l["id"] for l in style["layers"] if l["id"] not in layers_seen and l.get("source") == "omt"]
print("omt layers in the style:", sum(1 for l in style["layers"] if l.get("source") == "omt"), "never drew a feature at any camera:", never)
errs = {k: (i.get("errors"), i.get("missingImages"), [p for p in i.get("problems", []) if "favicon" not in p]) for k, i in all_.items() if i.get("errors") or i.get("missingImages") or [p for p in i.get("problems", []) if "favicon" not in p]}
print("plates with errors:", len(errs))
for k, e in list(errs.items())[:20]:
    print("  ", k, e)
print(f"{'camera':28} day-ar: labels ar/lat   day-en: labels lat/ar   rendered features  ms")
names = sorted({k.split("/")[1] for k in all_})
for n in names:
    a, e = all_.get("day-ar/" + n, {}), all_.get("day-en/" + n, {})
    sa, se = a.get("symbols", {}), e.get("symbols", {})
    print(f"{n:28} {sa.get('total', 0):4} {sa.get('arabic', 0):4}/{sa.get('latin', 0):<4}       {se.get('total', 0):4} {se.get('latin', 0):4}/{se.get('arabic', 0):<4}      {sum(a.get('byLayer', {}).values()):6}  {a.get('ms')}")
# Characters MapLibre was asked for at these cameras in English that a Latin map should not need
bad = {}
for k, i in all_.items():
    if not k.split("/")[0].endswith("en"):
        continue
    for lay, ls in i.get("labels", {}).items():
        for t in ls:
            if any(0x1E00 <= ord(c) <= 0x1EFF or 0x2B0 <= ord(c) <= 0x36F for c in t):
                bad.setdefault(k.split("/")[1], []).append(t)
print("English labels with transliteration marks, by camera (samples):")
for k, v in sorted(bad.items()):
    print("  ", k, len(v), sorted(set(v))[:4])
