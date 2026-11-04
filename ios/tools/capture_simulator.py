"""Boots a simulator, installs the Debug build, and takes a picture of every screen in a set state, in Naghm's manner. Arabic
first. The screens come from `-screen <name>` (App/Screens/DemoScreens.swift); what only a real flow shows is in the UI tests'
screenshots. Run from ios/ after a simulator build.
"""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

APP = "DerivedData/Build/Products/Debug-iphonesimulator/Amri.app"
BUNDLE = "com.abdllh.amri"
OUT = Path("evidence/screens")

ARABIC = [
    "first", "unpaired", "denied", "home", "expiry", "typing", "results", "empty", "place", "place-long",
    "locating", "sending", "waiting", "asking", "started", "trip", "failed", "card", "unreachable", "noword", "pending",
    "car", "car-idle", "car-notfound", "pair", "check", "repair",
    "share-reading", "share-confirm", "share-pick", "share-nothing", "share-sending", "share-waiting", "share-card",
    "share-started", "share-failed", "share-unreachable", "share-denied", "share-notpaired",
]
ENGLISH = ["home", "results", "place", "started", "car", "pair", "repair", "share-started"]
DARK = ["home", "place", "started", "car", "share-started"]


def run(*args, timeout=180):
    return subprocess.check_output(args, text=True, timeout=timeout).strip()


def pick_device():
    devices = json.loads(run("xcrun", "simctl", "list", "devices", "available", "-j"))["devices"]
    phones = [device for runtime, entries in sorted(devices.items(), reverse=True)
              if "iOS" in runtime for device in entries if "iPhone" in device["name"]]
    return next((x for x in phones if "Pro" in x["name"] and "Max" not in x["name"]), phones[0])


def launch(udid, screen, language):
    locale = "ar_SA" if language == "ar" else "en_US"
    run("xcrun", "simctl", "launch", "--terminate-running-process", udid, BUNDLE,
        "-AppleLanguages", f"({language})", "-AppleLocale", locale, "-screen", screen, "-demoSearch")


def shoot(udid, screen, language, suffix=""):
    launch(udid, screen, language)
    # The map's tiles take a moment; the screens over the panel take a little longer to come up.
    time.sleep(4 if not screen.startswith("share-") else 2)
    name = f"{language}{suffix}-{screen}.png"
    run("xcrun", "simctl", "io", udid, "screenshot", "--type=png", str(OUT / name))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    Path("evidence").mkdir(exist_ok=True)
    device = pick_device()
    udid = device["udid"]
    Path("evidence/simulator-udid.txt").write_text(udid)
    Path("evidence/simulator.txt").write_text(json.dumps(device, indent=2))
    if device["state"] != "Booted":
        run("xcrun", "simctl", "boot", udid)
    run("xcrun", "simctl", "bootstatus", udid, "-b", timeout=420)
    run("xcrun", "simctl", "status_bar", udid, "override", "--time", "9:41", "--batteryState", "charged",
        "--batteryLevel", "100", "--wifiBars", "3", "--cellularBars", "4")
    run("xcrun", "simctl", "location", udid, "set", "26.2894,50.1975")
    run("xcrun", "simctl", "install", udid, APP)
    run("xcrun", "simctl", "privacy", udid, "grant", "location", BUNDLE)
    run("xcrun", "simctl", "ui", udid, "appearance", "light")
    if "--boot-only" in sys.argv:
        print("Booted", device["name"], udid)
        return
    # The first start is slow while the system prepares the app, and the map's tiles load.
    launch(udid, "home", "ar")
    time.sleep(12)

    only = sys.argv[1:]
    for screen in ARABIC:
        if not only or screen in only:
            shoot(udid, screen, "ar")
    if not only:
        for screen in ENGLISH:
            shoot(udid, screen, "en")
        run("xcrun", "simctl", "ui", udid, "appearance", "dark")
        for screen in DARK:
            shoot(udid, screen, "ar", "-dark")
        run("xcrun", "simctl", "ui", udid, "appearance", "light")
    print("Captured", len(list(OUT.glob("*.png"))), "screens on", device["name"], "at", os.getcwd())


if __name__ == "__main__":
    main()
