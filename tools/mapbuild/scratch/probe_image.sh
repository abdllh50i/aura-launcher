#!/bin/bash
which osmium osmconvert ogr2ogr spatialite sqlite3 python3 pip3 strace gdb curl tar gzip lz4 time ltrace valgrind
echo ---
python3 -c 'import sys; print(sys.version)'
python3 -c 'import osmium; print("pyosmium", osmium.__version__)' 2>&1 | tail -1
python3 -c 'import valhalla; print("pyvalhalla", valhalla.__file__)' 2>&1 | tail -1
python3 -c 'import numpy; print("numpy", numpy.__version__)' 2>&1 | tail -1
python3 -c 'import shapely; print("shapely", shapely.__version__)' 2>&1 | tail -1
echo ---
valhalla_service --version 2>&1 | head -3
valhalla_build_tiles --version 2>&1 | head -3
valhalla_build_extract --help 2>&1 | head -60
valhalla_build_config --help 2>&1 | grep -E 'include|tile|admin|timezone|concurrency|scan|landmark|driveway|construction|platform|reclass|infer|shortcut|hierarchy|max_cache|lru|synchron|reader' | head -60
cat /etc/os-release | head -3
id; nproc; free -m; df -h /data 2>/dev/null
spatialite --version 2>&1 | head -2
echo 'SELECT spatialite_version(), geos_version(), HasGeos();' | spatialite :memory: 2>/dev/null