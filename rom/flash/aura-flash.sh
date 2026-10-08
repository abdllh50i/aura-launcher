#!/system/bin/sh
# Applies or reverts an Aura ROM patch pack on the system block device (runs on the head unit as root).
#
#   aura-flash.sh apply  PACKDIR [DEVICE]    stock  -> Aura
#   aura-flash.sh revert PACKDIR [DEVICE]    Aura   -> stock
#   aura-flash.sh status PACKDIR [DEVICE]    reports which of the two the device currently is
#
# PACKDIR holds: forward.bin reverse.bin ranges.txt hashes.txt   (made by rom/tools/make_patch.py)
# DEVICE defaults to /dev/block/mapper/system (the live "system" logical partition).
#
# Safety: the whole device hash must equal the expected *source* hash before anything is written, the patch is only a
# few hundred KiB of changed blocks, and the result is verified against the expected *target* hash; if verification
# fails the previous contents are written back and verified again.

BS=4096
MODE=$1
PACK=$2
DEV=${3:-/dev/block/mapper/system}

say()  { echo "$*"; }
die()  { echo "ERROR: $*"; exit 1; }

[ -n "$MODE" ] && [ -d "$PACK" ] || die "usage: aura-flash.sh apply|revert|status PACKDIR [DEVICE]"
[ -b "$DEV" ] || [ -e "$DEV" ] || die "device not found: $DEV"
for f in forward.bin reverse.bin ranges.txt hashes.txt; do [ -f "$PACK/$f" ] || die "pack is incomplete (missing $f)"; done

OLD=$(grep '^old=' "$PACK/hashes.txt" | cut -d= -f2)
NEW=$(grep '^new=' "$PACK/hashes.txt" | cut -d= -f2)
[ ${#OLD} = 64 ] && [ ${#NEW} = 64 ] || die "bad hashes.txt"

hash_dev() {
    sync
    echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
    sha256sum "$DEV" | cut -d' ' -f1
}

# write_ranges BIN : writes every range of ranges.txt from BIN to the device
write_ranges() {
    bin=$1
    n=0
    while read -r start count off; do
        [ -n "$start" ] || continue
        dd if="$bin" of="$DEV" bs=$BS skip="$off" seek="$start" count="$count" conv=notrunc 2>/dev/null || return 1
        n=$((n + 1))
    done < "$PACK/ranges.txt"
    sync
    say "  wrote $n ranges"
    return 0
}

say "Aura ROM patch: mode=$MODE device=$DEV"
say "  reading current system partition hash (about 20-60 s)..."
CUR=$(hash_dev)
say "  current = $CUR"

case "$MODE" in
status)
    if [ "$CUR" = "$OLD" ]; then say "STATE: stock firmware (patch NOT applied)"
    elif [ "$CUR" = "$NEW" ]; then say "STATE: Aura ROM applied"
    else say "STATE: unknown system contents (neither stock nor Aura)"; fi
    exit 0 ;;
apply)  FROM=$OLD; TO=$NEW; BIN="$PACK/forward.bin"; BACK="$PACK/reverse.bin" ;;
revert) FROM=$NEW; TO=$OLD; BIN="$PACK/reverse.bin"; BACK="$PACK/forward.bin" ;;
*) die "unknown mode: $MODE" ;;
esac

if [ "$CUR" = "$TO" ]; then say "RESULT: already in the requested state, nothing to do"; exit 0; fi
if [ "$CUR" != "$FROM" ]; then
    say "RESULT: REFUSED - the system partition is not the expected base (expected $FROM)."
    say "         Nothing was written. (Different firmware version, or an unknown modification.)"
    exit 2
fi

# Logical (dynamic) partitions are created read-only by the kernel: flip to read-write for the update and back afterwards.
RO_WAS=0
restore_ro() { [ "$RO_WAS" = 1 ] && blockdev --setro "$DEV" 2>/dev/null; }
trap restore_ro EXIT
if command -v blockdev >/dev/null 2>&1 && [ "$(blockdev --getro "$DEV" 2>/dev/null)" = "1" ]; then
    RO_WAS=1
    blockdev --setrw "$DEV" 2>/dev/null
    [ "$(blockdev --getro "$DEV" 2>/dev/null)" = "0" ] || die "$DEV is read-only and could not be made writable (nothing was written)"
    say "  device was read-only: switched to read-write for the update"
fi

say "  base verified, writing patch..."
if ! write_ranges "$BIN"; then
    say "  write failed - restoring previous blocks"
    write_ranges "$BACK"
    die "dd failed"
fi

say "  verifying result (about 20-60 s)..."
AFTER=$(hash_dev)
say "  now     = $AFTER"
if [ "$AFTER" = "$TO" ]; then
    say "RESULT: OK"
    exit 0
fi

say "  VERIFY FAILED - writing the previous blocks back"
BIN2=$BACK
write_ranges "$BIN2"
AGAIN=$(hash_dev)
if [ "$AGAIN" = "$FROM" ]; then say "RESULT: FAILED, original contents restored"; else say "RESULT: FAILED AND RESTORE NOT VERIFIED ($AGAIN)"; fi
exit 3
