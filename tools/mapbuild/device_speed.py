#!/usr/bin/env python
"""How fast places.db answers on SQLite 3.22, the firmware's version, with the real statements of SEARCH_SPEC.md.

  python -I device_speed.py places.db --sql queries.sql          writes the statements
  python -I device_speed.py places.db --adb emulator-5576        pushes the file under /data/local/tmp, times every statement, removes both again
  python -I device_speed.py places.db --local                    the same statements with this PC's SQLite

Take the emulator's lock first (emu-lock.ps1 -Take -Who places -Serial emulator-5576) and give it back after.
Times are of the SQLite statements alone: the scoring of up to 200 rows in Kotlin comes on top.
"""
import argparse
import json
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import search_ref as S  # noqa: E402

QUERIES = [
    ("مطعم", (26.29, 50.20)), ("مسجد", (27.01, 49.66)), ("قهوة", (26.42, 50.09)), ("بنزين", (27.01, 49.66)), ("صيدلية", (26.29, 50.20)), ("مغسلة سيارات", (27.01, 49.66)),
    ("البيك", (26.29, 50.20)), ("كارفور", (26.42, 50.09)), ("starbucks", (26.29, 50.20)), ("ستاربكس", (26.29, 50.20)),
    ("مطعم البيك الخبر", (27.01, 49.66)), ("طريق الملك فهد", (26.29, 50.20)), ("شارع الملك عبدالعزيز", (27.01, 49.66)), ("King Fahd Rd", (26.29, 50.20)),
    ("حي الفناتير", (27.01, 49.66)), ("الرياض", (26.29, 50.20)), ("شارع ٢٠", (26.42, 50.09)),
    ("مط", (26.29, 50.20)), ("مطع", (26.29, 50.20)), ("صي", (26.29, 50.20)), ("صيد", (26.29, 50.20)), ("ال", (26.29, 50.20)), ("st", (26.29, 50.20)), ("sta", (26.29, 50.20)), ("star", (26.29, 50.20)),
    ("عبدال", (26.29, 50.20)), ("بي", (26.29, 50.20)),
]


def statements(db):
    """(label, SQL with the values written in) for the candidate reads of each query, as search() would run them."""
    syn = S.Syn(db)
    out = []
    for q, near in QUERIES:
        plan = S.plan_query(q, syn)
        if not plan.required:
            continue
        m = S.fts_string(plan).replace("'", "''")
        la = int(near[0] * 1e6)
        lo = int(near[1] * 1e6)
        import math
        k = math.cos(math.radians(near[0])) ** 2
        base = f"SELECT {S.COLUMNS} FROM f CROSS JOIN p ON p.id=f.docid WHERE f MATCH '{m}' "
        out.append((f"{q} | nearest {S.NEAREST}", base + f"ORDER BY (p.lat-{la})*(p.lat-{la})+(p.lon-{lo})*(p.lon-{lo})*{k:.6f} LIMIT {S.NEAREST};"))
        out.append((f"{q} | best {S.BEST}", base + f"ORDER BY p.rank DESC, p.id LIMIT {S.BEST};"))
    near = (26.29, 50.20)
    la, lo = int(near[0] * 1e6), int(near[1] * 1e6)
    out.append(("reverse", f"SELECT {S.COLUMNS} FROM p WHERE p.lat BETWEEN {la - 7000} AND {la + 7000} AND p.lon BETWEEN {lo - 7800} AND {lo + 7800} AND p.kind>=5;"))
    out.append(("spots in a 2 km box", f"SELECT * FROM spot WHERE lat BETWEEN {la - 18000} AND {la + 18000} AND lon BETWEEN {lo - 20000} AND {lo + 20000};"))
    return out


def write_sql(path, stm):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(".timer on\n.output /dev/null\n")
        for label, sql in stm:
            f.write(f".print @@{label}\n{sql}\n")


def parse_cli(text, stm):
    """Pairs the CLI's "Run Time" lines with the statements in order."""
    times = re.findall(r"Run Time: real ([0-9.]+)", text)
    return [(label, float(t) * 1000.0) for (label, _), t in zip(stm, times)], len(times)


def adb(serial, *args, input_bytes=None):
    return subprocess.run(["adb", "-s", serial, *args], input=input_bytes, capture_output=True)


def verify(serial, remote, db, stm):
    """The same answers on the device's SQLite as on this PC's: a count and a sum of ids per statement. This is what shows that FTS4 parses
    the query strings the same way there (standard syntax, OR before AND) and reads a file written here."""
    wrapped = []
    for label, sql in stm:
        if " FROM f " in sql:
            wrapped.append((label, f"SELECT count(*), sum(id) FROM ({sql.rstrip(';')});"))
    with tempfile.TemporaryDirectory(dir=os.environ.get("TEMP")) as d:
        p = os.path.join(d, "v.sql")
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            for _, sql in wrapped:
                f.write(sql + "\n")
        adb(serial, "push", p, f"{remote}/v.sql")
    out = adb(serial, "shell", f"cd {remote} && sqlite3 places.db < v.sql").stdout.decode(errors="replace").split()
    same = 0
    for (label, sql), line in zip(wrapped, out):
        here = db.execute(sql).fetchone()
        there = line.split("|")
        if str(here[0]) == there[0] and str(here[1] or "") == there[1]:
            same += 1
        else:
            print("DIFFERENT:", label, here, there)
    print(f"same answers on the device and here: {same} of {len(wrapped)} statements")


def report(rows):
    for label, ms in rows:
        print(f"{ms:8.1f} ms  {label}")
    near = [ms for label, ms in rows if label.endswith(f"nearest {S.NEAREST}")]
    if near:
        near_sorted = sorted(near)
        print(f"nearest-candidates statement: median {near_sorted[len(near) // 2]:.1f} ms, worst {near_sorted[-1]:.1f} ms over {len(near)} queries")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("--sql")
    ap.add_argument("--adb")
    ap.add_argument("--local", action="store_true")
    ap.add_argument("--runs", type=int, default=2)
    ap.add_argument("--json")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    db = sqlite3.connect(f"file:{a.db}?mode=ro", uri=True)
    stm = statements(db)
    if a.sql:
        write_sql(a.sql, stm)
        print(f"{len(stm)} statements")
    if a.local:
        for run in range(a.runs):
            rows = []
            for label, sql in stm:
                t = time.perf_counter()
                db.execute(sql).fetchall()
                rows.append((label, (time.perf_counter() - t) * 1000.0))
        report(rows)
    if a.adb:
        remote = "/data/local/tmp/places-speed"
        with tempfile.TemporaryDirectory(dir=os.environ.get("TEMP")) as d:
            sql_path = os.path.join(d, "q.sql")
            write_sql(sql_path, stm)
            adb(a.adb, "shell", f"mkdir -p {remote}")
            t = time.time()
            r = adb(a.adb, "push", a.db, f"{remote}/places.db")
            print(f"push: {time.time() - t:.1f} s", r.stdout.decode(errors="replace").strip()[-90:])
            adb(a.adb, "push", sql_path, f"{remote}/q.sql")
        results = {}
        try:
            print(adb(a.adb, "shell", "sqlite3 --version").stdout.decode().strip())
            print("android", adb(a.adb, "shell", "getprop ro.build.version.release").stdout.decode().strip(), adb(a.adb, "shell", "getprop ro.product.cpu.abi").stdout.decode().strip())
            verify(a.adb, remote, db, stm)
            for run in range(a.runs + 1):
                cold = run == 0
                if cold:  # the emulator is rooted: drop the file cache once, for a first query after a start
                    adb(a.adb, "shell", "sync; echo 3 > /proc/sys/vm/drop_caches")
                t = time.time()
                out = adb(a.adb, "shell", f"cd {remote} && sqlite3 places.db < q.sql").stdout.decode(errors="replace")
                rows, n = parse_cli(out, stm)
                if n != len(stm):
                    print(f"run {run}: {n} timings of {len(stm)} statements; head of the output:", out[:400])
                results[("cold" if cold else f"warm{run}")] = rows
                print(f"--- run {run} ({'cold file cache' if cold else 'warm'}): whole script {time.time() - t:.2f} s")
                if cold or run == a.runs:
                    report(rows)
            if a.json:
                with open(a.json, "w", encoding="utf-8") as f:
                    json.dump(results, f, ensure_ascii=False, indent=0)
        finally:
            adb(a.adb, "shell", f"rm -rf {remote}")
            print("removed", remote, adb(a.adb, "shell", f"ls {remote} 2>&1").stdout.decode().strip())


if __name__ == "__main__":
    main()
