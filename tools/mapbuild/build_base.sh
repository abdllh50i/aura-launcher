#!/bin/sh
# Base map tiles: Planetiler + OpenMapTiles schema, zoom 0-14, Arabic and English names.
# Runs inside openmaptiles/planetiler-openmaptiles:3.16 with --entrypoint sh; /data is the bind mount on D:, /work a tmpfs:
#   build_base.sh <in.osm.pbf> <outline.poly> <water polygons .zip> <out.mbtiles> [more planetiler args...]
# D: is a spinning disk behind Docker Desktop's file share, so everything random-access stays in memory: node locations
# and multipolygons in the heap, the unpacked Natural Earth database and the SQLite output on the tmpfs. The sorted
# features, large and sequential, stay on D:.
set -eu

PBF="$1"; POLY="$2"; WATER="$3"; OUT="$4"; shift 4
mkdir -p /data/tmp
exec_java() {
  java -cp @/app/jib-classpath-file org.openmaptiles.OpenMapTilesMain "$@"
}
exec_java \
  --osm_path="$PBF" \
  --polygon="$POLY" \
  --water_polygons_path="$WATER" \
  --lake_centerlines_path=/data/sources/lake_centerline.empty.shp.zip \
  --natural_earth_path=/data/sources/natural_earth_vector.sqlite.zip \
  --languages=ar,en \
  --storage=ram \
  --nodemap_storage=ram \
  --multipolygon_geometry_storage=ram \
  --tmpdir=/work/tmp \
  --temp_features=/data/tmp/feature.db \
  --output=/work/base.mbtiles \
  --threads="${THREADS:-8}" \
  --force \
  "$@"
ls -l /work/base.mbtiles
cp /work/base.mbtiles "$OUT"
ls -l "$OUT"
