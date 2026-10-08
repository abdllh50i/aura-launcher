"""Builds a block-level patch pack between two same-size raw images (stock -> Aura).

  python make_patch.py OLD.img NEW.img OUTDIR [--gap N]

Writes into OUTDIR:
  forward.bin     concatenated NEW data of every changed range
  reverse.bin     concatenated OLD data of every changed range (used for the one-click restore)
  ranges.txt      one "start_block count offset_in_bin old_sha256 new_sha256" line per range (4 KiB blocks)
  hashes.txt      key=value file for the on-device script (see below)
  manifest.json   the same facts for humans / other tools

hashes.txt:
  old / new         SHA-256 of the whole OLD / NEW image
  rest              SHA-256 of every block *outside* the ranges, in order (identical in OLD and NEW)
  forward_sha256 / reverse_sha256   SHA-256 of the two .bin files (detects a truncated copy before anything is written)
  image_bytes / patch_blocks        sizes

Why "rest": the whole partition is exactly `rest` + the ranges. If a write was ever interrupted, the partition matches
neither OLD nor NEW, but when `rest` still matches, rewriting all ranges from forward.bin (or reverse.bin) provably
produces NEW (or OLD) again, so the script can finish or undo an interrupted run instead of refusing.

Changed 4 KiB blocks separated by <= gap unchanged blocks are merged into one range (fewer dd calls; the merged
unchanged blocks are simply rewritten with identical data).
"""
import hashlib, json, os, sys

BS = 4096
CHUNK = 64 * 1024 * 1024


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
    ranges = []
    for c in changed:
        if ranges and c - (ranges[-1][0] + ranges[-1][1]) <= gap:
            ranges[-1][1] = c - ranges[-1][0] + 1
        else:
            ranges.append([c, 1])
    entries = []
    off = 0
    fwd, rev = os.path.join(out, "forward.bin"), os.path.join(out, "reverse.bin")
    with open(old_p, "rb") as fo, open(new_p, "rb") as fn, open(fwd, "wb") as ff, open(rev, "wb") as fr:
        for start, count in ranges:
            fo.seek(start * BS); fn.seek(start * BS)
            a = fo.read(count * BS); b = fn.read(count * BS)
            ff.write(b); fr.write(a)
            entries.append(dict(start=start, count=count, offset=off, old_sha256=hashlib.sha256(a).hexdigest(), new_sha256=hashlib.sha256(b).hexdigest()))
            off += count
    rest_old, rest_new = rest_hash(old_p, ranges, so), rest_hash(new_p, ranges, so)
    if rest_old != rest_new:
        raise SystemExit("internal error: the blocks outside the ranges differ between the images")
    with open(os.path.join(out, "ranges.txt"), "w", newline="\n") as f:
        for e in entries:
            f.write("%d %d %d %s %s\n" % (e["start"], e["count"], e["offset"], e["old_sha256"], e["new_sha256"]))
    man = dict(
        block_size=BS, image_bytes=so, changed_blocks=len(changed), range_count=len(ranges), patch_blocks=off,
        old_sha256=ho.hexdigest(), new_sha256=hn.hexdigest(), rest_sha256=rest_old,
        forward_sha256=file_sha256(fwd), reverse_sha256=file_sha256(rev), ranges=entries,
    )
    with open(os.path.join(out, "manifest.json"), "w") as f:
        json.dump(man, f, indent=1)
    # plain key=value file for the on-device shell script
    with open(os.path.join(out, "hashes.txt"), "w", newline="\n") as f:
        f.write("old=%s\nnew=%s\nrest=%s\nforward_sha256=%s\nreverse_sha256=%s\nimage_bytes=%d\npatch_blocks=%d\n" % (
            man["old_sha256"], man["new_sha256"], man["rest_sha256"], man["forward_sha256"], man["reverse_sha256"], so, off))
    print("changed 4K blocks : %d  (%.2f MiB)" % (len(changed), len(changed) * BS / 1048576))
    print("merged ranges     : %d  patch size %.2f MiB (per direction)" % (len(ranges), off * BS / 1048576))
    print("old sha256        : " + man["old_sha256"])
    print("new sha256        : " + man["new_sha256"])
    print("rest sha256       : " + man["rest_sha256"])


if __name__ == "__main__":
    main()
