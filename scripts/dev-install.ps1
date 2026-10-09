# Builds the debug APK, installs it on the emulator workshop and brings the home screen up.
#   dev-install.ps1 [-Version 1.1.0] [-NoBuild] [-Serial emulator-5570]
param([string]$Version = "1.1.0", [switch]$NoBuild, [string]$Serial = "emulator-5570")
$ErrorActionPreference = "Continue"
$adb = "E:\Android\Sdk\platform-tools\adb.exe"
$root = Split-Path $PSScriptRoot -Parent
if (-not $NoBuild) {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
    $env:ANDROID_HOME = "E:\Android\Sdk"
    Push-Location $root
    $out = & .\gradlew.bat --offline -q assembleDebug "-PappVersionName=$Version" 2>&1
    $code = $LASTEXITCODE
    Pop-Location
    $out | Where-Object { "$_" -match "^e:|error:|FAILED|What went wrong" } | ForEach-Object { "$_" }
    if ($code -ne 0) { "BUILD FAILED"; exit 1 }
}
& $adb -s $Serial install -r -d (Join-Path $root "app\build\outputs\apk\debug\app-debug.apk") | Select-Object -Last 1
& $adb -s $Serial shell "am force-stop com.abdllh.aura; am start -W -n com.abdllh.aura/.home.HomeActivity" | Select-String "TotalTime|Error"
