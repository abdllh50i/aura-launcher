# Retakes the README screenshots on the emulator workshop (debug build installed): docs/screenshots/*.png
#   doc-shots.ps1 [-Serial emulator-5570]
# Uses the debug-only SettingsActivity extras (--es theme / --es lang) and leaves the unit on auto theme + system language.
param([string]$Serial = "emulator-5570")
$adb = "E:\Android\Sdk\platform-tools\adb.exe"
$dir = Join-Path (Split-Path $PSScriptRoot -Parent) "docs\screenshots"
New-Item -ItemType Directory -Force -Path $dir | Out-Null

function Shot([string]$name) {
    & $adb -s $Serial shell "screencap -p /sdcard/aura_doc.png" | Out-Null
    & $adb -s $Serial pull /sdcard/aura_doc.png (Join-Path $dir "$name.png") | Out-Null
    "  $name.png"
}
function Prefs([string]$theme, [string]$lang) {
    & $adb -s $Serial shell "am start -n com.abdllh.aura/.settings.SettingsActivity --es theme $theme --es lang $lang" | Out-Null
    Start-Sleep -Milliseconds 1500
    & $adb -s $Serial shell "input keyevent KEYCODE_HOME" | Out-Null
    Start-Sleep -Seconds 5      # language changes recreate the home screen and replay its intro
}
function Settings([int]$page) {
    & $adb -s $Serial shell "am start -n com.abdllh.aura/.settings.SettingsActivity --ei page $page" | Out-Null
    Start-Sleep -Seconds 2
}

Prefs "dark" "en";   Shot "home-dark"
& $adb -s $Serial shell "input tap 42 514" | Out-Null; Start-Sleep -Milliseconds 1200; Shot "controls"
& $adb -s $Serial shell "input keyevent KEYCODE_BACK" | Out-Null; Start-Sleep -Milliseconds 600
Settings 1;          Shot "update-en"
Prefs "light" "en";  Shot "home-light"
Prefs "dark" "ar";   Shot "home-ar"
Settings 1;          Shot "update-ar"
Prefs "auto" "system"
"done -> $dir"
