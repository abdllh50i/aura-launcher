#!/usr/bin/env python
"""The 30 reference trips (trips.py) through the app's real service on an emulator (nav/route, RouteDebug's batch), against out/ref-routes: km, Valhalla's own minutes, routes, steps,
points, and the scaled time against OSRM's. The tar must be on the device (see docs/DEV.md).

    python -I app_route_check.py <adb> <serial> <ref-routes dir> <out.json> [--rounds N] [--no-limits]

Prints a table and writes the rows. With --rounds a leak test: PSS and HWM of each round.
"""
import json
import os
import statistics
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import trips  # noqa: E402

PKG = "com.abdllh.aura"
DIAG = f"/data/data/{PKG}/files/diag"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    adb, serial, ref, out = args[:4]
    rounds = int(sys.argv[sys.argv.index("--rounds") + 1]) if "--rounds" in sys.argv else 1
    if rounds != 1:
        args.remove(str(rounds))
    limits = "--no-limits" not in sys.argv

    def sh(cmd):
        return subprocess.run([adb, "-s", serial, "shell", cmd], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout

    rows = [t for group in (trips.LONG, trips.SHORT, trips.EDGES) for t in group]
    lines = "\n".join(f"{t[0]},{t[2][0]},{t[2][1]},{t[3][0]},{t[3][1]}" for t in rows) + "\n"
    tmp = os.path.join(os.path.dirname(os.path.abspath(out)), "route-trips.txt")
    with open(tmp, "w", newline="\n") as f:
        f.write(lines)
    subprocess.run([adb, "-s", serial, "push", tmp, "/data/local/tmp/route-trips.txt"], capture_output=True)
    sh(f"chmod 644 /data/local/tmp/route-trips.txt; rm -f {DIAG}/route-batch.json")
    t0 = time.time()
    sh(f"am broadcast -a com.abdllh.aura.debug.ROUTE -p {PKG} --es batch /data/local/tmp/route-trips.txt --ei rounds {rounds} --ez limits {'true' if limits else 'false'}")
    text = ""
    while time.time() - t0 < 1800:
        time.sleep(1)
        text = sh(f"cat {DIAG}/route-batch.json 2>/dev/null")
        if text.startswith("["):
            break
    got = json.loads(text)
    print(f"{len(got)} answers in {time.time() - t0:.0f} s")
    with open(os.path.join(ref, "summary.json"), encoding="utf-8") as f:
        refs = {r["id"]: r for r in json.load(f)}
    osrm = {}
    tsv = os.path.join(ref, "compare-osrm.tsv")
    if os.path.exists(tsv):
        with open(tsv, encoding="utf-8") as f:
            head = f.readline().rstrip("\n").split("\t")
            for ln in f:
                d = dict(zip(head, ln.rstrip("\n").split("\t")))
                osrm[d["id"]] = float(d["osrm_min"])
    table = []
    print(f"{'trip':34}{'km':>9}{'ref':>9}{'raw min':>9}{'ref':>8}{'scaled':>8}{'OSRM':>8}{'rts':>4}{'ref':>4}{'stp':>5}{'ref':>5}{'pts':>7}{'ref':>7}{'nat ms':>7}{'par ms':>7}{'cli ms':>7}{'KB':>6}{'PSS':>6}{'HWM':>6}{'lim%':>6} first")
    for g in got:
        if g["round"] != 0 and rounds > 1:
            continue
        r = refs[g["id"]]
        if g["status"] != 0:
            print(f"{g['id']:34} status {g['status']}: {g['message']}")
            table.append({"id": g["id"], "status": g["status"], "message": g["message"]})
            continue
        a = g["routes"][0]
        # the ref's first route, or an alternate that the scaled times put first
        same_first = abs(a["km"] - r["km"]) < 0.02
        alt = [x for x in r.get("alternates", []) if abs(x[0] - a["km"]) < 0.06]
        row = {"id": g["id"], "km": round(a["km"], 2), "ref_km": r["km"], "raw_min": round(a["rawMinutes"], 1), "ref_min": r["minutes"], "scaled_min": round(a["minutes"], 1), "osrm_min": osrm.get(g["id"]),
               "routes": len(g["routes"]), "ref_routes": r["routes"], "steps": a["steps"], "ref_steps": r["steps"], "points": a["points"], "ref_points": r["points"], "route_ms": g["routeMs"], "parse_ms": g["parseMs"], "client_ms": g["clientMs"],
               "bytes": g["bytes"], "ref_bytes": r["bytes"], "pss_mb": round(g["pssKb"] / 1024), "hwm_mb": round(g["vmHwmKb"] / 1024), "limit_share": round(a["limitsKm"] / a["km"], 3), "first": "same" if same_first else ("alternate" if alt else "DIFFERENT"),
               "estimate": a["estimate"], "line_km": round(a["lineKm"], 2)}
        table.append(row)
        print(f"{row['id'][:33]:34}{row['km']:>9}{row['ref_km']:>9}{row['raw_min']:>9}{row['ref_min']:>8}{row['scaled_min']:>8}{str(row['osrm_min']):>8}{row['routes']:>4}{row['ref_routes']:>4}{row['steps']:>5}{row['ref_steps']:>5}{row['points']:>7}{row['ref_points']:>7}"
              f"{row['route_ms']:>7}{row['parse_ms']:>7}{row['client_ms']:>7}{row['bytes'] // 1024:>6}{row['pss_mb']:>6}{row['hwm_mb']:>6}{row['limit_share'] * 100:>6.0f} {row['first']}")
    ok = [t for t in table if t.get("first") == "same" and abs(t["km"] - t["ref_km"]) < 0.02 and abs(t["raw_min"] - t["ref_min"]) < 0.11 and t["steps"] == t["ref_steps"] and t["points"] <= t["ref_points"]]
    alt = [t for t in table if t.get("first") == "alternate"]
    bad = [t for t in table if t not in ok and t not in alt]
    print(f"same first route, km, minutes and steps: {len(ok)} of {len(table)}; an alternate first by scaled time: {len(alt)} ({[t['id'] for t in alt]}); else: {[t['id'] for t in bad]}")
    if table and "scaled_min" in table[0]:
        have = [t for t in table if t.get("osrm_min")]
        raw = [t["raw_min"] / t["osrm_min"] - 1 for t in have]
        sc = [t["scaled_min"] / t["osrm_min"] - 1 for t in have]
        print(f"against OSRM's time, median: raw {statistics.median(raw) * 100:+.1f} %, scaled {statistics.median(sc) * 100:+.1f} %")
        print("ms native/parse/client, median and worst:", *(f"{k} {statistics.median(t[k] for t in table if k in t):.0f}/{max(t[k] for t in table if k in t)}" for k in ("route_ms", "parse_ms", "client_ms")))
    if rounds > 1:
        print("round: PSS MB of the last answer, HWM MB, pid")
        for k in range(rounds):
            last = [g for g in got if g["round"] == k][-1]
            print(f"  {k}: {last['pssKb'] // 1024} {last['vmHwmKb'] // 1024} {last['pid']}")
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"table": table, "all": got if rounds > 1 else []}, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
