# The pack's sea and fonts in the real renderer: the debug app's MapLibre Native 10.3.7 on an x86_64 emulator, no app code changed.
# Tiles come from serve_logged.py through adb reverse (pref ofmTileTemplate). The style asks for "Noto Sans Regular/Bold", so for the length of the test glyphs.db's two stacks are put
# into a small files/offline/map.db under those names; the app's real file, if any, is kept aside. -Restore puts everything back. Take the emulator's lock first.
#   & native_pack.ps1 -Serial emulator-5570 -Glyphs <glyphs.db> -Out <folder> [-Port 8802]      (start serve_logged.py first)
#   & native_pack.ps1 -Serial emulator-5570 -Restore [-Port 8802]
param([Parameter(Mandatory)][string]$Serial, [string]$Glyphs, [string]$Out, [int]$Port = 8802, [switch]$Restore)
$ErrorActionPreference = 'Continue'
$app = '/data/data/com.abdllh.aura'
$prefs = "$app/shared_prefs/aura.xml"
$backup = '/data/local/tmp/aura.xml.adversary-backup'
$dbBackup = '/data/local/tmp/map.db.pack-backup'
function Sh([string]$c) {
    $f = New-TemporaryFile
    [IO.File]::WriteAllText($f.FullName, $c + "`n")
    adb -s $Serial push $f.FullName /data/local/tmp/pack_cmd.sh 2>$null | Out-Null
    Remove-Item $f.FullName -Confirm:$false
    adb -s $Serial shell "su 0 sh /data/local/tmp/pack_cmd.sh"
}
if ($Restore) {
    Sh "am force-stop com.abdllh.aura; cat $backup > $prefs; rm -f $backup; rm -f $app/files/offline/map.db $app/files/offline/map.db-wal $app/files/offline/map.db-shm; if [ -f $dbBackup ]; then cp -p $dbBackup $app/files/offline/map.db; rm -f $dbBackup; fi; rm -f /data/local/tmp/native_setpref.sh /data/local/tmp/adv.png /data/local/tmp/map.db.new /data/local/tmp/pack_cmd.sh $app/files/mbgl-offline.db*"
    adb -s $Serial reverse --remove "tcp:$Port" | Out-Null
    "restored"
    return
}
New-Item -ItemType Directory -Force $Out | Out-Null
$seed = Join-Path $Out 'map-seed.db'
python -I -c "import os,sqlite3,sys; a=sqlite3.connect(f'file:{sys.argv[1]}?mode=ro',uri=True); os.path.exists(sys.argv[2]) and os.remove(sys.argv[2]); b=sqlite3.connect(sys.argv[2]); b.execute('CREATE TABLE tiles (k INTEGER PRIMARY KEY, data BLOB NOT NULL)'); b.execute('CREATE TABLE glyphs (name TEXT PRIMARY KEY, data BLOB NOT NULL)'); b.execute('CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)'); b.executemany('INSERT INTO glyphs VALUES(?,?)', [(n.replace('AMRI Map Sans','Noto Sans').replace('SemiBold','Bold'),d) for n,d in a.execute('SELECT name,data FROM glyphs')]); b.commit(); b.close()" $Glyphs $seed
adb -s $Serial reverse "tcp:$Port" "tcp:$Port" | Out-Null
adb -s $Serial push $seed /data/local/tmp/map.db.new | Out-Null
adb -s $Serial push "$PSScriptRoot\native_setpref.sh" /data/local/tmp/native_setpref.sh | Out-Null
Sh "am force-stop com.abdllh.aura; [ -f $backup ] || cp $prefs $backup; [ -f $app/files/offline/map.db ] && [ ! -f $dbBackup ] && cp -p $app/files/offline/map.db $dbBackup; rm -f $app/files/offline/map.db*; cp /data/local/tmp/map.db.new $app/files/offline/map.db; chown `$(stat -c %u:%g $app/files) $app/files/offline/map.db; chmod 600 $app/files/offline/map.db; restorecon $app/files/offline/map.db"
# lang, theme, name, lon, lat, zoom steps from the app's start zoom: + is a tap on the plus button, - on the minus
$shots = @(
    @('ar', 'light', 'khobar', 50.2158, 26.2945, 0), @('ar', 'light', 'gulf-out', 50.2158, 26.2945, -6), @('ar', 'light', 'gulf-far', 50.2158, 26.2945, -8),
    @('ar', 'light', 'no-row', 51.58081, 25.195, 0), @('ar', 'light', 'causeway', 50.33, 26.185, 0), @('ar', 'light', 'jeddah', 39.11, 21.6, -4), @('ar', 'light', 'riyadh', 46.6745, 24.7113, 0),
    @('ar', 'dark', 'jubail', 49.6586, 27.0046, 0), @('en', 'light', 'khobar', 50.2158, 26.2945, 0), @('en', 'light', 'abha', 42.505, 18.216, 0)
)
$last = ''
foreach ($s in $shots) {
    $lang, $theme, $name, $lon, $lat, $steps = $s
    $key = "$lang-$theme"
    $file = "$key-$name"
    adb -s $Serial emu geo fix $lon $lat | Out-Null
    if ($key -ne $last) {
        Sh "am force-stop com.abdllh.aura; rm -f $app/files/mbgl-offline.db*; sh /data/local/tmp/native_setpref.sh $Port $lang $theme" | Out-Null
        $last = $key
    }
    Sh "logcat -c; am force-stop com.abdllh.aura; am start -n com.abdllh.aura/.nav.MapsActivity" | Out-Null
    Start-Sleep -Seconds 9
    adb -s $Serial emu geo fix $lon $lat | Out-Null
    $bx = if ($lang -eq 'ar') { 48 } else { 975 } # the zoom buttons change sides with the language
    for ($i = 0; $i -lt [math]::Abs($steps); $i++) {
        if ($steps -gt 0) { adb -s $Serial shell "input tap $bx 158" | Out-Null } else { adb -s $Serial shell "input tap $bx 222" | Out-Null }
        Start-Sleep -Milliseconds 900
    }
    Start-Sleep -Seconds 7
    Sh "screencap -p /data/local/tmp/adv.png; chmod 644 /data/local/tmp/adv.png" | Out-Null
    adb -s $Serial pull /data/local/tmp/adv.png "$Out\$file.png" 2>$null | Out-Null
    $bad = (Sh "logcat -d | grep -E 'Mbgl|mbgl|MapGuard|FATAL|signal 11|tombstone' | grep -vE 'HttpRequest.*(successful|expected)|ConnectivityReceiver|EGLConfig|GPU Identifier' | tail -3")
    "$file"
    if ($bad) { $bad | ForEach-Object { "   $_" } }
}
