"""Zips a directory with portable entry names.

  python make_zip.py SRC_DIR OUT.zip

Windows PowerShell 5.1's Compress-Archive stores entries with backslashes ("patch\\forward.bin"), which unzip tools
other than Windows' own may not understand. This writes forward-slash names, a fixed order and fixed timestamps, and
Unix permissions (scripts executable) so that `unzip` on Ubuntu gives a ready-to-run install-linux.sh.
"""
import os, sys, zipfile

src, out = sys.argv[1], sys.argv[2]
files = []
for root, _dirs, names in os.walk(src):
    for n in names:
        full = os.path.join(root, n)
        files.append((os.path.relpath(full, src).replace(os.sep, "/"), full))
files.sort(key=lambda t: (t[0].count("/"), t[0]))
if os.path.exists(out):
    os.remove(out)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for arc, full in files:
        zi = zipfile.ZipInfo(arc, date_time=(2026, 10, 9, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        zi.create_system = 3        # Unix: the permission bits below are honoured by unzip
        zi.external_attr = (0o755 if arc.endswith((".sh", ".py")) else 0o644) << 16
        with open(full, "rb") as f:
            z.writestr(zi, f.read())
print("zip: %d files -> %s (%.1f MB)" % (len(files), out, os.path.getsize(out) / 1048576))
