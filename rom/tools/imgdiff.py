"""Compare two ext4 images file by file (type, mode, uid/gid, SELinux label, link target, content hash).
usage: python imgdiff.py OLD.img NEW.img
Prints added / removed / changed paths and a summary. Exit code 0 always (it is a report, not a gate).
Self-contained read-only ext4 reader (extents + block maps), same logic the ROM workshop output is validated with.
"""
import hashlib, struct, sys

S_IFMT, S_IFDIR, S_IFREG, S_IFLNK = 0o170000, 0o040000, 0o100000, 0o120000


class Ext4:
    def __init__(self, path):
        """[path] is a file name, or an already open binary file-like object (used by the write-order simulation)."""
        self.f = path if hasattr(path, "read") else open(path, "rb")
        self.f.seek(1024)
        sb = self.f.read(1024)
        assert struct.unpack_from("<H", sb, 0x38)[0] == 0xEF53, "not ext4"
        self.bs = 1024 << struct.unpack_from("<I", sb, 0x18)[0]
        self.first_data_block = struct.unpack_from("<I", sb, 0x14)[0]
        self.blocks_per_group = struct.unpack_from("<I", sb, 0x20)[0]
        self.inodes_per_group = struct.unpack_from("<I", sb, 0x28)[0]
        self.inode_size = struct.unpack_from("<H", sb, 0x58)[0]
        self.reserved_gdt = struct.unpack_from("<H", sb, 0xCE)[0]
        self.compat, incompat, self.ro_compat = struct.unpack_from("<III", sb, 0x5C)
        self.incompat = incompat
        self.sparse_super = bool(self.ro_compat & 0x1)
        is64 = bool(incompat & 0x80)
        self.desc_size = max(32, struct.unpack_from("<H", sb, 0xFE)[0]) if is64 else 32
        bc = struct.unpack_from("<I", sb, 4)[0] | ((struct.unpack_from("<I", sb, 0x150)[0] << 32) if is64 else 0)
        self.blocks = bc
        ngroups = (bc - self.first_data_block + self.blocks_per_group - 1) // self.blocks_per_group
        self.ngroups = ngroups
        self.f.seek((self.first_data_block + 1) * self.bs)
        raw = self.f.read(ngroups * self.desc_size)
        self.itable, self.bbitmap, self.ibitmap = [], [], []
        for g in range(ngroups):
            d = raw[g * self.desc_size:(g + 1) * self.desc_size]
            hi64 = is64 and self.desc_size >= 64
            self.bbitmap.append(struct.unpack_from("<I", d, 0)[0] | ((struct.unpack_from("<I", d, 0x20)[0] << 32) if hi64 else 0))
            self.ibitmap.append(struct.unpack_from("<I", d, 4)[0] | ((struct.unpack_from("<I", d, 0x24)[0] << 32) if hi64 else 0))
            self.itable.append(struct.unpack_from("<I", d, 8)[0] | ((struct.unpack_from("<I", d, 0x28)[0] << 32) if hi64 else 0))

    def raw_inode(self, ino):
        g, idx = divmod(ino - 1, self.inodes_per_group)
        self.f.seek(self.itable[g] * self.bs + idx * self.inode_size)
        return self.f.read(self.inode_size)

    def meta(self, ino):
        r = self.raw_inode(ino)
        mode, uid = struct.unpack_from("<HH", r, 0)
        size = struct.unpack_from("<I", r, 4)[0] | (struct.unpack_from("<I", r, 0x6C)[0] << 32)
        gid = struct.unpack_from("<H", r, 0x18)[0]
        flags = struct.unpack_from("<I", r, 0x20)[0]
        iblock = r[0x28:0x28 + 60]
        blocks512 = struct.unpack_from("<I", r, 0x1C)[0]
        uid |= struct.unpack_from("<H", r, 0x78)[0] << 16
        gid |= struct.unpack_from("<H", r, 0x7A)[0] << 16
        return dict(mode=mode, uid=uid, gid=gid, size=size, flags=flags, iblock=iblock, blocks512=blocks512, raw=r)

    def xattrs(self, m):
        r = m["raw"]
        out = {}
        if len(r) <= 128:
            return out
        extra = struct.unpack_from("<H", r, 128)[0]
        start = 128 + extra
        if start + 4 > len(r) or struct.unpack_from("<I", r, start)[0] != 0xEA020000:
            return out
        pos = start + 4
        base = pos
        while pos + 16 <= len(r) and struct.unpack_from("<I", r, pos)[0] != 0:
            nl, ni, voff, vino, vsz, h = struct.unpack_from("<BBHIII", r, pos)
            name = r[pos + 16:pos + 16 + nl].decode("latin1")
            val = r[base + voff:base + voff + vsz]
            prefix = {1: "user.", 2: "system.posix_acl_access", 3: "system.posix_acl_default", 4: "trusted.", 6: "security.", 7: "system."}.get(ni, "?%d." % ni)
            out[prefix + name] = bytes(val)
            pos += (16 + nl + 3) & ~3
        return out

    def _extents(self, node, out):
        magic, entries, _mx, depth = struct.unpack_from("<HHHH", node, 0)
        assert magic == 0xF30A
        for e in range(entries):
            o = 12 + e * 12
            if depth == 0:
                blk, ln, hi, lo = struct.unpack_from("<IHHI", node, o)
                if ln > 32768:
                    ln -= 32768
                out.append((blk, ln, (hi << 32) | lo))
            else:
                _b, lo, hi, _u = struct.unpack_from("<IIHH", node, o)
                self.f.seek(((hi << 32) | lo) * self.bs)
                self._extents(self.f.read(self.bs), out)

    def read(self, m):
        size = m["size"]
        if (m["mode"] & S_IFMT) == S_IFLNK and size < 60 and m["blocks512"] == 0:
            return m["iblock"][:size]
        assert m["flags"] & 0x80000, "block-mapped files not supported in imgdiff"
        ext = []
        self._extents(m["iblock"], ext)
        buf = bytearray(size)
        for blk, ln, pblk in ext:
            start = blk * self.bs
            if start >= size:
                continue
            n = min(ln * self.bs, size - start)
            self.f.seek(pblk * self.bs)
            buf[start:start + n] = self.f.read(n)
        return bytes(buf)

    def entries(self, ino):
        data = self.read(self.meta(ino))
        pos = 0
        while pos + 8 <= len(data):
            i, rl, nl, _t = struct.unpack_from("<IHBB", data, pos)
            if rl < 8:
                break
            if i and nl:
                name = data[pos + 8:pos + 8 + nl].decode("utf-8", "replace")
                if name not in (".", ".."):
                    yield name, i
            pos += rl

    def walk(self, ino=2, path=""):
        yield path or "/", ino
        m = self.meta(ino)
        if (m["mode"] & S_IFMT) == S_IFDIR:
            for name, i in sorted(self.entries(ino)):
                cm = self.meta(i)
                p = path + "/" + name
                if (cm["mode"] & S_IFMT) == S_IFDIR:
                    yield from self.walk(i, p)
                else:
                    yield p, i


def describe(fs, ino, with_hash=True):
    m = fs.meta(ino)
    t = m["mode"] & S_IFMT
    d = dict(type=t, perm=m["mode"] & 0o7777, uid=m["uid"], gid=m["gid"])
    x = fs.xattrs(m)
    d["label"] = x.get("security.selinux", b"").rstrip(b"\0").decode("latin1")
    d["xattrs"] = sorted(k for k in x if k != "security.selinux")
    if "security.capability" in x:
        d["cap"] = x["security.capability"].hex()
    if t == S_IFLNK:
        d["target"] = fs.read(m).decode("utf-8", "replace")
    elif t == S_IFREG:
        d["size"] = m["size"]
        if with_hash:
            d["sha"] = hashlib.sha256(fs.read(m)).hexdigest()
    return d


def main(a, b):
    A, B = Ext4(a), Ext4(b)
    ta = {p: i for p, i in A.walk()}
    tb = {p: i for p, i in B.walk()}
    added = sorted(set(tb) - set(ta))
    removed = sorted(set(ta) - set(tb))
    changed = []
    for p in sorted(set(ta) & set(tb)):
        da, db = describe(A, ta[p]), describe(B, tb[p])
        if da != db:
            diff = [k for k in set(da) | set(db) if da.get(k) != db.get(k)]
            changed.append((p, sorted(diff)))
    print("images: %s (%d paths)  vs  %s (%d paths)" % (a, len(ta), b, len(tb)))
    print("ADDED   (%d)" % len(added))
    for p in added:
        d = describe(B, tb[p], with_hash=False)
        print("   + %-62s %s %o %d:%d %s" % (p, {S_IFDIR: "dir", S_IFREG: "file", S_IFLNK: "link"}.get(d["type"], "?"), d["perm"], d["uid"], d["gid"], d["label"]))
    print("REMOVED (%d)" % len(removed))
    for p in removed:
        print("   - " + p)
    print("CHANGED (%d)" % len(changed))
    for p, diff in changed:
        print("   ~ %-62s %s" % (p, ",".join(diff)))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
