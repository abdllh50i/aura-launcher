# End-to-end test of the PC installer + on-device script against a loop device standing in for the system partition,
# on the Android-10 emulator workshop (never on a real unit).
#   test-flash.ps1 -Pack D:\k2501-work\out\testpack2          (a folder laid out like the release zip)
# Needs ~2 GB free on the emulator's /data and the stock image at /data/local/tmp/ws/system.img.
param(
    [string]$Pack = "D:\k2501-work\out\testpack2",
    [string]$Serial = "emulator-5570",
    [string]$Adb = "E:\Android\Sdk\platform-tools\adb.exe",
    [switch]$Quick      # only T0 + T1 (install / restore round trip): for checking a freshly unpacked release zip
)
$ErrorActionPreference = "Continue"
$env:PYTHONDONTWRITEBYTECODE = "1"
$stockImg = "/data/local/tmp/ws/system.img"
$standin = "/data/local/tmp/standin.img"
$script:loop = ""
$script:fails = 0
$hashes = @{}
Get-Content (Join-Path $Pack "patch\hashes.txt") | ForEach-Object { $k, $v = $_ -split "=", 2; $hashes[$k] = $v }
$OLD = $hashes["old"]; $NEW = $hashes["new"]

function Sh([string]$text) {
    $tmp = Join-Path $env:TEMP "tf.sh"
    [IO.File]::WriteAllText($tmp, ($text -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding($false)))
    & $Adb -s $Serial push $tmp /data/local/tmp/tf.sh 2>&1 | Out-Null
    & $Adb -s $Serial shell "sh /data/local/tmp/tf.sh" 2>&1 | ForEach-Object { "$_" }
}
function Rom([string]$pack, [string]$action, [string[]]$more) {
    powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $pack "aura-rom.ps1") -Action $action -Serial $Serial -BlockDevice $script:loop `
        -ExpectModel "Android SDK built for x86_64" -Adb $Adb @more 2>&1 | ForEach-Object { "$_" }
}
function Expect([string]$name, $lines, [string]$pattern) {
    if (($lines -join "`n") -match $pattern) { "PASS  $name" }
    else { $script:fails++; "FAIL  $name   (wanted /$pattern/)"; $lines | Select-Object -Last 14 | ForEach-Object { "      $_" } }
}
function DevHash() { ((Sh "sha256sum $script:loop") -join "" -split "\s+")[0] }
function ExpectHash([string]$name, [string]$want) {
    $h = DevHash
    if ($h -eq $want) { "PASS  $name (device hash $($h.Substring(0,12))...)" } else { $script:fails++; "FAIL  $name  device=$h  wanted=$want" }
}
function NewStandin() {
    $o = Sh "losetup -d /dev/block/loop1 2>/dev/null; rm -f $standin; cp $stockImg $standin || echo COPY_FAILED; L=`$(losetup -f --show $standin); blockdev --setro `$L; echo LOOP=`$L"
    $l = ($o | Where-Object { $_ -match "^LOOP=" }) -replace "^LOOP=", ""
    if (-not $l -or ($o -match "COPY_FAILED")) { throw "cannot create the stand-in: $o" }
    $script:loop = $l.Trim()
    ExpectHash "fresh stand-in is the stock image" $OLD
}
# write the first K ranges of an order list from a bin onto the stand-in (a run that was cut off)
function PartialWrite([string]$list, [string]$bin, [int]$k) {
    Sh @"
P=/data/local/tmp/aura-rom/patch
blockdev --setrw $script:loop
n=0
while read -r s c o a b; do
  [ -n "`$s" ] || continue
  n=`$((n + 1))
  [ `$n -gt $k ] && break
  dd if=`$P/$bin of=$script:loop bs=4096 skip=`$o seek=`$s count=`$c conv=notrunc 2>/dev/null
done < `$P/$list
sync; blockdev --flushbufs $script:loop; blockdev --setro $script:loop
echo cut-off after $k ranges
"@ | Out-Null
}

"=== T0 setup"
NewStandin
Expect "status on stock" (Rom $Pack "Status") "STATE_CODE=STOCK"

"=== T1 install / restore round trip"
Expect "install" (Rom $Pack "Install" @("-Yes", "-NoReboot")) "RESULT: OK"
ExpectHash "after install = Aura image" $NEW
Expect "device is read-only again" (Sh "blockdev --getro $script:loop") "^1"
Expect "status on Aura" (Rom $Pack "Status") "STATE_CODE=AURA"
Expect "restore" (Rom $Pack "Restore" @("-Yes", "-NoReboot")) "RESULT: OK"
ExpectHash "after restore = stock image" $OLD
if ($Quick) {
    Sh "losetup -d $script:loop 2>/dev/null; rm -f $standin /data/local/tmp/tf.sh" | Out-Null
    if ($script:fails -eq 0) { "ALL TESTS PASSED (quick)"; exit 0 } else { "FAILED TESTS: $script:fails"; exit 1 }
}

"=== T2 interrupted runs are finished (cut after K ranges in write order)"
PartialWrite "order-apply.txt" "forward.bin" 5 | Out-Null
Expect "apply cut after 5 ranges is reported as interrupted" (Rom $Pack "Status") "STATE_CODE=PARTIAL"
Expect "install finishes it" (Rom $Pack "Install" @("-Yes", "-NoReboot")) "RESULT: OK"
ExpectHash "  = Aura image" $NEW
PartialWrite "order-revert.txt" "reverse.bin" 12 | Out-Null
Expect "revert cut after 12 ranges is reported as interrupted" (Rom $Pack "Status") "STATE_CODE=PARTIAL"
Expect "restore finishes it" (Rom $Pack "Restore" @("-Yes", "-NoReboot")) "RESULT: OK"
ExpectHash "  = stock image" $OLD
PartialWrite "order-apply.txt" "forward.bin" 25 | Out-Null
Expect "apply cut after 25 of 26 ranges is reported as interrupted" (Rom $Pack "Status") "STATE_CODE=PARTIAL"
Expect "install finishes it" (Rom $Pack "Install" @("-Yes", "-NoReboot")) "RESULT: OK"
ExpectHash "  = Aura image" $NEW
Expect "restore (back to stock for the next tests)" (Rom $Pack "Restore" @("-Yes", "-NoReboot")) "RESULT: OK"
ExpectHash "  = stock image" $OLD

"=== T3 refusals"
Sh "blockdev --setrw $script:loop; dd if=/dev/zero of=$script:loop bs=4096 seek=100000 count=1 conv=notrunc 2>/dev/null; sync; blockdev --setro $script:loop" | Out-Null
Expect "foreign contents: status" (Rom $Pack "Status") "STATE_CODE=UNKNOWN"
Expect "foreign contents: install refuses" (Rom $Pack "Install" @("-Yes", "-NoReboot")) "Nothing was written"
Expect "foreign contents: restore refuses" (Rom $Pack "Restore" @("-Yes", "-NoReboot")) "Nothing was written"
Sh "blockdev --setrw $script:loop; dd if=$stockImg of=$script:loop bs=4096 skip=100000 seek=100000 count=1 conv=notrunc 2>/dev/null; sync; blockdev --setro $script:loop" | Out-Null
ExpectHash "block repaired from the stock image" $OLD

# damaged copies of the pack
function BadPack([string]$name, [scriptblock]$damage) {
    $d = "$Pack-$name"
    if (Test-Path $d) { Remove-Item -Recurse -Force $d }
    Copy-Item -Recurse $Pack $d
    & $damage $d
    return $d
}
$p = BadPack "trunc" { param($d) $fs = [IO.File]::Open("$d\patch\forward.bin", "Open", "Write"); $fs.SetLength($fs.Length - 4096); $fs.Close() }
Expect "truncated forward.bin is refused" (Rom $p "Install" @("-Yes", "-NoReboot")) "forward.bin is damaged|wrong size"
$p = BadPack "ranges" { param($d) $f = "$d\patch\ranges.txt"; $t = [IO.File]::ReadAllText($f); [IO.File]::WriteAllText($f, $t.Replace(" 0 ", " 1 "), (New-Object Text.UTF8Encoding($false))) }
Expect "edited ranges.txt is refused" (Rom $p "Install" @("-Yes", "-NoReboot")) "does not belong to this pack|range table"
$p = BadPack "order" { param($d) $f = "$d\patch\order-apply.txt"; $l = Get-Content $f; $l = @($l[1], $l[0]) + $l[2..($l.Count - 1)]; [IO.File]::WriteAllText($f, (($l -join "`n") + "`n"), (New-Object Text.UTF8Encoding($false))) }
Expect "re-ordered order-apply.txt is refused" (Rom $p "Install" @("-Yes", "-NoReboot")) "does not belong to this pack"
# bin changed AND its file hash fixed up: only the per-range hash can catch it
$p = BadPack "slice" { param($d)
    $f = "$d\patch\forward.bin"; $b = [IO.File]::ReadAllBytes($f); $b[100] = $b[100] -bxor 0xFF; [IO.File]::WriteAllBytes($f, $b)
    $h = (Get-FileHash $f -Algorithm SHA256).Hash.ToLower()
    $hf = "$d\patch\hashes.txt"; (Get-Content $hf) -replace "^forward_sha256=.*", "forward_sha256=$h" | Set-Content $hf -Encoding ascii
}
Expect "damaged range data (file hash fixed up) is refused" (Rom $p "Install" @("-Yes", "-NoReboot")) "data of the range at block \d+ is damaged"
# a self-consistent pack whose table points at the wrong blocks: only the comparison with the device can catch it
$p = BadPack "table" { param($d)
    python -c @"
import hashlib, os
d = r'$d\patch'
def sha(p): return hashlib.sha256(open(p, 'rb').read()).hexdigest()
rng = open(os.path.join(d, 'ranges.txt')).read().split('\n')
idx = max(i for i, l in enumerate(rng) if l.strip())              # the last (highest) range
last = rng[idx].split(); old_off = last[2]
last[0] = str(int(last[0]) + 1)                                  # still ascending and inside the image, but the wrong blocks
rng[idx] = ' '.join(last)
open(os.path.join(d, 'ranges.txt'), 'w', newline='\n').write('\n'.join(rng))
for name in ('order-apply.txt', 'order-revert.txt'):
    ls = open(os.path.join(d, name)).read().split('\n')
    ls = [(' '.join(last) if l.split() and l.split()[2] == old_off else l) for l in ls]
    open(os.path.join(d, name), 'w', newline='\n').write('\n'.join(ls))
h = open(os.path.join(d, 'hashes.txt')).read().split('\n')
out = []
for l in h:
    if l.startswith('ranges_sha256='): l = 'ranges_sha256=' + sha(os.path.join(d, 'ranges.txt'))
    if l.startswith('order_apply_sha256='): l = 'order_apply_sha256=' + sha(os.path.join(d, 'order-apply.txt'))
    if l.startswith('order_revert_sha256='): l = 'order_revert_sha256=' + sha(os.path.join(d, 'order-revert.txt'))
    out.append(l)
open(os.path.join(d, 'hashes.txt'), 'w', newline='\n').write('\n'.join(out))
"@
}
Expect "self-consistent but wrong table is refused" (Rom $p "Install" @("-Yes", "-NoReboot")) "range table does not match the device|REFUSED"
ExpectHash "nothing was written by any refusal" $OLD
Get-ChildItem "$Pack-*" -Directory | Remove-Item -Recurse -Force

"=== T4 a second run while an earlier one is still working"
Rom $Pack "Status" | Out-Null          # pushes the pack, so the background run below can use it
$bg = Start-Process -FilePath $Adb -ArgumentList @("-s", $Serial, "shell", "sh /data/local/tmp/aura-rom/aura-flash.sh apply /data/local/tmp/aura-rom/patch $script:loop") `
    -RedirectStandardOutput "$env:TEMP\bg.out" -RedirectStandardError "$env:TEMP\bg.err" -WindowStyle Hidden -PassThru
Start-Sleep -Seconds 8
Expect "second run is refused while the first works" (Rom $Pack "Install" @("-Yes", "-NoReboot")) "still working on the unit"
Stop-Process -Id $bg.Id -Force -ErrorAction SilentlyContinue   # the client dies; the unit-side run must go on (T6)
$deadline = (Get-Date).AddMinutes(8)
do { Start-Sleep -Seconds 10; $alive = (Sh "pgrep -f '[a]ura-flash.sh'") -join "" } while ($alive -match "\d" -and (Get-Date) -lt $deadline)
Expect "T6 client killed mid-run: the unit finished on its own" (Sh "tail -3 /data/local/tmp/aura-rom/flash.log") "RESULT: OK"
ExpectHash "  = Aura image" $NEW

"=== T5 finishing after a write whose follow-up never ran"
Expect "status hints that a restart is pending" (Rom $Pack "Status") "has not been restarted since"
Expect "install notices the pending restart" (Rom $Pack "Install" @("-Yes", "-NoReboot")) "Finishing that[\s\S]*prepared[\s\S]*DONE: Install finished"
Sh "setprop ro.aura.rom.version 1.0.0" | Out-Null
Expect "install: ROM is running -> nothing to do" (Rom $Pack "Install" @("-Yes", "-NoReboot")) "already installed and running"
Expect "restore (normal)" (Rom $Pack "Restore" @("-Yes", "-NoReboot")) "RESULT: OK"
Expect "restore again: restart pending -> follow-up only" (Rom $Pack "Restore" @("-Yes", "-NoReboot")) "Finishing that[\s\S]*undone[\s\S]*DONE: Restore finished"
ExpectHash "stock again" $OLD

"=== cleanup"
Sh "losetup -d $script:loop 2>/dev/null; rm -f $standin /data/local/tmp/tf.sh" | Out-Null
if ($script:fails -eq 0) { "ALL TESTS PASSED" } else { "FAILED TESTS: $script:fails"; exit 1 }
