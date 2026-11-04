#!/bin/sh
# Base map tiles: Planetiler + OpenMapTiles schema, zoom 0-14, Arabic and English names.
# Runs inside openmaptiles/planetiler-openmaptiles:3.16 with --entrypoint sh; /data is the bind mount on D:, /work a tmpfs
# mounted with exec (sqlite-jdbc unpacks its native library into the Java temp dir):
#   build_base.sh <in.osm.pbf> <outline.poly> <water polygons .zip> <out.mbtiles> [more planetiler args...]
# D: is a spinning disk behind Docker Desktop's file share, shared with the emulators' disks: under load it gave 1.3 MB/s,
# and Planetiler reading the Natural Earth zip in small pieces from there did 1 MB/s unpacked (20 minutes for that one
# file). So the inputs are copied to the tmpfs in one go and everything else stays in memory: node locations and
# multipolygons in the heap; features, sort files and the SQLite output on the tmpfs. Inputs are dropped once read.
# The lake centre lines are an empty stand-in (make_empty_shapefile.py): the Kingdom has no lake they would label.
# The mountain peaks are left out: the style does not read them.
set -eu

PBF="$1"; POLY="$2"; WATER="$3"; OUT="$4"; shift 4
mkdir -p /work/tmp /work/in
echo "copying the inputs $(date +%T)"
cp "$PBF" /work/in/in.osm.pbf
cp "$WATER" /work/in/water.shp.zip
cp /data/sources/natural_earth_vector.sqlite.zip /work/in/natural_earth_vector.sqlite.zip
cp /data/sources/lake_centerline.empty.shp.zip /work/in/lake.shp.zip
echo "inputs copied $(date +%T)"
java -cp @/app/jib-classpath-file org.openmaptiles.OpenMapTilesMain \
  --osm_path=/work/in/in.osm.pbf \
  --polygon="$POLY" \
  --water_polygons_path=/work/in/water.shp.zip \
  --lake_centerlines_path=/work/in/lake.shp.zip \
  --natural_earth_path=/work/in/natural_earth_vector.sqlite.zip \
  --free_natural_earth_after_read=true \
  --free_water_polygons_after_read=true \
  --languages=ar,en \
  --exclude_layers=mountain_peak \
  --tile_weights=/data/tile_weights.tsv.gz \
  --output_layerstats=true \
  --storage=ram \
  --nodemap_storage=ram \
  --multipolygon_geometry_storage=ram \
  --tmpdir=/work/tmp \
  --temp_features=/work/tmp/feature.db \
  --output=/work/base.mbtiles \
  --threads="${THREADS:-8}" \
  --force \
  "$@"
ls -l /work/base.mbtiles
cp /work/base.mbtiles "$OUT"
cp /work/base.mbtiles.layerstats.tsv.gz "$OUT.layerstats.tsv.gz" 2>/dev/null || true
ls -l "$OUT"
echo "finished $(date +%T)"
