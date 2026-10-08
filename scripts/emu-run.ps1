# Run a shell script (LF endings) on the emulator workshop as root and stream its output.
#   emu-run.ps1 -Script path\to\script.sh [-Serial emulator-5570]
param([Parameter(Mandatory = $true)][string]$Script, [string]$Serial = "emulator-5570")
$adb = "E:\Android\Sdk\platform-tools\adb.exe"
$text = (Get-Content -Raw -LiteralPath $Script) -replace "`r`n", "`n"
$tmp = Join-Path $env:TEMP "emu-run-tmp.sh"
[System.IO.File]::WriteAllText($tmp, $text, (New-Object System.Text.UTF8Encoding($false)))
& $adb -s $Serial push $tmp /data/local/tmp/emu-run.sh | Out-Null
& $adb -s $Serial shell "sh /data/local/tmp/emu-run.sh" 2>&1 | ForEach-Object { "$_" }
