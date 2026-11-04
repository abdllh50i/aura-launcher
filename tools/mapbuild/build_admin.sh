#!/bin/bash
# Valhalla's admin database of the Kingdom, on the tmpfs, then copied to D:.   build_admin.sh <in.osm.pbf> <out admin.sqlite>
set -euo pipefail
ONLY_ADMIN=1 ADMIN=/work/admin.sqlite bash "$(dirname "$0")/build_route.sh" "$1" /work/v /work/none.tar
cp /work/admin.sqlite "$2"
ls -l "$2"
