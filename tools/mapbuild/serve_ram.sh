#!/bin/bash
# valhalla_service on a copy of the tar in memory (tmpfs on /work): times then say what the CPU does, not what the
# data disk and Docker's file share do.   serve_ram.sh <tar> [threads] [extra config args...]
set -euo pipefail
TAR="$1"; shift
cp "$TAR" /work/route.tar
exec bash "$(dirname "$0")/serve.sh" /work/route.tar "$@"
