#!/bin/bash
# What does a tar need for a start without scanning it? Inside ghcr.io/valhalla/valhalla:3.9.1:   tar_start_check.sh <tar> <request.json>
# Four starts of valhalla_service: the tar as built (index.bin first) and the same tar without index.bin, each with
# mjolnir.data_processing.scan_tar false and true. For each: what it logs about the extract, the tar's pages in memory after
# the start, and whether a route comes back.
set -euo pipefail
TAR="$1"; REQ="$2"; PORT=8004
HERE="$(dirname "$0")"
mkdir -p /work/startcheck
cp "$TAR" /work/startcheck/with-index.tar
python3 - /work/startcheck/with-index.tar /work/startcheck/no-index.tar <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
with open(src, "rb") as f, open(dst, "wb") as out:
    h = f.read(512)
    name = h[:100].rstrip(b"\0").decode()
    size = int(h[124:136].rstrip(b"\0 ") or b"0", 8)
    assert name == "index.bin", name
    f.seek(512 + ((size + 511) // 512) * 512)
    while True:
        b = f.read(1 << 20)
        if not b:
            break
        out.write(b)
print("first entry was", name, size, "bytes; removed")
PY
ls -l /work/startcheck
for tar in with-index no-index; do
  for scan in false true; do
    bash "$HERE/device_config.sh" "/work/startcheck/$tar.tar" 67108864 --mjolnir-data-processing-scan-tar "$scan" \
      --httpd-service-listen "tcp://*:$PORT" --httpd-service-loopback "ipc:///tmp/l$PORT" --httpd-service-interrupt "ipc:///tmp/i$PORT" > /tmp/s.json
    t0=$(date +%s%N)
    valhalla_service /tmp/s.json 1 > /tmp/s.log 2>&1 &
    pid=$!
    for i in $(seq 1 100); do
      if python3 -c "import urllib.request as u; u.urlopen('http://127.0.0.1:$PORT/status', timeout=1).read()" 2>/dev/null; then break; fi
      sleep 0.05
    done
    t1=$(date +%s%N)
    shm=$(awk '/RssShmem/{print $2}' /proc/$pid/status)
    code=$(python3 - "$PORT" "$REQ" <<'PY'
import json, sys, urllib.error, urllib.request as u
try:
    r = u.urlopen(u.Request(f"http://127.0.0.1:{sys.argv[1]}/route", data=open(sys.argv[2], "rb").read(), headers={"Content-Type": "application/json"}), timeout=60)
    print(r.status, json.loads(r.read())["routes"][0]["distance"])
except urllib.error.HTTPError as e:
    print(e.code, e.read()[:120])
PY
)
    kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true
    echo "$tar scan_tar=$scan: up after $(( (t1-t0)/1000000 )) ms; tar pages in memory ${shm} kB; route: $code"
    sed 's/\x1b\[[0-9;]*m//g' /tmp/s.log | grep -iE 'extract|tile count|tar' | grep -v Traffic | cut -c31-160 | sort | uniq -c | head -5
    sleep 0.3
  done
done
rm -rf /work/startcheck
