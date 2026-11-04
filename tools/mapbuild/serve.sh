#!/bin/bash
# Runs valhalla_service on one tar, inside ghcr.io/valhalla/valhalla:3.9.1:   serve.sh <tar> [threads] [extra config args...]
set -euo pipefail
TAR="$1"; THREADS="${2:-2}"; shift 2 || true
valhalla_build_config \
  --mjolnir-tile-extract "$TAR" \
  --mjolnir-tile-dir /nonexistent/tiles \
  --mjolnir-traffic-extract /nonexistent/traffic.tar \
  --mjolnir-admin /nonexistent/admin.sqlite \
  --mjolnir-timezone /nonexistent/tz.sqlite \
  --mjolnir-landmarks /nonexistent/landmarks.sqlite \
  --mjolnir-logging-type std_out \
  "$@" > /tmp/valhalla.json
exec valhalla_service /tmp/valhalla.json "$THREADS"
