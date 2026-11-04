#!/usr/bin/env python
"""Builds places.db: every place, shop and named road of Saudi Arabia in one SQLite file, FTS4 only, readable by SQLite 3.22.

  python build_places.py --work D:\\k2501-work\\mapbuild\\places --out D:\\k2501-work\\mapbuild\\out\\places.db
      --overture-places <parquet of Overture places rows> --overture-dir D:\\k2501-work\\mapbuild\\src\\overture
      [--additions additions.json] [--prefix=]

build_all_places.ps1 runs this and every check after it. Needs osm_extract.py's files in --work and fetch_overture.py's in --overture-dir. numpy and pyarrow, nothing else.
The merge follows section 8.3 of the plan: Overture + OSM POIs + named roads + divisions + place nodes; names split by script;
duplicates merged; area by point in polygon; rank; category tokens; Arabic for Latin names and the reverse in the index only.
"""
import argparse
import collections
import json
import math
import os
import re
import sqlite3
import sys
import time
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


class _NoPandas:
    """pyarrow only peeks at pandas, which is never used here: a pandas built for another numpy must not break it."""

    def find_spec(self, name, path=None, target=None):
        if name == "pandas" or name.startswith("pandas."):
            raise ImportError("pandas is not used here")


sys.meta_path.insert(0, _NoPandas())
import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

import places_tables as T
from geo_index import Points, parse_wkb, rings_area_km2, rings_bbox
from search_ref import ABD_LATIN, CT, NORM_VERSION, PL, ROLE_ALIAS, ROLE_FILLER, ROLE_LETTER, SK, SRC_COARSE, base, is_ar, latin_base, norm, skeleton, skeletons
from translit import latin_forms

KIND_REGION, KIND_CITY, KIND_TOWN, KIND_VILLAGE, KIND_DISTRICT, KIND_POI, KIND_ROAD = 1, 2, 3, 4, 5, 6, 7
SRC_OVERTURE, SRC_OSM = 1, 2
KSA_BOX = (34.4, 16.3, 55.8, 32.3)
ROAD_RANK = {"motorway": 62, "trunk": 58, "primary": 52, "secondary": 44, "tertiary": 36, "unclassified": 22, "residential": 20, "living_street": 18,
             "motorway_link": 30, "trunk_link": 28, "primary_link": 26, "secondary_link": 24, "tertiary_link": 22, "service": 12, "pedestrian": 16, "road": 16}
CAT = {c[0]: i + 1 for i, c in enumerate(T.CATS)}
CAT_ICON = {c[0]: c[3] for c in T.CATS}
CAT_WEIGHT = {c[0]: c[4] for c in T.CATS}
STOP = {k for k, v in T.ROLES.items() if v[0] == 3}
OPTIONAL = {k for k, v in T.ROLES.items() if v[0] in (1, 2, 4)}
AR_RANGE = re.compile(r"[\u0600-\u06FF]")
LAT_RANGE = re.compile(r"[A-Za-z]")
FOREIGN = re.compile(r"[\u0370-\u03FF\u0400-\u052F\u0590-\u05FF\u0900-\u0DFF\u0E00-\u0FFF\u1000-\u109F\u1100-\u11FF\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7AF]")
URLISH = re.compile(r"(?:https?:|www\.|\.com\b|\.net\b|\.org\b|\.sa\b|\.in\b|\.co\b)", re.I)
HARAKAT = re.compile(r"[\u064B-\u065F\u0670\u0640\u06D6-\u06ED\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]")
COUNTRYISH = re.compile(r"\b(?:kingdom of saudi arabia|saudi arabia|ksa)\s*$", re.I)
COUNTRY_TAIL = re.compile(r"[\s,،-]+(?:kingdom of saudi arabia|saudi arabia|ksa|السعودية|المملكة العربية السعودية)\s*$", re.I)
COUNTRY_ALONE = re.compile(r"\s*(?:kingdom of saudi arabia|saudi arabia|ksa|السعودية|المملكة العربية السعودية)\s*", re.I)


PHONE_IN_NAME = re.compile(r"\+?\d[\d\s-]{6,}\d")
# a bare number or code as a name: 12251, E203, 396-00-308, F211
CODE_NAME = re.compile(r"^[\W_]*[A-Za-z\u0621-\u064A]{0,2}[\W_]*\d[\d\W_]*[A-Za-z\u0621-\u064A]{0,2}[\W_]*$")
# an advertisement as a name
ADVERT_NAME = re.compile(r"خصم|تخفيض|ارخص|أرخص|افضل شرك|أفضل شرك|واتس|whats ?app|نقل عفش ب|نقل اثاث ب|نقل أثاث ب|شراء اثاث|شراء أثاث|شراء .*مستعمل|تنظيف .* ب[ال]|رش مبيدات|مكافحة حشرات ب|كشف تسرب.* ب", re.I)
# a part of a place, not a place: Gate 13, an ICU ward, a floor
SUB_UNIT = re.compile(r"^(?:gate|door|entrance|exit|floor|ward|bed|unit|room|hall|counter|terminal gate)\s*[-#:]?\s*[\dA-Za-z]{0,3}\b|\b(?:icu|nicu|picu)\b|\bward\b|\bfood court\b|^(?:بوابة|بوابه|باب|مدخل|الدور|دور|عنبر|جناح|قسم|غرفة)\s+[\d\u0660-\u0669]{1,3}\s*$", re.I)
# a name that says it is somewhere else, on an Overture row with no other witness
ELSEWHERE = re.compile(r"\b(?:dubai|doha|qatar|kuwait|bahrain|manama|abu dhabi|sharjah|muscat|oman|egypt|cairo|jordan|amman|lebanon|beirut|kerala|india|pakistan|karachi|lahore|philippines|manila|indonesia|jakarta|nigeria|kenya|poland|romania|turkey|istanbul)\s*$|(?:المنامة|المنامه|دبي|الدوحة|الدوحه|الكويت|ابوظبي|أبوظبي|ابو ظبي|الشارقة|الشارقه|القاهرة|القاهره|بيروت)\s*$", re.I)
MAX_INDEX_WORDS = 10  # a longer "name" is an advertisement: its first words are the name, the rest would answer every search
WEAK_CATS = {"other", "shop", "office", "building", "real_estate", "community"}
# words of table syn that often begin a name without saying what the place is ("محطة توزيع المياه", "Riyadh Gas")
NOT_A_KIND = {"محطه", "محطات", "مغسله", "مغاسل", "غسيل", "استراحه", "استراحات", "عدد", "ماركت", "بحر", "gas", "cash", "post", "club", "food", "market", "home", "gold", "medical", "lab", "park"}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def phone_out_of_name(name):
    """(name without the phone numbers an advertisement put in it, the first number in ASCII digits)."""
    found = PHONE_IN_NAME.findall(name)
    if not found:
        return name, ""
    rest = " ".join(PHONE_IN_NAME.sub(" ", name).split()).strip(" -_|.,:/+،")
    if len(rest) < 3:
        return name, ""
    return rest, "".join(str(unicodedata.digit(c)) for c in found[0] if c.isdigit())


def syn_words():
    """Table syn's words and phrases to category keys, the way a name's words are written after norm and base."""
    out = {}
    for words, phrases, cats in T.SYNONYMS:
        for w in list(words.split()) + phrases:
            k = " ".join(base(x) for x in norm(w).split())
            if k and k not in NOT_A_KIND:
                out.setdefault(k, list(cats))
    return out


def kind_from_name(r, words_to_cats, stats):
    """Sources file many shops under a word that says nothing (office, building, other). When such a row's name says what it is, in the
    place a name says it (first in Arabic: "مكتبة جهينة"; last in Latin: "Techno Paints"), the name's word decides."""
    if r.kind != KIND_POI or r.cat not in WEAK_CATS:
        return
    for nm in (r.name, r.alt):
        ws = [base(w) for w in norm(nm).split()] if nm else []
        if not ws:
            continue
        if is_ar(ws[0]):
            hit = words_to_cats.get(" ".join(ws[:2])) or words_to_cats.get(ws[0])
        else:
            hit = words_to_cats.get(" ".join(ws[-2:])) or words_to_cats.get(ws[-1])
        if hit:
            stats["rows whose category came from their name"] += 1
            r.cat = hit[0]
            return


def usual_kinds(rows, stats, w2c):
    """What a name usually is. Overture's categories are noisy and one batch is plainly wrong, so:
      * a row of that batch (Row.suspect) takes the kind the same name has on other rows, or "other" when no other row knows it;
      * a row whose name is seen often, nearly always as one family of kinds, and is itself filed under another family, joins them;
      * a row with a weak kind whose name begins with a chain's name takes the chain's kind (Kudu - Al-Shiraa - Al Khobar);
      * so does any row whose first word is a big chain's whole name, unless another word of its name says what it is (Starbucks Middle East, not Al Rajhi Mosque)."""

    def key(r):
        return " ".join(latin_base(w) if not is_ar(w) else w for w in canon_tokens(r.name)[0])

    names = collections.defaultdict(collections.Counter)
    first = collections.defaultdict(collections.Counter)
    for r in rows:
        if r.suspect or r.generic or r.cat in WEAK_CATS:
            continue
        k = key(r)
        names[k][r.cat] += 1
        if " " not in k:
            first[k][r.cat] += 1
        if r.alt and not r.suspect:
            names[" ".join(latin_base(w) if not is_ar(w) else w for w in canon_tokens(r.alt)[0])][r.cat] += 1

    def family(c):
        return CAT_ICON.get(c)

    def dominant(counter, least, share):
        total = sum(counter.values())
        if total < least:
            return None
        fam = collections.Counter()
        for c, n in counter.items():
            fam[family(c)] += n
        f, n = fam.most_common(1)[0]
        if n / total < share:
            return None
        return max((c for c in counter if family(c) == f), key=lambda c: counter[c])

    for r in rows:
        k = key(r)
        if r.suspect:
            got = dominant(names.get(k, {}), 1, 0.5) if k in names else None
            r.cat = got or "other"
            stats["pharmacy batch: took the name's usual kind" if got else "pharmacy batch: no other row knows the name"] += 1
            continue
        if r.generic:
            continue
        usual = dominant(names.get(k, {}), 6, 0.6) if k in names else None
        if usual and family(usual) != family(r.cat) and not compatible(r.cat, usual):
            stats["rows moved to their name's usual kind"] += 1
            r.cat = usual
            continue
        if " " in k:
            words = k.split()
            ch = words[0]
            if ch not in first or len(ch) < 3:
                continue
            if r.cat in WEAK_CATS:
                usual = dominant(first[ch], 12, 0.8)
                if usual:
                    stats["weak rows took a chain's kind from their first word"] += 1
                    r.cat = usual
            else:
                usual = dominant(first[ch], 20, 0.9)
                if usual and family(usual) != family(r.cat) and not compatible(r.cat, usual) and not any(w in w2c for w in words[1:]):
                    stats["rows took a big chain's kind from their first word"] += 1
                    r.cat = usual


def check_chains(rows, stats):
    """Stops the build when a known chain is filed under another kind (a batch of Overture rows once filed كنتاكي under pharmacy)."""
    want = {" ".join(latin_base(w) if not is_ar(w) else w for w in canon_tokens(k)[0]): v for k, v in T.CHAIN_KINDS.items()}
    bad = collections.Counter()
    total = collections.Counter()
    for r in rows:
        for nm in (r.name, r.alt):
            if not nm:
                continue
            k = " ".join(latin_base(w) if not is_ar(w) else w for w in canon_tokens(nm)[0])
            w = want.get(k)
            if w:
                total[k] += 1
                if not (r.cat == w or CAT_ICON.get(r.cat) == CAT_ICON.get(w) or {CAT_ICON.get(r.cat), CAT_ICON.get(w)} == {T.FOOD, T.CAFE} or r.cat in WEAK_CATS):
                    bad[(k, r.cat)] += 1
                break
    wrong = {k: sum(v for (a, _), v in bad.items() if a == k) for k, _ in bad}
    log("chains checked:", sum(total.values()), "rows; off their kind:", dict(bad.most_common(20)))
    stats["chain rows checked"] = sum(total.values())
    stats["chain rows off their kind"] = sum(bad.values())
    over = {k: n for k, n in wrong.items() if n > max(2, total[k] * 0.03)}
    if over:
        raise SystemExit(f"known chains filed under another kind: {over}")


def mark_piles(rows, stats):
    """Five or more POIs on one point: that point is a geocoder's default. The rows stay in search, marked as coarse, and stay off the map's tiles, unless OpenStreetMap vouches for the row."""
    at = collections.Counter((int(round(r.lat * 1e6)), int(round(r.lon * 1e6))) for r in rows if r.kind == KIND_POI)
    n = 0
    for r in rows:
        if r.kind == KIND_POI and at[(int(round(r.lat * 1e6)), int(round(r.lon * 1e6)))] >= 5 and not r.src & SRC_OSM:
            r.src |= SRC_COARSE
            n += 1
    stats["rows on a pile of 5 or more, marked coarse"] = n


# ------------------------------------------------------------------------------------------------------- names

def clean_display(s):
    """A name as shown: no tashkeel or stretching, no symbols or emoji, single spaces, no stray punctuation at the ends."""
    if not s:
        return ""
    s = HARAKAT.sub("", s)
    out = []
    for c in s:
        t = unicodedata.category(c)
        if t[0] in "LN" or c == " " or c in "-'&.,()/+#:'’_|،":
            out.append(c)
        elif t[0] == "M":
            out.append(c)
        else:
            out.append(" ")
    s = " ".join("".join(out).split())
    s = s.strip(" -_|.,:/+،")
    t = COUNTRY_TAIL.sub("", s)  # "King Abdul Aziz street, Jubail, saudi arabia"
    return t if len(t) >= 3 else s


SEPARATORS = re.compile(r"\s*(?:\||｜|/|\\|–|—|\s-\s|-\s|\s-|\(|\)|\[|\]|،|:|,)\s*")


def split_dual(name):
    """(Arabic, Latin) parts of a name, for names that carry both scripts: "Nahdi pharmacy | صيدليه النهدى", "Kadi Mall كادي مول".
    A name in one script comes back whole, whatever its punctuation."""
    ar = bool(AR_RANGE.search(name))
    la = bool(LAT_RANGE.search(name))
    if not (ar and la):
        return (name, "") if ar else ("", name)
    parts_ar = []
    parts_la = []
    mixed = False
    for seg in SEPARATORS.split(name):
        seg = seg.strip(" -_|.,:")
        if not seg:
            continue
        a = bool(AR_RANGE.search(seg))
        b = bool(LAT_RANGE.search(seg))
        if a and b:
            # two runs of words, one in each script?
            words = seg.split()
            kinds = []
            for w in words:
                ka = "a" if AR_RANGE.search(w) else "l" if LAT_RANGE.search(w) else ""
                if ka and (not kinds or kinds[-1][0] != ka):
                    kinds.append([ka, []])
                if kinds:
                    kinds[-1][1].append(w)
            if len(kinds) == 2:
                for ka, ws in kinds:
                    (parts_ar if ka == "a" else parts_la).append(" ".join(ws))
            else:
                mixed = True
        elif a:
            parts_ar.append(seg)
        elif b:
            parts_la.append(seg)
    if mixed or not parts_ar or not parts_la:
        return (name, "")  # interleaved scripts: one name, as written
    return " ".join(parts_ar), " ".join(parts_la)


def canon_tokens(text):
    """The words of a name for the index: (main words in order, extra forms). Stop words go; joined names are held both ways."""
    raw = norm(text).split()
    main = []
    extra = []
    i = 0
    n = len(raw)
    while i < n:
        w = raw[i]
        nxt = raw[i + 1] if i + 1 < n else None
        if len(w) == 1 and not w.isdigit() and not is_ar(w):
            j = i
            while j < n and len(raw[j]) == 1 and raw[j].isalpha() and not is_ar(raw[j]):
                j += 1
            if j - i >= 2:  # K.F.C, A & W: the letters together
                extra.append("".join(raw[i:j]))
            main.extend(raw[i:j])
            i = j
            continue
        if w in STOP:
            if w in ("al", "el") and nxt and not is_ar(nxt):
                extra.append(w + nxt)
            i += 1
            continue
        if is_ar(w):
            b = base(w)
            if b.startswith("عبد") and len(b) > 4:
                main.append("عبد")
                main.append(base(b[3:]))
                extra.append(b)
            elif b == "عبد" and nxt:
                main.append(b)
                extra.append(b + nxt)
            elif b.startswith("ابو") and len(b) >= 6:
                main.append("ابو")
                main.append(base(b[3:]))
                extra.append(b)
            elif b == "ابو" and nxt:
                main.append(b)
                extra.append(b + base(nxt))
            else:
                main.append(b)
        else:
            b = base(w)
            m = ABD_LATIN.match(b)
            if m:
                main.append("abd")
                main.append(latin_base(m.group(1)))
                extra.append(b)
            else:
                main.append(b)
                lb = latin_base(b)
                if lb != b:
                    extra.append(lb)
        i += 1
    return main, extra


def hyphen_joined(text):
    """Coca-Cola also as cocacola."""
    out = []
    for m in re.finditer(r"[^\W\d_]+(?:[-.&'’][^\W\d_]+)+", text.lower()):
        j = norm(re.sub(r"[-.&'’]", "", m.group(0)))
        if j and " " not in j:
            out.append(j)
    return out


# ----------------------------------------------------------------------------------------------------- the word pairs

class Lexicon:
    """What the data itself says about Arabic and Latin spellings, with the hand tables beside it."""

    def __init__(self):
        self.lat2ar = collections.defaultdict(list)  # Latin word -> Arabic words
        self.ar2lat = collections.defaultdict(list)
        self.word_pairs = collections.Counter()
        self.brand_pairs = collections.Counter()  # (Latin key, Arabic key) -> count
        self.brand_cat = collections.defaultdict(collections.Counter)  # (Latin key, Arabic key) -> category keys it was seen on
        self.brand_disp = {}  # Arabic key -> the Arabic as written in the data
        self.wd_lat = collections.defaultdict(collections.Counter)
        self.wd_ar = collections.defaultdict(collections.Counter)
        self.lat_brand = {}
        self.ar_brand = {}
        self.max_len = 1
        self.translated = set()
        for k, v in T.DICT.items():
            self.translated.add(k)
            for a in v.split():
                self.translated.add(base(a))

    @staticmethod
    def key(words):
        return " ".join(words)

    def add_pair_words(self, ar_text, lat_text):
        """Aligns the words of two names of one thing, after taking out the words the hand table already translates."""
        a, _ = canon_tokens(ar_text)
        e, _ = canon_tokens(lat_text)
        a = [w for w in a if is_ar(w) and w not in OPTIONAL and w not in self.translated]
        e = [w for w in e if not is_ar(w) and w not in OPTIONAL and w not in self.translated and not w.isdigit() and len(w) >= 2]
        if len(a) != len(e) or not 1 <= len(a) <= 4:
            return
        for x, y in zip(a, e):
            self.word_pairs[(y, x)] += 1

    def add_brand(self, lat_text, ar_text, cat=None, weight=1):
        if not lat_text or not ar_text:
            return
        la, _ = canon_tokens(lat_text)
        ar, _ = canon_tokens(ar_text)
        la = [latin_base(w) for w in la if not is_ar(w)]
        ar = [w for w in ar if is_ar(w)]
        if not la or not ar or len(la) > 4 or len(ar) > 4:
            return
        lk = " ".join(la)
        ak = " ".join(ar)
        if len(lk) < 2 or len(ak) < 2:
            return
        self.brand_pairs[(lk, ak)] += weight
        if cat:
            self.brand_cat[(lk, ak)][cat] += 1
        self.brand_disp.setdefault(ak, ar_text.strip())

    def finish(self):
        """Keeps the word pairs whose letters agree, and the brand pairs seen often enough."""
        from_lat = collections.defaultdict(collections.Counter)
        from_ar = collections.defaultdict(collections.Counter)
        kept = 0
        for (y, x), c in self.word_pairs.items():
            sy = skeletons(y)
            sx = skeletons(x)
            if not sx or not sy:
                ok = c >= 3 and len(y) <= 4
            else:
                ok = any(_close(p, q) for p in sx for q in sy)
            if ok and (c >= 2 or (sx and sy and sx[0] == sy[0] and len(sx[0]) >= 3)):
                from_lat[y][x] += c
                from_ar[x][y] += c
                kept += 1
        for y, cc in from_lat.items():
            top = cc.most_common(2)
            self.lat2ar[y] = [w for w, c in top if c * 4 >= top[0][1]]
        for x, cc in from_ar.items():
            top = cc.most_common(2)
            self.ar2lat[x] = [w for w, c in top if c * 4 >= top[0][1]]
        for k, v in T.DICT.items():
            arabic = [base(a) for a in v.split()]
            self.lat2ar[k] = list(dict.fromkeys(arabic + self.lat2ar.get(k, [])))
            for a in arabic:
                if k not in self.ar2lat[a]:
                    self.ar2lat[a] = (self.ar2lat[a] + [k])[:3]
        # brands: what the data's tags say, plus the seeds
        nb = 0
        seed = set()
        for la, ar in T.BRANDS.items():
            self.add_brand(la, ar, None, 1)
            lw = [latin_base(w) for w in canon_tokens(la)[0]]
            aw = canon_tokens(ar)[0]
            seed.add((" ".join(lw), " ".join(aw)))
        self.seeds = seed
        for (lk, ak), c in self.brand_pairs.items():
            if c < 2 and (lk, ak) not in seed:
                continue
            nb += 1
            lw = lk.split()
            aw = ak.split()
            self.lat_brand.setdefault(lk, []).append(ak)
            self.ar_brand.setdefault(ak, []).append(lk)
            self.max_len = max(self.max_len, len(lw), len(aw))
        return kept, nb

    def display_arabic(self, lat_key, cat):
        """The Arabic name a Latin-only row may show: its whole name is a brand the data has often written in Arabic on the same kind of place. Never a guess."""
        best = None
        for ak in self.lat_brand.get(lat_key, []):
            cats = self.brand_cat.get((lat_key, ak))
            c = self.brand_pairs[(lat_key, ak)]
            if not cats or c < 3:
                continue
            if CAT_ICON.get(cat) not in {CAT_ICON.get(k) for k in cats}:
                continue
            if best is None or c > best[1]:
                best = (ak, c)
        return self.brand_disp.get(best[0]) if best else None


def _close(a, b):
    """Skeletons at most one edit apart (both long enough for that to mean something), or equal."""
    if a == b:
        return True
    if min(len(a), len(b)) < 3:
        return False
    return _lev1(a, b)


def _lev1(a, b):
    la, lb = len(a), len(b)
    if abs(la - lb) > 1:
        return False
    if la > lb:
        a, b, la, lb = b, a, lb, la
    i = 0
    while i < la and a[i] == b[i]:
        i += 1
    if la == lb:
        return a[i + 1:] == b[i + 1:]
    return a[i:] == b[i + 1:]


# ------------------------------------------------------------------------------------------------------- rows

class Row:
    __slots__ = ("kind", "name", "alt", "others", "cat", "lat", "lon", "phone", "conf", "nsrc", "brand", "src", "generic", "website", "wiki", "area_poly", "cls", "pop",
                 "rank", "area", "mkeys", "sks", "toks", "merged", "oid", "brand_ok", "ad", "suspect", "core", "sub", "away")

    def __init__(self, kind, name, alt, cat, lat, lon, src):
        self.kind = kind
        self.name = name
        self.alt = alt
        self.others = []
        self.cat = cat
        self.lat = lat
        self.lon = lon
        self.phone = ""
        self.conf = 0.0
        self.nsrc = 1
        self.brand = ""
        self.src = src
        self.generic = False
        self.website = False
        self.wiki = False
        self.area_poly = False
        self.cls = ""
        self.pop = 0
        self.rank = 0
        self.area = 0
        self.mkeys = None
        self.sks = None
        self.toks = None
        self.merged = 0
        self.oid = ""
        self.brand_ok = False
        self.ad = False
        self.suspect = False  # its category came from a batch known to be wrong
        self.core = None
        self.sub = False  # a part of a place
        self.away = False  # says it is elsewhere


def names_of_osm(t):
    """(Arabic or main name, Latin name, other names) of an OSM object."""
    name = clean_display(t.get("name", ""))
    ar = clean_display(t.get("name:ar", ""))
    en = clean_display(t.get("name:en", "") or t.get("int_name", ""))
    if name:
        a2, l2 = split_dual(name)
        ar = ar or a2
        en = en or l2
    others = []
    for k in ("alt_name", "alt_name:ar", "alt_name:en", "old_name", "old_name:ar", "old_name:en", "official_name", "official_name:ar", "official_name:en",
              "short_name", "short_name:ar", "short_name:en", "loc_name", "loc_name:ar", "brand", "brand:ar", "brand:en"):
        v = clean_display(t.get(k, ""))
        if v:
            for part in v.split(";"):
                part = part.strip()
                if part and part not in others:
                    others.append(part)
    if name and name not in (ar, en) and name not in others:
        others.append(name)
    return ar, en, others


def first_phone(t):
    for k in ("phone", "contact:phone", "phone:mobile", "contact:mobile", "mobile"):
        v = t.get(k)
        if v:
            return clean_phone(v.split(";")[0])
    return ""


def clean_phone(p):
    p = "".join(c for c in (p or "") if c.isdigit() or c == "+")
    return p if len(p) >= 7 else ""


def read_jsonl(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            yield json.loads(line)


# ------------------------------------------------------------------------------------------------- divisions

class Division:
    __slots__ = ("id", "sub", "cls", "ar", "en", "lat", "lon", "pop", "parent", "region", "rings", "bbox", "km2")


def clean_en(s):
    if not s:
        return ""
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.replace("`", "'").strip(" '")
    return s


def strip_governorate(ar, en):
    ar = re.sub(r"^محافظة\s+", "", ar or "")
    en = re.sub(r"\s+Governorate$", "", en or "")
    return ar, en


def load_divisions(d):
    t = pq.ParquetFile(os.path.join(d, "divisions-sa.parquet")).read(columns=["id", "subtype", "class", "parent_division_id", "population", "names", "region", "geometry"])
    out = {}
    for r in t.to_pylist():
        v = Division()
        v.id = r["id"]
        v.sub = r["subtype"]
        v.cls = r.get("class") or ""
        nm = r.get("names") or {}
        com = dict(nm.get("common") or [])
        prim = clean_display(nm.get("primary") or "")
        ar = clean_display(com.get("ar") or "") or (prim if AR_RANGE.search(prim) else "")
        en = clean_en(clean_display(com.get("en") or "")) or (clean_en(prim) if not AR_RANGE.search(prim) else "")
        v.ar = ar
        v.en = en
        g = parse_wkb(r["geometry"])
        v.lon, v.lat = g
        v.pop = r.get("population") or 0
        v.parent = r.get("parent_division_id")
        v.region = r.get("region")
        v.rings = None
        v.bbox = None
        v.km2 = 0.0
        out[v.id] = v
    return out


def load_areas(d, divs):
    """Attaches polygons to divisions. Returns the country's polygons (land and sea) and the division ids that have one."""
    country = []
    pf = pq.ParquetFile(os.path.join(d, "division_areas-sa.parquet"))
    for b in pf.iter_batches(batch_size=200, columns=["division_id", "subtype", "class", "geometry"]):
        for r in b.to_pylist():
            g = parse_wkb(r["geometry"])
            if r["subtype"] == "country":
                country.append((r["class"], g))
                continue
            if r["class"] != "land":
                continue
            v = divs.get(r["division_id"])
            if v is None:
                continue
            if v.rings is None:
                v.rings = g
            else:
                v.rings = v.rings + g
    for v in divs.values():
        if v.rings is not None:
            v.bbox = rings_bbox(v.rings)
            v.km2 = rings_area_km2(v.rings)
    return country


class Grid:
    """Things with a position, found by cell."""

    def __init__(self, size):
        self.size = size
        self.cells = collections.defaultdict(list)

    def add(self, lat, lon, item):
        self.cells[(math.floor(lat / self.size), math.floor(lon / self.size))].append((lat, lon, item))

    def near(self, lat, lon, reach=1):
        cy, cx = math.floor(lat / self.size), math.floor(lon / self.size)
        for dy in range(-reach, reach + 1):
            for dx in range(-reach, reach + 1):
                c = self.cells.get((cy + dy, cx + dx))
                if c:
                    yield from c


def dist_m(lat1, lon1, lat2, lon2):
    dy = (lat2 - lat1) * 111320.0
    dx = (lon2 - lon1) * 111320.0 * math.cos(math.radians((lat1 + lat2) / 2))
    return math.hypot(dx, dy)


def assign_areas(rows, divs):
    """Each row's district and town by point in polygon against Overture's division areas, with the nearest town of its governorate
    where no polygon says: Overture's own locality is messy ("محافظة الدمام", "AL DAMMAM", "Al Khubar")."""
    t0 = time.time()
    lat = np.array([r.lat for r in rows], dtype=np.float64)
    lon = np.array([r.lon for r in rows], dtype=np.float64)
    pts = Points(lat, lon)
    n = len(rows)
    nb = np.full(n, -1, dtype=np.int32)
    county = np.full(n, -1, dtype=np.int32)
    loc = np.full(n, -1, dtype=np.int32)
    dlist = list(divs.values())
    idx = {d.id: i for i, d in enumerate(dlist)}
    districts = sorted((d for d in dlist if d.rings is not None and d.sub in ("neighborhood", "microhood", "macrohood")), key=lambda d: -d.km2)
    for d in districts:  # largest first: the smallest that holds a point has the last word
        hit = pts.in_rings(d.rings, d.bbox)
        if hit.size:
            nb[hit] = idx[d.id]
    for d in dlist:
        if d.rings is not None and d.sub == "county":
            hit = pts.in_rings(d.rings, d.bbox)
            if hit.size:
                county[hit] = idx[d.id]
    localities = sorted((d for d in dlist if d.rings is not None and d.sub == "locality"), key=lambda d: -d.km2)
    for d in localities:
        hit = pts.in_rings(d.rings, d.bbox)
        if hit.size:
            loc[hit] = idx[d.id]
    log(f"  polygons: {time.time() - t0:.1f} s; in a district polygon {int((nb >= 0).sum())} of {n}, in a governorate {int((county >= 0).sum())}, in a locality polygon {int((loc >= 0).sum())}")
    # districts that are only points
    point_nb = Grid(0.02)
    for d in dlist:
        if d.sub in ("neighborhood", "microhood", "macrohood") and d.rings is None and d.ar:
            point_nb.add(d.lat, d.lon, idx[d.id])
    towns = Grid(0.1)
    for d in dlist:
        if d.sub == "locality" and d.cls in ("city", "town", "village", "hamlet"):
            towns.add(d.lat, d.lon, idx[d.id])
    weights = {"city": 1.6, "town": 1.0, "village": 0.5, "hamlet": 0.3}
    reach = {"city": 40.0, "town": 14.0, "village": 5.0, "hamlet": 2.5}
    # the governorate's own town: the locality with the governorate's name
    seat = {}
    for d in dlist:
        if d.sub == "locality":
            p = divs.get(d.parent)
            if p is not None and p.sub == "county":
                a, _ = strip_governorate(p.ar, p.en)
                if a and norm(a) == norm(d.ar):
                    seat[p.id] = d.id
    cache = {}

    def town_for(la, lo, cty):
        key = (round(la, 2), round(lo, 2), cty)
        got = cache.get(key)
        if got is not None:
            return got
        best = -1
        best_s = 1e18
        cid = dlist[cty].id if cty >= 0 else None
        for tla, tlo, ti in towns.near(la, lo, 4):
            t = dlist[ti]
            km = dist_m(la, lo, tla, tlo) / 1000.0
            if km > reach[t.cls]:
                continue
            if cid is not None and t.parent != cid and divs.get(t.parent) is not None and divs[t.parent].sub == "county":
                continue  # a shop in Khobar's governorate is not in Dammam, however near Dammam's centre is
            w = weights[t.cls]
            if seat.get(cid) == t.id:
                w *= 1.6
            if t.pop:
                w *= 1.0 + 0.15 * max(0.0, math.log10(max(t.pop, 1000)) - 4.0)
            s = km / w
            if s < best_s:
                best_s = s
                best = ti
        cache[key] = best
        return best

    # areas: (district Arabic, English, town Arabic, English) -> id
    table = {}
    area_rows = []
    near_d = 0
    for i, r in enumerate(rows):
        ni = int(nb[i])
        if ni < 0 and r.kind != KIND_REGION:
            best_m = 1200.0
            for pla, plo, pi in point_nb.near(r.lat, r.lon, 1):
                m = dist_m(r.lat, r.lon, pla, plo)
                if m < best_m:
                    best_m = m
                    ni = pi
            if ni >= 0:
                near_d += 1
        cty = int(county[i])
        li = int(loc[i])
        d = dlist[ni] if ni >= 0 else None
        city_ar = city_en = ""
        t = None
        if d is not None:
            p = divs.get(d.parent)
            if p is not None and p.sub == "locality":
                t = p
        if t is None and li >= 0 and dlist[li].cls in ("city", "town"):
            t = dlist[li]
        if t is None:
            ti = town_for(r.lat, r.lon, cty)
            if ti >= 0:
                t = dlist[ti]
        if t is not None:
            city_ar, city_en = t.ar, t.en
        elif cty >= 0:
            city_ar, city_en = strip_governorate(dlist[cty].ar, dlist[cty].en)
        nb_ar = d.ar if d is not None else ""
        nb_en = d.en if d is not None else ""
        if r.kind in (KIND_CITY, KIND_TOWN, KIND_VILLAGE, KIND_REGION):
            nb_ar = nb_en = ""
        if norm(nb_ar) == norm(r.name or "") and r.kind == KIND_DISTRICT:
            nb_ar = nb_en = ""
        if norm(city_ar) == norm(r.name or "") and r.kind <= KIND_VILLAGE:
            city_ar = city_en = ""
        k = (nb_ar, nb_en, city_ar, city_en)
        if not any(k):
            r.area = 0
            continue
        aid = table.get(k)
        if aid is None:
            aid = len(table) + 1
            table[k] = aid
            area_rows.append((aid,) + k)
        r.area = aid
    log(f"  areas: {time.time() - t0:.1f} s; {len(area_rows)} distinct; {near_d} rows took a district by a point within 1.2 km")
    return area_rows


# ------------------------------------------------------------------------------------------------- Overture places

def prepare_overture(path, cache, inside):
    """Writes the rows inside the Kingdom to a small JSON lines file once: reading the whole parquet takes minutes."""
    if os.path.exists(cache):
        return
    t0 = time.time()
    pf = pq.ParquetFile(path)
    cols = ["id", "bbox", "confidence", "basic_category", "names", "brand", "phones", "websites", "addresses", "sources", "taxonomy"]
    total = 0
    box = 0
    kept = 0
    by_country = collections.Counter()
    tmp = cache + ".part"
    with open(tmp, "w", encoding="utf-8") as out:
        for b in pf.iter_batches(batch_size=40000, columns=cols):
            total += b.num_rows
            bb = b.column("bbox")
            xmin = bb.field("xmin").to_numpy(zero_copy_only=False)
            ymin = bb.field("ymin").to_numpy(zero_copy_only=False)
            mask = (xmin >= KSA_BOX[0]) & (xmin <= KSA_BOX[2]) & (ymin >= KSA_BOX[1]) & (ymin <= KSA_BOX[3])
            if not mask.any():
                continue
            b2 = b.filter(pa.array(mask))
            box += b2.num_rows
            rows = b2.to_pylist()
            la = np.array([r["bbox"]["ymin"] for r in rows])
            lo = np.array([r["bbox"]["xmin"] for r in rows])
            ins = inside(la, lo)
            for r, ok in zip(rows, ins.tolist()):
                ad = (r.get("addresses") or [{}])[0] or {}
                cc = ad.get("country")
                if not ok:
                    by_country["outside:" + str(cc)] += 1
                    continue
                by_country["inside:" + str(cc)] += 1
                nm = r.get("names") or {}
                br = r.get("brand") or {}
                tx = r.get("taxonomy") or {}
                srcs = sorted({s.get("dataset") for s in (r.get("sources") or []) if s.get("dataset") and s.get("dataset") != "Overture"})
                json.dump({"lat": r["bbox"]["ymin"], "lon": r["bbox"]["xmin"], "conf": r.get("confidence") or 0.0, "cc": cc, "name": nm.get("primary"),
                           "common": dict(nm.get("common") or []), "brand": (br.get("names") or {}).get("primary"), "wd": br.get("wikidata"),
                           "phone": (r.get("phones") or [None])[0], "web": bool(r.get("websites")), "tax": tx.get("primary"), "hier": tx.get("hierarchy"),
                           "basic": r.get("basic_category"), "src": srcs, "freeform": ad.get("freeform")}, out, ensure_ascii=False)
                out.write("\n")
                kept += 1
    os.replace(tmp, cache)
    log(f"overture prepare: {total} rows read, {box} in the box, {kept} inside the Kingdom, {time.time() - t0:.0f} s")
    log("  by country:", by_country.most_common(14))


def overture_rows(cache, lex, stats):
    rows = []
    for o in read_jsonl(cache):
        cc = o.get("cc")
        conf = o.get("conf") or 0.0
        if conf < 0.3:
            stats["overture: below confidence 0.3"] += 1
            continue
        if cc not in ("SA", None) and conf < 0.6:
            stats["overture: other country's address, low confidence"] += 1
            continue
        raw = clean_display(o.get("name") or "")
        if not raw or len(raw) < 2:
            stats["overture: no name"] += 1
            continue
        if FOREIGN.search(raw) and not AR_RANGE.search(raw):
            stats["overture: foreign script"] += 1
            continue
        if not AR_RANGE.search(raw) and not LAT_RANGE.search(raw):
            stats["overture: no Arabic or Latin"] += 1
            continue
        whole = o.get("name") or ""
        if CODE_NAME.match(raw) and not o.get("brand"):
            stats["overture: a number or code as a name"] += 1
            continue
        if ADVERT_NAME.search(raw):
            stats["overture: an advertisement as a name"] += 1
            continue
        if COUNTRY_ALONE.fullmatch(raw):
            stats["overture: a name that is only a country"] += 1
            continue
        # "Al Rakah, Dammam, Ksa" filed as lodging is an address someone checked in at. "Anarkali Restaurant KSA" is a restaurant
        address_like = bool(COUNTRYISH.search(whole)) and ("," in raw or "،" in raw) and o.get("basic") in (None, "lodging", "mountain", "travel_and_transportation", "accommodation")
        if address_like:
            stats["overture: an address as a name"] += 1
            continue
        raw = FOREIGN.sub("", raw).strip()
        raw, name_phone = phone_out_of_name(raw)
        if name_phone:
            stats["overture: phone number taken out of the name"] += 1
        ar, la = split_dual(raw)
        common = o.get("common") or {}
        ar = ar or clean_display(common.get("ar") or "")
        la = la or clean_display(common.get("en") or "")
        if ar and la:
            lex.add_pair_words(ar, la)
            lex.add_brand(la, ar, None)
        cat = T.overture_category(o.get("tax"), o.get("hier"), o.get("basic"))
        if cat == "mosque" and o.get("tax") in ("christian_place_of_worship", "hindu_place_of_worship", "buddhist_place_of_worship") and not re.search("مسجد|جامع|mosque|masjid", raw, re.I):
            cat = "other"
        if o.get("tax") in ("christian_place_of_worship",) and re.search("مسجد|جامع|mosque|masjid", raw, re.I):
            cat = "mosque"
        r = Row(KIND_POI, ar or la, la if ar else "", cat, o["lat"], o["lon"], SRC_OVERTURE)
        r.conf = conf
        # a batch of Microsoft's rows files fast food, phones and perfume under pharmacy; the same names elsewhere say what they are (see usual_kinds)
        if cat == "pharmacy" and "Microsoft" in (o.get("src") or []) and not T.PHARMACY_WORDS.search(raw):
            r.suspect = True
        r.sub = bool(SUB_UNIT.search(raw))
        r.away = bool(ELSEWHERE.search(raw))
        r.phone = clean_phone(o.get("phone")) or name_phone
        r.ad = bool(name_phone)
        r.website = bool(o.get("web"))
        r.nsrc = max(1, len(o.get("src") or []))
        br = clean_display(o.get("brand") or "")
        if br:
            r.brand = br
            r.others.append(br)
            wd = o.get("wd")
            if wd:
                if AR_RANGE.search(br):
                    lex.wd_ar[wd][br] += 1
                else:
                    lex.wd_lat[wd][br] += 1
        r.oid = o.get("wd") or ""
        rows.append(r)
    return rows


# ------------------------------------------------------------------------------------------------- OSM

def osm_poi_rows(path, inside, lex, stats):
    """Rows in the Kingdom. Every object of the whole extract teaches the lexicon: a chain's Arabic spelling is the same in Kuwait."""
    all_rows = []
    for o in read_jsonl(path):
        t = o["t"]
        cat = T.osm_category(t)
        if cat is None:
            stats["osm: no category"] += 1
            continue
        ar, en, others = names_of_osm(t)
        if ar and en:
            lex.add_pair_words(ar, en)
        br = clean_display(t.get("brand", ""))
        bar = clean_display(t.get("brand:ar", ""))
        ben = clean_display(t.get("brand:en", ""))
        if bar:
            lex.add_brand(ben or (br if not AR_RANGE.search(br) else "") or en, bar, cat, 2)
        elif br and AR_RANGE.search(br) and (ben or en):
            lex.add_brand(ben or en, br, cat, 2)
        elif br and ar and not AR_RANGE.search(br) and (norm(br) == norm(en) or not en):
            lex.add_brand(br, ar, cat, 1)
        wd = t.get("brand:wikidata")
        if wd:
            for v in (bar, ar if br else ""):
                if v:
                    lex.wd_ar[wd][v] += 1
            for v in (ben, br if br and not AR_RANGE.search(br) else ""):
                if v:
                    lex.wd_lat[wd][v] += 1
        all_rows.append((o, cat, ar, en, others))
    la = np.array([x[0]["lat"] for x in all_rows])
    lo = np.array([x[0]["lon"] for x in all_rows])
    ins = inside(la, lo)
    rows = []
    for (o, cat, ar, en, others), ok in zip(all_rows, ins.tolist()):
        if not ok:
            continue
        t = o["t"]
        name = ar or en
        generic = False
        if t.get("highway") == "motorway_junction":
            ref = t.get("ref", "")
            if not name and not ref:
                continue
            if ref:
                ref2 = clean_display(ref)
                others = others + ([name] if name else [])
                name = f"مخرج {ref2}"
                en = f"Exit {ref2}"
                ar = name
        if not name:
            if t.get("access") in ("private", "no") and cat == "parking":
                stats["osm: private parking"] += 1
                continue
            if cat not in ("mosque", "fuel", "parking", "atm", "pharmacy", "hospital", "clinic", "car_wash", "car_repair", "ev_charging", "bank", "police", "civil_defense", "tires", "rest_area"):
                stats["osm: unnamed, not essential"] += 1
                continue
            brand = clean_display(t.get("brand", "") or t.get("operator", ""))
            if brand:
                a2, l2 = split_dual(brand)
                ar, en = a2, l2
                name = ar or en
            else:
                cat_row = T.CATS[CAT[cat] - 1]
                name = cat_row[1]
                en = cat_row[2]
                ar = name
                generic = True
        if FOREIGN.search(name) and not AR_RANGE.search(name):
            stats["osm: foreign script"] += 1
            continue
        if not generic and CODE_NAME.match(name) and not t.get("brand") and t.get("highway") != "motorway_junction":
            stats["osm: a number or code as a name"] += 1
            continue
        r = Row(KIND_POI, name, en if ar and en else "", cat, o["lat"], o["lon"], SRC_OSM)
        r.sub = bool(SUB_UNIT.search(name)) and t.get("highway") != "motorway_junction"
        r.others = others
        r.phone = first_phone(t)
        r.generic = generic
        r.brand = clean_display(t.get("brand", ""))
        r.website = bool(t.get("website"))
        r.wiki = bool(t.get("wikidata"))
        r.area_poly = o["o"][0] != "n"
        r.oid = t.get("brand:wikidata") or ""
        rows.append(r)
    return rows


def osm_place_rows(path, inside, stats):
    all_rows = []
    kinds = {"city": KIND_CITY, "town": KIND_TOWN, "village": KIND_VILLAGE, "hamlet": KIND_VILLAGE, "isolated_dwelling": KIND_VILLAGE, "farm": KIND_VILLAGE, "locality": KIND_VILLAGE,
             "island": KIND_VILLAGE, "islet": KIND_VILLAGE, "suburb": KIND_DISTRICT, "quarter": KIND_DISTRICT, "neighbourhood": KIND_DISTRICT, "borough": KIND_DISTRICT, "plot": KIND_DISTRICT, "square": KIND_DISTRICT}
    for o in read_jsonl(path):
        k = kinds.get(o["t"].get("place"))
        if k is None:
            stats["osm place: other kind"] += 1
            continue
        all_rows.append((o, k))
    la = np.array([x[0]["lat"] for x in all_rows])
    lo = np.array([x[0]["lon"] for x in all_rows])
    ins = inside(la, lo)
    rows = []
    for (o, k), ok in zip(all_rows, ins.tolist()):
        if not ok:
            continue
        ar, en, others = names_of_osm(o["t"])
        if not ar and not en:
            continue
        r = Row(k, ar or en, en if ar else "", "city" if k in (KIND_CITY, KIND_TOWN, KIND_VILLAGE) else "neighbourhood", o["lat"], o["lon"], SRC_OSM)
        r.others = others
        r.cls = o["t"].get("place", "")
        r.pop = _int(o["t"].get("population"))
        rows.append(r)
    return rows


def _int(v):
    try:
        return int(str(v).replace(",", "").strip())
    except (TypeError, ValueError):
        return 0


def division_rows(divs):
    rows = []
    for d in divs.values():
        if d.sub == "country":
            continue
        kind = {"region": KIND_REGION, "county": KIND_REGION, "neighborhood": KIND_DISTRICT, "macrohood": KIND_DISTRICT, "microhood": KIND_DISTRICT}.get(d.sub)
        if kind is None:
            kind = {"city": KIND_CITY, "town": KIND_TOWN}.get(d.cls, KIND_VILLAGE)
        name = d.ar or d.en
        if not name:
            continue
        cat = "region" if kind == KIND_REGION else "neighbourhood" if kind == KIND_DISTRICT else "city"
        r = Row(kind, name, d.en if d.ar else "", cat, d.lat, d.lon, SRC_OVERTURE)
        r.cls = d.cls or d.sub
        r.pop = d.pop or 0
        rows.append(r)
    return rows


def hand_rows():
    rows = []
    for ar, en, others, cls, lat, lon in T.HAND_PLACES:
        r = Row({"city": KIND_CITY, "town": KIND_TOWN, "village": KIND_VILLAGE, "district": KIND_DISTRICT}[cls], ar, en, "city", lat, lon, SRC_OSM)
        r.others = list(others)
        r.cls = cls
        rows.append(r)
    return rows


def merge_places(rows, stats):
    """Overture's divisions and OSM's place nodes are largely the same places: one row each, with every name kept."""
    grid = Grid(0.1)
    out = []
    reach = {KIND_REGION: 60000.0, KIND_CITY: 15000.0, KIND_TOWN: 8000.0, KIND_VILLAGE: 3000.0, KIND_DISTRICT: 2500.0}
    rows.sort(key=lambda r: (-(r.src == SRC_OVERTURE), r.kind, -r.pop))
    for r in rows:
        keys = {" ".join(canon_tokens(r.name)[0])}
        if r.alt:
            keys.add(" ".join(canon_tokens(r.alt)[0]))
        for x in r.others:
            keys.add(" ".join(canon_tokens(x)[0]))
        keys.discard("")
        hit = None
        for la, lo, other in grid.near(r.lat, r.lon, 1):
            if abs(other.kind - r.kind) > 1 and not (r.kind == KIND_REGION or other.kind == KIND_REGION):
                continue
            if keys & other.mkeys and dist_m(r.lat, r.lon, la, lo) <= max(reach[r.kind], reach[other.kind]):
                if r.kind == KIND_REGION and other.kind != KIND_REGION:
                    continue
                hit = other
                break
        if hit is None:
            r.mkeys = keys
            grid.add(r.lat, r.lon, r)
            out.append(r)
        else:
            if not hit.alt and r.alt:
                hit.alt = r.alt
            elif not hit.alt and bool(AR_RANGE.search(hit.name)) != bool(AR_RANGE.search(r.name)):
                hit.alt = r.name
            for x in [r.name, r.alt] + r.others:
                if x and x not in (hit.name, hit.alt) and x not in hit.others:
                    hit.others.append(x)
            hit.mkeys |= keys
            hit.pop = max(hit.pop, r.pop)
            hit.src |= r.src
            stats["places merged"] += 1
    return out


# ------------------------------------------------------------------------------------------------- duplicates

def keys_of(r, lex):
    """What identifies a POI's name: whole-name keys, skeleton keys, and the brands in it."""
    toks = []
    mk = set()
    sk = set()
    for nm in [r.name, r.alt] + r.others[:4]:
        if not nm:
            continue
        m, _ = canon_tokens(nm)
        m = [w for w in m if w not in OPTIONAL]
        if not m:
            continue
        key = " ".join(m)
        mk.add(key)
        s = "".join(skeleton(base(w) if is_ar(w) else latin_base(w)) for w in m)
        if len(s) >= 3:
            sk.add(s)
        toks.append(m)
    r.mkeys = mk
    r.sks = sk
    r.toks = toks


def compatible(a, b):
    if a == b:
        return True
    if a in ("other", "office", "shop") or b in ("other", "office", "shop"):
        return True
    ia, ib = CAT_ICON.get(a), CAT_ICON.get(b)
    if ia == ib:
        return True
    return {ia, ib} == {T.FOOD, T.CAFE}


def core_of(r, w2c):
    """The consonants of the words that tell this place from others of its kind: no titles, no words that say its kind, nothing shorter than three consonants."""
    out = set()
    icon = CAT_ICON.get(r.cat)
    for nm in [r.name, r.alt] + r.others[:4]:
        if not nm:
            continue
        for w in canon_tokens(nm)[0]:
            if w in OPTIONAL or w.isdigit() or w in ("for", "and", "the", "of", "co"):
                continue
            kinds = w2c.get(w)
            if kinds and any(CAT_ICON.get(c) == icon or c == r.cat for c in kinds):
                continue
            for s in skeletons(w)[:1]:
                if len(s) >= 3:
                    out.add(s)
    return out


def scripts_differ(a, b):
    """One row only in Arabic letters, the other only in Latin ones."""
    return not a.alt and not b.alt and bool(AR_RANGE.search(a.name)) != bool(AR_RANGE.search(b.name))


def same_place(a, b, d):
    """Is b, d metres from a, the same place as a?"""
    if a.generic or b.generic:
        return d <= 40.0 and a.cat == b.cat
    if not compatible(a.cat, b.cat):
        return False
    if a.mkeys & b.mkeys and d <= 150.0 and CAT_ICON.get(a.cat) == CAT_ICON.get(b.cat):  # the search too holds one name within 150 m to be one place
        return True
    if (a.mkeys & b.mkeys or a.sks & b.sks) and d <= 100.0:
        return True
    if d <= 100.0 and scripts_differ(a, b) and a.core and b.core:  # مطعم الشحرور, Shahroor; Tamimi Market, أسواق التميمي
        short, long_ = (a.core, b.core) if len(a.core) <= len(b.core) else (b.core, a.core)
        if short <= long_:
            return True
    if d <= 80.0:
        for x in a.toks:
            sx = set(x)
            for y in b.toks:
                sy = set(y)
                short, long_ = (sx, sy) if len(sx) <= len(sy) else (sy, sx)
                if short and short <= long_ and sum(len(w) for w in short) >= 4:
                    return True
    return False


def dedupe_pois(rows, lex, stats, w2c):
    """Merges the same place from several sources, the richest row first. A row keeps every name its copies had."""
    for r in rows:
        keys_of(r, lex)
        r.core = core_of(r, w2c)

    def richness(r):
        return (0 if r.generic else 6) + (3 if r.alt else 0) + (2 if r.phone else 0) + (2 if r.brand else 0) + r.conf + (1.2 if r.src & SRC_OSM else 0)

    rows.sort(key=richness, reverse=True)
    grid = Grid(0.0016)
    out = []
    for r in rows:
        hit = None
        for la, lo, a in grid.near(r.lat, r.lon, 1):
            d = dist_m(r.lat, r.lon, la, lo)
            if d <= 150.0 and same_place(a, r, d):
                hit = a
                break
        if hit is None:
            grid.add(r.lat, r.lon, r)
            out.append(r)
            continue
        stats["pois merged"] += 1
        if hit.src != r.src:
            stats["pois merged across sources"] += 1
        hit.src |= r.src
        hit.merged += 1
        hit.nsrc += 1
        hit.conf = max(hit.conf, r.conf)
        if not hit.alt:
            if r.alt and bool(AR_RANGE.search(r.alt)) != bool(AR_RANGE.search(hit.name)):
                hit.alt = r.alt
            elif bool(AR_RANGE.search(r.name)) != bool(AR_RANGE.search(hit.name)):
                hit.alt = r.name
        if hit.alt and AR_RANGE.search(hit.alt) and not AR_RANGE.search(hit.name):
            hit.name, hit.alt = hit.alt, hit.name  # Arabic first
        hit.phone = hit.phone or r.phone
        hit.brand = hit.brand or r.brand
        hit.website = hit.website or r.website
        hit.wiki = hit.wiki or r.wiki
        hit.area_poly = hit.area_poly or r.area_poly
        hit.sub = hit.sub and r.sub
        hit.away = hit.away and r.away
        for x in [r.name, r.alt] + r.others:
            if x and x != hit.name and x != hit.alt and x not in hit.others and len(hit.others) < 8:
                hit.others.append(x)
        if r.src & SRC_OSM and not (hit.src & SRC_OSM & ~r.src) and hit.generic is False and r.generic is False:
            pass
    return out


# ------------------------------------------------------------------------------------------------- roads

def road_rows(path, inside, lex, stats):
    """Named roads. One row per name in a cell of about a kilometre (about five for a number with no name), so a long road can be
    found near the car, and the same name in another town stays apart."""
    cells = {}
    rows = []
    lat_list = []
    lon_list = []
    all_items = []
    road_words = {w for w, v in T.ROLES.items() if v[0] == 1}
    for o in read_jsonl(path):
        t = o["t"]
        ar, en, others = names_of_osm(t)
        ref = clean_display(t.get("ref", ""))
        if ar and en:
            lex.add_pair_words(ar, en)
        hw = t.get("highway", "")
        if not ar and not en and not (ref and hw in ("motorway", "trunk", "primary", "secondary", "motorway_link", "trunk_link")):
            continue
        for p in o["pts"]:
            lat_list.append(p[0])
            lon_list.append(p[1])
            all_items.append((len(rows), p))
        rows.append((t, ar, en, others, ref, hw))
    ins = inside(np.array(lat_list), np.array(lon_list))
    out = []
    for (i, p), ok in zip(all_items, ins.tolist()):
        if not ok:
            continue
        t, ar, en, others, ref, hw = rows[i]
        name = ar or en
        alt = en if ar else ""
        if not name:
            name = f"طريق {ref}"
            alt = f"Road {ref}"
        # the key without road words and titles, so that "King Fahd Rd" and "طريق الملك فهد" find each other
        words = [w for w in canon_tokens(name)[0] if w not in OPTIONAL] or canon_tokens(name)[0]
        key = "".join(skeleton(base(w) if is_ar(w) else latin_base(w)) or w for w in words) or " ".join(words)
        size = 0.05 if not (ar or en) else 0.02 if hw in ("motorway", "trunk") else 0.01
        ck = (key, math.floor(p[0] / size), math.floor(p[1] / size))
        hit = cells.get(ck)
        if hit is not None:
            stats["road points merged"] += 1
            if not hit.alt:
                if alt and bool(AR_RANGE.search(alt)) != bool(AR_RANGE.search(hit.name)):
                    hit.alt = alt
                elif bool(AR_RANGE.search(name)) != bool(AR_RANGE.search(hit.name)):
                    hit.alt = name
            if hit.alt and AR_RANGE.search(hit.alt) and not AR_RANGE.search(hit.name):
                hit.name, hit.alt = hit.alt, hit.name
            if ROAD_RANK.get(hw, 16) > hit.pop:
                hit.pop = ROAD_RANK.get(hw, 16)
            if ref and ref not in hit.others:
                hit.others.append(ref)
            continue
        cat = "road"
        if t.get("junction") == "roundabout" or re.search("دوار|دوّار|roundabout", name, re.I):
            cat = "roundabout"
        elif t.get("bridge") and t.get("bridge") != "no" and re.search("جسر|كوبري|bridge", name, re.I):
            cat = "bridge"
        elif t.get("tunnel") and t.get("tunnel") != "no":
            cat = "tunnel"
        r = Row(KIND_ROAD, name, alt, cat, p[0], p[1], SRC_OSM)
        r.others = list(others) + ([ref] if ref else [])
        r.pop = ROAD_RANK.get(hw, 16)  # holds the road's rank until rank_rows
        cells[ck] = r
        out.append(r)
    return out


# ------------------------------------------------------------------------------------------------- rank

def rank_rows(rows):
    for r in rows:
        if r.kind == KIND_ROAD:
            v = r.pop + (4 if r.alt else 0)
        elif r.kind == KIND_POI:
            if r.src == SRC_OVERTURE:
                v = 42.0 * r.conf + (4 if r.nsrc > 1 else 0)
            elif r.src == SRC_OSM:
                v = 30.0 + (3 if r.alt else 0) + (6 if r.wiki else 0) + (3 if r.area_poly else 0)
            else:
                v = 42.0 * r.conf + 12 + (6 if r.wiki else 0) + (3 if r.area_poly else 0)
            if r.brand:
                v += 12
            if r.phone:
                v += 2
            if r.website:
                v += 2
            weight = CAT_WEIGHT.get(r.cat, 0)
            if r.cat == "airport" and r.src == SRC_OVERTURE:
                weight = 10  # one source only: a check-in spot, a lounge or an airline's desk more often than an airport
            v += weight
            if r.generic:
                v -= 14
            if URLISH.search(r.name or "") or r.ad:
                v -= 18
            if len(r.name or "") > 50:
                v -= 6
            if r.away and r.src == SRC_OVERTURE:
                v -= 25
            if r.sub:  # a part of a place: never above the z13 tile's lowest rank (30)
                v = min(v, 28)
        else:
            base_rank = {KIND_REGION: 72, KIND_CITY: 84, KIND_TOWN: 68, KIND_VILLAGE: 34, KIND_DISTRICT: 44}.get(r.kind, 30)
            if r.cls == "county":
                base_rank = 50
            if r.cls == "hamlet":
                base_rank = 26
            if r.cls == "macrohood":
                base_rank = 48
            if r.cls == "microhood":
                base_rank = 30
            bump = 0.0
            if r.pop:
                bump = max(0.0, (math.log10(max(r.pop, 1)) - 4.0)) * 5.0
            v = base_rank + bump
        r.rank = max(1, min(100, int(round(v))))


# ------------------------------------------------------------------------------------------------- tokens

def index_tokens(r, lex, stats):
    """The k column: the name's words in order first (a phrase query needs them next to each other), then everything else that
    should find this row: other names, the other script, spellings by rule, skeletons, the category. None of this is ever shown."""
    main = []
    extra = []
    latin_words = []
    arabic_words = []
    for nm in (r.name, r.alt):
        if not nm:
            continue
        m, e = canon_tokens(nm)
        main += m[:MAX_INDEX_WORDS]
        extra += e[:MAX_INDEX_WORDS]
        extra += hyphen_joined(nm)
    for nm in r.others:
        m, e = canon_tokens(nm)
        extra += m[:MAX_INDEX_WORDS] + e[:MAX_INDEX_WORDS]
    words = list(dict.fromkeys(main + extra))
    has_ar = any(is_ar(w) for w in words)
    has_la = any(not is_ar(w) and not w.isdigit() for w in words)
    added = []
    # brands, both ways
    seqs = []
    for nm in [r.name, r.alt] + r.others:
        if nm:
            seqs.append(canon_tokens(nm)[0])
    for seq in seqs:
        seq2 = [latin_base(w) if not is_ar(w) else w for w in seq]
        n = len(seq2)
        for i in range(n):
            for ln in range(1, min(lex.max_len, n - i) + 1):
                key = " ".join(seq2[i:i + ln])
                for ak in lex.lat_brand.get(key, ()):
                    added += ak.split()
                for lk in lex.ar_brand.get(key, ()):
                    added += lk.split()
    for w in words:
        if w.isdigit():
            continue
        if is_ar(w):
            arabic_words.append(w)
            if not has_la:
                added += lex.ar2lat.get(w, [])[:2]
            elif w in T_AR_WORDS:
                added += lex.ar2lat.get(w, [])[:1]
        else:
            latin_words.append(w)
            forms = lex.lat2ar.get(w) or lex.lat2ar.get(latin_base(w))
            if forms:
                added += forms[:2]
            elif not has_ar and w not in OPTIONAL and w not in STOP:
                f = latin_forms(latin_base(w))
                if f:
                    added += f
                    stats["words spelled by rule"] += 1
    extra2 = [w for w in dict.fromkeys(added) if w and w not in words]
    all_words = words + extra2
    sks = []
    for w in words:  # only of the row's own words: a guess's skeleton adds nothing
        for s in skeletons(w):
            if len(s) >= 2:
                sks.append(SK + s)
    places = []
    if r.kind <= KIND_DISTRICT:  # a town's or a district's consonants again under their own mark: see search_ref.place_string
        places = [PL + s[1:] for s in dict.fromkeys(sks) if len(s) >= 4]
    toks = list(dict.fromkeys(main)) + [w for w in dict.fromkeys(extra + extra2) if w not in main] + list(dict.fromkeys(sks)) + places + [CT + str(CAT[r.cat])]
    return " ".join(toks)


T_AR_WORDS = set()


def area_tokens(a):
    """The a column: the words of a district and a town in both scripts."""
    words = []
    for part in a[1:5]:
        if part:
            for w in canon_tokens(part)[0]:
                if w not in OPTIONAL:
                    words.append(w)
            for w in canon_tokens(part)[1]:
                words.append(w)
    return " ".join(dict.fromkeys(words))


# ------------------------------------------------------------------------------------------------- the file

def syn_rows():
    """Table syn: everything a query needs to know about words, stored in the file so that the app never drifts from the data."""
    table = {}

    def key(text):
        return " ".join(base(w) for w in norm(text).split())

    for words, phrases, cats in T.SYNONYMS:
        ids = [CAT[c] for c in cats]
        for w in list(words.split()) + phrases:
            k = key(w)
            if not k:
                continue
            e = table.setdefault(k, [[], 0, ""])
            for i in ids:
                if i not in e[0]:
                    e[0].append(i)
    for w in T.SPECIFIC:
        e = table.get(key(w))
        if e is not None:
            e[1] = 5
    for w, (role, canon) in T.ROLES.items():
        k = key(w)
        e = table.setdefault(k, [[], 0, ""])
        e[1] = role
        e[2] = canon
    for canon, words in T.CANON.items():
        for w in words.split():
            e = table.setdefault(key(w), [[], 0, ""])
            e[2] = key(canon)
    for w in T.FILLERS + T.FILLER_PHRASES:
        k = key(w)
        e = table.setdefault(k, [[], 0, ""])
        if e[1] == 0 and not e[0]:  # a word that already means something keeps its meaning
            e[1] = ROLE_FILLER
    for w, letter in T.LETTERS.items():
        k = key(w)
        if k not in table:
            table[k] = [[], ROLE_LETTER, letter]
    for w, target in T.ALIASES.items():
        table[key(w)] = [[], ROLE_ALIAS, target]
    return [(k, " ".join(map(str, v[0])), v[1], v[2]) for k, v in sorted(table.items())]


def with_junctions(spots):
    """Adds a column to each spot: signal nodes within 60 m of each other are one junction, with one number. "الإشارة الثانية" counts these, not the nodes."""
    sig = [i for i, s in enumerate(spots) if s[0] == 2]
    parent = {i: i for i in sig}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    grid = Grid(0.001)
    for i in sig:
        la, lo = spots[i][1] / 1e6, spots[i][2] / 1e6
        for la2, lo2, j in grid.near(la, lo, 1):
            if dist_m(la, lo, la2, lo2) <= 60.0:
                parent[find(i)] = find(j)
        grid.add(la, lo, i)
    return [s + (find(i) + 1 if s[0] == 2 else None,) for i, s in enumerate(spots)]


def write_db(path, rows, area_rows, spots, gates, zones, stats, meta, prefix):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    prefix_opt = f", prefix='{prefix}'" if prefix else ""
    db.execute("PRAGMA page_size=4096")
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    db.execute("PRAGMA cache_size=-300000")
    db.execute("PRAGMA temp_store=MEMORY")
    db.executescript(f"""
        CREATE TABLE area(id INTEGER PRIMARY KEY, ar TEXT, en TEXT, city_ar TEXT, city_en TEXT);
        CREATE TABLE cat(id INTEGER PRIMARY KEY, key TEXT, ar TEXT, en TEXT, icon INT);
        CREATE TABLE p(id INTEGER PRIMARY KEY, kind INT, name TEXT NOT NULL, alt TEXT, cat INT, lat INT, lon INT, area INT, phone TEXT, rank INT, src INT);
        CREATE VIRTUAL TABLE f USING fts4(content='', k, a{prefix_opt}, matchinfo=fts3);
        CREATE TABLE spot(kind INT, lat INT, lon INT, dir INT, kmh INT, src INT, j INT);
        CREATE TABLE gate(lat INT, lon INT, sub INT, road INT);
        CREATE TABLE zone(id INTEGER PRIMARY KEY, kind INT, name TEXT, en TEXT, s REAL, n REAL, w REAL, e REAL, ring TEXT);
        CREATE TABLE syn(w TEXT PRIMARY KEY, cats TEXT, role INT, canon TEXT) WITHOUT ROWID;
        CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT) WITHOUT ROWID;
    """)
    db.execute("BEGIN")
    db.executemany("INSERT INTO cat VALUES(?,?,?,?,?)", [(CAT[c[0]], c[0], c[1], c[2], c[3]) for c in T.CATS])
    db.executemany("INSERT INTO area VALUES(?,?,?,?,?)", area_rows)
    db.executemany("INSERT INTO syn VALUES(?,?,?,?)", syn_rows())
    db.executemany("INSERT INTO meta VALUES(?,?)", list(meta.items()))
    for i, r in enumerate(rows, 1):
        db.execute("INSERT INTO p VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                   (i, r.kind, r.name, r.alt or None, CAT[r.cat], int(round(r.lat * 1e6)), int(round(r.lon * 1e6)), r.area or None, r.phone or None, r.rank, r.src))
    db.executemany("INSERT INTO spot VALUES(?,?,?,?,?,?,?)", spots)
    db.executemany("INSERT INTO gate VALUES(?,?,?,?)", gates)
    db.executemany("INSERT INTO zone VALUES(?,?,?,?,?,?,?,?,?)", zones)
    db.execute("COMMIT")
    db.execute("CREATE INDEX p_lat ON p(lat)")
    db.execute("CREATE INDEX spot_lat ON spot(lat)")
    db.execute("CREATE INDEX gate_lat ON gate(lat)")
    return db


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--overture-places", required=True)
    ap.add_argument("--overture-dir", required=True)
    ap.add_argument("--additions")
    ap.add_argument("--prefix", default="", help="FTS4 prefix= list; bytes, so an Arabic letter counts 2. Empty: no prefix tables")
    ap.add_argument("--osm-stamp", default="2026-11-02T21:21:26Z", help="data stamp of the PBF that osm_extract.py read")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    t00 = time.time()
    stats = collections.Counter()

    divs = load_divisions(a.overture_dir)
    country = load_areas(a.overture_dir, divs)
    log("divisions", len(divs), "with polygons", sum(1 for d in divs.values() if d.rings is not None), "country polygons", [(c, len(g)) for c, g in country])

    def inside(la, lo):
        pts = Points(la, lo)
        mask = np.zeros(la.size, dtype=bool)
        for cls, rings in country:
            mask[pts.in_rings(rings)] = True
        return mask

    release = "unknown"
    rel_path = os.path.join(a.overture_dir, "release.txt")
    if os.path.exists(rel_path):
        with open(rel_path, encoding="ascii") as f:
            release = f.read().strip()

    lex = Lexicon()
    cache = os.path.join(a.work, "overture-sa.jsonl")
    prepare_overture(a.overture_places, cache, inside)
    ov = overture_rows(cache, lex, stats)
    log("overture rows", len(ov))
    osm = osm_poi_rows(os.path.join(a.work, "osm-poi.jsonl"), inside, lex, stats)
    log("osm poi rows in the Kingdom", len(osm))
    place_osm = osm_place_rows(os.path.join(a.work, "osm-place.jsonl"), inside, stats)
    log("osm place rows in the Kingdom", len(place_osm))
    roads = road_rows(os.path.join(a.work, "osm-road.jsonl"), inside, lex, stats)
    log("road rows", len(roads))
    kept, nb = lex.finish()
    log(f"lexicon: {kept} word pairs kept of {len(lex.word_pairs)}, {len(lex.lat2ar)} Latin words, {len(lex.ar2lat)} Arabic words; {nb} brand pairs")
    for ar_words in T.DICT.values():
        for w in ar_words.split():
            T_AR_WORDS.add(base(w))

    # a chain's name in the other script, from the Wikidata id both sources carry
    for r in ov + osm:
        if r.oid and (lex.wd_ar.get(r.oid) or lex.wd_lat.get(r.oid)):
            for store in (lex.wd_ar, lex.wd_lat):
                c = store.get(r.oid)
                if c:
                    top = c.most_common(1)[0][0]
                    if top not in r.others:
                        r.others.append(top)
                        stats["other-script brand name from Wikidata"] += 1

    w2c = syn_words()
    usual_kinds(ov + osm, stats, w2c)
    pois = dedupe_pois(ov + osm, lex, stats, w2c)
    del ov, osm
    log("pois after merging", len(pois), "merged", stats["pois merged"], "across sources", stats["pois merged across sources"])

    # names decide weak categories
    for r in pois:
        kind_from_name(r, w2c, stats)
    check_chains(pois, stats)

    # one source's "airport" far from any airport OpenStreetMap knows is a check-in somewhere else: it stays findable, not as an airport
    fields = Grid(0.25)
    for r in pois:
        if r.cat == "airport" and r.src & SRC_OSM:
            fields.add(r.lat, r.lon, r)
    for r in pois:
        if r.cat == "airport" and r.src == SRC_OVERTURE and not any(dist_m(r.lat, r.lon, la, lo) < 12000.0 for la, lo, _ in fields.near(r.lat, r.lon, 1)):
            r.cat = "travel"
            stats["overture airports far from any airport"] += 1

    # a Latin-only row whose whole name is a brand the data often writes in Arabic on the same kind of place shows that Arabic too
    shown = 0
    shown_pairs = collections.Counter()
    for r in pois:
        if not r.alt and not AR_RANGE.search(r.name):
            key = " ".join(latin_base(w) for w in canon_tokens(r.name)[0] if not is_ar(w))
            arabic = lex.display_arabic(key, r.cat)
            if arabic:
                shown_pairs[f"{r.name} = {arabic}"] += 1
                r.alt = r.name
                r.name = arabic
                r.brand_ok = True
                shown += 1
    log("Latin-only rows given a real Arabic brand name for display:", shown)

    places = merge_places(division_rows(divs) + place_osm + hand_rows(), stats)
    del place_osm
    log("places after merging", len(places))

    # a POI that is only a town's name is the town
    pl_grid = Grid(0.1)
    for r in places:
        pl_grid.add(r.lat, r.lon, r)
    keep = []
    for r in pois:
        if r.cat in ("other", "historic", "nature", "gym", "fun", "office", "community", "gov", "shop", "events", "real_estate", "hotel") and not r.brand:
            dup = False
            for la, lo, pl in pl_grid.near(r.lat, r.lon, 1):
                if r.mkeys & pl.mkeys and dist_m(r.lat, r.lon, la, lo) < 5000.0:
                    dup = True
                    break
            if dup:
                stats["pois that were only a place's name"] += 1
                continue
        keep.append(r)
    pois = keep
    log("pois after dropping place names", len(pois))

    mark_piles(pois, stats)
    rows = places + pois + roads
    del places, pois, roads
    log("assigning areas to", len(rows))
    area_rows = assign_areas(rows, divs)
    rank_rows(rows)
    rows.sort(key=lambda r: (r.lat, r.lon))
    areas = {x[0]: x for x in area_rows}

    # spots
    spots = []
    sp = list(read_jsonl(os.path.join(a.work, "osm-spot.jsonl")))
    la = np.array([s["lat"] for s in sp])
    lo = np.array([s["lon"] for s in sp])
    ins = inside(la, lo)
    KIND = {"camera": 1, "signal": 2, "bump": 3}
    vertical = {"bump", "hump", "table", "cushion", "rumble_strip", "dip", "yes", "speed_bump", "speed_hump"}
    sc = collections.Counter()
    with open(os.path.join(a.work, "roadside.json"), encoding="utf-8") as f:
        roadside = json.load(f)  # osm_roadside.py
    for s, ok in zip(sp, ins.tolist()):
        if not ok:
            continue
        if s["k"] == "bump" and s.get("sub") not in vertical:
            sc["calming that is no bump (island, chicane...)"] += 1
            continue
        if s["k"] == "camera":  # the traffic it watches comes from the road: OSM's direction tag is where the lens faces, when it is anything
            c = roadside["cameras"].get(f'{s["lat"]:.7f},{s["lon"]:.7f}')
            if c is None:
                sc["camera the road-side file does not know"] += 1
            s["dir"] = c["dir"] if c else None
        spots.append((KIND[s["k"]], int(round(s["lat"] * 1e6)), int(round(s["lon"] * 1e6)), s.get("dir"), s.get("kmh"), 1))
        sc[s["k"]] += 1
        if s.get("dir") is not None:
            sc[s["k"] + " with direction"] += 1
        if s.get("kmh"):
            sc[s["k"] + " with limit"] += 1
    add_src = "none"
    if a.additions and os.path.exists(a.additions):
        with open(a.additions, encoding="utf-8") as f:
            ad = json.load(f)
        if ad.get("version") == 1:
            add_src = str(ad.get("updated"))
            for key, kind in (("cameras", 1), ("signals", 2), ("bumps", 3)):
                for x in ad.get(key) or []:
                    spots.append((kind, int(round(x["lat"] * 1e6)), int(round(x["lon"] * 1e6)), None, x.get("kmh") or None, 2))
                    sc["owner " + key] += 1
    spots.sort(key=lambda s: s[1])
    spots = with_junctions(spots)
    sc["signal junctions (nodes within 60 m are one)"] = len({s[6] for s in spots if s[6] is not None})
    log("spots", dict(sc))
    la = np.array([g["lat"] for g in roadside["gates"]])
    lo = np.array([g["lon"] for g in roadside["gates"]])
    road_order = ["motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service"]
    gates = sorted((int(round(g["lat"] * 1e6)), int(round(g["lon"] * 1e6)), g["sub"], next((i for i, x in enumerate(road_order) if g["road"].startswith(x)), 9))
                   for g, ok in zip(roadside["gates"], inside(la, lo).tolist()) if ok)
    zones = [(i, z["kind"], z["name"] or None, z["en"] or None, min(p[1] for p in z["ring"]), max(p[1] for p in z["ring"]), min(p[0] for p in z["ring"]), max(p[0] for p in z["ring"]), json.dumps(z["ring"], separators=(",", ":")))
             for i, z in enumerate(roadside["zones"], 1)]
    log("gates", len(gates), "zones", len(zones))

    meta = {
        "schema": "2", "norm_version": str(NORM_VERSION), "built": time.strftime("%Y-%m-%dT%H:%MZ", time.gmtime()), "overture": release,
        "osm_data": a.osm_stamp, "additions": add_src,
        "attribution": "© OpenStreetMap contributors (ODbL), © Overture Maps Foundation, overturemaps.org. Places from Overture: CDLA Permissive 2.0, Apache 2.0 for Foursquare data; "
                       "Foursquare data was transformed to the Overture schema. Copyright 2024 Foursquare Labs, Inc. All rights reserved.",
        "rank": "0-100, higher is more important",
    }
    log("writing", a.out)
    t0 = time.time()
    db = write_db(a.out, rows, area_rows, spots, gates, zones, stats, meta, a.prefix)
    log(f"tables written {time.time() - t0:.1f} s, file {os.path.getsize(a.out) / 1e6:.1f} MB before the index")
    t0 = time.time()
    db.execute("BEGIN")
    tok_total = 0
    area_cache = {}
    ins_sql = "INSERT INTO f(docid,k,a) VALUES(?,?,?)"
    batch = []
    for i, r in enumerate(rows, 1):
        k = index_tokens(r, lex, stats)
        tok_total += k.count(" ") + 1
        at = area_cache.get(r.area)
        if at is None:
            at = area_tokens(areas[r.area]) if r.area else ""
            area_cache[r.area] = at
        batch.append((i, k, at))
        if len(batch) >= 5000:
            db.executemany(ins_sql, batch)
            batch.clear()
    if batch:
        db.executemany(ins_sql, batch)
    db.execute("COMMIT")
    log(f"index inserted {time.time() - t0:.1f} s, tokens per row {tok_total / len(rows):.1f}")
    t0 = time.time()
    db.execute("INSERT INTO f(f) VALUES('optimize')")
    db.commit()
    log(f"optimize {time.time() - t0:.1f} s")
    t0 = time.time()
    db.execute("VACUUM")
    db.commit()
    db.close()
    log(f"vacuum {time.time() - t0:.1f} s")
    size = os.path.getsize(a.out)
    by_kind = collections.Counter(r.kind for r in rows)
    by_src = collections.Counter(r.src for r in rows if r.kind == KIND_POI)
    by_cat = collections.Counter(r.cat for r in rows if r.kind == KIND_POI)
    report = {
        "rows": len(rows), "file_bytes": size, "bytes_per_row": round(size / len(rows)), "tokens_per_row": round(tok_total / len(rows), 1),
        "by_kind": {str(k): v for k, v in sorted(by_kind.items())}, "poi_by_source (1 Overture, 2 OSM, 3 both)": {str(k): v for k, v in sorted(by_src.items())},
        "poi_by_category": dict(by_cat.most_common()), "areas": len(area_rows), "spots": dict(sc), "spot_rows": len(spots), "stats": dict(stats),
        "seconds": round(time.time() - t00), "latin_only_rows_given_arabic_brand_display": shown,
        "lexicon_word_pairs_kept": kept, "brand_pairs": nb, "brand_display_pairs": dict(shown_pairs.most_common()),
    }
    with open(os.path.join(a.work, "build-report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    log(json.dumps({k: v for k, v in report.items() if k not in ("poi_by_category", "brand_display_pairs")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
