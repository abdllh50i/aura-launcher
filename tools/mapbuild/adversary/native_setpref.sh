#!/system/bin/sh
# On the emulator, as root: the app's prefs from the backup, with the tile template pointing at the PC and the language and theme asked for.
#   native_setpref.sh <port> <lang> <theme>
P=/data/data/com.abdllh.aura/shared_prefs/aura.xml
B=/data/local/tmp/aura.xml.adversary-backup
cat "$B" > "$P"
sed -i '/name="ofmTileTemplate"/d; /name="ofmTileTemplateAt"/d; /name="lang"/d; /name="theme"/d' "$P"
NOW="$(date +%s)000"
sed -i "s#</map>#    <string name=\"ofmTileTemplate\">http://127.0.0.1:$1/base/{z}/{x}/{y}.pbf</string>\n    <long name=\"ofmTileTemplateAt\" value=\"$NOW\" />\n    <string name=\"lang\">$2</string>\n    <string name=\"theme\">$3</string>\n</map>#" "$P"
grep -E 'name="(ofmTileTemplate|ofmTileTemplateAt|lang|theme)"' "$P"
