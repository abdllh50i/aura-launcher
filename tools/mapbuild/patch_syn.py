#!/usr/bin/env python
"""Writes table syn of a built places.db again from places_tables.py, and nothing else: no words of the index change, so a new alias or role needs no build of an hour.

  python -I patch_syn.py <places.db>

Then make_pack.py again for that piece, and in the app `scripts/search_compare.py fixture`. A word that should also be in the index (a new DICT entry, a HAND_PLACES row) still needs build_places.py.
"""
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_places as B  # noqa: E402


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    db = sqlite3.connect(sys.argv[1])
    before = db.execute("SELECT count(*) FROM syn").fetchone()[0]
    rows = B.syn_rows()
    with db:
        db.execute("DELETE FROM syn")
        db.executemany("INSERT INTO syn VALUES(?,?,?,?)", rows)
    db.execute("VACUUM")
    mode = db.execute("PRAGMA journal_mode").fetchone()[0]
    check = db.execute("PRAGMA quick_check").fetchone()[0]
    db.close()
    print(f"syn: {before} rows before, {len(rows)} now; journal {mode}; quick_check {check}")


if __name__ == "__main__":
    main()
