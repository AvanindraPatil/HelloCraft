# Builds everything and packs the release:  release\HelloCraft-<version>.zip
#   powershell -ExecutionPolicy Bypass -File tools\build_release.ps1 [-SkipTests]
# Needs the developer requirements (docs\DEVELOPING.md): Visual Studio C++ tools and JDK 25. Nothing in the project is
# changed; the output goes to release\ (delete it any time).
param([switch]$SkipTests)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Run($what, [scriptblock]$cmd) {
    Write-Output "== $what"
    & $cmd
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit code $LASTEXITCODE)" }
}

$version = ((Get-Content (Join-Path $root "fabric-mod\gradle.properties")) | Where-Object { $_ -match '^version=' }) -replace 'version=', ''
$name = "HelloCraft"
# Assembled in release\obj, never in an unzipped release someone may be playing from (release\<name> with a settings.ini).
$out = Join-Path $root "release\obj\package\$name"
$zip = Join-Path $root "release\$name-$version.zip"

if (-not $SkipTests) {
    $tests = Join-Path $root "ue4ss-mods\HnLink\tests"
    if (Test-Path (Join-Path $tests "node_modules")) {
        Push-Location $tests; Run "Lua tests" { npm test --silent }; Pop-Location
    } else { Write-Output "== Lua tests skipped (run 'npm install' in ue4ss-mods\HnLink\tests to enable them)" }
}
Run "hn_gfx.dll" { cmd /c "native\hn_gfx\build.bat" }
$obj = Join-Path $root "release\obj"   # not protocol\build: a running bridge locks the exe there
Run "hn_bridge.exe" { cmd /c "protocol\build_bridge.bat" "$obj" }
Push-Location (Join-Path $root "fabric-mod")
Run "Minecraft mod" { .\gradlew.bat releaseMods --console=plain -q }
Pop-Location

Write-Output "== packing $zip"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\scripts", "$out\files" | Out-Null

# the part that goes into Hello Neighbor: UE4SS (the tested build) + our two mods
$game = "$out\files\game"
Copy-Item (Join-Path $root "native\third_party\ue4ss") $game -Recurse
New-Item -ItemType Directory -Force "$game\ue4ss\Mods\HnGfx\dlls", "$game\ue4ss\Mods\HnLink\Scripts" | Out-Null
Remove-Item "$game\VERSION.txt"
Copy-Item (Join-Path $root "native\hn_gfx\build\hn_gfx.dll") "$game\ue4ss\Mods\HnGfx\dlls\main.dll"
Copy-Item (Join-Path $root "ue4ss-mods\HnLink\Scripts\main.lua") "$game\ue4ss\Mods\HnLink\Scripts\main.lua"

# the part that goes into Prism: the instance, with the mod and Fabric API
Copy-Item (Join-Path $root "tools\release\instance") "$out\files\instance" -Recurse
New-Item -ItemType Directory -Force "$out\files\instance\minecraft\mods" | Out-Null
Copy-Item (Join-Path $root "fabric-mod\build\release-mods\*.jar") "$out\files\instance\minecraft\mods"

# scripts and the files next to them
Copy-Item "$obj\hn_bridge.exe" $out
foreach ($f in "Install.bat", "Play.bat", "Uninstall.bat", "README.txt") { Copy-Item (Join-Path $root "tools\release\$f") $out }
foreach ($f in "install.ps1", "uninstall.ps1") { Copy-Item (Join-Path $root "tools\release\$f") "$out\scripts" }
foreach ($f in "start_mod.ps1", "hn-common.ps1") { Copy-Item (Join-Path $root "tools\$f") "$out\scripts" }
Copy-Item (Join-Path $root "LICENSE") $out

# licences of what is bundled or adapted
$mit = (Get-Content (Join-Path $root "LICENSE") -Raw) -replace '(?m)^Copyright.*$', 'Copyright (c) SkyCraft contributors'
@"
Third-party software in this package
====================================

UE4SS (https://github.com/UE4SS-RE/RE-UE4SS): MIT licence. The build and its licence text are in
  files\game\ue4ss (UE4SS.dll, LICENSE).

Fabric API (https://github.com/FabricMC/fabric): Apache License 2.0, https://www.apache.org/licenses/LICENSE-2.0
  The jar in files\instance\minecraft\mods carries its own licence file.

MinHook (https://github.com/TsudaKageyu/minhook), linked into hn_gfx.dll:

$((Get-Content (Join-Path $root "native\third_party\minhook\LICENSE.txt") -Raw).TrimStart([char]0xFEFF))

SkyCraft (https://github.com/chasmlol/SkyCraft): parts of the Minecraft mod (frame export, input, entity export) are
adapted from it. MIT licence:

$mit
"@ | Set-Content "$out\THIRD-PARTY-NOTICES.txt"

if (Test-Path $zip) { Remove-Item $zip -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($out, $zip, [IO.Compression.CompressionLevel]::Optimal, $true)
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
"$hash  $(Split-Path -Leaf $zip)" | Set-Content (Join-Path $root "release\SHA256SUMS.txt")   # for the release notes
$mb = [math]::Round((Get-Item $zip).Length / 1MB, 1)
Write-Output "== done: $zip ($mb MB)"
