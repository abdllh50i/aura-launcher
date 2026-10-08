#!/system/bin/sh
# Aura ROM integration  —  runs from init (/system/etc/init/aura.rc) after persist props are loaded and
# before the framework starts. Idempotent and quick; init wraps it in `timeout`, so it can never block boot.
#
#   aura-prepare.sh                run on the car (patches /data/nwdappconfig/app/*)
#   aura-prepare.sh --image DIR    run by the ROM builder on a mounted image root (patches DIR/system/config/app/*)
#
# Kill switch: `setprop persist.aura.disabled 1` (or touch /data/aura_disabled) gives the home role back to the stock launcher.

PKG=com.abdllh.aura
STOCK=com.android.launcher
CFG=${AURA_TEST_CFG:-/data/nwdappconfig/app}      # AURA_TEST_* are only used by the ROM test-suite
APK=${AURA_TEST_APK:-/system/priv-app/Aura/Aura.apk}
IMAGE_MODE=0

if [ "$1" = "--image" ] && [ -n "$2" ]; then
    IMAGE_MODE=1
    CFG="$2/system/config/app"
fi

log() { [ $IMAGE_MODE = 1 ] && echo "$*" || /system/bin/log -t aura-prepare "$*"; }

# patch_xml FILE MARKER LINE CLOSE_TAG
# Inserts LINE before the first line containing CLOSE_TAG unless MARKER is already present. Writes through `cat >`
# so owner, mode and SELinux label of the original file are preserved.
patch_xml() {
    f="$1"; marker="$2"; line="$3"; close="$4"
    [ -f "$f" ] || return 0
    grep -q "$marker" "$f" && return 0
    tmp="$f.aura-tmp"
    : > "$tmp" || return 1
    added=0
    while IFS= read -r l || [ -n "$l" ]; do
        if [ $added = 0 ]; then
            case "$l" in
                *"$close"*) printf '%s\n' "$line" >> "$tmp"; added=1 ;;
            esac
        fi
        printf '%s\n' "$l" >> "$tmp"
    done < "$f"
    if [ $added = 1 ] && grep -q "$marker" "$tmp"; then
        [ -f "$f.pre-aura" ] || cp "$f" "$f.pre-aura"
        cat "$tmp" > "$f" && log "patched $f"
    else
        log "skip $f (close tag not found)"
    fi
    rm -f "$tmp"
    return 0
}

if [ $IMAGE_MODE = 0 ]; then
    # ---- kill switch: hand home back to the stock launcher -------------------------------------------------
    if [ "$(getprop persist.aura.disabled)" = "1" ] || [ -e /data/aura_disabled ] || [ -e /data/data/$PKG/files/disable_home ]; then
        [ "$(getprop persist.nwd.launcher.default)" = "$PKG" ] && setprop persist.nwd.launcher.default $STOCK
        log "disabled -> stock launcher"
        exit 0
    fi
    # ---- default home app = Aura (only when it is really installed in /system) ------------------------------
    if [ -f "$APK" ] && [ "$(getprop persist.nwd.launcher.default)" != "$PKG" ]; then
        setprop persist.nwd.launcher.default $PKG
        log "home -> $PKG"
    fi
fi

# ---- NWD firmware config: Aura is a trusted app, is kept alive and is not killed by "one-key clean" ------
patch_xml "$CFG/AppConfig.xml"       "Package=\"$PKG\""            "	<NwdApp Package=\"$PKG\"/>"                                   "</configList>"
patch_xml "$CFG/TaskWhitelist.xml"   "name=\"$PKG\""               "	<package id=\"120\" name=\"$PKG\"/>"                          "</packages>"
patch_xml "$CFG/OneClearConfig.xml"  "powernotkill.$PKG"          "	<ProcessItem PackName=\"powernotkill.$PKG\">
	</ProcessItem>"                                                                                                                         "</ProcessList>"
patch_xml "$CFG/FastDexOpt.xml"      "name=\"$PKG\""               "	<package id=\"99\" name=\"$PKG\" priority=\"0\"/>"             "</packages>"
exit 0
