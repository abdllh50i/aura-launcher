"""Builds a block-level patch pack between two same-size raw images (stock -> Aura).

  python make_patch.py OLD.img NEW.img OUTDIR [--gap N]

Writes into OUTDIR:
  forward.bin     concatenated NEW data of every changed range
  reverse.bin     concatenated OLD data of every changed range (used for the one-click restore)
  ranges.txt      one "start_block count offset_in_bin" line per range (4 KiB blocks)
  manifest.json   sizes, whole-image SHA-256 of OLD and NEW, per-range SHA-256 of old/new data

Changed 4 KiB blocks separated by <= gap unchanged blocks are merged into one range (fewer dd calls; the merged
unchanged blocks are simply rewritten with identical data).
"""
import hashlib, json, os, sys

BS = 4096
CHUNK = 64 * 1024 * 1024


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
    with open(old_p, "rb") as fo, open(new_p, "rb") as fn, open(os.path.join(out, "forward.bin"), "wb") as ff, open(os.path.join(out, "reverse.bin"), "wb") as fr:
        for start, count in ranges:
            fo.seek(start * BS); fn.seek(start * BS)
            a = fo.read(count * BS); b = fn.read(count * BS)
            ff.write(b); fr.write(a)
            entries.append(dict(start=start, count=count, offset=off, old_sha256=hashlib.sha256(a).hexdigest(), new_sha256=hashlib.sha256(b).hexdigest()))
            off += count
    with open(os.path.join(out, "ranges.txt"), "w", newline="\n") as f:
        for e in entries:
            f.write("%d %d %d\n" % (e["start"], e["count"], e["offset"]))
    man = dict(
        block_size=BS, image_bytes=so, changed_blocks=len(changed), range_count=len(ranges), patch_blocks=off,
        old_sha256=ho.hexdigest(), new_sha256=hn.hexdigest(), ranges=entries,
    )
    with open(os.path.join(out, "manifest.json"), "w") as f:
        json.dump(man, f, indent=1)
    # plain key=value file for the on-device shell script
    with open(os.path.join(out, "hashes.txt"), "w", newline="\n") as f:
        f.write("old=%s\nnew=%s\nimage_bytes=%d\n" % (man["old_sha256"], man["new_sha256"], so))
    print("changed 4K blocks : %d  (%.2f MiB)" % (len(changed), len(changed) * BS / 1048576))
    print("merged ranges     : %d  patch size %.2f MiB (per direction)" % (len(ranges), off * BS / 1048576))
    print("old sha256        : " + man["old_sha256"])
    print("new sha256        : " + man["new_sha256"])


if __name__ == "__main__":
    main()
