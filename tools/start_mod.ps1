# (Re)starts the mod: the bridge (only if it is not running), Minecraft and, once Minecraft is in its world, Hello
# Neighbor.
#   powershell -ExecutionPolicy Bypass -File tools\start_mod.ps1            restart Minecraft + Hello Neighbor
#   ... -HnOnly                                                             restart Hello Neighbor only
#   ... -McOnly                                                             restart Minecraft only (fresh world + kit)
#   ... -HnDir "X:\...\Hello Neighbor"                                      the game's folder, if not found by itself
# Hello Neighbor is found through Steam (its library folders), or HnDir / the HN_DIR environment variable.
# Hello Neighbor sometimes hangs on a white window at start-up. That shows as hn_gfx.log never logging the game's
# first frame; the logs of such a launch are kept (notes\launch-failures\, or logs\ in a release) and the game is
# started again.
#
# Two layouts: in the project, Minecraft is the Gradle dev client (gradlew runClient). In a release (a settings.ini
# written by install.ps1 sits next to the scripts folder) Minecraft is the Prism Launcher instance, everything is
# already installed, and the script keeps running until Hello Neighbor is closed, then closes the rest.
param([switch]$HnOnly, [switch]$McOnly, [string]$HnDir = $env:HN_DIR, [int]$Tries = 3, [int]$FirstFrameTimeoutS = 60, [int]$McTimeoutS = 300)

$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "hn-common.ps1")

$settings = Read-Settings (Join-Path $root "settings.ini")
$release = $settings.Count -gt 0
if (-not $HnDir -and $release) { $HnDir = $settings["HnDir"] }
if (-not $HnDir) { $HnDir = Find-HelloNeighbor }
if (-not $HnDir) { Write-Output "Hello Neighbor not found through Steam: pass -HnDir or set HN_DIR"; exit 2 }
$hnBin = Join-Path $HnDir "HelloNeighbor\Binaries\Win64"
$gfxLog = Join-Path $hnBin "hn_gfx.log"

if ($release) {
    $logs = Join-Path $root "logs"
    $fails = Join-Path $logs "launch-failures"
    $bridgeExe = Join-Path $root "hn_bridge.exe"
    $bridgeOut = Join-Path $logs "bridge.out"; $bridgeErr = Join-Path $logs "bridge.err"
    $instance = $settings["Instance"]
    $mcLog = Join-Path $settings["PrismData"] "instances\$instance\minecraft\logs\latest.log"
    $mcProcess = "*\instances\$instance\*"
    New-Item -ItemType Directory -Force $logs | Out-Null
} else {
    $fails = Join-Path $root "notes\launch-failures"
    $bridgeExe = Join-Path $root "protocol\build\hn_bridge.exe"
    $bridgeOut = Join-Path $root "protocol\build\bridge.out"; $bridgeErr = Join-Path $root "protocol\build\bridge.err"
    $mc = Join-Path $root "fabric-mod"
    $mcLog = Join-Path $mc "run\runClient.out"
    $mcProcess = "*$root*"   # the project's path: not a release's Prism instance, which has a similar name
}

function Stop-AndWait($names) {
    $p = Get-Process $names -ErrorAction SilentlyContinue
    if (-not $p) { return }
    $p | Stop-Process -Force -ErrorAction SilentlyContinue
    $p | ForEach-Object { try { $_.WaitForExit(15000) | Out-Null } catch {} }
}

# This project's Minecraft (and the dev client's Gradle runner); other Java programs stay open.
function Get-OwnJava { Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" | Where-Object { $_.CommandLine -like $mcProcess } }

# Bridge: keep a running one (both games reconnect to it).
if (-not (Get-Process hn_bridge -ErrorAction SilentlyContinue)) {
    Start-Process -FilePath $bridgeExe -WorkingDirectory (Split-Path -Parent $bridgeExe) `
        -RedirectStandardOutput $bridgeOut -RedirectStandardError $bridgeErr -WindowStyle Hidden
    Write-Output "bridge started"
}

if ($release -and -not $HnOnly -and -not $McOnly -and (Get-Process HelloNeighbor-Win64-Shipping -ErrorAction SilentlyContinue)) {
    # A player's own game may have unsaved progress: never close it for them.
    Write-Output "Hello Neighbor is already running. Close it first, then run Play.bat again."
    exit 3
}

if (-not $HnOnly -and -not $McOnly) {
    # A full restart closes Hello Neighbor FIRST: a new Minecraft connected to the old one, still in a level, drew
    # into it, and the new Hello Neighbor started with that leftover frame on screen.
    Stop-AndWait @("HelloNeighbor-Win64-Shipping", "HelloNeighbor")
}

$prismWasRunning = $true   # Prism is only closed at the end if this run started it
if (-not $HnOnly) {
    $own = @(Get-OwnJava)
    foreach ($p in $own) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
    foreach ($p in $own) { try { (Get-Process -Id $p.ProcessId -ErrorAction Stop).WaitForExit(15000) | Out-Null } catch {} }
    $since = Get-Date
    if ($release) {
        $prismWasRunning = [bool](Get-Process prismlauncher -ErrorAction SilentlyContinue)
        Start-Process -FilePath $settings["PrismExe"] -ArgumentList "-l", $instance
    } else {
        Start-Process -FilePath (Join-Path $mc "gradlew.bat") -ArgumentList "runClient" -WorkingDirectory $mc `
            -RedirectStandardOutput $mcLog -RedirectStandardError (Join-Path $mc "run\runClient.err") -WindowStyle Hidden
    }
    Write-Output "Minecraft starting"
    # Hello Neighbor only once Minecraft is in its world: two games loading at once is slow, and is a suspect for the
    # white-window start-ups. (Minecraft makes its world on its own, it does not wait for Hello Neighbor.) The log must
    # be written after this start: Minecraft's log of the last run still says "joined the game".
    $ready = $false
    while (((Get-Date) - $since).TotalSeconds -lt $McTimeoutS) {
        Start-Sleep -Seconds 2
        if ((Test-Path $mcLog) -and (Get-Item $mcLog).LastWriteTime -gt $since -and (Select-String -Path $mcLog -Pattern "joined the game" -Quiet)) { $ready = $true; break }
    }
    if ($ready) { Write-Output ("Minecraft in its world after {0:N0} s" -f ((Get-Date) - $since).TotalSeconds); Start-Sleep -Seconds 3 }
    else { Write-Output "Minecraft not in a world after $McTimeoutS s; starting Hello Neighbor anyway" }
    if ($McOnly) { exit ([int](-not $ready)) }
}

function Install-HnFiles {
    # Hello Neighbor is closed here, so its files are not locked: copy what was rebuilt or edited.
    $copies = @(
        @{ From = Join-Path $root "native\hn_gfx\build\hn_gfx.dll"; To = Join-Path $hnBin "ue4ss\Mods\HnGfx\dlls\main.dll" },
        @{ From = Join-Path $root "ue4ss-mods\HnLink\Scripts\main.lua"; To = Join-Path $hnBin "ue4ss\Mods\HnLink\Scripts\main.lua" }
    )
    foreach ($c in $copies) {
        if ((Test-Path $c.From) -and (-not (Test-Path $c.To) -or (Get-FileHash $c.From).Hash -ne (Get-FileHash $c.To).Hash)) {
            Copy-Item $c.From $c.To -Force
            Write-Output ("deployed " + (Split-Path -Leaf $c.From))
        }
    }
}

$started = $false
for ($try = 1; $try -le $Tries; $try++) {
    Stop-AndWait @("HelloNeighbor-Win64-Shipping", "HelloNeighbor")
    if (-not $release) { Install-HnFiles }
    Start-Sleep -Seconds 3            # let Steam see the game has exited, or it may not launch it again
    $since = Get-Date
    Start-Process "steam://rungameid/521890"
    $ok = $false
    while (((Get-Date) - $since).TotalSeconds -lt $FirstFrameTimeoutS) {
        Start-Sleep -Seconds 2
        if ((Test-Path $gfxLog) -and (Get-Item $gfxLog).LastWriteTime -gt $since -and
            (Select-String -Path $gfxLog -Pattern "game Present: 1 frames" -Quiet)) { $ok = $true; break }
    }
    # A white window can also come with frames: hn_gfx and another Present hook (Steam's overlay) calling each
    # other, cut by hn_gfx, so nothing is ever shown. It logs that within the first frames.
    if ($ok) {
        Start-Sleep -Seconds 4
        if (Select-String -Path $gfxLog -Pattern "Present hook loop cut" -Quiet) { $ok = $false; Write-Output "Hello Neighbor's frames never reach the screen (Present hook loop)" }
    }
    if ($ok) { Write-Output "Hello Neighbor running (try $try)"; $started = $true; break }
    $dir = Join-Path $fails (Get-Date -Format "yyyyMMdd-HHmmss")
    New-Item -ItemType Directory -Force $dir | Out-Null
    foreach ($f in @($gfxLog, (Join-Path $hnBin "ue4ss\UE4SS.log"))) { if (Test-Path $f) { Copy-Item $f $dir } }
    Write-Output "Hello Neighbor showed no frame within $FirstFrameTimeoutS s (try $try); logs kept in $dir"
}
if (-not $started) { Write-Output "Hello Neighbor did not start after $Tries tries"; exit 1 }

if ($release) {
    # Once Hello Neighbor is closed: the flag file tells Minecraft to close itself (a clean exit, so Prism shows no
    # "crashed" window), then the bridge and, if this script started it, Prism go too.
    Write-Output "Playing. Close Hello Neighbor when you are done; everything else closes with it."
    while (Get-Process HelloNeighbor-Win64-Shipping -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 2 }
    Write-Output "Hello Neighbor closed; closing Minecraft"
    $flag = Join-Path $settings["PrismData"] "instances\$instance\minecraft\hnmc-quit.flag"
    Set-Content $flag "quit"
    $until = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $until -and @(Get-OwnJava).Count -gt 0) { Start-Sleep -Seconds 1 }
    foreach ($p in @(Get-OwnJava)) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
    Remove-Item $flag -Force -ErrorAction SilentlyContinue
    Stop-AndWait @("hn_bridge")
    if (-not $prismWasRunning) {
        Start-Sleep -Seconds 2                 # let Prism note the game's exit
        Stop-AndWait @("prismlauncher")
    }
}
exit 0
