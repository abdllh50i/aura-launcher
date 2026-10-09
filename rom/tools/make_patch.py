"""Builds a block-level patch pack between two same-size raw images (stock -> Aura).

  python make_patch.py OLD.img NEW.img OUTDIR [--gap N]

Writes into OUTDIR:
  forward.bin       concatenated NEW data of every changed range (ranges in ascending order)
  reverse.bin       concatenated OLD data of every changed range (used for the one-click restore)
  ranges.txt        one "start_block count offset_in_bin old_sha256 new_sha256" line per range (4 KiB blocks), ascending
  order-apply.txt   the same lines in the order they must be WRITTEN when going OLD -> NEW
  order-revert.txt  the same lines in the order they must be written when going NEW -> OLD (exact reverse)
  hashes.txt        key=value file for the on-device script (see below)
  manifest.json     the same facts for humans / other tools

Write order (crash consistency). The partition is live and a power cut during the write must not leave a system that
cannot boot, so the ranges are written in this order (the revert order is its exact reverse):
  1. data      blocks nobody uses in OLD (new file data, new directory blocks, extent/xattr blocks, the grown tail)
  2. sb/gdt    backup copies, then the primary group descriptors, then the primary superblock (new size) -
               before the inodes that point into the grown area
  3. bitmaps   block / inode allocation bitmaps
  4. itables   inode tables (inodes now point at data that is already in place)
  5. dirs      existing directory blocks (new entries appear only when everything they point to is ready)
Every prefix of that sequence is a consistent file system (see check_order.py, which proves it block by block).
A changed block that is live file data in OLD would break this; the build stops if it finds one.

hashes.txt:
  old / new         SHA-256 of the whole OLD / NEW image
  rest              SHA-256 of every block *outside* the ranges, in order (identical in OLD and NEW)
  forward_sha256 / reverse_sha256 / ranges_sha256 / order_apply_sha256 / order_revert_sha256   SHA-256 of those files
  image_bytes / patch_blocks        sizes

Why "rest": the whole partition is exactly `rest` + the ranges. If a write was ever interrupted, the partition matches
neither OLD nor NEW, but when `rest` still matches, rewriting all ranges from forward.bin (or reverse.bin) provably
produces NEW (or OLD) again, so the script can finish or undo an interrupted run instead of refusing.

Neighbouring changed blocks of the same class separated by <= gap unchanged blocks are merged into one range (fewer dd
calls; the merged unchanged blocks are simply rewritten with identical data).
"""
import hashlib, json, os, sys

import fsmap

BS = 4096
CHUNK = 64 * 1024 * 1024

# write classes, in apply order
C_DATA, C_SB_BACKUP, C_GDT_PRIMARY, C_SB_PRIMARY, C_BITMAP, C_ITABLE, C_DIR, C_INPLACE = range(1, 9)
CLASS_NAMES = {C_DATA: "data", C_SB_BACKUP: "sb/gdt-backup", C_GDT_PRIMARY: "gdt", C_SB_PRIMARY: "superblock",
               C_BITMAP: "bitmaps", C_ITABLE: "itables", C_DIR: "dirs", C_INPLACE: "IN-PLACE (unsafe)"}


def rest_hash(path, ranges, size):
    """SHA-256 over all blocks that are not inside one of the (sorted, non-overlapping) ranges."""
    h = hashlib.sha256()
    pos = 0
    nblocks = size // BS
    with open(path, "rb") as f:
        for start, count in ranges + [[nblocks, 0]]:
            left = (start - pos) * BS
            if left > 0:
                f.seek(pos * BS)
                while left:
                    b = f.read(min(left, CHUNK))
                    if not b:
                        raise SystemExit("short read while hashing the unchanged blocks")
                    h.update(b)
                    left -= len(b)
            pos = start + count
    return h.hexdigest()


def file_sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(CHUNK)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def classify(changed, old_p, new_p):
    """Maps every changed block number to its write class (see the module docstring)."""
    fs_o, role_o, prob_o = fsmap.load(old_p)
    fs_n, role_n, prob_n = fsmap.load(new_p)
    if prob_o or prob_n:
        raise SystemExit("cannot map the file systems: %s" % (prob_o + prob_n)[:5])
    gdt_blocks = (fs_n.ngroups * fs_n.desc_size + BS - 1) // BS
    primary_gdt_end = 1 + gdt_blocks + fs_n.reserved_gdt
    cls = {}
    inplace = []
    for b in changed:
        ro = role_o[b] if b < len(role_o) else fsmap.FREE
        rn = role_n[b] if b < len(role_n) else fsmap.FREE
        meta = rn if rn in fsmap.METADATA else (ro if ro in fsmap.METADATA else None)
        if meta == fsmap.SB:
            cls[b] = C_SB_PRIMARY if b == 0 else C_SB_BACKUP
        elif meta == fsmap.GDT:
            cls[b] = C_GDT_PRIMARY if b < primary_gdt_end else C_SB_BACKUP
        elif meta in (fsmap.BMAP, fsmap.IMAP):
            cls[b] = C_BITMAP
        elif meta == fsmap.ITAB:
            cls[b] = C_ITABLE
        elif ro == fsmap.DIR:
            cls[b] = C_DIR
        elif ro in (fsmap.DATA, fsmap.EXT, fsmap.XATTR):
            cls[b] = C_INPLACE
            inplace.append(b)
        else:
            cls[b] = C_DATA
    return cls, inplace


def main():
    old_p, new_p, out = sys.argv[1], sys.argv[2], sys.argv[3]
    gap = int(sys.argv[sys.argv.index("--gap") + 1]) if "--gap" in sys.argv else 16
    so, sn = os.path.getsize(old_p), os.path.getsize(new_p)
    if so != sn or so % BS:
        raise SystemExit("images must have the same size (multiple of 4 KiB): %d vs %d" % (so, sn))
    os.makedirs(out, exist_ok=True)
    ho, hn = hashlib.sha256(), hashlib.sha256()
    changed = []  # block numbers
    with open(old_p, "rb") as fo, open(new_p, "rb") as fn:
        blk = 0
        while True:
            a = fo.read(CHUNK)
            b = fn.read(CHUNK)
            if not a:
                break
            ho.update(a)
            hn.update(b)
            if a != b:
                for i in range(0, len(a), BS):
                    if a[i:i + BS] != b[i:i + BS]:
                        changed.append(blk + i // BS)
            blk += len(a) // BS

    cls, inplace = classify(changed, old_p, new_p)
    if inplace:
        raise SystemExit("%d changed blocks are live file data in the old image (first: %s): a crash-safe write order is "
                         "impossible; rebuild the image so that new data goes to free blocks" % (len(inplace), inplace[:5]))

    # ranges: runs of neighbouring changed blocks of the same class (no changed block of another class inside)
    ranges = []   # [start, count, class]
    prev = None
    for c in changed:
        if prev is not None and cls[c] == cls[prev] and c - (ranges[-1][0] + ranges[-1][1]) <= gap:
            ranges[-1][1] = c - ranges[-1][0] + 1
        else:
            ranges.append([c, 1, cls[c]])
        prev = c

    entries = []
    off = 0
    fwd, rev = os.path.join(out, "forward.bin"), os.path.join(out, "reverse.bin")
    with open(old_p, "rb") as fo, open(new_p, "rb") as fn, open(fwd, "wb") as ff, open(rev, "wb") as fr:
        for start, count, c in ranges:
            fo.seek(start * BS); fn.seek(start * BS)
            a = fo.read(count * BS); b = fn.read(count * BS)
            ff.write(b); fr.write(a)
            entries.append(dict(start=start, count=count, offset=off, cls=c, klass=CLASS_NAMES[c],
                                old_sha256=hashlib.sha256(a).hexdigest(), new_sha256=hashlib.sha256(b).hexdigest()))
            off += count
    plain = [[e["start"], e["count"]] for e in entries]
    rest_old, rest_new = rest_hash(old_p, plain, so), rest_hash(new_p, plain, so)
    if rest_old != rest_new:
        raise SystemExit("internal error: the blocks outside the ranges differ between the images")

    def line(e):
        return "%d %d %d %s %s\n" % (e["start"], e["count"], e["offset"], e["old_sha256"], e["new_sha256"])

    apply_order = sorted(entries, key=lambda e: (e["cls"], e["start"]))
    revert_order = list(reversed(apply_order))
    files = {"ranges.txt": entries, "order-apply.txt": apply_order, "order-revert.txt": revert_order}
    for name, lst in files.items():
        with open(os.path.join(out, name), "w", newline="\n") as f:
            for e in lst:
                f.write(line(e))
    man = dict(
        block_size=BS, image_bytes=so, changed_blocks=len(changed), range_count=len(ranges), patch_blocks=off,
        old_sha256=ho.hexdigest(), new_sha256=hn.hexdigest(), rest_sha256=rest_old,
        forward_sha256=file_sha256(fwd), reverse_sha256=file_sha256(rev),
        ranges_sha256=file_sha256(os.path.join(out, "ranges.txt")),
        order_apply_sha256=file_sha256(os.path.join(out, "order-apply.txt")),
        order_revert_sha256=file_sha256(os.path.join(out, "order-revert.txt")),
        ranges=entries, apply_order=[e["start"] for e in apply_order],
    )
    with open(os.path.join(out, "manifest.json"), "w") as f:
        json.dump(man, f, indent=1)
    # plain key=value file for the on-device shell script
    with open(os.path.join(out, "hashes.txt"), "w", newline="\n") as f:
        for k in ("old", "new", "rest"):
            f.write("%s=%s\n" % (k, man[{"old": "old_sha256", "new": "new_sha256", "rest": "rest_sha256"}[k]]))
        for k in ("forward_sha256", "reverse_sha256", "ranges_sha256", "order_apply_sha256", "order_revert_sha256"):
            f.write("%s=%s\n" % (k, man[k]))
        f.write("image_bytes=%d\npatch_blocks=%d\n" % (so, off))
    print("changed 4K blocks : %d  (%.2f MiB)" % (len(changed), len(changed) * BS / 1048576))
    print("merged ranges     : %d  patch size %.2f MiB (per direction)" % (len(ranges), off * BS / 1048576))
    print("write order (apply):")
    for c in sorted(CLASS_NAMES):
        rs = [e for e in entries if e["cls"] == c]
        if rs:
            print("   %d. %-15s %3d ranges %5d blocks" % (c, CLASS_NAMES[c], len(rs), sum(e["count"] for e in rs)))
    print("old sha256        : " + man["old_sha256"])
    print("new sha256        : " + man["new_sha256"])
    print("rest sha256       : " + man["rest_sha256"])


if __name__ == "__main__":
    main()
