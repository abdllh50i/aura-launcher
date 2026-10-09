"""Logic tests for rom/flash/aura-install.py that need neither a device nor an emulator.

  python scripts/test_installer_logic.py

A scripted fake unit stands in for the connection (see FakeUnit); only loopback sockets are used. Covers the decisions the
installer takes (status x action matrix, confirmation, pending restart, failed follow-up, restart detection) and the guards
around discovery (authorisation requests, dribbling hosts, huge networks, terminal escapes).
"""
import hashlib
import importlib.util
import io
import ipaddress
import os
import socket
import struct
import sys
import tempfile
import threading
import time
import unittest
from contextlib import redirect_stdout
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "rom", "flash", "aura-install.py")
spec = importlib.util.spec_from_file_location("aura_install", SRC)
ai = importlib.util.module_from_spec(spec)
sys.modules["aura_install"] = ai
spec.loader.exec_module(ai)
ai.LANG = "en"


class FakeUnit:
    """Answers the commands the installer sends, like the unit would, from a small table."""

    def __init__(self, state="STOCK", rom_ver="", follow_ok=True, uptimes=None, root=True, model="K2501"):
        self.state, self.rom_ver, self.follow_ok = state, rom_ver, follow_ok
        self.uptimes = list(uptimes or [500.0])
        self.root, self.model = root, model
        self.files, self.log, self.rebooted = {}, [], False
        self.addr, self.port = "10.42.0.7:5555", 5555

    def shell(self, cmd, stream=None, on_idle=None, give_up=900):
        self.log.append(cmd)
        out = self._answer(cmd)
        if stream:
            stream(out)
        return out

    def _answer(self, cmd):
        if cmd.startswith("getprop "):
            name = cmd.split()[1]
            return {"ro.product.system.model": self.model, "ro.nwd.platform.name": self.model,
                    "ro.build.display.id": "K2501_FY_TEST", "ro.product.system.manufacturer": "Allwinner",
                    "ro.aura.rom.version": self.rom_ver, "persist.nwd.launcher.default": "com.android.launcher",
                    "sys.boot_completed": "1"}.get(name, "") + "\n"
        if cmd == "id":
            return ("uid=0(root) gid=0(root)" if self.root else "uid=2000(shell) gid=2000(shell)") + "\n"
        if cmd.startswith("pm path"):
            return "package:/system/priv-app/Aura/Aura.apk\n"
        if cmd.startswith("pgrep"):
            return ""
        if cmd.startswith("wc -c <"):
            return "%d\n" % len(self.files.get(cmd.split("<")[1].strip(), b""))
        if cmd.startswith("sha256sum"):
            p = cmd.split()[1]
            return "%s  %s\n" % (hashlib.sha256(self.files.get(p, b"")).hexdigest(), p)
        if cmd.startswith("cat /proc/uptime"):
            if len(self.uptimes) > 1:
                return "%.2f 1.0\n" % self.uptimes.pop(0)
            return "%.2f 1.0\n" % self.uptimes[0]
        if "follow.sh" in cmd:
            return ("prepared\n" if "install" in self.mode_hint else "undone\n") if self.follow_ok else ""
        if "aura-flash.sh" in cmd:
            mode = cmd.split("aura-flash.sh")[1].split()[0]
            if mode == "status":
                return "STATE: x\nSTATE_CODE=%s\n" % self.state
            return "RESULT: OK\n"
        return ""

    mode_hint = "install"

    def push(self, data, remote, mode=0):
        self.files[remote] = bytes(data)

    def reboot(self):
        self.rebooted = True

    def close(self):
        pass


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        os.makedirs(os.path.join(self.tmp.name, "patch"), exist_ok=True)
        for f in ai.PACK_FILES:
            with open(os.path.join(self.tmp.name, "patch", f), "wb") as fh:
                fh.write(b"x" * 64)
        with open(os.path.join(self.tmp.name, "aura-flash.sh"), "wb") as fh:
            fh.write(b"#!/system/bin/sh\n")
        self._saved = (ai.PACK, ai.FLASH_SH, ai.WAIT_FIRST, ai.WAIT_TOTAL, ai.WAIT_POLL)
        ai.PACK, ai.FLASH_SH = os.path.join(self.tmp.name, "patch"), os.path.join(self.tmp.name, "aura-flash.sh")
        ai.WAIT_FIRST, ai.WAIT_TOTAL, ai.WAIT_POLL = 0, 1.5, 0.05

    def tearDown(self):
        ai.PACK, ai.FLASH_SH, ai.WAIT_FIRST, ai.WAIT_TOTAL, ai.WAIT_POLL = self._saved
        self.tmp.cleanup()

    def run_installer(self, unit, command="install", yes=True, no_reboot=False, answer="YES"):
        args = ai.argparse.Namespace(command=command, ip=None, scan=None, port=5555, yes=yes, no_reboot=no_reboot,
                                     system_adb=False, adb=None, serial=None, lang="en", device="/dev/block/mapper/system",
                                     expect_model="K2501")
        inst = ai.Installer(args)
        inst.connect = lambda: setattr(inst, "unit", unit)
        unit.mode_hint = command
        buf = io.StringIO()
        with mock.patch("builtins.input", lambda prompt="": answer), redirect_stdout(buf):
            try:
                rc = inst.run()
            except ai.Stop as ex:
                rc = "STOP: %s" % ex
        return rc, buf.getvalue(), inst


class Decisions(Base):
    def test_install_on_stock_writes_and_restarts(self):
        u = FakeUnit(state="STOCK", uptimes=[500.0, 5.0])
        u.rom_ver = ""
        rc, out, _ = self.run_installer(u, "install")
        self.assertEqual(rc, 0, out)
        self.assertTrue(u.rebooted)
        self.assertIn("RESULT: OK", out)
        self.assertTrue(any("aura-flash.sh apply" in c for c in u.log))

    def test_pack_is_copied_and_checked_before_the_script_runs(self):
        u = FakeUnit(state="STOCK", uptimes=[500.0, 5.0])
        self.run_installer(u, "install", no_reboot=True)
        first_run = next(i for i, c in enumerate(u.log) if c.startswith("sh ") and "aura-flash.sh" in c)
        checks = [i for i, c in enumerate(u.log) if c.startswith("sha256sum") or c.startswith("wc -c")]
        self.assertGreaterEqual(len(checks), 14)               # size and hash of the 6 pack files + the script
        self.assertTrue(all(i < first_run for i in checks))

    def test_failed_followup_does_not_restart(self):
        u = FakeUnit(state="STOCK", follow_ok=False)
        rc, out, _ = self.run_installer(u, "install")
        self.assertEqual(rc, 1, out)
        self.assertFalse(u.rebooted)
        self.assertIn("NOT restarted", out)

    def test_failed_followup_of_restore_does_not_restart(self):
        u = FakeUnit(state="AURA", rom_ver="1.0.0", follow_ok=False)
        rc, out, _ = self.run_installer(u, "restore")
        self.assertEqual(rc, 1, out)
        self.assertFalse(u.rebooted)

    def test_already_running_is_left_alone(self):
        u = FakeUnit(state="AURA", rom_ver="1.0.0")
        rc, out, _ = self.run_installer(u, "install")
        self.assertEqual(rc, 0)
        self.assertIn("already installed and running", out)
        self.assertFalse(any("aura-flash.sh apply" in c for c in u.log))

    def test_written_but_not_restarted_only_finishes(self):
        u = FakeUnit(state="AURA", rom_ver="", uptimes=[500.0, 5.0])
        rc, out, _ = self.run_installer(u, "install")
        self.assertEqual(rc, 0, out)
        self.assertIn("Finishing that", out)
        self.assertFalse(any("aura-flash.sh apply" in c for c in u.log))
        self.assertTrue(u.rebooted)

    def test_unknown_partition_is_refused_without_writing(self):
        for action in ("install", "restore"):
            u = FakeUnit(state="UNKNOWN")
            rc, out, _ = self.run_installer(u, action)
            self.assertTrue(str(rc).startswith("STOP") or rc != 0, out)
            self.assertFalse(any("aura-flash.sh apply" in c or "aura-flash.sh revert" in c for c in u.log))

    def test_confirmation_other_than_YES_cancels(self):
        for answer in ("", "yes", "y", "no"):
            u = FakeUnit(state="STOCK")
            rc, out, _ = self.run_installer(u, "install", yes=False, answer=answer)
            self.assertIn("Cancelled", str(rc) + out)
            self.assertFalse(any("aura-flash.sh apply" in c for c in u.log))

    def test_status_writes_nothing_and_never_asks(self):
        u = FakeUnit(state="STOCK")
        rc, out, _ = self.run_installer(u, "status", yes=False)
        self.assertEqual(rc, 0)
        self.assertFalse(any("aura-flash.sh apply" in c or "aura-flash.sh revert" in c for c in u.log))
        self.assertFalse(u.rebooted)

    def test_wrong_model_is_refused_before_root_is_even_checked(self):
        u = FakeUnit(model="IKON-TV", root=False)
        rc, out, _ = self.run_installer(u, "install")
        self.assertIn("not a 'K2501'", str(rc) + out)
        self.assertFalse(any(c == "id" for c in u.log))
        self.assertFalse(any("mkdir" in c or "aura-flash" in c for c in u.log))


class RestartDetection(Base):
    def wait(self, unit, before):
        inst = ai.Installer(ai.argparse.Namespace(command="install", ip=None, scan=None, port=5555, yes=True, no_reboot=False,
                                                  system_adb=False, adb=None, serial=None, lang="en",
                                                  device="x", expect_model="K2501"))
        inst.unit = unit
        buf = io.StringIO()
        real = ai.NativeAdb
        ai.NativeAdb = lambda host, port, connect_timeout=3.0: unit
        try:
            with redirect_stdout(buf):
                inst.wait_back("install", before)
        finally:
            ai.NativeAdb = real
        return buf.getvalue()

    def test_old_system_still_up_is_not_accepted(self):
        u = FakeUnit(rom_ver="1.0.0", uptimes=[520.0, 530.0, 540.0])       # uptime keeps growing: no restart
        out = self.wait(u, 500.0)
        self.assertNotIn("Aura ROM is running", out)
        self.assertIn("does not seem to have restarted", out)

    def test_new_boot_is_accepted(self):
        u = FakeUnit(rom_ver="1.0.0", uptimes=[520.0, 12.0])               # first the old system, then a fresh boot
        out = self.wait(u, 500.0)
        self.assertIn("Aura ROM is running (version 1.0.0)", out)

    def test_unit_that_went_away_counts_as_restarted_even_without_uptime(self):
        class Flaky(FakeUnit):
            calls = 0

            def shell(self, cmd, **kw):
                if cmd.startswith("cat /proc/uptime"):
                    return "garbage"
                return super().shell(cmd, **kw)

        u = Flaky(rom_ver="1.0.0")
        real = ai.NativeAdb
        state = {"n": 0}

        def factory(host, port, connect_timeout=3.0):
            state["n"] += 1
            if state["n"] == 1:
                raise ai.AdbError("down")          # the unit disappeared
            return u
        inst = ai.Installer(ai.argparse.Namespace(command="install", ip=None, scan=None, port=5555, yes=True, no_reboot=False,
                                                  system_adb=False, adb=None, serial=None, lang="en",
                                                  device="x", expect_model="K2501"))
        inst.unit = u
        ai.NativeAdb = factory
        buf = io.StringIO()
        try:
            with redirect_stdout(buf):
                inst.wait_back("install", None)
        finally:
            ai.NativeAdb = real
        self.assertIn("Aura ROM is running", buf.getvalue())

    def test_reachable_old_system_without_uptime_waits_for_timeout(self):
        class NoUptime(FakeUnit):
            def shell(self, cmd, **kw):
                if cmd.startswith("cat /proc/uptime"):
                    return "garbage"
                return super().shell(cmd, **kw)
        out = self.wait(NoUptime(rom_ver="1.0.0"), None)
        self.assertNotIn("Aura ROM is running", out)


class Discovery(unittest.TestCase):
    def test_authorisation_request_is_never_answered_by_the_system_adb(self):
        # a fake "device" that answers CNXN with AUTH
        srv = socket.socket()
        srv.bind(("127.0.0.1", 0))
        srv.listen(1)
        port = srv.getsockname()[1]

        def serve():
            c, _ = srv.accept()
            c.recv(4096)
            c.sendall(struct.pack("<6I", ai.A_AUTH, 1, 0, 20, 0, ai.A_AUTH ^ 0xFFFFFFFF) + b"x" * 20)
            time.sleep(0.5)
            c.close()
        threading.Thread(target=serve, daemon=True).start()
        started = []
        real = ai.SystemAdb
        ai.SystemAdb = lambda *a, **k: started.append(a) or (_ for _ in ()).throw(AssertionError("system adb must not run"))
        try:
            args = ai.argparse.Namespace(command="status", ip="127.0.0.1:%d" % port, scan=None, port=port, yes=True,
                                         no_reboot=True, system_adb=False, adb=None, serial=None, lang="en",
                                         device="x", expect_model="K2501")
            inst = ai.Installer(args)
            with self.assertRaises(ai.Stop) as cm:
                inst.connect()
            self.assertIn("asks for ADB authorisation", str(cm.exception))
            self.assertEqual(started, [])
        finally:
            ai.SystemAdb = real
            srv.close()

    def test_host_that_dribbles_bytes_cannot_stall_a_probe(self):
        srv = socket.socket()
        srv.bind(("127.0.0.1", 0))
        srv.listen(1)
        port = srv.getsockname()[1]
        stop = threading.Event()

        def serve():
            try:
                c, _ = srv.accept()
                hdr = struct.pack("<6I", ai.A_CNXN, 0x01000001, 4096, 1000000, 0, ai.A_CNXN ^ 0xFFFFFFFF)
                for b in hdr:
                    if stop.is_set():
                        break
                    c.sendall(bytes([b]))
                    time.sleep(0.6)           # one byte per 0.6 s: never completes a header within the deadline
                c.close()
            except OSError:
                pass                          # the probe gave up and closed the connection: that is the point
        threading.Thread(target=serve, daemon=True).start()
        t0 = time.monotonic()
        result = ai.probe("127.0.0.1", port, timeout=0.5)
        took = time.monotonic() - t0
        stop.set()
        srv.close()
        self.assertIsNone(result)
        self.assertLess(took, 4.0)

    def test_a_huge_network_is_not_expanded_in_full(self):
        t0 = time.monotonic()
        hosts = ai.expand_hosts([ipaddress.ip_network("10.0.0.0/8")])
        self.assertLessEqual(len(hosts), 2048)
        self.assertLess(time.monotonic() - t0, 1.0)

    def test_control_characters_from_other_machines_are_neutralised(self):
        self.assertEqual(ai.clean("K2501\x1b[2J\x07"), "K2501?[2J?")
        self.assertNotIn("\x1b", ai.short_banner("device::ro.product.model=\x1b[31mK;ro.product.device=\x1b]0;x"))

    def test_picking_a_unit_validates_the_number(self):
        args = ai.argparse.Namespace(command="install", ip=None, scan=["127.0.0.0/30"], port=5555, yes=False, no_reboot=False,
                                     system_adb=False, adb=None, serial=None, lang="en", device="x", expect_model="K2501")
        inst = ai.Installer(args)
        two = ([("10.0.0.1:5555", "device::ro.product.model=K2501"), ("10.0.0.2:5555", "device::ro.product.model=K2501")], [])
        with mock.patch.object(ai, "scan", lambda nets, port, model: two), mock.patch.object(sys.stdin, "isatty", lambda: True):
            for bad in ("0", "-1", "3", "x"):
                with mock.patch("builtins.input", lambda prompt="", v=bad: v), redirect_stdout(io.StringIO()):
                    with self.assertRaises(ai.Stop):
                        inst.find_unit()
            with mock.patch("builtins.input", lambda prompt="": "2"), redirect_stdout(io.StringIO()):
                self.assertEqual(inst.find_unit(), ("10.0.0.2", 5555))


if __name__ == "__main__":
    unittest.main(verbosity=2)
