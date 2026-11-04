#!/bin/bash
# Builds Valhalla routing tiles for one PBF, driving only, into one tar with an index (a start that does not scan it).
# Runs inside ghcr.io/valhalla/valhalla:3.9.1, with the data on a bind mount:
#   build_route.sh <in.osm.pbf> <work dir> <out.tar> [extra valhalla_build_config args...]
# The work dir holds admin.sqlite, the loose tiles and the build's temporary files; it can be removed afterwards.
# Timezones are not built: only time-dependent routing needs them, and the unit does not ask for it.
set -euo pipefail

PBF="$1"; WORK="$2"; TAR="$3"; shift 3
mkdir -p "$WORK/tiles"
CFG="$WORK/valhalla.json"

valhalla_build_config \
  --mjolnir-tile-dir "$WORK/tiles" \
  --mjolnir-tile-extract "$TAR" \
  --mjolnir-admin "$WORK/admin.sqlite" \
  --mjolnir-timezone "$WORK/no-timezones.sqlite" \
  --mjolnir-landmarks "$WORK/no-landmarks.sqlite" \
  --mjolnir-include-bicycle false \
  --mjolnir-include-pedestrian false \
  --mjolnir-concurrency "${CONCURRENCY:-8}" \
  "$@" > "$CFG"

stamp() { date '+%H:%M:%S'; }
peak() { for p in "$@"; do :; done; awk '/^(MemTotal|MemAvailable)/{printf "%s %d MB  ", $1, $2/1024}END{print ""}' /proc/meminfo; }

echo "$(stamp) valhalla_build_admins"; peak
time valhalla_build_admins -c "$CFG" "$PBF" 2>&1 | tail -n 5
echo "$(stamp) valhalla_build_tiles"; peak
time valhalla_build_tiles -c "$CFG" "$PBF" 2>&1 | grep -vE 'Finished with [0-9]+ ways|^\s*$' | tail -n 40
echo "$(stamp) valhalla_build_extract"; peak
time valhalla_build_extract -c "$CFG" -v -O 2>&1 | tail -n 5
echo "$(stamp) done"; peak
ls -l "$TAR"
du -sm "$WORK/tiles" "$WORK/admin.sqlite" 2>/dev/null || true
