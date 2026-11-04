# Builds out\places.db and out\places.mbtiles and checks them. Each step's output is in places\logs. Everything is under -Work (D:, never C:).
#   powershell -File build_all_places.ps1 [-Work D:\k2501-work\mapbuild]
# Needs what fetch_overture.py and osm_extract.py made, and the Overture places rows (cached in places\overture-sa.jsonl after the first run).
param([string]$Work = 'D:\k2501-work\mapbuild', [string]$OverturePlaces = 'D:\k2501-work\tmp\v2\scratch\maps\overture-gcc-rows.parquet')
$env:TEMP = "$Work\tmp"; $env:TMP = "$Work\tmp"; $env:PYTHONDONTWRITEBYTECODE = '1'
$here = $PSScriptRoot
$logs = "$Work\places\logs"
New-Item -ItemType Directory -Force $logs | Out-Null
$t0 = Get-Date
function step($name, [string[]]$pyArgs) {
    $t = Get-Date
    & python -I @pyArgs > "$logs\$name.log" 2> "$logs\$name.err"
    "{0}: exit {1}, {2:n1} s" -f $name, $LASTEXITCODE, ((Get-Date) - $t).TotalSeconds
}
step 'build-places' @("$here\build_places.py", '--work', "$Work\places", '--out', "$Work\tmp\places.new.db", '--overture-places', $OverturePlaces, '--overture-dir', "$Work\src\overture", '--additions', "$Work\places\additions.json", '--prefix=')
Move-Item -Force "$Work\tmp\places.new.db" "$Work\out\places.db"
step 'vectors' @("$here\tests\run_vectors.py", "$Work\out\places.db")
step 'search-tests' @("$here\search_ref.py", "$Work\out\places.db", '--test', "$here\tests\search_tests.json", '--golden', "$Work\out\search-golden.json")
step 'translit' @("$here\tests\test_translit.py", "$Work\out\places.db")
step 'self-retrieval' @("$here\tests\self_retrieval.py", "$Work\out\places.db", '--n', '1000')
step 'poi-tiles' @("$here\build_poi_tiles.py", '--db', "$Work\out\places.db", '--out', "$Work\out\places.mbtiles")
step 'check-tiles' @("$here\check_poi_tiles.py", '--tiles', "$Work\out\places.mbtiles", '--db', "$Work\out\places.db", '--json', "$Work\places\tiles-check.json")
"all: {0:n0} s" -f ((Get-Date) - $t0).TotalSeconds
