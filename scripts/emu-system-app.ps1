# Makes an APK a real system app on an emulator (/system/priv-app/Aura, plus AMRI's privapp XML), without writing to the
# system image, for tests of what an update and a way back do to a ROM copy.
#   emu-system-app.ps1 -Apk <APK with x86_64 in it> [-Serial emulator-5574]
#
# "adb remount" does not work on the android-29 image: /system has to go through overlayfs, which needs verity off, and
# "adb disable-verity" changes vbmeta while the emulator keeps passing the old androidboot.vbmeta.digest. The next boot
# then loops: "vbmeta digest mismatch ... Failed to mount /system". (Way out: emu kill, delete the AVD's system.img.qcow2.)
# So: overlayfs over the two directories, from /data, with the framework stopped; its package scan at the next start is
# the same code a boot runs. The mounts live until the emulator's next real reboot, so a reboot in a test is
# "emu-system-app.ps1 -Restart" (stop + start: a new system_server and a new scan). Do not reboot for real: the scan of a
# boot without the mounts takes the system flag away from the package.
param(
    [string]$Apk = "",
    [string]$Serial = "emulator-5574",
    [switch]$Restart
)
$adb = "E:\Android\Sdk\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
function WaitBoot() {
    foreach ($i in 1..60) {
        Start-Sleep 3
        $up = (& $adb -s $Serial shell "pidof system_server; dumpsys activity activities 2>/dev/null | grep -c mResumedActivity").Trim()
        if (($up -split "\s+").Count -ge 2 -and ($up -split "\s+")[-1] -ne "0") { return }
    }
    "the framework did not come back in 3 minutes"
}
& $adb -s $Serial root | Out-Null
Start-Sleep 2
if ($Restart) {
    & $adb -s $Serial shell "stop; sleep 1; start"
    WaitBoot
    return
}
$xml = Join-Path $root "rom\system\etc\permissions\privapp-permissions-aura.xml"
& $adb -s $Serial push $Apk /data/local/tmp/sys-aura.apk | Select-Object -Last 1
& $adb -s $Serial push $xml /data/local/tmp/sys-aura.xml | Select-Object -Last 1
& $adb -s $Serial shell @'
O=/data/sysov
setenforce 0
stop
mkdir -p $O/priv/upper/Aura $O/priv/work $O/perm/upper $O/perm/work
cp /data/local/tmp/sys-aura.apk $O/priv/upper/Aura/Aura.apk
cp /data/local/tmp/sys-aura.xml $O/perm/upper/privapp-permissions-aura.xml
chmod 755 $O $O/priv $O/priv/upper $O/priv/upper/Aura $O/perm $O/perm/upper
chmod 644 $O/priv/upper/Aura/Aura.apk $O/perm/upper/privapp-permissions-aura.xml
mount | grep -q ' /system/priv-app type overlay' || mount -t overlay overlay -o lowerdir=/system/priv-app,upperdir=$O/priv/upper,workdir=$O/priv/work /system/priv-app
mount | grep -q ' /system/etc/permissions type overlay' || mount -t overlay overlay -o lowerdir=/system/etc/permissions,upperdir=$O/perm/upper,workdir=$O/perm/work /system/etc/permissions
ls -l /system/priv-app/Aura
start
rm -f /data/local/tmp/sys-aura.apk /data/local/tmp/sys-aura.xml
'@
WaitBoot
& $adb -s $Serial shell "dumpsys package com.abdllh.aura | grep -E 'codePath=|versionCode=|versionName=| flags=|privateFlags=' | head -n 6"
