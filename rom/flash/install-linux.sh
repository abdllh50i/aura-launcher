#!/bin/sh
# Aura ROM installer for Ubuntu / Linux: finds the car unit on the network and installs the Aura ROM.
#   ./install-linux.sh              find the unit, show what it has, install
#   ./install-linux.sh status       only show what is installed (writes nothing)
#   ./install-linux.sh restore      put the stock firmware back
# (the same as: python3 aura-install.py [status|restore])
cd "$(dirname "$0")" || exit 1
if ! command -v python3 >/dev/null 2>&1; then
    echo "Python 3 is needed (it is normally part of Ubuntu):  sudo apt install python3"
    exit 1
fi
exec python3 aura-install.py "$@"
