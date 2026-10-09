#!/system/bin/sh
# Applies or reverts an Aura ROM patch pack on the system block device (runs on the head unit as root).
#
#   aura-flash.sh apply  PACKDIR [DEVICE]    stock  -> Aura
#   aura-flash.sh revert PACKDIR [DEVICE]    Aura   -> stock
#   aura-flash.sh status PACKDIR [DEVICE]    reports which of the two the device currently is
#
# PACKDIR holds: forward.bin reverse.bin ranges.txt order-apply.txt order-revert.txt hashes.txt   (made by rom/tools/make_patch.py)
# DEVICE defaults to /dev/block/mapper/system (the live "system" logical partition).
#
# Safety
#  * Nothing is written unless the whole-device SHA-256 equals the expected source (stock for apply, Aura for revert), or
#    the device is provably an interrupted run of this very patch: every block outside the patch ranges still matches
#    ("rest" hash), so rewriting all ranges leads to a known image again.
#  * The patch is only the changed 4 KiB blocks (a few MiB). Before the first write the pack is checked completely: file
#    hashes, the range table, and every range's data against its own hash; for a stock/Aura source every range is also
#    compared with what the device holds. The result is verified against the whole-device target hash; on failure the
#    previous blocks are written back and verified again.
#  * The ranges are written in a crash-consistent order (new data first, the structures that point at it last; revert is the
#    exact reverse), so a power cut at any moment leaves a readable file system, and a re-run finishes the job.
#  * A dropped connection (Wi-Fi, closed console) cannot kill the script half-way: HUP and PIPE are ignored.
#
# Output lines a caller can rely on:  STATE_CODE=STOCK|AURA|PARTIAL|UNKNOWN   and   RESULT: OK | ... (see the end)

BS=4096
MODE=$1
PACK=$2
DEV=${3:-/dev/block/mapper/system}

trap '' HUP PIPE

WORK="$(dirname "$PACK")"
LOG="$WORK/flash.log"
say() { echo "$*"; echo "$*" >> "$LOG" 2>/dev/null; }
die() { say "ERROR: $*"; exit 1; }

[ -n "$MODE" ] && [ -d "$PACK" ] || die "usage: aura-flash.sh apply|revert|status PACKDIR [DEVICE]"
[ -b "$DEV" ] || [ -e "$DEV" ] || die "device not found: $DEV"
for f in forward.bin reverse.bin ranges.txt order-apply.txt order-revert.txt hashes.txt; do [ -f "$PACK/$f" ] || die "pack is incomplete (missing $f)"; done

hv() { grep "^$1=" "$PACK/hashes.txt" | cut -d= -f2 | tr -d '\r '; }
OLD=$(hv old); NEW=$(hv new); REST=$(hv rest); FSHA=$(hv forward_sha256); RSHA=$(hv reverse_sha256); IMGB=$(hv image_bytes)
SHA_RANGES=$(hv ranges_sha256); SHA_OAPPLY=$(hv order_apply_sha256); SHA_OREVERT=$(hv order_revert_sha256)
for v in "$OLD" "$NEW" "$REST" "$FSHA" "$RSHA" "$SHA_RANGES" "$SHA_OAPPLY" "$SHA_OREVERT"; do
    [ ${#v} = 64 ] || die "bad hashes.txt"
done
[ -n "$IMGB" ] || die "bad hashes.txt"
IMG_BLOCKS=$((IMGB / BS))

# the range tables without CR characters: "start count offset old_sha new_sha" per line
RANGES="$WORK/ranges.clean"; ORD_APPLY="$WORK/order-apply.clean"; ORD_REVERT="$WORK/order-revert.clean"
tr -d '\r' < "$PACK/ranges.txt" > "$RANGES" || die "cannot prepare the range list"
tr -d '\r' < "$PACK/order-apply.txt" > "$ORD_APPLY" || die "cannot prepare the range list"
tr -d '\r' < "$PACK/order-revert.txt" > "$ORD_REVERT" || die "cannot prepare the range list"

# ---- the pack itself must be complete and consistent before anything else happens
sum_of() { [ "$(sha256sum "$1" | cut -d' ' -f1)" = "$2" ]; }
sum_of "$PACK/forward.bin" "$FSHA" || die "forward.bin is damaged (checksum) - copy the pack again"
sum_of "$PACK/reverse.bin" "$RSHA" || die "reverse.bin is damaged (checksum) - copy the pack again"
sum_of "$RANGES" "$SHA_RANGES" || die "ranges.txt does not belong to this pack (checksum) - copy the pack again"
sum_of "$ORD_APPLY" "$SHA_OAPPLY" || die "order-apply.txt does not belong to this pack (checksum) - copy the pack again"
sum_of "$ORD_REVERT" "$SHA_OREVERT" || die "order-revert.txt does not belong to this pack (checksum) - copy the pack again"

# range table: ascending, no overlap, inside the image; bins have exactly the right size and every slice its own hash
TOTAL=0; NRANGES=0; END=0
while read -r start count off osha nsha; do
    [ -n "$start" ] || continue
    [ "$start" -ge "$END" ] || die "range table is not ascending / overlaps at block $start"
    END=$((start + count))
    [ "$END" -le "$IMG_BLOCKS" ] || die "range at block $start lies outside the image"
    [ "$off" = "$TOTAL" ] || die "range table offsets are wrong at block $start"
    TOTAL=$((TOTAL + count)); NRANGES=$((NRANGES + 1))
done < "$RANGES"
[ "$TOTAL" -gt 0 ] || die "the patch has no ranges"
for b in forward.bin reverse.bin; do
    sz=$(wc -c < "$PACK/$b" | tr -d ' ')
    [ "$sz" = "$((TOTAL * BS))" ] || die "$b has the wrong size ($sz bytes, expected $((TOTAL * BS))) - copy the pack again"
done
for t in "$ORD_APPLY" "$ORD_REVERT"; do
    [ "$(wc -l < "$t" | tr -d ' ')" = "$NRANGES" ] || die "a write-order list does not have $NRANGES ranges"
done
while read -r start count off osha nsha; do
    [ -n "$start" ] || continue
    [ "$(dd if="$PACK/forward.bin" bs=$BS skip="$off" count="$count" 2>/dev/null | sha256sum | cut -d' ' -f1)" = "$nsha" ] || die "forward.bin data of the range at block $start is damaged"
    [ "$(dd if="$PACK/reverse.bin" bs=$BS skip="$off" count="$count" 2>/dev/null | sha256sum | cut -d' ' -f1)" = "$osha" ] || die "reverse.bin data of the range at block $start is damaged"
done < "$RANGES"

hash_dev() {
    sync
    blockdev --flushbufs "$DEV" 2>/dev/null
    echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
    sha256sum "$DEV" | cut -d' ' -f1
}

hash_blocks() {   # hash_blocks START COUNT
    dd if="$DEV" bs=$BS skip="$1" count="$2" 2>/dev/null | sha256sum | cut -d' ' -f1
}

# SHA-256 of every block outside the patch ranges, in order (the same definition make_patch.py uses)
hash_rest() {
    {
        pos=0
        while read -r start count off osha nsha; do
            [ -n "$start" ] || continue
            [ "$start" -gt "$pos" ] && dd if="$DEV" bs=$BS skip="$pos" count=$((start - pos)) 2>/dev/null
            pos=$((start + count))
        done < "$RANGES"
        [ "$IMG_BLOCKS" -gt "$pos" ] && dd if="$DEV" bs=$BS skip="$pos" count=$((IMG_BLOCKS - pos)) 2>/dev/null
    } | sha256sum | cut -d' ' -f1
}

# how many ranges currently hold the stock data / the Aura data / something else
count_ranges() {
    R_OLD=0; R_NEW=0; R_OTHER=0
    while read -r start count off osha nsha; do
        [ -n "$start" ] || continue
        h=$(hash_blocks "$start" "$count")
        if [ "$h" = "$osha" ]; then R_OLD=$((R_OLD + 1))
        elif [ "$h" = "$nsha" ]; then R_NEW=$((R_NEW + 1))
        else R_OTHER=$((R_OTHER + 1)); fi
    done < "$RANGES"
}

# write_ranges BIN LIST : writes every range named in LIST (in LIST order) from BIN to the device
write_ranges() {
    bin=$1
    list=$2
    n=0
    while read -r start count off osha nsha; do
        [ -n "$start" ] || continue
        err=$(dd if="$bin" of="$DEV" bs=$BS skip="$off" seek="$start" count="$count" conv=notrunc,fsync 2>&1) \
            || { say "  write failed at block $start: $(echo "$err" | tail -1)"; return 1; }
        n=$((n + 1))
    done < "$list"
    sync
    blockdev --flushbufs "$DEV" 2>/dev/null
    say "  wrote $n ranges"
    return 0
}

say "Aura ROM patch: mode=$MODE device=$DEV"

SIZE_OK=1
DSZ=$(blockdev --getsize64 "$DEV" 2>/dev/null)
case "$DSZ" in
    ''|*[!0-9]*) ;;    # cannot tell: the hash decides
    *) [ "$DSZ" = "$IMGB" ] || { SIZE_OK=0; say "  the device is $DSZ bytes, this patch is for $IMGB bytes"; } ;;
esac

say "  reading the system partition (about 20-60 s)..."
CUR=$(hash_dev)
say "  current = $CUR"

if [ "$CUR" = "$OLD" ]; then STATE=STOCK
elif [ "$CUR" = "$NEW" ]; then STATE=AURA
elif [ "$SIZE_OK" = 1 ]; then
    say "  matches neither image - checking whether this is an interrupted run of the patch (about 20-60 s)..."
    if [ "$(hash_rest)" = "$REST" ]; then STATE=PARTIAL; else STATE=UNKNOWN; fi
else
    STATE=UNKNOWN
fi

case "$MODE" in
status)
    case "$STATE" in
        STOCK)   say "STATE: stock firmware (patch NOT applied)" ;;
        AURA)    say "STATE: Aura ROM applied" ;;
        PARTIAL) count_ranges
                 say "STATE: interrupted patch - $R_OLD ranges are stock, $R_NEW are Aura, $R_OTHER are in between. Run install (or restore) again to finish it." ;;
        *)       say "STATE: unknown system contents (neither stock nor Aura)" ;;
    esac
    say "STATE_CODE=$STATE"
    exit 0 ;;
apply)  FROM=$OLD; TO=$NEW; BIN="$PACK/forward.bin"; LIST="$ORD_APPLY";  BACK="$PACK/reverse.bin"; BACKLIST="$ORD_REVERT"; WANT=AURA;  START=STOCK ;;
revert) FROM=$NEW; TO=$OLD; BIN="$PACK/reverse.bin"; LIST="$ORD_REVERT"; BACK="$PACK/forward.bin"; BACKLIST="$ORD_APPLY";  WANT=STOCK; START=AURA ;;
*) die "unknown mode: $MODE" ;;
esac

if [ "$STATE" = "$WANT" ]; then say "RESULT: already in the requested state, nothing to do"; exit 0; fi
if [ "$STATE" != "$START" ] && [ "$STATE" != "PARTIAL" ]; then
    say "RESULT: REFUSED - the system partition is not the expected base (expected $FROM)."
    say "         Nothing was written. (Different firmware version, or an unknown modification.)"
    exit 2
fi
if [ "$STATE" = "PARTIAL" ]; then
    say "  the partition holds an interrupted run of this patch - finishing it"
else
    # the device equals the source image, so every range must hold exactly the source data of the table
    count_ranges
    if [ "$STATE" = "STOCK" ]; then GOOD=$R_OLD; else GOOD=$R_NEW; fi
    [ "$GOOD" = "$NRANGES" ] || { say "RESULT: REFUSED - the range table does not match the device ($GOOD of $NRANGES ranges). Nothing was written."; exit 2; }
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

say "  base verified, writing the patch..."
if write_ranges "$BIN" "$LIST"; then
    say "  verifying the result (about 20-60 s)..."
    AFTER=$(hash_dev)
    say "  now     = $AFTER"
    if [ "$AFTER" = "$TO" ]; then
        say "RESULT: OK"
        exit 0
    fi
    say "  VERIFY FAILED"
fi

say "  writing the previous blocks back..."
write_ranges "$BACK" "$BACKLIST"
AGAIN=$(hash_dev)
if [ "$AGAIN" = "$FROM" ]; then
    say "RESULT: FAILED, the previous contents were restored and verified"
    exit 3
fi
say "RESULT: FAILED AND THE ROLLBACK COULD NOT BE VERIFIED ($AGAIN) - do not reboot; run the same command again"
exit 4
