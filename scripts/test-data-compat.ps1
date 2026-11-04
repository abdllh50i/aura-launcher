# The written data-compatibility test: 1.19.0 must start on whatever the newer version left. Emulator only.
#   test-data-compat.ps1 -Old <1.19.0 APK with x86_64 in it> [-New <newer APK>] [-Serial emulator-5574]
# The old APK: build the tag with its debug config ("git archive v1.19.0", -PappVersionName=1.19.0), or -PwithEmulatorAbi;
# the published asset is ARM only and the emulator refuses it. Needs a newer build already running, or -New to install one.
#
# It does what a rollback does to the data (pm install -r -d, nothing else), then asks: does 1.19.0's process come up, does
# home stay in front, and did anything crash? It also fails on a stored preference of another type than 1.19 reads
# (the debug check in system/PrefTypes.kt says the same from inside; this says it from what 1.19 itself did with them).
param(
    [Parameter(Mandatory = $true)][string]$Old,
    [string]$New = "",
    [string]$Serial = "emulator-5574",
    [int]$Settle = 40
)
$adb = "E:\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.abdllh.aura"
$script:fails = 0
function Sh([string]$c) { (& $adb -s $Serial shell $c 2>&1) -join "`n" }
function Check([string]$name, [bool]$ok, [string]$detail = "") {
    if ($ok) { "PASS  $name" } else { $script:fails++; "FAIL  $name  $detail" }
}
function Installed() { (Sh "dumpsys package $pkg | grep -E 'versionCode=|versionName=' | head -n 2").Trim() -replace "\s+", " " }
function Home() { Sh "am start -a android.intent.action.MAIN -c android.intent.category.HOME" | Out-Null }

& $adb -s $Serial root | Out-Null
Start-Sleep 2
if ($New) {
    "installing the newer build"
    & $adb -s $Serial install -r -d $New | Select-Object -Last 1
}
Sh "cmd package set-home-activity $pkg/.home.HomeActivity" | Out-Null
Home
"newer version: $(Installed); letting it run $Settle s, so it writes what it writes"
Start-Sleep $Settle
$before = Sh "ls -l /data/data/$pkg/shared_prefs/ | md5sum; ls /data/data/$pkg/files | wc -l"
"going down to 1.19.0 the way rollback.sh does"
Sh "rm -f /data/data/$pkg/files/last_crash.txt" | Out-Null
& $adb -s $Serial logcat -c
& $adb -s $Serial push $Old /data/local/tmp/compat-old.apk | Out-Null
$r = Sh "pm install -r -d /data/local/tmp/compat-old.apk"
Check "pm install -r -d" ($r -match "Success") $r
$v = Installed
Check "versionCode is 1.19.0's" ($v -match "versionCode=1190099") $v
Home
"letting 1.19.0 run $Settle s on that data"
Start-Sleep $Settle
$crashes = (& $adb -s $Serial logcat -d -b crash 2>&1 | Select-String "FATAL EXCEPTION|Process: $pkg") -join "`n"
Check "nothing crashed" (-not $crashes) $crashes
$lastCrash = Sh "[ -f /data/data/$pkg/files/last_crash.txt ] && cat /data/data/$pkg/files/last_crash.txt | head -n 5"
Check "no last_crash.txt from 1.19.0" (-not ($lastCrash -match "Exception|Error")) $lastCrash
$pid0 = (Sh "pidof $pkg").Trim()
Check "1.19.0's process is up" ($pid0 -match "^\d+") $pid0
$top = Sh "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 2"
Check "home is in front" ($top -match "HomeActivity") $top
$disabled = (Sh "getprop persist.aura.disabled").Trim()
Check "the crash guard did not switch it off" ($disabled -ne "1") "persist.aura.disabled=$disabled"
Sh "rm -f /data/local/tmp/compat-old.apk" | Out-Null
"prefs and files before: $before"
if ($script:fails -eq 0) { "ALL PASSED: 1.19.0 starts on what the newer version left" } else { "FAILED: $script:fails"; exit 1 }
