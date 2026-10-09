"""Proves that every prefix of the patch write sequence leaves a consistent, readable file system.

  python check_order.py OLD.img NEW.img PACKDIR [--order apply|revert|ascending] [--step N]

The chosen sequence of blocks comes from order-apply.txt / order-revert.txt (or from ranges.txt in ascending order, which
is only a negative control: the order the first version of the pack used). The state after k written blocks is simulated on
top of the source image (apply: OLD + first k blocks of NEW; revert: NEW + first k blocks of OLD) and in *every* state:
  * the superblock and group descriptors parse and describe a geometry the kernel accepts,
  * every path that differs between the images (and all its parent directories) resolves to exactly the OLD or exactly
    the NEW version: never garbage, a dangling directory entry, or an extent beyond the end of the file system.
Exit code 1 if any state is bad.
"""
import hashlib, os, struct, sys

from imgdiff import Ext4, S_IFMT, S_IFDIR, S_IFREG, S_IFLNK, describe

BS = 4096


class Overlay:
    """Read-only file-like view of an image with some 4 KiB blocks replaced."""

    def __init__(self, path, over):
        self.f = open(path, "rb")
        self.over = over
        self.pos = 0

    def seek(self, pos):
        self.pos = pos

    def read(self, n):
        out = bytearray()
        pos, end = self.pos, self.pos + n
        while pos < end:
            blk, off = divmod(pos, BS)
            take = min(BS - off, end - pos)
            data = self.over.get(blk)
            if data is None:
                self.f.seek(pos)
                chunk = self.f.read(take)
            else:
                chunk = data[off:off + take]
            out += chunk
            pos += len(chunk)
            if len(chunk) < take:
                break
        self.pos = pos
        return bytes(out)

    def close(self):
        self.f.close()


class Bad(Exception):
    pass


def check_inode(fs, ino):
    m = fs.meta(ino)
    raw = m["raw"]
    links = struct.unpack_from("<H", raw, 0x1A)[0]
    dtime = struct.unpack_from("<I", raw, 0x14)[0]
    if m["mode"] == 0 or links == 0 or dtime:
        raise Bad("directory entry points at an unused inode (%d)" % ino)
    return m


def extents_ok(fs, m):
    if not m["flags"] & 0x80000:
        return
    ext = []
    fs._extents(m["iblock"], ext)
    for _l, ln, p in ext:
        if p + ln > fs.blocks or p == 0:
            raise Bad("extent %d+%d lies outside the file system (%d blocks)" % (p, ln, fs.blocks))


def lookup(fs, path):
    ino = 2
    for comp in [c for c in path.split("/") if c]:
        m = check_inode(fs, ino)
        if (m["mode"] & S_IFMT) != S_IFDIR:
            return None
        extents_ok(fs, m)
        for name, i in fs.entries(ino):
            if name == comp:
                ino = i
                break
        else:
            return None
    return ino


def view(fs, path):
    ino = lookup(fs, path)
    if ino is None:
        return ("-", None)
    m = check_inode(fs, ino)
    t = m["mode"] & S_IFMT
    if t == S_IFDIR:
        extents_ok(fs, m)
        return ("d", tuple(sorted(n for n, _ in fs.entries(ino))))
    if t == S_IFLNK:
        extents_ok(fs, m)
        return ("l", fs.read(m))
    extents_ok(fs, m)
    return ("f", hashlib.sha256(fs.read(m)).hexdigest())


def geometry(fs, total_blocks):
    if fs.blocks > total_blocks:
        raise Bad("file system larger than the partition")
    for g in range(fs.ngroups):
        for what, blk in (("block bitmap", fs.bbitmap[g]), ("inode bitmap", fs.ibitmap[g]), ("inode table", fs.itable[g])):
            if not 0 < blk < fs.blocks:
                raise Bad("group %d: %s at %d is outside the file system" % (g, what, blk))


def watch_paths(a, b):
    """Paths that differ between the two images, plus all their parent directories."""
    ta, tb = {p: i for p, i in a.walk()}, {p: i for p, i in b.walk()}
    paths = set()
    for p in set(ta) | set(tb):
        if p not in ta or p not in tb or describe(a, ta[p]) != describe(b, tb[p]):
            paths.add(p)
    for p in list(paths):
        parts = [c for c in p.split("/") if c]
        for i in range(len(parts)):
            paths.add("/" + "/".join(parts[:i]) if i else "/")
    return sorted(paths)


def read_order(pack, which):
    name = {"apply": "order-apply.txt", "revert": "order-revert.txt", "ascending": "ranges.txt"}[which]
    out = []
    for ln in open(os.path.join(pack, name)):
        p = ln.split()
        if len(p) >= 2:
            out.append((int(p[0]), int(p[1])))
    return out


def main():
    old_p, new_p, pack = sys.argv[1:4]
    orders = (sys.argv[sys.argv.index("--order") + 1] if "--order" in sys.argv else "apply").split(",")
    step = int(sys.argv[sys.argv.index("--step") + 1]) if "--step" in sys.argv else 1
    A, B = Ext4(old_p), Ext4(new_p)
    paths = watch_paths(A, B)
    exp = {p: {view(A, p), view(B, p)} for p in paths}
    print("watching %d paths (changed files, new files, their parent directories)" % len(paths))
    worst = 0
    for which in orders:
        worst = max(worst, run(old_p, new_p, pack, which, step, paths, exp))
    sys.exit(worst)


def run(old_p, new_p, pack, which, step, paths, exp):
    total = os.path.getsize(old_p) // BS
    base_p = old_p if which in ("apply", "ascending") else new_p

    with open(old_p, "rb") as fo, open(new_p, "rb") as fn:
        def blk(f, n):
            f.seek(n * BS)
            return f.read(BS)
        seq = []
        for start, count in read_order(pack, which):
            for n in range(start, start + count):
                if blk(fo, n) != blk(fn, n):
                    seq.append(n)
        data = {n: blk(fn if which in ("apply", "ascending") else fo, n) for n in seq}

    over = {}
    bad_states = []
    states = 0
    for k in range(0, len(seq) + 1):
        if k:
            over[seq[k - 1]] = data[seq[k - 1]]
        if k % step and k != len(seq):
            continue
        states += 1
        ov = Overlay(base_p, over)
        try:
            fs = Ext4(ov)
            geometry(fs, total)
            for p in paths:
                v = view(fs, p)
                if v not in exp[p]:
                    raise Bad("%s resolves to something that is neither the old nor the new version" % p)
        except Bad as ex:
            bad_states.append((k, str(ex)))
        except Exception as ex:   # an unreadable structure is as bad as a wrong one
            bad_states.append((k, "unreadable: %r" % ex))
        finally:
            ov.close()
    print("order=%-9s blocks written one by one: %d  states checked: %d  BAD states: %d" % (which, len(seq), states, len(bad_states)))
    shown = 0
    for k, why in bad_states:
        if shown < 6:
            print("   after block #%d (%d): %s" % (k, seq[k - 1] if k else -1, why))
            shown += 1
    return 1 if bad_states else 0


if __name__ == "__main__":
    main()
