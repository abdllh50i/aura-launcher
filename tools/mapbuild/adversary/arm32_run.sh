#!/bin/bash
# Inside a privileged x86_64 container: mounts the firmware's system.img read-only, makes a small root with its /system and its runtime APEX, and
# runs the ARM32 driver in a chroot. The kernel hands ARM ELF files to Docker Desktop's qemu-arm (binfmt). The tar goes to a tmpfs: mmap and bulk
# reads through Docker's Windows file share are unreliable and slow.
#   arm32_run.sh <system.img> <driver> <lib.so> <config.json> <route.tar> <req dir> <out dir> request-name...
set -u
IMG="$1"; DRIVER="$2"; LIB="$3"; CONFIG="$4"; TAR="$5"; REQ="$6"; OUT="$7"; shift 7
R=/work/root
mkdir -p /work/img "$R"/system "$R"/apex/com.android.runtime "$R"/proc "$R"/dev "$R"/run "$R"/out "$R"/data/local/tmp
mount -o loop,ro "$IMG" /work/img || { echo "cannot mount $IMG"; exit 1; }
S=/work/img
[ -d /work/img/system/lib ] && S=/work/img/system
mount --bind "$S" "$R/system"
mount --bind "$S/apex/com.android.runtime.release" "$R/apex/com.android.runtime"
mount --bind /dev "$R/dev"
mount -t proc proc "$R/proc"
mount --bind "$OUT" "$R/out"
cp "$DRIVER" "$R/data/local/tmp/driver"; chmod 755 "$R/data/local/tmp/driver"
cp "$LIB" "$R/data/local/tmp/libvalhalla-wrapper.so"
cp "$CONFIG" "$R/data/local/tmp/config.json"
cp "$TAR" "$R/data/local/tmp/route.tar"
ls -l "$R/data/local/tmp"
ls -l "$R/system/bin/linker" "$R/system/lib/libc.so" 2>&1
RL=""
for n in "$@"; do cp "$REQ/$n.json" "$R/data/local/tmp/$n.json"; RL="$RL /data/local/tmp/$n.json"; done
free -m | head -2
cd "$R"
/usr/bin/env -i LD_LIBRARY_PATH=/data/local/tmp:/apex/com.android.runtime/lib ANDROID_RUNTIME_ROOT=/apex/com.android.runtime ANDROID_ROOT=/system ANDROID_DATA=/data PATH=/system/bin "$(command -v chroot)" "$R" /data/local/tmp/driver /data/local/tmp/libvalhalla-wrapper.so /data/local/tmp/config.json /out $RL
echo "exit code $?"
umount "$R/out" "$R/proc" "$R/dev" "$R/apex/com.android.runtime" "$R/system" /work/img 2>/dev/null
