# The Kotlin search (nav/places) against the reference (tools/mapbuild/search_ref.py), row by row, on an emulator with SQLite 3.22.
# Pushes places.db under a name of its own (files/searchtest), runs the jobs through the debug broadcast, pulls the answers and compares. Take the emulator's lock first.
#   search-compare.ps1 -Serial emulator-5578 [-Data D:\k2501-work\mapbuild\out] [-Work D:\k2501-work\tmp\v2\scratch\search\cmp] [-NoPrepare] [-Jobs set200,golden,...]
#   -Failures: instead, the files it must refuse (missing, cut short, another version, not a database): what the search answers, what the search box then does, and whether the file is still there.
#   The search box's old road goes online when it can: the network is off for that part.
param([string]$Serial = "emulator-5578", [string]$Data = "D:\k2501-work\mapbuild\out", [string]$Work = "D:\k2501-work\tmp\v2\scratch\search\cmp",
      [switch]$NoPrepare, [switch]$NoPushDb, [switch]$Failures, [string[]]$Jobs = @("text", "set200", "set200.warm", "golden", "prefixes", "finished", "own", "reverse", "routes", "typing", "cold", "pss"))
$ErrorActionPreference = "Continue"
$Jobs = $Jobs -split ','
$adb = "E:\Android\Sdk\platform-tools\adb.exe"
$py = Join-Path $PSScriptRoot "search_compare.py"
$pkg = "com.abdllh.aura"
$dir = "/data/data/$pkg/files/searchtest"
New-Item -ItemType Directory -Force -Path $Work | Out-Null
& $adb -s $Serial root | Out-Null
Start-Sleep 2
function Sh($c) { & $adb -s $Serial shell $c }
$uid = (Sh "stat -c %u /data/data/$pkg").Trim()
Sh "mkdir -p $dir"

if ($Failures) {
    $bad = Join-Path $Work "bad"
    python -I (Join-Path $PSScriptRoot "search_badfiles.py") "$Data\places.db" $bad
    Get-ChildItem "$bad\*.db" | ForEach-Object { & $adb -s $Serial push $_.FullName "$dir/$($_.Name)" | Out-Null }
    Sh "chown -R ${uid}:${uid} $dir; chmod -R u+rwX $dir; restorecon -R $dir"
    Sh "svc data disable; svc wifi disable"
    Sh "am start -W -n $pkg/.home.HomeActivity" | Out-Null
    Sh "am broadcast -a com.abdllh.aura.debug.PLACES --ez swap true" | Out-Null
    foreach ($f in @("no-such-file.db", "empty.db", "garbage.db", "cut-plain.db", "cut-patched.db", "holes.db", "old-norm.db", "old-schema.db", "no-meta.db")) {
        Sh "logcat -c"
        Sh "am broadcast -a com.abdllh.aura.debug.PLACES --es db $dir/$f --ez status true" | Out-Null
        Start-Sleep 1
        Sh "am broadcast -a com.abdllh.aura.debug.PLACES --es q pharmacy --es near 27.0046,49.6586" | Out-Null
        Start-Sleep 1
        Sh "am broadcast -a com.abdllh.aura.debug.PLACES --es rev 27.0046,49.6586" | Out-Null
        Start-Sleep 1
        Sh "am broadcast -a com.abdllh.aura.debug.PLACES --es q pharmacy --es near 27.0046,49.6586 --ez box true" | Out-Null
        Start-Sleep 4
        "--- $f : " + (Sh "ls -l $dir/$f 2>&1")
        Sh "logcat -d -s AuraPlacesDebug:I AuraPlaces:W AndroidRuntime:E | grep -v '^-' | cut -c 20-220"
    }
    Sh "am broadcast -a com.abdllh.aura.debug.PLACES --es db default --ez swap false" | Out-Null
    Sh "svc data enable; svc wifi enable"
    "--- after it all, the app's process: " + (Sh "pidof $pkg")
    exit 0
}

if (-not $NoPrepare) { python -I $py prepare --db "$Data\places.db" --routes "$Data\route-tests" --out $Work; if ($LASTEXITCODE -ne 0) { "prepare failed"; exit 1 } }
if (-not $NoPushDb) { & $adb -s $Serial push "$Data\places.db" "$dir/places.db" | Select-Object -Last 1 }
Get-ChildItem "$Work\*.job.json" | ForEach-Object { & $adb -s $Serial push $_.FullName "$dir/$($_.Name)" | Out-Null }
Sh "chown -R ${uid}:${uid} $dir; chmod -R u+rwX $dir; restorecon -R $dir"

function Broadcast($extra) { Sh "am broadcast -a com.abdllh.aura.debug.PLACES $extra" | Out-Null }
function WaitDone($name, $seconds) {
    $until = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $until) {
        $hit = Sh "logcat -d -s AuraPlacesDebug:I | grep 'job done $name' | tail -n 1"
        if ($hit) { return $true }
        Start-Sleep 1
    }
    return $false
}
Sh "am start -W -n $pkg/.home.HomeActivity" | Out-Null
Broadcast "--es db $dir/places.db"
Start-Sleep 1
foreach ($j in $Jobs) {
    if ($j -eq "cold") {
        # a job a query: the kernel's file cache dropped, the file closed and opened again by the app
        Sh "logcat -c"
        $t0 = Get-Date
        foreach ($n in 0..29) {
            $c = "cold{0:d2}" -f $n
            Sh "echo 3 > /proc/sys/vm/drop_caches"
            Broadcast "--es db $dir/places.db"
            Start-Sleep -Milliseconds 300
            Broadcast "--es job $dir/$c.job.json,$dir/$c.got.json"
            if (-not (WaitDone "$c.job.json" 60)) { "$c : no answer" }
            & $adb -s $Serial pull "$dir/$c.got.json" "$Work\$c.got.json" | Out-Null
        }
        "cold : $([int]((Get-Date) - $t0).TotalSeconds) s"
        continue
    }
    $base = $j -replace '\.warm$', ''
    $kind = "job"; if ($j -eq "typing") { $kind = "typing" }; if ($j -eq "pss") { $kind = "pss"; $base = "set200" }
    Sh "logcat -c"
    $t0 = Get-Date
    Broadcast "--es $kind $dir/$base.job.json,$dir/$j.got.json"
    if (-not (WaitDone "$base.job.json" 900)) { "$j : no answer in 900 s"; continue }
    & $adb -s $Serial pull "$dir/$j.got.json" "$Work\$j.got.json" | Out-Null
    "$j : $([int]((Get-Date) - $t0).TotalSeconds) s"
}
python -I $py compare --out $Work --golden "$Data\search-golden.json"
