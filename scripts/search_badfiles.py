#!/usr/bin/env python
"""Files that places.db must never be taken for, for scripts/search-compare.ps1 -Failures.

  python -I search_badfiles.py <places.db> <out folder>
"""
import os
import sqlite3
import struct
import sys


def small(path, meta, tables=True):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    if tables:
        db.execute("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT) WITHOUT ROWID")
        db.executemany("INSERT INTO meta VALUES(?,?)", meta)
    else:
        db.execute("CREATE TABLE other(x)")
    db.commit()
    db.close()


def main():
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    with open(src, "rb") as f:
        head = f.read(20_000_384)  # 4883 whole pages of 4096, about half of the file
    with open(os.path.join(out, "cut-plain.db"), "wb") as f:  # a download cut short: the header still says 9552 pages
        f.write(head)
    patched = bytearray(head)
    patched[28:32] = struct.pack(">I", len(head) // 4096)  # the header says what the file has, so only a read that reaches a missing page can tell
    with open(os.path.join(out, "cut-patched.db"), "wb") as f:
        f.write(patched)
    # whole, and the header right, with holes in table p and the index: it opens and answers its meta; a search is what meets the damage, and the file must still be there after it
    db = sqlite3.connect(f"file:{src}?mode=ro", uri=True)
    keep = {r[0] for r in db.execute("SELECT rootpage FROM sqlite_master WHERE rootpage>0")}
    db.close()
    with open(src, "rb") as f:
        whole = bytearray(f.read())
    pages = len(whole) // 4096
    for n in range(pages // 20, pages - pages // 20, 7):
        if n + 1 not in keep:
            whole[n * 4096:(n + 1) * 4096] = bytes(4096)
    with open(os.path.join(out, "holes.db"), "wb") as f:
        f.write(whole)
    small(os.path.join(out, "old-norm.db"), [("norm_version", "2"), ("schema", "2")])
    small(os.path.join(out, "old-schema.db"), [("norm_version", "3"), ("schema", "1")])
    small(os.path.join(out, "no-meta.db"), [], tables=False)
    with open(os.path.join(out, "garbage.db"), "wb") as f:
        f.write(b"this is no database\n" * 300)
    with open(os.path.join(out, "empty.db"), "wb"):
        pass
    print("written to", out)


if __name__ == "__main__":
    main()
