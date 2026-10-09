# Builds everything that gets published for one version:
#   out\release\aura-<ver>.apk (+ .sha256)          the launcher, for in-app updates (GitHub release asset)
#   out\release\aura-rom-<ver>.zip (+ .sha256)     ROM installer pack: patch + scripts + docs
# Requirements: the emulator workshop is running (see rom\README.md) and holds the stock system.img.
param(
    [string]$Version = "1.0.0",
    [string]$Serial = "emulator-5570",
    [string]$Stock = "D:\k2501-work\parts\system.img",
    [string]$Out = "D:\k2501-work\out",
    [switch]$SkipVerify,
    [switch]$RepackOnly   # keep the APK and system-aura.img already built; only redo the patch pack, the zip and the checks
)
$ErrorActionPreference = "Continue"
$env:PYTHONDONTWRITEBYTECODE = "1"
$proj = Split-Path $PSScriptRoot -Parent
$rel = Join-Path $Out "release"
New-Item -ItemType Directory -Force -Path $rel | Out-Null
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
$env:ANDROID_HOME = "E:\Android\Sdk"
if ($Version -notmatch '^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$') { throw "Version must look like 1.2.3 or 1.2.3-beta.4 (got '$Version')" }
$apk = Join-Path $rel "aura-$Version.apk"
$img = Join-Path $Out "system-aura.img"

if (-not $RepackOnly) {
    # ---- 1. APK  (the versionCode is derived from the version name by app\build.gradle.kts: 1.3.0-beta.2 < 1.3.0)
    Push-Location $proj
    & ".\gradlew.bat" :app:assembleRelease --offline "-PappVersionName=$Version" | Out-Host
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw "gradle failed" }
    Pop-Location
    Copy-Item (Join-Path $proj "app\build\outputs\apk\release\app-release.apk") $apk -Force
    $apkHash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
    Set-Content -Path "$apk.sha256" -Value "$apkHash  aura-$Version.apk" -Encoding ascii
    & "E:\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs $apk 2>&1 | Select-String "Signer #1 certificate SHA-256" | ForEach-Object { $_.Line }
    "APK  : $apk  ($([math]::Round((Get-Item $apk).Length/1KB)) KB)  sha256=$apkHash"

    # ---- 2. ROM image (uses the APK built above)
    Set-Content -Path (Join-Path $PSScriptRoot "ROM_VERSION") -Value $Version -Encoding ascii -NoNewline
    & (Join-Path $PSScriptRoot "build-rom.ps1") -SkipGradle -Serial $Serial -Out $Out | Out-Host
}
if (-not (Test-Path $img)) { throw "ROM image missing" }
if (-not (Test-Path $apk)) { throw "release APK missing" }

# ---- 3. independent verification (file-level diff against stock)
if (-not $SkipVerify) {
    "== file-level diff against stock"
    python (Join-Path $PSScriptRoot "tools\imgdiff.py") $Stock $img | Out-Host
}

# ---- 4. patch pack
$patch = Join-Path $Out "patch"
if (Test-Path $patch) { Remove-Item -Recurse -Force $patch }
python (Join-Path $PSScriptRoot "tools\make_patch.py") $Stock $img $patch --gap 16 | Out-Host
if ($LASTEXITCODE -ne 0) { throw "make_patch failed" }
if (-not $SkipVerify) {
    "== write-order proof: every intermediate state of the write, both directions, must be a consistent file system"
    python (Join-Path $PSScriptRoot "tools\check_order.py") $Stock $img $patch --order apply,revert | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "an intermediate write state is not consistent - do not ship this pack" }
}

# ---- 5. installer pack
$pk = Join-Path $rel "aura-rom-$Version"
if (Test-Path $pk) { Remove-Item -Recurse -Force $pk }
New-Item -ItemType Directory -Force -Path "$pk\patch" | Out-Null
foreach ($f in "forward.bin", "reverse.bin", "ranges.txt", "order-apply.txt", "order-revert.txt", "hashes.txt", "manifest.json") { Copy-Item (Join-Path $patch $f) "$pk\patch\$f" }
foreach ($f in "aura-flash.sh", "aura-rom.ps1", "aura-install.py", "install-linux.sh", "install.bat", "restore.bat", "status.bat") { Copy-Item (Join-Path $PSScriptRoot "flash\$f") "$pk\$f" }
# the Linux-side files must have LF line ends whatever the checkout did (a CR in the shebang line breaks ./install-linux.sh)
foreach ($f in "aura-flash.sh", "aura-install.py", "install-linux.sh") {
    $t = [IO.File]::ReadAllText("$pk\$f") -replace "`r`n", "`n"
    [IO.File]::WriteAllText("$pk\$f", $t, (New-Object Text.UTF8Encoding($false)))
}
foreach ($f in "README.ar.md", "README.md") { if (Test-Path (Join-Path $proj $f)) { Copy-Item (Join-Path $proj $f) "$pk\$f" } }
$zip = Join-Path $rel "aura-rom-$Version.zip"
python (Join-Path $PSScriptRoot "tools\make_zip.py") $pk $zip | Out-Host   # portable entry names (forward slashes)
if (-not (Test-Path $zip)) { throw "zip was not created" }
$zh = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
Set-Content -Path "$zip.sha256" -Value "$zh  aura-rom-$Version.zip" -Encoding ascii
"ROM pack: $zip  ($([math]::Round((Get-Item $zip).Length/1MB,1)) MB)  sha256=$zh"
Get-Content (Join-Path $patch "hashes.txt")
