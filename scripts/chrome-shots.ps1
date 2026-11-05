# Shoots the real chrome (app/src/main/.../chrome) in the states the mockups draw and in the words they use, on the mockups' plates, for scripts/gallery_compare.py:
#   shots\v2-ds-c-<state>-<theme>.png.  chrome-shots.ps1 -Serial emulator-5578 [-Design D:\k2501-work\tmp\v2\design\final2] [-States home,drive] [-Themes night,day]
# -Design pushes the plates once.
param([string]$Serial = "emulator-5578", [string]$Design = "", [string[]]$States, [string[]]$Themes = @("night", "day"), [int]$Wait = 700, [string]$Prefix = "c", [string]$Lang = "ar")
$adb = "E:\Android\Sdk\platform-tools\adb.exe"
$dir = "D:\k2501-work\shots"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$Themes = $Themes | ForEach-Object { $_ -split "," }
$States = $States | ForEach-Object { $_ -split "," }
$p = "com.abdllh.aura/.debug.ChromeProbe"
function Sh([string]$c) { & $adb -s $Serial shell $c | Out-Null }
function Probe([string]$a) { Sh "am broadcast -n $p $a" }
function Gear([string]$g) { Sh "am broadcast -a com.abdllh.aura.debug.GEAR --es gear $g" }
function Q([string]$s) { "'" + $s + "'" }

Sh "settings put global policy_control immersive.full=*; svc power stayon true; input keyevent KEYCODE_WAKEUP"
if ($Design) {
    $files = "/sdcard/Android/data/com.abdllh.aura/files"
    Sh "mkdir -p $files"
    foreach ($t in "night", "day") {
        & $adb -s $Serial push "$Design\assets\car\stage-hole-$t.png" "$files/stage-hole-$t.png" | Out-Null
        & $adb -s $Serial push "$Design\assets\plates\browse-$t.png" "$files/browse-$t.png" | Out-Null
        & $adb -s $Serial push "$Design\assets\plates\drive-turn-$t.png" "$files/drive-turn-$t.png" | Out-Null
    }
}

# state -> page under it, panel, gear, time, speed, etc., by theme
$all = [ordered]@{
    home = @{ page = "r-chrome-wall"; panel = "home"; gear = "P" }
    moving = @{ page = "r-chrome-wall"; panel = "home"; gear = "D" }
    trip = @{ page = "r-chrome-wall"; panel = "home"; gear = "D"; speed = "112,120" }
    "voice-home" = @{ page = "r-chrome-wall"; panel = "home"; gear = "P"; listen = $true }
    "volume-home" = @{ page = "r-chrome-wall"; panel = "home"; gear = "P"; vol = "--ei vol 14" }
    drive = @{ page = "r-chrome-drive"; panel = "maps"; gear = "D"; timeN = "7:20 م"; timeD = "10:05 ص" }
    volume = @{ page = "r-chrome-drive"; panel = "maps"; gear = "D"; timeN = "7:20 م"; timeD = "10:05 ص"; vol = "--ei vol 14" }
    muted = @{ page = "r-chrome-drive"; panel = "maps"; gear = "D"; timeN = "7:20 م"; timeD = "10:05 ص"; vol = "--ei vol 14 --ez mute true" }
    voice = @{ page = "r-chrome-drive"; panel = "maps"; gear = "D"; timeN = "7:20 م"; timeD = "10:05 ص"; listen = $true }
    apps = @{ page = "r-chrome-room"; panel = "apps"; gear = "P"; song = "سهر الطريق" }
    settings = @{ page = "r-chrome-room"; panel = "settings"; gear = "D"; speed = "72,80"; song = "سهر الطريق"; timeN = "7:20 م"; timeD = "10:05 ص" }
    music = @{ page = "r-chrome-bg"; panel = "music"; gear = "P" }
    "en-home" = @{ page = "r-chrome-wall"; panel = "home"; gear = "P" }
    "en-drive" = @{ page = "r-chrome-drive"; panel = "maps"; gear = "D"; timeN = "7:20 PM"; timeD = "10:05 AM" }
}
if (-not $States) { $States = @($all.Keys | Where-Object { $_ -notlike "en-*" }) }
foreach ($t in $Themes) {
    foreach ($s in $States) {
        $d = $all[$s]
        $night = $t -eq "night"
        & $adb -s $Serial logcat -c | Out-Null
        Sh "am broadcast -n com.abdllh.aura/.debug.GalleryReceiver --es page $($d.page) --es theme $t --ez cut true --es lang $Lang"
        for ($i = 0; $i -lt 24; $i++) {
            if ((& $adb -s $Serial logcat -d -s AuraGallery:I) -match "drawn $($d.page)\.") { break }
            Start-Sleep -Milliseconds 250
        }
        Probe "--ez on true"
        Gear $d.gear
        $time = if ($night) { $d.timeN } else { $d.timeD }
        if (-not $time) { $time = "4:18 م" }
        $vol = if ($d.vol) { $d.vol } else { "--ei vol 12" }
        $a = "--es cut $($d.panel) --ez status true $vol --es time $(Q $time)"
        if ($d.speed) { $a += " --es speed $($d.speed)" }
        if ($d.song) { $a += " --es song $(Q $d.song)" }
        if ($d.listen) { $a += " --ez listen true" }
        Probe $a
        Start-Sleep -Milliseconds $Wait
        $name = "v2-ds-$Prefix-$s-$t"
        & $adb -s $Serial shell "screencap -p /sdcard/aura_shot.png" | Out-Null
        & $adb -s $Serial pull /sdcard/aura_shot.png "$dir\$name.png" | Out-Null
        if ($d.listen) { Probe "--ez listen false" }
        $name
    }
}
Gear "none"
