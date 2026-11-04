#!/system/bin/sh
# A stand-in for the firmware's NwdBackgroundManagerService, for the emulator, which has no such thing. It does what the
# service does (framework.jar, class android.os.NwdBackgroundManagerService): when the app in front changes, 500 ms later,
# force-stop a package with a running service that is not on the white list, not the home property and not the top task.
# And what the firmware's home property does: the home screen follows it (Launcher3 standing in for the stock launcher). And
# its refusal to start a service of a package off the list and not in front (AMS.startService, skipService): the guard's
# service is disabled while that is so, or the notification listener, which Android rebinds after a force-stop, would
# bring the process back, and the guard with it.
#
#   adb push scripts/fake_bms.sh /data/local/tmp/fake_bms.sh
#   adb shell "nohup sh /data/local/tmp/fake_bms.sh > /data/local/tmp/fake_bms.log 2>&1 &"       stop: rm /data/local/tmp/fake_bms.run
#
# Only AMRI is watched, and the list is read the way the service reads it now. A test tool: never for a real unit.
PKG=com.abdllh.aura
STOCK=com.android.launcher3/.Launcher
RUN=/data/local/tmp/fake_bms.run
touch $RUN
top() { dumpsys activity activities | grep -m1 mResumedActivity | sed 's/.* u0 //; s/\/.*//'; }
# (on this emulator Android forgets the default home when the home app crashes, and a chooser comes up. On the unit the
# property alone decides, at once)
follow() { if [ "$(getprop persist.nwd.launcher.default)" = $PKG ]; then cmd package set-home-activity $PKG/.home.HomeActivity >/dev/null; else cmd package set-home-activity $STOCK >/dev/null; fi; }
listed() {
    [ "$(getprop persist.nwd.launcher.default)" = $PKG ] && return 0
    case "$(settings get system whitelist_packagename)" in *$PKG*) return 0 ;; esac
    return 1
}
refused=0
refuse() {
    if listed; then
        [ $refused = 1 ] && { refused=0; pm enable $PKG/.guard.GuardService >/dev/null; echo "$(date +%T) guard may start"; }
    else
        [ $refused = 0 ] && { refused=1; pm disable $PKG/.guard.GuardService >/dev/null; echo "$(date +%T) guard may not start"; }
    fi
}
pm enable $PKG/.guard.GuardService >/dev/null
home=$(getprop persist.nwd.launcher.default)
last=$(top)
echo "up, in front: $last, home: $home"
while [ -f $RUN ]; do
    h=$(getprop persist.nwd.launcher.default)
    if [ "$h" != "$home" ]; then
        home=$h
        echo "$(date +%T) home: $h"
    fi
    follow
    refuse
    now=$(top)
    if [ "$now" != "$last" ]; then
        last=$now
        echo "$(date +%T) in front: $now"
        sleep 0.5
        if ! listed && [ "$(top)" != $PKG ] && dumpsys activity services $PKG | grep -q ServiceRecord; then
            echo "$(date +%T) force-stop $PKG"
            follow
            am force-stop $PKG
        fi
    fi
    sleep 0.2
done
pm enable $PKG/.guard.GuardService >/dev/null
echo "stopped"
