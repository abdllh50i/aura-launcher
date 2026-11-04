#!/usr/bin/env python3
"""
A kept trip after a power-off (nav/TripMemory.kt, docs/DEV.md): the card asks, no answer is no, the voice's stop reaches it.

    python scripts/trip-resume-test.py [--serial emulator-5570]

Needs the debug build installed and root (adb root): it writes the kept trip, as another boot left it, into the app's
preferences, and drives the fixes with the debug broadcast. A route needs the internet (OSRM, BRouter) on the accept case.
Exit code 1 when something failed.
"""
import argparse
import json
import re
import subprocess
import sys
import time
import xml.sax.saxutils as sx

ADB = r"E:\Android\Sdk\platform-tools\adb.exe"
PKG = "com.abdllh.aura"
PREFS = "/data/data/%s/shared_prefs/aura.xml" % PKG
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (("   " + str(detail)) if detail and not ok else ""), flush=True)
    return ok


def adb(*args, timeout=60):
    try:
        return subprocess.run([ADB, "-s", SERIAL] + list(args), capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def shell(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout).strip()


def wait(pred, seconds, every=0.5):
    end = time.time() + seconds
    while time.time() < end:
        v = pred()
        if v:
            return v
        time.sleep(every)
    return None


def nav(*extras):
    shell("am broadcast -a com.abdllh.aura.debug.NAV %s >/dev/null" % " ".join(extras))


def fix(lat, lon):
    nav("--es", "fix", "%s,%s,0,0" % (lat, lon))


def state():
    adb("logcat", "-c")
    nav("--ez", "dump", "true")
    time.sleep(1)
    m = re.search(r"nav state=(\w+)", adb("logcat", "-d", "-s", "AuraNavDebug:I"))
    return m.group(1) if m else ""


def card_up():
    """The card is an overlay: dumpsys sees it, uiautomator does not."""
    return "AuraTrip" in shell("dumpsys window windows | grep -E 'Window #'")


def press(which):
    """The wide card's buttons, by position on the 1024 x 600 screen, as phone-server-test.py has them; Arabic is the mirror image."""
    x, y = {"yes": (735, 62), "no": (820, 62)}[which]
    lang = shell("cat " + PREFS)
    arabic = 'name="lang">ar' in lang or shell("getprop persist.sys.locale").startswith("ar")
    shell("input tap %d %d" % ((1024 - x) if arabic else x, y))


def kept():
    return "navTrip" in shell("cat %s" % PREFS)


def seed(minutes_ago=2, name="الراشد مول", lat=26.35, lon=50.15):
    """The trip, as a process of another boot left it, standing at 26.40,50.09."""
    shell("am force-stop %s" % PKG)
    time.sleep(1)
    now_ms = int(shell("date +%s")) * 1000
    o = {"dest": {"name": name, "detail": "", "lat": lat, "lon": lon}, "boot": "another-boot", "at": now_ms - minutes_ago * 60000,
         "elapsed": 900000000, "tries": 0, "remember": True, "lat": 26.40, "lon": 50.09}
    xml = adb("exec-out", "cat " + PREFS)
    xml = re.sub(r'\s*<string name="navTrip">.*?</string>', "", xml, flags=re.S)
    entry = '<string name="navTrip">%s</string>' % sx.escape(json.dumps(o, ensure_ascii=False), {'"': "&quot;"})
    xml = xml.replace("</map>", "    " + entry + "\n</map>")
    tmp = "trip-resume-prefs.xml"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(xml)
    adb("push", tmp, "/data/local/tmp/aura-prefs.xml")
    shell("cp /data/local/tmp/aura-prefs.xml %s; chown $(stat -c %%u:%%g %s) %s; rm -f /data/local/tmp/aura-prefs.xml" % (PREFS, "/data/data/%s/files" % PKG, PREFS))
    try:
        import os
        os.remove(tmp)
    except OSError:
        pass
    # (again: Android rebinds the notification listener at once, so the process was back, with the file as it was)
    shell("am force-stop %s" % PKG)
    adb("logcat", "-c")
    shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME %s >/dev/null" % PKG)
    wait(lambda: "a trip was kept" in log_tail(), 15)  # (the resume looks 4 s after the start)


def log_tail():
    return adb("logcat", "-d", "-s", "AuraTrip:I")


def t_no_answer():
    seed()
    fix(26.40, 50.09)
    check("no answer: the card asks", wait(card_up, 6))
    check("no answer: nothing started", state() == "IDLE")
    check("no answer: gone after 15 s", wait(lambda: not card_up(), 22, 1))
    check("no answer: no trip", state() == "IDLE")
    check("no answer: nothing kept", wait(lambda: not kept(), 5))


def t_declined():
    seed()
    fix(26.40, 50.09)
    check("no: the card asks", wait(card_up, 6))
    time.sleep(1)
    press("no")
    check("no: the card goes", wait(lambda: not card_up(), 5))
    check("no: nothing kept", wait(lambda: not kept(), 5))
    check("no: no trip", state() == "IDLE")


def t_accepted():
    seed()
    fix(26.40, 50.09)
    check("yes: the card asks", wait(card_up, 6))
    time.sleep(1)
    press("yes")
    print("     (a route needs the internet)")
    ok = wait(lambda: state() == "NAVIGATING", 40, 2)
    check("yes: the trip goes on", ok, log_tail()[-400:])
    nav("--ez", "end", "true")
    time.sleep(1)


def t_voice_stop():
    seed()
    adb("logcat", "-c")
    shell("am broadcast -a com.abdllh.aura.debug.AMRI --es say 'وقف الملاحة' >/dev/null")
    time.sleep(2)
    check("voice: the stop reaches a trip still waiting", "stopped by voice" in log_tail(), log_tail()[-300:])
    check("voice: nothing kept", wait(lambda: not kept(), 5))
    fix(26.40, 50.09)
    time.sleep(3)
    check("voice: no card after it", not card_up())
    check("voice: no trip after it", state() == "IDLE")


def t_near():
    seed(lat=26.40, lon=50.098)  # (about 800 m from where the car stands)
    fix(26.40, 50.09)
    check("parked 800 m from the place: he is there", wait(lambda: "ARRIVED" in log_tail(), 5), log_tail()[-300:])
    check("parked 800 m from the place: no card", not card_up())


def t_call():
    seed()
    fix(26.40, 50.09)
    check("a call: first the card", wait(card_up, 6))
    shell("am broadcast -a com.bt.ACTION_BT_INCOMING_CALL >/dev/null")
    time.sleep(0.5)
    fix(26.40, 50.09)
    check("a call: the card makes way", wait(lambda: not card_up(), 5))
    shell("am broadcast -a com.bt.ACTION_BT_END_CALL >/dev/null")
    time.sleep(0.5)
    fix(26.40, 50.09)
    check("a call's end: it asks again", wait(card_up, 6))
    time.sleep(1)
    press("no")
    time.sleep(1)


def main():
    global SERIAL
    p = argparse.ArgumentParser()
    p.add_argument("--serial", default="emulator-5570")
    a = p.parse_args()
    SERIAL = a.serial
    adb("root")
    time.sleep(2)
    shell("appops set %s SYSTEM_ALERT_WINDOW allow" % PKG)
    for t in (t_no_answer, t_declined, t_voice_stop, t_near, t_call, t_accepted):
        print("--- " + t.__name__, flush=True)
        t()
    failed = [n for n, ok in results if not ok]
    print("\n%d checks, %d failed" % (len(results), len(failed)))
    for n in failed:
        print("  FAILED: " + n)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
