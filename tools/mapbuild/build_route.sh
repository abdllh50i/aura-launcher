#!/bin/bash
# Builds Valhalla routing tiles for one PBF, driving only, into one tar with an index (a start that does not scan it).
# Runs inside ghcr.io/valhalla/valhalla:3.9.1, with the data on a bind mount:
#   build_route.sh <in.osm.pbf> <work dir> <out.tar> [extra valhalla_build_config args...]
# The work dir holds the loose tiles and the build's temporary files; it can be removed afterwards. Keep it on a tmpfs:
# the data disk is a spinning one behind Docker's file share, and the build reads its temporary files at random.
# ADMIN=<file>: admin.sqlite. When it exists it is used as it is, which is how a cut-out set (the probe) gets the
# Kingdom's real boundary and its default_language=ar: a boundary cut by the rectangle would give it neither.
# ONLY_ADMIN=1 stops after the admin database.
# Timezones are not built: only time-dependent routing needs them, and the unit does not ask for it.
set -euo pipefail

PBF="$1"; WORK="$2"; TAR="$3"; shift 3
ADMIN="${ADMIN:-$WORK/admin.sqlite}"
mkdir -p "$WORK/tiles" "$(dirname "$TAR")" "$(dirname "$ADMIN")"
CFG="$WORK/valhalla.json"

valhalla_build_config \
  --mjolnir-tile-dir "$WORK/tiles" \
  --mjolnir-tile-extract "$TAR" \
  --mjolnir-traffic-extract "$WORK/no-traffic.tar" \
  --mjolnir-admin "$ADMIN" \
  --mjolnir-timezone "$WORK/no-timezones.sqlite" \
  --mjolnir-landmarks "$WORK/no-landmarks.sqlite" \
  --mjolnir-include-bicycle false \
  --mjolnir-include-pedestrian false \
  --mjolnir-concurrency "${CONCURRENCY:-8}" \
  "$@" > "$CFG"

stamp() { date '+%H:%M:%S'; }
mem() { awk '/^(MemTotal|MemAvailable)/{printf "%s %d MB  ", $1, $2/1024}END{print ""}' /proc/meminfo; }

if [ -s "$ADMIN" ]; then
  echo "$(stamp) admin database kept: $ADMIN"
else
  echo "$(stamp) valhalla_build_admins"; mem
  time valhalla_build_admins -c "$CFG" "$PBF" 2>&1 | grep -v 'NOT NULL constraint' | tail -n 5
  ls -l "$ADMIN"
fi
if [ -n "${ONLY_ADMIN:-}" ]; then exit 0; fi
echo "$(stamp) valhalla_build_tiles"; mem
time valhalla_build_tiles -c "$CFG" "$PBF" 2>&1 | grep -E 'INFO|WARN|ERROR|TIMING' | grep -vE 'Finished with [0-9]+ ways|Cleaning|sqlite3_step' | tail -n 60
echo "$(stamp) valhalla_build_extract"; mem
time valhalla_build_extract -c "$CFG" -v -O 2>&1 | tail -n 5
echo "$(stamp) done"; mem
ls -l "$TAR"
du -sm "$WORK/tiles" 2>/dev/null || true
