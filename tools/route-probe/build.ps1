# Makes the route probe's test program with the NDK. -Out: where; the good path's stand-in for the emulator with -Target x86_64.
#   build.ps1 [-Target aarch64] [-Out app\src\main\assets\probe\hello-aarch64]
param([string]$Target = "aarch64", [string]$Out = "")
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$clang = "E:\Android\Sdk\ndk\27.1.12297006\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe"
if (-not $Out) { $Out = Join-Path $root "app\src\main\assets\probe\hello-$Target" }
New-Item -ItemType Directory -Force (Split-Path $Out -Parent) | Out-Null
$flags = @("--target=$Target-linux-android29", "-nostdlib", "-static", "-fuse-ld=lld", "-s", "-Wl,--build-id=none", "-Wl,-z,max-page-size=4096", "-Wl,-z,noseparate-code")
& $clang @flags -o $Out (Join-Path $PSScriptRoot "hello.S")
if ($LASTEXITCODE -ne 0) { "BUILD FAILED"; exit 1 }
"$Out $((Get-Item $Out).Length) bytes"
