"""Turns the Meshy FBX of the car into the render-ready mesh used by the car baker.

    python fbx2mesh.py sedan.fbx OUTDIR [--front +x|-x|+z|-z]

Writes into OUTDIR:
  mesh.bin    little-endian: magic "AUMS", u32 version=1, u32 vertex_count, u32 index_count,
              f32[3*n] positions (metres-ish, car centred, wheels on y=0, +y up, car length along x, front towards +x),
              f32[3*n] normals, f32[2*n] uvs (v flipped for GL), u32[index_count] indices
  bounds.txt  extents of the transformed car
  views.png   orthographic point previews (side / top / front) to check the orientation
"""
import math
import os
import struct
import sys

import numpy as np

from fbx_binary import read_fbx, props70


def rotation_matrix(deg_xyz):
    rx, ry, rz = [math.radians(a) for a in deg_xyz]
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    mx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    my = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    mz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return mz @ my @ mx          # FBX default rotation order XYZ: applied X first


def main():
    src, out = sys.argv[1], sys.argv[2]
    front = sys.argv[sys.argv.index("--front") + 1] if "--front" in sys.argv else None
    os.makedirs(out, exist_ok=True)
    root = read_fbx(src)
    objs = root.find("Objects")
    geo = objs.find("Geometry")
    model = objs.find("Model")
    p = props70(model)
    rot = p.get("Lcl Rotation", [0, 0, 0])
    scl = p.get("Lcl Scaling", [1, 1, 1])
    trn = p.get("Lcl Translation", [0, 0, 0])

    verts = np.asarray(geo.find("Vertices").props[0], dtype=np.float64).reshape(-1, 3)
    pvi = np.asarray(geo.find("PolygonVertexIndex").props[0], dtype=np.int64)
    if not np.all((pvi < 0)[2::3]) or len(pvi) % 3:
        raise SystemExit("the mesh is not made of triangles only")
    tri_pos = pvi.copy()
    tri_pos[2::3] = ~tri_pos[2::3]                       # the last corner of each polygon is stored as -(i+1)

    ln = geo.find("LayerElementNormal")
    normals = np.asarray(ln.find("Normals").props[0], dtype=np.float64).reshape(-1, 3)
    nidx = np.asarray(ln.find("NormalsIndex").props[0], dtype=np.int64)
    luv = geo.find("LayerElementUV")
    uvs = np.asarray(luv.find("UV").props[0], dtype=np.float64).reshape(-1, 2)
    uvidx = np.asarray(luv.find("UVIndex").props[0], dtype=np.int64)

    # model transform (scale, then rotation, then translation), then the scene units
    m = rotation_matrix(rot) @ np.diag(scl)
    v = verts @ m.T + np.asarray(trn)
    nm = normals @ np.linalg.inv(m)                      # normals use the inverse transpose
    nm /= np.linalg.norm(nm, axis=1, keepdims=True) + 1e-12

    lo, hi = v.min(0), v.max(0)
    ext = hi - lo
    print("raw extents after the model transform:", np.round(ext, 4), "min", np.round(lo, 4), "max", np.round(hi, 4))
    up = int(np.argmin(ext))                              # a car is lowest along its up axis... check with the previews
    length_axis = int(np.argmax(ext))
    print("guessed up axis:", "xyz"[up], " length axis:", "xyz"[length_axis])

    # canonical frame: y up, x = length, z = width; wheels on y = 0; centred in x and z
    axes = [length_axis, up, 3 - length_axis - up]
    v = v[:, axes]
    nm = nm[:, axes]
    if np.linalg.det(np.eye(3)[axes]) < 0:              # keep it right-handed
        v[:, 2] *= -1
        nm[:, 2] *= -1
    if front in ("-x",):
        v[:, 0] *= -1
        v[:, 2] *= -1
        nm[:, 0] *= -1
        nm[:, 2] *= -1
    lo, hi = v.min(0), v.max(0)
    centre = (lo + hi) / 2
    v -= np.array([centre[0], lo[1], centre[2]])
    lo, hi = v.min(0), v.max(0)
    with open(os.path.join(out, "bounds.txt"), "w") as f:
        f.write("length %.4f height %.4f width %.4f\n" % tuple(hi - lo))
    print("canonical extents (length, height, width):", np.round(hi - lo, 4))

    # one GL vertex per distinct (position, normal, uv) corner
    key = np.stack([tri_pos, nidx, uvidx], axis=1)
    uniq, inverse = np.unique(key, axis=0, return_inverse=True)
    inverse = inverse.reshape(-1)
    pos = v[uniq[:, 0]].astype(np.float32)
    nor = nm[uniq[:, 1]].astype(np.float32)
    uv = uvs[uniq[:, 2]].astype(np.float32)
    uv[:, 1] = 1.0 - uv[:, 1]                             # GL textures start at the top row
    idx = inverse.astype(np.uint32)
    print("GL vertices:", len(pos), " triangles:", len(idx) // 3)
    with open(os.path.join(out, "mesh.bin"), "wb") as f:
        f.write(b"AUMS" + struct.pack("<III", 1, len(pos), len(idx)))
        f.write(pos.tobytes())
        f.write(nor.tobytes())
        f.write(uv.tobytes())
        f.write(idx.tobytes())
    print("mesh.bin: %.1f MB" % (os.path.getsize(os.path.join(out, "mesh.bin")) / 1048576))

    # orientation previews (points, coloured by the base-colour texture)
    try:
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = None
        tex_path = os.path.join(os.path.dirname(src), "textures", "Image_0.jpg")
        tex = np.asarray(Image.open(tex_path).convert("RGB").resize((1024, 1024)))
        col = tex[np.clip((uv[:, 1] * 1023).astype(int), 0, 1023), np.clip((uv[:, 0] * 1023).astype(int), 0, 1023)]   # uv is already GL-flipped: row = v'
        panels = []
        for a, b, flip in ((0, 1, False), (0, 2, False), (2, 1, False)):    # side (x,y), top (x,z), front (z,y)
            W, H = 600, 300
            img = np.full((H, W, 3), 40, np.uint8)
            P = pos[:, [a, b]]
            s = min((W - 20) / (P[:, 0].max() - P[:, 0].min()), (H - 20) / (P[:, 1].max() - P[:, 1].min()))
            x = ((P[:, 0] - P[:, 0].min()) * s + 10).astype(int)
            y = (H - 10 - (P[:, 1] - P[:, 1].min()) * s).astype(int)
            order = np.argsort(pos[:, 3 - a - b] if a != b else np.zeros(len(pos)))   # paint far points first
            img[np.clip(y[order], 0, H - 1), np.clip(x[order], 0, W - 1)] = col[order]
            panels.append(img)
        Image.fromarray(np.vstack(panels)).save(os.path.join(out, "views.png"))
        print("views.png written (side x-y, top x-z, front z-y)")
    except Exception as ex:
        print("preview skipped:", ex)


if __name__ == "__main__":
    main()
