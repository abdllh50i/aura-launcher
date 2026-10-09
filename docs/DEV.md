# Developer notes

## Build the app
```
set JAVA_HOME=...\jdk-17   &  set ANDROID_HOME=...\Sdk
gradlew :app:assembleRelease        (needs signing\keystore.properties — see below)
```
`app/build.gradle.kts` reads `signing/keystore.properties` (storeFile, storePassword, keyAlias, keyPassword). The keystore is
never committed. Version: `-PappVersionName=1.2.3` (or `1.3.0-beta.2`); the versionCode is derived from it, in the same order
as the name: `(MAJOR*10000 + MINOR*100 + PATCH) * 100 + (99 if stable, else the pre-release number N of "-beta.N")`, so
`1.3.0-beta.1` = 130001 < `1.3.0-beta.2` = 130002 < `1.3.0` = 130099 < `1.3.1-beta.1` = 130101. MINOR and PATCH stay below 100,
and a version's pre-releases form one numbered series. The updater compares the GitHub tag (`v1.2.3`) with
`BuildConfig.VERSION_NAME`; the downloaded APK must carry the same signing certificate and a higher versionCode.

## Publish an app update (what the on-screen updater installs)
1. bump the version, build release, 2. `gh release create vX.Y.Z aura-X.Y.Z.apk aura-X.Y.Z.apk.sha256 --notes "..."`
(`--prerelease` for betas; they are only offered to units that switched on "Include beta versions").
Release notes are shown in the update screen. `rom/make-release.ps1 -Version X.Y.Z` builds the APK, the ROM image and the pack.
Integrity: the app verifies the APK against GitHub's own asset digest, else the `.sha256` asset (keep it next to the APK), and
always checks package name, signing certificate and a higher versionCode before handing the file to the installer.
A release that has no APK (for example one that only carries the ROM pack) is simply skipped by the updater.

## ROM workshop (how the system image is built)
The stock `system.img` (ext4, from the dump) is edited inside an Android 10 emulator because it ships the real
`e2fsck`/`resize2fs` and a Linux ext4 driver: `rom/workshop/apply.sh` grows the filesystem into the free space of its logical
partition (never beyond), mounts it, copies `rom/system/**`, patches the NWD config with `rom/system/bin/aura-prepare.sh --image`
and the `build.prop` default-launcher line, runs `e2fsck -fn`. `rom/tools/imgdiff.py` then proves, file by file, that only the
intended paths changed; `rom/tools/make_patch.py` turns the difference into a block patch (forward + reverse), which
`rom/flash/aura-flash.sh` applies with full hash verification on the unit. The pack also carries per-range hashes and a hash of
every block *outside* the ranges (`rest`): if a run is ever interrupted, a partition that matches neither image but whose
`rest` still matches is reported as an interrupted patch and `install`/`restore` simply finish it (rewriting all ranges leads
to a known image again); anything else is refused. `rom/tools/make_zip.py` writes the release zip (forward-slash entries).

Crash consistency: the ranges are written in a fixed order (new data, superblock/group descriptors, bitmaps, inode tables,
existing directory blocks; restore = the exact reverse), because the partition is live and a power cut during the write must
not leave a system that cannot boot. `rom/tools/make_patch.py` classifies every changed block with `rom/tools/fsmap.py`
(and refuses images that overwrite live file data), and `rom/tools/check_order.py` proves the order: it simulates the state
after *every single block* of the write (both directions) and requires that all changed files, new files and their parent
directories are always exactly the old or exactly the new version; the same check on an ascending order fails in >1000 of
1022 states. `make-release.ps1` runs it. `scripts/test-flash.ps1` exercises the PC installer and the on-device script
against a loop device on the emulator (round trip, interrupted runs, refusals, damaged packs, a run that outlives its adb
session, finishing after a lost follow-up); `-Quick` runs only the round trip, e.g. on a freshly unzipped release.

Emulator: AVD "CarUnit" (Android 10 x86_64, 1024×600, 160 dpi) created with `ANDROID_AVD_HOME` on an ASCII path; the stock image is pushed to
`/data/local/tmp/ws/system.img` once.

## Facts about the firmware (from the dump)
* No `avb` flag in the vendor fstab → no dm-verity on system/vendor/product; vbmeta uses the public AOSP test keys.
* `persist.nwd.launcher.default=<pkg>` makes the patched PackageManager return that package as HOME.
* The firmware keeps trusted apps in `AppConfig.xml` (`NwdApp`), a background-kill whitelist in `TaskWhitelist.xml`, and a
  fast-dexopt list; `aura-prepare.sh` registers Aura in all of them at every boot (idempotent).
* The stock launcher also reports "home in front" (ACTION_APP_IN_OUT, app id 4) and starts the floating volume bar, boot tip and
  assistive touch services; Aura repeats both (`system/NwdBridge.kt`).
