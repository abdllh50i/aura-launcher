#!/bin/bash
# The unit's Valhalla config, written by the tool of the same version as the library: every key valhalla's services read is
# present (a key missing from a hand-written file stops createActor), with the small caches of maps.md section 7.4.
#   device_config.sh <tile extract path> [max_cache_bytes] [more valhalla_build_config args] > device-config.json
# scan_tar false: the tar must start with index.bin (valhalla_build_extract writes it there), and only that file is read to
# know where the tiles are (0 kB of the tar in memory after the start). A tar without the index still starts, scanned, whatever this says:
# measured in tar_start_check.sh.
set -euo pipefail
TAR="$1"; CACHE="${2:-67108864}"; shift; shift || true
valhalla_build_config \
  --mjolnir-tile-extract "$TAR" \
  --mjolnir-tile-dir /nonexistent/tiles \
  --mjolnir-traffic-extract /nonexistent/traffic.tar \
  --mjolnir-admin /nonexistent/admin.sqlite \
  --mjolnir-timezone /nonexistent/tz.sqlite \
  --mjolnir-landmarks /nonexistent/landmarks.sqlite \
  --mjolnir-max-cache-size "$CACHE" \
  --mjolnir-use-lru-mem-cache true \
  --mjolnir-lru-mem-cache-hard-control true \
  --mjolnir-global-synchronized-cache false \
  --mjolnir-max-concurrent-reader-users 1 \
  --mjolnir-data-processing-scan-tar false \
  --thor-max-reserved-labels-count-astar 100000 \
  --thor-max-reserved-labels-count-bidir-astar 100000 \
  --thor-max-reserved-labels-count-dijkstras 200000 \
  --thor-max-reserved-labels-count-bidir-dijkstras 100000 \
  --thor-clear-reserved-memory true \
  --service-limits-max-alternates 2 "$@"
