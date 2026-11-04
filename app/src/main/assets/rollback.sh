#!/system/bin/sh
# AMRI's way back. update/Rollback.kt copies this file to /data/local/tmp/amri-rollback/ as root; any root caller may
# run it from there: Settings, the guard, AMRI Rescue, a PC at the car, a phone.
#
#   sh /data/local/tmp/amri-rollback/rollback.sh          back to aura-prev.apk; if that fails, the kill switch and the
#                                                         stock launcher
#   sh /data/local/tmp/amri-rollback/rollback.sh --test   the same, but a failure leaves everything as it was
#   sh /data/local/tmp/amri-rollback/rollback.sh --stock  only the kill switch and the stock launcher (at once)
#
# Going back runs detached: replacing the package kills every process of the app, and with it whoever asked. The outcome
# is in result ("EXIT=n"), the script's own output in runner.log. --stock outlives its caller too: the firmware force-stops
# AMRI within a second of the stock launcher coming up, unless AMRI is on its list (guard/BackgroundList.kt).
#
# It never runs pm uninstall: when the ROM copy is older than the /data copy that deletes AMRI's data (docs/DEV.md).

PKG=com.abdllh.aura
STOCK=com.android.launcher
D=/data/local/tmp/amri-rollback
APK=$D/aura-prev.apk
JSON=$D/aura-prev.json
R=$D/result

# What a dead AMRI cannot give back by itself. A living one does the same through its watcher of the kill switch; both
# together are harmless. The same steps as the ROM restore's, as far as they are not AMRI's own business.
give_back() {
    pm enable com.android.launcher/com.launcher.FloatBar >/dev/null 2>&1
    am startservice -n com.android.launcher/com.launcher.FloatBar >/dev/null 2>&1
    # never during a call: it restarts the phone process. Then at its end, behind everything else: the stock launcher with
    # this window off shows no call at all, and AMRI may not be there to do it
    (
        n=0
        while [ $n -lt 720 ]; do
            case "$(settings get system key_bt_state 2>/dev/null)" in
                3|4|5) sleep 5; n=$((n + 1)) ;;
                *) pm enable com.nwd.android.phone/com.nwd.android.phone.FloatBar >/dev/null 2>&1; break ;;
            esac
        done
    ) >/dev/null 2>&1 </dev/null &
    K=/data/nwdappconfig/app/KeyRuleConfig.xml
    [ -f $K.pre-aura ] && cat $K.pre-aura > $K && rm -f $K.pre-aura
    # AMRI's ring (only while it still is AMRI's: the marker holds its MD5); the owner's own comes back
    D=$(getprop ro.nwd.internal.path); [ -n "$D" ] || D=/mnt/media_rw/sdcard
    if [ -f "$D/.aura-ring" ]; then
        [ "$(md5sum "$D/ring.mp3" 2>/dev/null | cut -d' ' -f1)" = "$(cat "$D/.aura-ring")" ] && rm -f "$D/ring.mp3"
        [ -f "$D/ring.mp3.pre-aura" ] && [ ! -f "$D/ring.mp3" ] && mv -f "$D/ring.mp3.pre-aura" "$D/ring.mp3"
        rm -f "$D/.aura-ring"
    fi
    case "$(settings get global policy_control 2>/dev/null)" in
        *immersive.*) settings delete global policy_control >/dev/null 2>&1 ;;
    esac
}

to_stock() {
    setprop persist.aura.disabled 1
    setprop persist.nwd.launcher.default $STOCK
    give_back
    am start -a android.intent.action.MAIN -c android.intent.category.HOME -p $STOCK 2>&1 | grep -q Error \
        && am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
}

case "$1" in
    --stock)
        # (whoever asked may be force-stopped halfway: adbd then hangs up on this shell, which goes on)
        trap '' HUP PIPE
        to_stock
        echo stock-done
        exit 0
        ;;
    --run)
        shift
        ;;
    *)
        [ "$(id -u)" = 0 ] || { echo "needs root"; exit 1; }
        L=$(cat $D/running 2>/dev/null)
        if [ -n "$L" ] && grep -q rollback.sh /proc/$L/cmdline 2>/dev/null; then
            echo already-running
            exit 0
        fi
        rm -f $R $D/running
        S=
        command -v setsid >/dev/null 2>&1 && S=setsid
        # adbd hangs up on the whole process group the moment this shell ends, and the child may not have reached nohup
        # by then (seen on a loaded emulator: "started", and then nothing): the child is born ignoring it, and this shell
        # only ends once the child has said it is up
        trap '' HUP PIPE
        nohup $S sh $D/rollback.sh --run "$@" > $D/runner.log 2>&1 < /dev/null &
        n=0
        while [ ! -f $D/running ] && [ ! -f $R ] && [ $n -lt 100 ]; do
            sleep 0.1
            n=$((n + 1))
        done
        if [ -f $D/running ] || [ -f $R ]; then echo started; else echo failed-to-start; fi
        exit 0
        ;;
esac

# ---- detached from here on: the caller is gone or about to be
trap '' HUP PIPE
echo $$ > $D/running
MODE=back
[ "$1" = "--test" ] && MODE=test

note() { echo "$*" >> $R; }
installed() { dumpsys package $PKG 2>/dev/null | grep -E 'versionCode=|versionName=' | head -n 2 | tr -s ' ' | tr '\n' ' '; }

failed() {
    if [ $MODE = test ]; then
        setprop persist.aura.disabled "${was:-0}"
    else
        to_stock
    fi
    note "HOME=$(getprop persist.nwd.launcher.default) DISABLED=$(getprop persist.aura.disabled)"
    note DONE
    rm -f $D/running
    exit 1
}

note "MODE=$MODE"
note "FROM=$(installed)"
want=$(sed -n 's/.*"sha256"[^"]*"\([0-9a-f]*\)".*/\1/p' $JSON 2>/dev/null)
ver=$(sed -n 's/.*"version"[^"]*"\([^"]*\)".*/\1/p' $JSON 2>/dev/null)
note "TO=$ver"
was=$(getprop persist.aura.disabled)

have=$(sha256sum $APK 2>/dev/null | cut -d' ' -f1)
if [ -z "$want" ] || [ "$want" != "$have" ]; then
    note "the saved copy does not match its hash: want=$want have=$have"
    note "EXIT=99"
    failed
fi

# the new process must not find the switch on, and so read the home screen as not its own
setprop persist.aura.disabled 0
rm -f /data/data/$PKG/files/disable_home /data/aura_disabled

T=
command -v timeout >/dev/null 2>&1 && T="timeout -k 5 300"
$T pm install -r -d $APK >> $R 2>&1
code=$?
note "EXIT=$code"
[ "$code" = 0 ] || failed

note "NOW=$(installed)"
sync
# (again: a dying AMRI's crash guard may have switched it on while the install ran)
setprop persist.aura.disabled 0
rm -f /data/data/$PKG/files/disable_home /data/aura_disabled
# the guard counts the version left behind anew, should it come back in this boot (an older version leaves no marks of its own)
M=/data/data/$PKG/files/guard-marks
[ -f $M ] && echo "R $(cut -c1-8 /proc/sys/kernel/random/boot_id) 0 0" >> $M
setprop persist.nwd.launcher.default $PKG
am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
note DONE
rm -f $D/running
exit 0
