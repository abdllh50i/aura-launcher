"""Test double for adbd, for exercising aura-install.py without a real unit.

  python fake_adbd.py --adb PATH_TO_ADB --serial emulator-5570 --port 15555 [--model "Android SDK built for x86_64"]

It speaks the ADB wire protocol on a TCP port, the way adbd does on a head unit (CNXN banner, OPEN/OKAY/WRTE/CLSE streams, the
`exec:`/`shell:` and `sync:` services) and runs the commands on the Android emulator through the real adb program, so the shell,
toybox and block devices the scripts see are real. It is deliberately strict about the rules adbd enforces (the client must
connect first, OPEN needs arg1 == 0 and a NUL-terminated name, payloads are limited, every WRTE is acknowledged).
`shell:` answers with CR LF line ends like the pty of an old adbd, to prove the client copes with them.
It is a test tool for the emulator only: never point it at a real car unit.
"""
import argparse, os, socket, struct, subprocess, sys, tempfile, threading, time

A_CNXN, A_AUTH, A_OPEN, A_OKAY, A_CLSE, A_WRTE = 0x4E584E43, 0x48545541, 0x4E45504F, 0x59414B4F, 0x45534C43, 0x45545257
MAXDATA = 256 * 1024


def pack(cmd, a0, a1, data=b""):
    return struct.pack("<6I", cmd, a0, a1, len(data), sum(data) & 0xFFFFFFFF, cmd ^ 0xFFFFFFFF) + data


class Conn:
    def __init__(self, sock, args):
        self.sock, self.args = sock, args
        self.lock = threading.Lock()
        self.streams = {}           # my id -> Stream
        self.next_id = 1000

    def send(self, cmd, a0, a1, data=b""):
        with self.lock:
            self.sock.sendall(pack(cmd, a0, a1, data))

    def recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            c = self.sock.recv(n - len(buf))
            if not c:
                raise EOFError
            buf += c
        return buf

    def recv(self):
        cmd, a0, a1, dlen, crc, magic = struct.unpack("<6I", self.recv_exact(24))
        assert magic == cmd ^ 0xFFFFFFFF, "bad magic"
        assert dlen <= MAXDATA, "payload larger than the negotiated maximum"
        data = self.recv_exact(dlen) if dlen else b""
        return cmd, a0, a1, data

    def run(self):
        try:
            cmd, a0, a1, data = self.recv()
            assert cmd == A_CNXN, "the client must send CNXN first"
            banner = "device::ro.product.name=%s;ro.product.model=%s;ro.product.device=%s;features=shell_v2,cmd" % (
                self.args.name, self.args.model, self.args.device)
            self.send(A_CNXN, 0x01000001, MAXDATA, banner.encode() + b"\0")
            while True:
                cmd, a0, a1, data = self.recv()
                if cmd == A_OPEN:
                    assert a1 == 0 and a0 != 0 and data.endswith(b"\0"), "bad OPEN"
                    self.open_stream(a0, data[:-1].decode())
                elif cmd == A_WRTE:
                    st = self.streams.get(a1)
                    if st is None:
                        continue
                    st.write(data)
                    self.send(A_OKAY, a1, a0)
                elif cmd == A_OKAY:
                    st = self.streams.get(a1)
                    if st:
                        st.acked.set()
                elif cmd == A_CLSE:
                    st = self.streams.pop(a1, None)
                    if st:
                        st.close()
        except (EOFError, ConnectionError, AssertionError) as ex:
            if isinstance(ex, AssertionError):
                sys.stderr.write("[fake adbd] protocol violation: %s\n" % ex)
        finally:
            try:
                self.sock.close()
            except OSError:
                pass

    def open_stream(self, client_id, service):
        mine = self.next_id
        self.next_id += 1
        kind, _, arg = service.partition(":")
        if kind in ("exec", "shell"):
            st = CommandStream(self, mine, client_id, arg, crlf=(kind == "shell"))
        elif kind == "sync":
            st = SyncStream(self, mine, client_id)
        else:
            self.send(A_CLSE, 0, client_id)
            return
        self.streams[mine] = st
        self.send(A_OKAY, mine, client_id)
        st.start()


class Stream:
    def __init__(self, conn, mine, client):
        self.conn, self.mine, self.client = conn, mine, client
        self.acked = threading.Event()
        self.acked.set()

    def start(self):
        pass

    def write(self, data):
        pass

    def close(self):
        pass

    def emit(self, data):
        """WRTE to the client, one packet at a time, waiting for its OKAY (adbd's flow control)."""
        for i in range(0, len(data), 4096 * 16):
            self.acked.wait(60)
            self.acked.clear()
            self.conn.send(A_WRTE, self.mine, self.client, data[i:i + 65536])


class CommandStream(Stream):
    def __init__(self, conn, mine, client, command, crlf):
        super().__init__(conn, mine, client)
        self.command, self.crlf = command, crlf

    def start(self):
        threading.Thread(target=self.run, daemon=True).start()

    def run(self):
        a = self.conn.args
        p = subprocess.Popen([a.adb, "-s", a.serial, "exec-out", self.command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        while True:
            chunk = p.stdout.read1(65536) if hasattr(p.stdout, "read1") else p.stdout.read(65536)
            if not chunk:
                break
            if self.crlf:
                chunk = chunk.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
            self.emit(chunk)
        p.wait()
        self.acked.wait(60)
        try:
            self.conn.send(A_CLSE, self.mine, self.client)
        except OSError:
            pass
        self.conn.streams.pop(self.mine, None)


class SyncStream(Stream):
    def __init__(self, conn, mine, client):
        super().__init__(conn, mine, client)
        self.buf = b""
        self.path = None
        self.data = bytearray()

    def write(self, data):
        self.buf += data
        while len(self.buf) >= 8:
            sid, n = self.buf[:4], struct.unpack("<I", self.buf[4:8])[0]
            if sid == b"SEND":
                if len(self.buf) < 8 + n:
                    return
                spec = self.buf[8:8 + n].decode()
                self.path, _, mode = spec.rpartition(",")
                assert mode.isdigit(), "bad mode in SEND"
                self.buf = self.buf[8 + n:]
                self.data = bytearray()
            elif sid == b"DATA":
                assert n <= 64 * 1024, "DATA chunk larger than SYNC_DATA_MAX"
                if len(self.buf) < 8 + n:
                    return
                self.data += self.buf[8:8 + n]
                self.buf = self.buf[8 + n:]
            elif sid == b"DONE":
                self.buf = self.buf[8:]
                self.finish()
            elif sid == b"QUIT":
                self.buf = self.buf[8:]
                return
            else:
                raise AssertionError("unknown sync request %r" % sid)

    def finish(self):
        a = self.conn.args
        fd, tmp = tempfile.mkstemp()
        os.write(fd, bytes(self.data))
        os.close(fd)
        out = subprocess.run([a.adb, "-s", a.serial, "push", tmp, self.path], stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout
        os.unlink(tmp)
        if b"error" in out.lower() or b"failed" in out.lower():
            msg = out[:200]
            self.emit(b"FAIL" + struct.pack("<I", len(msg)) + msg)
        else:
            self.emit(b"OKAY" + struct.pack("<I", 0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb", required=True)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--port", type=int, default=15555)
    ap.add_argument("--model", default="Android SDK built for x86_64")
    ap.add_argument("--name", default="sdk_phone_x86_64")
    ap.add_argument("--device", default="generic_x86_64")
    args = ap.parse_args()
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", args.port))
    srv.listen(16)
    print("fake adbd on 127.0.0.1:%d -> %s" % (args.port, args.serial), flush=True)
    while True:
        s, _ = srv.accept()
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        threading.Thread(target=Conn(s, args).run, daemon=True).start()


if __name__ == "__main__":
    main()
