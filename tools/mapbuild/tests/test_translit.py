#!/usr/bin/env python
"""How often the rule-based Latin to Arabic spelling gives a real spelling. Search-only: a miss costs a hit in search, never a wrong name on screen.

  python -I test_translit.py [places.db]

Hand-checked words first. Then, with a db, a measure on the data's own pairs: rows whose Arabic name and Latin name are both one word, so
the two are the same word in two scripts. "exact" means the rule wrote the data's Arabic word; "skeleton" means its consonants agree, which is
what the loose stage of a search can still use.
"""
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from search_ref import base, is_ar, latin_base, norm, skeleton  # noqa: E402
from translit import latin_forms  # noqa: E402

CASES = {
    "baskin": "باسكين", "fanateer": "فناتير", "burger": "برجر", "jollibee": "جوليبي", "marriott": "ماريوت", "ramada": "رمادا",
    "petromin": "بترومين", "herfy": "هرفي", "dose": "دوس", "shawarmer": "شاورمر",
}


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    bad = 0
    for la, ar in CASES.items():
        got = latin_forms(la)
        if ar not in got:
            bad += 1
            print(f"miss: {la} -> {got}, wanted {ar}")
    print(f"{len(CASES) - bad} of {len(CASES)} hand-checked words")
    assert latin_forms("a1") == [] and latin_forms("ab") == []
    if len(sys.argv) < 2:
        return 0
    db = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
    seen = set()
    n = exact = sk = 0
    misses = []
    for name, alt in db.execute("SELECT name,alt FROM p WHERE alt IS NOT NULL AND kind=6"):
        a = [base(w) for w in norm(name).split()]
        e = norm(alt).split()
        if len(a) != 1 or len(e) != 1 or not is_ar(a[0]) or is_ar(e[0]) or not e[0].isascii() or not e[0].isalpha():
            continue
        pair = (a[0], e[0])
        if pair in seen:
            continue
        seen.add(pair)
        forms = [base(f) for f in latin_forms(e[0])] + [base(f) for f in latin_forms(latin_base(e[0]))]
        if not forms:
            continue
        n += 1
        if a[0] in forms:
            exact += 1
        elif skeleton(a[0]) == skeleton(latin_base(e[0])):
            sk += 1
        elif len(misses) < 25:
            misses.append(pair)
    print(f"data pairs: {n} distinct one-word names; exact {exact} ({100 * exact / max(n, 1):.0f}%); not exact but same skeleton {sk} ({100 * sk / max(n, 1):.0f}%); neither {n - exact - sk}")
    print("some misses:", misses)
    return 0


if __name__ == "__main__":
    sys.exit(main())
