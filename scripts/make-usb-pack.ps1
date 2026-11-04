# Copies the map pack to a USB stick or SD card, for the unit to install from (Settings: the same chunks and hashes as a download, no internet).
#   make-usb-pack.ps1 -Stick E:                               the pack built on this PC, to E:\amri-maps
#   make-usb-pack.ps1 -Stick E: -Pack D:\somewhere\pack       another pack folder
# What it asks of the stick:
#  - Free space: the chunks and the notices, about 390 MB for the Saudi pack. It says the numbers and stops if they do not fit. Nothing already on the stick is touched, except an
#    amri-maps folder of an earlier pack, whose files are replaced.
#  - Format: FAT32 is the one to use. Every file in the pack is far under FAT32's 4 GB limit (largest chunk 57 MB), and FAT32 is what every car unit reads. exFAT and NTFS are not
#    known to work on this firmware: nobody has tried, and it warns about them.
# Every file is read back and checked against the manifest's SHA-256 at the end, so a stick that lies about its size or a copy cut short shows here and not in the car.
param(
    [Parameter(Mandatory = $true)][string]$Stick,
    [string]$Pack = "D:\k2501-work\mapbuild\out\pack"
)
$ErrorActionPreference = "Stop"

$root = $Stick.TrimEnd("\", "/")
if ($root -match "^[A-Za-z]$") { $root = "${root}:" }
if (-not (Test-Path ($root + "\"))) { Write-Host "No drive or folder at $root. Is the stick plugged in?"; exit 1 }
$manifestPath = Join-Path $Pack "manifest.json"
if (-not (Test-Path $manifestPath)) { Write-Host "No manifest.json in $Pack. Build the pack first (tools\mapbuild\make_pack.py)."; exit 1 }

$raw = [IO.File]::ReadAllText($manifestPath, [Text.Encoding]::UTF8)
$manifest = $raw | ConvertFrom-Json
$files = New-Object System.Collections.ArrayList
foreach ($p in $manifest.pieces) { foreach ($c in $p.chunks) { [void]$files.Add([pscustomobject]@{ name = $c.url; bytes = [long]$c.bytes; sha = $c.sha256 }) } }
foreach ($n in $manifest.notices) { [void]$files.Add([pscustomobject]@{ name = $n.url; bytes = [long]$n.bytes; sha = $n.sha256 }) }
$need = ($files | Measure-Object -Property bytes -Sum).Sum + (Get-Item $manifestPath).Length
foreach ($f in $files) {
    $src = Join-Path $Pack $f.name
    if (-not (Test-Path $src)) { Write-Host "$($f.name) is missing from $Pack."; exit 1 }
    if ((Get-Item $src).Length -ne $f.bytes) { Write-Host "$($f.name) in $Pack is not the size the manifest says."; exit 1 }
}

$mb = { param($n) "{0:N0} MB" -f ($n / 1MB) }
$dest = Join-Path $root "amri-maps"
$already = 0L
if (Test-Path $dest) { $already = (Get-ChildItem $dest -File | Measure-Object -Property Length -Sum).Sum; if ($null -eq $already) { $already = 0L } }

$letter = if ($root -match "^([A-Za-z]):$") { $Matches[1] } else { $null }
$free = $null
if ($letter) {
    $vol = Get-Volume -DriveLetter $letter -ErrorAction SilentlyContinue
    if ($vol) {
        $free = [long]$vol.SizeRemaining
        $fs = $vol.FileSystem
        Write-Host "The stick: $(& $mb $vol.Size), $(& $mb $free) free, format $fs."
        if ($fs -ne "FAT32") {
            Write-Host "WARNING: this stick is $fs. FAT32 is the format known to every car unit; $fs has not been tried on this firmware. If the unit does not see it, format it as FAT32 (for a stick over 32 GB, a third-party tool: Windows' own only offers FAT32 up to 32 GB)."
        }
    }
}
if ($null -eq $free) {
    $free = (Get-PSDrive -Name $root.Substring(0, 1) -ErrorAction SilentlyContinue).Free
    if ($null -eq $free) { $free = [long]::MaxValue }
}
Write-Host "The pack $($manifest.id): $($files.Count) files, $(& $mb $need) to copy."
Write-Host "On the unit it takes $(& $mb ($manifest.pieces | Measure-Object -Property size -Sum).Sum), and the unit needs $(& $mb $manifest.needBytes) free before it starts."
if ($free + $already -lt $need) {
    Write-Host "Not enough room on the stick: $(& $mb ($free + $already)) available, $(& $mb $need) needed."
    exit 1
}

New-Item -ItemType Directory -Force $dest | Out-Null
# files of another pack go first, so the stick never holds two packs mixed
$keep = @{ "manifest.json" = $true }
foreach ($f in $files) { $keep[$f.name] = $true }
Get-ChildItem $dest -File | Where-Object { -not $keep.ContainsKey($_.Name) } | Remove-Item -Force
# the manifest last: a stick pulled in the middle has no manifest, so the unit does not offer half a pack
$target = Join-Path $dest "manifest.json"
if (Test-Path $target) { Remove-Item $target -Force }

$i = 0
$t0 = Get-Date
foreach ($f in $files) {
    $i++
    $to = Join-Path $dest $f.name
    if ((Test-Path $to) -and (Get-Item $to).Length -eq $f.bytes -and (Get-FileHash $to -Algorithm SHA256).Hash.ToLower() -eq $f.sha) {
        Write-Host "[$i/$($files.Count)] $($f.name): already there"
        continue
    }
    Write-Host "[$i/$($files.Count)] $($f.name) ($(& $mb $f.bytes))"
    Copy-Item (Join-Path $Pack $f.name) $to -Force
}
Copy-Item $manifestPath $target -Force

Write-Host "Reading back..."
$bad = 0
foreach ($f in $files) {
    $to = Join-Path $dest $f.name
    if ((Get-FileHash $to -Algorithm SHA256).Hash.ToLower() -ne $f.sha) { Write-Host "BAD: $($f.name)"; $bad++ }
}
if ($bad -gt 0) { Write-Host "$bad files are wrong on the stick. It may be failing: run this again, or use another stick."; exit 1 }
$took = [int]((Get-Date) - $t0).TotalSeconds
Write-Host "Done in $took s, all $($files.Count) files checked. Eject the stick, plug it into the unit: Settings, map, install from USB."
