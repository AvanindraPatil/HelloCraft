# Developing

This guide is for building the mod from source and changing it. To just play, use a release (see the
[README](../README.md)). Read [ARCHITECTURE.md](ARCHITECTURE.md) first. `<Win64>` below is
`<Steam library>\steamapps\common\Hello Neighbor\HelloNeighbor\Binaries\Win64`.

## Requirements

- Windows 10 or 11
- **Hello Neighbor** on Steam
- [**UE4SS**](https://github.com/UE4SS-RE/RE-UE4SS), the experimental build v3.0.1-1152-ge3ba1016 that Hello Neighbor
  was tested with: the plain 3.0.1 release was not tried. The exact files are in `native/third_party/ue4ss`.
- **JDK 25**: in development Minecraft 26.3 runs from source with Gradle (`gradlew runClient`); the first start
  downloads Minecraft, Fabric and Fabric API
- **Visual Studio 2022** (or the Build Tools) with *Desktop development with C++*, for `hn_gfx` and the bridge
- [Node.js](https://nodejs.org/), only for the Lua tests

## Install from source

Paths are relative to the project folder.

1. **UE4SS**: copy `native\third_party\ue4ss\dwmapi.dll` and the folder `native\third_party\ue4ss\ue4ss` into
   `<Win64>` (its settings already have `EnableHotReloadSystem = 0`: a hot reload while the mod runs freezes the game).
   Or build a release and use its `Install.bat`, which does steps 1-4 and the Prism instance (see Releases below).
2. **Graphics plugin**: run `native\hn_gfx\build.bat`, then copy `native\hn_gfx\build\hn_gfx.dll` to
   `<Win64>\ue4ss\Mods\HnGfx\dlls\main.dll`.
3. **Script mod**: copy the folder `ue4ss-mods\HnLink` to `<Win64>\ue4ss\Mods\HnLink`.
4. **Enable both** in `<Win64>\ue4ss\Mods\mods.txt`, above the `Keybinds` line:
   ```
   HnLink : 1
   HnGfx : 1
   ```
5. **Bridge**: run `protocol\run_bridge.bat` once; it builds `protocol\build\hn_bridge.exe` (and starts it; you can
   close it again).

Then start everything with:

```
powershell -ExecutionPolicy Bypass -File tools\start_mod.ps1
```

It starts the bridge, then Minecraft (hidden; it waits until Minecraft is in its world), then Hello Neighbor through
Steam. It finds the game through Steam; if it does not, add `-HnDir "X:\path\to\Hello Neighbor"` (or set `HN_DIR`).
`-HnOnly` restarts only Hello Neighbor, `-McOnly` only Minecraft (a fresh world and kit). Later builds of the DLL and
changes to `main.lua` are copied into the game by this script while Hello Neighbor is closed.

## Layout

```
ue4ss-mods/HnLink/Scripts/main.lua   the Lua mod (one file; sections marked with ===== banners)
ue4ss-mods/HnLink/tests/             offline tests for main.lua (Node + fengari)
native/hn_gfx/                       the rendering / input DLL (hn_gfx.cpp: Present hook + overlay,
                                     hn_world.cpp: 3D blocks and entities, hn_input.cpp: input routing)
native/third_party/minhook/          MinHook (code hooks for the depth-buffer tracking)
native/third_party/ue4ss/            the UE4SS build the game was tested with (MIT), bundled in releases
protocol/                            shared memory layouts (*.h), the bridge and test tools (tools/), C++ tests
fabric-mod/                          the Minecraft side (src/main: server + shared, src/client: client)
tools/start_mod.ps1                  start / restart everything; tools/vcvars.bat finds Visual Studio
tools/build_release.ps1              builds the release zip; tools/release/ holds what goes into it
                                     (Install.bat, Play.bat, Uninstall.bat, install.ps1, the Prism instance template)
notes/                               development diary: what was tried, measured and why
```

## Build and run

| What | How |
|---|---|
| Everything, for playing | `tools\start_mod.ps1` (see README). `-HnOnly` / `-McOnly` restart one side. It copies a changed `hn_gfx.dll` and `main.lua` into the game while Hello Neighbor is closed. |
| hn_gfx | `native\hn_gfx\build.bat` → `build\hn_gfx.dll`. The game locks `<Win64>\ue4ss\Mods\HnGfx\dlls\main.dll` while it runs; restart Hello Neighbor to load a new one. |
| Bridge | `protocol\run_bridge.bat` builds and starts `protocol\build\hn_bridge.exe`. |
| Minecraft mod | `cd fabric-mod && gradlew compileJava compileClientJava`; the start script runs `gradlew runClient`. |
| main.lua | No build step. Restart Hello Neighbor (`start_mod.ps1 -HnOnly`); UE4SS hot reload is off on purpose. |

All native builds find MSVC with `tools\vcvars.bat` (vswhere); set `VCVARS64` to a `vcvars64.bat` to override.

## Releases

`powershell -ExecutionPolicy Bypass -File tools\build_release.ps1` runs the Lua tests (if `npm install` was done),
builds `hn_gfx.dll`, `hn_bridge.exe` (into `release\obj`, so a running bridge does not matter) and the mod jar, and
packs `release\HelloCraft-<version>.zip` (the version is `version` in `fabric-mod/gradle.properties`).
Nothing in the project changes; `release\` can be deleted any time.

The zip is what a player gets (see the README): `Install.bat` puts UE4SS and the two mods into Hello Neighbor (keeping
an existing UE4SS install as `*.before-hnmc` if it is a different build, and only adding our two lines to `mods.txt`)
and a Prism Launcher instance (`HelloCraft`: Minecraft 26.3, Fabric, the mod, Fabric API, options) into
Prism's `instances` folder; it writes `settings.ini` with the paths it found and `installed.txt` with every file and
folder it added. `Uninstall.bat` removes exactly those (so a UE4SS the installer brought goes too), puts the
`*.before-hnmc` files back, and deletes the files HnLink writes next to the game. The bundled UE4SS has its console
window off (`ConsoleEnabled = 0`) and BPModLoaderMod off (it is for blueprint mods and logs an error on this game). `Play.bat` runs the same
`scripts\start_mod.ps1` as in development: when `settings.ini` exists the script is in *release mode*: Minecraft is
started through Prism (`prismlauncher.exe -l <instance>`), nothing is copied, and it waits for Hello Neighbor to be
closed. Then it creates `hnmc-quit.flag` in the instance's game folder and the Minecraft mod closes itself cleanly
(in release mode, `-Dhnmc.release=true` in the instance, it also never shows its window, and closes on its own 30 s
after Hello Neighbor's heartbeat is gone if Play.bat's window was closed); the script stops the bridge and, if it
started Prism, Prism. A re-install updates the instance's Java arguments and keeps the rest. The player name is the account's, unless `playerName` is set in
`config\hnmc.properties` (development: `-Dhnmc.name=Nicky` in `fabric-mod/build.gradle`).

To test a release without touching a real setup, run `tools\release\install.ps1` with `-HnDir`, `-PrismExe` and
`-PrismData` pointing at scratch folders.

## Tests

| What | How |
|---|---|
| Lua | `cd ue4ss-mods\HnLink\tests`, `npm install` once, then `npm test`. Compiles the whole script with a real Lua compiler (catches Lua's 200-locals limit), then runs pieces of it against mocks. |
| Protocol | `protocol\build_tests.bat`: layout, coordinates, seqlock and ring races, the overlay triple buffer. |
| Java | `protocol\build_java_deps.bat` (builds `layout.txt` and `fake_host.exe`), then `cd fabric-mod && gradlew test`: the Java layout must match the C++ one byte for byte, plus a two-process shared memory test. |
| hn_gfx | `native\hn_gfx\tests\run.bat`: loads the DLL into a small D3D11 window and checks the hooks and the overlay. |

Run the Lua tests before every change to `main.lua` is put into the game: a script that fails to load means no mod at
all, and the only symptom in the game is that nothing happens.

## Logs and debugging

| Log | Where |
|---|---|
| HnLink (Lua) and UE4SS | `<Win64>\ue4ss\UE4SS.log` (lines `[HnLink]`) |
| hn_gfx | `<Win64>\hn_gfx.log`: hooks, frame gaps, its own cost per frame every 20 s |
| Minecraft | `fabric-mod\run\runClient.out` and `fabric-mod\run\logs\latest.log` (lines `(hnmc)`) |
| Bridge | `protocol\build\bridge.out` when started by the start script |

- **Status lines** every 2 s (positions, scan counters, frame costs): set `st.VERBOSE = true` in `main.lua`. On the
  Minecraft side: add `vmArg "-Dhnmc.verbose=true"` to `loom { runs { client { ... } } }` in `fabric-mod/build.gradle`.
- **Always logged**: slow frames (with the slowest query and Lua memory), refused pawn moves, failures, state changes,
  hand-offs, teleports, deaths.
- **F9** in the game logs every collision component in the cells in front of the player, with the shape Minecraft has:
  the first thing to look at when something blocks you, or something you should hit lets you through.
- **Crash guard**: risky calls run through `risky(name, fn)`, which writes `<Win64>\hnlink_trace_breadcrumb.txt` before
  the call and removes it after. If the game dies inside, the next start finds the crumb, adds the name to
  `<Win64>\hnlink_trace_broken.txt` and skips that step from then on. Delete the line to try it again.

## Adding things

- **A command or event**: add the number to `CmdType` / `EvType` in `protocol/hn_protocol.h`, print it in
  `protocol/tools/layout_dump.cpp`, mirror it in `fabric-mod/.../link/Layout.java` (constant and the `layout` map that
  `LayoutTest` compares). The bridge passes any type through. Lua sends with `queueCmd(type, a, b, c, d)` and handles
  events in `readReply`; Java pushes events with `link.pushEvent(...)` on the client thread and handles commands in
  `McLink`.
- **Per-frame Lua work**: call it from `tick()` inside `pcall`, log a failure once, and keep it cheap — HnLink runs
  on Hello Neighbor's game thread every frame while Minecraft drives (`st.profAdd` times a section).
- **New state in Lua**: put it in an existing table (`st`, `ia`, `interact`, `voxAhead`, `K` for constants). The main
  chunk is close to Lua's 200-locals limit (`count_locals.js` in the tests prints the count).
- **A Minecraft feature**: server-side code in `src/main` (it runs in the integrated server), client code in
  `src/client`. Register it from `HnMc.onInitialize` / `HnMcClient`.

## Pitfalls (each of these cost a debugging session)

UE4SS and Hello Neighbor:
- `IsValid()` is unreliable for level objects; use the `exists()` helper. `FindFirstOf` / `FindAllOf` by class name can
  return unrelated objects after a level load: find the player through
  `GameEngine_0.GameInstance.LocalPlayers[1].PlayerController` and `IsA` (see `playerController()`).
- Functions with an `FHitResult` out parameter (line traces, sweeps) crash the game from Lua. Use yes/no overlap queries.
- After a level change every old actor is dead and touching one is a native crash: `forgetWorld` drops every
  reference; new caches must be cleared there too.
- Lua coroutines cannot call engine functions in UE4SS 3.0.1 ("lua state has no instance").
- UE4SS hot reload (any key) deadlocks the game when it lands inside the mod's per-frame tick.
- Garbage matters: a new table per query and a string key per cell made ~5 MB/s of garbage and 50-250 ms collector
  pauses. Reuse tables, use number keys.
- Out parameters (arrays, vectors) either fill the table passed in or come back as return values; handle both
  (`arrayOut`, `ia.bounds`).
- The Win64 folder is the working directory for `io.open`.

Minecraft:
- A proxy block's shape depends on its position, but Minecraft asks some questions (solid? pathfindable?) with the shape
  at `BlockPos.ZERO`, which is empty. Override them (`isPathfindable`, `forceSolidOn`, `canBeReplaced`).
- Server work on the server thread (`server.execute`), client work on the render thread; `McLink` and `OverlayLink`
  are single-threaded on the client.
- Do not change blocks from inside a `setBlock` callback (`LevelMixin`): schedule it.
- Hello Neighbor's geometry exists in Minecraft only where it has been scanned: anything that teleports the Minecraft
  player can land it in an empty void (see the far-teleport hand-off in ARCHITECTURE.md).
