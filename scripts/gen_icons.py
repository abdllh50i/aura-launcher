"""AMRI OS 2.0: the icon family as VectorDrawables.

    python gen_icons.py [--icons DIR] [--out DIR]

Run it with no arguments: it reads design/icons/*.svg and manifest.json and writes
app/src/main/res/drawable/ic_<name>.xml, dashes in names becoming underscores. Every svg holds only <path> elements with one fill or one stroke and only commands VectorDrawable reads,
so no geometry is touched here. autoMirrored comes from the manifest (mirrorInRtl), so a call site never flips by hand.
ALIASES keeps the names 1.19 call sites use while they move over; delete a line when its last caller has gone.
"""
import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
# in the repo: scripts/gen_icons.py + design/icons/. In the design folder: tools/gen_icons.py + icons/.
CANDIDATES = [os.path.join(HERE, "..", "design", "icons"), os.path.join(HERE, "..", "icons")]
DEFAULT_OUT = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable")

# old name (1.19) -> new name. Every drawable the old set had that the code still asks for.
ALIASES = {
    "settings": "gear", "camera": "cam", "grid": "apps", "volume": "vol", "volume_low": "vol_low", "volume_off": "vol_mute",
    "skip_back": "prev", "skip_fwd": "next", "chevron_right": "chev_right", "chevron_left": "chev_left",
    "alert": "warn", "check_circle": "check_round", "smartphone": "iphone", "briefcase": "work",
    "moon": "moon", "contrast": "auto", "sliders": "eq", "monitor": "screen", "tool": "tools", "folder": "files",
    "bluetooth_audio": "bluetooth", "list": "queue", "music_list": "queue", "user": "person", "disc": "music",
    "call": "phone", "call_end": "hang", "speed_camera": "cam", "download": "update", "github": "link",
    "cpu": "tools", "shield": "lock", "zap": "power", "layers": "overview", "droplet": "sun_dim", "wrench_screw": "tools", "eye_off": "lock",
}

HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n    android:height="24dp"\n'
        '    android:viewportWidth="24"\n    android:viewportHeight="24"%s>\n')

PATH = re.compile(r"<path\b([^>]*)/>")
ATTR = re.compile(r'([a-z-]+)="([^"]*)"')
CMD = re.compile(r"^[MmLlHhVvCcSsQqTtAaZz0-9eE.,\s-]+$")


def convert(svg, name):
    body = []
    for m in PATH.finditer(svg):
        a = dict(ATTR.findall(m.group(1)))
        d = a.get("d", "")
        if not d or not CMD.match(d):
            raise ValueError("%s: path data VectorDrawable cannot read: %r" % (name, d[:60]))
        lines = ['    <path\n        android:pathData="%s"' % d]
        if a.get("fill", "none") != "none":
            lines.append('        android:fillColor="#FFFFFFFF"')
            if a.get("fill-rule") == "evenodd":
                lines.append('        android:fillType="evenOdd"')
        if a.get("stroke"):
            lines += ['        android:strokeColor="#FFFFFFFF"',
                      '        android:strokeWidth="%s"' % a.get("stroke-width", "2"),
                      '        android:strokeLineCap="round"',
                      '        android:strokeLineJoin="round"']
        body.append("\n".join(lines) + "/>\n")
    if not body:
        raise ValueError("%s: no paths" % name)
    return body


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--icons")
    ap.add_argument("--out", default=DEFAULT_OUT)
    a = ap.parse_args()
    src = a.icons or next((p for p in CANDIDATES if os.path.exists(os.path.join(p, "manifest.json"))), None)
    if not src:
        sys.exit("no icons folder with manifest.json")
    man = json.load(open(os.path.join(src, "manifest.json"), encoding="utf-8"))
    os.makedirs(a.out, exist_ok=True)
    made = {}
    for name, meta in man.items():
        svg = open(os.path.join(src, name + ".svg"), encoding="utf-8").read()
        res = name.replace("-", "_")
        mirror = '\n    android:autoMirrored="true"' if meta.get("mirrorInRtl") else ""
        xml = HEAD % mirror + "".join(convert(svg, name)) + "</vector>\n"
        made[res] = xml
        with open(os.path.join(a.out, "ic_%s.xml" % res), "w", encoding="utf-8", newline="\n") as f:
            f.write(xml)
    aliases = 0
    for old, new in ALIASES.items():
        if old in made:
            continue
        if new not in made:
            sys.exit("alias %s -> %s: no such icon" % (old, new))
        # (an old name's caller still flips by hand in RTL; only the new names mirror by themselves)
        with open(os.path.join(a.out, "ic_%s.xml" % old), "w", encoding="utf-8", newline="\n") as f:
            f.write(made[new].replace('\n    android:autoMirrored="true"', ""))
        aliases += 1
    print("wrote %d icons, %d aliases" % (len(made), aliases))


if __name__ == "__main__":
    main()
