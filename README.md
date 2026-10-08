# Aura — modern EV-style system for the K2501 car head unit

An EV-inspired home screen, app drawer, settings, boot animation and an **in-app updater that pulls releases from GitHub**,
packaged as a minimal ROM patch for the **K2501** head unit (NWD firmware, Allwinner T507, Android 10, 1024×600).
Arabic-first docs: [README.ar.md](README.ar.md).

* `app/` — the Aura launcher (Kotlin, no third-party dependencies, ~210 KB).
* `rom/` — the ROM tooling: overlay files, boot script, workshop that builds the image inside an Android emulator,
  block-patch generator, and the PC installer (`rom/flash`).
* Releases contain `aura-<ver>.apk` (used by the in-app updater) and `aura-rom-<ver>.zip` (the installer pack).

Aura is an independent design inspired by modern EV interfaces; it is not affiliated with any car maker.
See [NOTICE](NOTICE) for third-party notices.
