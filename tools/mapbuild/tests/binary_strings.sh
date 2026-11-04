#!/bin/bash
# Which OSRM manoeuvre words does this Valhalla build hold? Run inside the image: tests/binary_strings.sh
BIN=$(command -v valhalla_service)
FILES="$BIN"
for f in $(ldd "$BIN" 2>/dev/null | awk '/valhalla/ {print $3}'); do FILES="$FILES $f"; done
echo "files: $FILES"
for w in "exit rotary" "exit roundabout" "roundabout turn" "end of road" "new name" "on ramp" "off ramp" "notification" "use lane" "rotary" "roundabout" "merge" "fork" "turn" "continue" "depart" "arrive" "sharp left" "sharp right" "slight left" "slight right" "uturn" "straight" "ferry" "pushing bike" "train"; do
  n=0
  for f in $FILES; do c=$(grep -a -c -F "$w" "$f"); n=$((n + c)); done
  echo "$w: $n"
done
