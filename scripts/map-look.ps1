# The map on the real renderer, at the cameras that matter: screenshots of the debug app's MapLookActivity (MapStyle, TileServer, the pack, MapLibre Native).
#   map-look.ps1 -Serial emulator-5578 -Out <folder> [-Lang ar,en] [-Theme light,dark] [-Only name,name] [-Pack name]
# The pack is what lies in files/maps2/<Pack> on the emulator (setprop debug.aura.pack). Take the emulator's lock first; it stays offline for the length of the shots
# (-KeepNet not to touch the network). What it logs: style warnings, the server's read times, what MapLibre asked for that failed.
param([Parameter(Mandatory)][string]$Serial, [Parameter(Mandatory)][string]$Out, [string[]]$Lang = @('ar'), [string[]]$Theme = @('light', 'dark'), [string[]]$Only, [string]$Pack = 'style-test', [switch]$KeepNet)
$ErrorActionPreference = 'Continue'
$Lang = $Lang -split ','; $Theme = $Theme -split ','; if ($Only) { $Only = $Only -split ',' } # (powershell -File passes a list as one word)
$adb = 'E:\Android\Sdk\platform-tools\adb.exe'
function Sh([string]$c) { & $adb -s $Serial shell $c }
New-Item -ItemType Directory -Force $Out | Out-Null
$act = 'com.abdllh.aura/.debug.MapLookActivity'
$cast = 'am broadcast -a com.abdllh.aura.debug.MAPLOOK'
# name, lat, lon, zoom, tilt, bearing, top, right, bottom, left of padding
$shots = @(
    @('khobar-z13', 26.2945, 50.2050, 13, 0, 0), @('khobar-z14', 26.2945, 50.2050, 14, 0, 0), @('khobar-z146', 26.2945, 50.2190, 14.6, 0, 0, 40, 0, 80, 378),
    @('khobar-z15-tilt', 26.3000, 50.2020, 15.2, 50, 335, 300, 0, 80, 378), @('khobar-z16-tilt', 26.2930, 50.2130, 16.4, 50, 20, 300, 0, 80, 378), @('khobar-z17', 26.2930, 50.2130, 17.2, 44, 20, 240, 0, 80, 378),
    @('jubail-z13', 27.0046, 49.6586, 13, 0, 0), @('jubail-z14', 27.0100, 49.6500, 14, 0, 0), @('jubail-z15-tilt', 27.0046, 49.6586, 15.2, 50, 300, 300, 0, 80, 378), @('jubail-z16', 27.0046, 49.6586, 16, 0, 0),
    @('motorway', 26.3515, 50.1921, 15.2, 50, 335, 300, 0, 80, 378), @('interchange', 26.3070, 50.1540, 14.6, 0, 0), @('roundabout', 26.2810, 50.2070, 17.2, 40, 0, 270, 0, 80, 378),
    @('causeway-z11', 26.1850, 50.3700, 11, 0, 0), @('causeway-z14', 26.1860, 50.3340, 14, 0, 0), @('sea-z9', 26.7000, 50.6000, 9, 0, 0),
    @('makkah-z14', 21.4225, 39.8262, 14, 0, 0), @('makkah-z10', 21.4200, 39.8300, 10, 0, 0), @('reserve-z9', 27.7000, 44.0000, 9, 0, 0), @('reserve-z12', 25.2000, 47.2000, 12, 0, 0),
    @('desert-z12', 20.5000, 50.5000, 12, 0, 0), @('desert-z7', 21.0000, 50.0000, 7, 0, 0), @('wadi-z10', 24.3000, 44.0000, 10, 0, 0), @('farms-z11', 26.1000, 43.9000, 11, 0, 0),
    @('ksa-z5', 24.2000, 45.0000, 5, 0, 0), @('ksa-z6', 26.5000, 48.5000, 6, 0, 0), @('east-z7', 26.5000, 49.5000, 7, 0, 0), @('east-z8', 26.5000, 50.0000, 8, 0, 0), @('riyadh-z10', 24.7136, 46.6753, 10, 0, 0),
    @('riyadh-z14', 24.7000, 46.6900, 14, 0, 0), @('jeddah-z13', 21.5400, 39.1700, 13, 0, 0), @('redsea-z7', 21.5000, 38.5000, 7, 0, 0),
    @('south-z5', 18.0000, 45.0000, 5, 0, 0), @('south-z7', 16.5000, 41.5000, 7, 0, 0), @('world-z4', 24.0000, 45.0000, 4, 0, 0), @('kuwait-z8', 29.3000, 48.0000, 8, 0, 0), @('manama-z14', 26.2235, 50.5876, 14, 0, 0),
    @('corniche-z15-tilt', 26.2944, 50.2222, 15.2, 50, 335, 300, 0, 80, 378), @('east-edge-z6', 25.5000, 56.0000, 6, 0, 0), @('east-edge-z8', 26.2000, 56.2000, 8, 0, 0), @('south-edge-z6', 16.0000, 44.0000, 6, 0, 0), @('south-edge-z8', 16.0000, 41.8000, 8, 0, 0), @('north-edge-z6', 33.0000, 36.0000, 6, 0, 0), @('ksa-z7', 24.0000, 45.0000, 7, 0, 0), @('ksa-z8', 24.7000, 46.7000, 8, 0, 0)
)
if (-not $KeepNet) { Sh 'svc data disable; svc wifi disable' | Out-Null }
& $adb -s $Serial shell "setprop debug.aura.pack $Pack" | Out-Null
foreach ($l in $Lang) { foreach ($t in $Theme) {
    Sh "logcat -c" | Out-Null
    Sh "am start -S -W -n $act --es theme $t --es lang $l" | Out-Null
    Start-Sleep -Seconds 6
    foreach ($s in $shots) {
        $name = $s[0]
        if ($Only -and ($Only -notcontains $name)) { continue }
        $file = "$l-$t-$name"
        $cam = ($s[1..($s.Length - 1)] | ForEach-Object { ([double]$_).ToString([Globalization.CultureInfo]::InvariantCulture) }) -join ','
        Sh "logcat -c -b main" | Out-Null
        $sw = [Diagnostics.Stopwatch]::StartNew()
        Sh "$cast --es cam $cam" | Out-Null
        $seen = $false
        while ($sw.Elapsed.TotalSeconds -lt 15) {
            Start-Sleep -Milliseconds 400
            if (Sh "logcat -d -s AuraMapLook:I | grep -c drawn" | Where-Object { [int]$_ -gt 0 }) { $seen = $true; break }
        }
        $took = [int]$sw.Elapsed.TotalSeconds
        Start-Sleep -Milliseconds 500 # the labels' fade
        Sh "screencap -p /data/local/tmp/look.png" | Out-Null
        & $adb -s $Serial pull /data/local/tmp/look.png "$Out\$file.png" 2>$null | Out-Null
        Sh "$cast --ez stats true" | Out-Null
        Start-Sleep -Milliseconds 150
        $stats = (Sh "logcat -d -s AuraMapLook:I | grep 'pack:' | tail -1") -replace '.*pack: ', ''
        $bad = (Sh "logcat -d | grep -E 'Mbgl|mbgl-|AuraPack|FATAL|signal 11|Style|Failed' | grep -vE 'HttpRequest.*(successful|expected)|ConnectivityReceiver|EGLConfig|GPU Identifier|ViewRootImpl' | tail -4")
        "$file  $(if ($seen) { 'drawn' } else { 'NOT DRAWN' }) ${took}s  $stats"
        if ($bad) { $bad | ForEach-Object { "   $_" } }
    }
} }
if (-not $KeepNet) { Sh 'svc data enable; svc wifi enable' | Out-Null }
