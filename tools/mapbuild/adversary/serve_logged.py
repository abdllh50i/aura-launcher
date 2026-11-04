#!/usr/bin/env python
"""serve_tiles.py with a log of every request, to prove which tiles a client really read.

  python -I serve_logged.py <log file> --port 8802 base=<base.mbtiles>
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import serve_tiles  # noqa: E402

LOG = sys.argv.pop(1)


def log_message(self, fmt, *args):
    with open(LOG, "a", encoding="utf8") as f:
        f.write(f"{time.strftime('%H:%M:%S')} {self.requestline} {args[1] if len(args) > 1 else ''}\n")


serve_tiles.Handler.log_message = log_message
serve_tiles.main()
