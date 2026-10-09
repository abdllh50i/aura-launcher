# Runs the car baker (debug build of the app) on the emulator and pulls the frames.
#   bake.ps1 -Out D:\k2501-work\car3d\bakeN -Params "frames=90`nss=2`nonly=0,45" [-Install] [-PushMesh]
param(
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$Params = "frames=90`nss=4",
    [string]$In = "D:\k2501-work\car3d\out",
    [string]$Serial = "emulator-5570",
    [switch]$Install,
    [switch]$PushMesh
)
$ErrorActionPreference = "Continue"
$adb = "E:\Android\Sdk\platform-tools\adb.exe"
$remote = "/sdcard/Android/data/com.abdllh.aura/files/bake"
if ($Install) {
    & $adb -s $Serial install -r -d (Join-Path $PSScriptRoot "..\..\app\build\outputs\apk\debug\app-debug.apk") | Select-Object -Last 1
}
& $adb -s $Serial shell "mkdir -p $remote/out; rm -f $remote/out/*.png $remote/done.txt $remote/error.txt" | Out-Null
[IO.File]::WriteAllText((Join-Path $In "params.txt"), ($Params -replace "`r", ""), (New-Object Text.UTF8Encoding($false)))
$files = @("basecolor.jpg", "rm.png", "params.txt")
if ($PushMesh) { $files += "mesh.bin" }
foreach ($f in $files) { & $adb -s $Serial push (Join-Path $In $f) "$remote/$f" 2>&1 | Out-Null }
& $adb -s $Serial shell "am force-stop com.abdllh.aura; am start -n com.abdllh.aura/.debug.CarBakerActivity" | Out-Null
$t0 = Get-Date
$deadline = $t0.AddMinutes(30)
do {
    Start-Sleep -Seconds 4
    $state = (& $adb -s $Serial shell "ls $remote/done.txt $remote/error.txt 2>/dev/null") -join " "
} while (-not $state -and (Get-Date) -lt $deadline)
"baker finished in {0:N0} s: {1}" -f ((Get-Date) - $t0).TotalSeconds, $state
& $adb -s $Serial shell "cat $remote/error.txt 2>/dev/null" | Select-Object -First 30
New-Item -ItemType Directory -Force -Path $Out | Out-Null
& $adb -s $Serial pull "$remote/out/." $Out 2>&1 | Out-Null
"pulled: " + (Get-ChildItem $Out -Filter *.png).Count + " frames into $Out"
