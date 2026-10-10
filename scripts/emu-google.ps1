# A second emulator, with Google's services (the Google Play image, Android 10), for trying the voice assistant "Amri"
# with your own voice: understanding a request needs Google's speech recognition, which the unit has and the
# workshop emulator (CarUnit) does not. It hears Windows' default microphone and plays on the default output
# (scripts\emu-mic.bat shows which). The workshop emulator is stopped first so that only one listens; -KeepWorkshop
# leaves it running. The first start takes a few minutes.
#   powershell -ExecutionPolicy Bypass -File scripts\emu-google.ps1 [-KeepWorkshop] [-NoInstall]
param([switch]$KeepWorkshop, [switch]$NoInstall)
$adb = "E:\Android\Sdk\platform-tools\adb.exe"
$emu = "E:\Android\Sdk\emulator\emulator.exe"
$serial = "emulator-5572"
$env:ANDROID_AVD_HOME = "D:\k2501-work\avd"
if (-not (Test-Path "E:\Android\Sdk\system-images\android-29\google_apis_playstore\x86_64\system.img")) {
    throw "the Google Play image is not installed (system-images\android-29\google_apis_playstore\x86_64)"
}

if (-not $KeepWorkshop) {
    & $adb -s emulator-5570 emu kill 2>$null | Out-Null
}
if (-not ((& $adb devices) -match "^$serial\s+device")) {
    Start-Process -FilePath $emu -ArgumentList "-avd", "CarUnitGoogle", "-port", "5572", "-gpu", "host", "-no-boot-anim", "-allow-host-audio"
    $t0 = Get-Date
    do {
        Start-Sleep -Seconds 5
        $booted = (& $adb -s $serial shell getprop sys.boot_completed 2>$null)
    } while ($booted -ne "1" -and ((Get-Date) - $t0).TotalSeconds -lt 480)
    if ($booted -ne "1") { throw "the emulator did not boot" }
    Start-Sleep -Seconds 10 # Google's apps settle after the first boot
}
if (-not $NoInstall) {
    & (Join-Path $PSScriptRoot "dev-install.ps1") -NoBuild -Serial $serial
}
# the microphone, the assistant's bubble over other apps, AMRI as the home screen, in Arabic
& $adb -s $serial shell "pm grant com.abdllh.aura android.permission.RECORD_AUDIO; appops set com.abdllh.aura SYSTEM_ALERT_WINDOW allow; cmd package set-home-activity com.abdllh.aura/.home.HomeActivity" | Out-Null
& $adb -s $serial shell "am start -n com.abdllh.aura/.settings.SettingsActivity --es lang ar" | Out-Null
Start-Sleep -Seconds 2
& $adb -s $serial shell "am force-stop com.abdllh.aura; am start -n com.abdllh.aura/.home.HomeActivity; sleep 3; am start -n com.abdllh.aura/.settings.SettingsActivity --ei page 5" | Out-Null
Write-Output "Ready: AMRI's voice assistant page is open on the emulator with Google's services ($serial)."
