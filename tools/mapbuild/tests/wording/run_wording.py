#!/usr/bin/env python
"""Does the app's real Instructions.kt have wording for what Valhalla says? Runs the Kotlin itself over the real answers.

    python -I run_wording.py <app source root> <out dir of run_route_trips.py> <work dir> [answer folders under out dir, default valhalla var-top160]

1. Collects every manoeuvre type and modifier from the answers, with real names, refs and signs.
2. Adds each type crossed with each modifier, even those not seen, marked unseen.
3. Cuts class Lane and class Step out of nav/Router.kt and compiles them with nav/Instructions.kt and Harness.kt (kotlin-compiler-embeddable from the Gradle cache; Java temp on the work dir).
4. Judges each line: is there wording; does the Arabic and English say the same side as the modifier; does it fall into the "else" branch; does a raw "a; b" name reach the screen or the voice.

Writes <work>/wording.tsv and <work>/wording-report.json.
"""
import collections
import glob
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TYPES = ["depart", "arrive", "turn", "continue", "new name", "merge", "on ramp", "off ramp", "fork", "end of road", "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary", "notification", "use lane"]
MODS = ["", "uturn", "sharp right", "right", "slight right", "straight", "slight left", "left", "sharp left"]
EXPLICIT = {"arrive", "depart", "new name", "continue", "merge", "on ramp", "off ramp", "fork", "end of road", "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary"}


def jar(name):
    root = os.path.join(os.environ["USERPROFILE"], ".gradle", "caches", "modules-2", "files-2.1")
    hits = glob.glob(os.path.join(root, "**", name), recursive=True)
    if not hits:
        raise SystemExit("not in the Gradle cache: " + name)
    return hits[0]


def stubs(router_kt):
    src = open(router_kt, encoding="utf-8").read()
    lane = re.search(r"^class Lane\(.*?\)$", src, re.M).group(0)
    m = re.search(r"^class Step\(.*?^}\n", src, re.M | re.S)
    step = m.group(0)
    return ("package com.abdllh.aura.nav\n\ndata class LatLon(val lat: Double, val lon: Double)\n\nobject Places { fun isArabic(s: String) = s.any { it in '\\u0600'..'\\u06ff' } }\n\n" + lane + "\n\n" + step)


def collect(out, folders):
    seen = collections.Counter()
    sample = {}
    for folder in folders:
        for path in glob.glob(os.path.join(out, folder, "*.json")):
            doc = json.load(open(path, encoding="utf-8"))
            if doc.get("code") != "Ok":
                continue
            for r in doc["routes"]:
                for leg in r["legs"]:
                    for s in leg["steps"]:
                        m = s["maneuver"]
                        key = (m.get("type", ""), m.get("modifier", ""))
                        seen[key] += 1
                        rich = (bool(s.get("name")), bool(s.get("ref")), bool(s.get("destinations")), bool(s.get("exits")), m.get("exit", 0) > 0)
                        k2 = key + rich
                        if k2 not in sample:
                            sample[k2] = (m.get("type", ""), m.get("modifier", ""), str(m.get("exit", 0)), s.get("name", "") or "", s.get("ref", "") or "", s.get("destinations", "") or "", s.get("exits", "") or "")
    return seen, sample


def said_side(text):
    """Which way a sentence says: left, right, back, straight, or nothing."""
    t = text
    left = "يسار" in t or "اليسار" in t
    right = "يمين" in t or "اليمين" in t
    if left and not right:
        return "left"
    if right and not left:
        return "right"
    if "استدر" in t:
        return "back"
    if "مباشر" in t or "أمامك" in t:
        return "straight"
    return ""


def expected_side(mod):
    if "left" in mod:
        return "left"
    if "right" in mod:
        return "right"
    if mod == "uturn":
        return "back"
    if mod == "straight":
        return "straight"
    return ""


def main():
    app, out, work = sys.argv[1], sys.argv[2], sys.argv[3]
    folders = sys.argv[4:] or ["valhalla", "var-top160"]
    os.makedirs(work, exist_ok=True)
    seen, sample = collect(out, folders)
    rows = []
    for k2, v in sample.items():
        rows.append(v + ("seen",))
    for t in TYPES:
        for m in MODS:
            ex = "2" if t in ("roundabout", "rotary") else "0"
            rows.append((t, m, ex, "", "", "", "", "seen" if (t, m) in seen else "unseen"))
    with open(os.path.join(work, "cases.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("type\tmodifier\texit\tname\tref\tdest\texits\tseen\n")
        for r in rows:
            f.write("\t".join(x.replace("\t", " ") for x in r) + "\n")
    with open(os.path.join(work, "Stubs.kt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(stubs(os.path.join(app, "Router.kt")))
    cp = os.pathsep.join(jar(n) for n in ("kotlin-compiler-embeddable-2.0.21.jar", "kotlin-stdlib-2.0.21.jar", "kotlin-script-runtime-2.0.21.jar", "kotlin-daemon-embeddable-2.0.21.jar", "trove4j-1.0.20200330.jar", "annotations-13.0.jar", "kotlinx-coroutines-core-jvm-1.6.4.jar", "kotlin-reflect-1.9.23.jar"))
    stdlib = jar("kotlin-stdlib-2.0.21.jar")
    tmp = os.path.join(work, "jtmp")
    os.makedirs(tmp, exist_ok=True)
    classes = os.path.join(work, "classes")
    subprocess.run(["java", "-Xmx1500m", f"-Djava.io.tmpdir={tmp}", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-cp", stdlib, "-d", classes,
                    os.path.join(app, "Instructions.kt"), os.path.join(work, "Stubs.kt"), os.path.join(HERE, "Harness.kt")], check=True, stdout=subprocess.DEVNULL)
    res = os.path.join(work, "wording.tsv")
    subprocess.run(["java", "-Dfile.encoding=UTF-8", f"-Djava.io.tmpdir={tmp}", "-cp", classes + os.pathsep + stdlib, "com.abdllh.aura.nav.HarnessKt", os.path.join(work, "cases.tsv"), res], check=True)
    keys = ["type", "modifier", "exit", "name", "ref", "dest", "exits", "seen", "action_ar", "action_en", "text_ar", "text_en", "spoken_ar", "road_ar", "arrow"]
    out_rows = [dict(zip(keys, ln.rstrip("\n").split("\t"))) for ln in open(res, encoding="utf-8")]
    problems = collections.defaultdict(list)
    table = {}
    for r in out_rows:
        key = f"{r['type']} | {r['modifier']}"
        n = seen.get((r["type"], r["modifier"]), 0)
        table.setdefault(key, {"count": n, "action_ar": r["action_ar"], "action_en": r["action_en"], "arrow": r["arrow"]})
        if not r["action_ar"].strip() or not r["action_en"].strip():
            problems["no wording"].append(key)
        if r["type"] not in EXPLICIT and r["type"] != "turn":
            problems["falls into the else branch of action(): " + r["type"]].append(key)
        exp = expected_side(r["modifier"])
        said = said_side(r["action_ar"])
        if r["type"] in ("roundabout", "rotary", "exit roundabout", "exit rotary", "depart"):
            pass
        elif exp and said and exp != said:
            problems[f"says {said}, modifier says {exp}"].append(key)
        elif exp and not said and r["type"] != "arrive":
            problems[f"says no direction, modifier says {exp}"].append(key)
        if ";" in r["text_ar"]:
            problems["a raw 'a; b' name reaches the sentence"].append(r["text_ar"])
        if ";" in r["spoken_ar"]:
            problems["a raw 'a; b' name reaches the voice"].append(r["spoken_ar"])
        if re.search(r"[A-Za-z]{3}", r["road_ar"]) and "باتجاه" in r["road_ar"]:
            problems["Latin sign text in an Arabic road line"].append(r["road_ar"])
    report = {"types x modifiers seen": {f"{k[0]} | {k[1]}": v for k, v in sorted(seen.items())},
              "wording for each": {k: v for k, v in sorted(table.items()) if v["count"]},
              "wording for pairs never seen": {k: v for k, v in sorted(table.items()) if not v["count"]},
              "problems": {k: {"cases": len(v), "seen": sum(1 for x in v if " | " in x and seen.get(tuple(x.split(" | ", 1)), 0) > 0), "examples": sorted(set(v))[:12]} for k, v in problems.items()}}
    with open(os.path.join(work, "wording-report.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    print("rows", len(out_rows), "problems", {k: len(v) for k, v in problems.items()}.__repr__().encode("ascii", "backslashreplace").decode())


if __name__ == "__main__":
    main()
