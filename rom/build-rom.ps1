# Builds the Aura ROM system image.
#   1. builds + signs the release APK
#   2. stages the overlay files (LF line endings)
#   3. runs the workshop inside the emulator (grow, mount, install, patch, fsck)
#   4. pulls the finished image to  <out>\system-aura.img
# Needs: the stock system.img already pushed to /data/local/tmp/ws/system.img in the emulator workshop.
param(
    [string]$Serial = "emulator-5570",
    [string]$Out = "D:\k2501-work\out",
    [switch]$SkipGradle
)
$ErrorActionPreference = "Continue"   # adb writes progress to stderr; failures are checked explicitly
$proj = Split-Path $PSScriptRoot -Parent
$adb = "E:\Android\Sdk\platform-tools\adb.exe"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
$env:ANDROID_HOME = "E:\Android\Sdk"

if (-not $SkipGradle) {
    Push-Location $proj
    & ".\gradlew.bat" :app:assembleRelease --offline | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }
    Pop-Location
}
$apk = Join-Path $proj "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { throw "release APK not found: $apk" }

# ---- stage
$stage = Join-Path $PSScriptRoot "stage"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
Copy-Item -Recurse (Join-Path $PSScriptRoot "system") (Join-Path $stage "system")
New-Item -ItemType Directory -Force -Path "$stage\system\priv-app\Aura" | Out-Null
Copy-Item $apk "$stage\system\priv-app\Aura\Aura.apk"
Copy-Item (Join-Path $PSScriptRoot "ROM_VERSION") "$stage\ROM_VERSION"
Get-ChildItem $stage -Recurse -File | Where-Object { $_.Extension -in ".sh", ".rc", ".xml" -or $_.Name -eq "ROM_VERSION" } | ForEach-Object {
    $t = [IO.File]::ReadAllText($_.FullName) -replace "`r`n", "`n"
    [IO.File]::WriteAllText($_.FullName, $t, (New-Object Text.UTF8Encoding($false)))
}

# ---- push + run
& $adb -s $Serial shell "rm -rf /data/local/tmp/ws/rom; mkdir -p /data/local/tmp/ws/rom" | Out-Null
& $adb -s $Serial push "$stage/." /data/local/tmp/ws/rom/ | Out-Host
$apply = [IO.File]::ReadAllText((Join-Path $PSScriptRoot "workshop\apply.sh")) -replace "`r`n", "`n"
$tmp = Join-Path $env:TEMP "apply.sh"
[IO.File]::WriteAllText($tmp, $apply, (New-Object Text.UTF8Encoding($false)))
& $adb -s $Serial push $tmp /data/local/tmp/ws/apply.sh | Out-Null
$log = & $adb -s $Serial shell "sh /data/local/tmp/ws/apply.sh" 2>&1 | ForEach-Object { "$_" }
$log | Out-Host
if (-not ($log -match "^DONE")) { throw "workshop did not finish cleanly" }

# ---- pull
New-Item -ItemType Directory -Force -Path $Out | Out-Null
& $adb -s $Serial pull /data/local/tmp/ws/aura-system.img (Join-Path $Out "system-aura.img") | Out-Host
$h = (Get-FileHash (Join-Path $Out "system-aura.img") -Algorithm SHA256).Hash.ToLower()
Set-Content -Path (Join-Path $Out "system-aura.img.sha256") -Value $h -Encoding ascii
"system-aura.img  sha256=$h"
