"""Block ownership map of an ext4 image (read-only): which 4 KiB blocks hold the superblock / group descriptors, the
allocation bitmaps, inode tables, directory data, file data, extent-tree and xattr blocks, and which are free.

Used by make_patch.py to decide in which order a block patch may be written so that *every* prefix of the write sequence
is still a consistent file system (data first, the structures that point at it last).
"""
import struct
from imgdiff import Ext4, S_IFMT, S_IFDIR, S_IFLNK

FREE, SB, GDT, BMAP, IMAP, ITAB, DIR, DATA, EXT, XATTR = range(10)
NAMES = ["free", "sb", "gdt", "bitmap", "ibitmap", "itable", "dir", "data", "extent-node", "xattr"]
METADATA = (SB, GDT, BMAP, IMAP, ITAB)


def _backup_group(g, sparse):
    if not sparse or g in (0, 1):
        return True
    for base in (3, 5, 7):
        x = base
        while x < g:
            x *= base
        if x == g:
            return True
    return False


def _walk_extents(fs, node, role, kind, problems):
    magic, entries, _mx, depth = struct.unpack_from("<HHHH", node, 0)
    if magic != 0xF30A:
        raise ValueError("bad extent header")
    for e in range(entries):
        o = 12 + e * 12
        if depth == 0:
            _blk, ln, hi, lo = struct.unpack_from("<IHHI", node, o)
            if ln > 32768:
                ln -= 32768
            start = (hi << 32) | lo
            if start + ln > fs.blocks:
                problems.append("extent beyond the end of the file system")
                continue
            for b in range(start, start + ln):
                role[b] = kind
        else:
            _b, lo, hi, _u = struct.unpack_from("<IIHH", node, o)
            child = (hi << 32) | lo
            if child >= fs.blocks:
                problems.append("extent node beyond the end of the file system")
                continue
            role[child] = EXT
            fs.f.seek(child * fs.bs)
            _walk_extents(fs, fs.f.read(fs.bs), role, kind, problems)


def build(fs):
    """Returns (role, problems): role is a bytearray with one role code per block of the file system."""
    assert fs.bs == 4096 and fs.first_data_block == 0, "only 4 KiB block file systems are supported"
    assert not (fs.incompat & 0x10), "meta_bg layout is not supported"
    n = fs.blocks
    role = bytearray(n)
    problems = []
    gdt_blocks = (fs.ngroups * fs.desc_size + fs.bs - 1) // fs.bs
    itable_blocks = fs.inodes_per_group * fs.inode_size // fs.bs
    for g in range(fs.ngroups):
        gstart = g * fs.blocks_per_group
        if _backup_group(g, fs.sparse_super):
            role[gstart] = SB
            for b in range(gstart + 1, min(n, gstart + 1 + gdt_blocks + fs.reserved_gdt)):
                role[b] = GDT
        role[fs.bbitmap[g]] = BMAP
        role[fs.ibitmap[g]] = IMAP
        for b in range(fs.itable[g], fs.itable[g] + itable_blocks):
            role[b] = ITAB
    # inodes in use (inode bitmap), then everything they own
    for g in range(fs.ngroups):
        fs.f.seek(fs.ibitmap[g] * fs.bs)
        bits = fs.f.read(fs.bs)
        for i in range(fs.inodes_per_group):
            if not bits[i >> 3] & (1 << (i & 7)):
                continue
            ino = g * fs.inodes_per_group + i + 1
            if ino == 7:      # resize inode: its blocks are the reserved GDT blocks, already marked
                continue
            m = fs.meta(ino)
            raw = m["raw"]
            mode, flags = m["mode"], m["flags"]
            links = struct.unpack_from("<H", raw, 0x1A)[0]
            if mode == 0 or links == 0:
                continue
            acl = struct.unpack_from("<I", raw, 0x68)[0] | (struct.unpack_from("<H", raw, 0x76)[0] << 32)
            if acl and acl < n:
                role[acl] = XATTR
            t = mode & S_IFMT
            if flags & 0x10000000:                               # inline data
                continue
            if t == S_IFLNK and m["blocks512"] == 0:             # fast symlink
                continue
            if not flags & 0x80000:
                if m["blocks512"]:
                    problems.append("inode %d uses a block map (not supported)" % ino)
                continue
            try:
                _walk_extents(fs, m["iblock"], role, DIR if t == S_IFDIR else DATA, problems)
            except ValueError as ex:
                problems.append("inode %d: %s" % (ino, ex))
    return role, problems


def load(path):
    fs = Ext4(path)
    role, problems = build(fs)
    return fs, role, problems


if __name__ == "__main__":
    import sys
    fs, role, problems = load(sys.argv[1])
    counts = {}
    for c in role:
        counts[c] = counts.get(c, 0) + 1
    print("%s: %d blocks, %d groups" % (sys.argv[1], fs.blocks, fs.ngroups))
    for c in range(len(NAMES)):
        print("  %-12s %7d" % (NAMES[c], counts.get(c, 0)))
    for p in problems[:20]:
        print("  PROBLEM:", p)
