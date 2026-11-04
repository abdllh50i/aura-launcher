# On this PC: brings the IPAs of the last green run of the ios-app branch to D:\k2501-work\out\ios\, each file whole or not at all.
#   powershell -File ios\tools\fetch_ipa.ps1
param([string]$Run = "", [string]$Out = "D:\k2501-work\out\ios")
$repo = "abdllh50i/aura-launcher"
if (-not $Run) {
    $Run = gh run list --repo $repo --branch ios-app --status success --workflow "Amri iPhone" --limit 1 --json databaseId --jq ".[0].databaseId"
}
if (-not $Run) { throw "no green run on ios-app" }
$temp = Join-Path $env:TEMP "amri-ipa-$Run"
if (Test-Path $temp) { Remove-Item -Recurse -Force $temp }
New-Item -ItemType Directory -Force $temp | Out-Null
gh run download $Run --repo $repo --name Amri-IPA --dir $temp
if (-not (Test-Path "$temp\Amri-unsigned.ipa")) { throw "the run has no Amri-unsigned.ipa" }
New-Item -ItemType Directory -Force $Out | Out-Null
Copy-Item "$temp\*.ipa" $Out -Force
Remove-Item -Recurse -Force $temp
Get-ChildItem "$Out\*.ipa" | ForEach-Object { "{0}  {1:N2} MB  run {2}" -f $_.FullName, ($_.Length / 1MB), $Run }
