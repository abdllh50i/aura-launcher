#!/usr/bin/env python
"""The pack manager's tests on an emulator, against scripts/pack_server.py and the real pack. Each scenario says what it proved, with numbers.

  python -I pack_e2e.py --serial emulator-5578 --pack D:\\k2501-work\\mapbuild\\out\\pack --package com.abdllh.aura.standin [--lock D:\\k2501-work\\emu-emulator-5578.lock] scenario...

Scenarios: refusals space full kill points drops faults update usb usbroot trip reboot powercut fulldisk hotspot misc all
Needs: the debug app installed, a server on --port (started here if none answers), root on the emulator (adb root). Take the emulator's lock first. The app's files/maps2 and files/offline are wiped between scenarios: use a copy of the app of your own (-PstandIn), and afterwards
uninstall it and set the home app back: a second home screen makes Android forget the chosen one (cmd package set-home-activity com.abdllh.aura/.home.HomeActivity).
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.request

ADB = r"E:\Android\Sdk\platform-tools\adb.exe"
HERE = os.path.dirname(os.path.abspath(__file__))


class T:
    pass


def adb(*args, timeout=120, check=False):
    r = subprocess.run([ADB, "-s", T.serial, *args], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {args}: {r.stderr}")
    return r.stdout.strip()


def sh(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout)


def bc(**extras):
    parts = [f"am broadcast -a com.abdllh.aura.debug.PACK -p {T.pkg}"]
    for k, v in extras.items():
        if isinstance(v, bool):
            parts.append(f"--ez {k} {'true' if v else 'false'}")
        else:
            parts.append(f"--es {k} {v if v else repr('')}")
    sh(" ".join(parts))


def ctl(query):
    with urllib.request.urlopen(f"http://127.0.0.1:{T.port}/__ctl?{query}", timeout=10) as r:
        return r.read()


def stats():
    with urllib.request.urlopen(f"http://127.0.0.1:{T.port}/__stats", timeout=10) as r:
        d = json.load(r)
    # (the chunks and notices: what the pack costs; manifests are asked for at every start)
    d["served_total"] -= d["served"].get("manifest.json", 0)
    return d


class Logcat(threading.Thread):
    """Everything the app says about the pack, kept with the time it came."""

    def __init__(self):
        super().__init__(daemon=True)
        self.lines = []
        self.lock = threading.Lock()
        self.proc = None
        self.stop_it = False

    def run(self):
        while not self.stop_it:
            self.proc = subprocess.Popen([ADB, "-s", T.serial, "logcat", "-v", "time", "-T", "1", "AuraPackDebug:I", "AuraPack:I", "AndroidRuntime:E", "*:S"],
                                         stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
            for line in self.proc.stdout:
                with self.lock:
                    self.lines.append((time.time(), line.rstrip()))
            time.sleep(1)  # (adb went away: a reboot)

    def since(self, t0, pattern):
        with self.lock:
            return [l for (t, l) in self.lines if t >= t0 and re.search(pattern, l)]


def status(fresh=True):
    t0 = time.time()
    if fresh:
        bc(dump=True)
    for _ in range(20):
        got = T.log.since(t0 - 0.2, r"status state=")
        if got:
            d = dict(re.findall(r"(\w+)=(\S*)", got[-1].split("status ", 1)[1]))
            return d
        time.sleep(0.15)
    return {}


def wait_state(states, timeout=600, every=1.0):
    states = [states] if isinstance(states, str) else states
    t0 = time.time()
    last = {}
    while time.time() - t0 < timeout:
        last = status()
        if last.get("state") in states:
            return last
        time.sleep(every)
    raise TimeoutError(f"waited for {states}, last {last}")


def wait_log(pattern, t0, timeout):
    while time.time() - t0 < timeout:
        if T.log.since(t0, pattern):
            return True
        time.sleep(0.1)
    raise TimeoutError(pattern)


def wait_percent(p, timeout=600):
    t0 = time.time()
    while time.time() - t0 < timeout:
        s = status()
        if int(s.get("percent", 0)) >= p and s.get("state") in ("FETCHING", "WAITING"):
            return s
        time.sleep(0.5)
    raise TimeoutError(f"percent {p}: {s}")


def pid():
    return sh(f"pidof {T.pkg}").split(" ")[0].strip()


def start_app():
    sh(f"am start -n {T.pkg}/com.abdllh.aura.settings.SettingsActivity")
    for _ in range(50):
        if pid():
            break
        time.sleep(0.2)
    time.sleep(2.5)  # (the receiver and PackManager.init's thread)


def stop_app():
    sh(f"am force-stop {T.pkg}")


def app_dir():
    return f"/data/data/{T.pkg}/files"


def wipe():
    stop_app()
    sh(f"rm -rf {app_dir()}/maps2 {app_dir()}/fakeusb {app_dir()}/offline")
    for k in ("base", "free", "usb"):
        sh(f"setprop debug.aura.pack.{k} ''")
    sh(f"setprop debug.aura.pack.base http://127.0.0.1:{T.port}/releases/download/data-2026.11/")
    ctl("reset=1&clear=1")


def touch_lock():
    while T.lock_file:
        try:
            os.utime(T.lock_file, None)
        except OSError:
            pass
        time.sleep(60)


def hashes_ok(pack_id):
    """Every file on the unit against the manifest, by SHA-256 computed on the unit."""
    with open(os.path.join(T.pack, "manifest.json"), encoding="utf-8") as f:
        m = json.load(f)
    d = f"{app_dir()}/maps2/packs/{pack_id}"
    out = sh(f"cd {d} && sha256sum " + " ".join([p["name"] for p in m["pieces"]] + [n["url"] for n in m["notices"]]), timeout=300)
    got = {l.split()[1].lstrip("*"): l.split()[0] for l in out.splitlines() if len(l.split()) == 2}
    bad = [p["name"] for p in m["pieces"] if got.get(p["name"]) != p["sha256"]] + [n["url"] for n in m["notices"] if got.get(n["url"]) != n["sha256"]]
    if bad:
        return False, f"wrong: {bad}"
    return True, f"{len(got)} files equal the manifest by sha256sum on the unit"


def manifest_totals():
    with open(os.path.join(T.pack, "manifest.json"), encoding="utf-8") as f:
        m = json.load(f)
    chunks = sum(c["bytes"] for p in m["pieces"] for c in p["chunks"])
    notices = sum(n["bytes"] for n in m["notices"])
    largest = max(c["bytes"] for p in m["pieces"] for c in p["chunks"])
    return m, chunks + notices, largest


def fatal(t0):
    return T.log.since(t0, r"FATAL EXCEPTION|E/AndroidRuntime")


def result(name, ok, detail):
    print(("PASS " if ok else "FAIL ") + name + ": " + detail, flush=True)
    T.results.append({"scenario": name, "ok": bool(ok), "detail": detail})


def proc_cpu(p):
    """(jiffies of the thread named aura-pack, of the whole process)."""
    out = sh(f"for f in /proc/{p}/task/*/stat; do cat $f; done; cat /proc/{p}/stat")
    lines = out.splitlines()
    pack = 0
    for l in lines[:-1]:
        m = re.match(r"\d+ \((.*?)\) \S (.*)", l)
        if m and m.group(1).startswith("aura-pack"):
            f = m.group(2).split()
            pack += int(f[10]) + int(f[11])
    m = re.match(r"\d+ \((.*?)\) \S (.*)", lines[-1])
    f = m.group(2).split()
    return pack, int(f[10]) + int(f[11])


def pss_kb():
    out = sh(f"dumpsys meminfo {T.pkg} | grep -E 'TOTAL PSS|TOTAL  '")
    m = re.search(r"TOTAL PSS:\s+(\d+)", out) or re.search(r"TOTAL\s+(\d+)", out)
    return int(m.group(1)) if m else 0


# ------------------------------------------------------------------------------------------------ scenarios

def refusals():
    wipe()
    start_app()
    t0 = time.time()
    bc(selftest=True)
    for _ in range(40):
        done = T.log.since(t0, r"selftest done")
        if done:
            break
        time.sleep(0.5)
    lines = T.log.since(t0, r"selftest ")
    for l in lines:
        print("   ", l.split("selftest ", 1)[1])
    ok = bool(done) and "failed=0" in done[-1]
    result("refusals", ok, f"{sum(1 for l in lines if ' ok ' in l)} refusals and checks right, real manifest parsed; {done[-1].split('selftest ', 1)[1] if done else 'no end'}")
    # and over the wire: the same refusals through a whole start
    for label, q, want in (("format 2", 'patch=data-2026.11,format,2', "APP_TOO_OLD"), ("minApp", 'patch=data-2026.11,minApp,99999999', "APP_TOO_OLD"), ("maxApp", 'patch=data-2026.11,maxApp,1', "PACK_TOO_OLD")):
        ctl("reset=1&clear=1&" + q)
        bc(start=True)
        s = wait_state(["FAILED", "NONE", "OFFER"], 30)
        st = stats()
        result(f"refused start, {label}", s.get("problem") == want and st["served_total"] < 20000, f"state {s.get('state')}, problem {s.get('problem')}, {st['served_total']} bytes served (the manifest only); staging {sh('ls ' + app_dir() + '/maps2/staging 2>/dev/null')!r}")
    ctl("reset=1&clear=1")


def space():
    wipe()
    sh("setprop debug.aura.pack.free 500000000")
    start_app()
    t0 = time.time()
    bc(check=True)
    s = wait_state(["OFFER", "NO_SPACE"], 30)
    advice = T.log.since(t0, r"advice:")
    bc(start=True)
    time.sleep(2)
    s = wait_state(["NO_SPACE", "FAILED"], 30)
    files = sh(f"ls -la {app_dir()}/maps2 2>&1; ls -la {app_dir()}/maps2/staging 2>&1")
    served = stats()["served"]
    ok = s.get("state") == "NO_SPACE" and "fits=false" in s.get("offer", "") and "staging" not in sh(f"ls {app_dir()}/maps2 2>&1") and not [k for k in served if k.endswith(".gz")]
    result("space refused", ok, f"state {s.get('state')}, offer {s.get('offer')}; chunks served {list(served)}; maps2: {files.splitlines()[:3]}")
    if advice:
        print("    ", advice[-1].split("advice: ", 1)[1])
    sh("setprop debug.aura.pack.free ''")


def install_and_check(name, pack_id="ksa-2026.11", timeout=900):
    s = wait_state(["INSTALLED", "FAILED", "NO_SPACE"], timeout)
    if s.get("state") != "INSTALLED":
        result(name, False, f"ended {s}")
        return False
    ok, how = hashes_ok(pack_id)
    if not ok:
        result(name, False, how)
    T.last_hash = how
    return ok


def full():
    wipe()
    m, total, _ = manifest_totals()
    start_app()
    p = pid()
    pss0 = pss_kb()
    t0 = time.time()
    bc(start=True)
    samples = []
    cpu0 = None
    cpu1 = None
    while True:
        s = status()
        if s.get("state") in ("INSTALLED", "FAILED", "NO_SPACE"):
            break
        samples.append((time.time() - t0, s.get("state"), int(s.get("percent", 0)), int(s.get("speed", 0)), pss_kb()))
        if not T.fg and s.get("state") == "FETCHING":
            T.fg = sh(f"dumpsys activity services {T.pkg} | grep -E 'PackService|isForeground'")
        # (the thread's numbers are gone once it ends: the last look while it runs)
        now = proc_cpu(p)
        if cpu0 is None:
            cpu0, t_cpu = now, time.time()
        cpu1, t_cpu1 = now, time.time()
        time.sleep(3)
    took = time.time() - t0
    ok, how = hashes_ok("ksa-2026.11")
    st = stats()
    hz = 100.0
    span = max(t_cpu1 - t_cpu, 1)
    pack_cpu = (cpu1[0] - cpu0[0]) / hz / span * 100
    proc = (cpu1[1] - cpu0[1]) / hz / span * 100
    pss = [x[4] for x in samples if x[4]]
    fetch = [x for x in samples if x[1] == "FETCHING"]
    verify = [x for x in samples if x[1] == "VERIFYING"]
    t1 = fetch[-1][0] if fetch else 0
    line = T.log.since(t0, r"installed ksa")
    detail = (f"{total} bytes in {took:.0f} s ({total / 1048576 / max(took, 1):.1f} MB/s over all), fetching {t1:.0f} s, verifying about {took - t1:.0f} s; "
              f"thread aura-pack {pack_cpu:.0f}% of one core, process {proc:.0f}%; PSS before {pss0 // 1024} MB, while working {min(pss) // 1024 if pss else 0}-{max(pss) // 1024 if pss else 0} MB; "
              f"served {st['served_total']} in {st['requests']} requests through {st['redirects']} redirects, If-Range seen {st['if_range_seen']}; {how}; {line[-1].split('installed ', 1)[1] if line else ''}")
    result("full install", ok and st["served_total"] == total and s.get("state") == "INSTALLED" and not fatal(t0), detail)
    T.samples = samples
    fg_after = sh(f"dumpsys activity services {T.pkg} | grep -c PackService")
    result("foreground while running", "isForeground=true" in T.fg and fg_after == "0", f"while fetching: {' '.join(T.fg.split())[:150]}; PackService records after: {fg_after}")
    print("    paths", T.log.since(t0, r"paths base")[-1:] or "")


def kill():
    """kill -9 at random moments of real work: what is lost each time is at most the chunk in hand."""
    wipe()
    m, total, largest = manifest_totals()
    ctl("slow_kbps=30000")
    start_app()
    bc(start=True)
    kills = []
    for at in (15, 40, 70):
        s = wait_percent(at)
        p = pid()
        partlen = sh(f"ls -l {app_dir()}/maps2/staging/*/*.part 2>/dev/null | head -1")
        sh(f"kill -9 {p}")
        time.sleep(1.5)
        before = stats()["served_total"]
        kills.append((s.get("percent"), partlen.split()[-1].split('/')[-1] if partlen else "", partlen.split()[4] if partlen else "0"))
        start_app()
        time.sleep(4)
        s2 = status()
        after = stats()["served_total"]
        # nothing goes on by itself: the state says stopped and no byte more was asked for
        if s2.get("state") != "PAUSED" or after != before:
            result("kill -9", False, f"after a restart: {s2}, served {before} -> {after}")
            return
        print(f"    killed at {s.get('percent')}%: part {kills[-1][1]} of {kills[-1][2]} bytes kept; after restart: {s2.get('state')} {s2.get('percent')}%")
        bc(resume=True)
    t0 = time.time()
    ok = install_and_check("kill -9")
    st = stats()
    wasted = st["served_total"] - total
    result("kill -9", ok and wasted <= 3 * largest, f"3 kills, then finished; served {st['served_total']} of {total}: {wasted} bytes fetched again in all, largest chunk {largest}; {T.last_hash}")
    ctl("reset=1")


def points():
    """The process dies at each step of the work, one after the other, through one install."""
    wipe()
    m, total, largest = manifest_totals()
    start_app()
    steps = ["after-fetch", "after-unpack", "after-mark", "before-ok", "before-rename", "before-pointer", "after-pointer"]
    seen = []
    bc(dieat=steps[0], start=True)
    t0 = time.time()
    for i, point in enumerate(steps):
        p = pid()
        for _ in range(1200):
            if pid() != p:
                break
            time.sleep(0.25)
        else:
            result("kill points", False, f"never died at {point}")
            return
        dying = T.log.since(t0, f"dying at {point}")
        seen.append(point)
        time.sleep(1)
        start_app()
        s = status()
        files = sh(f"ls {app_dir()}/maps2; cat {app_dir()}/maps2/current 2>/dev/null")
        print(f"    died at {point} ({'said so' if dying else 'NO LOG'}): now {s.get('state')} {s.get('percent')}%, current={s.get('current')}, maps2={files.split()}")
        if point == "before-pointer":
            # the switch was renamed, not pointed: the next start finishes it with no one asking
            if s.get("current") != "ksa-2026.11":
                result("kill points", False, f"the start did not finish the switch: {s}")
                return
        if point == "after-pointer":
            if s.get("current") != "ksa-2026.11":
                result("kill points", False, f"not switched after the pointer: {s}")
                return
            break
        nxt = steps[i + 1]
        if point == "before-pointer":
            # (nothing left to run): after-pointer needs another install
            bc(delete=True)
            time.sleep(2)
            bc(dieat="after-pointer", start=True)
            continue
        bc(dieat=nxt, resume=True)
    bc(dieat="")
    ok, how = hashes_ok("ksa-2026.11")
    st = stats()
    result("kill points", ok and len(seen) == len(steps), f"died at {seen}; afterwards {how}; served {st['served_total']} (a second install is in this: {total} each)")


def drops():
    wipe()
    m, total, largest = manifest_totals()
    ctl("drop_mb=5")
    start_app()
    t0 = time.time()
    bc(start=True)
    ok = install_and_check("drops every 5 MB")
    st = stats()
    took = time.time() - t0
    waits = len(T.log.since(t0, r"failure \d+"))
    result("drops every 5 MB (close)", ok and st["served_total"] <= total + st["drops"] * 70 * 1024 + largest, f"{st['drops']} drops, {took:.0f} s; served {st['served_total']}, {st['served_total'] - total} over {total}, the overlap's doing; chunks found wrong {len(T.log.since(t0, r'hash [0-9a-f]+, wanted'))}; {T.last_hash}")
    # the same with a hard reset
    wipe()
    ctl("drop_mb=7&drop_rst=1")
    start_app()
    t0 = time.time()
    bc(start=True)
    ok = install_and_check("drops every 7 MB (reset)")
    st = stats()
    wrong = T.log.since(t0, r"hash [0-9a-f]+, wanted")
    result("drops every 7 MB (reset)", ok, f"{st['drops']} resets, {time.time() - t0:.0f} s; served {st['served_total']}, {st['served_total'] - total} over {total}; chunks found wrong and fetched again: {len(wrong)}; {T.last_hash}")
    ctl("reset=1")


def faults():
    wipe()
    m, total, largest = manifest_totals()
    # a wrong byte in one chunk, another version spliced in another, and what a hotspot's portal and a busy CDN answer
    # (first on the manifest: a busy server, then a sign-in page: it waits, it does not call the pack bad)
    ctl("portal=1&status=503,1")
    start_app()
    t0 = time.time()
    bc(start=True)
    wait_log(r"failure 2", t0, 30)
    ctl("corrupt=base.mbtiles.004.gz&splice=places.mbtiles.000.gz&slow_kbps=30000")
    wait_percent(2, 120)
    ctl("portal=2&status=503,2&stall=1&slow_kbps=0")
    ok = install_and_check("faults")
    st = stats()
    wrong = T.log.since(t0, r"hash [0-9a-f]+, wanted")
    extra = st["served_total"] - total
    sizes = {c["url"]: c["bytes"] for p in m["pieces"] for c in p["chunks"]}
    allowed = sizes["base.mbtiles.004.gz"] + sizes["places.mbtiles.000.gz"] * 2
    result("wrong byte, splice, portal, 503, a frozen link", ok and len(wrong) == 2 and extra <= allowed, f"faults {st['faults']}; chunks refused by their hash: {len(wrong)}; {extra} bytes over; took {time.time() - t0:.0f} s; {T.last_hash}")
    # the same chunk wrong every time: it stops and says so, nothing broken, and goes on when asked
    wipe()
    ctl("corrupt=glyphs.db.000.gz&corrupt_times=3")
    start_app()
    bc(start=True)
    s = wait_state(["FAILED", "INSTALLED"], 900)
    good = s.get("state") == "FAILED" and s.get("problem") == "BAD_DATA"
    bc(resume=True)
    ok = install_and_check("wrong three times")
    result("wrong three times", good and ok, f"stopped with {s.get('state')}/{s.get('problem')} at {s.get('percent')}%; then resumed and finished; {T.last_hash}")
    # a Range the server ignores, and a Range answered with the wrong place, on a stopped part
    wipe()
    ctl("slow_kbps=20000")
    start_app()
    bc(start=True)
    wait_percent(3)
    bc(pause=True)
    wait_state("PAUSED", 60)
    time.sleep(1)
    part = sh(f"ls -l {app_dir()}/maps2/staging/*/*.part | head -1").split()
    ctl("reset=1&no_range=1")
    bc(resume=True)
    wait_percent(10)
    bc(pause=True)
    wait_state("PAUSED", 60)
    time.sleep(1)
    ctl("reset=1&wrong_range=1")
    t0 = time.time()
    bc(resume=True)
    time.sleep(4)
    ctl("reset=1")
    ok = install_and_check("range faults")
    words = T.log.since(t0, r"asked from \d+, got")
    result("Range ignored, wrong Content-Range", ok and bool(words), f"part kept at the pause {part[4] if len(part) > 4 else '?'} bytes; {words[-1].split(': ', 1)[-1] if words else 'no wrong range seen'}; {T.last_hash}")


def update():
    """The old pack keeps working until the new one is verified; a live reader through the switch."""
    wipe()
    m, total, largest = manifest_totals()
    start_app()
    bc(start=True)
    install_and_check("first pack")
    sh(f"mkdir -p {app_dir()}/offline/segments4; cp {app_dir()}/maps2/packs/ksa-2026.11/glyphs.db {app_dir()}/offline/map.db; echo x > {app_dir()}/offline/segments4/E45_N25.rd5")
    own(f"{app_dir()}/offline")
    stop_app()
    ctl("clear=1&latest=data-2026.12&patch=data-2026.12,id,\"ksa-2026.12\"&patch=data-2026.12,built,\"2026-12-01T00:00Z\"")
    sh(f"setprop debug.aura.pack.base http://127.0.0.1:{T.port}/releases/latest/download/")
    start_app()
    t0 = time.time()
    bc(reader=True)
    time.sleep(2)
    bc(check=True)
    s = wait_state(["OFFER"], 30)
    time.sleep(5)
    st = stats()
    offered = "update=true" in s.get("offer", "") and "ksa-2026.12" in s.get("offer", "") and st["served_total"] < 20000
    result("a newer manifest offers", offered, f"offer {s.get('offer')}; 5 s later {st['served_total']} bytes served: it did not start by itself")
    ctl("slow_kbps=40000")
    bc(start=True)
    wait_percent(40)
    bc(dump=True)
    time.sleep(0.5)
    during = T.log.since(t0, r"paths base")[-1]
    p = pid()
    sh(f"kill -9 {p}")
    time.sleep(1)
    start_app()
    bc(reader=True)
    time.sleep(1.5)
    bc(dump=True)
    time.sleep(0.5)
    afterkill = T.log.since(t0, r"paths base")[-1]
    s1 = status()
    ctl("reset=1&latest=data-2026.12&patch=data-2026.12,id,\"ksa-2026.12\"&patch=data-2026.12,built,\"2026-12-01T00:00Z\"")
    t_resume = time.time()
    bc(resume=True)
    s2 = wait_state(["INSTALLED", "FAILED"], 900)
    ok, how = hashes_ok("ksa-2026.12")
    time.sleep(3)
    bc(reader=False)
    time.sleep(1.5)
    moved = T.log.since(t_resume, r"reader now on .*ksa-2026\.12")
    ended = T.log.since(t_resume, r"reader ended")
    old_there = sh(f"ls {app_dir()}/maps2/packs")
    old_ok = "2026.11" in during and "2026.11" in afterkill and s1.get("current") == "ksa-2026.11" and s1.get("state") == "PAUSED"
    errs = re.search(r"(\d+) errors", ended[-1]) if ended else None
    result("old pack in use until the switch", ok and old_ok and bool(moved) and s2.get("current") == "ksa-2026.12" and "ksa-2026.11" in old_there and errs and errs.group(1) == "0",
           f"at 40%: {during.split('paths ', 1)[1][:90]}; after kill -9: current {s1.get('current')}, {s1.get('state')}; after: current {s2.get('current')}, built {s2.get('built')}; {moved[-1].split('AuraPackDebug', 1)[-1] if moved else 'reader did not move'}; {ended[-1].split('reader ended: ', 1)[-1] if ended else ''}; packs: {old_there.split()}; {how}")
    # it has worked: the old pack goes, the old map can go, BRouter's squares stay
    bc(reader=True)
    time.sleep(1.5)
    before = status().get("oldMap")
    bc(worked=True)
    time.sleep(3)
    left = sh(f"ls {app_dir()}/maps2/packs")
    s3 = status()
    bc(free=True)
    time.sleep(2)
    off = sh(f"ls {app_dir()}/offline {app_dir()}/offline/segments4")
    s4 = status()
    bc(reader=False)
    time.sleep(1)
    result("after it has worked", "2026.11" not in left and before == "0" and int(s3.get("oldMap", 0)) > 0 and "map.db" not in off and "E45_N25.rd5" in off and s4.get("oldMap") == "0",
           f"oldMap before worked {before}, after {s3.get('oldMap')}; packs left {left.split()}; offline after free: {off.split()}")
    # a pack on its way is thrown away by cancel; the one in use stays
    ctl("reset=1&latest=data-2026.12&patch=data-2026.13,id,\"ksa-2026.13\"&patch=data-2026.13,built,\"2027-01-01T00:00Z\"&latest=data-2026.13&slow_kbps=30000")
    bc(check=True)
    wait_state("OFFER", 30)
    bc(start=True)
    wait_percent(5)
    bc(cancel=True)
    s5 = wait_state(["OFFER", "INSTALLED"], 60)
    st_dir = sh(f"ls {app_dir()}/maps2/staging 2>/dev/null; ls {app_dir()}/maps2/trash 2>/dev/null")
    ok6, how6 = hashes_ok("ksa-2026.12")
    result("cancel", s5.get("current") == "ksa-2026.12" and not st_dir and ok6, f"after cancel {s5.get('state')}, current {s5.get('current')}, staging+trash {st_dir!r}; {how6}")
    # delete: everything goes, PackPaths says null
    ctl("reset=1")
    sh(f"setprop debug.aura.pack.base http://127.0.0.1:{T.port}/releases/download/data-2026.11/")
    stop_app()
    start_app()
    t = time.time()
    bc(delete=True)
    time.sleep(3)
    bc(dump=True)
    time.sleep(0.6)
    paths = T.log.since(t, r"paths base")[-1:]
    result("delete", bool(paths) and "base=null" in paths[-1] and not sh(f"ls {app_dir()}/maps2/packs 2>/dev/null"), f"{paths[-1].split('paths ', 1)[1] if paths else ''}")


def own(path):
    """What root put in the app's folder becomes the app's."""
    sh(f"chown -R $(stat -c %u {app_dir()}):$(stat -c %g {app_dir()}) {path}; restorecon -R {path}")


def push_fake_usb(where):
    """The pack's files in a folder on the emulator, standing in for a stick."""
    sh(f"rm -rf {where}; mkdir -p {where}/amri-maps")
    subprocess.run([ADB, "-s", T.serial, "push", "--sync", T.pack + os.sep, f"{where}/amri-maps/"], capture_output=True, timeout=600)
    got = sh(f"ls {where}/amri-maps | wc -l; ls {where}/amri-maps/pack 2>/dev/null | wc -l")
    if got.split()[-1] != "0":  # (adb push of a folder may land inside its own name)
        sh(f"mv {where}/amri-maps/pack/* {where}/amri-maps/; rmdir {where}/amri-maps/pack")
    return sh(f"ls {where}/amri-maps | wc -l")


def usb():
    wipe()
    m, total, largest = manifest_totals()
    where = f"{app_dir()}/fakeusb"
    n = push_fake_usb(where)
    own(where)
    sh(f"setprop debug.aura.pack.usb {where}; setprop debug.aura.pack.base ''")
    start_app()
    t0 = time.time()
    bc(scan=True)
    s = wait_state(["OFFER"], 60)
    found = "ksa-2026.11" in s.get("usbOffer", "")
    bc(startusb=True)
    wait_percent(25)
    # pulled out in the middle
    sh(f"mv {where} {where}.out")
    s2 = wait_state(["FAILED"], 90)
    gone = s2.get("problem") == "USB_GONE"
    time.sleep(1)
    keep = s2.get("percent")
    sh(f"mv {where}.out {where}")
    bc(resume=True)
    ok = install_and_check("usb")
    took = time.time() - t0
    st = stats()
    result("usb install, folder readable", found and gone and ok and st["served_total"] == 0, f"{n} files; offer {s.get('usbOffer')}; pulled at about 25%: {s2.get('state')}/{s2.get('problem')} at {keep}%; plugged in, resumed, finished in {took:.0f} s in all; 0 bytes from the network; {T.last_hash}")
    sh(f"rm -rf {where}")


def usbroot():
    """Through the root shell, as on the unit if the app cannot read the mount: the fake adbd speaks the wire protocol and runs each command on the emulator as root."""
    wipe()
    m, total, largest = manifest_totals()
    real = "/data/local/tmp/fakeusb1"
    n = push_fake_usb(real)
    sh(f"chmod -R 700 {real}; mkdir -p /mnt/media_rw/udisk1; ln -sf {real}/amri-maps /mnt/media_rw/udisk1/amri-maps; chmod 700 /mnt/media_rw/udisk1")
    srv = subprocess.Popen([sys.executable, "-I", os.path.join(HERE, "fake_adbd.py"), "--adb", ADB, "--serial", T.serial, "--port", "15578"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        time.sleep(1)
        adb("reverse", "tcp:15578", "tcp:15578")
        sh("setprop debug.aura.pack.base ''")
        start_app()
        t0 = time.time()
        # (only the pack manager's commands: the app's own start-up keeps its hands off the emulator)
        bc(adb="127.0.0.1:15578", scan=True)
        s = wait_state(["OFFER", "NONE"], 60)
        found = "ksa-2026.11" in s.get("usbOffer", "")
        bc(startusb=True)
        wait_percent(20)
        p = pid()
        sh(f"kill -9 {p}")
        time.sleep(1)
        start_app()
        s2 = status()
        bc(adb="127.0.0.1:15578", resume=True)
        ok = install_and_check("usb root")
        took = time.time() - t0
        st = stats()
        owner = sh(f"ls -ln {app_dir()}/maps2/packs/ksa-2026.11 | head -4")
        result("usb install through the root shell", found and ok and st["served_total"] == 0, f"{n} files; found {s.get('usbOffer')}; kill -9 at 20%, restart says {s2.get('state')} {s2.get('percent')}%, resumed; {took:.0f} s in all; {T.last_hash}; owner stays the app: {owner.splitlines()[1:2]}")
    finally:
        srv.kill()
        adb("reverse", "--remove", "tcp:15578")
        sh(f"rm -rf {real} /mnt/media_rw/udisk1")


def trip():
    wipe()
    m, total, largest = manifest_totals()
    ctl("slow_kbps=30000")
    start_app()
    bc(start=True)
    wait_percent(10)
    t0 = time.time()
    bc(trip=True)
    s = wait_state("TRIP_HOLD", 30)
    held_after = time.time() - t0
    a = stats()["served_total"]
    time.sleep(6)
    b = stats()["served_total"]
    line = T.log.since(t0, r"line: ")
    bc(trip=False)
    s2 = wait_state(["FETCHING"], 30)
    time.sleep(1)
    # by the user, in the middle
    bc(pause=True)
    s3 = wait_state("PAUSED", 30)
    time.sleep(1)
    c = stats()["served_total"]
    time.sleep(5)
    d = stats()["served_total"]
    # hands that do not wait: nothing may stick or run twice
    bc(resume=True)
    bc(pause=True)
    bc(resume=True)
    bc(pause=True)
    bc(resume=True)
    ctl("reset=1")
    wait_log(r"status state=VERIFYING", t1 := time.time(), 400)
    # a trip during the whole-file hash
    t1 = time.time()
    bc(trip=True)
    s4 = wait_state(["TRIP_HOLD", "INSTALLED"], 30)
    held2 = time.time() - t1
    cpu = proc_cpu(pid())
    time.sleep(3)
    cpu2 = proc_cpu(pid())
    bc(trip=False)
    ok = install_and_check("trip")
    result("no work during a trip", s.get("state") == "TRIP_HOLD" and b - a <= 256 * 1024 and d - c == 0 and ok and s4.get("state") == "TRIP_HOLD" and cpu2[0] - cpu[0] <= 2, f"held {held_after:.1f} s after the trip began, {b - a} bytes in 6 s of it; {(line[-1].split('line: ', 1)[-1]) if line else ''}; pause: {d - c} bytes in 5 s; during the hash: {s4.get('state')} in {held2:.1f} s, its thread used {cpu2[0] - cpu[0]} jiffies in 3 s; {T.last_hash}")


def reboot_common(name, how):
    wipe()
    m, total, largest = manifest_totals()
    ctl("slow_kbps=30000")
    start_app()
    bc(start=True)
    wait_percent(45)
    before = sh(f"cat {app_dir()}/maps2/staging/*/progress.json")
    done_before = len(json.loads(before)["done"]) if before.startswith("{") else -1
    how()
    time.sleep(5)
    for _ in range(180):
        try:
            if adb("get-state", timeout=20) == "device" and sh("getprop sys.boot_completed", timeout=20) == "1":
                break
        except subprocess.TimeoutExpired:
            pass
        time.sleep(2)
    adb("root")
    time.sleep(3)
    adb("wait-for-device", timeout=60)
    adb("reverse", f"tcp:{T.port}", f"tcp:{T.port}")
    sh(f"setprop debug.aura.pack.base http://127.0.0.1:{T.port}/releases/download/data-2026.11/")
    start_app()
    a = stats()["served_total"]
    s = status()
    time.sleep(5)
    b = stats()["served_total"]
    after = sh(f"cat {app_dir()}/maps2/staging/*/progress.json")
    done_after = len(json.loads(after)["done"]) if after.startswith("{") else -1
    parts = sh(f"ls -l {app_dir()}/maps2/staging/*/*.part 2>/dev/null")
    bc(resume=True)
    ctl("reset=1")
    ok = install_and_check(name)
    st = stats()
    result(name, ok and s.get("state") == "PAUSED" and a == b and done_after >= done_before - 1, f"chunks marked before {done_before}, after {done_after}; state {s.get('state')} at {s.get('percent')}%; {b - a} bytes asked for on its own; part left: {parts.split()[4:5]}; served {st['served_total']} of {total}, {st['served_total'] - total} over; {T.last_hash}")


def reboot():
    reboot_common("reboot", lambda: adb("reboot"))


def powercut():
    # no sync, no unmount: the machine stops as it is, what the kernel had not written is gone
    def cut():
        try:
            subprocess.run([ADB, "-s", T.serial, "shell", "echo 1 > /proc/sys/kernel/sysrq; echo b > /proc/sysrq-trigger"], capture_output=True, timeout=20)
        except subprocess.TimeoutExpired:
            pass
    reboot_common("power cut (sysrq b)", cut)


def fulldisk():
    """A really full disk, past the check: the emulator believes it has room, then has none."""
    wipe()
    filler = "/data/local/tmp/packfiller"
    try:
        free = int(sh("df -k /data | tail -1").split()[3]) * 1024
        keep = 300 * 1024 * 1024
        sh(f"fallocate -l {free - keep} {filler}")
        left = int(sh("df -k /data | tail -1").split()[3]) * 1024
        sh("setprop debug.aura.pack.free 2000000000")
        start_app()
        p = pid()
        bc(start=True)
        s = wait_state(["NO_SPACE", "FAILED"], 120)
        alive = pid() == p
        real_free = int(sh("df -k /data | tail -1").split()[3]) * 1024
        result("a disk that really is full", s.get("state") == "NO_SPACE" and alive, f"{left // 1048576} MB really free; state {s.get('state')}/{s.get('problem')}; process alive {alive}; {real_free // 1048576} MB free after; in staging: {sh('ls -la ' + app_dir() + '/maps2/staging/* 2>/dev/null | head -8').splitlines()[:8]}")
    finally:
        sh(f"rm -f {filler}; setprop debug.aura.pack.free ''")
    bc(cancel=True)
    time.sleep(3)
    s = status()
    print("    after cancel:", s.get("state"), sh(f"ls {app_dir()}/maps2/staging 2>/dev/null"), sh("df -h /data | tail -1"))


def hotspot():
    """What it costs at a phone's speed: a slow link for a minute, then stopped."""
    wipe()
    ctl("slow_kbps=1500")
    start_app()
    p = pid()
    pss0 = pss_kb()
    bc(start=True)
    time.sleep(8)
    c0 = proc_cpu(p)
    t0 = time.time()
    pss = []
    speeds = []
    for _ in range(9):
        time.sleep(5)
        pss.append(pss_kb())
        s = status()
        speeds.append(int(s.get("speed", 0)))
    c1 = proc_cpu(p)
    took = time.time() - t0
    bc(cancel=True)
    wait_state(["OFFER", "NONE"], 40)
    ctl("reset=1")
    result("at 1.5 MB/s", True, f"shown speed {min(speeds) // 1024}-{max(speeds) // 1024} KB/s; thread aura-pack {(c1[0] - c0[0]) / took:.1f}% of one core, process {(c1[1] - c0[1]) / took:.1f}%; PSS {pss0 // 1024} MB before, {min(pss) // 1024}-{max(pss) // 1024} MB while working (emulator)")


def misc():
    """Files as the others will find them; the words; crashes in all of this."""
    wipe()
    ctl("reset=1&clear=1")
    start_app()
    bc(start=True)
    wait_state("INSTALLED", 300)
    t0 = time.time()
    bc(reader=True)
    time.sleep(2)
    bc(reader=False)
    time.sleep(1)
    ended = T.log.since(t0, r"reader ended")
    listing = sh(f"ls -la {app_dir()}/maps2/packs/ksa-2026.11; ls -la {app_dir()}/maps2; cat {app_dir()}/maps2/current; echo; cat {app_dir()}/maps2/last-install.txt")
    print(listing)
    side = [l for l in listing.splitlines() if "-wal" in l or "-shm" in l or "-journal" in l]
    pieces_ro = all(l.startswith("-r--") for l in listing.splitlines() if re.search(r"(mbtiles|route\.tar|places\.db|glyphs\.db)$", l))
    result("files as the others find them", not side and pieces_ro and bool(ended) and " 0 errors" in ended[-1], f"pieces read only {pieces_ro}, side files {side}; a read-only open: {ended[-1].split('reader ended: ', 1)[-1] if ended else ''}")
    # Arabic
    stop_app()
    sh(f"am start -n {T.pkg}/com.abdllh.aura.settings.SettingsActivity --es lang ar")
    time.sleep(4)
    t1 = time.time()
    bc(pause=False, dump=True)
    time.sleep(0.8)
    line = T.log.since(t1, r"line: ")
    print("    ", line[-1] if line else "no line")
    sh(f"am start -n {T.pkg}/com.abdllh.aura.settings.SettingsActivity --es lang en")
    time.sleep(1)
    # the real home screen on top of a pack, and what crashed anywhere in this session
    sh(f"am start -n {T.pkg}/com.abdllh.aura.home.HomeActivity")
    time.sleep(6)
    alive = bool(pid())
    crash = sh(f"logcat -d -b crash | grep -E 'Process: {T.pkg}|FATAL' | tail -5")
    result("home screen, crashes", alive and T.pkg not in crash, f"home alive {alive}; crash buffer for this app: {crash!r}")


SCENARIOS = {"hotspot": hotspot, "misc": misc, "refusals": refusals, "space": space, "full": full, "kill": kill, "points": points, "drops": drops, "faults": faults, "update": update, "usb": usb, "usbroot": usbroot,
             "trip": trip, "reboot": reboot, "powercut": powercut, "fulldisk": fulldisk}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("scenarios", nargs="+")
    ap.add_argument("--serial", default="emulator-5578")
    ap.add_argument("--pack", default=r"D:\k2501-work\mapbuild\out\pack")
    ap.add_argument("--package", default="com.abdllh.aura.standin")
    ap.add_argument("--port", type=int, default=8791)
    ap.add_argument("--lock", default=None)
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    T.serial, T.pack, T.pkg, T.port, T.results = a.serial, a.pack, a.package, a.port, []
    T.lock_file = a.lock
    T.last_hash = ""
    T.fg = ""
    if a.lock:
        threading.Thread(target=touch_lock, daemon=True).start()
    server = None
    try:
        stats()
    except Exception:
        server = subprocess.Popen([sys.executable, "-I", os.path.join(HERE, "pack_server.py"), "--root", a.pack, "--port", str(a.port)], stdout=subprocess.DEVNULL)
        time.sleep(1)
    adb("root")
    time.sleep(2)
    adb("wait-for-device", timeout=60)
    adb("reverse", f"tcp:{a.port}", f"tcp:{a.port}")
    T.log = Logcat()
    T.log.start()
    time.sleep(1)
    start_app()  # (so that its files folder is its own, whatever comes first)
    stop_app()
    names = list(SCENARIOS) if a.scenarios == ["all"] else a.scenarios
    try:
        for n in names:
            print(f"--- {n}", flush=True)
            t0 = time.time()
            try:
                SCENARIOS[n]()
            except Exception as e:
                result(n, False, f"{type(e).__name__}: {e}")
            print(f"    ({time.time() - t0:.0f} s)", flush=True)
    finally:
        T.log.stop_it = True
        if T.log.proc:
            T.log.proc.kill()
        stop_app()
        if server:
            server.kill()
        adb("reverse", "--remove", f"tcp:{a.port}")
        T.lock_file = None
    bad = [r for r in T.results if not r["ok"]]
    print(f"{len(T.results) - len(bad)} of {len(T.results)} passed")
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            json.dump(T.results, f, ensure_ascii=False, indent=1)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
