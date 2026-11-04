# Rebuilds the Saudi base tiles, routing tar, glyphs and pack on this PC, in order. Windows PowerShell 5.1, Docker Desktop running. See README.md for every input.
# Scripts live here (public repo, nothing secret); data lives in $W, never in the repo. Each step skips nothing by itself: comment out what is already done.
#   powershell -File run_build.ps1 -W <work folder> -Fonts <folder of IBMPlexSansArabic-*.ttf> [-Docker <docker.exe, when it is not on PATH>] [-Id ksa-2026.11] [-OfmTiles <folder of OpenFreeMap sample tiles, for the Arabic comparison>]
#
#   Step   What                                    In                              Out                                Measured here
#   1      Saudi outline from relation 307584      gcc-states PBF                  polys\ksa.poly (also .\polygon\)   seconds
#   2      Cut the Kingdom out                     gcc-states PBF, ksa.poly        src\ksa.osm.pbf                    12 min (pyosmium, HDD)
#   2b     Arabic road names                       ksa.osm.pbf, the rules          src\ksa-ar.osm.pbf                 1 min; every step below reads this file
#   3      Sea polygons from OSM's coastline       PBF, ksa.geojson, Natural Earth sources\water-polygons-ksa.shp.zip   1.2 min
#   4      Base tiles, then without its poi layer  ksa-ar.osm.pbf, two rectangles  out\base.mbtiles                   2 min + 45 s + 2 min
#   5      Admin database, then routing tar        ksa-ar.osm.pbf                  v\admin-ksa.sqlite, out\route.tar  20 s, 91 s + 1 s
#   6      The probe set, with the names           probe.osm.pbf, the admin        out\route-probe-ar.tar             25 s. out\route-probe.tar stays what the scout release was built with
#   7      Reference answers and checks            the tar, a running service      out\ref-routes\
#   8      Glyphs                                  IBM Plex Sans Arabic            out\glyphs.db                      30 s
#   9      Places: build_all_places.ps1
#   10     Lock file and pack                      all of out\                     .\lock.json, out\pack\             1-2 min
param([Parameter(Mandatory)][string]$W, [Parameter(Mandatory)][string]$Fonts, [string]$Docker, [string]$Id = 'ksa-' + (Get-Date -Format 'yyyy.MM'), [string]$OfmTiles)
$ErrorActionPreference = 'Stop'
$S = $PSScriptRoot
if (-not $Docker) { $Docker = (Get-Command docker -ErrorAction Stop).Source }
$python = 'python'
$venv = "$W\venv\Scripts\python.exe"       # pyosmium, for the cut and the road side
# pinned by digest: a tag can move. The digests are in lock.json too.
$V = 'ghcr.io/valhalla/valhalla:3.9.1@sha256:4fd114eb1d26b8fc2f43bcb3df234c1b63a0bf13c277405e5943d5361dc575de'
$P = 'openmaptiles/planetiler-openmaptiles:3.16@sha256:9c95d3fec85ac28380f25b85e6c887b88c1b1478809e7410cc74b022be28e099'
# z9-14 over this rectangle (also make_water_polygons.py's RECT): every tile that touches the Kingdom's outline, with the sea to its edge and a little on, at +4.4 MB against the old 34.4,16.2,55.7,32.2.
$Rectangle = '32.0,13.9,58.0,36.6'
# z0-8 over this one (make_water_polygons.py's REACH): all that shows when the Kingdom is on the screen at z5 or more, 3.3 MB. Planetiler cuts the sea polygons exactly at --bounds: that was the straight line at 16.2 N and the wall at 55.7 E.
$LowRectangle = '22.5,0,67.5,41'
$LowTop = 8
$FontRegular = "$Fonts\IBMPlexSansArabic-Regular.ttf"
$FontHeavy = "$Fonts\IBMPlexSansArabic-SemiBold.ttf"
$env:TEMP = "$W\tmp"; $env:TMP = "$W\tmp"; $env:PIP_CACHE_DIR = "$W\tmp\pip"

function Valhalla([string]$tmpfs, [string[]]$rest) {
    & $Docker run --rm --memory 9g -e CONCURRENCY=6 -v "${W}:/data" -v "${S}:/scripts:ro" --tmpfs "/work:size=$tmpfs,exec" --entrypoint bash $V @rest
}

# 1. The outline: relation 307584, 0.03 degrees of buffer so border posts and the causeway's Saudi end stay in.
& $venv -I "$S\boundary_members.py" "$W\src\gcc-states-latest.osm.pbf" 307584 "$W\polys\rel307584-members.json"
& $Docker run --rm -v "${W}:/data" -v "${S}:/scripts:ro" --entrypoint python3 $V -I /scripts/boundary_poly.py /data/polys/rel307584-members.json /data/polys ksa 0.03 0.005

# 2. The cuts.
& $venv -I "$S\cut_pbf.py" "$W\src\gcc-states-latest.osm.pbf" "$W\src\ksa.osm.pbf" --poly "$W\polys\ksa.poly"
& $venv -I "$S\cut_pbf.py" "$W\src\ksa.osm.pbf" "$W\src\probe.osm.pbf" --bbox 49.4,26.0,50.4,27.3

# 2b. Arabic on the roads with a Latin name: everything below reads this file. A new extract: `arabic_names.py learn` first, then read the words it marks unread.
& $venv -I "$S\arabic_names.py" apply "$W\src\ksa.osm.pbf" "$W\src\ksa-ar.osm.pbf" --report "$W\out\arabic-names-report.json" --sample "$S\tests\arabic-names-sample.tsv"
& $venv -I "$S\arabic_names.py" apply "$W\src\probe.osm.pbf" "$W\src\probe-ar.osm.pbf"

# 3. The sea. Planetiler's usual file (osmdata.openstreetmap.de) could not be reached from here on 2026-11-04.
& $venv -I "$S\coast_ways.py" "$W\src\gcc-states-latest.osm.pbf" "$W\polys\gcc-coast.json"
& $python -I "$S\make_empty_shapefile.py" "$W\sources\lake_centerline.empty.shp.zip"
& $Docker run --rm --memory 7g -v "${W}:/data" -v "${S}:/scripts:ro" --tmpfs /work:size=4g -e TMPDIR=/work --entrypoint python3 $V -I /scripts/make_water_polygons.py /data/polys/gcc-coast.json /data/polys/ksa.geojson /data/sources/natural_earth_vector.sqlite.zip /data/sources/water-polygons-ksa.shp.zip

# 4. Base tiles over rectangles, not the outline: build_base.sh says why. Twice: merge_low_zoom.py says why. (The low build goes one zoom past what is kept: Planetiler simplifies its last zoom less, and a z8 as last zoom had tiles of 140 KB.)
& $Docker run --rm --memory 11g -e 'JAVA_TOOL_OPTIONS=-Xmx5g -Djava.io.tmpdir=/work/tmp' -e THREADS=6 -v "${W}:/data" -v "${S}:/scripts:ro" --tmpfs '/work:size=5g,exec' --entrypoint sh $P /scripts/build_base.sh /data/src/ksa-ar.osm.pbf $Rectangle /data/sources/water-polygons-ksa.shp.zip /data/out/base-new.mbtiles
& $Docker run --rm --memory 8g -e 'JAVA_TOOL_OPTIONS=-Xmx3g -Djava.io.tmpdir=/work/tmp' -e THREADS=6 -v "${W}:/data" -v "${S}:/scripts:ro" --tmpfs '/work:size=3g,exec' --entrypoint sh $P /scripts/build_base.sh /data/src/ksa-ar.osm.pbf $LowRectangle /data/sources/water-polygons-ksa.shp.zip /data/out/base-low.mbtiles "--maxzoom=$($LowTop + 1)" "--render_maxzoom=$($LowTop + 1)"
& $python -I "$S\merge_low_zoom.py" "$W\out\base-new.mbtiles" "$W\out\base-low.mbtiles" $LowTop "$W\out\base-merged.mbtiles"

# 4b. Places come from places.mbtiles: OpenMapTiles' poi layer goes, or they would show twice on the map.
& $python -I "$S\strip_tiles.py" "$W\out\base-merged.mbtiles" --out "$W\out\base.mbtiles" --drop-layers poi
# 5. Routing. The admin database comes first: it holds the Kingdom's boundary and default_language=ar.
Valhalla '1g' @('/scripts/build_admin.sh', '/data/src/ksa-ar.osm.pbf', '/data/v/admin-ksa.sqlite')
Valhalla '6g' @('/scripts/build_set.sh', '/data/src/ksa-ar.osm.pbf', '/data/v/admin-ksa.sqlite', '/data/out/route.tar')

# 6. The probe set, with that same admin database. (The scout release's out\route-probe.tar is not built again: its answers are the references of the probe in app/src/main/assets/probe.)
Valhalla '2g' @('/scripts/build_set.sh', '/data/src/probe-ar.osm.pbf', '/data/v/admin-ksa.sqlite', '/data/out/route-probe-ar.tar')

# 7. Reference answers, with the request in trips.py. The service stays up until it is removed: docker rm -f amri-serve.
& $Docker run -d --name amri-serve --memory 2g -p 127.0.0.1:8002:8002 -v "${W}:/data:ro" -v "${S}:/scripts:ro" --tmpfs /work:size=500m --entrypoint bash $V /scripts/serve_ram.sh /data/out/route.tar 3 --service-limits-max-exclude-polygons-length 2000000
Start-Sleep -Seconds 60
& $python -I "$S\ref_routes.py" http://127.0.0.1:8002 "$W\out\ref-routes"
& $python -I "$S\route_fields.py" "$W\out\ref-routes" "$W\out\ref-routes\fields.json"
& $python -I "$S\api_checks.py" http://127.0.0.1:8002 "$W\out\ref-routes" "$W\out\ref-routes\api-checks.json"
& $python -I "$S\compare_osrm.py" "$W\out\ref-routes" "$W\out\ref-routes\compare-osrm.tsv"
& $python -I "$S\names_check.py" "$W\places\osm-road.jsonl" http://127.0.0.1:8002 '34.4,16.2,55.7,32.2' "$W\logs\names-full.json" 60
& $python -I "$S\make_requests.py" "$W\harness\req"
& $Docker exec -e PORT=8003 amri-serve bash /scripts/mem_peak.sh /work/route.tar /data/harness/req /data/out/ref-routes/mem 67108864 dammam-jeddah riyadh-jeddah abha-riyadh dammam-riyadh jubail-dammam
& $Docker exec amri-serve bash /scripts/tar_start_check.sh /data/out/route-probe-ar.tar /data/harness/req/jubail-dammam.json
$ofm = @(); if ($OfmTiles) { $ofm = @('--ofm', $OfmTiles) }
& $python -I "$S\check_tiles.py" "$W\out\base.mbtiles" --style "$S\..\..\app\src\main\java\com\abdllh\aura\nav\MapStyle.kt" --poi "$S\..\..\app\src\main\java\com\abdllh\aura\nav\Poi.kt" @ofm --out "$W\logs\check-tiles.txt"
& $Docker run --rm --memory 3g -v "${W}:/data:ro" -v "${S}:/scripts:ro" --entrypoint python3 $V -I /scripts/check_water_tiles.py /data/out/base.mbtiles

# 9. Places, from the same PBF: road rows hold the new Arabic, and zones and gates what osm_roadside.py says of them.
& $python -I "$S\osm_extract.py" --pbf "$W\src\ksa-ar.osm.pbf" --out "$W\places"
& $venv -I "$S\osm_roadside.py" "$W\src\ksa-ar.osm.pbf" "$W\places\roadside.json"
& "$S\build_all_places.ps1" -Work $W -OverturePlaces "$W\src\overture\places-gcc-rows.parquet"

# 8. Glyphs from the UI's font (after the tiles: --labels names what their labels need and the font lacks).
& $python -I "$S\make_glyphs.py" $FontRegular $FontHeavy "$W\out\glyphs.db" --report "$W\out\glyphs-report.json" --labels "$W\out\base.mbtiles,$W\out\places.mbtiles"

# 10. What it was built from, then the pack. make_pack.py stops when something is missing.
& $python -I "$S\make_lock.py" --work $W --docker $Docker --font $FontRegular --font $FontHeavy --overture-places "$W\src\overture\places-gcc-rows.parquet" --out "$S\lock.json"
& $python -I "$S\make_pack.py" --out "$W\out\pack" --id $Id --lock "$S\lock.json" --min-app 2000001 "base.mbtiles=$W\out\base.mbtiles" "route.tar=$W\out\route.tar" "places.db=$W\out\places.db" "places.mbtiles=$W\out\places.mbtiles" "glyphs.db=$W\out\glyphs.db"
& $python -I "$S\make_pack.py" --verify "$W\out\pack"
