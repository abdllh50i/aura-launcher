"""Text comparison shared by the lyrics search and the alignment.

The rules are the app's own (app/.../util/TextMatch.kt and CoverSearch's matching), so a song the unit and this server
both look at is judged the same way: lower case, no accents or Arabic marks, one form of alef / ya / ta marbuta, and for
Arabic against Latin spellings ("الأماكن" / "Al Amaken") a consonant skeleton.
"""
import re
import unicodedata

_AR_RANGE = re.compile("[؀-ۿ]")
_MARKS = re.compile("[ؐ-ًؚ-ٰٟۖ-ۭ]")  # harakat, shadda, superscript alef, Quranic marks
_NOT_WORD = re.compile(r"[^\w]+", re.UNICODE)


def is_arabic(s):
    return bool(_AR_RANGE.search(s or ""))


def norm(s):
    """Comparable text: lower case, no accents or Arabic diacritics, one alef / ya / ta marbuta, words split by spaces."""
    t = unicodedata.normalize("NFKD", (s or "").lower())
    t = "".join(ch for ch in t if not unicodedata.combining(ch))
    t = _MARKS.sub("", t)
    t = (t.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ٱ", "ا")
         .replace("ى", "ي").replace("ة", "ه").replace("ـ", ""))
    t = t.replace("_", " ")
    return _NOT_WORD.sub(" ", t).strip()


_AR = {
    "ب": "b", "ت": "t", "ث": "t", "ج": "j", "ح": "h", "خ": "k", "د": "d", "ذ": "z", "ر": "r", "ز": "z", "س": "s",
    "ش": "s", "ص": "s", "ض": "d", "ط": "t", "ظ": "z", "غ": "g", "ف": "f", "ق": "k", "ك": "k", "ل": "l", "م": "m",
    "ن": "n", "ه": "h", "پ": "b", "چ": "s", "گ": "j", "ڤ": "f",
    "ا": None, "و": None, "ي": None, "ع": None, "ء": None, "ؤ": None, "ئ": None,
}


def skeleton(s):
    """The consonants of a [norm]ed name in one alphabet ("عبدالمجيد عبدالله" and "Abdul Majeed Abdullah" both give
    "bdlmjdbdlh"): Arabic letters and their usual Latin spellings land on the same letters, vowels and the letters
    usually written as vowels (ا و ي ع) are dropped, doubles collapsed."""
    out = []

    def add(c):
        if not out or out[-1] != c:
            out.append(c)

    i = 0
    n = len(s)
    while i < n:
        c = s[i]
        nx = s[i + 1] if i + 1 < n else " "
        if c in _AR:
            if _AR[c]:
                add(_AR[c])
        elif c == "k" and nx == "h":
            add("k"); i += 1
        elif c == "g" and nx == "h":
            add("g"); i += 1
        elif c in "sc" and nx == "h":
            add("s"); i += 1
        elif c == "t" and nx == "h":
            add("t"); i += 1
        elif c == "d" and nx == "h":
            add("d"); i += 1
        elif c == "p" and nx == "h":
            add("f"); i += 1
        elif c == "c":
            add("s" if nx in "eiy" else "k")
        elif c == "q":
            add("k")
        elif c == "g":
            add("j")  # ج is written j or (Egyptian) g; غ is gh
        elif c == "x":
            add("k"); add("s")
        elif c == "v":
            add("f")
        elif c == "p":
            add("b")
        elif "a" <= c <= "z" and c not in "aeiouyw":
            add(c)
        i += 1
    return "".join(out)


def lev(a, b):
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        ai = a[i - 1]
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (0 if ai == b[j - 1] else 1))
        prev = cur
    return prev[len(b)]


def overlap(a, b):
    x = set(w for w in a.split(" ") if w)
    y = set(w for w in b.split(" ") if w)
    if not x or not y:
        return 0.0
    return len(x & y) / min(len(x), len(y))


_DECOR = re.compile(r"[(\[{][^)\]}]*[)\]}]")
_FEAT = re.compile(r"(?i)\s+(feat\.?|ft\.?|featuring|with)\s+.*$")
_JUNK = re.compile(r"(?i)\b(official( music)? (video|audio)|lyric video|lyrics|audio|video ?clip|hd|4k|remaster(ed)?( \d{4})?)\b")
_AR_JUNK = re.compile("(فيديو كليب|كليب|حصريا|حصرياً|بالكلمات|كلمات|النسخه الاصليه|النسخة الأصلية|اغنيه|أغنية|جلسات)")


def clean(s):
    """What a phone or a catalogue often adds to a name: "(Official Video)", "[Lyrics]", "feat. …", "حصرياً"."""
    t = _DECOR.sub(" ", s or "")
    t = _FEAT.sub(" ", t)
    t = _JUNK.sub(" ", t)
    t = _AR_JUNK.sub(" ", t)
    return re.sub(r"\s+", " ", t).strip(" -–—|·")


def same(query, found, words):
    """[found] names what [query] names: equal, one starting the other, enough words in common ([words] of the
    shorter), or across scripts the same consonants."""
    q = norm(query)
    r = norm(found)
    if not q or not r:
        return False
    if q == r or r.startswith(q) or q.startswith(r) or overlap(q, r) >= words:
        return True
    if is_arabic(q) == is_arabic(r):
        # same script: a long name inside a longer one ("Hello" in "Hello - Live at the BBC" was caught above)
        return False
    a = skeleton(q)
    b = skeleton(r)
    if len(a) < 2 or len(b) < 2:
        return False
    shorter = min(len(a), len(b))
    if (a in b or b in a) and shorter >= 3 and shorter >= 0.6 * max(len(a), len(b)):
        return True
    return 1.0 - lev(a, b) / max(len(a), len(b)) >= 0.75


def similarity(a, b):
    """How alike two names are, 0..1: by words in one script, by consonants across scripts."""
    x = norm(a)
    y = norm(b)
    if not x or not y:
        return 0.0
    if x == y:
        return 1.0
    if is_arabic(x) != is_arabic(y):
        p = skeleton(x)
        q = skeleton(y)
        if len(p) < 2 or len(q) < 2:
            return 0.0
        sim = 1.0 - lev(p, q) / max(len(p), len(q))
        if (p in q or q in p) and min(len(p), len(q)) >= 3:
            sim = max(sim, 0.8)
        return sim * 0.95
    if x.startswith(y) or y.startswith(x):
        return 0.95
    if x in y or y in x:
        return 0.9
    return overlap(x, y) * 0.85


# ---------------------------------------------------------------------------------------------- words of the lyrics
_AR_PREFIXES = ("وال", "فال", "بال", "كال", "لل", "ال", "و", "ف", "ب", "ل", "ك")


_APOSTROPHE = re.compile("['’‘`´]")


def word_norm(w):
    """A sung or written word for comparison: [norm] without apostrophes ("don't" = "dont"), ؤ → و, ئ → ي."""
    t = norm(_APOSTROPHE.sub("", w or "")).replace(" ", "")
    return t.replace("ؤ", "و").replace("ئ", "ي").replace("ء", "")


def word_stem(w):
    """The word without the prefixes that lyrics and a recogniser split or join differently (و ف ب ل ك ال)."""
    if len(w) > 3 and is_arabic(w):
        for p in _AR_PREFIXES:
            if w.startswith(p) and len(w) - len(p) >= 2:
                return w[len(p):]
    return w


def words(text):
    """The comparable words of a line."""
    return [w for w in (word_norm(x) for x in norm(_APOSTROPHE.sub("", text or "")).split(" ")) if w]
