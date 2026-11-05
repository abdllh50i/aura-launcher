"""The gallery's replica pages against the mockups, rect by rect: scripts/gallery-shots.ps1 shoots them, this reads
final2/screens/_layout.json for where each thing is in the mockup and compares the two pictures there.

    python -I scripts/gallery_compare.py <final2 folder> [shots folder] [--out <folder for side-by-side crops>] [--theme night|day]

For each rect: mean absolute difference per channel (0..255), the share of pixels off by more than 24, and the offset within +-3 px that fits
best, with its difference: a text one pixel low shows as a big difference and a small one at dy=-1. Only rects that exist in both pictures'
meaning are listed in PAGES: the map under them and the words the mockup changes per screen are left out.
"""
import json
import os
import sys

import cv2
import numpy as np

# gallery page -> (mockup screen, rect names to compare)
SETTINGS = ["settingsTitle", "pageTitle", "navItem", "settingsGroup", "settingsRow", "slider", "segmented", "switch"]
DOCK = ["dockBar", "volPill", "volMinus", "volNum", "volPlus", "voice", "selectedPlate", "dock-settings", "dock-apps", "dock-phone", "dock-music", "dock-map", "dock-home", "statusIcons", "controlsHint", "gearBadge"]
CHROME_STRIP = ["statusIcons", "controlsHint", "gearBadge", "stripTime"]
CHROME_DOCK = ["dockBar", "volPill", "volMinus", "volNum", "volPlus", "voice", "selectedPlate", "dock-settings", "dock-apps", "dock-phone", "dock-music", "dock-map", "dock-home", "glance", "glancePrayer", "glanceMini"]
CHROME_HOME = ["statusIcons", "controlsHint", "gearRow", "logo", "capLeft", "capDock", "capChip", "volMinus", "volNum", "volPlus", "voice", "tile-settings", "tile-apps", "tile-phone", "tile-music", "tile-map", "chipIcon", "chipPrayer", "chipLeft"]
PAGES = {
    # the real chrome (scripts/chrome-shots.ps1), not a replica, on the mockups' plates, in the spec's order
    "c-home": ("home", CHROME_HOME),
    "c-moving": ("home-moving", CHROME_HOME),
    "c-trip": ("home-trip", CHROME_HOME + ["stripSpeed", "stripLimit", "stripSpeedNum"]),
    "c-voice-home": ("home-voice", CHROME_HOME),
    "c-volume-home": ("home-volume", CHROME_HOME + ["volLevel"]),
    "c-drive": ("drive-turn", CHROME_STRIP + CHROME_DOCK),
    "c-volume": ("volume", CHROME_STRIP + CHROME_DOCK + ["volLevel"]),
    "c-muted": ("volume-muted", CHROME_STRIP + CHROME_DOCK),
    "c-voice": ("voice", CHROME_STRIP + CHROME_DOCK),
    "c-apps": ("apps", CHROME_STRIP + CHROME_DOCK),
    "c-settings": ("settings-moving", CHROME_STRIP + CHROME_DOCK + ["stripSpeed", "stripLimit", "stripSpeedNum"]),
    "c-music": ("music", CHROME_STRIP + CHROME_DOCK),
    "c-en-home": ("en-home", CHROME_HOME),
    "c-en-drive": ("en-drive", CHROME_STRIP + CHROME_DOCK),
    "r-settings-display": ("settings-display", SETTINGS + DOCK + ["stripTime"]),
    "r-settings-maps": ("settings-maps", SETTINGS + DOCK + ["stripTime"]),
    "r-controls": ("controls", ["controlsSheet", "controlsTitle", "controlsClose", "slider", "segmented", "controlTile", "glancePrayer"] + DOCK),
}


def diff(a, b):
    d = np.abs(a.astype(np.int16) - b.astype(np.int16))
    return float(d.mean()), float((d.max(axis=2) > 24).mean() * 100)


def best_offset(mock, shot, x, y, w, h):
    best = None
    for dy in range(-3, 4):
        for dx in range(-3, 4):
            x0, y0 = max(0, x + dx), max(0, y + dy)
            x1, y1 = min(shot.shape[1], x + dx + w), min(shot.shape[0], y + dy + h)
            mx0, my0 = x0 - dx, y0 - dy
            m = mock[my0:my0 + (y1 - y0), mx0:mx0 + (x1 - x0)]
            s = shot[y0:y1, x0:x1]
            if m.size == 0 or m.shape != s.shape:
                continue
            v = float(np.abs(m.astype(np.int16) - s.astype(np.int16)).mean())
            if best is None or v < best[0]:
                best = (v, dx, dy)
    return best


def digits(shots):
    """Section 14's question 6, on the page `digits`: the ink height of 600 at 56 px, alone, with its unit in spans, and in one run with Arabic. The design's: 0.70 em, 39 px."""
    im = cv2.imread(os.path.join(shots, "v2-ds-digits-night.png"))
    g = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY)
    for name, y in (("alone", 40), ("unit in spans", 140), ("one run with Arabic", 240)):
        box = g[y - 8:y + 78, 600:1000]
        cols = np.where((box > 120).any(axis=0))[0]
        # the digits are at the reading edge: the rightmost 3 x 0.6 em
        right = cols[-1] + 1
        digit = box[:, max(0, right - 104):right]
        rows = np.where((digit > 120).any(axis=1))[0]
        print("  %-22s digits ink height %d px (0.70 em = 39)" % (name, rows[-1] - rows[0] + 1))


def main():
    args = [a for a in sys.argv[1:]]
    if "--digits" in args:
        digits(args[1] if len(args) > 1 and not args[1].startswith("--") else r"D:\k2501-work\shots")
        return
    out = None
    themes = ["night", "day"]
    if "--out" in args:
        i = args.index("--out"); out = args[i + 1]; del args[i:i + 2]
    if "--theme" in args:
        i = args.index("--theme"); themes = [args[i + 1]]; del args[i:i + 2]
    root = args[0]
    shots = args[1] if len(args) > 1 else r"D:\k2501-work\shots"
    layout = json.load(open(os.path.join(root, "screens", "_layout.json"), encoding="utf-8"))
    if out:
        os.makedirs(out, exist_ok=True)
    total = []
    for page, (screen, names) in PAGES.items():
        for th in themes:
            mp = os.path.join(root, "screens", "%s-%s.png" % (screen, th))
            sp = os.path.join(shots, "v2-ds-%s-%s.png" % (page, th))
            if not (os.path.exists(mp) and os.path.exists(sp)):
                print("--", page, th, "no picture")
                continue
            mock = cv2.imread(mp)[:, :, :3]
            shot = cv2.imread(sp)[:, :, :3]
            if mock.shape != shot.shape:
                print("--", page, th, "sizes differ", mock.shape, shot.shape)
                continue
            print("== %s %s (%s)" % (page, th, screen))
            rows = layout["%s-%s" % (screen, th)]
            for name in names:
                k = 0
                for it in rows:
                    if it["k"] != name:
                        continue
                    k += 1
                    x, y, w, h = int(it["x"]), int(it["y"]), int(it["w"]), int(it["h"])
                    if page.startswith("c-") and (name.startswith("cap") or name == "dockBar"):
                        h, y = h - (516 - y), 516  # (above the row the mockup has the panel's cards, and these pages have none)
                    x, y = max(0, x), max(0, y)
                    if w <= 0 or h <= 0:
                        continue
                    if x + w > mock.shape[1]: w = mock.shape[1] - x
                    if y + h > mock.shape[0]: h = mock.shape[0] - y
                    m, s = mock[y:y + h, x:x + w], shot[y:y + h, x:x + w]
                    d, over = diff(m, s)
                    v, dx, dy = best_offset(mock, shot, x, y, w, h)
                    total.append(d)
                    flag = "" if d < 3 else " *" if d < 8 else " **"
                    print("  %-16s #%d %4d,%-4d %4dx%-4d diff %5.1f  off %4.1f%%  best %5.1f at (%+d,%+d)%s" % (name, k, x, y, w, h, d, over, v, dx, dy, flag))
                    if out and d >= 3:
                        pad = 6
                        a = mock[max(0, y - pad):y + h + pad, max(0, x - pad):x + w + pad]
                        b = shot[max(0, y - pad):y + h + pad, max(0, x - pad):x + w + pad]
                        side = np.hstack([a, np.full((a.shape[0], 4, 3), 128, np.uint8), b]) if w < 500 else np.vstack([a, np.full((4, a.shape[1], 3), 128, np.uint8), b])
                        cv2.imwrite(os.path.join(out, "%s-%s-%s-%d.png" % (page, th, name, k)), side)
    if total:
        print("rects %d, mean diff %.2f, rects over 3: %d, over 8: %d" % (len(total), sum(total) / len(total), sum(1 for t in total if t >= 3), sum(1 for t in total if t >= 8)))


if __name__ == "__main__":
    main()
