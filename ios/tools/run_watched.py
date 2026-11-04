"""Runs a command on a pseudo-terminal, so nothing it prints waits in a pipe's buffer, copies what it says here and to a file, and
gives up when it has been silent too long, saying where whatever it names is stuck. One CI run sat 20 minutes in `swift test` with an
empty log: this is so a hang costs two minutes and names its test.

    python3 tools/run_watched.py --log kit-tests.txt --silence 120 --total 480 --stacks xctest -- swift test --package-path ios/AMRIKit
"""
import argparse
import os
import pty
import select
import signal
import subprocess
import sys
import time


def stacks(name):
    """Best effort: a hung test's stacks, for the log."""
    try:
        pids = subprocess.run(["pgrep", "-x", name], capture_output=True, text=True, timeout=10).stdout.split()
    except Exception as error:
        print("pgrep:", error, flush=True)
        return
    for pid in pids[:2]:
        print(f"--- stacks of {name} {pid}", flush=True)
        try:
            done = subprocess.run(["sample", pid, "3", "-mayDie"], capture_output=True, text=True, timeout=40)
            print((done.stdout or done.stderr)[:12000], flush=True)
        except Exception as error:
            print("sample:", error, flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", required=True)
    parser.add_argument("--silence", type=int, default=120)
    parser.add_argument("--total", type=int, default=600)
    parser.add_argument("--stacks", default="")
    parser.add_argument("command", nargs="+")
    args = parser.parse_args()

    master, slave = pty.openpty()
    process = subprocess.Popen(args.command, stdin=subprocess.DEVNULL, stdout=slave, stderr=slave, start_new_session=True)
    os.close(slave)
    started = last = time.time()
    why = None
    with open(args.log, "ab") as log:
        while True:
            ready, _, _ = select.select([master], [], [], 5)
            if ready:
                try:
                    data = os.read(master, 65536)
                except OSError:  # the other end closed: that is how a pty says the command is done
                    data = b""
                if not data:
                    break
                last = time.time()
                log.write(data)
                log.flush()
                sys.stdout.buffer.write(data)
                sys.stdout.buffer.flush()
            now = time.time()
            if now - last > args.silence:
                why = f"silent for {args.silence} s"
            elif now - started > args.total:
                why = f"past {args.total} s"
            if why:
                break
    if why:
        print(f"\n*** {why}: giving up on {' '.join(args.command)}", flush=True)
        if args.stacks:
            stacks(args.stacks)
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except OSError:
            pass
        process.wait()
        return 124
    return process.wait()


if __name__ == "__main__":
    sys.exit(main())
