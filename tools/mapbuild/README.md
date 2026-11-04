# tools/mapbuild: the offline map pack

Makes the Saudi map pack of AMRI OS 2.0 from open data on one PC. Public on purpose: this also meets the share-alike of the ODbL. No secrets here, and no data: data lives in a work folder (`-W`), outside the repo.

`run_build.ps1` runs it all in order, `build_all_places.ps1` the places part. Each script says at its top how to run it. `lock.json` says exactly what the last build was made from; `python -I make_lock.py ... --check` lists what differs now.

## Inputs, all of them

| What | From | Into the work folder | Size |
|---|---|---|---|
| OpenStreetMap, GCC extract | https://download.geofabrik.de/asia/gcc-states-latest.osm.pbf (and its .md5) | `src\` | 257 MB |
| Natural Earth | https://dev.maptiler.download/geodata/omt/natural_earth_vector.sqlite.zip (Planetiler's `--download`) | `sources\` | 433 MB |
| Overture places rows | `fetch_overture.py --places`: row groups of the current release, anonymous S3, release from https://stac.overturemaps.org/ | any path, given to `build_all_places.ps1 -OverturePlaces` | 83 MB |
| Overture divisions | `fetch_overture.py` | `src\overture\` | 6 MB |
| The owner's additions | `additions.json` of this repo's branch `mapdata`, read only | `places\` | 16 KB |
| Valhalla's library | https://repo1.maven.org/maven2/io/github/rallista/valhalla-mobile/0.6.4/valhalla-mobile-0.6.4.aar | `dl\aar\` | 10 MB |
| Docker images, pinned by digest in `run_build.ps1` | `ghcr.io/valhalla/valhalla:3.9.1`, `openmaptiles/planetiler-openmaptiles:3.16` | Docker's disk | 229 + 184 MB |
| Python packages | `requirements.txt` (`osmium` in a venv at `venv\`; the rest in the Python that runs the scripts) | | |
| The app's font | `app/src/main/assets/fonts/ReadexPro-VF.ttf` in this repo | | |

Made here, not fetched: the sea (`make_water_polygons.py`, from the extract's coastline and Natural Earth: the usual `water-polygons-split-3857.zip` could not be reached from this PC), an empty lake shapefile (`make_empty_shapefile.py`), the outline (`boundary_poly.py`, `polygon\ksa.poly`).

Only to look at results, with the design kit's headless Chrome (a folder given with `--kit`, not in this repo, with a Chrome path of its own; its pages load MapLibre GL JS from unpkg.com): `plate\`, `adversary\plate_style.mjs`, `adversary\tile_time.mjs`.

## What it makes (`out\` of the work folder)

| File | What | Read by |
|---|---|---|
| `base.mbtiles` | Base map z0-14, OpenMapTiles schema without its `poi` layer (`strip_tiles.py`: places come from `places.mbtiles`), ar and en, over the rectangle 34.4,16.2,55.7,32.2 | `TileServer`; `polygon\ksa.poly` says what is inside the Kingdom, for the app's online-or-pack choice |
| `route.tar` | Valhalla 3.9.1 tiles, driving only, `index.bin` first | The JNI library with `device-config.json` |
| `places.db` | Search, spots, gates, zones: `SEARCH_SPEC.md` | The app's search |
| `places.mbtiles` | Places on the map z12-14: layer `places`, fields name, name:en, kind (the colour group, `cat.icon`), cat, rank, id | `TileServer`'s `/p/` |
| `glyphs.db` | Map fonts as glyph PBFs: stacks `Readex Pro AMRI Regular`, `Readex Pro AMRI Bold` (`make_glyphs.py --family`, `make_pack.py --family` and `MapStyle.PACK_FONT` must say the same name) | `TileServer`'s `/f/` |
| `pack\` | All of it in gzip chunks of 64 MiB, `manifest.json`, the notices | The unit's pack manager, or a USB stick |

## Checks

`tests\README.md` for routes, `tests\` for search and data, `adversary\README.md` for the pack as a whole. `make_pack.py` refuses a pack without notices, glyphs or the sea's rows, with a tar that does not start with its index, or with a file that fails SQLite's check.
