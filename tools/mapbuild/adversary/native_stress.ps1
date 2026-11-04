# Opens and closes the real map again and again and counts crashes: first on the pack's tiles, or on OpenFreeMap's with -Upstream. Take the emulator's lock first.
#   & native_stress.ps1 -Serial emulator-5578 -Cycles 15 [-Port 8802] [-Upstream] [-OnlyRiyadh]
param([Parameter(Mandatory)][string]$Serial, [int]$Cycles = 15, [int]$Port = 8802, [switch]$Upstream)
$prefs = '/data/data/com.abdllh.aura/shared_prefs/aura.xml'
function Sh([string]$c) { adb -s $Serial shell $c }
$places = @(@(46.6745, 24.7113), @(46.595, 24.74), @(39.826, 21.4225), @(50.2158, 26.2945), @(39.17, 21.54))
$before = @((Sh "ls /data/tombstones/")).Count
Sh "am force-stop com.abdllh.aura; rm -f /data/data/com.abdllh.aura/files/mbgl-offline.db*" | Out-Null
if (-not $Upstream) { Sh "sh /data/local/tmp/native_setpref.sh $Port ar light" | Out-Null }
else { Sh "cat /data/local/tmp/aura.xml.adversary-backup > $prefs" | Out-Null }
$crashes = 0
for ($i = 0; $i -lt $Cycles; $i++) {
    $p = $places[$i % $places.Count]
    adb -s $Serial emu geo fix $p[0] $p[1] | Out-Null
    Sh "logcat -c" | Out-Null
    Sh "am start -n com.abdllh.aura/.nav.MapsActivity" | Out-Null
    Start-Sleep -Seconds 7
    Sh "input keyevent 4" | Out-Null
    Start-Sleep -Seconds 3
    $c = Sh "logcat -d | grep -E 'exited due to signal|Fatal signal|FATAL EXCEPTION' | grep -iE 'aura|Zygote' | tail -3"
    if ($c) { $crashes++; "cycle $i ($($p -join ',')): $c" }
}
$after = @((Sh "ls /data/tombstones/")).Count
"cycles $Cycles, crashes seen in the log $crashes, tombstones $before -> $after"
