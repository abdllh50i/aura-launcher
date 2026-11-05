# Home in the states the mockups draw and in the words they use (debug/HomeProbe.kt): shots\<Prefix>-<state>-<theme>.png. The map is whatever the emulator's place shows.
#   home-shots.ps1 -Serial emulator-5578 [-States home,moving,trip,bt,nocover,empty] [-Themes night,day] [-Lang ar|en] [-Prefix v2-home]
param([string]$Serial = "emulator-5578", [string[]]$States = @("home", "moving", "trip", "bt", "nocover", "empty"), [string[]]$Themes = @("night", "day"), [string]$Lang = "ar", [string]$Prefix = "v2-home", [int]$Wait = 900)
$adb = "E:\Android\Sdk\platform-tools\adb.exe"
$States = $States | ForEach-Object { $_ -split "," }
$Themes = $Themes | ForEach-Object { $_ -split "," }
function Sh([string]$c) { & $adb -s $Serial shell $c | Out-Null }
function Home([string]$a) { Sh "am broadcast -n com.abdllh.aura/.debug.HomeProbe $a" }
function Chrome([string]$a) { Sh "am broadcast -n com.abdllh.aura/.debug.ChromeProbe $a" }
function Q([string]$s) { "'" + $s + "'" }
$en = $Lang -eq "en"
# (the window again in that language, which stays: --es lang back by hand)
Home "--es lang $Lang"
Start-Sleep -Seconds 4
$song =if ($en) { "--es song 'Midnight Drive' --es artist 'The Coastline' --es src Naghm" } else { "--es song 'سهر الطريق' --es artist 'فرقة الساحل' --es src 'من نغم'" }
$songBt = if ($en) { "--es song 'Midnight Drive' --es artist 'The Coastline' --es src Bluetooth --ef at -1" } else { "--es song 'سهر الطريق' --es artist 'فرقة الساحل' --es src 'بلوتوث' --ef at -1" }
$third = if ($en) { "Jubail Mall" } else { "الجبيل مول" }
$dateW = if ($en) { "Tue, 3 Nov · 23 Jumada I" } else { "الثلاثاء، 3 نوفمبر · 23 جمادى الأولى" }
$left = if ($en) { "38 min" } else { "بعد 38 د" }
$pray = if ($en) { "Maghrib 4:56" } else { "المغرب 4:56" }

foreach ($t in $Themes) {
    Home "--es theme $t"
    Start-Sleep -Milliseconds 1200
    foreach ($s in $States) {
        $gear = "P"
        Home "--es third $(Q $third)"
        Chrome "--ez status true --ei vol 12 --ez mute false --ef bar -1 --es word $(Q $pray) --es left $(Q $left) --es icon sunset"
        switch ($s) {
            "home" { Home "$song --es art sea --ei face 0 --es time 4:18 --es date $(Q $dateW)" }
            "moving" { $gear = "D"; Home "$song --es art sea --ei face 1 --ei kmh 87 --ei limit 120 --es time 7:20 --es date $(Q $dateW)" }
            "trip" {
                $gear = "D"
                if ($en) { $trip = "--es dist '2.4 km' --es road 'Prince Mohammed' --es eta '7:38 PM' --es left '5 min'" } else { $trip = "--es dist '2.4 كم' --es road 'الأمير محمد بن فهد' --es eta '7:38 م' --es left '5 دقائق'" }
                Home "$song --es art sea --ei face 2 $trip --es time 7:33 --es date $(Q $dateW)"
                Chrome "--es speed 112,120"
            }
            "bt" { Home "$songBt --es art sea --ei face 0 --es time 4:18 --es date $(Q $dateW)" }
            "nocover" { Home "$song --ei face 0 --es time 4:18 --es date $(Q $dateW)" }
            "empty" { Home "--ez nosong true --ei face 0 --es time 4:18 --es date $(Q $dateW)" }
        }
        Sh "am broadcast -a com.abdllh.aura.debug.GEAR --es gear $gear"
        Start-Sleep -Milliseconds $Wait
        & $adb -s $Serial shell "screencap -p /sdcard/aura_shot.png" | Out-Null
        & $adb -s $Serial pull /sdcard/aura_shot.png "D:\k2501-work\shots\$Prefix-$s-$t.png" | Out-Null
        "$Prefix-$s-$t"
    }
}
