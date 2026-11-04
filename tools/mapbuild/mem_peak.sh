#!/bin/bash
# The memory of valhalla_service for long routes under the unit's small settings, inside ghcr.io/valhalla/valhalla:3.9.1:
#   mem_peak.sh <tar> <requests dir> <out dir> <cache bytes> <trip> [<trip> ...]
# One worker, a new process per trip so each peak is that trip's own. Sampled from /proc 50 times a second while the route
# is asked for: the anonymous part (the labels, the JSON, the tile cache) and the file part (the tar's pages, which the
# kernel can drop at will). The tar is on the tmpfs, so it counts as shared memory here and not as file pages.
set -euo pipefail
TAR="$1"; REQ="$2"; OUT="$3"; CACHE="$4"; shift 4
mkdir -p "$OUT"
HERE="$(dirname "$0")"
PORT="${PORT:-8002}"; bash "$HERE/device_config.sh" "$TAR" "$CACHE" --httpd-service-listen "tcp://*:$PORT" --httpd-service-loopback "ipc:///tmp/loop$PORT" --httpd-service-interrupt "ipc:///tmp/int$PORT" > /tmp/device.json
python3 - "$REQ" "$OUT" "$CACHE" "$PORT" "$@" <<'PY'
import json, os, subprocess, sys, time, urllib.request, threading

req_dir, out_dir, cache, port, trips = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5:]

def status(pid):
    d = {}
    with open(f"/proc/{pid}/status") as f:
        for ln in f:
            k, _, v = ln.partition(":")
            if k in ("VmHWM", "VmRSS", "RssAnon", "RssFile", "RssShmem", "VmPeak", "VmSize"):
                d[k] = int(v.split()[0]) // 1024
    return d

rows = []
for trip in trips:
    p = subprocess.Popen(["valhalla_service", "/tmp/device.json", "1"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(100):
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/status", timeout=1).read()
            break
        except Exception:
            time.sleep(0.1)
    start = status(p.pid)
    peak = {}
    stop = threading.Event()
    def watch():
        while not stop.is_set():
            s = status(p.pid)
            for k, v in s.items():
                peak[k] = max(peak.get(k, 0), v)
            time.sleep(0.02)
    t = threading.Thread(target=watch); t.start()
    body = open(os.path.join(req_dir, trip + ".json"), "rb").read()
    times = []
    size = 0
    for _ in range(2):
        t0 = time.perf_counter()
        r = urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{port}/route", data=body, headers={"Content-Type": "application/json"}), timeout=280)
        raw = r.read()
        times.append(round((time.perf_counter() - t0) * 1000))
        size = len(raw)
    stop.set(); t.join()
    end = status(p.pid)
    p.terminate(); p.wait()
    doc = json.loads(raw)
    row = {"trip": trip, "cache_bytes": int(cache), "ms_first": times[0], "ms_second": times[1], "bytes": size, "routes": len(doc.get("routes", [])), "at_start_MB": start, "peak_MB": peak, "end_MB": end}
    rows.append(row)
    print(json.dumps(row), flush=True)
with open(os.path.join(out_dir, f"mem-cache{int(cache) // 1048576}.json"), "w") as f:
    json.dump(rows, f, indent=1)
PY
