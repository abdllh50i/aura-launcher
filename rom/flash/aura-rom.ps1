<#
  AMRI OS ROM installer / restorer for the K2501 head unit (NWD firmware, Allwinner T507).

  Usage (PowerShell on the PC, unit and PC on the same network, adb over network enabled on the unit):
      .\aura-rom.ps1 -Action Status  -Ip 192.168.1.50      what is installed right now? (writes nothing)
      .\aura-rom.ps1 -Action Install -Ip 192.168.1.50      stock firmware  ->  Aura ROM
      .\aura-rom.ps1 -Action Restore -Ip 192.168.1.50      Aura ROM        ->  stock firmware

  What it does: checks the device really is a K2501, reads the hash of the system partition, and only if that is the
  exact stock (or exact AMRI OS) image - or an interrupted run of this very patch - it writes the small block patch
  (a few MB, data first and the structures that point at it last), verifies the result and reboots.
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
$packFiles = "forward.bin", "reverse.bin", "ranges.txt", "order-apply.txt", "order-revert.txt", "hashes.txt"

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

# what the system that is running right now says (after a reboot this is the proof that the ROM is live)
$romVer = Prop "ro.aura.rom.version"
$homeApp = Prop "persist.nwd.launcher.default"
$auraPath = ((A shell "pm path com.abdllh.aura 2>&1") -join " ").Trim()
if ($romVer) { Say "Running: AMRI OS ROM $romVer   home app = $homeApp   $auraPath" "Cyan" }
else { Say "Running: no AMRI OS ROM in the running system   home app = $homeApp" "Cyan" }

foreach ($f in $packFiles) {
    if (-not (Test-Path (Join-Path $pack $f))) { Die "Patch pack incomplete: $pack\$f is missing." }
}
if (-not (Test-Path $flashSh)) { Die "aura-flash.sh is missing next to this script." }
$packMB = [math]::Round((Get-Item (Join-Path $pack "forward.bin")).Length / 1MB, 1)

# a run whose connection dropped keeps going on the unit (it ignores HUP): never pull the pack away from under it
$busy = (A shell "pgrep -f '[a]ura-flash.sh'") -join " "
if ($busy -match "\d") {
    Die "An earlier run (process $($busy.Trim())) is still working on the unit. Wait until it is finished - 'adb -s $script:dev shell cat $remote/flash.log' shows its progress and ends with a RESULT line - then run this again."
}

# ---------------------------------------------------------------- push pack + script (and prove the copy is complete)
Say ""
Say "Copying the patch pack to the unit ..."
A shell "mkdir -p $remote; rm -rf $remote/patch $remote/aura-flash.sh $remote/follow.sh $remote/*.clean" | Out-Null
& $adbExe -s $script:dev push $pack "$remote/" 2>&1 | Out-Null
$sh = [IO.File]::ReadAllText($flashSh) -replace "`r`n", "`n"
$tmp = Join-Path $env:TEMP "aura-flash.sh"
[IO.File]::WriteAllText($tmp, $sh, (New-Object Text.UTF8Encoding($false)))
& $adbExe -s $script:dev push $tmp "$remote/aura-flash.sh" 2>&1 | Out-Null

function Remote-Size($path) {
    $o = (A shell "wc -c < $path") -join ""
    if ($o -match '^\s*(\d+)\s*$') { return [int64]$Matches[1] }
    return -1
}
foreach ($f in $packFiles) {
    $want = (Get-Item (Join-Path $pack $f)).Length
    $got = Remote-Size "$remote/patch/$f"
    if ($got -ne $want) { Die "Copying $f to the unit failed (the unit has $got bytes, expected $want). Nothing was written; run the script again." }
}
if ((Remote-Size "$remote/aura-flash.sh") -ne (New-Object Text.UTF8Encoding($false)).GetByteCount($sh)) { Die "Copying aura-flash.sh to the unit failed. Nothing was written; run the script again." }

function Flash($mode) {
    $lines = @()
    & $adbExe -s $script:dev shell "sh $remote/aura-flash.sh $mode $remote/patch $BlockDevice" 2>&1 | ForEach-Object {
        $l = "$_"; $lines += $l; Say "  $l"
    }
    return $lines
}

# ---------------------------------------------------------------- status
Say ""
Say "Reading the system partition (this takes up to a minute) ..."
$st = Flash "status"
$stateLine = ($st | Where-Object { $_ -match "^STATE:" } | Select-Object -First 1)
$stateCode = ($st | Where-Object { $_ -match "^STATE_CODE=" } | Select-Object -First 1)
if (-not $stateLine -or -not $stateCode) { Die "Could not read the state. Output above." }
$code = ($stateCode -replace "^STATE_CODE=", "").Trim()
if ($Action -eq "Status") {
    Say ""
    $color = "Green"; if ($code -eq "PARTIAL" -or $code -eq "UNKNOWN") { $color = "Yellow" }
    Say $stateLine $color
    if ($code -eq "AURA" -and -not $romVer) { Say "The unit has not been restarted since the AMRI OS ROM was written: restart it to start using it." "Yellow" }
    if ($code -eq "STOCK" -and $romVer) { Say "The stock firmware is written but the unit has not been restarted since: restart it to finish the restore." "Yellow" }
    exit 0
}

# a finished write whose follow-up (and reboot) never happened, for example because the connection dropped at the end
$pending = $false
if ($Action -eq "Install") {
    if ($code -eq "AURA" -and $romVer) { Say ""; Say "The AMRI OS ROM is already installed and running. Nothing to do." "Green"; exit 0 }
    if ($code -eq "AURA") { $pending = $true; Say ""; Say "The AMRI OS ROM is already written to the unit but it has not been restarted since. Finishing that." "Yellow" }
    elseif ($code -ne "STOCK" -and $code -ne "PARTIAL") { Die "The system partition is neither the expected stock image nor an interrupted run of this patch (another firmware version?). Nothing was written and the unit was not changed." }
}
if ($Action -eq "Restore") {
    if ($code -eq "STOCK" -and -not $romVer) { Say ""; Say "The unit already has the stock firmware. Nothing to do." "Green"; exit 0 }
    if ($code -eq "STOCK") { $pending = $true; Say ""; Say "The stock firmware is already written to the unit but it has not been restarted since. Finishing that." "Yellow" }
    elseif ($code -ne "AURA" -and $code -ne "PARTIAL") { Die "The system partition is neither the AMRI OS image nor an interrupted run of this patch. Nothing was written." }
}
if ($code -eq "PARTIAL") { Say ""; Say "$stateLine" "Yellow" }

# ---------------------------------------------------------------- confirmation
Say ""
if ($pending) {
    Say "READY TO FINISH: nothing more is written to the system partition; the follow-up step runs and the unit restarts." "Yellow"
} elseif ($Action -eq "Install") {
    Say "READY TO INSTALL the AMRI OS ROM." "Yellow"
    Say "  - writes about $packMB MB of changed blocks into the system partition (not the whole system)" "Yellow"
    Say "  - the result is verified against the expected hash; on any problem the old blocks are written back" "Yellow"
    Say "  - your data (apps, settings, paired phones) is not touched" "Yellow"
} else {
    Say "READY TO RESTORE the stock firmware (undo the AMRI OS ROM)." "Yellow"
}
Say "  - keep the unit powered the whole time (ignition/ACC on, do NOT switch it off) until it reboots" "Yellow"
if (-not $Yes) {
    $answer = Read-Host "Type YES to continue"
    if ($answer -ne "YES") { Die "Cancelled. Nothing was written." }
}

# ---------------------------------------------------------------- do it
if (-not $pending) {
    Say ""
    Say "Writing ..." "Cyan"
    $mode = if ($Action -eq "Install") { "apply" } else { "revert" }
    $out = Flash $mode
    if (-not ($out | Where-Object { $_ -match "^RESULT: OK" })) {
        Say ""
        Say "The patch did not report success. The unit has NOT been rebooted - do not switch it off or reboot it yet." "Red"
        Say "  * If the lines above end with 'previous contents were restored and verified', nothing changed: you may try again." "Yellow"
        Say "  * If the connection dropped or the output stops early, the unit keeps working on its own: wait two or three minutes," "Yellow"
        Say "    then run this script with -Action Status. An interrupted run is completed by running Install (or Restore) again." "Yellow"
        Say "  * A log is kept on the unit: adb -s $script:dev shell cat $remote/flash.log" "Yellow"
        Die "The $Action did not complete."
    }
}

# small follow-up script, pushed as a file (no quoting games through three shells)
if ($Action -eq "Install") {
    # make sure a previous crash-guard / kill switch does not leave the stock launcher selected
    $follow = "setprop persist.aura.disabled 0`nrm -f /data/aura_disabled /data/data/com.abdllh.aura/files/disable_home`necho prepared`n"
    $expect = "prepared"
} else {
    # undo the config edits made at boot, Aura Music replacing the stock music app, the hidden status bar and the stock
    # volume bar turned off, and give the home role back to the stock launcher. An Aura updated from the screen lives in
    # /data/app and would stay on as an ordinary app once its system copy is gone: it is uninstalled (a system-only copy
    # refuses, and goes away at the restart), and the kill switch keeps anything left of it inert.
    $follow = @'
for f in /data/nwdappconfig/app/*.pre-aura; do
  [ -f "$f" ] || continue
  cat "$f" > "${f%.pre-aura}" && rm -f "$f"
done
L=/data/nwdappconfig/app/replace_source_list.xml
grep -q com.abdllh.aura "$L" 2>/dev/null && rm -f "$L"
pm enable com.nwd.android.music.ui >/dev/null 2>&1
pm enable com.android.launcher/com.launcher.FloatBar >/dev/null 2>&1
case "$(settings get global policy_control 2>/dev/null)" in *immersive.*) settings delete global policy_control >/dev/null 2>&1 ;; esac
[ "$(settings get system phone_connect_style 2>/dev/null)" = "0" ] && settings put system phone_connect_style 3 && settings put system recheck_phone_connect_style 1
setprop persist.nwd.launcher.default com.android.launcher
pm uninstall com.abdllh.aura >/dev/null 2>&1
setprop persist.aura.disabled 1
echo undone
'@
    $follow = ($follow -replace "`r`n", "`n") + "`n"
    $expect = "undone"
}
$ftmp = Join-Path $env:TEMP "aura-follow.sh"
[IO.File]::WriteAllText($ftmp, $follow, (New-Object Text.UTF8Encoding($false)))
& $adbExe -s $script:dev push $ftmp "$remote/follow.sh" 2>&1 | Out-Null
$fo = A shell "sh $remote/follow.sh"
$fo | ForEach-Object { Say "  $_" }
if (-not ($fo | Where-Object { $_ -match "^$expect" })) {
    # Not restarting is the safe answer: the unit still shows the state this step has to fix, so running the script again
    # takes the "finish what was written" path and repeats the step.
    Say "The follow-up step did not finish ('$expect' was not reported). The system partition itself is fine, but the unit was NOT restarted: run this script again and it will finish the job." "Red"
    exit 1
}

Say ""
if ($pending) { Say "DONE: $Action finished." "Green" } else { Say "DONE: $Action succeeded." "Green" }
if ($NoReboot) { Say "Reboot the unit now to finish, and do not use it before that (the running system still holds the old file tables)." "Yellow"; exit 0 }
Say "Rebooting the unit ..." "Cyan"
A shell "reboot" | Out-Null
Say "The first start after installing is slower (new apps are optimised); wait up to 3 minutes." "Gray"
Say "Afterwards run status.bat: it must say 'Running: Aura ROM' and show the Aura app path." "Gray"
