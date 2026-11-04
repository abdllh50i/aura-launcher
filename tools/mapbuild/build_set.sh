#!/bin/bash
# A routing set from one PBF: the whole Kingdom, or the small set for the first test on the unit, by the same method and
# with the Kingdom's admin database (a boundary cut by a rectangle would leave no country: drive_on_right false, no
# default language, roundabout exits counted the wrong way). Runs in ghcr.io/valhalla/valhalla:3.9.1, tmpfs on /work:
#   build_set.sh <in.osm.pbf> <admin.sqlite> <out.tar> [extra valhalla_build_config args...]
set -euo pipefail
PBF="$1"; ADMIN_IN="$2"; OUT="$3"; shift 3
mkdir -p /work/v
cp "$ADMIN_IN" /work/admin.sqlite
ADMIN=/work/admin.sqlite bash "$(dirname "$0")/build_route.sh" "$PBF" /work/v /work/out.tar "$@"
cp /work/out.tar "$OUT"
gzip -9 -c /work/out.tar > "$OUT.gz"
ls -l "$OUT" "$OUT.gz"
sha256sum "$OUT" "$OUT.gz"
