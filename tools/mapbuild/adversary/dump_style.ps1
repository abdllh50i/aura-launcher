# Compiles the app's real nav\MapStyle.kt, nav\MapPalette.kt and nav\Poi.kt with the stubs in .\style_dump and writes the four style JSON files.
# Uses the Kotlin compiler already in Gradle's cache. Nothing in the app is changed.
#   powershell -File dump_style.ps1 -Out <folder> [-Work <folder for jars and classes>]
param([Parameter(Mandatory)][string]$Out, [string]$Work = (Join-Path $env:TEMP 'style-dump'))
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$app = Join-Path $here '..\..\..\app\src\main\java\com\abdllh\aura\nav'
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
New-Item -ItemType Directory -Force "$Work\jars", "$Work\classes" | Out-Null
foreach ($n in 'kotlin-compiler-embeddable-2.0.21.jar', 'kotlin-stdlib-2.0.21.jar', 'kotlin-script-runtime-2.0.21.jar', 'kotlin-daemon-embeddable-2.0.21.jar', 'trove4j-1.0.20200330.jar', 'annotations-13.0.jar', 'kotlin-reflect-1.9.23.jar', 'kotlinx-coroutines-core-jvm-1.7.3.jar') {
    if (-not (Test-Path "$Work\jars\$n")) {
        $f = Get-ChildItem $cache -Recurse -Filter $n | Select-Object -First 1
        Copy-Item $f.FullName "$Work\jars\$n"
    }
}
$cp = (Get-ChildItem "$Work\jars\*.jar" | ForEach-Object { $_.FullName }) -join ';'
$std = "$Work\jars\kotlin-stdlib-2.0.21.jar"
$src = @(Get-ChildItem "$here\style_dump\*.kt" | ForEach-Object { $_.FullName }) + @("$app\MapStyle.kt", "$app\MapPalette.kt", "$app\Poi.kt")
& java "-Djava.io.tmpdir=$Work" -cp $cp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -jvm-target 17 -cp $std -d "$Work\classes" @src
if ($LASTEXITCODE -ne 0) { throw "kotlinc failed" }
& java -cp "$Work\classes;$std" styledump.MainKt $Out
