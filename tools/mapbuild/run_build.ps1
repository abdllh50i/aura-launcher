# Rebuilds the Saudi base tiles and routing tar on this PC, in order. Windows PowerShell 5.1, Docker Desktop running.
# Scripts live here (public repo, nothing secret); data lives in $W, never in the repo. Each step skips nothing by itself:
# comment out what is already done. The downloads are in .\download list at the end.
#
#   Step   What                                   In                             Out                               Measured here
#   1      Saudi outline from relation 307584     gcc-states PBF                 polys\ksa.poly (also .\polygon\)  seconds
#   2      Cut the Kingdom out                    gcc-states PBF, ksa.poly       src\ksa.osm.pbf                   12 min (pyosmium, HDD)
#   3      Sea polygons from OSM's coastline      PBF, ksa.geojson, Natural Earth sources\water-polygons-ksa.shp.zip  31 s
#   4      Base tiles                             ksa.osm.pbf, poly, water       out\base.mbtiles                  see build_base.sh
#   5      Admin database, then routing tar       ksa.osm.pbf                    v\admin-ksa.sqlite, out\route.tar  20 s, 91 s + 1 s
#   6      The probe set                          probe.osm.pbf, the admin       out\route-probe.tar.gz            25 s
#   7      Reference answers and checks           the tar, a running service     out\ref-routes\
param([string]$W = 'D:\k2501-work\mapbuild')
$ErrorActionPreference = 'Stop'
$S = $PSScriptRoot
$docker = 'E:\Docker\Program\resources\bin\docker.exe'
$python = 'python'
$venv = "$W\venv\Scripts\python.exe"       # pyosmium, only for the cut
$V = 'ghcr.io/valhalla/valhalla:3.9.1'
$P = 'openmaptiles/planetiler-openmaptiles:3.16'
$env:TEMP = "$W\tmp"; $env:TMP = "$W\tmp"; $env:PIP_CACHE_DIR = "$W\tmp\pip"

function Valhalla([string]$tmpfs, [string[]]$rest) {
    & $docker run --rm --memory 9g -e CONCURRENCY=6 -v "${W}:/data" -v "${S}:/scripts:ro" --tmpfs "/work:size=$tmpfs,exec" --entrypoint bash $V @rest
}

# 1. The outline: relation 307584, 0.03 degrees of buffer so border posts and the causeway's Saudi end stay in.
& $venv -I "$S\boundary_members.py" "$W\src\gcc-states-latest.osm.pbf" 307584 "$W\polys\rel307584-members.json"
& $docker run --rm -v "${W}:/data" -v "${S}:/scripts:ro" --entrypoint python3 $V -I /scripts/boundary_poly.py /data/polys/rel307584-members.json /data/polys ksa 0.03 0.005

# 2. The cuts.
& $venv -I "$S\cut_pbf.py" "$W\src\gcc-states-latest.osm.pbf" "$W\src\ksa.osm.pbf" --poly "$W\polys\ksa.poly"
& $venv -I "$S\cut_pbf.py" "$W\src\ksa.osm.pbf" "$W\src\probe.osm.pbf" --bbox 49.4,26.0,50.4,27.3

# 3. The sea. Planetiler's usual file (osmdata.openstreetmap.de) could not be reached from here on 2026-11-04.
& $venv -I "$S\coast_ways.py" "$W\src\gcc-states-latest.osm.pbf" "$W\polys\gcc-coast.json"
& $python -I "$S\make_empty_shapefile.py" "$W\sources\lake_centerline.empty.shp.zip"
& $docker run --rm --memory 7g -v "${W}:/data" -v "${S}:/scripts:ro" --tmpfs /work:size=4g -e TMPDIR=/work --entrypoint python3 $V -I /scripts/make_water_polygons.py /data/polys/gcc-coast.json /data/polys/ksa.geojson /data/sources/natural_earth_vector.sqlite.zip /data/sources/water-polygons-ksa.shp.zip

# 4. Base tiles. The heap and the tmpfs together are about 10 GB at the peak.
& $docker run --rm --memory 13g -e 'JAVA_TOOL_OPTIONS=-Xmx6g -Djava.io.tmpdir=/work/tmp' -e THREADS=8 -v "${W}:/data" -v "${S}:/scripts:ro" --tmpfs '/work:size=7g,exec' --entrypoint sh $P /scripts/build_base.sh /data/src/ksa.osm.pbf /data/polys/ksa.poly /data/sources/water-polygons-ksa.shp.zip /data/out/base.mbtiles

# 5. Routing. The admin database comes first: it holds the Kingdom's boundary and default_language=ar.
Valhalla '1g' @('/scripts/build_admin.sh', '/data/src/ksa.osm.pbf', '/data/v/admin-ksa.sqlite')
Valhalla '6g' @('/scripts/build_set.sh', '/data/src/ksa.osm.pbf', '/data/v/admin-ksa.sqlite', '/data/out/route.tar')

# 6. The probe set, with that same admin database.
Valhalla '2g' @('/scripts/build_set.sh', '/data/src/probe.osm.pbf', '/data/v/admin-ksa.sqlite', '/data/out/route-probe.tar')

# 7. Reference answers. The service stays up until it is removed: docker rm -f amri-serve.
& $docker run -d --name amri-serve --memory 2g -p 127.0.0.1:8002:8002 -v "${W}:/data:ro" -v "${S}:/scripts:ro" --tmpfs /work:size=500m --entrypoint bash $V /scripts/serve_ram.sh /data/out/route.tar 2
Start-Sleep -Seconds 60
& $python -I "$S\ref_routes.py" http://127.0.0.1:8002 "$W\out\ref-routes"
& $python -I "$S\route_fields.py" "$W\out\ref-routes" "$W\out\ref-routes\fields.json"
& $python -I "$S\api_checks.py" http://127.0.0.1:8002 "$W\out\ref-routes" "$W\out\ref-routes\api-checks.json"
& $python -I "$S\compare_osrm.py" "$W\out\ref-routes" "$W\out\ref-routes\compare-osrm.tsv"
& $python -I "$S\names_check.py" "$W\places\osm-road.jsonl" http://127.0.0.1:8002 '34.4,16.2,55.7,32.2' "$W\logs\names-full.json" 60
& $python -I "$S\make_requests.py" "$W\harness\req"
& $docker exec -e PORT=8003 amri-serve bash /scripts/mem_peak.sh /work/route.tar /data/harness/req /data/out/ref-routes/mem 67108864 dammam-jeddah riyadh-jeddah abha-riyadh dammam-riyadh jubail-dammam
& $docker exec amri-serve bash /scripts/tar_start_check.sh /data/out/route-probe.tar /data/harness/req/jubail-dammam.json
& $python -I "$S\check_tiles.py" "$W\out\base.mbtiles" --style "$S\..\..\app\src\main\java\com\abdllh\aura\nav\MapStyle.kt" --poi "$S\..\..\app\src\main\java\com\abdllh\aura\nav\Poi.kt" --ofm 'D:\k2501-work\tmp\v2\scratch\nav\data\tiles' --out "$W\logs\check-tiles.txt"

# Downloads this needs, once:
#   https://download.geofabrik.de/asia/gcc-states-latest.osm.pbf                                                    src\      257 MB
#   https://dev.maptiler.download/geodata/omt/natural_earth_vector.sqlite.zip (Planetiler's --download)             sources\  433 MB
#   https://repo1.maven.org/maven2/io/github/rallista/valhalla-mobile/0.6.4/valhalla-mobile-0.6.4.aar               dl\aar\   10 MB
#   docker pull ghcr.io/valhalla/valhalla:3.9.1; docker pull openmaptiles/planetiler-openmaptiles:3.16
#   pip install osmium into $W\venv
