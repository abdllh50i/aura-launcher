# The real renderer: the debug app's MapLibre Native 10.3.7 on an x86_64 emulator, with the app's real style, reading the pack's tiles.
# No app code changes: the app's tile server takes tiles it does not hold from its saved template (pref ofmTileTemplate), which is pointed
# at serve_logged.py through adb reverse for the length of the test, and put back with -Restore. Take the emulator's lock first.
#   & native_shots.ps1 -Serial emulator-5578 -Out <folder> [-Port 8802] [-Only name,name]
#   & native_shots.ps1 -Serial emulator-5578 -Restore
param([Parameter(Mandatory)][string]$Serial, [string]$Out, [int]$Port = 8802, [string[]]$Only, [switch]$Restore)
$ErrorActionPreference = 'Continue'
$prefs = '/data/data/com.abdllh.aura/shared_prefs/aura.xml'
$backup = '/data/local/tmp/aura.xml.adversary-backup'
function Sh([string]$c) { adb -s $Serial shell $c }
if ($Restore) {
    Sh "am force-stop com.abdllh.aura; cat $backup > $prefs; rm -f $backup /data/local/tmp/native_setpref.sh /data/local/tmp/adv.png /data/data/com.abdllh.aura/files/mbgl-offline.db*"
    adb -s $Serial reverse --remove "tcp:$Port" | Out-Null
    "restored"
    return
}
New-Item -ItemType Directory -Force $Out | Out-Null
Sh "am force-stop com.abdllh.aura; [ -f $backup ] || cp $prefs $backup"
adb -s $Serial reverse "tcp:$Port" "tcp:$Port" | Out-Null
adb -s $Serial push "$PSScriptRoot\native_setpref.sh" /data/local/tmp/native_setpref.sh | Out-Null
# lang, theme, name, lon, lat, zoom steps from the app's start zoom: + is a tap on the plus button, - on the minus
$shots = @(
    @('ar', 'light', 'khobar', 50.2158, 26.2945, 0), @('ar', 'light', 'dammam', 50.0888, 26.4207, 0), @('ar', 'light', 'jubail', 49.6586, 27.0046, 0),
    @('ar', 'light', 'riyadh', 46.6745, 24.7113, 0), @('ar', 'light', 'mecca', 39.826, 21.4225, 0), @('ar', 'light', 'jeddah', 39.11, 21.6, 0),
    @('ar', 'light', 'abha', 42.505, 18.216, 0), @('ar', 'light', 'causeway', 50.33, 26.185, 0), @('ar', 'light', 'rub', 50.5, 20.5, 0),
    @('ar', 'light', 'khobar-out', 50.2158, 26.2945, -3), @('ar', 'light', 'gulf-out', 50.2158, 26.2945, -6), @('ar', 'light', 'gulf-far', 50.2158, 26.2945, -8),
    @('ar', 'light', 'no-row', 51.58081, 25.195, 0),
    @('ar', 'dark', 'khobar', 50.2158, 26.2945, 0), @('ar', 'dark', 'riyadh', 46.6745, 24.7113, 0), @('ar', 'dark', 'riyadh-close', 46.6745, 24.7113, 2),
    @('en', 'light', 'khobar', 50.2158, 26.2945, 0), @('en', 'light', 'riyadh', 46.6745, 24.7113, 0), @('en', 'light', 'abha', 42.505, 18.216, 0), @('en', 'dark', 'jazan', 42.56, 16.89, -1)
)
$last = ''
foreach ($s in $shots) {
    $lang, $theme, $name, $lon, $lat, $steps = $s
    $key = "$lang-$theme"
    $file = "$key-$name"
    if ($Only -and ($Only -notcontains $file)) { continue }
    Sh "input keyevent 4" | Out-Null
    adb -s $Serial emu geo fix $lon $lat | Out-Null
    if ($key -ne $last) {
        Sh "am force-stop com.abdllh.aura; rm -f /data/data/com.abdllh.aura/files/mbgl-offline.db*" | Out-Null
        Sh "sh /data/local/tmp/native_setpref.sh $Port $lang $theme"
        $last = $key
    }
    Sh "logcat -c" | Out-Null
    Sh "am force-stop com.abdllh.aura" | Out-Null
    Sh "am start -n com.abdllh.aura/.nav.MapsActivity" | Out-Null
    Start-Sleep -Seconds 9
    adb -s $Serial emu geo fix $lon $lat | Out-Null
    $bx = if ($lang -eq 'ar') { 48 } else { 975 } # the zoom buttons change sides with the language
    for ($i = 0; $i -lt [math]::Abs($steps); $i++) {
        if ($steps -gt 0) { Sh "input tap $bx 158" | Out-Null } else { Sh "input tap $bx 222" | Out-Null }
        Start-Sleep -Milliseconds 900
    }
    Start-Sleep -Seconds 7
    Sh "screencap -p /data/local/tmp/adv.png" | Out-Null
    adb -s $Serial pull /data/local/tmp/adv.png "$Out\$file.png" | Out-Null
    $bad = (Sh "logcat -d | grep -E 'Mbgl|mbgl|MapGuard|FATAL|signal 11|tombstone|No Content|Failed' | grep -vE 'HttpRequest.*(successful|expected)|ConnectivityReceiver|EGLConfig|GPU Identifier' | tail -5")
    "$file"
    if ($bad) { $bad | ForEach-Object { "   $_" } }
}
