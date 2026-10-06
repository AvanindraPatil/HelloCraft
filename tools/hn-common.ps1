# Shared by start_mod.ps1, install.ps1 and uninstall.ps1 (dot-sourced): finding Hello Neighbor and Prism Launcher.

# Hello Neighbor's folder: every Steam library listed in steamapps\libraryfolders.vdf, looking for "Hello Neighbor".
function Find-HelloNeighbor {
    $steam = (Get-ItemProperty "HKCU:\Software\Valve\Steam" -ErrorAction SilentlyContinue).SteamPath
    if (-not $steam) { return $null }
    $libs = @($steam)
    $vdf = Join-Path $steam "steamapps\libraryfolders.vdf"
    if (Test-Path $vdf) {
        foreach ($m in [regex]::Matches((Get-Content $vdf -Raw), '"path"\s+"([^"]+)"')) { $libs += $m.Groups[1].Value -replace '\\\\', '\' }
    }
    foreach ($l in $libs) {
        $d = Join-Path $l "steamapps\common\Hello Neighbor"
        if (Test-Path (Join-Path $d "HelloNeighbor\Binaries\Win64")) { return $d }
    }
    return $null
}

# prismlauncher.exe: the installer's default folder, the uninstall registry entries, Program Files.
function Find-Prism {
    $cands = @(Join-Path $env:LOCALAPPDATA "Programs\PrismLauncher\prismlauncher.exe")
    foreach ($key in "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*", "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*") {
        foreach ($e in Get-ItemProperty $key -ErrorAction SilentlyContinue) {
            if ($e.DisplayName -like "Prism Launcher*" -and $e.InstallLocation) { $cands += Join-Path $e.InstallLocation "prismlauncher.exe" }
        }
    }
    $cands += "C:\Program Files\PrismLauncher\prismlauncher.exe"
    foreach ($c in $cands) { if (Test-Path $c) { return $c } }
    return $null
}

# Where Prism keeps its instances: next to the program for a portable install, else %APPDATA%\PrismLauncher.
function Get-PrismData($prismExe) {
    $dir = Split-Path -Parent $prismExe
    if (Test-Path (Join-Path $dir "portable.txt")) { return $dir }
    return Join-Path $env:APPDATA "PrismLauncher"
}

# settings.ini written by install.ps1: one "key=value" per line.
function Read-Settings($file) {
    $s = @{}
    if (Test-Path $file) {
        foreach ($l in Get-Content $file) { if ($l -match '^\s*([^#;=\s][^=]*?)\s*=\s*(.*?)\s*$') { $s[$Matches[1]] = $Matches[2] } }
    }
    return $s
}
