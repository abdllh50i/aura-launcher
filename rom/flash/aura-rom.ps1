<#
  Aura ROM installer / restorer for the K2501 head unit (NWD firmware, Allwinner T507).

  Usage (PowerShell on the PC, unit and PC on the same network, adb over network enabled on the unit):
      .\aura-rom.ps1 -Action Status  -Ip 192.168.1.50      what is installed right now? (writes nothing)
      .\aura-rom.ps1 -Action Install -Ip 192.168.1.50      stock firmware  ->  Aura ROM
      .\aura-rom.ps1 -Action Restore -Ip 192.168.1.50      Aura ROM        ->  stock firmware

  What it does: checks the device really is a K2501, reads the hash of the system partition, and only if that is the
  exact stock (or exact Aura) image it writes the small block patch (about 6 MB), verifies the result and reboots.
  Anything unexpected = it refuses and writes nothing.
#>
param(
    [ValidateSet("Status", "Install", "Restore")][string]$Action = "Status",
    [string]$Ip,
    [int]$Port = 5555,
    [string]$Serial,
    [string]$Adb,
    [string]$BlockDevice = "/dev/block/mapper/system",
    [string]$ExpectModel = "K2501",
    [switch]$Yes,
    [switch]$NoReboot
)

$ErrorActionPreference = "Continue"   # native tools write progress to stderr; results are checked explicitly
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$pack = Join-Path $here "patch"
$flashSh = Join-Path $here "aura-flash.sh"
$remote = "/data/local/tmp/aura-rom"

function Say($m, $c = "Gray") { Write-Host $m -ForegroundColor $c }
function Die($m) { Write-Host ""; Write-Host "STOPPED: $m" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- adb
function Find-Adb {
    if ($Adb -and (Test-Path $Adb)) { return $Adb }
    $cands = @()
    $g = Get-Command adb -ErrorAction SilentlyContinue; if ($g) { $cands += $g.Source }
    foreach ($r in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk", "C:\Android\Sdk", "E:\Android\Sdk", "D:\Android\Sdk")) {
        if ($r) { $cands += (Join-Path $r "platform-tools\adb.exe") }
    }
    $cands += (Join-Path $here "platform-tools\adb.exe")
    foreach ($c in $cands) { if ($c -and (Test-Path $c)) { return $c } }
    Die "adb.exe not found. Install Android platform-tools, or pass -Adb C:\path\to\adb.exe"
}
$adbExe = Find-Adb

function A { param([Parameter(ValueFromRemainingArguments)]$a) & $adbExe -s $script:dev @a 2>&1 | ForEach-Object { "$_" } }

# ---------------------------------------------------------------- pick the device (never guess between several)
function Connect-Device {
    if ($Ip) {
        $script:dev = "${Ip}:$Port"
        Say "Connecting to $script:dev ..."
        & $adbExe connect $script:dev 2>&1 | ForEach-Object { Say "  $_" }
        return
    }
    if ($Serial) { $script:dev = $Serial; return }
    $list = @(& $adbExe devices 2>&1 | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" } | ForEach-Object { ($_ -split "\s+")[0] })
    if ($list.Count -eq 1) { $script:dev = $list[0]; Say "Using the only connected device: $script:dev"; return }
    if ($list.Count -eq 0) { Die "No device connected. Use -Ip <unit address> (see Settings > Wi-Fi on the unit)." }
    Say "Several devices are connected:" "Yellow"
    $list | ForEach-Object { Say "   $_" "Yellow" }
    Die "Tell me which one is the car unit with -Ip or -Serial (I will not guess)."
}
Connect-Device

$who = (A shell "id" | Select-Object -First 1)
if ($who -notmatch "uid=0") { Die "The unit did not give a root shell ($who). Network adb must be enabled with root access (it was when the backup was made)." }

# ---------------------------------------------------------------- identity checks
function Prop($n) { ((A shell "getprop $n") -join "").Trim() }
$model = Prop "ro.product.system.model"
$nwd = Prop "ro.nwd.platform.name"
$build = Prop "ro.build.display.id"
$mfr = Prop "ro.product.system.manufacturer"
Say ""
Say "Device : model=$model  nwd=$nwd  vendor=$mfr" "Cyan"
Say "Build  : $build" "Cyan"
if ($model -ne $ExpectModel -and $nwd -ne $ExpectModel) {
    Die "This is not a '$ExpectModel' (found '$model'). Refusing to touch it. (If this is the TV or another device, that is exactly why this check exists.)"
}
foreach ($f in "forward.bin", "reverse.bin", "ranges.txt", "hashes.txt") {
    if (-not (Test-Path (Join-Path $pack $f))) { Die "Patch pack incomplete: $pack\$f is missing." }
}
if (-not (Test-Path $flashSh)) { Die "aura-flash.sh is missing next to this script." }

# ---------------------------------------------------------------- push pack + script
Say ""
Say "Copying the patch pack to the unit ..."
A shell "rm -rf $remote; mkdir -p $remote" | Out-Null
& $adbExe -s $script:dev push $pack "$remote/" 2>&1 | Out-Null
$sh = [IO.File]::ReadAllText($flashSh) -replace "`r`n", "`n"
$tmp = Join-Path $env:TEMP "aura-flash.sh"
[IO.File]::WriteAllText($tmp, $sh, (New-Object Text.UTF8Encoding($false)))
& $adbExe -s $script:dev push $tmp "$remote/aura-flash.sh" 2>&1 | Out-Null
if (-not ((A shell "ls $remote/patch/forward.bin 2>&1") -join "" -match "forward.bin")) { Die "Copy to the unit failed." }

function Flash($mode) {
    $lines = @()
    & $adbExe -s $script:dev shell "sh $remote/aura-flash.sh $mode $remote/patch $BlockDevice" 2>&1 | ForEach-Object {
        $l = "$_"; $lines += $l; Say "  $l"
    }
    return $lines
}

# ---------------------------------------------------------------- status
Say ""
Say "Reading the system partition hash (this takes up to a minute) ..."
$st = Flash "status"
$state = ($st | Where-Object { $_ -match "^STATE:" } | Select-Object -First 1)
if (-not $state) { Die "Could not read the state. Output above." }
$isStock = $state -match "stock"
$isAura = $state -match "Aura ROM applied"
if ($Action -eq "Status") { Say ""; Say $state "Green"; exit 0 }

if ($Action -eq "Install") {
    if ($isAura) { Say ""; Say "The Aura ROM is already installed. Nothing to do." "Green"; exit 0 }
    if (-not $isStock) { Die "The system partition is neither the expected stock image nor the Aura image. Nothing was written." }
}
if ($Action -eq "Restore") {
    if ($isStock) { Say ""; Say "The unit already has the stock firmware. Nothing to do." "Green"; exit 0 }
    if (-not $isAura) { Die "The system partition is neither the Aura image nor the stock image. Nothing was written." }
}

# ---------------------------------------------------------------- confirmation
Say ""
if ($Action -eq "Install") {
    Say "READY TO INSTALL the Aura ROM." "Yellow"
    Say "  - writes about 6 MB of changed blocks into the system partition (not the whole system)" "Yellow"
    Say "  - the result is verified against the expected hash; on any problem the old blocks are written back" "Yellow"
    Say "  - your data (apps, settings, paired phones) is not touched" "Yellow"
} else {
    Say "READY TO RESTORE the stock firmware (undo the Aura ROM)." "Yellow"
}
Say "  - keep the unit powered the whole time (ignition/ACC on, do NOT switch it off) until it reboots" "Yellow"
if (-not $Yes) {
    $answer = Read-Host "Type YES to continue"
    if ($answer -ne "YES") { Die "Cancelled. Nothing was written." }
}

# ---------------------------------------------------------------- do it
Say ""
Say "Writing ..." "Cyan"
$mode = if ($Action -eq "Install") { "apply" } else { "revert" }
$out = Flash $mode
if (-not ($out | Where-Object { $_ -match "^RESULT: OK" })) {
    Die "The patch did not complete. Read the lines above. The unit has NOT been rebooted."
}

if ($Action -eq "Install") {
    # make sure a previous crash-guard / kill switch does not leave the stock launcher selected
    A shell "setprop persist.aura.disabled 0; rm -f /data/aura_disabled /data/data/com.abdllh.aura/files/disable_home" | Out-Null
} else {
    # undo the config edits made at boot and give the home role back to the stock launcher
    $undo = 'for f in /data/nwdappconfig/app/*.pre-aura; do [ -f "$f" ] && cat "$f" > "${f%.pre-aura}" && rm -f "$f"; done; setprop persist.nwd.launcher.default com.android.launcher; setprop persist.aura.disabled 0; echo undone'
    A shell $undo | ForEach-Object { Say "  $_" }
}

Say ""
Say "DONE: $Action succeeded." "Green"
if ($NoReboot) { Say "Reboot the unit yourself to finish." "Yellow"; exit 0 }
Say "Rebooting the unit ..." "Cyan"
A shell "reboot" | Out-Null
Say "The first start after installing is slower (new apps are optimised); wait up to 3 minutes." "Gray"
