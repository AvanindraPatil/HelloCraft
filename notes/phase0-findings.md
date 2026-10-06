# Phase 0 findings log

Goal: can our own code run inside Hello Neighbor 1 and read the player position?

## Environment facts (2026-10-02)
- Install: `<Steam library>\steamapps\common\Hello Neighbor` (tools/start_mod.ps1 finds it through Steam)
- Steam build ID: 4340169 (last updated 2025-11-10)
- Target exe: `HelloNeighbor\Binaries\Win64\HelloNeighbor-Win64-Shipping.exe` (x64, Shipping, ~53 MB). `HelloNeighbor.exe` in the root is only a launcher stub.
- Engine: UE 4.20 (from `++UE4+Release-4.20` strings in the exe)
- PC RAM: 31 GB
- Official mods live in `HelloNeighbor\Mods\*` as content-only plugins (.uplugin + Content\Paks)
- `.pdb` listed in Manifest_DebugFiles but not on disk

## Attempt log

### 1. UE4SS experimental-latest (v3.0.1-1152-ge3ba1016)
- Installed into `...\Binaries\Win64\`: `dwmapi.dll` (proxy) + `ue4ss\` folder
- UE4SS-settings.ini: GuiConsoleEnabled/Visible and ConsoleEnabled set to 1
- Result: **ATTACHES.** Game launched, UE4SS.log written, Lua mods run.
  - Detected UE 4.20 / Shipping. Found GUObjectArray, GMalloc, FName::ToString, FName ctor, StaticConstructObject_Internal, FUObjectHashTables, GNatives, GameEngineTick.
  - Hash-table self test passed (1950 classes). Engine tick hook registers.
  - Harmless failures: ConsoleManagerSingleton AOB (2 matches), BPModLoaderMod (no Paks config).
  - `GraphicsAPI = dx11` set in settings (default was opengl).

### 2. PosLogger Lua mod (in progress)
- Source: `ue4ss-mods/PosLogger/` (deployed to the game's `ue4ss\Mods\`, enabled in mods.txt)
- Logs pawn location/yaw ~4x/sec to UE4SS.log and `ue4ss\poslog.csv`
- Result so far: **position reads live.** Pawn class `BP_Human_C`, map `/Game/Maps/Start` (menu/start scene; z changed -2447 -> -2445 -> -2462, x/y static as expected there). Coordinates are UE units (cm).
- Bug found: the loop threw when the map changed (`UEHelpers.GetPlayerController` hit an invalid object). Fixed with pcall + `FindFirstOf("PlayerController")`.
- poslog.csv is written to `Binaries\Win64\` (the exe's cwd), not `ue4ss\`.
- **Verified (second run, fixed script):** 220 samples, no errors. Map change `/Game/Maps/Start` -> `/Game/Maps/Act1/Act1_Main` logged with a 2.8s gap and no crash. In Act1 x/y/z/yaw all change as the player moves (yaw -175..175). Pawn class stays `BP_Human_C`.
- Oddity: in Act1 the pawn sat at (-7303, 1347, 195) for ~2s, then jumped to (-7565, -11060, 1574) and moved from there, z decreasing 1574 -> 412. Likely an intro/spawn teleport or cutscene descent; not a logger bug. Worth checking before phase 2 (we need to know which map coords are the playable "world").
- **Phase 0 verdict: PASS.** UE4SS attaches to HN1 (UE 4.20) and our code reads the player position live.

## Uninstall UE4SS
Delete `dwmapi.dll` and the `ue4ss` folder from the Win64 directory above.

## Next
- If attached: UE4SS Lua mod that logs player position each tick to a file
- If not: try a custom DLL proxy; else decide on HN:Code fallback
