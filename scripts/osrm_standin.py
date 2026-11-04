# A stand-in for the public OSRM server on the PC (setprop debug.aura.osrm http://10.0.2.2:<port>): answers any route with a real BRouter answer, as OSRM would
# give it (names on its steps), after a delay. usage: osrm_standin.py <port> <BRouter geojson> <delay seconds>; a file osrm_delay.txt beside it wins.
import json, math, os, sys, time
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn
port = int(sys.argv[1]); trip = sys.argv[2]; delay = float(sys.argv[3])
DELAY_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "osrm_delay.txt")
d = json.load(open(trip, encoding="utf8"))
f = d["features"][0]; coords = [c[:2] for c in f["geometry"]["coordinates"]]
def dist(a, b):
    R = 6371000.0; p1, p2 = math.radians(a[1]), math.radians(b[1])
    dp = p2 - p1; dl = math.radians(b[0] - a[0])
    h = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*R*math.asin(math.sqrt(h))
def bearing(a, b):
    p1, p2 = math.radians(a[1]), math.radians(b[1]); dl = math.radians(b[0]-a[0])
    return math.degrees(math.atan2(math.sin(dl)*math.cos(p2), math.cos(p1)*math.sin(p2)-math.sin(p1)*math.cos(p2)*math.cos(dl))) % 360
# a manoeuvre wherever the line turns more than 40 degrees, at least 300 m after the one before
idx = [0]; along = 0.0; last = 0.0
for i in range(1, len(coords)-1):
    along += dist(coords[i-1], coords[i])
    t = (bearing(coords[i], coords[i+1]) - bearing(coords[i-1], coords[i]) + 540) % 360 - 180
    if abs(t) > 40 and along - last > 300:
        idx.append(i); last = along
idx.append(len(coords)-1)
steps = []
total = 0.0
for k, i in enumerate(idx):
    j = idx[k+1] if k+1 < len(idx) else i
    length = sum(dist(coords[x], coords[x+1]) for x in range(i, j))
    total += length
    if k == 0: typ, mod = "depart", "straight"
    elif k == len(idx)-1: typ, mod = "arrive", ""
    else:
        t = (bearing(coords[i], coords[i+1]) - bearing(coords[i-1], coords[i]) + 540) % 360 - 180
        typ, mod = "turn", ("right" if t > 0 else "left")
    steps.append({"maneuver": {"type": typ, "modifier": mod, "location": coords[i]}, "name": "Stand-in Road %d" % k, "ref": "", "distance": length, "duration": length/15.0})
answer = {"code": "Ok", "routes": [{"geometry": {"type": "LineString", "coordinates": coords}, "distance": total, "duration": total/15.0, "legs": [{"steps": steps}]}]}
body = json.dumps(answer).encode()
class H(BaseHTTPRequestHandler):
    def do_GET(self):
        wait = delay
        try: wait = float(open(DELAY_FILE).read().strip())
        except Exception: pass
        print(time.strftime("%H:%M:%S"), "asked", self.path[:90], "waiting", wait, flush=True)
        time.sleep(wait)
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(body))); self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a): pass
class S(ThreadingMixIn, HTTPServer): daemon_threads = True
print("steps", len(steps), "total m", int(total), "start", coords[0], "end", coords[-1], flush=True)
S(("0.0.0.0", port), H).serve_forever()
