# Take a screenshot of the emulator (binary-safe) -> shots\<name>.png
param([string]$Name = "shot", [string]$Serial = "emulator-5570")
$adb = "E:\Android\Sdk\platform-tools\adb.exe"
$dir = "D:\k2501-work\shots"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
& $adb -s $Serial shell "screencap -p /sdcard/aura_shot.png" | Out-Null
& $adb -s $Serial pull /sdcard/aura_shot.png "$dir\$Name.png" | Out-Null
"$dir\$Name.png  " + (Get-Item "$dir\$Name.png").Length + " bytes"
