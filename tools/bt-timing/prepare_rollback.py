"""Prepare the preceding signed release, verified against the pinned SHA-256.

Run before a 1.6.1 beta build. --apk uses a local copy; otherwise downloads the
published v1.6.0 asset. The APK is a build input and is not committed to Git.
"""
import argparse
import hashlib
from pathlib import Path
import urllib.request

SHA256 = 'ee1d60c3be1a949a9e5237856dfced6f533595d10230e1c29d7ef9ae88d95caf'
URL = 'https://github.com/abdllh50i/aura-launcher/releases/download/v1.6.0/aura-1.6.0.apk'
DEST = Path(__file__).resolve().parents[2] / 'app/src/main/assets/rollback/aura-1.6.0.apk'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path)
    args = parser.parse_args()
    if args.apk:
        data = args.apk.read_bytes()
    else:
        with urllib.request.urlopen(URL, timeout=90) as response:
            data = response.read(40 * 1024 * 1024)
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise ValueError('The backup does not match the signed v1.6.0 release')
    DEST.parent.mkdir(parents=True, exist_ok=True)
    staged = DEST.with_suffix('.apk.part')
    staged.write_bytes(data)
    staged.replace(DEST)
    print('Verified offline rollback backup:', DEST)

if __name__ == '__main__':
    main()
