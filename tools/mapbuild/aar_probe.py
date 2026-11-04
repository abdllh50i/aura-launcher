#!/usr/bin/env python3
"""Looks into valhalla-mobile's AAR without running it: which Valhalla is inside, and what the JNI surface is.

    python -I aar_probe.py <valhalla-mobile.aar> [out dir for the x86_64 and armeabi-v7a .so]

Prints: the version strings of each .so, the graph-tile related strings, the exported JNI names, the Kotlin class that
binds them (from classes.jar: method names and JVM descriptors, read from the class file by hand).
"""
import io
import os
import re
import struct
import sys
import zipfile


def elf_dynsyms(b):
    """Names in .dynsym of a little-endian ELF, 32 or 64 bit."""
    is64 = b[4] == 2
    if is64:
        shoff = struct.unpack_from("<Q", b, 0x28)[0]
        shentsize, shnum = struct.unpack_from("<HH", b, 0x3A)
        fmt = "<IIQQQQIIQQ"
    else:
        shoff = struct.unpack_from("<I", b, 0x20)[0]
        shentsize, shnum = struct.unpack_from("<HH", b, 0x2E)
        fmt = "<IIIIIIIIII"
    secs = []
    for i in range(shnum):
        v = struct.unpack_from(fmt, b, shoff + i * shentsize)
        secs.append(dict(type=v[1], offset=v[4], size=v[5], link=v[6], entsize=v[9]))
    names = []
    for s in secs:
        if s["type"] != 11:  # SHT_DYNSYM
            continue
        strtab = secs[s["link"]]
        ent = s["entsize"]
        for off in range(s["offset"], s["offset"] + s["size"], ent):
            n = struct.unpack_from("<I", b, off)[0]
            end = b.index(b"\0", strtab["offset"] + n)
            names.append(b[strtab["offset"] + n:end].decode("ascii", "replace"))
    return names


def class_methods(data):
    """Method names and descriptors of a .class file; marks native ones."""
    p = 8
    count = struct.unpack_from(">H", data, p)[0]
    p += 2
    cp = [None] * count
    i = 1
    while i < count:
        tag = data[p]
        p += 1
        if tag == 1:
            n = struct.unpack_from(">H", data, p)[0]
            p += 2
            cp[i] = data[p:p + n].decode("utf-8", "replace")
            p += n
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            p += 4
        elif tag in (5, 6):
            p += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            p += 2
        elif tag == 15:
            p += 3
        else:
            raise ValueError(f"constant pool tag {tag}")
        i += 1

    p += 6  # access, this, super
    n_if = struct.unpack_from(">H", data, p)[0]
    p += 2 + 2 * n_if

    def skip_members(p):
        out = []
        n = struct.unpack_from(">H", data, p)[0]
        p += 2
        for _ in range(n):
            acc, name, desc, attrs = struct.unpack_from(">HHHH", data, p)
            p += 8
            for _ in range(attrs):
                ln = struct.unpack_from(">I", data, p + 2)[0]
                p += 6 + ln
            out.append((acc, cp[name], cp[desc]))
        return out, p

    _, p = skip_members(p)
    methods, _ = skip_members(p)
    return methods


def main():
    aar = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else None
    z = zipfile.ZipFile(aar)
    for abi in ("armeabi-v7a", "x86_64"):
        name = f"jni/{abi}/libvalhalla-wrapper.so"
        b = z.read(name)
        print(f"== {name}: {len(b):,} bytes")
        if out:
            os.makedirs(os.path.join(out, abi), exist_ok=True)
            with open(os.path.join(out, abi, "libvalhalla-wrapper.so"), "wb") as f:
                f.write(b)
        syms = elf_dynsyms(b)
        print("  JNI:", sorted(s for s in syms if s.startswith("Java_") or s == "JNI_OnLoad"))
        strs = re.findall(rb"[\x20-\x7e]{4,}", b)
        vers = sorted({s.decode() for s in strs if re.fullmatch(rb"3\.\d+\.\d+(-[0-9a-f]+)?", s)})
        print("  version-like strings:", vers)
        for key in (b"tile_extract", b"scan_tar", b"lru_mem_cache_hard_control", b"global_synchronized_cache",
                    b"max_concurrent_reader_users", b"max_reserved_labels_count_astar", b"max_reserved_labels_count_bidir_astar",
                    b"max_reserved_labels_count_bidir_dijkstras", b"clear_reserved_memory", b"index.bin", b"use_lru_mem_cache",
                    b"max_cache_size", b"tile_url", b"max_alternates"):
            print(f"  {key.decode()}: {'yes' if key in b else 'NO'}")
        hits = [s.decode() for s in strs if b"ersion" in s and (b"tile" in s.lower() or b"valhalla" in s.lower())][:12]
        print("  version mentions:", hits)

    jar = zipfile.ZipFile(io.BytesIO(z.read("classes.jar")))
    print("== classes.jar:", len(jar.namelist()), "entries")
    for n in jar.namelist():
        if n.endswith(".class"):
            print(" ", n)
    for n in jar.namelist():
        if n.endswith("ValhallaKotlin.class") or n.endswith("ValhallaKotlin$Companion.class"):
            print("==", n)
            for acc, mname, desc in class_methods(jar.read(n)):
                print(f"  {'native ' if acc & 0x0100 else ''}{'static ' if acc & 0x0008 else ''}{mname} {desc}")
    print("== proguard.txt")
    print(z.read("proguard.txt").decode("utf-8", "replace"))
    print("== aar-metadata")
    print(z.read("META-INF/com/android/build/gradle/aar-metadata.properties").decode())
    print(z.read("AndroidManifest.xml").decode())


if __name__ == "__main__":
    main()
