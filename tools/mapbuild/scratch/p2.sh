ls /usr/local/include/valhalla 2>/dev/null | head; ls /usr/local/lib/python3.12/dist-packages/valhalla/ ; python3 -c 'import valhalla; print(valhalla.__version__ if hasattr(valhalla,"__version__") else dir(valhalla))'; strings -a /usr/local/bin/valhalla_service 2>/dev/null | grep -n 'Tiles were built' | head; which strings; grep -c . /dev/null; python3 - <<'PY'
import re
b=open('/usr/local/bin/valhalla_service','rb').read()
print(len(b))
for m in re.finditer(rb'Tiles were built with version ',b):
    print(''.join(chr(c) if 32<=c<127 else '.' for c in b[m.start()-120:m.end()+160]))
for m in list(re.finditer(rb'3\.9\.1',b))[:5]:
    print(m.start(), ''.join(chr(c) if 32<=c<127 else '.' for c in b[m.start()-40:m.end()+40]))
PY