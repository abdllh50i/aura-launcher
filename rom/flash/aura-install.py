#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Aura ROM installer for the K2501 car head unit (NWD firmware, Allwinner T507).

Runs on Linux (Ubuntu), macOS and Windows with Python 3.8+ and nothing else (it carries its own small ADB client, so no
"apt install adb" and no internet are needed at the car).

    python3 aura-install.py              find the unit on the network, show what it has, install the Aura ROM
    python3 aura-install.py status       find the unit and show what is installed (writes nothing)
    python3 aura-install.py restore      put the stock firmware back
    python3 aura-install.py scan         only look for the unit

The laptop and the unit must be on the same network (a phone hotspot, or the laptop's own hotspot). The program only ever
touches a unit that says it is a K2501, writes nothing unless the system partition is exactly the stock (or exact Aura) image
or an interrupted run of this very patch, verifies the result and restarts the unit. All the safety checks run on the unit,
in aura-flash.sh, the same script the Windows installer uses.
"""
import argparse
import hashlib
import ipaddress
import itertools
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.join(HERE, "patch")
FLASH_SH = os.path.join(HERE, "aura-flash.sh")
PACK_FILES = ["forward.bin", "reverse.bin", "ranges.txt", "order-apply.txt", "order-revert.txt", "hashes.txt"]
REMOTE = "/data/local/tmp/aura-rom"
PORT = 5555
EXPECT_MODEL = "K2501"
WAIT_FIRST = 15      # seconds before looking for the unit again after the restart command
WAIT_TOTAL = 480     # how long to wait for the new boot (the first start after installing takes 3-4 minutes)
WAIT_POLL = 5

# ------------------------------------------------------------------------------------------------ messages
# English first; Arabic for the same keys. Lines that come from the unit itself (STATE:, RESULT: ...) stay English.
M = {
    "search": {"en": "Looking for the car unit on {nets} ...",
               "ar": "أبحث عن شاشة السيارة في الشبكة {nets} ..."},
    "found": {"en": "Found the unit: {addr}  ({banner})",
              "ar": "لقيت الشاشة: {addr}  ({banner})"},
    "skipped": {"en": "Skipped {addr}: not a {model} (left untouched)",
                "ar": "تجاهلت {addr}: ما هي {model} (ما لمستها)"},
    "none": {"en": "No {model} unit was found on this network.\n"
                   "  1. The laptop and the unit must be on the same network, for example the laptop's own hotspot\n"
                   "     (Ubuntu: Settings > Wi-Fi > menu > Turn On Wi-Fi Hotspot, then join it from the unit's Wi-Fi settings)\n"
                   "     or a phone hotspot that both of them have joined.\n"
                   "  2. Switch the unit on and wait until its home screen is up.\n"
                   "  3. Or give its address yourself:  python3 aura-install.py {action} --ip 192.168.x.x",
             "ar": "ما لقيت شاشة {model} على هذي الشبكة.\n"
                   "  1. لازم اللابتوب والشاشة على نفس الشبكة، مثلًا هوتسبوت اللابتوب نفسه\n"
                   "     (أوبنتو: الإعدادات ← الواي فاي ← القائمة ← تشغيل نقطة اتصال، وبعدها وصّل الشاشة عليها من إعدادات الواي فاي)\n"
                   "     أو هوتسبوت الجوال إذا كلهم متصلين فيه.\n"
                   "  2. شغّل الشاشة وانتظر حتى تظهر الشاشة الرئيسية.\n"
                   "  3. أو اكتب عنوانها بنفسك:  python3 aura-install.py {action} --ip 192.168.x.x"},
    "several": {"en": "Several {model} units were found. Choose one with --ip:",
                "ar": "لقيت أكثر من شاشة {model}. حدّد وحدة بـ --ip:"},
    "choose": {"en": "Which one? (number): ", "ar": "أي وحدة؟ (رقم): "},
    "connecting": {"en": "Connecting to {addr} ...", "ar": "أتصل بـ {addr} ..."},
    "auth": {"en": "This device asks for ADB authorisation. A K2501 head unit never does (it has ro.adb.secure=0), so this is probably "
                   "not the unit. If you are sure it is, install the system adb (sudo apt install adb) and run again with --system-adb.",
             "ar": "هذا الجهاز يطلب تصريح ADB. شاشة K2501 ما تطلبه أبدًا (عندها ro.adb.secure=0) فغالبًا هذا مو الجهاز المطلوب. "
                   "لو متأكد أنه هو، ثبّت adb (sudo apt install adb) وشغّل من جديد مع --system-adb."},
    "skipped_auth": {"en": "Skipped {addr}: it asks for ADB authorisation, so it cannot be checked "
                           "(use --system-adb --ip {addr} if this is the unit)",
                     "ar": "تجاهلت {addr}: تطلب تصريح ADB وما أقدر أفحصها (استخدم --system-adb --ip {addr} لو هذي الشاشة)"},
    "unreachable": {"en": "Could not reach {addr}: {why}\n  Is the unit switched on, on the same network, and is adb over the network enabled on it?",
                    "ar": "ما قدرت أوصل لـ {addr}: {why}\n  الشاشة شغّالة؟ ومتصلة بنفس الشبكة؟ وadb عبر الشبكة مفعّل فيها؟"},
    "bad_addr": {"en": "'{text}' is not a valid address (use 192.168.1.50 or 192.168.1.50:5555).",
                 "ar": "'{text}' عنوان غير صحيح (استخدم 192.168.1.50 أو 192.168.1.50:5555)."},
    "no_root": {"en": "The unit did not give a root shell ({who}). Network adb must be enabled with root access "
                      "(it was when the backup was made).",
                "ar": "الشاشة ما أعطت صلاحية روت ({who}). لازم adb عبر الشبكة يكون مفعّل بصلاحية روت (كان كذا وقت النسخة الاحتياطية)."},
    "device": {"en": "Device : model={model}  nwd={nwd}  vendor={mfr}", "ar": "الجهاز : model={model}  nwd={nwd}  vendor={mfr}"},
    "build": {"en": "Build  : {build}", "ar": "البناء : {build}"},
    "not_model": {"en": "This is not a '{expect}' (found '{model}'). Refusing to touch it. (If this is the TV or another "
                        "device, that is exactly why this check exists.)",
                  "ar": "هذا مو '{expect}' (لقيت '{model}'). أرفض ألمسه. (لو هذا التلفزيون أو جهاز ثاني، فهذا بالضبط سبب هذا الفحص.)"},
    "run_rom": {"en": "Running: Aura ROM {ver}   home app = {home}   {path}",
                "ar": "الشغّال الحين: Aura ROM {ver}   التطبيق الرئيسي = {home}   {path}"},
    "run_none": {"en": "Running: no Aura ROM in the running system   home app = {home}",
                 "ar": "الشغّال الحين: ما فيه Aura ROM في النظام الشغّال   التطبيق الرئيسي = {home}"},
    "incomplete": {"en": "Patch pack incomplete: {path} is missing.", "ar": "حزمة الرقعة ناقصة: الملف {path} غير موجود."},
    "no_script": {"en": "aura-flash.sh is missing next to this program.", "ar": "الملف aura-flash.sh غير موجود جنب البرنامج."},
    "busy": {"en": "An earlier run (process {pid}) is still working on the unit. Wait until it is finished - "
                   "'cat {log}' on the unit shows its progress and ends with a RESULT line - then run this again.",
             "ar": "تشغيل سابق (العملية {pid}) لسا شغّال على الشاشة. انتظر يخلص - الملف {log} على الشاشة يعرض تقدّمه "
                   "وينتهي بسطر RESULT - وبعدها شغّل البرنامج من جديد."},
    "copying": {"en": "Copying the patch pack to the unit ...", "ar": "أنسخ حزمة الرقعة إلى الشاشة ..."},
    "copy_failed": {"en": "Copying {name} to the unit failed ({detail}). Nothing was written; run the program again.",
                    "ar": "فشل نسخ {name} إلى الشاشة ({detail}). ما انكتب شي؛ شغّل البرنامج من جديد."},
    "reading": {"en": "Reading the system partition (this takes up to a minute) ...",
                "ar": "أقرأ قسم النظام (يأخذ لين دقيقة) ..."},
    "no_state": {"en": "Could not read the state. Output above.", "ar": "ما قدرت أقرأ الحالة. المخرجات فوق."},
    "hint_restart": {"en": "The unit has not been restarted since the Aura ROM was written: restart it to start using it.",
                     "ar": "الشاشة ما انعاد تشغيلها بعد كتابة Aura ROM: أعد تشغيلها عشان تشتغل."},
    "hint_restart_stock": {"en": "The stock firmware is written but the unit has not been restarted since: restart it to finish the restore.",
                           "ar": "النظام الأصلي انكتب لكن الشاشة ما انعاد تشغيلها: أعد تشغيلها عشان يكتمل الرجوع."},
    "already_running": {"en": "The Aura ROM is already installed and running. Nothing to do.",
                        "ar": "Aura ROM مثبّت وشغّال. ما في شي أسويه."},
    "pending_install": {"en": "The Aura ROM is already written to the unit but it has not been restarted since. Finishing that.",
                        "ar": "Aura ROM مكتوب على الشاشة بس ما انعاد تشغيلها. أكمل الباقي."},
    "bad_install": {"en": "The system partition is neither the expected stock image nor an interrupted run of this patch "
                          "(another firmware version?). Nothing was written and the unit was not changed.",
                    "ar": "قسم النظام مو الصورة الأصلية المتوقعة ولا تركيب منقطع من هذي الرقعة (نسخة فيرموير ثانية؟). "
                          "ما انكتب شي والشاشة ما تغيّرت."},
    "already_stock": {"en": "The unit already has the stock firmware. Nothing to do.",
                      "ar": "الشاشة على النظام الأصلي أصلًا. ما في شي أسويه."},
    "pending_restore": {"en": "The stock firmware is already written to the unit but it has not been restarted since. Finishing that.",
                        "ar": "النظام الأصلي مكتوب على الشاشة بس ما انعاد تشغيلها. أكمل الباقي."},
    "bad_restore": {"en": "The system partition is neither the Aura image nor an interrupted run of this patch. Nothing was written.",
                    "ar": "قسم النظام مو صورة Aura ولا تركيب منقطع من هذي الرقعة. ما انكتب شي."},
    "ready_finish": {"en": "READY TO FINISH: nothing more is written to the system partition; the follow-up step runs and the unit restarts.",
                     "ar": "جاهز للإكمال: ما راح يُكتب شي زيادة على قسم النظام؛ تنفّذ الخطوة الأخيرة وتنعاد تشغيل الشاشة."},
    "ready_install": {"en": "READY TO INSTALL the Aura ROM.", "ar": "جاهز لتثبيت Aura ROM."},
    "ready_install_1": {"en": "  - writes about {mb} MB of changed blocks into the system partition (not the whole system)",
                        "ar": "  - يكتب حوالي {mb} ميجا من الكتل المتغيّرة في قسم النظام (مو النظام كله)"},
    "ready_install_2": {"en": "  - the result is verified against the expected hash; on any problem the old blocks are written back",
                        "ar": "  - النتيجة تتحقق من بصمتها المتوقعة؛ لو صار أي خلل ترجع الكتل القديمة"},
    "ready_install_3": {"en": "  - your data (apps, settings, paired phones) is not touched",
                        "ar": "  - بياناتك (التطبيقات، الإعدادات، الجوالات المقترنة) ما تتأثر"},
    "ready_restore": {"en": "READY TO RESTORE the stock firmware (undo the Aura ROM).",
                      "ar": "جاهز لإرجاع النظام الأصلي (إلغاء Aura ROM)."},
    "keep_power": {"en": "  - keep the unit powered the whole time (ignition/ACC on, do NOT switch it off) until it reboots",
                   "ar": "  - خلّ الشاشة موصولة بالكهرباء طول الوقت (السيارة شغّالة/ACC، لا تطفيها) لين تعيد التشغيل"},
    "type_yes": {"en": "Type YES to continue: ", "ar": "اكتب YES للمتابعة: "},
    "cancelled": {"en": "Cancelled. Nothing was written.", "ar": "تم الإلغاء. ما انكتب شي."},
    "writing": {"en": "Writing ...", "ar": "أكتب ..."},
    "not_ok_1": {"en": "The patch did not report success. The unit has NOT been rebooted - do not switch it off or reboot it yet.",
                 "ar": "الرقعة ما أكدت النجاح. الشاشة ما انعاد تشغيلها - لا تطفيها ولا تعيد تشغيلها الحين."},
    "not_ok_2": {"en": "  * If the lines above end with 'previous contents were restored and verified', nothing changed: you may try again.",
                 "ar": "  * لو السطور فوق تنتهي بـ 'previous contents were restored and verified' فما تغيّر شي: تقدر تحاول من جديد."},
    "not_ok_3": {"en": "  * If the connection dropped or the output stops early, the unit keeps working on its own: wait two or three minutes,",
                 "ar": "  * لو انقطع الاتصال أو وقفت المخرجات بدري، الشاشة تكمل شغلها لحالها: انتظر دقيقتين أو ثلاث،"},
    "not_ok_4": {"en": "    then run 'status'. An interrupted run is completed by running install (or restore) again.",
                 "ar": "    وبعدها شغّل status. التركيب المنقطع يكتمل بتشغيل install (أو restore) مرة ثانية."},
    "not_ok_5": {"en": "  * A log is kept on the unit: {log}", "ar": "  * سجل العملية على الشاشة: {log}"},
    "not_done": {"en": "The {action} did not complete.", "ar": "العملية ({action}) ما اكتملت."},
    "follow_failed": {"en": "The follow-up step did not finish ('{expect}' was not reported). The system partition itself is fine, "
                            "but the unit was NOT restarted: run this program again and it will finish the job.",
                      "ar": "الخطوة الأخيرة ما اكتملت (ما رجّعت '{expect}'). قسم النظام نفسه سليم، لكن الشاشة ما انعاد تشغيلها: "
                            "شغّل البرنامج من جديد وراح يكمل الباقي."},
    "no_restart": {"en": "The unit does not seem to have restarted. Switch it off and on yourself, then run 'status' to check.",
                   "ar": "يبدو أن الشاشة ما أعادت التشغيل. أطفها وشغّلها بنفسك، وبعدها شغّل status للتأكد."},
    "done": {"en": "DONE: {action} succeeded.", "ar": "تم: نجحت العملية ({action})."},
    "done_finished": {"en": "DONE: {action} finished.", "ar": "تم: اكتملت العملية ({action})."},
    "no_reboot": {"en": "Reboot the unit now to finish, and do not use it before that (the running system still holds the old file tables).",
                  "ar": "أعد تشغيل الشاشة الحين لتكتمل العملية، ولا تستخدمها قبل كذا (النظام الشغّال لسا يحمل جداول الملفات القديمة)."},
    "rebooting": {"en": "Rebooting the unit ...", "ar": "أعيد تشغيل الشاشة ..."},
    "waiting": {"en": "Waiting for the unit to start again (the first start after installing takes up to 3-4 minutes) ...",
                "ar": "أنتظر الشاشة تشتغل من جديد (أول تشغيل بعد التثبيت يأخذ لين 3-4 دقايق) ..."},
    "back_rom": {"en": "The unit is back and the Aura ROM is running (version {ver}).",
                 "ar": "الشاشة رجعت وAura ROM شغّال (النسخة {ver})."},
    "back_stock": {"en": "The unit is back on the stock firmware.", "ar": "الشاشة رجعت على النظام الأصلي."},
    "back_unexpected": {"en": "The unit is back, but it does not report the expected system yet. Run 'status' in a minute.",
                        "ar": "الشاشة رجعت لكن ما أكدت النظام المتوقع بعد. شغّل status بعد دقيقة."},
    "back_timeout": {"en": "The unit has not shown up again yet. The first start can take a while - run 'status' later.",
                     "ar": "الشاشة ما ظهرت من جديد بعد. أول تشغيل ياخذ وقت - شغّل status بعدين."},
    "lost": {"en": "The connection to the unit was lost ({why}).", "ar": "انقطع الاتصال بالشاشة ({why})."},
}

LANG = "en"


def t(key, **kw):
    s = M[key][LANG]
    return s.format(**kw) if kw else s


def detect_lang():
    for var in ("LC_ALL", "LC_MESSAGES", "LANG"):
        v = os.environ.get(var, "")
        if v:
            return "ar" if v.lower().startswith("ar") else "en"
    return "en"


# ------------------------------------------------------------------------------------------------ output
_color = sys.stdout.isatty() and not os.environ.get("NO_COLOR") and (os.name != "nt" or "WT_SESSION" in os.environ)
COLORS = {"red": "31", "green": "32", "yellow": "33", "cyan": "36", "gray": "90"}


def say(msg="", color=None, end="\n"):
    if color and _color:
        msg = "\033[%sm%s\033[0m" % (COLORS[color], msg)
    sys.stdout.write(msg + end)
    sys.stdout.flush()


class Stop(Exception):
    """A reason to stop; nothing unsafe has happened."""


def die(msg):
    raise Stop(msg)


# ------------------------------------------------------------------------------------------------ ADB client
A_CNXN, A_AUTH, A_OPEN, A_OKAY, A_CLSE, A_WRTE = 0x4E584E43, 0x48545541, 0x4E45504F, 0x59414B4F, 0x45534C43, 0x45545257
A_VERSION = 0x01000001
MAX_PAYLOAD = 256 * 1024


class AdbError(Exception):
    pass


class AdbAuthRequired(AdbError):
    pass


class AdbRefused(AdbError):
    """The unit closed a stream right after OPEN: it does not offer that service."""


class NativeAdb:
    """The few parts of the ADB protocol the installer needs: connect, run a shell command, push a file."""

    name = "built-in"

    def __init__(self, host, port=PORT, connect_timeout=5.0):
        self.host, self.port = host, port
        self.addr = "%s:%d" % (host, port)
        self.sock = socket.create_connection((host, port), timeout=connect_timeout)
        self.sock.settimeout(connect_timeout)       # a host that accepts the connection but is not adbd must not hold us up
        # ...nor one that dribbles bytes: the whole handshake has a deadline and a small size limit
        self._deadline = time.monotonic() + connect_timeout * 1.5
        self._maxlen = 256 * 1024
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        for opt, val in (("TCP_KEEPIDLE", 20), ("TCP_KEEPINTVL", 10), ("TCP_KEEPCNT", 4)):   # notice a vanished unit within ~1 minute
            if hasattr(socket, opt):
                try:
                    self.sock.setsockopt(socket.IPPROTO_TCP, getattr(socket, opt), val)
                except OSError:
                    pass
        self._buf = bytearray()
        self._next_id = 1
        self._pending = {}        # stream id -> data received while waiting for something else
        self._svc = "exec"
        self.maxdata = 4096
        self.banner = ""
        try:
            self._handshake()
        except Exception:
            self.close()
            raise
        self._deadline = None
        self._maxlen = 16 * 1024 * 1024
        self.sock.settimeout(15)                    # afterwards commands may be quiet for a while; the loops below handle it

    # -- packets
    @staticmethod
    def _pack(cmd, arg0, arg1, data=b""):
        return struct.pack("<6I", cmd, arg0, arg1, len(data), sum(data) & 0xFFFFFFFF, cmd ^ 0xFFFFFFFF) + data

    def _send(self, cmd, arg0, arg1, data=b""):
        try:
            self.sock.sendall(self._pack(cmd, arg0, arg1, data))
        except OSError as ex:
            raise AdbError("connection lost: %s" % ex)

    def _need(self, n):
        """Make sure the receive buffer holds n bytes; a socket.timeout leaves what was read in the buffer."""
        while len(self._buf) < n:
            if self._deadline is not None and time.monotonic() > self._deadline:
                raise socket.timeout()
            try:
                chunk = self.sock.recv(65536)
            except socket.timeout:
                raise
            except OSError as ex:
                raise AdbError("connection lost: %s" % ex)
            if not chunk:
                raise AdbError("the unit closed the connection")
            self._buf += chunk

    def _recv(self):
        self._need(24)
        cmd, a0, a1, dlen, _crc, magic = struct.unpack("<6I", bytes(self._buf[:24]))
        if magic != (cmd ^ 0xFFFFFFFF) or dlen > self._maxlen:
            raise AdbError("garbled reply from the unit")
        self._need(24 + dlen)
        data = bytes(self._buf[24:24 + dlen])
        del self._buf[:24 + dlen]
        return cmd, a0, a1, data

    def _handshake(self):
        try:
            self._send(A_CNXN, A_VERSION, MAX_PAYLOAD, b"host::\0")
            for _ in range(4):
                cmd, a0, a1, data = self._recv()
                if cmd == A_AUTH:
                    raise AdbAuthRequired("the unit asks for authorisation")
                if cmd == A_CNXN:
                    self.maxdata = max(4096, min(a1, MAX_PAYLOAD))
                    self.banner = data.split(b"\0")[0].decode("utf-8", "replace")
                    return
            raise AdbError("no ADB answer")
        except socket.timeout:
            raise AdbError("no ADB answer (timeout)")

    @property
    def props(self):
        """The properties in the connect banner (device::ro.product.model=...;ro.product.device=...;...)."""
        out = {}
        for part in self.banner.split("::", 1)[-1].split(";"):
            if "=" in part:
                k, v = part.split("=", 1)
                out[k] = v
        return out

    # -- streams
    def _open(self, service):
        lid = self._next_id
        self._next_id += 1
        self._send(A_OPEN, lid, 0, service.encode() + b"\0")
        while True:
            try:
                cmd, a0, a1, data = self._recv()
            except socket.timeout:
                raise AdbError("the unit did not answer")
            if cmd == A_OKAY and a1 == lid:
                return lid, a0
            if cmd == A_CLSE and a1 == lid:
                raise AdbRefused("the unit refused the request")

    def shell(self, command, stream=None, on_idle=None, give_up=900):
        """Runs a shell command, returns its output as text. [stream] gets every piece as it arrives.
        Uses the `exec:` service (no pty, so no CR LF line ends) and falls back to `shell:` on an adbd that lacks it."""
        services = ["exec", "shell"] if self._svc == "exec" else ["shell"]
        for i, svc in enumerate(services):
            try:
                lid, rid = self._open("%s:%s" % (svc, command))
                break
            except AdbRefused:
                if i == len(services) - 1:
                    raise
                self._svc = "shell"
        out = bytearray()
        last = time.monotonic()
        while True:
            try:
                cmd, a0, a1, data = self._recv()
            except socket.timeout:
                if time.monotonic() - last > give_up:
                    raise AdbError("no output for %d s" % give_up)
                if on_idle:
                    on_idle()
                continue
            last = time.monotonic()
            if a1 != lid:
                continue
            if cmd == A_WRTE:
                out += data
                self._send(A_OKAY, lid, rid)
                if stream:
                    stream(data.decode("utf-8", "replace"))
            elif cmd == A_CLSE:
                return out.decode("utf-8", "replace")

    def _wait_okay(self, lid, rid):
        while True:
            try:
                cmd, a0, a1, data = self._recv()
            except socket.timeout:
                raise AdbError("the unit stopped answering while copying a file")
            if a1 != lid:
                continue
            if cmd == A_OKAY:
                return
            if cmd == A_WRTE:                     # an early FAIL message, keep it
                self._pending.setdefault(lid, bytearray()).extend(data)
                self._send(A_OKAY, lid, rid)
            elif cmd == A_CLSE:
                raise AdbError("the unit closed the file transfer")

    def push(self, local_or_bytes, remote, mode=0o100644):
        """Copies a file (or a bytes object) to the unit with the sync service."""
        data = local_or_bytes if isinstance(local_or_bytes, (bytes, bytearray)) else open(local_or_bytes, "rb").read()
        lid, rid = self._open("sync:")
        chunk = max(1024, min(64 * 1024, self.maxdata - 8))

        def send(payload):
            self._send(A_WRTE, lid, rid, payload)
            self._wait_okay(lid, rid)

        spec = ("%s,%d" % (remote, mode)).encode()
        send(b"SEND" + struct.pack("<I", len(spec)) + spec)
        for i in range(0, len(data), chunk):
            blk = data[i:i + chunk]
            send(b"DATA" + struct.pack("<I", len(blk)) + bytes(blk))
        send(b"DONE" + struct.pack("<I", int(time.time())))
        reply = bytearray(self._pending.pop(lid, b""))
        while len(reply) < 8:
            try:
                cmd, a0, a1, d = self._recv()
            except socket.timeout:
                raise AdbError("the unit did not confirm the file")
            if a1 != lid:
                continue
            if cmd == A_WRTE:
                reply += d
                self._send(A_OKAY, lid, rid)
            elif cmd == A_CLSE:
                break
        if bytes(reply[:4]) != b"OKAY":
            raise AdbError("the unit refused the file: %s" % bytes(reply[8:]).decode("utf-8", "replace"))
        try:
            send(b"QUIT" + struct.pack("<I", 0))
        except AdbError:
            pass
        self._send(A_CLSE, lid, rid)

    def reboot(self):
        try:
            self.shell("reboot", give_up=10)
        except AdbError:
            pass          # the connection drops while the unit goes down

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


class SystemAdb:
    """The same operations through the adb program of the system (for units that ask for ADB authorisation)."""

    name = "system adb"

    def __init__(self, adb, serial):
        self.adb, self.serial, self.addr = adb, serial, serial
        self.banner = ""
        self.props = {}
        self.maxdata = 0
        if ":" in serial:
            self._run(["connect", serial], serial_arg=False)
        state = self._run(["get-state"]).strip()
        if state != "device":
            raise AdbError("adb reports '%s' for %s" % (state, serial))

    def _run(self, args, serial_arg=True, stream=None):
        cmd = [self.adb] + (["-s", self.serial] if serial_arg else []) + args
        try:
            p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        except OSError as ex:
            raise AdbError("cannot run %s: %s" % (self.adb, ex))
        out = []
        for raw in iter(lambda: p.stdout.read(1), b""):
            out.append(raw)
            if stream and raw == b"\n":
                stream(b"".join(out).decode("utf-8", "replace"))
                out = []
        p.wait()
        text = b"".join(out).decode("utf-8", "replace")
        if stream and text:
            stream(text)
        return text

    def shell(self, command, stream=None, on_idle=None, give_up=900):
        return self._run(["shell", command], stream=stream)

    def push(self, local_or_bytes, remote, mode=0o100644):
        path = local_or_bytes
        if isinstance(local_or_bytes, (bytes, bytearray)):
            import tempfile
            fd, path = tempfile.mkstemp()
            os.write(fd, bytes(local_or_bytes))
            os.close(fd)
        try:
            out = self._run(["push", path, remote])
            if "error" in out.lower() or "failed" in out.lower():
                raise AdbError("adb push failed: %s" % out.strip())
        finally:
            if path is not local_or_bytes:
                os.unlink(path)

    def reboot(self):
        self._run(["shell", "reboot"])

    def close(self):
        pass


# ------------------------------------------------------------------------------------------------ finding the unit
SKIP_IFACE = re.compile(r"^(lo|docker|br-|veth|virbr|tun|tap|wg|tailscale|zt|vmnet|vboxnet|lxc|lxd|podman)")


def parse_ip_addr(text):
    """(address, prefix) pairs from `ip -4 -o addr show scope global`, virtual and VPN interfaces left out."""
    found = []
    for line in text.splitlines():
        m = re.match(r"\d+:\s+(\S+)\s+inet\s+(\d+\.\d+\.\d+\.\d+)/(\d+)", line)
        if m and not SKIP_IFACE.match(m.group(1)):
            found.append((m.group(2), int(m.group(3))))
    return found


def local_networks():
    """The laptop's own IPv4 networks (as ip_network objects)."""
    found = []
    try:
        out = subprocess.run(["ip", "-4", "-o", "addr", "show", "scope", "global"], stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, universal_newlines=True, timeout=5).stdout
        found = parse_ip_addr(out)
    except (OSError, subprocess.SubprocessError):
        pass
    if not found:                       # no `ip` command: ask the routing table for the main address
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("192.0.2.1", 9))
            found.append((s.getsockname()[0], 24))
            s.close()
        except OSError:
            pass
    nets = []
    for ip, prefix in found:
        addr = ipaddress.ip_address(ip)
        if not (addr.is_private or addr.is_link_local):
            continue
        # never walk more than a /22 around the laptop's own address
        net = ipaddress.ip_network("%s/%d" % (ip, max(prefix, 22)), strict=False)
        if net not in nets:
            nets.append(net)
    return nets


def probe(host, port, timeout=0.8):
    """Returns the ADB banner of host:port, or None when nothing that speaks ADB answers there."""
    try:
        s = socket.create_connection((host, port), timeout=timeout)
        s.close()
    except OSError:
        return None
    try:
        a = NativeAdb(host, port, connect_timeout=2.0)
    except AdbAuthRequired:
        return "needs-authorisation"
    except (AdbError, OSError):
        return None
    banner = a.banner
    a.close()
    return banner


def banner_model(banner):
    m = re.search(r"ro\.product\.model=([^;]*)", banner or "")
    return m.group(1) if m else None


def expand_hosts(networks, limit=2048):
    """The addresses to probe: at most [limit] of them, and a huge network is never expanded in full."""
    hosts = []
    for net in networks:
        hosts.extend(str(h) for h in itertools.islice(net.hosts(), limit))
    return hosts[:limit]


def scan(networks, port, model, workers=160):
    """Looks for ADB units on the given networks. Returns (matches, others): lists of (address, banner)."""
    hosts = expand_hosts(networks)
    own = set()
    try:
        own = {socket.gethostbyname(socket.gethostname())}
    except OSError:
        pass
    hosts = [h for h in hosts if h not in own]
    with ThreadPoolExecutor(max_workers=workers) as ex:
        results = list(ex.map(lambda h: (h, probe(h, port)), hosts))
    matches, others = [], []
    for host, banner in results:
        if banner is None:
            continue
        bm = banner_model(banner)
        if bm == model:
            matches.append(("%s:%d" % (host, port), banner))
        elif bm is None and banner != "needs-authorisation":
            matches.append(("%s:%d" % (host, port), banner))     # no model in the banner: the property check decides
        else:
            others.append(("%s:%d" % (host, port), banner))
    return matches, others


def clean(text):
    """Text that came from another machine is printed without control characters (no terminal escape sequences)."""
    return re.sub(r"[\x00-\x1f\x7f]", "?", text)


def short_banner(banner):
    p = {}
    for part in banner.split("::", 1)[-1].split(";"):
        if "=" in part:
            k, v = part.split("=", 1)
            p[k] = v
    return clean("model %s, device %s" % (p.get("ro.product.model", "?"), p.get("ro.product.device", "?")))


# ------------------------------------------------------------------------------------------------ the installer
class Installer:
    def __init__(self, args):
        self.args = args
        self.expect = args.expect_model
        self.port = args.port
        self.device = args.device
        self.unit = None

    # -- helpers
    def prop(self, name):
        return self.unit.shell("getprop " + name).strip()

    def remote_size(self, path):
        out = self.unit.shell("wc -c < %s" % path).strip()
        return int(out) if out.isdigit() else -1

    def remote_sha(self, path):
        out = self.unit.shell("sha256sum %s 2>&1" % path).strip().split()
        return out[0] if out else ""

    # -- connecting
    def connect(self):
        a = self.args
        if a.system_adb:
            adb = a.adb or shutil.which("adb")
            if not adb:
                die("adb was not found (sudo apt install adb, or give its path with --adb /path/to/adb)")
            serial = a.serial or (a.ip if a.ip else None)
            if not serial:
                die("with --system-adb give the unit with --ip or --serial")
            if ":" not in serial and a.ip:
                serial = "%s:%d" % (serial, self.port)
            say(t("connecting", addr=serial))
            try:
                self.unit = SystemAdb(adb, serial)
            except AdbError as ex:
                die(str(ex))
            return
        addr = self.find_unit() if not a.ip else self.parse_ip(a.ip)
        host, port = addr
        say(t("connecting", addr="%s:%d" % (host, port)))
        try:
            self.unit = NativeAdb(host, port)
        except AdbAuthRequired:
            # A K2501 never asks for authorisation, and the system adb would offer this laptop's key to the device (which
            # pops up "Allow USB debugging?" on a TV, say): only ever done when the user explicitly asks with --system-adb.
            die(t("auth"))
        except (AdbError, OSError) as ex:
            die(t("unreachable", addr="%s:%d" % (host, port), why=ex))

    def parse_ip(self, text):
        try:
            if ":" in text:
                h, p = text.rsplit(":", 1)
                return h, int(p)
            return text, self.port
        except ValueError:
            die(t("bad_addr", text=text))

    def find_unit(self, only_list=False):
        a = self.args
        if a.scan:
            try:
                nets = [ipaddress.ip_network(c, strict=False) for c in a.scan]
            except ValueError:
                die(t("bad_addr", text=" ".join(a.scan)))
        else:
            nets = local_networks()
        if not nets:
            die(t("none", model=self.expect, action=a.command))
        say(t("search", nets=", ".join(str(n) for n in nets)), "cyan")
        matches, others = scan(nets, self.port, self.expect)
        for addr, banner in others:
            if banner == "needs-authorisation":
                say("  " + t("skipped_auth", addr=addr), "yellow")
            else:
                say("  " + t("skipped", addr=addr, model=self.expect), "gray")
        if not matches:
            die(t("none", model=self.expect, action=a.command))
        for addr, banner in matches:
            say("  " + t("found", addr=addr, banner=short_banner(banner)), "green")
        if only_list:
            return None
        if len(matches) > 1:
            if not sys.stdin.isatty():
                die(t("several", model=self.expect))
            say(t("several", model=self.expect), "yellow")
            for i, (addr, banner) in enumerate(matches, 1):
                say("   %d) %s  (%s)" % (i, addr, short_banner(banner)))
            try:
                pick = int(input(t("choose")))
                if not 1 <= pick <= len(matches):
                    raise ValueError
                addr = matches[pick - 1][0]
            except (ValueError, EOFError):
                die(t("cancelled"))
        else:
            addr = matches[0][0]
        host, port = addr.rsplit(":", 1)
        return host, int(port)

    # -- checks
    def check_unit(self):
        # who is it? (property reads only; nothing is written and no root is needed for this)
        model = self.prop("ro.product.system.model")
        nwd = self.prop("ro.nwd.platform.name")
        build = self.prop("ro.build.display.id")
        mfr = self.prop("ro.product.system.manufacturer")
        say()
        say(t("device", model=clean(model), nwd=clean(nwd), mfr=clean(mfr)), "cyan")
        say(t("build", build=clean(build)), "cyan")
        if model != self.expect and nwd != self.expect:
            die(t("not_model", expect=self.expect, model=clean(model)))
        who = self.unit.shell("id").strip().splitlines()[:1]
        who = who[0] if who else ""
        if "uid=0" not in who:
            die(t("no_root", who=clean(who)))
        self.rom_ver = self.prop("ro.aura.rom.version")
        home = self.prop("persist.nwd.launcher.default")
        path = self.unit.shell("pm path com.abdllh.aura 2>&1").strip().replace("\n", " ")
        if self.rom_ver:
            say(t("run_rom", ver=clean(self.rom_ver), home=clean(home), path=clean(path)), "cyan")
        else:
            say(t("run_none", home=clean(home)), "cyan")

    # -- the pack on the unit
    def push_pack(self):
        for f in PACK_FILES:
            if not os.path.isfile(os.path.join(PACK, f)):
                die(t("incomplete", path=os.path.join(PACK, f)))
        if not os.path.isfile(FLASH_SH):
            die(t("no_script"))
        busy = self.unit.shell("pgrep -f '[a]ura-flash.sh'").strip()
        if re.search(r"\d", busy):
            die(t("busy", pid=" ".join(busy.split()), log=REMOTE + "/flash.log"))
        say()
        say(t("copying"))
        self.unit.shell("mkdir -p %s; rm -rf %s/patch %s/aura-flash.sh %s/follow.sh %s/*.clean" % ((REMOTE,) * 5))
        self.unit.shell("mkdir -p %s/patch" % REMOTE)
        script = open(FLASH_SH, "rb").read().replace(b"\r\n", b"\n")
        items = [(os.path.join(PACK, f), "%s/patch/%s" % (REMOTE, f), open(os.path.join(PACK, f), "rb").read()) for f in PACK_FILES]
        items.append((FLASH_SH, REMOTE + "/aura-flash.sh", script))
        for local, remote, data in items:
            name = os.path.basename(remote)
            try:
                self.unit.push(data, remote)
            except AdbError as ex:
                die(t("copy_failed", name=name, detail=ex))
            got = self.remote_size(remote)
            if got != len(data):
                die(t("copy_failed", name=name, detail="%d / %d bytes" % (got, len(data))))
            if self.remote_sha(remote) != hashlib.sha256(data).hexdigest():
                die(t("copy_failed", name=name, detail="checksum"))
        self.pack_mb = round(os.path.getsize(os.path.join(PACK, "forward.bin")) / 1048576.0, 1)

    def flash(self, mode, echo=True):
        lines = []
        pending = [""]

        def feed(piece):
            pending[0] += piece
            while "\n" in pending[0]:
                line, pending[0] = pending[0].split("\n", 1)
                line = line.rstrip("\r")
                lines.append(line)
                if echo:
                    say("  " + line)

        def idle():
            if echo:
                say(".", end="")

        try:
            self.unit.shell("sh %s/aura-flash.sh %s %s/patch %s" % (REMOTE, mode, REMOTE, self.device), stream=feed, on_idle=idle)
        except AdbError as ex:
            if pending[0].strip():
                feed("\n")
            say()
            say(t("lost", why=ex), "red")
        if pending[0].strip():
            feed("\n")
        return lines

    # -- the whole run
    def run(self):
        a = self.args
        action = a.command
        if action == "scan":
            self.find_unit(only_list=True)
            return 0
        self.connect()
        self.check_unit()
        self.push_pack()

        say()
        say(t("reading"))
        st = self.flash("status")
        state_line = next((l for l in st if l.startswith("STATE:")), None)
        code_line = next((l for l in st if l.startswith("STATE_CODE=")), None)
        if not state_line or not code_line:
            die(t("no_state"))
        code = code_line.split("=", 1)[1].strip()

        if action == "status":
            say()
            say(state_line, "yellow" if code in ("PARTIAL", "UNKNOWN") else "green")
            if code == "AURA" and not self.rom_ver:
                say(t("hint_restart"), "yellow")
            if code == "STOCK" and self.rom_ver:
                say(t("hint_restart_stock"), "yellow")
            return 0

        pending = False
        if action == "install":
            if code == "AURA" and self.rom_ver:
                say()
                say(t("already_running"), "green")
                return 0
            if code == "AURA":
                pending = True
                say()
                say(t("pending_install"), "yellow")
            elif code not in ("STOCK", "PARTIAL"):
                die(t("bad_install"))
        else:   # restore
            if code == "STOCK" and not self.rom_ver:
                say()
                say(t("already_stock"), "green")
                return 0
            if code == "STOCK":
                pending = True
                say()
                say(t("pending_restore"), "yellow")
            elif code not in ("AURA", "PARTIAL"):
                die(t("bad_restore"))
        if code == "PARTIAL":
            say()
            say(state_line, "yellow")

        say()
        if pending:
            say(t("ready_finish"), "yellow")
        elif action == "install":
            say(t("ready_install"), "yellow")
            say(t("ready_install_1", mb=self.pack_mb), "yellow")
            say(t("ready_install_2"), "yellow")
            say(t("ready_install_3"), "yellow")
        else:
            say(t("ready_restore"), "yellow")
        say(t("keep_power"), "yellow")
        if not a.yes:
            try:
                answer = input(t("type_yes"))
            except EOFError:
                answer = ""
            if answer.strip() != "YES":
                die(t("cancelled"))

        if not pending:
            say()
            say(t("writing"), "cyan")
            out = self.flash("apply" if action == "install" else "revert")
            if not any(l.startswith("RESULT: OK") for l in out):
                say()
                for k in ("not_ok_1",):
                    say(t(k), "red")
                for k in ("not_ok_2", "not_ok_3", "not_ok_4"):
                    say(t(k), "yellow")
                say(t("not_ok_5", log=REMOTE + "/flash.log"), "yellow")
                die(t("not_done", action=action))

        # the follow-up step, pushed as a file (no quoting games through shells)
        if action == "install":
            follow = ("setprop persist.aura.disabled 0\n"
                      "rm -f /data/aura_disabled /data/data/com.abdllh.aura/files/disable_home\n"
                      "echo prepared\n")
            expect = "prepared"
        else:
            # also undo what Aura changed outside the system partition: the firmware config files it edited (kept as
            # *.pre-aura: the boot-time registration and the factory ACC-off mode), its music replacing the stock music
            # app, the hidden status bar, the phone link (ZLink) kept off and the stock volume bar turned off (the stock
            # system must not inherit any of them)
            follow = ('for f in /data/nwdappconfig/app/*.pre-aura; do\n'
                      '  [ -f "$f" ] || continue\n'
                      '  cat "$f" > "${f%.pre-aura}" && rm -f "$f"\n'
                      'done\n'
                      'L=/data/nwdappconfig/app/replace_source_list.xml\n'
                      'grep -q com.abdllh.aura "$L" 2>/dev/null && rm -f "$L"\n'
                      'pm enable com.nwd.android.music.ui >/dev/null 2>&1\n'
                      'pm enable com.android.launcher/com.launcher.FloatBar >/dev/null 2>&1\n'
                      'case "$(settings get global policy_control 2>/dev/null)" in *immersive.*) '
                      'settings delete global policy_control >/dev/null 2>&1 ;; esac\n'
                      '[ "$(settings get system phone_connect_style 2>/dev/null)" = "0" ] && '
                      'settings put system phone_connect_style 3 && settings put system recheck_phone_connect_style 1\n'
                      'setprop persist.nwd.launcher.default com.android.launcher\n'
                      # an Aura updated from the screen lives in /data/app and would stay on as an ordinary app once
                      # its system copy is gone: drop it (a system-only copy refuses, and goes away at the restart);
                      # should anything of it survive, the kill switch keeps it from touching the stock system
                      'pm uninstall com.abdllh.aura >/dev/null 2>&1\n'
                      'setprop persist.aura.disabled 1\n'
                      'echo undone\n')
            expect = "undone"
        try:
            self.unit.push(follow.encode(), REMOTE + "/follow.sh")
            fo = self.unit.shell("sh %s/follow.sh" % REMOTE)
        except AdbError as ex:
            fo = ""
            say(t("lost", why=ex), "red")
        for line in fo.splitlines():
            say("  " + line)
        if not any(l.startswith(expect) for l in fo.splitlines()):
            # Not restarting is the safe answer: the unit still shows the state this step has to fix (a partition that says
            # "Aura" while the running system does not know it yet, or the other way round), so running the program again
            # takes the "finish what was written" path and repeats the step.
            say(t("follow_failed", expect=expect), "red")
            return 1

        say()
        say(t("done_finished" if pending else "done", action=action.capitalize()), "green")
        if a.no_reboot:
            say(t("no_reboot"), "yellow")
            return 0
        say(t("rebooting"), "cyan")
        before = self.uptime()
        self.unit.reboot()
        self.wait_back(action, before)
        return 0

    def uptime(self):
        try:
            return float(self.unit.shell("cat /proc/uptime").split()[0])
        except (AdbError, ValueError, IndexError):
            return None

    def wait_back(self, action, uptime_before=None):
        """After the restart command: wait for the unit to answer again as a NEW boot, then report what it runs.
        A new boot = it went away at some point, or its uptime is lower than before (the old system still shutting down
        has a higher one). If it never goes away and keeps its uptime, the restart did not happen."""
        say(t("waiting"), "gray")
        host = self.unit.addr.rsplit(":", 1)[0] if ":" in self.unit.addr else self.unit.addr
        port = getattr(self.unit, "port", self.port)        # the port the unit was actually reached on (--ip ADDR:PORT)
        went_down = saw_old = False
        start = time.monotonic()
        time.sleep(WAIT_FIRST)
        while time.monotonic() - start < WAIT_TOTAL:
            try:
                if self.args.system_adb:
                    u = SystemAdb(self.unit.adb, self.unit.serial)
                else:
                    u = NativeAdb(host, port, connect_timeout=3.0)
                self.unit = u
                booted = u.shell("getprop sys.boot_completed").strip() == "1"
                up = self.uptime()
            except (AdbError, OSError):
                went_down = True
                say(".", end="")
                time.sleep(WAIT_POLL)
                continue
            if uptime_before is not None and up is not None:
                new_boot = up < uptime_before
                saw_old = saw_old or not new_boot
            else:
                new_boot = went_down            # uptime unreadable: only trust a unit that has been seen to go away
            if booted and new_boot:
                ver = u.shell("getprop ro.aura.rom.version").strip()
                say()
                if action == "install" and ver:
                    say(t("back_rom", ver=clean(ver)), "green")
                elif action == "restore" and not ver:
                    say(t("back_stock"), "green")
                else:
                    say(t("back_unexpected"), "yellow")
                return
            say(".", end="")
            time.sleep(WAIT_POLL)
        say()
        say(t("no_restart") if (saw_old and not went_down) else t("back_timeout"), "yellow")


# ------------------------------------------------------------------------------------------------ command line
def main(argv=None):
    global LANG, EXPECT_MODEL
    ap = argparse.ArgumentParser(prog="aura-install", description="Aura ROM installer for the K2501 car head unit.")
    ap.add_argument("command", nargs="?", default="install", choices=["install", "status", "restore", "scan"],
                    help="install (default): stock -> Aura | status | restore: Aura -> stock | scan: only look for the unit")
    ap.add_argument("--ip", help="skip the search and use this address (ADDR or ADDR:PORT)")
    ap.add_argument("--scan", action="append", metavar="CIDR", help="search this network instead of the laptop's own (repeatable)")
    ap.add_argument("--port", type=int, default=PORT, help="adb port (default 5555)")
    ap.add_argument("--yes", action="store_true", help="do not ask for the YES confirmation")
    ap.add_argument("--no-reboot", action="store_true", help="do not restart the unit at the end")
    ap.add_argument("--system-adb", action="store_true",
                    help="use the adb program of the system instead of the built-in client (needs --ip or --serial)")
    ap.add_argument("--adb", metavar="PATH", help="path of the adb program (with --system-adb; default: the one in PATH)")
    ap.add_argument("--serial", help="adb serial (with --system-adb)")
    ap.add_argument("--lang", choices=["en", "ar"], help="language of the messages (default: from the system)")
    ap.add_argument("--device", default="/dev/block/mapper/system", help=argparse.SUPPRESS)
    ap.add_argument("--expect-model", default=EXPECT_MODEL, help=argparse.SUPPRESS)
    args = ap.parse_args(argv)
    LANG = args.lang or detect_lang()
    if args.expect_model != EXPECT_MODEL or args.device != "/dev/block/mapper/system":
        if os.environ.get("AURA_TEST") != "1":
            sys.exit("--expect-model and --device exist for the test-suite only (they need AURA_TEST=1).")
    inst = Installer(args)
    try:
        return inst.run()
    except Stop as ex:
        say()
        say("STOPPED: %s" % ex, "red")
        return 1
    except KeyboardInterrupt:
        say()
        say("Interrupted. If a write was in progress the unit finishes it by itself; run 'status' in two minutes.", "yellow")
        return 130
    finally:
        if inst.unit:
            inst.unit.close()


if __name__ == "__main__":
    sys.exit(main())
