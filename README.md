# Aura — modern EV-style system for the K2501 car head unit

An EV-inspired home screen, app drawer, settings, boot animation and an **in-app updater that pulls releases from GitHub**,
packaged as a minimal ROM patch for the **K2501** head unit (NWD firmware, Allwinner T507, Android 10, 1024×600).
Arabic-first docs: [README.ar.md](README.ar.md).

* `app/` — the Aura launcher (Kotlin, no third-party dependencies, ~210 KB).
* `rom/` — the ROM tooling: overlay files, boot script, workshop that builds the image inside an Android emulator,
  block-patch generator, and the installers (`rom/flash`): **`install-linux.sh` / `aura-install.py` for Ubuntu/Linux**
  (finds the unit on the network by itself, needs only Python 3) and `aura-rom.ps1` + `.bat` files for Windows.
* Releases contain `aura-<ver>.apk` (used by the in-app updater) and `aura-rom-<ver>.zip` (the installer pack, Linux and Windows).

### Installing from an Ubuntu laptop

```
unzip aura-rom-1.0.0.zip -d aura && cd aura
# laptop and unit on the same network, e.g. the laptop's own Wi-Fi hotspot
./install-linux.sh            # finds the K2501, shows its state, asks for YES, installs, restarts, checks
./install-linux.sh status     # only look
./install-linux.sh restore    # put the stock firmware back
```

It only ever touches a unit that reports itself as a K2501, writes nothing unless the system partition is exactly the stock
image (or an interrupted run of this patch), writes in a crash-consistent order, and verifies the result. Not tested on a
real unit yet: see the notes in [README.ar.md](README.ar.md) and [docs/DEV.md](docs/DEV.md).

Aura is an independent design inspired by modern EV interfaces; it is not affiliated with any car maker.
See [NOTICE](NOTICE) for third-party notices.
