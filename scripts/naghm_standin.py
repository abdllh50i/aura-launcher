# A stand-in for Naghm on the PC (setprop debug.aura.phonelink 10.0.2.2:<port>): sends what is written to naghm_send.txt beside it (one JSON
# message a line, the file then removed). Prints what the unit sends. usage: naghm_standin.py <port>
import json, os, socket, sys, threading, time
PORT = int(sys.argv[1])
SEND = os.path.join(os.path.dirname(os.path.abspath(__file__)), "naghm_send.txt")
clients = []
last_state = [None]
def reader(c):
    f = c.makefile("r", encoding="utf8")
    for line in f:
        line = line.strip()
        if '"ping"' in line:
            c.sendall(b'{"type":"pong"}\n')
        elif line:
            print(time.strftime("%H:%M:%S"), "unit:", line, flush=True)
    clients.remove(c)
def sender():
    while True:
        time.sleep(0.3)
        if os.path.exists(SEND):
            try:
                text = open(SEND, encoding="utf-8-sig").read()
                os.remove(SEND)
            except Exception:
                continue
            for line in text.splitlines():
                if not line.strip():
                    continue
                print(time.strftime("%H:%M:%S"), "send:", line[:120], flush=True)
                if '"state"' in line:
                    last_state[0] = line
                for c in list(clients):
                    try:
                        c.sendall((line + "\n").encode("utf8"))
                    except Exception:
                        pass
threading.Thread(target=sender, daemon=True).start()
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("0.0.0.0", PORT)); s.listen(5)
print("listening", PORT, flush=True)
while True:
    c, a = s.accept()
    print(time.strftime("%H:%M:%S"), "connected", a, flush=True)
    clients.append(c)
    c.sendall((json.dumps({"type": "hello", "app": "Naghm", "version": "stand-in"}) + "\n").encode("utf8"))
    if last_state[0]:
        c.sendall((last_state[0] + "\n").encode("utf8"))
    threading.Thread(target=reader, args=(c,), daemon=True).start()
