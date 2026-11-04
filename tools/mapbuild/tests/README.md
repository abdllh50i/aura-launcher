# Route tests

Run against `valhalla_service` on `out/route.tar` and the public OSRM demo (never more than one request a second). Answers stay in the data folder, not here.

```powershell
$W = 'D:\k2501-work\mapbuild'; $O = "$W\out\route-tests"; $U = 'http://127.0.0.1:8002'; $T = 'tools\mapbuild\tests'
docker run -d --name amri-serve --memory 2g -p 127.0.0.1:8002:8002 -v "${W}:/data:ro" -v "$PWD\tools\mapbuild:/scripts:ro" --tmpfs /work:size=500m --entrypoint bash ghcr.io/valhalla/valhalla:3.9.1 /scripts/serve_ram.sh /data/out/route.tar 3
& $W\venv\Scripts\python.exe -I $T\find_sites.py $W\src\probe.osm.pbf $O\sites.json        # real U-turn gaps and frontage roads
& $W\venv\Scripts\python.exe -I $T\barrier_nodes.py $W\src\ksa.osm.pbf $O\barriers.json     # gates and checkpoints on roads
python -I $T\run_route_trips.py $U $O                                                       # the 56 trips of route_trips.py, both engines
python -I $T\route_variants.py $U $O 'old140={\"top_speed\":140,\"use_distance\":0}'       # var-default is the request in ..\trips.py
python -I $T\route_report.py $O var-default                                                 # flags, overlap, loops, U-turns
python -I $T\route_probes.py $U $O all                                                      # OSRM's line in Valhalla's own cost; U-turn gaps; service roads
python -I $T\route_sweep.py $U $O 'dist50={\"top_speed\":160,\"use_distance\":0.5}'         # options over trips and gaps
python -I $T\route_eta.py $O $W\out\places.db var-default                                   # ETA scale against the yardstick
& $W\venv\Scripts\python.exe -I $T\check_rings.py $W\src\ksa.osm.pbf $O valhalla osrm       # exit numbers against OSM's own ways
& $W\venv\Scripts\python.exe -I $T\haram_check.py $W\src\ksa.osm.pbf $O var-default osrm    # km inside Makkah's and Madinah's zones
python -I $T\wording\run_wording.py app\src\main\java\com\abdllh\aura\nav $O $O\wording valhalla var-default   # compiles the app's real Instructions.kt and runs it on the answers
```

The same trips through the app's real service on an emulator, the tar on the device (`docs/DEV.md`, Valhalla in its own process): `python -I $T\app_route_check.py <adb> <serial> $W\out\ref-routes <out.json> [--rounds 5]`.

Plates: `python -I serve_tiles.py --port 8799 --root plate base=$W\out\base.mbtiles`, then `tests\make_plates.py` or `tests\plate_sites.py` for the jobs and `plate\plate_routes.mjs` to render them
(needs the design kit's headless Chrome; it loads MapLibre from unpkg.com and glyphs from tiles.openfreemap.org). Valhalla is blue, OSRM red dashes.

No ground truth is here: nobody has logged a real drive. OSRM's time is a yardstick, not the road.

# Search and data tests

```powershell
python -I $T\run_vectors.py $W\out\places.db                                                      # the text functions and MATCH strings
python -I ..\search_ref.py $W\out\places.db --test $T\search_tests.json --golden $W\out\search-golden.json   # the builder's own set: a regression set, tuned against
python -I $T\eval_search_set.py --db $W\out\places.db --set $T\search-set.json --out <file>        # the tester's 200 queries. Frozen, but the 2026-11-04 fixes were tuned against it too, so it needs a new set to measure again
python -I $T\self_retrieval.py $W\out\places.db --n 1000                                            # random rows searched by their own name: nobody tuned for it
python -I $T\junk_sample.py --db ... --base ... --areas ... --out <folder>                           # 200 random POIs for a person to judge; junk_tally.py adds up the judgement
python -I $T\junk_recheck.py --sample ... --judged $T\junk-judged-200.json --db $W\out\places.db   # what became of the rows judged junk once
& $W\venv\Scripts\python.exe -I $T\spot_check.py --db ... --pbf $W\src\probe.osm.pbf ...           # cameras, signals and bumps against the OSM file and the routes
python -I $T\poi_density.py --tiles $W\out\places.mbtiles --db $W\out\places.db --out <file>      # densest POI tiles
python -I $T\zone_gate_check.py $W\out\places.db $U $O\var-default <out.json>                      # the Haram ring in exclude_polygons; gates on the route to a guarded camp
python -I $T\glyph_plate.py $W\out\glyphs.db <out.png>                                              # labels drawn from glyphs.db with no renderer, to see the letters join
```

Pack: `python -I $T\test_pack.py $W\out ..\lock.json $W\out\pack` tries make_pack.py's refusals and a damaged chunk.
