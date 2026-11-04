#!/usr/bin/env python
"""Arabic road names at pack build: a new PBF that Planetiler, Valhalla and the places' extract all read, with name:ar on the roads that only have a Latin name. Nothing happens at run time.

    <venv python> -I arabic_names.py learn <ksa.osm.pbf> [--latin-names <json>]        what the Kingdom's own names say: arabic_names_words.tsv, for a person to read
    <venv python> -I arabic_names.py apply <ksa.osm.pbf> <out.osm.pbf> --report <report.json> [--sample tests/arabic-names-sample.tsv]
    python -I arabic_names.py try "King Fahd Road"                                       one name

Only highway ways with a Latin `name` and no `name:ar` are given one, and the hand corrections of arabic_names_rules.json's `fix` are made. The rest of the file is copied as it is, but for one thing that also must read the same
everywhere: a toll booth outside the King Fahd Causeway becomes barrier=checkpoint, or Valhalla's answer says toll_collection there (the Kingdom's roads have no other toll, osm_roadside.py).

A name is rewritten only when every word of it is known; otherwise it stays Latin, untouched, and the style then shows no label for a minor road (SPEC section 11). Nothing is ever spelled out letter by letter:
a wrong name on a sign is worse than a Latin one.

Where words come from, in order:
  1. arabic_names_rules.json, written by hand from the design's table (final2/tools/map.html, arRoad) and from reading the data: kinds of road, titles, phrases, adjectives that go after the name, overrides for typos.
  2. arabic_names_words.tsv: pairs learnt from the Kingdom's ways, places and POIs that hold both a Latin and an Arabic name, aligned word by word. `learn` proposes a pair as `yes` only when it is near-unanimous
     (LEARN_SHARE), seen in enough different names (LEARN_MIN) and its consonants fit. Only words that a Latin road name really uses are listed. The `verdict` column is a person's reading: `yes` is used, `no` never, and `unread`
     (what a new OSM extract brings) is not used until someone has read it. A pair under the bar may be `yes` when a person read it and knows the name: its count stays there to say so.

The report says how many ways each rule touched, and the named road segments with Arabic before and after, for the Kingdom and for the owner's box.
"""
import argparse
import collections
import json
import math
import os
import random
import re
import sys
import time
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
RULES = os.path.join(HERE, "arabic_names_rules.json")
WORDS = os.path.join(HERE, "arabic_names_words.tsv")

AR_RE = re.compile(r"[؀-ۿݐ-ݿࢠ-ࣿﭐ-﷿ﹰ-﻿]")
LAT_RE = re.compile(r"[A-Za-z]")
OWNER_BOX = (26.10, 27.25, 49.40, 50.30)  # s, n, w, e
CAUSEWAY = (26.10, 26.25, 50.19, 50.50)  # s, n, w, e: osm_roadside.py's
LEARN_SHARE = 0.9
LEARN_MIN = 3
SEED = 20261104

MARKS = re.compile(r"[ً-ٰٟـ]")
SUN = {"as": ("s", "z", "th"), "ash": ("sh", "s"), "ar": ("r",), "an": ("n",), "ad": ("d",), "adh": ("dh", "d"), "at": ("t", "th"), "ath": ("th", "t"), "az": ("z",)}
ARTICLES = {"al", "el", "ul"}
ABD_ALONE = {"abd", "abdul", "abdel", "abdal", "abdu", "abdol", "abdur", "abdus", "abduz", "abdun"}
ABD_JOINED = re.compile(r"^(?:abd|abdu|abdul|abdel|abdal|abdol)(?:ul|el|al|ol)?(?=[a-z]{3})")
ROAD_WORDS_AR = {"شارع", "طريق", "ش", "الشارع", "الطريق", "ط"}
NO_ARTICLE_AFTER = {"abu", "abi", "abo", "bin", "ibn", "bn", "ben", "abn", "lbn", "bint", "umm", "um", "om", "bani", "bni", "aal"}
ORDINAL = re.compile(r"^(\d{1,5})(st|nd|rd|th)$")


def fold_latin(s):
    """Lower case, no marks: Wādī = wadi. Apostrophes go (Misha'al = mishaal), other marks between words are spaces."""
    s = unicodedata.normalize("NFD", s)
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.lower().replace("’", "").replace("'", "").replace("`", "").replace("ʻ", "").replace("ʼ", "").replace("‘", "").replace("´", "")
    s = re.sub(r"(?<=[a-z])\.(?=\s|$)", " ", s)  # St. Blvd.
    return re.sub(r"[\-–—_/,.;:()\[\]\"#&]+", " ", s)


def latin_tokens(s):
    """Words of a Latin name. The article joins its word and the many spellings of Abd join what follows: Al-Khobar, Al Khobar and AlKhobar are one word, Abdul Aziz and Abdulaziz too."""
    raw = fold_latin(s).split()
    out = []
    i = 0
    while i < len(raw):
        w = raw[i]
        nxt = raw[i + 1] if i + 1 < len(raw) else ""
        if nxt and nxt.isalpha() and len(nxt) >= 2:
            if w in ARTICLES:
                out.append("al" + nxt)
                i += 2
                continue
            if w in SUN and nxt.startswith(SUN[w]) and len(nxt) >= 3:
                out.append("al" + nxt)
                i += 2
                continue
        if w in ABD_ALONE and nxt.isalpha() and len(nxt) >= 2:
            rest = nxt
            j = i + 2
            if rest in ARTICLES and j < len(raw):
                rest = raw[j]
                j += 1
            elif rest.startswith("al") and len(rest) > 4:
                rest = rest[2:]
            out.append("abdul" + rest)
            i = j
            continue
        m = ABD_JOINED.match(w)
        if m:
            w = "abdul" + w[m.end():]
        out.append(w)
        i += 1
    return out


def arabic_clean(s):
    s = MARKS.sub("", unicodedata.normalize("NFC", s))
    return re.sub(r"[\-–—_/,،.;؛:()\[\]\"'«»]+", " ", s)


def arabic_tokens(s):
    """Words of an Arabic name. Abd joins what follows, and the second half of a name in ...الدين joins the first, as the Latin word of it is one."""
    raw = arabic_clean(s).split()
    out = []
    i = 0
    while i < len(raw):
        w = raw[i]
        if w == "عبد" and i + 1 < len(raw):
            out.append("عبد" + raw[i + 1])
            i += 2
            continue
        if i + 1 < len(raw) and raw[i + 1] == "الدين":
            out.append(w + " الدين")
            i += 2
            continue
        out.append(w)
        i += 1
    return out


def ar_key(w):
    """Spellings that are the same word: hamza forms, ى and ي, ة and ه. Only to compare and count: what is written keeps its own letters."""
    return w.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ى", "ي").replace("ة", "ه").replace("ؤ", "و").replace("ئ", "ي").replace("ء", "").replace(" ", "")


AR_SOUND = {"ب": "b", "ت": "t", "ث": "t", "ج": "j", "ح": "h", "خ": "k", "د": "d", "ذ": "d", "ر": "r", "ز": "z", "س": "s", "ش": "s", "ص": "s", "ض": "d", "ط": "t", "ظ": "d", "غ": "g", "ف": "f", "ق": "k", "ك": "k", "ل": "l", "م": "m", "ن": "n", "ه": "h", "چ": "j", "پ": "b", "ڤ": "f", "گ": "g"}


def ar_skeleton(w):
    w = w.replace(" ", "")
    if w.startswith("ال") and len(w) > 3:
        w = w[2:]
    s = "".join(AR_SOUND.get(c, "") for c in w)
    return re.sub(r"(.)\1+", r"\1", s)


def lat_skeleton(w):
    if w.startswith("al") and len(w) > 4:
        w = w[2:]
    for a, b in (("sh", "s"), ("kh", "k"), ("gh", "g"), ("th", "t"), ("dh", "d"), ("ph", "f"), ("ch", "j"), ("ck", "k"), ("q", "k"), ("c", "k"), ("v", "f"), ("x", "ks"), ("p", "b"), ("y", ""), ("w", "")):
        w = w.replace(a, b)
    s = re.sub(r"[aeiou]", "", w)
    return re.sub(r"(.)\1+", r"\1", s)


SAME_SOUND = [set("gjk"), set("dtz"), set("sz"), set("sc"), set("hk")]


def skeleton_gap(a, b):
    """Edit distance of two consonant skeletons, sounds a transliteration confuses at half the price; a final h counts for little (ة, ه)."""
    a, b = a.rstrip("h"), b.rstrip("h")
    if not a or not b:
        return max(len(a), len(b))
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            sub = 0 if x == y else 0.5 if any(x in g and y in g for g in SAME_SOUND) else 1
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + sub))
        prev = cur
    return prev[-1]


def sounds_alike(lat, ar):
    a, b = lat_skeleton(lat), ar_skeleton(ar)
    if len(a) < 2 or len(b) < 2:
        return a == b or bool(a and b and skeleton_gap(a, b) <= 0.5)
    gap = skeleton_gap(a, b)
    return gap <= 0.34 * max(len(a), len(b)) or (gap <= 1 and max(len(a), len(b)) >= 3)


def load_rules():
    with open(RULES, encoding="utf-8") as f:
        return json.load(f)


def load_words():
    """latin word -> Arabic: what a person read and did not refuse."""
    out = {}
    if not os.path.exists(WORDS):
        return out
    with open(WORDS, encoding="utf-8") as f:
        for ln in f:
            if ln.startswith("#") or not ln.strip():
                continue
            p = ln.rstrip("\n").split("\t")
            if len(p) >= 5 and p[4] == "yes":
                out[p[0]] = p[1]
    return out


# ---- the translation

class Translator:
    def __init__(self, rules=None, words=None):
        r = rules or load_rules()
        self.fix = r["fix"]                  # a whole name as written, or Latin as its words: its Arabic
        self.fix_words = {tuple(latin_tokens(k)): v for k, v in r["fix"].items() if LAT_RE.search(k)}
        self.kind_end = r["kind_end"]        # Road -> طريق
        self.kind_before = r["kind_before"]  # Highway 40 -> طريق
        self.letters = r["letters"]          # 28A Street -> شارع 28أ: a letter's Arabic in the street numbers of the Kingdom's own pairs
        self.hand = {k: v for k, v in r["words"].items()}
        self.phrases = sorted(((tuple(ORDINAL.sub(r"\1", w) for w in latin_tokens(k)), v) for k, v in r["phrases"].items()), key=lambda x: -len(x[0]))
        self.after = r["after"]              # adjectives that follow: Old X Road -> طريق X القديم
        self.titled = set(r["titled"])       # a name starting with these is a street with no word for it
        self.stay = set(r["stay"])           # never a word on a sign
        self.first_word = r["first_word"]    # a typo in the first word of an Arabic name: شلرع = شارع
        self.learnt = words if words is not None else load_words()

    def mend(self, name):
        """An Arabic name with its typos corrected ("fix", "first_word"), or as it is."""
        if not name or not AR_RE.search(name):
            return name
        if name in self.fix:
            return self.fix[name]
        first, _, rest = name.partition(" ")
        return f"{self.first_word[first]} {rest}" if first in self.first_word and rest else name

    def word(self, w, first, before="", following=""):
        """(Arabic, source) of one word, or None. [before], [following]: its neighbours, for the article."""
        if w in self.stay:
            return None
        if w in ("bin", "ibn", "bn", "ben", "abn", "lbn"):
            return ("ابن" if first else "بن"), "hand"
        if w in self.hand:
            return self.hand[w], "hand"
        if w in self.learnt:
            return self.learnt[w], "learnt"
        # with or without the article, when the other form is known: mansour = منصور, so al-Mansour = المنصور; al-Thuqbah = الثقبة, so Thuqbah = الثقبة, as it is written in Arabic.
        # Not where Arabic has no article: after Abu or Bin (أبو قتادة), or before a word that has one (تراث الفكر).
        if w.startswith("al") and len(w) > 4:
            bare = w[2:]
            a = self.hand.get(bare) or self.learnt.get(bare)
            if a and bare not in self.stay and before not in NO_ARTICLE_AFTER:
                return (a if a.startswith("ال") else "ال" + a), "article"
        elif not w.startswith("al") and len(w) >= 4 and before not in NO_ARTICLE_AFTER and not following.startswith("al"):
            a = self.hand.get("al" + w) or self.learnt.get("al" + w)
            if a and a.startswith("ال") and w not in self.stay:
                return a, "article"
        return None

    def translate(self, name):
        """(Arabic, rule) or None. Never a part of a name: all its words, or nothing."""
        name = name.strip()
        if name in self.fix:
            return self.fix[name], "override"
        if not LAT_RE.search(name) or AR_RE.search(name):
            return None
        for part in name.split(";"):  # OSM's way of giving two names: the first that goes
            part = part.strip()
            if not part:
                continue
            t = self.fix.get(part)
            if t:
                return t, "override"
            t = self.fix_words.get(tuple(latin_tokens(part)))
            if t:
                return t, "override"
            t = self._part(part)
            if t:
                return t
        return None

    def _part(self, name):
        # a letter in brackets belongs to the number; other brackets only say more ("Jeddah Street (Road 274)")
        name = re.sub(r"\(\s*([A-Za-z])\s*\)", r" \1 ", name)
        name = re.sub(r"\(.*?\)|\(.*$", " ", name).strip()
        # "Ras Al Khair - Jubail", "King Khalid Road - 3rd Ring Road": the dash stays where it says two things
        segs = [s.strip() for s in re.split(r"\s[-–—]\s", name) if s.strip()]
        if len(segs) > 1:
            first_has_kind = latin_tokens(segs[0])[-1:] and latin_tokens(segs[0])[-1] in self.kind_end
            if first_has_kind or not (latin_tokens(segs[-1])[-1:] and latin_tokens(segs[-1])[-1] in self.kind_end):
                parts = [self._one(s, bare=not first_has_kind) for s in segs]
                if all(parts):
                    rule = "learnt" if any(p[1] == "learnt" for p in parts) else "table"
                    return " - ".join(p[0] for p in parts), rule
                return None
            # "Al Riyadh - Al Batha Road": the word of the whole goes first
            kinds = self._one(segs[-1])
            parts = [self._one(s, bare=True) for s in segs[:-1]]
            if kinds and all(parts):
                word = kinds[0].split(" ", 1)
                if len(word) == 2:
                    rule = "learnt" if kinds[1] == "learnt" or any(p[1] == "learnt" for p in parts) else "table"
                    return word[0] + " " + " - ".join([p[0] for p in parts] + [word[1]]), rule
            return None
        return self._one(name)

    def _number(self, toks):
        """"19th Street", "Road 195", "Highway 218", "28A St", "A Street": the words, or None. A number is written as digits, with no ordinal ending."""
        def num(w):
            m = re.fullmatch(r"(\d{1,5})(st|nd|rd|th)?", w)
            if m:
                return str(int(m.group(1)))
            m = re.fullmatch(r"(\d{1,5})([a-z])", w)
            if m and m.group(2) in self.letters:
                return str(int(m.group(1))) + self.letters[m.group(2)]
            return None
        if len(toks) == 2:
            n = num(toks[0])
            if n and toks[1] in self.kind_end:
                return f"{self.kind_end[toks[1]]} {n}"
            n = num(toks[1])
            if n and toks[0] in self.kind_before:
                return f"{self.kind_before[toks[0]]} {n}"
            if toks[0] in self.letters and toks[1] in ("street", "st", "road", "rd"):
                return f"{self.kind_end[toks[1]]} {self.letters[toks[0]]}"
            if toks[1] in self.letters and toks[0] in ("street", "road"):
                return f"{self.kind_before[toks[0]]} {self.letters[toks[1]]}"
        if len(toks) == 3:
            if toks[1] in ("st", "nd", "rd", "th") and toks[0].isdigit() and toks[2] in self.kind_end:  # "16 th Street"
                return f"{self.kind_end[toks[2]]} {toks[0]}"
            if toks[0] in self.kind_before and toks[1] in ("no", "number") and toks[2].isdigit():
                return f"{self.kind_before[toks[0]]} {toks[2]}"
            if toks[0].isdigit() and toks[1] in self.letters and toks[2] in self.kind_end:  # "22 (A) Street"
                return f"{self.kind_end[toks[2]]} {toks[0]}{self.letters[toks[1]]}"
        return None

    def _one(self, name, bare=False):
        """One name. [bare]: no word of its own is added in front of it (a part of a longer one)."""
        toks = [ORDINAL.sub(r"\1", w) for w in latin_tokens(name)] if not self._number(latin_tokens(name)) else latin_tokens(name)
        if not toks:
            return None
        n = self._number(toks)
        if n:
            return n, "number"
        kinds = []
        rest = list(toks)
        number = ""
        # "King Fahd Road 68": the number stays behind the name
        if len(rest) > 2 and rest[-1].isdigit() and rest[-2] in self.kind_end:
            number = rest[-1]
            rest = rest[:-1]
        whole = None
        for p, a in self.phrases:  # whole phrases first: Ring Road
            if a.startswith(("الطريق ", "الدائري ")) and len(rest) >= len(p) and tuple(rest[-len(p):]) == p:
                whole = a
                rest = rest[:-len(p)]
                break
        if whole is None:
            for _ in range(2):  # "King Fahd Road Tunnel", "Al Rahmah Mountain Road"
                if len(rest) > 1 and rest[-1] in self.kind_end:
                    kinds.append(self.kind_end[rest[-1]])
                    rest = rest[:-1]
                else:
                    break
            if not kinds and len(rest) > 1 and rest[0] in self.kind_before:
                kinds.append(self.kind_before[rest[0]])
                rest = rest[1:]
        words = []
        tail = []
        sources = collections.Counter()
        i = 0
        while i < len(rest):
            hit = None
            for p, a in self.phrases:
                if rest[i:i + len(p)] == list(p):
                    hit = (p, a)
                    break
            if hit:
                words.append(hit[1])
                sources["phrase"] += 1
                i += len(hit[0])
                continue
            w = rest[i]
            if w in self.after and (kinds or whole):
                tail.append(self.after[w])
                i += 1
                continue
            if w.isdigit():
                words.append(str(int(w)))
                i += 1
                continue
            got = self.word(w, i == 0, rest[i - 1] if i else "", rest[i + 1] if i + 1 < len(rest) else "")
            if got is None:
                return None
            words.append(got[0])
            sources[got[1]] += 1
            i += 1
        if whole is not None:
            front = (" ".join(words) + " ") if words else ""
            text = (front + whole + (" " + " ".join(tail) if tail else "") + (" " + number if number else "")).strip()
            return text, "learnt" if sources["learnt"] or sources["article"] else "table"
        if not words and not tail:
            return None
        if tail and "جادة" in kinds:  # (the adjectives are written for a شارع or a طريق: a جادة is feminine)
            return None
        if not words and kinds:  # "Main Street", "old road": an adjective with no name needs the article
            kinds[0] = "ال" + kinds[0]
        if not kinds and rest[0] in self.titled and not bare:
            kinds = ["شارع"]
        kinds = [k for k in kinds]
        text = " ".join(x for x in kinds[::-1] + words + tail + ([number] if number else []) if x) if len(kinds) < 2 else " ".join(x for x in kinds + words + tail + ([number] if number else []) if x)
        return text, "learnt" if sources["learnt"] or sources["article"] else "table"


# ---- learning

def name_pairs(tags):
    """(latin, arabic) names an object holds, by the tags that name the same thing in two scripts."""
    name = tags.get("name") or ""
    ar = tags.get("name:ar") or ""
    en = tags.get("name:en") or ""
    if AR_RE.search(ar) and not LAT_RE.search(ar):
        if LAT_RE.search(en) and not AR_RE.search(en):
            yield en, ar
        if LAT_RE.search(name) and not AR_RE.search(name):
            yield name, ar
    elif AR_RE.search(name) and not LAT_RE.search(name):
        if LAT_RE.search(en) and not AR_RE.search(en):
            yield en, name
    for key in ("official_name", "alt_name", "old_name", "short_name"):
        a, e = tags.get(key + ":ar") or "", tags.get(key + ":en") or ""
        if AR_RE.search(a) and not LAT_RE.search(a) and LAT_RE.search(e) and not AR_RE.search(e):
            yield e, a


def peel(lat, ar, kinds):
    """Both names without what says the kind of road, which is not always the same word in the two."""
    said = None
    if len(lat) > 1 and lat[-1] in kinds:
        said = kinds[lat[-1]]
        lat = lat[:-1]
    elif len(lat) > 1 and lat[0] in ("street", "road", "highway", "st", "rd"):
        lat = lat[1:]
    if len(ar) > 1 and (ar[0] in ROAD_WORDS_AR or ar[0] == said):
        ar = ar[1:]
    return lat, ar


def learn(pbf, latin_json):
    """Writes arabic_names_words.tsv: the words Latin road names use, with what the data says of each. Verdicts already in the file stay."""
    import osmium
    t0 = time.time()
    rules = load_rules()
    kinds = rules["kind_end"]
    pairs = collections.Counter()  # (latin name, arabic name, group)
    latin_ways = collections.Counter()

    class H(osmium.SimpleHandler):
        def rows(self, o, group):
            t = {k: v for k, v in ((x.k, x.v) for x in o.tags) if k.startswith(("name", "official_name", "alt_name", "old_name", "short_name"))}
            if not t:
                return
            for lat, ar in name_pairs(t):
                pairs[(lat.strip(), ar.strip(), group)] += 1

        def node(self, n):
            if n.tags:
                self.rows(n, "place" if "place" in n.tags else "poi")

        def way(self, w):
            if not w.tags:
                return
            self.rows(w, "road" if "highway" in w.tags else "place" if "place" in w.tags else "poi")
            if "highway" in w.tags:
                n = w.tags.get("name") or ""
                if LAT_RE.search(n) and not AR_RE.search(n) and not AR_RE.search(w.tags.get("name:ar") or ""):
                    latin_ways[n] += 1

        def relation(self, r):
            if r.tags:
                self.rows(r, "road" if r.tags.get("type") == "route" else "poi")

    H().apply_file(pbf)
    print(f"{len(pairs):,} different name pairs, {len(latin_ways):,} Latin road names, {time.time() - t0:.0f} s", flush=True)
    seen = collections.defaultdict(collections.Counter)  # latin word -> arabic key -> names
    spelled = collections.defaultdict(collections.Counter)  # arabic key -> spelling -> names
    aligned = skipped = 0
    for (lat, ar, group), _ in pairs.items():
        for lat_part, ar_part in zip(lat.split(";"), ar.split(";")):
            lt, at = peel(latin_tokens(lat_part), arabic_tokens(ar_part), kinds)
            if len(lt) != len(at) or not lt:
                skipped += 1
                continue
            aligned += 1
            for x, y in zip(lt, at):
                if not x.isalpha() or not AR_RE.search(y):
                    continue
                # roads and places are named the same way in both scripts; a POI's words are in another order ("Rajhi Bank" = بنك الراجحي), so only what sounds alike counts
                if group == "poi" and not sounds_alike(x, y):
                    continue
                k = ar_key(y)
                seen[x][k] += 1
                spelled[k][y] += 1
    print(f"aligned {aligned:,}, skipped for a different count of words {skipped:,}; {len(seen):,} Latin words", flush=True)
    used = collections.Counter()
    for n, c in latin_ways.items():
        for part in re.split(r"[;]", n):
            for w in latin_tokens(part):
                if not w[0].isdigit() and w not in kinds and w not in rules["kind_before"]:
                    used[w] += c
    if latin_json:
        with open(latin_json, "w", encoding="utf-8") as f:
            json.dump({"latin": latin_ways.most_common()}, f, ensure_ascii=False, indent=0)
    verdicts = {}
    if os.path.exists(WORDS):
        with open(WORDS, encoding="utf-8") as f:
            for ln in f:
                p = ln.rstrip("\n").split("\t")
                if len(p) >= 5 and not ln.startswith("#"):
                    verdicts[p[0]] = p[4]
    rows = []
    for w in sorted(used):
        c = seen.get(w)
        if not c:
            continue
        total = sum(c.values())
        k, n = c.most_common(1)[0]
        best = best_spelling(spelled[k])
        share = n / total
        passes = n >= LEARN_MIN and share >= LEARN_SHARE and sounds_alike(w, best)
        rows.append((w, best, n, round(share, 3), verdicts.get(w, "unread"), used[w], "passes" if passes else "under"))
    with open(WORDS, "w", encoding="utf-8", newline="\n") as f:
        f.write("# latin word\tArabic\tnames that hold the pair\tits share\tverdict: yes, no, unread (only yes is used)\tways that use the word\tthe automatic bar: passes or under\n")
        for r in rows:
            f.write("\t".join(str(x) for x in r) + "\n")
    print(f"{len(rows):,} words of Latin road names have evidence, {sum(1 for r in rows if r[6] == 'passes'):,} pass the bar; {sum(1 for r in rows if r[4] == 'yes'):,} yes, {sum(1 for r in rows if r[4] == 'no'):,} no, {sum(1 for r in rows if r[4] == 'unread'):,} unread; {time.time() - t0:.0f} s", flush=True)


def best_spelling(c):
    """The most usual spelling, but a hamza written is a hamza right: الأمير over الامير when it is written that way at least a fifth as often."""
    (top, n), *_ = c.most_common(1)

    def marks(w):
        return sum(w.count(x) for x in "أإآةىئؤء")
    for w, m in c.most_common():
        if w != top and m >= n * 0.2 and ar_key(w) == ar_key(top) and marks(w) > marks(top):
            return w
    return top


# ---- applying it

CITIES = [
    ("Riyadh", 24.40, 25.05, 46.40, 47.05), ("Jeddah", 21.20, 21.95, 39.00, 39.35), ("Makkah", 21.30, 21.55, 39.70, 40.00), ("Madinah", 24.35, 24.60, 39.45, 39.75),
    ("Khobar-Dhahran", 26.14, 26.36, 50.05, 50.28), ("Dammam-Qatif", 26.36, 26.70, 49.90, 50.20), ("Jubail", 26.85, 27.25, 49.40, 49.80), ("Hofuf-Ahsa", 25.20, 25.60, 49.40, 49.80),
    ("Taif", 21.15, 21.40, 40.30, 40.55), ("Abha-Khamis", 18.10, 18.40, 42.40, 42.85), ("Tabuk", 28.30, 28.50, 36.45, 36.70), ("Hail", 27.40, 27.65, 41.60, 41.80), ("Buraidah-Unaizah", 26.00, 26.45, 43.80, 44.15),
]


def city_of(lat, lon):
    for c, s, n, w, e in CITIES:
        if s <= lat <= n and w <= lon <= e:
            return c
    return "elsewhere"


def way_km(nodes):
    km = 0.0
    last = None
    for nd in nodes:
        if not nd.location.valid():
            continue
        if last:
            km += math.hypot((nd.location.lon - last[1]) * 111.32 * math.cos(math.radians(last[0])), (nd.location.lat - last[0]) * 110.54)
        last = (nd.location.lat, nd.location.lon)
    return km


def split_mixed(name):
    """A name in both scripts ("Ali Ibn Abi Talib Road طريق علي بن أبي طالب"): (Latin, Arabic), or None when one of them says nothing by itself."""
    m = re.fullmatch(r"([^؀-ۿ]*[A-Za-z][^؀-ۿ]*)\s*([؀-ۿ].*)", name)
    if m and not LAT_RE.search(m.group(2)):
        lat, ar = m.group(1), m.group(2)
    else:
        m = re.fullmatch(r"([؀-ۿ][^A-Za-z]*)\s*([A-Za-z].*)", name)
        if not m or AR_RE.search(m.group(2)):
            return None
        ar, lat = m.group(1), m.group(2)
    lat, ar = lat.strip(" ,;-–"), ar.strip(" ,;-–")
    if not lat or not ar or ar in ROAD_WORDS_AR or len(ar) < 3:
        return None
    return lat, ar


def apply(pbf, out, report_path, sample_path):
    import osmium
    t0 = time.time()
    tr = Translator()
    rule_ways = collections.Counter()
    rule_names = collections.defaultdict(set)
    areas = {"kingdom": collections.Counter(), "box": collections.Counter()}
    samples = collections.defaultdict(dict)
    stayed = collections.Counter()
    stayed_box = collections.Counter()
    wanted = {}

    def tally(area, arabic, km, new):
        c = areas[area]
        c["named_ways"] += 1
        c["named_km"] += km
        if arabic:
            c["arabic_ways_before"] += 1
            c["arabic_km_before"] += km
        if arabic or new:
            c["arabic_ways_after"] += 1
            c["arabic_km_after"] += km

    class Look(osmium.SimpleHandler):
        def way(self, w):
            t = w.tags
            if "highway" not in t:
                return
            name = t.get("name") or ""
            if not name:
                return
            ar = t.get("name:ar") or ""
            arabic = bool(AR_RE.search(name) or AR_RE.search(ar))
            plan = None  # the tags to set
            rule = ""
            good_name, good_ar = tr.mend(name), tr.mend(ar)
            if good_name != name or good_ar != ar:
                plan, rule = {}, "override"
                if good_name != name:
                    plan["name"] = good_name
                if good_ar != ar:
                    plan["name:ar"] = good_ar
            elif AR_RE.search(name) and LAT_RE.search(name):
                got = split_mixed(name)
                if got:
                    plan, rule = {"name": got[0], "name:ar": got[1]}, "mixed"
            elif LAT_RE.search(name) and not AR_RE.search(ar) and not ar:
                got = tr.translate(name)
                if got:
                    plan, rule = {"name:ar": got[0]}, got[1]
            nodes = w.nodes
            mid = nodes[len(nodes) // 2].location if len(nodes) else None
            lat, lon = (mid.lat, mid.lon) if mid is not None and mid.valid() else (0.0, 0.0)
            km = way_km(nodes)
            in_box = OWNER_BOX[0] <= lat <= OWNER_BOX[1] and OWNER_BOX[2] <= lon <= OWNER_BOX[3]
            new = bool(plan and "name:ar" in plan)
            tally("kingdom", arabic, km, new)
            if in_box:
                tally("box", arabic, km, new)
            if plan:
                wanted[w.id] = plan
                rule_ways[rule] += 1
                rule_names[rule].add(name)
                if rule not in ("override", "mixed"):
                    samples[city_of(lat, lon)].setdefault(name, (plan["name:ar"], rule, t.get("highway"), round(lat, 5), round(lon, 5)))
            elif LAT_RE.search(name) and not AR_RE.search(name) and not AR_RE.search(ar):
                stayed[name] += 1
                if in_box:
                    stayed_box[name] += 1

    Look().apply_file(pbf, locations=True, idx="flex_mem")
    print(f"looked at the ways: {len(wanted):,} to rewrite; {time.time() - t0:.0f} s", flush=True)

    fp = osmium.FileProcessor(pbf)
    header = osmium.io.Header()
    src = fp.header
    if src.box().valid():
        header.add_box(src.box())
    header.set("generator", "AMRI tools/mapbuild/arabic_names.py with pyosmium")
    stamp = src.get("osmosis_replication_timestamp", "")
    if stamp:
        header.set("osmosis_replication_timestamp", stamp)
    n = 0
    tolls = 0
    with osmium.SimpleWriter(out, overwrite=True, header=header) as wr:
        for o in fp:
            if o.is_node():
                if o.tags.get("barrier") == "toll_booth" or o.tags.get("highway") == "toll_gantry":
                    la, lo = o.location.lat, o.location.lon
                    if not (CAUSEWAY[0] <= la <= CAUSEWAY[1] and CAUSEWAY[2] <= lo <= CAUSEWAY[3]):
                        tags = {x.k: x.v for x in o.tags if not (x.k == "highway" and x.v == "toll_gantry")}
                        tags["barrier"] = "checkpoint"
                        wr.add_node(o.replace(tags=tags))
                        tolls += 1
                        continue
                wr.add_node(o)
            elif o.is_way():
                plan = wanted.get(o.id)
                if plan is None:
                    wr.add_way(o)
                else:
                    tags = {x.k: x.v for x in o.tags}
                    tags.update(plan)
                    wr.add_way(o.replace(tags=tags))
                    n += 1
            elif o.is_relation():
                wr.add_relation(o)
    print(f"written, {n:,} ways changed, {tolls} toll booths outside the causeway made checkpoints; {time.time() - t0:.0f} s", flush=True)
    rep = {
        "ways_by_rule": dict(rule_ways), "names_by_rule": {k: len(v) for k, v in rule_names.items()}, "toll_booths_made_checkpoints": tolls,
        "kingdom": {k: round(v, 1) for k, v in areas["kingdom"].items()}, "owner_box": {k: round(v, 1) for k, v in areas["box"].items()},
        "latin_left_kingdom_ways": sum(stayed.values()), "latin_left_kingdom_names": len(stayed), "latin_left_box_ways": sum(stayed_box.values()), "latin_left_box_names": len(stayed_box),
        "latin_left_top": stayed.most_common(200), "latin_left_box_top": stayed_box.most_common(200),
    }
    if report_path:
        with open(report_path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(rep, f, ensure_ascii=False, indent=1)
    if sample_path:
        write_sample(samples, sample_path)
    print(json.dumps({k: rep[k] for k in ("ways_by_rule", "names_by_rule", "kingdom", "owner_box", "latin_left_kingdom_ways", "latin_left_kingdom_names", "latin_left_box_ways", "latin_left_box_names")}, ensure_ascii=False))


def write_sample(samples, path, total=300):
    """Random rewritten names, about the same number from each city (fewer where there are fewer), so that a reader sees every town's way of naming."""
    rng = random.Random(SEED)
    cities = sorted(samples)
    want = max(1, total // len(cities))
    chosen = []
    left = []
    for c in cities:
        rows = sorted(samples[c].items())
        rng.shuffle(rows)
        chosen += [(c, *r) for r in rows[:want]]
        left += [(c, *r) for r in rows[want:]]
    rng.shuffle(left)
    chosen += left[:max(0, total - len(chosen))]
    chosen.sort(key=lambda r: (r[0], r[1]))
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("city\tLatin name in OSM\tArabic written by the build\trule\thighway\tlat\tlon\n")
        for c, name, (ar, rule, hw, lat, lon) in chosen:
            f.write(f"{c}\t{name}\t{ar}\t{rule}\t{hw}\t{lat}\t{lon}\n")
    print(f"sample: {len(chosen)} rows, {collections.Counter(r[0] for r in chosen).most_common()}", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("what", choices=["learn", "apply", "try"])
    ap.add_argument("input")
    ap.add_argument("output", nargs="?")
    ap.add_argument("--report")
    ap.add_argument("--sample")
    ap.add_argument("--latin-names")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    if a.what == "learn":
        learn(a.input, a.latin_names)
    elif a.what == "apply":
        apply(a.input, a.output, a.report, a.sample)
    else:
        print(Translator().translate(a.input))


if __name__ == "__main__":
    main()
