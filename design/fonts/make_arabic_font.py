"""Makes the app's Arabic UI font from Readex Pro (SIL OFL 1.1) as published by Google Fonts.

  python -I make_arabic_font.py "ReadexPro[HEXP,wght].ttf"     (needs fontTools)

Source: https://github.com/google/fonts/tree/main/ofl/readexpro  (git blob 563b89d182a98521152f7d5fc904bd646b058880,
version 1.205). Writes app/src/main/assets/fonts/ReadexPro-VF.ttf.

The only change is the vertical metrics. Upstream declares ascent 1000 / descent 250 (hhea and OS/2 typo), Latin-sized,
while its Arabic goes from about -550 (the dots of ي, the tails of ج ح ع) to 1150 (stacked marks) over the weights.
Android sizes a line box from those values and the app's labels do not add font padding, so everything below -250 was
cut off (ي lost its dots: «فى»). The box now covers the Arabic: ascent 1150, descent 560. Nothing else is touched
(outlines, variations, features); the family is renamed "Readex Pro AMRI" as the OFL asks of modified versions.
"""
import hashlib, os, sys
from fontTools.ttLib import TTFont

UPSTREAM_SHA = "563b89d182a98521152f7d5fc904bd646b058880"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "fonts", "ReadexPro-VF.ttf")
ASCENT, DESCENT = 1150, -560


def git_sha(data):
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def main(src):
    data = open(src, "rb").read()
    if git_sha(data) != UPSTREAM_SHA:
        sys.exit("not the expected upstream file (git sha %s)" % git_sha(data))
    f = TTFont(src)
    hhea, os2 = f["hhea"], f["OS/2"]
    hhea.ascent, hhea.descent, hhea.lineGap = ASCENT, DESCENT, 0
    os2.sTypoAscender, os2.sTypoDescender, os2.sTypoLineGap = ASCENT, DESCENT, 0
    os2.usWinAscent = max(os2.usWinAscent, ASCENT + 10)
    os2.usWinDescent = max(os2.usWinDescent, -DESCENT)
    for rec in f["name"].names:
        s = rec.toUnicode()
        if rec.nameID in (1, 4, 16) and s.startswith("Readex Pro"):
            rec.string = s.replace("Readex Pro", "Readex Pro AMRI", 1)
        elif rec.nameID in (3, 6, 25) and "ReadexPro" in s:
            rec.string = s.replace("ReadexPro", "ReadexProAMRI", 1)
        elif rec.nameID == 5:
            rec.string = s + "; AMRI OS: vertical metrics for Arabic UI"
    f.save(OUT)
    print("wrote", os.path.normpath(OUT), os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main(sys.argv[1])
