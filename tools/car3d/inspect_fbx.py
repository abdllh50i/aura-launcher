"""Prints what a binary FBX contains: objects, mesh sizes, materials, textures and the embedded image files.
    python inspect_fbx.py model.fbx [--dump-textures OUTDIR]
"""
import os
import sys

from fbx_binary import read_fbx, props70


def name_of(n):
    raw = n.props[1] if len(n.props) > 1 else b""
    return raw.split(b"\x00\x01")[0].decode("utf-8", "replace") if isinstance(raw, (bytes, bytearray)) else str(raw)


def main():
    path = sys.argv[1]
    root = read_fbx(path)
    print("FBX version", root.props[0], "| top-level:", [c.name for c in root.children])
    gs = root.find("GlobalSettings")
    if gs:
        p = props70(gs)
        for k in ("UpAxis", "UpAxisSign", "FrontAxis", "FrontAxisSign", "CoordAxis", "CoordAxisSign", "UnitScaleFactor"):
            if k in p:
                print("  %s = %s" % (k, p[k]))
    objs = root.find("Objects")
    kinds = {}
    for o in objs.children:
        kinds[o.name] = kinds.get(o.name, 0) + 1
    print("objects:", kinds)
    for g in objs.find_all("Geometry"):
        v = g.find("Vertices").props[0]
        pvi = g.find("PolygonVertexIndex").props[0]
        polys = int((pvi < 0).sum())
        print("Geometry %r: %d vertices, %d polygon corners, %d polygons" % (name_of(g), len(v) // 3, len(pvi), polys))
        for layer in ("LayerElementNormal", "LayerElementUV", "LayerElementMaterial", "LayerElementColor", "LayerElementTangent"):
            for le in g.find_all(layer):
                mapping = le.find("MappingInformationType").props[0] if le.find("MappingInformationType") else b"?"
                ref = le.find("ReferenceInformationType").props[0] if le.find("ReferenceInformationType") else b"?"
                sizes = {c.name: len(c.props[0]) for c in le.children if c.props and hasattr(c.props[0], "__len__") and not isinstance(c.props[0], (bytes, bytearray))}
                print("   %s: %s / %s %s" % (layer, mapping.decode(), ref.decode(), sizes))
    for m in objs.find_all("Model"):
        p = props70(m)
        print("Model %r type=%s T=%s R=%s S=%s" % (name_of(m), m.props[2].decode() if len(m.props) > 2 else "?",
                                                   p.get("Lcl Translation"), p.get("Lcl Rotation"), p.get("Lcl Scaling")))
    for mat in objs.find_all("Material"):
        p = props70(mat)
        print("Material %r: %s" % (name_of(mat), {k: v for k, v in p.items() if "Color" in k or "Factor" in k or "Shininess" in k}))
    for t in objs.find_all("Texture"):
        fn = t.find("RelativeFilename") or t.find("FileName")
        print("Texture %r -> %s" % (name_of(t), fn.props[0].decode("utf-8", "replace") if fn else "?"))
    dump = sys.argv[sys.argv.index("--dump-textures") + 1] if "--dump-textures" in sys.argv else None
    for v in objs.find_all("Video"):
        content = v.find("Content")
        fn = v.find("RelativeFilename") or v.find("Filename")
        size = len(content.props[0]) if content and content.props else 0
        fname = fn.props[0].decode("utf-8", "replace") if fn else "?"
        print("Video %r file=%s embedded=%d bytes" % (name_of(v), fname, size))
        if dump and size:
            os.makedirs(dump, exist_ok=True)
            out = os.path.join(dump, os.path.basename(fname.replace("\\", "/")) or (name_of(v) + ".bin"))
            open(out, "wb").write(content.props[0])
            print("   -> written", out)
    conns = root.find("Connections")
    print("connections:", len(conns.children) if conns else 0)
    for c in (conns.children if conns else [])[:40]:
        print("  ", [x.decode() if isinstance(x, (bytes, bytearray)) else x for x in c.props])


if __name__ == "__main__":
    main()
