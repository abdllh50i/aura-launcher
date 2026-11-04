#!/usr/bin/env python
"""The reference for AMRI's offline place search: normalisation, the query plan, the FTS4 strings, the score.

SEARCH_SPEC.md says the same in words. The Kotlin side must give the same answers on tests/search_tests.json and
tests/norm_vectors.json, so everything a query needs is here and nothing else: index-time logic lives in build_places.py.

  python search_ref.py places.db "مطعم البيك الخبر" --near 26.29,50.20
  python search_ref.py places.db --reverse 26.29,50.20
  python search_ref.py places.db --test tests/search_tests.json [--golden out.json]

Standard library only, the same SQL as the app, readable by SQLite 3.22 with FTS4.
"""
import argparse
import json
import math
import os
import re
import sqlite3
import sys
import unicodedata

NORM_VERSION = 3
SK = "§"  # a token that starts with this is a consonant skeleton
CT = "¤"  # a token that starts with this is a category id
PL = "¶"  # a token that starts with this is the consonant skeleton of a word of a town or district's name
ROLE_ROAD, ROLE_OPTIONAL, ROLE_STOP, ROLE_DISTRICT, ROLE_SPECIFIC, ROLE_FILLER, ROLE_LETTER, ROLE_ALIAS = 1, 2, 3, 4, 5, 6, 7, 8

APOSTROPHES = "'’‘ʼʻ`´"
FOLD = {
    "ٱ": "ا",  # wasla alef
    "ى": "ي", "ی": "ي", "ې": "ي",  # alef maksura, Persian ya
    "ة": "ه", "ۃ": "ه", "ھ": "ه", "ہ": "ه", "ە": "ه",  # ta marbuta, other ha forms
    "ک": "ك", "ڪ": "ك",  # keheh
    "ڤ": "ف",  # veh
    "گ": "ق", "ڨ": "ق",  # gaf: said and written for qaf in the Gulf
    "پ": "ب",  # peh
    "چ": "ج",  # tcheh
    "ژ": "ز",  # jeh
    "ں": "ن", "ٹ": "ت", "ڈ": "د", "ڑ": "ر",
    "ء": "",  # bare hamza
}
for _i in range(10):
    FOLD[chr(0x0660 + _i)] = str(_i)  # Arabic-Indic digits
    FOLD[chr(0x06F0 + _i)] = str(_i)  # Persian digits


def norm(s):
    """Comparable text: lower case, no marks, one form of each letter, ASCII digits, words split by single spaces, and
    split where the script changes between Arabic and anything else."""
    out = []
    prev_ar = None
    for c in unicodedata.normalize("NFKD", s.lower()):
        if c in APOSTROPHES:
            continue
        t = unicodedata.category(c)
        if t in ("Mn", "Me", "Cf") or c == "ـ":
            continue
        c = FOLD.get(c, c)
        if c == "":
            continue
        if t[0] in "LN":
            ar = "؀" <= c <= "ۿ"
            if prev_ar is not None and ar != prev_ar:
                out.append(" ")
            out.append(c)
            prev_ar = ar
        else:
            out.append(" ")
            prev_ar = None
    return " ".join("".join(out).split())


def is_ar(w):
    return "؀" <= w[0] <= "ۿ" if w else False


WORD_CANON = {"بو": "ابو", "abou": "abu", "abo": "abu", "abdul": "abd", "abdel": "abd", "abdal": "abd", "abdol": "abd", "abdu": "abd", "um": "umm"}


def base(w):
    """A word without its Arabic article, in its one spelling. The index holds these, so a query does too."""
    w = WORD_CANON.get(w, w)
    if is_ar(w):
        if w == "الله":
            return w
        if w.startswith("ال") and len(w) >= 4:
            return w[2:]
        if w.startswith("لل") and len(w) >= 5:
            return w[2:]
    return w


def latin_base(w):
    """A Latin word without its article (Alkhobar, Ar-Riyadh written together), for skeletons and the index's second form."""
    if len(w) >= 5 and w[:2] in ("al", "el"):
        return w[2:]
    if len(w) >= 6 and w[0] == "a":
        for d in ("sh", "dh", "th"):
            if w[1:3] == d and w[3:5] == d:
                return w[3:]
        if w[1] == w[2] and w[1] in "rsdtnz":
            return w[2:]
    return w


ABD_LATIN = re.compile(r"^(?:abdul|abdel|abdal|abdol|abdur|abdus|abdu)(.{3,})$")


def query_words(text):
    """The words of a text as the index holds them: norm, base, and joined names apart ("عبدالعزيز" is عبد + عزيز, "Abdulaziz" is abd + aziz,
    "ابوبكر" is ابو + بكر), so that a name written either way finds the other."""
    out = []
    spelled = 0  # how many single Latin letters the last word of out was built from
    for w in norm(text).split():
        b = base(w)
        if is_ar(b):
            if b.startswith("عبد") and len(b) > 4:
                out.append("عبد")
                out.append(base(b[3:]))
                continue
            if b.startswith("ابو") and len(b) >= 6:
                out.append("ابو")
                out.append(base(b[3:]))
                continue
        else:
            m = ABD_LATIN.match(b)
            if m:
                out.append("abd")
                out.append(latin_base(m.group(1)))
                continue
        # single Latin letters belong together: H&M, K.F.C
        if len(b) == 1 and b.isalpha() and b.isascii() and out and out[-1].isascii() and out[-1].isalpha() and len(out[-1]) == spelled + 0 and spelled > 0:
            out[-1] += b
            spelled += 1
            continue
        spelled = 1 if len(b) == 1 and b.isalpha() and b.isascii() else 0
        out.append(b)
    return out


SK_AR = {
    "ب": "b", "ت": "t", "ث": "t", "ط": "t", "ج": "k", "ق": "k", "ك": "k", "خ": "k", "غ": "k", "ح": "h", "ه": "h",
    "د": "d", "ذ": "d", "ض": "d", "ظ": "d", "ز": "d", "ر": "r", "س": "s", "ش": "s", "ص": "s", "ف": "f", "ل": "l", "م": "m", "ن": "n",
}
SK_LATIN_2 = {"kh": "k", "gh": "k", "ck": "k", "sh": "s", "ch": "s", "th": "t", "dh": "d", "ph": "f"}
SK_LATIN_1 = {"b": "b", "d": "d", "f": "f", "g": "k", "h": "h", "j": "k", "k": "k", "l": "l", "m": "m", "n": "n", "p": "b", "q": "k", "r": "r", "s": "s", "t": "t", "v": "f", "z": "d"}


def skeleton(w):
    """The consonants of one word in one alphabet, so that an Arabic word and its Latin spelling land on the same letters.
    Expects norm() and base() or latin_base() to have run. Words with digits have none."""
    out = []
    i = 0
    n = len(w)
    if is_ar(w):
        for c in w:
            if c.isdigit():
                return ""
            k = SK_AR.get(c)
            if k:
                out.append(k)
    else:
        prev = ""
        while i < n:
            c = w[i]
            if c.isdigit():
                return ""
            two = w[i:i + 2]
            k = SK_LATIN_2.get(two)
            if k:
                i += 2
            else:
                i += 1
                if c == "c":
                    k = "s" if i < n and w[i] in "eiy" else "k"
                elif c == "x":
                    if prev != "k":
                        out.append("k")
                    k = "s"
                else:
                    k = SK_LATIN_1.get(c, "")
                    if not k:
                        prev = ""  # a vowel between two equal consonants keeps both
                        continue
            if k != prev:
                out.append(k)
            prev = k
    if out and out[-1] == "h":
        out.pop()
    return "".join(out)


def skeletons(w):
    """The skeleton of a word, and for a Latin word ending in -at (a ta marbuta written out: Rawdat, Madinat) the one without its t."""
    b = base(w) if is_ar(w) else latin_base(w)
    s = skeleton(b)
    if not s:
        return []
    if not is_ar(w) and b.endswith("at") and len(b) >= 4 and s.endswith("t") and len(s) >= 2:
        return [s, s[:-1]]
    return [s]


def lev1(a, b):
    """True when a and b differ by at most one edit."""
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


class Syn:
    """Table syn: normalised word or phrase to categories, role and canonical word."""

    __slots__ = ("words", "max_len", "airport", "by_canon")

    def __init__(self, db):
        self.words = {}
        self.max_len = 1
        self.by_canon = {}
        for w, cats, role, canon in db.execute("SELECT w, cats, role, canon FROM syn ORDER BY w"):
            self.words[w] = ([int(x) for x in cats.split()] if cats else [], role, canon or "")
            self.max_len = max(self.max_len, w.count(" ") + 1)
            if canon and role in (0, ROLE_SPECIFIC) and " " not in w:
                self.by_canon.setdefault(canon, []).append(w)
        row = db.execute("SELECT id FROM cat WHERE key='airport'").fetchone()
        self.airport = row[0] if row else -1

    def get(self, w):
        return self.words.get(w)


class Group:
    """One thing the query asks for: a word or phrase, with the categories it can also mean."""

    __slots__ = ("words", "cats", "role", "prefix", "optional", "sks", "alts")

    def __init__(self, words, cats, role, prefix, optional, alts=()):
        self.words = words
        self.cats = cats
        self.role = role
        self.prefix = prefix
        self.optional = optional
        self.sks = skeletons(words[0]) if len(words) == 1 else []
        self.alts = alts  # the same word in its other spellings (شاورمه, شاورما)


class Plan:
    __slots__ = ("groups", "required", "road_hint", "district_hint", "fillers", "ws", "typing", "other", "skeleton_from")


def without_li(w, syn):
    """"لاقرب", "لصيدليه", "لمول" as a recogniser writes them: the word with ل in front of it, when that word means something and the whole does not."""
    if len(w) >= 4 and w[0] == "ل" and syn.get(w) is None:
        e = syn.get(w[1:])
        if e is not None and e[1] not in (ROLE_LETTER, ROLE_ALIAS) and (len(w) >= 5 or e[1] != ROLE_STOP):
            return w[1:]
    return w


def plan_query(q, syn):
    """Groups of a query. Words that only a road, a title or a company name carry are optional; stop words vanish; words of speech at either end of the query are optional."""
    typing = not q[-1:].isspace()
    ws = [without_li(w, syn) for w in query_words(q)]
    p = Plan()
    p.groups = []
    p.road_hint = False
    p.district_hint = False
    p.fillers = []
    p.ws = ws
    p.typing = typing
    p.other = ""  # what kind of other reading this is, "" for the query as written
    p.skeleton_from = 0  # with a value: the first stages also take skeletons of at least so many letters
    i = 0
    n = len(ws)
    while i < n:
        hit = None
        for ln in range(min(syn.max_len, n - i), 0, -1):
            e = syn.get(" ".join(ws[i:i + ln]))
            if e is not None and e[1] not in (ROLE_LETTER, ROLE_ALIAS):
                hit = (ln, e)
                break
        if hit is None:
            last = i == n - 1
            p.groups.append(Group([ws[i]], [], 0, last and typing, False))
            i += 1
            continue
        ln, (cats, role, canon) = hit
        words = ws[i:i + ln]
        last = i + ln == n
        if role == ROLE_STOP:
            i += ln
            continue
        if role == ROLE_ROAD:
            p.road_hint = True
        if role == ROLE_DISTRICT:
            p.district_hint = True
        alts = ()
        if canon and role in (0, ROLE_SPECIFIC) and ln == 1:
            alts = [x for x in syn.by_canon.get(canon, ()) if x != words[0]]
        p.groups.append(Group(words, cats, role, last and typing and ln == 1, role in (ROLE_ROAD, ROLE_OPTIONAL, ROLE_DISTRICT), alts))
        i += ln
    # words of speech: optional at the ends of the query, plain words in the middle
    first = 0
    while first < len(p.groups) and p.groups[first].role == ROLE_FILLER:
        first += 1
    end = len(p.groups)
    while end > first and p.groups[end - 1].role == ROLE_FILLER:
        end -= 1
    for j, g in enumerate(p.groups):
        if g.role == ROLE_FILLER:
            if j < first or j >= end:
                g.optional = True
                p.fillers.append(g)
            else:
                g.role = 0
    # a single letter being typed says nothing yet
    p.groups = [g for g in p.groups if not (g.prefix and len(g.words[0]) < 2 and not g.words[0].isdigit() and len(g.words) == 1)]
    p.required = [g for g in p.groups if not g.optional]
    if not p.required:  # "طريق" alone: what was optional is all there is
        p.required = p.groups
    return p


def _term(w, prefix):
    star = prefix or (len(w) >= 3 and not w.isdigit())
    return w + "*" if star else w


def fts_string(plan, loose_from=0, in_names=False, no_cats=False):
    """The MATCH string. Standard query syntax: OR binds tighter than the implicit AND.
    loose_from: also the word's consonants, from a skeleton of so many letters.
    in_names: every word must be in the name. FTS4 cannot put a column on a quoted phrase, so a phrase stays as it is.
    no_cats: the words themselves, without the kinds of place they also mean."""
    parts = []
    single = in_names or len(plan.required) == 1
    for g in plan.required:
        alts = []
        if len(g.words) == 1:
            w = g.words[0]
            alts.append(_term(w, g.prefix))
            if not is_ar(w) and latin_base(w) != w:  # "alkhobar" also finds "Al Khobar"
                alts.append(_term(latin_base(w), g.prefix))
            for x in g.alts:
                alts.append(_term(x, False))
        else:
            alts.append('"' + " ".join(g.words) + '"')
        if not no_cats:
            for c in g.cats:
                alts.append(CT + str(c))
        loose = loose_from or plan.skeleton_from
        if loose:
            for i, s in enumerate(g.sks):
                if len(s) >= loose:
                    alts.append(SK + (s + "*" if len(s) >= 3 and i == 0 else s))
        if single:  # a word alone must be in the name, not only in the area: "الخبر" is the town, not every shop in it
            alts = ["k:" + a if not a.startswith('"') else a for a in alts]
        parts.append(" OR ".join(alts))
    return " ".join(parts)


# letters that sound or are written alike here: one standing for another is the commonest slip in a recogniser's Arabic
SOUND_ALIKE = ("تثط", "سصث", "دذضظز", "قكجغ", "هح", "اع")


def one_edit_forms(w):
    """The spellings one letter from a word that an index can be asked for: with a letter less, and with a letter swapped for one that sounds alike."""
    out = []
    if len(w) >= 4:
        for i in range(len(w)):
            out.append(w[:i] + w[i + 1:])
    elif len(w) == 3 and w[1] in "اوي":  # "هات" for هت
        out.append(w[0] + w[2])
    if is_ar(w):
        for i, c in enumerate(w):
            for group in SOUND_ALIKE:
                if c in group:
                    out += [w[:i] + x + w[i + 1:] for x in group if x != c]
        if len(w) <= 7:  # a long vowel left out: "دنكن" for دانكن
            for i in range(1, len(w)):
                out += [w[:i] + x + w[i:] for x in "اوي"]
    return list(dict.fromkeys(x for x in out if len(x) >= 2))


def fts_edit_string(plan):
    """For a query that found little: the words one letter away from each word, and all that begin with its first three letters; what comes back is checked by edit_ok.
    ("ايثرا" finds "اثراء"; "النهضي" finds "النهدي"; "بيتزا هات" finds "بيتزا هت".)"""
    parts = []
    for g in plan.required:
        for w in g.words:
            alts = []
            if len(w) >= 3 and not w.isdigit():
                if len(w) >= 4:
                    alts.append(w[:3] + "*")
                else:
                    alts.append(_term(w, g.prefix))
                alts += one_edit_forms(w)
            elif len(g.words) == 1:
                alts.append(_term(w, g.prefix))
            if not alts:
                continue
            if len(g.words) == 1:
                alts += [CT + str(c) for c in g.cats]
            parts.append(" OR ".join("k:" + a for a in alts))
    return " ".join(parts)


def edit_ok(plan, r):
    """Is a row from fts_edit_string what was meant: every required word is one edit from a word of its name, or starts one, or says its kind."""
    names = set(word_set(r[2])) | set(word_set(r[3]))
    for g in plan.required:
        if r[4] in g.cats:
            continue
        for w in g.words:
            if not any(lev1(w, n) or n.startswith(w) for n in names):
                return False
    return True


def holds_word(g, names, syn):
    """Is the group's word in the names, in this or another spelling of it?"""
    if all(w in names for w in g.words):
        return True
    return bool(g.alts) and any(x in names for x in g.alts)


def fts_all_words(plan):
    """Every word of a reading, the optional ones too, in the name."""
    return " ".join("k:" + w for w in plan.ws if (w not in ("ال",)) and not w.isdigit())


def place_ok(plan, r):
    """Is a town or district found by its consonants the word that was typed? In the script of the query it must be one edit from a word of the name; only a row with no name in that script
    goes by the consonants. (Al Malqa is Al Malga. ارامكو is not اليرموك, nor KFC Al Khafji.)"""
    w = plan.required[0].words[0]
    names = list(word_set(r[2])) + list(word_set(r[3]))
    same = [n for n in names if is_ar(n) == is_ar(w)]
    if same:
        return any(n == w or lev1(w, n) or (latin_base(n) == latin_base(w)) or lev1(latin_base(w), latin_base(n)) for n in same)
    return True


def place_string(plan):
    """A word alone may be a town or district written another way ("Al Malqa", "Al Malga District"): its consonants, among places only, whatever else shares them."""
    if len(plan.required) != 1 or plan.required[0].cats or len(plan.required[0].words) != 1:
        return None
    alts = ["k:" + PL + s for s in plan.required[0].sks if len(s) >= 3]
    return " OR ".join(alts) or None


NEAREST = 150
BEST = 50
BAND = 1.0  # degrees of latitude either side of the car, about 110 km
COLUMNS = "p.id,p.kind,p.name,p.alt,p.cat,p.lat,p.lon,p.area,p.rank,p.src"
SRC_COARSE = 4  # bit of p.src: the position is a geocoder's default (many rows on one point), about right at best


def id_band(db, lat):
    """Ids run south to north, so a band of latitude is a range of ids, which FTS4 can skip to without reading the rows."""
    a = db.execute("SELECT id FROM p WHERE lat>=? ORDER BY lat LIMIT 1", (int((lat - BAND) * 1e6),)).fetchone()
    b = db.execute("SELECT id FROM p WHERE lat<=? ORDER BY lat DESC LIMIT 1", (int((lat + BAND) * 1e6),)).fetchone()
    return (a[0], b[0]) if a and b else None


def nearest_sql(near):
    la = int(near[0] * 1e6)
    lo = int(near[1] * 1e6)
    k = math.cos(math.radians(near[0])) ** 2
    return f"ORDER BY (p.lat-{la})*(p.lat-{la})+(p.lon-{lo})*(p.lon-{lo})*{k:.6f} LIMIT {NEAREST}"


def wants_best(plan, syn):
    """Does a far row have a chance against 150 near ones? Not when only a kind of place is asked for, unless it is an airport.
    This saves most of the time of the commonest searches (مسجد, مطعم)."""
    if plan.required and all(g.cats for g in plan.required):
        return any(syn.airport in g.cats for g in plan.required)
    return True


def candidates(db, match, near, want_best=True):
    """Up to NEAREST nearest and BEST best-ranked rows answering a MATCH string."""
    rows = {}
    base_sql = f"SELECT {COLUMNS} FROM f CROSS JOIN p ON p.id=f.docid WHERE f MATCH ? "
    if near:
        order = nearest_sql(near)
        got = []
        band = id_band(db, near[0])
        if band:
            got = db.execute(base_sql + f"AND f.docid BETWEEN {band[0]} AND {band[1]} " + order, (match,)).fetchall()
        if len(got) < NEAREST:
            got = db.execute(base_sql + order, (match,)).fetchall()
        for r in got:
            rows[r[0]] = r
        if len(got) == NEAREST and want_best:
            for r in db.execute(base_sql + f"ORDER BY p.rank DESC, p.id LIMIT {BEST}", (match,)):
                rows[r[0]] = r
    else:
        for r in db.execute(base_sql + f"ORDER BY p.rank DESC, p.id LIMIT {NEAREST + BEST}", (match,)):
            rows[r[0]] = r
    return rows


def areas_of(db, rows):
    ids = sorted({r[7] for r in rows.values() if r[7]})
    out = {}
    for i in range(0, len(ids), 400):
        part = ids[i:i + 400]
        for a in db.execute(f"SELECT id,ar,en,city_ar,city_en FROM area WHERE id IN ({','.join(map(str, part))})"):
            out[a[0]] = a
    return out


def km_between(la1, lo1, la2, lo2):
    p1 = math.radians(la1)
    p2 = math.radians(la2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lo2 - lo1) / 2) ** 2
    return 12742.0 * math.asin(min(1.0, math.sqrt(a)))


def word_set(name):
    return query_words(name) if name else []


EXACT_WEIGHT = 22.0
KIND_WEIGHT = {1: 8.0, 2: 30.0, 3: 22.0, 4: 10.0, 5: 14.0, 6: 10.0, 7: 8.0}
KIND_REGION, KIND_CITY, KIND_TOWN, KIND_VILLAGE, KIND_DISTRICT, KIND_POI, KIND_ROAD = 1, 2, 3, 4, 5, 6, 7


def group_quality(g, names, name_sks, area_words, area_sks, cat, syn, cat_query=False):
    """How well a row answers one group, 0..1: what the driver can see counts more than what only the index knew.
    In a query that only asks for a kind of place, being that kind is most of the answer, and the word in the name as well is all of it.
    A word for one thing within a kind (role 5: شاورما, كرك, سمسا) works the other way: the name first, the kind only when no name has it."""
    if cat_query:
        in_cat = cat in g.cats
        named = holds_word(g, names, syn)
        if in_cat and named:
            return 1.0
        if in_cat:
            return 0.4 if g.role == 5 else 0.9
        if named:
            return 0.95 if g.role == 5 else 0.8
        return min(0.7, group_quality(g, names, name_sks, area_words, area_sks, cat, syn))
    if len(g.words) > 1:
        if all(w in names for w in g.words):
            return 1.0
        if cat in g.cats:
            return 0.8
        if all(w in area_words for w in g.words):
            return 0.6
        return 0.5
    w = g.words[0]
    if w in names or (not is_ar(w) and latin_base(w) in names):
        return 1.0
    canon = syn.get(w)
    if canon and canon[2]:
        for n in names:
            e = syn.get(n)
            if e and e[2] == canon[2]:
                return 1.0
    best = 0.0
    if len(w) >= 2:
        for n in names:
            if n.startswith(w):
                best = max(best, 0.8 + 0.09 * len(w) / len(n))
    if cat in g.cats:
        best = max(best, 0.8)
    if len(w) >= 4:  # one letter off is closer than the same consonants: العتيم is العثيم before it is إطعام, and a letter swapped is closer than a letter missing
        for n in names:
            if lev1(w, n):
                best = max(best, 0.9 if len(n) == len(w) else 0.86)
    for s in g.sks:
        if len(s) >= 2:
            if s in name_sks:
                # the same consonants: a long word in another spelling or script is that word (Jubail, Jubayl; ستاربكس, Starbucks)
                other_script = is_ar(w) != any(is_ar(n) for n in names)
                best = max(best, 0.92 if len(s) >= 3 else 0.85 if other_script else 0.75)
            elif g.prefix and len(s) >= 3 and any(x.startswith(s) for x in name_sks):
                best = max(best, 0.65)
    if best >= 0.75:
        return best
    if w in area_words:
        return max(best, 0.6)
    if len(w) >= 2 and any(n.startswith(w) for n in area_words):
        return max(best, 0.55)
    for s in g.sks:
        if len(s) >= 2 and s in area_sks:
            return max(best, 0.55)
    return max(best, 0.5)


def score_row(r, plan, near, area, syn):
    """The score of one candidate. Same formula in Kotlin; ties go to the lower id."""
    rid, kind, name, alt, cat, lat, lon, area_id, rank = r[:9]
    n1 = word_set(name)
    n2 = word_set(alt)
    names = set(n1) | set(n2)
    names |= {latin_base(w) for w in names if not is_ar(w)}
    name_sks = set()
    for w in names:
        name_sks.update(skeletons(w))
    area_words = set()
    area_sks = set()
    names_town = False
    if area:
        for part in area[1:5]:
            for w in word_set(part):
                area_words.add(w)
                area_sks.update(skeletons(w))
        if len(plan.required) > 1:
            asked_words = {w for g in plan.required for w in g.words}
            for part in area[3:5]:  # the query holds the whole name of the row's town
                town = [w for w in word_set(part) if (syn.get(w) or (0, 0))[1] != 3]
                if town and all(w in asked_words for w in town):
                    names_town = True
    cat_query = bool(plan.required) and all(g.cats for g in plan.required)
    qual = [group_quality(g, names, name_sks, area_words, area_sks, cat, syn, cat_query) for g in plan.required]

    def says_where(g, q):
        """The group only says where: its word is in the row's district or town, and not in its name."""
        return q < 0.75 and any(w in area_words or (len(w) >= 2 and any(a.startswith(w) for a in area_words)) for w in g.words)

    where = [says_where(g, q) for g, q in zip(plan.required, qual)]
    named_q = [q for q, x in zip(qual, where) if not x]
    t = sum(named_q) / len(named_q) if named_q else (sum(qual) / len(qual) if qual else 0.0)
    # how much of a name the query covers, in the script it covers best
    asked = set()
    ask_sks = set()
    for g in plan.groups:
        asked.update(g.words)
        asked.update(latin_base(w) for w in g.words if not is_ar(w))
        ask_sks.update(g.sks)

    ask_roles = {w for w in plan.ws if (syn.get(w) or (0, 0))[1] == ROLE_STOP}

    def covered(words):
        if not words:
            return 0.0
        hit = 0
        for w in words:
            if w in asked or any(a.startswith(w) or w.startswith(a) for a in asked if len(a) >= 2):
                hit += 1
            elif any(s in ask_sks for s in skeletons(w) if len(s) >= 2):
                hit += 1
            else:
                e = syn.get(w)
                # road words and titles do not make a name longer, nor do a word that says the row's own kind and its own town: "Nahdi pharmacy" is as much النهدي as "Nahdi"
                if e is not None and (e[1] in (ROLE_ROAD, ROLE_OPTIONAL, ROLE_STOP, ROLE_DISTRICT) or cat in e[0]):
                    hit += 1
                elif w in area_words:
                    hit += 1
        return hit / len(words)

    c = max(covered(n1), covered(n2))
    # the name answers: the row is of the kind asked for, and every word that is not there for the kind or for the area is in the name, in full.
    # "مطعم البيك الخبر": البيك, a restaurant. "مطار": a street called طريق المطار is no answer of this sort
    in_name = [q for g, q, x in zip(plan.required, qual, where) if not g.cats and not x]
    of_kind = all(cat in g.cats for g in plan.required if g.cats)
    exact = 1.0 if c >= 1.0 and of_kind and in_name and min(in_name) >= 0.9 else 0.0
    rank_weight = 15.0
    if cat_query:  # "صيدلية": the nearest good pharmacy, not the one that happens to be called only that
        c = 0.0
        exact = 0.0
        rank_weight = 30.0
    s = 55.0 * t + 15.0 * c + EXACT_WEIGHT * exact + rank_weight * rank / 100.0 + KIND_WEIGHT.get(kind, 8.0)
    if plan.road_hint and kind == KIND_ROAD:
        s += 20.0
    if plan.district_hint and kind == KIND_DISTRICT:
        s += 40.0
    km = None
    if near:
        km = km_between(near[0], near[1], lat / 1e6, lon / 1e6)
        away = min(75.0, 11.0 * math.log(1.0 + km))
        if exact and kind in (KIND_CITY, KIND_TOWN) and not plan.road_hint:  # a town's name asks for the town, however far: not for a street named after it,
            if km < 150.0 or all(any(w == n or lev1(w, n) for n in names) for g in plan.required for w in g.words):  # and not a far town with only the same consonants (KFC, Al Khafji)
                away = min(away, 6.0)
        if exact and kind == KIND_DISTRICT and km < 60.0:  # a district's whole name asks for the district, before a street called after it; not for one in another town
            away = min(away, 18.0)
        if plan.other == "alias":  # another name of one known thing: how far it is says little, and a row with all of that name is the thing
            away = min(away, 15.0)
            if all(w in names or w in ask_roles for w in plan.ws):
                s += 30.0
        if cat_query and cat == syn.airport:  # one drives far to an airport: the real one before a check-in spot that is nearer
            away *= 0.3
        if names_town:
            away = min(away, 15.0)  # "مطار الدمام": the driver said which town, so how far it is from him says little
        s -= away
    return s, km, t


def display(r, area, cats, arabic=True):
    """(name, detail) for the result list. A search-only spelling can never get here: only p.name and p.alt are shown."""
    rid, kind, name, alt, cat, lat, lon, area_id, rank = r[:9]
    main = name
    if alt:
        name_ar = bool(name) and any("؀" <= c <= "ۿ" for c in name)
        if arabic != name_ar:
            main = alt
    parts = []
    c = cats.get(cat)
    if c and kind in (KIND_POI, KIND_ROAD, KIND_DISTRICT):
        parts.append(c[1] if arabic else c[2])
    if area:
        na = area[1] if arabic else area[2] or area[1]
        ci = area[3] if arabic else area[4] or area[3]
        for x in (na, ci):
            if x and x != main and x not in parts:
                parts.append(x)
    return main, ("، " if arabic else ", ").join(parts)


def dedupe(scored):
    """One place once: the same name, in either of a row's two names, within 150 m, or the same road name in one town, is one result (the best)."""
    out = []
    seen = []
    for item in scored:
        r = item["row"]
        key = {" ".join(sorted(word_set(r[2])))}
        if r[3]:
            key.add(" ".join(sorted(word_set(r[3]))))
        dup = False
        for key2, r2 in seen:
            if not key & key2:
                continue
            if r[1] == KIND_ROAD and r2[1] == KIND_ROAD and (r[7] == r2[7] or km_between(r[5] / 1e6, r[6] / 1e6, r2[5] / 1e6, r2[6] / 1e6) < 5.0):
                dup = True
                break
            if km_between(r[5] / 1e6, r[6] / 1e6, r2[5] / 1e6, r2[6] / 1e6) < 0.15:
                dup = True
                break
        if not dup:
            out.append(item)
            seen.append((key, r))
    return out


def first_stages(plan):
    """The MATCH strings every search runs, in order."""
    out = []
    plain = fts_string(plan)
    if plan.fillers:
        # "روح الشرق", "ابي طالب": the rows that hold the words of speech too, exactly and in the name
        strict = [fts_string(plan, in_names=True)] + ["k:" + g.words[0] for g in plan.fillers if len(g.words) == 1]
        out.append(" ".join(strict))
    if any(g.role == ROLE_SPECIFIC for g in plan.required):
        # شاورما: the shops called that, or the many near restaurants would crowd them out of the candidates
        out.append(fts_string(plan, in_names=True, no_cats=True))
    if plan.other == "alias" and len(plan.ws) > 1:
        out.append(fts_all_words(plan))
    if len(plan.required) > 1:
        # the rows that hold all the words in their name, so that a thing itself is never crowded out by the many rows that are merely in it
        names = fts_string(plan, in_names=True)
        if names != plain:
            out.append(names)
    out.append(plain)
    return out


def other_plans(plan, syn):
    """Other readings of the same words. A row found by one is scored by the reading that suits it best:
    a known place's other name (جسر البحرين); letters said one by one (كي اف سي is kfc); a name written apart, as a recogniser writes it (ستار بكس, ماك دونالدز, لو لو)."""
    ws = plan.ws
    n = len(ws)
    end = "" if plan.typing else " "
    out = []

    def add(kind, words, skeleton_from=0):
        p = plan_query(" ".join(words) + end, syn)
        if p.required and " ".join(p.ws) not in {" ".join(x.ws) for x in out} and p.ws != ws:
            p.other = kind
            p.skeleton_from = skeleton_from
            out.append(p)

    core = [w for w in ws if (syn.get(w) or (0, 0))[1] not in (ROLE_STOP, ROLE_FILLER)]  # another name is for the whole query, words of speech aside
    e = syn.get(" ".join(core))
    if e is not None and e[1] == ROLE_ALIAS:
        for target in e[2].split("|"):
            add("alias", target.split())
    i = 0
    while i < n:
        j = i
        said = ""
        while j < n:
            e = syn.get(ws[j])
            if e is None or e[1] != ROLE_LETTER:
                break
            said += e[2]
            j += 1
        if j - i >= 2 and len(said) >= 2:
            add("letters", ws[:i] + [said] + ws[j:])
        i = max(j, i + 1)
    plain = {g.words[0] for g in plan.groups if len(g.words) == 1 and not g.cats and g.role in (0, ROLE_FILLER) and not g.words[0].isdigit()}
    for i in range(n - 1):
        a, b = ws[i], ws[i + 1]
        if a in plain and b in plain and is_ar(a) == is_ar(b):
            add("joined", ws[:i] + [a + b] + ws[i + 2:], 4)
    return out[:4]


def search(db, query, near=None, limit=12, syn=None, debug=None):
    """The answer to a query: a list of dicts, best first. near is (lat, lon) in degrees or None."""
    syn = syn or Syn(db)
    plan = plan_query(query, syn)
    if not plan.required:
        return []
    letters = sum(len(w) for g in plan.required for w in g.words)
    if letters < 2 and not (plan.road_hint and plan.required[0].words[0].isdigit()):  # one letter says nothing; "شارع 9" does
        return []
    rows = {}
    stages = first_stages(plan)
    best = wants_best(plan, syn)
    for m in stages:
        rows.update(candidates(db, m, near, best))
    m = stages[-1]
    plans = [plan]
    for other in other_plans(plan, syn):
        hit = False
        for m9 in first_stages(other):
            stages.append(m9)
            got = candidates(db, m9, near, wants_best(other, syn))
            hit = hit or bool(got)
            rows.update(got)
        if hit:
            plans.append(other)
    few = len(rows) < 6
    # few, or none near the car: perhaps it is written another way. What a letter or two apart finds is a nearer branch of the same name more often than another word
    none_near = near is not None and sum(1 for r in rows.values() if abs(r[5] / 1e6 - near[0]) < 0.1 and abs(r[6] / 1e6 - near[1]) < 0.1) < 3
    mp = place_string(plan)
    if mp:
        stages.append(mp)
        for rid, r in candidates(db, mp, near, False).items():
            if place_ok(plan, r):
                rows[rid] = r
    if few or none_near:
        m3 = fts_edit_string(plan)
        if m3 and m3 != m:
            stages.append(m3)
            for rid, r in candidates(db, m3, near, False).items():
                if edit_ok(plan, r):
                    rows[rid] = r
    if few:
        m2 = fts_string(plan, loose_from=3)
        if m2 != m:
            stages.append(m2)
            rows.update(candidates(db, m2, near, False))
    if len(rows) < 3:  # two consonants say little: only when nothing else has been found
        m2 = fts_string(plan, loose_from=2)
        if m2 != m:
            stages.append(m2)
            rows.update(candidates(db, m2, near))
    if debug is not None:
        debug["match"] = stages
        debug["candidates"] = len(rows)
    areas = areas_of(db, rows)
    scored = []
    for rid, r in rows.items():
        s, km, t = max((score_row(r, pl, near, areas.get(r[7]), syn) for pl in plans), key=lambda x: x[0])
        scored.append({"id": rid, "score": round(s, 3), "km": None if km is None else round(km, 2), "row": r, "t": round(t, 3)})
    scored.sort(key=lambda x: (-x["score"], x["id"]))
    scored = dedupe(scored)[:limit]
    cats = {c[0]: c for c in db.execute("SELECT id,ar,en FROM cat")}
    out = []
    for it in scored:
        r = it["row"]
        main, detail = display(r, areas.get(r[7]), cats)
        out.append({"id": it["id"], "kind": r[1], "name": r[2], "alt": r[3], "cat": r[4], "lat": r[5] / 1e6, "lon": r[6] / 1e6, "rank": r[8],
                    "km": it["km"], "score": it["score"], "shown": main, "detail": detail, "approx": bool(r[9] & SRC_COARSE)})
    return out


def reverse(db, lat, lon):
    """What is at a point: the nearest POI within 60 m, else a named road within 40 m (its points are about 1 km apart, so
    within 600 m of a point of it), else the district and town. Returns a dict or None."""
    la = int(lat * 1e6)
    lo = int(lon * 1e6)
    k = math.cos(math.radians(lat))
    d = 7000  # 0.007 degrees, about 780 m, is the widest ring this asks for
    dl = int(d / k)
    rows = db.execute(f"SELECT {COLUMNS} FROM p WHERE p.lat BETWEEN ? AND ? AND p.lon BETWEEN ? AND ? AND p.kind>=5", (la - d, la + d, lo - dl, lo + dl)).fetchall()
    best = {}
    for r in rows:
        if r[9] & SRC_COARSE:  # a pile of rows on a geocoder's default point is not what is at that point
            continue
        m = km_between(lat, lon, r[5] / 1e6, r[6] / 1e6) * 1000.0
        if r[1] not in best or m < best[r[1]][0]:
            best[r[1]] = (m, r)
    poi = best.get(KIND_POI)
    if poi and poi[0] <= 60.0:
        return {"what": "poi", "m": round(poi[0], 1), "id": poi[1][0], "name": poi[1][2], "alt": poi[1][3]}
    road = best.get(KIND_ROAD)
    if road and road[0] <= 600.0:
        return {"what": "road", "m": round(road[0], 1), "id": road[1][0], "name": road[1][2], "alt": road[1][3]}
    # the district and the town: the nearest row of any kind says which area the point is in
    dist = best.get(KIND_DISTRICT)
    nearest = None
    for kd, (m, r) in best.items():
        if r[7] and (nearest is None or m < nearest[0]):
            nearest = (m, r)
    if nearest:
        a = db.execute("SELECT id,ar,en,city_ar,city_en FROM area WHERE id=?", (nearest[1][7],)).fetchone()
        if a:
            return {"what": "area", "m": round(nearest[0], 1), "area": a[0], "ar": a[1], "en": a[2], "city_ar": a[3], "city_en": a[4]}
    if dist:
        return {"what": "district", "m": round(dist[0], 1), "id": dist[1][0], "name": dist[1][2], "alt": dist[1][3]}
    return None


# ------------------------------------------------------------------------------------------------- the test set


def check(case, results, cat_ids):
    """Does a result list satisfy one test? Returns (passed, why)."""
    exp = case.get("expect", {})
    top = exp.get("top", 3)
    within = exp.get("within_km")
    kinds = exp.get("kind")
    if isinstance(kinds, int):
        kinds = [kinds]
    want_names = [norm(x) for x in exp.get("name_any", [])]
    want_cats = [cat_ids.get(c) for c in exp.get("cat_any", [])]
    if exp.get("none"):
        return (not results), f"expected nothing, got {len(results)}"
    if not results:
        return False, "no results"
    only = [cat_ids.get(c) for c in exp.get("all_cat", [])]
    if only:
        bad = [r for r in results[:top] if r["cat"] not in only]
        if bad:
            return False, "not all of the kind: " + " / ".join(f"{r['name']} [c{r['cat']}]" for r in bad)
        if within is None:
            return True, "all of the kind"
    for i, r in enumerate(results[:top]):
        if kinds and r["kind"] not in kinds:
            continue
        if want_cats and r["cat"] not in want_cats:
            continue
        if want_names:
            text = norm((r["name"] or "") + " " + (r["alt"] or ""))
            if not any(w in text for w in want_names):
                continue
        if within is not None and (r["km"] is None or r["km"] > within):
            continue
        return True, f"hit at {i + 1}"
    return False, "not in top %d: %s" % (top, " / ".join(f"{r['name']} [{r['km']}]" for r in results[:top]))


def check_reverse(case, got):
    exp = case.get("expect", {})
    what = got["what"] if got else "none"
    if exp.get("what") and what != exp["what"]:
        return False, f"got {what}: {got}"
    if exp.get("what_any") and what not in exp["what_any"]:
        return False, f"got {what}: {got}"
    if exp.get("name_any"):
        text = norm(((got or {}).get("name") or "") + " " + ((got or {}).get("alt") or ""))
        if not any(norm(x) in text for x in exp["name_any"]):
            return False, f"got {got}"
    return True, "ok"


def run_tests(db, path, golden=None, verbose=True):
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    cases = doc["cases"]
    syn = Syn(db)
    cat_ids = {k: i for i, k in db.execute("SELECT id,key FROM cat")}
    passed = 0
    gold = []
    by_group = {}
    for c in cases:
        near = tuple(c["near"]) if c.get("near") else None
        res = search(db, c["q"], near, syn=syn)
        ok, why = check(c, res, cat_ids)
        passed += ok
        g = by_group.setdefault(c.get("group", ""), [0, 0])
        g[1] += 1
        g[0] += ok
        if verbose and not ok:
            print(f"FAIL [{c.get('group', '')}] {c['q']!r}: {why}")
        gold.append({"q": c["q"], "near": c.get("near"), "ids": [r["id"] for r in res], "scores": [r["score"] for r in res], "ok": bool(ok)})
    print(f"{passed} of {len(cases)} pass")
    for k, (a, b) in sorted(by_group.items()):
        print(f"  {k}: {a}/{b}")
    rev = doc.get("reverse", [])
    rev_ok = 0
    rev_gold = []
    for c in rev:
        got = reverse(db, c["at"][0], c["at"][1])
        ok, why = check_reverse(c, got)
        rev_ok += ok
        rev_gold.append({"at": c["at"], "got": got, "ok": bool(ok)})
        if verbose and not ok:
            print(f"FAIL [reverse] {c['at']}: {why}")
    print(f"reverse: {rev_ok}/{len(rev)}")
    if golden:
        with open(golden, "w", encoding="utf-8") as f:
            json.dump({"search": gold, "reverse": rev_gold}, f, ensure_ascii=False, indent=0)
    return passed, len(cases)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("query", nargs="?")
    ap.add_argument("--near")
    ap.add_argument("--reverse")
    ap.add_argument("--test")
    ap.add_argument("--golden")
    ap.add_argument("--limit", type=int, default=12)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    v = db.execute("SELECT v FROM meta WHERE k='norm_version'").fetchone()
    if not v or int(v[0]) != NORM_VERSION:
        raise SystemExit(f"places.db has norm version {v}, this code {NORM_VERSION}")
    if a.test:
        run_tests(db, a.test, a.golden)
        return
    if a.reverse:
        la, lo = map(float, a.reverse.split(","))
        print(json.dumps(reverse(db, la, lo), ensure_ascii=False))
        return
    near = tuple(map(float, a.near.split(","))) if a.near else None
    dbg = {}
    res = search(db, a.query, near, a.limit, debug=dbg)
    print("match:", dbg.get("match"), "candidates:", dbg.get("candidates"))
    for r in res:
        print(f"{r['score']:8.2f} {str(r['km']):>8} km  k{r['kind']} c{r['cat']:<3} r{r['rank']:<3} {r['shown']}  |  {r['alt'] or ''}  |  {r['detail']}")


if __name__ == "__main__":
    main()
