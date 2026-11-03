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
# A home intent limited to the package, never the class by name: only a start that names no component makes a home-type
# activity (docs/DEV.md, "One way to the screens"). For the Home key to come back to AMRI as on the unit, once per
# emulator:  adb shell cmd package set-home-activity com.abdllh.aura/.home.HomeActivity
& $adb -s $Serial shell "am force-stop com.abdllh.aura; am start -W -a android.intent.action.MAIN -c android.intent.category.HOME --es from dev-install com.abdllh.aura" | Select-String "TotalTime|Error"
