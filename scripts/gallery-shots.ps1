# Shoots the debug gallery (app/src/debug/.../GalleryPanel.kt) page by page, night and day: shots\v2-ds-<page>-<theme>.png
#   gallery-shots.ps1 [-Pages controls,r-controls] [-Themes night,day] [-Serial emulator-5578] [-Design D:\k2501-work\tmp\v2\design\final2]
# The emulator is told to hide its bars (the unit has none), so the pictures are the real 1024 x 600. -Design pushes the mockups' map
# plates once, for the replicas to lie on.
param([string[]]$Pages, [string[]]$Themes = @("night", "day"), [string]$Serial = "emulator-5570", [string]$Design = "", [string]$Lang = "", [int]$Wait = 1200)
$adb = "E:\Android\Sdk\platform-tools\adb.exe"
$dir = "D:\k2501-work\shots"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$Pages = $Pages | ForEach-Object { $_ -split "," }
$Themes = $Themes | ForEach-Object { $_ -split "," }
if (-not $Pages) {
    $Pages = "buttons", "abtn", "controls", "bars", "rows", "sheet", "sheet-trip", "modal", "confirm", "menu", "snack", "snack-words", "snack-long", "field", "wash", "covers", "surfaces", "plates", "icons", "dock", "chrome-orders", "type1", "type2", "type3", "type4", "digits", "r-settings-display", "r-settings-maps", "r-controls", "running"
}
& $adb -s $Serial shell "settings put global policy_control immersive.full=*; svc power stayon true; input keyevent KEYCODE_WAKEUP" | Out-Null # (a window draws nothing while the display is off, whatever is asked of it)
if ($Design) {
    $files = "/sdcard/Android/data/com.abdllh.aura/files"
    & $adb -s $Serial shell "mkdir -p $files" | Out-Null
    foreach ($n in "browse-night", "browse-day") { & $adb -s $Serial push "$Design\assets\plates\$n.png" "$files/browse-$($n.Split('-')[1]).png" | Out-Null }
    foreach ($t in "night", "day") { & $adb -s $Serial push "$Design\assets\plates\drive-turn-$t.png" "$files/drive-turn-$t.png" | Out-Null }
}
foreach ($t in $Themes) {
    foreach ($p in $Pages) {
        $extra = if ($Lang) { "--es lang $Lang" } else { "" }
        & $adb -s $Serial logcat -c | Out-Null
        & $adb -s $Serial shell "am broadcast -n com.abdllh.aura/.debug.GalleryReceiver --es page $p --es theme $t --ez cut true $extra" | Out-Null
        # (the page says when its first frame is out; on a host that is busy that can take seconds)
        for ($i = 0; $i -lt 24; $i++) {
            if ((& $adb -s $Serial logcat -d -s AuraGallery:I) -match "drawn $p\.") { break }
            Start-Sleep -Milliseconds 250
        }
        Start-Sleep -Milliseconds $(if ($p -in "modal", "confirm", "menu", "sheet", "sheet-trip") { $Wait + 3000 } else { $Wait }) # (a Snack is gone 4 s after it came)
        $name = if ($Lang) { "v2-ds-$p-$t-$Lang" } else { "v2-ds-$p-$t" }
        & $adb -s $Serial shell "screencap -p /sdcard/aura_shot.png" | Out-Null
        & $adb -s $Serial pull /sdcard/aura_shot.png "$dir\$name.png" | Out-Null
        if ($p -like "snack*") { Start-Sleep -Milliseconds 4500 } # (it outlives its page, as it does a panel)
        "$name"
    }
}
