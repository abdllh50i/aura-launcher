"""Latin names in Arabic letters, for search only: a wrong guess must never reach the screen, and it does not, because these
forms only ever go into the index, never into p.name or p.alt.

Rule based. No model: a transliteration model on the PC's GPU would mean a download that was not on the approved list, so it
stays a later option. tests/test_translit.py and the build's log say how often the rules give a real spelling.

Two forms per word, because Arabic writes foreign names and Arabic names differently: a "full" form with every vowel written
(Baskin -> باسكين) and a "sparse" one with only the long vowels (Fanateer -> فناتير, Burger -> برجر).
"""
DIGRAPHS = {
    "sh": "ش", "kh": "خ", "gh": "غ", "th": "ث", "dh": "ظ", "ph": "ف", "ck": "ك", "ch": "تش", "qu": "كو",
}
LONG_VOWELS = {
    "aa": "ا", "ee": "ي", "ii": "ي", "ie": "ي", "ei": "ي", "ey": "ي", "ai": "اي", "ay": "اي", "oo": "و", "ou": "و", "uu": "و", "au": "و", "aw": "او", "oa": "و", "ow": "و", "ea": "ي",
}
CONSONANTS = {
    "b": "ب", "t": "ت", "j": "ج", "h": "ه", "d": "د", "r": "ر", "z": "ز", "s": "س", "f": "ف", "q": "ق", "k": "ك", "l": "ل", "m": "م", "n": "ن",
    "w": "و", "y": "ي", "v": "ف", "p": "ب", "g": "ق", "x": "كس",
}
VOWELS = "aeiou"


def _forms(w):
    """(full, sparse) Arabic letters of a lower-case Latin word."""
    full = []
    sparse = []
    i = 0
    n = len(w)
    while i < n:
        two = w[i:i + 2]
        c = w[i]
        at_end = i + len(two) >= n
        if two in DIGRAPHS:
            full.append(DIGRAPHS[two])
            sparse.append(DIGRAPHS[two])
            i += 2
            continue
        if two in LONG_VOWELS:
            v = LONG_VOWELS[two]
            if i == 0 and v in ("ي", "و", "اي", "او"):
                v = "ا" + v.lstrip("ا")
            full.append(v)
            sparse.append(v)
            i += 2
            continue
        if c in VOWELS:
            if i == 0:
                full.append("ا")
                sparse.append("ا")
            elif i == n - 1:
                if c == "e":
                    pass  # silent, as in Dose
                else:
                    v = {"a": "ا", "i": "ي", "o": "و", "u": "و"}[c]
                    full.append(v)
                    sparse.append(v)
            else:
                v = {"a": "ا", "e": "ي", "i": "ي", "o": "و", "u": "و"}[c]
                full.append(v)
                if c == "o":
                    sparse.append(v)
            i += 1
            continue
        if c == "c":
            ch = "س" if i + 1 < n and w[i + 1] in "eiy" else "ك"
        elif c == "h" and i == n - 1:
            ch = "ه"
        else:
            ch = CONSONANTS.get(c)
        if ch:
            if not (full and full[-1] == ch and i > 0 and w[i - 1] == c):  # a doubled letter is written once
                full.append(ch)
                sparse.append(ch)
        i += 1
    return "".join(full), "".join(sparse)


def latin_forms(word):
    """Arabic spellings of one Latin word, or [] for words that should not be guessed at (digits, very short)."""
    if len(word) < 3 or not word.isascii() or not word.isalpha():
        return []
    a, b = _forms(word)
    out = []
    for f in (b, a):
        if len(f) >= 2 and f not in out:
            out.append(f)
    return out
