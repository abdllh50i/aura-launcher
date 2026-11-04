#!/usr/bin/env python3
"""
The guard and the way back against what the firmware does to a package that stops being home (guard/BackgroundList.kt),
which the emulator does not do by itself: scripts/fake_bms.sh stands in for it. docs/DEV.md, "The guard".

    python scripts/guard-test.py --old OLD.apk --new NEW.apk [--serial emulator-5570] [--only name,name] [--list]

OLD: a debug build of a lower version; NEW: a debug build of 2.0.0 or higher, the one on trial (same key). The root shell
is scripts/fake_adbd.py. Needs `adb root`. Leaves the emulator with NEW's data as it ends, home set back to AMRI, the kill
switch off. Exit code 1 when something failed.
"""
import argparse
import hashlib
import os
import subprocess
import sys
import time

ADB = r"E:\Android\Sdk\platform-tools\adb.exe"
HERE = os.path.dirname(os.path.abspath(__file__))
PKG = "com.abdllh.aura"
HOME_PROP = "persist.nwd.launcher.default"
OFF_PROP = "persist.aura.disabled"
DIR = "/data/local/tmp/amri-rollback"
FILES = "/data/data/%s/files" % PKG
ADB_PORT = 15555
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (("   " + str(detail)) if detail and not ok else ""), flush=True)
    return ok


def adb(*args, timeout=120):
    try:
        return subprocess.run([ADB, "-s", SERIAL] + list(args), capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        print("     (adb %s: no answer)" % " ".join(args)[:60])
        return ""


def shell(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout).strip()


def prop(k):
    return shell("getprop " + k)


def wait(pred, seconds, every=0.5):
    end = time.time() + seconds
    while time.time() < end:
        v = pred()
        if v:
            return v
        time.sleep(every)
    return None


def version():
    out = shell("dumpsys package %s | grep -m1 versionName" % PKG)
    return out.split("=")[-1].strip()


def apk_version(path):
    return VERSIONS[path]


def guard_log(since=0):
    lines = shell("cat %s/guard-log.txt 2>/dev/null" % FILES).splitlines()
    return lines[since:]


def main_pid():
    return shell("pidof %s" % PKG).split(" ")[0]


def guard_pid():
    return shell("pidof %s:guard" % PKG).split(" ")[0]


def front():
    return shell("dumpsys activity activities | grep -m1 mResumedActivity")


def trial():
    return shell("cat %s/guard-trial 2>/dev/null" % FILES).split(" ")


def bms_start():
    adb("push", os.path.join(HERE, "fake_bms.sh"), "/data/local/tmp/fake_bms.sh")
    # (a process of its own for good: adb exec-out would wait for it)
    subprocess.Popen([ADB, "-s", SERIAL, "shell", "nohup sh /data/local/tmp/fake_bms.sh > /data/local/tmp/fake_bms.log 2>&1 &"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    wait(lambda: "up," in shell("cat /data/local/tmp/fake_bms.log 2>/dev/null"), 10)


def bms_stop():
    shell("rm -f /data/local/tmp/fake_bms.run")
    time.sleep(1)


def bms_log():
    return shell("cat /data/local/tmp/fake_bms.log 2>/dev/null")


def clean(extra_props=()):
    """Nothing left of the last test: not on any list, no marks, no trial, AMRI home. It ends with a force-stop, and the process comes back by itself (its notification listener), with these properties."""
    bms_stop()
    shell("setprop %s 0; setprop %s %s; rm -f /data/aura_disabled %s/disable_home %s/guard-marks %s/guard-log.txt %s/guard-trial; rm -f /data/data/%s/shared_prefs/aura_crash.xml" % (OFF_PROP, HOME_PROP, PKG, FILES, FILES, FILES, FILES, PKG))
    shell("settings delete system whitelist_packagename; settings delete system key_bt_state; settings delete system mcu_backcar_state; settings delete system activity_starting")
    shell("cmd package set-home-activity %s/.home.HomeActivity" % PKG)
    for k in ("throw", "hang", "hangstart", "slow", "trialmin", "livedsec"):
        shell("setprop debug.aura.guard.%s ''" % k)
    for k, v in extra_props:
        shell("setprop %s %s" % (k, v))
    shell("cmd notification allow_listener %s" % LISTENER)
    shell("rm -f %s/guard-marks %s/guard-log.txt %s/guard-trial; am force-stop %s" % (FILES, FILES, FILES, PKG))
    time.sleep(1)


def install(apk):
    out = adb("install", "-r", "-d", apk, timeout=180)
    if "Success" not in out:
        print("     install: " + out)
    return "Success" in out


def save_copy(apk):
    """The way back's saved copy, as Rollback.saveRunning leaves it."""
    h = hashlib.sha256(open(apk, "rb").read()).hexdigest()
    name, code = VERSIONS[apk]
    shell("mkdir -p %s; rm -f %s/result %s/running" % (DIR, DIR, DIR))
    adb("push", apk, DIR + "/aura-prev.apk", timeout=180)
    size = os.path.getsize(apk)
    shell("""printf '{"version":"%s","code":%s,"sha256":"%s","size":%d,"source":"self"}\\n' > %s/aura-prev.json; chmod 644 %s/aura-prev.*""" % (name, code, h, size, DIR, DIR))


LISTENER = PKG + "/.media.AuraNotificationListener"


def stopped():
    """AMRI not running, and nothing to bring it back: Android rebinds a notification listener right after a force-stop, which is
    what would have restarted the process here (and the guard with it: on the unit the firmware refuses that start). Launcher3 is
    home for now; fake_bms puts home where the property says."""
    shell("cmd package set-home-activity com.android.launcher3/.Launcher; am start -a android.intent.action.MAIN -c android.intent.category.HOME")
    for _ in range(6):
        # (AMRI's own start may allow it again a moment later: until it stays gone)
        shell("cmd notification disallow_listener %s; am force-stop %s" % (LISTENER, PKG))
        time.sleep(2.5)
        if not main_pid():
            return
    print("     (AMRI came back by itself: %s)" % shell("ps -A | grep abdllh"))


def seed_crash_loop():
    """Three crashes just now, as CrashGuard and the guard would have them: the next start is the one that finds the loop.
    (The emulator's own crash loop does not get there the way the unit's does: Android forgets the default home after the second crash, and a chooser comes up.)"""
    boot = shell("cat /proc/sys/kernel/random/boot_id")
    now = int(float(shell("cut -d' ' -f1 /proc/uptime")) * 1000)
    crashes = ",".join(str(now - i * 2000) for i in (3, 2, 1))
    shell("""printf '%%s\\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' '<string name="crashes">%s|%s</string>' '</map>' > /data/data/%s/shared_prefs/aura_crash.xml""" % (boot, crashes, PKG))
    code = VERSIONS[NEW][1]
    marks = "".join("S %s %d %d %s\\n" % (boot[:8], now - i * 2000, 90000 + i, code) for i in (3, 2, 1))
    shell("printf '%s' > %s/guard-marks" % (marks, FILES))
    shell("U=$(stat -c %%u:%%g %s); chown $U %s/guard-marks /data/data/%s/shared_prefs/aura_crash.xml" % (FILES, FILES, PKG))


def no_copy():
    shell("rm -f %s/aura-prev.apk %s/aura-prev.json %s/result %s/running" % (DIR, DIR, DIR, DIR))


def home_intent():
    subprocess.run([ADB, "-s", SERIAL, "shell", "am start -a android.intent.action.MAIN -c android.intent.category.HOME %s" % PKG], capture_output=True, timeout=60)


def restore_new():
    bms_stop()
    if version() != VERSIONS[NEW][0]:
        install(NEW)
    clean()


# ------------------------------------------------------------------------------------------------ the tests
def t_list():
    """The list: into the setting at once, into the ini with the checks, once, and undone by the restore's loop."""
    install(NEW)
    clean((("debug.aura.unit", "1"),))
    c = "/data/nwdappconfig"
    if prop("ro.nwd.config.path") == "":
        shell("setprop ro.nwd.config.path %s" % c)
    # the firmware's own file: no newline at its end
    shell("mkdir -p %s/app; printf 'white:com.nwd.a\\n# note\\nwhite:com.nwd.audioset' > %s/app/BackgroundManager.ini; rm -f %s/app/BackgroundManager.ini.pre-aura" % (c, c, c))
    ini = c + "/app/BackgroundManager.ini"
    before = shell("md5sum < %s" % ini)
    shell("settings put system whitelist_packagename '[com.a,com.b]'; rm -f %s/guard-log.txt; am force-stop %s" % (FILES, PKG))
    time.sleep(1)
    home_intent()
    ok = wait(lambda: "white:" + PKG in shell("cat " + ini), 30)
    check("list: the ini has the entry", ok, shell("cat " + ini))
    text = shell("cat " + ini).replace("\r", "").split("\n")
    check("list: on a line of its own, the firmware's last line whole", text[-2:] == ["white:com.nwd.audioset", "white:" + PKG], text)
    check("list: the setting keeps what was there", shell("settings get system whitelist_packagename") == "[com.a,com.b,%s]" % PKG, shell("settings get system whitelist_packagename"))
    check("list: backed up as .pre-aura, the old bytes", shell("md5sum < %s.pre-aura" % ini) == before, before)
    check("list: nothing left beside it", "aura-tmp" not in shell("ls %s/app" % c))
    check("list: the guard says so", any("background list" in l for l in guard_log()), guard_log())
    # once only
    sum1 = shell("md5sum < " + ini)
    shell("am force-stop %s" % PKG)
    home_intent()
    time.sleep(8)
    check("list: a second start changes nothing", shell("md5sum < " + ini) == sum1 and shell("settings get system whitelist_packagename") == "[com.a,com.b,%s]" % PKG)
    # no setting at all
    shell("settings delete system whitelist_packagename; am force-stop %s" % PKG)
    home_intent()
    check("list: no setting yet: the list of one", wait(lambda: shell("settings get system whitelist_packagename") == "[%s]" % PKG, 20), shell("settings get system whitelist_packagename"))
    # the restore's loop
    shell('for f in %s/app/*.pre-aura; do [ -f "$f" ] || continue; cat "$f" > "${f%%.pre-aura}" && rm -f "$f"; done' % c)
    check("list: the restore's loop puts the file back", shell("md5sum < " + ini) == before)
    shell("rm -f %s; setprop debug.aura.unit ''" % ini)
    restore_new()


def t_stock_outlives():
    """rollback.sh --stock goes on when whoever asked is gone, as with a force-stop: adbd hangs up on the shell."""
    install(NEW)
    clean()
    shell("mkdir -p " + DIR)
    adb("push", os.path.join(HERE, "..", "app", "src", "main", "assets", "rollback.sh"), DIR + "/rollback.sh")
    d = "/mnt/media_rw/sdcard"
    shell("mkdir -p %s; echo owner > %s/ring.mp3.pre-aura; echo aura > %s/ring.mp3; md5sum %s/ring.mp3 | cut -d' ' -f1 > %s/.aura-ring" % (d, d, d, d, d))
    shell("settings put system key_bt_state 3")
    p = subprocess.Popen([ADB, "-s", SERIAL, "shell", "sh %s/rollback.sh --stock" % DIR], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(0.25)
    p.kill()
    check("--stock: the flip came first", wait(lambda: prop(OFF_PROP) == "1", 5))
    gone = wait(lambda: shell("ls %s/.aura-ring 2>/dev/null" % d) == "", 20)
    check("--stock: it went on after its caller died (the ring is back)", gone and shell("cat %s/ring.mp3" % d) == "owner", shell("ls -la %s" % d))
    check("--stock: during a call it waits to give the call window back", "rollback.sh" in shell("ps -A -o ARGS | grep rollback.sh | grep -v grep"), shell("ps -A -o ARGS | grep rollback"))
    shell("settings put system key_bt_state 0")
    check("--stock: at the call's end it does, and is gone", wait(lambda: "rollback.sh" not in shell("ps -A -o ARGS | grep rollback.sh | grep -v grep"), 15))
    shell("rm -f %s/ring.mp3 %s/.aura-ring %s/ring.mp3.pre-aura" % (d, d, d))
    restore_new()


def t_throw_back():
    """A throw in AuraApp on trial, with a copy: back on the old version, whatever the firmware's killer does."""
    install(NEW)
    clean()
    save_copy(OLD)
    stopped()
    seed_crash_loop()
    bms_start()
    t0 = time.time()
    home_intent()
    running = wait(lambda: shell("ls %s/result 2>/dev/null" % DIR), 30, 0.25)
    check("throw on trial: the script started", running, guard_log())
    done = wait(lambda: version() == VERSIONS[OLD][0], 60, 1)
    print("     back in %.0f s" % (time.time() - t0))
    print("     guard: " + " | ".join(guard_log()))
    print("     stand-in: " + bms_log().replace("\n", " | "))
    check("throw on trial: back on the old version", done, (shell("cat %s/result" % DIR), guard_log()))
    check("throw on trial: no kill switch left", prop(OFF_PROP) == "0" and prop(HOME_PROP) == PKG, (prop(OFF_PROP), prop(HOME_PROP)))
    check("throw on trial: home in front", wait(lambda: PKG in front(), 30), front())
    check("throw on trial: the stand-in had nothing to stop", "force-stop" not in bms_log(), bms_log())
    check("throw on trial: the old version finds no crash loop", wait(lambda: main_pid() != "", 20) and prop(OFF_PROP) == "0")
    restore_new()


def t_throw_stock():
    """The same with no copy: the kill switch and the stock launcher, the give-backs done though AMRI is force-stopped."""
    install(NEW)
    clean()
    no_copy()
    d = "/mnt/media_rw/sdcard"
    shell("mkdir -p %s; echo owner > %s/ring.mp3.pre-aura; echo aura > %s/ring.mp3; md5sum %s/ring.mp3 | cut -d' ' -f1 > %s/.aura-ring" % (d, d, d, d, d))
    stopped()
    seed_crash_loop()
    bms_start()
    home_intent()
    check("no copy: kill switch and stock", wait(lambda: prop(OFF_PROP) == "1" and prop(HOME_PROP) == "com.android.launcher", 30), (prop(OFF_PROP), prop(HOME_PROP), guard_log()))
    check("no copy: the give-backs are done", wait(lambda: shell("ls %s/.aura-ring 2>/dev/null" % d) == "", 25), shell("ls -la %s" % d))
    check("no copy: stock launcher in front", wait(lambda: "launcher3" in front(), 25), front())
    check("no copy: the marker says crash", shell("cat %s/disable_home" % FILES).startswith("crash"), shell("cat %s/disable_home" % FILES))
    check("no copy: a new build gets another chance", 'name="crashDisabled" value="true"' in shell("cat /data/data/%s/shared_prefs/aura_crash.xml" % PKG), shell("cat /data/data/%s/shared_prefs/aura_crash.xml" % PKG))
    print("     stand-in: " + bms_log().replace("\n", " | "))
    check("no copy: the stand-in force-stopped AMRI (nothing on its list)", "force-stop" in bms_log(), bms_log())
    shell("rm -f %s/ring.mp3 %s/.aura-ring %s/ring.mp3.pre-aura" % (d, d, d))
    restore_new()


def t_listed_survives():
    """On the list, the guard outlives the stock launcher, and the package is not left stopped."""
    install(NEW)
    clean()
    no_copy()
    shell("settings put system whitelist_packagename '[%s]'" % PKG)
    stopped()
    seed_crash_loop()
    bms_start()
    home_intent()
    check("listed: kill switch and stock", wait(lambda: prop(OFF_PROP) == "1" and prop(HOME_PROP) == "com.android.launcher", 30), (prop(OFF_PROP), prop(HOME_PROP)))
    check("listed: stock launcher in front", wait(lambda: "launcher3" in front(), 25), front())
    time.sleep(3)
    check("listed: the guard is still there", guard_pid() != "", shell("ps -A | grep abdllh"))
    check("listed: nothing force-stopped", "force-stop" not in bms_log(), bms_log())
    check("listed: the package is not left stopped", "stopped=true" not in shell("dumpsys package %s | grep -m1 stopped=" % PKG), shell("dumpsys package %s | grep -m1 stopped=" % PKG))
    restore_new()


def kill_after_ok(seconds_after_ok=4):
    """Waits for this start's OK mark, then a native crash."""
    pid = wait(main_pid, 30)
    ok = wait(lambda: any(l.startswith("O ") and l.split(" ")[3] == pid for l in shell("cat %s/guard-marks 2>/dev/null" % FILES).splitlines()), 70, 1)
    if not ok:
        return False
    time.sleep(seconds_after_ok)
    shell("kill -SEGV %s" % pid)
    wait(lambda: main_pid() != pid, 30, 0.5)
    return True


def t_young_watch():
    """1.x, watching only: a native crash soon after OK is a failed start; nothing of it goes to the trial, and a verdict is written."""
    install(OLD)
    clean((("debug.aura.guard.livedsec", "120"), ("debug.aura.guard.trialmin", "1"),))
    no_copy()
    home_intent()
    for i in range(3):
        if not kill_after_ok():
            check("young: start %d reached OK" % i, False, guard_log())
            break
        home_intent()
    time.sleep(6)
    t = trial()
    check("young: no healthy start for those", t[1:2] == ["0"], t)
    check("young: no use for those", t[2:3] == ["0"], t)
    log = guard_log()
    check("young: the guard saw it", any("died young" in l for l in log), log)
    check("young: third death: it would act", any("would" in l and "3 starts in a row failed" in l for l in log), log)
    restore_new()


def t_young_back():
    """On trial, the same loop: back on the old version."""
    install(NEW)
    clean((("debug.aura.guard.livedsec", "240"),))
    save_copy(OLD)
    bms_start()  # (it keeps home where the property says: the emulator forgets its default home app after a crash or two)
    home_intent()
    for i in range(4):
        if version() == VERSIONS[OLD][0]:
            break
        if not kill_after_ok():
            break
        home_intent()
    done = wait(lambda: version() == VERSIONS[OLD][0], 60, 1)
    check("young on trial: back on the old version", done, guard_log())
    check("young on trial: no kill switch left", prop(OFF_PROP) == "0" and prop(HOME_PROP) == PKG)
    restore_new()


def t_lived():
    """A start that goes on: a healthy one, its use counted. It clears the way for the next failure to be judged anew."""
    install(NEW)
    clean((("debug.aura.guard.livedsec", "40"),))
    no_copy()
    home_intent()
    pid = wait(main_pid, 30)
    wait(lambda: any(l.startswith("O ") for l in shell("cat %s/guard-marks" % FILES).splitlines()), 70, 1)
    t = trial()
    check("lived: OK alone is no healthy start", t[1:2] in (["0"], []), t)
    check("lived: the L mark comes", wait(lambda: any(l.startswith("L ") for l in shell("cat %s/guard-marks" % FILES).splitlines()), 60, 1), shell("cat %s/guard-marks" % FILES))
    time.sleep(70)  # (the trial's file is saved once a minute)
    t = trial()
    check("lived: one healthy start", t[1:2] == ["1"], t)
    check("lived: its use counted, from its start", int((t[2:3] or ["0"])[0]) >= 40, t)
    check("lived: same process", main_pid() == pid)
    restore_new()


def t_behind():
    """Killed behind another screen, as Android's memory killer does: not a failed start. (With its notification listener
    bound the process ranks 250 behind another screen, out of that killer's reach; without it, as here, the home app's 600 or lower.)"""
    install(NEW)
    clean((("debug.aura.guard.livedsec", "600"),))
    no_copy()
    bms_start()
    for i in range(3):
        home_intent()
        pid = wait(main_pid, 30)
        wait(lambda: any(l.startswith("O ") and l.split(" ")[3] == pid for l in shell("cat %s/guard-marks 2>/dev/null" % FILES).splitlines()), 70, 1)
        shell("cmd notification disallow_listener %s; am start -W -n com.android.settings/.Settings" % LISTENER)
        low = wait(lambda: int((shell("cat /proc/%s/oom_score_adj" % pid) or "0")) >= 600, 20, 0.5)
        print("     rank behind Settings: %s" % shell("cat /proc/%s/oom_score_adj" % pid))
        if not low:
            check("behind: ranked low", False, shell("dumpsys activity processes %s | grep -E 'oom|adj' | head -n 5" % PKG))
        time.sleep(11)  # (two looks of the guard)
        shell("kill -9 %s" % pid)
        time.sleep(2)
    home_intent()
    time.sleep(8)
    log = guard_log()
    check("behind: said, and not counted", any("not counted" in l for l in log), log)
    check("behind: no verdict", not any("in a row failed" in l for l in log), log)
    restore_new()


def passed_trial():
    code = VERSIONS[NEW][1]
    shell("echo '%s 3 7200 x 0 1' > %s/guard-trial; chown $(stat -c %%u:%%g %s) %s/guard-trial" % (code, FILES, FILES, FILES))


def rescue_in_front():
    return "RescueActivity" in front()


def t_rescue_reverse():
    """After the trial a stuck main thread gets Rescue: never over the reverse camera; when it ends."""
    install(NEW)
    clean((("debug.aura.guard.hang", "1"),))
    no_copy()
    home_intent()
    time.sleep(1)
    passed_trial()
    shell("am force-stop %s" % PKG)  # (the guard reads the trial when it comes up)
    shell("settings put system mcu_backcar_state 1")
    home_intent()
    check("rescue: it waits", wait(lambda: any("Rescue waits" in l for l in guard_log()), 150, 3), guard_log())
    time.sleep(7)
    check("rescue: nothing over the camera", not rescue_in_front(), front())
    shell("settings put system mcu_backcar_state 0")
    check("rescue: it comes when reverse ends", wait(rescue_in_front, 15, 1), front())
    shell("setprop debug.aura.guard.hang ''")
    restore_new()


def t_rescue_recovered():
    """The frozen thread moved on while reverse lasted: nothing to offer any more."""
    install(NEW)
    clean()
    no_copy()
    home_intent()
    time.sleep(1)
    passed_trial()
    shell("am force-stop %s" % PKG)
    home_intent()
    pid = wait(main_pid, 30)
    time.sleep(15)
    shell("settings put system mcu_backcar_state 1")
    shell("kill -STOP %s" % pid)
    check("rescue: frozen, it waits", wait(lambda: any("Rescue waits" in l for l in guard_log()), 150, 3), guard_log())
    shell("kill -CONT %s" % pid)
    time.sleep(12)
    shell("settings put system mcu_backcar_state 0")
    check("rescue: dropped", wait(lambda: any("not offered" in l for l in guard_log()), 15, 1), guard_log())
    check("rescue: nothing in front of home", not rescue_in_front(), front())
    restore_new()


TESTS = {"list": t_list, "outlives": t_stock_outlives, "throw": t_throw_back, "stock": t_throw_stock, "listed": t_listed_survives,
         "young": t_young_watch, "youngback": t_young_back, "lived": t_lived, "behind": t_behind, "reverse": t_rescue_reverse, "recovered": t_rescue_recovered}


def main():
    global SERIAL, NEW, OLD, VERSIONS
    p = argparse.ArgumentParser()
    p.add_argument("--serial", default="emulator-5570")
    p.add_argument("--old")
    p.add_argument("--new")
    p.add_argument("--old-version", default="1.19.1:1190199")
    p.add_argument("--new-version", default="2.0.0:2000099")
    p.add_argument("--only", default="")
    p.add_argument("--list", action="store_true")
    a = p.parse_args()
    if a.list:
        print(" ".join(TESTS))
        return
    SERIAL, NEW, OLD = a.serial, a.new, a.old
    VERSIONS = {OLD: tuple(a.old_version.split(":")), NEW: tuple(a.new_version.split(":"))}
    adb("root")
    time.sleep(2)
    shell("setenforce 0")  # (as on the unit: an app may set these properties there)
    srv = subprocess.Popen([sys.executable, os.path.join(HERE, "fake_adbd.py"), "--adb", ADB, "--serial", SERIAL, "--port", str(ADB_PORT)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        time.sleep(1)
        adb("reverse", "tcp:%d" % ADB_PORT, "tcp:%d" % ADB_PORT)
        shell("setprop debug.aura.adb 127.0.0.1:%d" % ADB_PORT)
        only = [x for x in a.only.split(",") if x] or list(TESTS)
        for name in only:
            print("--- " + name, flush=True)
            t0 = time.time()
            TESTS[name]()
            print("     (%.0f s)" % (time.time() - t0), flush=True)
    finally:
        srv.kill()
        bms_stop()
        shell("setprop debug.aura.adb ''; setprop debug.aura.unit ''; setenforce 1")
    failed = [n for n, ok in results if not ok]
    print("\n%d checks, %d failed" % (len(results), len(failed)))
    for n in failed:
        print("  FAILED: " + n)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
